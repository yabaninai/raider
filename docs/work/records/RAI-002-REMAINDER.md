<!-- Work record. Original in Russian; key results in English below. -->

# RAI-002-REMAINDER: work record (bundle/result schemas + launch capability)

Status: done (schema/codec slice; CLI/bundle-loading surfaces остаются карточками
RAI-022/024). Date: 2026-10-03. Baseline: предыдущий last-passing gate
artifacts/quality/20261003-214446 (REPL-LOOP).

## Resulting behavior

RAI-002 remainder slice — versioned JSON-схемы в core (данные, ничего не
диспетчат):

- `raider.core.bundle.BundleManifest` (+`BundleEntry`): манифест compiled
  bundle — schemaVersion/bundleId/entries(name, mainClass, version)/
  declaredTrustLevel; `validate` (unknown version, пустые id, нет entries,
  дубликаты, версия<1) и `entry(name)` — неизвестная запись = typed
  InputValidation со списком известных имён (никогда «какой-то» fallback);
- `raider.core.result.RunResult`: result.json-схема (ci-runtime §7) —
  ExecutionStatus (§4 terminal set), exitCode по СТАБИЛЬНОЙ карте
  `ExitCodes` (0/10/20/21/22/23/24/25/26/27/130/143, ci-runtime §8),
  taskVerdict (вердикт verifier'а — поля, где ответ модели мог бы выдать себя
  за вердикт, нет принципиально), checks (Passed/Failed/Errored/Skipped),
  artifacts (artifactId/sha256/mediaType/sizeBytes/path), output (codec+
  version+JSON), error (плоский wire-кодекс RaiderError), таймстемпы;
  validate: unknown exitCode, 64-hex sha256, sizeBytes>=0, непустые пути/
  таймстемпы, output JSON well-formed;
- `raider.core.launch`: `LaunchCapability` (явная capability для launch-
  сигнатур, устанавливается prelude/loader'ом — ядро не имеет глобального
  launch-хука; ReplSession в repl удовлетворяет контракт) +
  `DefinitionContext` (явный immutable snapshot зарегистрированных агентов/
  workflow; дубликаты/пустые имена = RA-INP; lookup неизвестного имени =
  structured error со списком известных, §11).

Канонические roundtrip-кодеки (zio-json, версии дискриминируются
schemaVersion-валидацией ДО payload — стиль существующего VersionedEnvelope).

## Changed paths

```
ca144fbd…  modules/core/src/main/scala/raider/core/bundle.scala
5926a0d8…  modules/core/src/main/scala/raider/core/result.scala
eaecc448…  modules/core/src/main/scala/raider/core/launch.scala
e48a58eb…  modules/core/src/test/scala/raider/core/RemainderSchemasSpec.scala
1a539ec5…  modules/core/src/test/scala/raider/core/ContractsSpec.scala (+9 проверок, 26→35)
```

Публичные схемы — НОВЫЕ типы; существующие codec'и/events/envelope не менялись
(26 прежних проверок ContractsSpec остаются и зелёные).

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| RemainderSchemasSpec (bundle 3 + result 4 + launch 3) | 10/10 pass | raiderCore/test |
| ContractsSpec (канонический wire, расширено) | ALL PASS, 35 проверок, exit 0 | gate core-contracts-spec |
| Полный sbt test | 121 тест, 0 failed, exit 0 | /tmp/full-test-C.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-215025/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCore/test"` | 0 | /tmp/core-C4.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCore/Test/runMain raider.core.ContractsSpec"` | 0 (ALL PASS) | /tmp/contracts-C.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (121) | /tmp/full-test-C.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-215025/ |

## Limitations / obligations

- CLI загрузки bundle, jar-упаковка, checks.junit.xml/events.jsonl/summary.md
  репортеры — карточки RAI-022/024/025/026; здесь только core-схемы.
- `input`-схема (RAI-002 «JSON schemas input/context/...») — input-контекст
  остаётся в RunContext + OutputPayload slice; полная input-политика = RAI-030.
- LaunchCapability — контракт; concrete `using`-применение в новых
  launch-сигнатурах REPL/CLI накапливается в RAI-018/022.

## Review and next task

Независимый review не проводился (координаторская сессия). Следующая фаза:
RAI-006.b (testkit virtual time / controllable promises, MOCK-03).
