# Raider Long-Range Roadmap: Tabs, Live Statuses, Plugins, Agent State

Owner-directed long-range plan (2026-10-05, **v2** same day: owner added the
optional **agent state registry** with a reserved **Yabanin direct
integration** slot; +1,700 h). Scope: turn the current harness into a tool the
owner uses *simply*, around four capabilities — **subagent tabs**, **live
intermediate statuses**, **a convenient plugin system**, and an **optional
persistent state registry** (with Yabanin as a possible remote plane) — sized
for **~11,700 hours**. This document does not replace `docs/PLAN.md`
(historical scope) or the card system; it sequences the next era.

All product code stays Scala 3 + ZIO. Every phase exits through milestone
gates with honest evidence (AGENTS rules apply: no green skips, no silent
threshold changes, timeout ≠ passed).

## 1. Vision and usability budget

Raider becomes a single command the owner lives inside:

```
$ raider
┌─ [1:coder*]─[2:scout]─[3:reviewer●]─[+] ────────── 3 agents · $0.041 ─┐
│ coder ▸ round 4 ▸ model streaming ▍87 tok/s ▸ 12s                     │
│   → fs_patch modules/core/.../AgentLoop.scala   ctx 3/3 ✓             │
│   → proc_run sbt --batch compile                running 8s            │
│   → state_put blackboard.findings               (root scope)          │
│ dim: scout ○ idle · reviewer ◐ waiting model slot (2/2)               │
│ raider(coder)> _                                                       │
└────────────────────────────────────────────────────────────────────────┘
Ctrl+T new · Ctrl+PgDn next · Ctrl+K palette · F1 help
```

**Usability budget** (measured, gated — "simply" is a requirement, not a vibe):

| Metric | Budget |
| --- | --- |
| `raider` → first agent answering | < 10 s |
| Keystrokes to spawn a template agent | ≤ 3 |
| Keystrokes to switch tabs | ≤ 2 |
| Glanceable status of every agent | 1 line each, always visible |
| Commands a new user must memorize | 0 (hint bar + palette) |
| Plugin install → tool visible | ≤ 3 commands, zero rebuilds |
| Agent state: enable → first `state_put` | ≤ 1 config line + 0 rebuilds |

## 2. Gap analysis — capabilities vs today

| Capability | Exists today | Missing |
| --- | --- | --- |
| **Tabs** | JobManager jobs, DelegationToolset children, REPL `:jobs`, facade `ask/start/await/cancel`, per-agent `openSession` chats | Session registry with identity/persistence; any TUI (output is linear stdout); per-session scrollback; input routing; unread/activity indicators |
| **Statuses** | TraceEvents (rounds, tool calls, children), JobSnapshot statuses, StreamingDisplay deltas, tracer echo in chat/REPL | Central subscribable event bus; current-state ledger ("what is it doing NOW"); waiting-for-slot exposure; live tool progress (proc_run is final-only); budget/spend surfacing in UI; retry/backoff visibility |
| **Plugins** | Validated ToolRegistry; compiled-in providers; jar-bundle manifest precedent (`META-INF/raider-bundle.json`); ThreadLocal bridge lessons | Runtime loading of tools/providers/hooks; manifests + discovery; isolation & failure semantics; hooks/interceptors; plugin dev kit & gates; `raider plugin` CLI |
| **Agent state** | `Ref`-based in-memory state per run; chat `SessionStore` transcripts | Optional durable state registry: scoped KV (per-agent / per-root blackboard / global), atomic writes, opt-in tools, size/trust bounds; remote backend slot (Yabanin, Phase 7) |

Folded-in tracked obligations (from board/code comments): job steering
(`send` — currently a typed refusal), streaming per-event `watch`, parallel
`concurrentSafe` tool fan-out, REPL completions, `maxLLM=1` stuck-child cancel
channel, durable-resume honesty.

## 3. Architecture evolution

New capabilities, each a thin layer over the ONE runtime (no second
scheduler — AGENTS constraint):

### 3.1 Observation spine (powers statuses, tab badges, future web)

