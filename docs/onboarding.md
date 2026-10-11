# Raider Onboarding / Онбординг (RAI-080)

Getting-started гайд для нового пользователя: от чистого клона до первого
ответа агента. Каждая команда ниже дословно сверена с исходниками этого
дерева: `modules/cli/src/main/scala/raider/cli/**` (`Main`, `CliArgs`),
`modules/repl/src/main/scala/raider/repl/**`, `modules/tools`, `Makefile`,
`build.sbt`, `README.md`. Ссылки вида `файл:строка` — источник истины; если
код изменится, гайд подлежит переверке. Это документация, а не gate: UX01-пороги
и метрики здесь НЕ измеряются и НЕ заявляются — они остаются обязательствами
своих карточек (см. `docs/working-product.md`, `docs/quality-gates.md`).

---

## 1. Требования / Requirements

- **JDK 21** — CI собирается на Temurin 21 (`.github/workflows/ci.yml:8-9`);
  Scala 3.9.0 требует минимум JDK 17+, проверено на 21.
- **sbt 1.11.4** — закреплён в `project/build.properties:1`; нужен
  установленный запускатель `sbt`.
- **Scala 3.9.0 + ZIO 2.1.26 + zio-json 0.10.0 + JLine 4.0.14** — pinned
  версии   (`build.sbt:3,10-11`; JLine: `build.sbt:97-99`). Новые зависимости
  не добавляются (языковая политика AGENTS.md).
- **Кэш coursier**: по умолчанию `COURSIER_CACHE=/tmp/cc-master`
  (`Makefile:7`) — переменная make, а не env-подстановка.
- **`toolchain.json`**: `make bootstrap` (`Makefile:13-14`) скачивает пinned
  набор jar'ов (scala3-compiler/scala3-repl/scala3-library 3.9.0, zio,
  zio-json, jline, coursier-interface — `scripts/bootstrap.sh:9-17`) в
  `artifacts/tools/m2` и записывает инвентаризацию (координаты, размеры,
  sha256) в `artifacts/toolchain/toolchain.json`
  (`experiments/repl-spike/fetch_toolchain.py:14-17`). Запуск идемпотентен;
  в подготовленном снапшоте/CI jar'ы уже лежат — bootstrap повторно НЕ нужен.

Проверка сборки без REPL: `make compile` → `sbt --batch compile`
(`Makefile:16-17`).

### EN

JDK 21, sbt 1.11.4 (`project/build.properties:1`), Scala 3.9.0 / ZIO 2.1.26
pinned (`build.sbt:3,10-11`), coursier cache `/tmp/cc-master` (`Makefile:7`).
`make bootstrap` fetches the pinned jars into `artifacts/tools/m2` and writes
the inventory `artifacts/toolchain/toolchain.json` (`scripts/bootstrap.sh`).

---

## 2. Первая сессия REPL (mock) / First REPL session (mock)

REPL — настоящий in-process компилятор Scala 3.9.0
(`modules/repl-engine`, `build.sbt:90-100`), поэтому он запускается через sbt,
а не из headless fat-jar (CLI без компилятора — `build.sbt:150`).

```sh
sbt "raiderRepl/run"
```

Единственная точка входа модуля — `raider.repl.Main`
(`modules/repl/src/main/scala/raider/repl/Main.scala:221`; sbt-настройки
`run / fork` — `build.sbt:122-125`). Без переменных окружения используется
scripted/mock-бэкенд: `RAIDER_PROVIDER` по умолчанию `mock`
(`repl/Main.scala:65`), прелюдия строит `ScriptedModelBackend` и сессию
(`repl/Main.scala:194-207`) — без сети и ключей.

Баннер подсказывает первые шаги (`repl/Main.scala:232-239`):

```scala
scout.ask("hello")            // foreground-ответ fixture-агента
val j = worker.start("bg")    // фоновая задача
j.await()                     // дождаться результата
:quit
```

- `scout.ask(prompt)` — блокирующий foreground-запрос
  (`modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala:192-193`).
- Fixture-ответ честно помечен: `[scripted-fixture] mock answer …`
  (`repl/Main.scala:199`).

### EN

Run `sbt "raiderRepl/run"` (entrypoint `raider.repl.Main`,
`repl/Main.scala:221`). Default `RAIDER_PROVIDER=mock` wires the scripted
fixture backend — no network, no keys (`repl/Main.scala:65,194-207`). Try
`scout.ask("hello")`, then `:quit`.

