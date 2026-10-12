# USER-DOCS-CURRENT: пользовательская документация и проверенная галерея

Status: done (content/examples; final main evidence in work record).
Date: 2026-10-13. Baseline: `7702fd0` (main, clean).
Owner request: изучить текущую основную ветку, обновить user docs, проверить
каждый демонстрируемый пример и вмержить в основную ветку. Luna — worker,
primary session — coordinator. Это отдельная documentation/example card.

## Result

README и user guide показывают только actual current entrypoints и runnable
examples. Каждый самостоятельный пример имеет проверяемый результат, fixture
inputs, точную команду и captured evidence. Product/runtime API не меняется.
Целевая gallery: Scala compiler; lazy/map; parallel all/bounded batch; background
jobs; multi-turn session mechanics; typed Program compiled bundle; native coding
read/check-fail/edit/check-pass через локальный scripted Scala HTTP stand;
реальные artifacts и проверенное поведение replay. Mock не доказывает качество LLM.

## Ownership / allowed paths

Coordinator owns `README.md`, `docs/dsl-experience.md`, `docs/selfdev-prompts.md`,
`docs/user-guide.md`, `docs/work/board.md`, этот task и
`docs/work/records/USER-DOCS-CURRENT.md`,
`scripts/build/user-examples.sh`, `scripts/quality/user_examples.py`,
`examples/user-guide/stand/**`, `examples/user-guide/fixture/**`.
Luna in its own worktree exclusively owns `docs/user-guide-repl.md`,
`examples/user-guide/repl/**`, `examples/user-guide/bundle/**`.
Reviewer read-only. Evidence/output — ignored `artifacts/user-examples/**`.

## Acceptance

- DOCS-01: build/setup prerequisites, actual CLI flags, default mock markers,
  env/helper credentials and correct workspace/artifact paths described.
  No invented model outputs, secret values, hardcoded test/gate counters.
- DOCS-02: каждый published runnable example исполнен against integrated candidate;
  actual exit, transcript/result/artifact assertions, source hashes recorded.
  Interactive examples use real compiler; tabs demo, if included, uses real PTY.
- DOCS-03: coding demo mutates only disposable fixture workspace, performs real
  fs_read/fs_edit/proc_run through native CLI; failed check is observable before
  edit, passing check afterward. Stand binds loopback and labels scripted output;
  rejects unexpected sequences, no paid calls or hidden direct fallback.
- DOCS-04: public API unchanged; mock/scripted/model-quality/replay capabilities
  described truthfully. Every future/untested capability stays outside gallery.
  Independent review; changed and execute required on final integrated inputs.

## Required checks

`make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`, docs profile,
explicit real examples verifier with per-example cases and logs, independent
review. New Scala examples must compile with pinned compiler. No protected
registry/verifier fixtures edited; new verifier is standalone, no gate registration.
Work record only after execution. Root final evidence after merge and records.

## Non-goals

Runtime/DSL/CLI fixes, new dependencies/macros/daemon, claiming released embedding
SDK or full SDLC, paid provider calls, edits to TokenRouter, remote push/publication,
changing protected gate policy. Found product defects receive reproduction and
separate card; document actual limitations and exclude a broken success example.

## Integration

Branch-per-lane worktrees. Coordinator integrates Luna by reviewed no-ff merge,
then final docs/example branch into main (the actual master-equivalent branch).
Shared build/gate/board have one owner; no concurrent mutable builds of one checkout.
