package raider.cli.lineage

import raider.dsl.lineage.data.NodeData
import raider.dsl.lineage.replay.ReplayDataError
import raider.dsl.lineage.replay.RecordedSource
import raider.runtime.lineage.model.RecoveryClass
import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.model.SessionNode

/** Integrity verdict of one [[ReplayInterpreter.interpret]] pass (LIN-05E,
  * ADR-027 §2 — replay-recorded is the default replay mode). A pass reports
  * exactly one verdict: the FIRST failure in traversal order wins (the same
  * discipline as `SessionGraph.validated`); a pass with no failure is
  * [[ReplayVerdict.AllResolvable]].
  *
  *   - [[ReplayVerdict.AllResolvable]] — every ModelCall node resolved through
  *     the recorded source;
  *   - [[ReplayVerdict.Unresolved]] — a ModelCall dataRef that exists in the
  *     graph but has no entry in the recorded document;
  *   - [[ReplayVerdict.MissingDataRef]] — a ModelCall node without a usable
  *     dataRef. The frozen graph model carries `dataRef: DataRef` (required),
  *     so "no ref" is representable only as the EMPTY ref value
  *     (`DataRef("")`); the interpreter reports the node id and never consults
  *     the source for it;
  *   - [[ReplayVerdict.KindMismatch]] — a ModelCall dataRef that resolves to a
  *     non-`ModelCallData` entry (phase/tool/delegation data); `expected` /
  *     `actual` are the honest kind labels of the data layer.
  */
enum ReplayVerdict:
  case AllResolvable
  case Unresolved(dataRef: String)
  case MissingDataRef(nodeId: String)
  case KindMismatch(dataRef: String, expected: String, actual: String)

/** One replayed ModelCall node (LIN-05E): the recorded answer IS the data —
  * `data` is the exact `ModelCallData` snapshot resolved from the recorded
  * source, never a re-ask of the model and never a synthesized substitute.
  */
final case class ReplayedModelCall(
    nodeId: String,
    dataRef: String,
    data: NodeData.ModelCallData
)

/** ToolCall obligation recorded by the replay slice (LIN-05E). This card does
  * NOT recompute tool calls — the entry carries graph-level identity only
  * (node, tool name, recovery class, recorded ref) so the NEXT card can
  * recompute deterministic tool calls; no recorded tool data (args, result
  * tails, call outcomes) is copied into the report.
  */
final case class DeferredToolCall(
    nodeId: String,
    tool: String,
    recovery: RecoveryClass,
    dataRef: Option[String]
)

/** Result of one replay-recorded pass (LIN-05E): replayed model calls in
  * traversal order, the tool obligations left to the next card, and the
  * integrity verdict. Pure data over already-loaded inputs — determinism is by
  * construction (see [[ReplayInterpreter]]), so a repeat pass over the same
  * graph + source yields an equal report and a byte-identical [[render]].
  */
final case class ReplayReport(
    rootId: String,
    sessionId: String,
    nodesWalked: Int,
    replayed: Vector[ReplayedModelCall],
    deferredTools: Vector[DeferredToolCall],
    verdict: ReplayVerdict
):

  /** Canonical deterministic text form of the report: entries in traversal
    * order, fixed field order, no map iteration, no timestamps — two equal
    * reports always render byte-identically.
    */
  def render: String =
    val out = new StringBuilder
    out
      .append("replay root=")
      .append(rootId)
      .append(" session=")
      .append(sessionId)
      .append(" walked=")
      .append(nodesWalked)
      .append('\n')
    replayed.foreach { call =>
      out
        .append("model ")
        .append(call.nodeId)
        .append(" ref=")
        .append(call.dataRef)
        .append(" round=")
        .append(call.data.round)
        .append(" model=")
        .append(call.data.model)
        .append(" response=")
        .append(call.data.response)
        .append('\n')
    }
    deferredTools.foreach { tool =>
      out
        .append("tool ")
        .append(tool.nodeId)
        .append(' ')
        .append(tool.tool)
        .append(" recovery=")
        .append(tool.recovery)
        .append(" status=deferred-tool-recompute")
        .append('\n')
    }
    out.append("verdict=").append(ReplayInterpreter.verdictLabel(verdict))
    out.result()

end ReplayReport

