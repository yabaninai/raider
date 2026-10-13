# Scala REPL and typed workflows

The REPL is an in-process Scala 3 compiler with Raider bindings installed in the same interpreter. You can define values and methods, use case classes, and build typed expressions. Agent calls use the current REPL session and its configured backend, tools, and limits.

This guide describes the current implementation. The snippets marked **source illustration** are adapted from existing source examples for readability; they were not executed as part of this documentation edit. The scripted REPL fixtures return text marked `[scripted-fixture]`: they exercise Scala compilation and Raider composition, not model quality. See [the lineage guide](user-guide-lineage.md) for reading and reviewing recorded runs.

## Start the REPL

Build the user example classpath, then start the REPL with the scripted backend:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

The prelude defines `scout` and `worker` as `AgentRef`s and installs a `ReplSession`. `RAIDER_PROVIDER=mock` selects a deterministic fixture backend; it does not contact a provider. A configured live provider uses the same Scala facade, but its responses and tool calls are live effects.

The compiler accepts ordinary Scala definitions. This is the source-backed example from [`01compiler.repl`](../examples/user-guide/repl/01compiler.repl):

```scala
val count: Int = 21
case class Pair(left: Int, right: Int)
val pair = Pair(count, count + 1)
def total(p: Pair): Int =
  p.left + p.right

assert(total(pair) == 43)
```

Multiline definitions are submitted when you enter a blank line. The REPL keeps bindings across submissions until `:reset`.

## Build a lazy ask and inspect its type

Calling `scout(prompt)` constructs an `AgentAsk[String]`. It does not dispatch a model request. `map` transforms the result while keeping the descriptor lazy; `.run()` or `.ask()` executes it in the foreground, and `.start()` creates a background job.

```scala
val description = scout("Summarize the parser entry point").map(_.length)
:type description
```

The type query compiles the expression against the current interpreter and reports its type without running it. Here the descriptor is `AgentAsk[Int]`. You can likewise check that effectful code is not evaluated:

```scala
var observed = 0
:type { observed += 1; description.run() }
assert(observed == 0)
val summaryLength: Int = description.run()
```

This distinction is useful when you want to prepare a request, add typed transformations, and inspect the result shape before dispatch. The executable fixture is [`02lazy-map.repl`](../examples/user-guide/repl/02lazy-map.repl).

## Compose parallel reviews and check structured output

`all(askA, askB).run()` runs two lazy asks through the composition interpreter and returns a tuple whose component types come from the asks. `all(scout, worker).ask(prompt)` is the shorter form when both agents receive the same prompt. `map` can attach a typed label to a result, so the tuple retains which answer came from which review.

The following **source illustration** follows [`06billing-review.repl`](../examples/user-guide/repl/06billing-review.repl). It asks for a code review and a JSON boundary test plan for the same billing rule. In live OpenAI-compatible mode, the fixture parses the worker's answer as JSON and checks it against the stated policy.

```scala
import zio.json.*

val billingCode = "if subtotalCents > 10000 then 1000 else 0"
val billingPolicy =
  "A flat $10 (1000 cents) discount applies to purchases of at least $100 (10000 cents)."

val reviewAsk = scout(
  s"$billingPolicy Review this Scala expression: $billingCode. " +
    "Identify the exact boundary defect and give one concrete input/output counterexample. " +
    "Do not use tools. Reply concisely."
).map(answer => ("boundary-review", answer))

val testPlanAsk = worker(
  s"$billingPolicy For this Scala expression: $billingCode, " +
    "specify the expected discounts required by the policy, not the outputs of the buggy code. " +
    "Return ONLY a JSON array of [subtotalCents, expectedDiscountCents] pairs " +
    "for 9999, 10000 and 10001 cents. No markdown or tools."
).map(answer => ("test-plan", answer))

val (review, plan) = all(reviewAsk, testPlanAsk).run()
assert(review._1 == "boundary-review" && review._2.nonEmpty)
assert(plan._1 == "test-plan" && plan._2.nonEmpty)

if sys.env.get("RAIDER_PROVIDER").contains("openai") then
  val expected = List(List(9999, 0), List(10000, 1000), List(10001, 1000))
  val decoded = plan._2.fromJson[List[List[Int]]].toOption
  assert(decoded.map(_.sortBy(_.headOption.getOrElse(-1))).contains(expected))
```

With the mock backend, both answers are scripted fixture text; only the shape and composition run. The JSON assertion is conditional on `RAIDER_PROVIDER=openai`. The existing example verifier can run the live case only with the configured endpoint/model and explicit live opt-in; this guide does not perform a live call. The verifier rejects tool requests for this review and checks the exact three boundary inputs.

## Run an ordered batch with bounded concurrency

`batch(inputs, parallelism)(f)` builds one ask per input, runs at most `parallelism` items at once, and returns results in input order. The function `f` returns a lazy `AgentAsk`; it can use `map` to produce a value tied to each input.

```scala
val ordered = batch(List("parser", "config", "cli"), parallelism = 2) { area =>
  scout(s"Review the $area module").map(_ => area)
}.run()

assert(ordered == List("parser", "config", "cli"))
```

This is bounded parallel work with deterministic result ordering, not a promise that the responses themselves are deterministic. Use `collect()` when you need per-item domain failures returned as explicit outcomes; external interruption still propagates. The runnable composition fixture is [`03parallel-batch.repl`](../examples/user-guide/repl/03parallel-batch.repl).

## Start and cancel a background job

`worker.start(prompt)` returns a typed job handle immediately. `await` waits for its result; `cancel()` requests cancellation and returns `false` when the job is already terminal. The handle remains usable in later REPL submissions.

