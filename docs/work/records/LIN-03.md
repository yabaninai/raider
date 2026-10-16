# LIN-03: work record

Status: partial. Date: 2026-10-16. Baseline commit/source: `cd7032f` (worktree `lane/lin-03`, initially clean).

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

This is not complete LIN-03 acceptance. The new pure snapshot fold is not connected to the live export stream or filesystem artifacts. Consequently it does not yet persist `lineage.scala`/`lineage-data.json` at each round/crash checkpoint, does not atomically coordinate the two artifact files, and does not compile the live projection or report/continue after a compile failure. The targeted fixture uses scripted local events only. No paid calls were made.

## Review and next task

Independent review is pending. Continue LIN-03 by integrating this state into the single ordered export stream and artifact lifecycle, then implement a safe artifact commit/checkpoint strategy and periodic compile validation with typed invalid status that does not change the session exit. Rerun the full acceptance gates after that work. Next dependent card is LIN-04 after LIN-03 acceptance.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
