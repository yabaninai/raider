package raider.runtime.lineage.io

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.*
import raider.runtime.events.EventCodecs.given
import raider.runtime.jobs.JobStatus
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** LIN-01B acceptance tests (ADR-027): [[LineageEventReader]] over JSONL files.
  * Fixtures are written through the frozen ADR-024 codecs ([[EventCodecs]])
  * into a per-suite temp directory — no shared or repo-local files, no live
  * services.
  */
object LineageEventReaderSpec extends ZIOSpecDefault:

  private val jobId = JobId("job-1")
  private val job2Id = JobId("job-2")
  private val rootId = RootId("root-1")
  private val sessionId = SessionId("sess-1")

  /** One instance per frozen EventPayload case — exactly 13. */
  private val payloads: List[EventPayload] = List(
    EventPayload.JobStatusChanged(
      JobStatus.Running,
      JobStatus.Waiting,
      Some(WaitReason.Slot)
    ),
    EventPayload.Waiting(WaitReason.Approval),
    EventPayload.ModelCallStarted(1, "model-a", Some(12)),
    EventPayload.ModelCallFinished(
      1,
      1500L,
      UsageSnapshot(MicroUsd(1000L), MicroUsd(900L), unknown = true)
    ),
    EventPayload.ToolStarted("call-1", "bash", "ls -la"),
    EventPayload.ToolProgress(
      "call-1",
      "bash",
      LineTail(Vector("line-1", "line-2"), 3, 42L)
    ),
    EventPayload.ToolFinished("call-1", "bash", ToolOutcome.Failed("exit 1")),
    EventPayload.RetryScheduled(2, 5000L, "rate limited"),
    EventPayload.BudgetChanged(MicroUsd(1000000L), MicroUsd(250000L), 3L),
    EventPayload.Delegated(JobId("job-child"), "worker-2"),
    EventPayload.Diagnostics("diagnostic text"),
    EventPayload.DiagnosticsCoalesced(17L),
    EventPayload.Terminal(JobStatus.Succeeded)
  )

  private def env(
      seq: Long,
      p: EventPayload,
      id: JobId = jobId
  ): EventEnvelope =
    EventEnvelope(1, seq, id, rootId, sessionId, 1760000000000L, p)

  // ------------------------------------------------------------------
  // Fixture helpers: files live only in a temp directory, cleaned on exit
  // ------------------------------------------------------------------

  private val tempDir: Path =
    val dir = Files.createTempDirectory("lineage-reader-spec")
    Runtime.getRuntime.addShutdownHook(
      new Thread(() => deleteRecursively(dir))
    )
    dir

  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p) then
      val children = Files.newDirectoryStream(p)
      try children.forEach((c: Path) => deleteRecursively(c))
      finally children.close()
    Files.deleteIfExists(p)
    ()

  private def fixture(name: String, content: String): Path =
    val path = tempDir.resolve(name)
    Files.writeString(path, content, StandardCharsets.UTF_8)
    path

  /** JSONL wire form: one envelope per line, encoded with the frozen codecs. */
  private def jsonl(
      envelopes: EventEnvelope*
  )(trailingNewline: Boolean = true): String =
    val body = envelopes.map(_.toJson).mkString("\n")
    if body.isEmpty then ""
    else if trailingNewline then body + "\n"
    else body

  /** Simulates a crash mid-append: keeps only a prefix of the encoded line. */
  private def torn(envelope: EventEnvelope, dropChars: Int): String =
    envelope.toJson.dropRight(dropChars)

  def spec = suite("LineageEventReader (LIN-01B)")(
    // --------------------------------------------------------------
    // LIN1-B-01: roundtrip (write via codecs pipeline -> read, identical)
    // --------------------------------------------------------------
    test(
      "LIN1-B-01 roundtrip: all 13 payload cases, one job, trailing newline"
    ) {
      val envelopes = payloads.zipWithIndex.map((p, i) => env(i + 1L, p))
      val path = fixture("roundtrip.jsonl", jsonl(envelopes*)())
      assertTrue(
        LineageEventReader.read(path) ==
          Right(ParsedEvents(envelopes.toVector, None))
      )
    },
    test("LIN1-B-01 roundtrip: interleaved jobs, no trailing newline") {
      val envelopes = Vector(
        env(1L, payloads(0), jobId),
        env(1L, payloads(1), job2Id),
        env(2L, payloads(2), jobId),
        env(2L, payloads(3), job2Id)
      )
      val path = fixture("roundtrip-no-nl.jsonl", jsonl(envelopes*)(false))
      assertTrue(
        LineageEventReader.read(path) == Right(ParsedEvents(envelopes, None))
      )
    },

    // --------------------------------------------------------------
    // LIN1-B-02: corrupt/truncated tail -> honest marker, not an error;
    // the valid lines before the tail are read normally
    // --------------------------------------------------------------
    test("LIN1-B-02 torn last line: valid prefix is read, tail marked") {
      val first2 = Vector(env(1L, payloads(0)), env(2L, payloads(1)))
      val tail = torn(env(3L, payloads(2)), dropChars = 9)
      val content = first2.map(_.toJson).mkString("\n") + "\n" + tail
      val path = fixture("torn-tail.jsonl", content)
      assertTrue(
        LineageEventReader.read(path) ==
          Right(
            ParsedEvents(first2, Some(TruncatedTail(3, tail.length)))
          )
      )
    },
    test("LIN1-B-02 garbage last line with trailing newline is still a tail") {
      val first = env(1L, payloads(0))
      val path = fixture("garbage-tail.jsonl", first.toJson + "\nnot json\n")
      assertTrue(
        LineageEventReader.read(path) ==
          Right(
            ParsedEvents(
              Vector(first),
              Some(TruncatedTail(2, "not json".length))
            )
          )
      )
    },
    test("LIN1-B-02 single torn line yields empty envelopes plus tail marker") {
      val tail = torn(env(1L, payloads(0)), dropChars = 3)
      val path = fixture("single-torn.jsonl", tail)
      assertTrue(
        LineageEventReader.read(path) ==
          Right(ParsedEvents(Vector.empty, Some(TruncatedTail(1, tail.length))))
      )
    },

    // --------------------------------------------------------------
    // LIN1-B-03: typed errors (negative cases)
    // --------------------------------------------------------------
    test("LIN1-B-03 malformed line before the tail -> MalformedLine") {
      val path = fixture(
        "malformed.jsonl",
        env(1L, payloads(0)).toJson + "\n" +
          "not json\n" +
          env(2L, payloads(1)).toJson + "\n"
      )
      val verdict = LineageEventReader.read(path).left.map {
        case LineageError.MalformedLine(lineNo, detail) =>
          (lineNo, detail.nonEmpty)
        case other => (-1, false)
      }
      assertTrue(verdict.swap.toOption == Some((2 -> true)))
    },
    test("LIN1-B-03 schemaVersion=2 -> SchemaVersionUnsupported(2)") {
      val v2 = env(1L, payloads(0)).toJson
        .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
      val path = fixture("version.jsonl", v2 + "\n")
      assertTrue(
        LineageEventReader.read(path) ==
          Left(LineageError.SchemaVersionUnsupported(2))
      )
    },
    test("LIN1-B-03 schemaVersion=2 on the last line is an error, not a tail") {
      val good = env(1L, payloads(0))
      val v2 = env(2L, payloads(1)).toJson
        .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
      val path = fixture("version-tail.jsonl", good.toJson + "\n" + v2)
      assertTrue(
        LineageEventReader.read(path) ==
          Left(LineageError.SchemaVersionUnsupported(2))
      )
    },
    test("LIN1-B-03 seq gap -> SeqGap with expected and found") {
      val path = fixture(
        "seq-gap.jsonl",
        jsonl(env(1L, payloads(0)), env(3L, payloads(1)))()
      )
      assertTrue(
        LineageEventReader.read(path) ==
          Left(LineageError.SeqGap("job-1", 2L, 3L))
      )
    },
    test("LIN1-B-03 first seq of a job must be 1") {
      val path = fixture("seq-first.jsonl", jsonl(env(2L, payloads(0)))())
      assertTrue(
        LineageEventReader.read(path) ==
          Left(LineageError.SeqGap("job-1", 1L, 2L))
      )
    },
    test("LIN1-B-03 duplicate seq -> SeqGap") {
      val path = fixture(
        "seq-dup.jsonl",
        jsonl(env(1L, payloads(0)), env(1L, payloads(1)))()
      )
      assertTrue(
        LineageEventReader.read(path) ==
          Left(LineageError.SeqGap("job-1", 2L, 1L))
      )
    },
    test("LIN1-B-03 per-job counters are independent; a gap names its job") {
      val path = fixture(
        "seq-two-jobs.jsonl",
        jsonl(env(1L, payloads(0), jobId), env(1L, payloads(1), job2Id))() +
          env(3L, payloads(2), job2Id).toJson + "\n"
      )
      assertTrue(
        LineageEventReader.read(path) ==
          Left(LineageError.SeqGap("job-2", 2L, 3L))
      )
    },
    test("LIN1-B-03 empty file -> Empty") {
      val path = fixture("empty.jsonl", "")
      assertTrue(LineageEventReader.read(path) == Left(LineageError.Empty))
    }
  )