```scala
val job = worker.start("Inspect the configuration loading path")
job
:jobs
```

`:jobs` lists jobs started through the facade, including each job's ID, status, and label. To request cancellation, copy the ID shown there:

```text
:cancel <job-id>
```

You can also await a job in Scala with `val answer = job.await`. Do not assume cancellation rolled back a tool effect that already happened. The REPL reports the job state; it cannot undo arbitrary user code or external effects. The current job fixture awaits a job to completion in [`04background-job.repl`](../examples/user-guide/repl/04background-job.repl); cancellation is also available through the console command.

## Keep conversation history in a session

`openSession(worker)` creates an in-memory `Chat`. Each successful `ask` appends the user message and assistant reply; the next call receives that history. The history belongs to the `Chat` object in this REPL process.

```scala
val chat = openSession(worker)
val first = chat.ask("Inspect the parser and identify its entry point")
val second = chat.ask("Now summarize the error handling around that entry point")
chat.messages.size // four RequestMessage values: two user and two assistant
```

This facade does not provide session persistence or a `close` method. The deterministic mock fixture confirms that two exchanges are retained in memory, but fixed replies do not demonstrate that a live model used earlier context. See [`05session.repl`](../examples/user-guide/repl/05session.repl).

## Connect a typed `Program` pipeline to a bundle

The REPL facade composes agent asks with `all` and `batch`. A deterministic workflow can instead be expressed as `Program` stages and packaged as a bundle. The existing [`TaskSummary` implementation](../examples/user-guide/bundle/TaskSummary.scala) is a complete example: it parses comma-separated check records, validates their names and statuses, then produces a JSON readiness report. Its second stage is a typed `Program[List[(String, String)], String]`; `andThen` connects it to the parser's output.

The following **source illustration** reproduces the essential body and types from that bundle. It uses only `Task.succeed` and `Task.fail`; it makes no model or network calls.

```scala
import raider.core.{Program, RaiderError, Task}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

val parseChecks: Program[String, List[(String, String)]] =
  Program.fromFunction("parse-release-checks") { input =>
    Task.succeed(
      input.split(",", -1).toList.map(_.trim).filter(_.nonEmpty).map { item =>
        item.split("=", 2) match
          case Array(name, status) => name.trim -> status.trim.toLowerCase
          case _                   => item -> "invalid"
      }
    )
  }

val evaluate: Program[List[(String, String)], String] =
  Program.fromFunction("evaluate-release-checks") { checks =>
    val required = List("tests", "migration", "rollback")
    val names = checks.map(_._1)
    val duplicates = names.diff(names.distinct).distinct
    val unknown = names.filterNot(required.contains)
    val missing = required.filterNot(names.contains)
    val invalid = checks.collect {
      case (name, status) if status != "pass" && status != "pending" => name
    }

    if duplicates.nonEmpty || unknown.nonEmpty || invalid.nonEmpty then
      Task.fail(
        RaiderError.InputValidation(
          s"invalid checks: ${(duplicates ++ unknown ++ invalid).distinct.sorted.mkString(", ")}"
        )
      )
    else if missing.nonEmpty then
      Task.fail(
        RaiderError.InputValidation(s"missing required checks: ${missing.mkString(", ")}")
      )
    else
      val pending = checks.collect { case (name, "pending") => name }.sorted
      val passed = checks.collect { case (name, "pass") => name }.sorted
      val decision = if pending.isEmpty then "READY" else "BLOCKED"
      val action =
        if pending.isEmpty then "No follow-up required."
        else s"Complete: ${pending.mkString(", ")}."
      val report = Json.Obj(
        Chunk(
          "decision" -> Json.Str(decision),
          "passed" -> Json.Arr(Chunk.fromIterable(passed.map(Json.Str(_)))),
          "pending" -> Json.Arr(Chunk.fromIterable(pending.map(Json.Str(_)))),
          "action" -> Json.Str(action)
        )
      )
      Task.succeed(report.toJson)
  }

val taskSummary: Program[String, String] = parseChecks.andThen(evaluate)
```

Here the final `Program` has type `Program[String, String]`: it accepts the CLI input string and returns a JSON string. Validation fails with `RaiderError.InputValidation` for missing, duplicate, unknown, or invalid checks. A `pending` check produces a successful `BLOCKED` decision with an action; all passing checks produce `READY`.

The bundle declares the workflow name `task-summary`. With the user-example artifacts built, run the compiled bundle through the CLI:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check \
  --mock
```

For this input, the report is `BLOCKED`, lists `rollback` as pending, and asks for that check to be completed. `--mock` selects the CLI backend configuration; this workflow itself has no model stage. The bundle is marked `trusted-local`. Run only bundles you trust: Scala workflow code executes with the process's permissions, and the trust label is not a sandbox.

## REPL commands and boundaries

Use `:help` for the commands available in the current console. The relevant ones are `:type <expression>`, `:jobs`, `:cancel <id>`, `:log <id>`, `:reset`, and `:quit`. Everything else is compiled as Scala. `:type` compiles without evaluating the expression. `:reset` clears interpreter bindings and installs a fresh prelude; it does not provide persistence for those bindings.

The REPL facade's `JobHandle.send` method is present for API shape but currently throws `CapabilityUnsupported`; job steering is not implemented in this slice. Agent and tool code runs with the permissions of the Raider process. Tool policy governs calls routed through the agent runtime, but it is not a sandbox for arbitrary Scala entered into the REPL. Do not paste credentials into REPL expressions or examples; configure credentials through the supported environment/helper setup.
