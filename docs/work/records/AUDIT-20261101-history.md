# AUDIT-20261101 history lane: remote and integration history

Status: done. Date: 2026-11-01 (UTC). Baseline commit/source: `04f74780891e0e7032bf98b3d1dccac279f32354` (`main`). Inspected in `wt/audit-20261101-history`, branch `audit/20261101-history`; this worktree began clean at `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`.

Coordinator update after this lane inspection: the active OpenCode session merged RAI-254 as `e41cb28` during the audit, making local main 76 commits ahead of server `ed1ef4f`. The unmerged RAI-254 state and 73-count below describe the earlier `04f7478` snapshot. See the coordinator readiness report for the later observation.

## Resulting behavior

This record explains the visible local/remote gap from Git history and repository evidence. The available local `origin/main` ref is `ed1ef4f72b1771500a1367f856c49065fbbdb567`, dated 2026-10-28, and is the ancestor of local `main` (`04f7478`). Local main is 73 commits ahead by reachability: 26 first-parent commits, plus 47 additional lane-side commits in the merged history. The 73 reachable commits add 70 changed paths relative to `origin/main`: 5 product main-source files, 14 test/acceptance files, 32 documentation paths, 10 infrastructure/script/build paths, and 9 evidence paths. The diff totals 23,564 insertions and 7,740 deletions; much of the volume is evidence, records, and the 15,367-line backlog JSON rewrite, so line count is not a product-size measure.

The 73 commits by author date are: Oct 28: 7; Oct 29: 33; Oct 30: 16; Oct 31: 4; Nov 1: 13. The 26 first-parent commits by date are: Oct 29: 16, Oct 30: 1, Nov 1: 9. The first-parent sequence integrates the Oct 29 wave-F batch, then wave-G follow-ups through RAI-253 on Nov 1; the remaining commits in the reachable set are lane history. Thus “remote main last Oct 28” accurately describes the locally recorded remote-tracking ref; it does not describe local `main`. No fetch/push was performed, so this audit cannot establish whether the remote advanced after the inspected ref or why changes were not published. The wave-F board explicitly says “no pushes” for its continuation; that is evidence of the stated workflow constraint, not a complete explanation for the remote's present state.

Recent deliverables, based on diffs and work records rather than titles alone:

