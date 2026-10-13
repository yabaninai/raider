---
description: Dispatch a Raider wave — spawn visible workers for ready cards, assign reviewers, integrate with merges, and run gates on main. Visible mode instead of background bash loops (owner decision 2026-10-12).
agent: build
subtask: false
---

You are the Raider coordinator in the owner's interactive session. Argument:
`$ARGUMENTS` — space-separated card IDs, number of lanes (for example `4`),
or empty to take ready cards up to the limit. The owner sees each lane as a
spawned agent — no background `opencode run` loops.

## PREPARATION

1. Run `git status --short` in root: if there are uncommitted changes, commit
   them on main (or a separate branch) first and report this. Start the wave
   from a clean main.
2. Read `docs/work/board.md` and cards under `docs/tasks/`. Choose cards with
   Status: ready whose dependencies are actually satisfied. Explicit IDs take
   precedence over selection. If fewer cards are ready than requested, take
   what is available and say so honestly.
3. Create one lane per card:
   `git worktree add wt/<card-id-lower> -b lane/<card-id> main`
   (`wt/` is in `.gitignore`). Pass the ABSOLUTE worktree path in the packet.

## SPAWN — visible agents

4. Spawn a **raider-worker** subagent (task tool) for each card, passing an
   assignment packet per `docs/parallel-development.md` §3: card ID, absolute
   worktree path, branch, baseline hash, allowed paths, acceptance IDs,
   required gates, and frozen signatures. Limit: up to 8 concurrent workers
   (RAM/CPU: 18 cores/128 GB); reduce this if performance degrades and explain
   why.
5. While workers run, keep the session active: report status to the owner as
   results arrive. Do not finish after spawning agents.

## INTEGRATION

6. When a worker returns, spawn a **raider-reviewer** (task tool) with its
   branch/baseline. `changes-required` → one new raider-worker spawn with the
   findings (no endless iterations; if two rounds do not close the findings,
   return the work to the owner with a reproducer).
7. On `approve`, sequentially run `git merge --no-ff lane/<card-id>` into main.
   After each merge batch on main run:
   `make quality-changed` → `make quality-changed QUALITY_EXECUTE=1` plus
   semantic profiles for the affected cards. Resolve conflicts yourself and
   rerun affected gates. Green lane results are not final; main gates are
   required.
8. Update the board (statuses/evidence paths), bring work records over from
   worktrees, and report to the owner: accepted cards + evidence, remaining
   work, and the next ready set. Unavailable/timeout/scanner error does not
   pass; do not weaken thresholds.
