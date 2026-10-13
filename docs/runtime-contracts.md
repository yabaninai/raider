<!-- Translated from Russian original. Key terms preserved as-is. -->

# Runtime and Contracts

## 1. Technology Decisions

- Scala 3; baseline candidate — 3.9.0, JDK 21 LTS. RAI-001 verifies published artifacts and compatibility. Final exact versions are pinned in the build/ADR; no `latest`, snapshots, or floating patch versions in CI.
- ZIO 2 for effects/fibers/scopes/STM, ZIO Test for checks. Do not create a custom scheduler or support Cats Effect alongside it.
- sbt multi-project is the canonical build. Scala CLI is an optional developer/reference launcher, not a second incompatible build graph.
- The REPL engine is the pinned official Scala REPL behind a narrow adapter; JLine handles input/terminal coordination. Compiler-specific imports belong only in `repl-engine`.
- Choose one HTTP implementation in RAI-001: preferably JDK HttpClient + an isolated ZIO adapter if streaming/cancellation pass the spike; otherwise use a documented effect-native client. The wire parser and client transport are separate concerns.
- JSON/schema: choose one compatible pair of codec/schema libraries and verify derivation/REPL in a spike. Do not write a general schema derivation framework.
- MVP persistence: a versioned append-only JSONL journal + atomic snapshots/artifacts; one writer. Consider SQLite based on measurements and query needs; it is not required for the first version.

Compatibility among pinned Scala/ZIO/REPL/JLine versions must be verified with a real prototype; the chosen libraries alone do not prove that signals/classloaders work.

## 2. Module Boundaries

```text
raider-core            contracts, IDs, errors, schemas, Agent/Task descriptions
raider-runtime         jobs, scopes, mailbox, budgets, loop, cancellation
raider-dsl             immutable builders, compositional helpers
raider-provider-chat   OpenAI-compatible Chat HTTP/SSE normalization
raider-provider-anthropic Anthropic-compatible Messages HTTP/SSE normalization
raider-ci              context, trust, checks, exit codes, artifacts, platform adapters
raider-yabanin         labels, auth/session keys, capability doctor, reconciliation
raider-tools           fs/search/edit/process primitives
raider-repl-engine     compiler/JLine integration only
raider-cli             headless run/doctor and bundle loading; no compiler
raider-repl            interactive facade, commands/rendering, compile launcher
raider-testkit         scripted backend, fake tools, virtual time, contract suite
```

Additional modules only under their corresponding cards: `compat-pi`, `compat-opencode`, `backend-pi`, `backend-opencode`, `eval-export`.

Dependencies are one-way: core knows nothing about the terminal, HTTP client, Yabanin, compiler, or concrete tool implementations. Runtime depends on core and abstract services. DSL does not invoke the global runtime. CLI assembles layers. Testkit is not included in the production classpath.

Core/provider adapters are independent of Yabanin: [protocol contract](provider-protocols.md). Native ModelBackend normalizes both wires; the Yabanin layer adds optional gateway metadata/auth/accounting. The headless distribution excludes repl/repl-engine and their transitive compiler/JLine dependencies; the dev distribution adds them. `raider-ci` does not depend on a specific hosted CI SDK.

## 3. Types and Public Signatures

Sketch that should become a compilable contract in RAI-002:

```scala
trait Program[I, O]:
  def apply(input: I): Task[O]
  def andThen[P](next: Program[O, P]): Program[I, P]
  def mapOutput[P](f: O => P): Program[I, P]

trait Agent[I, O] extends Program[I, O]

trait Workflow[I, O] extends Program[I, O]

trait Task[+A]:
  def map[B](f: A => B): Task[B]
  def flatMap[B](f: A => Task[B]): Task[B]

trait Job[A]:
  def id: JobId

trait Child[A]:
  def join: Task[A]

trait AgentBackend:
  def capabilities: BackendCapabilities
  def run(input: AgentRequest): ZStream[Scope, AgentError, AgentEvent]

trait ModelBackend:
  def capabilities: ModelCapabilities
  def stream(input: ModelRequest): ZStream[Scope, ModelError, ModelEvent]
```

These are two levels, not two names for the same interface. `ModelBackend` performs one inference attempt over the OpenAI/Anthropic wire. `AgentBackend` performs an agent run: the native implementation contains a loop that calls ModelBackend and Raider tools; pi/OpenCode implementations observe an external engine that owns its own loop/tools. The native loop does not execute tool observations from an external backend a second time. `ModelEvent` contains complete model/tool-request data; `AgentEvent` contains lifecycle/output/tool-observation/usage evidence. Runtime root/scopes/budgets control both implementations subject to their capabilities.

