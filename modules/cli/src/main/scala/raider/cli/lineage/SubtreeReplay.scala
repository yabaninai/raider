package raider.cli.lineage

import raider.dsl.lineage.replay.ReplayDataError
import raider.dsl.lineage.replay.RecordedSource
import raider.runtime.lineage.model.NodeId
import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.model.SessionNode

/** Typed failure of [[SubtreeReplay.subtree]] synthesis (LIN-07, ADR-027 §2):
  *   - [[SubtreeError.NodeNotFound]] — the requested subtree root does not
  *     exist in the graph;
  *   - [[SubtreeError.Cycle]] — the parent-edge descent re-entered an already
  *     visited node. Impossible for a validated graph (parents come from a
  *     tree), but `SessionGraph` construction never validates, so the descent
  *     must stop typed instead of hanging.
  */
enum SubtreeError:
  case NodeNotFound(id: NodeId)
  case Cycle(id: NodeId)

/** Typed verify failure of one ModelCall node inside the subtree (LIN-07) —
  * always carries the EXACT node id, unlike the raw [[ReplayDataError]] which
  * only names the dataRef:
  *   - [[SubtreeFailure.MissingDataRef]] — the node carries the EMPTY ref value
  *     (the frozen graph model has a required `DataRef`); the recorded source
  *     is never consulted for it;
  *   - [[SubtreeFailure.Unresolved]] — the ref has no entry in the recorded
  *     document;
  *   - [[SubtreeFailure.KindMismatch]] — the ref resolves to a
  *     non-`ModelCallData` entry (phase/tool/delegation data).
  */
enum SubtreeFailure:
  case MissingDataRef(nodeId: String)
  case Unresolved(nodeId: String, dataRef: String)

  case KindMismatch(
      nodeId: String,
      dataRef: String,
      expected: String,
      actual: String
  )

/** Integrity verdict of one [[SubtreeReplay.verify]] pass (LIN-07). ToolCall
  * nodes are listed as [[DeferredToolCall]] obligations in BOTH cases — this
  * slice never recomputes a tool call (the same discipline as the LIN-05E
  * replay interpreter):
  *   - [[SubtreeVerdict.Resolved]] — every ModelCall dataRef resolved through
  *     the recorded source; `count` is the number of resolved model calls;
  *   - [[SubtreeVerdict.PartiallyResolved]] — at least one ModelCall failed
  *     typed; `failures` lists every failing node in traversal order (the
  *     resolving nodes stay silent).
  */
enum SubtreeVerdict:
  case Resolved(count: Int, tools: Vector[DeferredToolCall])

  case PartiallyResolved(
      failures: Vector[SubtreeFailure],
      tools: Vector[DeferredToolCall]
  )

/** Partial replay of a subtree/phase (LIN-07, ADR-027 §2): synthesizes the
  * verifiable subgraph rooted at one node and checks its recorded data —
  * WITHOUT executing anything and without re-asking any model.
  *
  * Pure functions over already-loaded inputs: no IO, no model access, no
  * environment reads; a repeat pass over the same inputs yields equal results.
  *
  * Synthesis ([[SubtreeReplay.subtree]]): breadth-first descent from the
  * requested root over `childrenOf` (each level sorted by `(seq, id)`), then
  * the slice is emitted in the ORIGINAL `nodes` emission order — a restriction
  * of a parent-before-child order stays parent-before-child, so the slice of a
  * validated graph passes `validated`. Per node:
  *   - the slice ROOT loses its parent edge (it IS the slice root); every
  *     descendant keeps its parent verbatim;
  *   - kind, seq, `dataRef` and all other node fields survive verbatim;
  *   - dataflow `inputs` referencing nodes OUTSIDE the slice are cut — a
  *     dangling edge into the un-sliced rest of the session would make the
  *     synthesized graph structurally invalid; edges within the slice survive.
  * The synthesized graph keeps the session's `rootId`/`sessionId`, so it is a
  * first-class input for [[GraphHash]] (a full-tree slice is hash-equal to the
  * original graph).
  *
  * Verification ([[SubtreeReplay.verify]]): the same deterministic traversal as
  * the LIN-05E interpreter (breadth-first roots→children, sorted by
  * `(seq, id)`, visited-set guard): ModelCall refs must resolve through the
  * recorded source (`Unresolved`/`KindMismatch`/`MissingDataRef` — typed, with
  * the exact node; ALL failures are collected, traversal continues so the
  * report stays complete); ToolCall nodes are listed as obligations only;
  * Delegation/Phase nodes are structural.
  */