- `raider.runtime.observability`: `AgentEvent` ADT (runtime-level; core
  `TraceEvent` stays frozen), JSON-codec envelopes
  `{sessionId, rootId, jobId, seq, at, event}`.
- Vocabulary (initial): `SessionOpened/Closed`, `WaitingAdmission(slot,
  queuePos)`, `ModelRound(n)`, `ModelStreaming(chunk, tokens, rate)`,
  `ToolStarted(name, digest)`, `ToolProgress(key, value)`, `ToolFinished`,
  `RetryScheduled(kind, attempt, delayMs)`, `ChildSpawned/Settled`,
  `CompactionStarted/Done`, `StateRead/StateWritten/StateDeleted` (§3.4),
  `BudgetReserved/Charged/Refunded(usd)`, `RunFinished/RunFailed`,
  `Backoff`, `IdleDeadline`.
- `EventBus`: ZHub with sliding ring per severity (diagnostics may drop-oldest
  under backpressure; accounting events never drop).
- `StatusLedger`: TMap sessionId → derived `AgentStatus` (state machine +
  counters + lastEventAt + unreadSinceFocus), queryable snapshot + subscribe.
- Emission points: AgentLoop rounds, Admission waits, `RateLimitRetries`,
  tool dispatch, compaction, BudgetLedger, state registry (§3.4).
- Tool progress: tools get a `ProgressSink` service (ADR-019 decides: env
  service vs fiber-ref; `ProcessTool` streams a bounded stdout tail using
  chunked `read(buf)` — never `readNBytes`, see board lessons).

### 3.2 Session registry + TUI shell (powers tabs)

- `raider.ui` module: `SessionRegistry` (SessionId, name, kind
  TabAgent|ChildDelegation, backend, toolset, conversation, status ref).
- Input router: focused session consumes the keyboard; others keep running
  (jobs are daemon fibers already — switching never pauses work).
- Per-tab ring-buffer scrollback, unread badges, activity glyphs
  (`● streaming`, `◐ tool/waiting`, `○ idle`, `✗ failed`).
- Renderer: **custom diff-based ANSI renderer on JLine 4** (already a
  dependency, BSD-3) behind a thin `ScreenRenderer` seam — decided in
  [ADR-016](adr/ADR-016-tui-renderer.md) so the project stays **MIT-only**:
  no LGPL (Lanterna) or GPL-family dependencies are permitted in the runtime
  graph. The seam keeps a future web renderer swappable.
  Implementation design: [docs/tui-design.md](tui-design.md) (three modes
  behind the seam: inline v1 on printAbove, fullscreen v2 on a cell buffer,
  web Phase 9).
- Headless `raider run` and `raider chat` remain; `chat` becomes a tab.

### 3.3 Plugin system (powers extension without rebuilds)

- `raider.plugin` module. Manifest JSON (bundle-manifest precedent):
  `{id, version, apiVersion, entryClass, provides:{tools, backends, hooks}}`.
- Discovery: `~/.raider/plugins/*.jar`, `<workspace>/.raider/plugins/*.jar`,
  explicit config list. One child classloader per plugin over a narrow
  exported API package (parent-first for `raider.api.*`; the REPL self-first
  lesson applied deliberately).
- SPI: `RaiderPlugin { tools: List[Tool]; backendFactories; hooks }`.
  Hooks: `onModelCall`/`onToolCall` (pre/post) returning
  `Allow | Deny(reason→feedback) | Replace(x)` — replacements are always
  logged, never silent; guard hooks are policy, NOT a sandbox.
- Failure semantics: any plugin defect → typed `PluginFailed`, loop survives;
  duplicate tool names / apiVersion mismatch → preflight refusal (bundle
  preflight pattern).
- CLI lifecycle: `raider plugin install|list|enable|disable|remove|check`.
- Plugin dev kit: template project, scripted-backend harness, gate profile
  `plugin` (fmt/fix/forbidden-apis + fixture run on plugin sources).
- Plugin-provided **state backends** (§3.4) are a sanctioned extension point.

