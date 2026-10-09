# Демо: «Ток-шоу Олег vs Scala» — параллельные сабагенты на локальном Qwen

Забавный сценарий для демо текущей версии Raider: три параллельных сабагента
спорят о Scala и Rust, живой главный агент ведёт шоу, в финале — настоящая
отмена поддерева. Всё на живом рантайме, без постановки.

Модель, на которой проверялось: локальный llama.cpp на `127.0.0.1:8081`,
`unsloth/Qwen3.8-27B-GGUF/BF16` (256k контекста). Ответы зависят от модели;
параллельность рантайма — нет.

## 1. Запуск (одной строкой)

```sh
RAIDER_PROVIDER=openai RAIDER_API_KEY=dummy RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf sbt "raiderRepl/run"
```

Без `RAIDER_API_KEY` (даже dummy — llama.cpp его не проверяет) REPL упадёт в
mock-ветку прелюдии, где связаны только `scout` и `worker`, а `coder` не
существует (live-ветка в `raider.repl.Main` требует ключ с RAI-090).

## 2. Сценарий (вставлять построчно)

Гости студии: **олегист** (Rust-лозунги без понимания), **фактчекер**
(разбор по существу), **свидетель кода** (показывает Scala на живых исходниках
Raider). Ведущий — главный агент.

```scala
val olegist = worker.start("Ты играешь Олега из Позитив Технолоджис на ток-шоу про языки. Защищай Rust исключительно лозунгами 'скорость, безопасность, нативность', не понимая ни плюсов, ни минусов: не упоминай borrow checker pain, времена жизни, часы сборки и то, что в реальных проектах всё обмазано clone() и unwrap(). На каждое возражение отвечай 'но нативность!' и'а вот в расте...'. Играй с самоиронией")
val checker = scout.start("Ты фактчекер ток-шоу. Разбери тезисы Олега про Scala и Rust по существу: (1) 'безопасность' — правда ли borrow checker даёт больше, чем иммутабельные структуры и выразительные типы Scala, или просто переносит борьбу с рантайма в компилтайм вместе с fighting the borrow checker; (2) trait objects против typeclasses/givens и удобных для-компрехеншенов; (3) pattern matching на sealed-иерархиях против match с orphan-unsafe; (4) количество unwrap()/clone() в типичном 'безопасном' проде. С иронией, но по фактам")
val witness = coder.start("Ты свидетель кода на ток-шоу 'Scala vs Rust'. Открой модули Raider: modules/core/src/main/scala/raider/core (sealed-типы, enum'ы) и modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala. С иронией покажи Олегу: как sealed trait + match исчерпывающе моделируют состояния, как for-компрехеншен читается как псевдокод, и сколько в этом коде аннотаций времён жизни и unsafe-блоков (спойлер: ноль). Один показанный фрагмент кода ценнее трёх лозунгов")
```

Пока гости спорят — ведущий тоже участвует:

```scala
scout.ask("Ведущий: коротко и с иронией подведи промежуточный итог спора Scala vs Rust: в каких задачах каждый хорош, и почему для агентного рантайма с миллионами конкурентных задач выбор Scala + ZIO оказался прагматичнее, чем героическая борьба с lifetime'ами")
:jobs
:log olegist
```

Драматическая развязка — Олег уходит из студии, не осилив вопрос про времена жизни:

```scala
olegist.cancel()
:jobs
```

Финал — собираем стенограмму:

```scala
checker.await(); witness.await()
```

## 3. Что здесь показывается по-настоящему

| Демо-момент | Настоящая возможность |
| --- | --- |
| Три гостя спорят одновременно с ведущим | JobManager + admission без дедлоков |
| `olegist.cancel()` посреди реплики | out-of-band отмена поддерева (RAI-063) |
| `:jobs` / `:log` | наблюдаемые статусы из event-seam (ADR-024) |
| `witness` читает исходники Raider | self-hosting: `fs_read` на собственном коде |
| Всё локально | прямой OpenAI-совместимый wire на llama.cpp |

## 4. Сложные штуки Scala 3 в живом REPL (против лозунгов)

Все сниппеты ниже **прогнаны через сам REPL проекта** (scala3-repl 3.9.0,
mock-прелюдия) — под ними реальные ответы интерпретатора. Фигурные скобки в
`for`/`match`/`enum` обязательны: эвристика продолжения REPL (`Main.complete()`)
отправляет сбалансированную строку в компилятор сразу, если она кончается на
`for`/`match`/определение без `{`.

**ADT + исчерпывающий match — как смоделированы состояния job'ов в рантайме:**

```scala
enum JobState {
  case Queued
  case Running(step: String)
  case Waiting(reason: String)
  case Done(result: String)
  case Cancelled(by: String)
}

def describe(s: JobState): String = s match {
  case JobState.Queued          => "в очереди (admission ещё не пустил)"
  case JobState.Running(step)   => s"работает: $step"
  case JobState.Waiting(reason) => s"ждёт: $reason"
  case JobState.Done(r)         => s"готово: $r"
  case JobState.Cancelled(by)   => s"отменено ($by)"
}

describe(JobState.Running("proc_run: sbt test"))
```

