package raider.repl

import zio.test._

/** RAI-108 unit level of the product tabs entry: the mode gate behind `--tabs`.
  * The banner/stream honesty depends on ONE decision — whether the prelude plan
  * wires the scripted/mock backend or a live one — so the spec pins
  * `Main.preludePlan(...).isScripted` across the provider matrix:
  *
  *   - mock/unknown provider → scripted (the fixture tab-stream provider may
  *     run; zero network by construction);
  *   - live provider with a resolvable key → live (NO fixture stream —
  *     synthetic events for a real backend are never allowed);
  *   - live provider without a resolvable key → the honest scripted fallback
  *     WITH a how-to-enable notice (RAI-090 rule, unchanged).
  *
  * Pure planning only: no engine, no network, no process is started here; the
  * PTY behavior of the real entry is MainTabsPtySpec.
  */
object MainTabsModeSpec extends ZIOSpecDefault:

  private val LiveKey = "k" * 24 // fake fixture value, never a real secret

  def spec = suite("Main tabs-entry mode gate (RAI-108)")(
    test("mock provider plans the scripted backend (fixture stream allowed)") {
      val plan = Main.preludePlan(env = Map.empty)
      assertTrue(plan.isScripted) &&
      assertTrue(plan.notices.isEmpty)
    },
    test("resolvable live key plans the live backend (no fixture stream)") {
      val plan = Main.preludePlan(
        env = Map("RAIDER_PROVIDER" -> "openai", "RAIDER_API_KEY" -> LiveKey)
      )
      assertTrue(!plan.isScripted) &&
      assertTrue(plan.notices.isEmpty)
    },
    test("anthropic live key also plans live (no fixture stream)") {
      val plan = Main.preludePlan(
        env = Map("RAIDER_PROVIDER" -> "anthropic", "RAIDER_API_KEY" -> LiveKey)
      )
      assertTrue(!plan.isScripted)
    },
    test(
      "live provider without a key degrades to scripted (fixture stream + notice)"
    ) {
      val plan = Main.preludePlan(env = Map("RAIDER_PROVIDER" -> "openai"))
      assertTrue(plan.isScripted) &&
      assertTrue(plan.notices.nonEmpty)
    }
  )

end MainTabsModeSpec
