# RAI-009-B: Work Record (Stress Interrupt-Takers + Late-Settlement/Idempotence)

Status: done. Date: 2026-10-03. Baseline: previous last-passing gate
artifacts/quality/20261003-215609 (RAI-006-B).

## Resulting behavior

RAI-009.b (review fixes from RAI-009): closing fixtures on top of the FROZEN
RAI-009 code (production main was NOT changed — tests only):

- stress interrupt-takers: 50 concurrent waiters park in the fair queue for one
  slot (llm=1) and are interrupted together — deterministic barriers
  (`queued == 51` → interrupt → `queued == 1`): no waiter spent money, the
  holder's slot was not lost, after interrupting the holder the slot pool
  returned exactly to its original size (no phantom permits), Uncertain charge
  retained (500µ$);
- mixed outcomes under concurrency (6 settle + 6 interrupt-in-dispatch, llm=8,
  attempts=64): observed exactly 6×40, uncertain exactly 6×100 (charge kept),
  reservedActive=0, violations=0;
- late-settlement: settle-after-settle / release-after-settle /
  markUncertain-after-settle / settle-after-release / settle-after-uncertain —
  each late operation is a late observation and NEVER rewrites the outcome
  (observed is not doubled, reserved does not go negative, uncertain is not
  charged twice); settling an unknown reservation gives typed RA-INP; an
  unpriced reservation settles once, and subsequent terminal operations are late;
- idempotence: cancelRoot/clearCancellation are idempotent, dispatch works
  after cancellation is cleared, and accounting is exact.

An implementation fact was recorded along the way (in a test comment):
maxAttempts counts EVERY root dispatch (model+tool), so a stress test with 12
dispatches requires attempts>=12 — otherwise extra attempts get RA-LBUDGET
before parking (this is Admission behavior, not a defect).

## Changed paths

```
17df7044…  modules/runtime/src/test/scala/raider/runtime/budget/LateSettlementStressSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Stress 50 interrupt-takers (deterministic) | pass | LateSettlementStressSpec |
| Mixed settle/Uncertain outcomes are exact | pass | LateSettlementStressSpec |
| Late-settlement/idempotence fixtures (5) | pass | LateSettlementStressSpec |
| Stability ×3 | 7/7, exit 0 ×3 | /tmp/e-run-{1,2,3}.log |
| Full sbt test | 134 tests, 0 failed, exit 0 | /tmp/full-test-E.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-220233/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.budget.LateSettlementStressSpec"` ×3 | 0,0,0 | /tmp/e-run-{1..3}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (134) | /tmp/full-test-E.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-220233/ |

## Limitations / obligations

- Fair admission BETWEEN DIFFERENT roots (RAI-009 obligation) remains for real
  fanout (RAI-010/017 integration) — this stress test is within one root.
- The performance profile remains the existing milestone obligation (not in
  the registry without a separate card).

## Review and next task

Independent review was not conducted (coordinator session). Critical path A–E
is closed; next is the final full run and (stretch) Phase F.
