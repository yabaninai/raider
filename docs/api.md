# Raider API reference

This page maps the Scala 3 + ZIO APIs that exist in this checkout. It is a
source reference, not a published SDK promise. Frozen contracts are described
in [runtime contracts](runtime-contracts.md).

## Choose the API surface

Raider has two entry styles that share the same runtime but have different
types and setup:

| Use case | Surface | Work description |
| --- | --- | --- |
| Interactive work in the Scala REPL | `raider.repl.facade` | `AgentRef`, `AgentAsk`, and REPL extensions such as `ask`, `start`, `all`, and `batch` |
| Compiled Scala workflow or bundle | `raider.core` | `Task[A]`, `Program[I, O]`, and `BundleWorkflow` |
| Runtime integration | `raider.runtime` | `AgentLoop`, admission, limits, jobs, delegation, events |
| New model transport or tool | `raider.core` SPI | `ModelBackend` and `Tool` |

The REPL prelude creates names such as `scout`, `worker`, and `reviewer` and
binds a live `ReplSession`. Those names are not global Scala APIs. A compiled
workflow defines its own `Program`; it does not call `scout.ask` or depend on
the REPL interpreter. Both paths reach the same runtime execution machinery.

## Modules

| sbt project | Package | Main contents |
| --- | --- | --- |
| `raiderCore` | `raider.core` | Model and tool contracts, errors, tasks, programs, bundles, IDs, codecs |
| `raiderRuntime` | `raider.runtime` | Agent loop, admission and budgets, jobs, delegation, events |
| `raiderUi` | `raider.ui` | Event-backed session registry and tab shell |
| `raiderTools` | `raider.tools` | Workspace tools: `fs_read`, `fs_search`, `fs_edit`, `fs_patch`, `fs_tree`, `proc_run` |
| `raiderProviderChat` | `raider.provider.chat` | OpenAI-compatible Chat transport, including SSE |
| `raiderProviderAnthropic` | `raider.provider.anthropic` | Anthropic Messages transport |
| `raiderDsl` | `raider.dsl` | Lazy composition helpers such as `all` and `batch` |
| `raiderTestkit` | `raider.testkit` | Scripted model backends and deterministic fixtures |
| `raiderReplEngine` | `raider.repl.engine` | Scala 3 REPL driver |
| `raiderRepl` | `raider.repl` | REPL facade, console, and live wiring |
| `raiderCli` | `raider.cli` | Headless runner and interactive chat |

## Core contracts

### Model backends

One `ModelBackend.stream` call represents one inference attempt. Providers
normalize their wire events to `ModelEvent`; partial tool-argument fragments
are not executable.

```scala
trait ModelBackend:
  def capabilities: ModelCapabilities
  def stream(input: ModelRequest):
    ZStream[Scope, RaiderError, ModelEvent]
```

`ModelRequest` contains `model`, `messages: List[RequestMessage]`,
`maxOutputTokens`, and `tools: List[ToolDefinition]`. Message roles are
`system`, `user`, `assistant`, and `tool`. The event variants include
`Started`, `MetadataObserved`, `TextDelta`, `ToolCallDelta`, `ToolCallReady`,
`UsageObserved`, `Finished`, and `Failed`. The loop dispatches only
`ToolCallReady`.

### Implement and register a tool

At the core boundary, tools accept and return JSON strings. The loop validates
and dispatches these calls through a `ToolRegistry`.

```scala
import raider.core.*
import zio.{Scope, ZIO}
import zio.json.*
import zio.json.ast.Json
import zio.Chunk

final case class EchoArgs(text: String)
object EchoArgs:
  given JsonDecoder[EchoArgs] = DeriveJsonDecoder.gen

final class EchoTool extends Tool:
  val name = "echo"
  val version = 1
  val description = "Return the supplied text as JSON."
  val recovery = RecoveryClass.ReadOnly
  val timeoutMs = 1000L
  val capabilities = ToolCapabilities(concurrentSafe = true)

  override val parametersJsonSchema =
    """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    ZIO.fromEither(
      argumentsJson.fromJson[EchoArgs].left.map(RaiderError.InputValidation.apply)
    ).map(args => Json.Obj(Chunk("text" -> Json.Str(args.text))).toJson)

val registry: Either[RaiderError, ToolRegistry] =
  ToolRegistry.of(EchoTool())
```

