package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.jobs.JobStatus
import raider.runtime.events.EventCodecs.given
import zio.json.*
import zio.test.*

/** ADR-024 §2.6 contract tests: the envelope codec must roundtrip ALL 13 frozen
  * payload cases and the full envelope (schemaVersion = 1). Deterministic: pure
  * encode/decode, no I/O.
  */
object EventCodecSpec extends ZIOSpecDefault:

  private val jobId = JobId("job-1")
  private val rootId = RootId("root-1")
  private val sessionId = SessionId("sess-1")

  private def roundtrip[A: JsonCodec](a: A): Either[String, A] =
    a.toJson.fromJson[A]

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

  private val caseNames: Set[String] = Set(
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

  private def envelope(seq: Long, atMs: Long, p: EventPayload): EventEnvelope =
    EventEnvelope(1, seq, jobId, rootId, sessionId, atMs, p)

  def spec = suite("EventEnvelope JSON codec")(
    test("all 13 frozen payload cases are covered exactly once") {
      val names = payloads.map(_.productPrefix).toSet
      assertTrue(payloads.size == 13, names == caseNames)
    },
    test("roundtrip: every EventPayload case") {
      val failures = payloads.flatMap { p =>
        roundtrip(p) match
          case Right(_)  => Nil
          case Left(err) => List(s"${p.productPrefix}: $err")
      }
      val decoded = payloads.map(p => roundtrip(p).toOption.get)
      assertTrue(failures.isEmpty, decoded == payloads)
    },
    test("roundtrip: full envelope for every payload case") {
      val envelopes =
        payloads.zipWithIndex.map((p, i) =>
          envelope(i + 1L, 1760000000000L + i, p)
        )
      val failures = envelopes.flatMap { e =>
        roundtrip(e) match
          case Right(_)  => Nil
          case Left(err) => List(s"seq=${e.seq}: $err")
      }
      val decoded = envelopes.map(e => roundtrip(e).toOption.get)
      assertTrue(failures.isEmpty, decoded == envelopes)
    },
    test("wire: schemaVersion=1, plain-string ids, discriminated payload") {
      val json = envelope(
        1L,
        1000L,
        EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
      ).toJson
      assertTrue(
        json.contains("\"schemaVersion\":1"),
        json.contains("\"seq\":1"),
        json.contains("\"jobId\":\"job-1\""),
        json.contains("\"rootId\":\"root-1\""),
        json.contains("\"sessionId\":\"sess-1\""),
        json.contains("\"atMs\":1000"),
        json.contains("\"type\":\"JobStatusChanged\""),
        json.contains("\"from\":\"Queued\""),
        json.contains("\"to\":\"Running\""),
        // None Option fields are omitted from the wire (decode: missing = None)
        !json.contains("\"reason\"")
      )
    },
    test("roundtrip: JobStatus (all 10) and JobStatus.Terminal (all 5)") {
      val statuses: List[JobStatus] = List(
        JobStatus.Queued,
        JobStatus.Running,
        JobStatus.Waiting,
        JobStatus.WaitingApproval,
        JobStatus.Cancelling,
        JobStatus.Succeeded,
        JobStatus.Failed,
        JobStatus.Cancelled,
        JobStatus.Interrupted,
        JobStatus.Uncertain
      )
      val terminals: List[JobStatus.Terminal] = List(
        JobStatus.Succeeded,
        JobStatus.Failed,
        JobStatus.Cancelled,
        JobStatus.Interrupted,
        JobStatus.Uncertain
      )
      val s = statuses.map(roundtrip(_).toOption.get)
      val t = terminals.map(roundtrip(_).toOption.get)
      assertTrue(s == statuses, t == terminals)
    },
    test("roundtrip: WaitReason (all 6) and ToolOutcome (all 4)") {
      val reasons: List[WaitReason] = List(
        WaitReason.Slot,
        WaitReason.Admission,
        WaitReason.Approval,
        WaitReason.Budget,
        WaitReason.ChildSlot,
        WaitReason.Backpressure
      )
      val outcomes: List[ToolOutcome] = List(
        ToolOutcome.Succeeded,
        ToolOutcome.Failed("boom"),
        ToolOutcome.Cancelled,
        ToolOutcome.Timeout
      )
      val r = reasons.map(roundtrip(_).toOption.get)
      val o = outcomes.map(roundtrip(_).toOption.get)
      assertTrue(r == reasons, o == outcomes)
    },
    test("decode: unknown JobStatus name is rejected, never guessed") {
      assertTrue("NotAStatus".fromJson[JobStatus].isLeft)
    },
    test("decode: unknown payload discriminator is rejected, never guessed") {
      assertTrue("""{"type":"Nope"}""".fromJson[EventPayload].isLeft)
    },
    test("decode: non-terminal status is not accepted as Terminal") {
      assertTrue("Running".fromJson[JobStatus.Terminal].isLeft)
    }
  )
