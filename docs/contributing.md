# Contributor Guide

This guide describes how to make a small, reviewable change to Raider and report
what the evidence supports. Read [AGENTS.md](../AGENTS.md), the assigned task
card, and the relevant contracts before editing. The [architecture guide](architecture.md)
shows how the modules fit together. The current implementation and board are
the source of status; plans and old records are not proof that a feature exists.

## Contributor workflow

### 1. Read the assignment and establish the lane

Work on one card and its stated acceptance, allowed paths, dependencies, and
non-goals. Start in the assigned absolute worktree path. Check `git status
--short`, the branch, and the baseline commit before editing. Keep generated
outputs, compiler state, and reports inside that lane. Do not edit shared
contracts, `build.sbt`, gate policy, or the board unless the assignment names
you as their owner. Follow [parallel-development.md](parallel-development.md)
for isolation and integration.

### 2. Trace the behavior to current code

Read the relevant implementation, tests, contracts, and user-facing docs. Check
actual signatures and current behavior before writing an example or proposing
a change. If the card conflicts with the code or a frozen contract, report the
small discrepancy and a reproducer; do not silently invent a new API. Product
code in `modules/**`, `contracts/**`, and `examples/**` uses Scala 3 and ZIO.

### 3. Make one verifiable change

Keep the diff within the card. Add or update a behavioral fixture for the
accepted behavior, including relevant failure and cancellation cases. Preserve
trust boundaries: workspace policy does not sandbox arbitrary Scala or plugin
code; tests use mock/replay or an explicitly configured local stand. Do not
place credentials in source, argv, examples, logs, or reports. Do not make paid
provider calls without an explicit endpoint and budget.

For a public contract change, use the separate ADR process and update affected
consumers and examples. Do not add adjacent features, dependencies, or
abstraction layers as part of an unrelated card.

### 4. Run the checks required by the card

Use the current [quality-gate catalog](quality-gates.md) and the assigned
card's profiles. When the runner is available, the standard changed-file flow
is:

```sh
make quality-changed
make quality-changed QUALITY_EXECUTE=1
```

Then run the card's required semantic profiles. `quality-changed` without
`QUALITY_EXECUTE=1` classifies work; it does not run gates. A skipped, timed
out, errored, or unavailable check is pending or failed, never passing. A
successful command is evidence only for the source and gate configuration it
actually checked. After source changes, rerun the affected checks. Do not edit
thresholds, fixtures, or verifiers to make a result green.

Commands in this guide are instructions, not evidence that they were run.
Record exact commands and exit codes only after execution. If the task
explicitly forbids checks or an external dependency prevents them, state that
clearly; do not create a work record with fabricated results.

### 5. Review and hand off with result lineage

After actual execution, create a record from
[the work-record template](templates/work-record.md). Report:

- the card, lane branch, baseline commit, and resulting commit;
- changed paths and the behavior they implement;
- each executed command and exit code, with its log, manifest, or artifact path;
- the source fingerprint and gate configuration for the evidence, where available;
- checks that remain pending or failed, risks, and the next available card.

Evidence belongs to the exact lane snapshot that produced it. A lane report
does not establish that an integrated candidate passed. The coordinator must
review the merge and run the affected checks on the integrated source. Keep
historical evidence values intact when documenting later changes.

## Writing and maintaining documentation

- Write repository prose in English, including comments, prompts, task text,
  historical records, and work records. Preserve identifiers, paths, API names,
  and immutable evidence values.
- Use direct, specific language. Remove filler and AI clichés such as “delve,”
  “leverage,” “foster,” “it's worth noting,” “importantly,” and “genuinely.”
  Describe the action, observed behavior, evidence, and limitation plainly.
- Back Scala snippets and API claims with current source. Prefer linking to a
  checked-in example or implementation file. For example, the lazy `scout`
  expression in [`02lazy-map.repl`](../examples/user-guide/repl/02lazy-map.repl)
  is exercised by the command and expected marker in the
  [REPL examples guide](user-guide-repl.md#2-lazy-ask-and-map). Do not present a
  planned signature as implemented. Label fragments that are illustrative and
  commands that have not been executed.
- For runnable commands, give the working directory, required configuration,
  expected observable result, and where output is written when relevant. Say
  whether a result comes from a scripted fixture or a live model. Never imply
  that a mock response demonstrates model quality.
- Update navigation links and adjacent docs when a change makes them stale.
  Do not rewrite roadmap status or historical results as part of an unrelated
  change.

## Documentation map

- [README](../README.md): project status, current runnable examples, and entry
  points.
- [Onboarding](onboarding.md): setup and first REPL and CLI runs.
- [REPL and typed workflow examples](user-guide-repl.md): checked Scala and
  bundle examples with their inputs and expected results.
- [Session lineage](lineage.md): lineage artifacts, replay behavior, and
  limitations.
- [API reference](api.md): current module and API contracts.
- [Architecture](architecture.md): modules, runtime flow, and resource control.
- [TUI guide](user-guide-tui.md): tabs, drafts, command palette, and scrollback.
- [Lineage guide](user-guide-lineage.md): run artifacts and recorded replay.
- [Working product acceptance](working-product.md) and
  [development plan](PLAN.md): target behavior and roadmap; read alongside the
  current code and board.

When documenting a newly shipped surface, check its command parser/dispatcher
and behavioral test, then use a runnable example only if the example's working
directory, setup, and expected output match that code. The `docs` profile also
checks links and examples; `scripts/quality/user_examples.py` exercises the
checked-in REPL and CLI example corpus, but it does not prove every prose
example in every guide. For example, do not describe `:approve` as a general
Scala REPL command: it is routed by the tabs shell's plan panel. Likewise,
`raider resume` recovers recorded observable state; it does not restart a run or
replay mutations. Keep exported workflow support distinct from downstream
verification and walkthrough work that is not yet delivered.

## Trust and execution boundaries

Scala programs, bundles, plugins, and repository commands run with the
invoking process's permissions. Workspace tools are not a sandbox. Use
disposable workspaces for untrusted repositories or code. Supply credentials
through approved environment or helper references, and never expose their
values in Git, argv, examples, artifacts, or output. Do not retry a mutating
operation after an ambiguous result unless its contract permits it.
