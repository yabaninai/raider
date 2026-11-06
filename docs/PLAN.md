<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider: Detailed Development Plan

Current execution correction (2026-11-01): read the
[readiness audit](reviews/2026-11-01-readiness.md) and the updated
[OpenCode handoff](handoff-opencode.md) before selecting another card.
The product already has a native runtime, two provider transports, a real
source-checkout Scala REPL, headless CI paths, and portable/process workflows.
It is a developer preview with substantial component evidence; complete
W01–W17 engineering acceptance, live coding verification, and OpenCode parity
are not established.

At audit start local `main` was `04f7478` (73 commits beyond remote `ed1ef4f`
of October 28). The active OpenCode session then merged RAI-254 as `e41cb28`
(76 commits beyond that remote). Recheck refs and active work before acting.
RAI-196/204 and the First CI/TUI implementation cards are already accepted;
do not restart them from the old October 23 handoff. RAI-246..254 are merged
at the later snapshot but their backlog statuses still require reconciliation
with actual reviews and integrated evidence. A merge alone does not accept a card.

The owner priority remains **useful, checked workflows beyond Scala** and
chat → editable Scala workflow → local/generic CI. The next sequence is:

1. Finish the active wave-G integration/evidence/status handoff without
   duplicating its workers. Preserve its current candidate and reviewed pins.
2. Register separate, independently reviewed gate-policy cards for verifier
   completeness and source inventory isolation. The audit reproduced an
   empty-gate manifest accepted as `fast`; `wt/` source also contaminates
   fingerprints. Keep existing real gate results, but do not infer full
   acceptance from a manifest label.
3. Register focused product cards for configured model propagation into
   delegated children, aggregate native-coder limits, protected-path and
   project-instruction policy wiring, and structured multi-file/delete tools.
   Reproduce failures with local Scala provider stands before fixing them.
4. Close the installed user path: helpful CLI entrypoint, a separate packaged
   REPL distribution/launcher, and compiler-checked W11 golden scenarios.
   Keep the headless CLI free of the REPL compiler. Do not silently remove
   required acceptance to match current implementation.
5. Create one explicit W01–W17 acceptance matrix over one frozen candidate,
   then run the required real-process/PTY/clean-install/task cases and retain
   logs. Close remaining CI/release/security obligations through scoped child
   cards of the existing acceptance owners; do not restart implemented parents.
6. Keep bounded live coding for `direct-chat`, `direct-anthropic`, and
   `gateway`, plus the comparative OpenCode corpus, as separate results.
   The historical gateway wire smoke does not verify a real coding task.
   Publication, remote pushes, and additional live spending retain their
   existing authorization boundaries.

## Throughput experiment for the next two integration batches

Owner follow-up, 2026-11-01: accelerate delivery of working scenarios. This is
an execution improvement within the existing parallel runbook, not permission
to reduce checks or to add more scope. See
[THROUGHPUT-20261101](tasks/development-throughput-20261101.md).

The bottlenecks are evidenced locally: RAI-252 records host load from 5 to 198,
provisioning failures and interrupted full gates; the
[Zinc study](research/zinc-concurrency-2026-10-28.md) reproduced bad incremental
compiler state during overlapping cold compiles, including at low concurrency.
The audit found unrelated worktrees in fingerprints and merged cards still
marked ready. More simultaneous builders cannot be assumed to increase delivery.

1. **Use one concrete delivery target.** The next target is an installed user
   path that can show help, open the real Scala REPL, and run a native coding
   task against a local Scala provider stand: read files, edit, observe a
   failing check, correct the result, and persist a passing check. Include a
   delegated child using the configured model plus cancellation/limit cases.
   Maintain a short acceptance checklist and demonstrate the actual artifacts
   after each integrated batch. This is an incremental target, not a reduction
   of the final W01–W17 requirements or evidence of live-model decision quality.
2. **Keep parallel work, queue scarce build capacity.** Keep at least eight
   visible ready lanes when dependencies and disjoint paths permit, with
   reviewers and fixture/document work among them. As an initial measured
   trial below the runbook's 8–10-heavy-lane maximum, admit only 2–4 heavy JVM
   jobs in total, including at most one integrated full-suite run. Reserve a
   slot for that run. Serialize cold compilations across the coordinator's
   lanes until the known failure is resolved; a 2–4 limit alone is not a cure.
   Retain private incremental targets and verified dependency caches; never
   share mutable targets. Account for other active sessions before dispatch,
   and do not stop another owner's processes. Adjust after measurement, not
   by raising timeouts or inventing idle cards.
