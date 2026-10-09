package raider.runtime.events

import raider.core.{JobId, RaiderError, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.jobs.JobStatus
import zio.{IO, UIO, ZIO}
import zio.test.*

/** RAI-055.a (first half): pure reducer tests — EVT-01 essence, per-job seq
  * rules (regression / duplicate), Terminal exactly once and last, bounded
  * progress tail with honest truncation counters, unknown usage honesty.
  * Deterministic: no sleeps, no clock, no effects beyond the typed error
  * channel.
  */
object SessionReducerSpec extends ZIOSpecDefault:

  private val jobId = JobId("job-1")
  private val rootId = RootId("root-1")
  private val sessionId = SessionId("sess-1")

  private def env(seq: Long, atMs: Long, p: EventPayload): EventEnvelope =
    EventEnvelope(1, seq, jobId, rootId, sessionId, atMs, p)

  private def replay(es: List[EventEnvelope]): IO[EventError, SessionSnapshot] =
    es.foldLeft(ZIO.succeed(SessionReducer.initial(jobId, rootId, sessionId))) {
      (acc, e) => acc.flatMap(s => SessionReducer.reduce(s, e))
    }

  private def replayAll(
      es: List[EventEnvelope]
  ): IO[EventError, Vector[SessionSnapshot]] =
    es.foldLeft(ZIO.succeed(Vector.empty[SessionSnapshot])) { (acc, e) =>
      acc.flatMap { prev =>
        val s = prev.lastOption.getOrElse(
          SessionReducer.initial(jobId, rootId, sessionId)
        )
        SessionReducer.reduce(s, e).map(s2 => prev :+ s2)
      }
    }

  /** Reduce `e` against the snapshot if the fold succeeded, else keep the
    * original failure (tests stay total: no .get on failed folds).
    */
  private def stepIfSucceeded(
      r: Either[EventError, SessionSnapshot],
      e: EventEnvelope
  ): UIO[Either[EventError, SessionSnapshot]] =
    r match
      case Right(s)  => SessionReducer.reduce(s, e).either
      case Left(err) => ZIO.succeed(Left(err))

  private def isMalformed(r: Either[EventError, SessionSnapshot]): Boolean =
    r match
      case Left(_: EventError.Malformed) => true
      case _                             => false

  /** 10 lines, the last one 2500 chars: the bound keeps 8 lines and cuts
    * exactly 500 chars.
    */
  private val scriptedLines: Vector[String] =
    Vector("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "x" * 2500)

  /** EVT-01 scripted stream: one of every meaningful payload class, ending with
    * the single Terminal.
    */
  private val scripted: List[EventEnvelope] = List(
    env(
      1L,
      1000L,
      EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
    ),
    env(2L, 1010L, EventPayload.ModelCallStarted(1, "model-a", Some(2))),
    env(3L, 1020L, EventPayload.ToolStarted("call-1", "bash", "echo hi")),
    env(
      4L,
      1030L,
      EventPayload
        .ToolProgress("call-1", "bash", LineTail(scriptedLines, 0, 0L))
    ),
    env(
      5L,
      1040L,
      EventPayload.ToolFinished("call-1", "bash", ToolOutcome.Succeeded)
    ),
    env(
      6L,
      1050L,
      EventPayload.BudgetChanged(MicroUsd(1000000L), MicroUsd(250000L), 0L)
    ),
    env(
      7L,
      1060L,
      EventPayload.JobStatusChanged(
        JobStatus.Running,
        JobStatus.Waiting,
        Some(WaitReason.Slot)
      )
    ),
    env(8L, 1070L, EventPayload.Waiting(WaitReason.Budget)),
    env(
      9L,
      1080L,
      EventPayload.ModelCallFinished(
        1,
        1500L,
        UsageSnapshot(MicroUsd(1000000L), MicroUsd(240000L), unknown = false)
      )
    ),
    env(10L, 1090L, EventPayload.Terminal(JobStatus.Failed))
  )

  def spec = suite("SessionReducer (pure)")(
    test("initial snapshot: Queued, watermark 0, empty tail and usage") {
      val s = SessionReducer.initial(jobId, rootId, sessionId)
      assertTrue(
        s.jobId == jobId,
        s.rootId == rootId,
        s.sessionId == sessionId,
        s.status == JobStatus.Queued,
        s.waitReason.isEmpty,
        s.lastSeq == 0L,
        s.activeTool.isEmpty,
        s.progressTail == LineTail(Vector.empty, 0, 0L),
        s.usage == UsageSnapshot(MicroUsd(0L), MicroUsd(0L), unknown = false),
        s.updatedAtMs == 0L
      )
    },
    test("EVT-01: scripted sequence folds to the expected snapshot") {
      for r <- replay(scripted).either
      yield assertTrue(
        r match
          case Right(s) =>
            s.status == JobStatus.Failed &&
            s.waitReason.isEmpty &&
            s.lastSeq == 10L &&
            s.activeTool.isEmpty &&
            s.progressTail.lines ==
              Vector("l3", "l4", "l5", "l6", "l7", "l8", "l9", "x" * 2000) &&
              s.progressTail.truncatedLines == 2 &&
              s.progressTail.truncatedChars == 500L &&
              s.usage == UsageSnapshot(
                MicroUsd(1000000L),
                MicroUsd(240000L),
                unknown = false
              ) &&
              s.updatedAtMs == 1090L
          case Left(_) => false
      )
    },
    test(
      "duplicate of the current lastSeq is idempotent (same state returned)"
    ) {
      for
        r <- replay(scripted).either
        r2 <- stepIfSucceeded(r, scripted.last)
      yield assertTrue(
        (r, r2) match
          case (Right(s), Right(s2)) => s2 == s
          case _                     => false
      )
    },
    test("seq regression is a typed Malformed error") {
      for
        r <- replay(scripted.take(5)).either
        r2 <- stepIfSucceeded(r, env(3L, 1L, EventPayload.Diagnostics("late")))
      yield assertTrue(isMalformed(r2))
    },
    test(
      "any new event after Terminal is Malformed (Terminal: exactly one, last)"
    ) {
      for
        r <- replay(scripted).either
        r2 <- stepIfSucceeded(
          r,
          env(11L, 1100L, EventPayload.Diagnostics("late"))
        )
        r3 <- stepIfSucceeded(
          r,
          env(12L, 1200L, EventPayload.Terminal(JobStatus.Succeeded))
        )
      yield assertTrue(isMalformed(r2), isMalformed(r3))
    },
    test(
      "progress tail bound: last 8 lines, 2000 chars/line, honest counters"
    ) {
      val chunk1 = LineTail(
        Vector(
          "a",
          "b",
          "c",
          "d",
          "e",
          "f",
          "g",
          "h",
          "i",
          "j",
          "k",
          "y" * 2100
        ),
        0,
        0L
      )
      val chunk2 = LineTail(Vector("k2", "l2"), 0, 0L)
      for r <- replay(
          List(
            env(1L, 1L, EventPayload.ToolProgress("c1", "bash", chunk1)),
            env(2L, 2L, EventPayload.ToolProgress("c1", "bash", chunk2))
          )
        ).either
      yield assertTrue(
        r match
          case Right(s) =>
            s.progressTail.lines ==
              Vector("g", "h", "i", "j", "k", "y" * 2000, "k2", "l2") &&
              s.progressTail.truncatedLines == 6 &&
              s.progressTail.truncatedChars == 100L
          case Left(_) => false
      )
    },
    test(
      "activeTool tracks the in-flight call (tool, callId) and clears on ToolFinished"
    ) {
      for r <- replayAll(
          List(
            env(1L, 1L, EventPayload.ToolStarted("c1", "bash", "echo")),
            env(
              2L,
              2L,
              EventPayload.ToolProgress(
                "c1",
                "bash",
                LineTail(Vector("x"), 0, 0L)
              )
            ),
            env(
              3L,
              3L,
              EventPayload.ToolFinished("c1", "bash", ToolOutcome.Succeeded)
            )
          )
        ).either
      yield assertTrue(
        r match
          case Right(snapshots) =>
            snapshots(0).activeTool == Some(("bash", "c1")) &&
            snapshots(1).activeTool == Some(("bash", "c1")) &&
            snapshots(2).activeTool.isEmpty &&
            snapshots(2).progressTail.lines == Vector("x")
          case Left(_) => false
      )
    },
    test(
      "Waiting sets status=Waiting and the reason; lastSeq/updatedAtMs follow the envelope"
    ) {
      for r <- replay(
          List(
            env(
              1L,
              1L,
              EventPayload.JobStatusChanged(
                JobStatus.Queued,
                JobStatus.Running,
                None
              )
            ),
            env(2L, 2L, EventPayload.Waiting(WaitReason.ChildSlot))
          )
        ).either
      yield assertTrue(
        r match
          case Right(s) =>
            s.status == JobStatus.Waiting &&
            s.waitReason.contains(WaitReason.ChildSlot) &&
            s.lastSeq == 2L &&
            s.updatedAtMs == 2L
          case Left(_) => false
      )
    },
    test("unknown usage stays unknown (never coerced to zero)") {
      for r <- replay(
          List(
            env(
              1L,
              1L,
              EventPayload.ModelCallFinished(
                1,
                100L,
                UsageSnapshot(MicroUsd(500L), MicroUsd(0L), unknown = true)
              )
            ),
            env(
              2L,
              2L,
              EventPayload.BudgetChanged(MicroUsd(500L), MicroUsd(0L), 1L)
            )
          )
        ).either
      yield assertTrue(
        r match
          case Right(s) =>
            s.usage == UsageSnapshot(
              MicroUsd(500L),
              MicroUsd(0L),
              unknown = true
            ) &&
            s.usage.unknown
          case Left(_) => false
      )
    },
    test(
      "EventError maps to the frozen RaiderError families (verified against core)"
    ) {
      val bp = EventError.Backpressure(jobId, 256).toRaiderError
      val mf = EventError.Malformed("bad seq").toRaiderError
      assertTrue(
        bp.isInstanceOf[RaiderError.CapabilityUnsupported],
        bp.code == "RA-CAP",
        mf.isInstanceOf[RaiderError.InputValidation],
        mf.code == "RA-INP"
      )
    }
  )
