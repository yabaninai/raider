package raider.runtime.lineage.reduce

import raider.runtime.events.{EventEnvelope, EventPayload, ToolOutcome}
import raider.runtime.lineage.model.{
  DataRef,
  GraphError,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}
import raider.runtime.lineage.wire.{
  LineageEnvelope,
  LineagePayload,
  LineageWire
}

/** Typed failures of [[LineageReducer.reduce]] / [[LineageReducer.reduceWire]]
  * (LIN-01C, ADR-027; v2 extension ADR-032). Sealed and total over the
  * reducer's own checks: a broken or contradictory event stream is DATA the
  * caller may inspect — never a silent skip and never an exception. The first
  * violation in stream order wins.
  */
enum ReduceError:

  /** Zero envelopes: no campaign identity to reduce from. */
  case Empty

  /** Envelope schemaVersion outside the entry point's allowed set: the frozen
    * v1 entry points ([[LineageReducer.reduce]] /
    * [[LineageReducer.reducePartial]]) allow exactly the pinned revision 1; the
    * versioned entry points ([[LineageReducer.reduceWire]] /
    * [[LineageReducer.reducePartialWire]]) allow 1 and the ADR-032 revision 2.
    * An unknown future version is refused here, never guessed. Defence in
    * depth: the readers ([[raider.runtime.lineage.io.LineageEventReader]],
    * [[raider.runtime.lineage.io.LineageWireReader]]) enforce the same rule for
    * file input; the reducer accepts envelopes directly and re-checks.
    */
  case SchemaVersionUnsupported(found: Int)

  /** Event of a job that is neither the campaign root job nor a job introduced
    * by an earlier `Delegated` event: the parent link of that job's subtree is
    * unknown.
    */
  case UnknownParent(job: String, seq: Long)

  /** A `schemaVersion: 2` envelope of a NON-root job without the correlation
    * field `invocationId` (ADR-032 §1: correlation is required-on-v2 for child
    * jobs, optional on v1 and on the root job). The invocation-level join key
    * is how RAI-144 links a child segment into one provenance tree; without it
    * the child would be an invented edge waiting to happen.
    */
  case MissingCorrelation(job: String, seq: Long)

  /** Per-job `seq` does not run 1,2,3,...: a gap OR a duplicate (both are
    * `found != expected`; mirrors
    * [[raider.runtime.lineage.io.LineageError.SeqGap]]).
    */
  case SeqGap(job: String, expected: Long, found: Long)

  /** `Delegated` names a job that already exists — a second introduction of the
    * same child, or the root job itself. One child subtree cannot have two
    * parents in a tree.
    */
  case DuplicateDelegation(child: String, seq: Long)

  /** Any event for a job after its `Terminal` — a second `Terminal` included.
    * ADR-024 freezes `Terminal` as exactly one, last.
    */
  case AfterTerminal(job: String, seq: Long)

  /** `ModelCallFinished` / `ToolFinished` with no open matching start in the
    * same job; `detail` names the payload kind and its key.
    */
  case UnmatchedFinish(job: String, seq: Long, detail: String)

  /** `PhaseFinished` with no open phase of that name in the same job (ADR-032
    * §1: exit closes the innermost open phase with the name, LIFO).
    */
  case PhaseMismatch(job: String, seq: Long, detail: String)

  /** A second `RunProvenance` in one stream: the cross-run provenance record is
    * at most ONE per campaign (ADR-032 §1).
    */
  case DuplicateProvenance(seq: Long)

  /** The stream ends with a job that emitted events but never reached
    * `Terminal`: an incomplete campaign (e.g. a torn final record) is refused,
    * not silently reduced into a graph that claims completeness. The named job
    * is the first such in first-appearance order. A job introduced by
    * `Delegated` but silent (no events at all) is NOT an error: the delegation
    * node records the attempt, the subtree is empty.
    *
    * [[LineageReducer.reducePartial]] is the tolerant LIN5-03 counterpart for
    * crash prefixes: it keeps the completed nodes instead of refusing. `reduce`
    * itself never tolerates a missing terminal.
    */
  case MissingTerminal(job: String)

  /** Defensive self-check: the constructed graph violated a
    * [[SessionGraph.validated]] invariant. Unreachable under the construction
    * rules below; kept typed so an internal regression can never surface as an
    * invalid graph or an exception.
    */
  case InvariantBroken(violation: GraphError)
