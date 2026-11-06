package raider.compat.opencode

import zio.Scope
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.{Files, Path}

/** End-to-end spec for the importer over the vendored corpus: registry-only
  * golden document, JSON config scenarios (global permission merging,
  * legacy/native conflict, small_model, default_agent), markdown scenarios,
  * home expansion, and disabled removal.
  */
object ImporterGoldenSpec extends ZIOSpecDefault:

  private def repoRoot: Path = Path.of(sys.env.getOrElse("RAIDER_REPO_ROOT", "."))

  private def corpusRoot: Path = repoRoot.resolve("vendor/opencode-profiles")

  private def parse(s: String): Json =
    Json.decoder.decodeJson(s) match
      case Right(v) => v
      case Left(e)  => sys.error(s"test fixture is not JSON: $s ($e)")

  private def importAll(documents: Vector[Importer.Document], home: Option[String] = None) =
    Importer.importDocument(corpusRoot, Importer.Input(documents, home))

  private def byId(profiles: Vector[Profile]) = profiles.map(p => p.id -> p).toMap

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Importer")(
    test("registry-only import is deterministic and matches the reviewed golden fixture") {
      importAll(Vector.empty).flatMap { doc =>
        val golden = Files.readString(repoRoot.resolve("contracts/compat/opencode/expected/registry-golden.json"))
        val actual = Model.encodePretty(doc).stripTrailing
        assertTrue(doc.schemaVersion == 1) &&
        assertTrue(doc.diagnostics.isEmpty) &&
        assertTrue(doc.defaultAgentId == "build") &&
        assertTrue(doc.corpus.commit == "d7d85817160120fd59fb27ee6ff354f45d18b994") &&
        assertTrue(doc.profiles.length == 7) &&
        assertTrue(actual == golden.stripTrailing)
      }
    },
    test("legacy JSON config: global tools+permission merge before per-agent rules") {
      val cfg = parse("""{
        "agent": {
          "build": {"permission": {"edit": "ask"}},
          "reviewer": {"description": "reviews", "mode": "subagent", "model": "anth/x", "prompt": "be terse"}
        },
        "permission": {"edit": "allow"},
        "tools": {"write": false}
      }""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg))).map { doc =>
        val m = byId(doc.profiles)
        val build = m("build")
        assertTrue(doc.profiles.last.id == "reviewer") &&
        assertTrue(doc.profiles.count(_.id == "reviewer") == 1) &&
        assertTrue(m("reviewer").mode == "subagent") &&
        assertTrue(m("reviewer").system.contains("be terse")) &&
        assertTrue(m("reviewer").model.contains(ModelRef("anth", "x", None))) &&
        // per-agent rules land after baseline + global rules; evaluate is last-match-wins
        assertTrue(
          build.permissions.takeRight(3) == Vector(
            PermissionRule("edit", "*", "deny"), // tools.write=false, renamed write->edit
            PermissionRule("edit", "*", "allow"), // permission.edit=allow
            PermissionRule("edit", "*", "ask"),   // agent-level edit=ask
          )
        ) &&
        assertTrue(Evaluate.effect("edit", "src/x", build.permissions) == "ask") &&
        // The v1 "write" key was renamed to "edit", so the v1 action name no
        // longer matches any migrated rule and only the baseline allows it.
        assertTrue(Evaluate.effect("write", "src/x", build.permissions) == "allow")
      }
    },
    test("native JSON config agents coexist with legacy and win conflicts") {
      val cfg = parse("""{
        "agent": {"duo": {"description": "legacy", "mode": "subagent", "prompt": "old"}},
        "agents": {"duo": {"description": "native", "mode": "subagent", "system": "new"}},
        "permissions": [{"action": "read", "resource": "*.env", "effect": "ask"}]
      }""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg))).map { doc =>
        val m = byId(doc.profiles)
        assertTrue(doc.diagnostics.exists(d => d.kind == "conflict" && d.path.contains("duo"))) &&
        assertTrue(m("duo").description.contains("native")) &&
        assertTrue(m("duo").origin == "native-v2") &&
        assertTrue(m("duo").system.contains("new")) &&
        assertTrue(m("duo").permissions.lastOption.contains(PermissionRule("read", "*.env", "ask")))
      }
    },
    test("legacy mode map forces primary and small_model overlays title") {
      val cfg = parse("""{
        "mode": {"plan": {"description": "plans", "prompt": "plan well"}},
        "small_model": "anth/haiku"
      }""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg))).map { doc =>
        val m = byId(doc.profiles)
        assertTrue(m("plan").mode == "primary") &&
        assertTrue(m("plan").system.contains("plan well")) &&
        assertTrue(m("title").model.contains(ModelRef("anth", "haiku", None)))
      }
    },
    test("default_agent resolves like selectedDefault and orders the list") {
      val cfg = parse("""{"default_agent": "plan"}""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg))).map { doc =>
        assertTrue(doc.defaultAgentId == "plan") &&
        assertTrue(doc.profiles.head.id == "plan") &&
        assertTrue(doc.profiles.count(_.id == "plan") == 1)
      }
    },
    test("hidden default_agent falls back to build") {
      val cfg = parse("""{"default_agent": "title"}""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg))).map { doc =>
        assertTrue(doc.defaultAgentId == "build") && assertTrue(doc.profiles.head.id == "build")
      }
    },
    test("disabled removes an existing agent") {
      val cfg = parse("""{"agents": {"title": {"disabled": true}}}""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg))).map { doc =>
        assertTrue(!doc.profiles.exists(_.id == "title")) &&
        assertTrue(doc.diagnostics.exists(d => d.kind == "unsupported" && d.path.contains("title")))
      }
    },
    test("markdown documents contribute agents after the registry in path order") {
      val docs = Vector(
        Importer.Document.Markdown("agent/planner.md", "---\ndescription: plans\nmode: primary\nmodel: openai/gpt-4o\nvariant: mini\n---\nPlan things."),
        Importer.Document.Markdown("mode/writer.md", "---\ndescription: writes\n---\nWrite things."),
      )
      importAll(docs).map { doc =>
        val m = byId(doc.profiles)
        assertTrue(doc.profiles.map(_.id).takeRight(2) == Vector("planner", "writer")) &&
        assertTrue(m("planner").model.contains(ModelRef("openai", "gpt-4o", Some("mini")))) &&
        assertTrue(m("writer").mode == "primary") &&
        assertTrue(m("writer").source == "markdown")
      }
    },
    test("markdown and JSON documents interact: later document overrides silently for markdown") {
      val docs = Vector(
        Importer.Document.Markdown("agent/researcher.md", "---\ndescription: v1\nmode: subagent\n---\nBody one."),
        Importer.Document.Json("opencode.json", parse("""{"agents": {"researcher": {"description": "v2", "mode": "subagent", "system": "Body two."}}}""")),
      )
      importAll(docs).map { doc =>
        val m = byId(doc.profiles)
        assertTrue(doc.profiles.count(_.id == "researcher") == 1) &&
        assertTrue(m("researcher").description.contains("v2")) &&
        assertTrue(m("researcher").system.contains("Body two."))
      }
    },
    test("home expansion applies only to path actions") {
      val cfg = parse("""{
        "agents": {"ed": {"permissions": [
          {"action": "edit", "resource": "~/.opencode/plan/*", "effect": "allow"},
          {"action": "shell", "resource": "~/bin/*", "effect": "allow"}
        ]}}
      }""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg)), home = Some("/Users/dev")).map { doc =>
        val ed = byId(doc.profiles)("ed")
        assertTrue(ed.permissions.contains(PermissionRule("edit", "/Users/dev/.opencode/plan/*", "allow"))) &&
        assertTrue(ed.permissions.contains(PermissionRule("shell", "~/bin/*", "allow")))
      }
    },
    test("no home keeps tilde resources literal") {
      val cfg = parse("""{
        "agents": {"ed": {"permissions": [
          {"action": "edit", "resource": "~/.opencode/plan/*", "effect": "allow"}
        ]}}
      }""")
      importAll(Vector(Importer.Document.Json("opencode.json", cfg)), home = None).map { doc =>
        assertTrue(byId(doc.profiles)("ed").permissions.contains(PermissionRule("edit", "~/.opencode/plan/*", "allow")))
      }
    },
    test("malformed JSON document fails the import") {
      importAll(Vector(Importer.Document.Json("x.json", parse("""42""")))).fold(
        err => assertTrue(err.nonEmpty),
        _ => assertTrue(false) // malformed config was accepted,
      )
    },
    test("every profile in the output satisfies the contract schema constraints") {
      importAll(Vector.empty).map { doc =>
        val modesOk = doc.profiles.forall(p => Vector("subagent", "primary", "all").contains(p.mode))
        val effectsOk = doc.profiles.forall(_.permissions.forall(r => Vector("allow", "deny", "ask").contains(r.effect)))
        val sourcesOk = doc.profiles.forall(p => Vector("registry", "markdown", "json-config").contains(p.source))
        assertTrue(modesOk) && assertTrue(effectsOk) && assertTrue(sourcesOk)
      }
    },
  )