`Tool` requires `name`, `version`, `description`, `recovery`, `timeoutMs`,
`capabilities`, and `invoke`. `parametersJsonSchema` defaults to a permissive
JSON object schema. `ToolRegistry.of` and `ToolRegistry.build` return
`Either[RaiderError, ToolRegistry]`; they reject duplicate or empty names,
versions below 1, and non-positive timeouts. Declare `concurrentSafe` only
when simultaneous calls are safe. The loop runs tools sequentially today.

Tool recovery is `ReadOnly`, `Idempotent`, or `Mutating`. Mutating operations
are not automatically retried after an uncertain outcome. Tool code executes
with the process user's permissions; workspace containment is not a sandbox
for arbitrary Scala.

### Errors and traces

`RaiderError` is a sealed typed failure family. Stable codes include
`RA-CFG` (configuration), `RA-INP` (input validation), `RA-AUTH`, `RA-RATE`,
`RA-TOOLDENY`, `RA-TOOLFAIL`, `RA-DEADLINE`, `RA-CANCEL`, `RA-LBUDGET`, and
`RA-TOOLUNCERT`. See `modules/core/src/main/scala/raider/core/errors.scala`
for the complete set.

`raider.core.trace.TraceEvent` is a sealed data model for run, round, model,
text, tool, delegation, and terminal events. Recording a trace is observational;
it does not dispatch work.

## Lazy `Task` and typed `Program`

`Task[A]` is a lazy wrapper around `ZIO[Any, RaiderError, A]`. Creating,
mapping, or composing a task does not execute it. `Task.succeed`, `Task.fail`,
and `Task.apply` construct tasks; `Task.zio(task)` exposes the effect at the
runtime boundary.

`Program[I, O]` is the compiled workflow contract:

```scala
trait Program[I, O]:
  def apply(input: I): Task[O]
  infix def andThen[P](next: Program[O, P]): Program[I, P]
  def mapOutput[P](f: O => P): Program[I, P]

object Program:
  def fromFunction[I, O](name: String)(body: I => Task[O]): Program[I, O]
```

The important composition rule is encoded in the types: the first program's
output must equal the next program's input. Calling `program(input)` creates a
`Task`; it is not a REPL `.run()` call.

The current source includes a complete deterministic compiled example in
[`TaskSummary.scala`](../examples/user-guide/bundle/TaskSummary.scala). Its
essential structure is:

```scala
import raider.core.{BundleWorkflow, Program, RaiderError, Task}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

class TaskSummary extends BundleWorkflow:
  override def name: String = "task-summary"

  override def program: Program[String, String] =
    val parseChecks: Program[String, List[(String, String)]] =
      Program.fromFunction("parse-release-checks") { input =>
        Task.succeed(
          input.split(",", -1).toList.map(_.trim).filter(_.nonEmpty).map {
            item =>
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
            RaiderError.InputValidation(
              s"missing required checks: ${missing.mkString(", ")}"
            )
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
              "passed" -> Json.Arr(
                Chunk.fromIterable(passed.map(Json.Str(_)))
              ),
              "pending" -> Json.Arr(
                Chunk.fromIterable(pending.map(Json.Str(_)))
              ),
              "action" -> Json.Str(action)
            )
          )
          Task.succeed(report.toJson)
      }

    parseChecks.andThen(evaluate)
```

`BundleWorkflow` requires `name` and `program: Program[String, String]`.
The CLI loads a compiled bundle and selects a named workflow. The example
validates required release checks and returns JSON; it makes no model or
network call. The runnable invocation is documented in the
[user guide](user-guide.md).

## Runtime execution

### Agent loop

`raider.runtime.loop.AgentLoop` is the single model/tool execution point. Its
constructor freezes the backend, registry, model, admission controller, root,
and optional observers. The implementation signature is:

