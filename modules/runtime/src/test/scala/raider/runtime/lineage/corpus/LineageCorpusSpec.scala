package raider.runtime.lineage.corpus

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import raider.core.JobId
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{EventEnvelope, EventPayload, ToolOutcome}
import zio.json.*
import zio.test.*

/** LIN-05A acceptance tests (zio-test; temp files ONLY under
  * modules/runtime/target):
  *   - LIN5-A-01: regenerating the corpus is byte-identical (hash equal).
  *   - LIN5-A-02: parsing the JSONL back through the frozen `EventCodecs`
  *     restores the campaign exactly, and the corpus honors the seam invariants
  *     (per-job seq 1..n without gaps, one Terminal per job last, all 13 frozen
  *     payload cases, all 4 ToolOutcomes, 2 delegations).
  *   - LIN5-A-03: the adversarial variants are broken in exactly the documented
  *     damage type (truncated tail / per-job seq gap / wrong schemaVersion).
  */
object LineageCorpusSpec extends ZIOSpecDefault:

  private val seed = 1
  private val corpus = LineageCorpus.campaign(seed)

  /** sbt test JVMs run with cwd = build root; every file this suite creates
    * stays inside the runtime target dir (never a source path).
    */
  private def specDir(): Path =
    val dir = Path.of("modules", "runtime", "target", "lineage-corpus-spec")
    Files.createDirectories(dir)
    dir

  private def sha256Hex(path: Path): String =
    val digest =
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    digest.map(b => f"${b & 0xff}%02x").mkString

  private val frozenCaseNames = Set(
    "JobStatusChanged",
    "Waiting",
    "ModelCallStarted",
    "ModelCallFinished",
    "ToolStarted",
    "ToolProgress",
    "ToolFinished",
    "RetryScheduled",
    "BudgetChanged",
    "Delegated",
    "Diagnostics",
    "DiagnosticsCoalesced",
    "Terminal"
  )

  def spec = suite("LineageCorpus")(
    test("LIN5-A-01: repeated generation is byte-identical (hash equal)") {
      val a = specDir().resolve("campaign-a.jsonl")
      val b = specDir().resolve("campaign-b.jsonl")
      LineageCorpus.writeJsonl(corpus, a)
      LineageCorpus.writeJsonl(LineageCorpus.campaign(seed), b)
      assertTrue(
        LineageCorpus.jsonlLines(corpus) == LineageCorpus
          .jsonlLines(LineageCorpus.campaign(seed)),
        sha256Hex(a) == sha256Hex(b)
      )
    },
    test("LIN5-A-01: the seed actually varies the bytes") {
      val c1 = specDir().resolve("campaign-seed-1.jsonl")
      val c2 = specDir().resolve("campaign-seed-2.jsonl")
      LineageCorpus.writeJsonl(LineageCorpus.campaign(1), c1)
      LineageCorpus.writeJsonl(LineageCorpus.campaign(2), c2)
      assertTrue(sha256Hex(c1) != sha256Hex(c2))
    },
    test("LIN5-A-02: parse back via codecs restores the campaign") {
      val fromLines = LineageCorpus.parseJsonl(LineageCorpus.jsonlLines(corpus))
      assertTrue(fromLines == Right(corpus))
    },
    test("LIN5-A-02: disk roundtrip — writeJsonl then parse from file") {
      val path = specDir().resolve("roundtrip.jsonl")
      LineageCorpus.writeJsonl(corpus, path)
      val lines =
        Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
      assertTrue(LineageCorpus.parseJsonl(lines) == Right(corpus))
    },
    test("LIN5-A-02: campaign structure — 1 root + 2 delegated children") {
      val root = JobId("job-1-root")
      val child1 = JobId("job-1-child-1")
      val child2 = JobId("job-1-child-2")
      val delegated = corpus.flatMap(_.payload match
        case EventPayload.Delegated(child, _) => Some(child)
        case _                                => None)
      val jobIds = corpus.map(_.jobId).toSet
      assertTrue(
        jobIds == Set(root, child1, child2),
        delegated == Vector(child1, child2)
      )
    },
    test("LIN5-A-02: per-job seq is monotonic 1..n without gaps") {
      val byJob = corpus.groupBy(_.jobId)
      assertTrue(
        byJob.values.forall { envs =>
          envs.map(_.seq) == (1L to envs.size.toLong).toVector
        }
      )
    },
    test("LIN5-A-02: exactly one Terminal per job, and it is last") {
      val byJob = corpus.groupBy(_.jobId)
      assertTrue(
        byJob.nonEmpty,
        byJob.values.forall { envs =>
          val terminalCount =
            envs.count(e => e.payload.isInstanceOf[EventPayload.Terminal])
          terminalCount == 1 && envs.last.payload
            .isInstanceOf[EventPayload.Terminal]
        }
      )
    },
    test(
      "LIN5-A-02: all 13 frozen payload cases, nothing else (no phase events)"
    ) {
      val names = corpus.map(_.payload.productPrefix).toSet
      assertTrue(names == frozenCaseNames)
    },
    test(
      "LIN5-A-02: all 4 ToolOutcomes covered (Succeeded/Failed/Cancelled/Timeout)"
    ) {
      val outcomes = corpus.flatMap(_.payload match
        case EventPayload.ToolFinished(_, _, outcome) => Some(outcome)
        case _                                        => None)
      val names = outcomes.map {
        case ToolOutcome.Failed(_) => "Failed"
        case other                 => other.productPrefix
      }.toSet
      assertTrue(
        names == Set("Succeeded", "Failed", "Cancelled", "Timeout"),
        outcomes.nonEmpty
      )
    },
    test(
      "LIN5-A-02: schemaVersion 1 and shared root identity on every envelope"
    ) {
      assertTrue(
        corpus.nonEmpty,
        corpus.forall(_.schemaVersion == 1),
        corpus.forall(_.rootId.value == "root-1")
      )
    },
    test(
      "LIN5-A-03: corruptedTail — damage is exactly an unparseable truncated last line"
    ) {
      val lines = LineageCorpus.jsonlLines(corpus)
      val broken = LineageCorpus.corruptedTail(corpus)
      val parsedAll = LineageCorpus.parseJsonl(broken)
      assertTrue(
        broken.size == lines.size + 1,
        broken.dropRight(1) == lines,
        parsedAll.isLeft,
        // the failure is pinned to the corrupt tail line
        parsedAll.left.exists(_.startsWith(s"line ${broken.size}:")),
        // damage type: a strict prefix of the last real line (truncated JSON)
        broken.last == lines.last.take(lines.last.length / 2),
        broken.last.fromJson[EventEnvelope].isLeft,
        // everything before the tail is intact and parses back to the corpus
        LineageCorpus.parseJsonl(broken.dropRight(1)) == Right(corpus)
      )
    },
    test(
      "LIN5-A-03: withSeqGap — exactly one dropped per-job seq, decodable lines"
    ) {
      val gapLines = LineageCorpus.withSeqGap(corpus)
      val parsed = LineageCorpus.parseJsonl(gapLines)
      val envs = parsed.toOption.getOrElse(Vector.empty)
      val gapped = envs.groupBy(_.jobId).filter { case (_, es) =>
        es.map(_.seq) != (1L to es.size.toLong).toVector
      }
      assertTrue(
        // the lines still decode — the damage is the seq discipline, not the JSON
        parsed.isRight,
        envs.size == corpus.size - 1,
        // exactly one job (the first-emitted job) loses one seq; the others stay contiguous
        gapped.keySet == Set(JobId("job-1-root")),
        gapped.values.forall { es =>
          val s = es.map(_.seq)
          // gap definition: no duplicates, all seqs within 1..n+1, exactly one missing
          s.size == s.distinct.size &&
          s.forall(seq => seq >= 1L && seq <= es.size.toLong + 1L) &&
          (1L to (es.size.toLong + 1L)).toVector.diff(s.sorted).size == 1
        }
      )
    },
    test(
      "LIN5-A-03: wrongSchemaVersion — decodable but schemaVersion 999 != 1"
    ) {
      val wrongLines = LineageCorpus.wrongSchemaVersion(corpus)
      val parsed = LineageCorpus.parseJsonl(wrongLines)
      val envs = parsed.toOption.getOrElse(Vector.empty)
      assertTrue(
        // pinned fact: the zio-json codec itself does NOT validate schemaVersion,
        // so a downstream schema gate must reject — the corpus documents that gap
        parsed.isRight,
        envs.map(_.schemaVersion).toSet == Set(
          LineageCorpus.WrongSchemaVersion
        ),
        envs == corpus.map(
          _.copy(schemaVersion = LineageCorpus.WrongSchemaVersion)
        )
      )
    },
    test(
      "writeJsonl: atomic publish — parents created, no tmp litter, overwrite stable"
    ) {
      val dir = specDir().resolve("atomic").resolve("nested")
      val path = dir.resolve("events.jsonl")
      val reference = specDir().resolve("atomic-reference.jsonl")
      LineageCorpus.writeJsonl(corpus, reference)
      LineageCorpus.writeJsonl(corpus, path)
      val listing = dir.toFile.listFiles().map(_.getName).toVector.sorted
      val first = sha256Hex(path)
      LineageCorpus.writeJsonl(corpus, path)
      val content = Files.readAllBytes(path)
      assertTrue(
        Files.exists(path),
        listing == Vector("events.jsonl"),
        content.nonEmpty,
        content.last == '\n'.toByte,
        sha256Hex(path) == sha256Hex(reference),
        first == sha256Hex(path)
      )
    },
    test("writeJsonl: empty corpus writes an empty JSONL file") {
      val path = specDir().resolve("empty.jsonl")
      LineageCorpus.writeJsonl(Vector.empty, path)
      val lines =
        Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
      assertTrue(
        Files.exists(path),
        Files.readAllBytes(path).isEmpty,
        LineageCorpus.parseJsonl(lines) == Right(Vector.empty)
      )
    }
  )