3. **Pipeline review and integration.** Review a ready lane immediately while
   other lanes continue. Merge compatible reviewed changes in small coherent
   batches; keep no more than one completed batch waiting for integration.
   Freeze the candidate's product inputs for its gate run; avoid concurrent
   edits to its code, specs, prompts and build. Other work remains in isolated
   lanes. First repair the fingerprint bug under its own card so unrelated
   worktrees cannot invalidate the candidate. Refresh board/backlog/index in
   the same handoff so accepted work is not redispatched.
4. **Reduce repeated work lawfully.** During implementation use the narrow
   relevant tests for feedback, then run every required completion check.
   Plan the union of a batch's required profiles before starting its gates.
   The current mandatory `quality-changed` classification, EXECUTE and card
   profiles remain required even if their commands overlap. Register a
   separately reviewed runner card to execute duplicate gate IDs once for an
   identical source/policy/toolchain and retain per-profile coverage receipts;
   no cross-fingerprint reuse, skipped assertions, changed timeout threshold,
   or lane-only substitute for integrated acceptance is allowed. Until it is
   implemented and reviewed, do not manually skip repeated required gates.
5. **Bound exploratory work.** Assign research one falsifiable question and
   an agreed timebox before dispatch; a useful starting timebox is 45 minutes.
   At expiry record evidence, a narrow follow-up and its priority. Defer only
   research that does not block required acceptance and has a documented safe
   workaround; failed mandatory tests and reproduced product blockers stay
   blockers. Keep Java-facade/adjacent feature work demand-gated. Reuse reviewed
   reproduction evidence when its exact inputs match, without relabelling it
   as a new run.
6. **Measure throughput in the existing work records.** For each of the next
   two batches record time to an accepted integrated scenario, review wait,
   build/gate time, retries and their causes, cold-compile overlap, and host
   contention. Record actual scenario completions, not commit or agent counts.
   Compare with recent recorded runs with toolchain/host/workload differences
   disclosed. Select the build concurrency and batch size from those results;
   no numeric speedup is promised in advance. This requires no new daemon,
   scheduler, telemetry service or product abstraction.

The coordinator can run the independent evidence-tooling, child-model,
packaging/help, DSL lifecycle and acceptance-fixture work in parallel after
registering narrow cards and freezing the required interfaces. Native policy,
limits and mutation-tool changes must wait where their shared contracts or
files overlap. One owner per shared file remains mandatory.

Concrete scopes, dependencies, acceptance and evidence gaps are in the audit's
follow-up table. The existing OpenCode coordinator owns product integration,
board and numbered card registration. This documentation audit assigns no
competing product workers and changes no card acceptance or gate policy.

