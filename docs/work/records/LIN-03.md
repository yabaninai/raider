# LIN-03: work record

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
