package raider.runtime.lineage.replay

import raider.core.RaiderError
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.{NodeId, SessionGraph, SessionNode}
import java.nio.file.Path
import zio.{Scope, ZIO}

/** A typed selection of a phase or any graph node whose descendants form a
  * subtree.
  */
enum ReplaySelection:
  case Phase(id: NodeId)
  case Subtree(root: NodeId)

/** Explicit pointer-resolved inputs crossing the selected subtree boundary. */
final case class PartialReplayInputs(values: Map[NodeId, String])

final case class PartialRerunRequest(
    recordedGraph: SessionGraph,
    selectedGraph: SessionGraph,
    selection: ReplaySelection,
    inputs: PartialReplayInputs,
    invocationId: String,
    limits: BudgetLimits,
    maxRounds: Int
)

final case class PartialRerunExecution(
    invocationId: String,
    graph: SessionGraph
)

trait PartialRerunBackend:

  /** Executes fresh work for the selected graph. Implementations honor limits
    * and interruption.
    */
  def execute(
      request: PartialRerunRequest
  ): ZIO[Scope, RaiderError, PartialRerunExecution]

enum PartialReplayError:
  case InvalidSelection(id: NodeId, reason: String)
  case MissingExternalInput(node: NodeId, input: NodeId)
  case UnusedExternalInput(id: NodeId)
  case InvalidInvocationId

/** Plans and dispatches a fresh partial rerun. The recorded graph is provenance
  * only.
  */
object PartialRerun:

  def select(
      graph: SessionGraph,
      selection: ReplaySelection,
      inputs: PartialReplayInputs
  ): Either[PartialReplayError, SessionGraph] =
    val byId = graph.nodes.map(n => n.id -> n).toMap
    val root = selection match
      case ReplaySelection.Phase(id) =>
        byId.get(id) match
          case Some(_: SessionNode.Phase) => Right(id)
          case Some(_) =>
            Left(
              PartialReplayError.InvalidSelection(
                id,
                "selected node is not a phase"
              )
            )
          case None =>
            Left(
              PartialReplayError.InvalidSelection(
                id,
                "selected node does not exist"
              )
            )
      case ReplaySelection.Subtree(id) =>
        if byId.contains(id) then Right(id)
        else
          Left(
            PartialReplayError.InvalidSelection(
              id,
              "selected node does not exist"
            )
          )

    root.flatMap { selectedRoot =>
      var included = Set(selectedRoot)
      var changed = true
      while changed do
        val next = graph.nodes.iterator
          .filter(n => n.parent.exists(included.contains))
          .map(_.id)
          .toSet
        changed = !next.subsetOf(included)
        included ++= next

      val selected = graph.nodes.filter(n => included(n.id))
      val externalInputs = selected.flatMap(_.inputs).filterNot(included).toSet
      val missing = externalInputs.diff(inputs.values.keySet).headOption
      val unused = inputs.values.keySet.diff(externalInputs).headOption
      missing match
        case Some(id) =>
          val consumer = selected.find(_.inputs.contains(id)).get.id
          Left(PartialReplayError.MissingExternalInput(consumer, id))
        case None if unused.isDefined =>
          Left(PartialReplayError.UnusedExternalInput(unused.get))
        case None =>
          val selectedIds = selected.map(_.id).toSet
          val graphSlice = selected.map { node =>
            // Parent edges at the subtree boundary are intentionally cut; all data dependencies
            // must either be in-scope or have an explicitly resolved input value.
            node match
              case p: SessionNode.Phase if p.id == selectedRoot =>
                p.copy(parent = None)
              case m: SessionNode.ModelCall if m.id == selectedRoot =>
                m.copy(parent = None, inputs = m.inputs.filter(selectedIds))
              case t: SessionNode.ToolCall if t.id == selectedRoot =>
                t.copy(parent = None, inputs = t.inputs.filter(selectedIds))
              case d: SessionNode.Delegation if d.id == selectedRoot =>
                d.copy(parent = None, inputs = d.inputs.filter(selectedIds))
              case m: SessionNode.ModelCall =>
                m.copy(inputs = m.inputs.filter(selectedIds))
              case t: SessionNode.ToolCall =>
                t.copy(inputs = t.inputs.filter(selectedIds))
              case d: SessionNode.Delegation =>
                d.copy(inputs = d.inputs.filter(selectedIds))
              case p: SessionNode.Phase => p
          }
          Right(graph.copy(nodes = graphSlice))
    }

  /** Execute a planned partial graph through the live rerun seam. The committed
    * graph and resolved input values provide provenance only; the backend is
    * given the selected slice and the caller's fresh prompt/model. A backend
    * failure or interruption remains a failure/interruption and never returns
    * partial state as a completed result.
    */
  def executeLive(
      backend: RerunBackend,
      graph: SessionGraph,
      selection: ReplaySelection,
      inputs: PartialReplayInputs,
      workspace: Path,
      prompt: String,
      model: String,
      snapshotGeneration: String,
      limits: BudgetLimits,
      maxRounds: Int
  ): ZIO[Scope, RaiderError, PartialRerunExecution] =
    val partialBackend = new PartialRerunBackend:
      override def execute(
          request: PartialRerunRequest
      ): ZIO[Scope, RaiderError, PartialRerunExecution] =
        LiveRerun
          .execute(
            backend,
            RerunRequest(
              workspace,
              prompt,
              model,
              snapshotGeneration,
              request.recordedGraph,
              request.selectedGraph,
              request.inputs,
              request.limits,
              request.maxRounds
            )
          )
          .map(result =>
            PartialRerunExecution(request.invocationId, result.graph)
          )
    execute(partialBackend, graph, selection, inputs, limits, maxRounds)

  def execute(
      backend: PartialRerunBackend,
      graph: SessionGraph,
      selection: ReplaySelection,
      inputs: PartialReplayInputs,
      limits: BudgetLimits,
      maxRounds: Int
  ): ZIO[Scope, RaiderError, PartialRerunExecution] =
    if maxRounds < 1 then
      ZIO.fail(
        RaiderError.InputValidation("partial rerun maxRounds must be positive")
      )
    else
      val invocationId = java.util.UUID.randomUUID().toString
      select(graph, selection, inputs) match
        case Left(error) =>
          ZIO.fail(RaiderError.InputValidation(error.toString))
        case Right(selected) =>
          val request = PartialRerunRequest(
            graph,
            selected,
            selection,
            inputs,
            invocationId,
            limits,
            maxRounds
          )
          backend.execute(request).flatMap { result =>
            if result.invocationId != invocationId then
              ZIO.fail(
                RaiderError
                  .InputValidation("partial rerun invocationId mismatch")
              )
            else ZIO.succeed(result)
          }
