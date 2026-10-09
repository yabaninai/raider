# Raider Onboarding / Онбординг

Гайд для нового пользователя: от чистого клона до первого ответа агента. Команды
в этом документе дословно сверены с исходниками (Makefile, `build.sbt`,
`modules/cli`, `modules/repl`, `modules/provider-chat`, `modules/core`) на
baseline этой карточки. Это документация, а не gate: UX01-пороги и числа
производительности здесь НЕ измеряются и НЕ заявляются — они остаются
обязательствами соответствующих карточек (см. `docs/tasks/m0.md`, RAI-070
UXB-протокол).

This guide takes a new user from a clean clone to the first agent answer. Every
command is verified against the sources listed above. It is documentation, not a
gate: UX01 thresholds and performance numbers are neither measured nor claimed
here.

---

## 1. Первый запуск: mock, без ключей и сети / First run (mock, no keys, no network)

### RU

Требования:

- JDK 21 (`java -version` должен показать 21.x).
- sbt 1.11.4 (версия закреплена в `project/build.properties`; нужен установленный
  запускатель `sbt`).
- Кэш зависимостей coursier: Makefile по умолчанию использует
  `COURSIER_CACHE=/tmp/cc-master`.

Сборка fat-jar CLI:

```sh
make raider-jar
```

`make raider-jar` выполняет `sbt --batch raiderCli/assembly`; результат —
`modules/cli/target/scala-3.9.0/raider-cli.jar` (главный класс `raider.cli.Main`,
без рантайм-компилятора — правило headless-дистрибуции).

Первый запрос — mock-провайдер: без API-ключей, без сети, без spend:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent scout --provider mock \
  --input "Summarize what Raider is in one sentence" \
  --out artifacts/first-run
```

В stdout печатается одна JSON-строка итога:

```text
{"status":"Succeeded","exitCode":0,"workDir":"artifacts/first-run/work-1a2b3c4d"}
```

Проверка артефактов — всё лежит в `<--out>/work-<8 hex>`:

```sh
ls artifacts/first-run/work-*/
cat artifacts/first-run/work-*/summary.md
grep '"status":"Succeeded"' artifacts/first-run/work-*/run.json
```

Ожидаемые файлы: `run.json` (итог прогона, атомарная запись temp+rename,
схема валидируется до записи), `events.jsonl` (конверты событий job-слоя),
`summary.md` (человекочитаемая сводка + exit code), `transcript.json`
(trace событий, best-effort). Ответ mock-бэкенда помечен маркером
`[mock] answer to: …` — fixture-ответ никогда не выдаёт себя за живой.
Опциональный флаг `--mock` дополнительно фиксирует fixture-режим в
`summary.md`/`events`.

Справка по флагам `run` (парсер `CliArgs`): `--agent scout|worker|coder` либо
пара `--bundle <jar> --workflow <name>` (взаимоисключимо), `--input <text>`
или `--input-json <file>`, обязательный `--out <dir>`, `--provider
mock|openai|anthropic`, `--base-url <url>`, `--model <id>`, `--workspace <dir>`,
`--mock`. `scout`/`worker` — mock-петли без инструментов; `coder` — реальные
workspace-инструменты (`fs_read`, `fs_search`, `fs_edit`, `proc_run`),
корень workspace по умолчанию — текущий каталог.

### EN

Requirements: JDK 21, sbt 1.11.4 (`project/build.properties`), coursier cache at
`/tmp/cc-master` (Makefile default). Build with `make raider-jar`
(`sbt --batch raiderCli/assembly`) producing
`modules/cli/target/scala-3.9.0/raider-cli.jar`. First request, no keys and no
network, is the mock run shown above; stdout prints one JSON line
(`status`/`exitCode`/`workDir`), artifacts land in `<--out>/work-<8hex>/`
(`run.json`, `events.jsonl`, `summary.md`, `transcript.json`). The mock answer
is always marked `[mock]`.

---

## 2. Живой провайдер / Live provider

### RU

Ключ — только через окружение, никогда через argv:

- `RAIDER_API_KEY` — значение ключа (непустое значение имеет приоритет);
- `RAIDER_API_KEY_CMD` — helper-команда: исполняется через `sh -c`, ключом
  считается первая строка stdout, весь helper ограничен 10 секундами.

Флаг `--api-key` удалён сознательно: он отклоняется типизированной ошибкой
`RA-CFG` (exit 21), чтобы секрет не попал в shell history и листинг процессов.
Ни ключ, ни текст helper-команды не логируются и не пишутся в артефакты.

Интерактивный чат (OpenAI-совместимый сервер):

```sh
export RAIDER_API_KEY="..."   # или RAIDER_API_KEY_CMD='op read ...'
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat \
  --provider openai --base-url <url> --model <id>