### 3.4 Agent state registry (OPTIONAL — default off)

Agents need durable state beyond transcripts: scratch notes, cross-tab
blackboards, task ledgers, learned preferences. Today only `Ref` memory and
chat `SessionStore` exist. The registry is an optional capability: **when not
configured, nothing is constructed, no tools register, behavior is identical
to today** (asserted by fixtures).

- Module `raider.state`. SPI (illustrative):
  ```scala
  trait AgentStateStore:
    def get(scope: Scope, key: String): IO[RaiderError, Option[Json]]
    def put(scope: Scope, key: String, value: Json): IO[RaiderError, Unit] // atomic
    def delete(scope: Scope, key: String): IO[RaiderError, Boolean]
    def keys(scope: Scope): IO[RaiderError, List[String]]

  enum Scope:
    case Agent(sessionId: String)   // private to one agent/tab
    case Root(rootId: String)       // blackboard shared by a run's tabs/children
    case Global                     // cross-run preferences (explicit opt-in)
  ```
- Backends: `Memory` (tests), `File` (JSON documents, atomic temp+rename —
  SessionStore patterns, sanitized keys, per-scope directories) and —
  Phase 7 — a **Yabanin remote backend** (§3.5, contract from discovery).
- Bounds & trust: value ≤ 64 KiB by default; per-scope key-count caps;
  values are model-controlled DATA — never executed, size-capped, surfaced
  through events like any tool result; secrets rules apply (never store
  credentials in state).
- Opt-in tool exposure: `state_get {scope?, key}` /
  `state_put {scope?, key, value}` (`state_put` is `RecoveryClass.Mutating`);
  default scope = the calling agent's own; `Root`/`Global` are deliberate
  choices visible in args and events.
- Facade: `session.state` handle in the REPL; `:state [prefix]` listing in
  chat (both behind the same option).
- Events: `StateRead/StateWritten/StateDeleted` on the observation spine, so
  tabs/statuses can show state activity (§1 mock includes `state_put`).
- Durability honesty: atomic per-key writes, NO transactions, at-least-once
  sync semantics for any remote backend — documented, never overclaimed.

### 3.5 Yabanin direct integration (OPTIONAL — discovery-first)

Grounded in [`docs/yabanin-integration.md`](yabanin-integration.md) (the
contract source of truth; neighbor Go repo `tokenrouter`). Yabanin is an
optional gateway plane: OpenAI-compatible routing/cache/failover, session
keys (mint/revoke/finish/usage), attribution labels, CI wrapper (`yb ci
exec`), a harness-adapter slot (YB-RAI-01 on the Yabanin side), agent-eval
export. Raider works fully without it; direct transports stay first-class.

Integration surfaces, in dependency order:

1. **Transport profile** (generic OpenAI-compatible URL/key — possible
   today; enriched in Phase 7): attribution labels
   (`harness=raider`, `raider.root/job/attempt`, `task`, git refs), capability
   doctor (never a paid call by default), session-key modes
   `disabled/optional/required`.
