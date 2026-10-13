# ADR-031: Lineage projection runtime contract — typed dry-run description (LIN-02 completion contract)

Status: **Accepted-on-review** (reviewable contract of card RAI-140; independent
review required before the renderer implementation card RAI-141). Date:
2026-10-13. Extends ADR-027 (session lineage). Does not change runtime-contracts
§14, the frozen `Task`/`Program` API (RAI-017), wire schemas, or gate policy.

## 1. Context

LIN-02 audit finding: the projection renderer (`LineageRenderer`,
`modules/dsl/src/main/scala/raider/dsl/lineage/proj/LineageProjection.scala`)
emits model/tool nodes as `//` comments and named-def bodies as `()` — the
generated file compiles, which proves syntax only, not the promised
Program/Task composition (ADR-027 §3.2). Before the renderer can emit real
composition (RAI-141), the runtime that the GENERATED code will reference must
exist as a public, reviewable contract. This ADR is that contract; card RAI-140
implements the runtime and its unit evidence (PRC-01/02/03).

## 2. Decision

A small pure runtime in `raider.dsl.lineage.proj.runtime`, plus one dataflow
field on `NodeView`. Generated projections reference the runtime; the four
execution-adjacent layers of the lineage stack are explicitly distinguished so
the generated source is never mistaken for a rerun or replay entrypoint.

### 2.1 Four-layer semantics table

| Layer | Mechanism | What executes | Provider/tool dispatch | Data source | Entry point |
| --- | --- | --- | --- | --- | --- |
| Rendering | `LineageRenderer.render` (existing) | nothing — produces source text | none, ever | graph model (`LineageView`) | `raider lineage` CLI export |
| Typed dry-run description (this ADR) | generated defs over `LineageStep` → `Task` | the built `Program`'s `Task`, observed only via `Task.zio`; evaluates to `StepReport` values | NONE — at construction AND at execution | `lineage-data.json`, referenced by `dataRef` keys only | the generated object's `Program` value |
| Recorded replay | `ReplayHarness` (graph-based, existing, `modules/cli/.../lineage/ReplayHarness.scala`) | recorded rounds over the record document | replayed recorded backend calls only (LIN-06) | replay record + `lineage-data.json` | `raider replay` CLI against a throwaway workspace copy |
| Live rerun | `IsolatedRerun` (graph-based, existing, `modules/cli/.../lineage/IsolatedRerun.scala`) | hybrid replay in an isolated workspace copy | recorded + live, isolated and budgeted (LIN-06D) | replay record + live provider | guarded `IsolatedRerun.rerun` pipeline |

Rules the table pins down:

1. **The generated source is NOT a rerun/replay entrypoint and must say so.**
   The generated file carries an explicit header statement to that effect
   (RAI-141 renders it; this ADR mandates the statement). Executing the
   projection never contacts a provider and never dispatches a tool.
2. The projection's `Task` values are pure structural reports; the single
   observation boundary stays `Task.zio` on the built `Program` (no second
   execution semantics).
3. Replay/rerun layers consume the graph, not the generated source; they are
   verified unaffected by this contract (layers are independent).

### 2.2 Runtime contract (public API)

Package `raider.dsl.lineage.proj.runtime`:

- `final case class StepReport(id: String, kind: proj.NodeKind,
  dataRef: Option[String], inputs: Vector[String], nesting: Int)` — the
  structural report of one projected node; `kind` is the projection layer's
  parameterized `NodeKind` (`ModelCall(model, round)`, `ToolCall(tool,
  recovery)`, `Delegation(agent)`, `Phase(name)`).
- `final case class LineageStepFailure(step: StepReport, reason: String)
  extends RuntimeException` — the typed failure value (§2.4).
