package raider.dsl.portable

import raider.core.{Program, Task}
import raider.dsl.composition.batch
import zio.json.*
import zio.json.ast.Json

/** Existing trusted native programs resolved by descriptor identity. */
final case class PortableProgramRegistry(
    agents: Map[String, Program[Json, Json]],
    checks: Map[(String, String), Program[Json, Json]]
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
    structure: PortableProgramNode
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
                case _ => Task.succeed(Json.Null)
            PortableLoweredWorkflow(
              Program.fromFunction[Json, Json]("portable-workflow") { input =>
                execute(structure, input)
              },
              structure
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

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def objectValue(value: Json): Option[Json.Obj] = value match
    case result: Json.Obj => Some(result)
    case _                => None

  private def string(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }
