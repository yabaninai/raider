# Bring Raider to working condition through OpenCode

## Current request: 12,000 hours, parallel development

For further work, use [roadmap-12k](roadmap-12k.md) and the
[full prompt](prompts/opencode-12k-parallel.txt). Open OpenCode at the repository
root, select Build, and paste:

```text
Execute @docs/prompts/opencode-12k-parallel.txt according to @docs/roadmap-12k.md.
Start by checking the actual source/board and nearest ready cards.
Coordinator + up to 3 workers in isolated worktrees/snapshot copies.
Priority: live statuses, subagent tabs, convenient plugins.
Yabanin strictly in final phase P11, after standalone candidate P10 is accepted.
Continue implementation, review, integration, and checks; save a handoff if the session limit is reached.
```

The old `/raider-finish` and `/raider-build` below describe the previous finish
target. They do not replace the new prompt or its Yabanin-last order. The
coordinator checks installed tools and isolated-cwd support at startup.

## Launch

From the repository root, start `opencode`, then:

```text
/raider-finish
```

The command [.opencode/commands/raider-finish.md](../.opencode/commands/raider-finish.md)
selects Build and includes the full [finish prompt](prompts/opencode-finish.txt).
The old name `/raider-build` remains an identical alias. Vendor/model are not
hardcoded: the owner-selected coding profile is used. Project command format
is confirmed in [OpenCode docs](https://opencode.ai/docs/commands/); OpenCode
1.18.34 was installed at the time of review. Before launch, the worker checks
its actual version and available subagent tools.

Short prompt for manual insertion in Build:

```text
Bring Raider to a working coding agent according to @docs/working-product.md.
Execute @docs/prompts/opencode-finish.txt and AGENTS.md.
Account for the current code and @docs/reviews/2026-10-03-readiness.md; do not start over.
Parallel development is authorized: coordinator + up to 3 workers
in isolated worktrees/snapshot copies per docs/parallel-development.md.
Fix defects, integrate, run checks, and continue through W01–W17.
Show the installed CLI, real REPL/DSL, coding workflow, and CI evidence.
Report live/model comparison separately; do not present mock success as parity.
```

## What exists and where to continue

There is a specification, `docs/work/board.md`, records, and real REPL/headless
and OpenAI-compatible feasibility spikes. The [independent review](reviews/2026-10-03-readiness.md)
found false-green results in both launchers, incomplete harness/evidence
reproducibility, and uncovered production guarantees. Product build/runtime/DSL/provider/tools/CLI/gates
still need implementation. The next session first checks the actual repository:
status may have changed.

The first result of a new run is closure of current review findings and
foundation prerequisites, then parallel runtime/providers/tools slices. Old
“32/32” and “6/6” do not close full acceptance: confirm their scope and check
reliability. Preserve original evidence; each new run gets separate hash/run
identity.

`python3 scripts/plan/validate.py` checks only the plan/links/backlog/prompts.
A successful exit does not prove Scala execution or coding-agent readiness.
Create and execute product gates according to the [stage contract](quality-gates.md).

## Coordinator authority

The owner has authorized parallel development. The coordinator assigns ready
child cards, separate directories, path ownership, and an independent reviewer
without asking again for each task. The coordinator alone integrates changes,
controls shared APIs/build/quality registry, and reruns affected shared tests.
Spawning a subagent does not create a worktree; verify the isolation mechanism
before coding.

Git is currently unborn/untracked: until a suitable commit exists, use
immutable source snapshot copies that include needed untracked files and exclude
secrets/.git/outputs. Do not create a commit/stash/reset of others' work for
convenience. Each worker gets an absolute path/baseline/exact files/signatures/tests
and returns a patch+hashes/evidence. See the [detailed runbook](parallel-development.md).

Use narrow packets for simpler models. A fresh-context reviewer reads the
actual diff and tests/logs. Split large contracts before implementation; do not
let new architecture appear silently inside a worker diff. Future-stage checks
remain pending; do not disable active failing tests.

## When work is complete

[W01–W17](working-product.md) require more than an M0 runtime: useful file tools,
a test-driven coding loop, sessions/history/context/compaction, typed DSL, real
REPL, installable package, and CI. One binary/source snapshot passes shared
acceptance. Baseline Yabanin contract W17 is required offline; gateway execution,
enriched integration, and external backends are optional and do not replace
the native loop.

Report these separately: engineering_ready, live_verified, opencode_comparison.
Run live smoke/comparison with a configured endpoint and explicit budget;
without those, mark the result pending. The coding model used by OpenCode is
part of the owner-selected development session, not product paid evals.

## Resume after a session stops

```text
Continue executing docs/prompts/opencode-finish.txt.
Check git status, board/records, active lane paths/baselines/session IDs.
Check live workers and applied patches; do not duplicate them.
Resume the nearest ready action, integrate, and rerun affected gates.
Complete W01–W17; list external blockers precisely and continue the rest of the work.
```

For every handoff: active task, source baseline, actual changed paths, exact
commands/exits/log hashes, reviewer verdict, remaining obligations, and next
executable action. A list of files or spawned workers does not close a milestone.
