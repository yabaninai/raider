# USER-DOCS-REWRITE: practical English manual

Date: 2026-10-13. Baseline main: `fecfda6`.
Card: `docs/tasks/user-docs-rewrite.md`.

The owner requested a complete rewrite of the user documentation to remove
internal evidence/acceptance narration. README now leads with launch commands
and task links. Onboarding covers Java/build, TUI, Scala REPL, CLI, and model
configuration. The handbook starts with the existing live billing helper and
then explains direct coding tasks on a user's own project. The Scala, CI, and
lineage guides are shorter task instructions. A new TUI guide explains tabs,
drafts, scrollback, and the current lack of prompt submission.

Complete projection artifacts and historical captures stay in their existing
appendices. User pages link them without repeating hashes, cards, or acceptance
history. Critical behavior remains explicit at point of use: mock responses,
workspace permissions, separate live CI roots, and projection versus replay.

Eight visible Luna lanes used isolated branch/worktree pairs. Six writers
owned disjoint guides; two read-only reviewers checked commands and reader
usefulness. The coordinator integrated branches sequentially in `wt/manual-v2`
and unified navigation, launch commands, examples, and wording.

The owner's documentation-only restriction on general checks remains in force.
No builds, runtime tests, replay, or provider calls ran. Source comparisons used
the actual CLI/REPL/TUI implementations, example helper and application, and
projection sources. Existing run results are not new execution claims.

Static checks in the integration worktree:

- `python3 artifacts/manual/check-docs.py`: exit 0; relative links and anchors
  resolve, no tracked Cyrillic text, six historical projection strings match,
  and the complete unified projection keeps its original SHA-256.
- `git diff --check`: exit 0 after removing one trailing blank line.
- Command/source review: `artifacts/manual/command-review.json`.
- Independent user manual review: `artifacts/manual/review.json`.

Runtime code, build scripts, provider configuration, protected fixtures, and
recorded captures were not changed. The next implementation work relevant to
these pages is the existing live phase/delegation wiring work; the TUI remains
a tab/draft interface and the separate REPL/CLI run agent tasks.
