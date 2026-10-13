# LIN-15: work record

Status: done (lane; integration review pending). Date: 2026-10-12 (lane
worktree `wt/lin-15`, branch `lane/lin-15`). Baseline commit/source: `4b46dc7`
(“plan: wave 4 — register LIN-13/14/15 + RAI-113/114”; checked with
`git status --short` — clean, `git log --oneline -1` — matches packet).

## Resulting behavior

Deterministic offline (scripted) LIN-06 acceptance — LIN6-01/02/03 — was
implemented as behavioral fixtures over existing IsolatedRerun /
WorkspaceIsolation / RunDiffReport / PhaseReplay / ReplayHarness / Admission
mechanics. Scripted backend: the only “model” is a recording
(ReplayModelBackend, ordinal playback); no live model, network, or paid calls.
The spec is marked `Scripted` in its name and scaladoc, and cases use suffix
`-SC`.

Acceptance cases (new `LiveRerunScriptedAcceptanceSpec`):

- **LIN6-01-SC**: Isolated rerun of a phased recording (Phase node “read-notes”,
  wrapping the round-1 subtree) completes; diff report = whole-graph
  RunDiffReport verdict + PhaseReplay section (identity, slice-hash, subtree
  label, per-child resolved/obligation/unresolved rows, deferred tail). Honest
  semantics are asserted: the live graph has no phase grouping (no wire case,
  docs/lineage.md §8), so first divergence is `missing-node` at the phase node
  (`job-1:n2`), while the phase structure is reported from the recording.
  Determinism: rerun on a fresh isolated copy yields a byte-identical report;
  original untouched (verifyUntouched).
- **LIN6-02-SC budget**: caps `maxAttempts=1, maxRounds=1` through new
  `IsolatedRerun.rerun(limits, maxRounds)` parameters → round-1 model call
  consumes attempt 1, fs_edit dispatch is rejected by Admission
  (`LocalBudgetExceeded("maxAttempts=1 exceeded …")`) BEFORE tool execution →
  `IsolatedRerun.rerun` returns `Left(RerunError.Replay(RaiderError.
  LocalBudgetExceeded))`; isolated copy has no dirt file (tool not run), original
  untouched.
- **LIN6-02-SC guard**: product path (not a test helper):
  `IsolatedRerun.mutatingTools(recorded)` extracts mutating names from recorded
  RESPONSE envelopes `tool_calls` (ReplayModelBackend convention: what the loop
  actually executes) using static taxonomy ADR-027 §6.2 and from
  `ToolCallData.recovery == "Mutating"` (recording verdict). A dirty recording
  yields exactly `Vector("fs_edit")`, despite a false recording annotation
  (“fs_read/ReadOnly”). `IsolatedRerun.requireIsolatedTarget(recorded, original,
  target)`: in-place (target == original) → `Left(RerunError.MutatingUnisolated)`;
  faithful isolated copy (byte-identical snapshot, different path) →
  `Right(())`; foreign directory → rejection. Guard creates/runs nothing: after
  rejection original bytes match and parent is empty. ReadOnly recording needs
  no isolation (`Right(())` without checks).
- **LIN6-03-SC**: interrupt issued MID-rerun (fork + deterministic marker
  “dirt landed in isolated copy” + `fiber.interrupt`): original untouched
  (verifyUntouched before and after, no dirt in original at any time); copies
  remain classified/consistent (complete “dirt” file, fixture file byte-equal
  to original); rerun AFTER cancellation gives consistent verdict (`match=false`,
  same first-divergence `field-mismatch(tool)` at `job-1:n7`), rerun#2 and
  rerun#3 reports byte-identical, exactly one dirt file in each isolated copy —
  no duplicate side effects. Honest boundary: ZIO interrupt of synchronous work
  is observed at the boundary (the abandoned thread finishes in its throwaway
  copy or is abandoned); assertions cover observable state after cancellation,
  as LIN6-03 requires.

Minimal typed product fixes (each acceptance-driven, with reproducer):

1. `ReplayHarness.recordedGraph` — `NodeKind.Phase` + `PhaseData` branch →
   `SessionNode.Phase`. Reproducer: a document with a phase node reached
   `sys.error("fixture bug: node … kind Phase …")` — LIN6-01(sc) was impossible.
   Comparison semantics unchanged: the reducer does not synthesize phases, and
   phased recording honestly diverges at the phase node (documented in scaladoc
   and assertions).
2. `ReplayHarness.replay` / `IsolatedRerun.rerun` — additive parameters
   `limits: BudgetLimits = FixtureLimits`, `maxRounds: Int = FixtureMaxRounds`
   (frozen defaults moved to `ReplayHarness.FixtureLimits` /
   `FixtureMaxRounds`; existing calls unchanged). Reproducer: exhausting budget
   through product rerun was impossible because caps were hard-coded in
   `ReplayHarness.replay` (4 attempts in a 2-round recording are not exhausted).
3. `IsolatedRerun` — LIN6-02 policy: `mutatingTools`, `requireIsolatedTarget`,
   new `RerunError.MutatingUnisolated(tools: Vector[String])`; rerun report gains
   phased section (`phasedSection`: PhaseReplay.replayAll over doc graph +
   RecordedSource; for records without phases section is EMPTY, preserving
   byte-identical IR-01/IR-02 reports and all existing assertions). Reproducer:
   “mutating without isolation → typed rejection BEFORE execution” had no
   product path (ReplayHarness.replay is isolation-blind; guard belongs in the
   calling layer).

