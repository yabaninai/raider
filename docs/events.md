<!-- RAI-072: documentation of the ACTUALLY implemented behavior of the
     runtime→UI event seam (ADR-024) and streaming writes to events.jsonl
     (RAI-060, LIN-03A-prep). Introduces no new decisions; the wire schema is
     frozen (schemaVersion=1, 13 payload cases). Every claim includes a link
     to a specific file:line in this tree. -->

# Runtime→UI events: envelope, hub, reducer, events.jsonl

The event seam is an in-process channel through which the runtime delivers event envelopes for one job to a UI/CLI consumer. Sources of truth: `modules/runtime/src/main/scala/raider/runtime/events/` (model, hub, reducer, codec), emitters in `modules/runtime/src/main/scala/raider/runtime/`, and streaming writes in `modules/cli/src/main/scala/raider/cli/run/`. This document describes only what is implemented; the event schema (13 payload cases, `schemaVersion=1`) is frozen.

## 1. EventEnvelope

The envelope is the seam's only wire type: `schemaVersion` (always 1 in this revision), `seq` (per-job, monotonic starting at 1, with no gaps), routing identifiers `jobId`/`rootId`/`sessionId`, an informational timestamp `atMs` (epoch millis), and one typed `payload` — `events.scala:16-24` in `modules/runtime/src/main/scala/raider/runtime/events/events.scala`. `SessionSnapshot` is in-memory reducer state, not a wire document (`events.scala:92-107`).

## 2. The 13 frozen payload cases

Discriminated by the `type` field (`events.scala:26-30`). The complete list has exactly 13 cases (`events.scala:31-61`; completeness is fixed by test `EventCodecSpec.scala:52-66,72-75` in `modules/runtime/src/test/scala/raider/runtime/events/`):

| Case | Fields | Purpose |
| --- | --- | --- |
| `JobStatusChanged` | `from`, `to`, `reason: Option[WaitReason]` | job status transition (`events.scala:33-37`) |
| `Waiting` | `reason: WaitReason` | job is about to wait (UX03) (`events.scala:39`) |
| `ModelCallStarted` | `round`, `model`, `messageCount: Option[Int]` | model round begins (`events.scala:40`) |
| `ModelCallFinished` | `round`, `durationMs`, `usage: UsageSnapshot` | model call ends (`events.scala:41`) |
| `ToolStarted` | `callId`, `tool`, `argsSummary` (≤512 characters, redacted) | tool call starts (`events.scala:43-47`) |
| `ToolProgress` | `callId`, `tool`, `tail: LineTail` | bounded output tail (`events.scala:48`) |
| `ToolFinished` | `callId`, `tool`, `outcome: ToolOutcome` | tool call result (`events.scala:49`) |
| `RetryScheduled` | `attempt`, `delayMs`, `cause` | retry backoff scheduled (`events.scala:50`) |
| `BudgetChanged` | `reserved`, `observed: MicroUsd`, `unknownAttempts` | budget state (`events.scala:52-56`) |
| `Delegated` | `childJobId`, `agent` | child agent forked (`events.scala:58`) |
| `Diagnostics` | `text` (≤2000 characters) | untyped text (`events.scala:59`) |
| `DiagnosticsCoalesced` | `lost: Long` | marker for lost diagnostics (`events.scala:60`) |
| `Terminal` | `outcome: JobStatus.Terminal` | exactly one, last (`events.scala:61`) |

Helper types: `WaitReason` is `Slot|Admission|Approval|Budget|ChildSlot|Backpressure` (`events.scala:64-65`); `ToolOutcome` is `Succeeded|Failed(reason)|Cancelled|Timeout` (`events.scala:68-72`); `LineTail` is the last lines with honest truncation counters `truncatedLines`/`truncatedChars` (`events.scala:77-81`); `UsageSnapshot` is `reserved`/`observed` in micro-USD plus the `unknown` flag (“unknown is not coerced to 0/false”, `events.scala:86-90`).

