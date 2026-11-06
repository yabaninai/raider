package raider.compat.opencode

import zio.*
import zio.Scope
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.Path

/** Spec for loading the vendored OpenCode profile corpus: manifest SHA-256
  * verification, extracted registry defaults, and default-agent resolution
  * matching pinned agent.ts selectedDefault semantics.
  */
object RegistrySpec extends ZIOSpecDefault:

  private def corpusRoot: Path =
    Path.of(sys.env.getOrElse("RAIDER_REPO_ROOT", ".")).resolve("vendor/opencode-profiles")

  private def load = Registry.load(corpusRoot)

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Registry")(
    test("loads corpus pin and manifest hash") {
      load.flatMap { r =>
        assertTrue(r.corpus.commit == "d7d85817160120fd59fb27ee6ff354f45d18b994") &&
        assertTrue(r.corpus.repo == "https://github.com/anomalyco/opencode") &&
        assertTrue(r.corpus.license == "MIT") &&
        assertTrue(r.corpus.manifestSha256 == "3b800d6bdd5ea9e8904cb1a0dcbd0c5da24328d8e93bbb8dbe7639efbdaad165")
      }
    },
    test("registry contains the seven built-in agents in pinned order") {
      load.map { r =>
        assertTrue(r.defaultAgentId == "build") &&
        assertTrue(r.profiles.map(_.id) == Vector("build", "general", "explore", "compaction", "title", "summary", "plan")) &&
        assertTrue(r.profiles.map(_.source).forall(_ == "registry"))
      }
    },
    test("built-in modes and hidden flags match the pinned registry") {
      load.map { r =>
        val byId = r.profiles.map(p => p.id -> p).toMap
        assertTrue(byId("build").mode == "primary") &&
        assertTrue(byId("build").hidden == false) &&
        assertTrue(byId("general").mode == "subagent") &&
        assertTrue(byId("explore").mode == "subagent") &&
        assertTrue(byId("compaction").hidden == true) &&
        assertTrue(byId("title").hidden == true) &&
        assertTrue(byId("summary").hidden == true) &&
        assertTrue(byId("plan").mode == "primary")
      }
    },
    test("built-in permissions match the pinned final rulesets") {
      load.map { r =>
        val byId = r.profiles.map(p => p.id -> p).toMap
        assertTrue(byId("build").permissions == Vector(
          PermissionRule("*", "*", "allow"),
          PermissionRule("read", "*.env", "ask"),
          PermissionRule("read", "*.env.*", "ask"),
          PermissionRule("read", "*.env.example", "allow"),
          PermissionRule("question", "*", "allow"),
        )) &&
        assertTrue(byId("general").permissions.lastOption.contains(PermissionRule("subagent", "*", "deny"))) &&
        assertTrue(byId("explore").permissions.headOption.contains(PermissionRule("*", "*", "allow"))) &&
        assertTrue(byId("explore").permissions.exists(_ == PermissionRule("shell", "*", "allow"))) &&
        assertTrue(byId("explore").permissions.lastOption.contains(PermissionRule("external_directory", "*", "allow"))) &&
        assertTrue(byId("title").permissions.lastOption.contains(PermissionRule("*", "*", "deny"))) &&
        assertTrue(byId("plan").permissions.lastOption.contains(PermissionRule("edit", "~/.opencode/plan/*", "allow"))) &&
        assertTrue(byId("compaction").permissions.length == 4)
      }
    },
    test("built-in system prompts are carried") {
      load.map { r =>
        val byId = r.profiles.map(p => p.id -> p).toMap
        assertTrue(byId("explore").system.exists(_.startsWith("You are a file search specialist."))) &&
        assertTrue(byId("title").system.exists(_.contains("one line, no quotes"))) &&
        assertTrue(byId("summary").system.exists(_.contains("pull request description")))
      }
    },
    test("tampered corpus fails to load") {
      val tmp = ZIO.succeed(java.nio.file.Files.createTempDirectory("raider-corpus-tamper"))
      tmp.flatMap { dir =>
        val tamper = ZIO.attempt {
          import java.nio.file.{Files, StandardCopyOption}
          import scala.jdk.CollectionConverters.*
          val root = corpusRoot
          Files
            .walk(root)
            .iterator()
            .asScala
            .filter(_.toFile.isFile)
            .foreach { src =>
              val rel = root.relativize(src)
              val dst = dir.resolve(rel)
              Files.createDirectories(dst.getParent)
              Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
          Files.write(dir.resolve("extracted/defaults.json"), "not-json".getBytes)
        }
        tamper.flatMap { _ =>
          Registry.load(dir).fold(
            err => assertTrue(err.contains("defaults.json")),
            _ => assertTrue(false) // tampered corpus loaded without error,
          )
        }
      }
    },
    test("default resolution: configured, then build, then first selectable") {
      load.map { r =>
        assertTrue(Registry.resolve(r.profiles, Some("plan")) == "plan") &&
        assertTrue(Registry.resolve(r.profiles, Some("general")) == "build") &&
        assertTrue(Registry.resolve(r.profiles, Some("title")) == "build") &&
        assertTrue(Registry.resolve(r.profiles, Some("nope")) == "build") &&
        assertTrue(Registry.resolve(r.profiles, None) == "build")
      }
    },
    test("ordering puts the default first") {
      load.map { r =>
        val ordered = Registry.ordered(r.profiles, Some("plan"))
        assertTrue(ordered.head.id == "plan") && assertTrue(ordered.count(_.id == "plan") == 1) &&
        assertTrue(ordered.map(_.id).toSet == r.profiles.map(_.id).toSet)
      }
    },
  )