RAI-002 pins down concrete event/error/request schemas, final completion, and cancellation contract; `ZStream/Scope` imports are explicit. `Job[A]` is an opaque/immutable handle with a typed result reference; a heterogeneous registry exists only at the admin boundary with codecs. Public outputs do not return `Any`/unchecked casts. Endpoint compatibility does not imply external engine compatibility; these have separate suites and release capabilities.

Agent and Workflow implement the common `Program[I,O]` with `apply(input):Task[O]`, `andThen`, and `mapOutput`. Public `ask/start` are extension methods from `raider.repl`, not members of the core interface. Core Program does not depend on the terminal/global ReplSession. `all(Program,Program)` builds a Program with tuple output; `all(Task,Task)` builds a Task. One effect interpreter executes both cases.

`Job.await` and Task `.run()` use one facade boundary. Do not duplicate implementations for functional and method syntax. The facade detects an attempt to use them from a workflow fiber and returns a clear error; library workflows use apply/fork/join. Launch type signatures must require `using ReplSession` or an equivalent explicit capability installed by the prelude.

Task is a thin wrapper around the selected ZIO effect with metadata/step hooks. Do not promise an inspectable serialized AST for arbitrary Scala callbacks. Introduce a structured workflow plan IR only in durable recovery cards.

Separate IDs: SessionId, RootId, JobId, StepId, AttemptId, ToolCallId, MessageId, ArtifactId. RootId and parentJobId are retained on all descendants/events; attempts for one model request are distinguishable. Opaque types are acceptable if codecs/REPL printer remain simple.

## 4. Job State Machine

Execution states:

```text
Queued → Running ↔ Waiting
                ↔ WaitingApproval
Queued/Running/Waiting/WaitingApproval → Cancelling
Running/Waiting → Succeeded | Failed
Cancelling → Cancelled | Interrupted | Uncertain
```

`Succeeded`, `Failed`, `Cancelled`, `Interrupted`, `Uncertain` are terminal. Deadline/budget/protocol/safety reasons are recorded separately in StopReason, not mixed into arbitrary status strings.

- Succeeded: output validation is complete, required child results have been received, and owned resources have been finalized.
- Failed: a known unsuccessful outcome with a structured error.
- Cancelled: cancellation acknowledged and cleanup completed.
- Interrupted: the process was terminated/recovered after a crash; no active execution remains.
- Uncertain: the backend/tool may have performed the action, but confirmation of the result/stop is missing.

Cancellation races with final success through one atomic transition; there is one terminal winner and one root result completion. Late events are retained as late observations and do not roll the terminal outcome back. Cancelling blocks new model/tool/child actions from starting.

`await` is idempotent and permits multiple waiters. Awaiting a terminal job does not call the provider. Root completion must not precede required finalizers; on grace timeout, the status reflects cleanup that is incomplete/unconfirmed.

## 5. Ownership and Cancellation

A CI invocation or REPL session owns root jobs. The headless runner owns the same root scope and finalization; it does not imitate a terminal session. A REPL session owns root jobs. A root job owns the root scope. `fork` belongs to the nearest workflow scope. A child does not outlive its owner without explicit reassignment in a future daemon API.

Child and finalizer registration must be cancellation-safe: there must be no interval in which a fiber has started but the registry/cleanup does not know about it. Use a narrow uninterruptible registration section and restore interruptibility for external actions.

Do not confuse supervision failures with join failures: an unawaited child error is recorded, and policy determines propagation; `all` is fail-fast by default. `batchCollect` explicitly converts domain failures to Outcome and does not swallow external interruption.

HTTP cancellation: stop reading, close the response body, cancel the underlying request, and finish accounting with known/unknown usage. A TCP close does not prove the upstream stopped charging.

Process cancellation: first gracefully interrupt/terminate the owned process group, then perform bounded kill escalation. PID reuse and grandchildren require a platform contract. Do not kill other people's processes or treat requested cancellation as confirmed.

