package raider.runtime.lineage.live

import raider.core.{RootId, SessionId}
import raider.runtime.events.{EventEnvelope, EventPayload, ToolOutcome}
import raider.runtime.lineage.model.{
  DataRef,
  NodeId,
  RecoveryTaxonomy,
  SessionGraph,
  SessionNode
}
import raider.runtime.lineage.reduce.ReduceError

/** Immutable state of the live lineage graph (LIN-03A, packet L21, ADR-027).
  *
  * Two states only: nothing applied yet ([[LiveGraphState.NotStarted]]) or a
  * running campaign with >=1 applied envelopes ([[LiveGraphState.Running]]).
  * Every state is a pure value: applying an event returns a NEW state and the
  * previous one stays valid forever, so a crash, an error or an observer can
  * never see a half-applied node.
  */
sealed trait LiveGraphState extends Product with Serializable:

  /** Number of envelopes successfully applied to reach this state. */
  def applied: Int

  /** Complete nodes appended so far, in emission order — a prefix of the batch
    * reducer's node vector for the same stream.
    */
  def nodes: Vector[SessionNode]

  /** The campaign root job once the first envelope is applied. */
  def rootJob: Option[String]

  /** Session identity (rootId, sessionId) taken from the first envelope. */
  def identity: Option[(RootId, SessionId)]

  /** The artifact an observer sees at this point: the validated
    * [[SessionGraph]] of the node prefix.
    *
    * Before the first envelope there is no artifact ([[ReduceError.Empty]] —
    * the same typed answer the batch reducer gives to an empty stream). Once
    * envelopes are applied, the prefix is structurally valid by construction
    * (all edges point strictly backward); the `validated` re-check mirrors the
    * batch reducer's defensive self-check, so an internal regression can never
    * surface as a silently invalid artifact.
    */
  def snapshot: Either[ReduceError, SessionGraph]

object LiveGraphState:

  /** No envelope applied yet. */
  case object NotStarted extends LiveGraphState:
    val applied: Int = 0
    val nodes: Vector[SessionNode] = Vector.empty
    val rootJob: Option[String] = None
    val identity: Option[(RootId, SessionId)] = None
    val snapshot: Either[ReduceError, SessionGraph] = Left(ReduceError.Empty)

  /** Running campaign state after >=1 applied envelopes. Constructed only by
    * [[LiveGraphBuilder]]; the fields are read-only immutable values, safe to
    * share with observers (crash-consistency: a published reference never
    * changes under its reader).
    */
  final case class Running private[live] (
      campaignRoot: String,
      rootId: RootId,
      sessionId: SessionId,
      nextSeq: Map[String, Long],
      terminalJobs: Set[String],
      base: Map[String, NodeId],
      currentRound: Map[String, NodeId],
      lastNode: Map[String, NodeId],
      openRounds: Map[String, List[(Int, NodeId)]],
      openTools: Map[String, Vector[OpenTool]],
      introduced: Set[String],
      jobOrder: Vector[String],
      nodes: Vector[SessionNode],
      applied: Int
  ) extends LiveGraphState:

    def rootJob: Option[String] = Some(campaignRoot)

    def identity: Option[(RootId, SessionId)] = Some((rootId, sessionId))

    def snapshot: Either[ReduceError, SessionGraph] =
      val graph = SessionGraph(rootId, sessionId, nodes)
      graph.validated match
        case Right(valid) => Right(valid)
        case Left(v)      => Left(ReduceError.InvariantBroken(v))

  end Running

end LiveGraphState

/** A tool call opened by `ToolStarted`, waiting for its `ToolFinished`. Mirrors
  * the batch reducer's private open-tool record: `parent`/`inputs` are fixed at
  * start; the node is appended at finish (or flushed at the job's `Terminal`
  * with an unknown outcome). Read-only observability record of
  * [[LiveGraphState.Running]] — the live builder never mutates one.
  */
final case class OpenTool(
    callId: String,
    tool: String,
    startSeq: Long,
    atMs: Long,
    parent: Option[NodeId],
    inputs: Vector[NodeId]
)

/** Live incremental lineage graph builder (LIN-03A, ADR-027 §3; packet L21;
  * parent LIN-03).
  *
  * Step-wise twin of [[raider.runtime.lineage.reduce.LineageReducer]]: it
  * applies ONE envelope at a time and keeps the exact batch semantics — the
  * validation order (schemaVersion, known job, per-job seq 1,2,3,...,
  * after-terminal, payload pairing), node construction, the `RecoveryClass`
  * taxonomy, the no-phase rule, and the end-of-stream `MissingTerminal` +
  * `validated` checks. The equivalence is pinned by tests (LIN3-A-01): the fold
  * of applications over a stream equals the batch `LineageReducer.reduce` of
  * the same stream — same node vector, same
  * [[raider.runtime.lineage.model.GraphHash]].
  *
  * Contract:
  *   - applying ONE event returns the new state or a typed [[ReduceError]]; on
  *     error the PREVIOUS state stays valid — the session does not collapse,
  *     the error goes up and the caller continues or stops explicitly;
  *   - crash-consistency: the state after each NODE is atomic. States are
  *     immutable and each event appends zero or more COMPLETE nodes (a tool
  *     node only at its finish, or flushed at the job's `Terminal` with an
  *     unknown outcome), so an observer sees either the whole node or its
  *     absence — never a half-applied one (LIN3-03; demonstrated at every event
  *     boundary by tests);
  *   - [[finish]] closes the stream with the batch end-of-stream semantics:
  *     every job that emitted at least one event must be terminal, then the
  *     graph is re-checked with `SessionGraph.validated`.
  *
  * NOT in this card by scope (next card, cli layer): the periodic
  * compile-validation of the projection (LIN3-02 full). This builder only
  * guarantees the validated in-memory snapshot ([[LiveGraphState.snapshot]])
  * that the future projection consumes; it compiles and validates nothing else.
  *
  * Wire-freeze: the 13 frozen payload cases are consumed as-is — no schema
  * extension, no `Phase` nodes (there is no phase case on the frozen wire).
  */
