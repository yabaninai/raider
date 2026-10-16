# ADR-030: Scala SDLC Workflow, Checkpoints, and Local Integration/Release Contract

Status: **Proposed** (contract draft for RAI-117; not accepted and not an
implementation authorization). Date: 2026-10-16. Depends on RAI-116 / ADR-029
and the accepted RAI-116 boundary; first consumer: TokenRouter code development.
The gateway remains P11 and is outside this decision. This ADR extends the
workflow boundary without implementing a runtime, forge adapter, or release
operation. It reconciles the older [CI runtime](../ci-runtime.md) boundary only
for explicitly configured SDLC capabilities described here. ADR-029 H1–H8
remain inherited unless a clause below narrows their application; no silent
public signature or schema-v1 change is authorized.

All APIs, verbs, schemas, cases, commands, and packets below are proposals, not
existing Raider behavior. This document is a reviewable contract, not evidence
that any SDLC behavior has shipped. Independent review and owner acceptance are
required before implementation cards can freeze signatures or capabilities.

## 1. Context and scope

The owner selected full AI-native SDLC authoring in Scala, with TokenRouter code
development as the first consumer. The workflow can plan, implement, run
checks, perform bounded repair and review, and conditionally integrate changes.
The owner also authorized automatic commit/PR/merge under configured policy,
Scala-configured checkpoints within granted capabilities, and a local release
consisting of version/changelog, a local tag, and local artifacts. There is no
selected forge or CI engine. A generic contract and Scala stand come first;
GitHub Actions is **not** the first adapter. The gateway remains P11.

This is a proposal for a new workflow-level contract after ADR-029's authoring
and launch boundary. Raider remains one Scala/ZIO runtime inside a CI job; the
external orchestrator owns CI job scheduling, retries, runner allocation,
secrets, job-level approvals, and artifact upload (ci-runtime §1). This ADR
defines neither a new scheduler nor a daemon. An SDLC workflow never gains
authority from a chat/export or repository text alone: effective capabilities
are provided by the current owner policy and can only be narrowed by the
workflow.

### 1.1 Decisions

1. Stages and their inputs, outputs, evidence, bounded repair/review, and
   candidate identity are explicit (§2).
2. Checkpoints are Scala-configured policy decisions, not implicit human
   prompts. A headless checkpoint without an affirmative capability returns
   `NeedsApproval`; it does not block on stdin or resume a suspended fiber.
3. Integration is expressed as generic commit / PR / merge intents with
   preconditions, ownership, and reconciliation; the initial executable
   contract is exercised against a local Scala stand and disposable Git repo,
   not a selected forge product (§4).
4. Release is local-only: version/changelog, a local tag, and local build
   artifacts. Never push a release tag, publish a package/image, or deploy
   (§5).
5. Commit/PR/merge may happen automatically only when the current configured
   repository rules and granted capabilities authorize the specific action.
   The authoritative source and precedence of those repository rules remain an
   owner question (§9).
6. Evidence is bound to the exact candidate revision and relevant inputs,
   policy, toolchain, and verifier identities. A changed candidate invalidates
   affected check/review evidence.
7. There is no durable resume. A new invocation reconstructs state from
   recorded evidence, reconciles external effects read-only, and rechecks
   current policy/state before any new mutation.

## 2. Stage contract

An implementation may expose these stages as ordinary Scala workflow
composition. This ADR does not prescribe a new YAML/DSL language, a new
scheduler, or frozen signatures. Each stage returns an immutable outcome and
evidence references; the orchestrator keeps the candidate workspace under one
integration owner. Inputs and outputs below are conceptual contract fields,
not wire schemas.