---

## 3. Первый headless run (mock) и артефакты / First headless run + artifacts

Сборка headless fat-jar (CLI без компилятора/JLine — `build.sbt:150-151`,
main class `raider.cli.Main` — `build.sbt:169`):

```sh
make raider-jar
```

(`Makefile:58-59` → `sbt --batch raiderCli/assembly`; результат —
`modules/cli/target/scala-3.9.0/raider-cli.jar`.)

Первый прогон — mock-провайдер, без ключей и сети:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent scout --provider mock \
  --input "Summarize what Raider is in one sentence" \
  --out artifacts/first-run
```

- Подкоманда `run` парсится `CliArgs.parse`
  (`modules/cli/src/main/scala/raider/cli/run/CliArgs.scala:95`);
  встроенные агенты `scout|worker|coder` — `CliArgs.scala:72`.
- В stdout печатается ОДНА JSON-строка итога
  (`modules/cli/src/main/scala/raider/cli/Main.scala:171-176`):
  `{"status":"Succeeded","exitCode":0,"workDir":"..."}`.
- `--agent scout|worker` — mock-петли без инструментов; `coder` — реальные
  workspace-инструменты `fs_read, fs_search, fs_edit, fs_patch, fs_tree,
  proc_run` (`CliArgs.scala:69-72`; набор —
  `modules/tools/src/main/scala/raider/tools/CodingToolset.scala:202-250`;
  wiring — `HeadlessRunner.scala:394-400`).

Артефакты лежат в `<--out>/work-<8 hex>` — по каталогу на прогон
(`modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala:112-116`,
UUID-суффикс; параллельные прогоны каталоги не делят):

| Файл | Что это | Где в коде |
| --- | --- | --- |
| `run.json` | итог прогона; атомарная запись temp+rename, валидация ДО записи | `HeadlessRunner.scala:500-530` |
| `events.jsonl` | конверты событий job-слоя (schemaVersion 1), стримятся во время прогона | `HeadlessRunner.scala:216,531-535` |
| `summary.md` | человекочитаемая сводка + exit code | `HeadlessRunner.scala:562-576` |
| `transcript.json` | trace событий модель-цикла (best-effort) | `HeadlessRunner.scala:285-305` |

Ответ mock-бэкенда всегда помечен маркером `[mock]`
(`modules/cli/src/main/scala/raider/cli/run/MockBackend.scala:16,33`) —
fixture никогда не выдаёт себя за живой ответ. Опциональный флаг `--mock`
(`CliArgs.scala:111`) дополнительно фиксирует fixture-режим в `summary.md`
(`HeadlessRunner.scala:537-540`); сам по себе он бэкенд НЕ подменяет —
провайдера выбирает `--provider` (по умолчанию `mock`, `CliArgs.scala:36`).

Альтернатива без fat-jar — classpath-лаунчер с автоопределением модели:
`scripts/selfdev/raider.sh run --agent coder --provider openai --input "…"
--out artifacts/selfdev` (`scripts/selfdev/raider.sh`).

### EN

`make raider-jar` builds `modules/cli/target/scala-3.9.0/raider-cli.jar`
(main class `raider.cli.Main`, `build.sbt:169`). First run, no keys/network:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent scout --provider mock --input "…" --out artifacts/first-run
```

One JSON result line on stdout (`Main.scala:171-176`); artifacts land in
`<--out>/work-<8hex>/`: `run.json` (atomic, validated pre-write),
`events.jsonl` (streamed envelopes), `summary.md`, `transcript.json`
(`HeadlessRunner.scala:112-116,500-576`). Mock answers are always marked
`[mock]` (`MockBackend.scala:16,33`); `--mock` only marks fixture mode in
summary/events (`HeadlessRunner.scala:537-540`).

---

## 4. Провайдеры и ключи / Providers and keys

Провайдеры — `mock | openai | anthropic`, только эти три значения
(`CliArgs.scala:149-158`). OpenAI-совместимый и Anthropic Messages —
first-class транспорта (`ProviderBackend.scala:29-57`; Anthropic — нативный
`/v1/messages`, `provider-anthropic`).

