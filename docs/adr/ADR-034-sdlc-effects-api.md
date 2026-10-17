# ADR-034: Proposed Public SDLC Effects API and Consumer Migration

Status: **PROPOSED DESIGN — not an accepted contract.** This document proposes
an API design for review under RAI-163. ADR-030 remains the accepted SDLC
contract. No signature, schema version, implementation, or consumer migration is
authorized or frozen by this proposal. Independent public-contract review is a
mandatory gate before any downstream signature freeze.

## 1. Purpose and boundaries

ADR-030 D7 selects a public effects API and requires ADR-029 H8 discipline:
design and consumer migration first, signature freeze only after review. This
proposal maps the ADR-030 SDLC-01..05 obligations into a possible public surface.
It does not select a forge, change `Program`/`Task`, alter BundleWorkflow v1,
modify ci-runtime generally, or implement effects. The initial implementation
target remains the generic Scala forge stand and disposable Git repository.

The goal is to describe effects as typed, inspectable values and keep their
execution in an owner-controlled capability boundary. A declaration is not
permission to perform the effect. Effective authority is the intersection of
the current invocation's owner-granted capability set and the workflow's
narrower declarations. Task text, repository content, model output, generated
Scala, an approval file, or exported chat source cannot expand that set.

## 2. Proposed surface: descriptions separate from execution

Conceptually, public API types would be grouped into three roles (names below
are illustrative, not proposed frozen Scala identifiers):

1. **Typed effect descriptions** represent requested intent, resource identity,
   explicit preconditions, and required capabilities. Construction is pure and
   does not inspect or mutate a repository. They may be composed into ordinary
   bounded workflow stages.
2. **Effect interpreters/executors** are supplied by the trusted invocation
   host. They validate the capability intersection, current Scala-configured
   rules and preconditions, perform read-only reconcile, then optionally perform
   one authorized mutation. A workflow cannot manufacture or replace an
   interpreter.
3. **Typed outcomes/evidence** distinguish completion, refusal, and uncertainty;
   no non-success is converted to success by a model or a caller.

### 2.1 Integration effect families

The proposed intent families are `Commit`, `PullRequestCreate` (or
open/update—review the desired distinction), `PullRequestStatus`, and `Merge`.
Each description names the repository/worktree, candidate identity, requested
target/ref, configured rule snapshot, check/review evidence references, and
expected preconditions. Exact fields and methods remain open.

Commit, PR creation/update, and merge are mutations. PR status is an observation
and must be read-only. Before **every mutation**, the executor first performs
read-only reconcile against current repository/forge state and compares the
observed state with explicit preconditions and authoritative Scala workflow
rules (ADR-030 D2). Divergence yields a typed refusal; it never falls through to
the mutation. The executor rechecks current invocation capabilities at dispatch,
not only when the description is constructed. One integration owner serializes
mutations per repository/target. Stable operation correlation keys may assist
reconcile but do not promise exactly-once behavior.

### 2.2 Local release effect families

The proposal includes typed descriptions for determining/validating a SemVer
version and changelog input, creating a **local** `v<semver>` tag, building local
artifacts, and producing checksums/provenance beneath
`artifacts/release/<version>/`. The release interpreter is local-only. It has no
push-tag, publish-package/image, or deploy operation. Existing versions/tags
are conflicts, never overwrites. Before each mutation (including tag and
artifact writes), read-only inspection checks current source identity, rules,
capabilities, required checks, checkpoint result, and target state. Unclear
completion is uncertain and requires read-only inspection, not automatic retry.

### 2.3 Checkpoint and approval artifacts

Checkpoint placement is static trusted Scala workflow configuration. A
checkpoint can gate a named transition but cannot grant an effect. Proposed
`NeedsApproval` output consists of two distinct files:

- **Request artifact:** schema/version, invocation and checkpoint identity,
  requested action, candidate digest, target/head identity where relevant,
  current policy/capability snapshot identity, expiry, and artifact integrity
  metadata. It contains no secret values.
