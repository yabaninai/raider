# Product, REPL, and DSL

All APIs here are target contracts. Signatures are refined by RAI-002 and then
frozen in compile fixtures. This document takes precedence over older examples
from discussions.

## 1. Core usage model

Primary product mode is the CI runner in the [CI contract](ci-runtime.md). The
REPL is a local development environment for the same Program/Task/runtime, with
a prepared prelude. It is not a chat with a text parser that recognizes strings
that look like Scala.

The user launches `raider` or `raider repl`. The working directory comes from
cwd; override it with `--workspace`. The prelude imports `raider.dsl.*`,
`raider.repl.*`, duration types, and safe local defaults; it shows workspace,
session ID, backend/profile, connectivity, and mock/live mode. It never shows
keys. Ready-to-use string profiles `assistant`, `scout`, `planner`, `worker`,
`reviewer`, `researcher`, and `writer` are available from the prelude. Registry
and inventory show their permissions; coding tools may require explicit policy.
Configuring a new agent before the first question is not required.

Shortest user path:

```scala
scout.ask("How is authorization implemented?")
val job = scout.start("Check error handling")
job.send("First inspect the network boundaries")
val result = job.await
(scout andThen reviewer).ask("Review the architecture")
```

Detailed UX decisions: [DSL experience](dsl-experience.md). `.ask` and `.start`
are explicit REPL extension methods over the same Program contract; no
macro/parser tricks.

```scala
val scout = agent("scout")
  .model("fast")
  .instructions("Investigate the project and cite file paths")
  .tools("fs.read", "fs.search")

val task = scout("How is authorization implemented?")
// task: Task[String]. No network/tool execution has happened yet.

val answer = task.run()
// Foreground: streams output, waits for completion, returns a String.

val job = scout.start("Check error handling")
// Background: immediately returns Job[String], allowing the next command.

val result = job.await
```

Prompt is optional: `agent("name")` gets a small general prompt. `.instructions(text)`
replaces it; `.appendInstructions(text)` adds a section. Agent descriptor is
immutable; new settings apply only to new runs.

`model("fast")` means a named role in Raider configuration, not a guaranteed
provider model name. Provider/model can be set explicitly. Without live
credentials, a launch command reports an actionable configuration error; it
does not spend money or silently use another account.

## 2. Separate description from execution

| API | Effect when called | Result |
| --- | --- | --- |
| `agent(name)` and `.tools/.model/.instructions` | Build descriptor only | `Agent[String, String]` |
| `a(input)` | Build task; input itself is evaluated as ordinary Scala | `Task[O]` |
| `task.map/flatMap` | Build computation | `Task[B]` |
| `a.ask(input)` | Create root job and wait with streaming | `O` or `RunFailed` |
| `a.start(input)` | Create session-owned root job | `Job[O]` |
| `task.run()` / `task.start()` | Run prepared task in foreground/background | `A` / `Job[A]` |
| `a andThen b` | Build typed pipeline: A's result becomes B's input | `Program[I, P]` |
| `job.await / .send / .cancel / .watch / .info` | Control existing job | Typed result/receipt/snapshot |
| `run(task)` | Create root job and wait for completion | `A` or clear `RunFailed` exception |
| `spawn(task)` | Create session-owned root job | `Job[A]` |
| `await(job)` | Wait for existing job; do not launch again | `A` or `RunFailed` |
| `cancel(job)` | Request cancellation and await confirmation within deadline | `CancelResult` |
| `send(job, text)` | Add steering message to mailbox | `MessageReceipt` |
| `inspect(job)` | Read state snapshot | `JobSnapshot` |
| `watch(job)` | Observe events until completion/observer cancellation | `Unit` |
| `all(a, b)` for Tasks | Describe parallel tasks with different types | `Task[(A, B)]` |
| `all(a, b)` for Programs | Build fan-out with shared input | `Program[I, (A, B)]` |
| `fork(task)` within workflow | Describe creation of child job | `Task[Child[A]]` |
| `child.join` | Describe waiting for child | `Task[A]` |

