# CI-EXAMPLE: a Scala CI pipeline with recorded lineage

Owner request: 2026-10-13. Add a substantial generic local CI example using the
current Scala/ZIO runtime and show its actual resulting lineage. The owner
authorized building and executing only this example once; general tests and
quality gates remain outside this follow-up. Existing paid-call authorization
is coding-plan-only Yabanin, requested model `glm-5.3-flash`; secrets stay private.

## Scope

Allowed paths: `examples/user-guide/ci/**`, `scripts/build/user-ci.sh`,
`docs/user-guide-ci.md`, README and navigation in the existing user guides,
`docs/work/records/CI-EXAMPLE.md`, and safe captured example data under
`docs/examples/ci-pipeline/**`. Coordinator owns shared build script, README,
card and integration. Visible workers own disjoint worktrees and named scopes.

The example composes current `Program`, `Task`, `all`, and bounded `batch`
with current native headless runs. A repair runs in a disposable billing
workspace; pinned deterministic compiler/tests verify the change independently.
Parallel reviewers receive inline source without mutation authorization; their
results are checked, followed by bounded boundary reviews and a typed decision.
Each agent invocation retains its own native event and lineage artifacts.
An application-owned pipeline index links these runs; it must not be presented
as native unified phase lineage, shared root accounting, or durable recovery.

## Acceptance

Use real implemented signatures, concrete typed stages and meaningful input/output.
Build and run only the example after static review. Preserve failed results.
Report actual stage outcomes and actual lineage references; do not invent a
graph, complete trace, known cost, or replay result. Keep secrets out of tracked
files, argv, snapshots and reports. No production deployment, Git mutation,
forge integration, new runtime abstractions, or protected verifier changes.