- **Decision file:** explicit approve/deny decision bound to the request
  identity and digest, decision time, expiry/identity material required by the
  eventual authentication design. It is not a bearer capability and is not
  sufficient on its own to dispatch.

Absent, malformed, denied, expired, or stale approval results in typed
`NeedsApproval`/refusal (using ci-runtime's headless semantics, not stdin
blocking). The caller starts a **new invocation**, which reloads current trusted
Scala configuration, policy and granted capabilities; re-reads repository,
candidate and target state; validates request/decision bindings and expiry; and
revalidates evidence before any effect. No fiber, continuation, or old dispatch
state resumes. Approval cannot raise authority beyond the newly granted
capability set.

## 3. Composition with Raider execution and trust boundaries

The API should compose as bounded stages in existing `Program`/`Task`, not
introduce an independent job model. Today `Program[I,O]` is lazy: `apply(input)`
builds a `Task[O]`; construction, printing, or import does not dispatch
model/tool effects. Proposed stage descriptions should preserve this property.
Stage execution belongs to the existing invocation/Runner; stage boundaries
make outcomes/evidence explicit while sharing its ownership, deadline,
cancellation, resource and artifact context.

`AgentLoop` admission remains authoritative for model calls and existing
concurrency/budget policy. A forge/release effect must not create another
scheduler, admission queue, daemon, or hidden retry loop (ADR-029 H6). Effect
dispatch can be a bounded stage using the existing invocation's cancellation and
deadline; its adapter may serialize mutations for one integration owner but
cannot schedule independent Raider jobs. Mutation retries are explicit new
actions only after read-only reconcile establishes no effect and all current
preconditions/capabilities still hold.

ci-runtime §5–6 continues to apply: Raider tools policy is not a sandbox for
trusted Scala or repository code, headless mode never prompts/blocks, and
SIGINT/SIGTERM stops admission and cancels the owned subtree with bounded
cleanup. No broad CI permission change is proposed. Commit/PR/merge/release
effects are available only through explicit SDLC capabilities. CI orchestration
owns job DAG/retries/runner/secrets/manual gates/artifact upload; Raider owns
one workflow in one job. External orchestrator retry must not replay a run whose
record shows a mutation may have happened.

## 4. Proposed refusals, uncertainty, and reconciliation

This taxonomy is a review proposal; exact sealed types, fields and exit mapping
are unresolved. It should preserve the distinctions below:

| Condition | Proposed result | Required next action |
| --- | --- | --- |
| `stale-ref` / stale candidate or target | Typed stale refusal | Read-only reconcile; replan/rebase as a new candidate and invalidate/rerun affected checks/review. |
| `conflict` / observed state differs from requested preconditions | Typed conflict refusal | Read-only reconcile; no mutation using stale assumptions. |
| `duplicate` operation identity | Typed duplicate/refusal unless reconcile proves matching effect | Read-only reconcile; only report `AlreadyApplied` when resource identity and effect match. Correlation is not exactly-once. |
| `unknown-outcome` / timeout or failure after dispatch | `Uncertain`, never success | Read-only reconcile before any explicitly authorized new action; never blind retry. |
| denied/insufficient capability or policy mismatch | Typed denied refusal | No fallback or escalation; obtain a new invocation with owner-granted authority if appropriate. |
| missing/invalid/expired/stale checkpoint approval | `NeedsApproval` or typed approval refusal | Emit/bind request and decision artifacts; start a new invocation and recheck all state. |
| failed validation/check/review, existing release version/tag | Typed non-success | Repair/review or resolve conflict as a new bounded stage; never overwrite or waive required check by model assertion. |

The common result shape should include stable category, operation/correlation
identity where available, observed-vs-expected identity, safe diagnostic,
reconcile requirement, and evidence/artifact references. It must not serialize
credential values or suggest a mutation is safe merely because a timeout
elapsed. In all mutation paths, read-only reconciliation precedes every new
mutation, not just retries.

