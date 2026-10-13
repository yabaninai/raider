# Scala REPL tutorial

Use the REPL to explore Raider from Scala: try an agent request, transform its answer, compare two reviews, or keep a short conversation. The examples below use the current source checkout and its built-in `scout` and `worker` bindings.

## Start the REPL

From the repository root, build the user examples and start the compiler-backed REPL in mock mode:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

At the `raider>` prompt, enter Scala. Bindings remain available in later submissions until you run `:reset`. Submit multiline definitions with a blank line. For example:

```scala
case class Check(name: String, passed: Boolean)
val checks = List(Check("compile", true), Check("tests", false))
checks.filterNot(_.passed).map(_.name)
```

The final expression evaluates to `List("tests")`. To try a real provider, follow [provider setup](onboarding.md#connect-a-model) and configure its endpoint, model, and credential in your private environment before starting the REPL.

To have Raider change and check code in a disposable project, follow the [offline billing repair](user-guide.md#see-the-same-bug-case-without-a-live-model). It uses a scripted stand and independent Scala compile/test commands; the short mock REPL request above does not modify files.

## Make one request

Call `ask` for a request that should run immediately:

```scala
scout.ask("Explain what a failed test means in one sentence")
```

The call returns the assistant's answer as a `String`. In mock mode the answer is fixed scripted text; it does not inspect your project. A configured live provider supplies the response instead.

<a id="2-lazy-ask-and-map"></a>

## Prepare a lazy ask, then map its result

Calling `scout(prompt)` creates an `AgentAsk[String]` without dispatching a request. Use `:type` to inspect it, then call `.run()` when you want the answer. `.map` transforms the eventual answer and keeps the ask lazy.

```scala
val ask = scout("Summarize the parser entry point")
:type ask

val answer: String = ask.run()
val answerLength = scout("Summarize the parser entry point").map(_.length)
:type answerLength
val length: Int = answerLength.run()
```

The type queries report `AgentAsk[String]` and `AgentAsk[Int]`. Both `.run()` calls execute requests; the mapped one returns the answer's character count. `:type` compiles an expression but does not evaluate it. The runnable version of this example is [`02lazy-map.repl`](../examples/user-guide/repl/02lazy-map.repl).

## Ask for a review and a test plan in parallel

Create two lazy asks and label their results with `map`. `all(...).run()` returns both results as a tuple:

```scala
val code = "if subtotalCents > 10000 then 1000 else 0"
val policy = "A 1000-cent discount applies at 10000 cents and above."

val reviewAsk = scout(
  s"$policy Review this expression: $code. Give one boundary counterexample. Do not use tools."
).map(answer => ("review", answer))

val testAsk = worker(
  s"$policy Return expected [subtotalCents, discountCents] pairs for " +
    "9999, 10000, and 10001. Return only JSON; do not use tools."
).map(answer => ("tests", answer))

val (review, testPlan) = all(reviewAsk, testAsk).run()
review._1   // "review"
testPlan._1 // "tests"
```

The tuple labels show which answer belongs to each request. In mock mode, the answer strings are fixed fixture replies; the example demonstrates Scala composition, not review quality. The [billing review fixture](../examples/user-guide/repl/06billing-review.repl) contains a fuller prompt and response check.

## Review several items with a bounded batch

Use `batch(inputs, parallelism)(f)` when each input needs its own ask. It returns results in input order and runs no more than the requested number concurrently.

```scala
val areas = List("boundary", "integer cents", "test coverage")
val notes = batch(areas, parallelism = 2) { area =>
  scout(s"Review $area in this expression: $code. Give one observation; do not use tools.")
    .map(answer => area -> answer)
}.run()

notes.map(_._1) // List("boundary", "integer cents", "test coverage")
```

Each result is a `(String, String)` pair containing the input label and response. The [batch fixture](../examples/user-guide/repl/03parallel-batch.repl) demonstrates ordered results using the scripted provider.

## Run a background job

Use `.start(prompt)` to get a job handle immediately. `await` returns its result; it can be called again to observe the same completed result. `cancel()` requests cancellation and returns `false` if the job is already terminal.

```scala
val job = worker.start("Review the parser for an empty-input bug. Do not use tools.")
job.id
val result = job.await
```

Use `:jobs` to list facade jobs and their IDs. To cancel by ID, enter `:cancel <id>`; to inspect a job trace, use `:log <id>`. Cancellation cannot undo an external effect that already happened. See the [background-job fixture](../examples/user-guide/repl/04background-job.repl).

## Keep a conversation in memory

`openSession(agent)` creates a `Chat`. Each successful `ask` adds a user message and assistant reply; the next ask receives that conversation history.

```scala
val chat = openSession(worker)
val first = chat.ask("Review this parser: def parsePort(raw: String): Int = raw.toInt")
val second = chat.ask("What happens for an empty string?")
chat.messages.size // 4: two user messages and two replies
```

The history belongs to this `Chat` object in the current REPL process. It is not saved between processes, and `Chat` has no `close()` method. Mock replies are fixed text and do not demonstrate that a model used the earlier turn. The [session fixture](../examples/user-guide/repl/05session.repl) shows the scripted case.

## Connect typed stages with `andThen`

`Program[I, O]` describes a stage from input type `I` to output type `O`. `andThen` connects stages when the first output type matches the next input type. Applying the composed program creates a `Task`; a runner interprets that task.

```scala
import raider.core.{Program, Task}

val parseCount: Program[String, Int] =
  Program.fromFunction("parse-count")(text => Task.succeed(text.toInt))
val describeCount: Program[Int, String] =
  Program.fromFunction("describe-count")(count => Task.succeed(s"count=$count"))
val describeInput: Program[String, String] = parseCount.andThen(describeCount)
val pending: Task[String] = describeInput("3")
```

This pure Scala pipeline makes no model request. For an executable typed workflow, see [`TaskSummary.scala`](../examples/user-guide/bundle/TaskSummary.scala) and the [typed bundle walkthrough](user-guide.md#2-typed-release-readiness-workflow). It composes two `Program` stages, then runs them through the bundle runner.

In the REPL, `:help` lists commands. The current console supports `:type <expression>`, `:jobs`, `:cancel <id>`, `:log <id>`, `:reset`, and `:quit` (also `:q` and `:exit`). There is no `:session` command; use `openSession` in Scala. `JobHandle.send(message)` currently throws `CapabilityUnsupported`, so running jobs cannot be steered from this facade.

Agent and tool code runs with the permissions of the Raider process. Tool policy applies to calls through the agent runtime; it is not a sandbox for arbitrary Scala entered in the REPL.

The DSL examples here run through the REPL. A generated lineage projection is a description of recorded structure, not a replay; see the [lineage walkthrough](user-guide-lineage.md) for the distinction between live CI roots and the separately generated unified projection.
