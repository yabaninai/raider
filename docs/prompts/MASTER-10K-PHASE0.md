# Raider 10K — Phase 0 Execution Master Prompt

You are working on Raider, a self-hosting agent harness (Scala 3 + ZIO 2).
This prompt launches **Phase 0** of the 10,000-hour roadmap
(`docs/roadmap-10k.md`): foundations, decisions, and the terminal-renderer
spike. Work the tasks in order; skip a task only if genuinely blocked and
record why.

## Hard constraints (read first)

- The project stays **MIT-only**: the runtime dependency graph must never
  contain copyleft licenses (GPL/LGPL/AGPL — forbidden; MPL/EPL/CDDL — fail
  until allowlisted). The `license-audit` gate enforces this. **Lanterna is
  permanently excluded.**
- Product code: Scala 3 + ZIO only (`modules/**`). Python only under
  `scripts/quality` (smokes/audits).
- ALL code compiles with `-Werror` (zero warnings).
- TUI renderer: follow `docs/tui-design.md` and
  `docs/adr/ADR-016-tui-renderer.md` exactly — custom diff-based ANSI over
  JLine 4 (BSD-3, already a dependency). No new runtime dependencies.
- Never call a live/paid model from product tests; mock/replay only. The
  llama.cpp stand on 127.0.0.1:8081 is for selfdev scripts, not tests.
- Do not modify `modules/core/**` frozen contracts; runtime-level additions
  are fine. Gate policy changes must ADD strictness, never relax.

## Verification trio — after EVERY task

1. `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` — exit 0
2. `COURSIER_CACHE=/tmp/cc-master sbt --batch test` — 0 failures
3. `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1`
   — **10/10 gates passed** (fast profile now includes `license-audit`)

Write a work record in `docs/work/records/` and update `docs/work/board.md`
after every task. Commit after every completed task:
`git add -A && git commit -m "feat(phase-0.N): <description>"`.

## Tasks

### Task 0.1 — ADR-017: AgentEvent schema + bus + ledger (~1 day)

Write `docs/adr/ADR-017-agent-events.md` (status: Accepted after self-review)
covering:
- The `AgentEvent` ADT per `docs/roadmap-10k.md` §3.1 vocabulary (envelope:
  `{sessionId, rootId, jobId, seq, at, event}`; JSON codecs via zio-json).
- EventBus on ZHub: sliding ring per severity; accounting events never drop;
  slow-consumer policy (drop-oldest diagnostics, never accounting).
- StatusLedger: TMap sessionId → derived AgentStatus (state machine:
  Idle/WaitingAdmission/ModelRound/Streaming/ToolRunning/RetryScheduled/
  WaitingChild/Compacting/Terminal + counters + unreadSinceFocus).
- Emission points: AgentLoop rounds, Admission waits, RateLimitRetries
  retries, tool dispatch, compaction, BudgetLedger.
Acceptance: ADR committed; vocabulary matches roadmap §3.1; drop policy
explicitly states accounting events are lossless.

### Task 0.2 — raider-ui module skeleton + CellBuffer (~2 days)

New module `raider-ui` (package `raider.ui`) in `build.sbt`, depending on
`raiderCore` + zio + jline-terminal/jline-reader ONLY (mirror the repl-engine
settings style; coordinator-owned file — keep the edit minimal). Implement:
- `ScreenRenderer` seam, `Size/Frame/Line/Span/Style` per
  `docs/tui-design.md` §2–3 (pure data, no terminal I/O in these types).
- `CellBuffer` (cols×rows cell array, blit from Frame, per-row dirty bitmap).
- `AnsiRenderer`: diff → ANSI string (cursor moves + runs, SGR palette with
  16/256/truecolor degrade by TERM).
Tests FIRST (ZIO Test): golden Frame→ANSI strings, dirty-row minimality
(unchanged rows emit nothing), resize full-repaint, style degradation table.
Acceptance: golden tests green; no terminal I/O inside CellBuffer/AnsiRenderer
(they must be constructible in plain unit tests).

### Task 0.3 — InlineRenderer on printAbove (~2 days)

`InlineRenderer` (Mode A): JLine `LineReader` + `printAbove` for log lines;
one in-place rewriteable status line (`\r` + `ESC[K`) that degrades to
append-only when other output landed since the last rewrite. Tab switch =
separator banner + bounded tail replay (200 lines from a ring buffer).
Acceptance: unit tests with a fake writer for the rewrite/degrade logic;
docstring documents the printAbove concurrency contract
(`docs/tui-design.md` §1).

### Task 0.4 — PTY render smoke (~1 day)

`scripts/quality/tui_render_smoke.py` (pattern: `repl_pty_smoke.py`): spawn a
tiny demo main (`raider.ui.DemoMain` — renders a scripted event feed: tab bar,
two tabs, status updates) under a PTY; assert: tab bar visible, status line
updates, SIGWINCH resize leaves no ghost characters, exit 0. Add a registry
gate `tui-render-smoke` (timeout 120) and add it to a NEW `ui` profile (stage
`component`) — do NOT add it to `fast` yet.
Acceptance: gate passes standalone and via
`make quality-profile PROFILES=ui`; smoke is deterministic (fixed feed).

### Task 0.5 — ADR-019: Tool progress contract (~0.5 day)

Write `docs/adr/ADR-019-tool-progress.md`: decide how tools report live
progress to the observation layer (option A: `ToolEnv` service in the ZIO
environment of `invoke`; option B: fiber-ref implicit sink). Compare against
the frozen `Tool` contract (core), list migration steps, pick ONE with
rationale. Do NOT implement.
Acceptance: ADR committed with a clear decision and migration notes.

### Task 0.6 — Roadmap close-out (~0.5 day)

Mark Phase 0 complete in `docs/roadmap-10k.md` (remaining items: none if all
tasks above landed), update the board, and write the phase work record
summarizing evidence (gate manifests, PTY smoke output, golden test counts).

## Final verification checklist (run once at the end)

```sh
COURSIER_CACHE=/tmp/cc-master sbt --batch compile
COURSIER_CACHE=/tmp/cc-master sbt --batch test
COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1   # 10/10
make quality-profile PROFILES=ui                                       # new profile
python3 scripts/quality/license_audit.py --self-test                   # MIT-only
sh scripts/quality/repl_smoke.sh
sh scripts/quality/cli_smoke.sh
```

All must pass. Fix before committing; a timeout or skip is NOT a pass.

## Parallel-execution note

If the state workstream prompt (`MASTER-10K-STATE.md`) runs in another
session, coordinate `build.sbt`: module registrations are a single-owner edit
(both prompts add a module). Sequence the two additions or route them through
one session.

## Honesty rules (unchanged)

No invented checks, commits, URLs, or quality percentages. If blocked,
record the exact blocker in the work record and move to the next task.