| Stage | Required inputs | Result/evidence | Bounded loop and invalidation |
| --- | --- | --- | --- |
| Task | Explicit task, repository identity, base/head identity, effective capabilities, policy snapshot | Accepted task statement, source identity, requested scope and risk | No inferred scope expansion; changed task or source identity requires a new plan. |
| Plan | Task, repository rules snapshot, current base/head, available capabilities | Plan and ordered intended file/check/integration actions, digest and provenance | Plan review/repair is bounded by configured limit; changed task/rules/base invalidates plan approval. |
| Implement | Accepted plan, exact candidate workspace/revision, allowed tools and limits | Candidate tree/revision, diff, mutation/tool evidence, source digest | Bounded agent rounds/repair budget. Candidate writes have one owner; no concurrent writer may silently replace it. |
| Checks | Candidate identity, named trusted check definitions, toolchain/verifier identities | Per-check actual argv identity, outcome, exit, bounded output/evidence digest | Failed checks remain failed until a bounded repair produces a new candidate and reruns affected checks. No model assertion substitutes for a check. |
| Repair | Failed check evidence, same candidate lineage, current capability | New candidate identity and repair provenance | Bounded by configured repair count/time/budget; each candidate change invalidates affected checks and review. |
| Review | Candidate/diff identity, task, plan, repository rules and prior check evidence | Structured findings and review verdict tied to candidate | Bounded number of review/repair iterations. Review is not authority to widen capabilities or override failed required checks. |
| Integration | Final candidate, passing required checks, acceptable review, current target/head, integration capability | Commit/PR/merge result or typed non-success, operation identity and reconciliation evidence | Exactly one integration owner. Recheck target/head before mutation. Stale target/ref requires rebase/replan and affected checks on the changed candidate. |
| Local release | Accepted version/changelog inputs, final source identity, required checks, local release capability | Local version/changelog, local tag, local artifacts and hashes | No remote/publishing/deployment action. Existing version/tag is a refusal, not overwrite. Rebuild/recheck if release inputs or source change. |

### 2.1 Evidence identity

Every stage record must identify, as applicable: repository and worktree;
base/head and candidate revision/tree digest; task/plan digest; named check
definitions and toolchain; verifier version/hash; effective policy/capability
snapshot; stage implementation version; and evidence/artifact digests. A value
that cannot be observed is recorded as unknown, not fabricated. Evidence from a
prior candidate or policy snapshot is not transferred to a new candidate.
Exact evidence schemas and canonical digest rules remain unresolved (§9).

## 3. Configurable checkpoints and approval

Checkpoint placement and behavior are declared in trusted Scala workflow
configuration and validated before dispatch. A checkpoint can gate a named
transition (for example, before integration or local release); it cannot
authorize an action outside the invocation's owner-granted capability set.
Repository input, task text, generated code, and model output cannot create or
broaden checkpoints or capabilities at runtime. The selected policy is
recorded with the workflow/candidate identity.

At an interactive checkpoint, the integration owner supplies an explicit
decision bound to the exact stage, candidate, target/head, requested action,
and current policy. Stale approval is rejected. In headless mode, absent or
insufficient approval yields a typed `NeedsApproval` outcome consistent with
ci-runtime §6/§8 (proposed exit 25 under the existing table); the process does
not ask, wait on stdin, or auto-approve. After approval, the caller starts a
**new invocation**, which rechecks current policy, repository state, target,
candidate, and all relevant evidence. No fiber or workflow stack is resumed,
and prior approval is not a reusable bearer capability.

The approval artifact's schema, storage, signature/authenticity mechanism,
retention, and exact identity fields are unresolved owner decisions. This ADR
does not invent them.

## 4. Generic integration contract

The initial contract is independent of any forge vendor. It has generic
operations equivalent to `commit(candidate, message, preconditions)`,
`openOrUpdatePullRequest(candidate, target, description, preconditions)`, and
`merge(request, target, preconditions)`. Names and signatures are illustrative
and not frozen. A local Scala forge stand plus a disposable Git repository
exercise the contract. Which forge semantics the generic stand models versus
leaving adapter-specific is unresolved; the stand must report unsupported
semantics rather than claim universal parity.

### 4.1 Preconditions and ownership

- One integration owner serializes mutations for a repository/target. A second
  owner must not act on a stale in-memory view.
- Before each mutation, read and compare the latest repository identity,
  target/ref, head/base, candidate digest, required check/review evidence, and
  current policy/capabilities. The expected values are explicit preconditions.
- A changed base/head or stale target is a typed stale/conflict outcome. Do not
  apply the old approval or evidence to a new candidate. Replan/rebase as a new
  candidate, then rerun affected checks and review.
- A duplicate request is reconciled by read-only inspection using a stable
  operation correlation key where the adapter supports it. The key is not an
  exactly-once guarantee. Return the already-observed matching result only
  when identity and effect match; otherwise report conflict/unknown.
- A failure or timeout after a mutating request may mean the mutation
  succeeded. Record `Uncertain`; first perform read-only reconcile against the
  remote/local state. Never automatically retry an ambiguous mutation.
- Retrying is permitted only after reconciliation establishes that the effect
  did not occur, current policy still grants it, and all preconditions still
  hold. Retry is a new explicit action, not replay/resume.

