package raider.runtime.lineage.wire

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.*
import raider.runtime.events.EventCodecs.given
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.io.{
  LineageWireError,
  LineageWireReader,
  ParsedWireEvents,
  TruncatedTail
}
import raider.runtime.lineage.model.RecoveryClass
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** SCH-01/SCH-03 acceptance (card RAI-143, ADR-032): the versioned lineage wire
  * decodes v2 fixtures, decodes v1 fixtures UNCHANGED bit-for-bit through the
  * frozen codecs, and refuses malformed/unknown-version input typed. Fixtures
  * are written into a per-suite temp directory — no repo-local files, no live
  * services.
  */
object LineageWireSpec extends ZIOSpecDefault:

  private val jobId = JobId("job-1")
  private val childId = JobId("job-1-child")
  private val rootId = RootId("root-1")
  private val sessionId = SessionId("sess-1")

  /** One instance per frozen EventPayload case — exactly 13. */
  private val frozenPayloads: List[EventPayload] = List(
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
    EventPayload.Delegated(childId, "worker-2"),
    EventPayload.Diagnostics("diagnostic text"),
    EventPayload.DiagnosticsCoalesced(17L),
    EventPayload.Terminal(JobStatus.Succeeded)
  )

  private def v1Envelope(seq: Long, p: EventPayload, job: JobId = jobId) =
    EventEnvelope(1, seq, job, rootId, sessionId, 1760000000000L + seq, p)

  private def wireEnvelope(
      schemaVersion: Int,
      seq: Long,
      payload: LineagePayload,
      job: JobId = jobId,
      invocationId: Option[String] = None
  ) =
    LineageEnvelope(
      schemaVersion,
      seq,
      job,
      rootId,
      sessionId,
      1760000000000L + seq,
      payload,
      invocationId
    )

  // ------------------------------------------------------------------
  // Temp-dir fixture helpers (LineageEventReaderSpec pattern)
  // ------------------------------------------------------------------

  private val tempDir: Path =
    val dir = Files.createTempDirectory("lineage-wire-spec")
    Runtime.getRuntime.addShutdownHook(new Thread(() => deleteRecursively(dir)))
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

  private def jsonl(lines: String*) = lines.mkString("", "\n", "\n")

  def spec = suite("LineageWire (SCH-01/SCH-03, ADR-032)")(
    // --------------------------------------------------------------
    // SCH-01: v2 fixtures decode
    // --------------------------------------------------------------
    test("SCH-01 v2 payloads decode: phase_started/finished/run_provenance") {
      val started =
        """{"type":"phase_started","name":"repair","recovery":"Mutating","note":"touchy"}"""
      val startedNoNote =
        """{"type":"phase_started","name":"review","recovery":"ReadOnly"}"""
      val finished = """{"type":"phase_finished","name":"repair"}"""
      val provenance =
        """{"type":"run_provenance","parentRunId":"run-parent-9","artifacts":[{"kind":"work_dir","path":"/tmp/run-9"},{"kind":"events","path":"/tmp/run-9/events.jsonl"}]}"""
      assertTrue(
        started.fromJson[LineagePayload] ==
          Right(
            LineagePayload.PhaseStarted(
              "repair",
              RecoveryClass.Mutating,
              Some("touchy")
            )
          ),
        startedNoNote.fromJson[LineagePayload] ==
          Right(
            LineagePayload.PhaseStarted("review", RecoveryClass.ReadOnly, None)
          ),
        finished.fromJson[LineagePayload] ==
          Right(LineagePayload.PhaseFinished("repair")),
        provenance.fromJson[LineagePayload] ==
          Right(
            LineagePayload.RunProvenance(
              Some("run-parent-9"),
              Vector(
                ArtifactPointer("work_dir", "/tmp/run-9"),
                ArtifactPointer("events", "/tmp/run-9/events.jsonl")
              )
            )
          )
      )
    },
    test(
      "SCH-01 v2 wire fixtures decode end-to-end (mixed jobs, correlation)"
    ) {
      val lines = Vector(
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1760000000001,"payload":{"type":"phase_started","name":"baseline","recovery":"ReadOnly"}}""",
        """{"schemaVersion":2,"seq":2,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1760000000002,"payload":{"type":"run_provenance","artifacts":[]}}""",
        """{"schemaVersion":2,"seq":1,"jobId":"job-1-child","rootId":"root-1","sessionId":"sess-1","atMs":1760000000001,"payload":{"type":"phase_started","name":"repair","recovery":"Mutating"},"invocationId":"call-9"}""",
        """{"schemaVersion":2,"seq":2,"jobId":"job-1-child","rootId":"root-1","sessionId":"sess-1","atMs":1760000000002,"payload":{"type":"phase_finished","name":"repair"},"invocationId":"call-9"}"""
      )
      val path = fixture("sch01-v2.jsonl", jsonl(lines*))
      val expected = Vector(
        wireEnvelope(
          2,
          1,
          LineagePayload.PhaseStarted("baseline", RecoveryClass.ReadOnly, None)
        ),
        wireEnvelope(2, 2, LineagePayload.RunProvenance(None, Vector.empty)),
        wireEnvelope(
          2,
          1,
          LineagePayload.PhaseStarted(
            "repair",
            RecoveryClass.Mutating,
            None
          ),
          job = childId,
          invocationId = Some("call-9")
        ),
        wireEnvelope(
          2,
          2,
          LineagePayload.PhaseFinished("repair"),
          job = childId,
          invocationId = Some("call-9")
        )
      )
      assertTrue(
        LineageWireReader.read(path) ==
          Right(ParsedWireEvents(expected, None))
      )
    },
    test(
      "SCH-01 v2 roundtrip: encode(decode(x)) == x for all v2 payload shapes"
    ) {
      val payloads = Vector(
        LineagePayload.PhaseStarted("p", RecoveryClass.Idempotent, Some("n")),
        LineagePayload.PhaseStarted("p", RecoveryClass.Idempotent, None),
        LineagePayload.PhaseFinished("p"),
        LineagePayload.RunProvenance(
          Some("run-1"),
          Vector(ArtifactPointer("work_dir", "/tmp/1"))
        ),
        LineagePayload.RunProvenance(None, Vector.empty)
      )
      assertTrue(payloads.forall { p =>
        val json = p.toJson
        json.fromJson[LineagePayload] == Right(p) &&
        json.fromJson[LineagePayload].map(_.toJson) == Right(json)
      })
    },
    test(
      "SCH-01 v2 envelope roundtrip keeps invocationId last, omitted when absent"
    ) {
      val without = wireEnvelope(
        2,
        1,
        LineagePayload.PhaseFinished("p")
      ).toJson
      val withId = wireEnvelope(
        2,
        1,
        LineagePayload.PhaseFinished("p"),
        invocationId = Some("call-1")
      ).toJson
      assertTrue(
        without.endsWith("""{"type":"phase_finished","name":"p"}}"""),
        withId.endsWith(
          """{"type":"phase_finished","name":"p"},"invocationId":"call-1"}"""
        )
      )
    },

    // --------------------------------------------------------------
    // SCH-01: v1 fixtures decode UNCHANGED bit-for-bit
    // --------------------------------------------------------------
    test(
      "SCH-01 v1 pin: all 13 frozen payloads decode and re-encode byte-identically"
    ) {
      val lines = frozenPayloads.zipWithIndex.map { (p, i) =>
        v1Envelope(i + 1L, p).toJson
      }
      val path = fixture("sch01-v1.jsonl", jsonl(lines*))
      val expected = frozenPayloads.zipWithIndex.map { (p, i) =>
        LineageWire.wrap(v1Envelope(i + 1L, p))
      }
      val read = LineageWireReader.read(path)
      val reencoded = read.toOption
        .map(_.envelopes.map(_.toJson))
        .getOrElse(Vector.empty)
      assertTrue(
        read == Right(ParsedWireEvents(expected.toVector, None)),
        reencoded == lines
      )
    },
    test("SCH-01 v1 pin: wire codec and frozen codec agree line-for-line") {
      val checks = frozenPayloads.map { p =>
        val frozen = v1Envelope(1L, p).toJson
        val viaWire = LineageWire
          .wrap(v1Envelope(1L, p))
          .toJson
        frozen == viaWire
      }
      assertTrue(checks.forall(identity))
    },

    // --------------------------------------------------------------
    // SCH-01: mixed stream decodes typed, per-envelope version kept
    // --------------------------------------------------------------
    test(
      "SCH-01 mixed v1/v2 stream decodes; versions preserved as typed data"
    ) {
      val v1Line = v1Envelope(1L, frozenPayloads(0)).toJson
      val v2Line =
        """{"schemaVersion":2,"seq":2,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1760000000002,"payload":{"type":"phase_started","name":"fix","recovery":"Mutating"}}"""
      val path = fixture("sch01-mixed.jsonl", jsonl(v1Line, v2Line))
      val read = LineageWireReader.read(path)
      assertTrue(
        read.map(_.envelopes.map(e => (e.schemaVersion, e.seq))) ==
          Right(Vector((1, 1L), (2, 2L))),
        read.map(_.truncatedTail) == Right(None)
      )
    },

    // --------------------------------------------------------------
    // SCH-03: typed refusals (reader layer)
    // --------------------------------------------------------------
    test("SCH-03 unknown future version 999 -> SchemaVersionUnsupported(999)") {
      val line = v1Envelope(1L, frozenPayloads(0)).toJson
        .replace("\"schemaVersion\":1", "\"schemaVersion\":999")
      val path = fixture("sch03-v999.jsonl", jsonl(line))
      assertTrue(
        LineageWireReader.read(path) ==
          Left(LineageWireError.SchemaVersionUnsupported(999))
      )
    },
    test("SCH-03 unknown future version 3 -> SchemaVersionUnsupported(3)") {
      val line = v1Envelope(1L, frozenPayloads(0)).toJson
        .replace("\"schemaVersion\":1", "\"schemaVersion\":3")
      val path = fixture("sch03-v3.jsonl", jsonl(line))
      assertTrue(
        LineageWireReader.read(path) ==
          Left(LineageWireError.SchemaVersionUnsupported(3))
      )
    },
    test(
      "SCH-03 invocationId on a v1 envelope -> InvocationIdOnV1 (never silently ignored)"
    ) {
      val line =
        """{"schemaVersion":1,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1760000000001,"payload":{"type":"Waiting","reason":"Slot"},"invocationId":"call-9"}"""
      val path = fixture("sch03-v1-iid.jsonl", jsonl(line))
      assertTrue(
        LineageWireReader.read(path) == Left(
          LineageWireError.InvocationIdOnV1(1)
        )
      )
    },
    test(
      "SCH-03 malformed v2 payloads -> MalformedLine typed, each with detail"
    ) {
      // Each malformed line is followed by one good line so the bad line is
      // not the LAST line (a last-line failure would be the honest torn tail,
      // which is tested separately below).
      val good =
        """{"schemaVersion":2,"seq":2,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":2,"payload":{"type":"phase_finished","name":"p"}}"""
      val cases = List(
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1,"payload":{"type":"phase_started_unknown","name":"p","recovery":"Mutating"}}""",
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1,"payload":{"type":"phase_started","name":"p","recovery":"Sometimes"}}""",
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1,"payload":{"type":"phase_started","name":"","recovery":"Mutating"}}""",
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1,"payload":{"type":"phase_finished","name":"p","extra":1}}""",
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1,"payload":{"type":"run_provenance","artifacts":[{"kind":"work_dir"}]}}""",
        """{"schemaVersion":2,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":1,"payload":{"type":"run_provenance","artifacts":"nope"}}"""
      )
      val results = cases.zipWithIndex.map { case (line, i) =>
        val path = fixture(s"sch03-malformed-$i.jsonl", jsonl(line, good))
        LineageWireReader.read(path)
      }
      assertTrue(
        results.forall {
          case Left(LineageWireError.MalformedLine(1, detail)) =>
            detail.nonEmpty
          case other => false
        }
      )
    },
    test(
      "SCH-03 torn LAST line is an honest tail, not an error; earlier garbage is MalformedLine"
    ) {
      val good = v1Envelope(1L, frozenPayloads(0)).toJson
      val torn =
        """{"schemaVersion":2,"seq":2,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":2,"payload":{"type":"phase_star"""
      val tailPath =
        fixture("sch03-tail.jsonl", good + "\n" + torn)
      val garbagePath = fixture(
        "sch03-garbage.jsonl",
        good + "\nnot json\n" + good + "\n"
      )
      assertTrue(
        LineageWireReader.read(tailPath) ==
          Right(
            ParsedWireEvents(
              Vector(LineageWire.wrap(v1Envelope(1L, frozenPayloads(0)))),
              Some(TruncatedTail(2, torn.length))
            )
          ),
        LineageWireReader.read(garbagePath).swap.toOption.exists {
          case LineageWireError.MalformedLine(2, _) => true
          case _                                    => false
        }
      )
    },
    test("SCH-03 empty file -> Empty; seq gap -> SeqGap") {
      val empty = fixture("sch03-empty.jsonl", "")
      val gap = fixture(
        "sch03-gap.jsonl",
        jsonl(
          v1Envelope(1L, frozenPayloads(0)).toJson,
          v1Envelope(3L, frozenPayloads(1)).toJson
        )
      )
      assertTrue(
        LineageWireReader.read(empty) == Left(LineageWireError.Empty),
        LineageWireReader.read(gap) ==
          Left(LineageWireError.SeqGap("job-1", 2L, 3L))
      )
    }
  )

end LineageWireSpec
