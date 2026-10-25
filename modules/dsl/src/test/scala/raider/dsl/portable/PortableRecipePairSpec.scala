package raider.dsl.portable

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import raider.core.{Program, RaiderError, Task}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** RAI-203 recipe pair (XE-RECIPE-01/02/03): the two checked-in example
  * documents under docs/examples/portable-workflows run END TO END through the
  * real validator + lowerer with deterministic Scala stand authorities (mock
  * mode — no model call, no paid call). Every outcome is checked independently:
  * the run verdict, the per-stage invocation counts and the typed failures.
  *
  * Stand convention: the stand snapshot digest material is the NUL-joined check
  * id + stand material (documented in the examples README); the documents'
  * `registrySnapshot` values must equal these digests or lowering refuses
  * `untrusted-authority-reference`.
  */
object PortableRecipePairSpec extends ZIOSpecDefault:

  private val RecipeA =
    "docs/examples/portable-workflows/checked-repair/checked-repair.workflow.json"

  private val RecipeB =
    "docs/examples/portable-workflows/extraction-classification/extraction-classification.workflow.json"

  private val SnapshotA =
    "sha256:bb788ad23f74c3c1e5d610f0e4ff8c317ec4b8fc8fc738d3180a59671cc09456"

  private val SnapshotB =
    "sha256:69baafd85b2d35be2ffcfc66150ff9a0f8f5f0f3ff73686729e3c64999ad2dff"

  private val InputA =
    "docs/examples/portable-workflows/checked-repair/input-success.json"

  private val InputAExhaustion =
    "docs/examples/portable-workflows/checked-repair/input-repair-exhaustion.json"

  private val InputBTwo =
    "docs/examples/portable-workflows/extraction-classification/input-two-documents.json"

  private val InputBThree =
    "docs/examples/portable-workflows/extraction-classification/input-unclassifiable.json"

  private def repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(cwd)

  private def recipeDoc(relative: String): Json =
    Files
      .readString(repoRoot.resolve(relative))
      .fromJson[Json]
      .toOption
      .get

  private def inputDoc(relative: String): Json = recipeDoc(relative)

  // -- shared helpers --------------------------------------------------------

  private def valueOf(exit: Exit[RaiderError, Json]): Json =
    exit match
      case Exit.Success(value) => value
      case _                   => Json.Null

  private def failureOf(exit: Exit[RaiderError, Json]): Option[RaiderError] =
    exit match
      case Exit.Failure(cause) => cause.failureOption
      case Exit.Success(_)     => None

  private def interrupted(exit: Exit[RaiderError, Json]): Boolean =
    exit match
      case Exit.Failure(cause) =>
        cause.failureOption.isEmpty && cause.interruptors.nonEmpty
      case Exit.Success(_) => false

  /** Runs a lowered workflow over one input and asserts on the green output. */
  private def runGreen(
      lowered: Either[PortableDiagnostic, PortableLoweredWorkflow],
      input: Json
  )(
      asserts: Json => TestResult
  ): ZIO[Any, Nothing, TestResult] =
    lowered match
      case Right(workflow) =>
        Task.zio(workflow.program.apply(input)).exit.map { exit =>
          assertTrue(exit.isSuccess) && asserts(valueOf(exit))
        }
      case Left(d) =>
        ZIO.succeed(
          assertTrue(
            false,
            ("lowering refused: " + d.code + " at " + d.documentPath) != ""
          )
        )

  /** Runs a lowered workflow over one input and asserts on the typed failure.
    */
  private def runExpectFailure(
      lowered: Either[PortableDiagnostic, PortableLoweredWorkflow],
      input: Json
  )(
      asserts: Option[RaiderError] => TestResult
  ): ZIO[Any, Nothing, TestResult] =
    lowered match
      case Right(workflow) =>
        Task
          .zio(workflow.program.apply(input))
          .exit
          .map(exit => asserts(failureOf(exit)))
      case Left(d) =>
        ZIO.succeed(
          assertTrue(
            false,
            ("lowering refused: " + d.code + " at " + d.documentPath) != ""
          )
        )

  private def callsOf(stand: StandB, id: String): Int =
    Option(stand.agentCalls.get(id)).map(_.get).getOrElse(0)

  /** Edits one nested object field of a JSON document. */
  private def withField(doc: Json, path: List[String], value: Json): Json =
    doc match
      case obj: Json.Obj =>
        path match
          case last :: Nil =>
            Json.Obj(
              obj.fields.map(e => if e._1 == last then (last, value) else e)
            )
          case head :: rest =>
            Json.Obj(
              obj.fields.map { e =>
                if e._1 == head then (head, withField(e._2, rest, value))
                else e
              }
            )
          case Nil => doc
      case _ => doc

  /** Appends documents to the nested `inputs.documents` array of a recipe B run
    * input.
    */
  private def withDocuments(input: Json, extra: (String, String)*): Json =
    input match
      case root: Json.Obj =>
        val nested = root.fields.collectFirst { case ("inputs", i: Json.Obj) =>
          i
        }
        nested match
          case Some(inputs) =>
            val docs = inputs.fields
              .collectFirst { case ("documents", Json.Arr(values)) =>
                values
              }
              .getOrElse(Vector.empty)
            val appended = docs ++ extra.map { case (id, body) =>
              Json.Obj(
                "id" -> Json.Str(id),
                "body" -> Json.Str(body)
              )
            }
            Json.Obj(
              root.fields.map {
                case ("inputs", _) =>
                  (
                    "inputs",
                    Json.Obj(
                      inputs.fields.map {
                        case ("documents", _) =>
                          ("documents", Json.Arr(appended*))
                        case other => other
                      }
                    )
                  )
                case other => other
              }
            )
          case None => input
      case _ => input

  private def strField(obj: Json, name: String): Option[String] =
    obj match
      case o: Json.Obj =>
        o.fields.collectFirst { case (`name`, Json.Str(v)) => v }
      case _ => None

  // -- recipe A stands -------------------------------------------------------

  /** Mode of the `normalize-task-v1` safe operation. */
  private sealed trait OpMode

  private object OpMode:
    case object Normalize extends OpMode
    case object AlwaysFail extends OpMode
    case object Uncertain extends OpMode

    final case class Latch(
        started: Promise[Nothing, Unit],
        hold: Promise[Nothing, Unit]
    ) extends OpMode

  private final case class StandA(
      draftCalls: AtomicInteger,
      opCalls: AtomicInteger,
      checkCalls: AtomicInteger,
      opMode: OpMode
  )

  private def draftProgram(stand: StandA): Program[Json, Json] =
    Program.fromFunction[Json, Json]("stand-draft-coder") { root =>
      Task(
        ZIO.succeed {
          stand.draftCalls.incrementAndGet()
          val task = root.asInstanceOf[Json.Obj].fields.collectFirst {
            case ("task", t: Json.Obj) => t
          }
          def str(name: String): String =
            task
              .flatMap(
                _.fields.collectFirst { case (`name`, Json.Str(v)) => v }
              )
              .getOrElse("")
          Json.Obj(
            "taskId" -> Json.Str(str("taskId")),
            "patch" -> Json.Str(str("instruction"))
          )
        }
      )
    }

  private def opProgram(stand: StandA): Program[Json, Json] =
    Program.fromFunction[Json, Json]("stand-normalize-task-v1") { candidate =>
      Task(
        ZIO
          .succeed(stand.opCalls.incrementAndGet())
          .flatMap { _ =>
            stand.opMode match
              case OpMode.Normalize => ZIO.unit
              case OpMode.AlwaysFail =>
                ZIO.fail(
                  RaiderError.InputValidation(
                    "normalize-task-v1: candidate is not a normalized task shape"
                  )
                )
              case OpMode.Uncertain =>
                ZIO.fail(
                  RaiderError.ToolOutcomeUncertain(
                    "normalize-task-v1: outcome unknown"
                  )
                )
              case OpMode.Latch(started, hold) =>
                started.succeed(()).ignore *> hold.await
          }
          .as {
            val obj = candidate.asInstanceOf[Json.Obj]
            def str(name: String): String =
              obj.fields
                .collectFirst { case (`name`, Json.Str(v)) => v }
                .getOrElse("")
            val taskId = str("taskId")
            val patch = str("patch")
            if taskId.matches("T-[0-9]+") && patch.nonEmpty then
              Right(
                Json.Obj(
                  "taskId" -> Json.Str(taskId),
                  "normalized" -> Json.Bool(true),
                  "detail" -> Json.Str(
                    "normalized " + patch.length + " characters"
                  )
                )
              )
            else
              Left(
                RaiderError.InputValidation(
                  "normalize-task-v1: candidate is not a normalized task shape"
                )
              )
          }
          .flatMap {
            case Right(normalized) => ZIO.succeed(normalized)
            case Left(failure)     => ZIO.fail(failure)
          }
      )
    }

  private def checkAProgram(stand: StandA): Program[Json, Json] =
    Program.fromFunction[Json, Json]("stand-check-normalized-task-shape") {
      normalized =>
        Task(
          ZIO.succeed {
            stand.checkCalls.incrementAndGet()
            val obj = normalized.asInstanceOf[Json.Obj]
            def str(name: String): String =
              obj.fields
                .collectFirst { case (`name`, Json.Str(v)) => v }
                .getOrElse("")
            def flag(name: String): Boolean =
              obj.fields
                .collectFirst { case (`name`, Json.Bool(v)) => v }
                .getOrElse(false)
            val passed =
              str("taskId").matches("T-[0-9]+") && flag("normalized")
            Json.Obj(
              "status" -> Json.Str(if passed then "passed" else "failed"),
              "detail" -> Json.Str(
                if passed then
                  "normalized task shape verified for " + str("taskId")
                else "normalized task shape refused for '" + str("taskId") + "'"
              )
            )
          }
        )
    }

  private def registryA(stand: StandA): PortableProgramRegistry =
    PortableProgramRegistry(
      agents = Map("coder" -> draftProgram(stand)),
      checks = Map(
        ("normalized-task-shape", SnapshotA) -> checkAProgram(stand)
      ),
      safeOperations = Map("normalize-task-v1" -> opProgram(stand))
    )

  // -- recipe B stands -------------------------------------------------------

  private final case class StandB(
      agentCalls: ConcurrentHashMap[String, AtomicInteger],
      checkCalls: AtomicInteger,
      latch: Option[(Promise[Nothing, Unit], Promise[Nothing, Unit])]
  )

  private def classifyProgram(stand: StandB): Program[Json, Json] =
    Program.fromFunction[Json, Json]("stand-classify-extractor") { item =>
      Task(
        ZIO
          .succeed {
            val obj = item.asInstanceOf[Json.Obj]
            val id = obj.fields
              .collectFirst { case ("id", Json.Str(v)) =>
                v
              }
              .getOrElse("?")
            val body = obj.fields
              .collectFirst { case ("body", Json.Str(v)) =>
                v
              }
              .getOrElse("")
            stand.agentCalls
              .computeIfAbsent(id, _ => new AtomicInteger(0))
              .incrementAndGet()
            (id, body)
          }
          .flatMap { case (id, body) =>
            val label =
              if body.contains("refund") then "billing"
              else if body.contains("error") || body.contains("crash") then
                "technical"
              else if body.contains("pricing") then "sales"
              else ""
            val classification = Json.Obj(
              "id" -> Json.Str(id),
              "label" -> Json.Str(label),
              "summary" -> Json.Str(body.take(48))
            )
            stand.latch match
              case Some((started, hold)) =>
                // Every dispatched arrival signals readiness, then parks on
                // the hold latch so the test can observe a mid-flight
                // interruption.
                started.succeed(()).ignore *> hold.await.as(classification)
              case None => ZIO.succeed(classification)
          }
      )
    }

  private def checkBProgram(stand: StandB): Program[Json, Json] =
    Program.fromFunction[Json, Json](
      "stand-check-classification-labels-valid"
    ) { extracted =>
      Task(
        ZIO.succeed {
          stand.checkCalls.incrementAndGet()
          val items = extracted match
            case Json.Arr(values) => values.collect { case o: Json.Obj => o }
            case _                => Vector.empty
          val validLabels = Set("billing", "technical", "sales")
          val invalid = items.count { obj =>
            val label = obj.fields
              .collectFirst { case ("label", Json.Str(v)) =>
                v
              }
              .getOrElse("")
            !validLabels.contains(label)
          }
          if items.isEmpty then
            Json.Obj(
              "status" -> Json.Str("failed"),
              "detail" -> Json.Str("no classifications extracted")
            )
          else if invalid > 0 then
            Json.Obj(
              "status" -> Json.Str("failed"),
              "detail" -> Json.Str(
                "invalid extraction does not pass: " + invalid + " of " +
                  items.size + " labels are outside billing/technical/sales"
              )
            )
          else
            Json.Obj(
              "status" -> Json.Str("passed"),
              "detail" -> Json.Str(s"${items.size} classifications verified")
            )
        }
      )
    }

  private def registryB(stand: StandB): PortableProgramRegistry =
    PortableProgramRegistry(
      agents = Map("extractor" -> classifyProgram(stand)),
      checks = Map(
        ("classification-labels-valid", SnapshotB) -> checkBProgram(stand)
      )
    )

  def spec = suite("PortableRecipePair (RAI-203)")(
    suite("checked-repair")(
      test(
        "XE-RECIPE-01: success input runs green; agent, safe operation and check each invoked once"
      ) {
        val stand = StandA(
          new AtomicInteger(0),
          new AtomicInteger(0),
          new AtomicInteger(0),
          OpMode.Normalize
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeA), registryA(stand))
        runGreen(lowered, inputDoc(InputA)) { output =>
          assertTrue(
            strField(output, "status").contains("passed"),
            stand.draftCalls.get == 1,
            stand.opCalls.get == 1,
            stand.checkCalls.get == 1
          )
        }
      },
      test(
        "XE-RECIPE-03: exhaustion input repeats the safe operation exactly maxAttempts times, then LocalBudgetExceeded; check never runs"
      ) {
        val stand = StandA(
          new AtomicInteger(0),
          new AtomicInteger(0),
          new AtomicInteger(0),
          OpMode.AlwaysFail
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeA), registryA(stand))
        runExpectFailure(lowered, inputDoc(InputAExhaustion)) { failure =>
          assertTrue(
            failure.exists {
              case RaiderError.LocalBudgetExceeded(m) =>
                m.contains("exhausted its declared attempt bound of 3")
              case _ => false
            },
            stand.draftCalls.get == 1,
            stand.opCalls.get == 3,
            stand.checkCalls.get == 0
          )
        }
      },
      test(
        "XE-RECIPE-03: uncertain repair outcome is typed and never repeated"
      ) {
        val stand = StandA(
          new AtomicInteger(0),
          new AtomicInteger(0),
          new AtomicInteger(0),
          OpMode.Uncertain
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeA), registryA(stand))
        runExpectFailure(lowered, inputDoc(InputA)) { failure =>
          assertTrue(
            failure.exists {
              case _: RaiderError.ToolOutcomeUncertain => true
              case _                                   => false
            },
            stand.opCalls.get == 1,
            stand.checkCalls.get == 0
          )
        }
      },
      test(
        "XE-RECIPE-03: interrupt mid-repair — safe operation invoked exactly once, exit interrupted"
      ) {
        for
          started <- Promise.make[Nothing, Unit]
          hold <- Promise.make[Nothing, Unit]
          stand = StandA(
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicInteger(0),
            OpMode.Latch(started, hold)
          )
          lowered =
            PortableWorkflowLowerer.lower(recipeDoc(RecipeA), registryA(stand))
          fiber <- lowered match
            case Right(workflow) =>
              Task.zio(workflow.program.apply(inputDoc(InputA))).fork
            case Left(_) => ZIO.die(new IllegalStateException("lower failed"))
          _ <- started.await
          exit <- fiber.interrupt
        yield assertTrue(
          interrupted(exit),
          stand.draftCalls.get == 1,
          stand.opCalls.get == 1,
          stand.checkCalls.get == 0
        )
      },
      test(
        "XE-RECIPE-02: benign edit validates and lowers; stale registry snapshot refuses typed"
      ) {
        val stand = StandA(
          new AtomicInteger(0),
          new AtomicInteger(0),
          new AtomicInteger(0),
          OpMode.Normalize
        )
        val benign = withField(
          recipeDoc(RecipeA),
          List("metadata", "title"),
          Json.Str("checked-repair (edited)")
        )
        val snapshotEdit = {
          val root = recipeDoc(RecipeA).asInstanceOf[Json.Obj]
          Json.Obj(
            root.fields.map {
              case ("steps", Json.Arr(steps)) =>
                (
                  "steps",
                  Json.Arr(
                    steps.map {
                      case s: Json.Obj
                          if strField(s, "id").contains("verify") =>
                        Json.Obj(
                          s.fields.map {
                            case ("registrySnapshot", _) =>
                              (
                                "registrySnapshot",
                                Json.Str("sha256:" + ("00" * 32))
                              )
                            case other => other
                          }
                        )
                      case other => other
                    }*
                  )
                )
              case other => other
            }
          )
        }
        val benignValidated =
          PortableWorkflowValidator.validate(benign.toJson)
        val benignLowered =
          PortableWorkflowLowerer.lower(benign, registryA(stand))
        val snapshotLowered =
          PortableWorkflowLowerer.lower(snapshotEdit, registryA(stand))
        assertTrue(
          benignValidated.isRight,
          benignLowered.isRight,
          snapshotLowered.isLeft,
          snapshotLowered.left.toOption.get.code == "untrusted-authority-reference"
        )
      }
    ),
    suite("extraction-classification")(
      test(
        "XE-RECIPE-01: two classifiable documents run green; each item extracted once, check once"
      ) {
        val stand = StandB(
          new ConcurrentHashMap[String, AtomicInteger](),
          new AtomicInteger(0),
          None
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeB), registryB(stand))
        runGreen(lowered, inputDoc(InputBTwo)) { output =>
          assertTrue(
            strField(output, "status").contains("passed"),
            callsOf(stand, "d1") == 1,
            callsOf(stand, "d2") == 1,
            stand.checkCalls.get == 1
          )
        }
      },
      test(
        "XE-RECIPE-02: invalid extraction does not pass — the gate honestly fails the unclassifiable document"
      ) {
        val stand = StandB(
          new ConcurrentHashMap[String, AtomicInteger](),
          new AtomicInteger(0),
          None
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeB), registryB(stand))
        runGreen(lowered, inputDoc(InputBThree)) { output =>
          assertTrue(
            strField(output, "status").contains("failed"),
            strField(output, "detail").exists(
              _.contains("invalid extraction does not pass")
            ),
            callsOf(stand, "d3") == 1,
            stand.checkCalls.get == 1
          )
        }
      },
      test(
        "XE-RECIPE-03: fan-out above maxItems fails LocalBudgetExceeded BEFORE any dispatch"
      ) {
        val stand = StandB(
          new ConcurrentHashMap[String, AtomicInteger](),
          new AtomicInteger(0),
          None
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeB), registryB(stand))
        val five = withDocuments(
          inputDoc(InputBThree),
          ("d4", "filler one"),
          ("d5", "filler two")
        )
        runExpectFailure(lowered, five) { failure =>
          assertTrue(
            failure.exists {
              case RaiderError.LocalBudgetExceeded(m) =>
                m.contains("maxItems or root children limit")
              case _ => false
            },
            stand.agentCalls.isEmpty,
            stand.checkCalls.get == 0
          )
        }
      },
      test(
        "XE-RECIPE-03: root children budget caps the fan-out before dispatch"
      ) {
        val stand = StandB(
          new ConcurrentHashMap[String, AtomicInteger](),
          new AtomicInteger(0),
          None
        )
        val doc = withField(
          recipeDoc(RecipeB),
          List("limits", "children"),
          Json.Num(2)
        )
        val lowered = PortableWorkflowLowerer.lower(doc, registryB(stand))
        runExpectFailure(lowered, inputDoc(InputBThree)) { failure =>
          assertTrue(
            failure.exists {
              case RaiderError.LocalBudgetExceeded(m) =>
                m.contains("maxItems or root children limit")
              case _ => false
            },
            stand.agentCalls.isEmpty,
            stand.checkCalls.get == 0
          )
        }
      },
      test(
        "XE-RECIPE-03: interrupt mid-fan-out — each item dispatched at most once"
      ) {
        for
          started <- Promise.make[Nothing, Unit]
          hold <- Promise.make[Nothing, Unit]
          stand = StandB(
            new ConcurrentHashMap[String, AtomicInteger](),
            new AtomicInteger(0),
            Some((started, hold))
          )
          lowered =
            PortableWorkflowLowerer.lower(recipeDoc(RecipeB), registryB(stand))
          fiber <- lowered match
            case Right(workflow) =>
              Task.zio(workflow.program.apply(inputDoc(InputBTwo))).fork
            case Left(_) => ZIO.die(new IllegalStateException("lower failed"))
          _ <- started.await
          exit <- fiber.interrupt
        yield assertTrue(
          interrupted(exit),
          stand.checkCalls.get == 0,
          callsOf(stand, "d1") <= 1,
          callsOf(stand, "d2") <= 1
        )
      },
      test(
        "XE-RECIPE-02: benign edit validates and lowers; template-scope escape refuses at the gate"
      ) {
        val benign = withField(
          recipeDoc(RecipeB),
          List("metadata", "title"),
          Json.Str("extraction-classification (edited)")
        )
        val escaped = {
          val root = recipeDoc(RecipeB).asInstanceOf[Json.Obj]
          Json.Obj(
            root.fields.map {
              case ("steps", Json.Arr(steps)) =>
                (
                  "steps",
                  Json.Arr(
                    steps.map {
                      case s: Json.Obj
                          if strField(s, "id").contains("classify-item") =>
                        Json.Obj(
                          s.fields.map {
                            case ("input", _) =>
                              ("input", Json.Str("#/inputs/documents"))
                            case other => other
                          }
                        )
                      case other => other
                    }*
                  )
                )
              case other => other
            }
          )
        }
        val benignValidated =
          PortableWorkflowValidator.validate(benign.toJson)
        val benignLowered = PortableWorkflowLowerer.lower(
          benign,
          registryB(
            StandB(
              new ConcurrentHashMap[String, AtomicInteger](),
              new AtomicInteger(0),
              None
            )
          )
        )
        val escapedValidated =
          PortableWorkflowValidator.validate(escaped.toJson)
        assertTrue(
          benignValidated.isRight,
          benignLowered.isRight,
          escapedValidated.isLeft,
          escapedValidated.left.toOption.get.code == "map-template-scope-escape"
        )
      },
      test(
        "XE-RECIPE-02: mis-nested input yields no items; the gate honestly fails an empty extraction"
      ) {
        val stand = StandB(
          new ConcurrentHashMap[String, AtomicInteger](),
          new AtomicInteger(0),
          None
        )
        val lowered =
          PortableWorkflowLowerer.lower(recipeDoc(RecipeB), registryB(stand))
        val misNested = inputDoc(InputBTwo) match
          case root: Json.Obj =>
            root.fields
              .collectFirst { case ("inputs", i: Json.Obj) => i }
              .getOrElse(root)
          case other => other
        runGreen(lowered, misNested) { output =>
          assertTrue(
            strField(output, "status").contains("failed"),
            strField(output, "detail").contains("no classifications extracted"),
            stand.agentCalls.isEmpty
          )
        }
      }
    )
  )

end PortableRecipePairSpec
