# Raider Architecture

Raider is a self-hosting agent harness for CI pipelines: a Scala 3 + ZIO 2
runtime where a model backend drives workspace tools through ONE bounded loop,
with budget/admission accounting on every call. This guide explains the module
structure, the data flow of an agent round, and the resource-control system.

## 1. Module structure

```
raider (root)
├── raiderCore            frozen types: ModelBackend/ModelEvent, Tool,
│                         RaiderError, TraceEvent, Task/Bundle/Job, codecs
├── raiderRuntime         AgentLoop, Admission, BudgetLedger, JobManager,
│                         DelegationToolset, runtime→UI event seam
│                         (EventHub/reducer), display decorators
├── raiderUi              tab/session UI state over the event seam
│                         (SessionRegistry, TabShell)
├── raiderDsl             composition interpreter (all / batch, fail-fast)
├── raiderTestkit         scripted backends and fixtures (used by REPL mock
│                         wiring; excluded from the headless CLI dependency graph)
├── raiderTools           CodingToolset: fs_read/fs_search/fs_edit/fs_patch/
│                         fs_tree/proc_run over a Workspace
├── raiderProviderChat    OpenAI-compatible transport (+ SSE streaming)
├── raiderProviderAnthropic  Anthropic Messages transport (+ SSE)
├── raiderReplEngine      in-process scala3-repl driver (pinned 3.9.0)
├── raiderRepl            REPL facade + console + live wiring
├── raiderCli             headless runner + streaming chat (no compiler/JLine)
└── raiderCliFixtures     compiled demo bundle for runner tests
```

Dependency direction: everything flows down to `raiderCore`. Providers depend
only on core (plus shared SSE framing from provider-chat). `raiderCli`,
`raiderRepl` and `raiderUi` are frontends over the same runtime — the CLI is
headless (no compiler/JLine in its distribution), the REPL embeds the real
Scala 3 compiler, `raiderUi` is the tab shell (JLine only, no compiler). There
is no second scheduler: the REPL facade, the chat loop, the headless runner
and the tab shell all delegate to the same `AgentLoop`.

## 2. Data flow: one agent round

```
model ──SSE/HTTP──► ModelBackend.stream ──► ModelEvent stream
                                              │
   ┌──────────────────────────────────────────┘
   ▼
AgentLoop.collect  (ONE admission attempt: Model slot + BudgetLedger reserve)
   │  TextDelta…    → accumulated text           (displayed live if wired)
   │  ToolCallReady → the ONLY executable request (deltas never execute)
   ▼
Tool dispatch (sequential, Tool slot, per-tool timeout)
   │  lookup → validate args JSON → invoke → JSON-validated result
   │  unknown tool / bad args → structured FEEDBACK message (loop continues)
   │  definitive failure      → typed failure of the run (no blind retries)
   ▼
tool-role message appended (matched by tool_call_id) → next round
   │
   ▼
final text (non-empty, marker-free) → RunFinished trace → caller
```

Key invariants:

- **One execution point.** REPL `ask`, chat, `:fix`, headless coder runs and
  child delegation all ride `AgentLoop.runText`. Nothing re-implements the
  round semantics.
- **Traces are data.** Every round/call/result emits `TraceEvent`s; the tracer
  is an injected `TraceEvent => UIO[Unit]` (stderr echo in interactive
  frontends, transcript.json in the runner, Ref capture in tests).
- **Honesty at the edges.** Unknown tools and malformed arguments are feedback,
  not crashes; crash-flavored tool failures are typed failures, never masked as
  conversational content. Mutational tool calls are never auto-retried after an
  ambiguous outcome.

### 2.1 Event seam: how the UI observes a run

Live observability is a one-way publish seam — the runtime emits, consumers
fold. The hub never calls back into the runtime.

```
AgentLoop emitters (LoopEvents) ──┐
JobManager transitions (StatusLedger) ──┤──► EventHub ──► StatusLine (chat)
DelegationToolset (DelegationEvents) ──┘   (reliable |      tabs/registry (raiderUi,
                                            diagnostics)     SessionRegistry)
```

- **Routing.** Each of the 13 frozen payload cases is RELIABLE (bounded-wait,
  typed `Backpressure` on timeout, no silent loss — `Terminal`, `Waiting`,
  `JobStatusChanged`, `BudgetChanged`, `RetryScheduled`, `Delegated`) or
  DIAGNOSTICS (non-blocking, dropped only with a counted bump — model/tool
  progress, `Diagnostics`). Every envelope carries a per-job `seq`, monotonic
  from 1 and gap-free; consumers reorder by `seq` (the two classes are relayed
  by independent forwarders, so cross-class arrival order is unspecified).
- **Authority split.** The loop emits model/tool/wait events and NEVER a
  terminal status: `JobStatusChanged` and the exactly-one, always-last
  `Terminal` belong to the job layer (`StatusLedger`). The reducer refuses any
  event after a terminal snapshot as `Malformed`.
- **Consumers stay dumb.** `SessionReducer` folds envelopes into a
  `SessionSnapshot`; `StatusLine` renders one line per change; the
  `SessionRegistry` keeps per-tab history/scrollback with honest eviction
  counters. Emission failure never breaks a run, and a slow consumer can never
  block the loop.
