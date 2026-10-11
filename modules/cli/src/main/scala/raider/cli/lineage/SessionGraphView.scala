package raider.cli.lineage

import raider.dsl.lineage.proj.{LineageView, NodeKind, NodeView}
import raider.runtime.lineage.model.{
  GraphError,
  RecoveryClass,
  SessionGraph,
  SessionNode
}

/** Cli-layer adapter from the runtime graph model
  * (`raider.runtime.lineage.model.SessionGraph`, LIN-01) to the dsl projection
  * input (`raider.dsl.lineage.proj.LineageView`, LIN-02C) — ADR-027 wave-2 item
  *   2. It lives in the cli layer because `dsl` cannot depend on `runtime` (the
  *      dsl module stays model-free), while cli already sees both.
  *
  * The conversion is a pure, deterministic 1:1 structural rewrite of every
  * frozen kind; no payload ever flows through — the view carries references
  * only, exactly like the generated program it feeds (ADR-027 §2: prompts and
  * tool args/results stay in the lineage-data resource, never inline).
  *
  * Documented static mappings (frozen until the wave-2 schema card):
  *
  *   - '''RecoveryClass → String''' ([[recoveryName]]): the enum case names
  *     themselves — `ReadOnly` / `Idempotent` / `Mutating`. These are exactly
  *     the strings `lineage-data.json` schemaVersion 1 uses for the `recovery`
  *     field, so adapter output agrees with the frozen data contract.
  *   - '''DataRef → String''': the exact reference key (`DataRef.value`, e.g.
  *     `d0`) — the key into the lineage-data `data` map. Never a payload.
  *   - '''runId''': `SessionGraph.rootId.value` — the graph's run identity. The
  *     session id is transport-level and is not part of the view contract.
  *   - '''Delegation/Phase dataRef''': the wave-1 model carries no dataRef on
  *     these kinds (only `model_call`/`tool_call` nodes hold one), so the view
  *     maps them to `None` even though lineage-data v1 emits a dataRef there;
  *     those payloads are simply not addressable from the graph yet.
  *
  * Structural safety: the graph is validated first ([[SessionGraph.validated]])
  * and every violation becomes a typed [[ViewError.Inconsistent]] — duplicate
  * ids, missing parents/inputs, parent-order violations (which also covers
  * control cycles) and dataflow cycles are data, never crashes. A future wire
  * revision adding a `SessionNode` case (WIRE-FREEZE: wave-3 schema card)
  * yields [[ViewError.UnknownKind]] instead of a silent skip.
  */
object SessionGraphView:

  enum ViewError:
    case UnknownKind(detail: String)
    case Inconsistent(detail: String)

  /** RecoveryClass → recovery string of the frozen lineage-data v1 contract:
    * the static tool-name taxonomy of ADR-027 §6.2 — closed set until the
    * wave-2 schema card extends it.
    */
  def recoveryName(r: RecoveryClass): String =
    r match
      case RecoveryClass.ReadOnly   => "ReadOnly"
      case RecoveryClass.Idempotent => "Idempotent"
      case RecoveryClass.Mutating   => "Mutating"

  /** Deterministic conversion of the whole graph: roots in emission order,
    * children recursively by [[SessionGraph.childrenOf]] (which preserves the
    * emission order per parent), kind → NodeKind 1:1, parentId → parentId,
    * dataRef → dataRef. Same graph → same view, byte for byte.
    */
  def toView(g: SessionGraph): Either[ViewError, LineageView] =
    g.validated.left.map(inconsistent).flatMap { graph =>
      def build(
          n: SessionNode,
          parentId: Option[String]
      ): Either[ViewError, NodeView] =
        // widened to Matchable so the defensive UnknownKind branch stays
        // reachable-by-construction for future wire revisions (same trick as
        // LineageRenderer; SessionNode is sealed today).
        val node: Matchable = n
        val kind: Either[ViewError, NodeKind] = node match
          case m: SessionNode.ModelCall =>
            Right(NodeKind.ModelCall(m.model, m.round))
          case t: SessionNode.ToolCall =>
            Right(NodeKind.ToolCall(t.tool, recoveryName(t.recovery)))
          case d: SessionNode.Delegation =>
            Right(NodeKind.Delegation(d.agent))
          case p: SessionNode.Phase =>
            Right(NodeKind.Phase(p.name))
          case other =>
            // Option-wrapped check (sanctioned orNull/Option pattern, keeps
            // DisableSyntax.noNulls green): a null foreign node is still
            // described as "null", never a NullPointerException.
            val describe =
              Option(other).fold("null")(_.getClass.getSimpleName)
            Left(
              ViewError
                .UnknownKind("unsupported session node kind: " + describe)
            )
        kind.flatMap { k =>
          g.childrenOf
            .getOrElse(n.id.value, Vector.empty)
            .foldLeft[Either[ViewError, Vector[NodeView]]](
              Right(Vector.empty)
            ) { (acc, child) =>
              acc.flatMap(vs => build(child, Some(n.id.value)).map(vs :+ _))
            }
            .map(children =>
              NodeView(n.id.value, k, parentId, children, dataRefOf(n))
            )
        }
      g.roots
        .foldLeft[Either[ViewError, Vector[NodeView]]](Right(Vector.empty)) {
          (acc, root) => acc.flatMap(vs => build(root, None).map(vs :+ _))
        }
        .map { rootViews =>
          new LineageView:
            def runId: String = graph.rootId.value
            def roots: Vector[NodeView] = rootViews
        }
    }

  /** DataRef → String: the exact reference key into the lineage-data `data` map
    * (`ModelCall` always carries one, `ToolCall` optionally; the other two
    * kinds carry none in the wave-1 model).
    */
  private def dataRefOf(n: SessionNode): Option[String] =
    n match
      case m: SessionNode.ModelCall => Some(m.dataRef.value)
      case t: SessionNode.ToolCall  => t.dataRef.map(_.value)
      case _                        => None

  /** Structural violations are data: every GraphError becomes a typed
    * Inconsistent with the offending ids in the detail string.
    */
  private def inconsistent(e: GraphError): ViewError.Inconsistent =
    e match
      case GraphError.Duplicate(id) =>
        ViewError.Inconsistent("duplicate node id: " + id.value)
      case GraphError.MissingParent(id, parent) =>
        ViewError.Inconsistent(
          "node " + id.value + " references missing parent/input: " + parent.value
        )
      case GraphError.ParentOrderViolation(child, parent) =>
        ViewError.Inconsistent(
          "parent " + parent.value + " is not before child " + child.value
        )
      case GraphError.DataflowCycle(id) =>
        ViewError.Inconsistent("dataflow cycle through node: " + id.value)
