<!-- Work record. Original in Russian; key results in English below. -->

# RAI-006 (MOCK-02 slice): work record

Status: done. Date: 2026-10-10 (UTC). Baseline: working tree (parallel lane;
git status recorded below). Prior slices: RAI-006-SLICE1 (MOCK-01), RAI-006-B
(MOCK-03 controllables), RAI-010-A (ScriptedSequenceBackend).

## Resulting behavior

Closing MOCK-02 gap: adversarial testkit cases are executable fixtures —
unexpected call / overread of a scripted sequence fail typed
RA-STREAM StreamProtocol (never a silent success); an invalid fixture
(negative token usage) is emitted as scripted and observable as invalid data
rather than normalized into success; output bound (maxOutputTokens) is
recorded verbatim by the recorder — the mock never inflates nor truncates it.
No production/testkit main-code change was needed: the frozen testkit
primitives already enforce the semantics; this slice adds the missing
executable adversarial fixtures proving it.

MOCK-01 (ordered event traces, scripted usage/price, determinism) and MOCK-03
(virtual time via TestClock, gated cancel/finish races, no real sleeps) were
verified-done in prior slices and re-run green here (11/11 testkit tests).

## Changed paths

- `modules/testkit/src/test/scala/raider/testkit/Mock02Spec.scala` (added; 4 tests)

No public API/schema changes; no new dependencies; allowed paths respected
(`modules/testkit/**` only).

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| MOCK-01 ordered traces + scripted usage/price | 3/3 ScriptedBackendSpec | raiderTestkit/test |
| MOCK-01 tool/tool-result rounds | 2/2 ScriptedSequenceBackendSpec | raiderTestkit/test |
| MOCK-02 unexpected call fails typed | passed (MOCK-02 suite) | Mock02Spec |
| MOCK-02 overread exhausts typed | passed (MOCK-02 suite) | Mock02Spec |
| MOCK-02 invalid fixture not success | passed (MOCK-02 suite) | Mock02Spec |
| MOCK-02 output bound recorded verbatim | passed (MOCK-02 suite) | Mock02Spec |
| MOCK-03 gated primitives / no sleeps | 2/2 ControllableSpec | raiderTestkit/test |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| sbt --batch "raiderTestkit/test" | 0 (11 passed / 0 failed) | console |
| make quality-changed | see below | artifacts/quality/<ts> |
| make quality-changed QUALITY_EXECUTE=1 | see below | artifacts/quality/<ts> |

## Limitations / blockers

- Parallel working tree contains unrelated lanes (plugins/examples/cli); gates
  run on the changed set which may include other lanes' files — recorded as-is.
- Streaming backpressure/partial frames remain transport-fixture territory
  (RAI-011/012), per RAI-006-SLICE1 limitation.

## Review and next task

Review: pending. Next: RAI-007 remainder / next ready M0 card.
