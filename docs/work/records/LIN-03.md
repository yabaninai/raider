# LIN-03: work record

## Final fixture completion round (2026-10-18)

Baseline: `637ab0ae716e72d1a8a43b3d6b34ceb22de97789` on `lane/lin-03`; the
worktree was clean before the two fixtures were added. Scope is limited to the
two previously missing fixtures plus the crash-window test seam and this
record. The frozen lineage schema and GraphHash implementation are unchanged.

### Fixture mechanisms and results

- **LIN3-01 — production-path 200-round fixture:** the campaign uses the real
  `JobManager` transition listener, `EventHub` subscription/collector,
  `MultiJobExport`, and its phase sink. Each of 200 ordered model-call rounds
  emits start/finish records and a matching phase start/finish through the
  production sink. At rounds 25, 50, 75, 100, 125, 150, 175, and 200, the test
  reads `currentProjection` while the campaign is still running and checks the
  projection wire equals the export prefix, the graph hash equals
  `GraphHash(graph)`, source rendering is present, and completed model/phase
  counts match the checkpoint. The final wire is also compared with the
  `events.jsonl` read. This extends the former fold-only 200-round evidence.
- **LIN3-03 — publication interruption fixture:** the production
  `LineageSnapshot.publish` path delegates to a package-scoped hookable helper;
  the test pauses an interruptible publication fiber after both generation
  files are written and before the manifest/pointer operation, then interrupts
  it. During the window, `LineageSnapshot.read` still resolves the original
  generation, both committed files remain readable, and the uncommitted pair
  is present/readable. A subsequent normal publication replaces the pointer;
  reading it verifies the new generation and both complete byte contents (the
  reader validates the committed SHA-256 digests). Existing three-repetition,
  eight-reader concurrent publication coverage continues to pass.

### Final acceptance mapping against the rescoped basis

| ID | Status | Evidence |
| --- | --- | --- |
| LIN3-01 | Pass: ordered 200 live production-path rounds, sampled-prefix validity/consistency. | `PhaseEmissionExportSpec` test `LIN3-01`; three consecutive targeted-suite passes. |
| LIN3-02 | Pass under the rescoped basis: live fold-error diagnostic is observable without changing the successful session exit; live dotc compile failure is explicitly outside this basis and remains pinned-compiler work. | Existing `LineageSummarySpec` / `LiveInvalidSession` evidence recorded above; not relabeled as compiler validation. |
| LIN3-03 | Pass under the rescoped basis: injected interruption between generation writes and pointer replacement leaves the old pointer valid; completing publication exposes the new complete pair, with no torn snapshot observed. | `LineageSnapshotProtocolSpec` test `LIN3-03`; three consecutive targeted-suite passes plus existing 8-reader × 3 publication race test. |

The two newly required fixtures each passed in three consecutive executions.
The targeted invocation was
`sbt --batch 'raiderCli/testOnly raider.cli.run.PhaseEmissionExportSpec raider.cli.lineage.LineageSnapshotProtocolSpec'`;
each execution reported 7 passed, 0 failed, 0 ignored. One initial compile
attempt failed because the new fixture passed `None` where the
`modelCallStarted` API expects an integer; the test argument was corrected and
the targeted suites then passed three times. This compile error was not a
phantom compile; no target/BSP purge was needed.

**Pre-gate record finalized before quality commands.** The only code/test files
changed are `LineageSnapshot.scala`, `LineageSnapshotProtocolSpec.scala`, and
`PhaseEmissionExportSpec.scala`, plus this operational record. No frozen schema,
GraphHash, build, quality policy, task card, or board was changed.

**Pre-gate record finalized before quality commands.** This document records
the fixture criteria and results and is an operational work record, not product
evidence. Required commands, in order: `make quality-changed`,
`make quality-changed QUALITY_EXECUTE=1`, then
`make quality-profile PROFILES="static unit contracts runtime"`. Manifests and
quality-verifier output are reported in the completion message only, as required
for this lane.

