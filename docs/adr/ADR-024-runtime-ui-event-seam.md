# ADR-024: Runtime→UI event seam (envelopes, snapshot+subscribe, progress)

Status: **Proposed** (freeze after independent review). Date: 2026-10-06.
Owner: coordinator (P0.B, [roadmap-12k](../roadmap-12k.md) §P0.B, 160 h package).
Reviewers: independent, see work record. [ADR-016](ADR-016-tui-renderer.md) remains in force
(inline renderer over JLine — this ADR does not change the renderer decision).

## 1. Context

Actual state (P0.A [gap matrix](../reviews/2026-10-05-p0a-gap-matrix.md)):
the runtime has a single event mechanism: one callback
`trace: TraceEvent => UIO[Unit]` (`AgentLoop.scala:60`, 11 `TraceEvent` subtypes,
JSON codec in `modules/core/trace.scala`); job statuses are pull-only
(`JobManager.snapshot/adminView`, `JobSnapshot(id, root, label, status, lateObservations)`,
`JobStatus` state machine with `canTransition`); there is no live watch (only
initial+final snapshot); admission wait / budget / retry / delegation /
tool-failure events are not emitted; `ModelCall/ToolCall*` hardcode `round = -1`.
UI consumers (CLI chat, REPL echo, headless stderr) write to the terminal without
a shared writer or subscription.

Target requirements: P1.A (envelopes with root/session/job/event IDs and per-job
sequence; reducer/snapshot plus subscribe with no loss window), P1.C (diagnostics
may be coalesced/dropped with a counter; terminal/approval/accounting use a separate
reliable path; a slow viewer does not block cancellation), UX03/UX08 (status with
wait reason and truthful unknown), M1 (tabs driven by these events).

## 2. Decision

### 2.1 New event family instead of extending TraceEvent

`TraceEvent` remains unchanged (compatibility: transcript.json, consumers in
CLI/REPL/DelegationToolset). UI events form a new sealed family in
`modules/runtime/.../events/` with required routing fields
(jobId/rootId/sessionId) and per-job sequence. Reason: `TraceEvent` carries no
jobId/seq; adding breaking fields requires a schema version. The UI needs wait
reasons and bounded progress, which `TraceEvent` does not provide.

### 2.2 Per-job sequence and no loss window

`EventHub` assigns sequence per job (monotonically from 1, without gaps).
`subscribe` returns the initial `SessionSnapshot` (watermark = lastSeq), and
an envelope stream with `seq > watermark` — a subscription with no loss window: the subscriber
deduplicates by seq and does not miss events that occur between the snapshot and
connection to the stream. The reducer is the pure function `reduce(snapshot, envelope)`;
the snapshot can always be reconstructed from the envelope stream (events, not renderer
guesses, are the source of truth for the UI).

### 2.3 Two delivery classes

- **Reliable (terminal/approval/accounting)**: publishing terminal/approval/
  budget events uses a **separate reliable buffer** with bounded publisher wait
  (cap + timeout); on overflow return typed `EventError.Backpressure`,
  never silently drop. In-process below the cap, these events are neither lost nor
  reordered relative to per-job seq; beyond the cap or on process failure there is no
  guarantee (durable journal is out of scope, §7).
- **Diagnostics (text tails, intermediate lines)**: publish to a separate
  diagnostics buffer, immediately dropping on overflow and incrementing
  `droppedDiagnostics`; a slow consumer never blocks model/tool/cancellation
  paths (the diagnostics buffer cannot consume the reliable buffer). Coalescing is
  allowed for diagnostics, with an explicit loss count in the replacement envelope
  (`DiagnosticsCoalesced(lost: Long)`).
- Cancellation is checked outside model admission and does not wait for consumers (P1.C invariant:
  a slow viewer does not block cancel).

### 2.4 Wait reasons and bounded progress

`WaitReason` is an exhaustive enum of reasons (Slot, Admission, Approval, Budget,
ChildSlot, Backpressure). Progress contains only observed facts: tool call state
and a bounded output tail (`LineTail`: last N lines, truncation counters), with no
invented percentages or model thoughts. Cost is represented by
`UsageSnapshot(reserved, observed, unknown: Boolean)`; unknown remains unknown,
never becomes 0.

### 2.5 UI focus is interface state only

Tab/session focus is UI-layer data (`SessionRegistry`, RAI-057). Focus does not
change owner, budget, policy, or admission, and does not cancel/pause jobs.
Changing focus is a registry operation, not a runtime operation.

### 2.6 Schema and compatibility

`schemaVersion = 1`; JSON codec (zio-json) for envelopes; round trip is required
(contract tests). Existing TraceEvent emitters do not change under P1.A;
their integration with the hub is covered by separate P1.B/P2.C cards (admission/model/
tool/retry/compaction/budget emitters), each with cancellation/ownership/budget tests.
Planned seam for real emission: an optional single-listener hook on
JobManager (`onTransition(jobId, from, to) => UIO[Unit]`, a nonblocking call
after STM commit). Its addition will be a separate runtime card with a
cancellation-test matrix, and is not part of the freeze below.

## 3. Alternatives considered

1. **Extend TraceEvent with jobId/seq fields** — breaks existing consumers
   and schema compatibility; rejected.
2. **One zio.Hub with await strategy** — a slow consumer blocks the publisher
   (violates P1.C); rejected.
3. **Pull/poll snapshots only** — cannot achieve p95 ≤250 ms (UX03) without a busy loop;
   rejected (pull remains for resync).