## 3. Wire format (zio-json)

Codecs are `EventCodecs` (`codec.scala:24-85` in `modules/runtime/src/main/scala/raider/runtime/events/codec.scala`); rules are recorded in the `codec.scala:8-23` docstring and verified by test `EventCodecSpec.scala:98-116`:

- identifiers are encoded as plain strings (`jobId: "j_1"`) — core codecs `modules/core/src/main/scala/raider/core/codecs.scala:18-22`;
- payload is discriminated by the `type` field (`codec.scala:81`, derived for the enum);
- `JobStatus`/`JobStatus.Terminal` are encoded by name (`Queued`, `Running`, `Waiting`, `WaitingApproval`, `Cancelling`, `Succeeded`, `Failed`, `Cancelled`, `Interrupted`, `Uncertain`); an unknown name is a decode error (`codec.scala:29-71`);
- `MicroUsd` is a plain integer (`codec.scala:26-27`);
- absent `Option` fields are omitted on the wire and decoded as `None` (`codec.scala:18-19`, verified by `EventCodecSpec.scala:114-115`).

Example events.jsonl line (shape verified by test `EventCodecSpec.scala:98-116`):

```json
{"schemaVersion":1,"seq":1,"jobId":"j_1","rootId":"cli_ab12cd34","sessionId":"","atMs":1760000000000,"payload":{"type":"JobStatusChanged","from":"Queued","to":"Running"}}
```

Adding a case is a schema decision (a new schemaVersion), not a silent shape change (`events.scala:26-29`, `codec.scala:12-14`).

## 4. EventHub: publishing, subscribing, routing

`EventHub` is a live in-process hub (`hub.scala:29-65` in `modules/runtime/src/main/scala/raider/runtime/events/hub.scala`):

- `publish(jobId, rootId, sessionId, payload)` atomically assigns `lastSeq + 1` together with enqueueing into the buffer under a per-job lock; the sequence is committed only when actually accepted into the delivery-class buffer, so the delivered stream has no gaps (`hub.scala:31-41,10-16`); the per-job lock serializes publishers for the same job (`hub.scala:359-374`), and different jobs do not share a lock (`hub.scala:360-363`);
- `subscribe(jobId)` registers the subscriber queue and reads the snapshot in ONE STM transaction, then filters the stream to `seq > snapshot.lastSeq`: each event is either in the snapshot or in the stream — never both, never neither (`hub.scala:43-57,183-219`);
- `snapshot(jobId)` is the current folded state; `None` for a job with no events (`hub.scala:59-60,221-222`);
- `droppedDiagnostics` counts dropped diagnostics envelopes; drops are counted, never silent (`hub.scala:62-65,224,352-356`).

Delivery classes are the `routeOf` routing table (`hub.scala:135-152`):

- RELIABLE (offer with a bounded 5-second wait; on timeout, typed `EventError.Backpressure`, no silent loss, per-job order): `Terminal`, `Waiting`, `JobStatusChanged`, `BudgetChanged`, `RetryScheduled`, `Delegated`;
- DIAGNOSTICS (non-blocking offer; on overflow, drop + counter): `ModelCallStarted`, `ModelCallFinished`, `ToolStarted`, `ToolProgress`, `ToolFinished`, `Diagnostics`, `DiagnosticsCoalesced`.

Constants: reliable publish timeout 5 s (`hub.scala:70`), subscriber queue capacity 1024 (`hub.scala:73`), default buffer capacities 256/1024; non-positive values cause `IllegalArgumentException` at construction (`hub.scala:85-96`). The publisher never waits on subscriber queues — backpressure is absorbed by forwarder fibers (`hub.scala:21-22,247-251`).

