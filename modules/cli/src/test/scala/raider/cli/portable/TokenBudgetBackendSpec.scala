package raider.cli.portable

import raider.core.*
import zio.{Chunk, Exit, Scope, ZIO}
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

/** The launcher-owned token ceiling (RAI-199): honest accounting over the
  * wire's `UsageObserved` events — an in-flight attempt that goes past the
  * ceiling COMPLETES (the spend happened; failing it mid-flight would lie), and
  * the NEXT dispatch past the ceiling is refused BEFORE any wire call with the
  * typed RA-PBUDGET, on the ONE shared ledger of the invocation.
  */
object TokenBudgetBackendSpec extends ZIOSpecDefault:

  private final class ScriptedBackend(usage: Usage, dispatches: AtomicInteger)
      extends ModelBackend:

    def capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.fromZIO(ZIO.succeed(dispatches.incrementAndGet())) *>
        ZStream(
          ModelEvent
            .UsageObserved(AttemptId("a1"), usage, priceKnown = false),
          ModelEvent.Finished(AttemptId("a1"), "stop")
        )

  private val req = ModelRequest("stand-model", Nil, maxOutputTokens = 512)

  private def drainedFailure(
      b: ModelBackend
  ): ZIO[Any, Nothing, Option[String]] =
    ZIO
      .scoped(b.stream(req).runDrain)
      .exit
      .map {
        case Exit.Failure(cause) =>
          cause.failures.collectFirst { case RaiderError.ProviderBudget(m) =>
            m
          }
        case Exit.Success(_) => None
      }

  private def scripted(usage: Usage): (ScriptedBackend, AtomicInteger) =
    val d = new AtomicInteger(0)
    (ScriptedBackend(usage, d), d)

  def spec =
    suite("raider portable token budget envelope")(
      test(
        "usage is folded from the wire and the decorator is a pass-through"
      ) {
        val (raw, _) = scripted(Usage(100L, 50L, None))
        val used = new AtomicLong(0L)
        val b = TokenBudgetBackend(raw, ceilingTokens = 1000L, used)
        for events <- ZIO.scoped(b.stream(req).runCollect)
        yield
          val usages =
            events.collect { case ModelEvent.UsageObserved(_, u, _) =>
              u
            }
          assert(events.nonEmpty)(isTrue) &&
          assert(usages.map(u => u.inputTokens + u.outputTokens))(
            equalTo(Chunk(150L))
          ) &&
          assert(b.totalTokens)(equalTo(150L)) &&
          assert(b.capabilities)(equalTo(raw.capabilities))
      },
      test(
        "an in-flight attempt past the ceiling COMPLETES (honest completion)"
      ) {
        val (raw, _) = scripted(Usage(150L, 0L, None))
        val b = TokenBudgetBackend(
          raw,
          ceilingTokens = 100L,
          new AtomicLong(0L)
        )
        for events <- ZIO.scoped(b.stream(req).runCollect)
        yield assert(events.collectFirst { case ModelEvent.Finished(_, _) =>
          true
        })(
          isSome
        ) && assert(b.totalTokens)(equalTo(150L))
      },
      test(
        "the NEXT dispatch past the ceiling is refused typed RA-PBUDGET with NO wire call"
      ) {
        val (raw, dispatches) = scripted(Usage(150L, 0L, None))
        val b = TokenBudgetBackend(
          raw,
          ceilingTokens = 100L,
          new AtomicLong(0L)
        )
        // First dispatch: honest completion past the ceiling (spend recorded).
        for
          _ <- ZIO.scoped(b.stream(req).runDrain)
          // Second dispatch: refused BEFORE hitting the wire.
          failure <- drainedFailure(b)
        yield assert(failure)(isSome(containsString("token ceiling of 100"))) &&
          assert(dispatches.get)(equalTo(1))
      },
      test(
        "the ledger is shared across the invocation's wrappers (same AtomicLong)"
      ) {
        val (raw, _) = scripted(Usage(60L, 0L, None))
        val used = new AtomicLong(0L)
        val first = TokenBudgetBackend(raw, ceilingTokens = 100L, used)
        val second = TokenBudgetBackend(raw, ceilingTokens = 100L, used)
        for
          _ <- ZIO.scoped(first.stream(req).runDrain)
          _ <- ZIO.scoped(first.stream(req).runDrain) // total 120 > 100
          failure <- drainedFailure(second)
        yield assert(failure)(isSome(containsString("observed 120")))
      }
    )

end TokenBudgetBackendSpec