No paid calls were made. This status closes the LIN3-01/02/03 fixtures under
the rescoped basis only; it does not claim a live pinned-dotc compile gate or
accept LIN-04/the wider LIN milestone.

## Final consolidation attempt (2026-10-18)

Owner's final acceptance basis for this round: a live fold-invalid projection is
reported, the session exit remains unchanged, and the prior committed snapshot
remains readable through `lineage-commit.json`. A live compile failure is not an
acceptance condition; it is transferred to pinned-dotc validation scope. This
maps the earlier LIN3-02 compile-failure wording to the actual live fold-error
diagnostic and does not claim dotc validation.

The requested `git merge main` was run first. It brought the RAI-174 carrier
and board update; the only conflict was the `HeadlessRunner.writeArtifacts`
parameter list, resolved by retaining both `liveProjectionStatus` and the
defaulted `sdlcEvidence` carrier. Merge commit: `40a4faa`.

### Executed RED-first

Created a scratch worktree at `e74f621^`, cherry-picked the live fixture commit
`cec12a4`, and ran the fixture. The first compilation attempt exposed an
unrelated stale incremental-build error in `AgentLoop.scala` (`ToolCall.id` not
found); per the phantom-compile rule, removed all lane `target/` directories and
`.bsp` and retried once. The clean retry compiled production and failed test
compilation as expected: `LineageSnapshot` and
`HeadlessRunner.liveProjectionDiagnostic` were not found at
`LineageSummarySpec.scala:179` and `:181` (plus cascading `Any` typing errors).
The retry command exited 1. This is executed RED evidence for the prior
implementation's missing diagnostic/snapshot-reader fixture support, not
evidence of a runtime compile failure and not an acceptance requirement for
live dotc.

### Remaining production-path and crash proof

The available `LineageSummarySpec` live-invalid fixture exercises a real
`JobManager`/`EventHub` campaign, `MultiJobExport` phase sink and the live fold;
it verifies an invalid duplicate phase finish is reported without changing the
successful exit and that a prior snapshot is readable through the pointer.
The requested 200-prefix test driving 200 ordered production-path rounds was
not added or executed in this consolidation attempt; existing 200-round
evidence remains fold-level only. A fixture-controlled interruption after
generation writes but before pointer replacement was also not added or
executed; existing assertions prove readability after a fold-invalid campaign,
not the protocol crash checkpoint. LIN3-01 and the LIN3-03 crash criterion
therefore remain unproven here.

### Verification for this attempt

| Command | Exit | Evidence |
| --- | --- | --- |
| `git merge main` | 1 | Automatic merge stopped at one content conflict in `HeadlessRunner.scala`; resolved by retaining both parameters. |
| `git commit -m "merge: RAI-174 carrier into LIN-03 lane"` | 0 | Merge commit `40a4faa`. |
| `sbt --batch 'raiderCli/testOnly raider.cli.run.LineageSummarySpec'` on scratch parent after clean retry | 1 | Expected test-compilation RED: missing `LineageSnapshot` and `liveProjectionDiagnostic`; first attempt had unrelated compile error, then mandated purge/retry. |
| `sbt --batch 'raiderCli/testOnly raider.cli.run.LineageSummarySpec'` on lane tip | 0 | 4 tests passed, including the live invalid-fold diagnostic/prior-pointer fixture; no failures or ignored tests. |
| `make quality-changed` | 0 | No worktree changes detected by the classifier; clean-tree minimum `fast` selected. Operational work-record files are excluded from the product fingerprint. |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | Passed `fast`; manifest `artifacts/quality/20261018-042717-60618-0001/manifest.json`, SHA-256 `aea31f66b0a59f1849bc90dd488c761157f299f2e5322ba2d5afbe2ad5928349`; self-registry, compile, scalafmt/scalafix, forbidden APIs, license, contracts, compile fixtures, plan validation, and tests passed. |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0 | All requested profiles passed; manifest `artifacts/quality/20261018-043438-71846-0001/manifest.json`, SHA-256 `1bf98b85cb73639baf38e10a16e5e00d5702f4d390acfc36d36ba749cc5b9f29`; contracts, runtime, static, unit reported passed. |

