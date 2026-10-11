# Демо «Райдер для скалиста» — типы до токенов, фибры вместо тредов

Целевая аудитория: Scala-разработчик, который пока не понял, зачем ему агент.
Главный номер — **не «агент ответил текстом», а то, что агентский API — это
типизированная Scala-библиотека в живом REPL с in-process dotc 3.9**: ошибки
ловятся компилятором ДО вызова модели, fan-out — обычные коллекции поверх
ZIO-фибр, и весь код агента живёт в том же JVM. Всё ниже прогнано через
`raider.repl.Main` — ответы интерпретатора настоящие.

## Акт 0 — мощь Scala за 90 секунд (REPL)

Запуск (живой Qwen):

```sh
RAIDER_PROVIDER=openai RAIDER_API_KEY=dummy RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf sbt "raiderRepl/run"
```

**1. Компилятор стоит между вами и счётом за токены.** Опечатка в аргументе —
и модель НЕ вызывается вообще:

```scala
scout(123)
```

```text
-- [E007] Type Mismatch Error: -------------------------------------------------
1 |scout(123)
  |      ^^^
  |      Found:    (123 : Int)
  |      Required: String
1 error found
```

В Python-агентах такая ошибка — это HTTP 500 в 3 часа ночи. Здесь её не
существует: она не скомпилировалась.

**2. Fan-out — это просто List поверх ZIO-фибр.** Три субагента стартуют
одновременно, await — map, ответы — `List[String]`, то есть обычные значения:

```scala
val t0 = System.nanoTime()
val jobs = List(
  scout("1: назови один плюс ZIO-фибр").start(),
  scout("2: назови один плюс иммутабельности").start(),
  scout("3: назови один плюс sealed-типов").start()
)
val answers = jobs.map(_.await())
val wall = (System.nanoTime() - t0) / 1000000000
("скорость", "безопасность", "нативность").map(p => scout(s"Олег из Позитив Технолоджис хвалит Rust словом '$p', не понимая плюсов и минусов: разбери с иронией, где лозунг, а где правда, и чем отвечает Scala По итогу у тебя должен получиться исекай аниме сюжет про Олега в мире хайпожорства").start()).map(_.!!).zipWithIndex.map((a, i) => s"[${i + 1}] ${a.take(60)}").mkString("\n")
```

```text
val jobs: List[raider.runtime.jobs.JobHandle[String]] = List(...)
val answers: List[String] = List(...)
val wall: Double = 0.251670125
List("скорость", "безопасность", "нативность").map(p => scout(s"Олег из Позитив Технолоджис хвалит Rust словом '$p', не понимая плюсов и минусов: разбери с иронией, где лозунг, а где правда, и чем отвечает Scala По итогу у тебя должен получиться исекай аниме сюжет про Олега в мире хайпожорства").start()).map(_.!!).zipWithIndex.map((a, i) => s"[${i + 1}] ${a.take(60)}").mkString("\n")

```

Никакого `Promise.all`, никакого stringly-typed API: `.start()` возвращает
`JobHandle[String]`, и дальше это обычный Scala-код — `grouped`, `take`,
`mkString`, ваша фантазия. На живой модели поставьте 5–8 задач — wall-clock
покажет честный параллелизм (admission ограничит только LLM-слоты, а не вас).

**3. In-process компилятор.** REPL компилирует введённое тем же dotc 3.9,
которым собран сам Raider — включая enum/given/opaque/match type
(проверенные примеры: `docs/demos/repl-oleg-parallel-demo.md` §4). Реплика для
скептика: «агент, который написан на Scala и компилируется компилятором,
который ты сейчас используешь как чат».

## Акт 1 — self-hosting: агент работает на своём же коде

Агент-кодер читает собственный `AgentLoop.scala` и объясняет admission:

```sh
RAIDER_API_KEY=dummy java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent coder --provider openai --base-url http://127.0.0.1:8081/v1 --model unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf --input "Прочитай modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala и в двух предложениях объясни, как admission ограничивает параллелизм LLM-вызовов" --out artifacts/scala-pro-demo
```

