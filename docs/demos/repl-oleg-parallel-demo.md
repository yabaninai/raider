# Historical demo: “Oleg vs Scala Talk Show” — Parallel Subagents on Local Qwen

**Archive, captured against the 2026-10-09 local Qwen setup.** The command,
model-specific responses, and examples below preserve that recorded run; they
are not a current launch recipe or evidence of a fresh run against today's
source. For current setup and runnable examples, use the [current launch
guide](../user-guide.md) and [verified REPL/bundle gallery](../user-guide-repl.md).

A playful recorded scenario: three parallel subagents
argue about Scala and Rust, the live main agent hosts the show, and the finale has a real
subtree cancellation. Everything runs on the live runtime, without staging.

Model used for verification: local llama.cpp at `127.0.0.1:8081`,
`unsloth/Qwen3.8-27B-GGUF/BF16` (256k context). Responses depend on the model;
runtime concurrency does not.

## 1. Run (one line)

```sh
RAIDER_PROVIDER=openai RAIDER_API_KEY=dummy RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf sbt "raiderRepl/run"
```

Without `RAIDER_API_KEY` (even a dummy value — llama.cpp does not validate it), the REPL
falls back to the mock prelude branch, where only `scout` and `worker` are bound and `coder`
does not exist (the live branch in `raider.repl.Main` requires a key per RAI-090).

## 2. Scenario (paste one line at a time)

Studio guests: **Olegist** (Rust slogans without understanding), **fact-checker**
(substantive analysis), and **code witness** (shows Scala in Raider's actual source code).
The host is the main agent.

```scala
val olegist = worker.start("Play Oleg from Positive Technologies on a programming-language talk show. Defend Rust using only the slogans 'speed, safety, native performance', without understanding either the pros or cons: don't mention borrow-checker pain, lifetimes, build times, or that real projects are covered in clone() and unwrap(). Answer every objection with 'but it's native!' and 'well, in Rust...'. Play it with self-irony")
val checker = scout.start("You're the talk show's fact-checker. Analyze Oleg's Scala and Rust claims on their merits: (1) 'safety' — does the borrow checker really provide more than Scala's immutable data structures and expressive types, or does it just move the struggle from runtime to compile time along with fighting the borrow checker; (2) trait objects versus typeclasses/givens and convenient for-comprehensions; (3) pattern matching on sealed hierarchies versus match with orphan-unsafe; (4) the number of unwrap()/clone() calls in typical 'safe' production code. Be ironic, but stick to the facts")
val witness = coder.start("You're the code witness on a 'Scala vs Rust' talk show. Open Raider modules: modules/core/src/main/scala/raider/core (sealed types, enums) and modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala. Show Oleg, with irony: how sealed trait + match model states exhaustively, how a for-comprehension reads like pseudocode, and how many lifetime annotations and unsafe blocks this code contains (spoiler: zero). One code snippet is worth more than three slogans")
```

While the guests debate, the host also joins in:

```scala
scout.ask("Host: briefly and ironically summarize the Scala vs Rust debate so far: what kinds of tasks each is good for, and why Scala + ZIO proved more practical for an agent runtime with millions of concurrent tasks than a heroic struggle with lifetimes")
:jobs
:log olegist
```

The dramatic finale: Oleg leaves the studio, unable to handle a question about lifetimes:

```scala
olegist.cancel()
:jobs
```

Finale — collect the transcript:

```scala
checker.await(); witness.await()
```

## 3. What This Actually Demonstrates

| Demo moment | Actual capability |
| --- | --- |
| Three guests debate the host at the same time | JobManager + deadlock-free admission |
| `olegist.cancel()` mid-speech | out-of-band subtree cancellation (RAI-063) |
| `:jobs` / `:log` | observable statuses from the event seam (ADR-024) |
| `witness` reads Raider source code | self-hosting: `fs_read` on its own code |
| Everything runs locally | direct OpenAI-compatible wire to llama.cpp |

## 4. Scala 3 Features in the Live REPL (Against the Slogans)

All snippets below were **run through the project's own REPL** (scala3-repl 3.9.0,
mock prelude); the actual interpreter responses appear below them. Braces in
`for`/`match`/`enum` are required: the REPL continuation heuristic (`Main.complete()`)
sends a balanced line to the compiler immediately if it ends in
`for`/`match`/a definition without `{`.

**ADT + exhaustive match — how job states are modeled in the runtime:**

```scala
enum JobState {
  case Queued
  case Running(step: String)
  case Waiting(reason: String)
  case Done(result: String)
  case Cancelled(by: String)
}

def describe(s: JobState): String = s match {
  case JobState.Queued          => "queued (admission has not let it through yet)"
  case JobState.Running(step)   => s"running: $step"
  case JobState.Waiting(reason) => s"waiting: $reason"
  case JobState.Done(r)         => s"done: $r"
  case JobState.Cancelled(by)   => s"cancelled ($by)"
}

describe(JobState.Running("proc_run: sbt test"))
```

