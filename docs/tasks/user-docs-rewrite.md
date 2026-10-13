# USER-DOCS-REWRITE: task-first user manual

Owner request: 2026-10-13. Completely rewrite the user-facing documentation.
The current guides overwhelm users with internal acceptance/evidence prose.
Baseline main: fecfda6. Deliver an English manual users can follow to start
Raider, choose TUI/REPL/CLI, configure a model, do useful tasks, and inspect results.

## Scope and ownership

Allowed: README.md; docs/onboarding.md; docs/user-guide.md;
docs/user-guide-repl.md; docs/user-guide-tui.md; docs/user-guide-ci.md;
docs/user-guide-lineage.md; this card; docs/work/records/USER-DOCS-REWRITE.md.
Coordinator owns README/navigation/card/integration. Six independent writing
lanes own one user guide each; two read-only lanes review source correctness and
reader usefulness. Each lane uses its own branch and worktree.

Preserve runtime, build, API contracts, protected fixtures, recorded captures,
and full projection artifacts. Keep engineering history in existing references
and work records. Link deeper technical material only when useful.

## Acceptance

Begin each page with the action it supports. Commands are copyable after named
setup, required variables are defined, output locations are explicit. Lead with
TUI/REPL/CLI entry points rather than release bookkeeping. Make a real agent
coding task easy to find. Explain dry-run demo versus model-backed behavior in
one sentence at the point of use. Keep constraints that change user expectations:
TUI input currently stores drafts; no Scala evaluation in tabs; six live CI roots
versus separately generated unified structure; graph projection is not replay.
No invented APIs, blanket safety claims, or fresh runtime-result claims.

All user prose is English. No card IDs, acceptance gate commentary, hash blocks,
repeated provenance paragraphs, or audit narration in the main user path.
Existing captures/projection appendices and engineering references stay available.

The owner's no-general-checks instruction persists for this documentation-only
change. No builds, provider calls, or runtime tests. Perform source comparisons,
link/anchor checks, preserved-artifact checks, and independent static review.
Merge and push accepted documentation to main under standing authorization.