end ReduceError

/** Result of the versioned reduction entries (ADR-032 §5): the validated
  * [[SessionGraph]] plus the cross-run provenance record when the stream
  * carried one. Provenance deliberately lives HERE, not on the graph model:
  * GraphHash's canonical form is unchanged (ADR-032 §3 — excluded by contract),
  * so v1 hash comparability is preserved bit-for-bit and provenance equality is
  * asserted by its own acceptance surface (RAI-146), not by the graph hash.
  */
final case class LineageReduction(
    graph: SessionGraph,
    provenance: Option[LineagePayload.RunProvenance]
)

/** Reduces the event stream of ONE campaign (root job + delegated children)
  * into a [[SessionGraph]] (LIN-01C, ADR-027 §3; packet L11; v2 wire ADR-032).
  *
  * Determinism (LIN1-01): the graph is a pure fold over the envelopes in stream
  * order — no wall clock, no RNG, no heap-order iteration — so the same input
  * vector always yields the same node vector and the same [[GraphHash]]. The
  * campaign root job is the `jobId` of the FIRST envelope; every other job must
  * be introduced by a `Delegated` event before its own events appear (otherwise
  * [[ReduceError.UnknownParent]]).
  *
  * Entry points and versions:
  *   - [[reduce]] / [[reducePartial]] take the FROZEN ADR-024 envelopes and
  *     allow exactly `schemaVersion == 1` — the pinned v1 entry points, whose
  *     behavior is unchanged;
  *   - [[reduceWire]] / [[reducePartialWire]] take the versioned
  *     [[LineageEnvelope]]s and allow revisions 1 and 2 (ADR-032); a mixed
  *     v1/v2 stream is legal, every envelope keeps its own version.
  *
  * Validation order, first violation wins:
  *   1. `schemaVersion` in the entry point's allowed set;
  *   2. the envelope's job is the root or an introduced child;
  *   3. a v2 envelope of a non-root job carries `invocationId`
  *      ([[ReduceError.MissingCorrelation]]);
  *   4. per-job `seq` runs 1,2,3,... (gap or duplicate ->
  *      [[ReduceError.SeqGap]]);
  *   5. the job has not reached `Terminal` yet (any later event, including a
  *      second `Terminal` -> [[ReduceError.AfterTerminal]]);
  *   6. payload-specific pairing (finish without open start ->
  *      [[ReduceError.UnmatchedFinish]]; phase exit without an open phase of
  *      that name -> [[ReduceError.PhaseMismatch]]; a second provenance record
  *      -> [[ReduceError.DuplicateProvenance]]).
  * After the stream: every job that emitted at least one event must be terminal
  * ([[ReduceError.MissingTerminal]]; not applicable to the partial variants),
  * then the constructed graph is re-checked with `SessionGraph.validated` as a
  * typed self-check.
  *
  * Nodes and when they are created:
  *   - `ModelCall` — at `ModelCallStarted(round, model, …)`; `seq`/`atMs` are
  *     the start event's. `ModelCallFinished(round)` only closes the open round
  *     (LIFO match by round); duration/usage have no node fields in the frozen
  *     model.
  *   - `ToolCall` — at the matching `ToolFinished` (LIFO match by `callId`
  *     among open starts), carrying `callId`/`tool`/`seq`/`atMs` from the START
  *     event and the verbatim `ToolOutcome`. A tool started but never finished
  *     before its job's `Terminal` is flushed in start order with
  *     `outcome = None` — the call happened, the outcome is unknown, and
  *     unknown is passed as unknown, never coerced.
  *   - `Delegation` — at `Delegated(childJobId, agent)`; the child job's
  *     subtree hangs under this node.
  *   - `Phase` — at `PhaseStarted(name, recovery, note)` (v2, ADR-032): phases
  *     are wire-native since the schema card. `PhaseFinished(name)` closes the
  *     innermost open phase with that name (LIFO per job) and creates no node;
  *     a phase still open at the job's `Terminal` is legal — its node stands
  *     (it started; the stream says nothing about an exit). The phase
  *     `recovery` metadata is stream-carried policy input (ADR-032 §1), not a
  *     graph-model field; the lineage-data v2 resource is its durable surface.
  *
  * Control-plane payloads (`JobStatusChanged`, `Waiting`, `ToolProgress`,
  * `RetryScheduled`, `BudgetChanged`, `Diagnostics`, `DiagnosticsCoalesced`)
  * produce no nodes because the node kinds have no field for them; the stream
  * itself remains the source of truth for them, nothing is dropped or
  * rewritten.
  *
  * Control edges (`parent`): a job's model-call rounds are siblings under the
  * job's base — `None` for the root job, the spawning `Delegation` node for a
  * child job — unless a phase is open, in which case the round parents to the
  * innermost open phase (phases group rounds). Tools and delegations parent to
  * the round whose `ModelCallStarted` was last seen in the job at their START
  * event (tool calls run between model rounds; a call's context is fixed when
  * it starts), falling back to the innermost open phase, then to the job's
  * base. A phase itself parents to the innermost open phase, else the round it
  * opened inside (if any), else the job's base. All of this degenerates to the
  * exact v1 shape when no phase payload occurs.
  *
  * Dataflow edges (`inputs`): each node inputs the job's previously appended
  * node; the FIRST node of a job inputs its base (the spawning `Delegation`
  * node for children, nothing for the root). Phases carry no inputs by
  * definition (grouping, not data). All edges point strictly backward in append
  * order, so the dataflow is acyclic and every parent precedes its child by
  * construction.
  *
  * `DataRef` keys: no data content is invented — events carry redacted
  * summaries only. The reducer emits the canonical lineage-data.json key
  * convention `<job>/round-<n>` (model calls) and `<job>/call-<callId>` (tool
  * calls); the wave-2 recording store (ADR-027 addendum §6.5) binds content to
  * these keys.
  *
  * `RecoveryClass` for TOOL calls (static taxonomy until a tool-recovery wire
  * field exists, ADR-027 §6.2): exec tools (`bash`, `sh`, `process`, `python`)
  * and write tools (`edit`, `fs_edit`, `write`, `fs_write`, `apply_patch`,
  * `patch`, `mv`, `rm`, `mkdir`, `touch`) are `Mutating`; everything else is
  * `ReadOnly` (the ADR default). `Idempotent` is deliberately never guessed
  * from `ToolOutcome`/`RetryScheduled` — a rate-limit retry does not make a
  * mutating tool safe to re-run. PHASE recovery is explicit on the v2 wire
  * (`PhaseStarted.recovery`, validated against the closed enum at decode); it
  * is never inferred here.
  *
  * Relationship to the readers: the readers validate schema/seq for file input
  * and report a torn last line as an honest `TruncatedTail`; because the
  * reducers accept envelopes directly, they re-check the same rules so every
  * guarantee is local to the returned `Either`. A torn FINAL record loses its
  * envelope, so the prefix is an incomplete campaign and reduces to
  * [[ReduceError.MissingTerminal]] — never to a silently complete graph. The
  * partial variants are the tolerant LIN5-03 counterpart for exactly that crash
  * shape.
  */
