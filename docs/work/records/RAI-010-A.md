<!-- Work record. Original in Russian; key results in English below. -->

# RAI-010-A: work record (native agent loop slice)

Status: done (scripted/fixture slice; honest obligations listed). Date: 2026-10-03.
Baseline: root checkout без коммитов; стартовый gate 6/6 full-tree
artifacts/quality/20261003-212034 (fingerprint до изменений этой карточки).

## Resulting behavior

`raider.runtime.loop.AgentLoop` — единая точка АГЕНТНОГО запуска
(runtime-contracts §8), не второй runtime:

- freeze-точка = конструктор: backend, ToolRegistry, model, Admission, root,
  attemptEstimate; каждый `runText(messages, ceilings, maxRounds)` связывает
  разговор с потолками; валидация конфигурации (пустые сообщения, maxRounds<1,
  maxAttempts<1, maxRounds>maxAttempts) — typed Configuration ДО любого вызова;
- раунд = один admission attempt (Model slot + BudgetLedger reserve в порядке
  §6) вокруг одного `backend.stream`; исполняются ТОЛЬКО `ToolCallReady`
  (`ToolCallDelta` никогда не исполняется, §8);
- unknown tool / invalid arguments JSON → structured feedback модели
  (`{"error":"unknown_tool"|"invalid_arguments"}`, роль "tool"), loop
  продолжается, ограничен maxRounds — не silent ignore;
- дефинитивная ошибка инструмента (или non-JSON output инструмента) роняет
  весь run с кодом инструмента (RA-TOOLFAIL и т.п.); mutational tool calls
  НЕ ретраятся после неоднозначного/неудачного результата;
- инструменты dispatch через Tool slot (`withSlotFree`), строго последовательно
  в порядке emission (§6 default serialization), с per-tool timeoutMs →
  typed DeadlineExceeded, слот освобождается;
- результаты инструментов — RequestMessage("tool",
  `{"tool_call_id":…,"output":…}`) с zio-json экранированием;
- исчерпание maxRounds/admission.maxAttempts → typed LocalBudgetExceeded;
- финальный текст валидируется: непустой + marker-free
  (`AgentLoop.InternalMarker` — зарезервированный сентинел, loop его нигде
  не инжектит; наличие в ответе = OutputValidation);
- cancellation-бухгалтерия целиком в Admission-брейкетах RAI-009: cancel до
  permit'а — ничего; после permit'а до dispatch — release+refund; interrupt
  DURING model/tool dispatch — Uncertain, charge kept (при Priced estimate
  виден в uncertainTotal, Unknown — честно tracked unpriced);
- с пустым реестром loop вырождается В ТОЧНОСТИ в Runner.runText
  (fixture-равенство текста) — Runner.runText НЕ тронут, REPL-база цела.

Testkit (RAI-006 territory, минимальное документированное расширение):
`ScriptedSequenceBackend` — k-й `stream()` играет k-й скрипт, все запросы
записываются, `fixtureMode=true`, лишний вызов = typed StreamProtocol.

Попутная закалка (территория RAI-009.b, перенесена вперёд из-за флака под
новой параллельной нагрузкой): два sleep-упорядоченных теста
BudgetAdmissionSpec ("cancel while WAITING…", "cancel during dispatch…")
переведены на детерминированные `repeatUntil`-барьеры по
availableSlots/reservedActive (паттерн соседнего детерминированного теста).

## Changed paths

Новые:

- `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala`
- `modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopSpec.scala`
- `modules/testkit/src/main/scala/raider/testkit/ScriptedSequenceBackend.scala`
- `modules/testkit/src/test/scala/raider/testkit/ScriptedSequenceBackendSpec.scala`

Изменённые:

- `build.sbt`: raiderRuntime `.dependsOn(raiderTestkit % Test)` — loop-фикстуры
  гоняют scripted-бэкенды; Test-scope, testkit не попадает в production
  classpath (координаторская правка, без новых внешних deps).
- `modules/runtime/src/test/scala/raider/runtime/budget/BudgetAdmissionSpec.scala`:
  2 теста детерминированы (см. выше), семантика утверждений не менялась.

sha256 (финальные исходники):

