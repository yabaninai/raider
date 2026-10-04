<!-- Work record. Original in Russian; key results in English below. -->

# SELFDEV: work record — coder-агент (самостоятельная работа над репо)

Status: done (slice: coder = built-in агент с НАСТОЯЩИМИ workspace-инструментами
fs_read/fs_search/proc_run + live OpenAI-compatible провайдер; edit-инструмент
и мультиход-память — obligations). Date: 2026-10-04. Baseline: предыдущий
last-passing gate artifacts/quality/20261004-163645.

## Resulting behavior

`raider run --agent coder --provider openai --base-url http://127.0.0.1:8081/v1
--input "…" --out …` — Raider работает над СОБСТВЕННЫМ репо:

- `raider.tools.CodingToolset` — реальные RAI-014/016 инструменты за JSON
  -контрактом core Tool: `fs_read` (bounded read + sha256 provenance + realpath
  containment — symlink/`..` эскейпы отклонены), `fs_search` (bounded,
  declared truncation), `proc_run` (ЯВНЫЙ argv, БЕЗ shell, cwd в workspace,
  empty-env + allowlist, timeout graceful→force, bounded output). POLICY-
  отказы (containment/bounds/bad args) — structured feedback МОДЕЛИ (§8/§10:
  цикл продолжается, модель может скорректироваться); crash-фактуры остаются
  typed failures. Это ЕДИНСТВЕННЫЙ канал мутаций (argv полностью виден).
- CLI: `--provider mock|openai` (default mock), `--base-url`
  (default 127.0.0.1:8081/v1 — локальный llama.cpp), `--model` (default
  local), `--api-key` (default no-key — НЕ секрет-литерал), `--workspace`
  (default CWD). coder в whitelist BuiltInAgents.
- `MockBackend` — fixture-маркированный (`[mock]` в тексте) для --mock.
- Живой backend: non-streaming OpenAIChatBackend (RAI-011 slice) — парсит
  `tool_calls` → ToolCallReady → инструменты реально исполняются.

НАЙДЕНО И ЗАКРЫТО fixture'ами (воспроизведено, потом устранено):
1. java-glob `**/*.scala` НЕ матчит файл в корне workspace (нужен `*.scala`
   или `\u007b\u007b`-семантика — задокументировано в тесте).
2. containment-отказ как typed failure ВАЛИЛ ВЕСЬ прогон (LOOP-03
   семантика «tool failure → Failed» слишком жёсткая для policy-отказов) —
   введено разделение policy-denial (feedback) vs crash (fail) в адаптере.
3. `Stream.empty *> s` в zio-streams ЗАВЕРШАЕТ комбинированный стрим, не
   потянув s (zip-семантика) — детерминированный park в TurnBackend переведён
   на `++` + drain; park переведён с ИНДЕКСА вызова на СОДЕРЖАНИЕ запроса
   (parent/child fork order недетерминирован под нагрузкой).

## Changed paths

```
7088d4d2…  modules/tools/src/main/scala/raider/tools/CodingToolset.scala
8ca26368…  modules/cli/src/main/scala/raider/cli/run/CliArgs.scala
1b90aa38…  modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala
00191342…  modules/cli/src/main/scala/raider/cli/run/MockBackend.scala
d53b0fce…  modules/cli/src/test/scala/raider/cli/run/CoderAgentSpec.scala
2bc4dc48…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala
57127e11…  scripts/selfdev/raider.sh
18e7f020…  docs/selfdev-prompts.md
fbf91edb…  build.sbt
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| coder fs_read: модель спрашивает → loop читает РЕАЛЬНЫЙ файл → контент+sha256 доходят до модели, БЕЗ абсолютных путей | pass | CoderAgentSpec |
| coder fs_search: bounded search находит РЕАЛЬНОЕ совпадение | pass (после glob-фикса) | CoderAgentSpec |
| containment: fs_read ВНЕ workspace → feedback модели, утечки НЕТ | pass | CoderAgentSpec |
| делегация: park по контенту запроса детерминирован | pass | AgentLoopDelegationSpec |
| Стабильность | cli 10/10 ×2, tools 19/19, полный 188 ×2 | /tmp/cli4.log, /tmp/c50.log, /tmp/full-final3.log |
| Полный sbt test | 188 тестов, 0 failed, exit 0 | /tmp/full-final3.log |
| cli_smoke (Main-процесс, closed stdin) | PASS | /tmp/final-cli-smoke2.log |
| Quality gates 6/6 | exit 0 | artifacts/quality/20261004-181210/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCli/test"` ×2 | 0 (10/10) | /tmp/c50.log, /tmp/cli4.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderTools/test"` | 0 (19/19) | /tmp/cli5.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×2 | 0 (188) | /tmp/full-final{,3}.log |
| `sh scripts/quality/cli_smoke.sh` | 0 | /tmp/final-cli-smoke2.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261004-181210/ |

## Limitations / obligations

- Edit tool RAI-015 СОБРАН (fs_edit: expected-sha, atomic, stale-detect). proc_run остаётся для команд.
- Мультиход-память для CLI-агента (Chat-класс есть только в REPL-фасаде) —
  obligation; сейчас один --input = один run.
- Live-wire верификация (реальный llama.cpp прогон) — НЕ в этой карточке:
  запуск командой владельца (см. docs/selfdev-prompts.md); продуктовые
  тесты 8081 не трогают (правило сессии).
- Системный промпт coder'у пока не передаётся (ModelRequest без system) —
  structured message contract obligation.

## Review and next task

Независимый review не проводился (координаторская сессия). Self-hosting
loop готов к владельческому запуску; следующие: RAI-015 (edit tool),
RAI-023 (CI context), structured messages.
