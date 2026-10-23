# ADR-035: Cross-ecosystem workflow boundary and first portable surface

Status: **Proposed** (RAI-196 architecture/market boundary; not an accepted
portable schema or implementation contract). Date: 2026-10-23. This ADR follows
the [cross-ecosystem plan](../cross-ecosystem-plan.md),
[competitive research](../research/llm4s-langchain4j-2026-10-23.md), and
[roadmap §6e](../roadmap-12k.md#6e-cross-ecosystem-workflows). Source inventory
is based on the RAI-196 lane after merging `main` at `6f4e560`.

No API, command, schema, SDK, grammar, customer demand, or feature is declared
implemented by this proposal. RAI-197 owns the portable format and semantics
contract; independent review is required before it is frozen.

## 1. Product boundary and ordered audience hypotheses

Proposed promise: turn useful agent work into an editable, checked workflow that
runs from a team's existing tools. The first product path is local coding work
and generic CI. Portable definitions are a second entry surface into the same
Scala 3 + ZIO execution system; native Scala remains the expressive authoring
and embedding surface.

The audience order is deliberate; these are hypotheses, not observed demand or
three simultaneous launches.

| Order | Audience | First recurring task | Initial entry surface | Evidence required before expansion |
| --- | --- | --- | --- | --- |
| 1 | Polyglot development/platform teams | Repair failing tests or apply a bounded migration with independent checks | Installed CLI, portable recipe, generic local CI | Repeat runs in two non-Scala repositories with independently verified changes |
| 2 | JVM application teams | Typed extraction/classification followed by a deterministic application check | Same local process boundary; Java-friendly embedding considered later | A real caller needs in-process integration and accepts its lifecycle contract |
| 3 | Internal automation/data teams | Extract fields from supplied documents or route supplied tickets into a report | JSON input/output and portable workflow, without connectors | Repeat use saves manual work at an acceptable correction rate |

Test transfer beyond coding first with a read-only extraction recipe. This does
not authorize a RAG platform, connectors, hosted service, or external outreach.
No participant evidence is available from this card; demand remains unverified.

## 2. Entry surfaces and DSL decision

Keep separate (a) the repository language, (b) the caller language, and (c) the
language implementing Raider. A repository may be non-Scala and a caller may be
any language without changing the product implementation language. All product
modules, tests, and HTTP stands remain Scala 3 + ZIO.

| Surface | Decision for v1 | Reason / boundary |
| --- | --- | --- |
| Versioned JSON workflow interchange with schema-aware editing | **Select as the first portable execution surface.** RAI-197 must define exact schema fixtures/refusals before implementation. | Producers in any language can emit ordinary JSON; deterministic validation and lowering need no new grammar. JSON is the engineering default, not a claim of superior human authoring. |
| Ordinary Scala DSL | **Retain.** | Existing typed `Program`/`Task`, `andThen`, `all`, and `batch` preserve compile-time expressiveness. Arbitrary Scala closures remain native and are not coerced into portable nodes. |
| YAML over the JSON semantics | **Defer.** | Implicit typing, tags, aliases, duplicate-key handling, source locations, and an extra parser dependency are unjustified before user evidence. Evaluate only in a separate reviewed card. |
| TypeScript/Python/Kotlin builders or SDKs | **Defer.** | Each package creates versioning, installation, support, and shipped-language obligations; later builders may be thin JSON producers if demand justifies them. No language-policy exception exists. |
| New textual language / expression grammar | **Reject for this program.** | Parser, type checker, completion/LSP, debugger, security, and migration costs lack measured advantage over JSON plus Scala. The illustrative sketch in the plan is not syntax and must not be parsed. |

The authoring comparison protocol is specified in §7. It uses read-only
mockups; no participants means the usability result is **pending**, not a
positive result or a blocker to the independent engineering contract work.

## 3. Shared execution, policy, and evidence boundary

```text
chat selection -> portable definition (supported subset only)
portable document -> validate / resolve -> existing Program[ I, O ] / Task[ O ]
native Scala --------------------------------> existing Program / Task
CLI or local process client ----------------> same launch, policy, and evidence boundary
                                                     |
                             existing Runner / AgentLoop / admission / budget
                             owned cancellation / checkpoints / artifacts
```

The portable representation is validated and lowered to the existing lazy
`Program`/`Task` execution path. It does not introduce an intermediate runtime,
job model, scheduler, retry engine, budget ledger, or persistence engine. Native
Scala uses the same runner and may express behavior the portable subset cannot;
portable export of an arbitrary closure must fail with a precise unsupported
reason. Import, validation, editing, and export dispatch no model or tool work.

The source audit (see
[`cross-ecosystem-audit.md`](../research/cross-ecosystem-audit.md)) identifies
these existing owners:

* `raider.core.Program[I,O]` is the shared composition contract;
  `Task[A]` is lazy and has one ZIO interpreter boundary. `all` and bounded
  `batch` already express typed parallel composition and cancellation.
* `AgentLoop` owns model/tool rounds and uses the existing `Admission`, root
  limits, and `BudgetLedger`. Agent/profile resolution and tools come from
  trusted invocation configuration, not workflow input.
* `SdlcStages` is a real, bounded SDLC stage description lowered to a
  `Program`; its fixed stage vocabulary/order is not a general portable graph.
  `SdlcEvidence` already supplies candidate-bound check/review/integration and
  local-release evidence types.
* `CheckpointDispatch` and `ApprovalArtifacts` implement launch-policy
  checkpoint request/decision/recheck behavior. The checkpoint is owner
  supplied and can only narrow or pause authority; workflow data cannot grant
  capability.
* `SdlcArtifactSchema`, the CLI workflow/headless execution path, and CI
  artifacts provide existing event, result, evidence, and artifact carriers.
  Portable step identity/provenance needs an additive reviewed projection, not
  a second artifact store.

### Contract-level security and compatibility commitments

These constraints are requirements for RAI-197 onward, not a declaration that
the portable contract has already been implemented:

1. Workflow documents are reviewed executable configuration. Inputs, repository
   content, model output, and tool output are untrusted data. Profile and trusted
   check references resolve from pinned/owner-trusted configuration; effective
   permissions are intersected with inherited invocation authority and may only
   narrow. No input or model result can add commands, profiles, tools, or rights.
2. References use a bounded declarative path form (for example JSON Pointer),
   never executable expressions, shell interpolation, arbitrary callbacks, or
   dynamic remote imports. Schema validation is local even when a provider has
   native schema support; provider capability remains endpoint/model/config
   evidence and `Unknown` never silently becomes supported.
3. Reject unknown major versions, duplicate keys/IDs, cycles, missing or
   ill-typed references, impossible joins, oversized/deep documents, and
   unbounded work before dispatch. Diagnostics identify document path, step ID,
   invalid reference, and a suggested correction. Validation makes zero model
   or tool calls.
4. Agent, profile, tool, check, and verifier definitions bind through the
   trusted registry/configuration snapshot. Task input and model output cannot
   synthesize commands or widen profile permissions. Trusted checks remain
   protected from edits by the workspace being checked.
5. Root deadline, attempt, child, and token limits use existing root ownership
   and accounting. Monetary limits are claimed only when estimate and
   enforcement support them; unknown remains unknown, never zero. Map fan-out is
   bounded; cancellation owns all children through the existing runtime.
6. Repair is bounded and may repeat only a declared safe operation. Never
   automatically repeat a mutating operation after an ambiguous outcome.
   Interruption/uncertain effects require existing read-only inspection and
   explicit reconciliation; no durable resume guarantee is made for arbitrary
   Scala lambdas.
7. Preserve current `Program`/`Task`, command defaults, frozen evidence
   contracts, BundleWorkflow v1, and existing Scala workflow behavior. Any
   public wire/API evolution is versioned, has migration/refusal fixtures, and
   is reviewed before implementation. An unsupported required capability
   blocks dispatch; there is no hidden provider fallback after auth/policy
   failure.
8. Scala programs and configured processes execute with their process
   permissions. Tool policy is not a sandbox. No arbitrary class deserialization,
   automatic dependency download, or sandbox claim is introduced.

## 4. Explicit non-goals

This ADR does not authorize foreign-language runtime code or SDKs, a new textual
grammar, YAML parser/dependency, daemon, remote/distributed scheduler, second
runtime, durable workflow engine/database, autonomous supervisor, RAG/vector
platform, connectors, copied competitor code, publication, or paid comparison.
No edits to Yabanin are in scope. External SDKs, custom syntax tooling, and
durable execution require distinct cards and estimates.

## 5. Reuse audit and work allocation

The detailed element-to-symbol audit, classification (exists/adapt/new),
provenance, and estimation reconciliation is in
[`cross-ecosystem-audit.md`](../research/cross-ecosystem-audit.md). In brief:
composition, agent execution, root limits/admission, CI artifact transport,
candidate-bound evidence, and launch checkpoint mechanics exist. Portable
validation/lowering, stable portable step identity and reference typing, general
conditional/map/join lowering, and an additive portable result projection are
new or require adaptation. Existing `SdlcStages` is a specialized SDLC workflow,
not a shortcut that proves a general portable graph already exists.

The program estimates remain provisional and unchanged: RAI-196 24–48 hours;
RAI-197 40–80; RAI-198 80–160; RAI-199 60–120; RAI-200 24–48; RAI-201 60–120;
RAI-202 24–48; RAI-203 60–120; RAI-204 32–64; RAI-205 40–80. RAI-196..205
sum to 444–888 hours of additional scope. Optional, demand-gated RAI-206 adds
16–32 hours. These values include implementation, review, and relevant tests;
they are not elapsed work, approved budget, or a delivery date. Reuse is credited
in the element audit, not deducted from the approved ranges without coordinator
allocation. Capability gaps discovered by RAI-202, foreign SDKs, custom syntax,
and durable execution are excluded and require separately estimated cards.

## 6. Ownership and sequence

RAI-196 owns this ADR and the read-only reuse audit. RAI-197 owns the versioned
portable data format and semantics; RAI-198 owns validation/lowering; RAI-199
owns portable CLI execution and semantic parity. RAI-200 owns process protocol
design; RAI-201 its managed-child adapter; RAI-202 provider/output capability
contract; RAI-203 chat selection/recipes; RAI-204 onboarding; RAI-205 independent
comparative/consumer acceptance; RAI-206 only a demand-gated Java design spike.

Contracts 197/200/202 may proceed after 196. Implementation follows the plan's
dependencies. Root checkout, public contracts, build, gate registry, and board
have one coordinator owner; implementation lanes receive non-overlapping
concrete paths. Preserve active First CI and TUI-X work. Lane evidence does not
replace gates on an integrated candidate.

## 7. Authoring mockup comparison protocol

Compare read-only mockups of native Scala composition and a schema-aware JSON
editor using the same task text, inputs, and expected outcomes. Do not run or
dispatch workflows. For each participant, randomize mockup order and record
background, order, completion time, correction count, and comprehension answers.
Use five independent tasks:

1. **Change schema:** add or change a typed input/output field and locate all
   affected references.
2. **Insert check:** add a named trusted deterministic check without turning
   model/task text into a command.
3. **Add branch:** express a typed conditional and explain both branch outcomes.
4. **Bound map:** set a map's fan-out and join behavior, then identify what
   happens on one child failure or cancellation.
5. **Diagnose invalid reference:** find an invalid step/data reference and
   select or describe the actionable correction.

Measures are task completion time, number of corrections before a valid result,
and comprehension of data flow, trusted-vs-untrusted authority, failure, and
limits. Preserve raw per-task outcomes and note assisted/unassisted completion;
do not pool an internal author walkthrough as participant evidence. No
participants are assigned or contacted by RAI-196: result **pending**. JSON is
selected as an engineering interchange default, not declared the usability
winner. RAI-197 may refine its shape without changing the single-runtime rule.

## 8. Evidence and review

The source inventory is reported with the RAI-196 record and audit. XE-ARCH-01
is addressed by the element map and named existing owners/cases; XE-ARCH-02 by
the unsupported-surface, policy, capability, and documentation boundaries;
XE-ARCH-03 by the ownership/sequence above. These are documentation acceptance
claims for independent review, not execution or parity results. Current
comparative usability, non-Scala consumer runs, foreign-client usability, and
live model behavior remain pending until independently performed under their
respective cards and budgets.