**Ключи — ТОЛЬКО через окружение, никогда через argv** (RAI-064):

- `RAIDER_API_KEY` — значение ключа; непустое значение имеет приоритет
  (`modules/cli/src/main/scala/raider/cli/run/Credentials.scala:26,59-61`);
- `RAIDER_API_KEY_CMD` — helper-команда: исполняется через `sh -c`, ключ —
  первая строка stdout, весь helper ограничен 10 секундами
  (`Credentials.scala:29,79,97-159`);
- `--api-key` в argv — НАМЕРЕННЫЙ отказ: типированная ошибка `RA-CFG`,
  exit 21 (`CliArgs.scala:114-120`; для `chat` — `Main.scala:116-122`).
  Значение никогда не эхо-печатается.
- Ни ключ, ни текст helper-команды не логируются и не пишутся в артефакты;
  безопасная форма — `mask()` (первые 4 символа + `***` или `<redacted>`,
  `Credentials.scala:35-37`).

Интерактивный чат (подкоманда `chat`, `Main.scala:112-137`):

```sh
export RAIDER_API_KEY="<ключ из вашего секрет-хранилища>"   # или RAIDER_API_KEY_CMD='<helper>'
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat \
  --provider openai --base-url <url> --model <id>
```

- Флаги `chat`: `--provider` (по умолчанию `openai`, `Main.scala:128`),
  `--base-url` (по умолчанию `http://127.0.0.1:8081/v1`, `Main.scala:129`),
  `--model` (по умолчанию — автодетект ТОЛЬКО на
  `http://127.0.0.1:8081/v1/models`, иначе `local`; `Main.scala:37-52,130`),
  `--workspace`, `--resume <name>` (`Main.scala:131-132`; `--resume`
  загружает сохранённую сессию, `ChatLoop.scala:150-172`).
- Команды сессии: `:help :quit(:q/:exit/Ctrl+D) :clear :context :tools
  :trace :save <name> :sessions :load <name> :compact :fix :pipe :delegate`
  (`modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala:296-325`,
  справка `ChatLoop.scala:767-784`; сессии хранятся в `~/.raider/sessions/`,
  `ChatLoop.scala:775`).

Одноразовый прогон с живой моделью и инструментами:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent coder --provider openai \
  --base-url <url> --model <id> \
  --input "Read README.md and describe this project" \
  --out artifacts/live-run