Implemented cancellation mechanics (2026-10-10; details and file:line references — [cancel.md](cancel.md)): transition guard `JobStatus.canTransition` (`modules/runtime/src/main/scala/raider/runtime/jobs/JobStatus.scala:29-42`); `JobManager.cancel`/`cancelAny` — atomic transition to `Cancelling`, event hook before interrupt, cancellation/completion race resolved in favor of cancel by one STM transaction (`modules/runtime/src/main/scala/raider/runtime/jobs/JobManager.scala:166-248,291-311`); `RaiderScope.close(grace)` — cancels unjoined children within a bounded grace period with an honest `ScopeCloseReport` (a child that is not terminal within grace is reported as `Uncertain`) and LIFO finalizers (`modules/runtime/src/main/scala/raider/runtime/scopes/RaiderScope.scala:77-150`); out-of-band cancellation of delegated children outside model-admission and owner-side deadlines with outcomes `Settled|Uncertain|AlreadyTerminal` (`modules/runtime/src/main/scala/raider/runtime/delegate/DelegationToolset.scala:366-478`). Cancellation at the HTTP response-body and process-group levels is not implemented in this slice — the cancellation available here is fiber-interrupt and its listed consequences.

## 6. Concurrency and Limits

There are separate counters for:

- active jobs and queued jobs;
- concurrent model HTTP calls;
- concurrent tools/processes;
- root descendants, tree depth, inbox capacity;
- retries/repair attempts, steps, wall time, tokens, money reservations.

A model/tool permit is held only during the corresponding operation. A parent waiting for a child does not hold the permit needed to run the child. Minimum acceptance: with maxConcurrentLlm=1, a parent delegates to a child and waits — there is no deadlock.

Acquisition order: eligibility/deadline → concurrency permit → recheck cancellation → atomic budget reservation → dispatch. Cancellation at any point releases reservation/permit according to actual dispatch status. Do not hold a monetary reservation for the entire queue wait without a justified policy.

Bounded queues: explicit backpressure, fair admission across roots; one recursive agent must not starve everyone. Check large fanout before launch. Deadlines cancel queued jobs; they do not wait to acquire a semaphore.

Mutating tools are serialized in the workspace by default. Parallel coding agents receive separate worktrees; merge/diff review is an explicit step. A shared context agent does not authorize concurrent rewrites of one file.

## 7. Root Budget Ledger

The ledger accounts for the entire root and its descendants; reservations are atomic. Money uses integer micro-USD or exact decimal, not Double for aggregation. Unit tests check rounding, overflow, and the absence of negative counters.

Cost data is distinguished as follows:

- estimated/reserved — before/during the call;
- observed — data confirmed by the gateway/provider;
- uncertain — the attempt may have been charged, but the amount is unknown;
- unpriced — usage is known, but the price is missing.

Unknown is not zero. Retries and structured-output repairs are new billable attempts and consume the shared cap. When a price is available, the reservation includes a conservative input estimate and explicit max output tokens. With a hard monetary cap and unknown/unbounded pricing, reject the launch or require another explicitly selected policy; a “hard USD cap” cannot be inferred from post-hoc usage.

Gateway billing availability may lag. The local ledger enforces launch discipline; the gateway enforces its own boundary. A global cross-process hard budget cannot be promised with one process-local STM ledger. P2 requires shared authority/admission, not polling analytics.

## 8. Native Agent Loop

1. Freeze AgentVersion, context snapshot, tool registry, resolved model role, and limits.
2. Assemble structured messages, including relevant instructions/skills/artifact references.
3. Reserve an attempt, call the backend, and normalize streaming events.
4. If there are tool calls: collect complete arguments and validate schema, policy, and deadline.
5. Execute permitted tools, match results to tool_call_id, and record events.
6. Apply steering messages at a safe boundary; check completion/budget/loop limits.
7. If there is a final answer: decode/validate output; bounded repair under configured policy.
8. Complete the outcome, cleanup, journal, and cost reconciliation.

Partial streaming tool arguments are never executed. Multiple calls in one step are not parallel by default: they require the tool capability `readOnly/concurrentSafe`; mutation order is explicit. Missing tools, invalid args, and denials produce structured feedback to the model with bounded attempts, not silent ignore.

## 9. Backend Protocol

Normalized events: Started, TextDelta, ToolCallDelta/ToolCallReady, UsageObserved, MetadataObserved, Finished, Failed. Provider reasoning is optional metadata with a provider-specific retention policy; do not treat reasoning as the primary answer or require disclosure of hidden reasoning.

BackendCapabilities specifies streaming, tools, structured output, steering, cancellation acknowledgement, accounting precision, and context/session support. Capabilities have supported/unsupported/unknown states; unknown does not become true.

An external backend may own tool execution. In that mode, Raider tool policy is not considered enforcement inside the external process; verify the backend's mapping/configuration or a tool proxy. Unsupported guarantees are visible in the doctor/launch report.