object SubtreeReplay:

  /** Synthesizes the subgraph of `graph` rooted at `root`. */
  def subtree(
      graph: SessionGraph,
      root: NodeId
  ): Either[SubtreeError, SessionGraph] =
    if !graph.nodes.exists(_.id == root) then
      Left(SubtreeError.NodeNotFound(root))
    else
      var failure = Option.empty[SubtreeError]
      val visited = scala.collection.mutable.Set.empty[String]
      var pending = Vector(root)
      while pending.nonEmpty && failure.isEmpty do
        val id = pending.head
        pending = pending.tail
        if visited.contains(id.value) then
          failure = Some(SubtreeError.Cycle(id))
        else
          visited += id.value
          val children = graph.childrenOf
            .getOrElse(id.value, Vector.empty)
            .sortBy(n => (n.seq, n.id.value))
          pending = pending :++ children.map(_.id)
      failure.toLeft {
        val members = visited.toSet
        SessionGraph(
          graph.rootId,
          graph.sessionId,
          graph.nodes
            .filter(n => members.contains(n.id.value))
            .map(n => sliceNode(n, root, members))
        )
      }

  /** Verifies the recorded data of an already synthesized `sub` against the
    * recorded `source`.
    */
  def verify(sub: SessionGraph, source: RecordedSource): SubtreeVerdict =
    var failures = Vector.empty[SubtreeFailure]
    var tools = Vector.empty[DeferredToolCall]
    var resolved = 0
    val visited = scala.collection.mutable.Set.empty[String]
    var queue = sub.roots.sortBy(n => (n.seq, n.id.value))
    while queue.nonEmpty do
      val node = queue.head
      queue = queue.tail
      if visited.contains(node.id.value) then () // malformed-graph guard
      else
        visited += node.id.value
        node match
          case m: SessionNode.ModelCall =>
            if m.dataRef.value.isEmpty then
              failures :+= SubtreeFailure.MissingDataRef(m.id.value)
            else
              source.modelOutput(m.dataRef.value) match
                case Right(_) =>
                  resolved += 1
                case Left(ReplayDataError.Missing(ref)) =>
                  failures :+= SubtreeFailure.Unresolved(m.id.value, ref)
                case Left(
                      ReplayDataError.KindMismatch(ref, expected, actual)
                    ) =>
                  failures :+= SubtreeFailure.KindMismatch(
                    m.id.value,
                    ref,
                    expected,
                    actual
                  )
          case t: SessionNode.ToolCall =>
            // obligation only: recorded tool data is NOT read into the verdict
            tools :+= DeferredToolCall(
              t.id.value,
              t.tool,
              t.recovery,
              t.dataRef.map(_.value)
            )
          case _: SessionNode.Delegation => () // structural
          case _: SessionNode.Phase      => () // structural
        queue = queue :++ sub.childrenOf
          .getOrElse(node.id.value, Vector.empty)
          .sortBy(n => (n.seq, n.id.value))
    if failures.isEmpty then SubtreeVerdict.Resolved(resolved, tools)
    else SubtreeVerdict.PartiallyResolved(failures, tools)

  /** Slice-local view of one node: the slice root drops its parent edge;
    * dataflow inputs outside the slice are cut; everything else verbatim.
    * Returns the SAME instance when nothing changes (a full-tree slice is then
    * node-identical to the original graph).
    */
  private def sliceNode(
      n: SessionNode,
      root: NodeId,
      members: Set[String]
  ): SessionNode =
    val parent = if n.id == root then None else n.parent
    def kept(inputs: Vector[NodeId]) =
      inputs.filter(i => members.contains(i.value))
    n match
      case p: SessionNode.Phase =>
        // phases carry no inputs by definition
        if p.parent == parent then p else p.copy(parent = parent)
      case m: SessionNode.ModelCall =>
        val inputs = kept(m.inputs)
        if m.parent == parent && inputs == m.inputs then m
        else m.copy(parent = parent, inputs = inputs)
      case t: SessionNode.ToolCall =>
        val inputs = kept(t.inputs)
        if t.parent == parent && inputs == t.inputs then t
        else t.copy(parent = parent, inputs = inputs)
      case d: SessionNode.Delegation =>
        val inputs = kept(d.inputs)
        if d.parent == parent && inputs == d.inputs then d
        else d.copy(parent = parent, inputs = inputs)

end SubtreeReplay
