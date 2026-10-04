<!-- Translated from Russian original. Key terms preserved as-is. -->

# Product, REPL и DSL

Все API здесь — целевой контракт. Сигнатуры уточняются RAI-002 и затем фиксируются compile fixtures. Документ имеет приоритет над старыми примерами из обсуждения.

## 1. Основная модель использования

Основной product mode — CI runner по [CI-контракту](ci-runtime.md). REPL — среда локальной разработки того же Program/Task/runtime, с подготовленным prelude. Это не чат с текстовым парсером, распознающим строки, похожие на Scala.

Пользователь запускает `raider` или `raider repl`. Рабочая директория берётся из cwd, override — `--workspace`. Prelude импортирует `raider.dsl.*`, `raider.repl.*`, длительности и безопасные локальные defaults; показывает workspace, session ID, backend/profile, connectivity и режим mock/live. Ключи не показывает. Готовые string-профили `assistant`, `scout`, `planner`, `worker`, `reviewer`, `researcher`, `writer` доступны из prelude. Registry и inventory показывают их permissions; coding tools могут требовать explicit policy. Настраивать новый agent перед первым вопросом не обязательно.

Самый короткий пользовательский путь:

```scala
scout.ask("Как устроена авторизация?")
val job = scout.start("Проверь обработку ошибок")
job.send("Сначала изучи сетевые границы")
val result = job.await
(scout andThen reviewer).ask("Оцени архитектуру")
```

Подробные UX решения: [Удобство DSL](dsl-experience.md). `.ask` и `.start` — explicit REPL extension methods над одинаковым Program contract; никаких macro/parser tricks.

```scala
val scout = agent("scout")
  .model("fast")
  .instructions("Исследуй проект и приведи ссылки на файлы")
  .tools("fs.read", "fs.search")

val task = scout("Как устроена авторизация?")
// task: Task[String]. Network/tool execution ещё не было.

val answer = task.run()
// Foreground: показывает поток результата, ожидает завершения, возвращает String.

val job = scout.start("Проверь обработку ошибок")
// Background: немедленно возвращает Job[String], ввод следующей команды доступен.

val result = job.await
```

Промпт необязателен: `agent("name")` получает небольшой общий prompt. `.instructions(text)` заменяет его, `.appendInstructions(text)` добавляет раздел. Agent descriptor immutable; новые параметры применяются только к новым запускам.

`model("fast")` означает именованную роль из конфигурации Raider, а не гарантированное имя модели у провайдера. Можно задать provider/model явно. Без live credentials команда запуска сообщает actionable configuration error; ничего не оплачивает и не использует молча другой аккаунт.

## 2. Разделение описания и выполнения

| API | Эффект в момент вызова | Результат |
| --- | --- | --- |
| `agent(name)` и `.tools/.model/.instructions` | Только построение descriptor | `Agent[String, String]` |
| `a(input)` | Построение задачи; сам input вычисляется обычной Scala | `Task[O]` |
| `task.map/flatMap` | Построение вычисления | `Task[B]` |
| `a.ask(input)` | Создать root job и ждать с streaming | `O` или `RunFailed` |
| `a.start(input)` | Создать session-owned root job | `Job[O]` |
| `task.run()` / `task.start()` | Запустить подготовленную задачу foreground/background | `A` / `Job[A]` |
| `a andThen b` | Построить typed pipeline: результат A становится входом B | `Program[I, P]` |
| `job.await / .send / .cancel / .watch / .info` | Управление существующим job | Typed result/receipt/snapshot |
| `run(task)` | Создать root job и ждать его завершения | `A` или понятное исключение `RunFailed` |
| `spawn(task)` | Создать session-owned root job | `Job[A]` |
| `await(job)` | Ожидать существующий job; не запускать заново | `A` или `RunFailed` |
| `cancel(job)` | Запросить отмену и дождаться подтверждения в пределах deadline | `CancelResult` |
| `send(job, text)` | Добавить steering message в mailbox | `MessageReceipt` |
| `inspect(job)` | Прочитать snapshot состояния | `JobSnapshot` |
| `watch(job)` | Наблюдать events до завершения/отмены наблюдения | `Unit` |
| `all(a, b)` для Tasks | Описать параллельные разнотипные задачи | `Task[(A, B)]` |
| `all(a, b)` для Programs | Построить fanout с общим входом | `Program[I, (A, B)]` |
| `fork(task)` внутри workflow | Описать создание дочернего job | `Task[Child[A]]` |
| `child.join` | Описать ожидание ребёнка | `Task[A]` |

