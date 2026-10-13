package raider.cli.lineage

import zio.json.EncoderOps
import zio.test.*

import raider.core.{JobId, RootId, SessionId}
import raider.core.result.ExitCodes
import raider.runtime.budget.MicroUsd
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{
  EventEnvelope,
  EventPayload,
  ToolOutcome,
  UsageSnapshot
}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.reduce.LineageReducer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption
}
import java.nio.file.attribute.FileTime

/** LIN-08B acceptance for the lineage-tree catalog (packet L43, ADR-027 §5,
  * docs/lineage.md §5 "tree catalog"):
  *   - LIN8-B-01: two runs under `work-*` directories — both cataloged, each
  *     hash EQUALS the canonical [[GraphHash]] of the graph reduced from the
  *     same envelopes, ordering is mtime desc (path tie-break), and a repeat
  *     scan is identical;
  *   - LIN8-B-02: a damaged run (torn final line) is a PARTIAL entry with the
  *     tail reported — data, never an exception; hard damage (malformed line
  *     before the tail) is a TYPED scan refusal, never a silent skip;
  *   - LIN8-B-03: an empty catalog is `Right(Vector.empty)` with an EMPTY table
  *     and honest exit semantics (0 — nothing to list is not an error); a
  *     missing runsRoot is the typed `NotFound` refusal (nonzero).
  *
  * Fixture note: `LineageCorpus` lives in raiderRuntime's TEST scope and
  * raiderCli has no `test->test` dependency on it (build.sbt is frozen for this
  * lane), so the fixture below REPLICATES `LineageCorpus.writeJsonl` semantics
  * test-locally: the same frozen `EventCodecs` (schemaVersion 1, one envelope
  * per line, trailing newline, atomic tmp+move) and the same corrupted-tail
  * trick (a strict prefix of a real line — never valid JSON).
  */