- `object LineageStep` — typed step constructors the generated code references:
  - `model(id, model, round, dataRef, inputs, nesting = 0):
    Task[Vector[StepReport]]`
  - `tool(id, tool, recovery, dataRef, inputs, nesting = 0):
    Task[Vector[StepReport]]`
  - `delegation(id, agent, dataRef, inputs, nesting = 0):
    Task[Vector[StepReport]]`
  - `phase(id, name, dataRef, inputs, nesting = 0): Task[Vector[StepReport]]`
  - `failed(id, kind, dataRef, inputs, nesting, reason):
    Task[Vector[StepReport]]` — evaluates to `Task.fail` (§2.4)
  - `seq(steps: Task[Vector[StepReport]]*): Task[Vector[StepReport]]` —
    sequential composition via a `Task.flatMap` chain

Hard constraints (all normative):

- ONLY `Task.succeed` / `Task.fail` / `map` / `flatMap` are used by the
  runtime. No `Task.apply`, no ZIO constructors, no clock, no environment.
- Construction dispatches nothing; execution dispatches nothing: no provider
  call, no tool call, no I/O, no environment reads. Every constructor is a
  pure function of its arguments; the runtime holds no shared mutable state
  (PRC-03). This is enforced twice: by construction (pure data constructors)
  and by classpath (the `dsl` module depends on nothing provider- or
  tool-shaped).
- Uniform vector shape: every step evaluates to the pre-order report fragment
  of its subtree (one element for an atomic step; own node + descendants for a
  nested def), so `seq` composes atomic steps and nested defs uniformly and
  the generated code needs no shape switching.

### 2.3 Dry-run execution semantics

- **`Task.succeed` reports ONLY.** Model/tool/delegation/phase steps evaluate
  to their `StepReport` — values derived exclusively from the constructor
  arguments. No payload is read from anywhere: prompts, responses, tool args
  and results stay in `lineage-data.json` (§2.5).
- **Sequencing = declaration order = graph child order.** `seq` accumulates
  through a `flatMap` chain; the report vector order equals the generated
  statement order, which equals the pre-order walk of the graph (RAI-141
  discipline; PRC-01 pins order == declaration).
- **Inputs threaded as values.** The generated code binds the upstream step's
  report via `flatMap` and passes the referenced node ids (or dataRef keys)
  into the downstream step's `inputs` parameter — dependencies are values in
  the chain, not global lookups (PRC-02 pins the threading).
- **Nesting is an explicit argument.** The renderer knows each node's depth
  statically (roots at 0, each named-def level +1) and emits it as the
  `nesting` argument. The runtime tracks no depth: stateless and deterministic.
- **Delegations and phases nest as sub-Programs.** Each named generated def is
  a `Program[Unit, Vector[StepReport]]` built over the same runtime; the
  parent's `seq` splices the sub-program's reports after its own node (pre-
  order), and the reports make the nesting visible (PRC-02).
- **Deterministic.** The same `Program` returns the identical report vector on
  every run, sequential or concurrent (PRC-01/PRC-03).

### 2.4 Failure path (typed)

A recorded-failed node projects to `LineageStep.failed(...)`, which evaluates
to `Task.fail` so sequencing short-circuits honestly:

- The `Task` error channel is the frozen, sealed `RaiderError` family (18
  cases, runtime-contracts §14; `modules/core` is outside this contract's
  paths, so extension is impossible by construction). The typed failure rides
  the channel as `RaiderError.OutputValidation` (RA-OUT) whose public `detail`
  is the failure's deterministic one-line message; the fully typed value
  `LineageStepFailure(step, reason)` is chained as its JDK cause.
- Consumer pattern: catch `RaiderError`, check `code == "RA-OUT"`, unwrap
  `getCause` — the typed step context (`id`/`kind`/`dataRef`/`inputs`/
  `nesting`) plus the recorded `reason` are available as data.
- `initCause` is called exactly once at construction; the resulting error is
  frozen afterwards, so repeated or concurrent runs of the same projection
  share one immutable failure value (no mutation during execution).
