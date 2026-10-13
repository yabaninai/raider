# DOCS-EN follow-up: English throughout tracked files

Date: 2026-10-13. Baseline: `a4ba175` on `main`.

The owner clarified that the language requirement applies to every tracked file,
including code comments, test descriptions, sample prompts, and responses. The
previous documentation translation did not cover all of these source files.
The owner explicitly requested no builds, tests, or quality gates.

## Work performed

Eight visible Luna workers used separate `docs/all-files-1` through
`docs/all-files-8` branches and isolated checkouts. Their scopes were disjoint.
The coordinator integrated their changes and expanded the contributor language
rule in `AGENTS.md`. The task and protected-verifier cards record this scope.

- Runtime source changes translate comments and Scaladoc without changing
  executable statements.
- Lineage fixture prompts and responses are English; corresponding exact
  expectations and payload-leak guards were translated together. IDs, schemas,
  policy, and non-prose metadata remain intact.
- PTY inputs and corresponding assertions use English words with accented Latin,
  emoji, and CJK cases retained. Existing assertions and timeouts remain intact.
- Historical spike prompts and mock responses were translated with their
  corresponding expected responses. The two-byte UTF-8 fixture uses U+00E9.
- Protected verifier changes are limited to prose and fixture representation;
  evaluator compatibility patterns retain their original matching semantics.
  Independent static review is required before their integration.

## Evidence and limits

Reading, a tracked-text inventory, and independent diff review are recorded under
ignored `artifacts/docs-english-followup/`. These are static observations, not
executed acceptance gates. No build, test, live model call, or quality gate was
run for this follow-up. Earlier source fingerprints do not accept this revision.

Immutable historical run outputs retain their actual original inputs. Independent
older worktrees keep their own branch contents. Neither is part of the tracked
main tree delivered by this revision. No existing history or evidence was rewritten.

GitHub publication, when requested by the owner, is a separate ordinary push;
local integration alone does not update `origin/main`.