```

Флаги `chat`: `--provider mock|openai|anthropic`, `--base-url <url>`,
`--model <id>`, `--workspace <path>`, `--resume`. Команды сессии:
`:help :quit :clear :context :tools :trace`. Если `--model` опустить, `chat`
пытается автоопределить модель ТОЛЬКО на `http://127.0.0.1:8081/v1/models` —
для удалённого base-url указывайте `--model` явно.

Одноразовый прогон с живой моделью и инструментами (агент `coder`):

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent coder --provider openai \
  --base-url <url> --model <id> \
  --input "Read README.md and describe this project" \
  --out artifacts/live-run
```

`--provider anthropic` — нативный Anthropic Messages wire (`/v1/messages`),
те же флаги `--base-url`/`--model` и тот же env-preflight.

Про base-url (OpenAI-совместимый профиль): URL используется как есть (хвостовые
слэши срезаются), запрос — `POST <base>/chat/completions` с `Authorization:
Bearer <ключ>`. Пример OpenAI-совместимого стенда — Yabanin gateway:
`https://yabanin.ai/gw/v1` (интеграция опциональна, см.
[yabanin-integration.md](yabanin-integration.md)). Локальный стенд по умолчанию:
`http://127.0.0.1:8081/v1`. Протоколы —
[provider-protocols.md](provider-protocols.md).

Маппинг HTTP-статусов (оба живых провайдера): 401/403 → `RA-AUTH`,
429 → `RA-RATE` (автоматические ретраи 1с/2с/4с, максимум 3), 5xx →
`RA-UNAVAIL`, остальное → `RA-STREAM`; всё это — exit 20. Реальные платные
вызовы допустимы только в явно заданном режиме и бюджете (AGENTS.md).

### EN

Set `RAIDER_API_KEY` (or `RAIDER_API_KEY_CMD`; helper runs via `sh -c`, first
stdout line is the key, 10 s bound). Secrets in argv are rejected by design:
`--api-key` fails with `RA-CFG` (exit 21). Use
`chat --provider openai --base-url <url> --model <id>` (session commands:
`:help :quit :clear :context :tools :trace`; model auto-detect only probes
`127.0.0.1:8081`), or `run --agent coder --provider openai …` for one-shot
work. `--provider anthropic` speaks the native Messages wire. Example
OpenAI-compatible base-url: the Yabanin stand `https://yabanin.ai/gw/v1`
(optional integration). HTTP 401/403 map to `RA-AUTH`, 429 retries then
`RA-RATE`, 5xx `RA-UNAVAIL` — all exit 20.

---

## 3. REPL и headless CI / REPL and headless CI

### RU

Запуск REPL (настоящий in-process компилятор Scala 3.9.0):

```sh
sbt "raiderRepl/run"
```

Окружение REPL (читается на старте, `raider.repl.Main`):

- `RAIDER_PROVIDER` — `mock` (по умолчанию; scripted fixture-бэкенд, без сети)
  или `openai` (живой OpenAI-совместимый streaming-бэкенд);
- `RAIDER_BASE_URL` — по умолчанию `http://127.0.0.1:8081/v1`;
- `RAIDER_MODEL` — по умолчанию `local`.

В slice REPL живая ветка пока не читает `RAIDER_API_KEY` (для стендов без
auth); credential-проверенный путь для живых провайдеров — CLI `run`/`chat`
из раздела 2. Попытки уйти через реальный gateway из REPL live-ветки
закономерно дают 401 — это ожидаемо, а не дефект гайда.

Внутри REPL: `scout.ask("hello")`, фоновая задача
`val j = worker.start("…")` и `j.await()`; команды `:help :jobs :cancel <id>
:log <id> :reset :quit`. Многострочный ввод копится в буфере; пустая строка
отправляет буфер; Ctrl+C в TTY очищает pending-буфер, а не убивает сессию.
Подробнее: [product-repl.md](product-repl.md).

Headless CI-режим — это подкоманда `run` из раздела 1: работает без TTY
(stdin никогда не читается), печатает одну JSON-строку в stdout, пишет
артефакты в собственный `work-<uuid>` на прогон (параллельные прогоны не
делят каталог), завершается стабильными exit-кодами; SIGTERM даёт
ограниченную остановку (до 5 секунд) и exit 143. Обёртка в Makefile:
`make raider-run INPUT="..."` (агент `coder`, провайдер `openai`). Контракт
CI — [ci-runtime.md](ci-runtime.md).

### EN

Run the REPL with `sbt "raiderRepl/run"` (in-process Scala 3.9.0 compiler).
Environment: `RAIDER_PROVIDER` (`mock` default, `openai` for a live
OpenAI-compatible stand), `RAIDER_BASE_URL` (default `http://127.0.0.1:8081/v1`),
`RAIDER_MODEL` (default `local`). The REPL live slice does not read
`RAIDER_API_KEY` yet — the credential-checked live path is the CLI
`run`/`chat`. Headless CI mode is the `run` subcommand: non-TTY by
construction (stdin never read), one JSON line on stdout, per-run
`work-<uuid>` directory, stable exit codes, SIGTERM bounded stop (max 5 s,
exit 143).