`ask/start`, Task/Job launch/control extensions и `run/spawn/await/send/cancel` входят в REPL facade. Functions — низкоуровневый launch/control путь, а не второй набор implementations. В headless/library code есть асинхронный `Runtime` API, возвращающий ZIO effects; facade не импортируется по умолчанию в workflows/plugins. Не применять blocking facade из fibers. Каждая facade операция делегирует одному canonical runtime method; outcomes, scopes и events совпадают.

Построение descriptor не обязано быть совершенно без I/O: `.instructionsFile(path)` может явно прочитать локальный файл. Оно не вызывает модель и не выполняет agent tools. `.instructionsFile` фиксирует содержимое/hash при регистрации версии; running job не читает файл заново посреди выполнения.

Повторный `run(task)` создаёт новый root ID и выполняет задачу снова. Повторный `await(job)` возвращает сохранённый outcome без повторения действий. Snapshot/REPL history не должны неявно перезапускать задачи.

## 3. Настоящая интерактивность

Обязательно поддержать:

- variables и последующее использование их результатов;
- `def`, `case class`, pattern matching, коллекции, `for`;
- многострочный ввод и bracketed paste;
- autocomplete доступных Scala symbols плюс агентные/tool/model names в meta-командах;
- history с управляемым сохранением и исключением секретов;
- сохранение корректных bindings после compile error и runtime error;
- foreground streaming и background summary без разрушения редактируемой строки;
- печать дескрипторов/jobs кратко, без prompts, секретов, гигантских transcripts;
- portable no-color/non-TTY режим и structured JSON output для CLI;
- загрузку файла сценария и запуск зарегистрированных workflows;
- `help` с рабочими примерами, а не со списком внутренних классов.

Compiler state обрабатывается последовательно. Background jobs не изменяют его и не выполняют compiler API. Completion и evaluation синхронизируются адаптером REPL engine.

## 4. Запуск всего набора задач

Короткий путь для разнотипных результатов:

```scala
val (facts, review) = all(
  scout("Найди важные интерфейсы"),
  reviewer("Оцени архитектуру")
).run()
```

Для группы одного типа с ограничением параллельности:

```scala
val topics = List("auth", "cache", "routing", "streaming")
val checks = batch(topics, parallelism = 2)(topic => scout(topic)).run()
```

`batch` возвращает результаты в порядке входа, не порядке завершения. Пустая коллекция возвращает пустую коллекцию без model calls. Invalid parallelism отклоняется до запуска. Default failure policy — fail-fast с отменой остальных и cleanup.

Для независимых проверок:

```scala
val outcomes = batchCollect(topics, parallelism = 2)(topic => scout(topic)).run()
```

`batchCollect` возвращает `List[Outcome[String]]`, сохраняет failures, но внешняя отмена всё равно останавливает всю группу. `raceSuccess` и `quorum` — P2; не добавлять их в MVP вместо обязательного `all/batch/batchCollect`.

Для одного входа нескольким агентам:

```scala
val audit = all(scout, reviewer)
val (facts, critique) = audit.ask("Оцени расширяемость проекта")
```

`all` overloads для Tasks и Programs разделены по erased signatures; compile fixtures проверяют inference и mixed input rejection. В MVP arity 2; beta 3/4 при такой же type safety. Не приводить heterogeneous results к `List[Any]`.

## 5. Workflow из REPL и файла

```scala
val inspectProject = workflow[String, String]("inspect"): question =>
  for
    findings <- scout(question)
    review   <- reviewer(findings)
  yield review

register(inspectProject)
val result = inspectProject.ask("Оцени расширяемость")
```

Explicit `register` добавляет immutable versioned definition в registry. Создание workflow не изменяет registry. Новый registration того же имени создаёт новую версию; старые jobs используют snapshot предыдущей версии.

Управляемые дети:

```scala
val investigate = workflow[String, String]("investigate"): question =>
  scope:
    for
      a     <- fork(scout(question))
      b     <- fork(researcher(question))
      plan  <- planner(question)
      facts <- a.join
      docs  <- b.join
      text  <- writer(s"План: $plan\nФакты: $facts\nИсточники: $docs")
    yield text

val job = investigate.start("Как добавить плагины?")
```