object LineageReducer:

  /** Static exec-class tool names (ADR-027 §6.2): running them can mutate the
    * world, so live re-run isolation must treat them as Mutating.
    */
  private val ExecTools = Set("bash", "sh", "process", "python")

  /** Static write-class tool names (ADR-027 §6.2). */
  private val WriteTools = Set(
    "edit",
    "fs_edit",
    "write",
    "fs_write",
    "apply_patch",
    "patch",
    "mv",
    "rm",
    "mkdir",
    "touch"
  )

  private def recovery(tool: String): RecoveryClass =
    if ExecTools.contains(tool) || WriteTools.contains(tool) then
      RecoveryClass.Mutating
    else RecoveryClass.ReadOnly

  /** A tool call opened by `ToolStarted`, waiting for its `ToolFinished`.
    * `parent`/`inputs` are fixed at start; the node is appended at finish (or
    * flushed at the job's `Terminal` with an unknown outcome).
    */
  private final case class OpenTool(
      callId: String,
      tool: String,
      startSeq: Long,
      atMs: Long,
      parent: Option[NodeId],
      inputs: Vector[NodeId]
  )

  /** The job and the defining-event `seq` of one appended node — the seq that
    * names the node id (`ModelCallStarted`/`Delegated`/`PhaseStarted` for those
    * kinds, the START's seq for a tool call appended at its finish).
    * [[reducePartialWire]] keeps a node only while this seq precedes the job's
    * partial cut.
    */
  private final case class NodeOrigin(job: String, seq: Long)

  /** Everything one strict fold over `events` produces, before the batch or
    * partial finisher decides the outcome. `failure` is the first strict
    * violation (schema, unknown parent, missing correlation, seq,
    * after-terminal, unmatched finish, phase mismatch, duplicate provenance);
    * the MISSING-terminal condition is deliberately NOT folded in — it is the
    * one check that distinguishes the strict entries from the partial ones.
    */
  private final case class Folded(
      failure: Option[ReduceError],
      first: LineageEnvelope,
      nodes: Vector[(SessionNode, NodeOrigin)],
      terminalJobs: Set[String],
      jobOrder: Vector[String],
      openRounds: Map[String, List[(Int, NodeId, Long)]],
      openTools: Map[String, Vector[OpenTool]],
      openPhases: Map[String, List[(String, NodeId, Long)]],
      provenance: Option[LineagePayload.RunProvenance],
      base: Map[String, NodeId]
  )

  private def fold(
      events: Vector[LineageEnvelope],
      allowedVersions: Set[Int]
  ): Folded =
    val first = events.head
    val rootJob = first.jobId.value

    val nextSeq = scala.collection.mutable.HashMap.empty[String, Long]
    val terminalJobs = scala.collection.mutable.HashSet.empty[String]
    val base = scala.collection.mutable.HashMap.empty[String, NodeId]
    val currentRound = scala.collection.mutable.HashMap.empty[String, NodeId]
    val lastNode = scala.collection.mutable.HashMap.empty[String, NodeId]
    val openRounds =
      scala.collection.mutable.HashMap.empty[String, List[(Int, NodeId, Long)]]
    val openTools =
      scala.collection.mutable.HashMap.empty[String, Vector[OpenTool]]
    // Per-job stack of open phases: (name, nodeId, startSeq); innermost first.
    val openPhases =
      scala.collection.mutable.HashMap
        .empty[String, List[(String, NodeId, Long)]]
    var provenance = Option.empty[LineagePayload.RunProvenance]
    val introduced = scala.collection.mutable.HashSet.empty[String]
    val jobOrder = scala.collection.mutable.ListBuffer.empty[String]
    val nodes = Vector.newBuilder[(SessionNode, NodeOrigin)]

    def nodeId(job: String, seq: Long): NodeId = NodeId(s"$job:n$seq")

    def innermostPhase(job: String): Option[NodeId] =
      openPhases.get(job).flatMap(_.headOption).map(_._2)

    def appendTool(
        job: String,
        open: OpenTool,
        outcome: Option[ToolOutcome]
    ): Unit =
      val id = nodeId(job, open.startSeq)
      nodes += (SessionNode.ToolCall(
        id,
        open.parent,
        open.startSeq,
        open.atMs,
        open.callId,
        open.tool,
        recovery(open.tool),
        outcome,
        open.inputs,
        Some(DataRef(s"$job/call-${open.callId}"))
      ) -> NodeOrigin(job, open.startSeq))
      lastNode(job) = id

    def flushOpenTools(job: String): Unit =
      openTools.getOrElse(job, Vector.empty).foreach { open =>
        appendTool(job, open, None)
      }
      openTools(job) = Vector.empty

    var failure = Option.empty[ReduceError]
    val it = events.iterator
    while failure.isEmpty && it.hasNext do
      val e = it.next()
      val job = e.jobId.value

      // (1) the entry point's allowed wire revisions
      if !allowedVersions.contains(e.schemaVersion) then
        failure = Some(ReduceError.SchemaVersionUnsupported(e.schemaVersion))
      // (2) the job must be the campaign root or an introduced child
      else if job != rootJob && !introduced.contains(job) then
        failure = Some(ReduceError.UnknownParent(job, e.seq))
      // (3) v2 correlation: required on non-root jobs (ADR-032 §1)
      else if e.schemaVersion == LineageWire.V2 && job != rootJob && e.invocationId.isEmpty
      then failure = Some(ReduceError.MissingCorrelation(job, e.seq))
      else
        // (4) per-job seq runs 1,2,3,... — gap or duplicate both fail here
        val expected = nextSeq.getOrElse(job, 1L)
        if e.seq != expected then
          failure = Some(ReduceError.SeqGap(job, expected, e.seq))
        else
          if expected == 1L then jobOrder += job
          nextSeq(job) = expected + 1L
          // (5) Terminal closes the job: exactly one, last (ADR-024 §5)
          if terminalJobs.contains(job) then
            failure = Some(ReduceError.AfterTerminal(job, e.seq))
          else
            e.payload match
              case LineagePayload.Frozen(p) =>
                p match
                  case p: EventPayload.ModelCallStarted =>
                    val id = nodeId(job, e.seq)
                    val input = lastNode.get(job).orElse(base.get(job))
                    nodes += (SessionNode.ModelCall(
                      id,
                      innermostPhase(job).orElse(base.get(job)),
                      e.seq,
                      e.atMs,
                      p.round,
                      p.model,
                      input.toVector,
                      DataRef(s"$job/round-${p.round}")
                    ) -> NodeOrigin(job, e.seq))
                    lastNode(job) = id
                    currentRound(job) = id
                    openRounds(job) =
                      (p.round, id, e.seq) :: openRounds.getOrElse(job, Nil)

                  case EventPayload.ModelCallFinished(round, _, _) =>
                    val open = openRounds.getOrElse(job, Nil)
                    open.find(_._1 == round) match
                      case None =>
                        failure = Some(
                          ReduceError.UnmatchedFinish(
                            job,
                            e.seq,
                            s"ModelCallFinished(round=$round)"
                          )
                        )
                      case Some((_, id, _)) =>
                        openRounds(job) = open.filter(_._2 != id)

                  case p: EventPayload.ToolStarted =>
                    openTools(job) = openTools.getOrElse(job, Vector.empty) :+
                      OpenTool(
                        p.callId,
                        p.tool,
                        e.seq,
                        e.atMs,
                        currentRound
                          .get(job)
                          .orElse(innermostPhase(job))
                          .orElse(base.get(job)),
                        lastNode.get(job).toVector
                      )

                  case _: EventPayload.ToolProgress =>
                    () // streaming tail, no node

                  case p: EventPayload.ToolFinished =>
                    val open = openTools.getOrElse(job, Vector.empty)
                    val idx = open.lastIndexWhere(_.callId == p.callId)
                    if idx < 0 then
                      failure = Some(
                        ReduceError.UnmatchedFinish(
                          job,
                          e.seq,
                          s"ToolFinished(callId=${p.callId})"
                        )
                      )
                    else
                      appendTool(job, open(idx), Some(p.outcome))
                      openTools(job) = open.patch(idx, Nil, 1)

                  case p: EventPayload.Delegated =>
                    if p.childJobId.value == rootJob || introduced.contains(
                        p.childJobId.value
                      )
                    then
                      failure = Some(
                        ReduceError.DuplicateDelegation(
                          p.childJobId.value,
                          e.seq
                        )
                      )
                    else
                      val id = nodeId(job, e.seq)
                      nodes += (SessionNode.Delegation(
                        id,
                        currentRound
                          .get(job)
                          .orElse(innermostPhase(job))
                          .orElse(base.get(job)),
                        e.seq,
                        e.atMs,
                        p.childJobId,
                        p.agent,
                        lastNode.get(job).toVector
                      ) -> NodeOrigin(job, e.seq))
                      lastNode(job) = id
                      introduced += p.childJobId.value
                      base(p.childJobId.value) = id

                  // Control-plane payloads: no node kind in the frozen model;
                  // the stream stays the source of truth for them.
                  case _: EventPayload.JobStatusChanged     => ()
                  case _: EventPayload.Waiting              => ()
                  case _: EventPayload.RetryScheduled       => ()
                  case _: EventPayload.BudgetChanged        => ()
                  case _: EventPayload.Diagnostics          => ()
                  case _: EventPayload.DiagnosticsCoalesced => ()

                  case EventPayload.Terminal(_) =>
                    flushOpenTools(
                      job
                    ) // started-but-unfinished: outcome unknown
                    terminalJobs += job

              // ---- v2 payloads (ADR-032) ----
              case p: LineagePayload.PhaseStarted =>
                val id = nodeId(job, e.seq)
                val parent = innermostPhase(job)
                  .orElse(currentRound.get(job))
                  .orElse(base.get(job))
                nodes += (SessionNode.Phase(id, parent, e.seq, p.name, p.note)
                  -> NodeOrigin(job, e.seq))
                lastNode(job) = id
                openPhases(job) =
                  (p.name, id, e.seq) :: openPhases.getOrElse(job, Nil)

              case p: LineagePayload.PhaseFinished =>
                val stack = openPhases.getOrElse(job, Nil)
                val idx = stack.indexWhere(_._1 == p.name)
                if idx < 0 then
                  failure = Some(
                    ReduceError.PhaseMismatch(
                      job,
                      e.seq,
                      s"PhaseFinished(name=${p.name})"
                    )
                  )
                else
                  // pop the innermost phase with this name; outer phases stay
                  openPhases(job) = stack.take(idx) ++ stack.drop(idx + 1)

              case p: LineagePayload.RunProvenance =>
                if provenance.isDefined then
                  failure = Some(ReduceError.DuplicateProvenance(e.seq))
                else provenance = Some(p)

    Folded(
      failure,
      first,
      nodes.result(),
      terminalJobs.toSet,
      jobOrder.toVector,
      openRounds.toMap,
      openTools.toMap,
      openPhases.toMap,
      provenance,
      base.toMap
    )

  /** Reduces a PARTIAL campaign — a crash prefix whose tail may be gone — into
    * the graph of its completed nodes (LIN5-03, ADR-027; W10 review F-L28-3).
    * Pure and deterministic like [[reduce]]: a pure fold over the envelopes in
    * stream order, same `SessionGraph`/[[GraphHash]] result for the same input
    * vector, every returned graph re-checked by `SessionGraph.validated`.
    *
    * The batch contract of [[reduce]] is NOT relaxed anywhere else; only the
    * missing-terminal condition changes:
    *
    *   - all strict checks stay EXACTLY as strict — schemaVersion, unknown
    *     parent, missing correlation, per-job seq, after-terminal, unmatched
    *     finish, phase mismatch and duplicate provenance fail with the same
    *     [[ReduceError]] constructors as in `reduce`; damage is never papered
    *     over into a "plausible" partial graph;
    *   - `MissingTerminal` is NEVER returned. A terminally-complete job keeps
    *     its batch nodes verbatim (including the unknown-outcome flush of
    *     started-but-unfinished tools at its `Terminal`); a job WITHOUT
    *     `Terminal` keeps only its COMPLETED nodes — no half-nodes:
    *     - a round without `ModelCallFinished` on the slice contributes no node
    *       (the batch reducer creates it at start; here the cut removes it
    *       again);
    *     - a tool without `ToolFinished` contributes no node (batch would flush
    *       it only at the job's `Terminal`, which did not happen);
    *     - a phase without `PhaseFinished` contributes no node — the phase is
    *       an open construct exactly like an unfinished round;
    *     - the job's cut is the earliest seq that opened one of its still-open
    *       constructs, and a node survives only while its defining event
    *       precedes that cut — later nodes of the same job are dropped with it
    *       so no parent/input edge dangles;
    *   - a child job keeps nodes only while its base `Delegation` node (which
    *     lives in the parent and can itself fall past the parent's cut) is
    *     kept, so the retained subgraph always resolves every edge.
    *
    * A prefix can therefore shrink to an EMPTY node vector (e.g. the crash hit
    * before the first round finished) — still a valid, honest, hashable graph;
    * and an intact campaign reduces to exactly what [[reduce]] returns.
    */
  private def finishFromFold(
      f: Folded,
      partial: Boolean
  ): Either[ReduceError, LineageReduction] =
    f.failure match
      case Some(err) => Left(err)
      case None if partial =>
        val rootJob = f.first.jobId.value
        val cut = cutAfter(f)
        val retained = scala.collection.mutable.HashMap.empty[String, Boolean]
        val kept = Vector.newBuilder[SessionNode]
        f.nodes.foreach { case (node, origin) =>
          val baseKept = origin.job == rootJob || f.base
            .get(origin.job)
            .exists(b => retained.getOrElse(b.value, false))
          val keep =
            origin.seq < cut.getOrElse(origin.job, Long.MaxValue) && baseKept
          retained(node.id.value) = keep
          if keep then kept += node
        }
        finish(f.first, kept.result(), f.provenance)
      case None =>
        f.jobOrder.iterator.find(job => !f.terminalJobs.contains(job)) match
          case Some(job) => Left(ReduceError.MissingTerminal(job))
          case None =>
            finish(f.first, f.nodes.map(_._1), f.provenance)

  /** Strict reduction of the FROZEN v1 envelopes (pinned entry point: exactly
    * `schemaVersion == 1`; the versioned stream goes through [[reduceWire]]).
    */
  def reduce(events: Vector[EventEnvelope]): Either[ReduceError, SessionGraph] =
    if events.isEmpty then Left(ReduceError.Empty)
    else
      finishFromFold(
        fold(events.map(LineageWire.wrap), Set(LineageWire.V1)),
        partial = false
      ).map(_.graph)

  /** Strict reduction of the VERSIONED stream (ADR-032): revisions 1 and 2, a
    * mixed stream legal, every envelope keeping its own version. The three v2
    * payload cases map to graph nodes ([[SessionNode.Phase]]) and to the
    * provenance record on [[LineageReduction]]; GraphHash is unchanged (ADR-032
    * §3 — new metadata excluded from the canonical form by contract).
    */
  def reduceWire(
      events: Vector[LineageEnvelope]
  ): Either[ReduceError, LineageReduction] =
    if events.isEmpty then Left(ReduceError.Empty)
    else
      finishFromFold(
        fold(events, Set(LineageWire.V1, LineageWire.V2)),
        partial = false
      )

  /** Partial (crash-prefix) reduction of the FROZEN v1 envelopes — unchanged
    * pinned entry point; see [[finishFromFold]] and the [[reducePartial]]
    * contract above.
    */
  def reducePartial(
      events: Vector[EventEnvelope]
  ): Either[ReduceError, SessionGraph] =
    if events.isEmpty then Left(ReduceError.Empty)
    else
      finishFromFold(
        fold(events.map(LineageWire.wrap), Set(LineageWire.V1)),
        partial = true
      ).map(_.graph)

  /** Partial (crash-prefix) reduction of the VERSIONED stream (ADR-032): the
    * LIN5-03 tolerant entry for v2 campaigns — an open phase at the cut is an
    * open construct and drops with the cut, like an unfinished round.
    */
  def reducePartialWire(
      events: Vector[LineageEnvelope]
  ): Either[ReduceError, LineageReduction] =
    if events.isEmpty then Left(ReduceError.Empty)
    else
      finishFromFold(
        fold(events, Set(LineageWire.V1, LineageWire.V2)),
        partial = true
      )

  /** Per-job retention bound of the partial reduce: a job's node survives only
    * while its defining `seq` is strictly BELOW the bound. Terminal jobs keep
    * everything (batch behavior); a non-terminal job's bound is the earliest
    * seq that opened one of its still-open constructs — an unfinished round's
    * `ModelCallStarted`, an unfinished tool's `ToolStarted` or an unfinished
    * phase's `PhaseStarted` — and `Long.MaxValue` when nothing is open (all
    * emitted constructs finished).
    */
  private def cutAfter(f: Folded): Map[String, Long] =
    f.jobOrder.iterator.map { job =>
      val bound =
        if f.terminalJobs.contains(job) then Long.MaxValue
        else
          val opens =
            f.openRounds.getOrElse(job, Nil).map(_._3) ++
              f.openTools.getOrElse(job, Nil).map(_.startSeq) ++
              f.openPhases.getOrElse(job, Nil).map(_._3)
          if opens.isEmpty then Long.MaxValue else opens.min
      job -> bound
    }.toMap

  /** Shared finisher: identity + node vector -> validated [[SessionGraph]] (+
    * provenance) or the typed self-check failure.
    */
  private def finish(
      first: LineageEnvelope,
      nodes: Vector[SessionNode],
      provenance: Option[LineagePayload.RunProvenance]
  ): Either[ReduceError, LineageReduction] =
    val graph = SessionGraph(first.rootId, first.sessionId, nodes)
    graph.validated match
      case Right(valid) => Right(LineageReduction(valid, provenance))
      case Left(violation) =>
        Left(ReduceError.InvariantBroken(violation))

end LineageReducer
