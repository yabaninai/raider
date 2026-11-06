# STEP 0 — interrupted predecessor audit (RAI-234, this worktree)

Found on lane start (2026-10-29, branch lane/rai-234, base ed1ef4f, clean tree
vs HEAD, no stashes): an UNTRACKED `evidence/rai-234/` directory left by a
cancelled predecessor worker. Tracked files: none touched (git status showed
only `?? evidence/`).

## Inventory (sha256, content-identical before/after relocation)

| File (original path) | sha256 |
| --- | --- |
| evidence/rai-234/calm/pre-sanity.log | ffb88484c2dcc0c494acfd752d1b2a486ba86added93dcb0da09119cab1ef396 |
| evidence/rai-234/calm/pre-sanity.meta | 45c0f603b4224aa10277c49947adbdd7fc298a89a82dd2c736a0267a3ff5ad17 |
| evidence/rai-234/load-gate.log | 2552a60eb7929a05cb7f7dd246633df3d2e3f88c36c2bbe808a844ff19d1429e |
| evidence/rai-234/matrix.sh | 5888b00fecc619b5865423a40f98418cdcd8e001a1de8c6f3b1a7450ed70fe09 |

Relocated (move only, no content change) to `evidence/rai-234/predecessor/` so
the successor battery writes into a fresh namespace; content hashes above were
re-verified after the move.

## Interpretation

- `calm/pre-sanity.log` + `calm/pre-sanity.meta`: a FAILED sanity compile run
  (rc=1, 2026-10-28T12:08:07Z, ~27 s): zinc E008 "value id is not a member of
  raider.core.ToolCall" at `AgentLoop.scala:772:7` (`tc.id` in
  `extractCallId`). The CURRENT clean tree (ed1ef4f) has the same `tc.id` line
  AND `ToolCall.id` exists in core (`tool.scala`: `def id: ToolCallId = call`,
  the documented Scala 3.9 E046 alias accessor), so the E008 cannot come from
  the baseline; the predecessor evidently had local source edits (reverted
  before cancellation). The successor sanity run below proves the clean tree
  compiles; the failed log is retained as history, not as baseline evidence.
- `load-gate.log`: 40 gate-check cycles 2026-10-28T12:16:04Z..13:34:05Z, 1-min
  load 79.46..319.35 — the predecessor held off starting any sbt run under
  extreme ambient load and never started its battery (no run logs exist).
  No loaded-matrix evidence was salvaged: the predecessor produced no runs.
- `matrix.sh`: the predecessor's battery driver (RUNS=32, 18 `yes` burners,
  own-PID pidfile sweep, per-run 1/5/15-min before/after readings, stall
  watchdog killing only its own comm-verified sbt PID, resumable). Audited by
  the successor and ADOPTED for this card's battery (see successor audit in
  docs/work/records/RAI-234.md); copied to `evidence/rai-234/matrix.sh` for
  execution.

## Decisions

- KEEP: all four files, under `evidence/rai-234/predecessor/`.
- SALVAGE: `matrix.sh` (adopted, re-hashed as executed).
- No tracked file was or will be touched by this audit.