`scope` создаёт область владения детьми. Выход из неё отменяет и завершает незавершённых детей; default не делает бесконечный implicit join. `all/batch` сами ожидают свои ветви. Вся root task также получает scope, поэтому `fork` безопасен и без вложенного `scope`.

Файл `workflows.raider.scala` содержит Scala declarations и explicit registrations. `:load` **исполняет пользовательский Scala-код**, не является sandbox и не гарантирует отсутствие side effects. Для обычных сценариев в файле держат definitions, а запуск делают отдельно. `:load` не должен автоматически replay history.

После загрузки:

```text
:workflows
:run inspect "Оцени проект"
:spawn investigate "Исследуй провайдеры"
```

Meta-команды с string input передают остаток аргумента как текст согласно своей грамматике, не делают Scala interpolation. Typed input идёт через `--input-json` или Scala expression.

## 6. Настройки запуска

```scala
val task = scout("Проверь проект")
  .within(2.minutes)
  .limit(Limits(maxSteps = 10, maxLlmAttempts = 12, maxChildren = 3))
  .tag("task", "RAI-017")

val job = task.start()
```

Настройки задачи задают верхнюю границу; global/session/parent policy может её только ужесточить. Deadlines включают очередь, retries, tools и cleanup grace отдельно. Agent defaults < task options < обязательные ceilings; overrides не расширяют полномочия.

Root budget задаётся настройкой сессии/launch options. Все descendants расходуют один root ledger. Отдельный child cap ограничивает его долю и не создаёт новые деньги/attempts.

## 7. Типизированный путь

```scala
case class Question(text: String)
case class Finding(path: String, explanation: String)
case class Findings(items: List[Finding])

// В реальном примере здесь также объявлены Codec/Schema instances.
val typedScout = agent[Question, Findings]("typed-scout")
  .instructions("Верни находки по заданной схеме")
  .tools("fs.read", "fs.search")

val findings: Findings = typedScout.ask(Question("Найди публичные API"))
```

Команда имеет входной/выходной codec и schema. Нет runtime `asInstanceOf` на пользовательском пути. Неверный JSON, неподдержанная schema и исчерпанный repair budget дают explicit error. Импортированные Markdown agents сначала `Agent[String,String]`; `.output[Findings]` добавляет проверяемую схему, не обещает её соблюдать одной инструкцией.

Первый публичный sugar — строки. Typed agents/tools входят в beta и обязательны для [working coding agent](working-product.md); их инфраструктурные contracts нужны раньше, чтобы не переписывать ядро.

## 8. Продолжительное общение

В MVP `job.send(text)` — steering работающего запуска. Это не новый model call и не отдельный turn; runtime применяет сообщение на следующей безопасной границе между шагами. Functional `send(job,text)` вызывает тот же метод service.

Receipt содержит message ID, sequence, состояние `queued/applied/rejected`. Если job завершился между enqueue и consume, message получает rejected/finalized status и не теряется молча. Модель не гарантирует выполнение просьбы; receipt подтверждает доставку в контекст.

Session API реализуется в P1 и обязателен для [working coding agent](working-product.md), хотя не блокирует первоначальный runtime M0:

```scala
val chat = openSession(researcher)
val first = chat.ask("Исследуй варианты хранения")
val second = chat.ask("Сравни два лучших")
chat.close()
```

Session implements Program-style application: `chat(input)` возвращает Task, `chat.ask(input)` запускает foreground, `chat.start(input)` ставит turn в фон. Turns одной session сериализуются, cancellation одного turn не стирает прошлую историю. Session хранит отдельный conversation context, не mutates `Agent`. На overflow mailbox — bounded wait/error; не бесконечный рост памяти. В v1 нет автоматической замены interactive facade магическим direct-style macro.

## 9. Meta-команды

