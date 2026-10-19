package raider.cli.run

import raider.core.{JobId, RootId}
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.jobs.StatusLedger
import raider.cli.lineage.ReplayHarness
import java.util.concurrent.TimeUnit
import java.time.Instant
import zio.{Clock, Ref, ZIO}
import zio.test.*
import zio.test.TestClock

/** Clock contract acceptance: all production wall-clock polls terminate while
  * the enclosing ZIO TestClock remains frozen.
  */
object ClockPollSpec extends ZIOSpecDefault:

  private val root = RootId("clock-poll-root")
  private val session = StatusLedger.sessionId

  private def elapsedMillis(started: Long): ZIO[Any, Nothing, Long] =
    ZIO.succeed((java.lang.System.nanoTime() - started) / 1000000L)

  def spec = suite("runtime wall-clock poll contract (RAI-180)")(
    test("CLOCK-POLL-02: awaitCaughtUp reaches its deadline") {
      val jobId = JobId("frozen-clock-event-export")
      for
        // ZIO 2.1 TestClock is frozen unless explicitly adjusted or set.
        _ <- TestClock.setTime(Instant.EPOCH)
        hub <- EventHub.make()
        _ <- hub.publish(
          jobId,
          root,
          session,
          EventPayload.Waiting(
            raider.runtime.events.WaitReason.Slot
          )
        )
        virtualTimeBefore <- Clock.currentTime(TimeUnit.NANOSECONDS)
        started <- ZIO.succeed(java.lang.System.nanoTime())
        _ <- EventExport.awaitCaughtUp(
          hub,
          jobId,
          ZIO.succeed(0L),
          budgetMs = 40L
        )
        elapsed <- elapsedMillis(started)
        virtualTimeAfter <- Clock.currentTime(TimeUnit.NANOSECONDS)
      yield assertTrue(
        elapsed >= 30L,
        elapsed < 1000L,
        virtualTimeAfter == virtualTimeBefore
      )
    },
    test("CLOCK-POLL-02: MultiJobExport.pollUntil reaches its deadline") {
      for
        // ZIO 2.1 TestClock is frozen unless explicitly adjusted or set.
        _ <- TestClock.setTime(Instant.EPOCH)
        virtualTimeBefore <- Clock.currentTime(TimeUnit.NANOSECONDS)
        started <- ZIO.succeed(java.lang.System.nanoTime())
        _ <- MultiJobExport.pollUntil(ZIO.succeed(false), budgetMs = 40L)
        elapsed <- elapsedMillis(started)
        virtualTimeAfter <- Clock.currentTime(TimeUnit.NANOSECONDS)
      yield assertTrue(
        elapsed >= 30L,
        elapsed < 1000L,
        virtualTimeAfter == virtualTimeBefore
      )
    },
    test(
      "CLOCK-POLL-02: eventuallyTerminal preserves its typed deadline error"
    ) {
      val jobId = JobId("frozen-clock-replay")
      for
        // ZIO 2.1 TestClock is frozen unless explicitly adjusted or set.
        _ <- TestClock.setTime(Instant.EPOCH)
        hub <- EventHub.make()
        sink <- Ref.make(Vector.empty[EventEnvelope])
        virtualTimeBefore <- Clock.currentTime(TimeUnit.NANOSECONDS)
        started <- ZIO.succeed(java.lang.System.nanoTime())
        result <- ReplayHarness
          .eventuallyTerminal(
            hub,
            jobId,
            0L,
            sink,
            zio.Duration.fromMillis(40L)
          )
          .either
        elapsed <- elapsedMillis(started)
        virtualTimeAfter <- Clock.currentTime(TimeUnit.NANOSECONDS)
      yield assertTrue(
        result.left.toOption.exists(_.contains("within the outer bound")),
        elapsed >= 30L,
        elapsed < 1000L,
        virtualTimeAfter == virtualTimeBefore
      )
    }
  ) @@ TestAspect.timeout(zio.Duration.fromSeconds(5))

end ClockPollSpec
