package raider.dsl.workflow

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.ast.Json
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

/** RAI-155.e — corpus integrity (contract side, independent of the cli
  * harness): the frozen expectation corpus of the independent verifier must
  * parse, cover all three acceptance IDs, declare its derivation and external
  * prerequisites, and carry the deterministic normalization spec. This suite
  * validates the CORPUS as a versioned contract artifact; the behavioral
  * verdicts live in the cli-side verifier suites.
  */
object ChatWorkflowVerifierCorpusSpec extends ZIOSpecDefault:

  private def repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(cwd)

  private def corpus(rel: String): Path =
    repoRoot.resolve("contracts").resolve("chat-workflow").resolve(rel)

  private def readJson(rel: String): Either[String, Json] =
    Files.readString(corpus(rel), UTF_8).fromJson[Json]

  private def field(j: Json, name: String): Option[Json] = j match
    case o: Json.Obj => o.fields.collectFirst { case (n, v) if n == name => v }
    case _           => None

  private def str(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case _           => None

  def spec =
    suite("ChatWorkflowVerifierCorpus (contracts/chat-workflow)")(
      test(
        "all three expectation documents parse and carry their acceptance IDs"
      ) {
        val e01 = readJson("expectations/CWF-E01.json")
        val e02 = readJson("expectations/CWF-E02.json")
        val e03 = readJson("expectations/CWF-E03.json")
        assertTrue(
          e01.isRight,
          e02.isRight,
          e03.isRight,
          e01.toOption
            .flatMap(j => field(j, "expectationId").flatMap(str))
            .contains("CWF-E01"),
          e02.toOption
            .flatMap(j => field(j, "expectationId").flatMap(str))
            .contains("CWF-E02"),
          e03.toOption
            .flatMap(j => field(j, "expectationId").flatMap(str))
            .contains("CWF-E03")
        )
      },
      test("CWF-E02 covers the full negative corpus from the card") {
        val required = Set(
          "lost-chat-context",
          "unsupported-selection",
          "secret-leakage-select",
          "secret-leakage-export",
          "path-escape-nonexistent-parent",
          "path-escape-symlink-target",
          "export-staging-remnant-and-existing-target",
          "invalid-source",
          "invalid-input",
          "workspace-symlink",
          "no-credential",
          "bogus-success",
          "prompt-injection-stays-data",
          "stale-build",
          "corrupted-build"
        )
        val ids = readJson("expectations/CWF-E02.json").toOption
          .flatMap(j => field(j, "cases"))
          .toList
          .flatMap {
            case Json.Arr(chunk) => chunk.toList
            case _               => Nil
          }
          .flatMap(c => field(c, "id").flatMap(str))
        assertTrue(
          ids.toSet == required,
          ids.size == ids.distinct.size
        )
      },
      test(
        "the README declares the normalization spec and the external prerequisites explicitly"
      ) {
        val readme = Files.readString(corpus("README.md"), UTF_8)
        val requiredSections = List(
          "## Normalization spec (deterministic)",
          "## External prerequisites (declared, not hidden)",
          "artifacts/tools/m2",
          "loopback scripted OpenAI-compatible stand",
          "FIFTH fresh run of the UNEDITED workflow"
        )
        assertTrue(requiredSections.forall(s => readme.contains(s)))
      },
      test(
        "the exported corpus directories contain exactly the frozen expectation files"
      ) {
        val listing = Files.list(corpus("expectations"))
        val names =
          try listing.iterator().asScala.map(_.getFileName.toString).toList
          finally listing.close()
        assertTrue(
          names.sorted == List("CWF-E01.json", "CWF-E02.json", "CWF-E03.json"),
          Files.isRegularFile(corpus("README.md"))
        )
      }
    )

end ChatWorkflowVerifierCorpusSpec
