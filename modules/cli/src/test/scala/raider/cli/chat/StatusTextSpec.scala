package raider.cli.chat

import raider.core.{JobId, RootId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{
  LineTail,
  SessionReducer,
  SessionSnapshot,
  StatusText,
  UsageSnapshot,
  WaitReason
}
import raider.runtime.jobs.{JobStatus, StatusLedger}
import zio.test.*

/** RAI-097 (UNI-01..03): the ONE canonical StatusText render over a session
  * snapshot — fixture-first, pure, honest.
  *
  *   - UNI-01 the same snapshot renders the same frozen line byte-for-byte;
  *   - UNI-02 unknown fields stay honest («?»/«unknown») — no `$0`, no `done`
  *     invented from nothing;
  *   - UNI-03 the render is pure and deterministic (no IO/clock/random; the
  *     line depends on snapshot fields only, repeated calls are identical and
  *     single-line).
  */
object StatusTextSpec extends ZIOSpecDefault:

  private val root = RootId("uni-root")
  private val sid = StatusLedger.sessionId
  private val jobId = JobId("uni-j")

  /** Frozen fixture: the pristine reducer snapshot (RAI-097 UNI-01 basis). */
  private val fixture: SessionSnapshot =
    SessionReducer.initial(jobId, root, sid)

  private def withStatus(
      s: SessionSnapshot,
      status: JobStatus,
      wait: Option[WaitReason] = None
  ): SessionSnapshot =
    s.copy(status = status, waitReason = wait)

  def spec = suite("StatusText (RAI-097)")(
    test(
      "UNI-01: same snapshot → byte-for-byte identical frozen line (fixture)"
    ) {
      val a = StatusText.render(fixture)
      val again = SessionReducer.initial(jobId, root, sid)
      assertTrue(
        a == "queued | cost unknown",
        a == StatusText.render(fixture),
        a == StatusText.render(again),
        StatusLine.render(StatusLine.Fold.initial(fixture)) == a
      )
    },
    test(
      "UNI-02: unknown fields stay honest — no $0, no invented done/reason"
    ) {
      val waitingNoReason =
        StatusText.render(withStatus(fixture, JobStatus.Waiting))
      val unknownCost = StatusText.render(
        fixture.copy(usage =
          UsageSnapshot(MicroUsd(500), MicroUsd(0), unknown = true)
        )
      )
      val pristine = StatusText.render(fixture)
      assertTrue(
        pristine.contains("cost unknown"),
        !pristine.contains("$0"),
        waitingNoReason == "waiting(unknown) | cost unknown",
        unknownCost.endsWith("cost unknown"),
        // a non-terminal status is never rendered as done
        !pristine.contains("terminal"),
        !pristine.contains("done")
      )
    },
    test(
      "UNI-03: pure and deterministic — repeated renders identical, one line"
    ) {
      val runs = (1 to 100).map(_ => StatusText.render(fixture))
      val otherMeta =
        withStatus(fixture, JobStatus.Running).copy(
          lastSeq = 7L,
          updatedAtMs = 42L
        )
      assertTrue(
        runs.forall(_ == runs.head),
        !runs.head.contains('\n'),
        !runs.head.contains('\r'),
        // the frozen format carries status/counters, not stream metadata
        StatusText.render(otherMeta) == StatusText
          .render(withStatus(fixture, JobStatus.Running))
      )
    },
    test("frozen format: every state renders its actual snapshot fields") {
      val tool = StatusText.render(
        fixture.copy(
          status = JobStatus.Running,
          activeTool = Some(("fs_read", "c1"))
        )
      )
      val wait = StatusText.render(
        withStatus(fixture, JobStatus.Waiting, Some(WaitReason.Backpressure))
      )
      val approval =
        StatusText.render(withStatus(fixture, JobStatus.WaitingApproval))
      val cancelling =
        StatusText.render(withStatus(fixture, JobStatus.Cancelling))
      val running = StatusText.render(withStatus(fixture, JobStatus.Running))
      val known =
        StatusText.render(
          fixture.copy(usage =
            UsageSnapshot(MicroUsd(0), MicroUsd(42), unknown = false)
          )
        )
      val tail = StatusText.render(
        fixture.copy(progressTail = LineTail(Vector("x"), 2, 300))
      )
      assertTrue(
        tool == "tool fs_read | cost unknown",
        wait == "waiting(backpressure) | cost unknown",
        approval == "waiting(approval) | cost unknown",
        cancelling == "cancelling | cost unknown",
        running == "running | cost unknown",
        known == "queued | cost $0.000042",
        tail == "queued | cost unknown, tail +2 lines/+300 chars"
      )
    }
  )

end StatusTextSpec