Final status against the rescoped basis: LIN3-02 live fold-error diagnostic,
unchanged successful session exit, and readable prior pointer snapshot were
already implemented and covered by the existing live fixture; the RED-first
comparison confirms the diagnostic API was absent at `e74f621^`. LIN3-01's
200-round production-path requirement and LIN3-03's crash-between-publication
checkpoint remain incomplete. Therefore LIN-03 is not accepted and LIN-04
remains blocked. No frozen schema or GraphHash changes were made. No paid calls
were made.

## Live invalid-fold acceptance fixture (2026-10-18)

Status: partial completion of the assigned fixture task; baseline `e74f621` on
`lane/lin-03`, clean before edits. Reporting implementation was already at tip.
No production runtime code, frozen schema, GraphHash, build, gate policy, or
board was changed.

### Fixture mechanism and result

`LiveInvalidSession` starts a real `JobManager` campaign, subscribes its real
`EventHub` stream, and feeds ordered envelopes through production
`MultiJobExport` and `LoopEvents`' phase sink. The campaign emits phase-start, a
matching finish, then a duplicate finish without another start. The third live
phase envelope is invalid to the incremental reducer; `currentProjection`
retains the fold error while the campaign completes successfully. The fixture
passes this live result through `HeadlessRunner.liveProjectionDiagnostic` and
its summary-line renderer.

Before this invalid session, the fixture runs a valid mock HeadlessRunner
session and resolves its committed snapshot through `lineage-commit.json` using
`LineageSnapshot.read`. It checks that the invalid session exit equals the valid
session exit (both 0), the diagnostic has a fold error and an invalid report
line, and both pointed source/data files remain readable. This checks the
prior committed snapshot, not an invalid-session snapshot or crash checkpoint.

This fixture does not cause a dotc compilation failure. `MultiJobExport
currentProjection` returns only `(Option[LiveLineageProjection], Option[String])`;
the second member records fold/reduce/view rejection from
`LiveLineageProjection.append`. No compiler is invoked by incremental append,
and no compiler result exists in that tuple. The fold-invalid test therefore
proves diagnostic/exit/pointer behavior only, not compiler-failure recovery.

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| LIN3-01 | Partial: scripted 200-round fold evidence exists from prior work; no 200-round production stream in this fixture. | `LiveLineageProjectionSpec`; `LiveInvalidSession`. |
| LIN3-02 | Partial: live fold-invalid input reaches HeadlessRunner diagnostic formatting without changing the session exit. No live dotc failure was tested. | `LineageSummarySpec` LIN3-03; `currentProjection` semantics. |
| LIN3-03 | Partial: earlier committed pointer resolves to readable files after later fold-invalid campaign; no crash-between-nodes persisted checkpoint. | `LineageSummarySpec` LIN3-03 and `LineageSnapshot.read`. |
| Report/exit/pointer assertions | Pass: fold-error diagnostic + invalid report line; both exits 0; both pointed files readable. | Targeted test output below. |
| RED-first | `e74f621^` lacks `HeadlessRunner.liveProjectionDiagnostic`; this test statically references it and is incompatible with the parent. Parent-revision test was not executed, so no failing pre-fix output is claimed. At tip the test passes. | Parent source inspection; targeted tip run. |
| GraphHash/frozen schema | Unchanged; no production source changed. | Diff inventory. |

