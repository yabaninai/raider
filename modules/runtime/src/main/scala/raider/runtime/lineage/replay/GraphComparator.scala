package raider.runtime.lineage.replay

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.lineage.model.{
  DataRef,
  GraphHash,
  NodeId,
  SessionGraph,
  SessionNode
}

/** Why two session graphs diverged at the first diverging node (LIN-05B, packet
  * L25, ADR-027). Closed set on purpose: it covers every way the deterministic
  * replay mechanics can disagree with a recorded graph. There is deliberately
  * NO `HashMismatch` case — if the structural comparison finds no divergence,
  * the canonical forms are equal and so are the hashes
  * ([[raider.runtime.lineage.model.GraphHash]] is a function of structure
  * alone); a same-shape hash mismatch is impossible by construction and
  * LIN5-B-03 pins that invariant.
  */
enum DivergenceReason:

  /** The node id exists in the expected graph but not in the actual one. */
  case MissingNode

  /** The node id exists in the actual graph but not in the expected one. */
  case ExtraNode

  /** Both sides have the node id, but as different frozen kinds. The kind names
    * are the canonical discriminators of
    * [[raider.runtime.lineage.model.GraphHash]] (`model_call`, `tool_call`,
    * `delegation`, `phase`).
    */
  case KindMismatch(expectedKind: String, actualKind: String)

  /** Both sides have the node id with the same kind, but one field differs.
    * `field` is the canonical field name; `expected`/`actual` are the two sides
    * rendered for diagnosis (unescaped values, `-` for none/empty).
    */
  case FieldMismatch(field: String, expected: String, actual: String)
end DivergenceReason

/** One divergence located in the graph: the campaign root (expected side) and
  * the node where it happened. `nodeId` is `None` only for the graph-header
  * fields (`rootId`/`sessionId`) which belong to no node.
  */
final case class Divergence(
    rootId: RootId,
    nodeId: Option[NodeId],
    reason: DivergenceReason
)

/** Structural mismatch report of [[GraphComparator.compare]]: the FIRST
  * divergence in canonical `(seq, id)` node order (the same order
  * [[raider.runtime.lineage.model.GraphHash]] canonicalizes with, so "first" is
  * a graph property, not an emission-order accident) plus both hashes for
  * diagnosis.
  */
final case class GraphDiff(
    firstDivergence: Divergence,
    expectedHash: String,
    actualHash: String
)

/** Deterministic structural comparison of two session graphs (LIN-05B, packet
  * L25, ADR-027). This is the replay-comparator SLICE: pure mechanics over
  * already-reduced [[SessionGraph]] values — no event reading, no projection
  * execution, no checkpoints.
  *
  * NOT part of this card (next card's obligations, LIN5-03 onward): checkpoint
  * slicing (comparing the graph prefix up to a checkpoint instead of the whole
  * graph), projection execution, and replaying with an LLM from the recording.
  * Nothing here reads or executes projections; callers hand over two complete
  * graphs.
  *
  * Comparison rules:
  *   - nodes are keyed by [[NodeId]]; the node-id sets must be equal
  *     ([[DivergenceReason.MissingNode]] / [[DivergenceReason.ExtraNode]]);
  *   - a node present on both sides must have the same frozen kind
  *     ([[DivergenceReason.KindMismatch]]) and the same canonical fields in the
  *     [[raider.runtime.lineage.model.GraphHash]] field order — the FIRST
  *     differing field is reported ([[DivergenceReason.FieldMismatch]]);
  *   - the graph header (`rootId`, `sessionId`) is compared before the nodes,
  *     mirroring the canonical payload (`root=`/`session=` lines precede node
  *     lines);
  *   - emission order of the `nodes` vector is NOT structure: [[GraphHash]]
  *     sorts canonically, so the comparator is order-invariant the same way.
  *
  * On full structural agreement the result is `Right(hash)` where `hash` is the
  * canonical [[raider.runtime.lineage.model.GraphHash]] hex of BOTH graphs
  * (`GraphHash` is an object over `SessionGraph`, so the right side carries the
  * string it computes). Structural equality ⇒ equal hashes is an invariant,
  * pinned by LIN5-B-03 — the comparator never re-derives or second-guesses the
  * hash.
  *
  * Preconditions: both graphs are expected to be `validated` (the reducer
  * guarantees it for replay input; the corpus and tests build valid graphs).
  * The comparator does not re-validate; duplicate node ids (impossible in a
  * validated graph) are outside this contract.
  */