The relative arrival order of reliable and diagnostics envelopes for one job is NOT specified (two independent forwarder fibers); the consumer must sort by `seq` before folding (`hub.scala:49-53`). Known limitation of this slice: forwarders have no supervision — a faulty forwarder can lose one envelope and one permit; supervision is deferred to a separate card (`hub.scala:228-234`).

The hub has no `stop()`: it is a process-lifetime object, and teardown is delegated to the surrounding scope (`hub.scala:24-27,129-133`).

## 5. SessionReducer: folding the stream into a snapshot

Pure per-job fold `(snapshot, envelope) => snapshot`, with no effects (`reducer.scala:8-20,47-64` in `modules/runtime/src/main/scala/raider/runtime/events/reducer.scala`):

- `seq < lastSeq` → `EventError.Malformed` (regression); `seq == lastSeq` is idempotent; a new event after a terminal snapshot → `Malformed` (Terminal occurs exactly once and last) (`reducer.scala:47-64`);
- initial state is `Queued`, empty tail, zeros (`reducer.scala:29-45`);
- fields folded: status/wait reason from `JobStatusChanged`/`Waiting`, `usage` from `ModelCallFinished`/`BudgetChanged` (unknown = `unknownAttempts > 0`), `activeTool` from `ToolStarted`/`ToolProgress`/`ToolFinished`, `Terminal` fixes the status and clears the wait reason (`reducer.scala:69-103`);
- progress tail is bounded: last 8 lines (`maxTailLines`), each line truncated at 2000 characters (`maxTailLineChars`); every evicted line and every truncated character is counted in `truncatedLines`/`truncatedChars` (`reducer.scala:23-27,111-129`).

## 6. Seam errors

`EventError` is its own sealed family (the core `RaiderError` family is frozen; conversion is explicit only): `Backpressure(jobId, cap)` — reliable-buffer overflow after timeout, and `Malformed(msg)` — seq regression, event after Terminal, unknown wire value (`modules/runtime/src/main/scala/raider/runtime/events/errors.scala:12-31`).

## 7. Emitters: who publishes events

- Job layer: `StatusLedger.onTransition` bridges `JobManager` transition hooks to the hub — terminal `to` is published as `Terminal(outcome)`, other transitions as `JobStatusChanged(from, to, reason = None)`; publish errors are swallowed (`StatusLedger.scala:7-29,36-50` in `modules/runtime/src/main/scala/raider/runtime/jobs/`). The job layer's session is empty (`StatusLedger.scala:32-33`).
- Loop layer: `LoopEvents` binds one `AgentLoop` run to job/root/session and emits `Waiting(Slot)`, `RetryScheduled`, `ModelCallStarted/Finished`, `ToolStarted/Finished`, `Diagnostics` (`LoopEvents.scala:21-39,150-240` in `modules/runtime/src/main/scala/raider/runtime/loop/`). Any publish error is swallowed — the run is not broken (`LoopEvents.scala:95-99`); usage is always `unknown = true` (the loop does not observe cost, `LoopEvents.scala:41-44,175-182`). Hygiene limits: `argsSummary` ≤512, `Diagnostics` ≤2000, `cause`/`reason` ≤512 (`LoopEvents.scala:244-253`). Tool refusals are published as `ToolFinished(Failed("refused: …"))`, including a refusal without a preceding `ToolStarted` (`LoopEvents.scala:200-214,255-263`).
- Delegation: `DelegationEvents` publishes `Delegated(childJobId, agent)` on fork and `Diagnostics` when an observed child completes (`DelegationToolset.scala:68-71` in `modules/runtime/src/main/scala/raider/runtime/delegate/`).

## 8. Headless run: streaming events.jsonl

`raider run` performs the entire run (including preflight) as ONE job in the JobManager with a real hub (`HeadlessRunner.scala:117-123` in `modules/cli/src/main/scala/raider/cli/run/`); invocation state lives in `<out>/work-<uuid8>` (`HeadlessRunner.scala:112-116`) — concurrent runs do not share directories.

