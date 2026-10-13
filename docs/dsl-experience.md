<!-- Translated from Russian original. Key terms preserved as-is. -->

# DSL: Everyday path in the REPL

Status: target DSL design. Some API is already implemented, but the sections below retain design obligations and are not instructions for the current binary interface. Verified commands and examples for the current version are in the [user guide](user-guide.md) and [REPL gallery](user-guide-repl.md). This document refines the target usability described by the [full contract](product-repl.md).

## 1. First answer without agent setup

```text
$ raider --mock
raider> scout.ask("How is the project structured?")
```

In live mode, a previously configured provider profile is sufficient. Preset roles are immediately available, and startup shows the selected mode. `--mock` returns a scripted fixture response and labels it explicitly; it never pretends to be a real model.

The user learns three verbs:

```scala
scout.ask("Question")            // Answer now
val job = scout.start("Question") // Background work
val answer = job.await         // Result of the existing run
```

Infix Scala syntax uses the same methods:

```scala
scout ask "Find the main interfaces"
val job = scout start "Investigate retry"
```

Documentation defaults to dot syntax: it is simpler for completion and multiline input. Infix verbs are marked `infix`, and tests cover both forms.

## 2. Control next to the job

```scala
val job = worker.start("Add tests for the parser")
job.info
job.send("Account for UTF-8 split across network chunks")
job.watch()
job.cancel()
```

`info` is a small, safe snapshot. `watch` does not restart the task. `cancel` returns whether cancellation was confirmed, not just Unit. The `send` receipt reports queued/applied/rejected; delivery does not promise model compliance.

The ID is available as `job.id`; `:jobs` and completion let users control a lost binding with `:job/:watch/:cancel`. The last foreground job also remains visible in the jobs registry after a compile/runtime error, even if `val result = ...` did not create a binding.

## 3. A chain in one line

```scala
(scout andThen planner andThen worker).ask("Add plugin support")
```

All built-in quick profiles here are string-in/string-out. `andThen` passes the previous Program output as the next Program input. The original goal is retained as bounded root-context metadata, but the full history of previous agents is not copied automatically. A typed pipeline with incompatible inputs/outputs does not compile.

Save the chain:

```scala
val implement = (scout andThen planner andThen worker).named("implement")
register(implement)
implement.ask("Add a new tool")
val job = implement.start("Add a new provider adapter")
```

`named` creates a descriptor; `register` updates the registry explicitly. Registration stores a snapshot of stage versions; reload does not change a current job.

## 4. Multiple agents at once

Shared input:

```scala
val audit = all(scout, reviewer)
val (facts, critique) = audit.ask("Review the architecture")
```

Different inputs:

```scala
val job = all(
  scout("Study routing"),
  reviewer("Study the cache")
).start()

val (routing, cache) = job.await
```

List of tasks:

```scala
val topics = List("routing", "cache", "streaming")
val job = batch(topics, parallelism = 2)(topic => scout(topic)).start()
val answers = job.await
```

By default, the group is fail-fast. Collecting independent errors requires explicit `batchCollect(...).run()`. Results preserve their types and order; the `all` tuple preserves heterogeneous outputs.

## 5. Where execution begins

```scala
val task = scout("Study the project") // Description only
task                           // Brief preview, no model calls
task.run()                     // Foreground
task.start()                   // Background
```

This rule is the same for Agent, Workflow, and Session: `program(input)` creates a Task; `ask/start` explicitly execute it. Do not make `agent(input)` sometimes eager and sometimes lazy depending on imports.

If a newcomer enters a bare Task expression, the printer shows `Task[String](...; use .run() or .start())`. The renderer does not execute the task. Task construction is lazy with respect to providers/tools, but ordinary Scala still evaluates the input expressions passed to it.

`run(task)` and `spawn(task)` are available as low-level launch functions; they delegate to the single launch API. Method syntax does not have a separate lifecycle. The earlier `parallel/each/collect` proposals are not required API; `all/batch/batchCollect` replace them to avoid proliferating names.

## 6. Your own agent without lengthy boilerplate

```scala
val security = reviewer
  .named("security")
  .model("review")
  .appendInstructions("Pay special attention to auth and trust boundaries")

security.ask("Review session key handling")
```

From scratch:

```scala
val researcher = agent("researcher")
  .model("fast")
  .instructionsFile("agents/researcher.md")
  .tools("fs.read", "fs.search")
```

The fluent API is sufficient for MVP. Do not add a second builder language with mutable contextual setters until a user experience need is demonstrated.

## 7. Complex coordination without magic

```scala
val investigate = workflow[String, String]("investigate"): question =>
  scope:
    for
      research <- fork(researcher(question))
      code     <- fork(scout(question))
      plan     <- planner(question)
      sources  <- research.join
      facts    <- code.join
      report   <- writer(s"Task: $question\n$plan\n$sources\n$facts")
    yield report

investigate.start("Where should the new backend be added?")
```

Inside a workflow, use Task expressions and `fork/join`; outside, use ask/start/await. This is ordinary Scala, with no rewriting of the block into asynchronous code. A guardrail/test detects a blocking facade used inside a workflow; the error explains which expression to use.

## 8. Continued conversation

```scala
val chat = openSession(researcher)
chat.ask("Study storage options")
chat.ask("Compare the two best options")
val job = chat.start("Prepare recommendations")
job.await
chat.close()
```

Verb semantics do not change: ask is foreground, start is background, apply runs a Task. Turns in one conversation are serialized. This is a P1 capability, not an MVP promise.

## 9. Move to a file and CLI

In `workflows.raider.scala`:

```scala
import raider.dsl.*

val inspect = (agents.scout andThen agents.reviewer).named("inspect")
register(inspect)
```

In loaded scripts, `agents` and `register` come from the definition context, not an imported blocking facade. RAI-002 specifies the `using DefinitionContext` mechanism, provided consistently by the REPL/CLI. Bare library use passes this context explicitly.

```text
raider> :load workflows.raider.scala
raider> inspect.ask("Assess extensibility")
raider> :spawn inspect "Assess streaming"

$ raider compile workflows.raider.scala --output pipelines.jar
$ raider run --bundle pipelines.jar --workflow inspect --input "Assess extensibility"
```

The production runner executes the bundle without a compiler. `pipeline` adds codecs and trusted checks to a Workflow: [CI DSL](ci-runtime.md). Direct OpenAI/Anthropic profiles and Yabanin are selected through configuration: [provider contract](provider-protocols.md). Same callable Program, same runtime. Loading a file executes trusted code; it does not automatically replay command history.

## 10. Usability checks

Required UX transcripts:

1. First question without manually creating an agent.
2. Two background jobs while the prompt remains available.
3. A three-stage chain in one line.
4. Fanout producing two different outputs without casts.
5. Batch with bounded parallelism.
6. Steering/cancellation next to the job handle.
7. Save/load definitions and headless execution of the same workflow.
8. A compile error does not lose previously created handles.
9. A bare Task explains how to run it without executing anything.
10. Importing `raider.dsl.*` alone does not open the runtime or make paid calls.

These fixtures are required for DSL/REPL gates. The first seven tasks must be doable using the short built-in `:help`, without reading the runtime architecture.