| Команда | Целевое поведение | Этап |
| --- | --- | --- |
| `:help [topic]` | Короткие примеры, topic lookup | MVP |
| `:agents`, `:tools`, `:models` | Registry/model-role inventory | MVP |
| `:jobs [--all]` | ID, owner, status, age, limits, краткий cost status | MVP |
| `:job ID` | Snapshot плюс причина остановки | MVP |
| `:watch ID` | Поток events, Ctrl+C прекращает наблюдение | MVP |
| `:await ID` | Ждать outcome существующего job | MVP |
| `:cancel ID` | Отмена с подтверждением/неопределённостью | MVP |
| `:send ID TEXT` | Steering message | MVP |
| `:load PATH` | Evaluate trusted local script | MVP |
| `:workflows` | Зарегистрированные definitions/версии | MVP |
| `:run NAME TEXT` | Foreground workflow | MVP |
| `:spawn NAME TEXT` | Background workflow | MVP |
| `:doctor` | Toolchain/runtime/provider checks без оплат по умолчанию | MVP |
| `:history` | History без автоматического исполнения | MVP |
| `:reset` | Закрыть текущую runtime session, отменить jobs, создать новую и reset compiler | MVP |
| `:exit` | Graceful shutdown, подтвердить отсутствие owned jobs | MVP |
| `:session save NAME` | Сохранить registry references, outcomes и transcript metadata | P1 |
| `:session open NAME` | Открыть архив без запуска кода; jobs как исторические records | P1 |
| `:export PATH` | Сохранить reviewable Scala history/script, без автоматического replay | P1 |
| `:replay RUN_ID` | Deterministic fixture replay без сетевых/мутационных эффектов | P1 |
| `:type`, `:imports` | Compiler-native introspection через engine adapter | MVP |

`:run` получает только registered workflow, не arbitrary shell command. `:load` расширяет `~`, принимает quoted path, показывает локальную ошибку с line/column. Не использовать shell для разбора путей.

## 10. Ctrl+C и завершение

| Контекст | Первый Ctrl+C |
| --- | --- |
| Редактирование команды | Очистить текущий ввод, jobs продолжаются |
| `agent.ask(input)` / `task.run()` / `run(task)` | Отменить foreground root и его детей, затем вернуть prompt |
| `job.await` / `await(existingJob)` / `:await` | Прекратить ожидание; job остаётся работать |
| `job.watch()` / `watch(job)` / `:watch` | Отписаться; job остаётся работать |
| Pending approval | Не разрешать действие; отменить foreground или закончить observer согласно контексту |

Повторный Ctrl+C во время shutdown может завершить процесс после bounded grace; pending remote actions сохраняются как uncertain/interrupted, не succeeded. Нельзя `Thread.stop`, ловить cancellation и продолжать agent loop или ждать бесконечно финализатор.

MVP: закрытие REPL отменяет session-owned jobs. Background означает «не мешает следующему вводу», а не «переживает выход из процесса». Daemon/remote attachment — отдельный P2.

Произвольная Scala может выполнять бесконечный CPU loop или native blocking code. Spike обязан проверить interruptibility pinned REPL. Если безопасно остановить arbitrary evaluation невозможно, runtime cancellation всё равно работает, а escape path завершает REPL процесс; обещание сохранить bindings после такого hard stop не даётся.

## 11. Терминальное представление

При background spawn печатать одну строку с job ID и возможностью `:watch`. Ввод следующей команды не ждёт model/network.

```text
Job[String](j_018, queued, owner=session:s_03)
```

Summary обновления выводить coalesced, не каждый token всех jobs одновременно. `:watch` выбирает один stream; critical state changes не теряются. TerminalRenderer — единственный владелец terminal writes; slow renderer не останавливает бюджет/accounting state machine.

`run` streams текст, затем возвращает значение без повторного полного вывода того же длинного текста. Pretty printer для Task/Agent/Job не вызывает эффекты. No-color output не содержит ANSI; JSON stdout не смешивается с логами, stderr предназначен для diagnostics.

## 12. CLI и переносимость

Целевые команды:

```text
raider repl --mock
raider repl --profile direct
raider repl --profile claude
raider repl --profile yabanin
raider compile workflows.raider.scala --output pipelines.jar
raider run --bundle pipelines.jar --workflow inspect --input "Оцени проект"
raider run --bundle pipelines.jar --workflow typed-inspect --input-json input.json
raider run --bundle pipelines.jar --workflow inspect --input "Оцени проект" --json
raider doctor --profile yabanin
raider test examples/ --mock
```

Production CI запускает compiled trusted bundle без compiler; source evaluation — dev mode через тот же pinned engine. Подробные CLI/exits/artifacts/trust: [CI contract](ci-runtime.md); подключения без gateway: [provider contract](provider-protocols.md). Пустой stdin, EOF, compile error, missing workflow и wrong input codec дают стабильные exit codes. Headless invocation не спрашивает разрешение интерактивно: потребуются explicit policy/capabilities; отсутствие решения — error, не auto-allow.

Все документированные examples должны исполняться через fake backend в docs gate и выбранные — через реальный Scala REPL. Переписать пример, чтобы замаскировать дефект REPL, без обновления контракта нельзя.