### 4.2 Outcomes

The adapter contract distinguishes `Applied`, `AlreadyApplied` (verified by
reconciliation), `Rejected`, `Stale`, `Conflict`, `Denied`, `Failed`,
`TimedOutUncertain`, and `Unknown`. These are outcome categories only; the
public error family/schema remains to be designed and reviewed. Unknown and
uncertain are never success and never trigger blind retries.

## 5. Local release contract

Release produces local, auditable artifacts only. The intended operations are
to determine a version under the configured version policy, update a changelog,
create a local (not pushed) tag, and build local artifacts with checksums and
provenance. The precise versioning, changelog, tag, artifact names/paths,
formats, reproducibility and schema conventions are unresolved owner
questions. No example here selects a convention.

Preconditions include current source/candidate identity, required checks,
release capability and checkpoint result, a version not already present, and
an absent local tag. Existing version/tag is a typed conflict/refusal; do not
overwrite. A local build failure is a failed release, with no success marker.
If a timeout occurs after local tag creation or artifact write and completion
is unclear, report uncertainty and inspect local state read-only before any
explicit next action. Never push the release tag, publish a package/image,
deploy, or claim those operations occurred.

## 6. Trust boundaries, capabilities, and compatibility

- **Trusted Scala, not a sandbox.** Workflows and plugins execute with the
  Raider process's permissions. Tool policy does not sandbox arbitrary Scala
  (ADR-029 H5; ci-runtime §5). Repository code/tests execute with the process
  permissions; untrusted execution requires a separately established isolated,
  credential-free worker and must not be implied by this contract.
- **Credentials.** Supply credentials only through environment/helper
  references and owner-managed CI secret facilities. Never put secret values in
  source, argv, evidence, artifacts, logs, snapshots, or reports. CI
  orchestrators remain the owners of job secrets and artifact upload.
- **Capabilities.** Capabilities are explicit, invocation-scoped, owner-granted
  and immutable for the run. Workflow configuration may narrow them only.
  Integration/release capability does not imply network publication. No
  hidden provider fallback or escalation is allowed (ADR-029 H7).
- **Mutations and interruption.** No automatic repeat of an ambiguous external
  or local mutation. Incomplete/uncertain outcomes are retained for read-only
  reconciliation. There is no durable resume/exactly-once claim (ADR-029 H1).
- **Orchestration.** The CI engine owns job DAG/retries/runner/secrets/manual
  gates. Raider owns one workflow inside one job; no second scheduler/daemon
  (ci-runtime §1, ADR-029 H6).
- **Compatibility.** This contract does not change the frozen `Task`/`Program`
  API, BundleWorkflow schema v1, or wire/event schemas. Any public signature or
  schema change requires separate reviewed migration work and consumer/example
  updates (ADR-029 H8). No implementation signature is frozen here.
- **Relationship to other ADRs.** ADR-029 establishes workflow authoring and
  launch, not SDLC effects. ADR-031's lineage projection remains a
  zero-dispatch dry-run description, never execution, integration or release.
  ADR-033 chat export remains a separate authoring artifact and explicitly
  leaves SDLC effects to this ADR; export authority does not carry into a run.
  Older ci-runtime §5's baseline prohibition on commit/PR/merge is revisited
  only within the explicit RAI-117 capabilities and safeguards above; the
  contract does not generally authorize those effects outside this workflow.

## 7. Acceptance matrix and traceability (SDLCADR-01..03)

The following are future contract-verifier cases, not executed tests. Local
stand and disposable-Git cases are mandatory first. Real forge acceptance waits
for a later adapter-selection decision and separate evidence.

