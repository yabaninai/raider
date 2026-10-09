package raider.repl

import zio.Scope
import zio.test.*

/** RAI-090 fixtures: REPL live env-key resolution (RKEY-01..03).
  *
  * The core is the PURE `LiveCredentials.resolveKey(provider, envValue,
  * helperOutput)` — both inputs are injectable, so no test spawns a helper
  * process by accident; the `sh -c` first-line extraction itself is covered by
  * two explicit helper-runner cases (one injected fake, one real `sh -c
  * printf`).
  *
  *   - RKEY-01: env key present -> Some(key); the exact live-backend
  *     construction Main performs with it succeeds.
  *   - RKEY-02: helper output -> Some(key); env key wins over the helper.
  *   - RKEY-03: both empty -> Left with a how-to-enable message; the REPL plan
  *     falls back to the scripted/mock sources with a notice (never a crash,
  *     never a fake live wire), and no message ever contains the secret or the
  *     helper command text (mask/`<redacted>` only).
  *
  * RAI-098 supersession: `anthropic` + a resolved key NO LONGER falls back to
  * the scripted/mock sources with a masked notice — it wires the LIVE
  * AnthropicMessagesBackend. That behavior and its fixtures live in
  * AnthropicLiveWiringSpec (RANT-01); the no-key anthropic fallback itself is
  * unchanged (RANT-02) and stays asserted here.
  */