```scala
final case class AgentLoop(
  backend: ModelBackend,
  tools: ToolRegistry,
  model: String,
  admission: Admission,
  root: RootId,
  attemptEstimate: CostEstimate = CostEstimate.Unknown,
  trace: raider.core.trace.TraceEvent => zio.UIO[Unit] =
    _ => zio.ZIO.unit,
  systemPrompt: Option[String] = None,
  events: Option[LoopEvents] = None,
  plugins: Option[PluginHost] = None,
  planMode: Option[PlanMode] = None
)

def runText(
  messages: List[RequestMessage],
  ceilings: BudgetLimits,
  maxRounds: Int
): ZIO[Any, RaiderError, String]
```

`maxRounds` must be at least 1 and no greater than `ceilings.maxAttempts`.
Each model round consumes an admission attempt. Ready tool calls run in model
order under tool admission. Definitive tool failures fail the run; mutating
tools are not automatically replayed.

### Admission and budgets

`BudgetLimits.make` validates the ceilings and returns
`Either[RaiderError, BudgetLimits]`. Its current defaults are:
`hardUsdCap = None`, one concurrent LLM, one concurrent tool, 64 children,
depth 16, eight attempts, and queue capacity 256. Negative money caps and
non-positive count limits are rejected.

`MicroUsd` stores money as integer micro-USD. Use `MicroUsd.ofDollars(Long)`
for whole-dollar values; floating-point arithmetic is not used for budgets.
`Admission.make(limits: Either[RaiderError, BudgetLimits])` returns
`ZIO[Any, RaiderError, Admission]`. A cancellation while waiting for a slot
does not charge an attempt; cancellation during dispatch can leave the amount
uncertain.

### Jobs and delegation

`JobManager.make(maxRetained = 256, onTransition = None)` returns
`UIO[JobManager]`. It starts owned jobs and exposes typed handles to await or
cancel. Awaiting an already completed job observes the same result; it does
not start the task again.

`DelegationToolset.make(jobs, backend, admission, limits, baseRegistry,
allowedAgents, root, ...)` returns `Either[RaiderError, DelegationToolset]`.
It adds model-callable `delegate`, `await_agent`, `cancel_agent`, and
`list_agents` tools. The parent and children share root limits. At least two
concurrent tool slots are required to avoid holding the only slot while a
parent waits for its child. Children cannot delegate further.

Owner-side cancellation bypasses model tool calls:
`cancelOutOfBand(childId): UIO[Boolean]` and
`cancelAllOutOfBand: UIO[List[String]]`. `setChildDeadline(childId, deadline)`
arms an owner-side deadline; `deadlineOutcome(childId)` reports
`Settled`, `Uncertain`, or `AlreadyTerminal`. Expiry past the cleanup grace is
reported as uncertain, not as a successful cancellation.

`raider.runtime.display.StreamingDisplay` decorates a `ModelBackend` and sends
text deltas and ready tool calls to supplied callbacks. `RateLimitRetries`
retries HTTP 429 (`ProviderRateLimit`) only; other errors are not retried.

## Runtime events and UI

`EventHub.make(reliableCapacity = 256, diagnosticsCapacity = 1024)` returns
`UIO[EventHub]`. Its principal operations are:

```scala
trait EventHub:
  def publish(jobId: JobId, rootId: RootId, sessionId: SessionId,
              payload: EventPayload): IO[EventError, Unit]
  def subscribe(jobId: JobId):
    UIO[(SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])]
  def snapshot(jobId: JobId): UIO[Option[SessionSnapshot]]
  def droppedDiagnostics: UIO[Long]
```

Envelopes carry schema version 1 and per-job sequence numbers. Reliable events
such as status and terminal changes use bounded delivery; diagnostic events
may be dropped with a counter. Subscription registers its queue and reads the
snapshot atomically, so there is no gap between the snapshot and stream.
`SessionReducer.reduce` is a pure state fold. See
[`lineage.md`](lineage.md) for the lineage user guide and recorded-run model.

`SessionRegistry.make(hub)` creates a read model over this event stream. It
supports tab creation, focus, close/hide, rename, draft editing, history, and
scrollback. It does not publish events or cancel runtime jobs. A live tab close
returns a choice for the caller; the caller performs cancellation through the
runtime API when requested. `TabShell.run` is the JLine command loop over the
registry.

