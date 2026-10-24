package raider.dsl.portable

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

import raider.core.{Program, RaiderError, Task}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scala.jdk.CollectionConverters.*

object PortableWorkflowValidatorSpec extends ZIOSpecDefault:

  private def repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(cwd)

  private def contract(relative: String): Path =
    repoRoot.resolve("contracts/workflow-portable").resolve(relative)

  private def diagnostic(result: Either[PortableDiagnostic, Json]) =
    result.swap.toOption

  def spec =
    suite("PortableWorkflowValidator bounded preflight")(
      test(
        "both oversized corpus artifacts refuse at the root by UTF-8 byte count"
      ) {
        val artifacts = List(
          "refusals/fixtures/oversized.json",
          "refusals/fixtures/oversized-utf8.json"
        )
        val results = artifacts.map(path =>
          PortableWorkflowValidator.validate(Files.readAllBytes(contract(path)))
        )
        assertTrue(
          results.forall(result =>
            diagnostic(result).exists(d =>
              d.code == "document-too-large" && d.documentPath == "" &&
                d.offendingRef.contains("262145 bytes")
            )
          )
        )
      },
      test(
        "known positive fixtures pass the decoder and supported major check"
      ) {
        val listing = Files.list(contract("valid"))
        val files =
          try listing.iterator().asScala.toList
          finally listing.close()
        val results = files.map(path =>
          PortableWorkflowValidator.validate(Files.readAllBytes(path))
        )
        assertTrue(files.size == 10, results.forall(_.isRight))
      },
      test(
        "agent and sequence lower to the existing lazy Program composition tree"
      ) {
        val agent = Files
          .readString(contract("valid/agent.json"))
          .fromJson[Json]
          .toOption
          .get
        val sequence = Files
          .readString(contract("valid/sequence.json"))
          .fromJson[Json]
          .toOption
          .get
        val native = Program.fromFunction[Json, Json]("native-scripted")(_ =>
          Task.succeed(Json.Null)
        )
        val registry = PortableProgramRegistry(
          agents = Map("worker" -> native, "extractor" -> native),
          checks = Map(
            (
              "format-check",
              "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            ) -> native
          )
        )
        val loweredAgent = PortableWorkflowLowerer.lower(agent, registry)
        val loweredSequence = PortableWorkflowLowerer.lower(sequence, registry)
        assertTrue(
          loweredAgent.exists(
            _.structure == PortableProgramNode("summarize", "agent", Nil)
          ),
          loweredSequence.exists(
            _.structure == PortableProgramNode(
              "ordered",
              "sequence",
              List(
                PortableProgramNode("draft", "agent", Nil),
                PortableProgramNode("verify", "trustedCheck", Nil)
              )
            )
          )
        )
      },
      test("all ten positive fixtures lower with trusted native descriptors") {
        val listing = Files.list(contract("valid"))
        val files =
          try listing.iterator().asScala.toList
          finally listing.close()
        val native = Program.fromFunction[Json, Json]("native-scripted")(_ =>
          Task.succeed(Json.Null)
        )
        val checks = files.flatMap { path =>
          Files.readString(path).fromJson[Json].toOption.toList.flatMap {
            case obj: Json.Obj =>
              obj.fields
                .collectFirst { case ("steps", Json.Arr(steps)) =>
                  steps.toList
                }
                .getOrElse(Nil)
                .flatMap {
                  case step: Json.Obj =>
                    val id = step.fields.collectFirst {
                      case ("checkId", Json.Str(v)) => v
                    }
                    val snapshot = step.fields.collectFirst {
                      case ("registrySnapshot", Json.Str(v)) => v
                    }
                    for checkId <- id; revision <- snapshot
                    yield (checkId -> revision) -> native
                  case _ => Nil
                }
            case _ => Nil
          }
        }.toMap
        val registry = PortableProgramRegistry(
          agents = List("worker", "extractor", "scout").map(_ -> native).toMap,
          checks = checks,
          safeOperations = Map("safe-edit-v1" -> native),
          checkpoints = Map("review-before-write" -> native)
        )
        val lowered = files.map { path =>
          PortableWorkflowLowerer.lower(
            Files.readString(path).fromJson[Json].toOption.get,
            registry
          )
        }
        assertTrue(files.size == 10, lowered.forall(_.isRight))
      },
      test("unknown-major refusal matches the frozen diagnostic fields") {
        val result = PortableWorkflowValidator.validate(
          """{"format":"raider.portable-workflow","version":"2.0"}"""
        )
        assertTrue(
          diagnostic(result).contains(
            PortableDiagnostic(
              "unknown-major-version",
              "/version",
              None,
              Some("2.0"),
              "Use a supported major version or upgrade the validator."
            )
          )
        )
      },
      test(
        "the frozen refusal corpus is executed and diagnostics match exactly"
      ) {
        val manifest = Files
          .readString(contract("refusals/manifest.json"))
          .fromJson[Json]
          .toOption
          .get
          .asInstanceOf[Json.Obj]
        val cases = manifest.fields.collectFirst {
          case ("cases", Json.Arr(values)) => values.toList
        }.get
        val actual = cases.map { entry =>
          val c = entry.asInstanceOf[Json.Obj]
          val id = c.fields.collectFirst { case ("id", Json.Str(value)) =>
            value
          }.get
          val expected = c.fields.collectFirst {
            case ("expected", value: Json.Obj) => value
          }.get
          val input = c.fields.collectFirst {
            case ("rawDocument", Json.Str(raw)) =>
              raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            case ("fixture", Json.Str(relative)) =>
              Files.readAllBytes(contract(s"refusals/$relative"))
            case ("document", value) =>
              value.toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          }.get
          val diagnostic =
            PortableWorkflowValidator.validate(input).swap.toOption
          (id, expected, diagnostic)
        }
        val matches = actual.forall { case (_, expected, diagnostic) =>
          diagnostic.exists { d =>
            def field(name: String) =
              expected.fields.find(_._1 == name).map(_._2)
            val step = field("stepId") match {
              case Some(Json.Str(v)) => Some(v); case _ => None
            }
            val ref = field("offendingRef") match {
              case Some(Json.Str(v)) => Some(v); case _ => None
            }
            d == PortableDiagnostic(
              field("code").collect { case Json.Str(v) => v }.get,
              field("documentPath").collect { case Json.Str(v) => v }.get,
              step,
              ref,
              field("suggestedCorrection").collect { case Json.Str(v) => v }.get
            )
          }
        }
        assertTrue(cases.size == 17, actual.forall(_._3.nonEmpty), matches)
      },
      test("malformed UTF-8 and invalid JSON are refused locally") {
        val malformedUtf8 = Array(0xc3.toByte, 0x28.toByte)
        val badJson = PortableWorkflowValidator.validate("{]")
        assertTrue(
          PortableWorkflowValidator
            .validate(malformedUtf8)
            .swap
            .toOption
            .nonEmpty,
          diagnostic(badJson).exists(_.code == "invalid-json")
        )
      },
      test(
        "every declared field has its frozen JSON type and closed object shape"
      ) {
        val valid = Files.readString(contract("valid/agent.json"))
        val wrongBoolean = valid.replace(
          "\"structuredOutput\": true",
          "\"structuredOutput\": \"true\""
        )
        val wrongLimit = valid.replace(
          "\"attempts\": 3",
          "\"attempts\": \"3\""
        )
        val extraStepField = valid.replace(
          "\"structuredOutput\": true",
          "\"structuredOutput\": true, \"surprise\": false"
        )
        assertTrue(
          diagnostic(PortableWorkflowValidator.validate(wrongBoolean))
            .exists(d =>
              d.code == "invalid-type" && d.documentPath.endsWith(
                "/structuredOutput"
              )
            ),
          diagnostic(PortableWorkflowValidator.validate(wrongLimit))
            .exists(d =>
              d.code == "invalid-type" && d.documentPath == "/limits/attempts"
            ),
          diagnostic(PortableWorkflowValidator.validate(extraStepField))
            .exists(d =>
              d.code == "unsupported-construct" && d.documentPath.endsWith(
                "/surprise"
              )
            )
        )
      },
      test("validator and lowerer construction invoke no configured program") {
        val calls = new AtomicInteger(0)
        val counted =
          Program.fromFunction[Json, Json]("dispatch-spy") { input =>
            Task(ZIO.succeed { calls.incrementAndGet(); input })
          }
        val document = Files
          .readString(contract("valid/agent.json"))
          .fromJson[Json]
          .toOption
          .get
        val registry = PortableProgramRegistry(
          agents = Map("extractor" -> counted),
          checks = Map.empty
        )
        val validation = PortableWorkflowValidator.validate(document.toJson)
        val lowering = PortableWorkflowLowerer.lower(document, registry)
        assertTrue(validation.isRight, lowering.isRight, calls.get() == 0)
      },
      test(
        "a lowered run preserves typed step failure and does not run later sequence steps"
      ) {
        val sequenceText = Files
          .readString(contract("valid/sequence.json"))
          .replace(
            "\"steps\": [\"draft\", \"verify\"]",
            "\"steps\": [\"draft\", \"verify\", \"after\"]"
          )
          .replace(
            "\"outputSchema\": \"text\" }],",
            "\"outputSchema\": \"text\" }, {\"id\":\"after\",\"kind\":\"agent\",\"profile\":\"worker\",\"toolset\":\"read-only\",\"input\":\"#/inputs/request\",\"outputSchema\":\"text\",\"structuredOutput\":true}],"
          )
        val sequence = sequenceText.fromJson[Json].toOption.get
        val agents = new AtomicInteger(0)
        val checks = new AtomicInteger(0)
        val agent =
          Program.fromFunction[Json, Json]("scripted-agent") { input =>
            Task(ZIO.succeed { agents.incrementAndGet(); input })
          }
        val check =
          Program.fromFunction[Json, Json]("scripted-failing-check") { _ =>
            Task(
              ZIO
                .succeed(checks.incrementAndGet())
                .flatMap(_ =>
                  ZIO.fail(RaiderError.ToolFailed("scripted check failure"))
                )
            )
          }
        val registry = PortableProgramRegistry(
          agents = Map("worker" -> agent),
          checks = Map(
            (
              "format-check",
              "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            ) -> check
          )
        )
        val lowered =
          PortableWorkflowLowerer.lower(sequence, registry).toOption.get
        for result <- Task.zio(lowered.program(Json.Null)).either
        yield assertTrue(
          result.swap.toOption.exists(_.isInstanceOf[RaiderError.ToolFailed]),
          agents.get() == 1,
          checks.get() == 1
        )
      },
      test("external cancellation of a lowered run remains interruption") {
        val document = Files
          .readString(contract("valid/agent.json"))
          .fromJson[Json]
          .toOption
          .get
        for
          started <- Promise.make[Nothing, Unit]
          blocked = Program.fromFunction[Json, Json]("scripted-blocked") { _ =>
            Task(started.succeed(()).unit *> ZIO.never)
          }
          registry = PortableProgramRegistry(
            agents = Map("extractor" -> blocked),
            checks = Map.empty
          )
          lowered = PortableWorkflowLowerer
            .lower(document, registry)
            .toOption
            .get
          fiber <- Task.zio(lowered.program(Json.Null)).fork
          _ <- started.await
          exit <- fiber.interrupt
        yield assertTrue(exit.isInterrupted)
      }
    )

end PortableWorkflowValidatorSpec