`RAIDER_API_KEY=dummy` — llama.cpp ключ не проверяет; CLI требует
credential-preflight по дизайну (секрет никогда не в argv).

Настоящий ответ агента (живой прогон 2026-10-09):

```text
Admission реализует пул permits как bounded ZIO Queue[Unit] (modelSlots),
размер которого задаёт максимум одновременных LLM-вызовов…
```

След агента — типизированный и машиночитаемый (покажите скептику):

```sh
cat artifacts/scala-pro-demo/work-*/run.json | python3 -m json.tool | head -20
```

`run.json` (валидация схемы до записи, атомарная запись temp+rename),
`events.jsonl` (таймлайн job-состояний), `summary.md` (человекочитаемо),
`transcript.json` (trace модель-цикла). Exit-коды типизированы: 0/10/20/21/22/23/24/25/26/27/130/143 — CI различает «отказ проверки» и «сбой рантайма» без парсинга строк.

## Акт 2 — агент гоняет sbt на самом себе (мутирующий круг)

```sh
RAIDER_API_KEY=dummy java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent coder --provider openai --base-url http://127.0.0.1:8081/v1 --model unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf --input "Выполни proc_run: sbt --batch core/test, затем одним абзацем: сколько тестов прошло, сколько упало, и что из упавшего видно в выводе" --out artifacts/sbt-selftest
```

proc_run парсит вывод sbt — агент видит структурированный результат компиляции
и тестов, а не стену текста.

## Акт 3 — REPL: параллельная разведка тремя субагентами

```sh
RAIDER_PROVIDER=openai RAIDER_API_KEY=dummy RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf sbt "raiderRepl/run"
```

Внутри (построчно):

```scala
val a = scout.start("Прочитай modules/core/src/main/scala/raider/core и перечисли все enum/sealed-иерархии с назначением в одну строку каждая")
val b = coder.start("Прочитай modules/tools/src/main/scala/raider/tools и объясни, как fs_edit гарантирует атомарность патча")
val c = worker.start("Прочитай modules/provider-chat и нарисуй в тексте путь SSE-чанка от сокета до ChatChunkParser")

a.await(); b.await(); c.await()
:jobs
```

Сам REPL — настоящий in-process компилятор Scala 3.9: то, что вы печатаете,
компилируется тем же dotc, что и сам Raider. Дополнительные сложные штуки —
`docs/demos/repl-oleg-parallel-demo.md` §4 (opaque/given/union/match type —
все прогнаны через этот REPL).

## Почему скалисту это интересно (честная версия)

| Что видит скалист | Что под этим |
| --- | --- |
| `scout(123)` не компилируется | типизированный DSL, ошибки до spend (E007) |
| `List(...).map(_.start())` — fan-out | ZIO-фибры, JobHandle[String], admission |
| REPL = in-process dotc 3.9 | repl-engine, тот же компилятор, что собирает Raider |
| Агент работает на своём коде | self-hosting: fs_read/proc_run на модулях Raider |
| Типизированные exit-коды, JSONL-события | `result.scala`, schema-валидация артефактов |
| Прямой OpenAI/Anthropic wire | без gateway; локальный Qwen, ноль облака |

## Замечания честности

- Акт 0 прогнан через REPL (mock-прелюдия) — тайминги и типы настоящие; на живой модели числа будут живыми.
- Акты 1 проверен на живом Qwen: `Succeeded/exit 0`, 11 раундов, артефакты `artifacts/scala-pro-demo/work-9c36a7b6/`.
- Jar собран 2026-10-08; если меняли CLI — сначала `make raider-jar`.
- Качество ответов зависит от локальной модели; типы/параллельность/бюджет/артефакты — свойства рантайма и от модели не зависят.
- Акт 2 запускает настоящий `sbt test` — первый прогон включает холодный старт sbt.
- Не пишите `for (x <- agentString)` — for по String итерирует Char (компилятор честно это покажет).
worker("Сложи в один вердикт для Олега: " + List("скорость", "безопасность", "нативность").map(p => scout(s"Правда или лозунг в тезисе Олега 'Rust = $p'? Одна фраза").start()).map(_.!!).mkString(" | ")).start().!!