# User handbook rewrite

Date: 2026-10-13. Baseline: `787c6aa` on `main`.

The owner requested natural English documentation with more Scala examples and
concrete resulting lineage. The coordinator rewrote README; seven visible Luna
workers used isolated `docs/handbook-*` branches for disjoint guide files. The
coordinator integrated their commits and expanded the lineage explanation after
reading the recorded node relationships.

## Changed documents

- `README.md`: first REPL launch, parallel review and test planning, repository
  repair and audit, typed release decision, and a recorded lineage excerpt.
- `docs/onboarding.md`: toolchain setup, source launch commands, provider and
  credential configuration, first workflows, artifact locations, and troubleshooting.
- `docs/user-guide.md`: billing repair, payment audit, typed release workflow,
  and inspection of the resulting files and process results.
- `docs/user-guide-repl.md`: Scala definitions, lazy asks, type inspection,
  mapped labels, JSON boundary assertions, ordered batches, background jobs,
  conversation history, and the existing typed `Program` pipeline.
- `docs/user-guide-lineage.md`: actual billing-run nodes, containment and input
  edges, generated Scala, JSON lookups, process-result interpretation, replay
  command, and a separate nested delegation schema fixture.
- `docs/api.md`: current signatures, REPL setup, compiled workflow types, tool
  implementation, runtime services, and lineage references.
- `docs/architecture.md`: descriptor execution, shared runtime paths, ownership,
  resource accounting, events, and the implemented lineage export path.
- `docs/contributing.md`: card and lane workflow, evidence, English writing
  rules, and source-backed example requirements.

## Example provenance

Scala examples use current implementation and the existing files under
`examples/user-guide/`. The release workflow is the checked-in `TaskSummary`.
Adapted snippets are source illustrations; no new execution is claimed.

The lineage walkthrough uses the previously executed offline billing run at
`artifacts/user-examples/verification-ip716woq/coding/work-b1d4212a/`. Its saved
projection has eight model-call nodes and seven tool-call nodes. The guide
preserves empty model text, null result tails, and the contradictory preflight
check entry. Process exit codes are inspected in the detailed trace rather than
inferred from tool invocation success. The saved billing export lacks model
response content needed to reproduce the tool sequence through replay; the
guide explains that limit and gives the replay command for compatible complete
records. The nested delegation example is the
separate checked-in `deep-tree` schema fixture. The foreground REPL example
currently does not export the CLI run directory.

## Review and execution scope

Independent static review is recorded in ignored `artifacts/docs-handbook/review.json`.
The tracked-file language inventory is recorded in
`artifacts/docs-handbook/language.json`. These reports identify their source
revision or file hashes. Static inspection is not an executed quality gate.

Per the owner's instruction, no builds, tests, quality gates, replay executions,
or live model calls were run for this rewrite. Existing execution evidence
belongs to its original source fingerprint. No new product API, runtime feature,
dependency, or gate policy was introduced.
