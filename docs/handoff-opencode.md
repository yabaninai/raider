# Continue Raider development through OpenCode

## Current request and ownership — 2026-11-01

The owner requested a skeptical readiness audit, an account of recent work,
and a corrected plan for the already-running OpenCode session. Read
[the audit](reviews/2026-11-01-readiness.md) and [PLAN](PLAN.md) first.
This handoff is repository content; it is not a claim that a message has been
injected into a running session. Keep the current coordinator and active lanes.

At audit start local `main` was `04f7478`, 73 commits ahead of server-side
`main` at `ed1ef4f` (October 28). During inspection the active session merged
RAI-254 at `e41cb28`: 76 ahead, 27 first-parent commits. The remote was checked
again with `git ls-remote`; no remote `master` branch was advertised. These are
dated observations, not permanent state. The board's October 30 no-push rule
remains in force; do not publish merely to make dates match.

## Resume prompt

```text
Read docs/reviews/2026-11-01-readiness.md, docs/PLAN.md and this handoff.
Continue the current session and preserve its workers/worktrees. Recheck root
status, HEAD, remote main, active gates and the wave-G evidence before acting.
RAI-246..254 were already merged by e41cb28; do not launch them again because
backlog.json still says ready. Reconcile their reviews and exact integrated
candidate results, then update board/backlog/index together. Preserve unrelated
LOCAL-YABANIN-20261028.md and the audit's documentation edits.

Register narrow follow-up cards for the audit's confirmed acceptance gaps.
Start with separate protected-verifier/inventory work and native child-model
propagation. Preserve existing gate thresholds and frozen expectations. Use
Scala stands for deterministic runtime regression tests; no paid calls for
ordinary checks. Add a W01-W17 matrix mapping actual paths/commands/evidence to
remaining obligations. Prioritize the installed user path and one real coding
loop with edits, failed check, correction and passing check over new extensions.
Apply PLAN's two-batch throughput experiment: keep ready lanes visible, queue
heavy JVM jobs (initial trial 2-4 total, one integrated full-suite run), serialize
cold compiles, review each ready lane immediately, and keep one integration
batch at a time. Preserve all mandatory checks. Record accepted scenarios and
build/review/retry time so concurrency can be adjusted from evidence.
Keep live-model quality and publishing separate. Do not restart accepted
RAI-196/204, First CI, TUI or historical prerequisite waves.
```

## Immediate sequence

1. **Close the current integration.** Inspect whether the current coordinator
   already ran gates after `e41cb28`; reuse only evidence whose exact source,
   policy, toolchain, required gate set and logs are valid. If incomplete,
   finish the card-required profile union and the 22-scenario corpus, then
   reconcile statuses. Eight merged wave-G cards plus RAI-254 are not nine
   new implementation assignments.
2. **Repair evidence trust under separate review.** `quality.py verify`
   accepts a zero-gate `fast` manifest; it does not compare the current
   toolchain. The inventory includes gitignored `wt/` copies and excludes
   checked-in `.opencode` prompts. Preserve negative reproductions and
   register separate protected-policy/runner cards before edits. Never
   re-label the audit's artificial negative manifest as a successful run.
3. **Close native coding gaps.** The audit statically confirmed that child
   model requests use `scripted:<agent>` even on a live backend. Assert the
   intended parent/child model on a local stand and fix the actual wiring.
   Scope separate cards for native-coder root token/time/spend limits,
   protected paths/project instructions, and structured multi-file/delete
   tools. Portable launch already has token/deadline enforcement; bundle
   deadlines also exist, so preserve them and do not redo those features.
4. **Make the installed path match the contract.** Candidate JAR `e629ab0b…`
   returns 21 for `--help`. The real Scala REPL is available from a source
   checkout but has no installed REPL launcher. The required `chat.close()`
   golden scenario disagrees with the facade. Register focused packaging,
   help and lifecycle/contract cards; compiler/PTY acceptance is mandatory.
5. **Prove one candidate.** Finish the W01–W17 matrix and actual missing
   cases. The current `quality-full` target is not full working-product
   acceptance. Security currently means a narrow secret scan, and the
   registered license gate runs a self-test. Dependency/SBOM/release gates
   and hosted-platform claims need their own evidence and reviewed policies.
6. **Then assess live usefulness.** Keep direct OpenAI-compatible, direct
   Anthropic-compatible, gateway coding tasks, and model comparison separate.
   RAI-184 is a historical gateway wire smoke with unknown billing, not a
   completed coding scenario. Reconcile its stopped guard/ledger before any
   further authorized live dispatch. RAI-206 remains demand-gated.

The detailed follow-up table in the audit gives suggested narrow scopes and
acceptance. These are planning actions, not pre-accepted or pre-numbered cards.
The product implementation remains Scala 3 + ZIO. Keep native Scala as the full
authoring surface and the portable format bounded by its reviewed contracts.

## Delivery throughput

Apply [PLAN's throughput experiment](PLAN.md#throughput-experiment-for-the-next-two-integration-batches)
for the next two integrated batches. The initial 2–4 heavy JVM jobs are a
measured scheduling choice below the current cap, not a guaranteed optimum.
Keep eight or more visible ready lanes when there is independent work; light
work/review does not require another sbt process. Preserve a build slot for
integrated acceptance and avoid overlapping cold compiles. Do not interrupt
existing workers to impose the trial; apply admission as slots become free.

Keep the next demo concrete: installed help/REPL and a native coding task with
real file effects and failing→passing checks against a deterministic local
Scala stand, including correctly configured child delegation. Live model
quality remains separately scoped. Review promptly, integrate a small coherent
batch, freeze it during required checks, and reconcile its status at handoff.

Gate deduplication/reuse requires a separate protected-runner card and review.
Until then run both mandatory changed commands and every required card profile;
selective development tests alone do not close a card. Defer only nonblocking
research with a recorded reason/workaround. Measure accepted-scenario lead time,
review waits and build/retry costs in existing records; do not promise a speedup
or build another orchestration layer.

## Execution and evidence discipline

Use visible workers and isolated worktrees under
[parallel-development](parallel-development.md). Respect current heavy-build
capacity and preserve a reviewer. This audit used eight light read-only lanes;
it did not start competing implementation builds or a background OpenCode loop.
Until the inventory issue is fixed, account explicitly for worktree changes
when interpreting fingerprints; do not weaken evidence checks to hide them.

Before completion run `make quality-changed`, then
`make quality-changed QUALITY_EXECUTE=1`, and the card's semantically required
profiles. Run `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json`
for planning edits. Planning checks and mock smoke tests are not live quality.
Preserve exact source identity, commands, exits, logs and independent review.
Do not modify protected policy or acceptance fixtures under a product-fix card.

Local release artifacts do not imply hosted publication. Keep the existing
no-push/no-publish/no-new-spend limits. Do not alter Yabanin under a Raider card.
Do not overwrite the other session's source, evidence, board entries or user
files; compare changed paths before applying this documentation handoff.