## 5. Evidence schema proposal (ADR-030 D4)

Typed Scala schemas under `contracts/` are required by ADR-030 D4; the following
conceptual records are a starting point, not frozen field lists. Each has a
versioned JSON projection into `run.json` and/or `events.jsonl`, with schema
identity and digest/reference preserved. Unknown/unobservable values are
explicitly unknown rather than invented. Evidence contains no secrets or raw
prompt/tool arguments by default.

| Evidence record | Minimum conceptual identity/content | Invalidation/relationship |
| --- | --- | --- |
| `check-result` | Check ID and trusted definition identity; exact argv identity; exit/outcome (pass/fail/error/skipped); toolchain and verifier identity; candidate digest; bounded-output/evidence digest; timestamps. | Bound to candidate, policy and verifier/toolchain snapshot. A changed candidate or check definition invalidates affected result. |
| `review-verdict` | Reviewer/verifier identity and version; structured verdict/findings references; task/plan/candidate digests; check-result references; policy snapshot. | Tied to exact candidate. Review cannot broaden capabilities or override required failed checks. |
| `candidate-identity` | Repository/worktree identity; base/head; source/tree digest; task/plan digest; candidate revision and lineage; relevant policy/config snapshot. | New candidate gets a new identity. Changes to task, rules, base/head, or candidate invalidate dependent evidence. |
| `integration-result` | Operation kind and ID; candidate and target identities; expected preconditions; reconciled observed state; typed outcome; mutation/reconcile evidence references; capability/rules snapshot. | Unknown/uncertain remains non-success and is preserved for read-only reconcile. No claim of exactly-once or remote durability. |

JSON canonicalization, digest algorithm/coverage, schema versioning/evolution,
event representation, retention/redaction, and precise links to current run
artifact envelope require review and implementation-specific specification.
This proposal does not modify existing artifact schemas.

## 6. H8 consumer migration plan

H8 requires identifying affected consumers, an explicit compatibility strategy,
and migration/example updates before freezing a public signature. This inventory
is a planning baseline; actual call sites and owners must be confirmed during
review before implementation.

| Consumer | Current seam / expected impact | Proposed compatibility and migration |
| --- | --- | --- |
| CLI launch paths (dev/REPL and generic non-TTY runner) | Existing Program/Task launch and CI artifact/exit contracts; no general SDLC mutation API. | Keep old launch behavior and BundleWorkflow v1 unchanged. Additive SDLC capability configuration at invocation boundary only after review; absent capability preserves existing behavior and cannot dispatch effects. Update CLI examples and failure/NeedsApproval artifacts before enabling. |
| Workflow runner / compiled workflows | Existing `Program` and `Task` execution in the same Runner; CI owns orchestration. | Preserve constructors and semantics. SDLC effects should be opt-in bounded stages, with same cancellation/deadline/ownership/artifact context. Version any new bundle/artifact representation separately; do not silently reinterpret v1. |
| Chat-export launcher (ADR-033 Option L) | Generated editable Scala launcher builds current input/provider/workspace/limits/policy at execution; export is authoring, not authority. | Generated source may gain an explicit SDLC capability/config binding only from trusted owner configuration. Never embed authority in exported code or inherit it from chat. Keep existing launcher compiling/running without the new capability; update generator, golden fixtures, docs and migration notes together if public APIs change. |
| Public DSL/host embedding consumers (`Program`/`Task`) | `Program` is the shared lazy execution contract; host may embed through ADR-029. | No change to existing `Program`/`Task` in this card. If effects require changed public signatures, provide a versioned additive route or reviewed major migration; update external-host examples and compile compatibility tests. |

Proposed sequence: (1) independent reviewers approve API boundaries, consumer
inventory, semantics, compatibility/versioning, evidence mapping and risks;
(2) coordinator resolves review comments and records accepted ADR/API version;
(3) implementation cards freeze exact symbols/schema only against that reviewed
baseline; (4) update all affected launchers, DSL/host examples, fixtures and
consumer documentation in the same migration; (5) provide a deprecation window
for any later superseded entry point, with compiler/deprecation warning and
documented removal version, unless review establishes an additive-only design;
(6) run compatibility tests for old Program/Task and BundleWorkflow v1 alongside
new API contract tests. There is no silent behavior change or automatic migration.

