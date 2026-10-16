# Backlog status reconciliation — 2026-10-16

Status: done. Date: 2026-10-16 UTC. Baseline commit/source: `facbe76` (`HEAD` = `main`). The root checkout contained unrelated pre-existing edits in `.opencode/commands/raider-wave.md`, `docs/parallel-development.md`, `docs/roadmap-12k.md`, `docs/work/board.md`, and untracked `contracts/chat-workflow/`; these were left untouched.

## Resulting behavior

Reconciled 50 backlog cards whose canonical status was `ready` or `todo` to `accepted`, using card-specific main-history merge/review evidence and work-record/board evidence where appropriate. Synced only the `Status:` word in each matching task card. A before/after JSON comparison against `HEAD:docs/tasks/backlog.json` confirmed exactly 50 changed status fields and no changes to any other card metadata.

Accepted IDs: `LIN-01`, `LIN-02`, `LIN-12`, `LIN-13`, `LIN-14`, `LIN-15`, `RAI-108`–`RAI-114`, `RAI-116`, `RAI-119`–`RAI-122`, `RAI-124`, `RAI-126`–`RAI-133`, `RAI-135`, `RAI-140`–`RAI-154`, `RAI-155.a`–`RAI-155.d`, and `RAI-158`–`RAI-160`.

Evidence examples: `74ef73a` (RAI-108), `afe53dc` (RAI-109), `184b483` (RAI-110), `780f555` (RAI-111), `6550800` plus `docs/work/records/RAI-112.md` addendum (RAI-112), `81dad02` (RAI-119), `89888f6` (RAI-127), `d01ac04` (RAI-131), `bd1f94b` and the board's explicit RAI-137 deferral (RAI-135), `b73a076`/`f70a462`/`f9f46b9` (LIN-02/RAI-140–142), `4a095bb`/`619fe25`/`6c69bae` plus `f2765aa`/`d23bc90` integration evidence (RAI-145–147 and phase/delegation follow-through), `23bbc36`/`fee77dd`/`29ad1b4`/`3a4e91d` (RAI-155.a–d), and `e6786b1`/`694fc88`/`facbe76` (RAI-158–160). LIN-01 was accepted based on the reviewed/integrated LIN-01A/B/C campaign: W7 records the graph model and event reader, W8–W9 records the reducer and GraphHash fixes, and the board records integration/review. Together these cover deterministic campaign reduction/hash, typed malformed-event errors, and child-subtree modeling.

## Changed paths

- `docs/tasks/backlog.json` — only 50 `status` values changed.
- `docs/tasks/lineage.md`, `docs/tasks/m0.md`, `docs/tasks/m1.md`, `docs/tasks/m2-p2.md` — corresponding card `Status:` words synchronized.
- `docs/work/records/BACKLOG-STATUS-RECONCILIATION-20261016.md` — this record.

No IDs, titles, dependencies, acceptance text, board, parallel-development guide, roadmap, `.opencode/**`, or `contracts/chat-workflow/` content was changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Per-card status decision | 50 flips supported by card-specific main merge/review records or the coordinator-confirmed accepted record/board evidence; incomplete parent/slice obligations were kept open | `git log main --oneline --merges --grep=<ID>` and `git log main --oneline -i --grep=<ID>` per ready/todo ID; `docs/work/records/`; board read-only |
| Status-field-only reconciliation | passed: 50 status changes, zero non-status metadata changes | before/after JSON comparison against `HEAD:docs/tasks/backlog.json` |
| Markdown mirror consistency | passed: all 50 accepted IDs have matching markdown sections and accepted status | `docs/tasks/{lineage,m0,m1,m2-p2}.md`; validator |
| LIN-01 coverage | accepted: reviewed LIN-01A/B/C and GraphHash evidence covers the complete card criteria | `docs/work/records/W7-INTEGRATION.md`, `docs/work/records/W8-W9-INTEGRATION.md`, `docs/work/board.md` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| Ready/todo enumeration with `jq` | 0 | Ran against `docs/tasks/backlog.json` before edits |
| Per-ID `git log main --oneline --merges --grep="$id"` and `git log main --oneline -i --grep="$id"` | 0 | Reviewed output for all enumerated IDs |
| `git diff --check` | 0 | No whitespace errors |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` | 0 | Passed; 180 cards |

## Limitations / blockers

Open/conditional cards were not accepted based only on mention or a child-slice merge: `RAI-134`, `RAI-136`, `RAI-137`, `RAI-156`, `RAI-157`, `RAI-123`, `RAI-155`, `RAI-155.e`, `RAI-155.f`, `RAI-117`, `LIN-03`, `LIN-04`, and `LIN-06` remain open. The RAI-135 perf half remains deferred to RAI-137. No commit was created.

## Review and next task

This is coordinator-authorized status hygiene based on accepted `main` history; no new product implementation or API claim is made. Remaining statuses needing work/review stay available per the board and their dependencies.
