package raider.runtime.lineage.model

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.ToolOutcome

/** Identity of one lineage node (ADR-027 §3). Opaque string wrapper: node ids
  * are stable within one SessionGraph and are the reference keys for both the
  * parent edge (control) and the [[SessionNode]] `inputs` edges (dataflow).
  */
final case class NodeId(value: String)

/** Key into the lineage data resource (lineage-data.json, ADR-027 §2): full
  * prompts/responses/args live in the data file, never inline in code or in the
  * graph itself.
  */
final case class DataRef(value: String)

/** Recovery policy class of a tool call (ADR-027 §2, LIN-06). Closed set on
  * purpose: it is the branch axis of future live re-run isolation. Source until
  * the wave-2 schema card is the static tool-name taxonomy documented in
  * ADR-027 §6.2 — the model carries the verdict, not the guess.
  */
enum RecoveryClass:
  case ReadOnly
  case Idempotent
  case Mutating

/** Structural violation detected by [[SessionGraph.validated]]. Typed, never
  * require/assert: a malformed graph is data the caller may want to inspect,
  * not a programming error.
  */
enum GraphError:
  case Duplicate(id: NodeId)
  case MissingParent(id: NodeId, parent: NodeId)
  case ParentOrderViolation(child: NodeId, parent: NodeId)
  case DataflowCycle(id: NodeId)

/** One node of the session call tree (ADR-027 §3 + addendum §6.3).
  *
  * The four frozen kinds: LLM rounds, tool calls (open `tool` kind string — new
  * tool kinds never change this contract; only [[RecoveryClass]] and the node
  * kinds themselves are closed), delegations (subtrees of child jobs) and user
  * phases (compaction/partial-replay slicing). Every non-phase node carries
  * `inputs` — dataflow edges to upstream node ids; phases carry no inputs by
  * definition (they are grouping, not data).
  */
sealed trait SessionNode extends Product with Serializable:
  def id: NodeId
  def parent: Option[NodeId]
  def seq: Long
  def inputs: Vector[NodeId]

object SessionNode:

  final case class ModelCall(
      id: NodeId,
      parent: Option[NodeId],
      seq: Long,
      atMs: Long,
      round: Int,
      model: String,
      inputs: Vector[NodeId],
      dataRef: DataRef
  ) extends SessionNode

  final case class ToolCall(
      id: NodeId,
      parent: Option[NodeId],
      seq: Long,
      atMs: Long,
      callId: String,
      tool: String,
      recovery: RecoveryClass,
      outcome: Option[ToolOutcome],
      inputs: Vector[NodeId],
      dataRef: Option[DataRef]
  ) extends SessionNode

  final case class Delegation(
      id: NodeId,
      parent: Option[NodeId],
      seq: Long,
      atMs: Long,
      childJobId: JobId,
      agent: String,
      inputs: Vector[NodeId]
  ) extends SessionNode

  final case class Phase(
      id: NodeId,
      parent: Option[NodeId],
      seq: Long,
      name: String,
      note: Option[String]
  ) extends SessionNode:
    def inputs: Vector[NodeId] = Vector.empty

end SessionNode

/** Event-sourced call-tree snapshot of one session (ADR-027 §3.1).
  *
  * Pure data — construction never validates. Structural invariants (unique ids,
  * resolvable parents, parents strictly before children, acyclic dataflow) are
  * checked explicitly by [[SessionGraph.validated]] so callers decide what a
  * violation means. The reducer (wave-2) is the producer that guarantees them;
  * [[GraphHash]] normalizes away node order.
  */
final case class SessionGraph(
    rootId: RootId,
    sessionId: SessionId,
    nodes: Vector[SessionNode]
):

  /** Structural validation over this graph's node vector.
    *
    *   - ids are unique;
    *   - every parent and every dataflow input resolves to an existing node;
    *   - a parent appears strictly BEFORE its child in `nodes` (the reducer's
    *     topological emission order);
    *   - the dataflow edge set (inputs) is acyclic.
    *
    * The first violation in emission order wins.
    */
  def validated: Either[GraphError, SessionGraph] =
    // pass 1: unique ids + positions
    val positions = scala.collection.mutable.HashMap.empty[String, Int]
    var failure = Option.empty[GraphError]
    nodes.zipWithIndex.foreach { (n, i) =>
      if failure.isEmpty then
        if positions.contains(n.id.value) then
          failure = Some(GraphError.Duplicate(n.id))
        else positions(n.id.value) = i
    }

    // pass 2: parents/inputs must EXIST somewhere in the graph
    if failure.isEmpty then
      nodes.foreach { n =>
        if failure.isEmpty then
          n.parent.find(p => !positions.contains(p.value)).foreach { missing =>
            failure = Some(GraphError.MissingParent(n.id, missing))
          }
        if failure.isEmpty then
          n.inputs.find(i => !positions.contains(i.value)).foreach { missing =>
            failure = Some(GraphError.MissingParent(n.id, missing))
          }
      }

    // pass 3: parents strictly before children (emission order)
    if failure.isEmpty then
      nodes.foreach { n =>
        if failure.isEmpty then
          n.parent.foreach { p =>
            if positions(p.value) >= positions(n.id.value) then
              failure = Some(GraphError.ParentOrderViolation(n.id, p))
          }
      }

    // pass 4: dataflow acyclicity
    if failure.isEmpty then
      val byId = nodes.map(n => n.id.value -> n).toMap
      // 0 = unvisited, 1 = on stack, 2 = done
      val state = scala.collection.mutable.HashMap.empty[String, Int]
      def visit(id: NodeId): Option[GraphError] =
        state.getOrElse(id.value, 0) match
          case 1 => Some(GraphError.DataflowCycle(id))
          case 2 => None
          case _ =>
            state(id.value) = 1
            val found = byId(id.value).inputs
              .map(visit)
              .find(_.isDefined)
              .flatten
            state(id.value) = 2
            found
      failure = nodes.iterator.map(n => visit(n.id)).find(_.isDefined).flatten

    failure.toLeft(this)

  /** Children grouped by parent id; order inside each group follows `nodes`
    * emission order.
    */
  lazy val childrenOf: Map[String, Vector[SessionNode]] =
    nodes
      .filter(_.parent.isDefined)
      .groupBy(_.parent.get.value)
      .withDefaultValue(Vector.empty)

  /** Root nodes (no parent), in emission order. */
  lazy val roots: Vector[SessionNode] = nodes.filter(_.parent.isEmpty)

end SessionGraph
