<!-- Translated from the Russian original. Key terms are preserved as-is. -->

# Working Raider: Goal and Required Acceptance

Revision of 2026-10-03, at the owner's request: make Raider a useful standalone coding agent with a good Scala DSL, developed in parallel. This clarifies the [original plan](PLAN.md); it does not claim that these capabilities are implemented. The [current assessment](reviews/2026-10-03-readiness.md) records facts and defects.

## 1. What must be delivered

An installable CLI/REPL that uses a native loop to read an existing project, find code, create/change/delete files, run checks, use check results in the next step, and return a verifiable result. It works through direct OpenAI-compatible and Anthropic-compatible APIs; Yabanin is optional. Review, patch+checks, and research/generation workflows run from the REPL and inside a CI job using the same runtime.

OpenCode is the reference for the primary coding workflow: tools, project instructions, permissions, history/context, subagents, CLI, and extensions. Model decision quality is demonstrated by a comparative run, not by the number of Scala classes or successful mock tests. No claim is made that Raider matches the entire upstream UI/IDE/web/cloud ecosystem. The competitor's main surfaces are [tools](https://opencode.ai/docs/tools/), [rules](https://opencode.ai/docs/rules/), [CLI](https://opencode.ai/docs/cli/), and [plugins](https://opencode.ai/docs/plugins/).

The final report contains three independent facts:

- `engineering_ready`: the built product passes all offline/contract/real-process/PTY checks below.
- `live_verified`: a scoped summary plus per-profile statuses `direct-chat`, `direct-anthropic`, and `gateway`; each records actual/pending/unavailable, endpoint identity without credentials, model/build hash, and evidence. One OpenAI smoke test does not verify Anthropic or the gateway. A verified profile completes a coding scenario with a real model, actual changes, and checks.
- `opencode_comparison`: Raider and OpenCode complete the same task set, with versions, model, budgets, attempts, and an independent verdict recorded. Until then, do not claim “at least as good as OpenCode.”

Missing endpoint/account/budget leaves live items pending and does not prevent engineering completion. Native tools/runtime cannot be replaced by an external OpenCode engine and called parity of Raider itself.

## 2. Definition of working

| ID | Required result | Acceptance and evidence | Main cards |
| --- | --- | --- | --- |
| W01 | Reproducible installation | JDK/toolchain pinned; clean-directory build/install; `raider --help`, REPL, and headless mode start | 003,022,044 |
| W02 | Native coding loop | From a fixture repo, the model loop reads, changes, runs a test, observes failure, and fixes it; actual diff and hidden assertions | 010,014–016,024,026,041 |
| W03 | Useful file tools | Numbered/ranged reads, glob/discovery, documented ignore rules, bounded search, continuation after truncation; create/edit/delete, multi-file patch, and explicit partial-failure contract | 014,015 |
| W04 | Real processes | argv/cwd/env/output bounds, exit status, deadline; child+grandchild cleanup; uncertainty is not hidden | 016,022 |
| W05 | Both direct wires | OpenAI Chat and Anthropic Messages: full tool round, streaming/non-streaming, fragmented Unicode/JSON, errors/usage/cancel, without Yabanin | 011–013 |
| W06 | Policy | read-only/write/exec boundaries, child intersection, no-TTY NeedsApproval, secrets/protected verifier files inaccessible to tools | 005,008,014–016,023 |
| W07 | Project context | Workspace/profile/instructions precedence, file provenance, relevant context, large-output limits, and no silent policy expansion | 005,029,030 |
| W08 | Sessions | Serialized turns; goal and tool IDs survive multiple turns; journal/archive/reopen; bounded compaction that does not replay mutations | 027,030,036 |
| W09 | Native subagents | DSL fork and model delegate, one owner/root budget, await/send/cancel/lineage, maxLLM=1 without deadlock | 007–010,017,021 |
| W10 | Real Scala REPL | val/def/case class/multiline/completion, bindings preserved, prompt after error; streaming does not corrupt typed input | 018–020 |
| W11 | Good DSL | All golden scenarios below compile+run; typed negative cases; bare Task without effects; pipeline codecs/checks | 002,017–020,028 |
| W12 | CI portability | One bundle for generic/GitHub/GitLab contexts; nonTTY/closed stdin/SIGTERM/exits/artifacts/JUnit; trusted bundle/policy | 022–026 |
| W13 | Extension without core edits | A separate example connects a custom tool + agent profile through the public Scala SPI and actually executes the tool | 028,029,031 |
| W14 | Root limits and truthful accounting | time/attempt/children/token bounds, known/unknown prices, refunds/reservations, retry/repair; no fake distributed cap | 009,027,028 |
| W15 | Ready-to-use entry point | Built-in worker/scout available immediately after connection/policy setup; ordinary tasks need no workflow authoring | 005,020,022,046 |
| W16 | Real task fixtures | Review planted defect, bugfix, new file/module, refactor, multi-file change, research report; workspace tests/verdict artifacts | 026,041,046 |
| W17 | Required baseline support for optional gateway | Yabanin-compatible config/URL/auth/labels, wrapper lifecycle owner without a second mint, no hidden direct fallback; deterministic local contract fixture. Enriched accounting/live stand are separate | 005,011,037; extended 038–039 |
| W18 | Final integration | All required W01–W17 on one immutable build, full gate report and limitations; optional claims only with evidence | 043–047 |

