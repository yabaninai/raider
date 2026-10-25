# LLM4S and LangChain4j lessons for Raider

Observed: 2026-10-23. Raider source baseline: `a351d3c`. This research informs
the [cross-ecosystem delivery plan](../cross-ecosystem-plan.md). Competitor
documentation describes capabilities; it is not a comparative execution result.
No competitor installation, benchmark, paid request, or customer interview was
performed for this report.

## 1. Release and evidence boundaries

| Project | Release evidence inspected | Limits of this comparison |
| --- | --- | --- |
| LLM4S | Release listing identifies v0.5.1, commit `6f2ad28`, published October 16. The 0.5.0 notes introduce modular artifacts and a graph-based agent runtime. | Pre-1.0; release notes warn that even patch releases may break compatibility. Current guides can describe changes after the selected tag. |
| LangChain4j | Release listing includes 1.22.0, commit `7948fba`, published October 8. | Module maturity differs: the agentic guide explicitly calls its API experimental. Do not infer an agentic stability promise from the core version. |

Sources: [LLM4S releases](https://github.com/llm4s/llm4s/releases),
[LangChain4j releases](https://github.com/langchain4j/langchain4j/releases),
[LangChain4j agentic guide](https://docs.langchain4j.dev/tutorials/agents/).
Before executable comparisons, resolve exact artifact coordinates, hashes,
module versions and JDK requirements; do not use moving `main` or documentation
examples as a binary compatibility oracle.

An instructive packaging failure: LLM4S release notes say the Kotlin artifact
was not published in 0.5.0/0.5.1, despite Kotlin integration appearing in the
broader documentation. The notes point to 0.5.2 for that artifact; this report
does not independently verify its publication. Raider must test advertised
installation paths from an empty consumer directory, including transitive
resolution, rather than accepting a generated formula or facade source alone.

## 2. What to learn from LLM4S

### Typed orchestration and bounded composition

The current agent guide describes agents built once with tools/middleware and
run by thread, typed outcomes, delegation, streaming, and graph composition.
The migration guide describes moving away from an older untyped orchestration
surface. This supports treating workflow data dependencies as explicit types
and validating joins, rather than passing an unrestricted map between stages.
[Agents](https://llm4s.org/guide/agents/index),
[migration](https://llm4s.org/reference/migration.html).

Raider already has `Task`, `Program`, `all` and `batch`; replacing them with a
second graph engine would duplicate ownership and cancellation. Borrow the
user-facing clarity: typed inputs/outputs, explicit failure results and a small
catalog of composition patterns. Lower any portable representation into the
existing runtime and demonstrate semantic parity with native Scala workflows.

### Recovery is a protocol, not serialization

The durable-checkpointer guide describes graph-step checkpoints, pending
writes, execution claims and fencing. A completed task result can be reused,
but an external side effect interrupted before its result is recorded can run
again. Downstream idempotency or fencing is still needed. Live content events
and durable events have different retention purposes.
[Durable checkpointers](https://llm4s.org/guide/agents/durable-checkpointers).

For Raider, first expose the distinction between inspect, restart, read-only
replay and mutating rerun. Keep the existing uncertainty contract for arbitrary
Scala functions. A future durable workflow would require named serializable
steps, versions, effect receipts and reconciliation; it is not a prerequisite
for the first portable release. Do not add a database merely to copy a feature.

### Modules and foreign-language integration

LLM4S separates core/provider, agent, tool, storage and observability artifacts;
the stability tiers are not uniform. The Java facade is intended to reduce
Scala-specific calling friction. Its current guide documents actual return
types and integration limits rather than promising every Scala API is equally
usable from Java.
[1.0 scope](https://llm4s.org/reference/v1-scope.html),
[Java guide](https://llm4s.org/guide/java).

Borrow dependency-light consumers and clean result/error types. Start Raider's
non-JVM access through a versioned local process boundary; consider a
Java-friendly facade only after a JVM consumer needs embedding. Keep the REPL
compiler out of the headless dependency graph. Kotlin, TypeScript and Python
packages would each create separate release/support obligations.

### Capability truth and practical examples

Provider behavior varies by endpoint and model, even behind a common API.
LLM4S documents provider configuration and an extension SPI; its roadmap still
lists provider parity and capability work. These are separate signals, not
evidence that every listed provider supports every feature.
[Providers](https://llm4s.org/guide/providers.html),
[writing a provider](https://llm4s.org/guide/writing-a-provider),
[roadmap](https://llm4s.org/reference/roadmap.html).

Raider already represents `Supported`, `Unsupported`, and `Unknown` for three
capabilities. Extend only the missing semantics through a reviewed contract:
schema-constrained output, usage completeness and capability provenance.
Generate a matrix from actual contract cases; keep offline support and live
verification separate. Never turn unknown support into a silent fallback.

The useful onboarding pattern is a small complete recipe with a deterministic
test double and an explicit live opt-in. Raider should offer one recipe that
does useful work on supplied input without requiring a user to author Scala.

## 3. What to learn from LangChain4j

### AI Services reduce plumbing

AI Services map Java interfaces to model interactions: method parameters and
return types guide input/output handling, while configured services supply
models, tools, memory and retrieval. This gives application developers a short
path from a domain operation to a callable service.
[AI Services](https://docs.langchain4j.dev/tutorials/ai-services/).

Borrow the domain-first entry point: describe the input, output, instructions,
tools and constraints together. Reuse Raider's codecs and `Agent`/`Program`
contracts. Do not introduce reflection-driven proxies, annotation scanning or
macros into the core just to imitate the implementation technique.

### Structured output is a multi-layer contract

LangChain4j distinguishes provider-native JSON Schema, JSON mode and
prompt-based output formatting. A typed return value is not proof that a
provider enforces a schema. Streaming and model support also affect the route.
[Structured outputs](https://docs.langchain4j.dev/tutorials/structured-outputs/).

Raider should expose the chosen route and validate locally in all cases.
Invalid output must be a typed failure or a bounded, budgeted repair. Repair
must not repeat mutating tools. Test missing/extra fields, unions, invalid
Unicode/JSON, streamed incomplete output and unsupported schema features.
Use a deliberately bounded schema subset before promising cross-language type
equivalence or complete JSON Schema support.

### Composition should make state and effects understandable

The experimental agentic module documents sequential, parallel, conditional,
loop and supervisor patterns. `AgenticScope` holds shared state; state storage
is not by itself proof of crash-safe effect recovery. The API also describes
error handling and tool compensation.
[Agents](https://docs.langchain4j.dev/tutorials/agents/).

For Raider's first portable subset, prefer explicit sequence, bounded parallel
map/join, typed branch and bounded repair over an unconstrained supervisor.
Make fan-in failure policy, root budget and child cancellation visible.
Compensation is a new operation with its own possible failure, not rollback
of arbitrary shell/Git/remote effects. Keep it out of v1 portable semantics.

### Cancellation and observation are distinct contracts

Streaming documentation exposes cancellation of a streaming response. That
does not establish ownership of all workflow children or subprocesses.
AI Service observability offers correlated events but is itself marked
experimental. Guardrails have a separate invocation layer and maturity note.
[Streaming](https://docs.langchain4j.dev/tutorials/response-streaming/),
[observability](https://docs.langchain4j.dev/tutorials/observability/),
[guardrails](https://docs.langchain4j.dev/tutorials/guardrails/).

Borrow correlated stage/tool/model events and useful diagnostics; preserve
Raider's stronger task-specific acceptance obligations for process trees,
SIGTERM, deadlines and slow observers. Keep deterministic checks, policy
decisions and optional model-judge advice separate in reports. No model output
can manufacture a successful trusted check. Default telemetry should carry
identities/status/counters without raw prompts or secrets.

### Ecosystem adoption is a product feature

Spring Boot integration supplies starters and wiring for models and AI
Services. The provider matrix explicitly distinguishes capabilities and
unknown/unimplemented support, rather than presenting provider names alone.
[Spring Boot](https://docs.langchain4j.dev/tutorials/spring-boot-integration/),
[provider matrix](https://docs.langchain4j.dev/integrations/language-models/).

Raider cannot match this integration breadth economically in the first wave.
Offer a stable process contract and well-tested recipes that a Java, JS, Go,
.NET or Python application can invoke. Later adapters should follow observed
consumer demand. A custom language without installation, diagnostics,
completion and migration support would increase adoption cost.

## 4. Actual Raider seams and gaps

| Existing seam at the baseline | Reuse and next evidence |
| --- | --- |
| `modules/core/.../backend.scala`: `ModelCapabilities`, `CapabilityStatus`, `ModelRequest` | Capability states exist; schema/output negotiation is not represented in this request shape. Audit codecs/validation before extending it. |
| `modules/core/.../codecs.scala`, `schemas.scala`; native `Task`/`Program` | Reuse typed data and composition. Cross-language data validation does not imply compile-time safety in the caller language. |
| `modules/dsl/.../workflow/ChatWorkflowExport.scala`; CLI `ExportWorkflowCommand`, `WorkflowCompile`, `WorkflowRun` | Existing supported chat selection exports Scala and runs compiled workflows. A portable export needs a separate format/version contract and explicit refusal for unrepresentable steps. |
| `modules/cli/.../run/HeadlessRunner.scala`, CI artifacts/checks | Reuse execution/reporting and First CI acceptance. RAI-186/187/195 remain relevant; do not create a second scheduler in an adapter. |
| `modules/repl/.../Main.scala`, `TabShellHost.scala` | Current tabs entry path is not the complete chat/approval workflow. Keep RAI-188..192 active, with runtime wiring ahead of cosmetic features. |
| Local distribution RAI-193 and source-checkout onboarding | Distribution is accepted locally, not hosted. User-facing installation evidence and a coherent quickstart are still necessary. |

See [current board](../work/board.md), [CI guide](../user-guide-ci.md),
[chat export walkthrough](../examples/chat-workflow/README.md),
[distribution](../releases/jvm-distribution.md).

## 5. Competitive hypothesis and deliberate exclusions

The proposed value is a workflow a team can inspect, edit, validate and invoke
from its existing stack, using one managed runtime with explicit effects and
verifiable results. Scala remains an advanced authoring/embedding path, not a
prerequisite for using a ready recipe. No claim of unique capability or better
model quality follows from this architecture.

The first comparisons should separate application orchestration (against
LLM4S/LangChain4j) from coding-agent performance (against a coding harness).
Frameworks need caller-supplied tools/prompts; record that setup effort rather
than benchmarking an unfairly bare framework against a finished agent.

Defer a general RAG platform, vector-store catalog, multimodal suite, remote
scheduler, bespoke expression language, autonomous supervisor, persistence
backend and multiple language SDKs. Borrow designs, not source code or
dependencies by default; any copied material requires license/provenance review
under Raider's existing policy. Demand outside SDLC remains a hypothesis until
an external user repeats a useful task.
