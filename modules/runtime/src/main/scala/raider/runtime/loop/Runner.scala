package raider.runtime.loop

import raider.core.{ModelBackend, ModelEvent, ModelRequest, RaiderError,
                    RequestMessage, RootId}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import zio.{Scope, ZIO}
import zio.stream.ZStream

import java.util.concurrent.atomic.AtomicLong

/** The single execution point for one model attempt (fast-REPL slice; RAI-010
  * loop is the generalizing successor): admission slot + budget reservation
  * around ONE backend.stream pass that collects TextDelta until Finished.
  * No tools, no retries here — that is the RAI-010 obligation.
  *
  * `ceilings` must be the same BudgetLimits the `admission` was built from;
  * permit and budget accounting are owned by the admission instance, so this
  * method validates the declaration sanity but never invents a second limiter.
  * Cost accounting: no price is known at this level, so the attempt is
  * admitted as Unknown (tracked unpriced, never treated as free, §7); the
  * observed settle cost is zero for the scripted fixture wire — live pricing
  * is a provider-adapter obligation.
  */
object Runner:

  private val counter = AtomicLong(0)

  private val MaxOutputTokens = 1024

  def runText(backend: ModelBackend, model: String,
      messages: List[RequestMessage], ceilings: BudgetLimits,
      admission: Admission): ZIO[Any, RaiderError, String] =
    if ceilings.maxAttempts < 1 then
      ZIO.fail(RaiderError.Configuration(
        s"maxAttempts must be >= 1, got ${ceilings.maxAttempts}"))
    else
      val root    = RootId(s"run_${counter.incrementAndGet()}")
      val request = ModelRequest(model, messages, MaxOutputTokens)
      admission.withSlot(SlotKind.Model, root, CostEstimate.Unknown, None) {
        ZIO.scoped[Any]:
          collectText(backend.stream(request)).map(text => (text, MicroUsd(0)))
      }

  private def collectText(events: ZStream[Scope, RaiderError, ModelEvent])
      : ZIO[Scope, RaiderError, String] =
    events
      .runFold((new StringBuilder, Option.empty[RaiderError], false)) {
        case ((sb, failure, _), ModelEvent.TextDelta(_, text)) =>
          (sb.append(text), failure, false)
        case ((sb, _, _), ModelEvent.Failed(_, err)) =>
          (sb, Some(err), false)
        case ((sb, failure, _), ModelEvent.Finished(_, _)) =>
          (sb, failure, true)
        case (acc, _) => acc
      }
      .flatMap:
        case (_, Some(err), _)  => ZIO.fail(err)
        case (sb, None, true)   => ZIO.succeed(sb.result())
        case (_, None, false)   =>
          ZIO.fail(RaiderError.StreamProtocol(
            "backend stream ended without Finished"))
end Runner
