package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.jobs.JobStatus
import zio.{IO, ZIO}

/** Pure per-job fold from the event stream to a UI snapshot (ADR-024 §2.2).
  *
  * `reduce` is a pure function of `(snapshot, envelope)`: no effect, no clock,
  * no environment — `IO` carries only the typed [[EventError]] channel. Per-job
  * ordering rules:
  *   - `seq < lastSeq` → Malformed (regression; the hub dedups, this refuses)
  *   - `seq == lastSeq` → idempotent: the same state is returned
  *   - any new seq after a terminal snapshot → Malformed (Terminal is exactly
  *     one event and the last one)
  *
  * The reducer trusts per-job routing (a stream is per-job by construction); it
  * does not re-check `jobId`/`rootId`/`sessionId` against the snapshot.
  */
object SessionReducer:

  /** Bounded progress-tail policy (ADR-024 §2.4): keep the last N lines, cut
    * each line at M chars; both losses are counted, never hidden.
    */
  val maxTailLines: Int = 8
  val maxTailLineChars: Int = 2000

  def initial(
      jobId: JobId,
      rootId: RootId,
      sessionId: SessionId
  ): SessionSnapshot =
    SessionSnapshot(
      jobId,
      rootId,
      sessionId,
      status = JobStatus.Queued,
      waitReason = None,
      lastSeq = 0L,
      activeTool = None,
      progressTail = LineTail(Vector.empty, 0, 0L),
      usage = UsageSnapshot(MicroUsd(0L), MicroUsd(0L), unknown = false),
      updatedAtMs = 0L
    )

  def reduce(
      s: SessionSnapshot,
      e: EventEnvelope
  ): IO[EventError, SessionSnapshot] =
    if e.seq < s.lastSeq then
      ZIO.fail(
        EventError.Malformed(
          s"seq regression for ${s.jobId.value}: got ${e.seq}, lastSeq=${s.lastSeq}"
        )
      )
    else if e.seq == s.lastSeq then ZIO.succeed(s)
    else if JobStatus.terminal.contains(s.status) then
      ZIO.fail(
        EventError.Malformed(
          s"event seq=${e.seq} after terminal status ${s.status} for ${s.jobId.value}"
        )
      )
    else ZIO.succeed(step(s, e))

  /** Apply one accepted event (seq strictly greater than the watermark, state
    * not yet terminal).
    */
  private def step(s: SessionSnapshot, e: EventEnvelope): SessionSnapshot =
    val base = s.copy(lastSeq = e.seq, updatedAtMs = e.atMs)
    e.payload match
      case EventPayload.JobStatusChanged(_, to, reason) =>
        base.copy(status = to, waitReason = reason)
      case EventPayload.Waiting(reason) =>
        base.copy(status = JobStatus.Waiting, waitReason = Some(reason))
      case EventPayload.ModelCallStarted(_, _, _) =>
        base
      case EventPayload.ModelCallFinished(_, _, usage) =>
        base.copy(usage = usage)
      case EventPayload.ToolStarted(callId, tool, _) =>
        base.copy(activeTool = Some((tool, callId)))
      case EventPayload.ToolProgress(callId, tool, tail) =>
        base.copy(
          activeTool = Some((tool, callId)),
          progressTail = appendTail(base.progressTail, tail)
        )
      case EventPayload.ToolFinished(_, _, _) =>
        base.copy(activeTool = None)
      case EventPayload.RetryScheduled(_, _, _) =>
        base
      case EventPayload.BudgetChanged(reserved, observed, unknownAttempts) =>
        base.copy(
          usage =
            UsageSnapshot(reserved, observed, unknown = unknownAttempts > 0L)
        )
      case EventPayload.Delegated(_, _) =>
        base
      case EventPayload.Diagnostics(_) =>
        base
      case EventPayload.DiagnosticsCoalesced(_) =>
        base
      case EventPayload.Terminal(outcome) =>
        base.copy(status = outcome, waitReason = None)

  /** Append a progress chunk to the bounded tail with honest counters: every
    * line evicted by the bound and every char cut by the per-line limit is
    * counted, on top of any truncation the producer already reported in the
    * chunk (no double counting: producer-truncated lines are at or below the
    * limits, so the reducer cuts nothing from them).
    */
  private def appendTail(old: LineTail, chunk: LineTail): LineTail =
    var cutChars = 0L
    val lines = chunk.lines.map { line =>
      if line.length > maxTailLineChars then
        cutChars += line.length - maxTailLineChars
        line.take(maxTailLineChars)
      else line
    }
    val all = old.lines ++ lines
    val (evicted, kept) =
      if all.length > maxTailLines then
        val drop = all.length - maxTailLines
        (drop, all.drop(drop))
      else (0, all)
    LineTail(
      kept,
      old.truncatedLines + chunk.truncatedLines + evicted,
      old.truncatedChars + chunk.truncatedChars + cutChars
    )