```

Корень workspace — `--workspace <dir>`, по умолчанию текущий каталог
(`HeadlessRunner.scala:388-393`). Прочие флаги `run`: `--input-json <file>`
(вместо `--input`, взаимоисключимо — `CliArgs.scala:141-148`),
`--bundle <jar> --workflow <name>` (вместо `--agent`, взаимоисключимо —
`CliArgs.scala:177-203`), `--context <file>` — ci-context JSON, файл должен
существовать на момент парсинга (`CliArgs.scala:166-176`). ВАЖНО: флаг
`--ci github|gitlab`, упомянутый в javadoc `HeadlessRunner` (RAI-100),
парсером argv пока НЕ принимается (`CliArgs.scala:76-89` его не содержит,
`Main` всегда передаёт `ci = None` — `HeadlessRunner.scala:91-97`); попытка
передать его даст `unknown argument '--ci'`.

REPL с живым провайдером (`raider.repl.Main`): `RAIDER_PROVIDER=openai`
(или `anthropic`) + `RAIDER_BASE_URL`/`RAIDER_MODEL`
(`repl/Main.scala:65-67`). Ключ резолвится так же, как в CLI —
`RAIDER_API_KEY` / `RAIDER_API_KEY_CMD`
(`repl/Main.scala:69-72`;
`modules/repl/src/main/scala/raider/repl/LiveCredentials.scala:31-34,89-106`).
Живые ветки openai/anthropic строят реальные streaming-бэкенды
(`repl/Main.scala:115-161`); БЕЗ резолвимого ключа REPL НЕ падает — печатает
подсказку (только имена env-переменных) и продолжает scripted/mock-путь
(`repl/Main.scala:101-102`; `LiveCredentials.scala:77-81`).

Маппинг HTTP-статусов (оба живых провайдера): 401/403 → `RA-AUTH`,
429 → `RA-RATE` (авто-ретраи 1с/2с/4с, максимум 3 —
`ProviderBackend.scala:59-62`), 5xx → `RA-UNAVAIL`, остальное → `RA-STREAM`
(`modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala:20-21,125-160`;
`modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicMessagesBackend.scala:28-30,263-265`);
все они — exit 20. В `run` ошибка сборки бэкенда — типированный отказ
(`ProviderBackend.scala:63-74`); в `chat` отказ настройки бэкенда честно
падает в помеченный fallback-mock `[fallback-mock] backend setup failed…`
(`ChatLoop.scala:786-808`) — никогда не маскируется под живой провод.

Про base-url (OpenAI-совместимый профиль): URL используется как есть,
запрос — `POST <base>/chat/completions`. Raider напрямую работает с
OpenAI/Anthropic-compatible API без gateway; протоколы —
[provider-protocols.md](provider-protocols.md). Реальные платные вызовы —
только в явно заданном режиме и бюджете (AGENTS.md); разработка/CI используют
mock/replay.

### EN

Providers are `mock|openai|anthropic` only (`CliArgs.scala:149-158`). Keys
come ONLY from the environment: `RAIDER_API_KEY` (wins) or
`RAIDER_API_KEY_CMD` (helper via `sh -c`, first stdout line, 10 s bound) —
`Credentials.scala:26-29,59-73,79`. `--api-key` is a deliberate typed
refusal, `RA-CFG` exit 21 (`CliArgs.scala:114-120`). Interactive chat:
`chat --provider openai --base-url <url> --model <id>` with session commands
`:help :quit :clear :context :tools :trace :save :sessions :load :compact
:fix :pipe :delegate` (`ChatLoop.scala:296-325,767-784`). One-shot live work:
`run --agent coder --provider openai --base-url <url> --model <id> …`.
REPL live mode: `RAIDER_PROVIDER=openai|anthropic` plus the same env key
(`repl/Main.scala:69-82`); without a key it prints a notice and stays on the
mock path (`repl/Main.scala:101-102`). HTTP 401/403 → `RA-AUTH`, 429 →
`RA-RATE` (retries 1s/2s/4s), 5xx → `RA-UNAVAIL`, else `RA-STREAM` — exit 20.

---

## 5. REPL: команды и фасад (кратко) / REPL commands and facade

- **Tab-completion** — реальные кандидаты компилятора dotty против текущего
  состояния сессии (`repl/Main.scala:284-290`;
  `modules/repl/src/main/scala/raider/repl/CompletionSupport.scala:45-64`):
  `hel`<Tab>, `List(1).map`<Tab>, `xs.`<Tab>. Вставка локальна для хвостового
  сегмента идентификатора (`CompletionSupport.scala:31-35`).
- **Многострочный ввод** копится в буфере; пустая строка отправляет буфер
  (`repl/Main.scala:322-342`, эвристика баланса скобок — `356-365`).
  Ctrl+C в TTY очищает pending-буфер, а не убивает сессию
  (`repl/Main.scala:291-297,309-312`).
- **Консольные команды** (`modules/repl/src/main/scala/raider/repl/Commands.scala:17-78`):
  `:help` (`:h`), `:jobs`, `:cancel <id>`, `:log <id>`, `:reset`, `:quit`
  (`:q`, `:exit`). `:type` НЕ в срезе — честный отказ (`Commands.scala:73-75`).
  `:log` требует, чтобы у задачи был trace (`modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala:140-141`).
- **Фасад агентов** (`FacadeOps.scala`):
  `scout.ask("…")` (192-193); ленивый дескриптор
  `scout("…").run() / .ask() / .start() / .map(f)` (169-187, 198-203);
  фоновая задача `worker.start("…")` → `JobHandle` (208-214) с
  `.await()` (219-227), `.cancel()` (230-231), `.watch()` (236-244);
  композиция `all(scout, worker).ask("…")` / `all(askA, askB).run()` (261-279);
  `batch(inputs, parallelism)(f).run()/.collect()` (284-311);
  многоходовый чат `openSession(worker).ask("…")` (321-345).
  Steering (`send`) — типированный отказ вне среза W10 (249-252).
- В scripted/mock-прелюдии связаны `scout` и `worker`
  (`repl/Main.scala:205-206`); в live-прелюдии также `coder`
  (`repl/Main.scala:186-188`).
- Честное завершение: при выходе активные задачи report'ятся и бросаются —
  durable resume НЕТ (`modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala:147-156`; `repl/Main.scala:400-410`).

### EN

Tab completion asks the real dotty engine (`CompletionSupport.scala:45-64`);
multiline input accumulates until braces balance and a blank line submits
(`repl/Main.scala:322-365`); Ctrl+C clears the pending buffer. Console
commands: `:help :jobs :cancel <id> :log <id> :reset :quit` (`:type` is not
in the slice, `Commands.scala:73-75`). Facade: `scout.ask("…")`,
`scout("…").run()/.start()/.map(f)`, `worker.start("…")` + `.await()`,
`all(...)`, `batch(...)`, `openSession(agent).ask(...)` (`FacadeOps.scala`).
Active jobs are dropped at exit — no durable resume.

---

## 6. Плагины (кратко) / Plugins

Реализовано (RAI-075/083/101): подкоманда `plugin` маршрутизируется ДО
`run`/`chat` (`Main.scala:101-109`); каталог — `~/.raider/plugins`, оверрайд
env `RAIDER_PLUGINS_DIR` (`modules/cli/src/main/scala/raider/cli/run/PluginCli.scala:78-85`).

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin list
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin install <jar>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin remove <name>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin enable <name>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin disable <name>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin update <name> <jar>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin rollback <name>
```

