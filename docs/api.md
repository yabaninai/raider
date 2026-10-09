# Raider API Reference

Audience: developers embedding Raider's agent runtime or extending its toolset.
Everything documented here is product code (Scala 3 + ZIO 2). Frozen contracts
live in `contracts/` and `docs/runtime-contracts.md`; this page is the
practical map of the implemented public surface.

## Module map

| Module (sbt id) | Package root | Purpose |
| --- | --- | --- |
| `raiderCore` | `raider.core` | Frozen types: model backend/events, tools, errors, traces, tasks, bundles, ids, codecs |
| `raiderRuntime` | `raider.runtime` | AgentLoop, admission, budget ledger, jobs, delegation, runtime→UI event seam, display decorators |
| `raiderUi` | `raider.ui` | Tab/session UI state over the event seam (SessionRegistry, TabShell) |
| `raiderTools` | `raider.tools` | Workspace tools: fs_read/fs_search/fs_edit/fs_patch/fs_tree/proc_run |
| `raiderProviderChat` | `raider.provider.chat` | OpenAI-compatible Chat transport (non-streaming + SSE) |
| `raiderProviderAnthropic` | `raider.provider.anthropic` | Anthropic Messages transport (non-streaming + SSE) |
| `raiderDsl` | `raider.dsl` | Composition interpreter (all/batch, fail-fast) |
| `raiderTestkit` | `raider.testkit` | Scripted backends/fixtures (test scope only) |
| `raiderReplEngine` | `raider.repl.engine` | In-process scala3-repl driver |
| `raiderRepl` | `raider.repl` | REPL facade, console, live wiring |
| `raiderCli` | `raider.cli` | Headless runner + interactive chat (no compiler/JLine) |

## Core (`raider.core`)

### Model backend

```scala
trait ModelBackend:
  def capabilities: ModelCapabilities   // streaming / tools / cancellationAck
  def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent]
```

- `ModelRequest(model, messages: List[RequestMessage], maxOutputTokens, tools: List[ToolDefinition])`
- `RequestMessage(role, content)` — roles: `system`, `user`, `assistant`, `tool`
- `ModelEvent` (sealed): `Started`, `MetadataObserved`, `TextDelta`,
  `ToolCallDelta`, `ToolCallReady`, `UsageObserved`, `ToolResultObserved`,
  `Failed`, `Finished`.
  **Only `ToolCallReady` is executable** — `ToolCallDelta` fragments are never
  executed by the loop.
- All events are JSON-codec-derivable (`raider.core.codecs`).

### Tools

```scala
trait Tool:
  def name: String
  def version: Int
  def description: String
  def recovery: RecoveryClass          // ReadOnly / Mutating
  def timeoutMs: Long
  def capabilities: ToolCapabilities   // concurrentSafe
  def parametersJsonSchema: String     // default: {"type":"object",...}
  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String]
```

- `ToolRegistry.build(List[Tool])` / `ToolRegistry.of(tool)` return
  `Either[RaiderError, ToolRegistry]` — construction validates uniqueness,
  version >= 1, timeout > 0 and declaration of recovery/concurrency classes.
- `registry.lookup(name): Option[Tool]`, `registry.names: List[String]`.

### Errors

`RaiderError` is a sealed hierarchy with stable `code`s (e.g. `RA-CFG`,
`RA-AUTH`, `RA-RATE`, `RA-TOOL`, `RA-BUDGET`): Configuration, InputValidation,
OutputValidation, ProviderAuth, ProviderRateLimit, ProviderUnavailable,
StreamProtocol, ToolDenied, ToolFailed, DeadlineExceeded, Cancelled,
CapabilityUnsupported, Compilation, ProviderBudget, ToolOutcomeUncertain,
LocalBudgetExceeded, ChildFailed, JournalFailure.

### Traces

`raider.core.trace.TraceEvent` (sealed, JSON-codec): `RunStarted`,
`RoundStarted`, `ModelCall`, `TextReceived`, `ToolCallStarted`,
`ToolCallFinished`, `ToolCallRefused`, `ChildDelegated`, `ChildAwaited`,
`RunFinished`, `RunFailed`. Traces are pure data; recording dispatches nothing.

## Runtime (`raider.runtime`)

### AgentLoop — the single execution point

```scala
AgentLoop(
  backend: ModelBackend,
  tools: ToolRegistry,
  model: String,
  admission: Admission,
  root: RootId,
  attemptEstimate: CostEstimate = CostEstimate.Unknown,
  trace: TraceEvent => UIO[Unit] = _ => ZIO.unit,
  systemPrompt: Option[String] = None
).runText(messages, ceilings: BudgetLimits, maxRounds: Int): ZIO[Any, RaiderError, String]
```

