package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.jobs.JobStatus
import zio.json.jsonDiscriminator

/** Runtime→UI event seam (ADR-024 §5, frozen signatures).
  *
  * Events are pure data: every envelope carries the routing identifiers
  * (job/root/session), a monotonically increasing per-job sequence and one
  * typed payload. The envelope is the only JSON-crossing wire type of this seam
  * (schemaVersion = 1); [[SessionSnapshot]] is in-memory reducer state, never a
  * wire document.
  */
final case class EventEnvelope(
    schemaVersion: Int, // always 1 in this revision
    seq: Long, // per-job, monotonic from 1, no gaps
    jobId: JobId,
    rootId: RootId,
    sessionId: SessionId,
    atMs: Long, // epoch millis, informational
    payload: EventPayload
)

/** The 13 frozen payload cases (ADR-024 §5). Wire shape: discriminated by a
  * `type` field, same rule as ModelEvent in raider.core.codecs; adding a case
  * is a schema decision, never a silent shape change.
  */
@jsonDiscriminator("type")
enum EventPayload:

  case JobStatusChanged(
      from: JobStatus,
      to: JobStatus,
      reason: Option[WaitReason]
  )

  case Waiting(reason: WaitReason)
  case ModelCallStarted(round: Int, model: String, messageCount: Option[Int])
  case ModelCallFinished(round: Int, durationMs: Long, usage: UsageSnapshot)

  case ToolStarted(
      callId: String,
      tool: String,
      argsSummary: String
  ) // redacted, ≤512 chars
  case ToolProgress(callId: String, tool: String, tail: LineTail)
  case ToolFinished(callId: String, tool: String, outcome: ToolOutcome)
  case RetryScheduled(attempt: Int, delayMs: Long, cause: String)

  case BudgetChanged(
      reserved: MicroUsd,
      observed: MicroUsd,
      unknownAttempts: Long
  )

  case Delegated(childJobId: JobId, agent: String)
  case Diagnostics(text: String) // ≤2000 chars
  case DiagnosticsCoalesced(lost: Long)
  case Terminal(outcome: JobStatus.Terminal) // exactly one, last

/** Exhaustive wait-reason set (UX03: status with a reason). */
enum WaitReason:
  case Slot, Admission, Approval, Budget, ChildSlot, Backpressure

/** Observed tool-call outcomes (no invented percentages). */
enum ToolOutcome:
  case Succeeded
  case Failed(reason: String)
  case Cancelled
  case Timeout

/** Bounded output tail (ADR-024 §2.4): last N lines with honest truncation
  * counters — the counters say what was cut, never hide it.
  */
final case class LineTail(
    lines: Vector[String],
    truncatedLines: Int,
    truncatedChars: Long
)

/** Cost state of the session view. `unknown` is passed as unknown and never
  * coerced into 0/false.
  */
final case class UsageSnapshot(
    reserved: MicroUsd,
    observed: MicroUsd,
    unknown: Boolean
)

/** Per-job UI state folded from the event stream (ADR-024 §2.2). Always
  * reconstructible from the envelope stream; the stream is the source of truth,
  * not rendering guesses.
  */
final case class SessionSnapshot(
    jobId: JobId,
    rootId: RootId,
    sessionId: SessionId,
    status: JobStatus,
    waitReason: Option[WaitReason],
    lastSeq: Long,
    activeTool: Option[(String, String)], // (tool, callId)
    progressTail: LineTail,
    usage: UsageSnapshot,
    updatedAtMs: Long
)
