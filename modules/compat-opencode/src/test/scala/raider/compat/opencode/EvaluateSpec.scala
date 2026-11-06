package raider.compat.opencode

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Faithfulness spec for the pinned Permission.evaluate/resolve semantics
  * (vendor/opencode-profiles/upstream/core/permission.ts): last matching
  * rule wins, no match asks; deny on any resource wins first; otherwise any
  * ask yields ask; otherwise allow.
  */
object EvaluateSpec extends ZIOSpecDefault:

  private def rule(a: String, r: String, e: String) = PermissionRule(a, r, e)

  private val buildRules = Vector(
    rule("*", "*", "allow"),
    rule("read", "*.env", "ask"),
    rule("read", "*.env.*", "ask"),
    rule("read", "*.env.example", "allow"),
    rule("question", "*", "allow"),
  )

  private val planRules = buildRules ++ Vector(
    rule("question", "*", "allow"),
    rule("edit", "*", "deny"),
    rule("edit", "~/.opencode/plan/*", "allow"),
  )

  private val generalRules = buildRules ++ Vector(
    rule("question", "*", "deny"),
    rule("subagent", "*", "deny"),
  )

  private val exploreRules = buildRules ++ Vector(
    rule("*", "*", "deny"),
    rule("shell", "*", "allow"),
    rule("grep", "*", "allow"),
    rule("glob", "*", "allow"),
    rule("webfetch", "*", "allow"),
    rule("websearch", "*", "allow"),
    rule("read", "*", "allow"),
    rule("read", "*.env", "ask"),
    rule("read", "*.env.*", "ask"),
    rule("read", "*.env.example", "allow"),
    rule("subagent", "*", "deny"),
    rule("external_directory", "*", "allow"),
  )

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Evaluate")(
    test("last matching rule wins") {
      assertTrue(Evaluate.effect("read", ".env.example", buildRules) == "allow") &&
      assertTrue(Evaluate.effect("read", ".env", buildRules) == "ask") &&
      assertTrue(Evaluate.effect("read", ".env.local", buildRules) == "ask")
    },
    test("no match defaults to ask") {
      assertTrue(Evaluate.effect("webfetch", "https://x", Vector(rule("edit", "*", "deny"))) == "ask") &&
      assertTrue(Evaluate.effect("edit", "x", Vector.empty) == "ask")
    },
    test("built-in build profile decisions") {
      assertTrue(Evaluate.effect("edit", "src/Main.scala", buildRules) == "allow") &&
      assertTrue(Evaluate.effect("question", "anything", buildRules) == "allow") &&
      assertTrue(Evaluate.effect("read", "secrets.env", buildRules) == "ask")
    },
    test("built-in plan profile decisions") {
      assertTrue(Evaluate.effect("edit", "src/Main.scala", planRules) == "deny") &&
      assertTrue(Evaluate.effect("edit", "~/.opencode/plan/todo.md", planRules) == "allow") &&
      assertTrue(Evaluate.effect("edit", "~/.opencode/planx/todo.md", planRules) == "deny")
    },
    test("built-in general profile decisions") {
      assertTrue(Evaluate.effect("question", "prompt", generalRules) == "deny") &&
      assertTrue(Evaluate.effect("subagent", "worker", generalRules) == "deny") &&
      assertTrue(Evaluate.effect("edit", "src/Main.scala", generalRules) == "allow")
    },
    test("built-in explore profile decisions") {
      assertTrue(Evaluate.effect("read", "src/x.ts", exploreRules) == "allow") &&
      assertTrue(Evaluate.effect("read", ".env", exploreRules) == "ask") &&
      assertTrue(Evaluate.effect("edit", "src/x.ts", exploreRules) == "deny") &&
      assertTrue(Evaluate.effect("shell", "ls", exploreRules) == "allow") &&
      assertTrue(Evaluate.effect("external_directory", "../other", exploreRules) == "allow")
    },
    test("resolve: deny on any resource wins first") {
      assertTrue(Evaluate.resolve("edit", Vector("/a", "/b"), Vector(rule("edit", "*", "deny"), rule("edit", "/b", "allow"))) == "deny") &&
      assertTrue(Evaluate.resolve("edit", Vector("/b"), Vector(rule("edit", "*", "deny"), rule("edit", "/b", "allow"))) == "allow")
    },
    test("resolve: any ask yields ask") {
      assertTrue(Evaluate.resolve("edit", Vector("/a", "/b"), Vector(rule("edit", "*", "ask"), rule("edit", "/b", "allow"))) == "ask") &&
      assertTrue(Evaluate.resolve("edit", Vector("/b"), Vector(rule("edit", "*", "ask"), rule("edit", "/b", "allow"))) == "allow")
    },
    test("resolve: all allow yields allow") {
      assertTrue(Evaluate.resolve("edit", Vector("/a", "/b"), Vector(rule("edit", "*", "allow"))) == "allow")
    },
  )

