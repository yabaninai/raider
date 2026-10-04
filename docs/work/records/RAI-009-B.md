<!-- Work record. Original in Russian; key results in English below. -->

# RAI-009-B: work record (stress interrupt-takers + late-settlement/idempotence)

Status: done. Date: 2026-10-03. Baseline: предыдущий last-passing gate
artifacts/quality/20261003-215609 (RAI-006-B).

## Resulting behavior

RAI-009.b (review fixes из RAI-009): закрывающие фикстуры над ЗАМОРОЖЕННЫМ
кодом RAI-009 (production main НЕ менялся — только тесты):

- стресс interrupt-takers: 50 конкурентных waiters паркуются в fair-очереди
  одного слота (llm=1) и прерываются разом — детерминированные барьеры
  (`queued == 51` → interrupt → `queued == 1`): ни один waiter не потратил
  денег, слот holder'а не потерян, после interrupt holder'а — ровно исходный
  пул слотов (нет фантомных permit'ов), Uncertain удержан (500µ$);
- смешанные исходы под конкурентностью (6 settle + 6 interrupt-in-dispatch,
  llm=8, attempts=64): observed ровно 6×40, uncertain ровно 6×100 (charge
  kept), reservedActive=0, violations=0;
- late-settlement: settle-after-settle / release-after-settle /
  markUncertain-after-settle / settle-after-release / settle-after-uncertain —
  каждый поздний вариант = late-наблюдение, НИКОГДА не переписывает исход
  (observed не удваивается, reserved не уходит в минус, uncertain не
  начисляется второй раз); settle неизвестной резервации = typed RA-INP;
  unpriced-резервация settle-ится один раз, последующие терминальные
  операции = late;
- idempotence: cancelRoot/clearCancellation идемпотентны, после снятия
  отмены dispatch работает, accounting точный.

По пути зафиксирован учебный факт (попал в тестовый комментарий):
maxAttempts считает КАЖДЫЙ dispatch root'а (model+tool), поэтому стресс-тест
с 12 dispatch'ами требует attempts>=12 — иначе лишние получают RA-LBUDGET до
парковки (это поведение Admission, не дефект).

## Changed paths

```
17df7044…  modules/runtime/src/test/scala/raider/runtime/budget/LateSettlementStressSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Стресс 50 interrupt-takers (детерминированный) | pass | LateSettlementStressSpec |
| Смешанные settle/Uncertain исходы точны | pass | LateSettlementStressSpec |
| Late-settlement/idempotence фикстуры (5 шт) | pass | LateSettlementStressSpec |
| Стабильность ×3 | 7/7, exit 0 ×3 | /tmp/e-run-{1,2,3}.log |
| Полный sbt test | 134 теста, 0 failed, exit 0 | /tmp/full-test-E.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-220233/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.budget.LateSettlementStressSpec"` ×3 | 0,0,0 | /tmp/e-run-{1..3}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (134) | /tmp/full-test-E.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-220233/ |

## Limitations / obligations

- Fair-admission МЕЖДУ РАЗНЫМИ roots (RAI-009 obligation) остаётся на
  реальный fanout (RAI-010/017 integration) — здесь стресс внутри одного root.
- performance-профиль — прежний milestone obligation (нет в registry без
  отдельной карточки).

## Review and next task

Независимый review не проводился (координаторская сессия). Критический путь
A–E закрыт; далее финальный полный прогон и (stretch) Phase F.