The precise compatibility mechanism (additive API in current artifact, package
version boundary, adapter duration, and deprecation/removal policy) is a review
decision. No call site should be migrated to a guessed signature before the
review gate. The launchers and examples are consumers, not trusted sources of
capability grants.

## 7. Mapping to ADR-030 and proposed review gates

| ADR-030 obligation | Proposed API contribution | Later verification target |
| --- | --- | --- |
| SDLC-01 | Bounded stage composition, candidate identity, typed evidence and invalidation. | Task→plan→implement→check→bounded repair→review; provenance and identity rechecks. |
| SDLC-02 | Typed refusal/uncertain outcomes, reconcile-before-mutation and local release boundary. | Failure injection for stale/denied/timeout/duplicate/conflict/tag/version/build failures; no blind retry/publication. |
| SDLC-03 | Invocation capability intersection, one mutation owner, current rules/state and new-invocation approval recheck. | Race/recheck, non-TTY approval and no capability expansion. |
| SDLC-04 | Vendor-neutral commit/PR-status/PR-create/merge intent/executor separation. | Full local Scala stand + disposable Git protocol including unknown outcomes. |
| SDLC-05 | Local SemVer/changelog/tag/artifact/checksum effect descriptions only. | Local release verification; demonstrate no push/publish/deploy. |

Review must affirm that the public surface, semantics, H8 migration and
versioning plan are coherent; that no proposed declaration bypasses owner
capability, reconciliation, bounded runtime ownership or headless behavior; and
that unresolved questions are either decided by reviewers/owner or remain
explicitly open. Until then, downstream signatures and schemas remain unfrozen.

## 8. Open questions for review (not decided here)

1. What public abstraction best separates effect descriptions and interpreters
   while remaining natural to Scala 3/ZIO and the existing lazy `Task`?
2. Should PR creation/update be one operation or distinct create/update intents?
   What identity is required for PR status reads and how are forge-neutral
   results represented?
3. What exact capability vocabulary and resource scoping are needed for each
   commit, PR, merge, tag and local artifact operation? How is owner-granted
   policy supplied to local/host-embedded invocation without workflow authority
   leakage?
4. What is the minimal complete precondition set for each mutation, and what
   state can the generic stand reliably reconcile? How should unsupported
   adapter guarantees be represented?
5. What stable error/outcome types and CLI exit mapping should be public, notably
   for duplicate-versus-already-applied and timeout-versus-unknown?
6. What is the authenticated decision-file format, identity binding, expiry
   policy and storage/retention policy? Which properties belong to CI's external
   approval system rather than Raider?
7. What exact typed fields, canonical JSON rules, digest algorithm, versioning
   policy and `run.json`/`events.jsonl` projection do D4 records require?
8. What additive/versioned compatibility strategy is viable for existing
   Program/Task, BundleWorkflow v1, CLI launchers and chat-export Option L?
   What deprecation period is appropriate if an incompatible route proves
   necessary?
9. How should configured checks/review and release inputs be referenced and
   pinned without allowing model-generated commands or repository content to
   define trusted verifiers?
10. Which operation stage is the natural cancellation/timeout boundary, and
    what local/forge states must be inspected after interruption before callers
    can take a new explicit action?

## 9. Review record and next step

This proposal is not independently reviewed or accepted at authoring time.
Record the independent public-contract review disposition against RAI-163
before any signature freeze. Reviewer findings may change the design and
consumer mapping; preserve unresolved risks rather than silently choosing an
answer. ADR-030 remains authoritative for accepted constraints. Once review is
complete, the coordinator may authorize the appropriate implementation and
contract cards against a reviewed, versioned baseline.
