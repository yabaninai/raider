package raider.cli.lineage

import java.nio.file.{Files, Path}

import raider.core.RaiderError
import raider.dsl.lineage.data.NodeData
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.{DataRef, SessionGraph, SessionNode}
import raider.runtime.lineage.replay.*
import zio.{Scope, ZIO}
import zio.json.*

/** Pointer-resolved entry point for a fresh partial lineage rerun. */
object PartialLiveRerun:

  final case class Outcome(
      snapshot: LineageSnapshot.Snapshot,
      selection: ReplaySelection,
      result: PartialRerunExecution,
      diff: Either[GraphDiff, String]
  )

  def run(
      pointerDirectory: Path,
      workspace: Path,
      isolationParent: Path,
      selection: ReplaySelection,
      prompt: String,
      model: String,
      limits: BudgetLimits,
      maxRounds: Int,
      backend: RerunBackend
  ): ZIO[Scope, RaiderError, Outcome] =
    ZIO
      .fromEither(
        LineageSnapshot
          .read(pointerDirectory)
          .left
          .map(error =>
            RaiderError
              .InputValidation(s"lineage snapshot could not be read: $error")
          )
      )
      .flatMap { snapshot =>
        val recorded = ReplayHarness.recordedGraph(snapshot.document)
        val selected = PartialRerun.select(
          recorded,
          selection,
          PartialReplayInputs(Map.empty)
        )
        val resolved = selected match
          case Left(PartialReplayError.MissingExternalInput(_, _)) =>
            externalInputs(recorded, selection, snapshot.document.data)
          case Left(error) => Left(error)
          case Right(_)    => Right(PartialReplayInputs(Map.empty))
        ZIO
          .fromEither(
            resolved.left
              .map(error => RaiderError.InputValidation(error.toString))
          )
          .flatMap { inputs =>
            ZIO
              .fromEither(
                WorkspaceIsolation.isolate(workspace, isolationParent)
              )
              .mapError(error =>
                RaiderError
                  .InputValidation(s"workspace isolation failed: $error")
              )
              .flatMap { isolated =>
                ZIO.acquireReleaseWith(ZIO.succeed(isolated)) { path =>
                  ZIO.attempt(deleteTree(path)).orDie
                } { path =>
                  val before = ZIO
                    .attempt(WorkspaceIsolation.snapshot(workspace))
                    .mapError(error =>
                      RaiderError.InputValidation(
                        s"workspace snapshot failed: ${error.getMessage}"
                      )
                    )
                  before.flatMap { originalBytes =>
                    PartialRerun
                      .executeLive(
                        backend,
                        recorded,
                        selection,
                        inputs,
                        path,
                        prompt,
                        model,
                        snapshot.generation,
                        limits,
                        maxRounds
                      )
                      .flatMap { result =>
                        ZIO
                          .fromEither(
                            WorkspaceIsolation
                              .verifyUntouched(workspace, originalBytes)
                          )
                          .mapError(error =>
                            RaiderError.InputValidation(
                              s"original workspace changed during partial rerun: $error"
                            )
                          )
                          .map { _ =>
                            val expected = PartialRerun
                              .select(recorded, selection, inputs)
                              .fold(
                                error =>
                                  throw new IllegalStateException(
                                    error.toString
                                  ),
                                identity
                              )
                            Outcome(
                              snapshot,
                              selection,
                              result,
                              GraphComparator
                                .compareNormalized(expected, result.graph)
                            )
                          }
                      }
                  }
                }
              }
          }
      }

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(path => Files.deleteIfExists(path))
        ()
      finally stream.close()

  private def externalInputs(
      graph: SessionGraph,
      selection: ReplaySelection,
      data: Map[String, NodeData]
  ): Either[PartialReplayError, PartialReplayInputs] =
    val byId = graph.nodes.map(node => node.id -> node).toMap
    val root = selection match
      case ReplaySelection.Phase(id)   => id
      case ReplaySelection.Subtree(id) => id
    if !byId.contains(root) then
      Left(
        PartialReplayError
          .InvalidSelection(root, "selected node does not exist")
      )
    else
      var included = Set(root)
      var changed = true
      while changed do
        val children = graph.nodes.iterator
          .filter(node => node.parent.exists(included.contains))
          .map(_.id)
          .toSet
        changed = !children.subsetOf(included)
        included ++= children
      val external = graph.nodes
        .filter(node => included(node.id))
        .flatMap(_.inputs)
        .filterNot(included)
        .distinct
      val resolved = external.map { id =>
        byId
          .get(id)
          .flatMap(dataRef)
          .flatMap(ref =>
            data.get(ref.value).map(value => id -> value.toJson)
          ) match
          case Some(value) => Right(value)
          case None =>
            val consumer = graph.nodes
              .find(node => included(node.id) && node.inputs.contains(id))
              .get
              .id
            Left(PartialReplayError.MissingExternalInput(consumer, id))
      }
      resolved.collectFirst { case Left(error) => error } match
        case Some(error) => Left(error)
        case None =>
          Right(PartialReplayInputs(resolved.collect { case Right(v) =>
            v
          }.toMap))

  private def dataRef(node: SessionNode): Option[DataRef] = node match
    case model: SessionNode.ModelCall => Some(model.dataRef)
    case tool: SessionNode.ToolCall   => tool.dataRef
    case _                            => None

end PartialLiveRerun
