package raider.dsl.portable

import raider.core.{Program, RaiderError, Task}
import raider.dsl.composition.{all, batch}
import zio.Duration
import zio.json.*
import zio.json.ast.Json

/** Existing trusted native programs resolved by descriptor identity. */
final case class PortableProgramRegistry(
    agents: Map[String, Program[Json, Json]],
    checks: Map[(String, String), Program[Json, Json]],
    safeOperations: Map[String, Program[Json, Json]] = Map.empty,
    checkpoints: Map[String, Program[Json, Json]] = Map.empty
)

/** A structural description of work that is composed from existing native
  * owners.
  */
final case class PortableProgramNode(
    stepId: String,
    kind: String,
    children: List[PortableProgramNode]
)

final case class PortableLoweredWorkflow(
    program: Program[Json, Json],
    structure: PortableProgramNode,
    launchMetadata: PortableLaunchMetadata
)

/** Additive, validated root ceilings for the trusted launch owner.
  *
  * The DSL deliberately does not interpret these values as scheduler or budget
  * state. A launcher must intersect them with invocation policy before it
  * dispatches the returned program.
  */
final case class PortableLaunchMetadata(
    maxAttempts: Int,
    maxParallelism: Int,
    deadlineMs: Long,
    maxTokens: Long,
    maxChildren: Int
)

/** Lowers supported portable nodes to lazy existing Program/Task compositions.
  * Construction resolves trusted descriptors only; no Program is applied until
  * the caller invokes the returned Program through the ordinary launch path.
  */
