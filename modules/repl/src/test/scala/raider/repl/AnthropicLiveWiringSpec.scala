package raider.repl

import raider.core.*
import raider.provider.anthropic.AnthropicMessagesBackend
import zio.Scope
import zio.test.*

/** RAI-098 fixtures: REPL live Anthropic wiring (RANT-01..03).
  *
  * All plan assertions are PURE (`Main.preludePlan` takes an injectable env);
  * the one construction test uses the provider-anthropic FACTORY exactly as the
  * generated prelude does — it builds an HttpClient and dials nothing, so no
  * network and no paid call ever happens (repl gates stay mock/replay).
  *
  *   - RANT-01: anthropic + resolvable key -> the prelude wires the REAL
  *     AnthropicMessagesBackend (SSE streaming via the factory); no notice, no
  *     scripted fallback; the factory construction succeeds without network and
  *     yields the Anthropic class; the raw key only ever appears as the escaped
  *     interpreter-world binding (evaluated silently).
  *   - RANT-02: anthropic without a key -> the 090 honest fallback: one
  *     how-to-enable notice (env-var names only) + scripted/mock sources, never
  *     a crash, never a fake live wire.
  *   - RANT-03: neighbor paths unchanged — openai live wiring keeps the RAI-090
  *     textual shape, the mock path stays scripted, mask semantics unchanged;
  *     whole-suite greenness (raiderRepl/test) plus
  *     scripts/quality/repl_smoke.sh are the run evidence.
  */
object AnthropicLiveWiringSpec extends ZIOSpecDefault:

  private val Secret = "test-sec-val"
  private val BaseUrl = "http://127.0.0.1:8081/v1"

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnthropicLiveWiring (RAI-098)")(
      suite("RANT-01: anthropic + key -> live AnthropicMessagesBackend")(
        test(
          "prelude wires the anthropic factory backend: no notice, no mock"
        ) {
          val plan = Main.preludePlan(
            Map("RAIDER_PROVIDER" -> "anthropic", "RAIDER_API_KEY" -> Secret)
          )
          val all = plan.sources.mkString("\n")
          assertTrue(
            plan.notices.isEmpty,
            all.contains("raider.provider.anthropic.AnthropicMessagesBackend"),
            all.contains(".make(Right(__cfg), streaming = true)"),
            all.contains("callTimeoutMs = 300000L"),
            all.contains("val __apiKey = \"" + Secret + "\""),
            all.contains("CodingToolset"),
            all.contains("echoStream = true"),
            !all.contains("ScriptedModelBackend"),
            !all.contains("continuing in mock")
          )
        },
        test(
          "quotes/backslashes in the anthropic key cannot break the prelude"
        ) {
          val plan = Main.preludePlan(
            Map(
              "RAIDER_PROVIDER" -> "anthropic",
              "RAIDER_API_KEY" -> "a\"b\\c-1"
            )
          )
          val all = plan.sources.mkString("\n")
          val expected = "val __apiKey = \"a\\\"b\\\\c-1\""
          assertTrue(
            all.contains(expected),
            !all.contains("a\"b\\c-1\"")
          )
        },
        test(
          "the exact factory construction the prelude performs succeeds " +
            "WITHOUT network and yields the Anthropic class"
        ) {
          for b <- AnthropicMessagesBackend.make(
              AnthropicMessagesBackend.Config
                .make(BaseUrl, Secret, callTimeoutMs = 300000L),
              streaming = true
            )
          yield assertTrue(
            b.getClass.getSimpleName == "AnthropicMessagesBackend",
            b.capabilities.streaming == CapabilityStatus.Supported,
            b.capabilities.tools == CapabilityStatus.Supported
          )
        }
      ),
      suite("RANT-02: anthropic without a key -> notice + mock (as 090)")(
        test(
          "missing key falls back to scripted/mock with one how-to-enable notice"
        ) {
          val plan = Main.preludePlan(Map("RAIDER_PROVIDER" -> "anthropic"))
          val all = plan.sources.mkString("\n")
          assertTrue(
            plan.notices.length == 1,
            plan.notices.head.contains("RAIDER_API_KEY"),
            plan.notices.head.contains("RAIDER_API_KEY_CMD"),
            plan.notices.head.contains("mock"),
            all.contains("ScriptedModelBackend"),
            !all.contains("AnthropicMessagesBackend")
          )
        },
        test("helper configured but producing nothing stays <redacted>") {
          val r = LiveCredentials.resolve(
            "anthropic",
            env = Map(LiveCredentials.EnvHelper -> "secret-helper-cmd-xyz"),
            helper = _ => None
          )
          r match
            case Left(msg) =>
              assertTrue(
                msg.contains("<redacted>"),
                !msg.contains("secret-helper-cmd-xyz")
              )
            case _ => assertTrue(false)
        }
      ),
      suite("RANT-03: neighbor paths unchanged")(
        test("openai live wiring keeps the RAI-090 textual shape") {
          val plan = Main.preludePlan(
            Map("RAIDER_PROVIDER" -> "openai", "RAIDER_API_KEY" -> Secret)
          )
          val all = plan.sources.mkString("\n")
          assertTrue(
            plan.notices.isEmpty,
            all.contains(
              "raider.provider.chat.stream.OpenAIChatStreamingBackend"
            ),
            all.contains("raider.runtime.display.RateLimitRetries"),
            all.contains("echoStream = true")
          )
        },
        test("mock provider stays on the scripted path without notices") {
          val plan = Main.preludePlan(Map("RAIDER_PROVIDER" -> "mock"))
          val all = plan.sources.mkString("\n")
          assertTrue(
            plan.notices.isEmpty,
            all.contains("ScriptedModelBackend"),
            !all.contains("AnthropicMessagesBackend"),
            !all.contains("OpenAIChatStreamingBackend")
          )
        },
        test("secret masking unchanged: mask is the only printable form") {
          val m = LiveCredentials.mask(Secret)
          assertTrue(
            m == "test***",
            m != Secret,
            !Secret.endsWith(m)
          )
        }
      )
    )