## Workspace tools

`CodingToolset.make(workspaceRoot)` wires six tools. Paths are constrained to
the workspace by realpath checks; denials return structured tool errors.

| Tool | Main arguments | Behavior |
| --- | --- | --- |
| `fs_read` | `path`, optional `offset`, `max_lines` | Bounded ranged read with content and SHA-256 |
| `fs_search` | `query`, optional `glob`, `max_matches` | Literal search with explicit truncation |
| `fs_edit` | `path`, `content`, `expected_sha256` | Atomic replacement; empty expected hash means create |
| `fs_patch` | `path`, `diff`, `expected_sha256` | Context-checked unified diff and atomic replacement |
| `fs_tree` | optional `depth`, `glob` | Bounded tree; default depth 3, maximum depth 5 and 500 entries |
| `proc_run` | `argv`, optional `cwd`, environment allowlist/values, `timeout_s` | Structured argv without a shell; bounded process output |

Process output may include a `parsed` result for `sbt_compile` or `sbt_test`,
using `raider.tools.process.OutputParser`. Tool results are JSON matched to
the originating `tool_call_id`. Keep credentials out of tool arguments and
logs.

## Provider implementations

- `OpenAIChatBackend` sends non-streaming OpenAI-compatible Chat requests to
  `/v1/chat/completions`. `Config.make(baseUrl, apiKey, callTimeoutMs)` builds
  its configuration.
- `OpenAIChatStreamingBackend` uses SSE with bounded frames and an idle timeout
  based on `callTimeoutMs`.
- `AnthropicMessagesBackend` maps Anthropic Messages responses, `tool_use`,
  `tool_result`, and cumulative usage. Tool definitions are encoded with
  `name`, `description`, and `input_schema` from `parametersJsonSchema`
  (RAI-127). Schemas must be JSON objects. Tool results use a
  `tool_result.content` array with a `text` block containing the rendered JSON
  payload (RAI-133). Local stands and cross-wire agent-loop checks exercise this
  mapping; the documented live examples use the OpenAI-compatible path.

  The structured `tool_result.content` is an Anthropic wire representation; it
  does not introduce a different public `Tool.invoke` result type.
- HTTP 401/403 map to `ProviderAuth`, 429 to `ProviderRateLimit`, 5xx to
  `ProviderUnavailable`, and other unexpected responses to `StreamProtocol`.

## REPL facade

The live prelude in `raider.repl.Main` creates `scout`, `worker`, and other
`AgentRef` values, wires a `ReplSession`, and makes those names available in
the interpreter. In that environment, examples are:

```scala
scout.ask("Find the entry point and explain how to run the tests")

val task = worker("Review the configuration changes") // lazy AgentAsk
val result = task.run()                                 // foreground execution

val job = worker.start("Inspect the test suite")       // background job
val answer = job.await()                                // same result on repeat

val (facts, review) = all(scout, reviewer).ask("Assess the parser")
val summaries = batch(List("parser", "config"), parallelism = 2) { area =>
  scout(s"Summarize $area")
}.run()
```

These are REPL facade extensions on `AgentRef`, with an implicit `ReplSession`.
The actual prelude owns provider, tool, and session construction. In Scala
source, callers can construct a session with
`ReplSession.make(backend, limits, tools, model, echoStream)`, which returns
`ZIO[Any, RaiderError, ReplSession]`; the `AgentRef` extensions live in
`raider.repl.facade` and are not members of `Program`.

The facade supports `AgentAsk.map`, `run`, `ask`, and `start`; job-handle
extensions provide `await`, `cancel`, and `watch`. `all` and `batch` compose
through `raider.dsl.composition`. `openSession(agent)` returns a multi-turn
`Chat` whose later asks include earlier user turns and assistant replies.
Foreground facade calls block the REPL thread. Workflow fibers should use the
asynchronous runtime APIs instead.