| Command | Exit | Evidence |
| --- | --- | --- |
| `git status --short --branch && git log -1 --oneline` | 0 | Clean baseline, `lane/lin-03`, `e74f621`. |
| `sbt --batch 'raiderCli/testOnly raider.cli.run.LineageSummarySpec'` | 0 | 4 passed, 0 failed, 0 ignored. |
| `sbt --batch scalafmtAll` | 0 | Two CLI test sources formatted. |
| `make quality-changed` | Pending | Run after pre-gate record finalization. |
| `make quality-changed QUALITY_EXECUTE=1` | Pending | Run after classification. |
| `make quality-profile PROFILES="static unit contracts runtime"` | Pending | Required profiles. |

Changed: `modules/cli/src/test/scala/raider/cli/run/LineageSummarySpec.scala`,
new `modules/cli/src/test/scala/raider/cli/run/LiveInvalidSession.scala`, and
this record. No paid calls. LIN3-01..03 remain partial; LIN-03 is not accepted
and LIN-04 remains dependent on LIN-03. Outstanding: live pinned-compiler
validation, 200 production rounds, and crash-between-nodes checkpoint proof.

## Live projection diagnostic completion round (2026-10-18)

Status: partial; the reviewed HeadlessRunner reporting gap is wired. Baseline:
`98d2b4f` on `lane/lin-03`, after the requested fast-forward merge from `main`;
worktree was clean before edits. No frozen schema, GraphHash, build, gates, or
board files were changed.

### Resulting behavior

`executeAsJob` now carries the exporter’s current live projection through to
artifact rendering. A non-valid `projectionStatus` and/or a retained live fold
error produces an additive, bounded `lineage live projection: invalid (...)`
line in `summary.md`. It is observational only and does not participate in the
session result or exit code. Existing settled lineage artifacts continue to be
published through `lineage-commit.json`; the fixture resolves the pointer and
checks both committed members remain readable.

### Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN3-01 | Existing live ordered fold behavior unchanged; this round did not add a 200-round production-run check. | Existing `LiveLineageProjectionSpec`; not rerun as a dedicated test in this round. |
| LIN3-02 | Partial: live `projectionStatus`/retained fold error now have a summary reporting path without changing the exit path. No live dotc compile was added; the pinned compiler remains exercised on settled committed artifacts only. | `HeadlessRunner.liveProjectionDiagnostic`; `LineageSummarySpec` LIN3-03 test; targeted spec below. |
| LIN3-03 | Partial: a report-only failure fixture observes success exit and a readable pointer-backed prior/settled snapshot. It is not a process-crash-between-nodes fixture and does not prove a mid-run checkpoint. | `LineageSummarySpec` LIN3-03 test. |
| RED-first | The baseline code at `a25641f` had no `liveProjectionStatus` passed to `writeArtifacts`, and the summary writer emitted only settled lineage/artifact lines; therefore the finding’s report assertion had no reporting path. This is source-inspection evidence, **not** an executed failing pre-fix test. | Baseline `HeadlessRunner.scala` before this patch; no historical run manifest. |
| Snapshot pointer | The fixture resolves `LineageSnapshot.read` and checks both source/data files exist. | `LineageSummarySpec` LIN3-03 test. |
| GraphHash | No canonicalization changes. | `GraphHash.scala` unchanged. |

The fixture supplies a retained fold-error value to the same diagnostic path and
runs a successful mock session to compare the unchanged success exit and
resolve the session snapshot. It does **not** cause the production live stream
to become invalid during execution, and does not exercise a dotc failure or
prove a compile-failing live projection leaves a prior mid-run snapshot. The
requested full end-to-end live-invalid/compile-failure and crash-checkpoint
acceptance remains open; no frozen schema carrier was needed for this additive
summary channel.