Each W-ID has an implementation acceptance manifest containing case IDs, owning card/lane, command argv, expected observable behavior, actual status, source/toolchain/policy hashes, and evidence links. An agent saying “done” is not a check. `not_due` is allowed only before its target milestone; required W01–W17 cannot be skipped/unavailable/not_due in `engineering_ready`.

## 3. CLI and short path

Target commands that still require implementation:

```text
raider repl --profile dev --workspace .
raider run --agent worker --input "Fix the regression and run checks" \
  --profile dev --policy coding --out artifacts/raider

raider compile workflows.raider.scala --output pipelines.jar
raider run --bundle pipelines.jar --workflow repair --input-json task.json \
  --ci generic --context ci-context.json --policy ci-patch --out artifacts/raider
```

`--agent` selects a registered built-in/custom Agent and is mutually exclusive with `--bundle/--workflow`. It uses the same Program/Runner, input codec, policy, and output artifacts. `coding` is an explicitly configured trusted local policy; `ci-patch` is a restricted CI workspace profile. Permissions are not inferred from the string name. Setup explains missing credentials/model/permissions before dispatch; doctor does not spend model tokens by default.

## 4. DSL golden scenarios

No new macros or pseudo-Scala parser are needed. Primary verbs remain `ask/start/await`, composition uses `andThen/all/batch`, and complex coordination uses `scope/fork/join`. All fixtures go through the real compiler; presets receive profile/workspace/policy before execution.

```scala
scout.ask("Find the entry point and explain how to run the tests")

val task = scout("Check configuration handling")
task                           // preview, without model/tool calls
task.run()

val job = worker.start("Fix the fixture bug and run checks")
job.send("Keep the public signature")
val result = job.await
val sameResult = job.await      // does not repeat the work

val implement = (scout andThen planner andThen worker).named("implement")
implement.ask("Add support for the new parameter")

val (facts, review) = all(scout, reviewer).ask("Check the parser")
val (size, critique) = all(
  scout("Explain the parser").map(_.length),
  reviewer("Check the parser")
).run()

val results = batch(List("parser", "config", "cli"), parallelism = 2)(
  name => scout(s"Research $name")
).run()

val chat = openSession(worker)
chat.ask("Study the parser")
chat.ask("Now fix the edge case you found")
chat.close()
```

Additional cases: explicit child `fork/join`; typed pipeline with case classes/codecs; incompatible `andThen` does not compile; `all` fail-fast cancels siblings; batchCollect does not swallow external cancellation; compile error preserves handles; await/watch Ctrl+C stops the observer; foreground Ctrl+C cancels the owned subtree. One definitions file works with REPL `:load` and compiled CLI.

