<!-- Work record. Original in Russian; key results in English below. -->

# RAI-006-B: work record (controllable primitives + deterministic cancel-mid-dispatch)

Status: done. Date: 2026-10-03. Baseline: предыдущий last-passing gate
artifacts/quality/20261003-215025 (RAI-002-REMAINDER).

## Resulting behavior

RAI-006.b slice (MOCK-03): управляемая детерминированность без реальных
sleep'ов.

Testkit-примитивы (`raider.testkit.Controllable`), контракт record-then-park:
наблюдение факта вызова (requests/started) ⇒ consumer-волокно находится в
(или в одном микрошаге от) точки парковки → interrupt / открытие гейта /
сдвиг виртуальных часов из тест-волокна — детерминированы:

- `GatedModelBackend(scripts*)` — k-й stream() записывает запрос и паркуется
  на гейте k ВНУТРИ dispatch до `open(k)`; лишний вызов = typed
  StreamProtocol; `openAll` для teardown;
- `GatedTool` — invoke записывает аргументы и паркуется на гейте до `open`;
  `started` — наблюдение факта вызова.

Приёмка поверх цикла Phase A (`AgentLoopDeterminismSpec`, TestClock по
умолчанию ZIO Test, `live{}` НЕ используется — ни одного реального sleep):

1. cancel DURING model dispatch (Priced 400µ$) → uncertainTotal=400µ$, charge
   kept; слот свободен; открытие гейта ПОСЛЕ смерти волокна не меняет usage
   (позднее settlement не переписывает outcome);
2. cancel DURING tool dispatch → раунд-1 модели settled, tool-free — ничего
   не zeroed; оба слота свободны; поздний open не меняет usage;
3. виртуальный дедлайн: tool timeout 1000ms срабатывает ровно по
   `TestClock.adjust(1001ms)` → RA-DEADLINE, слот освобождён (часовой sleep
   инструмента никогда не истекает сам);
4. finish-vs-cancel: гейт открыт до interrupt → чистый успех, accounting
   settled; interrupt завершённого волокна не фабрикует отмену.

## Changed paths

```
4a5da06b…  modules/testkit/src/main/scala/raider/testkit/Controllable.scala
5397df57…  modules/testkit/src/test/scala/raider/testkit/ControllableSpec.scala
05bc7b40…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDeterminismSpec.scala
```

Продуктовый код (main) НЕ менялся — только testkit и тесты.

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| MOCK-03-примитивы (gated backend/tool) | 7/7 testkit (5 прежних + 2 новых) | raiderTestkit/test |
| Детерминированный cancel-mid-dispatch поверх loop ×3 | 4/4, exit 0 ×3 | /tmp/det-run-{1,2,3}.log |
| Полный sbt test | 127 тестов, 0 failed, exit 0 | /tmp/full-test-D.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-215609/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderTestkit/test"` | 0 (7/7) | /tmp/tk-D.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopDeterminismSpec"` ×3 | 0,0,0 | /tmp/det-run-{1..3}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (127) | /tmp/full-test-D.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-215609/ |

## Limitations / obligations

- Виртуальный backoff: в кодовой базе НЕТ auto-retry/backoff-политики (loop
  не ретраит mutational calls — по дизайну), тестировать нечего; появится с
  retry-карточкой — тогда fixture добавляется на этот же примитив.
- Admission-wait дедлайн на уровне loop недоступен (attempt передаёт None в
  withSlot) — виртуальный дедлайн покрыт на tool-timeout поверхности и на
  уровне Admission (BudgetAdmissionSpec, реальное время). Surface-параметр
  дедлайна ожидания — отдельное решение (не добавлял без карточки).

## Review and next task

Независимый review не проводился (координаторская сессия). Следующая фаза:
RAI-009.b — stress interrupt-takers, late-settlement/idempotence fixtures.