| ID | Contract obligation | Local stand / disposable-Git case | Stage target | Implementation / verifier packet |
| --- | --- | --- | --- | --- |
| SDLC-01 | Stage inputs/results, bounded loops, checkpoints, evidence identity, integration policy are explicit and verifiable; record commands/source hashes and TokenRouter rechecks | Full task→plan→implement→check→bounded repair→review fixture; inspect evidence identity; stale task/policy/candidate invalidation; exact local check argv and source/tree hashes captured; TokenRouter source identity rechecked before a new candidate is accepted | M3: stages/checkpoints and generic integration on stand/repo | Proposed packet A: Scala SDLC stage/config/evidence implementation; packet V: independent contract cases and source/hash/TokenRouter recheck verifier. Coordinator must register exact card IDs, owners, dependencies, allowed paths and estimates before acceptance. |
| SDLC-02 | Stale candidate/ref/approval, failed checks, denied/expired capability, timeout after PR/merge/tag, existing tag/version, and build failure are honest; never blind retry or prohibited remote release | Inject each condition at local stand/Git boundary; assert typed non-success/uncertainty, then read-only reconcile before any explicit retry; assert tag remains local and publication/deployment endpoints are absent | M3: adversarial generic stand/repo cases; local release cases before M4 | Proposed packet A: integration/release contract implementation; packet V: deterministic failure-injection verifier. Coordinator registers exact IDs/owners/dependencies/paths/estimates. |
| SDLC-03 | One integration owner; target/head recheck; changed candidate reruns affected checks; no capability expansion; orchestrator retains CI job/approval/secrets; no new runtime/daemon | Concurrent competing owner attempts serialize or conflict; advance target between preflight and mutation; assert stale; update candidate and prove affected checks rerun; denied capability cannot be broadened; non-TTY approval exits NeedsApproval and new invocation rechecks; orchestrator boundary documented in stand | M3: ownership/concurrency/policy cases | Proposed packet A: ownership/precondition/approval integration; packet V: race, capability non-expansion and non-TTY acceptance. Coordinator registers exact IDs/owners/dependencies/paths/estimates. |
| SDLC-04 | Generic integration semantics are usable without selecting a forge; adapter-specific differences are visible | Local Scala stand contract suite plus disposable Git commit/branch/tag; explicit unsupported behavior; stale/conflict/duplicate/unknown timeout and read-only reconcile | M3: generic stand and disposable Git | Proposed packet A: stand + generic adapter interface; packet V: independent conformance suite. Real adapter excluded. Coordinator registers IDs/owners/dependencies/paths/estimates. |
| SDLC-05 | Local release is version/changelog/local tag/local artifacts only; hashes/provenance and failures are honest | Disposable repository release fixture; validate local tag and artifacts; existing version/tag and build failure; scan evidence for no push/publish/deploy operation | M4: local release acceptance | Proposed packet A: local release implementation; packet V: independent release verifier. Coordinator registers IDs/owners/dependencies/paths/estimates. |

**Milestone rule:** M3 requires SDLC-01–04 against the local stand and
disposable Git; M4 additionally requires SDLC-05. No case is passed by this
contract draft. RAI-117 acceptance also requires coordinator registration of
exact implementation/verifier card IDs with owners, allowed paths,
dependencies, per-card estimates, and the approved estimate source before
independent review and owner acceptance.

### 7.1 Target commands and source identity procedure

The first consumer target is the separate TokenRouter repository. No TokenRouter
checkout, source snapshot, approved command list, or hashes are supplied by
this Raider lane, so this ADR does not invent them or claim that target commands
were run. The implementation/verifier packet must capture the actual target
repository URL/identity, base/head commit, clean/dirty status, tree digest,
toolchain, and owner-approved exact argv (including the test command) at the
start of its disposable copy. It records those values in its evidence manifest,
then re-reads and compares target identity/base/head and source digest before
accepting a candidate or integration operation. If the target changes, work
becomes stale: rebase/replan, regenerate candidate identity, rerun affected
checks/review, and re-evaluate current policy. Never treat a historical
working-source hash as the latest target.

Proposed check families (exact commands **unresolved**): source inventory and
hash; compile/build; targeted and full relevant tests; formatter/static checks;
independent diff/scope review. Command definitions must be trusted,
owner-approved argv and verifier identities, not generated from model output.

## 8. Implementation/verifier planning estimate (not approval)

The historical 13,100-hour roadmap envelope is context only, not an approved
budget or a cost allocation. The following separate RAI-117 estimate is a
planning range for newly added kit/SCM/local-release work only, before
implementation and independent review; it must be reconciled with existing
roadmap allocations by the coordinator. No source of hours/budget has been
approved; owner confirmation is required before work is scheduled.

