package raider.cli.chat

import raider.runtime.display.{RateLimitRetries, StreamingDisplay}
import zio.test._

/** RAI-071 (chat slice): the chat backend wiring builds the native Anthropic
  * Messages backend for `--provider anthropic` — SSE streaming, wrapped in the
  * same RateLimitRetries as the openai path — and keeps the honest mock
  * fallback for any unknown provider (a failed setup never masquerades as a
  * live wire). No network is touched: the HttpClient is constructed lazily and
  * nothing is sent by construction alone.
  */
object ChatAnthropicBackendSpec extends ZIOSpecDefault:

  private def rawBackend(
      provider: String,
      baseUrl: String,
      key: Option[String]
  ): raider.core.ModelBackend =
    val display = ChatLoop.makeBackendSimple(
      provider,
      baseUrl,
      key,
      new ChatWriter
    )
    display match
      case sd: StreamingDisplay => sd.underlying
      case other                => other

  def spec = suite("chat anthropic backend (RAI-071)")(
    test(
      "chat: anthropic builds the streaming Messages backend behind RateLimitRetries"
    ) {
      val raw =
        rawBackend("anthropic", "http://127.0.0.1:8081/v1", Some("sk-ant-x"))
      val inner = raw match
        case rl: RateLimitRetries => rl.underlying
        case other                => other
      assertTrue(
        raw.isInstanceOf[RateLimitRetries],
        inner.isInstanceOf[raider.provider.anthropic.AnthropicMessagesBackend],
        inner.capabilities.streaming == raider.core.CapabilityStatus.Supported,
        inner.capabilities.tools == raider.core.CapabilityStatus.Supported
      )
    },
    test("chat: unknown provider keeps the mock fallback (never fake-live)") {
      val raw = rawBackend("gemini", "http://127.0.0.1:8081/v1", None)
      assertTrue(raw eq ChatLoop.MockBackendFallback.backend)
    }
  )