```text
val res0: String = "running: proc_run: sbt test"
```

Forget one case and the compiler tells you BEFORE production. No `unsafe`, no
orphan `_`.

**given/using + context bound — context without ceremony:**

```scala
trait Show[A]:
  def show(a: A): String

given Show[JobState] with
  def show(a: JobState) = describe(a)

def report[A: Show](a: A): String = summon[Show[A]].show(a)

extension (s: String) def shouted: String = s.toUpperCase + "!"

report(JobState.Waiting("LLM slot is occupied")) ; "but it's native".shouted
```

```text
// defined trait Show
// defined object given_Show_JobState
def report[A](a: A)(using evidence$1: Show[A]): String
val res1: String = "waiting: LLM slot is occupied"
val res2: String = "BUT IT'S NATIVE!"
```

**opaque type + given Conversion — type conversion and newtype without traits
(Oleg, this comes for free and needs no PhantomData):**

```scala
object budget {
  opaque type Tokens = Long
  def Tokens(n: Long): Tokens = if n < 0 then throw IllegalArgumentException("negative") else n
  extension (t: Tokens)
    def +(o: Tokens): Tokens = t + o
    def value: Long = t
}

given Conversion[Int, budget.Tokens] = n => budget.Tokens(n.toLong)

val spent: budget.Tokens = 42            // Int was converted to Tokens
val total = spent + budget.Tokens(8)
total.value                              // the compiler won't let you add Tokens to a Long
```

```text
// defined object budget
lazy val given_Conversion_Int_Tokens: Conversion[Int, budget.Tokens]
val spent: budget.Tokens = 42L
val total: budget.Tokens = 50L
val res3: Long = 50L
```

**Union types — a sum type without sealed ceremony:**

```scala
def label(id: String | Int): String = id match {
  case s: String => s"name: $s"
  case i: Int    => s"number: $i"
}

label("coder-1") ; label(7)
```

```text
def label(id: String | Int): String
val res4: String = "name: coder-1"
val res5: String = "number: 7"
```

**Chaining operations on Either — validate configuration with one for-comprehension:**

```scala
case class Cfg(model: String, maxTokens: Int)

def parseModel(s: String): Either[String, String] = if s.trim.isEmpty then Left("model is empty") else Right(s.trim)
def parseMax(n: String): Either[String, Int]      = n.toIntOption.toRight("not a number")

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

The first `Left` short-circuits the chain: errors are not lost, and types
flow through automatically.

**Chaining on ZIO — the foundation of the runtime itself:**

```scala
import zio.*
val prog = for {
  n1 <- ZIO.succeed(21)
  n2 <- ZIO.succeed(n1 * 2)
  _  <- ZIO.succeed(println("the chain is alive"))
} yield n2

zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(prog).getOrThrow() }
```

```text
the chain is alive
val res6: Int = 42
```

**Match type — the final boss (note: `Elem[X]`, not `Elem[F[_]]` —
the match type's scrutinee cannot be higher-kinded):**

```scala
type Elem[X] = X match {
  case List[t]   => t
  case Vector[t] => t
  case Option[t] => t
}

val s: Elem[List[String]] = "okay"
summon[Elem[Option[Int]] =:= Int]
```

```text
val s: String = "okay"
val res7: Int =:= Int = generalized constraint
```

**And a live chain on a real agent — the DSL built on those same fibers:**

```scala
val answer = scout("In one paragraph: how do opaque types and givens in Scala 3 cover the Rust newtype and trait use cases?").start().await()
```

The lazy descriptor `.start()` is submitted to JobManager, `.await()` waits for the fiber,
and all of this is compiled by the same compiler as Raider itself.

## 5. Honesty Notes

- This is a demo, not an acceptance run: the quality of the irony depends on the local model.
- The live REPL branch requires a key in the environment (`RAIDER_API_KEY`, RAI-090); without it,
  there is an honest mock fallback with no `coder` binding.
- For multiline blocks, the REPL continuation heuristic (balance of `(){}[]` plus trailing
  tokens, `Main.complete()`) sends a balanced line to the compiler immediately
  if it ends in `for`/`match`/a definition. Write `for`/`match`/
  `enum` using braces; put parameterized enum cases on separate lines
  (the REPL cannot parse comma-separated cases with parameters). A blank line always
  submits the buffer as-is.
- If Oleg sounds too convincing in the 27B run, that is a prompt defect, not a runtime defect.
