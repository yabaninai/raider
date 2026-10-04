<!-- Work record. Original in Russian; key results in English below. -->

# DELEGATION (§11): work record — model-callable child delegation

Status: done (slice: delegate/await_agent/cancel_agent/list_agents поверх
JobManager+AgentLoop; полный §11 — send_agent/контекст-политики —
obligations). Date: 2026-10-04. Baseline: предыдущий last-passing gate
artifacts/quality/20261004-055145 (DSL-ERGO).

## Resulting behavior

`raider.runtime.loop.DelegationToolset` — model-callable инструменты
делегации над ТЕМ ЖЕ JobManager / root ledger / admission, что и workflow
fork (runtime-contracts §11, LOOP-01/W09):

- `delegate {"agent","input"}` — валидация имени по явному whitelist и
  счётчика против `limits.maxChildren`; создаёт child job СВОИМ root'ом
  (детский budget = бюджет родителя — один ledger, проверено: 6 dispatch'ов
  на одном root); возвращает `{"child_id","state"}` — НИКОГДА transcript;
  ошибки (unknown_agent/max_children/invalid_arguments) — structured
  feedback JSON циклу, не silent ignore;
- `await_agent {"child_id"}` — приостанавливает родителя, НЕ держит model
  slot (дети работают под тем же maxConcurrentLlm=1 — BUD-03 no-deadlock
  для кооперативных детей, доказано фикстурой maxLLM=1); держит ОДИН tool
  slot, поэтому构造 requires `maxConcurrentTools >= 2` (типизированная
  конфигурация, BUD-03 дисциплина);
- `cancel_agent` / `list_agents` — статусы детей через JobManager admin;
- дети НЕ могут повторно делегировать: детский реестр = базовый (§11 —
  отдельная policy, не случайность); `send_agent` сознательно отсутствует
  (steering = RA-CAP obligation);
- попутный фикс компилятора: nested member select в raw-интерполяции
  (`${call.call.value.toJson}`) триггерил E046 cyclic в Scala 3.9 —
  промежуточная валюта (AgentLoop.toolMessage).

**Реальная находка дизайна** (воспроизведена фикстурой, честно
задокументирована): при maxLLM=1 ЗАВИСШИЙ чайлд удерживает единственный
model slot, а родителю нужен СВОЙ slot, чтобы хотя бы вызвать cancel_agent
— циклическое ожидание. Митигации: (а) llm≥2 для сценариев с риском
зависания (фикстура cancel использует llm=2), (б) дедлайны чайлдов,
(в) out-of-band cancel-канал — ОБЯЗАТЕЛЬСТВО в board (§11 policy).

## Changed paths

```
11b30699…  modules/runtime/src/main/scala/raider/runtime/loop/DelegationToolset.scala
0e9bc310…  modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala (toolMessage: intermediate val против E046)
429d635b…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| delegate → await_agent → финал: один root ledger (tries=6: 3 model родителя + 1 model чайлда + 2 tool), maxLLM=1 без deadlock | pass (timeout-guard 15s не сработал) | AgentLoopDelegationSpec |
| unknown agent → feedback unknown_agent + известные имена, ничего не порождено | pass | AgentLoopDelegationSpec |
| max_children превышен → feedback max_children, второй spawn отказан | pass | AgentLoopDelegationSpec |
| await_agent неизвестного id → feedback unknown_child | pass | AgentLoopDelegationSpec |
| cancel_agent паркует-и-отменяет: ребёнок Cancelled, model slot освобождён | pass (llm=2, см. находку) | AgentLoopDelegationSpec |
| Конструкция: maxConcurrentTools<2 / shadow-имена → RA-CFG | pass | AgentLoopDelegationSpec |
| Нет рекурсивной делегации: детский реестр = базовый | pass (структурно) | AgentLoopDelegationSpec |
| Стабильность ×3 | 7/7, exit 0 ×3 | /tmp/g24.log, /tmp/dlg-run-{1,2}.log |
| Полный sbt test | 178 тестов, 0 failed, exit 0 | /tmp/full-test-DLG.log |
| Quality gates 6/6 ×2 ПОДРЯД | exit 0 ×2 | artifacts/quality/20261004-06{4737,4809}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopDelegationSpec"` ×3 | 0,0,0 | /tmp/g24.log, /tmp/dlg-run-{1,2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (178) | /tmp/full-test-DLG.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261004-06{4737,4809}/ |

Диагностический след (честно): первые прогоны выглядели как hang —
 thread dump показал ВСЕ ZScheduler-воркеры parked; причина оказалась
комбинацией (а) неверного ассерта tries==5 (правильно 6 — чайлд тратит
root-бюджет) и (б) параллельной нагрузки тестов + реального дизайн-гэпа
cancel при maxLLM=1 (см. выше). `-zt`-фильтр sbt testOnly в этой
конфигурации не фильтровал (тесты шли все) — учтено на будущее.

## Limitations / obligations

- send_agent — отсутствует (RA-CAP steering obligation); context policy
  (TaskOnly/Summary) — с structured message contract.
- Зависший чайлд при maxLLM=1 блокирует cancelAgent родителя — design
  finding; митигации (дедлайны чайлдов / OOB-cancel) — obligation §11.
- Tool definitions (схемы delegate-аргументов) на wire — structured
  message contract obligation.
- Depth-tracking сейчас = запрет рекурсии по построению; числовая глубина
  появится с policy-карточкой.

## Review and next task

Независимый review не проводился (координаторская сессия). Следующие:
RAI-022 (headless CLI/bundle), structured message contract, RAI-014/015
tools интеграция в цикл.
