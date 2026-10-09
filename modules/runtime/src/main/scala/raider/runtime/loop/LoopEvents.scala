package raider.runtime.loop

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{
  EventHub,
  EventPayload,
  ToolOutcome,
  UsageSnapshot,
  WaitReason
}
import zio.{UIO, ZIO}

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
  */
final case class LoopEvents(
    hub: EventHub,
    jobId: JobId,
    rootId: RootId,
    sessionId: SessionId
):

  /** Failure-absorbing publish: any `EventError` → `unit`; an interruption
    * during publish propagates as the run's own cancellation (never as a typed
    * failure).
    */
  def emit(payload: EventPayload): UIO[Unit] =
    hub.publish(jobId, rootId, sessionId, payload).catchAll(_ => ZIO.unit)

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
end LoopEvents