Диспетчер — `PluginCli.execute` (`PluginCli.scala:91-112`), usage —
`PluginCli.scala:71-73`. Вывод — одна plain-JSON строка на stdout; exit 0 =
успех, типированный отказ = exit 21 (`Main.scala:92-98`; спека —
`modules/cli/src/test/scala/raider/cli/MainPluginSpec.scala`). install
проверяет манифест + payload sha256 + co-install preflight; update хранит
`<name>.prev.jar` для rollback (`PluginCli.scala:13-45,157-198,301-405`).
Семантика — ТОЛЬКО файлы каталога: запущенная сессия держит свой
classloader и подхватывает новую версию только после рестарта
(`PluginCli.scala:47-55`).

### EN

`plugin list|install <jar>|remove|enable|disable|update <name> <jar>|rollback`
is routed before run/chat (`Main.scala:101-109`), catalog
`~/.raider/plugins` (env `RAIDER_PLUGINS_DIR`), one JSON line on stdout,
exit 0/21 (`PluginCli.scala:71-112`). Catalog-files-only: running sessions
pick up a new version after a restart (`PluginCli.scala:47-55`).

---

## 7. Troubleshooting: типированные ошибки / Typed errors

Где что читать:

- Ошибки парсинга argv → stderr в форме `raider: <КОД>: <детали>` +
  exit по коду семейства (`Main.scala:140-142`).
- Preflight `chat` (в т.ч. отсутствие ключа) → stderr + exit 21
  (`ChatLoop.scala:100-110`).
- Отказы `run` → stdout JSON-строка итога, `run.json` (поле `error` с
  типированным кодом) и `summary.md` (`HeadlessRunner.scala:145-171,541-544`);
  в stderr в реальном времени идёт trace инструментов и `✗ FAILED [КОД]`
  (`HeadlessRunner.scala:307-328`).
- REPL не падает на отсутствии ключа: подсказка + scripted/mock
  (`repl/Main.scala:101-102`).

Частые случаи:

- `RA-CFG: missing credentials for provider 'openai': set RAIDER_API_KEY
  (env) or RAIDER_API_KEY_CMD (helper command)…` — живой провайдер без
  ключа. Preflight срабатывает ДО любого network call и spend
  (`HeadlessRunner.scala:375-378`; `Credentials.scala:67-73`). Задайте env
  или helper (см. раздел 4).
- `--api-key` в argv — намеренный отказ `RA-CFG`, exit 21, значение не
  эхо-печатается (`CliArgs.scala:114-120`). Помог helper, но не напечатал
  ключ в первую строку stdout, или не уложился в 10 секунд — тоже `RA-CFG`
  (`Credentials.scala:135-151`).
- `unknown --provider '…' (mock|openai|anthropic)` — exit 21
  (`CliArgs.scala:149-158`).
- `unknown argument '…'` / `$flag requires a value` / `$flag specified
  twice` — проверьте флаги против разделов выше (`CliArgs.scala:121-128`).