```
96d681fe…  build.sbt
c2138bdf…  modules/testkit/src/main/scala/raider/testkit/ScriptedSequenceBackend.scala
29e127c6…  modules/testkit/src/test/scala/raider/testkit/ScriptedSequenceBackendSpec.scala
94be0092…  modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala
1b456512…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopSpec.scala
49e1a6b1…  modules/runtime/src/test/scala/raider/runtime/budget/BudgetAdmissionSpec.scala
```

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LOOP-01 text→tool→result→final (scripted, tool_call_id matched, distinct attempts) | pass | AgentLoopSpec LOOP-01 suite |
| LOOP-01 два call'а одного раунда — строго последовательно, оба matched | pass | AgentLoopSpec |
| LOOP-01 вырождение: пустой реестр == Runner.runText | pass (равенство текста, 1 request) | AgentLoopSpec |
| LOOP-02 maxRounds>maxAttempts → RA-CFG ДО любого вызова (requests пуст) | pass | AgentLoopSpec |
| LOOP-02 maxRounds исчерпан с незакрытыми tool calls → RA-LBUDGET | pass | AgentLoopSpec |
| LOOP-02 admission maxAttempts исчерпан на root → RA-LBUDGET, backend не тронут | pass | AgentLoopSpec |
| LOOP-02 unknown tool / invalid args → feedback, инструмент не invoked | pass | AgentLoopSpec |
| LOOP-02 provider Failed → RA-RATE; без Finished → RA-STREAM; пустой/маркированный финал → RA-OUT | pass | AgentLoopSpec |
| LOOP-03 tool failure → Failed с кодом инструмента (RA-TOOLFAIL), без ретрая | pass | AgentLoopSpec |
| LOOP-03 non-JSON output инструмента → RA-TOOLFAIL | pass | AgentLoopSpec |
| LOOP-03 tool timeout → RA-DEADLINE, слот освобождён | pass | AgentLoopSpec |
| LOOP-03 cancelRoot до dispatch → RA-CANCEL, ноль зарядки | pass | AgentLoopSpec |
| LOOP-03 interrupt DURING model dispatch (Priced 250µ$) → uncertainTotal=250µ$, charge kept | pass | AgentLoopSpec |
| LOOP-03 interrupt DURING tool dispatch → модельная часть rounds settled, слоты без утечек | pass | AgentLoopSpec |
| Стабильность loop-спеки 3× | 18/18, 18/18, 18/18 (exit 0 ×3) | /tmp/loop-run-{1,2,3}.log (прогоны), консоль |
| Полный sbt test 3× | 110 тестов (core 10+13+5, dsl 19, testkit 6+6? см. разбивку ниже), exit 0 ×3 | /tmp/full-test-A2*.log |
| Testkit-спека | 5/5 (3 ScriptedModelBackend + 2 ScriptedSequenceBackend) | raiderTestkit/test |

Разбивка полного прогона (7 suite-блоков): 10 (core Config) + 13 (core
contracts) + 5 (core Tool) + 19 (runtime jobs/scopes) + 6 (runtime budget) +
6 (testkit) + 51 (runtime, вкл. 18 loop) — суммарно 110, exit 0.

## Commands

| argv / command | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderTestkit/test"` | 0 | /tmp/tk-test.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/test"` | 0 (после фиксов) | /tmp/rt-test7.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopSpec"` ×3 | 0,0,0 | /tmp/loop-run-{1..3}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×3 | 0,0,0 | /tmp/full-test-A2{,-run2,-run3}.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6 passed) | artifacts/quality/20261003-214145/manifest.json |

## Limitations / obligations

- Child delegation (LOOP-01 в полной карточке) — НЕ в этом slice: delegate/
  await_agent tool'ы поверх JobManager (§11) — отдельная карточка; здесь
  только text/tool-раунды, как разрешено скоупом фазы.
- Параллельный fan-out `concurrentSafe` tool calls — не реализован (всегда
  последовательно); честно задокументировано в коде.
- Steering на безопасной границе (§8 шаг 6) — obligation (send = RA-CAP в
  REPL, в loop API нет steering-входа).
- Live pricing: attemptEstimate по умолчанию Unknown (unpriced tracked, не
  free); цены появятся с provider-адаптерами (RAI-011/012).
- Journal/событийная запись раундов (§8 шаг 5/8) — obligation (RAI-027).
- Loop переиспользует admission-семантику attempts как «все dispatch root»
  (model+tool) — счётчики RAI-009 не менялись; maxRounds отдельно ограничивает
  модельные раунды.

## Review and next task

Независимый review не проводился (координаторская сессия). Следующая фаза
критического пути: Phase B — REPL scout/worker на AgentLoop (без tools
поведение идентично), затем RAI-002 remainder.