External retries and gateway failover may add attempts that are not directly visible to the client. Accounting reflects coverage; the adapter does not invent an exact upstream attempt count.

## 10. Tools and Permissions

Tool[I,O] contains name/version, input schema/codec, output codec, description, effect handler, timeout, capabilities, and recovery class:

- pure/read-only;
- idempotent with an explicit idempotency key;
- mutating non-idempotent.

Tool invocation includes principal/capabilities, workspace, root/job IDs, cancellation, and sanitized context. A tool does not receive raw parent credentials without need.

Minimum tools: scoped fs.read, fs.search, fs.edit with expected content hash, exec.run(argv, cwd, timeout). Shell syntax is a separate explicit capability `exec.shell`, not a way to invoke any command through the base tool. Validate paths, symlinks, traversals, output bytes, subprocess env allowlist, and process lifetime.

A path prefix string is not workspace containment. Resolve real paths for reads; mutations account for symlinks/TOCTOU and ownership. Portable policy limits trusted tools; strong isolation requires an OS/container sandbox. Neither the compiler nor a loaded Scala plugin is constrained by this.

## 11. Model-Initiated Delegation

Model-callable tools: delegate, await_agent, send_agent, cancel_agent, list_agents. They use the same JobManager/root ledger as workflow `fork`.

delegate accepts a registered AgentId/Version and encoded input; runtime validates the schema, allowed child profile, depth/count, context policy, and capability intersection. It returns a child handle ID and state, not the full transcript.

Ownership remains with the initiating job/scope. Awaiting a child suspends the parent loop and does not hold a model slot. The child has no automatic right to delegate again; that is a separate policy. Context.TaskOnly is the default, Context.Summary/Artifacts are explicit, and the full transcript is opt-in with size/redaction controls.

A prompt naming an agent does not create an executable callback. If the registry name is missing, return a structured error. Cross-agent messages are protected by scope/owner access checks.

## 12. Extensions

An extension package contains a manifest: ID, semantic version, API version range, dependencies, provided agents/tools/providers/middleware, permissions, resources, and lifecycle.

Lightweight tier: Markdown agents/skills/prompts and config. Runtime tier: compiled Scala module through a small SPI + effectful scoped acquisition. External tier: process/HTTP backend. TypeScript extensions do not load automatically on the JVM.

Registry snapshots are immutable and versioned. Reloading definitions applies to new jobs; running jobs continue with their pinned snapshot. Reloading compiled plugins/classloaders during operation is deferred; the first version allows restart for binary changes.

Events are notification-only; middleware transformer/decision interfaces are separate. Define stable ordering by priority + extension ID; document error policy. Middleware timeouts must not block cancellation. Mandatory ceilings apply after user middleware and cannot be bypassed by it.

## 13. Journal, Artifacts, and Recovery

Event envelope: schema_version, event_id, session_id, root_id, job_id, parent_job_id, step_id, attempt_id, monotonic per-root sequence, timestamp, event_type, sanitized payload.

Sequence assignment/persistence uses one writer. Flush critical state transitions/events before reporting completion. Token delta events may be coalesced; aggregate outcome/usage/tool decisions are not lost. Journal I/O failure → explicit degraded/failed execution; do not claim confirmed durability.

Store raw prompts/transcripts separately under policy, not in telemetry by default. Artifacts have ID, SHA-256, media type, size, provenance, and workspace-relative location; enforce size limits and redaction. The journal must not store live Java object refs or arbitrary closures.

After journal/archive RAI-027: on restart, show the archive and mark unfinished jobs Interrupted/Uncertain. Do not automatically re-run the last tool. P1 replay fixtures have no external side effects. P2 resume is only for serialized plans/registered named steps with a documented recovery policy. Automatic restoration of arbitrary `val`s, classes, and JVM stacks is not promised.

## 14. Errors

Structured error families: Configuration, Compilation, InputValidation, OutputValidation, ProviderAuth, ProviderRateLimit, ProviderBudget, ProviderUnavailable, StreamProtocol, ToolDenied, ToolFailed, ToolOutcomeUncertain, DeadlineExceeded, LocalBudgetExceeded, ChildFailed, Cancelled, JournalFailure, CapabilityUnsupported.

An error includes a stable code, public explanation, retryability, safe details, and root/job/attempt IDs. Secrets/provider bodies with credentials do not appear in public details. Domain failure, defect, and interruption are distinguishable; a programmer defect is not masked as a successful text response.

## 15. Deferred Complexity