The REPL command `:type <expression>` compiles the expression in the current
interpreter state and prints its type without evaluating it, dispatching tools,
or adding a binding. For example, after `val ask = scout("Inspect the parser")`,
`:type ask` reports `AgentAsk[String]`; `:type ask.map(_.length)` reports
`AgentAsk[Int]`. The engine also provides compiler-backed completion over
current bindings and member expressions. These are REPL-engine features, not
members of `Task` or `Program`.

`--tabs` starts the UI shell rather than the Scala interpreter. Its command
palette and Tab completion expose registered shell commands. `:scrollback
<tabId>` renders the selected tab's retained event lines and reports its
eviction count; the registry bounds retained scrollback to 500 lines. A tab
draft is local shell text, and typing it does not submit an agent request.
Plan-mode commands are scoped to this shell: `:approve` starts the proposed
plan once, while `:reject` cancels the active plan phase. The model's plan phase
is read-only; approval transitions to execution under
[ADR-028](adr/ADR-028-plan-mode.md).

## CLI entry points

Headless run:

```sh
raider run --bundle app.jar --workflow inspect --input "Inspect the project" --out artifacts/run --mock
raider run --agent coder --provider openai --input "Fix the regression" --out artifacts/coder
```

Interactive provider chat:

```sh
raider chat [--provider openai] [--base-url URL] [--model NAME] \
  [--workspace PATH] [--resume SESSION]
```

The `--api-key` option does not exist. Credentials come from `RAIDER_API_KEY`
or `RAIDER_API_KEY_CMD`; helper command text and credential values are not
logged. Live-provider credential preflight happens before dispatch. The mock
provider does not need a key.

The chat surface includes `:help`, `:quit`, `:clear`, `:context`, `:tools`,
`:trace`, `:save`, `:sessions`, `:load`, `:compact`, `:fix`, `:pipe`, and
`:delegate`. `SessionStore` saves and loads session documents under
`~/.raider/sessions/`. `CompileFixLoop` bounds compile/fix cycles. `StatusLine`
renders real event status on stderr. `EventExport` provides ordered event
consumption and canonical JSONL output; `awaitCaughtUp` is bounded best effort,
not an acceptance gate.

In chat, steering and task capture are explicit commands. `:send <jobId> <text>`
returns a typed `Queued` or `Rejected` receipt; a queued message is consumed at
a later model-turn boundary and its `settled` result may report `Applied` (or
no application if the job terminates first). Task-capture commands
include `:task status`, `:task mark <n> <intent|refinement|ordinary>`,
`:task check <name> <argv tokens...>`, and `:task select <n>`. A mark invalidates
an earlier selection, so select again after marking. For example:

```text
:task status
:task mark 1 intent
:task check compile sbt --batch compile
:task select 3
:task status
:export-workflow --out artifacts/workflows --name inspect --target-path src/main.scala --summary Inspect the project and document its entry point
```

Turn numbers refer to the displayed chat-turn list; selection is validated and
captures a snapshot of eligible turns and registered checks. `:export-workflow`
exports that selected snapshot as editable Scala. The independent `.e` verifier
and `.f` end-to-end walkthrough are not claimed as complete here.

For run recovery, `raider resume <run-dir>` reads that run's `events.jsonl`,
re-folds its consistent event prefix, reports status/jobs/artifact hashes, and
does not execute or write anything. Its exit codes are 0 (consistent journal),
26 (recovered prefix with uncertain truncated tail), 21 (usage/missing or
unreadable journal), and 20 (prefix cannot be folded). `events.jsonl` is the
headless run event stream; resume does not read a `segments.jsonl` file.
This recovery surface is separate from `raider replay`, which runs the recorded
replay path.

The headless CI preflight option `--require-provenance <manifest.json>` requires
a valid provenance manifest before dispatch. CI run artifacts include
`checks.junit.xml`; see the [CI guide](user-guide-ci.md) for invocation and
trust boundaries.

## Related references

- [REPL and typed workflow examples](user-guide-repl.md)
- [Lineage user guide](user-guide-lineage.md)
- [Architecture](architecture.md)
- [Contributing and extension workflow](contributing.md)
- [Runtime contracts](runtime-contracts.md)
- [Quality gates](quality-gates.md)
