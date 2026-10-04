<!-- Work record. Original in Russian; key results in English below. -->

# RAI-022-SLICE: work record — headless runner + bundle loading

Status: done (slice: RUN-01/02/03 скелет + remainder [--agent + SIGTERM
bounded stop]; packaged distribution/CI-адаптеры — milestone-обязанности).
Date: 2026-10-04 (updated same day). Baseline: предыдущий last-passing gate
artifacts/quality/20261004-064809 (DELEGATION).

## Resulting behavior (remainder, same day)

**--agent режим**: `run --agent scout|worker --input … --out … --mock` —
built-in агенты (whitelist `CliArgs.BuiltInAgents`) на `MockBackend`
(маркер `[mock]` В ТЕКСТЕ ответа — fixture discipline; tools=Unsupported
честно); `--agent` XOR `--bundle/--workflow` (RA-CFG). **Runner НЕ
двойной-admission**: bundle-программы — opaque Task, runner сам даёт
внешний slot; AgentLoop выполняет СВОЙ admission внутри — вложенный take
на maxLLM=1 = deadlock (поймано fixture'ом, разделено по веткам target).

**SIGTERM bounded stop**: shutdown-hook → interrupt fiber'ы → ожидание
финализаторов ≤ 5 c → RETURN (никогда System.exit из хука — exit из хука
дедлочит Shutdown.runHooks, поймано probe'ом). POSIX-смерть после хуков
даёт 143; нормальное завершение помечает `done` — hook мгновенный no-op.

**Три ловушки zio/Scala 3.9 этого remainder** (все пойманы probe'ами):
1. `.fork` (не-daemon) на top-level Unsafe: супервизор прерывает child,
   когда parent root fiber завершился → runner умирал сразу → `.forkDaemon`.
2. `System.exit` не флёшит piped stdout → println терялся → явный flush.
3. Scala 3.9 E046-cyclic на member-select-чейнах (`x.call.y`) в файле
   AgentLoop — поля деструктурированы по одному; добавлен отсутствующий
   import ToolCallId (раньше тип выводился, не именовался).

Headless runner БЕЗ компилятора/JLine/сети (headless distribution rule):

- `raider.core.BundleWorkflow` — замороженный v1 SPI (allowed path
  `modules/core/**/bundle/**`): публичный класс, no-arg конструктор,
  `name` + `program: Program[String,String]`; программы ленивы —
  инстанцирование ничего не диспетчит (API-03);
- `raider.cli.run.BundleLoader` — манифест из `META-INF/raider-bundle.json`
  внутри jar (validate → BundleManifest), инстанцирование entries через
  изолированный URLClassLoader; имя класса обязано совпадать с entry;
  НЕ Desktop-сетевой резолюции нет — только локальный jar;
- `raider.cli.run.CliArgs` — чистый парсер `run --bundle --workflow
  (--input|--input-json) --out [--mock]`; unknown/дубликаты/missing →
  typed RA-CFG; stdin НИКОГДА не читается (closed-stdin safe);
- `raider.cli.run.HeadlessRunner` — preflight ДО spend (manifest → load →
  entry → input; RUN-02), затем ОДИН admitted dispatch (admission.withSlot,
  root = cli_<uuid>); артефакты в `<out>/work-<uuid>/` (RUN-03: раздельные
  директории конкурентных запусков): `run.json` (RunResult, ВАЛИДИРОВАН
  до записи, атомарный temp+rename; schema-проверка — настоящая, её
  результат внутри документа), `events.jsonl` (versioned envelopes,
  bounded), `summary.md` с ОБЯЗАТЕЛЬНЫМ fixture-маркером при --mock
  (mock никогда не выдаётся за live);
- `--mock` обязателен в slice: live-провайдеры не подключены — без флага
  typed отказ (exit 21);
- `ExitCodes`-маппинг (ci-runtime §8): ВСЕ 18 семейств ошибок → коды
  10/20/21/22/23/24/25/26/27; успех 0; никакого catch-all нуля;
- `scripts/quality/cli_smoke.sh` — НАСТОЯЩИЙ процесс Main с закрытым
  stdin (`printf '' |`), exit 0 + run.json Succeeded + fixture marker.

Fixture-jar собирается В ТЕСТЕ из скомпилированных классов
(`modules/cli-fixtures`, Scala-классы DemoWorkflow/SecondWorkflow) —
runtime-компилятор не нужен.

Найдено fixture'ами: гонка параллельного копирования keep-jar (три
конкурентных Files.copy на один путь — FileAlreadyExistsException) —
сериализовано локом.

## Changed paths

```
3ebe372a…  build.sbt (raiderCli + raiderCliFixtures + aggregate)
77252d74…  modules/core/src/main/scala/raider/core/bundle.scala (BundleWorkflow SPI)
a906dbb7…  modules/cli/src/main/scala/raider/cli/Main.scala
4867b40c…  modules/cli/src/main/scala/raider/cli/run/CliArgs.scala
44776f2d…  modules/cli/src/main/scala/raider/cli/run/BundleLoader.scala
7e6c4f25…  modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala
978e9f4c…  modules/cli/src/test/scala/raider/cli/run/HeadlessRunnerSpec.scala
2fed586e…  modules/cli-fixtures/src/main/scala/raider/fixtures/DemoWorkflow.scala
a3a2112a…  scripts/quality/cli_smoke.sh
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| RUN-01: jar bundle → mock Program headlessly, artifacts, closed stdin | pass (unit + Main-process smoke) | HeadlessRunnerSpec RUN-01 + cli_smoke.sh |
| RUN-02: unknown workflow / not-a-jar / missing --mock / duplicate flags → typed preflight, exit 21/20, без output/spend | pass | HeadlessRunnerSpec RUN-02 |
| RUN-03 (light): конкурентные запуски → раздельные work-директории | pass (3 параллельно, dirs unique) | HeadlessRunnerSpec RUN-03 |
| exit-маппинг полон по 18 семействам | структурно (total function) | HeadlessRunner.exitCodeFor |
| Стабильность ×3 | 5/5 → 7/7 (после remainder), exit 0 ×3 | /tmp/c12.log, /tmp/c38.log, /tmp/cli-run-{1,2}.log, /tmp/cli3-run-{1,2}.log |
| Полный sbt test | 185 тестов, 0 failed, exit 0 | /tmp/full-test-R022{,b}.log |
| Quality gates 6/6 ×2 ПОДРЯД | exit 0 ×2 | artifacts/quality/20261004-14{5246,5334}/ (slice), 20261004-163216 (remainder, 6 passed) |
| SIGTERM probe: TERM в окне запуска → процесс завершается boundedly (без orphan) | pass | ручной probe (kill -TERM, process exited) |
| cli_smoke | PASS exit 0 (после flush-фикса) | /tmp/cli-smoke8.log |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCli/test"` ×5 | 0,0,0,0,0 | /tmp/c12.log, /tmp/cli-run-{1,2}.log, /tmp/c38.log, /tmp/cli3-run-{1,2}.log |
| `sh scripts/quality/cli_smoke.sh` | 0 | /tmp/cli-smoke8.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×2 | 0 (183/185) | /tmp/full-test-R022{,b}.log |
| `make quality-changed QUALITY_EXECUTE=1` ×4 | 0 ×4 (один честный FAIL 054915 — DSL-ERGO ассерт, исправлен) | artifacts/quality/20261004-14{5246,5334}/, 20261004-163216 |

## Limitations / obligations

- Packaged distribution (zip/изолированный classpath) и SIGTERM-фаза
  (shutdown-hook interrupt, bounded stop, exit 143) — ci/milestone
  обязательства; в slice SIGTERM-обработчика нет (root cleanup = scope
  вызывающего).
- Строгая изоляция загрузчика (child-first/sealed parent) — security
  profile obligation; сейчас parent-delegation.
- --agent режим (built-in агенты в CLI) — следующая часть RAI-022;
  сегодя только bundle-режим.
- Манифест-подписи/trust chain — вне slice (trust по declaredTrustLevel —
  честно задокументировано; «trust from bare hash» — non-goal карточки).

## Review and next task

Независимый review не проводился (координаторская сессия). Следующие:
--agent режим + structured message contract; RAI-023 (CI context/trust
preflight) поверх готовых артефактов.