object LiveGraphBuilder:

  /** State before the first envelope. */
  val initial: LiveGraphState = LiveGraphState.NotStarted

  /** Applies ONE envelope: the new state, or a typed error — with the previous
    * state left untouched (the caller keeps and owns it).
    */
  def apply(
      state: LiveGraphState,
      envelope: EventEnvelope
  ): Either[ReduceError, LiveGraphState] =
    state match
      case LiveGraphState.NotStarted =>
        // The first envelope establishes the campaign identity (root job,
        // rootId, sessionId) exactly like the batch reducer's `events.head`;
        // the seeded running state then passes the SAME per-event checks
        // (schemaVersion first, the root job's seq must be 1, ...).
        val seeded = LiveGraphState.Running(
          campaignRoot = envelope.jobId.value,
          rootId = envelope.rootId,
          sessionId = envelope.sessionId,
          nextSeq = Map.empty,
          terminalJobs = Set.empty,
          base = Map.empty,
          currentRound = Map.empty,
          lastNode = Map.empty,
          openRounds = Map.empty,
          openTools = Map.empty,
          introduced = Set.empty,
          jobOrder = Vector.empty,
          nodes = Vector.empty,
          applied = 0
        )
        step(seeded, envelope)
      case running: LiveGraphState.Running => step(running, envelope)

  /** End of stream: every job that emitted at least one event must be terminal,
    * then the same validated self-check as the batch reducer. Mirrors the batch
    * tail, so [[finish]] over a complete campaign equals
    * `LineageReducer.reduce`.
    */
  def finish(state: LiveGraphState): Either[ReduceError, SessionGraph] =
    state match
      case LiveGraphState.NotStarted => Left(ReduceError.Empty)
      case s: LiveGraphState.Running =>
        s.jobOrder.iterator.find(job => !s.terminalJobs.contains(job)) match
          case Some(job) => Left(ReduceError.MissingTerminal(job))
          case None      => s.snapshot

  /** Fold of applications over a whole stream + [[finish]] — the live-path twin
    * of `LineageReducer.reduce` (the first violation in stream order wins,
    * exactly like the batch fold).
    */
  def reduce(
      events: Vector[EventEnvelope]
  ): Either[ReduceError, SessionGraph] =
    events
      .foldLeft[Either[ReduceError, LiveGraphState]](Right(initial))((acc, e) =>
        acc.flatMap(apply(_, e))
      )
      .flatMap(finish)

  // ------------------------------------------------------------------
  // Per-event step: exact replica of the batch reducer's per-envelope
  // checks and payload dispatch, over immutable state. All updates happen
  // on locally derived values; the new state is returned only on success —
  // on failure the input state is the committed one (atomic apply).
  // ------------------------------------------------------------------

  private def step(
      s: LiveGraphState.Running,
      e: EventEnvelope
  ): Either[ReduceError, LiveGraphState.Running] =
    val job = e.jobId.value
    // (1) frozen wire revision
    if e.schemaVersion != 1 then
      Left(ReduceError.SchemaVersionUnsupported(e.schemaVersion))
    // (2) the job must be the campaign root or an introduced child
    else if job != s.campaignRoot && !s.introduced.contains(job) then
      Left(ReduceError.UnknownParent(job, e.seq))
    else
      // (3) per-job seq runs 1,2,3,... — gap or duplicate both fail here
      val expected = s.nextSeq.getOrElse(job, 1L)
      if e.seq != expected then Left(ReduceError.SeqGap(job, expected, e.seq))
      else
        val advanced = s.copy(
          nextSeq = s.nextSeq.updated(job, expected + 1L),
          jobOrder = if expected == 1L then s.jobOrder :+ job else s.jobOrder
        )
        // (4) Terminal closes the job: exactly one, last (ADR-024 §5)
        if s.terminalJobs.contains(job) then
          Left(ReduceError.AfterTerminal(job, e.seq))
        else dispatch(advanced, e).map(_.copy(applied = s.applied + 1))

  private def dispatch(
      s: LiveGraphState.Running,
      e: EventEnvelope
  ): Either[ReduceError, LiveGraphState.Running] =
    val job = e.jobId.value
    e.payload match
      case p: EventPayload.ModelCallStarted =>
        val id = NodeId(s"$job:n${e.seq}")
        val input = s.lastNode.get(job).orElse(s.base.get(job))
        val node = SessionNode.ModelCall(
          id,
          s.base.get(job),
          e.seq,
          e.atMs,
          p.round,
          p.model,
          input.toVector,
          DataRef(s"$job/round-${p.round}")
        )
        Right(
          s.copy(
            nodes = s.nodes :+ node,
            lastNode = s.lastNode.updated(job, id),
            currentRound = s.currentRound.updated(job, id),
            openRounds = s.openRounds.updated(
              job,
              (p.round, id) :: s.openRounds.getOrElse(job, Nil)
            )
          )
        )

      case EventPayload.ModelCallFinished(round, _, _) =>
        val open = s.openRounds.getOrElse(job, Nil)
        open.find(_._1 == round) match
          case None =>
            Left(
              ReduceError.UnmatchedFinish(
                job,
                e.seq,
                s"ModelCallFinished(round=$round)"
              )
            )
          case Some((_, id)) =>
            Right(
              s.copy(
                openRounds = s.openRounds.updated(job, open.filter(_._2 != id))
              )
            )

      case p: EventPayload.ToolStarted =>
        val open = OpenTool(
          p.callId,
          p.tool,
          e.seq,
          e.atMs,
          s.currentRound.get(job).orElse(s.base.get(job)),
          s.lastNode.get(job).toVector
        )
        Right(
          s.copy(
            openTools = s.openTools.updated(
              job,
              s.openTools.getOrElse(job, Vector.empty) :+ open
            )
          )
        )

      case _: EventPayload.ToolProgress =>
        Right(s) // streaming tail, no node

      case p: EventPayload.ToolFinished =>
        val open = s.openTools.getOrElse(job, Vector.empty)
        val idx = open.lastIndexWhere(_.callId == p.callId)
        if idx < 0 then
          Left(
            ReduceError.UnmatchedFinish(
              job,
              e.seq,
              s"ToolFinished(callId=${p.callId})"
            )
          )
        else
          val appended = appendTool(s, job, open(idx), Some(p.outcome))
          Right(
            appended.copy(
              openTools =
                appended.openTools.updated(job, open.patch(idx, Nil, 1))
            )
          )

      case p: EventPayload.Delegated =>
        if p.childJobId.value == s.campaignRoot || s.introduced.contains(
            p.childJobId.value
          )
        then Left(ReduceError.DuplicateDelegation(p.childJobId.value, e.seq))
        else
          val id = NodeId(s"$job:n${e.seq}")
          val node = SessionNode.Delegation(
            id,
            s.currentRound.get(job).orElse(s.base.get(job)),
            e.seq,
            e.atMs,
            p.childJobId,
            p.agent,
            s.lastNode.get(job).toVector
          )
          Right(
            s.copy(
              nodes = s.nodes :+ node,
              lastNode = s.lastNode.updated(job, id),
              introduced = s.introduced + p.childJobId.value,
              base = s.base.updated(p.childJobId.value, id)
            )
          )

      // Control-plane payloads: no node kind in the frozen model; the stream
      // stays the source of truth for them.
      case _: EventPayload.JobStatusChanged     => Right(s)
      case _: EventPayload.Waiting              => Right(s)
      case _: EventPayload.RetryScheduled       => Right(s)
      case _: EventPayload.BudgetChanged        => Right(s)
      case _: EventPayload.Diagnostics          => Right(s)
      case _: EventPayload.DiagnosticsCoalesced => Right(s)

      case EventPayload.Terminal(_) =>
        val flushed = flushOpenTools(s, job) // started-but-unfinished: unknown
        Right(flushed.copy(terminalJobs = flushed.terminalJobs + job))
    end match
  end dispatch

  private def appendTool(
      s: LiveGraphState.Running,
      job: String,
      open: OpenTool,
      outcome: Option[ToolOutcome]
  ): LiveGraphState.Running =
    val id = NodeId(s"$job:n${open.startSeq}")
    val node = SessionNode.ToolCall(
      id,
      open.parent,
      open.startSeq,
      open.atMs,
      open.callId,
      open.tool,
      RecoveryTaxonomy.classify(open.tool),
      outcome,
      open.inputs,
      Some(DataRef(s"$job/call-${open.callId}"))
    )
    s.copy(nodes = s.nodes :+ node, lastNode = s.lastNode.updated(job, id))

  private def flushOpenTools(
      s: LiveGraphState.Running,
      job: String
  ): LiveGraphState.Running =
    val open = s.openTools.getOrElse(job, Vector.empty)
    val flushed = open.foldLeft(s)((acc, t) => appendTool(acc, job, t, None))
    flushed.copy(openTools = flushed.openTools.updated(job, Vector.empty))

  // Static tool taxonomy consumed from the CANONICAL single source
  // (raider.runtime.lineage.model.RecoveryTaxonomy, ADR-027 §6.2); RAI-148
  // deduplicated the batch-reducer copy that used to live here — the
  // LIN3-A-01 equivalence tests still guard live==batch classification.

end LiveGraphBuilder