object LiveCredentialsSpec extends ZIOSpecDefault:

  private val OpenAiEnvKey = "test-sec-val"
  private val AnthropicEnvKey = "test-sec-val"

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("LiveCredentials (RAI-090)")(
      suite("RKEY-01: env key present -> live")(
        test("resolveKey(openai, env set, no helper) == Some(env key)") {
          val r = LiveCredentials.resolveKey("openai", Some(OpenAiEnvKey), None)
          assertTrue(r == Right(Some(OpenAiEnvKey)))
        },
        test("resolveKey(anthropic, env set, no helper) == Some(env key)") {
          val r =
            LiveCredentials.resolveKey("anthropic", Some(AnthropicEnvKey), None)
          assertTrue(r == Right(Some(AnthropicEnvKey)))
        },
        test("blank env value is treated as absent") {
          val r = LiveCredentials.resolveKey("openai", Some("   "), None)
          assertTrue(r.isLeft)
        },
        test("surrounding whitespace is trimmed from the env key") {
          val r =
            LiveCredentials.resolveKey(
              "openai",
              Some("  test-key-42\n"),
              None
            )
          assertTrue(r == Right(Some("test-key-42")))
        },
        test("resolved key is accepted by the live backend config") {
          // the exact construction Main's live prelude performs with the key
          val cfg = raider.provider.chat.OpenAIChatBackend.Config
            .make("http://127.0.0.1:8081/v1", OpenAiEnvKey, 300000L)
          assertTrue(cfg.isRight)
        },
        test(
          "prelude plan with openai + env key wires the LIVE streaming backend"
        ) {
          val plan = Main.preludePlan(
            Map("RAIDER_PROVIDER" -> "openai", "RAIDER_API_KEY" -> OpenAiEnvKey)
          )
          val all = plan.sources.mkString("\n")
          assertTrue(
            plan.notices.isEmpty,
            all.contains(s"val __apiKey = \"$OpenAiEnvKey\""),
            all.contains(
              "raider.provider.chat.stream.OpenAIChatStreamingBackend"
            ),
            !all.contains("no-key")
          )
        }
      ),
      suite("RKEY-02: helper output -> live")(
        test("resolveKey uses the helper first line when env is absent") {
          val r =
            LiveCredentials.resolveKey("openai", None, Some("helper-key-42"))
          assertTrue(r == Right(Some("helper-key-42")))
        },
        test("helper output is trimmed") {
          val r =
            LiveCredentials.resolveKey(
              "openai",
              None,
              Some("  helper-key-42  ")
            )
          assertTrue(r == Right(Some("helper-key-42")))
        },
        test("env key takes precedence over the helper output") {
          val r = LiveCredentials.resolveKey(
            "openai",
            Some("env-wins-123456"),
            Some("helper-loses-1")
          )
          assertTrue(r == Right(Some("env-wins-123456")))
        },
        test(
          "resolve runs the configured helper via the injected runner"
        ) {
          val r = LiveCredentials.resolve(
            "openai",
            env = Map(LiveCredentials.EnvHelper -> "<helper-ref>"),
            helper = _ => Some("helper-key-42")
          )
          assertTrue(r == Right(Some("helper-key-42")))
        },
        test(
          "resolve runs a REAL sh -c helper and takes the first stdout line"
        ) {
          val r = LiveCredentials.resolve(
            "openai",
            env = Map(
              LiveCredentials.EnvHelper ->
                "printf 'helper-key-42\\nsecond line\\n'"
            )
          )
          assertTrue(r == Right(Some("helper-key-42")))
        }
      ),
      suite("RKEY-03: no key -> message + mock, never a crash")(
        test("resolveKey with both inputs empty -> Left message") {
          val r = LiveCredentials.resolveKey("openai", None, None)
          r match
            case Left(msg) =>
              assertTrue(
                msg.contains("RAIDER_API_KEY"),
                msg.contains("RAIDER_API_KEY_CMD"),
                msg.contains("mock"),
                !msg.contains("=") // no key material anywhere
              )
            case _ => assertTrue(false)
        },
        test("blank env AND blank helper output -> Left message") {
          val r = LiveCredentials.resolveKey("openai", Some("  "), Some("  "))
          assertTrue(r.isLeft)
        },
        test(
          "helper configured but produced nothing -> <redacted>, no cmd text"
        ) {
          val r = LiveCredentials.resolve(
            "openai",
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
        },
        test("REAL helper that outputs nothing -> Left message") {
          val r = LiveCredentials.resolve(
            "openai",
            env = Map(LiveCredentials.EnvHelper -> "true") // exit 0, no stdout
          )
          assertTrue(r.isLeft)
        },
        test("prelude plan with openai + missing key falls back to mock") {
          val plan =
            Main.preludePlan(Map("RAIDER_PROVIDER" -> "openai"))
          val all = plan.sources.mkString("\n")
          assertTrue(
            plan.notices.length == 1,
            plan.notices.head.contains("RAIDER_API_KEY"),
            plan.notices.head.contains("RAIDER_API_KEY_CMD"),
            all.contains("ScriptedModelBackend"),
            all.contains("[scripted-fixture] mock answer"),
            !all.contains("OpenAIChatStreamingBackend"),
            !all.contains("no-key")
          )
        },
        test("anthropic + missing key -> notice + mock (no crash)") {
          val plan =
            Main.preludePlan(Map("RAIDER_PROVIDER" -> "anthropic"))
          assertTrue(
            plan.notices.length == 1,
            plan.sources.exists(_.contains("ScriptedModelBackend"))
          )
        },
        test("mock provider needs no key and prints no notice") {
          val r = LiveCredentials.resolveKey("mock", None, None)
          val plan = Main.preludePlan(Map("RAIDER_PROVIDER" -> "mock"))
          val all = plan.sources.mkString("\n")
          assertTrue(
            r == Right(None),
            plan.notices.isEmpty,
            all.contains("ScriptedModelBackend"),
            !all.contains("no-key")
          )
        }
      ),
      suite("secret masking")(
        test("mask keeps first 4 chars + ***") {
          assertTrue(LiveCredentials.mask("test-sec-val") == "test***")
        },
        test("short/empty secrets collapse to <redacted>") {
          assertTrue(
            LiveCredentials.mask("") == "<redacted>",
            LiveCredentials.mask("abc") == "<redacted>",
            LiveCredentials.mask("abcd") == "<redacted>"
          )
        },
        test("mask output never contains the whole secret") {
          val secret = "test-sec-val"
          val m = LiveCredentials.mask(secret)
          assertTrue(m != secret, !secret.endsWith(m))
        }
      ),
      suite("prelude key escaping")(
        test(
          "quotes/backslashes in a key cannot break the generated prelude source"
        ) {
          val plan = Main.preludePlan(
            Map("RAIDER_PROVIDER" -> "openai", "RAIDER_API_KEY" -> "a\"b\\c-1")
          )
          val expected = "val __apiKey = \"a\\\"b\\\\c-1\""
          val all = plan.sources.mkString("\n")
          assertTrue(
            all.contains(expected),
            !all.contains("a\"b\\c-1\"")
          )
        }
      )
    )

end LiveCredentialsSpec