`ask/start`, Task/Job launch/control extensions, and `run/spawn/await/send/cancel`
are part of the REPL facade. Functions are the low-level launch/control path,
not a second set of implementations. Headless/library code has an asynchronous
`Runtime` API that returns ZIO effects; the facade is not imported by default in
workflows/plugins. Do not use the blocking facade from fibers. Each facade
operation delegates to one canonical runtime method; outcomes, scopes, and
events match.

Building a descriptor need not be entirely free of I/O: `.instructionsFile(path)`
may explicitly read a local file. It does not call a model or run agent tools.
`.instructionsFile` pins contents/hash when the version is registered; a running
job does not reread the file mid-run.

Repeating `run(task)` creates a new root ID and runs the task again. Repeating
`await(job)` returns the saved outcome without repeating actions. Snapshot/REPL
history must not implicitly restart tasks.

## 3. Real interactivity

Required support:

- variables and later use of their results;
- `def`, `case class`, pattern matching, collections, `for`;
- multiline input and bracketed paste;
- autocomplete for available Scala symbols plus agent/tool/model names in meta-commands;
- history with controlled persistence and secret exclusion;
- preserve valid bindings after compile errors and runtime errors;
- foreground streaming and background summaries without disrupting the edited line;
- concise printing of descriptors/jobs, without prompts, secrets, or huge transcripts;
- portable no-color/non-TTY mode and structured JSON output for CLI;
- load a scenario file and run registered workflows;
- `help` with working examples, not a list of internal classes.

Compiler state is handled sequentially. Background jobs do not modify it or call
compiler APIs. Completion and evaluation are synchronized by the REPL engine
adapter.

## 4. Running a group of tasks

Short path for results of different types:

```scala
val (facts, review) = all(
  scout("Find important interfaces"),
  reviewer("Review the architecture")
).run()
```

For a group of one type with a parallelism bound:

```scala
val topics = List("auth", "cache", "routing", "streaming")
val checks = batch(topics, parallelism = 2)(topic => scout(topic)).run()
```

`batch` returns results in input order, not completion order. An empty collection
returns an empty collection without model calls. Invalid parallelism is rejected
before launch. Default failure policy is fail-fast with cancellation of the
others and cleanup.

For independent checks:

```scala
val outcomes = batchCollect(topics, parallelism = 2)(topic => scout(topic)).run()
```

`batchCollect` returns `List[Outcome[String]]` and preserves failures, but
external cancellation still stops the whole group. `raceSuccess` and `quorum`
are P2; do not add them to MVP instead of required `all/batch/batchCollect`.

For multiple agents on one input:

```scala
val audit = all(scout, reviewer)
val (facts, critique) = audit.ask("Review the project's extensibility")
```

`all` overloads for Tasks and Programs have separate erased signatures; compile
fixtures check inference and rejection of mixed inputs. MVP arity is 2; beta may
support 3/4 with the same type safety. Do not coerce heterogeneous results to
`List[Any]`.

## 5. Workflow from REPL and file

```scala
val inspectProject = workflow[String, String]("inspect"): question =>
  for
    findings <- scout(question)
    review   <- reviewer(findings)
  yield review

register(inspectProject)
val result = inspectProject.ask("Review the project's extensibility")
```

Explicit `register` adds an immutable, versioned definition to the registry.
Creating a workflow does not modify the registry. Registering the same name
again creates a new version; old jobs use the previous version's snapshot.

Managed children:

```scala
val investigate = workflow[String, String]("investigate"): question =>
  scope:
    for
      a     <- fork(scout(question))
      b     <- fork(researcher(question))
      plan  <- planner(question)
      facts <- a.join
      docs  <- b.join
      text  <- writer(s"Plan: $plan\nFacts: $facts\nSources: $docs")
    yield text

val job = investigate.start("How can plugins be added?")
```

`scope` creates an ownership scope for children. Exiting it cancels and completes
unfinished children; default behavior does not do an unbounded implicit join.
`all/batch` wait for their branches themselves. The root task also gets a scope,
so `fork` is safe without a nested `scope`.

