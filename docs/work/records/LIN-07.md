# LIN-07: work record

Status: partial. Date: 2026-10-20. Baseline: `90f6f882ebc6579b47de04301b0b96860a989bdd` on `lane/lin-07`; initial `git status --short` was clean.

## Resulting behavior

Added a runtime selection planner and dispatch seam for fresh partial reruns. Typed selections distinguish phase and subtree roots. Invalid/missing selections, phase-kind mismatch, unresolved external graph inputs, and extraneous supplied inputs are refused. The selected graph contains only the selected root and descendants; the selected root's parent edge is cut. A fresh invocation ID is attached to the request and mismatched backend correlation is refused. Explicit `maxRounds` and `BudgetLimits` reach the backend; backend execution remains interruptible through ZIO.

This is a partial completion. The scope does not yet resolve durable pointer inputs itself or wire this runtime seam to the CLI `IsolatedRerun.live` path. There is no end-to-end live graph-diff acceptance. The present budget fixture only proves invalid round-budget rejection before dispatch; budget-exhaustion acceptance and mid-execution cancellation fixtures remain pending.

## Changed paths

- `modules/runtime/src/main/scala/raider/runtime/lineage/replay/PartialRerun.scala` — selection planner, explicit-input contract, backend request/result, invocation correlation check.
- `modules/runtime/src/test/scala/raider/runtime/lineage/replay/PartialRerunSpec.scala` — LIN7-01 phase subtree/sibling proof, LIN7-02 invalid selection, LIN7-03 external dependency refusal, round-budget pre-dispatch refusal.
- `docs/work/records/LIN-07.md` — this record.

No schema, GraphHash, build, gate registry, shared contract, or task-card changes.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN7-01 / LIN7-PHASE | passed: selected phase and child only; sibling phase and child remain unchanged and excluded (node-vector diff) | `PartialRerunSpec`, 1 test |
| LIN7-02 / LIN7-INVALID | passed: missing phase and non-phase selection refused | `PartialRerunSpec`, 1 test |
| LIN7-03 / LIN7-INPUT | passed: external dependency without explicit resolved value refused | `PartialRerunSpec`, 1 test |
| LIN7-BUDGET | partial: non-positive `maxRounds` refused before backend dispatch; no exhaustion-during-work case | `PartialRerunSpec`, 1 test |
| LIN7-CANCEL | pending: no mid-partial-rerun cancellation fixture yet | — |
| Invocation correlation | implementation present; no behavioral fixture yet | — |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `sbt --batch 'raiderRuntime/testOnly raider.runtime.lineage.replay.ReplayVerifierSpec'` | 0; 14 passed | terminal output; existing LIN5-03 tests |
| `sbt --batch 'raiderRuntime/testOnly raider.runtime.lineage.replay.PartialRerunSpec'` | 0; 4 passed | terminal output |
| `sbt --batch scalafmtAll` | 0 | terminal output; only new sources formatted (`git status` showed only intended untracked files) |
| `make quality-changed` | 0; classified `contracts`, `static`, `unit` | classification output |
| `make quality-changed QUALITY_EXECUTE=1` | timed out/terminated by harness at 120 seconds; not passed | no manifest found |
| `make quality-changed QUALITY_EXECUTE=1` (retry) | timed out/terminated by harness at 900 seconds; not passed | no manifest found |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0; all five gate entries passed, timeout false | `artifacts/quality/20261020-080251-6346-0001/manifest.json`; SHA-256 `3d02952065f321aaef074e266a3c6e89914d5930a48997a05984eb65595f1c68` |
| `make quality-changed QUALITY_EXECUTE=1` (successful retry) | 0; all five gate entries passed, timeout false | `artifacts/quality/20261020-081106-44206-0001/manifest.json`; SHA-256 `833327b8315b99fc0be33e90771bbc6c0e9ac6d8e62e77d1b839ea6e3cc78c30` |
| `python3 scripts/quality/quality.py verify artifacts/quality/20261020-081106-44206-0001/manifest.json --require unit` | 0; fingerprint fresh | stdout: `[verify] OK: manifest … satisfies ['unit'] (policy/toolchain/logs hashes checked, fingerprint fresh)` |
| `python3 scripts/quality/quality.py verify artifacts/quality/20261020-080251-6346-0001/manifest.json --require runtime` | 0; fingerprint fresh | stdout: `[verify] OK: manifest … satisfies ['runtime'] (policy/toolchain/logs hashes checked, fingerprint fresh)` |

## Limitations / blockers

The full LIN-07 contract is not complete: no mid-rerun cancellation acceptance, no actual backend budget-exhaustion acceptance, and no CLI integration/pointer-resolution/graph-diff proof. The first two quality-changed attempts exceeded the harness timeout without emitting manifests; later runs passed and were verified. LIN5-03 record evidence is in `docs/work/records/W8-W9-INTEGRATION.md` §Self-executed; it covers flushed-prefix graph recovery, not selected re-execution.

## Review and next task

Not independently reviewed. Continue LIN-07 to add backend budget/cancellation behavioral fixtures and wire the selected request to live rerun while preserving pointer-resolved input and invocation correlation; then rerun required gates. Lane branch commit is recorded in the final handoff.

Product-input hash and operational-record hash differ under the reviewed policy. Lane evidence applies only to this lane snapshot; integrated-candidate checks are still required.