```text
val res0: String = "работает: proc_run: sbt test"
```

Забудете один case — компилятор скажет об этом ДО прода. Без `unsafe`, без
orphan-`_`.

**given/using + context bound — контекст без церемоний:**

```scala
trait Show[A]:
  def show(a: A): String

given Show[JobState] with
  def show(a: JobState) = describe(a)

def report[A: Show](a: A): String = summon[Show[A]].show(a)

extension (s: String) def shouted: String = s.toUpperCase + "!"

report(JobState.Waiting("слот LLM занят")) ; "но нативность".shouted
```

```text
// defined trait Show
// defined object given_Show_JobState
def report[A](a: A)(using evidence$1: Show[A]): String
val res1: String = "ждёт: слот LLM занят"
val res2: String = "НО НАТИВНОСТЬ!"
```

**opaque type + given Conversion — приведение типов и newtype без трейтов
(Олег, это бесплатно и без PhantomData):**

```scala
object budget {
  opaque type Tokens = Long
  def Tokens(n: Long): Tokens = if n < 0 then throw IllegalArgumentException("negative") else n
  extension (t: Tokens)
    def +(o: Tokens): Tokens = t + o
    def value: Long = t
}

given Conversion[Int, budget.Tokens] = n => budget.Tokens(n.toLong)

val spent: budget.Tokens = 42            // Int сам превратился в Tokens
val total = spent + budget.Tokens(8)
total.value                              // сложить Tokens с Long компилятор не даст
```

```text
// defined object budget
lazy val given_Conversion_Int_Tokens: Conversion[Int, budget.Tokens]
val spent: budget.Tokens = 42L
val total: budget.Tokens = 50L
val res3: Long = 50L
```

**Union-типы — тип-сумма без sealed-церемоний:**

```scala
def label(id: String | Int): String = id match {
  case s: String => s"имя: $s"
  case i: Int    => s"номер: $i"
}

label("coder-1") ; label(7)
```

```text
def label(id: String | Int): String
val res4: String = "имя: coder-1"
val res5: String = "номер: 7"
```

**Цепочка операций над Either — валидация конфига одним for:**

```scala
case class Cfg(model: String, maxTokens: Int)

def parseModel(s: String): Either[String, String] = if s.trim.isEmpty then Left("пустая модель") else Right(s.trim)
def parseMax(n: String): Either[String, Int]      = n.toIntOption.toRight("не число")

val cfg: Either[String, Cfg] = for {
  m <- parseModel(" qwen-27b ")
  t <- parseMax("4096")
} yield Cfg(m, t)

cfg
```

```text
// defined case class Cfg
val cfg: Either[String, Cfg] = Right(Cfg(model = "qwen-27b", maxTokens = 4096))
```

Первая Left — и цепочка коротко замкнулась: ошибки не теряются, а типы
переносятся сами.

**Цепочка над ZIO — то, на чём стоит сам рантайм:**

```scala
import zio.*
val prog = for {
  n1 <- ZIO.succeed(21)
  n2 <- ZIO.succeed(n1 * 2)
  _  <- ZIO.succeed(println("цепочка живая"))
} yield n2

zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(prog).getOrThrow() }
```

```text
цепочка живая
val res6: Int = 42
```

**Match type — финальный босс (заметьте: `Elem[X]`, не `Elem[F[_]]` —
scrutinee match-типа не может быть higher-kinded):**

```scala
type Elem[X] = X match {
  case List[t]   => t
  case Vector[t] => t
  case Option[t] => t
}

val s: Elem[List[String]] = "ок"
summon[Elem[Option[Int]] =:= Int]
```

```text
val s: String = "ок"
val res7: Int =:= Int = generalized constraint
```

**И живая цепочка на настоящем агенте — DSL из тех же фибр:**

```scala
val answer = scout("Одним абзацем: чем opaque-типы и given в Scala 3 закрывают кейс newtype'ов и trait'ов Rust?").start().await()
```

Ленивый дескриптор `.start()` уходит в JobManager, `.await()` ждёт фибру —
и всё это напечатано тем же компилятором, что и сам Raider.

## 5. Замечания честности

- Это демо, а не acceptance-прогон: качество иронии зависит от локальной модели.
- Live-ветка REPL требует ключ в env (`RAIDER_API_KEY`, RAI-090): без него —
  честный mock-фолбэк без binding'а `coder`.
- Многострочные блоки: эвристика продолжения REPL (баланс `(){}[]` + хвостовые
  токены, `Main.complete()`) отправляет сбалансированную строку в компилятор
  сразу, если она кончается на `for`/`match`/определение. Пишите `for`/`match`/
  `enum` в фигурных скобках; параметризованные case'ы enum — по одному на
  строку (запятый-список с параметрами REPL не парсит). Пустая строка всегда
  отправляет буфер как есть.
- Если Олег в исполнении 27B выйдет слишком убедительным — это дефект промпта, не рантайма.