object PortableWorkflowLowerer:

  def lower(
      document: Json,
      registry: PortableProgramRegistry
  ): Either[PortableDiagnostic, PortableLoweredWorkflow] =
    PortableWorkflowValidator.validate(document.toJson).flatMap { validated =>
      val root = validated.asInstanceOf[Json.Obj]
      val rootLimits = field(root, "limits").flatMap(objectValue)
      val launchMetadata = rootLimits.map { limits =>
        def integer(name: String): Long =
          field(limits, name).collect { case Json.Num(value) =>
            value.longValueExact()
          }.get
        PortableLaunchMetadata(
          maxAttempts = integer("attempts").toInt,
          maxParallelism = integer("parallelism").toInt,
          deadlineMs = integer("deadlineMs"),
          maxTokens = integer("tokens"),
          maxChildren = integer("children").toInt
        )
      }.get
      def rootLimit(name: String, default: Int): Int =
        rootLimits
          .flatMap(field(_, name))
          .collect { case Json.Num(value) =>
            value.intValue
          }
          .getOrElse(default)
      val steps = field(root, "steps")
        .collect { case Json.Arr(values) => values.toList }
        .getOrElse(Nil)
      val byId = steps
        .flatMap(step =>
          objectValue(step).flatMap(s => string(s, "id").map(_ -> s))
        )
        .toMap
      val outputs = field(root, "outputs").flatMap(objectValue)
      val bindingTarget = outputs
        .flatMap(field(_, "bindings"))
        .flatMap(objectValue)
        .flatMap(_.fields.headOption.map(_._2))
        .collect { case Json.Str(pointer) => pointer }
      val verdictTarget = outputs
        .flatMap(field(_, "verdict"))
        .flatMap(objectValue)
        .flatMap(field(_, "from"))
        .collect { case Json.Str(pointer) => pointer }
      val target = bindingTarget
        .orElse(verdictTarget)
        .flatMap(stepIdFromPointer)
      target
        .toRight(
          PortableDiagnostic(
            "unsupported-construct",
            "/outputs/verdict/from",
            None,
            None,
            "Bind the final verdict to an output produced by a supported portable step."
          )
        )
        .flatMap { rootStep =>
          def inspect(id: String, active: Set[String])
              : Either[PortableDiagnostic, PortableProgramNode] =
            if active(id) then
              Left(
                PortableDiagnostic(
                  "cycle",
                  s"/steps/${id}",
                  Some(id),
                  Some(id),
                  "Remove the back-edge so the step dependency graph is acyclic."
                )
              )
            else
              byId
                .get(id)
                .toRight(unsupported(s"/steps", Some(id), id))
                .flatMap { step =>
                  string(step, "kind") match
                    case Some("sequence") =>
                      val children = field(step, "steps")
                        .collect { case Json.Arr(values) =>
                          values.toList.collect { case Json.Str(child) =>
                            child
                          }
                        }
                        .getOrElse(Nil)
                      children
                        .foldLeft[Either[PortableDiagnostic, List[
                          PortableProgramNode
                        ]]](Right(Nil)) { (result, child) =>
                          result.flatMap(nodes =>
                            inspect(child, active + id).map(nodes :+ _)
                          )
                        }
                        .map(nodes =>
                          PortableProgramNode(id, "sequence", nodes)
                        )
                    case Some("map") | Some("join") =>
                      val kind = string(step, "kind").get
                      val references = field(step, "steps")
                        .collect { case Json.Arr(values) =>
                          values.toList.collect { case Json.Str(child) =>
                            child
                          }
                        }
                        .orElse(string(step, "templateStep").map(List(_)))
                        .getOrElse(Nil)
                      references
                        .foldLeft[Either[PortableDiagnostic, List[
                          PortableProgramNode
                        ]]](Right(Nil)) { (result, child) =>
                          result.flatMap(nodes =>
                            inspect(child, active + id).map(nodes :+ _)
                          )
                        }
                        .map(nodes => PortableProgramNode(id, kind, nodes))
                    case Some("conditional") =>
                      val branches =
                        List(string(step, "then"), string(step, "else")).flatten
                      branches
                        .foldLeft[Either[PortableDiagnostic, List[
                          PortableProgramNode
                        ]]](Right(Nil)) { (result, child) =>
                          result.flatMap(nodes =>
                            inspect(child, active + id).map(nodes :+ _)
                          )
                        }
                        .map(nodes =>
                          PortableProgramNode(id, "conditional", nodes)
                        )
                    case Some("repair") =>
                      val operation =
                        string(step, "safeOperationRef").getOrElse("")
                      registry.safeOperations
                        .get(operation)
                        .map(_ => PortableProgramNode(id, "repair", Nil))
                        .toRight(
                          unsupported(
                            s"/steps/${indexOf(steps, id)}/safeOperationRef",
                            Some(id),
                            operation
                          )
                        )
                    case Some("checkpoint") =>
                      val checkpoint =
                        string(step, "checkpointRef").getOrElse("")
                      registry.checkpoints
                        .get(checkpoint)
                        .map(_ => PortableProgramNode(id, "checkpoint", Nil))
                        .toRight(
                          unsupported(
                            s"/steps/${indexOf(steps, id)}/checkpointRef",
                            Some(id),
                            checkpoint
                          )
                        )
                    case Some("agent") =>
                      val profile = string(step, "profile").getOrElse("")
                      registry.agents
                        .get(profile)
                        .map(_ => PortableProgramNode(id, "agent", Nil))
                        .toRight(
                          PortableDiagnostic(
                            "untrusted-authority-reference",
                            s"/steps/${indexOf(steps, id)}/profile",
                            Some(id),
                            Some(profile),
                            "Resolve only names in the pinned trusted registry; obtain any authority from the invocation owner, never the document."
                          )
                        )
                    case Some("trustedCheck") =>
                      val checkId = string(step, "checkId").getOrElse("")
                      val snapshot =
                        string(step, "registrySnapshot").getOrElse("")
                      registry.checks
                        .get(checkId -> snapshot)
                        .map(_ => PortableProgramNode(id, "trustedCheck", Nil))
                        .toRight(
                          PortableDiagnostic(
                            "untrusted-authority-reference",
                            s"/steps/${indexOf(steps, id)}/checkId",
                            Some(id),
                            Some(checkId),
                            "Resolve only checks in the pinned trusted registry snapshot."
                          )
                        )
                    case Some(other) =>
                      Left(
                        unsupported(
                          s"/steps/${indexOf(steps, id)}/kind",
                          Some(id),
                          other
                        )
                      )
                    case None =>
                      Left(
                        unsupported(
                          s"/steps/${indexOf(steps, id)}/kind",
                          Some(id),
                          "missing kind"
                        )
                      )
                }

          inspect(rootStep, Set.empty).map { structure =>
            def execute(node: PortableProgramNode, input: Json): Task[Json] =
              node.kind match
                case "agent" =>
                  registry
                    .agents(string(byId(node.stepId), "profile").get)
                    .apply(input)
                case "trustedCheck" =>
                  val step = byId(node.stepId)
                  registry
                    .checks(
                      (
                        string(step, "checkId").get,
                        string(step, "registrySnapshot").get
                      )
                    )
                    .apply(input)
                case "sequence" =>
                  node.children.foldLeft[Task[Json]](Task.succeed(input))(
                    (prior, child) =>
                      prior.flatMap(value => execute(child, value))
                  )
                case "map" =>
                  val step = byId(node.stepId)
                  val items = string(step, "items")
                    .flatMap(pointer(input, _))
                    .collect { case Json.Arr(values) =>
                      values.toList
                    }
                    .getOrElse(Nil)
                  val parallelism = field(step, "parallelism")
                    .collect { case Json.Num(value) => value.intValue }
                    .getOrElse(1)
                  val maxItems = field(step, "maxItems")
                    .collect { case Json.Num(value) => value.intValue }
                    .getOrElse(1)
                  if items.size > maxItems || items.size > rootLimit(
                      "children",
                      0
                    )
                  then
                    Task.fail(
                      RaiderError.LocalBudgetExceeded(
                        "portable map exceeds its declared maxItems or root children limit"
                      )
                    )
                  else
                    batch(items, parallelism.min(rootLimit("parallelism", 1)))(
                      item => execute(node.children.head, item)
                    ).map(values => Json.Arr(values*))
                case "join" =>
                  val joined = node.children match
                    case first :: second :: Nil =>
                      all(execute(first, input), execute(second, input)).map {
                        case (a, b) => Json.Arr(a, b)
                      }
                    case many =>
                      batch(many, many.size.max(1))(child =>
                        execute(child, input)
                      ).map(values => Json.Arr(values*))
                  joined
                case "conditional" =>
                  val step = byId(node.stepId)
                  val present = field(step, "when")
                    .flatMap(objectValue)
                    .flatMap(field(_, "value"))
                    .collect { case Json.Str(path) =>
                      pointer(input, path).isDefined
                    }
                    .getOrElse(false)
                  execute(node.children(if present then 0 else 1), input)
                case "repair" =>
                  val step = byId(node.stepId)
                  registry
                    .safeOperations(string(step, "safeOperationRef").get)
                    .apply(input)
                case "checkpoint" =>
                  val step = byId(node.stepId)
                  registry
                    .checkpoints(string(step, "checkpointRef").get)
                    .apply(input)
                case _ => Task.succeed(Json.Null)
            PortableLoweredWorkflow(
              Program.fromFunction[Json, Json]("portable-workflow") { input =>
                Task(
                  Task
                    .zio(execute(structure, input))
                    .timeoutFail(
                      RaiderError.DeadlineExceeded(
                        "portable workflow exceeded its declared deadline"
                      )
                    )(
                      Duration
                        .fromMillis(rootLimit("deadlineMs", 86400000).toLong)
                    )
                )
              },
              structure,
              launchMetadata
            )
          }
        }
    }

  private def unsupported(
      path: String,
      step: Option[String],
      construct: String
  ) =
    PortableDiagnostic(
      "unsupported-construct",
      path,
      step,
      Some(construct),
      "Use only portable constructs supported by the native lowering adapter, or keep this workflow in native Scala."
    )

  private def indexOf(steps: List[Json], id: String): Int =
    steps.indexWhere(step =>
      objectValue(step).flatMap(string(_, "id")).contains(id)
    )

  private def stepIdFromPointer(pointer: String): Option[String] =
    val prefix = "#/steps/"
    if pointer.startsWith(prefix) then
      Some(pointer.stripPrefix(prefix).takeWhile(_ != '/'))
    else None

  private def pointer(value: Json, path: String): Option[Json] =
    path.stripPrefix("#/").split("/").foldLeft(Option(value)) {
      (current, part) =>
        current.flatMap {
          case obj: Json.Obj =>
            field(obj, part.replace("~1", "/").replace("~0", "~"))
          case Json.Arr(values) => part.toIntOption.flatMap(values.lift)
          case _                => None
        }
    }

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def objectValue(value: Json): Option[Json.Obj] = value match
    case result: Json.Obj => Some(result)
    case _                => None

  private def string(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }
