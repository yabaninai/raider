<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider parallel development

The owner explicitly authorized parallel development on 2026-10-03. This
runbook applies to agents developing Raider. Raider product subagents are
checked separately against runtime scopes/workspaces. Goal: [working product](working-product.md).

## 1. Roles and concurrency limit

Owner decision 2026-10-16 (supersedes the earlier "up to three implementers"
default and the 2026-10-12 "8–10 active workers" ceiling for agent count):
capacity is the primary coordinator session plus up to 20 spawned Luna
agents. The primary session remains the single coordinator/integrator and
sole owner of the integration checkout; spawned agents may lead heavy
worktree lanes under its assignment packets. Agent count is no longer the
binding limit — build capacity is: at most 8–10 concurrent heavy
(sbt-running) lanes on 18 cores / 128 GB, with light lanes (docs, contracts,
review, research, fixture preparation without full builds) filling the
remaining slots. A reviewer is always available; do not occupy all writer
slots at the cost of review. Reduce heavy concurrency when RAM/CPU degrade,
and split a large card before coding.

The coordinator solely owns the integration checkout, shared `build.sbt`/`project/**`,
public core/DSL signatures, gate registry/thresholds, dependency graph, board,
and final acceptance. A separately assigned owner may change these files while
the coordinator prevents competing edits. A worker gets a feature module/specific
files and tests. A reviewer checks a different diff in a fresh context; authors
do not review their own implementation.

## 1a. Worktree mode (owner decision 2026-10-12: aggressive parallelism, at least 8 lanes)

The owner explicitly authorized 8+ parallel lanes through **git worktrees**
(instead of snapshot copies). Worktree mode is primary; snapshot mode in §2 is
the fallback when Git is unavailable.

- Preparation: coordinator commits a clean tree to `main`; for each lane:
  `git worktree add <WT-ROOT>/raider-wt-<N> -b lane/wXX-r<N> main`. Each
  worktree has its own `.git` (a pointer file), its own `target/`, and a
  separate branch. Copy `artifacts/tools/m2` into the worktree (untracked;
  the bootstrap-SSL environment is pre-existing).
- Worker works ONLY in its own worktree/branch. Committing to its OWN branch is
  allowed and required (deliverable = branch + RECORD.md); pushing is
  prohibited; `main` and shared files (`build.sbt`/`project/**`, board,
  `docs/tasks/**`, `docs/adr/**`, `scripts/**`, `.gitignore`) belong to the
  coordinator only.
- In worktrees, gates use git mode (`make quality-changed` without
  QUALITY_BASE_MANIFEST classifies changes vs branch HEAD). No sbtlock is
  needed (isolated `target/`); the shared coursier cache is concurrency-safe.
- Integration: coordinator merges lane branches into `main` sequentially
  (`git merge --no-ff`), resolves conflicts, and runs full gates on main after
  each merge batch. Green lane ≠ final acceptance: main gates are required.
- REVIEW lane occupies one worktree slot (read-only, verdict file).
- Limit (owner decision 2026-10-16): the primary coordinator session plus up
  to 20 spawned Luna agents may be active, but concurrent heavy lanes that
  run sbt/full gates stay capped at 8–10 (empirically stable on 18 cores /
  128 GB; gate timeouts are load-calibrated). Additional capacity goes to
  light lanes, immediate per-lane reviewers, and queued heavy lanes that
  start as build slots free; it does not lift the build cap or invent ready
  cards.

## 1b. Visible spawn agents (owner decision 2026-10-12 — primary execution mode)

Work runs as **visible spawned subagents in the owner's interactive session**,
not as background processes. The owner opens OpenCode, enters `/raider-wave`
(or `/raider-wave <IDs>`), and sees each lane as an agent in the TUI in real time.

- Coordinator is the primary session with the owner. Workers are `raider-worker`
  subagents and the reviewer is `raider-reviewer` (`.opencode/agents/*.md`);
  dispatch is `.opencode/command/raider-wave.md`. Spawn using the task tool.
  Worker/reviewer routing uses the configured Luna agent pool (owner decision
  2026-10-16, replacing the earlier worker provider routing). Record routing
  in the board when it deviates from the default.
- Isolation in §1a is not weakened: the worker receives an ABSOLUTE worktree
  path + branch in the assignment packet and directs every tool call there.
  Default worktree is `wt/<card-id>` inside the repository (gitignored), so the
  spawned agent remains within project dir and its permissions; external
  `<WT-ROOT>` is allowed only if the owner explicitly configures external_directory allow.