- Bounded tool rounds; every round is one admission attempt (Model slot +
  BudgetLedger reservation) around one `backend.stream` pass.
- Tool dispatch is strictly sequential in model-emitted order (`Tool` slot).
- Definitive tool failures fail the run typed — mutational tools are never
  auto-retried (trust boundary).
- `runText` validates bounds BEFORE any call (`1 <= maxRounds <= maxAttempts`).

### Admission & budget

- `Admission.make(limits: Either[RaiderError, BudgetLimits])` — slot leases
  per `SlotKind.Model` / `SlotKind.Tool`; cancel-while-waiting charges nothing.
- `BudgetLimits.make(hardUsdCap, maxConcurrentLlm, maxConcurrentTools,
  maxChildren, maxDepth, maxAttempts, queueCapacity)` — validated construction.
- `MicroUsd(value: Long)` — money is Long-based micro-USD; Double arithmetic
  on money is banned by the forbidden-API gate.

### Jobs & delegation

- `JobManager.make()` → `start(root, label, task): UIO[JobHandle[A]]`,
  `await`, `cancelAny`, `snapshotAny`, `adminView`. Cancellation is
  transactional (STM transition + typed result publication).
- `DelegationToolset.make(jobs, backend, admission, limits, baseRegistry,
  allowedAgents, root, defaultChildDeadline = None,
  deadlineGrace = 5.seconds, delegationEvents = None)` adds model-callable
  `delegate` / `await_agent` / `cancel_agent` / `list_agents` tools. One root
  ledger; `maxConcurrentTools >= 2` required (the awaiting parent holds a slot
  — BUD-03 no-deadlock). Children cannot re-delegate; `send_agent` is
  deliberately absent.
- **Out-of-band cancel** (owner/UI channel, never a model-tool round):
  `cancelOutOfBand(childId): UIO[Boolean]` and
  `cancelAllOutOfBand: UIO[List[String]]` call `jobs.cancelAny` DIRECTLY — no
  model admission, no second slot, so cancel stays reachable while a parked
  child holds the only model slot. Model-tool `cancel_agent` semantics are
  unchanged.
- **Child deadlines** (Scala-level API; the delegate tool schema is
  unchanged): `setChildDeadline(childId, deadline): UIO[Boolean]` (and the
  `defaultChildDeadline` constructor default) arms an owner-side watchdog that
  cancels the child out-of-band and settles within `deadlineGrace`.
  `deadlineOutcome(childId): UIO[Option[DeadlineOutcome]]` reports
  `Settled` / `Uncertain` / `AlreadyTerminal` (`None` = not fired yet) — past
  the grace the outcome is Uncertain, never a fake `Cancelled`.

### Display decorators (`raider.runtime.display`)

- `StreamingDisplay(underlying, onTextDelta, onToolCall)` — transparent
  `ModelBackend` decorator surfacing deltas / ready tool calls to injected
  sinks while events pass through unchanged.
- `RateLimitRetries(underlying, maxRetries = 3, baseDelay = 1s)` — retries only
  `ProviderRateLimit` (HTTP 429) with exponential backoff 1s/2s/4s; everything
  else is first-shot.

### Mailbox (bounded send queue)

`raider.runtime.mailbox.Mailbox.make(jobId, capacity)` — bounded per-child очередь с message-ID дедупликацией и ack; `send` → `Ack`; применение — только на границе turn (`drainOnTurnBoundary`/`endTurn`); overflow — typed `MailboxError.Full`; terminal — `TerminalJob` (предложение начать новый turn). Wiring в делегирование — отдельная карточка.

## Runtime → UI event seam (`raider.runtime.events`)

The runtime publishes typed envelopes; consumers fold them into local view
state. The hub is the seam — it never calls back into the runtime, and
emission never breaks a run (every `EventError` is absorbed by the emitters).

```scala
EventHub.make(reliableCapacity = 256, diagnosticsCapacity = 1024): UIO[EventHub]

trait EventHub:
  def publish(jobId: JobId, rootId: RootId, sessionId: SessionId,
              payload: EventPayload): IO[EventError, Unit]
  def subscribe(jobId: JobId): UIO[(SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])]
  def snapshot(jobId: JobId): UIO[Option[SessionSnapshot]]
  def droppedDiagnostics: UIO[Long]
```

- `EventEnvelope(schemaVersion = 1, seq, jobId, rootId, sessionId, atMs,
  payload)` — the only JSON-crossing wire type of the seam. `seq` is per-job,
  monotonic from 1, gap-free: a failed publish or a dropped diagnostic consumes
  no sequence. There is no `stop()` — the hub is a process-lifetime object.
