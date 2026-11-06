package raider.compat.opencode

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Faithfulness spec for markdown agent profiles and the frontmatter subset
  * used by pinned ConfigMarkdown (vendor/opencode-profiles/upstream/core/
  * config-markdown.ts and config-plugin-agent.ts decode).
  */
object MarkdownProfilesSpec extends ZIOSpecDefault:

  private def agent(path: String, content: String) =
    MarkdownProfiles.MarkdownFile(path, content)

  private def decode(path: String, content: String) = MarkdownProfiles.decodeAgent(agent(path, content))

  private val reviewLegacy =
    """---
      |description: Code review without edits
      |mode: subagent
      |permission:
      |  edit: deny
      |  bash:
      |    "*": ask
      |    "git diff": allow
      |    "git log*": allow
      |    "grep *": allow
      |  webfetch: deny
      |---
      |
      |Only analyze code and suggest changes.""".stripMargin

  private val docWriterLegacy =
    """---
      |description: Writes technical documentation
      |mode: subagent
      |bash: deny
      |---
      |
      |You write documentation.""".stripMargin

  private val securityAuditorLegacy =
    """---
      |description: Security audit agent
      |mode: subagent
      |edit: deny
      |---
      |
      |You audit security.""".stripMargin

  private val nativeWithPermissions =
    """---
      |description: Native permissions agent
      |mode: all
      |permissions:
      |  - {"action": "read", "resource": "*.env", "effect": "ask"}
      |  - {"action": "edit", "resource": "*.lock", "effect": "deny"}
      |---
      |
      |Native body text.""".stripMargin

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("MarkdownProfiles")(
    suite("frontmatter parser")(
      test("parses scalars, booleans, numbers") {
        val p = Frontmatter.parse("---\ndescription: hi\nmode: subagent\nhidden: false\ntemperature: 0.1\n---\nBody")
        assertTrue(p.body == "Body") &&
        assertTrue(Frontmatter.str(p.fields, "description").contains("hi")) &&
        assertTrue(Frontmatter.bool(p.fields, "hidden").contains(false)) &&
        assertTrue(Frontmatter.num(p.fields, "temperature").contains("0.1")) &&
        assertTrue(p.issues.isEmpty)
      },
      test("keeps unquoted colons in scalar values (sanitize fallback)") {
        val p = Frontmatter.parse("---\ndescription: uses colons: a lot\n---\nBody")
        assertTrue(Frontmatter.str(p.fields, "description").contains("uses colons: a lot")) &&
        assertTrue(p.issues.isEmpty)
      },
      test("parses nested maps preserving key order") {
        val p = Frontmatter.parse("---\npermission:\n  edit: deny\n  bash:\n    \"*\": ask\n    \"git diff\": allow\n---\nB")
        val perm = Frontmatter.obj(p.fields, "permission")
        assertTrue(perm.map(Frontmatter.keys).getOrElse(Vector.empty) == Vector("edit", "bash")) &&
        assertTrue(perm.flatMap(Frontmatter.str(_, "edit")).contains("deny")) &&
        assertTrue(perm.flatMap(Frontmatter.obj(_, "bash")).flatMap(Frontmatter.str(_, "git diff")).contains("allow"))
      },
      test("strips quotes and skips comments and blank lines") {
        val p = Frontmatter.parse("---\n# a comment\n\ndescription: \"quoted\"\nmode: 'single'\n---\nB")
        assertTrue(Frontmatter.str(p.fields, "description").contains("quoted")) &&
        assertTrue(Frontmatter.str(p.fields, "mode").contains("single")) &&
        assertTrue(p.issues.isEmpty)
      },
      test("block scalars capture multiline values") {
        val p = Frontmatter.parse("---\nsystem: |\n  line one\n  line two\n---\nB")
        assertTrue(Frontmatter.str(p.fields, "system").contains("line one\nline two"))
      },
      test("missing frontmatter means empty fields and full body") {
        val p = Frontmatter.parse("Just text.\nSecond line.")
        assertTrue(Frontmatter.keys(p.fields).isEmpty) && assertTrue(p.body == "Just text.\nSecond line.")
      },
      test("unterminated frontmatter reports an issue") {
        val p = Frontmatter.parse("---\ndescription: never closed\n")
        assertTrue(p.issues.nonEmpty)
      },
    ),
    suite("legacy V1 markdown agents")(
      test("review.md migrates nested permission maps with action renames") {
        val d = decode("agent/review.md", reviewLegacy)
        val p = d.profile
        assertTrue(d.diagnostics.isEmpty) &&
        assertTrue(p.isDefined) &&
        assertTrue(p.map(_.id).contains("review")) &&
        assertTrue(p.map(_.origin).contains("legacy-v1")) &&
        assertTrue(p.map(_.mode).contains("subagent")) &&
        assertTrue(
          p.map(_.permissions) == Some(
            Vector(
              PermissionRule("edit", "*", "deny"),
              PermissionRule("shell", "*", "ask"),
              PermissionRule("shell", "git diff", "allow"),
              PermissionRule("shell", "git log*", "allow"),
              PermissionRule("shell", "grep *", "allow"),
              PermissionRule("webfetch", "*", "deny"),
            )
          )
        ) &&
        assertTrue(p.flatMap(_.system).contains("Only analyze code and suggest changes."))
      },
      test("doc-writer.md: unknown bash key lands in request.body, permissions stay empty") {
        val p = decode("agent/doc-writer.md", docWriterLegacy).profile
        assertTrue(p.map(_.permissions).contains(Vector.empty)) &&
        assertTrue(p.flatMap(_.request).flatMap(_.body).exists(b => Frontmatter.keys(b).contains("bash")))
      },
      test("security-auditor.md: unknown edit key lands in request.body, permissions stay empty") {
        val p = decode("agent/security-auditor.md", securityAuditorLegacy).profile
        assertTrue(p.map(_.permissions).contains(Vector.empty)) &&
        assertTrue(p.flatMap(_.request).flatMap(_.body).exists(b => Frontmatter.keys(b).contains("edit")))
      },
      test("legacy migration: temperature into request.body, theme color fallback, joined model dropped by legacy modelSelection") {
        val src =
          """---
            |description: m
            |mode: subagent
            |model: openai/gpt-4o
            |variant: mini
            |temperature: 0.2
            |color: accent
            |---
            |Body""".stripMargin
        val p = decode("agent/m.md", src).profile
        // Upstream quirk pinned here: the decode joins model+variant into
        // "openai/gpt-4o#mini", but the legacy migrateAgent modelSelection
        // regex rejects strings containing '#', so the model is dropped.
        assertTrue(p.flatMap(_.model).isEmpty) &&
        assertTrue(p.flatMap(_.request).flatMap(_.body).isDefined) &&
        assertTrue(p.flatMap(_.color).contains("#aaaaaa"))
      },
      test("migrates steps alias maxSteps") {
        val src =
          """---
            |description: m
            |maxSteps: 7
            |---
            |Body""".stripMargin
        val p = decode("agent/m2.md", src).profile
        assertTrue(p.flatMap(_.steps).contains(7))
      },
      test("migrates disable into removal") {
        val src =
          """---
            |description: m
            |disable: true
            |---
            |Body""".stripMargin
        val d = decode("agent/m2b.md", src)
        assertTrue(d.profile.isEmpty) && assertTrue(d.diagnostics.nonEmpty)
      },
      test("unsupported keys migrate into request.body") {
        val src =
          """---
            |description: m
            |title: Not a key
            |---
            |Body""".stripMargin
        val p = decode("agent/m3.md", src).profile
        assertTrue(p.isDefined) &&
        assertTrue(p.flatMap(_.request).flatMap(_.body).exists(b => Frontmatter.keys(b).contains("title")))
      },
      test("malformed permission structure skips the agent with a diagnostic") {
        val src =
          """---
            |description: m
            |permission:
            |  edit: maybe
            |---
            |Body""".stripMargin
        val d = decode("agent/m4.md", src)
        assertTrue(d.profile.isEmpty) && assertTrue(d.diagnostics.nonEmpty)
      },
    ),
    suite("native V2 markdown agents")(
      test("parses permissions array, system body, mode") {
        val p = decode("agent/native.md", nativeWithPermissions).profile
        assertTrue(p.isDefined) &&
        assertTrue(p.map(_.origin).contains("native-v2")) &&
        assertTrue(p.map(_.mode).contains("all")) &&
        assertTrue(
          p.map(_.permissions).contains(
            Vector(
              PermissionRule("read", "*.env", "ask"),
              PermissionRule("edit", "*.lock", "deny"),
            )
          )
        ) &&
        assertTrue(p.flatMap(_.system).contains("Native body text.")) &&
        assertTrue(p.map(_.unsupportedFields).contains(Vector.empty))
      },
      test("reports unsupported keys when native keys are present") {
        val src =
          """---
            |description: n
            |title: Not a key
            |permissions:
            |  - {"action": "read", "resource": "*", "effect": "allow"}
            |---
            |Body""".stripMargin
        val d = decode("agent/n.md", src)
        assertTrue(d.profile.isDefined) &&
        assertTrue(d.profile.map(_.permissions).contains(Vector(PermissionRule("read", "*", "allow")))) &&
        assertTrue(d.profile.map(_.unsupportedFields).contains(Vector("title"))) &&
        assertTrue(d.diagnostics.exists(d => d.kind == "unsupported" && d.path.contains("title")))
      },
      test("invalid steps skip the agent with an invalid diagnostic") {
        val src =
          """---
            |description: n
            |steps: 0
            |---
            |Body""".stripMargin
        val d = decode("agent/n2.md", src)
        assertTrue(d.profile.isEmpty) && assertTrue(d.diagnostics.exists(_.kind == "invalid"))
      },
      test("disabled true removes the agent with a diagnostic") {
        val src =
          """---
            |description: n
            |disabled: true
            |---
            |Body""".stripMargin
        val d = decode("agent/n3.md", src)
        assertTrue(d.profile.isEmpty) && assertTrue(d.diagnostics.nonEmpty)
      },
      test("broken frontmatter skips the agent with diagnostics") {
        val d = decode("agent/broken.md", "---\ndescription: never closed\n")
        assertTrue(d.profile.isEmpty) && assertTrue(d.diagnostics.nonEmpty)
      },
    ),
    suite("mode files")(
      test("mode directory forces mode primary") {
        val d = MarkdownProfiles.decodeMode(
          agent("mode/planner.md", "---\ndescription: plans\nmode: subagent\n---\nPlan.")
        )
        assertTrue(d.profile.map(_.mode).contains("primary")) &&
        assertTrue(d.profile.map(_.id).contains("planner"))
      },
    ),
    suite("ids")(
      test("agent ids come from the relative path without directory prefix or extension") {
        assertTrue(MarkdownProfiles.idFromPath("agent/research/deep.md").contains("research/deep")) &&
        assertTrue(MarkdownProfiles.idFromPath("agents/review.md").contains("review")) &&
        assertTrue(MarkdownProfiles.idFromPath("mode/planner.md").contains("planner")) &&
        assertTrue(MarkdownProfiles.idFromPath("tools/x.md").isEmpty)
      },
    ),
  )

