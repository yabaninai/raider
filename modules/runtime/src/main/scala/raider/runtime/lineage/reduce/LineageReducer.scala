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

/** Typed failures of [[LineageReducer.reduce]] (LIN-01C, ADR-027). Sealed and
  * total over the reducer's own checks: a broken or contradictory event stream
  * is DATA the caller may inspect — never a silent skip and never an exception.
  * The first violation in stream order wins.
  */
enum ReduceError:

  /** Zero envelopes: no campaign identity to reduce from. */
  case Empty

  /** Envelope schemaVersion is not the frozen wire revision 1. Defence in
    * depth: [[raider.runtime.lineage.io.LineageEventReader]] enforces the same
    * rule for file input; `reduce` accepts envelopes directly and re-checks.
    */
  case SchemaVersionUnsupported(found: Int)

  /** Event of a job that is neither the campaign root job nor a job introduced
    * by an earlier `Delegated` event: the parent link of that job's subtree is
    * unknown.
    */
  case UnknownParent(job: String, seq: Long)

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

/** Reduces the event stream of ONE campaign (root job + delegated children)
  * into a [[SessionGraph]] (LIN-01C, ADR-027 §3; packet L11).
  *
  * Determinism (LIN1-01): the graph is a pure fold over the envelopes in stream
  * order — no wall clock, no RNG, no heap-order iteration — so the same input
  * vector always yields the same node vector and the same [[GraphHash]]. The
  * campaign root job is the `jobId` of the FIRST envelope; every other job must
  * be introduced by a `Delegated` event before its own events appear (otherwise
  * [[ReduceError.UnknownParent]]).
  *
  * Validation order, first violation wins:
  *   1. `schemaVersion == 1`;
  *   2. the envelope's job is the root or an introduced child;
  *   3. per-job `seq` runs 1,2,3,... (gap or duplicate ->
  *      [[ReduceError.SeqGap]]);
  *   4. the job has not reached `Terminal` yet (any later event, including a
  *      second `Terminal` -> [[ReduceError.AfterTerminal]]);
  *   5. payload-specific pairing (finish without open start ->
  *      [[ReduceError.UnmatchedFinish]]).
  * After the stream: every job that emitted at least one event must be terminal
  * ([[ReduceError.MissingTerminal]]), then the constructed graph is re-checked
  * with `SessionGraph.validated` as a typed self-check.
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
  *   - `Phase` — NEVER created: there is no phase case on the frozen wire
  *     (schema-freeze; wave-2 schema card, ADR-027 addendum §6.1). Same for the
  *     other control-plane payloads (`JobStatusChanged`, `Waiting`,
  *     `ToolProgress`, `RetryScheduled`, `BudgetChanged`, `Diagnostics`,
  *     `DiagnosticsCoalesced`): they produce no nodes because the frozen node
  *     kinds have no field for them; the stream itself remains the source of
  *     truth for them, nothing is dropped or rewritten.
  *
  * Control edges (`parent`): a job's model-call rounds are siblings under the
  * job's base — `None` for the root job, the spawning `Delegation` node for a
  * child job. Tools and delegations parent to the round whose
  * `ModelCallStarted` was last seen in the job at their START event (tool calls
  * run between model rounds; a call's context is fixed when it starts), falling
  * back to the job's base when no round exists yet.
  *
  * Dataflow edges (`inputs`): each node inputs the job's previously appended
  * node; the FIRST node of a job inputs its base (the spawning `Delegation`
  * node for children, nothing for the root). All edges point strictly backward
  * in append order, so the dataflow is acyclic and every parent precedes its
  * child by construction.
  *
  * `DataRef` keys: no data content is invented — events carry redacted
  * summaries only. The reducer emits the canonical lineage-data.json key
  * convention `<job>/round-<n>` (model calls) and `<job>/call-<callId>` (tool
  * calls); the wave-2 recording store (ADR-027 addendum §6.5) binds content to
  * these keys.
  *
  * `RecoveryClass` (static taxonomy until the wave-2 schema card, ADR-027
  * §6.2): exec tools (`bash`, `sh`, `process`, `python`) and write tools
  * (`edit`, `fs_edit`, `write`, `fs_write`, `apply_patch`, `patch`, `mv`, `rm`,
  * `mkdir`, `touch`) are `Mutating`; everything else is `ReadOnly` (the ADR
  * default). `Idempotent` is deliberately never guessed from
  * `ToolOutcome`/`RetryScheduled` — a rate-limit retry does not make a mutating
  * tool safe to re-run; the closed enum stays reserved for the schema card and
  * a config-driven extension.
  *
  * Relationship to [[raider.runtime.lineage.io.LineageEventReader]]: the reader
  * already validates schema/seq for file input and reports a torn last line as
  * an honest `TruncatedTail`; because `reduce` accepts envelopes directly, it
  * re-checks the same rules so every guarantee is local to the returned
  * `Either`. A torn FINAL record loses its envelope, so the prefix is an
  * incomplete campaign and reduces to [[ReduceError.MissingTerminal]] — never
  * to a silently complete graph. [[LineageReducer.reducePartial]] is the
  * tolerant LIN5-03 counterpart for exactly that crash shape.
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
    * names the node id (`ModelCallStarted`/`Delegated` for those kinds, the
    * START's seq for a tool call appended at its finish). [[reducePartial]]
    * keeps a node only while this seq precedes the job's partial cut.
    */
  private final case class NodeOrigin(job: String, seq: Long)

  /** Everything one strict fold over `events` produces, before the batch or
    * partial finisher decides the outcome. `failure` is the first strict
    * violation (schema, unknown parent, seq, after-terminal, unmatched finish);
    * the MISSING-terminal condition is deliberately NOT folded in — it is the
    * one check that distinguishes `reduce` from `reducePartial`.
    */
  private final case class Folded(
      failure: Option[ReduceError],
      first: EventEnvelope,
      nodes: Vector[(SessionNode, NodeOrigin)],
      terminalJobs: Set[String],
      jobOrder: Vector[String],
      openRounds: Map[String, List[(Int, NodeId, Long)]],
      openTools: Map[String, Vector[OpenTool]],
      base: Map[String, NodeId]
  )

  private def fold(events: Vector[EventEnvelope]): Folded =
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
    val introduced = scala.collection.mutable.HashSet.empty[String]
    val jobOrder = scala.collection.mutable.ListBuffer.empty[String]
    val nodes = Vector.newBuilder[(SessionNode, NodeOrigin)]

    def nodeId(job: String, seq: Long): NodeId = NodeId(s"$job:n$seq")

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

      // (1) frozen wire revision
      if e.schemaVersion != 1 then
        failure = Some(ReduceError.SchemaVersionUnsupported(e.schemaVersion))
      // (2) the job must be the campaign root or an introduced child
      else if job != rootJob && !introduced.contains(job) then
        failure = Some(ReduceError.UnknownParent(job, e.seq))
      else
        // (3) per-job seq runs 1,2,3,... — gap or duplicate both fail here
        val expected = nextSeq.getOrElse(job, 1L)
        if e.seq != expected then
          failure = Some(ReduceError.SeqGap(job, expected, e.seq))
        else
          if expected == 1L then jobOrder += job
          nextSeq(job) = expected + 1L
          // (4) Terminal closes the job: exactly one, last (ADR-024 §5)
          if terminalJobs.contains(job) then
            failure = Some(ReduceError.AfterTerminal(job, e.seq))
          else
            e.payload match
              case p: EventPayload.ModelCallStarted =>
                val id = nodeId(job, e.seq)
                val input = lastNode.get(job).orElse(base.get(job))
                nodes += (SessionNode.ModelCall(
                  id,
                  base.get(job),
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
                    currentRound.get(job).orElse(base.get(job)),
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
                    ReduceError.DuplicateDelegation(p.childJobId.value, e.seq)
                  )
                else
                  val id = nodeId(job, e.seq)
                  nodes += (SessionNode.Delegation(
                    id,
                    currentRound.get(job).orElse(base.get(job)),
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
                flushOpenTools(job) // started-but-unfinished: outcome unknown
                terminalJobs += job

    Folded(
      failure,
      first,
      nodes.result(),
      terminalJobs.toSet,
      jobOrder.toVector,
      openRounds.toMap,
      openTools.toMap,
      base.toMap
    )

  def reduce(events: Vector[EventEnvelope]): Either[ReduceError, SessionGraph] =
    if events.isEmpty then Left(ReduceError.Empty)
    else
      val f = fold(events)
      f.failure match
        case Some(err) => Left(err)
        case None =>
          f.jobOrder.iterator.find(job => !f.terminalJobs.contains(job)) match
            case Some(job) => Left(ReduceError.MissingTerminal(job))
            case None      => finish(f.first, f.nodes.map(_._1))

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
    *     parent, per-job seq, after-terminal and unmatched finish fail with the
    *     same [[ReduceError]] constructors as in `reduce`; damage is never
    *     papered over into a "plausible" partial graph;
    *   - `MissingTerminal` is NEVER returned. A terminally-complete job keeps
    *     its batch nodes verbatim (including the unknown-outcome flush of
    *     started-but-unfinished tools at its `Terminal`); a job WITHOUT
    *     `Terminal` keeps only its COMPLETED nodes — no half-nodes:
    *     - a round without `ModelCallFinished` on the slice contributes no node
    *       (the batch reducer creates it at start; here the cut removes it
    *       again);
    *     - a tool without `ToolFinished` contributes no node (batch would flush
    *       it only at the job's `Terminal`, which did not happen);
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
  def reducePartial(
      events: Vector[EventEnvelope]
  ): Either[ReduceError, SessionGraph] =
    if events.isEmpty then Left(ReduceError.Empty)
    else
      val f = fold(events)
      f.failure match
        case Some(err) => Left(err)
        case None =>
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
          finish(f.first, kept.result())

  /** Per-job retention bound of the partial reduce: a job's node survives only
    * while its defining `seq` is strictly BELOW the bound. Terminal jobs keep
    * everything (batch behavior); a non-terminal job's bound is the earliest
    * seq that opened one of its still-open constructs — an unfinished round's
    * `ModelCallStarted` or an unfinished tool's `ToolStarted` — and
    * `Long.MaxValue` when nothing is open (all emitted constructs finished).
    */
  private def cutAfter(f: Folded): Map[String, Long] =
    f.jobOrder.iterator.map { job =>
      val bound =
        if f.terminalJobs.contains(job) then Long.MaxValue
        else
          val opens =
            f.openRounds.getOrElse(job, Nil).map(_._3) ++
              f.openTools.getOrElse(job, Nil).map(_.startSeq)
          if opens.isEmpty then Long.MaxValue else opens.min
      job -> bound
    }.toMap

  /** Shared finisher: identity + node vector -> validated [[SessionGraph]] or
    * the typed self-check failure.
    */
  private def finish(
      first: EventEnvelope,
      nodes: Vector[SessionNode]
  ): Either[ReduceError, SessionGraph] =
    val graph = SessionGraph(first.rootId, first.sessionId, nodes)
    graph.validated match
      case Right(valid) => Right(valid)
      case Left(violation) =>
        Left(ReduceError.InvariantBroken(violation))

end LineageReducer
