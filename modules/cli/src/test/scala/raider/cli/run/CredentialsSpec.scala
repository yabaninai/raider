package raider.cli.run

import raider.core.RaiderError
import zio.test._

/** RAI-064 credential-resolution acceptance (SETUP-01/02/03):
  *   - credentials come ONLY from the environment (`RAIDER_API_KEY`) or a
  *     helper command (`RAIDER_API_KEY_CMD`) — never from argv;
  *   - a live provider without a key is a typed `Configuration` preflight
  *     failure (before any spend);
  *   - the key is masked everywhere it could be printed (first 4 + `***`).
  *
  * The helper tests run a real local `sh` (no network, no spend) to prove the
  * "first line of stdout is the key" contract and that a helper that prints
  * nothing is a typed error that never leaks the command.
  */
object CredentialsSpec extends ZIOSpecDefault:

  private val emptyEnv: Map[String, String] = Map.empty

  private def keyOf(r: Either[RaiderError, Option[String]]): Option[String] =
    r.fold(_ => None, identity)

  def spec = suite("credentials (RAI-064)")(
    test("SETUP-01: mock provider needs no key (env not even read)") {
      val r = Credentials.resolve("mock", emptyEnv)
      assertTrue(r.isRight, keyOf(r) == None)
    },
    test("SETUP-01: live provider resolves the key from RAIDER_API_KEY") {
      val env = Map("RAIDER_API_KEY" -> "env-test-key-12345")
      val r = Credentials.resolve("openai", env)
      assertTrue(r.isRight, keyOf(r) == Some("env-test-key-12345"))
    },
    test(
      "SETUP-02: live provider without a key is a typed Configuration error"
    ) {
      val r = Credentials.resolve("openai", emptyEnv)
      assertTrue(
        r.isLeft,
        r.fold(
          e =>
            e.isInstanceOf[RaiderError.Configuration] &&
              e.detail.contains("RAIDER_API_KEY") &&
              e.code == "RA-CFG",
          _ => false
        )
      )
    },
    test("SETUP-01: helper command first stdout line becomes the key") {
      // multi-line stdout: only the FIRST line is the key
      val env =
        Map("RAIDER_API_KEY_CMD" -> "printf 'helper-key-42\\nsecond line\\n'")
      val r = Credentials.resolve("openai", env)
      assertTrue(r.isRight, keyOf(r) == Some("helper-key-42"))
    },
    test("RAIDER_API_KEY takes precedence over the helper") {
      val env = Map(
        "RAIDER_API_KEY" -> "env-wins",
        "RAIDER_API_KEY_CMD" -> "echo helper-loses"
      )
      val r = Credentials.resolve("openai", env)
      assertTrue(r.isRight, keyOf(r) == Some("env-wins"))
    },
    test("blank env key is treated as absent (falls through to the helper)") {
      val env = Map(
        "RAIDER_API_KEY" -> "   ",
        "RAIDER_API_KEY_CMD" -> "echo from-helper"
      )
      val r = Credentials.resolve("openai", env)
      assertTrue(r.isRight, keyOf(r) == Some("from-helper"))
    },
    test("SETUP-02: a helper that prints nothing is a typed error, no leak") {
      val env = Map("RAIDER_API_KEY_CMD" -> "true") // exits 0, no stdout
      val r = Credentials.resolve("openai", env)
      assertTrue(
        r.isLeft,
        r.fold(
          e =>
            e.isInstanceOf[RaiderError.Configuration] &&
              // the command text must NOT appear in the error
              !e.detail.contains("true") &&
              e.detail.contains("<redacted>"),
          _ => false
        )
      )
    },
    test("mask: a long secret becomes first-4 + ***") {
      assertTrue(Credentials.mask("sk-test123") == "sk-t***")
    },
    test("mask: empty and too-short secrets collapse to <redacted>") {
      assertTrue(
        Credentials.mask("") == "<redacted>",
        Credentials.mask("abc") == "<redacted>",
        Credentials.mask("abcd") == "<redacted>"
      )
    }
  )