- 13 frozen `EventPayload` cases, routed by delivery class:
  RELIABLE — `Terminal`, `Waiting`, `JobStatusChanged`, `BudgetChanged`,
  `RetryScheduled`, `Delegated` (bounded-wait offer, `EventError.Backpressure`
  on timeout, no silent loss); DIAGNOSTICS — `ModelCallStarted`/`Finished`,
  `ToolStarted`/`ToolProgress`/`ToolFinished`, `Diagnostics`,
  `DiagnosticsCoalesced` (non-blocking, dropped only with a counted
  `droppedDiagnostics` bump). Cross-class arrival order at a subscriber is
  unspecified; consumers reorder by `seq` (see `EventExport.inOrder`).
- `subscribe` is loss-window-free: the subscriber queue is registered and the
  snapshot read in ONE transaction, then the stream is filtered to
  `seq > snapshot.lastSeq`. Interrupting the stream unregisters the queue.
- `SessionReducer.reduce(snapshot, envelope)` is the pure per-job fold:
  `seq < lastSeq` → `Malformed`; `seq == lastSeq` → idempotent; any event
  after a terminal status → `Malformed`. `Terminal` is exactly one event and
  the last one. `SessionSnapshot` is in-memory state, never a wire document.
- `EventCodecs` — zio-json codecs for the envelope; payloads are discriminated
  by a `type` field; adding a case is a schema decision (new schemaVersion),
  never a silent shape change.

Built-in emitters:

- `LoopEvents(hub, jobId, rootId, sessionId)` — loop-layer emission points:
  `Waiting(Slot)` / `RetryScheduled` (reliable); `ModelCallStarted`/`Finished`,
  `ToolStarted`/`Finished` (incl. documented refusals), `Diagnostics`
  (diagnostics). The loop observes no cost: usage is always `unknown = true`.
- `StatusLedger.onTransition(hub, rootId)` — the JOB layer owns
  `JobStatusChanged` and the single `Terminal`.
- `DelegationEvents(hub, rootId)` — `Delegated(childJobId, agent)` on fork
  (child's own stream) and child-completion `Diagnostics` under the per-root
  lifecycle pool.

## UI (`raider.ui`)

Read-model tab state over the event seam. The registry NEVER publishes, cancels
or mutates the runtime — it returns typed decisions for the caller to act on.

- `SessionRegistry.make(hub): UIO[SessionRegistry]` — one hub subscription per
  tab, started at `create`; envelopes are deduplicated by `(jobId, seq)` and
  reordered by `seq` before folding. API: `create(jobId, rootId, label)`,
  `tabs`/`tab` (read model `TabView`: label, jobId, status, unread, lastSeq),
  `close`, `hide`, `rename`, `focused`/`focus`/`focusNext`/`focusPrev`,
  `draftAppend`/`draftClear`/`draftGet` (UI-local, never events),
  `snapshot`/`history`/`scrollback`. Focus is UI state only.
- `close(tabId)` consults the HUB's authoritative job status: terminal →
  `CloseDecision.Closed(affected)` (tab removed); live (including a job that
  published nothing — initial `Queued`) → `RunningChoice(affected)` and the
  caller chooses via `TabCloseChoice`: `Hide` → `hide` (the job keeps running)
  or `CancelRequested` → the CALLER cancels through runtime APIs (e.g.
  `DelegationToolset.cancelOutOfBand`), then calls `close` again. `affected`
  is the lineage set: other open tabs of the same `rootId`, creation order.
- Bounds are honest: history = last 200 folded snapshots, scrollback = last
  500 text lines, each with an `evicted` counter; the newest snapshot — in
  particular `Terminal` — always survives eviction.
- `TabShell.run(hub, registry, terminal, resolver)` — thin JLine loop over the
  registry: `:tabs :new :switch :next :prev :rename :close :help :quit`;
  non-command input appends to the focused tab's draft. ONE writer (everything
  visible goes through `ShellTerminal.printAbove`); a background pump
  re-renders the tab bar on hub events.

## Tools (`raider.tools`)

`CodingToolset.make(workspaceRoot)` wires the six workspace tools (all
contained to the workspace by realpath; escapes are structured `ToolDenied`
feedback to the model, never crashes):