object LineageCatalogSpec extends ZIOSpecDefault:

  // ----- run fixture (LineageCorpus.writeJsonl semantics, test-local) --------

  /** Minimal REAL campaign for `seed`: the root job's start transition, one
    * model round, one tool call, exactly one Terminal. Reduces (strict) to a
    * 2-node graph — validated in [[expectedHash]] before any test relies on it.
    */
  private def campaign(seed: Int): Vector[EventEnvelope] =
    val rootId = RootId(s"root-$seed")
    val sessionId = SessionId(s"sess-$seed")
    val job = JobId(s"job-$seed")
    val payloads = Vector(
      EventPayload.JobStatusChanged(
        JobStatus.Queued,
        JobStatus.Running,
        None
      ), // the start transition
      EventPayload.ModelCallStarted(1, "mock-model-a", Some(12)),
      EventPayload.ModelCallFinished(
        1,
        250L + seed,
        UsageSnapshot(MicroUsd(60_000), MicroUsd(45_000), unknown = false)
      ),
      EventPayload.ToolStarted("call-1", "bash", "ls -la"),
      EventPayload.ToolFinished("call-1", "bash", ToolOutcome.Succeeded),
      EventPayload.Terminal(JobStatus.Succeeded)
    )
    payloads.zipWithIndex.map { (payload, i) =>
      EventEnvelope(
        schemaVersion = 1,
        seq = (i + 1).toLong,
        jobId = job,
        rootId = rootId,
        sessionId = sessionId,
        atMs = 1760000000000L + seed * 1000L + i * 250L,
        payload = payload
      )
    }

  /** Canonical JSONL lines of the fixture campaign (frozen wire codecs). */
  private def jsonlLines(envelopes: Vector[EventEnvelope]): Vector[String] =
    envelopes.map(_.toJson)

  /** Atomic JSONL write (tmp + ATOMIC_MOVE with a plain-move fallback) — the
    * same discipline as `LineageCorpus.writeJsonl`.
    */
  private def writeJsonl(lines: Vector[String], path: Path): Path =
    Files.createDirectories(Option(path.getParent).getOrElse(Path.of(".")))
    val tmp = Files.createTempFile(
      path.getParent,
      s".${path.getFileName}",
      ".tmp"
    )
    Files.write(tmp, lines.mkString("", "\n", "\n").getBytes(UTF_8))
    try
      Files.move(
        tmp,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp)
    path

  /** Writes `runsRoot/<name>/events.jsonl` and pins its FS mtime (the catalog's
    * sort key / scannedAtMs source must be the file's mtime, not the clock).
    */
  private def writeRun(
      runsRoot: Path,
      name: String,
      seed: Int,
      mtimeMs: Long,
      withRunJson: Boolean = false
  ): Path =
    val dir = runsRoot.resolve(name)
    writeJsonl(jsonlLines(campaign(seed)), dir.resolve("events.jsonl"))
    if withRunJson then
      Files.writeString(dir.resolve("run.json"), "{\"schemaVersion\":1}", UTF_8)
    Files.setLastModifiedTime(
      dir.resolve("events.jsonl"),
      FileTime.fromMillis(mtimeMs)
    )
    dir

  /** Damaged run: canonical lines plus ONE trailing line that is a strict
    * PREFIX of the last real line (the Terminal record) — unparseable, so the
    * reader reports a truncated tail and the scan must survive with a partial
    * entry (LIN8-B-02).
    */
  private def writeTornRun(
      runsRoot: Path,
      name: String,
      seed: Int,
      mtimeMs: Long
  ): Path =
    val lines = jsonlLines(campaign(seed))
    val dir = runsRoot.resolve(name)
    writeJsonl(
      lines :+ lines.last.take(lines.last.length / 2),
      dir.resolve("events.jsonl")
    )
    Files.setLastModifiedTime(
      dir.resolve("events.jsonl"),
      FileTime.fromMillis(mtimeMs)
    )
    dir

  /** Hard-damaged run: an undecodable line BEFORE the tail (line 3) — reader
    * damage, so the scan refuses TYPED instead of cataloging the run.
    */
  private def writeBrokenRun(
      runsRoot: Path,
      name: String,
      seed: Int,
      mtimeMs: Long
  ): Path =
    val lines = jsonlLines(campaign(seed))
    val dir = runsRoot.resolve(name)
    writeJsonl(
      lines.patch(2, Vector("{{not-json"), 0),
      dir.resolve("events.jsonl")
    )
    Files.setLastModifiedTime(
      dir.resolve("events.jsonl"),
      FileTime.fromMillis(mtimeMs)
    )
    dir

  /** The canonical hash the catalog entry MUST carry for `seed` (LIN8-B-01). */
  private def expectedHash(seed: Int): String =
    LineageReducer.reduce(campaign(seed)) match
      case Right(g) => GraphHash(g)
      case Left(err) =>
        throw new IllegalStateException(s"seed $seed does not reduce: $err")

  private def tempRuns(): Path =
    val root = Files.createTempDirectory("raider-lineage-catalog")
    Files.createDirectories(root.resolve("runs"))
    root.resolve("runs")

  // ----- acceptance -----------------------------------------------------------

  def spec = suite("LineageCatalog")(
    suite("LIN8-B-01 catalog of runs")(
      test(
        "two runs — both entries, hash == GraphHash, mtime desc, repeat scan identical"
      ) {
        val runsRoot = tempRuns()
        writeRun(runsRoot, "work-aaaa", seed = 1, mtimeMs = 1000L)
        writeRun(
          runsRoot,
          "work-bbbb",
          seed = 2,
          mtimeMs = 2000L,
          withRunJson = true
        )
        val scan1 = LineageCatalog.scan(runsRoot)
        val scan2 = LineageCatalog.scan(runsRoot)
        scan1 match
          case Right(entries) =>
            assertTrue(
              entries.length == 2,
              entries == scan2.toOption.get, // repeat scan is identical
              // mtime desc: work-bbbb (2000) first, work-aaaa (1000) second
              entries(0).path.getFileName.toString == "work-bbbb",
              entries(1).path.getFileName.toString == "work-aaaa",
              entries(0).rootId == "root-2",
              entries(0).sessionId == Some("sess-2"),
              entries(0).nodeCount == 2,
              entries(0).hash == expectedHash(2),
              entries(0).complete,
              entries(0).tailReported.isEmpty,
              entries(0).scannedAtMs == 2000L, // FS mtime, not the clock
              entries(1).rootId == "root-1",
              entries(1).hash == expectedHash(1),
              entries(1).scannedAtMs == 1000L,
              Files.exists(entries(0).path.resolve("events.jsonl"))
            )
          case other =>
            throw new IllegalStateException(s"expected Right, got $other")
      },
      test("equal mtime — deterministic path tie-break") {
        val runsRoot = tempRuns()
        writeRun(runsRoot, "work-zzz", seed = 3, mtimeMs = 5000L)
        writeRun(runsRoot, "work-aaa", seed = 4, mtimeMs = 5000L)
        LineageCatalog.scan(runsRoot) match
          case Right(entries) =>
            assertTrue(
              entries.map(_.path.getFileName.toString) ==
                Vector("work-aaa", "work-zzz")
            )
          case other =>
            throw new IllegalStateException(s"expected Right, got $other")
      }
    ),
    suite("LIN8-B-02 damaged runs are data, not exceptions")(
      test(
        "torn final line — partial entry with the tail reported, scan stays Right"
      ) {
        val runsRoot = tempRuns()
        writeRun(runsRoot, "work-good", seed = 1, mtimeMs = 1000L)
        writeTornRun(runsRoot, "work-torn", seed = 2, mtimeMs = 2000L)
        LineageCatalog.scan(runsRoot) match
          case Right(entries) =>
            val torn = entries.find(_.path.getFileName.toString == "work-torn")
            val good = entries.find(_.path.getFileName.toString == "work-good")
            assertTrue(
              entries.length == 2,
              torn.isDefined && good.isDefined,
              // newest first even though it is the damaged one
              entries(0).path.getFileName.toString == "work-torn",
              torn.exists(e => !e.complete),
              torn.flatMap(_.tailReported).isDefined,
              torn.flatMap(_.tailReported).exists(_.lineNo == 7),
              // the torn record cost the campaign its Terminal line; the
              // completed nodes stay honestly cataloged
              torn.exists(_.nodeCount == 2),
              torn.exists(_.rootId == "root-2"),
              torn.exists(_.hash == expectedHash(2)),
              good.exists(e => e.complete && e.tailReported.isEmpty)
            )
          case other =>
            throw new IllegalStateException(s"expected Right, got $other")
      },
      test(
        "malformed line before the tail — typed RunUnreadable refusal, exit != 0"
      ) {
        val runsRoot = tempRuns()
        writeBrokenRun(runsRoot, "work-broken", seed = 1, mtimeMs = 1000L)
        val result = LineageCatalog.scan(runsRoot)
        val refused = result.fold(Some(_), _ => None)
        assertTrue(
          refused.exists(_.isInstanceOf[CatalogError.RunUnreadable]),
          refused.exists {
            case CatalogError.RunUnreadable(_, detail) =>
              detail.contains("MalformedLine")
            case _ => false
          },
          LineageCatalog.exitCode(result) == ExitCodes.TaskRejected
        )
      }
    ),
    suite("LIN8-B-03 empty catalog and honest exits")(
      test("empty runs root — Right(empty), empty table, exit 0") {
        val runsRoot = tempRuns()
        val result = LineageCatalog.scan(runsRoot)
        assertTrue(
          result == Right(Vector.empty),
          LineageCatalog.formatTable(result.toOption.get) == Vector.empty,
          LineageCatalog.exitCode(result) == ExitCodes.Success
        )
      },
      test("missing runsRoot — typed NotFound, nonzero exit") {
        val missing =
          Files.createTempDirectory("raider-lineage-catalog").resolve("nope")
        val result = LineageCatalog.scan(missing)
        assertTrue(
          result == Left(CatalogError.NotFound(missing)),
          LineageCatalog.exitCode(result) == ExitCodes.ConfigInvalid
        )
      },
      test("runsRoot is a file — typed NotADirectory") {
        val file = Files
          .createTempDirectory("raider-lineage-catalog")
          .resolve("a-file")
        Files.writeString(file, "not a directory", UTF_8)
        val result = LineageCatalog.scan(file)
        assertTrue(
          result == Left(CatalogError.NotADirectory(file)),
          LineageCatalog.exitCode(result) == ExitCodes.ConfigInvalid
        )
      },
      test(
        "non-run entries and a work-* dir without events.jsonl are skipped, not cataloged"
      ) {
        val runsRoot = tempRuns()
        Files.writeString(runsRoot.resolve("junk.txt"), "not a run", UTF_8)
        Files.createDirectories(runsRoot.resolve("not-a-run"))
        Files.createDirectories(runsRoot.resolve("work-empty"))
        val result = LineageCatalog.scan(runsRoot)
        assertTrue(
          result == Right(Vector.empty),
          LineageCatalog.formatTable(result.toOption.get) == Vector.empty
        )
      }
    ),
    test(
      "formatTable — aligned rows, honest dashes, same format for every run"
    ) {
      val runsRoot = tempRuns()
      writeRun(runsRoot, "work-aaa", seed = 1, mtimeMs = 1000L)
      writeTornRun(runsRoot, "work-bbb", seed = 2, mtimeMs = 2000L)
      val entries = LineageCatalog.scan(runsRoot).toOption.get
      val table = LineageCatalog.formatTable(entries)
      val completeRow = table.find(_.contains("work-aaa")).get
      val partialRow = table.find(_.contains("work-bbb")).get
      assertTrue(
        table.length == entries.length + 1, // header + one line per entry
        table.head.contains("PATH"),
        table.head.contains("HASH"),
        table.head.contains("STATUS"),
        // uniform column alignment: every rendered line has the same length
        table.map(_.length).distinct.length == 1,
        // complete run: no tail — the honest dash, never an invented value
        completeRow.contains("complete"),
        completeRow.contains("\u2014"),
        // partial run: the tail is REPORTED (line 7 = the torn Terminal line)
        partialRow.contains("partial"),
        partialRow.contains("line 7 (")
      )
    }
  )

end LineageCatalogSpec
