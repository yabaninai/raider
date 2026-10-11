package raider.cli.lineage

import raider.dsl.lineage.replay.RecordedSource
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.model.NodeId
import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.model.SessionNode

/** Typed failure of [[PhaseReplay.replayPhase]] (LIN-07-full, ADR-027 §2):
  *   - [[PhaseError.NoPhases]] — the graph carries NO phase nodes at all, so
  *     there is nothing to replay; checked BEFORE the id lookup;
  *   - [[PhaseError.NodeNotFound]] — the requested id does not exist in the
  *     graph OR exists but does not name a phase node;
  *   - [[PhaseError.Cycle]] — the phase's parent-edge descent re-entered an
  *     already visited node. Impossible for a validated graph (parents come
  *     from a tree), but `SessionGraph` construction never validates, so the
  *     descent must stop typed instead of hanging (the same discipline as
  *     [[SubtreeError.Cycle]]).
  */
enum PhaseError:
  case NoPhases
  case NodeNotFound(id: NodeId)
  case Cycle(id: NodeId)

/** One per-child row of a phase verdict (LIN-07-full): every DIRECT child of
  * the phase node gets exactly one line, in child order `(seq, id)`:
  *   - [[NodeVerdict.Resolved]] — a ModelCall whose dataRef resolved through
  *     the recorded source;
  *   - [[NodeVerdict.Obligation]] — a node the slice replay does NOT recompute:
  *     a ToolCall (carrying its [[DeferredToolCall]] obligation detail) or a
  *     structural Delegation / nested Phase (`deferred` empty);
  *   - [[NodeVerdict.Unresolved]] — a ModelCall whose dataRef failed typed in
  *     the subtree verdict (missing entry, kind mismatch or the empty ref); the
  *     full typed failure stays in [[PhaseVerdict.subtree]].
  */
enum NodeVerdict:
  case Resolved(nodeId: String, dataRef: String)
  case Obligation(nodeId: String, deferred: Option[DeferredToolCall])
  case Unresolved(nodeId: String, dataRef: String)

/** Verdict of ONE phase replay (LIN-07-full): the phase identity, the GraphHash
  * of its SubtreeReplay slice, the whole-slice verify outcome and the per-child
  * rows. Pure data over already-loaded inputs — a repeat pass over the same
  * graph + source yields an equal verdict.
  */
final case class PhaseVerdict(
    phaseId: NodeId,
    name: String,
    graphHash: String,
    subtree: SubtreeVerdict,
    children: Vector[NodeVerdict]
)

/** Phased partial replay (LIN-07-full, parent LIN-07, ADR-027 §2): replays the
  * recorded data PHASE BY PHASE, reusing [[SubtreeReplay]] (synthesis + verify
  * + slice GraphHash) for each phase node — never duplicating it.
  *
  * Pure functions over already-loaded inputs: no IO, no model access, no
  * environment reads; every phase is synthesized and verified INDEPENDENTLY, so
  * a broken dataRef in one phase never enters another phase's verdict (the
  * LIN-7 «частичный» guarantee).
  *
  * Traversal ([[PhaseReplay.phases]]): the same deterministic discipline as the
  * LIN-05E interpreter — breadth-first roots→children, each level sorted by
  * `(seq, id)`, visited-set guard — restricted to Phase nodes.
  */
