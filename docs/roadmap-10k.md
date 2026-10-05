# Raider 10,000-Hour Roadmap: Tabs, Live Statuses, Plugins

Owner-directed long-range plan (2026-10-05). Scope: turn the current harness
into a tool the owner uses *simply*, around three UX pillars — **subagent
tabs**, **intermediate statuses**, **a convenient plugin system** — sized for
~10,000 hours of refinement. This document does not replace `docs/PLAN.md`
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

## 2. Gap analysis — pillars vs today

| Pillar | Exists today | Missing |
| --- | --- | --- |
| **Tabs** | JobManager jobs, DelegationToolset children, REPL `:jobs`, facade `ask/start/await/cancel`, per-agent `openSession` chats | Session registry with identity/persistence; any TUI (output is linear stdout); per-session scrollback; input routing; unread/activity indicators |
| **Statuses** | TraceEvents (rounds, tool calls, children), JobSnapshot statuses, StreamingDisplay deltas, tracer echo in chat/REPL | Central subscribable event bus; current-state ledger ("what is it doing NOW"); waiting-for-slot exposure; live tool progress (proc_run is final-only); budget/spend surfacing in UI; retry/backoff visibility |
| **Plugins** | Validated ToolRegistry; compiled-in providers; jar-bundle manifest precedent (`META-INF/raider-bundle.json`); ThreadLocal bridge lessons | Runtime loading of tools/providers/hooks; manifests + discovery; isolation & failure semantics; hooks/interceptors; plugin dev kit & gates; `raider plugin` CLI |

Folded-in tracked obligations (from board/code comments): job steering
(`send` — currently a typed refusal), streaming per-event `watch`, parallel
`concurrentSafe` tool fan-out, REPL completions, `maxLLM=1` stuck-child cancel
channel, durable-resume honesty.

## 3. Architecture evolution

Three new capabilities, each a thin layer over the ONE runtime (no second
scheduler — AGENTS constraint):

### 3.1 Observation spine (powers statuses, tabs badges, future web)

- `raider.runtime.observability`: `AgentEvent` ADT (runtime-level; core
  `TraceEvent` stays frozen), JSON-codec envelopes
  `{sessionId, rootId, jobId, seq, at, event}`.
- Vocabulary (initial): `SessionOpened/Closed`, `WaitingAdmission(slot,
  queuePos)`, `ModelRound(n)`, `ModelStreaming(chunk, tokens, rate)`,
  `ToolStarted(name, digest)`, `ToolProgress(key, value)`, `ToolFinished`,
  `RetryScheduled(kind, attempt, delayMs)`, `ChildSpawned/Settled`,
  `CompactionStarted/Done`, `BudgetReserved/Charged/Refunded(usd)`,
  `RunFinished/RunFailed`, `Backoff`, `IdleDeadline`.
- `EventBus`: ZHub with sliding ring per severity (diagnostics may drop-oldest
  under backpressure; accounting events never drop).
- `StatusLedger`: TMap sessionId → derived `AgentStatus` (state machine +
  counters + lastEventAt + unreadSinceFocus), queryable snapshot + subscribe.
- Emission points: AgentLoop rounds, Admission waits, `RateLimitRetries`,
  tool dispatch, compaction, BudgetLedger.
