<!-- Work record. Original in Russian; key results in English below. -->

# DSL-ERGO (RAI-018 slice): work record — менее вербозный DSL

Status: done (slice: фасадные спеллинги golden-сценариев; полный RAI-018 —
отдельная карточка). Date: 2026-10-04. Baseline: предыдущий last-passing
gate artifacts/quality/20261004-051133 (RAI-013-SLICE).

## Resulting behavior

Менее вербозный DSL в REPL (всё через ОДИН интерпретатор — AgentLoop /
composition, никакой второй семантики; construction dispatches nothing —
API-03):

- `scout("исследуй parser")` — ЛЕНИВЫЙ типизированный дескриптор `AgentAsk`:
  `.run()` / `.ask()` (foreground), `.start()` (фон, :jobs), `.map(f)`
  (типизированное пост-преобразование); до run/ask/start — ноль вызовов
  бэкенда (fixture-проверка);
- `all(scout, reviewer).ask("p")` → (String, String); `all(askA.map(len),
  askB).run()` → типизированный кортеж — через `raider.dsl.composition.all`
  (fail-fast COMP-02);
- `batch(List("parser","config","cli"), parallelism = 2)(name =>
  scout(s"исследуй $name")).run()` → упорядоченные результаты;
  `.collect()` → BatchOutcome (доменные падения — значения, внешняя
  прерываемость — ре-raise, §5);
- `openSession(worker)` → `Chat` с историей: каждый `ask` добавляет
  user+assistant ходы — МУЛЬТИХОДОВАЯ непрерывность через тот же AgentLoop
  (fixture: запрос turn-2 несёт user1, assistant1, user2);
- `job.await` БЕЗ скобок как перегрузка `await()` через `@targetName`
  (erasure-clash решён аннотацией); оба спеллинга дают один исход,
  await идемпотентен;
- реестр фасада (`:jobs`/`:cancel`) расширен до JobHandle[?] (гетерогенные
  типизированные джобы), cancel идёт через cancelAny.

Международный контракт не менялся: AgentRef/ask/start/await()/send(RA-CAP)/
watch как были; Runner.runText не тронут.

## Changed paths

```
107b8f70…  modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala
4dd84d3a…  modules/repl/src/test/scala/raider/repl/facade/DslErgonomicsSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Ленивость: построение scout/all/batch — 0 запросов бэкенда (API-03) | pass | DslErgonomicsSpec |
| scout("p").run() / .ask() / .map(_.length) — 1 dispatch, тип | pass | DslErgonomicsSpec |
| .start() + await (оба спеллинга) идемпотентны, Succeeded | pass | DslErgonomicsSpec |
| all(агенты).ask / all(asks).run — кортежи, fail-fast через composition | pass | DslErgonomicsSpec |
| batch.run() — результаты в порядке ВХОДА (порядок записи запросов при parallelism>1 контрактом НЕ является — гейт поймал мой слишком строгий ассерт, исправлен на set-семантику) | pass | DslErgonomicsSpec + gate 054915 (честный fail) |
| batch.collect() — доменные падения в ауткамы | pass | DslErgonomicsSpec |
| openSession: ход-2 несёт user1+assistant1+user2 (непрерывность) | pass | DslErgonomicsSpec |
| Оба REPL smoke на изменённом фасаде | non-TTY exit 0; PTY 13/13 | /tmp/smoke-dsl.log, /tmp/pty-dsl.log |
| Стабильность ×3 (repl 16/16) | exit 0 ×3 | /tmp/d9.log, /tmp/dsl-run-{1,2}.log |
| Полный sbt test | 171 тест, 0 failed, exit 0 ×2 | /tmp/full-test-DSL{,2}.log |
| Quality gates 6/6 ×2 ПОДРЯД (после фикса ассерта) | exit 0 ×2 | artifacts/quality/20261004-05{5104,5145}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRepl/test"` ×3 | 0,0,0 | /tmp/d9.log, /tmp/dsl-run-{1,2}.log |
| `sh scripts/quality/repl_smoke.sh` | 0 | /tmp/smoke-dsl.log |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 (13/13) | /tmp/pty-dsl.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×2 | 0 (171) | /tmp/full-test-DSL{,2}.log |
| `make quality-changed QUALITY_EXECUTE=1` (до фикса ассерта, честно) | exit 2 | artifacts/quality/20261004-054915/ |
| `make quality-changed QUALITY_EXECUTE=1` ×2 (после) | 0, 0 | artifacts/quality/20261004-05{5104,5145}/ |

## Limitations / obligations

- `.named(...)` на композициях, `job.send` (RA-CAP), стриминговый watch —
  прежние obligations RAI-018.
- Chat не переживает :reset (состояние в мире интерпретатора — как и весь
  фасад; это известная модель W10).
- `all` arity-2 (как composition.all); arity-3+ и heterogeneous tuple map —
  в полной карточке RAI-018/017.
- AgentAsk инвариантен по A (JobHandle инвариантен — ковариантная +A ломала
  start()); на эргономику не влияет.

## Review and next task

Независимый review не проводился (координаторская сессия). Следующие:
child delegation (§11), RAI-022 (headless CLI), structured message contract.