---

## 4. Troubleshooting / Диагностика

### RU

Частые ситуации:

- `raider: RA-CFG: missing credentials for provider 'openai': set
  RAIDER_API_KEY (env) or RAIDER_API_KEY_CMD (helper command)…`, exit 21 —
  живой провайдер запущен без ключа. Preflight срабатывает ДО любого network
  call и spend. Задайте `RAIDER_API_KEY` или `RAIDER_API_KEY_CMD`.
- 401/403 от сервера: `✗ FAILED [RA-AUTH]: provider rejected auth (HTTP
  401|403)`, в `run.json` `status: "Failed"`, exit 20. Проверьте срок действия
  ключа, его scopes на gateway и, при использовании `RAIDER_API_KEY_CMD`, что
  helper укладывается в 10 секунд и печатает ключ первой строкой (иначе тоже
  `RA-CFG`, exit 21).
- 429/5xx: `RA-RATE`/`RA-UNAVAIL`, exit 20; ретраи только для 429.
- Бюджет: `RA-PBUDGET` (лимитер провайдера/ledger) или `RA-LBUDGET` (локальный
  admission) — exit 23.
- `--api-key` в argv — намеренный отказ (`RA-CFG`, exit 21) с подсказкой env-пути.
- `unknown --provider '…'` — допустимо только `mock|openai|anthropic` (exit 21).
- `baseUrl must be an http(s) URL` — проверьте схему base-url (exit 21).

Куда смотреть при диагностике: stderr (строка `raider: <КОД>: <детали>` и
real-time trace инструментов), затем `run.json` (поле `error` с типизированным
кодом), `events.jsonl` (таймлайн job-состояний), `summary.md` (статус и exit
code), `transcript.json` (trace событий модель-цикла).

Стабильные exit-коды (`modules/core/.../result.scala`, контракт —
[ci-runtime.md](ci-runtime.md) §8; catch-all нуля нет):

| Код | Константа | Причина |
| --- | --- | --- |
| 0 | `Success` | успешное завершение |
| 10 | `TaskRejected` | отказ задачи/верификатора/проверки |
| 20 | `RuntimeFailure` | сбой рантайма/провайдера/протокола (в т.ч. `RA-AUTH`, `RA-RATE`, `RA-UNAVAIL`, `RA-STREAM`) |
| 21 | `ConfigInvalid` | конфиг/ввод/bundle/компиляция/preflight (в т.ч. `RA-CFG`, `RA-INP`, `RA-COMPILE`) |
| 22 | `PolicyDenied` | отказ политики (`RA-TOOLDENY`) |
| 23 | `BudgetExhausted` | исчерпан бюджет (`RA-PBUDGET`, `RA-LBUDGET`) |
| 24 | `DeadlineExceeded` | внутренний дедлайн (`RA-DEADLINE`) |
| 25 | `NeedsApproval` | требуется approval в неинтерактивном режиме (в текущем маппинге сюда попадает `RA-CAP`) |
| 26 | `Uncertain` | исход side-effect/отмены неопределён (`RA-CANCEL`, `RA-TOOLUNCERT`) |
| 27 | `ArtifactWriteFailure` | сбой записи артефакта/журнала (`RA-JOURNAL`) |
| 130 | `SigInt` | SIGINT |
| 143 | `SigTerm` | SIGTERM (ограниченная остановка до 5 с) |

Напоминание: этот гайд не заявляет выполненные UX01-пороги; замеры и пороги —
отдельные карточки (RAI-070 UXB-протокол и последующие UX-карточки).

### EN

Frequent cases: missing key → `RA-CFG` exit 21 (preflight fires before any
network/spend); provider 401/403 → `RA-AUTH` exit 20 (check key lifetime and
gateway scopes; a slow or empty `RAIDER_API_KEY_CMD` helper also fails with
`RA-CFG`); 429/5xx → exit 20 (only 429 retries); budget exhaustion (`RA-PBUDGET`
/ `RA-LBUDGET`) → exit 23; `--api-key` in argv is a deliberate refusal
(exit 21). Diagnostics: stderr first, then `run.json` (`error` code),
`events.jsonl`, `summary.md`, `transcript.json` under `<--out>/work-<8hex>/`.
The exit-code table above mirrors `result.scala` exactly (ci-runtime.md §8);
this guide claims no UX01 thresholds.

---

## Что дальше / Where to go next

- Целевое применение и working acceptance: [working-product.md](working-product.md).
- Гейты и обязательные проверки: [quality-gates.md](quality-gates.md).
- Быстрый обзор проекта и сборки: [../README.md](../README.md).
