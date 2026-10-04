<!-- Translated from Russian original. Key terms preserved as-is. -->

# DSL: повседневный путь в REPL

Статус: утверждаемый целевой API. Реализация пока отсутствует. Этот документ уточняет удобство поверх [полного контракта](product-repl.md); примеры должны стать compiler/PTY fixtures.

## 1. Первый ответ без настройки агента

```text
$ raider --mock
raider> scout.ask("Как устроен проект?")
```

В live режиме достаточно ранее настроенного provider profile. Предустановленные роли доступны сразу, startup показывает выбранный режим. `--mock` даёт scripted fixture ответ и явно маркирует его; никакой маскировки под реальную модель.

Пользователь изучает три verbs:

```scala
scout.ask("Вопрос")            // Ответ сейчас
val job = scout.start("Вопрос") // Работа в фоне
val answer = job.await         // Результат существующего запуска
```

Infix Scala syntax работает теми же methods:

```scala
scout ask "Найди основные интерфейсы"
val job = scout start "Исследуй retry"
```

Документация по умолчанию показывает dot syntax: он проще для completion и многострочного ввода. Infix verbs помечены `infix`, tests проверяют оба пути.

## 2. Управление рядом с job

```scala
val job = worker.start("Добавь tests для parser")
job.info
job.send("Учитывай UTF-8, разбитый по network chunks")
job.watch()
job.cancel()
```

`info` — небольшой безопасный snapshot. `watch` не запускает задачу заново. `cancel` возвращает подтверждённый/неподтверждённый результат, а не просто Unit. Receipt `send` сообщает queued/applied/rejected; доставка не обещает compliance модели.

ID доступен как `job.id`; `:jobs` и completion позволяют управлять потерянным binding через `:job/:watch/:cancel`. Последний foreground job также виден в jobs registry при compile/runtime error, даже если `val result = ...` не создал binding.

## 3. Цепочка в одну строку

```scala
(scout andThen planner andThen worker).ask("Добавь поддержку плагинов")
```

Все built-in quick profiles здесь string-in/string-out. `andThen` передаёт output предыдущего Program во input следующего. Original goal сохраняется как ограниченная root-context metadata, но полная история предыдущих агентов не копируется автоматически. Typed pipeline несовместимых inputs/outputs не компилируется.

Сохранить цепочку:

```scala
val implement = (scout andThen planner andThen worker).named("implement")
register(implement)
implement.ask("Добавь новый инструмент")
val job = implement.start("Добавь новый provider adapter")
```

`named` создаёт descriptor, `register` обновляет registry явно. При registration сохраняется snapshot версий stages; reload не меняет текущий job.

## 4. Несколько агентов одновременно

Общий вход:

```scala
val audit = all(scout, reviewer)
val (facts, critique) = audit.ask("Проверь архитектуру")
```

Разные входы:

```scala
val job = all(
  scout("Изучи routing"),
  reviewer("Изучи cache")
).start()

val (routing, cache) = job.await
```

Список задач:

```scala
val topics = List("routing", "cache", "streaming")
val job = batch(topics, parallelism = 2)(topic => scout(topic)).start()
val answers = job.await
```

По умолчанию группа fail-fast. Независимый сбор ошибок — явный `batchCollect(...).run()`. Везде results сохраняют тип и порядок; `all` tuple сохраняет разнотипные outputs.

## 5. Где начинается выполнение

```scala
val task = scout("Изучи проект") // Только описание
task                           // Краткий preview, без model calls
task.run()                     // Foreground
task.start()                   // Background
```

Это правило одинаково для Agent, Workflow и Session: `program(input)` создаёт Task; `ask/start` явно выполняют её. Не делать `agent(input)` иногда eager, иногда lazy в зависимости от импортов.

Если новичок вводит bare Task expression, printer показывает `Task[String](...; use .run() or .start())`. Renderer не выполняет задачу. Task construction is lazy относительно provider/tools, но обычная Scala вычисляет переданные input expressions.

`run(task)` и `spawn(task)` доступны как низкоуровневые launch functions; они delegates единому launch API. Нет отдельного lifecycle только для method syntax. Старые варианты `parallel/each/collect` в первом обсуждении не являются обязательным API: их заменяют `all/batch/batchCollect`, чтобы не плодить имена.

## 6. Свой агент без длинного boilerplate

```scala
val security = reviewer
  .named("security")
  .model("review")
  .appendInstructions("Особое внимание: auth и границы доверия")

security.ask("Проверь обработку session keys")
```

С нуля:

```scala
val researcher = agent("researcher")
  .model("fast")
  .instructionsFile("agents/researcher.md")
  .tools("fs.read", "fs.search")
```

Fluent API достаточен для MVP. Второй builder language с mutable contextual setters не добавлять до доказанной UX потребности.

## 7. Сложная координация без магии

```scala
val investigate = workflow[String, String]("investigate"): question =>
  scope:
    for
      research <- fork(researcher(question))
      code     <- fork(scout(question))
      plan     <- planner(question)
      sources  <- research.join
      facts    <- code.join
      report   <- writer(s"Задача: $question\n$plan\n$sources\n$facts")
    yield report

investigate.start("Где лучше добавить новый backend?")
```

Внутри workflow используются Task expressions и `fork/join`; outside — ask/start/await. Это обычная Scala, без rewriting block в асинхронный код. Смешивание blocking facade внутрь workflow обнаруживается guardrail/test; error объясняет, какой expression использовать.

## 8. Повторное общение

```scala
val chat = openSession(researcher)
chat.ask("Изучи варианты хранения")
chat.ask("Сравни два лучших")
val job = chat.start("Подготовь рекомендации")
job.await
chat.close()
```

Verb semantics не меняется: ask foreground, start background, apply Task. Turns одной conversation сериализованы. Это P1 capability, не обещание MVP.

## 9. Перенос в файл и CLI

В `workflows.raider.scala`:

```scala
import raider.dsl.*

val inspect = (agents.scout andThen agents.reviewer).named("inspect")
register(inspect)
```

`agents` и `register` в loaded scripts приходят через definition context, не import blocking facade. RAI-002 закрепляет mechanism `using DefinitionContext`, который REPL/CLI предоставляет одинаково. Bare library use передаёт этот context явно.

```text
raider> :load workflows.raider.scala
raider> inspect.ask("Оцени расширяемость")
raider> :spawn inspect "Оцени streaming"

$ raider compile workflows.raider.scala --output pipelines.jar
$ raider run --bundle pipelines.jar --workflow inspect --input "Оцени расширяемость"
```

Production runner исполняет bundle без compiler. `pipeline` добавляет к Workflow codecs и trusted checks: [CI DSL](ci-runtime.md). Прямые OpenAI/Anthropic profiles и Yabanin выбираются конфигурацией: [provider contract](provider-protocols.md). Тот же callable Program, тот же runtime. File loading выполняет trusted code, не replay command history автоматически.

## 10. Проверка удобства

Обязательные UX transcripts:

1. Первый вопрос без ручного создания agent.
2. Два background jobs и свободный prompt.
3. Chain из трёх stages в одной строке.
4. Fanout двух разных outputs без casts.
5. Batch с bounded parallelism.
6. Steering/cancellation рядом с job handle.
7. Save/load definitions и headless запуск того же workflow.
8. Compile error не теряет ранее созданные handles.
9. Bare Task объясняет, как её запустить, ничего не выполняя.
10. Import `raider.dsl.*` сам по себе не открывает runtime и не делает paid calls.

Эти fixtures обязательны для DSL/REPL gates. Первые семь задач должны выполняться по короткому встроенному `:help`, без чтения runtime architecture.