4. **Actor/custom bus with an unbounded queue** — prohibited
   quality-gates §7.15 (queues must be bounded); rejected.

## 4. Consequences and risks

- Diagnostic loss during a burst is an accepted risk, reported by a counter (never silent).
- Duplicate events during subscription replay are possible — subscribers must deduplicate by (jobId, seq);
  the reducer is idempotent for a repeated seq (typed error on regression).
- Ordering: events for one job are processed strictly by seq; events from different jobs
  are not ordered relative to one another (reducer per job).
- Reliable event delivery is limited by a cap: publisher wait is bounded
 ; on
  timeout return typed `EventError.Backpressure`, never silently drop.

## 5. Frozen signatures (P1/P2 implement these exactly; changes require a new ADR)

```scala
package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}   // existing ids (modules/core/ids.scala)
import raider.runtime.jobs.JobStatus
import zio.{IO, UIO, ZStream}

final case class EventEnvelope(
  schemaVersion: Int,        // always 1 in this revision
  seq: Long,                 // per-job, monotonic from 1, without gaps
  jobId: JobId,
  rootId: RootId,
  sessionId: SessionId,
  atMs: Long,                // epoch millis, informational
  payload: EventPayload
)

enum EventPayload:
  case JobStatusChanged(from: JobStatus, to: JobStatus, reason: Option[WaitReason])
  case Waiting(reason: WaitReason)
  case ModelCallStarted(round: Int, model: String, messageCount: Option[Int])
  case ModelCallFinished(round: Int, durationMs: Long, usage: UsageSnapshot)
  case ToolStarted(callId: String, tool: String, argsSummary: String) // redacted, ≤512 chars
  case ToolProgress(callId: String, tool: String, tail: LineTail)
  case ToolFinished(callId: String, tool: String, outcome: ToolOutcome)
  case RetryScheduled(attempt: Int, delayMs: Long, cause: String)
  case BudgetChanged(reserved: MicroUsd, observed: MicroUsd, unknownAttempts: Long)
  case Delegated(childJobId: JobId, agent: String)
  case Diagnostics(text: String)                                       // ≤2000 chars
  case DiagnosticsCoalesced(lost: Long)
  case Terminal(outcome: JobStatus.Terminal)                           // exactly one, last

enum WaitReason { case Slot, Admission, Approval, Budget, ChildSlot, Backpressure }

enum ToolOutcome { case Succeeded, Failed(reason: String), Cancelled, Timeout }

final case class LineTail(lines: Vector[String], truncatedLines: Int, truncatedChars: Long)
final case class UsageSnapshot(reserved: MicroUsd, observed: MicroUsd, unknown: Boolean)

final case class SessionSnapshot(
  jobId: JobId, rootId: RootId, sessionId: SessionId,
  status: JobStatus, waitReason: Option[WaitReason],
  lastSeq: Long,
  activeTool: Option[(String, String)],   // (tool, callId)
  progressTail: LineTail,
  usage: UsageSnapshot,
  updatedAtMs: Long
)

/** Own sealed family following JobsError (RaiderError is sealed, so subtypes
  * cannot be defined in another file); conversion is explicit. */
sealed trait EventError:
  def toRaiderError: RaiderError
object EventError:
  final case class Backpressure(jobId: JobId, cap: Int) extends EventError:
    def toRaiderError: RaiderError = RaiderError.CapabilityUnsupported(
      s"event backpressure for ${jobId.value} (reliable cap $cap)")
  final case class Malformed(msg: String) extends EventError:
    def toRaiderError: RaiderError = RaiderError.InputValidation(msg)
  // NOTE: verify exact RaiderError constructors against modules/core/errors.scala
  // during implementation; changing RaiderError is prohibited (frozen core).

trait EventHub:
  def publish(jobId: JobId, rootId: RootId, sessionId: SessionId,
              payload: EventPayload): IO[EventError, Unit]
  def subscribe(jobId: JobId): UIO[(SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])]
  def snapshot(jobId: JobId): UIO[Option[SessionSnapshot]]
  def droppedDiagnostics: UIO[Long]

object EventHub:
  /** Two independent bounded buffers: reliable and diagnostics. Diagnostics-full
    * → drop+counter (never blocks publisher); reliable-full → bounded
    * wait, then EventError.Backpressure. */
  def make(reliableCapacity: Int = 256,
           diagnosticsCapacity: Int = 1024): UIO[EventHub]

object SessionReducer:
  def initial(jobId: JobId, rootId: RootId, sessionId: SessionId): SessionSnapshot
  def reduce(s: SessionSnapshot, e: EventEnvelope): IO[EventError, SessionSnapshot]
```

(`MicroUsd` is reused from `raider.runtime.budget.BudgetLedger`;
`JobId/RootId/SessionId` are from `raider.core` (ids.scala, exported);
`JobStatus`/`JobStatus.Terminal` are from `raider.runtime.jobs`; no new money types or
duplicate ids.)

## 6. Acceptance (in RAI-055/057 and subsequent P1.B/P2.C cards)

EVT-01..04 (see backlog) + JSON roundtrip + invariants: the only Terminal
is last by seq; reduce is idempotent for duplicate seq (typed error on regression);
only diagnostics may be dropped, with a counter; a slow consumer does not affect
cancellation timing (measured in a test with a virtual clock).

## 7. Out of scope

Real emission from AgentLoop/JobManager (P1.B/P2.C), terminal rendering
(P2.B/C, ADR-016), durable journal (P6), and gate policy changes. Gate stage for
RAI-055 — component (static/unit/contracts/runtime).
