<!-- Work record. Original in Russian; key results in English below. -->

# RAI-017.a: Typed composition (all/batch/batchCollect) — record

Дата: 2026-10-03. Writer: root. Deps: RAI-002 slice1 (codecs, accepted ранее),
RAI-008 (scopes, accepted), RAI-009 (admission, accepted в этой сессии) — все
готовы; старт от FROZEN Program/Task (modules/core/program.scala, task.scala —
не менялись).

## Deliverable

Production (1 файл): `modules/dsl/src/main/scala/raider/dsl/composition/Composition.scala`

- `all(Task, Task): Task[(A,B)]` — один interpreter (Task.zio), zipPar = parallel
  fail-fast (первый отказ прерывает sibling — COMP-02);
- `all(Program, Program): Program[I,(A,B)]` — гетерогенная arity-2 композиция с
  tuple-выходом; construction ленив, ничего не диспетчирует (API-03);
- `batch(inputs, parallelism)(run): Task[List[R]]` — ordered bounded batch:
  результаты в порядке ВХОДА независимо от порядка завершения; parallelism
  ограничивает конкурентность; пустой batch не диспетчирует ничего;
  parallelism < 1 → typed InputValidation (без silent clamp);
- `batch.collect(...): Task[List[BatchOutcome[R]]]` — domain failures становятся
  явными `Failed`-outcome; внешнее прерывание и дефекты — re-raise (НЕ
  проглатываются), по §5 (проверено тестом interrupt);
- `BatchOutcome` = Succeeded | Failed (typed).

Build: `build.sbt` — raiderDsl получил `testSettings` (zio-test; build.sbt —
файл coordinator'а).

Tests: `modules/dsl/src/test/scala/raider/dsl/composition/CompositionSpec.scala`
— 10 тестов: typed tuple; Program-композиция; andThen через frozen контракт;
fail-fast с прерыванием sibling (бounded 5s, sibling не завершился); typed
отказ parallelism=0 (RA-INP); пустой batch без диспетчера; input-порядок
результатов при обратном порядке завершения; peak concurrency ≤ parallelism;
domain→Outcome (401 → Failed, порядок сохранён); внешняя interruption
re-raised как interruption.

Fixture: `contracts/compile/positive/Composition.scala` — позитивная компиляция
(all arity-2 Programs/Tasks, andThen-цепочка, bounded batch) через pinned dotc
(подхватывается glob'ом compile.sh автоматически).

## COMP-03 (maxLLM=1 без deadlock) — cross-reference

Покрыт RAI-009 BUD-03 тестом (JobManager+Admission, manager.await без permit) —
запись в docs/work/records/RAI-009.md; дубликат теста здесь не создавался.
Scope/fork/join — RAI-008 SCOPE-01..03 (RaiderScope). Composition-слой Ref не
вводит второй семантики.

## Проверки (exact)

- `sbt --batch "raiderDsl/test"` → exit 0, **10 passed / 0 failed**.
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, **6/6 gates**
  (sbt-test прогнал 43+ теста всех модулей: core contracts + runtime 33 +
  dsl 10 + testkit 3), manifest artifacts/quality/20261003-140237/.
- Negative compile-кейс «несовместимый andThen не компилируется» — существующий
  contracts/compile/negative/AndThenMismatch.scala, gate compile-fixtures
  проверяет его в этом же прогоне (exit 0).

## Исправленные в ходе карточки дефекты тестов

1. Eager-эффекты при КОНСТРУИРОВАНИИ Task (`Task { sideEffect; zio }` —
   sideEffect выполняется при постройке). Зафиксирован тестом peak-concurrency:
   эффекты обязаны быть внутри ZIO. Сам продакшн-код композиции ленив по
   построению (API-03) — подтверждено.
2. `fiber.await` уже возвращает Exit — лишний `.exit` ломал assert.

## Limitations (честно)

- fork/join DSL-обёртки (COMP-03 scope-часть) — в runtime-слое (RaiderScope),
  DSL-фасад запуска — RAI-018 (не эта карточка).
- batch с parallelism-потолком не интегрирован с Admission-слотами (это
  композиция Задач без model-вызовов); связка batch→model-permit — RAI-010 loop.
