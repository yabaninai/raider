# LIN-07: work record

Status: done. Date: 2026-10-20. Baseline commit: `1c648d2` on `lane/lin-07`; initial `git status --short` was clean. Merged coordinator scope-note commit `dd8b536` before lane changes. This record was finalized before mandatory quality-gate execution.

## Resulting behavior

The review finding is closed. `RerunRequest` carries both the committed graph for provenance and the selected graph plus `PartialReplayInputs`; `PartialRerun.executeLive` forwards all three. The CLI `ReplayHarness.liveBackend` constructs its live prompt from the caller's fresh prompt, selected node IDs, and resolved boundary values, then executes through the supplied live `ModelBackend`. Its fixed campaign document is used only as reference/comparison metadata; the live backend response drives `AgentLoop` execution. `PartialLiveRerun.run` reads the committed pointer, resolves inputs from that document, and isolates the workspace.

The real-backend live-harness acceptance uses `ReplayHarness.liveBackend` with a scripted model stand, not a custom `RerunBackend` or custom expected graph. It asserts the stand's observed prompt contains the selected node and resolved upstream result, excludes the sibling node, and verifies sibling graph/data equality against the still-readable committed snapshot. Budget exhaustion remains typed `RaiderError.LocalBudgetExceeded`; cancellation interrupts, cleans the isolated directory, and preserves pointer/workspace state.

## Changed paths

- `modules/runtime/src/main/scala/raider/runtime/lineage/replay/RerunBackend.scala` — carries recorded and selected graphs plus resolved inputs.
- `modules/runtime/src/main/scala/raider/runtime/lineage/replay/PartialRerun.scala` — dispatches both graph views and boundary values through the live seam.
- `modules/runtime/src/test/scala/raider/runtime/lineage/replay/PartialRerunSpec.scala` — typed mid-work budget refusal and interruption/finalizer fixture.
- `modules/cli/src/main/scala/raider/cli/lineage/PartialLiveRerun.scala` — pointer resolution, boundary-data resolution, isolation, live invocation, graph diff.
- `modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala` — uses request-selected nodes and resolved inputs in live model execution; fixture document remains reference-only.
- `modules/cli/src/main/scala/raider/cli/lineage/IsolatedRerun.scala` — supplies the extended request fields for the existing full-graph caller.
- `modules/cli/src/test/scala/raider/cli/lineage/PartialLiveRerunAcceptanceSpec.scala` — non-circular real-harness selected-input/sibling acceptance and cancellation cleanup.
- `docs/work/records/LIN-07.md` — this work record.

No frozen schema, GraphHash, build, gate registry, or task-card changes.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN7-01 / LIN7-PHASE | passed: selected phase/subtree contains only selected descendants; sibling node values are preserved | `PartialRerunSpec` |
| LIN7-02 / LIN7-INPUT | passed: missing external data is a typed contract error; pointer-backed live path resolves inputs through committed data | `PartialRerunSpec`; `PartialLiveRerunAcceptanceSpec` |
| LIN7-BUDGET-EXHAUSTED | passed: backend work returns `LocalBudgetExceeded`; no completed partial execution is returned | `PartialRerunSpec` |
| LIN7-CANCEL | passed: interrupted work runs finalizer; no result is emitted | `PartialRerunSpec` |
| LIN7-LIVE-01 | passed: actual `ReplayHarness.liveBackend` model prompt observes selected node and pointer-resolved boundary value, excludes sibling; committed sibling graph/data remain identical/readable | `PartialLiveRerunAcceptanceSpec` |
| LIN7-LIVE-HARNESS | passed: fresh model stand receives selected graph context and resolved input through the real harness; no custom expected-graph backend | `PartialLiveRerunAcceptanceSpec` |
| LIN7-BUDGET-LIVE | passed: live `ReplayHarness` backend consumes the one-attempt budget during fresh tool/model work and returns typed `LocalBudgetExceeded` | `PartialLiveRerunAcceptanceSpec` |
| LIN7-CANCEL-LIVE | passed: cancellation interrupts; pointer remains readable; original workspace bytes unchanged; isolated workspace removed | `PartialLiveRerunAcceptanceSpec` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `sbt --batch 'raiderRuntime/testOnly raider.runtime.lineage.replay.PartialRerunSpec' 'raiderCli/testOnly raider.cli.lineage.PartialLiveRerunAcceptanceSpec'` ×3 | 0 each | each repetition: runtime 6/6 and CLI 4/4 passed |
| `make quality-changed` | 0 | final changed-path classification: `contracts`, `static`, `unit` |
| `make quality-changed QUALITY_EXECUTE=1` | pending at record freeze | required gate; output and manifest status reported in completion message only |
| `make quality-profile PROFILES="static unit contracts runtime"` | pending at record freeze | required semantic profiles; output and manifest status reported in completion message only |
| `python3 scripts/quality/quality.py verify <generated manifest> --require <each required profile>` | pending at record freeze | verification output reported in completion message only |
| `git diff --check` | pending at record freeze | recorded after gate execution in completion message only |

## Limitations / blockers

No paid calls were made. The end-to-end acceptance backend is scripted and deterministic. The lane evidence is for this lane snapshot; integrated-candidate gates remain required. No durable resume, exact-once execution, or mutation-replay claim is made.

## Review and next task

Independent review is pending. Next available work is lane integration/review; coordinator must run gates on the integrated candidate. Final lane commit hash is reported in the completion message; no push or merge-to-main was performed by this lane.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating this operational record does not rebind a manifest to another product snapshot. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