object PhaseReplay:

  /** Every Phase node of `graph` as `(id, name)`, in traversal order
    * (breadth-first roots→children, each level sorted by `(seq, id)`).
    * Terminates on malformed graphs (visited-set guard), never hangs.
    */
  def phases(graph: SessionGraph): Vector[(NodeId, String)] =
    val visited = scala.collection.mutable.Set.empty[String]
    var found = Vector.empty[(NodeId, String)]
    var queue = graph.roots.sortBy(n => (n.seq, n.id.value))
    while queue.nonEmpty do
      val node = queue.head
      queue = queue.tail
      if !visited.contains(node.id.value) then
        visited += node.id.value
        node match
          case p: SessionNode.Phase => found :+= (p.id, p.name)
          case _                    => () // non-phase nodes are walk-through
        queue = queue :++ graph.childrenOf
          .getOrElse(node.id.value, Vector.empty)
          .sortBy(n => (n.seq, n.id.value))
    found

  /** Replays ONE phase: synthesizes the phase node's subtree through
    * [[SubtreeReplay.subtree]], verifies it through [[SubtreeReplay.verify]]
    * against the recorded `source`, hashes the slice through [[GraphHash]] and
    * adds one [[NodeVerdict]] row per DIRECT child of the phase. The child rows
    * are derived from the SAME subtree verdict — the row index and the
    * whole-slice outcome cannot contradict each other.
    */
  def replayPhase(
      graph: SessionGraph,
      phaseId: NodeId,
      source: RecordedSource
  ): Either[PhaseError, PhaseVerdict] =
    if phases(graph).isEmpty then Left(PhaseError.NoPhases)
    else
      graph.nodes.find(_.id == phaseId) match
        case Some(phase: SessionNode.Phase) =>
          SubtreeReplay.subtree(graph, phaseId) match
            case Left(err) => Left(toPhaseError(err))
            case Right(slice) =>
              val verdict = SubtreeReplay.verify(slice, source)
              Right(
                PhaseVerdict(
                  phaseId = phase.id,
                  name = phase.name,
                  graphHash = GraphHash(slice),
                  subtree = verdict,
                  children = childRows(graph, phaseId, verdict)
                )
              )
        // the id exists but is not a phase (or does not exist at all):
        // the error names the REQUESTED id either way
        case _ => Left(PhaseError.NodeNotFound(phaseId))

  /** Replays ALL phases of `graph` in [[PhaseReplay.phases]] order, one
    * independent verdict per phase. Phases are never shared state: a failing
    * (even data-broken) phase does not remove or corrupt any other verdict. A
    * phase whose slice synthesis stops on a malformed parent cycle has no row
    * here — [[PhaseReplay.replayPhase]] gives the typed reason.
    */
  def replayAll(
      graph: SessionGraph,
      source: RecordedSource
  ): Vector[PhaseVerdict] =
    phases(graph).flatMap { case (id, _) =>
      replayPhase(graph, id, source).toOption
    }

  private def toPhaseError(err: SubtreeError): PhaseError = err match
    case SubtreeError.NodeNotFound(id) => PhaseError.NodeNotFound(id)
    case SubtreeError.Cycle(id)        => PhaseError.Cycle(id)

  /** The EXACT failing node a subtree failure carries (every [[SubtreeFailure]]
    * variant names its node first).
    */
  private def failingNode(f: SubtreeFailure): String = f match
    case SubtreeFailure.MissingDataRef(nodeId)        => nodeId
    case SubtreeFailure.Unresolved(nodeId, _)         => nodeId
    case SubtreeFailure.KindMismatch(nodeId, _, _, _) => nodeId

  /** One row per DIRECT child of the phase, in child order `(seq, id)`, derived
    * from the subtree verdict's failures and tool obligations (single source of
    * truth). Grandchild failures stay inside the whole-slice outcome — a child
    * is judged by its OWN dataRef only.
    */
  private def childRows(
      graph: SessionGraph,
      phaseId: NodeId,
      verdict: SubtreeVerdict
  ): Vector[NodeVerdict] =
    val failures: Map[String, SubtreeFailure] = verdict match
      case SubtreeVerdict.Resolved(_, _) => Map.empty
      case SubtreeVerdict.PartiallyResolved(fs, _) =>
        fs.map(f => failingNode(f) -> f).toMap
    val tools: Map[String, DeferredToolCall] = verdict match
      case SubtreeVerdict.Resolved(_, ts) =>
        ts.map(t => t.nodeId -> t).toMap
      case SubtreeVerdict.PartiallyResolved(_, ts) =>
        ts.map(t => t.nodeId -> t).toMap
    graph.childrenOf
      .getOrElse(phaseId.value, Vector.empty)
      .sortBy(n => (n.seq, n.id.value))
      .map {
        case m: SessionNode.ModelCall =>
          if failures.contains(m.id.value) then
            NodeVerdict.Unresolved(m.id.value, m.dataRef.value)
          else NodeVerdict.Resolved(m.id.value, m.dataRef.value)
        case t: SessionNode.ToolCall =>
          NodeVerdict.Obligation(t.id.value, tools.get(t.id.value))
        case d: SessionNode.Delegation =>
          NodeVerdict.Obligation(d.id.value, None) // structural
        case p: SessionNode.Phase =>
          NodeVerdict.Obligation(p.id.value, None) // nested phase: structural
      }

end PhaseReplay