Do not build in MVP: distributed scheduling, daemon attach, arbitrary durable Scala replay, self-modifying plugins, auto-merge/push, custom DSL parser, macro/direct-style transformation, general cross-effect API, independent repetition of gateway routing, marketplace/signing infrastructure. Their absence does not prevent a full local REPL and native agent loop.

## 16. Workflow SDK launch boundary (ADR-029)

Proposed contract ([ADR-029](adr/ADR-029-workflow-sdk.md), card RAI-116; signatures there are non-frozen until reviewed with the §7.3 consumer migration plan):

- **First-class path**: an author writes ordinary Scala over the existing lazy `Task`/`Program` and composition verbs and runs the same definition locally and in non-TTY generic CI through ONE ZIO-native launch capability per run, assembled from explicit backend/tools/policy/limits. Host embedding is a secondary surface. Construction performs no I/O and no dispatch; operations return effects, and only console adapters (REPL/CLI `main`) may block via `Runtime.default`.
- **Wire**: BundleWorkflow v1 stays the frozen `Program[String,String]` boundary; typed in-process composition is never presented as a typed wire. Wire/codec/SPI changes require a new bundle `schemaVersion`, a separate ADR, and a migration. Agent-performing workflows cannot run behind the v1 no-arg SPI today (no service injection; the runner admits one opaque outer slot) — see ADR-029 §3.2/§6.
- **One root/admission/ledger per run**: one launch instance owns one `RootId`, one `Admission`, one `BudgetLedger`, one `JobManager`; two instances share nothing. Admission discipline is single per dispatch path — loop-owned for loop-dispatched programs, one outer slot for opaque programs; nesting Model takes deadlocks on `maxLLM=1`.
- **Honesty**: no durable resume (`Interrupted`/`Uncertain` + explicit rerun); no hidden `Runtime.default` in the SDK path; no second scheduler; no hidden provider fallback. Known gap, not hidden: `AgentLoop.collect` ignores `UsageObserved` and both loop and bundle paths settle `MicroUsd(0)` today, so no truthful observed-USD or hard-cap claim may be made through the launch boundary until a separate runtime card corrects and verifies settlement; unknown stays unknown, never zero.
- **Cases**: WF-01/02 and SDK-01/02/03 with consumers, versioning, owner paths, and implementation packets are enumerated in ADR-029 §5/§7; the chat→export→edit→compile→run chain (RAI-155) is the first consumer and depends on this boundary.

## 17. Chat capture and workflow export (ADR-033)

Proposed contract ([ADR-033](adr/ADR-033-chat-workflow-export.md), card RAI-155.a; reviewed per CWF-A01..03 before implementation cards RAI-155.b–.f; all command names there are proposals — not implemented):

- **Packaging (decided in ADR-033 §3): Option L — launcher artifact.** The exported workflow is ordinary editable Scala with an explicit `main` that constructs the ADR-029 §3.1 launch capability and runs the definition; the same artifact runs locally and non-TTY in generic CI. Bundle v1 stays frozen and untouched: its no-arg SPI cannot host agent work (no service injection; one opaque outer slot settling `MicroUsd(0)`, ADR-029 §3.2), so Option B would require a separately reviewed bundle v2.
- **First scope**: the Supported Task Specification — one explicit sequential coding intent, ordered explicit refinements, named task/workspace parameters, named trusted check commands, one bounded agent stage, checks-must-pass success policy. A deterministic renderer covers it; model-assisted synthesis is a separate explicit configured operation (off in the first slice). Everything else is a typed refusal, never an approximation.
- **Capture**: an exportable turn ledger persisted alongside the chat message history (additive session schema v3); it survives save/load/compaction; legacy conversation-only sessions stay readable but export-refused (the product cannot invent absent execution records); pending/interrupted turns are preserved with their request and `Unknown` status.
- **Export**: deterministic render of a bounded immutable selection into editable source + non-secret manifest + versioned input example. Export/build/compile perform no model/tool dispatch; exported text is data, never a shell instruction; authority is never inherited from saved chat — the launcher binds current credentials/policy/workspace at execution (env/helper references only; non-TTY approval is a typed NeedsApproval, exit 25).
- **Honesty**: no arbitrary-session conversion; no durable resume; no forge/deploy; ADR-031 projection stays zero-dispatch and separate (the exported artifact is a distinct authoring layer); unknown pricing stays unknown — no observed-USD/hard-cap claim through the exported path until the settlement correction (ADR-029 §4 H3); bundle ABI unchanged.