- Short-circuit: the first failed step fails the whole `seq` chain; downstream
  steps are never evaluated (Task failure semantics). Observability of the
  report prefix up to the failure is pinned by the acceptance card (RAI-142
  PRJ-02), not by this contract.
- Family choice rationale: `ToolFailed` would assert a live tool dispatched
  and failed — the dry run dispatches nothing, so the honest family is
  `OutputValidation` (a validation of a recorded outcome). The review may
  re-map the family; the cause-chained typed value keeps consumers stable
  either way. A dedicated `RaiderError` family would be a schema-level core
  change and requires its own card.

### 2.5 Data boundary

Node payloads (prompts, responses, tool args/results, usage) stay in
`lineage-data.json` (ADR-027 §3.2, LIN-02B). The runtime carries `dataRef`
keys only (`Option[String]`); no inline data enters the generated source or
the reports. This keeps the generated file and the data resource separate
artifacts joined by key, exactly as the renderer's `// data:` comments do
today. The same carve-out covers `LineageStep.failed(..., reason)` (§2.4):
`reason` is a bounded, payload-free RECORDED-OUTCOME phrase — the renderer
emits only the recorded outcome class as a fixed phrase (e.g.
`recorded outcome: Failed`), never an inline payload; recorded payloads stay
addressable exclusively through `dataRef` keys.

### 2.6 `NodeView.inputs`

`NodeView` (projection layer, `raider.dsl.lineage.proj`) gains
`inputs: Vector[String]` — the graph dataflow edges (ids of the nodes a node
depends on), mirroring `DataNode.inputs` on the frozen wire. The field is
added with a default (`Vector.empty`) so every existing construction site
(renderer, `SessionGraphView`, golden fixtures) compiles unchanged and the
LIN2 golden outputs stay byte-identical. Population from the graph and value
threading into the generated steps are RAI-141's integration work (the cli
`SessionGraphView` is outside RAI-140's path boundary).

## 3. Non-goals

Renderer rewrite (RAI-141), wire/schema changes (RAI-143), `Task`/`Program`
API changes (frozen, RAI-017), replay/rerun behavior changes, provider or tool
invocation from projected code, executed-projection acceptance semantics
(RAI-142), and any second execution semantics alongside `Task.zio`.

## 4. Consequences

- LIN-02's promise becomes implementable: a projection is an executable
  `Program` whose execution is an honest, dry, deterministic description of
  the recorded call graph.
- Zero-dispatch is verifiable: by API shape (no effectful parameter exists),
  by unit evidence (PRC-01's probe with a negative control), and by classpath
  (the `dsl` module cannot reference provider/tool code).
- The generated code gains a deterministic runtime coupling; the comparator's
  edit detection plus the pinned-dotc compile gate (LIN2-01) keep artifact and
  runtime consistent.
- The typed failure uses the cause-chain pattern until/unless core adds a
  dedicated family (schema decision, separate card with independent review).

## 5. Evidence plan

Unit evidence lives in
`modules/dsl/src/test/scala/raider/dsl/lineage/proj/runtime/ProjectionRuntimeSpec.scala`:

- PRC-01: a hand-written sample projection builds a `Program`; executing it
  via `Task.zio` returns the deterministic report vector (order ==
  declaration); zero dispatch is asserted with an effect probe untouched by
  construction and repeat execution, with a negative control proving the probe
  harness detects a real effect.
- PRC-02: a failed step short-circuits with the typed error (RA-OUT channel,
  `LineageStepFailure` cause carrying id/kind/dataRef/inputs/nesting/reason);
  inputs flow as threaded values; delegation/phase nest as sub-Programs.
- PRC-03: concurrent construction/execution of two projections is isolated
  (no shared mutable state); repeat execution is deterministic (identical
  reports, sequential and concurrent).

Gates: `make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`,
`make quality-profile PROFILES="static unit contracts docs"`.
