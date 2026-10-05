# Raider API Reference

Audience: developers embedding Raider's agent runtime or extending its toolset.
Everything documented here is product code (Scala 3 + ZIO 2). Frozen contracts
live in `contracts/` and `docs/runtime-contracts.md`; this page is the
practical map of the implemented public surface.

## Module map

| Module (sbt id) | Package root | Purpose |
| --- | --- | --- |
| `raiderCore` | `raider.core` | Frozen types: model backend/events, tools, errors, traces, tasks, bundles, ids, codecs |
| `raiderRuntime` | `raider.runtime` | AgentLoop, admission, budget ledger, jobs, delegation, display decorators |
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
  allowedAgents, root)` adds model-callable `delegate` / `await_agent` /
  `cancel_agent` / `list_agents` tools. One root ledger; `maxConcurrentTools >= 2`
  required (the awaiting parent holds a slot — BUD-03 no-deadlock).

### Display decorators (`raider.runtime.display`)

- `StreamingDisplay(underlying, onTextDelta, onToolCall)` — transparent
  `ModelBackend` decorator surfacing deltas / ready tool calls to injected
  sinks while events pass through unchanged.
- `RateLimitRetries(underlying, maxRetries = 3, baseDelay = 1s)` — retries only
  `ProviderRateLimit` (HTTP 429) with exponential backoff 1s/2s/4s; everything
  else is first-shot.

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
            [--api-key KEY] [--workspace PATH] [--resume SESSION]
```

Chat commands: `:help :quit :clear :context :tools :trace :save <name>
:sessions :load <name> :compact :fix :pipe … :delegate …`.

- `raider.cli.chat.SessionStore` — `save/load/list` of `SavedSession`
  documents under `~/.raider/sessions/<id>.json` (atomic, sanitized ids).
- `raider.cli.chat.CompileFixLoop` — compile → structured errors → model fix →
  re-compile, bounded cycles (`:fix`).
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
