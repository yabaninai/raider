# LIN-07: work record

Status: complete. Date: 2026-10-20. Baseline commit: `73af700aaa84355c5a1693c5c55dbe21bbd9f0ff` on `lane/lin-07`; initial `git status --short` was clean.

## Resulting behavior

Partial reruns now have a typed live path. `PartialLiveRerun.run` reads the committed pointer via `LineageSnapshot.read`, creates a fresh graph from that committed document, resolves boundary inputs from the committed data, isolates the workspace, and delegates the selected graph to `PartialRerun.executeLive`. That runtime method translates the selected request to the LIN-06 `RerunBackend` seam, which `ReplayHarness.liveBackend` implements. Fresh prompt/model values are passed separately; committed prompts and responses are not replayed. The isolated directory is released on success, failure, and interruption. An execution failure is not turned into partial success.

Budget exhaustion during backend work remains the typed `RaiderError.LocalBudgetExceeded`; the call completes instead of hanging and produces no successful partial result. Cancellation interrupts the backend; finalizers run and no result is reported. The live pointer fixture verifies the selected-only request, a fresh graph change/diff, and unchanged sibling data in the still-readable committed snapshot. The cancellation fixture verifies pointer readability, unchanged original workspace bytes, and cleanup of the isolated directory.

## Changed paths

- `modules/runtime/src/main/scala/raider/runtime/lineage/replay/PartialRerun.scala` — dispatch through the existing live `RerunBackend` seam.
- `modules/runtime/src/test/scala/raider/runtime/lineage/replay/PartialRerunSpec.scala` — typed mid-work budget refusal and interruption/finalizer fixture.
- `modules/cli/src/main/scala/raider/cli/lineage/PartialLiveRerun.scala` — pointer resolution, boundary-data resolution, isolation, live invocation, graph diff.
- `modules/cli/src/test/scala/raider/cli/lineage/PartialLiveRerunAcceptanceSpec.scala` — committed-pointer/live graph-diff and cancellation cleanup acceptance.
- `docs/work/records/LIN-07.md` — this work record.

No frozen schema, GraphHash, build, gate registry, or task-card changes.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN7-01 / LIN7-PHASE | passed: selected phase/subtree contains only selected descendants; sibling node values are preserved | `PartialRerunSpec` |
| LIN7-02 / LIN7-INPUT | passed: missing external data is a typed contract error; pointer-backed live path resolves inputs through committed data | `PartialRerunSpec`; `PartialLiveRerunAcceptanceSpec` |
| LIN7-BUDGET-EXHAUSTED | passed: backend work returns `LocalBudgetExceeded`; no completed partial execution is returned | `PartialRerunSpec` |
| LIN7-CANCEL | passed: interrupted work runs finalizer; no result is emitted | `PartialRerunSpec` |
| LIN7-LIVE-01 | passed: pointer-resolved selected graph dispatched through `RerunBackend`; fresh graph diff detects changed selected output; committed sibling remains unchanged/readable | `PartialLiveRerunAcceptanceSpec` |
| LIN7-LIVE-HARNESS | passed: CLI `ReplayHarness.liveBackend` executes the selected request through the live seam with a fresh prompt and a local scripted model | `PartialLiveRerunAcceptanceSpec` |
| LIN7-CANCEL-LIVE | passed: cancellation interrupts; pointer remains readable; original workspace bytes unchanged; isolated workspace removed | `PartialLiveRerunAcceptanceSpec` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `sbt --batch 'raiderRuntime/testOnly raider.runtime.lineage.replay.PartialRerunSpec' 'raiderCli/compile'` | 0 | runtime six tests passed; CLI compile passed |
| `sbt --batch scalafmtAll 'raiderCli/testOnly raider.cli.lineage.PartialLiveRerunAcceptanceSpec'` | 0 | CLI three tests passed, including the real `ReplayHarness.liveBackend` seam |
| `sbt --batch scalafmtAll 'raiderRuntime/testOnly raider.runtime.lineage.replay.PartialRerunSpec' 'raiderCli/testOnly raider.cli.lineage.PartialLiveRerunAcceptanceSpec'` | 0 | runtime six and CLI three tests passed |
| `make quality-changed` (final pre-gates classification) | 0 | classified `contracts`, `static`, `unit`; four intended Scala source/test paths only |
| `make quality-changed QUALITY_EXECUTE=1` | 0; all 5 gates passed, timeout false | `artifacts/quality/20261020-092724-73270-0001/manifest.json`; SHA-256 `0293e9d11f51bfa76c5817a4ab67e2eec1a282cd135d9ce72d10636407cd7e8d` |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0; all 5 gates passed, timeout false | `artifacts/quality/20261020-093457-83972-0001/manifest.json`; SHA-256 `b489cd8e24b882017a6ceb34a34e36fa696f4f868d3e831fa1f751e34dff0802` |
| `python3 scripts/quality/quality.py verify ... --require contracts` and `--require unit` on changed manifest | 0 for both; fingerprint fresh | verifier stdout is included in final handoff only |
| `python3 scripts/quality/quality.py verify ... --require static/unit/contracts/runtime` on profile manifest | 0 for all four; fingerprints fresh | verifier stdout is included in final handoff only |
| `git diff --check` | 0 | no whitespace errors |

## Limitations / blockers

No paid calls were made. The end-to-end acceptance backend is scripted and deterministic. The lane evidence is for this lane snapshot; integrated-candidate gates remain required. No durable resume, exact-once execution, or mutation-replay claim is made.

## Review and next task

Independent review is pending. Next available work is lane integration/review; coordinator must run gates on the integrated candidate. Commit hash is recorded in the final handoff; no push or merge was performed by this lane.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating this operational record does not rebind a manifest to another product snapshot. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
