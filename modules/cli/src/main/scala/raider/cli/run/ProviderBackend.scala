package raider.cli.run

import raider.core.{ModelBackend, RaiderError}
import zio.ZIO

/** RAI-071: the provider → backend factory for the headless runner — one place
  * that maps a validated `--provider` value onto its ModelBackend:
  *   - mock → the built-in fixture backend (no network, no key);
  *   - openai → OpenAI-compatible chat backend (RAI-064 wire);
  *   - anthropic → native Anthropic Messages wire backend (provider-anthropic
  *     module, `/v1/messages` semantics — no fake OpenAI conversion).
  *
  * Both live wires get the SAME treatment: the caller's credential preflight
  * ([[Credentials.resolve]]) runs BEFORE this factory, so a missing key is a
  * typed RA-CFG refusal before any dispatch/spend and never reaches the backend
  * as a network attempt; construction failures are typed; and the built backend
  * is wrapped in RateLimitRetries (429 exponential backoff) — wire parity by
  * construction.
  */
object ProviderBackend:

  private val callTimeoutMs = 300000L

  /** Build the backend for `provider`. `keyOpt` is guaranteed non-empty for a
    * live provider by the caller's preflight (RAI-064); the `getOrElse("")`
    * fallback here surfaces the backend config's own typed error instead of
    * inventing a second validation.
    */
  def make(
      provider: String,
      baseUrl: String,
      keyOpt: Option[String]
  ): ZIO[Any, RaiderError, ModelBackend] =
    provider match
      case "openai" =>
        guarded(
          raider.provider.chat.OpenAIChatBackend.make(
            raider.provider.chat.OpenAIChatBackend.Config
              .make(
                baseUrl,
                keyOpt.getOrElse(""),
                callTimeoutMs = callTimeoutMs
              )
          )
        )
      case "anthropic" =>
        guarded(
          raider.provider.anthropic.AnthropicMessagesBackend.make(
            raider.provider.anthropic.AnthropicMessagesBackend.Config
              .make(
                baseUrl,
                keyOpt.getOrElse(""),
                callTimeoutMs = callTimeoutMs
              )
          )
        )
      case _ => ZIO.succeed(MockBackend.backend)

  /** 429s back off exponentially (1s/2s/4s, max 3 retries) for BOTH live wires;
    * every other error stays first-shot. Init failures are typed: a RaiderError
    * passes through, anything else becomes ProviderUnavailable.
    */
  private def guarded(
      build: ZIO[Any, Throwable, ModelBackend]
  ): ZIO[Any, RaiderError, ModelBackend] =
    build
      .map(raider.runtime.display.RateLimitRetries(_))
      .mapError {
        case e: RaiderError => e
        case t =>
          RaiderError.ProviderUnavailable(
            s"backend init: ${t.getClass.getSimpleName}"
          )
      }

end ProviderBackend
