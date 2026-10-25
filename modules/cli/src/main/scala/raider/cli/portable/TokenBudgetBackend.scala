package raider.cli.portable

import raider.core.{
  ModelBackend,
  ModelCapabilities,
  ModelEvent,
  ModelRequest,
  RaiderError
}
import zio.{Scope, ZIO}
import zio.stream.ZStream

import java.util.concurrent.atomic.AtomicLong

/** Launcher-owned token ceiling envelope (RAI-199): the declared portable
  * `tokens` limit has NO runtime knob — `BudgetLimits` is attempt/USD-based —
  * so the LAUNCH layer enforces it by wrapping the one backend every pinned
  * trusted agent shares.
  *
  * Discipline (honest accounting):
  *   - usage is folded from the wire's `UsageObserved` events (currency-neutral
  *     input+output sums, the same `Usage.plus` semantics);
  *   - an in-flight attempt whose usage arrives past the ceiling COMPLETES —
  *     the spend already happened, so failing it mid-flight would lie about
  *     what was paid;
  *   - the NEXT dispatch past the ceiling is refused BEFORE any wire call with
  *     the typed RA-PBUDGET (exit 23, BudgetExhausted via the frozen mapping).
  *
  * This is a pure decorator: no scheduler, no second ledger — one AtomicLong
  * over the ordinary backend stream, shared by all agents of ONE invocation.
  */
final class TokenBudgetBackend(
    underlying: ModelBackend,
    ceilingTokens: Long,
    used: AtomicLong
) extends ModelBackend:

  def capabilities: ModelCapabilities = underlying.capabilities

  def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    val total = used.get()
    if total > ceilingTokens then
      ZStream.fail(
        RaiderError.ProviderBudget(
          s"declared token ceiling of $ceilingTokens exceeded " +
            s"(observed $total); further model dispatch is refused"
        )
      )
    else
      underlying.stream(input).tap {
        case ModelEvent.UsageObserved(_, usage, _) =>
          ZIO.succeed {
            used.addAndGet(usage.inputTokens + usage.outputTokens)
            ()
          }
        case _ => ZIO.unit
      }

  def totalTokens: Long = used.get()

end TokenBudgetBackend