At least one coding example verifies a real multi-file change and failing→passing checks. Do not replace the worker with a string echo or a prebuilt patch in the production path. A scripted backend is allowed in deterministic tests when clearly labeled.

## 5. Reordering execution of the old backlog

Numbers 001–053 are retained. Sequence is determined by interface dependencies and working acceptance; completing all of M1/P2 in order is not a prerequisite for a useful product.

1. **F0 — trust the foundation.** Close spike findings, verify toolchain/JDK, clarify ADR-015. Build/contracts/runner and minimal developer CI belong in foundation. In particular, do not carry a custom POM resolver or ThreadDeath into production without new checks.
2. **F1 — first native end-to-end tasks.** After ModelBackend/AgentBackend/Task/Tool contracts are frozen, work in parallel on runtime; two protocol adapters; filesystem/process tools. One backend/tool round passes through a real CLI skeleton and local HTTP fixture.
3. **F2 — product surfaces.** After contracts: DSL/native integration; real REPL; headless CI/artifacts. There is one shared Runner. After the wave, one definition runs through both paths.
4. **F3 — daily coding workflow.** Journal/instructions/context/sessions, typed outputs and minimal SPI, package/help/examples. This is required for a working coding agent, even if originally marked beta.
5. **F4 — acceptance of one build.** W01–W17, regression corpus, soak/cancel/resource checks, clean installation. Then permitted live smoke tests and comparison with OpenCode. Baseline support for the optional Yabanin profile is required offline; extended gateway/importers/bridges get separate capabilities; standalone readiness does not wait for a companion Go PR.

To enable genuine parallelism, root creates narrow child cards with `implementationDependencies` and `integrationDependencies`. For example, provider serialization/SSE depends on frozen ModelBackend and testkit; full integration acceptance depends on the native loop. This does not allow a parent card to be accepted before its full integration gate. Root/reviewer records dependency refinements in the board/assignment and compatibility report.

Minimal developer CI is an early, separate `RAI-045.a` after foundation; its dependency does not inherit the packaging/full-MVP parent. Parent RAI-045 includes its result and the later release/platform matrix. New child-card IDs are registered at assignment with their own prerequisites/allowed paths/acceptance; a child does not depend on acceptance of a parent that is waiting for that child.

## 6. Audit fixes required before adding features

- The shell entrypoint returns the failed producer's exit code even when logging through tee; fault injection must prove this.
- Spike evidence includes the actual source hash, environment, complete check list, and truthful exit; original artifacts remain separate.
- Headless failure/report-write failure must not return 0; inspect actual descendants, not one sleep PID.
- Check HTTP cancellation before headers and on a stalled body; a blocked read must not occupy the compute pool; the UTF-8 decoder is incremental.
- PTY setup isolates user.home/history and sets a reproducible TERM; arbitrary REPL hard interruption is not evidence of safe agent-runtime cancellation.
- Background rendering, JDK21/Linux, and all uncovered clauses remain explicit obligations of their milestones.
- Gate activation is stage-aware: [rules](quality-gates.md). Full/milestone acceptance is never replaced by a sum of local green reports.

## 7. Model-quality comparison

First pin the OpenCode version/config and Raider build hash. Use at least six W16 task fixtures, the same provider/model, task instructions, tool permissions, context/output limits, wall-time/token/monetary caps, and isolated copies of one baseline. Run at least three repetitions per task/tool; mark unavailable seed capability rather than emulating it. Evaluate result artifacts/diff/hidden tests with the same verifier.

Report task pass rate, protocol/safety failures, wall time, attempts, tokens, observed/unknown cost, and breakdown by task. A small sample does not prove statistical superiority. Do not select the best run after the fact; preserve provider outages and tool mismatches in results. If comparison finds a Raider weakness, create a reproduction card, fix it, and rerun affected cases; do not change the verifier to force a match.

Live tests require an explicitly specified endpoint/account/cap. Until those exist, the development coding-model session runs in the mode selected by the owner, while product gates use local/mock fixtures. The final report distinguishes executable capability from real-model quality.
