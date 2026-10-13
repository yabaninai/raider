<!-- Work record. Original in Russian; key results in English below. -->

# RAI-006 (slice 1): work record

Status: done (review pending; the card remains open — tools-scripting, virtual time,
controllable barriers are the next slices). Date: 2026-10-03 (UTC).

## Resulting behavior

raider-testkit: first product Scala module on top of frozen contracts —
`ScriptedModelBackend` (deterministic, no LLM/network/clock): exact sequence of
normalized ModelEvents, recording of all requests (Ref), first-class
`fixtureMode` marker, `failing(error)` factory for typed Failed. ZIO Test is
connected (zio-test/zio-test-sbt 2.1.26, ZIOFramework). Spec: 3 tests (exact
sequence+request recording; determinism across two runs; typed Failed) — 3
passed / 0 failed.

Quality: registry extended with the `sbt-test` gate (unit/fast); dotc path
narrowed to src/main + contract fixtures (test gates run through sbt). Canonical
run: `make quality-changed QUALITY_EXECUTE=1` → passed 6/6
(artifacts/quality/20261003-030546/), `verify --require fast` → fingerprint
fresh OK.

## Commands (actual)

| argv | Exit |
| --- | --- |
| sbt --batch "raiderTestkit/test" | 0 (3 passed / 0 failed) |
| make quality-changed QUALITY_EXECUTE=1 | 0 (6/6 gates) |
| python3 scripts/quality/quality.py verify <last> --require fast | 0 (fresh) |

## Limitations

- Virtual time (loop TestClock gates), scripted tools, promises/barriers —
  next RAI-006 slices along with RAI-010 loop.
- ScriptedBackend does not emulate streaming backpressure/partial frames —
  that belongs to transport suites RAI-011/012 fixtures.

## Review and next task

Review: pending (along with the Stage-B fixed-round — one diff). Next: RAI-007
job registry + atomic lifecycle (runtime, ZIO STM) on Task/Job contracts.
