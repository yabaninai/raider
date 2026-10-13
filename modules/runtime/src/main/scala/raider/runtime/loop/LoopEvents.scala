package raider.runtime.loop

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  ToolOutcome,
  UsageSnapshot,
  WaitReason
}
import raider.runtime.journal.JobJournal
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.model.RecoveryClass
import zio.json.{DeriveJsonEncoder, EncoderOps, JsonEncoder}
import zio.{UIO, ZIO}

import java.util.concurrent.atomic.AtomicLong

/** Optional emission context binding one [[AgentLoop]] run (or one
  * [[raider.runtime.display.RateLimitRetries]] decorator) to a single
  * job/root/session of an [[EventHub]] (RAI-058, ADR-024 §2.3/§6).
  *
  * `None` at the call site = zero overhead: every emission point degrades to
  * `ZIO.unit` and no hub is even referenced.
  *
  * Delivery classes follow the hub routing table (ADR-024 §2.3):
  *   - RELIABLE — bounded-wait offer, typed `EventError.Backpressure` on
  *     timeout, no silent loss: `Waiting(Slot)`, `RetryScheduled`;
  *   - DIAGNOSTICS — non-blocking offer, drop + `droppedDiagnostics` counter
  *     when full (a slow consumer can never block the loop):
  *     `ModelCallStarted/Finished`, `ToolStarted/Finished`, `Diagnostics`.
  *
  * Emission NEVER breaks the run: every publish failure
  * (`EventError.Backpressure`, `EventError.Malformed`) is absorbed by
  * [[LoopEvents.emit]] — the loop's typed error channel is reserved for
  * model/tool/budget/admission failures, and job-level status and `Terminal`
  * are owned by the job layer (RAI-059), not by the loop.
  *
  * Honest unknowns (ADR-024 §2.4): the loop observes no cost, so
  * `ModelCallFinished` always reports
  * `UsageSnapshot(MicroUsd(0), MicroUsd(0), unknown = true)` — unknown is
  * passed as unknown, never coerced into 0/false.
  *
  * Journal wiring (RAI-093): `journal`, when present, receives every EMITTED
  * envelope — appended only AFTER the hub publish succeeded (a dropped
  * diagnostic or a Backpressure failure emits nothing, consumes no sequence and
  * is journaled by nothing: the journal is the log of what was actually
  * emitted). The sink is awaited-append, absorbed-failure ("fire and forget" in
  * the failure sense): appends are awaited INLINE so the journal order equals
  * the emission order of the publishing fiber, while EVERY sink failure is
  * swallowed — `EventError` from publish and any defect from
  * `append`/`checkpoint` (the journal's error channel is defects-only) never
  * reach the loop; interruption stays the run's own cancellation, exactly as
  * for publish. The journal therefore NEVER affects the loop, and NO automatic
  * replay exists anywhere in this wiring — recovery data returned by
  * `JobJournal.replay` is inert input for an explicit caller policy, never
  * re-executed here.
  *
  * Journal seq space: journaled envelopes carry a DENSE JOURNAL-LOCAL sequence
  * (`journalSeq`, 1, 2, 3, ... in emission order), NOT the hub's per-job seq.
  * `EventHub.publish` does not return the emitted envelope, and reading the hub
  * seq back via `snapshot(job).lastSeq` would be racy under concurrent
  * publishers of one job (the job layer publishes `JobStatusChanged`/`Terminal`
  * to the same job) and undefined for dropped diagnostics — claiming hub parity
  * from this seam would be dishonest. The two seq spaces stay separate, and the
  * checkpoint document names BOTH explicitly (`journalLastSeq` vs
  * `hubLastSeq`).
  *
  * Checkpoint (JW-02): when a `Terminal` payload passes the seam, one
  * [[JobJournal.checkpoint]] is written to `journal.path/checkpoint.json` after
  * the Terminal record is appended. The context knows the hub, so the snapshot
  * facts come from `hub.snapshot(jobId)` (`hubLastSeq`, `hubStatus` — canonical
  * ADR-024 status names); if the hub has no snapshot for the job, those fields
  * are simply absent and the document degenerates to the minimal JSON carrying
  * `journalLastSeq` only. Like every journal write, a failed checkpoint is
  * absorbed: the loop runs on.
  */