/** Replay-recorded interpreter slice (LIN-05E, ADR-027 §2–3): walks a
  * [[SessionGraph]] and rebuilds the recorded run from the data resource — LLM
  * answers come from the record (`RecordedSource`), NEVER from a model call.
  * Pure function over already-loaded inputs: no IO, no model access, no
  * environment reads.
  *
  * Traversal is deterministic: breadth-first roots→children, each level sorted
  * by `(seq, id)` — independent of the `nodes` vector emission order. A
  * visited-set guards malformed graphs against repeat visits (first visit wins;
  * a validated graph never triggers it).
  *
  * Per node kind:
  *   - ModelCall — the dataRef is REQUIRED: an empty ref reports
  *     `MissingDataRef(nodeId)`, an unknown ref `Unresolved(ref)`, a ref to
  *     non-model data `KindMismatch` (typed, from the recorded source); a
  *     resolved ref contributes the recorded [[NodeData.ModelCallData]]
  *     snapshot. The first failure in traversal order becomes the report
  *     verdict; traversal continues so the report stays complete.
  *   - ToolCall — recorded as a [[DeferredToolCall]] obligation with the
  *     per-node status `deferred-tool-recompute`: recomputing deterministic
  *     tool calls is the NEXT card; no tool result is invented here.
  *   - Delegation / Phase — structural traversal only (subtree/phase grouping);
  *     no data is read for them in this slice.
  */
object ReplayInterpreter:

  /** Replays `graph` from the already-loaded recorded `source`. */
  def interpret(graph: SessionGraph, source: RecordedSource): ReplayReport =
    var replayed = Vector.empty[ReplayedModelCall]
    var tools = Vector.empty[DeferredToolCall]
    var verdict = Option.empty[ReplayVerdict]
    var walked = 0
    val visited = scala.collection.mutable.Set.empty[String]

    def firstFailure(v: ReplayVerdict): Unit =
      if verdict.isEmpty then verdict = Some(v)
      ()

    // breadth-first queue of pending nodes, levels kept sorted by (seq, id)
    var queue = graph.roots.sortBy(n => (n.seq, n.id.value))
    while queue.nonEmpty do
      val node = queue.head
      queue = queue.tail
      if visited.contains(node.id.value) then () // malformed-graph guard
      else
        visited += node.id.value
        walked += 1
        node match
          case m: SessionNode.ModelCall =>
            if m.dataRef.value.isEmpty then
              firstFailure(ReplayVerdict.MissingDataRef(m.id.value))
            else
              source.modelOutput(m.dataRef.value) match
                case Right(data) =>
                  replayed :+= ReplayedModelCall(
                    m.id.value,
                    m.dataRef.value,
                    data
                  )
                case Left(ReplayDataError.Missing(ref)) =>
                  firstFailure(ReplayVerdict.Unresolved(ref))
                case Left(
                      ReplayDataError.KindMismatch(ref, expected, actual)
                    ) =>
                  firstFailure(
                    ReplayVerdict.KindMismatch(ref, expected, actual)
                  )
          case t: SessionNode.ToolCall =>
            // obligation only: the recorded tool data (if any) is NOT read
            // into the report — recomputation is the next card's slice.
            tools :+= DeferredToolCall(
              t.id.value,
              t.tool,
              t.recovery,
              t.dataRef.map(_.value)
            )
          case _: SessionNode.Delegation => () // structural
          case _: SessionNode.Phase      => () // structural
        val children = graph.childrenOf
          .getOrElse(node.id.value, Vector.empty)
          .sortBy(n => (n.seq, n.id.value))
        queue = queue :++ children

    ReplayReport(
      graph.rootId.value,
      graph.sessionId.value,
      walked,
      replayed,
      tools,
      verdict.getOrElse(ReplayVerdict.AllResolvable)
    )

  /** Stable text label of a verdict (used by [[ReplayReport.render]]). */
  private[lineage] def verdictLabel(v: ReplayVerdict): String = v match
    case ReplayVerdict.AllResolvable   => "all-resolvable"
    case ReplayVerdict.Unresolved(ref) => "unresolved ref=" + ref
    case ReplayVerdict.MissingDataRef(nodeId) =>
      "missing-data-ref node=" + nodeId
    case ReplayVerdict.KindMismatch(ref, expected, actual) =>
      "kind-mismatch ref=" + ref + " expected=" + expected + " actual=" + actual

end ReplayInterpreter