- **Wave F, integrated on Oct 29:** RAI-232 changed Outbound receipt draining/waiter wake coordination with strand/death tests; RAI-233 changed Session observe/live-pump coordination and added cross-ecosystem probes; RAI-234 documented and boundedly investigated an AgentLoop arming gap; RAI-235 moved MultiJobExport segment registration after guards; RAI-236 added anchored finalization ceilings. RAI-237/238 hardened acceptance scenario process handling (PID reuse/TOCTOU and EXIT/signal cleanup); RAI-239 added pinned m2 provisioning; RAI-240 added canonical adapter-jar repacking; RAI-241 added zinc-poison research/instrumentation; RAI-242 added cancel-mid-response scenarios; RAI-243 corrected a prior record; RAI-244 added a consumer dependency maintenance note; RAI-245 verified a scanner offset. Board records 14/14 independent reviews, with required fixes before merge for RAI-232/234/241, integrated gates, and acceptance of RAI-232..245. [Wave-F board entry](../board.md#2026-10-29--wave-f-integrated-rai-232245-accepted-14-lanes-reviews-1414-three-review-fixups-disclosed).
- **Wave G follow-ups integrated through RAI-253:** RAI-246 added a test-only deterministic Outbound race probe; RAI-247 added a test seam and uninterruptible plan-emission-to-arming span; RAI-248 recorded observe semantics as at-least-once with consumer deduplication and no exactly-once claim; RAI-249 localized the zinc poison carrier; RAI-250 documented the E008 lane hygiene protocol; RAI-251 wired the reproducible jar script into Make; RAI-252 replaced package exports with explicit aliases to stabilize jar bytes; RAI-253 re-pinned both cross-ecosystem goldens to `e629ab0b…` after two clean identical builds and six loaded corpus passes. Their lane records provide evidence, but the task backlog still lists RAI-246..253 as `ready`; the inspected board has no wave-G acceptance/integration entry that flips them accepted.
- **RAI-254 is not integrated in local main.** `lane/rai-254` points to `f2dfb29419f14d0c515c4e549405c2db0a56745e`, which is not an ancestor of `main`. Its diff from the baseline changes `run-all.sh` and `golden-checks.json` plus adds its work record. The lane record reports wiring two cancel scenarios, 22 corpus passes per run and checker 176/0 across six runs, two concurrent driver passes, and a bounded missing-stand failure. It also discloses a cold Scala 3.9.0 `raiderCli/Test` compile crash under Homebrew JDK 27; its completed quality gate passed under Oracle JDK 21. The record says review is pending. The backlog still says RAI-254 `ready`, and the coordinator has not integrated the branch in this inspected history. `git show lane/rai-254:docs/work/records/RAI-254.md` is available in its worktree; the commit also contains the same record.

The key status mismatch is therefore not “no recent work”: local main contains substantive runtime, test, and acceptance changes, while the latest remote-tracking ref does not. A second mismatch is operational: RAI-246..253 have landed on local main with lane records, but task statuses remain ready and no wave-G acceptance summary is recorded; RAI-254 is additionally a completed-looking lane record awaiting review/integration, yet its card also remains ready. Lane green evidence is not final integrated-candidate acceptance under the contributor rules.

## Changed paths

- `docs/work/records/AUDIT-20261101-history.md` — this audit record only.

No product paths or public APIs changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| History counts | `origin/main..main` = 73 commits; `--first-parent` = 26; origin ref is an ancestor; `main` is exactly baseline `04f7478` | Git commands below; local refs only |
| Change classification | 70 paths: 5 main source, 14 acceptance/test, 32 docs, 10 infra, 9 evidence; 23,564 insertions / 7,740 deletions | `git diff --name-only`, `git diff --numstat`, and `git diff --stat origin/main..main` |
| RAI-254 merge state | `git merge-base --is-ancestor lane/rai-254 main` returns 1; branch tip `f2dfb29` not integrated | `git log main..lane/rai-254`, `git diff main...lane/rai-254`; branch record |
| Wave-G task status | RAI-246..254 all remain `ready` in backlog | `docs/tasks/backlog.json`; README lists the same cards ready |
| Fresh quality/build checks | Not run, per audit assignment | This is a history audit, not product acceptance evidence |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short --branch` | 0 | Clean at inspection start; only audit branch shown |
| `git rev-list --count origin/main..main` | 0 | `73` |
| `git rev-list --first-parent --count origin/main..main` | 0 | `26` |
| `git merge-base --is-ancestor origin/main main` | 0 | Origin ref is ancestor |
| `git rev-list --count 04f7478..main` | 0 | `0` |
| `git diff --stat origin/main..main` | 0 | 70 paths; 23,564 insertions / 7,740 deletions |
| `git merge-base --is-ancestor lane/rai-254 main` | 1 | Expected: RAI-254 branch is unmerged |
| `git diff --stat main...lane/rai-254` | 0 | 3 paths; 97 insertions / 4 deletions |
| `git log --date=short --format='%ad %h %s' --reverse origin/main..main` with date aggregation | 0 | Commit author-date counts listed above |
| `python3 -c` reading `docs/tasks/backlog.json` cards RAI-246..254 | 0 | All nine statuses are `ready` |
| `git show lane/rai-254:docs/work/records/RAI-254.md` | 0 | Lane evidence reviewed; record absent from local main |

No remote fetch, build, quality gate, paid call, or push was performed. The recorded `origin/main` is the ref available at audit start, not a freshly fetched server state.

## Limitations / blockers

The audit depends on local Git refs and checked-in records; it does not prove the current server-side `main` tip. It does not independently reproduce lane tests, assess all acceptance gates, or establish final readiness. Some lane artifacts are gitignored/outside the repository and were not re-opened; claims above are attributed to checked-in work records. Date totals use commit author dates, and include merge commits and lane commits; first-parent totals isolate the main integration sequence.

## Review and next task

Coordinator handoff: review and integrate/reject `lane/rai-254` on its recorded evidence; then perform integrated candidate gates on local main, update the board/backlog so lane completion and acceptance status are explicit, and reconcile/publish remote main only under the coordinator's existing authorization and workflow. Also resolve the JDK 27 cold-compile limitation for clean worktrees or ensure checks explicitly pin the supported JDK 21 runtime. Next available card is the coordinator's wave-G integration/acceptance step; no product card status should be inferred from this audit alone.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind old manifests to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