object GraphComparator:

  /** Canonical header values of [[normalize]]: the normalized comparison is
    * identity-free, so the run-varying campaign identity (root/session ids —
    * `root-<seed>` in the corpus, `loop_<n>` for a standalone loop) is replaced
    * by constants instead of being compared raw.
    */
  private val NormalizedRoot = "root"
  private val NormalizedSession = "session"

  /** Canonical `atMs` of [[normalize]]: wall-clock publish time is not graph
    * identity (the same defuse discipline as the LIN3-B-02 lineage summary
    * acceptance, which normalizes `rootId`/`atMs` before comparing two runs).
    */
  private val NormalizedAtMs = 0L

  /** Compares the expected (recorded) graph against the actual (replayed)
    * graph: `Left(GraphDiff)` with the first divergence, or `Right(hash)` when
    * the structures are equal.
    */
  def compare(
      expected: SessionGraph,
      actual: SessionGraph
  ): Either[GraphDiff, String] =
    diverge(expected, actual) match
      case Some(d) =>
        Left(GraphDiff(d, GraphHash(expected), GraphHash(actual)))
      case None =>
        Right(GraphHash(actual))

  /** Identity-free comparison for end-to-end replay (LIN-06B-1, packet L48,
    * ADR-027 §2): [[normalize]]s BOTH sides first, then runs exactly the
    * [[compare]] mechanics (structure + kind + canonical fields, first
    * divergence in canonical `(seq, id)` order) over the normalized pair. A
    * replayed campaign and its recording differ in every run-varying identity
    * field — job ids (`j_1` vs the recorded job), root/session ids and
    * wall-clock `atMs` — while their STRUCTURE must agree; `compare` alone
    * would report those identity fields as divergences.
    *
    * Normalization (deterministic, see [[normalize]]):
    *   - node ids are rewritten through the canonical job map: the campaign
    *     root job becomes `job-1` and every other job is numbered by the FIRST
    *     `Delegation` node leading to it — a breadth walk over the
    *     job-delegation tree that visits each job's nodes in per-job `seq`
    *     order (unique by the reducer's SeqGap invariant). Raw ids — hence job
    *     NAMES — never take part in any ordering (rename-invariance, F-L51-1);
    *   - the graph header is replaced by canonical constants and `atMs` is
    *     zeroed (run-varying identity/time is not graph identity);
    *   - everything else (kinds, `seq`, `round`, `model`, `callId`, `tool`,
    *     recovery, outcome, dataflow edges, dataRef keys modulo the job prefix)
    *     is compared exactly like [[compare]].
    *
    * A `Left(GraphDiff)` from this method reports NORMALIZED ids/roots (the
    * divergence of the canonical forms); both hashes are the canonical
    * [[GraphHash]] of the normalized graphs, which is also the value `Right`
    * carries. The hash is therefore a function of structure + data identity
    * alone: two replays of the same record over different job identities yield
    * the SAME normalized hash (H-3 determinism). Because the job numbering is
    * derived from the delegation tree, this holds for ARBITRARY job renames —
    * including renames that reverse the alphabetical order of two jobs first
    * seen at equal `seq` (F-L51-1) — on reducer-shaped graphs (one root job;
    * every other job introduced by exactly one `Delegation` — the shape
    * [[raider.runtime.lineage.reduce.LineageReducer]] guarantees). Degenerate
    * graphs outside that shape keep their independent roots' first-seen
    * `(seq, id)` numbering: still deterministic, but only the delegation-tree
    * shape carries the rename-invariance promise. Any real structural drift
    * still diverges.
    *
    * Preconditions are the ones of [[compare]] (validated graphs); the job-map
    * parsing additionally assumes the reducer's node-id convention
    * `<jobId>:n<seq>` and dataRef convention `<jobId>/...` — a node id without
    * the `:n` separator is its own job, and a dataRef without a `/` prefix
    * stays unchanged.
    */
  def compareNormalized(
      expected: SessionGraph,
      actual: SessionGraph
  ): Either[GraphDiff, String] =
    compare(normalize(expected), normalize(actual))

  /** The canonical form [[compareNormalized]] compares: same node kinds and
    * structure, identity-free ids.
    *
    *   - Job map: derived from the job-delegation TREE, never from raw names
    *     (F-L51-1). The roots — jobs nobody delegates TO (no `Delegation`
    *     `childJobId` names them) — are numbered first, in first-seen canonical
    *     `(seq, id)` order of their nodes; a reducer-shaped graph has exactly
    *     one root (the campaign root job) and it becomes `job-1`. The remaining
    *     jobs are numbered by a breadth walk over the tree: each job's nodes
    *     are visited in per-job `seq` order (unique — the reducer's SeqGap
    *     invariant), and every `Delegation` node hands its `childJobId` the
    *     next label, so a job's number is the rank of the FIRST Delegation node
    *     leading to it. The numbering therefore depends only on the tree shape
    *     and the per-job delegation seqs — renaming jobs (even so as to reverse
    *     their alphabetical order at equal `seq`) cannot change it. An EMPTY
    *     child subtree (a silent child job is legal) still gets a label from
    *     its delegation. A degenerate graph with several independent roots
    *     (outside the reducer's shape) keeps first-seen `(seq, id)` numbering
    *     for them: deterministic, but only the tree shape carries the
    *     rename-invariance promise.
    *   - Node ids, `parent`, `inputs` are rewritten to `job-N:n<seq>` through
    *     that map; a `Delegation` `childJobId` becomes `job-N`; a `DataRef`
    *     `<job>/rest` becomes `job-N/rest` (a ref with no `/` stays as-is).
    *   - Header → `root`/`session` constants, `atMs` → 0.
    *
    * Emission order of the `nodes` vector is preserved (parents still precede
    * children, so the normalized graph re-validates), and the map never depends
    * on heap-order iteration: the numbering is a pure function of the graph —
    * tree shape plus seqs — not of names or map order.
    */
  private[replay] def normalize(g: SessionGraph): SessionGraph =
    val jobs = scala.collection.mutable.LinkedHashMap.empty[String, String]
    def register(job: String): Unit =
      if !jobs.contains(job) then jobs(job) = s"job-${jobs.size + 1}"
    // nodes of each job in emission order; per-job seq is unique (the
    // reducer's SeqGap invariant), so the per-job seq sort needs no id
    // tie-break — raw ids never order anything (F-L51-1)
    val nodesOfJob =
      scala.collection.mutable.HashMap.empty[String, Vector[SessionNode]]
    g.nodes.foreach { n =>
      val job = jobPrefixOf(n.id.value)
      nodesOfJob(job) = nodesOfJob.getOrElse(job, Vector.empty) :+ n
    }
    // every job of the graph, first-seen in canonical (seq, id) order of its
    // nodes, then delegation targets without own nodes (silent child jobs)
    val known = scala.collection.mutable.LinkedHashSet.empty[String]
    val delegatedTo = scala.collection.mutable.HashSet.empty[String]
    g.nodes
      .sortBy(n => (n.seq, n.id.value))
      .foreach(n => known += jobPrefixOf(n.id.value))
    g.nodes.foreach {
      case d: SessionNode.Delegation =>
        known += d.childJobId.value
        delegatedTo += d.childJobId.value
      case _ => ()
    }
    val queue = scala.collection.mutable.Queue.empty[String]
    // roots: nobody delegates to them. Reducer shape: exactly one, the
    // campaign root job → job-1; degenerate shapes fall back to first-seen
    // (seq, id) order for their extra roots.
    known.foreach { job =>
      if !delegatedTo.contains(job) then
        register(job)
        queue.enqueue(job)
    }
    // breadth walk: a Delegation numbers its child with the next label, so
    // jobs are numbered in the order of the first Delegation leading to them
    while queue.nonEmpty do
      val job = queue.dequeue()
      nodesOfJob
        .getOrElse(job, Vector.empty)
        .sortBy(_.seq)
        .foreach {
          case d: SessionNode.Delegation =>
            val child = d.childJobId.value
            if !jobs.contains(child) then
              register(child)
              queue.enqueue(child)
          case _ => ()
        }
    def renNode(id: NodeId): NodeId =
      val (job, suffix) = splitJob(id.value)
      NodeId(jobs.getOrElse(job, job) + suffix)
    def renJob(id: JobId): JobId =
      JobId(jobs.getOrElse(id.value, id.value))
    def renRef(r: DataRef): DataRef =
      val (job, suffix) = splitRef(r.value)
      DataRef(jobs.getOrElse(job, job) + suffix)
    SessionGraph(
      RootId(NormalizedRoot),
      SessionId(NormalizedSession),
      g.nodes.map {
        case SessionNode.ModelCall(id, p, seq, _, round, model, inputs, dr) =>
          SessionNode.ModelCall(
            renNode(id),
            p.map(renNode),
            seq,
            NormalizedAtMs,
            round,
            model,
            inputs.map(renNode),
            renRef(dr)
          )
        case SessionNode.ToolCall(
              id,
              p,
              seq,
              _,
              callId,
              tool,
              recovery,
              outcome,
              inputs,
              dr
            ) =>
          SessionNode.ToolCall(
            renNode(id),
            p.map(renNode),
            seq,
            NormalizedAtMs,
            callId,
            tool,
            recovery,
            outcome,
            inputs.map(renNode),
            dr.map(renRef)
          )
        case SessionNode.Delegation(id, p, seq, _, child, agent, inputs) =>
          SessionNode.Delegation(
            renNode(id),
            p.map(renNode),
            seq,
            NormalizedAtMs,
            renJob(child),
            agent,
            inputs.map(renNode)
          )
        case SessionNode.Phase(id, p, seq, name, note) =>
          SessionNode.Phase(renNode(id), p.map(renNode), seq, name, note)
      }
    )

  /** `(job, ":n<seq>" suffix)` of a node id — the LAST `:n` separator splits
    * the reducer's `<jobId>:n<seq>` convention; an id without one is its own
    * job with an empty suffix.
    */
  private def splitJob(id: String): (String, String) =
    val i = id.lastIndexOf(":n")
    if i < 0 then (id, "") else (id.take(i), id.drop(i))

  private def jobPrefixOf(id: String): String = splitJob(id)._1

  /** `(job, "/rest" suffix)` of a dataRef — the FIRST `/` splits the reducer's
    * `<jobId>/...` convention; a ref without one keeps its whole value as the
    * "job" so an unknown prefix is left unchanged rather than mangled.
    */
  private def splitRef(ref: String): (String, String) =
    val i = ref.indexOf('/')
    if i < 0 then (ref, "") else (ref.take(i), ref.drop(i))

  private def diverge(
      expected: SessionGraph,
      actual: SessionGraph
  ): Option[Divergence] =
    // header first, mirroring the canonical payload: root= then session=
    if expected.rootId != actual.rootId then
      Some(
        Divergence(
          expected.rootId,
          None,
          DivergenceReason.FieldMismatch(
            "rootId",
            expected.rootId.value,
            actual.rootId.value
          )
        )
      )
    else if expected.sessionId != actual.sessionId then
      Some(
        Divergence(
          expected.rootId,
          None,
          DivergenceReason.FieldMismatch(
            "sessionId",
            expected.sessionId.value,
            actual.sessionId.value
          )
        )
      )
    else nodeDivergence(expected, actual)

  private def nodeDivergence(
      expected: SessionGraph,
      actual: SessionGraph
  ): Option[Divergence] =
    val exp = expected.nodes.map(n => n.id.value -> n).toMap
    val act = actual.nodes.map(n => n.id.value -> n).toMap
    // deterministic candidate order: canonical (seq, id), like GraphHash;
    // the expected side's seq is the reference when both sides have the id
    val candidates =
      (exp.keySet ++ act.keySet).toVector
        .map(id => (exp.getOrElse(id, act(id)).seq, id))
        .sorted
    candidates.iterator
      .flatMap { (seq, id) =>
        (exp.get(id), act.get(id)) match
          case (Some(e), None) =>
            Some(
              Divergence(
                expected.rootId,
                Some(e.id),
                DivergenceReason.MissingNode
              )
            )
          case (None, Some(a)) =>
            Some(
              Divergence(
                expected.rootId,
                Some(a.id),
                DivergenceReason.ExtraNode
              )
            )
          case (Some(e), Some(a)) =>
            if kindName(e) != kindName(a) then
              Some(
                Divergence(
                  expected.rootId,
                  Some(e.id),
                  DivergenceReason.KindMismatch(kindName(e), kindName(a))
                )
              )
            else
              fieldDivergence(e, a)
                .map(r => Divergence(expected.rootId, Some(e.id), r))
          case (None, None) => None // unreachable: id comes from the union
      }
      .nextOption()

  /** First differing canonical field of two same-kind nodes. Field order and
    * names mirror [[raider.runtime.lineage.model.GraphHash.canonicalNode]]
    * exactly (id is the key and is not a field; parent/seq/atMs lead, matching
    * the canonical line layout).
    */
  private def fieldDivergence(
      e: SessionNode,
      a: SessionNode
  ): Option[DivergenceReason.FieldMismatch] =
    canonicalFields(e)
      .zip(canonicalFields(a))
      .collectFirst {
        case ((name, ev), (_, av)) if ev != av =>
          DivergenceReason.FieldMismatch(name, ev, av)
      }

  /** Canonical discriminator names — the GraphHash line prefixes. */
  private def kindName(n: SessionNode): String = n match
    case _: SessionNode.ModelCall  => "model_call"
    case _: SessionNode.ToolCall   => "tool_call"
    case _: SessionNode.Delegation => "delegation"
    case _: SessionNode.Phase      => "phase"

  /** `(field name, rendered value)` in canonical field order. Rendering is the
    * canonical VALUE convention (`-` for none/empty, enums by name) without
    * hash escaping: the diff is a diagnostic for humans, the hash itself stays
    * the GraphHash canonical form.
    */
  private def canonicalFields(n: SessionNode): Vector[(String, String)] =
    n match
      case SessionNode.ModelCall(
            _,
            parent,
            seq,
            atMs,
            round,
            model,
            inputs,
            dataRef
          ) =>
        Vector(
          "parent" -> optNode(parent),
          "seq" -> seq.toString,
          "atMs" -> atMs.toString,
          "round" -> round.toString,
          "model" -> model,
          "inputs" -> refs(inputs),
          "dataRef" -> dataRef.value
        )
      case SessionNode.ToolCall(
            _,
            parent,
            seq,
            atMs,
            callId,
            tool,
            recovery,
            outcome,
            inputs,
            dataRef
          ) =>
        Vector(
          "parent" -> optNode(parent),
          "seq" -> seq.toString,
          "atMs" -> atMs.toString,
          "callId" -> callId,
          "tool" -> tool,
          "recovery" -> recovery.toString,
          "outcome" -> outcome.fold("-")(_.toString),
          "inputs" -> refs(inputs),
          "dataRef" -> dataRef.fold("-")(_.value)
        )
      case SessionNode.Delegation(
            _,
            parent,
            seq,
            atMs,
            childJobId,
            agent,
            inputs
          ) =>
        Vector(
          "parent" -> optNode(parent),
          "seq" -> seq.toString,
          "atMs" -> atMs.toString,
          "childJobId" -> childJobId.value,
          "agent" -> agent,
          "inputs" -> refs(inputs)
        )
      case SessionNode.Phase(_, parent, seq, name, note) =>
        Vector(
          "parent" -> optNode(parent),
          "seq" -> seq.toString,
          "name" -> name,
          "note" -> note.getOrElse("-")
        )

  private def optNode(p: Option[NodeId]): String =
    p.fold("-")(_.value)

  private def refs(ids: Vector[NodeId]): String =
    if ids.isEmpty then "-" else ids.map(_.value).mkString(",")

end GraphComparator