- `unknown built-in agent '…'; known: coder, scout, worker` — exit 21
  (`CliArgs.scala:180-185`).
- `--input and --input-json are mutually exclusive` /
  `--agent is mutually exclusive with --bundle/--workflow` /
  `--bundle requires --workflow` — exit 21 (`CliArgs.scala:141-148,177-203`).
- `--context file does not exist: <path>` — exit 21 на парсинге
  (`CliArgs.scala:166-176`); битый payload контекста — типированный отказ
  ДО dispatch (`HeadlessRunner.scala:580-602`).
- `--ci` отклонён как `unknown argument` — флаг ещё не проведён через
  парсер (см. раздел 4; `CliArgs.scala:76-89`).
- `RA-AUTH` (провайдер отверг ключ, HTTP 401/403) / `RA-RATE` (429; ретраи
  1с/2с/4с) / `RA-UNAVAIL` (5xx) / `RA-STREAM` — exit 20. Проверьте срок
  действия ключа и scopes.
- Бюджет: `RA-PBUDGET` (лимитер провайдера) или `RA-LBUDGET` (локальный
  admission) — exit 23. Политика инструментов `RA-TOOLDENY` — exit 22.
  Дедлайн `RA-DEADLINE` — exit 24.

Стабильные exit-коды — контракт [ci-runtime.md](ci-runtime.md) §8; константы
`modules/core/src/main/scala/raider/core/result.scala:21-32`, маппинг
`HeadlessRunner.exitCodeFor` (`HeadlessRunner.scala:68-86`), catch-all
нуля нет:

| Код | Семейство (константа) | Типичные коды |
| --- | --- | --- |
| 0 | `Success` | успешное завершение |
| 10 | `TaskRejected` | `RA-OUT`, `RA-TOOLFAIL`, `RA-CHILD` |
| 20 | `RuntimeFailure` | `RA-AUTH`, `RA-RATE`, `RA-UNAVAIL`, `RA-STREAM` |
| 21 | `ConfigInvalid` | `RA-CFG`, `RA-INP`, `RA-COMPILE` (парсинг, preflight, bundle) |
| 22 | `PolicyDenied` | `RA-TOOLDENY` |
| 23 | `BudgetExhausted` | `RA-PBUDGET`, `RA-LBUDGET` |
| 24 | `DeadlineExceeded` | `RA-DEADLINE` |
| 25 | `NeedsApproval` | `RA-CAP` |
| 26 | `Uncertain` | `RA-CANCEL`, `RA-TOOLUNCERT` |
| 27 | `ArtifactWriteFailure` | `RA-JOURNAL` |
| 130 / 143 | `SigInt` / `SigTerm` | SIGINT / SIGTERM (ограниченная остановка до 5 c — `Main.scala:14-19,54`) |

Коды ошибок — `modules/core/src/main/scala/raider/core/errors.scala:18-70`.

### EN

Parse errors print `raider: <CODE>: <detail>` to stderr (`Main.scala:140-142`);
`run` typed failures land in `run.json` (`error`) + `summary.md` + the stdout
JSON line (`HeadlessRunner.scala:145-171,541-544`). Missing key → `RA-CFG`
exit 21 before any network/spend; `--api-key` is a deliberate refusal;
unknown `--provider`/agent/flag → exit 21; provider 401/403 → `RA-AUTH`,
429 → `RA-RATE` (retried), 5xx → `RA-UNAVAIL` — exit 20. The exit-code table
mirrors `result.scala:21-32` and `HeadlessRunner.exitCodeFor`
(`HeadlessRunner.scala:68-86`); SIGTERM gives a bounded stop (max 5 s,
exit 143).

---

## Что дальше / Where to go next

- Целевое применение и working acceptance: [working-product.md](working-product.md).
- Гейты и обязательные проверки: [quality-gates.md](quality-gates.md).
- Контракт headless/CI (артефакты, exit-коды, trust): [ci-runtime.md](ci-runtime.md).
- REPL подробнее: [product-repl.md](product-repl.md); плагины: [plugins.md](plugins.md).
- Обзор проекта и сборки: [../README.md](../README.md).

Напоминание: гайд не заявляет выполненные UX01-пороги; замеры — отдельные
карточки. Ничего из описанного не требует секретов в argv, файлах или
примерах — только ссылки на env.