final case class LoopEvents(
    hub: EventHub,
    jobId: JobId,
    rootId: RootId,
    sessionId: SessionId,
    journal: Option[JobJournal] = None,
    journalSeq: AtomicLong = new AtomicLong(0L),
    // RAI-153 (ADR-032 §1): the live phase-emission seam; None (default) =
    // zero overhead — the phase surface below degrades to `unit` and no
    // lineage sink is referenced.
    phases: Option[LoopEvents.PhaseSink] = None
):

  /** Failure-absorbing publish + optional journal append (RAI-093): any
    * `EventError` from the hub AND any defect from the journal sink → `unit`;
    * an interruption propagates as the run's own cancellation (never as a typed
    * failure). Only a SUCCESSFUL publish is journaled — the journal holds
    * exactly the emitted envelopes, in emission order.
    */
  def emit(payload: EventPayload): UIO[Unit] =
    hub.publish(jobId, rootId, sessionId, payload).catchAll(_ => ZIO.unit) *>
      (journal match
        case Some(j) => record(j, payload)
        case None    => ZIO.unit)

  /** Awaited-append, absorbed-failure sink: `JobJournal.append` is UIO (its
    * error channel is defects by contract), so absorbing means `catchAllDefect`
    * — a dying sink (append-after-close, IO failure) never reaches the loop.
    * Awaiting inline is what makes journal order == emission order for one
    * publishing fiber; an append torn by interruption is an honest Uncertain
    * for replay, never repaired here.
    */
  private def record(j: JobJournal, payload: EventPayload): UIO[Unit] =
    ZIO.succeed(journalSeq.incrementAndGet()).flatMap { seq =>
      val env = EventEnvelope(
        LoopEvents.schemaVersion,
        seq,
        jobId,
        rootId,
        sessionId,
        java.lang.System.currentTimeMillis(),
        payload
      )
      j.append(env).catchAllDefect(_ => ZIO.unit).unit *>
        (payload match
          case EventPayload.Terminal(_) => checkpoint(j, seq)
          case _                        => ZIO.unit)
    }

  /** Checkpoint after a journaled Terminal (JW-02): snapshot facts from the hub
    * the context already holds; hub fields absent when the hub knows nothing
    * about the job (the minimal lastSeq-only JSON). The status encodes as its
    * canonical ADR-024 name (the EventCodecs given). Absorbed like every sink
    * failure: `JournalError` and defects both → unit.
    */
  private def checkpoint(j: JobJournal, lastSeq: Long): UIO[Unit] =
    hub.snapshot(jobId).flatMap { snap =>
      val doc = LoopEvents.CheckpointDoc(
        kind = LoopEvents.CheckpointDoc.kind,
        jobId = jobId.value,
        journalLastSeq = lastSeq,
        hubLastSeq = snap.map(_.lastSeq),
        hubStatus = snap.map(_.status),
        capturedAtMs = java.lang.System.currentTimeMillis()
      )
      JobJournal
        .checkpoint(
          doc.toJson,
          j.path.resolve(LoopEvents.CheckpointDoc.fileName)
        )
        .ignore
        .catchAllDefect(_ => ZIO.unit)
    }

  /** RELIABLE: the run is about to wait for a Model or Tool slot. Emitted
    * unconditionally before each `admission.withSlot`/`withSlotFree` call — a
    * zero-length wait is still a (successful) slot acquisition, and the UI
    * needs the reason before any slot wait happens (UX03).
    */
  def waitingSlot: UIO[Unit] =
    emit(EventPayload.Waiting(WaitReason.Slot))

  /** DIAGNOSTICS: one model round is about to call the backend (`messageCount` =
    * conversation size without the system prompt, same semantics as
    * `TraceEvent.ModelCall`).
    */
  def modelCallStarted(
      round: Int,
      model: String,
      messageCount: Int
  ): UIO[Unit] =
    emit(EventPayload.ModelCallStarted(round, model, Some(messageCount)))

  /** DIAGNOSTICS: the model call terminated — on success AND on failure (the
    * call has ended; an interrupted call emits nothing, and the job layer owns
    * the terminal status). `durationMs` is the observed wall time of the stream
    * pass; usage is always `unknown = true` (no cost is known at this level,
    * §2.4).
    */
  def modelCallFinished(round: Int, durationMs: Long): UIO[Unit] =
    emit(
      EventPayload.ModelCallFinished(
        round,
        durationMs,
        UsageSnapshot(MicroUsd(0L), MicroUsd(0L), unknown = true)
      )
    )

  /** DIAGNOSTICS: a tool call starts; `argsSummary` is truncated to the
    * `ToolStarted` bound of ≤512 chars (redaction hygiene, ADR-024 §5).
    */
  def toolStarted(
      callId: String,
      tool: String,
      argsSummary: String
  ): UIO[Unit] =
    emit(
      EventPayload.ToolStarted(
        callId,
        tool,
        argsSummary.take(LoopEvents.maxArgsSummaryChars)
      )
    )

  /** DIAGNOSTICS: a tool call ended. The outcome is ALWAYS emitted — including
    * the failure path, which today's trace does not cover:
    *   - `Succeeded` — valid JSON output delivered to the message envelope;
    *   - `Timeout` — the tool exceeded its `timeoutMs` (DeadlineExceeded);
    *   - `Cancelled` — the run was interrupted during the call, or the root was
    *     cancelled at the post-permit recheck (typed `Cancelled`);
    *   - `Failed(reason)` — any other definitive failure, and BOTH refusals,
    *     which are documented as `ToolFinished(Failed(...))` rather than
    *     `Diagnostics` text: a refusal is a terminal fact of one specific
    *     callId (correlatable, never dropped silently, never lost on a full
    *     diagnostics buffer), while `Diagnostics` is untyped and droppable.
    *
    * A refusal emits `ToolFinished` WITHOUT a preceding `ToolStarted`: the tool
    * never started; the reducer's `activeTool` clear is a no-op.
    */
  def toolFinished(
      callId: String,
      tool: String,
      outcome: ToolOutcome
  ): UIO[Unit] =
    emit(EventPayload.ToolFinished(callId, tool, outcome))

  /** DIAGNOSTICS: RoundStarted/TextReceived equivalents (the loop has no
    * dedicated payload case for them in the frozen 13-case family); text is
    * truncated to the `Diagnostics` bound of ≤2000 chars.
    */
  def diagnostics(text: String): UIO[Unit] =
    emit(EventPayload.Diagnostics(text.take(LoopEvents.maxDiagnosticsChars)))

  /** RELIABLE: a rate-limit backoff retry is about to sleep (called by
    * [[raider.runtime.display.RateLimitRetries]] before each backoff). `cause`
    * is truncated to a hygiene bound (no frozen bound exists for it).
    */
  def retryScheduled(attempt: Int, delayMs: Long, cause: String): UIO[Unit] =
    emit(
      EventPayload.RetryScheduled(
        attempt,
        delayMs,
        cause.take(LoopEvents.maxReasonChars)
      )
    )

  /** RAI-153 (ADR-032 §1): phase ENTER at a real phase boundary — a
    * `phase_started` v2 envelope on the LINEAGE wire, never on the frozen hub
    * (the ADR-024 seam keeps its 13 frozen cases; the v2 envelope is a
    * lineage-layer type per ADR-032 §1, so this channel deliberately has no
    * `hub.publish` path). `recovery` is the closed lineage taxonomy (`ReadOnly |
    * Idempotent | Mutating` — the lineage-layer [[RecoveryClass]], not the
    * `raider.core` tool-axis enum).
    *
    * Failure-absorbing like every emission: with `phases = None` (the default)
    * this is `unit`; an empty `name` is dropped at this seam (the wire refuses
    * empty names at decode — never producing an undecodable stream beats
    * emitting one), and every sink failure is absorbed (see [[PhaseSink]]). The
    * name travels verbatim (no truncation): `phase_finished` closes the
    * innermost open phase BY NAME, so a truncated name would lie about which
    * phase ended.
    */
  def phaseStarted(
      name: String,
      recovery: RecoveryClass,
      note: Option[String] = None
  ): UIO[Unit] =
    phases match
      case Some(sink) if name.nonEmpty =>
        sink.phaseStarted(
          name,
          recovery,
          note,
          rootId,
          sessionId,
          java.lang.System.currentTimeMillis()
        )
      case _ => ZIO.unit

  /** RAI-153 (ADR-032 §1): phase EXIT for the innermost open phase with this
    * name (LIFO per job, reducer-checked). Same channel, same absorbing
    * discipline as [[phaseStarted]]. An open phase at the job's `Terminal`
    * stays legal (ADR-032 §2: its node stands) — callers that know the phase
    * ended emit here on every exit, including typed failures and cancellation.
    */
  def phaseFinished(name: String): UIO[Unit] =
    phases match
      case Some(sink) if name.nonEmpty =>
        sink.phaseFinished(
          name,
          rootId,
          sessionId,
          java.lang.System.currentTimeMillis()
        )
      case _ => ZIO.unit