The [cross-ecosystem plan](cross-ecosystem-plan.md),
[roadmap §6e](roadmap-12k.md#6e-cross-ecosystem-workflows), and
[canonical prompt](prompts/opencode-12k-parallel.txt) remain architectural
context. Apply the dated correction above before historical “start/next” lists.
Yabanin remains optional for standalone use; companion repository changes
require that repository's own cards. The project remains MIT open source.

Historical planning below records obligations, not current implementation
status or instructions to repeat accepted work.

Original plan: 2026-10-02; execution revision: 2026-10-03. A feasibility spike exists;
the product runtime had not yet been implemented at that time. Check actual status in code and the board.

Final scope: CI-primary; a useful real REPL; both direct API protocols; optional Yabanin;
current cards and dependencies are in the [backlog](tasks/backlog.json). Current entrypoint:
`opencode` → `/raider-finish` (`/raider-build` alias), [instructions and prompt](handoff-opencode.md).

## Clarification after the 2026-10-03 audit

[Working product W01–W18](working-product.md) defines the finish target; the
[parallel runbook](parallel-development.md) defines permitted parallel lanes; the
[review](reviews/2026-10-03-readiness.md) records what has been done; and
[stage-aware gates](quality-gates.md) resolve premature acceptance cycles. These
documents refine the original sequence and execution rules below. Original card
numbers are retained; M0 is an interim result, and sessions/context/SPI/package are
required for a useful coding agent.

## Goal

Create a universal, extensible Scala harness for running agent scenarios inside CI
jobs in GitHub/GitLab/other pipelines. A scenario must accept versioned inputs, run
synchronous/asynchronous subagents, observe policy/budgets/deadlines, and return a
verifiable result, exit code, and artifacts. A full REPL is needed to create and debug
the same scenario locally, without a separate execution model.

Key properties:

1. A portable non-TTY CI runner with consistent semantics across orchestrators.
2. Real Scala in the REPL: `val`, `def`, `case class`, imports, multiline input, and types.
3. One-line foreground/background launch with explicit effects.
4. Managed children: owner, deadline, limits, messages, cancellation, results.
5. Extend tools/profiles/backends through separate modules.
6. Ability to use pi/OpenCode profiles and run their engines through adapters.
7. First-class OpenAI-compatible/Anthropic-compatible APIs and optional Yabanin gateway/accounting.
8. Quality comes from reproducible checks and small cards, not model confidence.

## How to read this package

| Document | Purpose |
| --- | --- |
| [CI runtime](ci-runtime.md) | Main product contract: invocation, trust, exits, artifacts, orchestrator adapters |
| [Provider protocols](provider-protocols.md) | Direct OpenAI/Anthropic wires, optional gateway, capabilities/usage |
| [DSL experience](dsl-experience.md) | Quick user path and consistent verbs |
| [Product and REPL DSL](product-repl.md) | Full contract, exact semantics, commands |
| [Runtime and contracts](runtime-contracts.md) | Modules, states, ownership, concurrency, tools, plugins |
| [Yabanin integration](yabanin-integration.md) | Verified integration points, limits, staged rollout |
| [Stages and dependencies](development-plan.md) | Sequence, milestone acceptance, scope |
| [Implementation cards](tasks/README.md) | Small tasks for models |
| [Quality gates](quality-gates.md) | Checks, evidence, risk matrix, release policy |
| [Working with smaller models](model-workflow.md) | Context, handoff, independent review, escalation |
| [OpenCode launch](handoff-opencode.md) | Project command, prompt, and resume procedure |
| [z.ai assignment](handoff-zai.md) | Ready-to-use starting prompt and execution rules |
| [Sources and ADRs](sources-decisions.md) | Verified sources, accepted/deferred decisions |
| [Yabanin snapshot](research/yabanin-snapshot.json) | Commit, hashes, and dirty markers of inspected local files |

## Validate the package

```sh
python3 scripts/plan/validate.py --out artifacts/plan-review/report.json
```

The implemented validator checks links/JSON/fences/card metadata/dependency DAG and
that the OpenCode command matches the canonical prompt. Optional
`--yabanin-repo /path/to/checkout` compares source snapshot hashes read-only. This
validates the planning package only. Compiler, runtime, process and PTY checks
now exist in the repository; their current coverage and evidence must be checked
separately. Planning validation does not establish product readiness.

## Historical execution conditions (current order is above)

- All DSL examples and commands in the package are target contracts. Cards and compiler/PTY gates must prove they work.
- Start with RAI-001: check the toolchain, headless execution, and REPL embedding. It may refine the technical implementation, but must not reduce the REPL to a parser for a few commands.
- Bootstrap: RAI-001 → RAI-003 build → RAI-002 compile/schema contract → RAI-004 gates; then a vertical slice.
- The SDK/SDLC revision includes automatic commit/PR/merge under the RAI-117 contract and selected policy; accept a generic local stand first, then choose a real forge adapter later. Release means local tag and artifacts, without pushing a release tag, publishing a package/image, or deploying. The old CI/runtime boundary changes through a separate ADR; current planning does not perform these operations in TokenRouter.
- A native minimal agent loop is required for MVP. Pi/OpenCode bridges come in the next stage and must not be the only way to run Raider.
- Runtime language is Scala. Python is allowed for a small portable quality runner/metadata checks; it does not become the agent runtime.
- Language policy (owner, 2026-10-03): all further development uses Scala 3 + ZIO. `modules/**`, `contracts/**`, `examples/**`, product tests, and test stands use Scala only. Python is limited to `scripts/plan/validate.py`, `scripts/quality/**` (RAI-004 runner), glue in `scripts/bootstrap.sh`, and frozen historical `experiments/**`; Python is not shipped and must not grow. Provider test HTTP stands are written in Scala (JDK built-in HttpServer) within modules; a locally launched OpenAI-compatible stand is allowed for tests when explicitly configured; paid remote endpoints require an owner cap.
- Verify the real Yabanin connection on a separate stand. Reading its source does not prove deployment readiness.

## First CI acceptance

```text
raider run --bundle pipelines.jar --workflow inspect --ci generic \\
  --context ci-context.json --input "inspect the project" --mock --out artifacts/raider
```

The same compiled workflow runs without a TTY, completes owned children, saves
run/result/events/JUnit/summary, and returns a code according to policy. Repeat with
GitHub/GitLab environment fixtures, timeout, SIGTERM, and malformed input. The compiler
REPL is not needed on the runner classpath.

## Local REPL acceptance

```text
$ raider repl --mock
raider> scout.ask("Find the README")
raider> val a = scout.start("Find the interfaces")
raider> val b = scout.start("Find the tests")
raider> :jobs
raider> a.await
raider> b.cancel()
raider> (scout andThen reviewer).ask("Assess the project")
raider> :load workflows.raider.scala
raider> :run inspect "inspect the project"
raider> :exit
```

The result has no orphan jobs/processes; errors display correctly; compiler state is
preserved after invalid input; actual network/tool effects happen only after launch.
This transcript should become an executable acceptance fixture.
