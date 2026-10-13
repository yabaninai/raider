<!-- Work record. Original in Russian; key results in English below. -->

# RAI-006-B: work record (controllable primitives + deterministic cancel-mid-dispatch)

Status: done. Date: 2026-10-03. Baseline: previous last-passing gate
artifacts/quality/20261003-215025 (RAI-002-REMAINDER).

## Resulting behavior

RAI-006.b slice (MOCK-03): controllable determinism without real
`sleep` calls.

Testkit primitives (`raider.testkit.Controllable`), with the record-then-park
contract: observing that a call occurred (requests/started) ⇒ the consumer
fiber is at (or one microstep away from) the park point → interrupt / gate
opening / virtual clock advancement from the test fiber are deterministic:

- `GatedModelBackend(scripts*)` — the k-th `stream()` records the request and
  parks on gate k INSIDE dispatch until `open(k)`; an extra call = typed
  StreamProtocol; `openAll` for teardown;
- `GatedTool` — invoke records arguments and parks on a gate until `open`;
  `started` observes that the call occurred.

Acceptance on top of the Phase A loop (`AgentLoopDeterminismSpec`, TestClock
is ZIO Test's default, `live{}` is NOT used — not a single real sleep):

1. cancel DURING model dispatch (Priced 400µ$) → uncertainTotal=400µ$, charge
   kept; slot free; opening the gate AFTER the fiber dies does not change usage
   (late settlement does not rewrite the outcome);
2. cancel DURING tool dispatch → round-1 model settled, tool-free — nothing
   zeroed; both slots free; late open does not change usage;
3. virtual deadline: tool timeout 1000ms fires exactly on
   `TestClock.adjust(1001ms)` → RA-DEADLINE, slot released (the tool's clock
   sleep never expires on its own);
4. finish-vs-cancel: gate opened before interrupt → clean success, accounting
   settled; interrupting a completed fiber does not fabricate cancellation.

## Changed paths

```
4a5da06b…  modules/testkit/src/main/scala/raider/testkit/Controllable.scala
5397df57…  modules/testkit/src/test/scala/raider/testkit/ControllableSpec.scala
05bc7b40…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDeterminismSpec.scala
```

Product code (main) was NOT changed — only testkit and tests.

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| MOCK-03 primitives (gated backend/tool) | 7/7 testkit (5 existing + 2 new) | raiderTestkit/test |
| Deterministic cancel-mid-dispatch on loop ×3 | 4/4, exit 0 ×3 | /tmp/det-run-{1,2,3}.log |
| Full sbt test | 127 tests, 0 failed, exit 0 | /tmp/full-test-D.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-215609/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderTestkit/test"` | 0 (7/7) | /tmp/tk-D.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopDeterminismSpec"` ×3 | 0,0,0 | /tmp/det-run-{1..3}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (127) | /tmp/full-test-D.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-215609/ |

## Limitations / obligations

- Virtual backoff: the codebase has NO auto-retry/backoff policy (the loop
  does not retry mutational calls — by design), so there is nothing to test;
  when a retry card introduces it, add the fixture using this same primitive.
- Admission-wait deadline at loop level is unavailable (`attempt` passes None
  to `withSlot`) — the virtual deadline is covered at the tool-timeout surface
  and at Admission level (BudgetAdmissionSpec, real time). The wait-deadline
  surface parameter is a separate decision (not added without a card).

## Review and next task

No independent review was conducted (coordinator session). Next phase:
RAI-009.b — stress interrupt-takers, late-settlement/idempotence fixtures.