| Tool | Arguments (JSON) | Notes |
| --- | --- | --- |
| `fs_read` | `{"path","offset"?,"max_lines"?}` | bounded ranged read, returns content + sha256 |
| `fs_search` | `{"query","glob"?,"max_matches"?}` | literal search, declared truncation |
| `fs_edit` | `{"path","content","expected_sha256"}` | atomic temp+rename; empty sha = create |
| `fs_patch` | `{"path","diff","expected_sha256"}` | unified-diff apply; context must match; same sha/atomic contract |
| `fs_tree` | `{"depth"?,"glob"?}` | bounded tree (default depth 3, max 5; 500 entries; skips .git/target/node_modules) |
| `proc_run` | `{"argv","cwd"?,"env_allowlist"?,"env_values"?,"timeout_s"?}` | structured argv, no shell; output carries a `parsed` field (`sbt_compile` / `sbt_test`) from `raider.tools.process.OutputParser` |

All outputs are single-line JSON matched back by `tool_call_id`. Policy denials
(containment, bounds, bad args) are structured feedback to the model.

## Providers

- `raider.provider.chat.OpenAIChatBackend` — non-streaming OpenAI-compatible
  Chat (`/v1/chat/completions`); `Config.make(baseUrl, apiKey, callTimeoutMs)`.
- `raider.provider.chat.stream.OpenAIChatStreamingBackend` — SSE streaming wire
  (`"stream":true`), idle timeout = `callTimeoutMs`, bounded frame size.
- `raider.provider.anthropic.AnthropicMessagesBackend` — Anthropic Messages
  transport, native `tool_use`/`tool_result`, cumulative usage.
- Transport mapping: 401/403 → `ProviderAuth`, 429 → `ProviderRateLimit`,
  5xx → `ProviderUnavailable`, other → `StreamProtocol`.

## CLI (`raider.cli`)

```sh
# headless run (bundle or built-in agent)
raider run --bundle app.jar --workflow inspect --input "text" --out dir [--mock]
raider run --agent coder --provider openai --input "task" --out artifacts/selfdev

# interactive streaming chat
raider chat [--provider openai] [--base-url URL] [--model NAME]
            [--workspace PATH] [--resume SESSION]
```

Credentials are env-only (see below) — `--api-key` does not exist; passing it
is a typed `RA-CFG` error.

Chat commands: `:help :quit :clear :context :tools :trace :save <name>
:sessions :load <name> :compact :fix :pipe … :delegate …`.

- `raider.cli.chat.SessionStore` — `save/load/list` of `SavedSession`
  documents under `~/.raider/sessions/<id>.json` (atomic, sanitized ids).
- `raider.cli.chat.CompileFixLoop` — compile → structured errors → model fix →
  re-compile, bounded cycles (`:fix`).
- `raider.cli.chat.StatusLine` — one-line status folded from real hub
  envelopes (`queued`, `model round N`, `tool <name>`, `waiting(<reason>)`,
  `retry in <delay>ms`, `terminal <outcome>`, `cost unknown`); written to
  stderr through the single synchronized `ChatWriter`, never stdout.
- `raider.cli.run.EventExport` — consumer-side helpers:
  `inOrder` (reorders a subscribed stream into strict `seq` order),
  `jsonl` (canonical `events.jsonl` lines, schemaVersion 1),
  `startTransition` (deterministic pre-subscribe Queued→Running envelope),
  `awaitCaughtUp` (bounded best-effort tail wait — never a gate).
- `raider.cli.run.Credentials` — the ONLY credential source is the
  environment: `RAIDER_API_KEY` (the key; non-empty wins) or
  `RAIDER_API_KEY_CMD` (helper command; first stdout line is the key,
  hard-bounded at 10s, command text never logged). `mask(value)` (first 4
  chars + `***`, else `<redacted>`) is the only printable form. Live
  providers without either variable fail preflight (`Configuration`) BEFORE
  any dispatch/spend; the mock provider needs no key.
- `raider.cli.Main` is the only allowed `System.exit` site (enforced by the
  forbidden-API gate).

## REPL (`raider.repl`)

- `ReplSession.make(backend, limits, tools, model = "scripted",
  echoStream = false)` — one live session (backend + jobs + admission).
- Facade (extension methods on `AgentRef`, requires `using ReplSession`):
  `scout.ask("p")`, `scout("p").run()/.start()`, `all(a, b).ask("p")`,
  `batch(inputs, par)(f).run()`, `openSession(agent)` (multi-turn), `handle.await()/.cancel()`.
- `MainBridge` carries backend/tools/model across the in-process REPL
  classloader split via ThreadLocals (`setupLive` constructs the streaming
  backend when `RAIDER_PROVIDER=openai`; `RAIDER_MODEL` names the model).
- The REPL system prompt is prepended to every facade model call; with
  `echoStream = true` deltas print to stdout and tool calls/results to stderr
  in real time.

See also: `docs/architecture.md` (how the pieces compose),
`docs/contributing.md` (how to extend), `docs/runtime-contracts.md` (frozen
contracts), `docs/quality-gates.md` (gate catalog).