`workflows.raider.scala` contains Scala declarations and explicit registrations.
`:load` **executes user Scala code**, is not a sandbox, and does not guarantee
absence of side effects. For ordinary scenarios, keep definitions in the file
and launch separately. `:load` must not automatically replay history.

After loading:

```text
:workflows
:run inspect "Review the project"
:spawn investigate "Research the providers"
```

Meta-commands with string input pass the remaining argument as text according
to their grammar; they do not interpolate Scala. Typed input goes through
`--input-json` or a Scala expression.

## 6. Launch settings

```scala
val task = scout("Check the project")
  .within(2.minutes)
  .limit(Limits(maxSteps = 10, maxLlmAttempts = 12, maxChildren = 3))
  .tag("task", "RAI-017")

val job = task.start()
```

Task settings set an upper bound; global/session/parent policy may only tighten
it. Deadlines account separately for queue, retries, tools, and cleanup grace.
Agent defaults < task options < mandatory ceilings; overrides do not widen
authority.

Root budget is set in session/launch options. All descendants consume one root
ledger. A separate child cap limits its share and creates no additional money or
attempts.

## 7. Typed path

```scala
case class Question(text: String)
case class Finding(path: String, explanation: String)
case class Findings(items: List[Finding])

// The real example also declares Codec/Schema instances.
val typedScout = agent[Question, Findings]("typed-scout")
  .instructions("Return findings that match the specified schema")
  .tools("fs.read", "fs.search")

val findings: Findings = typedScout.ask(Question("Find public APIs"))
```

The command has input/output codecs and schemas. No runtime `asInstanceOf` on
the user path. Invalid JSON, unsupported schema, and exhausted repair budget
produce explicit errors. Imported Markdown agents begin as
`Agent[String,String]`; `.output[Findings]` adds a checkable schema, but does not
promise that one instruction will make the model obey it.

First public sugar is strings. Typed agents/tools enter beta and are required
for the [working coding agent](working-product.md); their infrastructure
contracts are needed earlier to avoid rewriting the core.

## 8. Ongoing interaction

In MVP, `job.send(text)` steers a running job. It is not a new model call or a
separate turn; runtime applies the message at the next safe boundary between
steps. Functional `send(job,text)` calls the same service method.

Receipt contains message ID, sequence, and state `queued/applied/rejected`. If
job finishes between enqueue and consume, the message gets a rejected/finalized
status and is not silently lost. The model is not guaranteed to follow the
request; receipt confirms delivery into context.

Session API is implemented in P1 and required for the [working coding
agent](working-product.md), though it does not block initial runtime M0:

```scala
val chat = openSession(researcher)
val first = chat.ask("Research storage options")
val second = chat.ask("Compare the two best")
chat.close()
```

Session implements a Program-style application: `chat(input)` returns a Task,
`chat.ask(input)` launches foreground, `chat.start(input)` backgrounds a turn.
Turns in one session are serialized; cancelling a turn does not erase earlier
history. Session stores separate conversation context and does not mutate
`Agent`. On mailbox overflow, use bounded wait/error; memory must not grow without
limit. V1 does not automatically replace the interactive facade with a magical
direct-style macro.

## 9. Meta-commands