- A child session is not itself a worktree — isolation comes from the worktree
  + branch + absolute paths, not the fact of spawning. All §1a rules (branch
  per lane, no worker push/merge, sequential coordinator merge, main gates after
  a batch) remain in force.
- Prohibited by default: executing cards in background bash loops using
  `opencode run`/`nohup` (night loop in docs/prompts/opencode-1k-continuation.txt).
  Headless night mode requires a separate explicit owner decision, recorded in
  the board with a reason; it is not used by default.
- Lane report = subagent's final message (changed paths, commands/exits,
  evidence, risks) in the agent deliverable format; coordinator integrates and
  updates records/board. Do not require the owner to read logs to learn lane
  status: it is visible in the TUI and coordinator report.
- Fallback, in order: (1) task tool unavailable in the installed version, or a
  provider/pool failure kills spawns mid-work (wave-3 and RAI-121 incidents)
  → workers execute sequentially in the primary session, review in parallel
  when the pool recovers, preserving uncommitted lane work; (2) separate
  explicit owner decision for the night loop. Record the fallback reason in
  the board. Do not invent API fields (`cwd` on task, etc.).

## 2. Prove isolation first

Before starting writers, record Git state, installed OpenCode version,
available task/subagent tools, cwd, and baseline fingerprint. At the time of
the audit, the repository was unborn and all sources untracked; a normal
worktree from HEAD was not possible.

If a suitable immutable commit exists and Git is writable: create a separate
worktree/branch per lane. Do not hide dirty user files with stash/reset or
include them in an arbitrary initial commit. If the needed baseline includes
uncommitted work, use the snapshot path.

Fallback without commits or `.git` write access: create an immutable source
snapshot and a separate lane copy in an allowed scratch directory. The manifest
includes all selected source/config/docs/scripts, including untracked files,
with relative paths/modes/hashes/symlink targets. Exclude `.git`, `.env*`/credentials,
runtime state, outputs/caches, and raw evidence; explicitly allowlist config
examples. Do not hardlink mutable source. A symlink outside the snapshot must
not become a way to read secrets; listed immutable toolchain artifacts may be
used separately read-only.

A snapshot copy without `.git` is a fully supported lane mode. Worker records
`Git unavailable: isolated snapshot`, checks the root-provided base manifest
and cwd; missing Git does not force a return to the shared checkout. RAI-004
must support diffing before/after inventories (path/hash/mode/symlink/add/delete;
rename may be delete+add), for example through the planned `QUALITY_BASE_MANIFEST`.
Until this mode is implemented, use explicit scoped bootstrap commands with
the same manifest. Manifest mode checks base/current hashes, active path
classification, and at least active fast when nothing has changed; it does not
skip checks. Implement and test the flag before using it in reports.

Worktree/copy path is absolute and passed to every tool call. Before work,
worker proves its cwd and baseline; coordinator confirms output/build directories
differ. An OpenCode child session alone does not create a worktree. The primary
lane binding mode is a visible spawned subagent (§1b): isolation comes from the
worktree + branch + absolute paths in the packet. A separate OpenCode process
with `--dir <lane>` is a last-resort fallback (only if task tool is unavailable,
with reason recorded in the board); do not invent a task API `cwd` parameter
if the installed version lacks one.

