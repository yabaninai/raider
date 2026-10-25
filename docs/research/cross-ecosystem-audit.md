# Cross-ecosystem reuse audit for RAI-196

Status: source/documentation audit at lane baseline `6f4e560` after merging
`main`; this is not an implementation report or proof of portable parity.
Scope: portable semantics listed in
[`cross-ecosystem-plan.md` §3](../cross-ecosystem-plan.md#3-portable-semantics-before-syntax),
current runtime/evidence/checkpoint machinery, and allocation reconciliation
against plan §6. No build or behavioral test was performed for this inventory.

## 1. Existing seams and one-runtime rule

| Concern | Existing source seam | Audit result |
| --- | --- | --- |
| Lazy common execution | `modules/core/.../program.scala`: `Program[I,O]`, `Agent`, `Workflow`; `modules/core/.../task.scala`: sealed lazy `Task[A]`, `Task.zio` interpreter; `modules/cli/.../workflow/WorkflowRun.scala`; `HeadlessRunner.scala` | Existing shared model is `Program`/`Task` and runner boundary; portable input must lower into it. No serializable AST for arbitrary Scala callbacks exists or is promised. |
| Composition | `modules/dsl/.../composition/Composition.scala`: typed `all`, ordered bounded `batch`, fail-fast cancellation and `batchCollect`; `Program.andThen` | Native sequential/parallel composition exists. It does not itself provide a portable node parser, conditional semantics, reference typing, or a serialized plan. |
| Agent execution | `modules/runtime/.../loop/AgentLoop.scala`; `Admission`, `BudgetLedger`; `WorkflowRun` setup | Existing model/tool loop uses root admission, budget reservations, sequential tool dispatch by default, typed failures, and cancellation. Portable agent descriptors must resolve to this configured runtime, not dispatch directly. |
| Existing workflow export | `modules/dsl/.../workflow/StsSelection.scala`, `ChatWorkflowRenderer.scala`; `modules/cli/.../workflow/WorkflowCompile.scala`, `WorkflowRun.scala` | Existing chat-to-Scala supported-task export/compiler path is specialized and partially/currently implemented per its own cards. It is not a portable JSON interchange importer. Preserve the native export path and explicit unsupported behavior. |
| CI and artifact boundary | `modules/cli/.../run/HeadlessRunner.scala`, `EventsJsonlWriter.scala`, `EventExport.scala`, `RunLineageArtifacts.scala`; `docs/ci-runtime.md` §§5–8 | Existing run directory, versioned events, results, summary, JUnit, manifest, exit mapping, redaction, and uncertainty semantics provide the carrier. A portable execution must reuse these writers/owners rather than implement another reporter. |

The `Program`/`Task` class-level contracts explicitly disallow eager dispatch
and a second interpreter. `SdlcStages.program` folds its validated description
into an ordinary `Program` and states there is no scheduler or durable resume.
That specialized SDLC description has fixed stages/order and cannot be treated
as a general portable graph engine.

## 2. Portable-semantics element mapping

“Exists” means a relevant mechanism exists in the inspected source, not that the
portable format is already supported. “Adapt” means retain/reuse an existing
owner but add a narrow binding/projection. “New” means the portable-specific
representation, validation, or composition semantics still need design and
implementation.

| Proposed element (plan §3) | Existing machinery and owner | Classification | Remaining adaptation/new work |
| --- | --- | --- | --- |
| Versioned input/output schemas; bounded path references | `raider.core.Codec`/`schemas.scala`, typed workflow I/O; `WorkflowRun` input decoding; `SdlcEvidence` versioned envelope is a separate evidence schema | **Adapt + new** | Select a deliberately bounded portable schema subset, type-check path references and encode version/refusal behavior. JSON Pointer-like syntax is not currently an executable workflow reference contract. |
| Stable portable step IDs | Existing run/job/root/event IDs and lineage projection IDs; `EventHub` envelopes correlate runtime events | **Adapt** | Add stable definition-level step identity and map it to existing per-run job/phase/event lineage. Do not conflate stable definition IDs with invocation run IDs. |
| Agent step with named profile/tool set | `AgentLoop`, profile/backend resolution in CLI launch, `ToolRegistry`, admission and root budget | **Adapt** | Resolve names only from trusted pinned registry/configuration; profile/tool permissions intersect invocation policy. Portable input cannot create tools or expand authority. |
| Local structured-output validation | `Codec`, schema codecs, `AgentLoop` output validation and bounded plan repair in `PlanMode` | **Adapt** | Portable schema-subset validation and provider-route reporting; locally validate regardless of provider-native schema. Capability unknown/unsupported remains explicit. |
| Sequence | `Program.andThen`, `Task.flatMap`, `SdlcStages.program` | **Exists for native composition; adapt for portable lowering** | Build sequential Program composition from validated references. No independent executor. |
| Typed conditional | `Program`/`Task` supports effect composition; no general serialized conditional workflow node identified | **New** | Define a closed typed predicate catalog and branch typing in RAI-197; lower both validated branches to one Program/Task. No expressions or closures. |
| Bounded parallel map and join | `all`, `batch`, bounded `foreachPar`, fail-fast sibling cancellation, `batchCollect` explicit domain-failure values | **Exists for native composition; adapt for portable node semantics** | Specify bounded input size/fan-out, join ordering, typed result shape, fan-in error policy, and cancellation mapping. Validate before construction/dispatch. |
| Trusted deterministic check references | `SdlcStages.Stage.Checks`; `SdlcEvidence.CheckResult`; CLI trusted-check registry / `ci-runtime` trust boundary; RAI-195 declaration contract | **Exists; adapt** | Portable check IDs resolve against a pinned trusted registry snapshot. Preserve protected verifier boundary, actual outcome/evidence, and fail-safe handling; no task/model-supplied command. |
| Bounded repair/checkpoint | `AgentLoop.runPlan` bounded repair and common admission; `SdlcStages` repair count; `CheckpointDispatch` / `ApprovalArtifacts`; WorkflowRun fresh-launch recheck | **Exists for specialized cases; adapt** | Define which portable operation can be repeated safely, max repair attempts, and checkpoint placement binding to launch policy. Never retry ambiguous mutation; checkpoint cannot grant authority. |
| Root deadline/attempt/child/token limits | `Admission`, `BudgetLimits`, `BudgetLedger`, `JobManager` subtree ownership and cancellation | **Exists; adapt** | Map portable declarations to owner-granted ceilings; reject impossible/unbounded limits at preflight. Monetary caps only where estimates/enforcement support them; keep unknown unknown. |
| Explicit output bindings and machine-readable final verdict | `Program[I,O]`, codecs, `SdlcEvidence` candidate/check/review/integration records, `SdlcArtifactSchema`, `WorkflowRun` and CI result artifacts | **Adapt + new** | Define portable output binding and step/evidence references; add versioned projection using existing artifact writers. Model output cannot manufacture a trusted check verdict. |
| Same-run execution and artifacts in CLI/local CI | `WorkflowRun`, `HeadlessRunner`, `events.jsonl`, `run.json`, `result.json`, JUnit/summary/artifact manifest; `Task.zio` single interpreter | **Exists; adapt** | Portable validator/loader selects Program/Task before the same runner; no second scheduler/reporting path. Preserve existing defaults, artifact versions, exit/error semantics. |

## 3. SDLC stages, evidence, checkpoints, and carrier audit

* **RAI-164 stages:** `modules/core/.../sdlc/SdlcStages.scala` defines
  `StageDescription`, `WorkflowDescription`, `Context`, `StageResult`, a fixed
  enum (`Task`, `Plan`, `Implement`, `Checks`, `Repair`, `Review`,
  `Integration`, `LocalRelease`), validation, and lowering to `Program`.
  This is proof that a bounded, described stage sequence can use Program/Task;
  it is not general sequence/branch/map/join interchange. The fixed vocabulary,
  order and release-specific evidence predicates must not be exposed as the
  universal portable semantics without a separate contract decision.
* **RAI-164 evidence:**
  `modules/core/.../sdlc/SdlcEvidence.scala` defines schema-v1 candidate-bound
  `CheckResult`, `ReviewVerdict`, `IntegrationResult`, `LocalReleaseResult`,
  typed union records and digest/applicability checks. Reuse those identities
  where semantics match; do not treat arbitrary output references as trusted
  evidence or change its frozen schema in this card.
* **RAI-165/179 checkpoints:**
  `modules/runtime/.../sdlc/approval/ApprovalArtifacts.scala` and
  `CheckpointDispatch.scala`, plus CLI `WorkflowRun.scala`, implement an
  owner-declared launch checkpoint, request/decision validation, expiry/current
  state/capability recheck, and fresh-invocation dispatch. This is an existing
  control boundary; a portable checkpoint reference may bind to it but cannot
  place authority in the document or resume a suspended fiber.
* **RAI-174 carrier:** `modules/cli/.../run/SdlcArtifactSchema.scala` projects
  typed SDLC records to the existing `run.json`/`events.jsonl` carrier and
  preserves legacy shape when the extension is absent. Extend additively only
  after schema review. Do not create a portable-only result store.
* **ci-runtime artifacts:** `HeadlessRunner` and associated event/result
  writers already own bounded/redacted artifacts and exit mapping. Existing
  `events.jsonl` is a stream/checkpoint for actual events, not a guarantee of
  durable workflow-step resume. Reuse its truthful incomplete/uncertain
  behavior and existing manifest identity.

## 4. Market and comparative evidence boundary

The 2026-10-23 competitive research inspected release listings and product
documentation for LLM4S and LangChain4j. It did **not** install either
competitor, execute benchmarks, make paid requests, or interview users. LLM4S
0.5.1 is pre-1.0 with compatibility cautions; LangChain4j's agentic API is
documented as experimental. Documentation capability is not measured parity.
The LLM4S Kotlin artifact publication mismatch reported in release notes is a
reminder to test clean consumer installation rather than infer it from source
or documentation. Any future comparison must pin artifact coordinates, hashes,
module versions, and JDK; compare equivalent tools/prompts/model/budgets and
record setup/adaptor effort separately.

No foreign-language SDK, benchmark, participant usability result, or demand
claim follows from this reuse audit.

## 5. Estimate reconciliation with cross-ecosystem plan §6

| Card | Plan range (hours) | Allocation/audit interpretation |
| --- | ---: | --- |
| RAI-196 | 24–48 | This card: architecture ADR, source inventory/reuse audit, ownership and mockup protocol; docs/profile gates and review included. |
| RAI-197 | 40–80 | Versioned schema/portable semantics ADR and positive/negative contract cases. Existing codec/evidence/SDLC seams reduce invention but do not replace format validation design. |
| RAI-198 | 80–160 | Strict zero-dispatch validation and lowering to existing runtime. This is the largest genuinely new portable compiler/validator work. |
| RAI-199 | 60–120 | Portable CLI path and integrated native/portable semantic parity on the same Program/Task runtime and artifact boundary. |
| RAI-200 | 24–48 | Managed-child JSON-lines protocol ADR/corpus; reuses existing headless lifecycle/error/artifact concepts, but freezes a new process wire. |
| RAI-201 | 60–120 | Process adapter, bounded framing/backpressure/cancel/exit/child cleanup; must call existing runtime once per invocation, not schedule a second runtime. |
| RAI-202 | 24–48 | Capability audit/contract; reuse `ModelCapabilities`, codecs and local validation; any missing public request shape gets separate ADR before implementation. |
| RAI-203 | 60–120 | Chat selection and recipes; builds on existing chat/export concepts but includes real portable recipes and refusal/policy wiring. |
| RAI-204 | 32–64 | Installed quickstart and first-use evidence. Independent onboarding card; not accounted as RAI-196 reuse. |
| RAI-205 | 40–80 | Independent comparative and consumer acceptance. Requires immutable integrated candidate and separate external demand evidence. |
| **RAI-196..205** | **444–888** | Sum of the plan's provisional card ranges; unchanged and additional to older 13,100-hour envelope and TUI-X. |
| RAI-206 (optional) | 16–32 | Demand-gated Java embedding design spike only; requires a real JVM consumer request. |

Ranges already include implementation, review, and relevant tests. Source reuse
is recorded as avoided duplication, not as permission to silently reduce the
planned ranges. Coordinator records any revised per-card remaining allocation
before implementation. New provider capabilities, foreign SDKs, custom syntax,
or durable execution are excluded and require separate scoped estimates/cards.

## 6. Source inventory and verification status

Primary inventory inspected: `program.scala`, `task.scala`,
`Composition.scala`, `AgentLoop.scala`, `Admission.scala`, `BudgetLedger.scala`,
`SdlcStages.scala`, `SdlcEvidence.scala`, `CheckpointDispatch.scala`,
`ApprovalArtifacts.scala`, `SdlcArtifactSchema.scala`, `WorkflowRun.scala`,
`HeadlessRunner.scala`, `RunLineageArtifacts.scala`,
`docs/ci-runtime.md`, ADR-033, RAI-196..206 task registration, roadmap §6e,
and the board's current RAI-164/165/174/179 status notes.

No source files were changed by RAI-196. This table is a static source/document
inventory only. The code inventory, runtime behavior, no-provider validation,
semantic parity, foreign-client usability, and consumer scenarios require their
named implementation/acceptance cards; this document does not execute or close
those cases.
