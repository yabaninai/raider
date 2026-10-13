# USER-DOCS-CURRENT: User Documentation and Verified Gallery

Status: done (content/examples; final main evidence in work record).
Date: 2026-10-13. Baseline: `7702fd0` (main, clean).
Owner request: inspect the current main branch, update user docs, verify every demonstrated example, and merge into the main branch. Luna is the worker; the primary session is coordinator. This is a separate documentation/example card.

## Result

The README and user guide show only actual current entry points and runnable examples. Every standalone example has a verifiable result, fixture inputs, an exact command, and captured evidence. Product/runtime APIs are unchanged. Target gallery: Scala compiler; lazy/map; parallel all/bounded batch; background jobs; multi-turn session mechanics; typed Program compiled bundle; native coding read/check-fail/edit/check-pass through a local scripted Scala HTTP stand; real artifacts and verified replay behavior. A mock does not demonstrate LLM quality.

## Ownership / allowed paths

Coordinator owns `README.md`, `docs/dsl-experience.md`, `docs/selfdev-prompts.md`,
`docs/user-guide.md`, `docs/work/board.md`, this task and
`docs/work/records/USER-DOCS-CURRENT.md`,
`scripts/build/user-examples.sh`, `scripts/quality/user_examples.py`,
`examples/user-guide/stand/**`, `examples/user-guide/fixture/**`.
Luna exclusively owns `docs/user-guide-repl.md`, `examples/user-guide/repl/**`,
`examples/user-guide/bundle/**` in her own worktree.
Reviewer is read-only. Evidence/output is ignored under `artifacts/user-examples/**`.

## Acceptance

- DOCS-01: describe build/setup prerequisites, actual CLI flags, default mock markers, env/helper credentials, and correct workspace/artifact paths. No invented model outputs, secret values, or hardcoded test/gate counters.
- DOCS-02: run every published runnable example against the integrated candidate; record actual exit, transcript/result/artifact assertions, and source hashes. Interactive examples use the real compiler; a tabs demo, if included, uses a real PTY.
- DOCS-03: coding demo mutates only a disposable fixture workspace and performs real fs_read/fs_edit/proc_run through the native CLI; failure is observable before edit and passing check afterward. Stand binds to loopback and labels scripted output; it rejects unexpected sequences; no paid calls or hidden direct fallback.
- DOCS-04: public API unchanged; mock/scripted/model-quality/replay capabilities described truthfully. Keep every future/untested capability out of the gallery. Independent review; changed and execute are required on final integrated inputs.

## Required checks

`make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`, docs profile,
explicit real examples verifier with per-example cases and logs, independent
review. New Scala examples must compile with pinned compiler. Do not edit
protected registry/verifier fixtures; the new verifier is standalone and is not
registered as a gate. Create a work record only after execution. Root records
final evidence after merge and records.

## Non-goals

Runtime/DSL/CLI fixes, new dependencies/macros/daemon, claiming a released
embedding SDK or full SDLC, paid provider calls, edits to TokenRouter, remote
push/publication, or changes to protected gate policy. For found product defects,
create a reproduction and separate card; document actual limitations and exclude
a broken success example.

## Integration

Branch-per-lane worktrees. Coordinator integrates Luna by reviewed no-ff merge,
then merges the final docs/example branch into main (the actual master-equivalent
branch). Shared build/gate/board have one owner; do not run concurrent mutable
builds in one checkout.
