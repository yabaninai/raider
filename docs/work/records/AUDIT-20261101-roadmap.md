# AUDIT-20261101-roadmap: plan and backlog audit

Status: partial (roadmap audit completed; no plan/backlog edits in this lane). Date: 2026-11-01 UTC. Baseline commit/source: assigned product baseline `04f74780891e0e7032bf98b3d1dccac279f32354`; inspected worktree `audit/20261101-roadmap` at `935cc2ff0e3498ff1080aad4d53e9b6ca157a405` before this record.

## Resulting behavior

No product or planning behavior changed. This record reconciles the dated plan/handoff with the current backlog, board, source tree, lane records, and locally available Git refs. It distinguishes accepted scope, lane completion, and integrated acceptance.

At inspection, `docs/tasks/backlog.json` contained 274 cards: 208 `accepted`, 55 `todo`, 9 `ready`, 1 `in-progress`, and 1 `blocked`. These are raw card statuses, not readiness percentages. The nine ready cards are exactly RAI-246..254; RAI-171 is in progress; RAI-206 is blocked on demand. The 55 todo entries include the original RAI-001..053 and three YB-RAI cards, so the count is not a count of missing current product features: old cards coexist with later accepted slices. The task index says the original IDs are preserved and status is canonical in backlog/board (`docs/tasks/README.md:7`), but offers no old-ID-to-accepted-slice crosswalk. Do not restart RAI-001..053 from this count without checking their acceptance mappings and W01–W18 evidence.

Anchor product progress to the assigned product baseline `04f74780891e0e7032bf98b3d1dccac279f32354`, not the audit worktree HEAD. The coordinator confirmed the server remote tip by `ls-remote` as `ed1ef4f72b1771500a1367f856c49065fbbdb567`; `git rev-list origin/main...04f7478` is `0 73` (73 product commits beyond that remote tip). The `74 0` count from this audit worktree (`git rev-list --left-right --count HEAD...origin/main`) includes the audit-scope registration commit `935cc2f`; that registration is not product progress. At product baseline, RAI-246..253 are merged on local main; RAI-254 remains unmerged on its lane branch. Do not attribute RAI-254's lane implementation or record to the product baseline.

## Findings and source evidence

1. **The launch handoff is stale and directly contradicts current cards.** `docs/handoff-opencode.md:19-20` says to start “new ready RAI-196 and RAI-204”; `docs/tasks/README.md:290-301` and the backlog mark RAI-196/204 accepted, alongside RAI-197..205. The handoff also says First CI 186/195/187 and TUI-X 188..192 “obligations remain” (`handoff-opencode.md:34-37`), while the task index lists these accepted (`tasks/README.md:263-276`). Replace these instructions with an as-of-current-checkout pointer to the board and a concrete integrated-review action; do not reopen accepted cards.

2. **The board stops before the latest registered wave.** The latest integration entry is wave F, RAI-232..245 (`docs/work/board.md:1508+`, heading at 1529); it says RAI-246..254 were registered `ready` at `37d401d` (`board.md:1525`) and wave G was authorized for unattended continuation (`board.md:1530`). At product baseline `04f7478`, RAI-246..253 are merged on local main, while RAI-254 remains unmerged on its lane branch. The baseline board has no later wave-G integration result, and backlog still reports all nine as `ready`. This is a **merged-but-status-ready / integrated-status gap** for RAI-246..253; RAI-254 is an outstanding lane result, not merged product work. The RAI-254 branch has a work record, but its lane evidence is not integrated-candidate evidence. RAI-253 and RAI-254 modify protected acceptance pins/goldens under their own cards, so verify exact allowed paths, review verdicts, ordering, and integrated corpus/gate evidence before status changes. The board itself says integration gates bind to the merged candidate and lists RAI-254 after RAI-253.

3. **Historical roadmap language can misdirect workers.** `docs/roadmap-12k.md:25-44` retains the Oct. 10 sequencing note that P-LIN, P1 remnants RAI-059/060, P3 RAI-063, and TUI remnants remain open. The same file warns at lines 8-10 that accepted work is not reopened and historical “next” instructions are not current. Current board/backlog must be checked before taking any of those as runnable work. Similarly, `docs/PLAN.md:10-16` correctly says RAI-196..206 were registered, but its baseline `a351d3c` and status snapshot do not cover later progress. Add one clearly dated current snapshot and an explicit “historical estimates/sequence, not current card state” label instead of editing old card acceptance based on inference.

4. **Readiness dimensions are rightly distinct but not yet summarized for the current source.** `working-product.md:13-24, 44` separates `engineering_ready`, per-profile `live_verified`, and `opencode_comparison`; live profiles require their own scenario evidence. The index explicitly says RAI-184's OpenAI-compatible demo-stand matrix is accepted while the Anthropic leg is pending (`tasks/README.md:263`), and RAI-171 remains in progress in backlog. Keep the offline implementation work moving, but report Anthropic/live model verification as pending until an authorized configured endpoint and scoped evidence exist. Do not infer live readiness from RAI-184's accepted card.

5. **Plan claims and installed product path must remain scoped.** `README.md:10-18, 28-48` describes a usable headless JVM package and a source-checkout Scala REPL/tab shell; the same section says the package excludes REPL compiler/terminal dependencies and that tabs currently do not launch agent work. The roadmap’s “useful product” target remains W01–W18, including actual task fixtures, clean install, and one-candidate acceptance (`working-product.md:26-44, 109`). A successful local release or acceptance of cross-ecosystem offline corpus RAI-205 does not by itself establish all W-items, external consumer demand, live provider quality, or OpenCode comparison.

## Prioritized correction and handoff

For the active OpenCode coordinator:

