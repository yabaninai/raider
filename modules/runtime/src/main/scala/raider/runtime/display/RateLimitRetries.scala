package raider.runtime.display

import raider.core.*
import zio.{Duration, Scope, ZIO}
import zio.stream.ZStream

/** Rate-limit backoff decorator (nightly Phase 4.2): retries ONLY
  * `RaiderError.ProviderRateLimit` (HTTP 429) with exponential backoff — 1s,
  * 2s, 4s by default, at most `maxRetries` (3) re-attempts. Every other error
  * propagates unchanged on the first occurrence; a rate-limited attempt is
  * unambiguous (the request was NOT accepted), so retrying it violates no
  * mutational-call trust boundary (model calls have no side effects by
  * contract).
  *
  * The decorator is transparent on success — events pass through unchanged.
  */
final case class RateLimitRetries(
    underlying: ModelBackend,
    maxRetries: Int = 3,
    baseDelay: Duration = Duration.fromSeconds(1)
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
        case rl: RaiderError.ProviderRateLimit if attemptNo < maxRetries =>
          // exponential: 1s, 2s, 4s, ...
          ZStream.fromZIO(
            ZIO.sleep(
              Duration.fromSeconds(
                baseDelay.getSeconds * math.pow(2, attemptNo).toLong
              )
            )
          ) *>
            attempt(input, attemptNo + 1)
        case other => ZStream.fail(other)