| Command | Target behavior | Stage |
| --- | --- | --- |
| `:help [topic]` | Short examples, topic lookup | MVP |
| `:agents`, `:tools`, `:models` | Registry/model-role inventory | MVP |
| `:jobs [--all]` | ID, owner, status, age, limits, brief cost status | MVP |
| `:job ID` | Snapshot plus stop reason | MVP |
| `:watch ID` | Event stream; Ctrl+C stops watching | MVP |
| `:await ID` | Await outcome of existing job | MVP |
| `:cancel ID` | Cancellation with confirmation/uncertainty | MVP |
| `:send ID TEXT` | Steering message | MVP |
| `:load PATH` | Evaluate trusted local script | MVP |
| `:workflows` | Registered definitions/versions | MVP |
| `:run NAME TEXT` | Foreground workflow | MVP |
| `:spawn NAME TEXT` | Background workflow | MVP |
| `:doctor` | Toolchain/runtime/provider checks; no spending by default | MVP |
| `:history` | History without automatic execution | MVP |
| `:reset` | Close current runtime session, cancel jobs, create new session and reset compiler | MVP |
| `:exit` | Graceful shutdown; confirm no owned jobs remain | MVP |
| `:session save NAME` | Save registry references, outcomes, and transcript metadata | P1 |
| `:session open NAME` | Open archive without running code; jobs remain historical records | P1 |
| `:export PATH` | Save reviewable Scala history/script without automatic replay | P1 |
| `:replay RUN_ID` | Deterministic fixture replay without network/mutation effects | P1 |
| `:type`, `:imports` | Compiler-native introspection via engine adapter | MVP |

`:run` accepts only a registered workflow, not an arbitrary shell command. `:load`
expands `~`, accepts a quoted path, and shows local errors with line/column. Do
not use shell for path parsing.

## 10. Ctrl+C and shutdown

| Context | First Ctrl+C |
| --- | --- |
| Editing a command | Clear current input; jobs continue |
| `agent.ask(input)` / `task.run()` / `run(task)` | Cancel foreground root and its children, then return prompt |
| `job.await` / `await(existingJob)` / `:await` | Stop waiting; job keeps running |
| `job.watch()` / `watch(job)` / `:watch` | Unsubscribe; job keeps running |
| Pending approval | Do not authorize action; cancel foreground or finish observer according to context |

A second Ctrl+C during shutdown may terminate process after bounded grace;
pending remote actions are recorded as uncertain/interrupted, not succeeded.
Do not use `Thread.stop`, catch cancellation and continue the agent loop, or wait
forever for a finalizer.

MVP: closing REPL cancels session-owned jobs. Background means “does not block
the next input,” not “survives process exit.” Daemon/remote attachment is a
separate P2.

Arbitrary Scala can run an infinite CPU loop or native blocking code. Spike must
check interruptibility of the pinned REPL. If arbitrary evaluation cannot be
stopped safely, runtime cancellation still works and an escape path terminates
the REPL process; no promise is made to preserve bindings after that hard stop.

## 11. Terminal presentation

On background spawn, print one line with job ID and `:watch` hint. Entering the
next command does not wait for model/network.

```text
Job[String](j_018, queued, owner=session:s_03)
```

Print coalesced summary updates, not every token from all jobs simultaneously.
`:watch` selects one stream; critical state changes are not lost.
TerminalRenderer is the sole owner of terminal writes; a slow renderer does not
stop the budget/accounting state machine.

`run` streams text, then returns the value without printing the same long text
again in full. Pretty printing Task/Agent/Job causes no effects. No-color output
contains no ANSI; JSON stdout is not mixed with logs, and stderr is for diagnostics.

## 12. CLI and portability

Target commands:

```text
raider repl --mock
raider repl --profile direct
raider repl --profile claude
raider repl --profile yabanin
raider compile workflows.raider.scala --output pipelines.jar
raider run --bundle pipelines.jar --workflow inspect --input "Review the project"
raider run --bundle pipelines.jar --workflow typed-inspect --input-json input.json
raider run --bundle pipelines.jar --workflow inspect --input "Review the project" --json
raider doctor --profile yabanin
raider test examples/ --mock
```

Production CI runs a compiled trusted bundle without compiler; source evaluation
is dev mode through the same pinned engine. Details on CLI/exits/artifacts/trust:
[CI contract](ci-runtime.md); gateway-independent connections:
[provider contract](provider-protocols.md). Empty stdin, EOF, compile error,
missing workflow, and wrong input codec produce stable exit codes. Headless
invocation does not ask for interactive approval: explicit policy/capabilities
are required; missing decision is an error, not auto-allow.

All documented examples must execute through fake backend in docs gate and
selected examples through real Scala REPL. Do not rewrite an example to hide a
REPL defect without updating the contract.
