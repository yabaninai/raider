package raider.cli.run

import raider.core.result.{CheckOutcome, ExecutionStatus}
import raider.runtime.display.RateLimitRetries
import zio.ZIO
import zio.json.DecoderOps
import zio.test._
import zio.test.live

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** RAI-071 acceptance (unit slice): `--provider anthropic` parses; the
  * provider→backend factory ([[ProviderBackend]]) builds the native Anthropic
  * Messages backend wrapped in the SAME RateLimitRetries as openai (wire
  * parity); the credential preflight refuses a missing key with a typed RA-CFG
  * BEFORE any spend (ANT-02, hermetic — no network is attempted on any path
  * here).
  *
  * The full tool-round through a local HTTP stand is covered by the
  * provider-anthropic module's own suite (AnthropicMessagesSpec) — the cli
  * module has no stand infrastructure of its own, so that integration slice is
  * honestly DEFERRED here, not faked with a mock.
  */
object AnthropicProviderSpec extends ZIOSpecDefault:

  private def readRunJson(
      workDir: java.nio.file.Path
  ): raider.core.result.RunResult =
    val text = Files.readString(workDir.resolve("run.json"), UTF_8)
    text.fromJson[raider.core.result.RunResult] match
      case Right(r) => r
      case Left(e)  => throw new IllegalStateException(s"bad run.json: $e")

  private def agentArgs(
      out: java.nio.file.Path,
      provider: String,
      baseUrl: String
  ) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--provider",
          provider,
          "--base-url",
          baseUrl
        )
      )
      .fold(e => throw e, identity)

  def spec = suite("anthropic provider (RAI-071)")(
    test("parse: --provider anthropic is accepted") {
      val parsed = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "t",
          "--out",
          "o",
          "--provider",
          "anthropic"
        )
      )
      assertTrue(
        parsed.isRight,
        parsed.fold(_ => false, _.provider == "anthropic")
      )
    },
    test(
      "parse: unknown provider is typed with the full mock|openai|anthropic set"
    ) {
      val parsed = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "t",
          "--out",
          "o",
          "--provider",
          "gemini"
        )
      )
      assertTrue(
        parsed.fold(
          e =>
            e.code == "RA-CFG" &&
              e.detail.contains("(mock|openai|anthropic)"),
          _ => false
        )
      )
    },
    test("parse: --api-key stays rejected for anthropic (never echoed)") {
      val parsed = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "t",
          "--out",
          "o",
          "--provider",
          "anthropic",
          "--api-key",
          "anthropic-test-key"
        )
      )
      assertTrue(
        parsed.fold(
          e =>
            e.code == "RA-CFG" &&
              e.detail.contains("RAIDER_API_KEY") &&
              !e.detail.contains("anthropic-test-key"),
          _ => false
        )
      )
    },
    test("credentials: anthropic is a live provider — preflight applies") {
      assertTrue(
        Credentials.isLive("anthropic"),
        Credentials
          .resolve("anthropic", Map.empty)
          .fold(_.code == "RA-CFG", _ => false),
        Credentials.resolve("anthropic", Map("RAIDER_API_KEY" -> "sk-ant-x")) ==
          Right(Some("sk-ant-x"))
      )
    },
    test(
      "ANT-02: anthropic without a key fails typed BEFORE spend (no output)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("raider-ant-test"))
              .orDie
            args = agentArgs(work, "anthropic", "not-a-url")
            outcome <- HeadlessRunner.run(args, Map.empty) // no env key at all
            runJson = readRunJson(outcome.workDir)
          yield assertTrue(
            outcome.exitCode == 21, // ConfigInvalid: typed preflight refusal
            runJson.status == ExecutionStatus.Failed,
            runJson.output.isEmpty, // no spend, no output produced
            runJson.error.exists(e =>
              e.code == "RA-CFG" &&
                e.detail.contains("missing credentials") &&
                e.detail.contains("RAIDER_API_KEY")
            ),
            runJson.checks.exists(c =>
              c.id == "bundle-preflight" &&
                c.outcome == CheckOutcome.Failed
            )
          )
        }
      }
    },
    test(
      "ANT-02: anthropic WITH an env key clears the credential gate"
    ) {
      // Hermetic (no network): the base URL is invalid, so the anthropic
      // Config.make fails AFTER the credential resolution — the typed error
      // is about the URL, never about missing credentials, proving the
      // backend factory was reached with the resolved key.
      live {
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("raider-ant-test"))
              .orDie
            args = agentArgs(work, "anthropic", "not-a-url")
            outcome <- HeadlessRunner.run(
              args,
              Map("RAIDER_API_KEY" -> "sk-ant-42")
            )
            raw = Files.readString(
              outcome.workDir.resolve("run.json"),
              UTF_8
            )
            runJson = readRunJson(outcome.workDir)
          yield assertTrue(
            outcome.exitCode == 21,
            runJson.error.exists(e =>
              e.detail.contains("baseUrl must be an http(s) URL")
            ),
            !raw.contains("sk-ant-42") // the key never lands in artifacts
          )
        }
      }
    },
    test(
      "factory: anthropic builds the Messages backend behind RateLimitRetries"
    ) {
      for b <- ProviderBackend.make(
          "anthropic",
          "http://127.0.0.1:8081/v1",
          Some("sk-ant-x")
        )
      yield
        val rl = b.asInstanceOf[RateLimitRetries]
        assertTrue(
          b.isInstanceOf[RateLimitRetries],
          rl.underlying
            .isInstanceOf[raider.provider.anthropic.AnthropicMessagesBackend],
          rl.underlying.capabilities.tools == raider.core.CapabilityStatus.Supported
        )
    },
    test("factory: openai parity and mock exemption") {
      for
        o <- ProviderBackend.make(
          "openai",
          "http://127.0.0.1:8081/v1",
          Some("k")
        )
        m <- ProviderBackend.make("mock", "http://127.0.0.1:8081/v1", None)
      yield assertTrue(
        o.isInstanceOf[RateLimitRetries],
        o.asInstanceOf[RateLimitRetries]
          .underlying
          .isInstanceOf[raider.provider.chat.OpenAIChatBackend],
        !m.isInstanceOf[RateLimitRetries], // mock is exempt from the wrapper
        m.capabilities.tools == raider.core.CapabilityStatus.Unsupported
      )
    },
    test("factory: invalid anthropic base-url / empty key are typed RA-CFG") {
      for
        badUrl <- ProviderBackend
          .make("anthropic", "not-a-url", Some("k"))
          .either
        noKey <- ProviderBackend
          .make("anthropic", "http://127.0.0.1:8081/v1", None)
          .either
      yield assertTrue(
        badUrl.fold(
          e =>
            e.code == "RA-CFG" &&
              e.detail.contains("baseUrl must be an http(s) URL"),
          _ => false
        ),
        noKey.fold(
          e =>
            e.code == "RA-CFG" && e.detail.contains("apiKey must be non-empty"),
          _ => false
        )
      )
    }
  ) @@ TestAspect.withLiveClock
  // RAI-178: HeadlessRunner.run's ensurings poll on the ambient clock;

  // live-clock discipline (docs/work/records/RAI-178.md). Assertions unchanged.
