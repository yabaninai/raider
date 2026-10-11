package raider.runtime.display

import raider.core.*
import zio.{Duration, Scope, UIO, ZIO}
import zio.stream.ZStream

/** Rate-limit/backoff decorator (nightly Phase 4.2): retries
  * `RaiderError.ProviderRateLimit` (HTTP 429) and
  * `RaiderError.ProviderUnavailable` (502/503 — retryable per the §14 taxonomy)
  * with exponential backoff — 1s, 2s, 4s by default, at most `maxRetries` (3)
  * re-attempts. Every other error propagates unchanged on the first occurrence;
  * a rate-limited attempt is unambiguous (the request was NOT accepted), so
  * retrying it violates no mutational-call trust boundary (model calls have no
  * side effects by contract).
  *
  * The decorator is transparent on success — events pass through unchanged.
  *
  * Retry emission (RAI-058): `onRetryScheduled` is an OPTIONAL hook called
  * BEFORE each backoff sleep with `(attempt, delayMs, cause)` — `attempt` is
  * the retry number (1 = first retry after the initial 429), `delayMs` the
  * exact sleep that follows, `cause` the typed error detail (bounded). The hook
  * is a `UIO` by contract (e.g. a failure-absorbing `RetryScheduled` publish —
  * see [[raider.runtime.loop.LoopEvents]]); an interrupted hook interrupts the
  * retry stream as any run cancellation would, never as a typed error. Default
  * `None` = zero overhead.
  */
final case class RateLimitRetries(
    underlying: ModelBackend,
    maxRetries: Int = 3,
    baseDelay: Duration = Duration.fromSeconds(1),
    onRetryScheduled: Option[RateLimitRetries.RetryScheduledSink] = None
) extends ModelBackend:

  override def capabilities: ModelCapabilities = underlying.capabilities

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    attempt(input, 0)

  private def attempt(
      input: ModelRequest,
      attemptNo: Int
  ): ZStream[Scope, RaiderError, ModelEvent] =
    underlying
      .stream(input)
      .catchAll:
        case rl @ (_: RaiderError.ProviderRateLimit |
            _: RaiderError.ProviderUnavailable) if attemptNo < maxRetries =>
          // exponential: 1s, 2s, 4s, ... (one value feeds hook AND sleep);
          // ProviderUnavailable (502/503) is retryable by the error taxonomy
          // (retryable=true) and only occurs before any event is emitted
          val delay =
            Duration.fromSeconds(
              baseDelay.getSeconds * math.pow(2, attemptNo).toLong
            )
          val hook = onRetryScheduled match
            case Some(h) =>
              h(
                attemptNo + 1,
                delay.toMillis,
                rl.detail.take(RateLimitRetries.maxCauseChars)
              )
            case None => ZIO.unit
          ZStream.fromZIO(hook) *>
            ZStream.fromZIO(ZIO.sleep(delay)) *>
            attempt(input, attemptNo + 1)
        case other => ZStream.fail(other)

object RateLimitRetries:

  /** `(retryAttempt, delayMs, cause) => emission effect`, failure-absorbing by
    * caller contract (`UIO`).
    */
  type RetryScheduledSink =
    (attempt: Int, delayMs: Long, cause: String) => UIO[Unit]

  /** Hygiene bound for the emitted `cause` (no frozen bound exists). */
  val maxCauseChars: Int = 512
