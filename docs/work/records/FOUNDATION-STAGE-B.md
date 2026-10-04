<!-- Work record. Original in Russian; key results in English below. -->

# FOUNDATION-STAGE-B + RAI-002 slice 1: work record

Status: done (review pending). Date: 2026-10-03 (UTC). Baseline: продолжает
FOUNDATION-STAGE-A; дерево — untracked (commits по-прежнему нет по правилу).

## Resulting behavior

1. Повторное независимое ревью F0 (round 2, read-only explore-агент): 13/13 findings
   round-1 подтверждены исправленными. Его узкие замечания закрыты в этой сессии:
   bootstrap.sh реально исполнен (inventory в artifacts/toolchain/), fault-injection
   self-tests архивированы, MetadataObserved добавлен, error families доведены до
   полных 18 §14, Usage.plus не смешивает валюты молча, Task запечатан (sealed),
   negative gate требует именно type-mismatch, OAI-05 grep привязан к сценарию.
2. RAI-003 stage B: sbt установлен (brew; runner 2.0.10), project/build.properties
   pin sbt 1.11.4; канонический build.sbt multi-project (raider-core/runtime/dsl/
   testkit), Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0, -Werror; .scalafmt.conf;
   Makefile (bootstrap/compile/build-script/spike-*). `sbt --batch compile` exit 0.
   Dotc-путь scripts/build/compile.sh продолжает работать (exit 0, 9 файлов).
3. RAI-002 slice 1: канонические JSON-кодеки (ids → строки; Usage; ModelEvent с
   `"type"`-дискриминатором; RaiderError flat-wire c отказом на unknown code;
   VersionedEnvelope с проверкой schemaVersion ДО payload; RunContext с
   валидацией обязательных полей) + ContractsSpec (main-based, exit 0/1):
   26 проверок — roundtrip всех событий/ошибок/конвертов, негативные cases
   (unknown error code, malformed JSON, unknown schemaVersion, пустой system,
   attempt<1). ALL PASS (26 проверок по архивированному логу гейта), exit 0.

## Changed paths

- modules/core/src/main/scala/raider/core/{backend,codecs,schemas}.scala (обновлён/новые)
- modules/core/src/test/scala/raider/core/ContractsSpec.scala (новый)
- modules/{runtime,dsl,testkit}/src/main/scala/raider/{runtime,dsl,testkit}/ (скелеты)
- build.sbt, project/build.properties, .scalafmt.conf, Makefile (новые)
- scripts/build/compile.sh (+zio-json/magnolia в CP)
- scripts/bootstrap.sh (реально исполнен), docs/work/{board.md, records/*},
  docs/prompts/opencode-autonomous-scala.txt (коррекция состояния)

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Re-review F0 | 13/13 fixed; narrow-замечания закрыты | artifacts/f0-final-rereview/{bootstrap,build,selftest-*,suite-*}.log |
| sbt compile | exit 0 | stdout сессии (sbt --batch compile) |
| ContractsSpec | ALL PASS (27), exit 0 | sbt Test/runMain вывод |
| Dual build | dotc-путь exit 0 (9 файлов) | artifacts/build.log |
| План-пакет | validate.py exit 0 | artifacts/plan-review/report.json |

## Limitations / blockers

- JDK-история неоднородна: PATH java 21.0.12.1, sbt использует собственную JVM
  (логи гейтов), spikes шли на 20.0.1. Единая JDK-политика (21 target + JAVA_HOME в
  гейтах) — итерация 2 RAI-004.
- scalafmt/scalafix plugins не подключены (static gate = compile с -Werror);
  форматирование — RAI-004+ доработка после выбора версий.
- RAI-002 остаток: DSL composition fixtures (all/fork — с RAI-017), bundle/context
  полные схемы, REPL-portion контрактов — следующие срезы.
- Runtime/dsl/testkit модули пустые (скелеты) — наполнение по карточкам F1.

## Review and next task

Review: pending (fresh explore-reviewer по этому diff). Next: RAI-004 quality
runner MVP (registry/classifier/evidence + Makefile quality-* targets) → затем F1.