OpenCode 1.18.34 was confirmed in the audited environment by `--version`;
`run --help` supports `--dir`, `--agent`, `--file`. Recheck the version before
new runs. Command/agent schemas may differ across versions. Docs: [CLI](https://opencode.ai/docs/cli/),
[agents](https://opencode.ai/docs/agents/).

If isolation is temporarily unavailable, writers work sequentially while
research/review remain parallel; record the reason in the board. Do not call
multiple agents writing the root checkout at the same time parallel lane work.

## 3. Assignment packet

Coordinator gives each worker:

```text
Assignment / parent card / lane:
Absolute workspace / baseline snapshot hash / source inventory:
Implementation prerequisites / later integration prerequisites:
Frozen public symbols + exact signatures + schema versions:
Allowed files and tests; shared files forbidden:
Normal/error/cancel acceptance IDs:
Required active gate cases / milestone obligations still pending:
Expected deliverable (patch + before/after hashes + evidence):
Heartbeat / bounded task size / escalation condition:
```

Do not pass the entire multi-thousand-line plan in each coding task. Required
invariants relevant to the change go in the packet. A worker must not present
an absent signature as agreed: on discrepancy, return a reproducer and minimal
contract-change request.

## 4. Practical waves

| Wave | Lane A | Lane B | Lane C | Integration condition |
| --- | --- | --- | --- | --- |
| F0 | Spike fixes/reproduction | Read-only contracts/gate review | Acceptance fixtures/design | Truthful exits/evidence; frozen bootstrap decisions |
| Foundation | Build/toolchain owner | Public contracts owner after build skeleton | Gate runner/testkit/minimal CI after interface freeze | Shared compile/contract tests, stage registry |
| F1 | Jobs/scopes/budgets/native loop | OpenAI+Anthropic protocol slices | Filesystem/process tools | One native tool loop through HTTP fixture |
| F2 | DSL/runtime integration | REPL/terminal adapter | Headless CI/reports | One Program works in REPL and CI |
| F3 | Sessions/context/journal | Typed tools/SPI/examples | Packaging/task fixtures | Daily coding workflow from installed package |
| F4 | Fixes for found defects | Independent reviewer/verification | Gateway baseline / optional enriched integration | W01–W17 on one candidate |

The table lists lanes; it does not authorize starting dependent work before
signatures are available. Two provider wires can be split across workers when
slots are available; shared HTTP/SSE framing has one owner. Root or an
exclusively assigned owner changes contracts; then all affected lanes get a
new frozen baseline.

## 5. Worker responsibilities

Check assignment/cwd/source hashes, write a behavioral fixture, implement
narrow behavior, run local gate cases, and prepare the deliverable. Tools/tests
run inside the lane; allocate ports dynamically and use unique temp paths.
`target/`, compiler state, generated files, and reports are not shared. Share
dependency-download caches only with supported concurrency/lock semantics; if
uncertain, use a separate cache or bootstrap first.

Worker does not edit the central board/other lanes; sends status and record to
coordinator. Result includes base hash, add/modify/delete/rename inventory,
patch/binary payload where needed, resulting source hash, test argv/exits, log
hashes, and unresolved findings. Do not copy the whole lane directory over root.

## 6. Integration and conflicts

Coordinator checks paths/secret exclusions/base hashes, reads the diff, gets
independent review, and applies changes sequentially. If a target path changed
since baseline, do a meaningful merge/rebase and rerun affected tests; never
force-overwrite. Preserve user changes.

After each integration batch: shared compile/contracts + affected active gates;
after each wave, a runnable demo from the integrated tree. Lane evidence proves
its baseline; a final milestone requires a new shared run, not a union of old
manifests. For a shared contract change, dependent lanes pause incompatible
edits and refresh snapshots.

If checks are absent, coordinator does not accept “passed by inspection”.
Create a scoped fixture/runner task; until then the obligation is pending. If a
failure cause repeats twice, a fresh reviewer decides on a bounded redesign/reproducer;
the executor does not disable the gate.

## 7. Statuses without a permanent review blocker

Card states: todo → ready → in-progress → review → accepted; with defects,
changes-required; with an external impediment, blocked with a reason. `ready`
means implementation dependencies are actually satisfied; “ready, waiting on
dependency” is invalid. A parent has separate integration acceptance — one
component does not make the parent accepted.

Coordinator schedules a reviewer automatically as soon as the diff is ready.
There is no need to wait for owner confirmation on every local card: the
owner's request already authorizes implementation/review/integration under
this runbook. Owner input is needed for new products/publication/live spend or
changes to mandatory requirements not already authorized by context. Pending
review is not resolved by the author accepting their own work.

Operational board/records describe progress. Product evidence is tied to an
immutable input snapshot; operational files are excluded only by an exact
protected policy allowlist, with their revisions/hashes retained as audit
metadata. Acceptance fixtures, specs, prompts, source/build/gate registry are
not excluded from the fingerprint. Documentation changes are checked separately,
but a new status line alone does not require rebuilding a binary.

## 8. Restart and completion

Before compaction/end of turn, coordinator saves active assignments, lane
paths/baselines, worker session/process IDs, ready queue, last checks, review
verdicts, and next actions. The new turn first checks live processes/current
hashes; it does not duplicate workers or replay product mutations from artifacts.

If workers continue, coordinator monitors and integrates their results during
the available turn; it does not declare completion merely because it spawned
them. On session-limit exhaustion, leave an actionable handoff; with an external
blocker, complete all independent local tasks. Done means [working acceptance](working-product.md),
not “56 cards were mentioned in the report.”