Frozen public contract families were not expanded: RaiderError/wire events/
schemaVersion untouched; new case is in lane-owned (LIN-06D, packet R4)
`RerunError` inside allowed paths; consumers (IsolatedRerunSpec IR-03
`case other`) remain compatible.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/IsolatedRerun.scala` — modified
  (+211/−20 including formatting)
- `modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala` — modified
- `modules/cli/src/test/scala/raider/cli/lineage/LiveRerunScriptedAcceptanceSpec.scala` — added

No changes outside allowed paths (docs/, build.sbt, scripts/, board untouched).
No ports used; unique TempDir prefixes (`raider-lin15sc-*`); fixture cleanup uses
acquireRelease + deleteRecursively.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN6-01(sc) — isolated rerun completes; phased diff report; byte-for-byte determinism | passed: `LiveRerunScriptedAcceptanceSpec` “LIN6-01-SC …” (honest match=false at phase node; phased section; report#1 == report#2; verifyUntouched Right) | sbt gate logs 20261012-183207 / 20261012-183708; local testOnly run (15 tests passed) |
| LIN6-02(sc) — budget exhausted → typed stop (RerunError.Replay over Admission) before exceeding budget | passed: “LIN6-02-SC budget …” — `Left(RerunError.Replay(LocalBudgetExceeded("maxAttempts=1 …")))`; no dirt in copy; original untouched | same run |
| LIN6-02(sc) — mutation without isolation → typed rejection BEFORE execution (product path) | passed: “LIN6-02-SC guard …” — `mutatingTools == Vector("fs_edit")` (from RESPONSE envelope); in-place/foreign → `MutatingUnisolated`; faithful copy → Right; ReadOnly in-place → Right; guard creates nothing (parent empty, bytes match) | same run |
| LIN6-03(sc) — interrupt mid-rerun; copy classified/consistent; rerun without duplicates | passed: “LIN6-03-SC …” — verifyUntouched before/after; dirt complete; fixture intact; report#2 == report#3; dirt: 0 in original, 1 per copy | same run |
| Flake discipline | 4 consecutive green spec runs (1 initial + 2 flake-check + 1 final on formatted sources), flake=0 | lane session sbt logs |
| Regression/no weakening | raiderCli/test — 262 tests passed / 0 failed; full `sbt test` (all modules) — 0 failed in gate run | gate manifest 20261012-183207-3502-0001 |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` | 0 (changed: 3 files; profiles: contracts/static/unit) | classification stdout (lane session) |
| `make quality-changed QUALITY_EXECUTE=1` (first run) | **1** — scalafmt-check failed (my files unformatted) + sbt-test failed (`raider.repl.TabShellUnreadPtySpec`, unrelated PTY flake in `modules/repl`) | artifacts/quality/20261012-181641-89313-0001/manifest.json, sha256 `52e2b04f…dc5412` |
| `sbt scalafmtAll` + `sbt scalafmtCheckAll` | 0 | — |
| `make quality-changed QUALITY_EXECUTE=1` (repeat) | 0, status=passed (core-contracts-spec / compile-fixtures / sbt-compile / scalafmt-check / sbt-test all passed, exit=0, no timeout/skip) | artifacts/quality/20261012-183207-3502-0001/manifest.json, sha256 `a5f2aa95…02b3080` |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0, status=passed | artifacts/quality/20261012-183708-7494-0001/manifest.json, sha256 `cfde3c68…7fd6ca5` |
| `sbt raiderCli/test` | 0 — 262 passed / 0 failed | sbt log |
| `sbt raiderCli/testOnly raider.cli.lineage.{LiveRerunScriptedAcceptanceSpec,IsolatedRerunSpec,ReplayHarnessSpec,WorkspaceIsolationSpec}` (final sources) | 0 — 15 passed / 0 failed | sbt log |
| `git status --short` / `git log --oneline -1` before start | baseline `4b46dc7`, clean tree | — |

## Limitations / blockers

- **In first gate run**, `TabShellUnreadPtySpec` (RAI-105, `modules/repl`, PTY)
  failed on timing; this card does not affect that module; repeat full run passed.
  Honestly recorded (manifest 52e2b04f…) as a flake outside this card — not
  “passed” for that run; passed is the repeat.
- **Phase-aware live-vs-recorded comparison**: whole-graph verdict on phased
  recording honestly diverges at phase node (reducer does not create phases —
  no wire case; changing comparator/reducer is outside allowed paths and card
  non-goals). Report's phased section comes from recorded side (PhaseReplay).
  Phase markers on wire remain a wave-2 schema card.
- **Duplicated static taxonomy §6.2**: `IsolatedRerun` contains a copy of
  exec/write sets (original is private in `LineageReducer`, runtime files outside
  allowed paths). Both cite ADR-027 §6.2; unification is a schema card.
- **LIN6-03 interrupt — ZIO boundary**: interrupt of synchronous work is observed
  at the boundary; fixture proves observable state invariants (original/copies/
  rerun), not thread termination. Process kill -9 is outside unit fixture.
- `requireIsolatedTarget` guards the calling layer; product CLI entry for
  live-rerun does not exist yet (LIN-06 live-wire is a separate owner card), so
  “calling layer” in acceptance is product API-level `IsolatedRerun` (as card
  contract specifies).
- No live model/paid calls (scripted only). No secrets in code/logs.

## Review and next task

Lane status: ready for coordinator review; merge belongs to coordinator
(lane push/merge prohibited). Draft record is this file (move to root after
merge). Next ready cards under parent card: LIN-12 (catalog persistence),
LIN-14 (LIN2-03 concurrent snapshot), RAI-113/114 — at coordinator's discretion.

Product-input hash and operational-record hash differ under reviewed policy;
lane evidence refers to lane snapshot (final acceptance refers to integrated
candidate).
