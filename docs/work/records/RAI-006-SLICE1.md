<!-- Work record. Original in Russian; key results in English below. -->

# RAI-006 (slice 1): work record

Status: done (review pending; карточка открыта — tools-scripting, virtual time,
controllable barriers — следующие срезы). Date: 2026-10-03 (UTC).

## Resulting behavior

raider-testkit: первый продуктовый Scala-модуль поверх замороженных контрактов —
`ScriptedModelBackend` (детерминированный, без LLM/сети/часов): точная
последовательность нормализованных ModelEvents, запись всех запросов (Ref),
первоклассный маркер `fixtureMode`, фабрика `failing(error)` для typed Failed.
ZIO Test подключён (zio-test/zio-test-sbt 2.1.26, ZIOFramework). Spec: 3 теста
(точная последовательность+запись запроса; детерминированность двух прогонов;
typed Failed) — 3 passed / 0 failed.

Quality: registry дополнен гейтом `sbt-test` (unit/fast); dotc-путь сужен до
src/main + contract fixtures (test-гейты идут через sbt). Канонический прогон:
`make quality-changed QUALITY_EXECUTE=1` → passed 6/6 (artifacts/quality/
20261003-030546/), `verify --require fast` → fingerprint fresh OK.

## Commands (actual)

| argv | Exit |
| --- | --- |
| sbt --batch "raiderTestkit/test" | 0 (3 passed / 0 failed) |
| make quality-changed QUALITY_EXECUTE=1 | 0 (6/6 gates) |
| python3 scripts/quality/quality.py verify <last> --require fast | 0 (fresh) |

## Limitations

- Виртуальное время (TestClock-гейты loop'а), scripted tools, promises/barriers —
  следующие срезы RAI-006 вместе с RAI-010 loop.
- ScriptedBackend не эмулирует streaming backpressure/частичные кадры — это
  компетенция transport-свен RAI-011/012 fixtures.

## Review and next task

Review: pending (вместе со Stage-B фикс-раундом — один diff). Next: RAI-007 job
registry + atomic lifecycle (runtime, ZIO STM) на Task/Job контрактах.
