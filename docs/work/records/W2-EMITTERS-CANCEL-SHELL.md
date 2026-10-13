# W2-EMITTERS-CANCEL-SHELL: work record

Status: done. Date: 2026-10-09 UTC. Baseline: integrated tree
[W1](W1-EVENTS-TABS.md). Execution: coordinator + 3 writers (cloud agents;
local-qwen was not used per owner decision) in isolated snapshot lanes
(a4/b4/c4, base 256 files). Incidents: machine reboot erased temp lanes (loss
of only one partial file in lane-b4; recovered); repeated infrastructure
500/abort errors were handled by restarts without losses.

## Resulting behavior

1. **RAI-059 (P1.B-2, accepted)** — job level became the source of events:
   `JobManager.make(onTransition)` — hook after STM commit (None = zero
   overhead); `StatusLedger` → JobStatusChanged + exactly one Terminal(outcome)
   per job (late observations do not emit); sessionId=SessionId("") placeholder;
   `BudgetLedger` → BudgetChanged(reserved, observed, unknownAttempts) on
   forked daemon (accounting is not blocked); `DelegationToolset` →
   Delegated(childJobId, agent) on fork + ChildAwaited-equivalent Diagnostics
   in the per-root pool (root pool never receives Terminal — invariant for
   RAI-062). 127/127 in lane, 137/137 integrated (after merge with 063).
2. **RAI-063 (P3.B, accepted)** — `cancelOutOfBand`/`cancelAllOutOfBand`:
   cancel OUTSIDE model admission (REPL/UI/owner channel); a parked child with
   maxLLM=1 is canceled within ≤5s ×20 without a second slot (CANCEL-01); child
   deadlines: owner-side watchdog + bounded grace (RaiderScope pattern), with
   honest Uncertain; latest-wins replace documented; observer-interrupt ≠
   owner-cancel — locked down by tests; cancelInvoke/tool schemas unchanged.
   119/119 in lane.
3. **RAI-061 (P2.B, accepted)** — `raider.ui.TabShell`: JLine isolated behind
   ShellTerminal (one writer, printAbove only); pure CommandRouter +
   TabBarRenderer (testable without a terminal); commands `:tabs :new :switch
   :next :prev :rename :close :help :quit`; close running → Hide|CancelRequested
   (does not cancel by itself); root close lists children; draft is byte-exact
   (raw line preserved — trim bug found and fixed); SessionRegistry.rename
   (additive, RAI-057 tests untouched). 16/16 ui.
4. **DelegationToolset merge** (both lanes touched the file): diff3 + manual
   union — review confirmed “pure additions” on both sides, BUD-03 gate first,
   cancelInvoke byte-unchanged, parameters root/defaultChildDeadline/deadlineGrace/
   delegationEvents have defaults; all 25 call sites remain intact.

## Changed paths

- `modules/runtime/src/main/scala/raider/runtime/jobs/{JobManager.scala,StatusLedger.scala}`, `budget/BudgetLedger.scala`, `delegate/{DelegationToolset.scala,DelegationEvents.scala}`
- `modules/runtime/src/test/scala/raider/runtime/{jobs/JobEmissionSpec,budget/BudgetEmissionSpec,delegate/DelegationEmissionSpec,delegate/DelegationCancelSpec}.scala`
- `modules/ui/src/main/scala/raider/ui/{SessionRegistry.scala (mod),CommandRouter,ShellCommand,ShellExecutor,ShellFeedback,ShellTerminal,TabBarRenderer,TabShell}.scala`, `modules/ui/src/test/scala/raider/ui/TabShellSpec.scala`
- `build.sbt` (JLine 4.0.14 → raiderUi, coordinator)
- `docs/tasks/*`, `docs/work/board.md`, this record

## Acceptance evidence

| ID | Result | Evidence |
| --- | --- | --- |
| EMJ-01..03 | 18 emission tests; cancel/success race 100 iterations — no lost/duplicate Terminal; hook-absent parity | lane-a4-evidence/work-record-RAI-059.md |
| CANCEL-01..03 | ×20 ≤5s; Uncertain honesty; observer≠owner; no-deadlock ×3 | lane-b4-evidence/work-record.md |
| SHELL-01..03 | 16/16 ui; draft byte-exact; one-writer; close decisions | lane-c4-evidence/WORK-RECORD-RAI-061.md |
| Integrated gates | EXECUTE 7/7 (final after cleanup); static/unit/contracts/runtime/ci passed | artifacts/quality/20261009-100041-70734-0001 and this session's execute manifest |
| Review | All three ACCEPTED; merge checked as pure additions from both sides | session review report |

## Commands

| argv | Exit |
| --- | --- |
| `sbt --batch raiderRuntime/test` | 0 — 137 passed |
| `sbt --batch raiderUi/test` | 0 — 16 passed |
| `sbt --batch scalafmtCheckAll` | 0 |
| `make quality-changed QUALITY_EXECUTE=1` | 0 |
| `make quality-profile PROFILES="static unit contracts runtime ci"` | 0 |

## Limitations / blockers

- **Delegated race**: an immediately finishing child can beat publication of
  Delegated (post-Terminal Malformed, silently consumed) — RAI-062 obligation
  (subscribe to child stream before delegate returns, or emit from spawn).
- **Root pool invariant** (JobId(rootId.value) without Terminal) — preserve in 062.
- setChildDeadline latest-wins is not covered by a test; watchdog maps are not
  cleaned up (growth in a very long-lived toolset) — small future cards.
- TabBarRenderer: unread display lags by one bump (view race, documented;
  registry counter is accurate).
- Yabanin.ai demo is blocked on the owner's side: no daily budget is set for
  the agent key (`agent_budget_not_configured`); local quickstart stand is up
  (mock, 127.0.0.1:8082).

## Review and next task

Review: ACCEPTED ×3 (dup-import cleanup applied before final EXECUTE).
Next in the DAG: **RAI-060** (status line/headless export; deps 058+059 — both
accepted → READY) and **RAI-062** (PTY integration of tabs + M1 demo; deps
060+061). Also: RAI-062 obligations from review (Delegated race, root pool).
After RAI-060+062 — **M1 demo** (5 tabs, live statuses).