1. Read the actual active worktrees/branches and inspect the nine RAI-246..254 records, commits, allowed paths, and independent reviews. Keep their state `ready` until the coordinator has reconciled each lane result with the integrated candidate; then add a wave-G board entry that records merge order, review/fixups, exact integrated gate commands/manifests, failures and reruns, and card-by-card disposition. Do not treat presence of a commit or a lane “done” record as integrated acceptance.
2. Run the required integrated-candidate checks after the current wave-G merge set, including current classification/execution gates and the union of semantically required profiles; run plan validation for the status/board reconciliation. Evidence must bind to the final candidate fingerprint. RAI-253 pin movement precedes RAI-254 corpus growth; confirm the final goldens and consumer corpus against that candidate.
3. Only after that review, reconcile backlog status, card Markdown, task index, and board together for RAI-246..254. Preserve RAI-206 as demand-blocked and RAI-171 as live-verification work in progress. Correct the handoff to say RAI-196..205 and First CI/TUI scopes are already accepted, then point to the current board and the next unaccepted, dependency-ready obligation.
4. Build the next roadmap around acceptance evidence, not card count. First reconcile each W01–W18 item against source, current acceptance manifests, and verified local/PTY/CI fixtures; record explicit current blockers and pending profiles. Continue engineering/offline gaps (including remaining P1/P3/TUI and full W gates) in narrow cards while external endpoint or user-demand items remain pending. Schedule live provider and OpenCode comparison only under their separately authorized mode/budget. Do not use old “start at 001” instructions or the 13,100-hour envelope as current remaining work.
5. Add a reviewed legacy-ID crosswalk (or an explicit retired/superseded classification) for original 001–053 so raw `todo` counts cannot be mistaken for unfinished current product scope. Preserve their acceptance criteria and map each only where evidence supports the mapping.

The immediate next action is therefore **wave-G integrated review and status/board reconciliation**, not starting RAI-196/204 or repeating accepted lineage/export/distribution work. Once reconciled, the next implementation assignment should be selected from the current board by explicit dependency and W-item gap evidence. No new card is asserted ready by this audit.

## Changed paths

- `docs/work/records/AUDIT-20261101-roadmap.md` — this audit record only.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Roadmap/backlog status inventory | Inspected: 274 cards; status counts above; 246..254 remain `ready`, 171 `in-progress`, 206 `blocked`. | `docs/tasks/backlog.json`; JSON parsed with Python 3. |
| Current board and wave history | Inspected through wave-F integration and wave-G registration/authorization; no wave-G integration entry found. | `docs/work/board.md:1508-1530`. |
| Plan/handoff consistency | Contradictions recorded with exact line references. | `docs/PLAN.md:10-16`; `docs/handoff-opencode.md:19-45`; `docs/tasks/README.md:263-302`. |
| Product baseline/remote relation | Product baseline `04f7478` is 73 commits beyond the coordinator-confirmed server `origin/main` tip; the audit worktree's 74th commit is audit-scope registration, not product progress. | Coordinator-confirmed `ls-remote` tip `ed1ef4f72b1771500a1367f856c49065fbbdb567`; `git rev-list origin/main...04f7478` → `0 73` (coordinator-provided). |
| Audit worktree/remote relation | Audit worktree HEAD is 74 commits beyond its locally cached `origin/main`; this count includes audit scope registration and is not a product-progress count. | `git rev-list --left-right --count HEAD...origin/main` → `74 0`; `git merge-base HEAD origin/main` → `ed1ef4f72b1771500a1367f856c49065fbbdb567`. |
| Product readiness dimensions | Inspected definitions and README claims; no current integrated gate was run in this audit. | `docs/working-product.md:13-44,109`; `README.md:10-48`. |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` | 0 | Clean before writing this record. |
| `git branch --show-current` | 0 | `audit/20261101-roadmap`. |
| `git rev-parse HEAD` | 0 | `935cc2ff0e3498ff1080aad4d53e9b6ca157a405` before record. |
| `git show-ref --verify --quiet refs/remotes/origin/main` | 0 | Locally cached ref exists; no network fetch. |
| `git merge-base HEAD refs/remotes/origin/main` | 0 | `ed1ef4f72b1771500a1367f856c49065fbbdb567`. |
| `git rev-list --left-right --count HEAD...refs/remotes/origin/main` | 0 | `74 0`. |
| `git log --oneline --decorate --all -15` | 0 | Inspected lane/worktree history; RAI-254 branch work is not merged product baseline. |
| `python3` JSON inventory of `docs/tasks/backlog.json` | 0 | 274 cards; status counts reported above. |
| `cat` required docs/card/template, `sed` board/plan/index, `rg` IDs/status/source, and lane-record reads | 0 | Read-only inspection; no build or paid call. |
| Initial exploratory Python inventory using the wrong root key (`tasks` instead of canonical `cards`) | 1 | Corrected immediately; no files changed. |

## Limitations / blockers

- No build, quality gate, plan validator, paid call, fetch, push, or contact was performed in this read-only audit lane. All gate claims above are cited historical/lane evidence, not newly executed results.
- I did not independently replay every worktree diff or validate all wave-G manifests against the integrated fingerprint. The board and status update therefore remain pending coordinator review.
- The server remote confirmation and `origin/main...04f7478` count were supplied by the coordinator after the initial record. I did not independently run `ls-remote` in this lane.
- Source readiness was spot-checked through README and current feature paths, not a complete W01–W18 acceptance matrix. Parent audit lanes own runtime/provider/REPL/CI/security details.

## Review and next task

Independent review: pending parent coordinator review. Next action: coordinator performs wave-G integrated review for merged RAI-246..253; then reviews the separate RAI-254 lane and merges only after its review, ordering, and integrated gates are satisfied. Update board/status/handoff only with evidence. No product task is selected by this record.
