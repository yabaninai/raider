# Raider: что можно запустить сейчас

Проверенные примеры для текущей кодовой базы. Основная ветка репозитория — `main`.
Raider использует Scala 3.9.0 и ZIO 2.1.26; SDK и полный автоматический SDLC ещё
разрабатываются. Галерея запускается без настоящей модели и без платных вызовов.

## Подготовка и запуск всей галереи

Нужны JDK 21, sbt, Python 3, команды JDK `java`/`jar` и POSIX `cmp` для coding
fixture. Проверьте `java -version`; при необходимости задайте `JAVA_HOME` на
установленный JDK 21. Скрипт сборки выбирает этот же JVM для sbt и compiler.
Первая сборка скачивает pinned dependencies, поэтому требует доступа к registry.
Все команды выполняются из корня checkout.

```sh
python3 scripts/quality/user_examples.py
```

Это проверяет восемь примеров с реальными JVM/compiler/CLI и возвращает exit 0
только после всех assertions. Каждый запуск получает свой каталог
`artifacts/user-examples/verification-<id>/report.json`, stdout/stderr и fixture
workspace. Report содержит фактические argv, exit codes, hashes исходников и
скомпилированных примеров. Никакие файлы вашего рабочего проекта coding fixture
не меняет. Нужен только локальный loopback socket для scripted server.

Для ручного запуска подготовьте артефакты:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

Сборка создаёт compiled bundle `artifacts/user-examples/task-summary.jar`.
Classpath привязан к этому checkout; после перемещения или изменения исходников
пересоберите его. Это source demo, а не опубликованный embedding SDK.

## 1–6. Scala, композиция и headless workflow

[Шесть пошаговых примеров](user-guide-repl.md) содержат готовые `.repl` файлы и
исходник `TaskSummary`. Можно показывать настоящий compiler, явный запуск lazy
ask, `map`, tuple `all`, bounded ordered `batch`, job lifecycle и историю turns.
Внутренний типизированный `Program` запускается compiled bundle через ту же CLI.

REPL по умолчанию выдаёт фиксированный `[scripted-fixture]` ответ. CLI `--mock`
выдаёт `[mock]` и не вызывает tools. Эти backend-и предназначены для проверки
механики. Они не читают файл в ответ на произвольную просьбу и не исправляют код.

## 7. Исправление файла и проверка результата

Показываем небольшой coding cycle: ошибка в `greeting.txt`, поиск места ошибки,
проверка до изменения, edit по ожидаемому SHA-256 и проверка после изменения.
Запустите отдельно:

```sh
python3 scripts/quality/user_examples.py --only coding
```

Runner копирует `examples/user-guide/fixture` в отдельный workspace и запускает
Scala `CodingStand` на случайном loopback-порту. Stand не имеет доступа к этому
workspace: он получает только сообщения HTTP и выбирает следующие tool calls
по фактическим результатам предыдущих. CLI работает через настоящий
OpenAI-compatible transport и native AgentLoop.

Фактическая последовательность, которую проверяет runner:

1. `fs_read` читает `Hello Raiedr!` и возвращает SHA-256.
2. `fs_search` находит опечатку в fixture.
3. `proc_run` запускает `cmp greeting.txt expected.txt`; exit **1** наблюдается
   до изменения файла. Это ожидаемый неуспех проверки, а не ошибка инструмента.
4. `fs_edit` записывает `Hello Raider!` с полученным `expected_sha256`.
5. Повторный `proc_run` возвращает exit **0**; runner отдельно сравнивает bytes.

В логе появятся `coding: PASS`, фактическое содержимое файла и каталог run
artifacts. Ответ stand содержит `[scripted-docs]`. Здесь решения tools заранее
заданы fixture; проверяется реальное исполнение coding loop, а не качество модели.

В `report.json` сохранён точный вызов `raider.cli.Main run` с `--agent coder`,
`--provider openai`, `--base-url`, `--model scripted-docs`, `--workspace`, `--input`
и `--out`. Фиктивный credential передаётся runner только через env и подходит
только локальному стенду. Реальные credentials в reports/argv не записывайте.

## 8. Просмотр результата и дерева запуска

```sh
python3 scripts/quality/user_examples.py --only artifacts
```

Этот пример сначала исполняет coding fixture, затем проверяет реальные outputs:

| Файл внутри выведенного `work-<id>` | Назначение |
| --- | --- |
| `run.json` | Итоговый status/exitCode, output, checks и accounting snapshot |
| `summary.md` | Краткий результат и ссылки на артефакты |
| `events.jsonl` | События запуска, по одному JSON envelope в строке |
| `transcript.json` | Model/tool rounds и результаты каждого tool call |
| `lineage.scala` | Scala-проекция графа запуска |
| `lineage-data.json` | Данные и ссылки lineage schema v1 |

Verifier читает эти файлы, проверяет успешный результат и компилирует экспорт
`lineage.scala` настоящим pinned Scala compiler. Проекция показывает записанное
исполнение; это не восстановленный исходный Scala workflow и не durable resume.
`Succeeded` обозначает завершение runtime; assertions результата и checks нужны
отдельно. Accounting snapshot не подтверждает денежную стоимость провайдера.

Команда `replay` реализована, но её совместимость зависит от записанной структуры
campaign и recovery contract. Общие ограничения и целевая поверхность описаны в
[lineage](lineage.md); галерея не обещает, что любой CLI run можно продолжить или
успешно replay после завершения процесса.

## Подключение своей модели

У native CLI `coder` есть tools `fs_read`, `fs_search`, `fs_edit`, `fs_patch`,
`fs_tree`, `proc_run`. `scout`/`worker` в headless CLI имеют пустой tool registry;
название агента само по себе не создаёт другой набор tools. В live Scala REPL
зарегистрированы scout/worker/coder, backend и coding tools общие.

| Настройка | CLI | Scala REPL |
| --- | --- | --- |
| Backend | `--provider openai` или `anthropic` | `RAIDER_PROVIDER` |
| Endpoint | `--base-url` | `RAIDER_BASE_URL` |
| Model | `--model` для `coder` | `RAIDER_MODEL` |
| Credential | env `RAIDER_API_KEY` или helper `RAIDER_API_KEY_CMD` | те же env/helper |
| Workspace | `--workspace` | текущий cwd live REPL |

Для OpenAI-compatible нужен URL с подходящим `/v1` prefix; Anthropic transport
использует Messages wire. CLI при отсутствии credential возвращает configuration
error до dispatch. REPL при недоступном credential может объявить переход в
scripted mode; всегда читайте startup notice и marker ответа. Remote модель
требует отдельно выбранных endpoint/account/budget. Галерея их не использует.

Legacy Scala REPL имеет `:help`, `:jobs`, `:cancel`, `:log`, `:reset`, `:quit`.
Списки команд tab shell и Scala REPL различаются. `:load`, `:run`, `:trace` и
`:type` не являются готовыми пользовательскими командами этой Scala-консоли;
`job.send` возвращает unsupported. `:log` может иметь пустой trace для facade
job. Для наблюдаемости coding run используйте реальные CLI artifacts.

Все Scala/bundle/plugin и запускаемые repo commands — доверенный код с правами
процесса. Tool workspace checks не являются sandbox. Первое знакомство с coding
tools проводите на disposable workspace, как в опубликованном примере.
