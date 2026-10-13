# ADR-029: Scala Workflow SDK for Local/Generic CI and the Host Launch Boundary

Status: **Proposed** (reviewable contract of card RAI-116; independent review is
required before any implementation card freezes signatures). Date: 2026-10-14.
Builds on the accepted RAI-115 source audit
([sdk-embedding-audit](../research/sdk-embedding-audit.md)). Extends
[runtime-contracts](../runtime-contracts.md),
[ci-runtime](../ci-runtime.md), and [product-repl](../product-repl.md). Does
not change the frozen `Task`/`Program` API, the frozen BundleWorkflow v1 wire,
gate policy, or the frozen TUI event seam (ADR-024). All code references below
were verified at lane baseline `5bec721` (branch `lane/rai-116`).

## 1. Context

### 1.1 Problem

Raider has a real runtime (lazy `Task`/`Program`, native `AgentLoop`,
jobs/scopes/admission/budgets, OpenAI- and Anthropic-compatible providers, a
headless CLI) but no supported way for an author to write one Scala workflow and
run it both locally and in generic CI:

- Convenient launch is coupled to the REPL console facade
  (`FacadeOps.scala` uses `zio.Runtime.default.unsafe.run`, process-global
  started/trace state); the CLI serves a separate process-oriented path.
- The compiled-bundle path (BundleWorkflow v1) is a frozen **String→String**
  wire with a public no-arg SPI (`bundle.scala:92-100`); every existing bundle
  example is a deterministic, non-agent program
  (`examples/user-guide/bundle/TaskSummary.scala` — "no model or network
  calls"; `modules/cli-fixtures/.../DemoWorkflow.scala`). There is **no
  existing path** by which a v1 bundle program receives a backend, tools,
  admission, or a root: the runner treats the program as one opaque task inside
  a single outer Model slot (`HeadlessRunner.scala:624-647`).
- There is no host-facing factory that assembles backend + tools + admission +
  ledger + root + lifecycle as one scoped resource (RAI-115 finding,
  unchanged).
- The successful `AgentLoop` round ignores `ModelEvent.UsageObserved`
  (`AgentLoop.scala:508-525`, `collect` falls into `case (st, _) => st`) and
  settles `MicroUsd(0)` (`AgentLoop.scala:488`); the bundle outer slot also
  settles `MicroUsd(0)` (`HeadlessRunner.scala:638-646`). Truthful observed-USD
  or hard-cap claims cannot be made through either path today.

### 1.2 Owner decisions this ADR implements

2026-10-12/14: the first scenario is a **Scala workflow authored by a developer
and run locally and in generic CI**; host embedding is an additional SDK
surface, not the first adoption path. Target user: AI-native SDLC (TokenRouter
first, work there in separate cards). Pinned Scala/ZIO experimental preview
before M4 with migration for breaking changes. **No durable resume**: after
interruption the result is uncertain and a rerun is explicit. RAI-155
(chat → export → edit → compile → run) is the **first consumer** of this
boundary; RAI-117 (SDLC effects) depends on it.

### 1.3 Non-goals (card)

Implementing runtime/DSL changes (separate cards), build/gate policy changes,
public publication, forking the TUI, Scala cross-build, SDLC
forge/release effects (RAI-117), paid calls, a second scheduler/runtime, a new
DSL parser/macros, distributed budget authority, durable workflow engine.

## 2. Decision summary — five areas

| # | Area | Decision (short) |
| --- | --- | --- |
| D1 | Authoring + launch boundary | Authors write ordinary Scala 3 over the existing lazy `Task`/`Program` and composition verbs; launch goes through ONE ZIO-native, explicitly-configured launch capability per run. REPL and CLI remain console adapters over the same runtime. No service is obtained from hidden/global state. |
| D2 | Wire: String bundle v1 vs typed | BundleWorkflow v1 stays the frozen portable wire `Program[String,String]`. Typed Scala composition is an in-process authoring convenience and **never** a typed wire. Typed I/O = validated decoding inside the program boundary. Any wire/codec/schema change requires a new bundle `schemaVersion`, a separate ADR, and a migration plan. |
| D3 | Root / admission / ledger | Exactly one root, one `Admission`, one `BudgetLedger` per run (per launch-capability instance). Admission discipline is single per dispatch path: loop-owned for loop-dispatched programs, one outer slot for opaque programs — never nested Model takes (maxLLM=1 deadlock, fixture 2026-10-04). The ledger mechanics distinguish reserved/observed/uncertain/unpriced; unknown ≠ zero. |
| D4 | Scope / ownership / cancellation / errors | The caller's ZIO scope owns the launch capability; it owns one root; `JobManager`/`RaiderScope` ownership rules are unchanged (`runtime-contracts` §4–6). Shutdown reports incomplete cleanup honestly (`ScopeCloseReport`); cancellation and terminal races follow the implemented mechanics (`cancel.md`). Errors are the structured `RaiderError` families (§14); domain failure ≠ defect ≠ interruption; `Uncertain` stays `Uncertain`. |
| D5 | Classpath + experimental compatibility | Consumable surface: `core`, `runtime`, `dsl`, `provider-chat`, `provider-anthropic`, optionally `tools`. Excluded from the main classpath: `repl`, `repl-engine`, compiler, JLine, `cli`, `testkit` (Test scope only). Pinned Scala 3.9.0 / ZIO 2.1.26 / JDK 21; experimental API with a documented migration note per breaking change; no backward-TASTy promise below 3.9.0 without an independent compile proof. |

Every signature shown below is **illustrative and non-frozen**; freezing any of
them requires the reviewed consumer migration plan (§7.3).

## 3. Alternatives considered (against the actual code at `5bec721`)

### 3.1 Authoring/launch unit (D1)

| Alternative | Verdict against actual code | Reason |
| --- | --- | --- |
| **A. Explicit launch capability over existing parts (CHOSEN)** | `AgentLoop`, `Admission`, `BudgetLedger`, `JobManager`, `RaiderScope`, providers, `CodingToolset` all exist and are reusable without the REPL; the missing piece is exactly the assembling factory + root + lifecycle | Smallest addition; one runtime; no new scheduler; services stay explicit; host and CI reuse the same assembly. RAI-115 recommendation, confirmed. |
| B. ZLayer-first SDK | ZIO-native, but `Admission.make`/`BudgetLimits.make` are `Either`-validating constructors, not layers; mandating a layer API complicates two isolated instances and binds consumers to a style before the boundary is proven | Deferred: a thin ZLayer adapter may be added later **on top of** A without a contract change; it is not the boundary. |
| C. Expose low-level `AgentLoop`/`Admission`/`JobManager` directly | They are `runtime`-internal shapes today; a caller must coordinate root, limits, tools, jobs, lifecycle manually — exactly the gap RAI-115 confirmed | Rejected as the only surface; these remain the internal parts A assembles. |
| D. REPL/CLI facade reuse | `FacadeOps.scala:67` blocks via `Runtime.default.unsafe.run`; started/traces are process-global; `HeadlessRunner` is tied to CLI args/artifact schemas/coding defaults; `ReplSession` has no close/finalizer API | Rejected as an SDK surface; facades remain console adapters. SDKADR-02 forbids a hidden `Runtime.default` in the SDK path. |
| E. Typed bundle wire now (v2 with typed codecs on the wire) | v1 is frozen (`bundle.scala:92-100`: "Adding methods = new schemaVersion"); `BundleLoader` reduces entries to `Map[String, Program[String,String]]`; no service injection exists in the no-arg SPI; the runner admits opaque programs with one outer slot | Rejected for this slice. Honest position: typed Scala composition (`Program[I,O]`, `all`, `andThen`) checks compile-time shapes in-process; the moment a workflow crosses a compiled artifact boundary it is `String→String` unless a separately reviewed v2 wire exists. RAI-155.c/d target the v1 boundary with validated JSON decoding inside the program (roadmap §6d). |
| F. Do nothing (docs only) | The local+CI author path does not exist; RAI-155 is blocked | Rejected. |

**Decision D1.** One launch capability (working name `WorkflowLaunch`,
non-frozen) constructed from explicit, validated parts:

```scala
// ILLUSTRATIVE — not frozen. Construction performs NO I/O and NO dispatch.
// Effects are returned, never run implicitly; there is no Runtime.default here.
val launch = WorkflowLaunch.make(
  backend   = ProviderBackend.fromConfig(cfg), // explicit; no hidden fallback
  tools     = ToolRegistry.empty,              // or CodingToolset.make(ws)
  policy    = limits,                          // validated BudgetLimits
  workspace = ws                               // explicit root for coding tools
) // Either[RaiderError, WorkflowLaunch], acquired in the CALLER's ZIO scope

val result: ZIO[Scope, RaiderError, O] = launch.run(program, input)
```

Rules D1 pins down:

1. **Lazy everywhere**: building a `Program`/`Task` dispatches nothing (existing
   `API-03` semantics, `task.scala`, `program.scala`); constructing the launch
   capability validates config and allocates admission primitives but performs
   no network call and starts no agent work.
2. **Explicit services**: backend, tools, policy/limits, workspace are
   constructor arguments. Provider selection maps to the two first-class
   transports (OpenAI-compatible, Anthropic-compatible) or mock; auth/policy
   failure is a typed error with **no hidden fallback**; credentials come from
   env/helper references and are never carried in argv or logged.
3. **One interpreter**: `Task.zio` remains the only effect boundary; the launch
   capability runs programs through the existing `AgentLoop`/`JobManager` —
   there is no second execution semantics and no second scheduler.
4. **ZIO-native result**: operations return `ZIO`/`Task` values; the caller's
   scope drives lifecycle. Blocking `.unsafe.run` exists only in console
   adapters (REPL/CLI `main`), never inside the capability.
5. **First-class consumer shape**: the same construction code runs (a) locally
   — dev REPL path, an sbt-run main, or the compiled artifact's main — and
   (b) in generic CI — the same compiled artifact, non-TTY, explicit input,
   artifacts and exit codes per `ci-runtime` §7–8. Local vs CI differs by
   process environment, not by a second implementation.

### 3.2 String bundle v1 vs typed internals (D2) — honest comparison

| Aspect | Reality at `5bec721` | Consequence for the SDK |
| --- | --- | --- |
| SPI shape | `trait BundleWorkflow { def name: String; def program: Program[String,String] }`, public no-arg class, lazy program (`bundle.scala:92-100`) | A bundle program cannot receive services through its constructor. No agent-performing bundle exists. |
| Loading | `BundleLoader.load` → `Map[String, Program[String,String]]`; manifest `schemaVersion` supported set is `{1}` (`schemas.scala:13`) | Wire evolution = new `schemaVersion` + separate ADR + migration. |
| Execution | One outer Model slot around the opaque task; `MicroUsd(0)` settlement; internal rounds (if any) invisible to the ledger (`HeadlessRunner.scala:624-647`) | An agent workflow behind the v1 boundary today would be **one unobservable admission unit with zero settled cost** — this must not be presented as typed, budgeted, per-round admission. |
| Typed composition | `Program[I,O]`/`all`/`andThen`/`batch` are real and compile-checked **in-process** | Types check composition; they do not cross the artifact boundary and do not make the wire typed (SDK-03 wording, roadmap §6a). |

**Decision D2.** Keep v1 frozen. The authoring path (D1) carries typed I/O
in-process; validated JSON decoding happens **inside** the String boundary
(this is the roadmap §6d preference for RAI-155.c/d). If a service-carrying or
typed wire is ever needed, it is a **bundle v2** proposal: new
`schemaVersion`, separate reviewed ADR, loader/runner migration, and updated
consumers — never a silent shape change of v1.

### 3.3 One root / admission / ledger (D3)

- One launch-capability instance = one `RootId`, one `Admission`, one
  `BudgetLedger`, one `JobManager` — no shared process-global registries. Two
  instances (SDK-02) share nothing: jobs, roots, queue, budget, tools.
- `newRoot` today uses a process-level `AtomicLong`
  (`AgentLoop.scala:779-785`); IDs remain unique per process. Cross-process
  uniqueness/cross-process budgets stay out of scope (`runtime-contracts` §7).
- **Single admission discipline per dispatch path**: loop-dispatched programs
  admit inside `AgentLoop.withSlot(SlotKind.Model, ...)`; opaque programs are
  admitted by the runner/launcher with exactly one outer slot. Wrapping an
  AgentLoop-dispatched program in an outer slot nests two Model takes and
  deadlocks on `maxLLM=1` (found by fixture, 2026-10-04; see the ADMISSION NOTE
  in `HeadlessRunner.scala`). The launch capability must preserve exactly one
  of the two disciplines per program and must document which one applies.
- A parent joining a child does not hold a model permit (`runtime-contracts`
  §6); WF-02 verifies this on the public path.
- The ledger distinguishes reserved/observed/uncertain/unpriced
  (`BudgetLedger.scala:96-106`); unknown is never zero. **Known gap, not
  hidden**: neither the loop path nor the bundle outer slot feeds observed
  usage into settlement today (§4, item H3).

### 3.4 Scope / ownership / cancellation / errors (D4)

- The caller's `Scope` owns the launch capability; the capability owns the
  root. Closing it cancels unjoined children within a bounded grace and
  returns `ScopeCloseReport` (`RaiderScope.scala:77-150`) — incomplete cleanup
  is reported, not swallowed. Incomplete/uncertain shutdown is an honest
  outcome, not an error to retry automatically.
- Cancellation uses the implemented mechanics: atomic `Cancelling`
  transition, cancel-wins races, out-of-band child cancellation with
  `Settled|Uncertain|AlreadyTerminal` (`cancel.md`; `JobManager.scala:294-311`).
  Cancellation does not prove upstream provider stopped billing
  (`runtime-contracts` §5); the ADR inherits that honesty rule.
- Errors are the structured families of `runtime-contracts` §14
  (`ProviderAuth`, `ProviderBudget`, `ToolDenied`, `LocalBudgetExceeded`,
  `DeadlineExceeded`, `Cancelled`, `CapabilityUnsupported`, ...). Budget/policy
  denial is typed and precedes spend (preflight before dispatch,
  `HeadlessRunner` preflight order). A programmer defect is never masked as a
  model-answer success.
- **No durable resume** (SDKADR-02): interruption yields
  `Interrupted`/`Uncertain` + artifacts; a rerun is an explicit new
  invocation that rechecks policy/task/repo/base/candidate. `raider resume`
  remains the existing recovery surface; nothing in this ADR promises fiber
  serialization or exactly-once effects.

### 3.5 Classpath + experimental compatibility (D5)

- Consumable modules for authoring+launch: `core`, `runtime`, `dsl`,
  `provider-chat`, `provider-anthropic`; `tools` optional (non-coding
  workflows must not pull `CodingToolset`). Main classpath must NOT contain
  `repl`, `repl-engine` (compiler/JLine), `cli`, `testkit` (Test scope only) —
  matching SDK-01 (roadmap §6a).
- Pinned toolchain: Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0, JDK 21
  (`build.sbt:3-11`). No cross-build in this slice.
- Compatibility policy (experimental preview): breaking API changes are
  allowed **with** a migration note in the ADR/release notes of the change;
  consumers are the migration plan's first-class entries (§7.3). TASTy
  honesty: no promise that 3.9.0-built artifacts are consumed by older
  compilers without an independent compile proof (RAI-115 finding).
- Publication/registry coordinates are out of scope (RAI-115/SDK-05 separate);
  first consumers use locally packaged, versioned artifacts.

## 4. Honesty obligations (acceptance SDKADR-02 — each addressed)

| # | Obligation | Status in this ADR |
| --- | --- | --- |
| H1 | No durable resume | §3.4: interrupted runs end `Interrupted`/`Uncertain`; rerun is explicit; no fiber/stack serialization; `raider resume` semantics unchanged. |
| H2 | No hidden `Runtime.default` | §3.1 rule 4: the capability returns effects; only console adapters (`FacadeOps.scala:67`, CLI `main`) may block. Any SDK path that blocks via `Runtime.default` fails review. |
| H3 | Accounting gap stated, not hidden | §1.1/§3.3: `UsageObserved` is ignored by `AgentLoop.collect` and both loop and bundle paths settle `MicroUsd(0)` today. Unknown stays Unknown for reservations. **No truthful observed-USD or hard-cap claim may be made through the launch boundary until a separate runtime card corrects settlement and verifies it end-to-end** (packet PKT-8). The ledger primitives can express it; the loop does not feed them yet. |
| H4 | Typed composition ≠ typed bundle wire | §3.2: v1 is `String→String`; typed I/O is in-process only; wire changes need v2 + ADR + migration. |
| H5 | Not a sandbox | Tool policy is not a security boundary for arbitrary Scala; generated/exported workflows are trusted code with process permissions (`runtime-contracts` §10; `ci-runtime` §5). |
| H6 | No second scheduler | §3.1 rule 3: one `AgentLoop`/`JobManager` per launch instance; adapters (REPL, CLI, CI) never reimplement scheduling. |
| H7 | No hidden provider fallback | §3.1 rule 2: auth/policy failure is typed and terminal; no automatic switch of endpoint/provider after failure (AGENTS.md core/CLI rule). |
| H8 | No new frozen signatures without migration | All signatures here are proposals; §7.3 fixes the freezing procedure and the consumer inventory that any freeze must update. |

## 5. Cases, consumers, versioning, owner paths (acceptance SDKADR-01/03)

### 5.1 Case table

| Case | Phase | First consumer | Observable result (from roadmap §6a/§6b, made checkable) |
| --- | --- | --- | --- |
| WF-01 | P4.C | RAI-155.d/e; then TokenRouter SDLC | Author's Scala source compiles with the pinned compiler; the same definition runs locally (dev/REPL path) and as a compiled artifact in non-TTY generic local CI; input/result/error/artifacts comparable across the two launchers; the String boundary v1 is explicit; lazy definition performs no model/tool dispatch at construction or compile time. |
| WF-02 | P4.C | WF-01 runs; SDK-03 parity | One root/ledger and per-call admission across all adapters; `maxLLM=1` with a delegated child does not deadlock; external cancellation closes owned resources and reports honestly; invalid input / policy / provider failure / cap each produce a typed outcome; two runs share no accidental global state. |
| SDK-01 | P4.C | Host-embed consumers (secondary path); preview packaging | A clean external Scala/ZIO consumer obtains versioned artifacts, compiles a typed `Program`, runs the native loop; main runtime classpath contains no repl/compiler/JLine/CLI/testkit; testkit only in Test scope. |
| SDK-02 | P4.C | Host-embed consumers; also exercises the launcher used by WF-01 | Host scope owns root/children/resources; success/failure/cancel and `maxLLM=1` verified; two host instances share no hidden global state; construction is lazy; budget/policy denial typed; provider/tool config explicit. |
| SDK-03 | P7.B/C | Parity proof for RAI-155.e-style runs | One workflow definition runs from host app, real REPL, and compiled CLI bundle with the same normalized result/error, policy/budget behavior, and cancellation; both direct wires pass local Scala HTTP stands; a non-coding typed workflow without required coding tools runs. Bundle v1 stays `String→String` — typed internals do not prove a typed wire. |

SDK-04/SDK-05 (external repo CI acceptance, artifact/compatibility inventory)
stay later-phase obligations (P7.C, P10) with their own cards; this ADR does
not packetize them.

### 5.2 Authoring preview ordering (SDKADR-03)

- The WF-01/02 authoring preview does **not** wait for a host consumer
  (SDK-01/02), the plugin UI (M2), or M4 acceptance; its own scope/support
  claims are reviewed separately.
- The current lineage/TUI wave and the frozen UI event seam (ADR-024) are not
  blocked and not touched by this contract; the UI event ADR is explicitly not
  a dependency of RAI-116.
- P-LIN obligations, W01–W18, and accepted contracts remain in force; nothing
  here re-scopes them.

### 5.3 Versioning

| Artifact | Version carrier | Rule |
| --- | --- | --- |
| Bundle wire | `BundleManifest.schemaVersion` (supported set `{1}`) | New wire/codec/SPI shape ⇒ new `schemaVersion` + separate ADR + loader/runner migration; v1 never changes silently. |
| Workflow entries | `Workflow.name` + `version: Int` (existing `program.scala`) | Registering the same name creates a new version; running jobs keep their pinned snapshot (`product-repl` §5, `runtime-contracts` §12). |
| SDK API | Pinned toolchain build; experimental status | Breaking change ⇒ migration note + consumer update per §7.3; no silent deprecation; no backward-TASTy promise without proof. |
| Event/journal schemas | Existing envelope `schema_version` (`runtime-contracts` §13) | Unchanged by this ADR. |

## 6. RAI-155 alignment — the first consumer (proposals, explicitly NOT accepted commands)

RAI-155.a freezes the actual selection/export contract **after** this ADR is
accepted. This section gives that card the authoring/launch boundary; the
command names below are **proposals with no current implementation** — there
is no `:export-workflow` command and no `raider compile` command today
(`CliArgs.Target` is `Bundle | Agent | Replay | Resume`; the REPL has no
export command). Nothing in this section authorizes advertising them as
shipped.

Proposed shape (for RAI-155.a to freeze or change):

1. **Export**: a REPL/chat command (proposed `:export-workflow`) takes an
   explicitly selected, completed, sequential task specification and renders
   normal editable Scala — a `Workflow[String,String]`-shaped definition with
   validated JSON decoding inside the String input boundary, explicit
   task/workspace/check/limit parameters, and non-secret origin metadata.
   Export performs no model/tool dispatch (CWF-A02/CWF-C03). A deterministic
   renderer covers the supported task specification; any LLM assistance is a
   separate, explicitly configured operation.
2. **Compile/package** (proposed CLI verb, name unconstrained): compiles the
   edited source with the pinned compiler into a runnable artifact. The
   artifact shape must be decided by RAI-155.a from two honest options this
   ADR leaves open:
   - **Option L (launcher artifact)**: an ordinary Scala main that constructs
     the D1 launch capability explicitly and runs the same definition — no
     bundle SPI change; local and CI runs are the same main in different
     process environments. Chosen by default unless v1 proves sufficient.
   - **Option B (bundle v1 as-is)**: only viable for deterministic
     String→String programs — it **cannot host agent work** because the no-arg
     SPI has no service injection and the runner admits one opaque outer slot
     (§3.2). Selecting Option B for chat-exported agent workflows would
     require bundle v2 (separate ADR + migration), not a v1 workaround.
3. **Run**: local run and non-TTY CI run execute the same definition through
   the same launch capability — one root/admission/ledger per run, preflight
   before spend, artifacts/exit codes per `ci-runtime` §7–8. Fresh workspace,
   current credentials/policy — never inherited chat authority (CWF-D02).
4. **What RAI-155.a must additionally freeze**: selection/boundary semantics,
   refusal cases, output layout, input schema, redaction rules, and the
   remaining-work estimate with proposed hour allocation (its own card
   obligation).

This ADR gives RAI-155 the boundary, not the feature: no export, compile, or
run capability exists until the child cards implement it.

## 7. Implementation packets (PROPOSALS — the coordinator owns phase/estimate allocation)

Effort classes: **Reused** = existing code applied as-is; **Added** = new work;
**Unknown** = cannot be estimated before a decision/selection. Ranges are
8–24 h packets per the card; they are planning proposals, not approved budget.

### 7.1 Packet table

| Packet | Case | Estimate | Reused / Added / Unknown | Owner path (proposed) | Depends on |
| --- | --- | --- | --- | --- | --- |
| PKT-1 Launch capability core | WF-02, SDK-02 | 16–24 h | Reused: `AgentLoop`, `Admission`, `BudgetLedger`, `JobManager`, `RaiderScope`, `BudgetLimits`, providers. Added: factory validating config, acquiring one root, exposing `run(program, input): ZIO[Scope, RaiderError, O]`, lifecycle close with `ScopeCloseReport`. | runtime owner | This ADR accepted |
| PKT-2 Local launch path | WF-01 | 8–16 h | Reused: REPL prelude/facade semantics, `dsl` composition. Added: run an authored definition from the dev/REPL path and from a plain `main` through PKT-1 (identical behavior, no facade-only features). | repl + dsl owners | PKT-1 |
| PKT-3 CI launch path | WF-01 | 12–24 h | Reused: `HeadlessRunner` preflight/artifacts/exit codes, `ci-runtime` §7–8 schemas. Added: compile/package the authored source (Option L default) and non-TTY launch of the same definition; source/build identity recorded. | cli owner | PKT-1, PKT-2 |
| PKT-4 WF-02 verifiable cases | WF-02 | 8–16 h | Reused: cancellation mechanics (`cancel.md`), ledger states. Added: case suite — one root/ledger per run; `maxLLM=1` parent+child no-deadlock; cancel closes owned resources; typed invalid-input/policy/provider/cap outcomes; two runs isolated. | runtime owner | PKT-1 |
| PKT-5 SDK-01 artifact boundary | SDK-01 | 12–20 h | Reused: sbt module split, pinned toolchain. Added: locally versioned artifacts, external consumer probe without checkout source, classpath exclusion proof (no repl/compiler/JLine/cli/testkit on main). | build owner (coordinator designates; `build.sbt` is shared) | PKT-1 |
| PKT-6 SDK-02 lifecycle cases | SDK-02 | 8–16 h | Reused: scope/jobs primitives. Added: host-scope ownership, lazy construction (no I/O), typed budget/policy denial, two-instance isolation, explicit provider/tool config. | runtime owner | PKT-1 |
| PKT-7 SDK-03 parity harness | SDK-03 | 16–24 h | Reused: three existing facades, local Scala HTTP stands, bundle loader. Added: one definition proven across host/REPL/compiled bundle with normalized parity (result/error/policy/budget/cancel); non-coding typed workflow; no double admission. | separate verifier owner | PKT-2..PKT-6 |
| PKT-8 Usage settlement correction (separate runtime card) | H3 | 8–16 h | Reused: `BudgetLedger.settle/markUncertain`, `UsageObserved` event. Added: loop feeds observed usage (or honest uncertain/unpriced) into settlement; zero-cost vs unknown fixtures; end-to-end usage report case. **Must not be hidden inside PKT-1.** | runtime owner | Accounting decision; independent of PKT-1 |

Total proposed new-work envelope: **88–156 h** gross before deduplication;
actual additive effort may be substantially lower where current P4.C/P7
allocations already cover a slice — reconciliation is the coordinator's
(`docs/work/board.md` owner). RAI-115's 120–240 h gross envelope included
wider slices (forge, SDLC kit) that are RAI-117 scope, not packets here.

### 7.2 Effort by phase (comparison, coordinator allocates)

| Phase | Reused | Added | Unknown |
| --- | --- | --- | --- |
| P4.C (WF-01/02, SDK-01/02) | loop/admission/ledger/scopes/jobs, providers, composition, preflight/artifact schemas, pinned build | PKT-1..PKT-6 (68–116 h) | remaining phase budget reconciliation; whether bundle v2 is ever needed (RAI-155.a decision) |
| P7.B/C (SDK-03, preview integration) | facades, stands, bundle loader | PKT-7 (16–24 h) | parity corner cases across real REPL; consumer pilot participants/dates |
| P7+ (accounting truth) | ledger primitives | PKT-8 (8–16 h) | pricing policy for unpriced models |
| P10 (SDK-05 class) | pinned build | not packetized here | publication/compatibility inventory — separate cards |

### 7.3 Consumer migration plan (precondition for freezing any signature)

Before any D1 signature is frozen, the implementing card must list, for each
consumer: impact and migration action:

| Consumer | Expected impact |
| --- | --- |
| REPL facade (`FacadeOps`, `ReplSession`) | Facade delegates to the same capability; console-only `Runtime.default` stays; no behavior change intended |
| CLI run path (`HeadlessRunner`, `CliArgs`) | Keeps `Target.Agent`/`Target.Bundle` disciplines; gains the authored-artifact target via PKT-3; existing v1 bundles unaffected |
| Bundle fixtures/examples (`DemoWorkflow`, `TaskSummary`) | Unchanged (v1 frozen) |
| RAI-155 chain (.a–.f) | First consumer of the frozen boundary; .a freezes command/export specifics |
| Lineage replay/rerun (`ReplayHarness`, `IsolatedRerun`) | Untouched; projection stays zero-dispatch (ADR-031) |
| Plugin SPI (RAI-096, ADR-025) | Separate surface; no coupling introduced here |

## 8. Consequences

- **Positive**: one supported author path (Scala → local + generic CI) built
  almost entirely from existing runtime parts; RAI-155 unblocked; host SDK
  becomes an incremental adapter instead of a redesign; honesty obligations
  are checkable (§4) instead of aspirational.
- **Costs/risks**: the launch capability is new public surface and needs the
  migration discipline of §7.3; single-admission discipline is easy to break
  by accident (deadlock precedent), so PKT-4 cases are mandatory before any
  preview; the accounting gap remains visible until PKT-8 lands — all cost
  reporting through the new path must say "unpriced/unknown", never zero;
  Option L vs Option B for RAI-155 packaging is still open and must be frozen
  by RAI-155.a before implementation.
- **Neutrality**: no change to frozen v1 wire, TUI event seam, gate policy,
  P-LIN acceptance, or the SDLC effects contract (RAI-117 owns it).