- Tool progress: tools get a `ProgressSink` service (ADR-4 decides: env
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

## 4. Phases (hours sum to 10,000)

| # | Phase | Hours | Cumulative |
| --- | --- | --- | --- |
| 0 | Foundations & decisions | 300 | 300 |
| 1 | Observation spine (statuses v1) | 900 | 1,200 |
| 2 | Sessions & tabs TUI (tabs v1) | 1,400 | 2,600 |
| 3 | Plugin system (plugins v1) | 1,300 | 3,900 |
| 4 | Delegation UX depth | 1,300 | 5,200 |
| 5 | Durability & control | 1,200 | 6,400 |
| 6 | Scale & multi-root | 1,100 | 7,500 |
| 7 | Web console (gated) | 900 | 8,400 |
| 8 | Ecosystem & hardening | 1,000 | 9,400 |
| 9 | Polish, onboarding, 1.0 | 600 | 10,000 |

### Phase 0 — Foundations & decisions (300h)

- ADR-1 TUI strategy — **RESOLVED** as
  [ADR-016](adr/ADR-016-tui-renderer.md): custom diff-based ANSI renderer on
  JLine 4 (BSD-3, existing dependency). Lanterna was rejected: LGPL-3.0 is
  incompatible with the owner's MIT-only requirement. Remaining Phase-0 work:
  the 50h renderer spike (flicker/resize on the tmux/iTerm2/IDEA-console
  matrix) proves the flicker/resize bar on the chosen path.
- ADR-2 AgentEvent bus & StatusLedger (schema, severities, drop policy).
- ADR-3 Plugin SPI + classloader policy (PoC: load a tool plugin into a live
  REPL session through the ThreadLocal bridge).
- ADR-4 Tool progress contract (`ProgressSink` env service vs fiber-ref).
- Freeze `docs/events.md` vocabulary; write golden schema files.
- **Exit**: ADRs accepted; three spikes demonstrated on real terminals;
  vocabulary frozen.

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
- TUI shell per ADR-1: tab bar, panes, keybindings (Ctrl+T new, Ctrl+W close
  with running-agent confirm, Ctrl+PgUp/PgDn and Alt+1..9 switch, Ctrl+K
  command palette, F1 help, F2 rename), persistent hint bar.
- StreamingDisplay per tab; per-tab jline history; `:pipe`/`:delegate`
  become "send to tab".
- Terminal compatibility matrix as a gate (tmux, screen, iTerm2, Terminal.app,
  IDEA console) incl. resize and 10k-line scrollback performance budget.
- **Exit gates**: 5 concurrent agents, switching preserves all scrollback,
  jobs never pause on switch; day-one script steps 1–4 pass.

### Phase 3 — Plugin system (1,300h)

- Manifest, discovery, loader, isolation, failure semantics, preflight
  conflicts (§3.3).
- Hook points wired into AgentLoop dispatch + backend construction with
  logged Replace semantics.
- `raider plugin` CLI; `plugin` gate profile; PDK template + harness.
- Three example plugins: `fs_extra` (JSON query tool), `provider-ollama`
  (backend factory), `hook-guard` (deny dangerous argv patterns → feedback).
- Plugin author guide (contributing.md extension).
- **Exit gates**: install→use ≤3 commands; crash/conflict fixtures; trust
  documentation loud (trusted code, process powers, NOT a sandbox).

### Phase 4 — Delegation UX depth (1,300h)

- Delegate from UI spawns a tab; templates gallery; child tabs show
  ChildSpawned/Settled lineage.
- Steering `send`: per-agent mailbox injecting the next round (replaces the
  typed refusal; conversation-visible, logged).
- Streaming per-event `watch` (replaces two-snapshot watch); optional 2×2
  tile view for parallel agents.
- Cancel robustness: out-of-band cancel channel, fix the `maxLLM=1`
  stuck-child case, child deadlines — encode the Phase-4 nightly lesson as a
  repetition gate (bounded settle, content-routed fixtures).
- Bounded parallel fan-out for `concurrentSafe` tools with per-tool progress.
- **Exit gates**: 4-agent parallel refactor scenario with live tiles; cancel
  settles ≤5 s under load ×20 repetitions; steering fixtures.

### Phase 5 — Durability & control (1,200h)

- SessionStore v2: conversations + statuses survive restart (at-least-once
  replay into the ledger; NO exactly-once claims — AGENTS boundary).
- Checkpointed compaction; resume prompts.
- Budget dashboards per tab/root; quotas and spend policies enforced typed;
  preflight cost estimates surfaced before runs.
- Graceful UI shutdown (SIGTERM → save + bounded stop, stable exit codes).
- **Exit gates**: kill -9 then restart restores tabs/histories/statuses;
  spend-cap denial fixture; shutdown-hook tests in UI mode.

### Phase 6 — Scale & multi-root (1,100h)

- Multi-workspace sessions (tabs bound to different roots); git-worktree
  awareness integrated with the parallel-development runbook.
- Bus performance: 10k events/s sustained; snapshot+subscribe consistency;
  memory budgets (scrollback caps, ring sizes) enforced.
- Nightly soak gate (8h run, no leaks, no lost accounting); long-run
  compaction behavior.
- Remote-target spike (agent executes on a CI runner, UI local): view-only
  first, trust boundaries documented before any steering.
- **Exit gates**: soak green ×3; multi-root demo; perf budgets in CI.

### Phase 7 — Web console, gated (900h)

- Decision gate first: only if TUI adoption evidence justifies it.
- zio-http + WebSocket; AgentEvent JSON codecs double as the wire protocol;
  browser tabs mirror terminal sessions (read), then steer.
- Explicitly a view+input router over the same bus — NOT a second runtime.
  Local-only auth token; no remote exposure by default.
- **Exit gates**: browser mirrors 10 tabs <200 ms; no runtime duplication
  (audit fixture: same ledger drives TUI and web).

### Phase 8 — Ecosystem & hardening (1,000h)

- Local plugin index (file registry with hashes; optional signed manifests).
- Opt-in local telemetry (counters only, secret rules per AGENTS).
- `raider demo` scripted tour; template gallery (10+); key remaps and
  high-contrast themes (accessibility).
- Security review pass: plugin classloader audit, path traversal re-audit,
  secret scanning extended to plugin sources; load/chaos gates.
- **Exit gates**: security review closed; gallery shipped; chaos gates green.

### Phase 9 — Polish, onboarding, 1.0 (600h)

- Docs overhaul (api/architecture/contributing refreshed for UI+plugins);
  first-run wizard; `raider doctor` environment check; screencasts.
- Release engineering: fat JAR with UI + generated `THIRD-PARTY-NOTICES` (§8 licensing), distribution notes.
- 1.0 milestone: extended final checklist (nightly's 10-point list plus
  UI/plugin gates), all green in one run.
- **Exit gates**: 1.0 released.

## 5. Governance

- **Cards**: one card = one verifiable result (unchanged). Each phase is a
  milestone of ~15–40 cards; epics above pre-slice them.
- **Hour accounting**: work records gain an `effort` field (estimated vs
  actual); every 500h → milestone review (demo, gates, re-prioritization).
- **Evidence**: AGENTS honesty rules apply unchanged; statuses/dashboards
  rendered in gates must come from real event streams, never from claimed
  state.
- **Sequencing rule**: observation spine (P1) precedes tabs (P2) because
  badges/status lines consume it; plugins (P3) can run parallel to P2 by a
  second worker (registry allows it — parallel-development runbook).

## 6. Risks

| Risk | Mitigation |
| --- | --- |
| Classloader hell (plugins × REPL self-first) | ADR-3 PoC before commitment; fixture matrix; narrow exported API |
| TUI flicker/perf across terminals | Phase-0 spike + terminal-matrix gate; `ScreenRenderer` seam keeps Lanterna swappable for custom ANSI |
| Custom renderer engineering cost (no widget toolkit available under MIT) | Budgeted in Phase 2 (+~200h vs widget toolkit); cell-buffer + diff-draw keeps scope to tab bar / status line / log pane; `ScreenRenderer` seam preserves a web-renderer escape hatch |
| Web console scope creep | Hard gate at Phase 7 entry; bus-first design keeps it additive |
| Event loss hiding diagnostics | Severity drop policy; accounting events lossless; soak gates |
| Plugin trust misuse | Loud "trusted code" docs, guard hooks, no sandbox claims |
| Multi-year drift / rotation | Card discipline, milestone demos, this doc re-baselined each 500h |

## 7. Non-goals (explicit)

- No sandbox for plugin/REPL/Scala code — tool policy is policy, not isolation.
- No exactly-once or durable-resume claims for arbitrary effects.
- No second scheduler/runtime in any UI (terminal or web).
- No paid API calls from gates; mock/replay only.
- Yabanin integration stays in its own repo and cards.

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
- **Future TUI/plugin ecosystems**: any candidate library goes through the
  audit gate first; Lanterna (LGPL) is permanently excluded.
- **Attribution**: the fat JAR (Phase 5/9) must ship a generated
  `THIRD-PARTY-NOTICES` file listing bundled libraries and their licenses —
  release-engineering checklist item.
- Baseline audit 2026-10-05: 25 runtime artifacts — all Apache-2.0 / BSD /
  MIT; 0 problems (report in `artifacts/license-audit/`).

## 9. ADR backlog

1. ~~ADR-TUI~~ → [ADR-016](adr/ADR-016-tui-renderer.md) — accepted 2026-10-05;
   implementation design in [docs/tui-design.md](tui-design.md).
2. ADR-002 (Phase 0): AgentEvent schema, severities, backpressure/drop policy.
3. ADR-003 (Phase 3): plugin SPI, classloader policy, failure & trust semantics.
4. ADR-004 (Phase 0): tool progress contract (ProgressSink env vs fiber-ref).
5. ADR-005 (Phase 5): session persistence/replay semantics (at-least-once).
6. ADR-006 (Phase 6): remote execution trust boundary.

## 10. Launch

Execution prompt for this roadmap's Phase 0:
[docs/prompts/MASTER-10K-PHASE0.md](prompts/MASTER-10K-PHASE0.md) — feed it to
an executor agent (or follow it manually). Verification contract: compile +
test + 10/10 gates after every task, MIT-only enforced by `license-audit`.