### Commands and gate evidence

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short && git branch --show-current && git log -1 --oneline` | 0 | Clean before edits; `lane/lin-03`, `98d2b4f`. |
| `git merge main` | 0 | Fast-forward to `98d2b4f`; no conflicts. |
| `sbt --batch 'raiderCli/testOnly raider.cli.run.LineageSummarySpec'` | 0 | 4 tests passed; no failures/ignored. |
| `sbt --batch scalafmtAll` | 0 | Formatting completed. |
| `make quality-changed` | pending | To be recorded after the final pre-gate record is finalized. |
| `make quality-changed QUALITY_EXECUTE=1` | pending | To be recorded. |
| `make quality-profile PROFILES="static unit contracts runtime"` | pending | To be recorded. |

No paid calls were made.

### Changed paths

- `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`
- `modules/cli/src/test/scala/raider/cli/run/LineageSummarySpec.scala`
- `docs/work/records/LIN-03.md`

Next: add a production-wired fixture that makes the live stream projection
invalid mid-session, runs the pinned compiler by an authorized live mechanism,
proves exit invariance and pointer resolution under the failure, and covers
crash-between-node checkpoint behavior. LIN-03 is not accepted; LIN-04 remains
dependent on LIN-03 acceptance.

## Completion attempt (2026-10-17)

Status: blocked. Baseline commit: `4bc5b0d` (`lane/lin-03`); merged `main` at `46dc140` as requested (merge completed without conflicts). The worktree was clean before the merge. No product source was changed in this attempt.

### Blocker

The required zero-torn-read guarantee cannot be truthfully implemented within LIN-03's authorized source paths as written. The existing run artifacts are two independently named files (`lineage.scala` and `lineage-data.json`); existing readers such as `ReplayCli` resolve the fixed data-file path. Publishing a generation directory plus an atomic manifest/pointer only protects readers that resolve and honor that pointer. Updating those readers is outside LIN-03's allowed `modules/cli/src/main/scala/raider/cli/run/**` path, and leaving them unchanged would make the required concurrent-reader proof irrelevant to actual consumers. Preserving the legacy fixed paths alongside the new pointer would still allow mixed generations to be observed. I did not add a manifest, claim atomic pair publication, or present a synthetic fixture as proof.

Also, this checkout has no authorized implementation interface for compiling a projection during an active session: `LiveLineageProjection` renders in-process but does not run the pinned dotc compile gate. Wiring a compiler invocation requires an agreed product mechanism (and its lifecycle/resource/error contract) rather than silently adding a process/dependency to this card. The requested invalid-projection status must remain observable while preserving the session result, and cannot be claimed from the existing render-only status.

These are implementation-scope/interface blockers, not a frozen schema or GraphHash defect. No frozen lineage schema, GraphHash canonicalization, gate policy, shared build files, board, or task-card files were changed. A narrowly scoped authorization/contract should identify the pair reader API and all consumer paths, then define how live compilation is provided without adding an unauthorized runtime dependency. LIN3-01's existing live fold wiring remains; LIN3-02 and LIN3-03 are not met by this completion attempt. LIN-04 remains blocked on LIN-03 acceptance.

### Acceptance and execution evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN3-01 | Partial; existing production fold wiring is present from baseline, but no new end-to-end checkpoint fixture was added. | Baseline `4bc5b0d`; existing `LiveLineageProjectionSpec` |
| LIN3-02 | Not met; no live pinned-compiler validation or new non-exit-changing diagnostic behavior. | Blocker above |
| LIN3-03 | Not met; no persisted atomic source/data snapshot or concurrent-reader fixture. | Blocker above |
| Torn reads | Not verified; no fixture run and no zero-torn-read claim. | Blocker above |

| Command | Exit | Evidence |
| --- | --- | --- |
| `git status --short && git branch --show-current && git log -1 --oneline && git merge main` | 0 | Clean pre-merge worktree; branch `lane/lin-03`; merge of `main` at `46dc140` succeeded |

No quality gates, test profiles, or manifests were run in this attempt; they would not establish the missing behavior. No paid calls were made. No implementation commit was created. This record reports the blocker only and is not acceptance evidence.

## Prior work records

## Wiring continuation (2026-10-16)

Status: partial. Branch baseline after requested refresh: `a62a5a2b05d34a0107a1c7bed021fe4ec63c5fb8` (merge of main `963d584`).

Implemented an ordered live fold in `MultiJobExport`: each committed export batch updates `LiveLineageProjection` under the existing export lock, after the bytes are appended to the export stream. HeadlessRunner reads that final incremental projection only after its existing drain and watermark barrier and uses its graph when it matches strict settled reduction. Existing reducer fallback and graph hash canonicalization are unchanged.

This does **not** complete LIN-03. In particular, this pass does not continuously persist projection snapshots, does not add an atomic source/data pair commit protocol or readers that honor one, and does not validate generated source by compiling it during the live session. Projection fold errors are retained by the exporter but are not yet surfaced as a dedicated summary diagnostic. Consequently LIN3-02's required visible invalid status and LIN3-03's paired crash checkpoint are not met. The frozen schema was not changed. No contract-change request is needed for this gap; the remaining implementation can fit the existing CLI path boundary, but the two-file readers need an explicitly integrated snapshot-manifest reader contract before zero-torn-pair can be claimed.

### Continuation evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN3-01 | Partial: existing scripted 200-round fold test passes; production exporter now feeds the same fold in serialized append order and HeadlessRunner reads it after drain. No end-to-end 200-round production stream fixture was added. | `LiveLineageProjectionSpec`; targeted command below |
| LIN3-02 | Not met: compile failure/status visibility and session-exit invariance are not wired. | No acceptance test |
| LIN3-03 | Not met: no crash-consistent paired lineage artifact checkpoint/commit protocol. | No acceptance test |
| GraphHash | No canonicalization change. | `GraphHash.scala` unchanged |

### Continuation commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git -C <worktree> merge main` | 0 | Merge commit `a62a5a2`; no conflicts |
| `sbt --batch 'raiderCli/testOnly raider.cli.run.LiveLineageProjectionSpec'` | 0 | 2 tests passed; compile of 2 changed Scala sources succeeded |
| `sbt --batch scalafmtAll` | 0 | Formatted the two changed Scala files |
| `make quality-changed` | 0 | Classified `contracts`, `docs`, `static`, `unit` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261016-163325-64064-0001/manifest.json`; SHA-256 `7bc5e85f33a7d39d12fe679371f6c061bc6989aae54abbb063c44cd53cfe0cb4`; compile, formatting, contracts, plan validation, and tests passed |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0 | `artifacts/quality/20261016-164354-10005-0001/manifest.json`; SHA-256 `d3dcce5f652507ed51bcd47088fc01265b50df1ffbecfb71fe2889a38775b9b7`; selected profiles passed |
| `make quality-changed QUALITY_EXECUTE=1` | 1 | `artifacts/quality/20261016-165350-25842-0001/manifest.json`; existing `MultiJobExportSpec` MJE-02 interleave/hash property failed (1 failure); all other gates passed |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261016-170348-38327-0001/manifest.json`; SHA-256 `abf6e453f8bf6d5bcc7cf07ffc8b6bd10b483629102f4eca6f8e77f6bb0df870`; full changed profile set passed on retry |
| `make quality-changed` (including final record update) | 0 | Classified `contracts`, `docs`, `static`, `unit` |
| `make quality-changed QUALITY_EXECUTE=1` (including final record update) | 1 | `artifacts/quality/20261016-171247-54420-0001/manifest.json`; MJE-03c detached-child test failed (`childId = <none>`); all other listed test suites and gates passed |

Product source SHA-256 at the successful run: `MultiJobExport.scala` `ac8a84bf5830ccd419e4a2a1a87e5be61722bea55d62e62fcbb81f2aed770a40`; `HeadlessRunner.scala` `463afccb7f1d660e152dc034675fb76aedf3aeb31a966f96255d384fa12ed4c6`.

The required changed and static/unit/contracts/runtime gates ran and passed for this implementation. These green checks do not close the unmet functional criteria above. No paid calls were made.

### Changed paths in continuation

- `modules/cli/src/main/scala/raider/cli/run/MultiJobExport.scala`
- `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`
- `docs/work/records/LIN-03.md`

Next action: complete the frozen-schema-preserving pair snapshot/commit reader protocol, persist each valid incremental checkpoint, expose compile/projection status without affecting run exit, then add production-wiring and crash/race acceptance before gates/review. LIN-04 remains dependent on full LIN-03 acceptance.

Status: partial. Date: 2026-10-16. Baseline commit/source: `7c03213` on `lane/lin-03`, initially clean; refreshed by merging `main` at `0870045` (merge commit `69a8fc1`).

## Continuation and merge refresh

The required baseline refresh completed without conflicts. RAI-156's ReplayHarness drain change is adjacent only to replay and its downstream ChatExport chain gate; it does not change the lineage envelope exporter or HeadlessRunner path. Its terminal-watermark drain semantics reinforce the requirement that final lineage reduction occurs only after the stream is caught up. No RAI-156 files were edited in this continuation.

This continuation did not implement the remaining live-export wiring. Inspection confirmed that HeadlessRunner currently serializes the exported stream through `MultiJobExport`, then reduces its final `exportedWire` snapshot; `LiveLineageProjection.append` remains a standalone pure fold. Likewise, `RunLineageArtifacts.write` atomically replaces each file independently, not as one atomic source/data pair. No code was changed to imply that these gaps are closed.

## Continuation checks

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git merge main` (worktree; main at `0870045`) | 0 | Merge commit `69a8fc1`; no conflicts |
| `make quality-changed` | 0 | Clean tree classification: minimum `fast` profile |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261016-141647-17048-0001/manifest.json`; self, compile, formatting, contracts, plan validation, and tests passed |
| `make quality-profile PROFILES="static unit contracts runtime ci"` | 0 | `artifacts/quality/20261016-142459-32470-0001/manifest.json`; all selected checks passed |
| `make quality-changed` (after this record update) | 0 | Classified operational-record-only change as `docs` |
| `make quality-changed QUALITY_EXECUTE=1` (after this record update) | 0 | `artifacts/quality/20261016-143330-38577-0001/manifest.json`; SHA-256 `16ebefc844c233ad7f051e507ad900786a334724da1d583928c6cb73a25a18d6`; plan validation passed |

These executions verify the refreshed, unchanged candidate; they do not establish the missing LIN-03 implementation acceptance or replace integrated-candidate checks after implementation.

## Resulting behavior

Added an immutable CLI-layer live projection fold. Each accepted versioned envelope is reduced using partial-prefix semantics, adapted to the LIN-02 view, and rendered into a new source snapshot. A valid graph whose projection is currently not renderable (including a prefix with no completed nodes) is retained with an explicit invalid projection status rather than losing graph progress. Typed reduce/view errors leave the prior immutable value available to the caller.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/run/LiveLineageProjection.scala`
- `modules/cli/src/test/scala/raider/cli/run/LiveLineageProjectionSpec.scala`
- `docs/work/records/LIN-03.md`

No wire/schema, GraphHash canonicalization, or public frozen contract changes.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN3-01 | Pass for a scripted 200-round stream: every prefix maintains a graph consistent with its complete projected nodes; final graph has 200 model nodes and the GraphHash agrees with the graph. | `LiveLineageProjectionSpec`; profile manifest `artifacts/quality/20261016-055937-31619-0001/manifest.json` (SHA-256 `dccc0cb3e21502e2cf1bbf95be2e11e712c61d8e15ee68a7d03a761c28d47297`) |
| LIN3-02 | Partial: reducer errors are typed and do not mutate the previous projection state. A compile-validation failure status/compile recovery on the live session is not implemented. | `LiveLineageProjectionSpec`; same profile manifest |
| LIN3-03 | Partial: incremental snapshots are immutable and the existing reducer/prefix implementation covers crash boundaries; this change does not persist snapshots atomically alongside the running artifact or wire the fold into `HeadlessRunner`. | Existing `LiveGraphBuilderSpec`; new `LiveLineageProjectionSpec`; execution manifest below |
| Existing GraphHash contract | No canonicalization code changed; the new fixture verifies the graph's hash against its graph value. | `modules/runtime/src/main/scala/raider/runtime/lineage/model/GraphHash.scala` unchanged |

Product source SHA-256 values at the verified run:

- `LiveLineageProjection.scala`: `e55dadf5198dfe4a06f17ab8d2ed73bfb12e489eb90d33de1de56a088ac9f194`
- `LiveLineageProjectionSpec.scala`: `09cd15ffb10833bd0b6e795978be445ccb5a636dbacc0a6c319f6dd661ded0d8`

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git -C <wt> log --oneline -1 && git -C <wt> status --short --branch` (before edits) | 0 | HEAD `cd7032f`, branch `lane/lin-03`, clean |
| `make quality-changed` | 0 | Selected `contracts`, `static`, `unit` |
| `sbt --batch scalafmtAll` | 0 | Formatting pass |
| `sbt --batch 'raiderCli/testOnly raider.cli.run.LiveLineageProjectionSpec'` | 0 (after fixing fixture API args) | Targeted 2 tests passed |
| `make quality-profile PROFILES="static unit contracts"` | 0 | `artifacts/quality/20261016-055937-31619-0001/manifest.json`, SHA-256 `dccc0cb3e21502e2cf1bbf95be2e11e712c61d8e15ee68a7d03a761c28d47297` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261016-060739-42171-0001/manifest.json`, SHA-256 `8b363e1f43b5c668079b31cac0d034a1b82405e367f9f645ffae6c2e9728a04b`; all selected checks passed |
| `make quality-changed` (including this record) | 0 | Selected `contracts`, `docs`, `static`, `unit` |
| `make quality-changed QUALITY_EXECUTE=1` (including this record) | 0 | `artifacts/quality/20261016-061541-50781-0001/manifest.json`, SHA-256 `07f875a9a070a68b6ba71dbcdc4bb98b5a1d6e77d1a19ac5d6cb1e9772bd4808`; all selected checks passed |

An earlier full changed run passed compile/format but failed once in existing `MultiJobExportSpec` (`MJE-02`, expected two fork records, observed one). The subsequent required profile and changed runs both passed without changing that fixture or threshold. A first execute attempt also exceeded 120 seconds and was rerun with a 900-second timeout; it is not treated as evidence.

## Limitations / blockers

This is not complete LIN-03 acceptance. The pure snapshot fold is not connected to the live export stream or filesystem artifacts. Consequently it does not yet persist `lineage.scala`/`lineage-data.json` at each round/crash checkpoint, does not atomically coordinate the two artifact files, and does not compile the live projection or report/continue after a compile failure. The targeted fixture uses scripted local events only. The requested atomic publication of two separately named files also needs a reader-visible commit/snapshot protocol if consumers must never observe a mixed generation; the current frozen artifact readers resolve the two fixed paths independently. That interface question must be resolved narrowly without changing the frozen lineage schema. No paid calls were made.

## Review and next task

Independent review is pending. Remaining LIN-03 work: feed the serialized live export stream into the incremental fold, persist a crash-consistent projection/data snapshot, report validation failure without affecting session exit, and prove LIN3-01/02/03 plus GraphHash canonicalization regression. Resolve whether artifact-pair atomicity requires a versioned reader-visible pointer/manifest before implementation; no wire/schema change is authorized here. Next dependent card remains LIN-04, but it is not ready for acceptance until LIN-03 closes.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
