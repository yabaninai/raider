# ADR-036: Versioned portable workflow format and semantics

Status: **Proposed**. Date: 2026-10-23. Owner: RAI-197. This proposal depends
on [ADR-035](ADR-035-cross-ecosystem-workflow-boundary.md), which selects JSON
interchange and one shared runtime but does not freeze a schema. The normative
draft schema and fixtures are in
[`contracts/workflow-portable/`](../../contracts/workflow-portable/README.md).
This ADR requires independent contract review before acceptance and before RAI-198
uses it as an implementation baseline.

This is a data contract, not a declaration that an importer, schema validator,
lowerer, CLI command, editor, or parity suite has been implemented. JSON Schema
is the editing aid; semantic validation remains a local, zero-dispatch
preflight. No new runtime, scheduler, persistence engine, or product language is
introduced.

## 1. Decision and scope

Propose `raider.portable-workflow` version `1.0` as a strict JSON document.
Versioning has two levels: `format` is the stable family identifier and
`version` is `MAJOR.MINOR`; an unknown major is refused, while a newer minor is
accepted only when the reader explicitly declares all encountered features
supported. JSON Schema `$schema` identifies the meta-schema and `$id` identifies
this versioned schema; neither is the workflow version.

V1 freezes the following portable semantics from
[cross-ecosystem-plan §3](../cross-ecosystem-plan.md#3-portable-semantics-before-syntax):

* JSON Schema subset for declared input/output and step values; stable
  definition-local step IDs; typed JSON Pointer references (`#/inputs/...` or
  `#/steps/<id>/output/...` using canonical escaped tokens).
* `agent`, `trustedCheck`, `sequence`, `conditional`, bounded `map`, and `join`
  step kinds. Each has a closed object shape and explicit input/output type.
* Conditionals use the closed predicate set `equals`, `notEquals`, `isPresent`,
  and `all`/`any` over those predicates. Operands are typed literals or typed
  references. No expression string, callback, interpolation, remote import, or
  host-language evaluation is portable.
* Agent profile and toolset names resolve from the launch's pinned trusted
  registry. Structured output is validated locally against the declared
  bounded schema irrespective of provider-native schema support.
* A trusted check reference resolves by `(checkId, registrySnapshot)` against
  owner-trusted configuration. Its provenance includes registry digest,
  definition digest, verifier/toolchain identity, and candidate/input digest
  when applicable. A workflow cannot define command text or manufacture a
  trusted pass result.
* Repair declarations cap attempts, identify the operation eligible for repair,
  and require safe/idempotent classification by trusted configuration. Explicit
  checkpoints pause at an owner-configured launch-policy boundary; the workflow
  cannot grant authority or resume a prior suspended execution.
* Root limits require finite `deadlineMs`, `attempts`, `parallelism`, `children`,
  and `tokens` with implementation ceilings. Monetary limits are deliberately
  omitted until estimation and enforcement are evidenced; unknown cost remains
  unknown.
* Explicit named output bindings identify typed step/input references and a
  required machine-readable final verdict (`pass`, `fail`, `incomplete`,
  `uncertain`, or `refused`). Only an authorized trusted check/verifier can
  produce `pass`; model text cannot set it.

The bounded schema vocabulary and numeric maxima live in `schema/workflow-v1.schema.json`.
Changing this vocabulary, predicate set, type/ref rules, failure behavior,
provenance requirements, or limits is a versioned contract change and requires
review plus fixture/migration updates.

## 2. Document and schema section map

The schema is normative for local document shape; semantics in this ADR are
normative for graph validation, trusted resolution, and execution outcomes.
Schema validity alone never implies safe or executable.

| Schema section | Frozen contract |
| --- | --- |
| Root `format`, `version`, `$schema`, `$id` | Format identity, semver-like major/minor, pinned JSON Schema 2020-12 identity. `additionalProperties: false` at every object boundary. |
| `limits` | Required finite positive root deadline/attempt/parallelism/children/token bounds; deployment maximum is an independent stricter ceiling. |
| `schemas.inputs`, `schemas.outputs` | Closed bounded JSON Schema subset: object, array, string, boolean, integer, finite number, null, enum, required/properties/items, min/max length/items, and additionalProperties false. No `$ref`, pattern regex, dynamic anchors, content decoding, or arbitrary keywords. |
| `steps[]` and `stepId` | Globally unique stable IDs matching `[A-Za-z][A-Za-z0-9._-]{0,63}`; IDs are definition identity, not run/job identity. References to IDs must resolve and graph must be acyclic. |
| `agent` | Named `profile` and `toolset`, typed input pointer, locally validated declared output schema ID. Names do not grant capabilities. |
| `trustedCheck` | Registry-bound `checkId` plus explicit pinned `registrySnapshot`, typed input, trusted provenance output. No executable command field. |
| `sequence` | Ordered non-empty child step IDs; outputs bind the final declared result. Children execute in order and stop at first non-success. |
| `conditional` | Closed typed predicate tree and two branch step IDs with compatible declared output types. Exactly the selected branch executes. |
| `map` | Input array pointer, one declared child template, finite `maxItems` and `parallelism`, stable index ordering. One child failure fails the join and cancels siblings; external cancellation remains cancellation. |
| `join` | Explicit unique incoming step IDs, ordered deterministic result object, and fail-fast semantics; impossible/unreachable members are preflight refusals. No implicit waiting or future/dynamic dependency. |
| `repair` | `maxAttempts` within root attempts; references an eligible step and checkpoint; only a registry-marked safe operation can repeat. Ambiguous mutation is `uncertain`, never retried. |
| `checkpoint` | Stable ID and owner-configured checkpoint reference. It can pause/refuse pending an explicit decision and fresh launch recheck; it cannot provide credentials, capability, or a continuation. |
| `outputs.bindings`, `outputs.verdict` | Unique output names, typed references, required final verdict reference and verdict authority rule. Artifacts are references to bounded existing artifact writers, not arbitrary paths. |

References use RFC 6901 JSON Pointer encoding and are typed before lowering.
Percent-encoding, URI fragments outside the leading `#`, wildcards, dynamic
keys, and noncanonical escapes are refused. A pointer into a map item is only
valid in that map's template scope. Graph `stepId` references are a separate
closed identifier form and are never interpreted as pointers.

## 3. Execution and failure semantics

Document load, schema validation, semantic validation, trusted-name resolution,
reference typing, and lowering-plan construction are pure preflight. They make
zero model or tool calls, do not evaluate a Scala value, and do not create a
runtime root. All failures return deterministic diagnostics including a stable
code, document JSON Pointer, step ID when known, offending reference/name/value
when safe, and a suggested correction. Never echo secrets or untrusted content
without existing redaction.

Execution begins only when the caller launches the resulting ordinary lazy
`Program[I,O]`/`Task[O]` through the configured Runner. Existing invocation
policy intersects all trusted profile/tool/check declarations; child authority
can only narrow. One existing root owns cancellation, children, admission,
accounting, deadlines, and artifacts. Declared limits must be within both
trusted invocation ceilings and runtime-supported ceilings before dispatch.
Schema/token estimates do not claim exact usage or monetary enforcement.

* `sequence`: ordered; first failed/refused/uncertain/cancelled step terminates
  the sequence. No continuation past a failure unless represented by an
  explicit supported conditional over a typed successful value.
* `conditional`: evaluate only the bounded predicate over validated immutable
  inputs/results; unknown/missing operands are validation errors, not false.
* `map`/`join`: preflight fan-out is bounded by document and root limits.
  Results preserve source-index order, not completion order. A domain failure
  fails the aggregate and cancels owned siblings; external cancellation is not
  converted to a value. No silent `batchCollect` of failed work.
* `repair`: each attempt is charged to the same root attempts/tokens and is
  bounded by the declaration and root ceiling. It may repeat only a trusted
  operation whose configured semantics establish safe repeat. After an
  ambiguous mutating outcome, emit `uncertain`, stop, and require read-only
  reconciliation and a new explicit action; no automatic retry.
* `checkpoint`: use the existing launch-policy checkpoint request/decision and
  recheck boundary. In headless mode, no blocking prompt; return the existing
  needs-approval/refusal behavior and request artifact. A decision starts a new
  invocation, reloading current policy and state; no suspended fiber resumes.
* `trustedCheck`: launch only a check already present in the pinned trusted
  registry snapshot and permitted by invocation policy. Result provenance binds
  the check definition, verifier/toolchain, candidate/input, and actual outcome.
  A model assertion or output binding cannot turn failure/skipped/error into
  pass.
* terminal results and artifact references project additively into existing
  lineage/run/evidence carriers. Stable portable step IDs correlate definition
  steps to runtime identities; they never replace per-run IDs. Preserve current
  artifact schemas unless a separately reviewed additive projection is accepted.

Final verdict is machine-readable and is the terminal result of a trusted
verifier policy over actual step outcomes. `pass` requires every required
trusted check to have a provenance-valid passing result. `fail` represents
observed failed work/checks; `incomplete` covers missing required completion;
`uncertain` covers an effect whose completion is ambiguous; `refused` covers
preflight or policy rejection. Cancellation remains a cancellation outcome and
is not falsely rewritten as any successful verdict.

## 4. Exists/adapt/new lowering contract

RAI-198 implements strict validation and exact lowering. This table freezes the
mapping boundary only; no portable runtime/interpreter is proposed.

| Portable construct | Audit classification | Lowering target / rule |
| --- | --- | --- |
| Input/output schema and typed JSON Pointer | Adapt + new portable validation | Existing `Codec`/schema codecs and `WorkflowRun` input decoding after closed-subset translation; typed refs resolve during preflight. |
| Stable step ID/provenance | Adapt | Project definition ID onto existing Program/Task lineage and event/artifact identities; retain separate invocation IDs. |
| Agent/profile/toolset and structured output | Adapt | Trusted launch registry resolves descriptors; existing `AgentLoop`, `ToolRegistry`, local `Codec` validation, `Admission`, `BudgetLedger`. Unknown required provider capability refuses; no direct fallback. |
| Sequence | Exists natively; adapt portable mapping | Compose lazy `Program.andThen`/`Task.flatMap` in declared order. |
| Typed conditional | New portable structure | Construct lazy typed branch composition inside `Program`/`Task`, predicate evaluated against validated typed data only. |
| Bounded map and join | Existing `all`/`batch`; adapt declared semantics | Lower to bounded `Composition.batch`/`all`/`Task` composition with deterministic ordering, fail-fast owned cancellation, and prevalidated caps. |
| Trusted check | Exists; adapt binding/projection | Resolve existing CLI trusted-check registry and `SdlcEvidence`/check result carriers by pinned provenance; no document-supplied command. |
| Repair/checkpoint | Exists for specialized cases; adapt | Reuse AgentLoop bounded repair and `CheckpointDispatch`/`ApprovalArtifacts`; no new retry owner or resume engine. |
| Root limits | Exists; adapt | Intersect declared finite limits with invocation limits and existing `Admission`, `BudgetLedger`, and root-owned child/deadline controls. |
| Outputs and verdict | Adapt + new projection | Bind typed Program output and existing artifact/evidence writers; add only a reviewed portable projection; trusted verifier owns pass. |

The audit is static inventory (`cross-ecosystem-audit.md`), not proof of these
semantics already working for portable documents. Any node not defined here,
unknown required feature, unsupported type/capability, or construct whose
semantics cannot be represented by these owners is refused before dispatch with
`unsupported-construct` (or a more specific refusal code). No lossy downgrade,
silent omission, arbitrary Scala-closure export, second interpreter, scheduler,
retry loop, budget ledger, or artifact store is allowed.

## 5. Compatibility, editing, and explicit exclusions

Preserve existing Scala workflows, `Program`/`Task`, CLI defaults, BundleWorkflow
v1, SDLC schemas, and existing evidence contracts. Portable JSON is an additive
entry surface only. An unsupported old/new major is not guessed or auto-migrated.
Any incompatible evolution requires a new reviewed ADR, explicit version
behavior, migration fixtures, and updates to all consumers/examples.

Initial editing support is JSON Schema 2020-12 completion/hover and local
diagnostics generated from the schema plus semantic validator. Diagnostics
include document path, step ID, offending reference, and corrective action.
This does not specify an LSP or custom editor. There is no parser for the
illustrative sketch in cross-ecosystem-plan §3; that sketch is expressly not
syntax. YAML, textual DSLs, expression grammars, foreign-language builders,
arbitrary callbacks, arbitrary Scala serialization, durable resume, monetary
caps without enforcement, and remote imports are out of scope.

## 6. Review and implementation gate

Required review confirms the shape and budgets, references and typing, failure
and cancellation semantics, provenance/trust binding, verdict authority, and
compatibility with ADR-035 and the RAI-195 trusted-check contract. RAI-198 may
start only against the accepted ADR and these fixtures. XE-SPEC-01/02/03 remain
acceptance obligations; the fixture corpus is a specification asset, not a
validator pass. Validation must prove zero model/tool calls in RAI-198.

No independent review or implementation is claimed here. Until accepted, this
ADR and schema are proposals; downstream work must not represent them as a
finished product contract.