- **Cancel is outside model admission.** Besides the model-callable
  `cancel_agent`, the owner/UI channel cancels children directly via
  `DelegationToolset.cancelOutOfBand` / `cancelAllOutOfBand` (`jobs.cancelAny`
  with no model round and no second slot) — reachable even when a parked child
  holds the only model slot. Per-child deadline watchdogs (`setChildDeadline`,
  bounded by `deadlineGrace`) do the same on expiry; an unobservable outcome
  past the grace is reported Uncertain, never a fake `Cancelled`.

## 3. Budget & admission system

Resource control is two-layered:

1. **BudgetLimits** (validated construction): `maxConcurrentLlm`,
   `maxConcurrentTools`, `maxChildren`, `maxDepth`, `maxAttempts`,
   `queueCapacity`, optional `hardUsdCap` (`MicroUsd` — Long micro-USD; Double
   money arithmetic is banned by the forbidden-API gate).
2. **Admission** leases slots per kind (`Model`, `Tool`) in the §6 acquisition
   order. An `AgentLoop` round = ONE attempt: acquire the Model slot, reserve
   budget, dispatch the stream, settle on exit. Cancellation accounting is
   owned by admission brackets: cancel while waiting charges nothing; interrupt
   after acquire keeps the charge and marks the reservation Uncertain — unknown
   is never zeroed.

Delegation extends this to child agents: `DelegationToolset` exposes
`delegate` / `await_agent` / `cancel_agent` / `list_agents` as model-callable
tools over the same `JobManager`. Children share the root's ledger (one
accounting root), and `maxConcurrentTools >= 2` is REQUIRED because the
awaiting parent holds a tool slot while children run (BUD-03 no-deadlock
rule). The `maxLLM=1` parent/cancel interplay (a parent parked behind a child
can never get a slot to issue `cancel_agent`) is resolved OUTSIDE model
admission: the out-of-band cancel channel and child deadlines of §2.1 reach
the same `JobManager` directly.

Jobs (`JobManager`) own lifecycle: start (daemon fiber, cancellation-safe
registration), STM status transitions, typed result publication — a final
status is always published, even for watcher defects (waiters never hang on a
silent drop).

## 4. Frontends

### CLI (headless)

`raider run` loads a jar bundle (`BundleLoader`, manifest-validated) or uses a
built-in agent (`--agent coder` = CodingToolset + coder system prompt). It
performs preflight BEFORE any spend, writes artifacts (`run.json`,
`transcript.json`, `events.jsonl`, `summary.md`) and maps failures to 18
stable exit-code families. SIGTERM stops through a bounded shutdown hook.
`raider chat` is the interactive frontend: streaming deltas (stdout), live tool
echo and a status line folded from the event seam (stderr, one synchronized
writer), session persistence (`~/.raider/sessions`), conversation
compaction and the `:fix` compile→errors→model→recompile loop. Credentials
come only from the environment — `RAIDER_API_KEY` or `RAIDER_API_KEY_CMD`;
`--api-key` in argv is a typed `RA-CFG` error, never a parsed secret.

### REPL

The REPL embeds the pinned scala3-repl in-process. The classloader split is
real: interpreter-world classes load SELF-FIRST, so the prelude constructs the
backend, tools, and session inside that interpreter world instead of passing
console-world statics across the boundary. The facade (`AgentRef.ask`, `all`,
`batch`, `openSession`) blocks the REPL thread over the same async runtime —
one semantics, two spellings. With a resolvable key, the OpenAI branch wires
the SSE streaming backend and coding tools; the Anthropic branch wires the
Anthropic Messages streaming backend. The Anthropic request currently omits
tool definitions (RAI-127 remains open), so do not infer tool-call parity.

## 5. Provider transport

Both providers share one framing layer (`SseFramer`, byte-level, UTF-8-safe,
bounded frames) with wire-specific semantic parsers. Status mapping is uniform:
401/403 → `ProviderAuth`, 429 → `ProviderRateLimit`, 5xx →
`ProviderUnavailable`, else → `StreamProtocol`. The response body is closed on
every exit path (scope-acquire/release); TCP close is NOT proof upstream
stopped metering — cost stays Uncertain. `RateLimitRetries` adds 429-only
exponential backoff (1s/2s/4s, max 3) as a decorator at construction sites;
everything else stays first-shot.

## 6. Self-hosting

The coder agent (`--agent coder`) is pointed at the Raider workspace itself
with the six workspace tools; its system prompt enforces READ → UNDERSTAND →
CHANGE → COMPILE → FIX → TEST → SUMMARIZE. Structured `proc_run` output
(`parsed` field: `sbt_compile` errors / `sbt_test` summaries) and `:fix` make
the edit→compile→fix loop explicit and budget-bounded. The quality system the
agent works inside is documented in `docs/quality-gates.md`.

## 7. Where to look

| Topic | File |
| --- | --- |
| Frozen contracts | `contracts/`, `docs/runtime-contracts.md` |
| Gate catalog & stage policy | `docs/quality-gates.md`, `scripts/quality-policy/registry.json` |
| Provider wire details | `docs/provider-protocols.md` |
| API reference | `docs/api.md` |
| Contributing (add tool/provider/tests) | `docs/contributing.md` |
| Work history | `docs/work/board.md`, `docs/work/records/` |
