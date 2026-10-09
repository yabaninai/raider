package raider.runtime.display

import raider.core.*
import raider.runtime.events.{
  EventEnvelope,
  EventError,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot
}
import raider.runtime.loop.LoopEvents
import zio.test.*
import zio.stream.ZStream
import zio.{Chunk, Duration as ZDuration, Ref, Scope, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** RAI-058 (RateLimitRetries seam): `RetryScheduled(attempt, delayMs, cause)`
  * is emitted BEFORE each backoff sleep when the optional hook is set; default
  * `None` = zero overhead; a failing emission (typed Backpressure) never breaks
  * the retry stream.
  *
  * Live clock on purpose (same discipline as RateLimitRetriesSpec): the
  * decorator sleeps real 1s/2s between attempts.
  */
object RateLimitRetriesEmissionSpec extends ZIOSpecDefault:

  private val job = JobId("rl-job")
  private val root = RootId("rl-root")
  private val sess = SessionId("rl-sess")
  private val att = AttemptId("rl-att")

  private final class Flaky(
      failures: Int,
      err: RaiderError,
      events: Vector[ModelEvent]
  ) extends ModelBackend:
    val attempts = AtomicInteger(0)

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          if attempts.incrementAndGet() <= failures then ZStream.fail(err)
          else ZStream.fromIterable(ModelEvent.Started(att) +: events)

  private val req =
    ModelRequest("m", List(RequestMessage("user", "hi")), 64, Nil)

  private def runStream(
      b: ModelBackend
  ): ZIO[Scope, RaiderError, Chunk[ModelEvent]] =
    b.stream(req).runCollect

  private def eventually(
      max: ZDuration
  )(cond: ZIO[Any, Nothing, Boolean]): ZIO[Any, String, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, String, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the generous outer bound")
            else ZIO.sleep(ZDuration.fromMillis(20)) *> loop
        }
      loop
    }

  def spec = suite("RateLimitRetries RetryScheduled emission (RAI-058)")(
    test(
      "RetryScheduled(attempt, delayMs, cause) before each backoff sleep, exact values"
    ) {
      val inner = Flaky(
        2,
        RaiderError.ProviderRateLimit("429 from fixture"),
        Vector(
          ModelEvent.TextDelta(att, "ok"),
          ModelEvent.Finished(att, "stop")
        )
      )
      for
        hub <- EventHub.make()
        (_, stream) <- hub.subscribe(job)
        sink <- Ref.make(Vector.empty[EventEnvelope])
        fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
        backend = RateLimitRetries(
          inner,
          maxRetries = 3,
          onRetryScheduled = Some((attempt, delayMs, cause) =>
            hub
              .publish(
                job,
                root,
                sess,
                EventPayload.RetryScheduled(attempt, delayMs, cause)
              )
              .catchAll(_ => ZIO.unit)
          )
        )
        events <- ZIO.scoped(runStream(backend))
        _ <- eventually(ZDuration.fromSeconds(10))(sink.get.map(_.size == 2))
        envs <- sink.get
        ordered = envs.sortBy(_.seq)
        _ <- fiber.interrupt
      yield assertTrue(
        inner.attempts.get() == 3, // 1 initial 429 + 2 retries, then success
        events.map(_.getClass.getSimpleName) == Vector(
          "Started",
          "TextDelta",
          "Finished"
        ),
        // RELIABLE class: per-class FIFO — attempt 1 (1s) before attempt 2 (2s)
        ordered.map(_.payload) == Vector(
          EventPayload.RetryScheduled(1, 1000L, "429 from fixture"),
          EventPayload.RetryScheduled(2, 2000L, "429 from fixture")
        )
      )
    } @@ TestAspect.withLiveClock,
    test("default None: zero overhead, nothing published, behavior unchanged") {
      val inner = Flaky(
        1,
        RaiderError.ProviderRateLimit("429"),
        Vector(
          ModelEvent.TextDelta(att, "ok"),
          ModelEvent.Finished(att, "stop")
        )
      )
      for
        hub <- EventHub.make()
        events <- ZIO.scoped(runStream(RateLimitRetries(inner)))
        snap <- hub.snapshot(job)
      yield assertTrue(
        inner.attempts.get() == 2,
        events.map(_.getClass.getSimpleName) == Vector(
          "Started",
          "TextDelta",
          "Finished"
        ),
        snap.isEmpty // nothing was ever published
      )
    } @@ TestAspect.withLiveClock,
    test("failing emission (Backpressure) never breaks the retry stream") {
      // the LoopEvents seam against a hub whose every publish fails with the
      // typed Backpressure outcome of the bounded wait
      val failing = new EventHub:
        def publish(
            jobId: JobId,
            rootId: RootId,
            sessionId: SessionId,
            payload: EventPayload
        ): ZIO[Any, EventError, Unit] =
          ZIO.fail(EventError.Backpressure(jobId, 1))
        def subscribe(
            jobId: JobId
        ): ZIO[
          Any,
          Nothing,
          (SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])
        ] =
          ZIO.succeed(
            (
              SessionReducer.initial(jobId, RootId(""), SessionId("")),
              ZStream.empty
            )
          )
        def snapshot(
            jobId: JobId
        ): ZIO[Any, Nothing, Option[SessionSnapshot]] = ZIO.succeed(None)
        def droppedDiagnostics: ZIO[Any, Nothing, Long] = ZIO.succeed(0L)
      val le = LoopEvents(failing, job, root, sess)
      val inner = Flaky(
        1,
        RaiderError.ProviderRateLimit("429"),
        Vector(
          ModelEvent.TextDelta(att, "ok"),
          ModelEvent.Finished(att, "stop")
        )
      )
      val backend = RateLimitRetries(
        inner,
        onRetryScheduled = Some((a, d, c) => le.retryScheduled(a, d, c))
      )
      for events <- ZIO.scoped(runStream(backend))
      yield assertTrue(
        inner.attempts.get() == 2, // the 429 was still retried
        events.map(_.getClass.getSimpleName) == Vector(
          "Started",
          "TextDelta",
          "Finished"
        )
      )
    } @@ TestAspect.withLiveClock
  )

end RateLimitRetriesEmissionSpec
