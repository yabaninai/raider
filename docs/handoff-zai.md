# z.ai development handoff

For OpenCode: [ready handoff](handoff-opencode.md) and project command
`/raider-finish`. Z.ai can be the coding provider; the runtime is vendor-independent.

Below is the historical single-card prompt. For the current goal of parallel
development and completing the working product, use the [finish prompt](prompts/opencode-finish.txt);
it accounts for review, the actual board, and stage-aware gates.

## 1. Initial prompt

```text
You are developing raider, a Scala agent harness for CI pipelines with a full REPL for debugging.
Repository: https://github.com/yabaninai/raider.git.

Required context: AGENTS.md, docs/PLAN.md, docs/dsl-experience.md,
docs/product-repl.md, docs/ci-runtime.md, docs/provider-protocols.md and the assigned card. Load other specifications
for relevant card clauses without ignoring required invariants.

The package currently contains a plan; runtime/build/gates are not yet implemented.
Start with RAI-001 → RAI-003 → RAI-002 → RAI-004 according to dependencies. Do not implement the
whole program at once. One card means one verifiable diff and work record.

Main UX:
  scout.ask("question") — foreground answer;
  val job = scout.start("task") — background;
  job.await / job.send("clarification") / job.cancel();
  (scout andThen reviewer).ask("task");
  all(scout("A"), reviewer("B")).run();
  batch(inputs, parallelism=2)(scout(_)).run().

Agent/Workflow/Session apply(input) creates a Task without model/tool execution.
ask/start explicitly launch from the REPL facade; one runtime owns all jobs.
Do not create a fake Scala custom parser or hide Future/Await/global effects.

Foundation: pinned Scala 3 + ZIO 2 + official compiler REPL adapter/JLine;
the exact toolchain is selected and checked by RAI-001/003. M0 native agent loop
works with scripted/mock backend, OpenAI-compatible Chat/SSE, Anthropic-compatible Messages/SSE, tools and headless CI.

Raider works without Yabanin through both first-class compatible APIs. Yabanin
is optional; do not change its Go code in Raider cards. docs/yabanin-integration.md and the source snapshot describe verified
surfaces and gaps; deployment capabilities are confirmed by doctor/stand.

Everyday gates do not use paid LLM calls. Do not change thresholds/protected
verifiers in product cards. Missing/scanner error/skipped/stale does not pass.
If an API is unclear, prepare an ADR/reproducer; do not change it silently.

Output: actual changed paths, commands/exit codes, valid evidence path,
limitations, and the next ready card. Create a work record only after
actual work; prepare it before the final fingerprint gate.
```

## 2. Assigning the next card

```text
Execute RAI-<NNN> from docs/tasks/<milestone-file>.md.
Accepted dependencies: <IDs + actual records>.
Relevant contracts: <sections>.
Allowed paths: <paths>.
Baseline: <source fingerprint/commit>.
Do not expand scope. Run required gates and provide the evidence.
```

Review separately in a fresh context using the prompt from [model workflow](model-workflow.md).
The owner need not manually check every line, but final acceptance/gate policy
ownership remains with them.

## 3. First results to obtain

1. RAI-001: REPL/headless feasibility prototype, transcripts, and ADR.
2. RAI-003: clean build, pinned toolchain, canonical commands.
3. RAI-002: compile-tested API and DefinitionContext/CI schemas.
4. RAI-004: quality runner with validated manifests and policy.
5. RAI-005/006: profile/config and deterministic backend.
6. Then a native runtime/REPL vertical slice from the graph, not a wholesale implementation.

## 4. Do not assign in one request

“Build all of raider”, “make it production-ready”, “add everything from pi/OpenCode”,
and “do all Yabanin integration” are not cards. Each task needs exact inputs,
contracts, normal/error/cancellation cases, and exit criteria.

## 5. Financial and publishing status

This package does not authorize spend, deployment, release publishing, or push.
Development uses local/mock tests. Live tests run in a separately selected,
capped owner profile. Keys are passed through environment/helper references
outside git. Model/provider names in examples are roles, not promises of current availability.