The event stream is written incrementally to `<workDir>/events.jsonl` (`HeadlessRunner.scala:203-277`; writer: `EventsJsonlWriter.scala:35-111` in the same directory):

- file is opened eagerly (CREATE + APPEND, UTF-8, parent directories created) — an observer sees the file from the first record (`EventsJsonlWriter.scala:83-109`);
- each envelope is one JSONL line: write + flush to the page cache, WITHOUT fsync (a crash/cancel leaves an honest truncated tail after already flushed lines, without fabricating a final line) (`EventsJsonlWriter.scala:22-33,42-63`);
- one writer under a semaphore — lines do not interleave; append after `close` is a defect, `close` is idempotent (`EventsJsonlWriter.scala:26-33,47-74`);
- line order: job body is held at a barrier until subscriber registration; the initial `Queued→Running` transition is recovered FROM the subscription snapshot (`startTransition`, `EventExport.scala:77-94` in the same directory) and written as the first line; the rest arrive through the subscription and are re-sorted into strict per-job seq order (`EventExport.inOrder`, `EventExport.scala:21-53`); an observed run is dense from 1 and ends with a Terminal envelope (`HeadlessRunner.scala:180-191,255-266`);
- the hub drop counter observed before draining is exported as a real `DiagnosticsCoalesced` envelope (best-effort, diagnostics class, never after Terminal) (`HeadlessRunner.scala:224-233`);
- before completion, there is a bounded wall-clock wait for the observer to catch up (`awaitCaughtUp`, `EventExport.scala:96-128`), then the collector is interrupted (`HeadlessRunner.scala:267-274`);
- summary.md includes the line `events: N event envelope(s) streamed to events.jsonl (schemaVersion 1)` (`HeadlessRunner.scala:573`).

The `inOrder` reorder holds a bounded pending map and drains it as the expected seq appears; the delivered hub stream has no gaps (a dropped diagnostic did not consume a seq), so draining completes; cap 4096 is purely defensive (`EventExport.scala:21-53`). `EventExport.jsonl` is the canonical serialization of an envelope collection (sorted by seq, duplicates defensively discarded, `EventExport.scala:55-66`).

The overflow policy for a headless-run subscriber matches hub semantics: a slow subscriber applies backpressure to the forwarder, diagnostics overflow is dropped and counted inside the hub, reliable envelopes (status/Terminal/approval) are never silently lost (`HeadlessRunner.scala:193-201`).

## 9. Implemented boundaries (honestly)

- The seam is in-process: events have no external transport (WebSocket/HTTP-stream); the only file surface is `events.jsonl` for one headless run.
- CLI commands for subscribing to/replaying events are absent: `raider run` writes `events.jsonl`; there is no separate `raider events` in the tree (`CliArgs.scala:92-102` — only `run`/`chat` subcommands, in `modules/cli/src/main/scala/raider/cli/run/`).
- Hub forwarders have no supervision (`hub.scala:228-234`); bounded rotation of events.jsonl is not implemented — this limit is deferred to the JobJournal layer (`EventsJsonlWriter.scala:17-20`).
- Emission journaling (JobJournal, RAI-093) is a separate optional `LoopEvents` channel with its own journal-local seq; it is not part of events.jsonl and does not restore hub state (`LoopEvents.scala:46-78`).

## 10. Tests fixing the behavior

`EventCodecSpec` (roundtrip for all 13 cases and wire shape, `modules/runtime/src/test/scala/raider/runtime/events/EventCodecSpec.scala`), `EventHubSpec`/`SessionReducerSpec` (ordering, backpressure, drop counter, seq rules, in the same directory), `EventExportSpec`/`EventsJsonlWriterSpec`/`HeadlessRunnerSpec`/`HeadlessRunnerCrashSpec` (reordering, first line, streaming, honest tail — `modules/cli/src/test/scala/raider/cli/run/`).