2. **State-registry remote backend** (the owner's slot): the state registry
   (§3.4) reserves a `yabanin` backend; the exact contract is FIXED BY A
   READ-ONLY DISCOVERY SPIKE before any client code — candidates from the
   integration doc: state-change attribution via labels/decision metadata,
   usage/session-key correlation for state-scoped accounting, or a native
   KV contract if the deployment exposes one. Until the contract is proven,
   `File` is the only persistent backend.
3. **Accounting sync**: MicroUsd reservations/charges ↔ Yabanin usage
   endpoints; bounded reconciliation with honest
   `accounting_pending/partial/complete` states (never timer-promoted).
4. **Agent-eval export**: run directories per Yabanin JSON Schemas
   (episodes/calls/verdicts) — measurement, not self-grading.

Hard rules (absolute): optional and offline-capable; **no hidden fallback**
to direct providers on gateway auth/policy failure — typed failure only; no
direct Postgres/Redis access and no Go imports; Yabanin-repo changes are
separate YB-cards under Yabanin's own rules; `license-audit` stays green.

## 4. Phases (hours sum to 11,700)

| # | Phase | Hours | Cumulative |
| --- | --- | --- | --- |
| 0 | Foundations & decisions | 300 | 300 |
| 1 | Observation spine (statuses v1) | 900 | 1,200 |
| 2 | Sessions & tabs TUI (tabs v1) | 1,400 | 2,600 |
| 3 | **Agent state registry (optional)** | 800 | 3,400 |
| 4 | Plugin system (plugins v1) | 1,300 | 4,700 |
| 5 | Delegation UX depth | 1,300 | 6,000 |
| 6 | Durability & control | 1,200 | 7,200 |
| 7 | **Yabanin direct integration (optional)** | 900 | 8,100 |
| 8 | Scale & multi-root | 1,100 | 9,200 |
| 9 | Web console (gated) | 900 | 10,100 |
| 10 | Ecosystem & hardening | 1,000 | 11,100 |
| 11 | Polish, onboarding, 1.0 | 600 | 11,700 |

### Phase 0 — Foundations & decisions (300h)

- ADR-017 AgentEvent bus & StatusLedger (schema, severities, drop policy).
- ADR-019 Tool progress contract (`ProgressSink` env service vs fiber-ref).
- Renderer spike per [ADR-016](adr/ADR-016-tui-renderer.md) +
  [tui-design.md](tui-design.md): prove the custom ANSI renderer meets the
  flicker/resize bar on the terminal matrix (50h).
- Freeze `docs/events.md` vocabulary; golden schema files.
- **Exit**: ADRs accepted; spike demonstrated; vocabulary frozen.

### Phase 1 — Observation spine (900h)

- Implement bus + ledger + envelopes; wire emission points (AgentLoop,
  Admission waits, RateLimitRetries, tools, compaction, BudgetLedger).
- proc_run live progress: bounded stdout tail + elapsed; compile-fix loop
  rounds surfaced as events.
- CLI v1 rendering: one-line live status under the prompt, `:status`,
  `:timeline <agent>`; headless `events.jsonl` upgraded to versioned
  envelopes.
- Fixtures: event ordering properties, slow-consumer backpressure, ledger
  consistency under cancel/interrupt (cancellation accounting is owned by
  admission — events must reflect it), 10k-event burst without accounting loss.
- **Exit gates**: statuses visible in chat for model/tool/retry/waiting;
  burst fixture green; schema goldens frozen.

### Phase 2 — Sessions & tabs TUI (1,400h)

- SessionRegistry + input router + per-tab scrollback/badges; templates
  (coder/scout/reviewer presets with prompts+toolsets).
- TUI shell per ADR-016/tui-design (Mode A inline first): tab bar, panes,
  keybindings (Ctrl+T new, Ctrl+W close with running-agent confirm,
  Ctrl+PgUp/PgDn and Alt+1..9 switch, Ctrl+K command palette, F1 help,
  F2 rename), persistent hint bar.
- StreamingDisplay per tab; per-tab jline history; `:pipe`/`:delegate`
  become "send to tab".
- Terminal compatibility matrix as a gate (tmux, screen, iTerm2,
  Terminal.app, IDEA console) incl. resize and 10k-line scrollback budget.
- **Exit gates**: 5 concurrent agents, switching preserves all scrollback,
  jobs never pause on switch; day-one script steps 1–4 pass.

### Phase 3 — Agent state registry, optional (800h)

- ADR-022 (registry SPI/scopes/bounds/optionality) + ADR-023 **draft**
  (Yabanin state slot — Proposed until the Phase-7 discovery proves the
  contract; see the state launch prompt).
- `raider.state` module: SPI + `Memory`/`File` backends (atomic temp+rename,
  sanitized keys, corrupt-file typed errors, per-scope directories).
- Opt-in wiring: `CodingToolset.make(..., state: Option[AgentStateStore])`
  adds `state_get`/`state_put` ONLY when configured; fixture asserts the
  default tool list is byte-identical when `None`.
- REPL `session.state` handle; chat `:state [prefix]`.
- `StateRead/StateWritten/StateDeleted` events onto the Phase-1 bus.
- **Exit gates**: default-off fixtures (no store, no tools, no events);
  scope-isolation, bounds and atomicity fixtures; blackboard demo (two tabs
  sharing `Root` scope).

### Phase 4 — Plugin system (1,300h)

- Manifest, discovery, loader, isolation, failure semantics, preflight
  conflicts (§3.3).
- Hook points wired into AgentLoop dispatch + backend construction with
  logged Replace semantics.
- `raider plugin` CLI; `plugin` gate profile; PDK template + harness.
- Three example plugins: `fs_extra` (JSON query tool), `provider-ollama`
  (backend factory), `hook-guard` (deny dangerous argv patterns → feedback).
- Plugin author guide (contributing.md extension). Sanctioned extension
  points include **state backends** (§3.4).
- **Exit gates**: install→use ≤3 commands; crash/conflict fixtures; trust
  documentation loud (trusted code, process powers, NOT a sandbox).

### Phase 5 — Delegation UX depth (1,300h)

- Delegate from UI spawns a tab; templates gallery; child tabs show
  ChildSpawned/Settled lineage.
- Steering `send`: per-agent mailbox injecting the next round (replaces the
  typed refusal; conversation-visible, logged).
- Streaming per-event `watch` (replaces two-snapshot watch); optional 2×2
  tile view for parallel agents.
- **Blackboard patterns on the state registry** (Phase 3): shared `Root`
  scope as the coordination plane between parent/children.
- Cancel robustness: out-of-band cancel channel, fix the `maxLLM=1`
  stuck-child case, child deadlines — encode the Phase-4 nightly lesson as a
  repetition gate (bounded settle, content-routed fixtures).
- Bounded parallel fan-out for `concurrentSafe` tools with per-tool progress.
- **Exit gates**: 4-agent parallel refactor scenario with live tiles; cancel
  settles ≤5 s under load ×20 repetitions; steering fixtures.

### Phase 6 — Durability & control (1,200h)

- SessionStore v2: conversations + statuses survive restart (at-least-once
  replay into the ledger; NO exactly-once claims — AGENTS boundary);
  **state-registry contents included in checkpoints**.
- Checkpointed compaction; resume prompts.
- Budget dashboards per tab/root; quotas and spend policies enforced typed;
  preflight cost estimates surfaced before runs.
- Graceful UI shutdown (SIGTERM → save + bounded stop, stable exit codes).
- **Exit gates**: kill -9 then restart restores tabs/histories/statuses AND
  state scopes; spend-cap denial fixture; shutdown-hook tests in UI mode.

### Phase 7 — Yabanin direct integration, optional (900h)

- Discovery refresh (read-only neighbor-repo probe per
  `docs/yabanin-integration.md`; cite files, no fabricated contracts —
  the integration doc explicitly warns about the stale OpenAPI and the
  FakeExecutor in `yb delegate`).
- ADR-023 finalized → implementation:
  - Transport profile with attribution labels + capability doctor (no paid
    calls by default; `--probe-inference` is explicit, capped).
  - Session-key modes `disabled/optional/required`; **no hidden fallback**
    on auth/policy failure (fixtures on the deny path).
  - Usage reconciliation: bounded wait, honest pending/partial/complete.
  - State-registry remote backend v1 per the discovered contract; if no KV
    contract exists, ship attribution/sync-only and document the gap.
  - Agent-eval export (episodes/calls/verdicts JSONL).
  - Capped live smoke LAST, behind an explicit live-mode flag.
- Yabanin-side work (harness adapter YB-RAI-01) stays in Yabanin cards.
- **Exit gates**: doctor offline-green; label golden tests (PathEscape
  semantics); deny-path fixtures; state backend fixtures against a local
  stand; license-audit green (client deps MIT-only).

### Phase 8 — Scale & multi-root (1,100h)

- Multi-workspace sessions (tabs bound to different roots); git-worktree
  awareness integrated with the parallel-development runbook.
- Bus performance: 10k events/s sustained; snapshot+subscribe consistency;
  memory budgets (scrollback caps, ring sizes, state-store caps) enforced.
- Nightly soak gate (8h run, no leaks, no lost accounting); long-run
  compaction behavior.
- Remote-target spike (agent executes on a CI runner, UI local): view-only
  first, trust boundaries documented before any steering (ADR-021).
- **Exit gates**: soak green ×3; multi-root demo; perf budgets in CI.

### Phase 9 — Web console, gated (900h)

- Decision gate first: only if TUI adoption evidence justifies it.
- zio-http + WebSocket; AgentEvent JSON codecs double as the wire protocol;
  browser tabs mirror terminal sessions (read), then steer.
- Explicitly a view+input router over the same bus — NOT a second runtime.
  Local-only auth token; no remote exposure by default.
- **Exit gates**: browser mirrors 10 tabs <200 ms; no runtime duplication
  (audit fixture: same ledger drives TUI and web).

### Phase 10 — Ecosystem & hardening (1,000h)

- Local plugin index (file registry with hashes; optional signed manifests).
- Opt-in local telemetry (counters only, secret rules per AGENTS).
- `raider demo` scripted tour; template gallery (10+); key remaps and
  high-contrast themes (accessibility).
- Security review pass: plugin classloader audit, path traversal re-audit
  (state store included), secret scanning extended to plugin sources;
  load/chaos gates.
- **Exit gates**: security review closed; gallery shipped; chaos gates green.

### Phase 11 — Polish, onboarding, 1.0 (600h)

- Docs overhaul (api/architecture/contributing refreshed for
  UI+plugins+state); first-run wizard; `raider doctor` environment check;
  screencasts.
- Release engineering: fat JAR with UI + generated `THIRD-PARTY-NOTICES`
  (§8 licensing), distribution notes.
- 1.0 milestone: extended final checklist (nightly's 10-point list plus
  UI/plugin/state gates), all green in one run.
- **Exit gates**: 1.0 released.

## 5. Governance

- **Cards**: one card = one verifiable result (unchanged). Each phase is a
  milestone of ~15–40 cards; epics above pre-slice them.
- **Hour accounting**: work records gain an `effort` field (estimated vs
  actual); every 500h → milestone review (demo, gates, re-prioritization).
- **Optionality rule**: optional capabilities (state registry, Yabanin,
  web console) default OFF; enabling them must not change default behavior —
  asserted by fixtures in BOTH modes.
- **Evidence**: AGENTS honesty rules apply unchanged; statuses/dashboards
  rendered in gates must come from real event streams, never from claimed
  state.
- **Sequencing**: observation spine (P1) precedes tabs (P2) because badges
  consume it; state registry (P3) precedes delegation blackboards (P5);
  plugins (P4) can run parallel to P2/P3 by a second worker (build.sbt is a
  single-owner shared file — module registrations sequence through one
  session; parallel-development runbook).

## 6. Risks

| Risk | Mitigation |
| --- | --- |
| Classloader hell (plugins × REPL self-first) | ADR-018 PoC before commitment; fixture matrix; narrow exported API |
| TUI flicker/perf across terminals | Phase-0 spike + terminal-matrix gate; `ScreenRenderer` seam keeps renderer swappable |
| Custom renderer engineering cost (no widget toolkit available under MIT) | Budgeted in Phase 2 (+~200h vs widget toolkit); cell-buffer + diff-draw keeps scope to tab bar / status line / log pane; seam preserves a web-renderer escape hatch |
| State store grows into a second database | Scope locked to bounded KV + blackboard (§3.4); size/count caps; no query language; anything richer needs an explicit ADR |
| State values as an injection vector | Values are inert data: size-capped, never executed, surfaced as events; secrets never stored; forbidden-apis-style rules documented |
| Yabanin contract drift (stale OpenAPI, FakeExecutor traps) | Discovery-first with file citations; versioned contract fixtures; doctor probes; no client generation from stale specs |
| Hidden-fallback temptation under gateway failures | Absolute rule: typed failure, no silent direct bypass; deny-path fixtures |
| Event loss hiding diagnostics | Severity drop policy; accounting events lossless; soak gates |
| Plugin trust misuse | Loud "trusted code" docs, guard hooks, no sandbox claims |
| Multi-year drift / rotation | Card discipline, milestone demos, this doc re-baselined each 500h |

## 7. Non-goals (explicit)

- No sandbox for plugin/REPL/Scala code — tool policy is policy, not isolation.
- No exactly-once or durable-resume claims for arbitrary effects.
- No second scheduler/runtime in any UI (terminal or web).
- No paid API calls from gates; mock/replay only.
- No transactions/query language in the state registry (bounded KV only).
- Yabanin integration is optional and never a hidden fallback; Yabanin-repo
  changes stay in Yabanin's own cards.

## 8. Licensing policy (MIT-only, enforced)

The project is MIT (`LICENSE`). Constraint from the owner: Raider must stay
usable under MIT — the runtime dependency graph must never acquire
copyleft licenses.

- **Runtime graph policy** (enforced by the `license-audit` gate,
  `scripts/quality/license_audit.py`, in the `fast` profile):
  - ALLOWED: MIT, BSD-2/3, Apache-2.0, ISC, CC0, Unlicense, public domain.
  - FORBIDDEN: GPL, LGPL, AGPL (any version).
  - REVIEW (fail until explicitly allowlisted in the script with a dated
    comment): MPL, EPL, CDDL.
  - UNKNOWN (no pom license, exhausted parent chain) = FAIL — an unaudited
    dependency is not a safe dependency.
- **Build-time sbt plugins** (scalafmt/scalafix/assembly) never ship in
  artifacts; permissive-family preferred anyway.
- **Future TUI/plugin/state ecosystems**: any candidate library goes through
  the audit gate first; Lanterna (LGPL) is permanently excluded. The Yabanin
  client must stay MIT-dep-clean (Go side unaffected).
- **Attribution**: the fat JAR (Phase 6/11) must ship a generated
  `THIRD-PARTY-NOTICES` file listing bundled libraries and their licenses —
  release-engineering checklist item.
- Baseline audit 2026-10-05: 25 runtime artifacts — all Apache-2.0 / BSD /
  MIT; 0 problems (report in `artifacts/license-audit/`).

## 9. ADR backlog

1. ~~ADR-TUI~~ → [ADR-016](adr/ADR-016-tui-renderer.md) — accepted 2026-10-05;
   implementation design in [docs/tui-design.md](tui-design.md).
2. ADR-017 (Phase 0): AgentEvent schema, severities, backpressure/drop policy.
3. ADR-018 (Phase 4): plugin SPI, classloader policy, failure & trust semantics.
4. ADR-019 (Phase 0): tool progress contract (ProgressSink env vs fiber-ref).
5. ADR-020 (Phase 6): session persistence/replay semantics (at-least-once).
6. ADR-021 (Phase 8): remote execution trust boundary.
7. ADR-022 (Phase 3): agent state registry — SPI, scopes, bounds, optionality.
8. ADR-023 (Phase 3 draft → Phase 7 final): Yabanin integration contract
   (transport labels, session-key modes, state backend, accounting sync).

## 10. Launch

Execution prompts (feed to an executor agent, or follow manually):

- **Phase 0**: [docs/prompts/MASTER-10K-PHASE0.md](prompts/MASTER-10K-PHASE0.md)
  — renderer spike, ADR-017, ADR-019.
- **State workstream** (can start immediately; ADRs + `raider.state` v1 +
  Yabanin discovery): [docs/prompts/MASTER-10K-STATE.md](prompts/MASTER-10K-STATE.md)

Verification contract for both: compile + test + 10/10 gates after every
task, MIT-only enforced by `license-audit`. If run in parallel, build.sbt
module registrations are a single-owner edit (parallel-development runbook).