object LoopEvents:

  /** `EventPayload.ToolStarted.argsSummary` bound (ADR-024 §5). */
  val maxArgsSummaryChars: Int = 512

  /** `EventPayload.Diagnostics.text` bound (ADR-024 §5). */
  val maxDiagnosticsChars: Int = 2000

  /** Hygiene bound for `RetryScheduled.cause` / `ToolOutcome.Failed` reasons
    * (no frozen bound exists; unbounded strings in envelopes are refused).
    */
  val maxReasonChars: Int = 512

  /** Documented refusal outcome: the model requested a tool the registry does
    * not contain (`ToolFinished(Failed("refused: unknown tool"))`).
    */
  val refusedUnknownTool: String = "refused: unknown tool"

  /** Documented refusal outcome: the tool exists but its argument JSON is
    * malformed (`ToolFinished(Failed("refused: invalid arguments"))`).
    */
  val refusedInvalidArgs: String = "refused: invalid arguments"

  /** RAI-107 (ADR-028, PLAN-01): documented refusal outcome of the plan phase —
    * the tool is known but not `RecoveryClass.ReadOnly`
    * (`ToolFinished(Failed("refused: plan phase allows read-only tools
    * only"))`).
    */
  val refusedPlanReadOnly: String =
    "refused: plan phase allows read-only tools only"

  /** Envelope schema version of journaled records (ADR-024 §5: "always 1 in
    * this revision"; the hub keeps the same value privately).
    */
  private[loop] val schemaVersion: Int = 1

  /** RAI-153 (ADR-032 §1): the receiver of LIVE phase envelopes — the
    * lineage-layer counterpart of the hub for the v2 payload cases. The ADR-024
    * seam rule is respected by construction: `raider.runtime.events.**` gains
    * nothing (the frozen 13-case payload family and its readers are untouched),
    * because ADR-032 already versioned the wire — this sink receives
    * `schemaVersion: 2` [[raider.runtime.lineage.wire.LineagePayload]] phase
    * records that the v1-only consumers never see.
    *
    * Implementations own envelope identity (job/root/session binding is passed
    * in verbatim from the context), the export-canonical per-job `seq`
    * assignment, file placement and ordering. Both methods are `UIO`:
    *   - a sink must never break the run — every failure (including a dead
    *     artifact environment) is the SINK's absorbing obligation, mirroring
    *     [[LoopEvents.emit]];
    *   - a sink must place a `phase_started` AFTER every campaign envelope
    *     published before the call and BEFORE every envelope published after it
    *     returns (the settle-to-export discipline of the exporter
    *     implementation), so the reduced graph shape reflects emission order.
    */
  trait PhaseSink:

    def phaseStarted(
        name: String,
        recovery: RecoveryClass,
        note: Option[String],
        rootId: RootId,
        sessionId: SessionId,
        atMs: Long
    ): UIO[Unit]

    def phaseFinished(
        name: String,
        rootId: RootId,
        sessionId: SessionId,
        atMs: Long
    ): UIO[Unit]

  end PhaseSink

  /** Checkpoint document written after a journaled Terminal (RAI-093, JW-02).
    * Wire shape is NOT part of the frozen event schema — it is a recovery aid
    * next to the segments: `journalLastSeq` is the journal-local dense seq of
    * the last journaled record, `hubLastSeq`/`hubStatus` mirror
    * `hub.snapshot(jobId)` at checkpoint time (absent when the hub knows
    * nothing about the job — the minimal lastSeq-only document). The two seq
    * spaces are different spaces and are never mixed: reconciliation across a
    * gap maps journal records to hub state via these two named fields, never by
    * assuming equality.
    */
  private[loop] final case class CheckpointDoc(
      kind: String,
      jobId: String,
      journalLastSeq: Long,
      hubLastSeq: Option[Long],
      // encodes as the canonical ADR-024 status name via the EventCodecs
      // given JsonEncoder[JobStatus] picked up by the derived encoder
      hubStatus: Option[JobStatus],
      capturedAtMs: Long
  )

  private[loop] object CheckpointDoc:
    val kind: String = "raider.loop.checkpoint"
    val fileName: String = "checkpoint.json"

    given JsonEncoder[CheckpointDoc] = DeriveJsonEncoder.gen[CheckpointDoc]

end LoopEvents
