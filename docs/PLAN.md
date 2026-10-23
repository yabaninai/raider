<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider: Detailed Development Plan

Current owner priority (2026-10-23): **useful, checked workflows beyond the
Scala ecosystem**, while preserving chat → editable Scala workflow → local/generic
CI. Read [roadmap §6e](roadmap-12k.md#6e-cross-ecosystem-workflows), the
[cross-ecosystem delivery plan](cross-ecosystem-plan.md), and the
[LLM4S/LangChain4j research](research/llm4s-langchain4j-2026-10-23.md).
RAI-196..206 are registered, not implemented. RAI-196 and RAI-204 are the first
new ready cards. Public format/protocol/capability changes require their own
reviewed ADRs; the Scala 3 + ZIO implementation policy remains unchanged.

At baseline `a351d3c`, RAI-155 and its child cards, RAI-160 and local distribution
RAI-193 are accepted. The live-wire 184, First CI 186/195/187 and TUI-X 188..192
work remains governed by the current board and dependencies. Do not restart
historical lineage/SDK prerequisite waves because an older section says "next".
A local release is not hosted publication, and offline engineering evidence is
not live-model quality or external demand.

Canonical execution uses the [roadmap](roadmap-12k.md),
[OpenCode prompt](prompts/opencode-12k-parallel.txt) and
[launch handoff](handoff-opencode.md). The project remains fully open source
under the existing MIT policy. First deliver local/generic-CI value; publishing,
paid calls and external outreach are separate actions. Gateway/Yabanin stays P11
following the standalone P10 candidate; overall W18 follows P11. Neighboring
TokenRouter/Yabanin changes still require that repository's own cards.

Historical planning below remains reference for obligations, not an inventory
of current source or a reason to repeat accepted work.

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
validates the planning package; Scala examples and runtime/PTY/CI gates have not yet
been implemented and are not reported as passed.

## Execution conditions

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
