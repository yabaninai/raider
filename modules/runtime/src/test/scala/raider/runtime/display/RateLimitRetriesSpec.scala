package raider.runtime.display

import raider.core.*
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.util.concurrent.atomic.AtomicInteger

/** Rate-limit backoff fixtures (nightly Phase 4.2): ONLY ProviderRateLimit is
  * retried, exponentially (1s, 2s, 4s), at most 3 retries; every other error
  * propagates immediately; success after a 429 storm streams through.
  *
  * The retry tests run on the LIVE clock (`@@ TestAspect.withLiveClock`): the
  * decorator sleeps real 1s/2s/4s between attempts (~7s total worst case).
  */
object RateLimitRetriesSpec extends ZIOSpecDefault:

  private val att = AttemptId("rl-att")

  /** Fails the first `failures` calls with `err`, then streams the scripted
    * events. Records attempt count.
    */
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
          else
            ZStream.fromIterable(
              ModelEvent.Started(att) +: events
            )

  private def runStream(
      b: ModelBackend
  ): ZIO[Scope, RaiderError, Chunk[ModelEvent]] =
    b.stream(ModelRequest("m", List(RequestMessage("user", "hi")), 64, Nil))
      .runCollect

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RateLimitRetries")(
      test("429 then success: retries and streams the result") {
        val inner = Flaky(
          1,
          RaiderError.ProviderRateLimit("429"),
          Vector(
            ModelEvent.TextDelta(att, "ok"),
            ModelEvent.Finished(att, "stop")
          )
        )
        val backend = RateLimitRetries(inner)
        for events <- ZIO.scoped(runStream(backend))
        yield assertTrue(
          inner.attempts.get() == 2,
          events.map(_.getClass.getSimpleName) == Vector(
            "Started",
            "TextDelta",
            "Finished"
          )
        )
      } @@ TestAspect.withLiveClock,
      test(
        "persistent 429: exactly maxRetries+1 attempts, then typed failure"
      ) {
        val inner =
          Flaky(999, RaiderError.ProviderRateLimit("429"), Vector.empty)
        val backend = RateLimitRetries(inner, maxRetries = 3)
        for exit <- ZIO.scoped(runStream(backend).exit)
        yield assertTrue(
          inner.attempts.get() == 4, // 1 initial + 3 retries
          exit.isFailure
        )
      } @@ TestAspect.withLiveClock,
      test("non-rate-limit errors are NOT retried") {
        val inner =
          Flaky(999, RaiderError.ProviderAuth("401"), Vector.empty)
        val backend = RateLimitRetries(inner)
        for exit <- ZIO.scoped(runStream(backend).exit)
        yield assertTrue(inner.attempts.get() == 1, exit.isFailure)
      }
    )
