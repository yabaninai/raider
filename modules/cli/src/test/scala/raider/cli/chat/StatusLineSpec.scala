package raider.cli.chat

import raider.core.{JobId, RaiderError, RootId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  SessionReducer,
  ToolOutcome,
  UsageSnapshot
}
import raider.runtime.jobs.{JobManager, JobStatus, StatusLedger}
import raider.runtime.loop.LoopEvents
import zio.*
import zio.test.*
import zio.test.TestAspect.withLiveClock

/** RAI-060 STAT-01 (status line) + the cancel slice of STAT-03: real hub + real
  * JobManager (RAI-059 terminal authority) + the REAL chat job wiring
  * ([[ChatLoop.runJob]]); only the terminal writer is a test sink.
  */
object StatusLineSpec extends ZIOSpecDefault:

  private val root = RootId("stat01-root")
  private val sid = StatusLedger.sessionId

  private def take(
      lines: Queue[String],
      n: Int
  ): ZIO[Any, Throwable, List[String]] =
    ZIO
      .foreach(1 to n) { _ =>
        lines.take.timeout(10.seconds).flatMap {
          case Some(line) => ZIO.succeed(line)
          case None =>
            ZIO.fail(new RuntimeException("status line did not arrive in time"))
        }
      }
      .map(_.toList)

  private def envelope(
      jobId: JobId,
      seq: Long,
      payload: EventPayload
  ): EventEnvelope =
    EventEnvelope(1, seq, jobId, root, sid, 0L, payload)

  def spec = suite("status line (RAI-060)")(
    test(
      "STAT-01: scripted round → waiting(slot) → model round 1 → tool → terminal, cost stays unknown"
    ) {
      live {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          lines <- Queue.bounded[String](64)
          answer <- ChatLoop.runJob(
            hub,
            manager,
            root,
            line => lines.offer(line).unit,
            "t"
          ) { jobId =>
            val le = LoopEvents(hub, jobId, root, sid)
            le.waitingSlot *>
              le.modelCallStarted(1, "model-x", 2) *>
              le.modelCallFinished(1, 5L) *>
              le.toolStarted("c1", "fs_read", "{}") *>
              le.toolFinished("c1", "fs_read", ToolOutcome.Succeeded) *>
              ZIO.succeed("ok")
          }
          seen <- take(lines, 5)
        yield assertTrue(
          answer == "ok",
          seen == List(
            "waiting(slot) | cost unknown",
            "model round 1 | cost unknown",
            "tool fs_read | cost unknown",
            "model round 1 | cost unknown", // tool finished → back to the round
            "terminal succeeded | cost unknown"
          ),
          seen.forall(!_.contains("cost $0"))
        )
      }
    },
    test("STAT-01: failed body → terminal failed (job authority)") {
      live {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          lines <- Queue.bounded[String](64)
          result <- ChatLoop
            .runJob(hub, manager, root, line => lines.offer(line).unit, "t") {
              jobId =>
                val le = LoopEvents(hub, jobId, root, sid)
                le.waitingSlot *> ZIO.fail(
                  RaiderError.InputValidation("boom")
                )
            }
            .either
          seen <- take(lines, 2)
        yield assertTrue(
          result.isLeft,
          seen == List(
            "waiting(slot) | cost unknown",
            "terminal failed | cost unknown"
          )
        )
      }
    },
    test(
      "STAT-03: cancel mid-run → terminal cancelled present, ordered, last"
    ) {
      live {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          lines <- Queue.bounded[String](64)
          result <- ChatLoop
            .runJob(
              hub,
              manager,
              root,
              line => lines.offer(line).unit,
              "t",
              onStart = h =>
                (ZIO.sleep(150.millis) *> manager.cancel(h)).forkDaemon.unit
            ) { jobId =>
              val le = LoopEvents(hub, jobId, root, sid)
              le.waitingSlot *> ZIO.sleep(60.seconds)
            }
            .either
          seen <- take(lines, 3)
        yield assertTrue(
          result.fold(_.code == "RA-CANCEL", _ => false),
          seen == List(
            "waiting(slot) | cost unknown",
            "cancelling | cost unknown",
            "terminal cancelled | cost unknown"
          )
        )
      }
    },
    test(
      "STAT-01: pure renders — queued, retry, waiting(unknown), cost honesty"
    ) {
      val jobId = JobId("j0")
      val init = StatusLine.Fold.initial(
        SessionReducer.initial(jobId, root, sid)
      )
      val retry = zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(
            StatusLine.Fold
              .step(
                init,
                envelope(jobId, 1, EventPayload.RetryScheduled(1, 2000, "429"))
              )
          )
          .getOrThrow()
      }
      val waiting = zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(
            StatusLine.Fold
              .step(
                retry,
                envelope(
                  jobId,
                  2,
                  EventPayload.JobStatusChanged(
                    JobStatus.Running,
                    JobStatus.Waiting,
                    None
                  )
                )
              )
          )
          .getOrThrow()
      }
      val knownZeroCost = init.copy(snapshot =
        init.snapshot.copy(usage =
          UsageSnapshot(MicroUsd(0), MicroUsd(42), unknown = false)
        )
      )
      assertTrue(
        StatusLine.render(init) == "queued | cost unknown",
        StatusLine.render(retry) == "retry in 2000ms | cost unknown",
        StatusLine.render(waiting) == "waiting(unknown) | cost unknown",
        StatusLine.render(knownZeroCost).endsWith("| cost $0.000042")
      )
    }
  ) @@ withLiveClock @@ TestAspect.timeout(120.seconds)

end StatusLineSpec