| Packet | Scope | Estimate | Dependency / owner role |
| --- | --- | --- | --- |
| A1 | Scala stage composition/config validation, candidate/evidence identity and bounded repair/review contract implementation | 32–48 h | Accepted ADR-029 and this ADR; runtime/DSL owner to be assigned |
| A2 | Checkpoint decisions, headless NeedsApproval, new-invocation state/policy recheck | 16–24 h | A1 and current policy/capability seam; runtime owner |
| A3 | Generic integration contract/one-owner preconditions, local Scala stand and disposable Git bridge | 32–48 h | A1/A2; integration owner; no real forge adapter |
| A4 | Local version/changelog/tag/artifact release path | 16–24 h | A1–A3 and owner-resolved release conventions |
| V1 | Independent local stand/disposable-Git verifier for SDLC-01–04, including negative/race/reconcile cases | 24–40 h | A1–A3 contract and stand; independent verifier owner |
| V2 | Independent local release verifier SDLC-05 | 8–16 h | A4; independent verifier owner |
| **Total** | **Added scope estimate, before deduplication** | **128–200 h** | Coordinator to reconcile overlaps, existing allocations, and source of hours |

These are estimates, not approved hours, commitments, or implementation
acceptance. Exact child card IDs, owners, allowed paths, dependencies, argv,
acceptance cases, and per-card estimates are coordinator-owned registry work
before ADR acceptance. Do not substitute the historical 13,100h envelope.

## 9. Unresolved owner questions (must not be decided silently)

1. What is the versioned schema and authority mechanism for headless approval
   artifacts, and what exact fields bind approval to candidate, target, action,
   policy, expiry and owner?
2. Which repository rules are authoritative for automatic commit/PR/merge, and
   what are their trust roots, precedence, pinning, and owner/maintainer
   override semantics?
3. Which exact forge behaviors does the generic Scala stand model, and which
   must be explicitly adapter-specific or unsupported?
4. What are the stage evidence schemas, canonical digest inputs, retention and
   verifier-version rules?
5. What version, changelog, local tag, build artifact naming/path/format and
   provenance conventions does local release use?
6. What is the approved hour/budget source for implementation, verifier,
   coordinator integration and review? The 13,100h roadmap envelope is not an
   approval.
7. Will implementation require public signature or wire/schema changes? If
   yes, which separate ADR and consumer/example migration cards are required?

## 10. Alternatives and consequences

| Alternative | Disposition | Reason |
| --- | --- | --- |
| Select GitHub Actions/forge first | Deferred/rejected for first adapter | No platform is selected; a generic Scala stand and disposable Git isolate the contract first. GitHub Actions is not the first adapter. |
| Permit arbitrary git/shell commands from workflow/model | Rejected | Mutations require typed capabilities, known preconditions, one owner, and reconciliation; generated text is not trusted command configuration. |
| Retry a timed-out PR/merge/tag mutation automatically | Rejected | Outcome may be ambiguous; inspect read-only state first, preserve uncertainty, then require a new authorized action. |
| Treat approval as durable workflow pause/resume | Rejected | Headless returns NeedsApproval; new invocation rechecks state/policy. No durable fiber resume. |
| Push tags/publish packages or deploy during local release | Rejected | Explicitly outside the owner-authorized release boundary. |
| Leave old ci-runtime §5 prohibition absolute | Rejected only for this scoped contract | Would contradict owner-authorized automatic commit/PR/merge. The narrow exception here does not grant general CI mutation or change orchestrator ownership. |
| Add a new scheduler/daemon | Rejected | Existing Raider runtime and external CI orchestrator retain their ownership boundaries. |

**Consequences:** Scala-authored SDLC intent has an auditable path to
integration/release without binding the product to a forge. This creates
substantial implementation/verifier work and unresolved contracts around
approval, repository rule authority, stand fidelity, evidence, and release
naming. A local stand pass does not establish a real forge adapter's behavior.
The current ADR does not change API signatures, runtime, CI engine, or product
behavior. Rollback of a future implementation must preserve mutation
uncertainty and must never replay an effect.

## 11. Acceptance status and required next step

RAI-117 normal acceptance SDLCADR-01 is addressed as a proposed stage/packet
contract in §§2, 7, and 7.1; coordinator registration and target source
evidence remain pending. SDLCADR-02 is addressed by the explicit adversarial
outcome/reconciliation and no-publication clauses in §§4–5 and the SDLC-02/05
cases. SDLCADR-03 is addressed by one-owner/recheck/capability/orchestrator
rules in §§3–4, 6, and the SDLC-03 case. These are contract provisions, not
executed acceptance tests.

Next: coordinator records exact SDLC-01..05 implementation and verifier card
IDs, owners, allowed paths, dependencies, approved estimates/source, and
target-specific commands; independent reviewer evaluates this ADR; owner
resolves or explicitly carries the questions in §9; then owner accepts the
contract. Only then may implementation cards be scheduled. Real forge adapter
selection and its acceptance remain a later decision.
