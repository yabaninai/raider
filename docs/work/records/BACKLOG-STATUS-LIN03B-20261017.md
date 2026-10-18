# BACKLOG-STATUS-LIN03B-20261017: work record

Status: done. Date: 2026-10-17 UTC. Baseline commit/source: `149f07d` on `main`, clean at start.

## Resulting behavior

Reconciled the requested accepted/ready card statuses from main merge commits and per-card records. Registered RAI-173 / LIN-03.b for snapshot-reader protocol authorization across ReplayCli, HeadlessRunner, and live export, including concurrent torn-read proof, LIN-14 alignment, and pinned-compiler validation authorization. The new card is ready for parallel-safe reader work after LIN-03 fold wiring merges.

## Changed paths

- `docs/tasks/backlog.json` — synchronized the requested statuses, updated the Raider card count, and registered RAI-173.
- `docs/tasks/m0.md` — synchronized RAI-161 status.
- `docs/tasks/m1.md` — synchronized RAI-117, RAI-134, RAI-137, RAI-163, and RAI-164 statuses.
- `docs/tasks/lineage.md` — synchronized RAI-155.e, RAI-155.f, RAI-156, and RAI-162 statuses; added RAI-173 card.
- `docs/tasks/README.md` — indexed RAI-173.
- `docs/work/records/BACKLOG-STATUS-LIN03B-20261017.md` — this execution record.

No product code, build, contracts, or gate policy changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Status evidence | Verified each requested accepted card has a matching main merge commit and work record before updating metadata. | `4545b5b` (RAI-117); `e850216` (RAI-134); `0870045` (RAI-156); `ee2186f` (RAI-161); `d7318f9` (RAI-137); `b3dda3f` (RAI-162); `d7dbd4d` (RAI-163); `37911dc` (RAI-155.e). Corresponding records are `docs/work/records/RAI-<ID>.md` and `RAI-155e.md` for the child card.
| New card registration | RAI-173 is the unique next RAI ID; backlog count is 193 and card status is ready. | `docs/tasks/backlog.json`, `docs/tasks/lineage.md`, and `docs/tasks/README.md`.
| Backlog validator | Passed with 193 cards. | `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` (exit 0).

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short && git branch --show-current && git rev-parse --short HEAD` | 0 | Initial status clean; branch `main`; baseline `149f07d`. |
| `git log --oneline --merges main --grep=<card-id>` for each of the eight accepted cards | 0 | Merge receipts recorded in acceptance evidence above. |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` | 0 | Plan package passed; 193 cards. |
| `git diff --check` | 0 | No whitespace errors. |

## Limitations / blockers

No product or runtime checks were required for this documentation-only coordinator update. RAI-173 reader implementation must wait for LIN-03 fold wiring to merge; its pinned-compiler mechanism must be authorized and exercised, not replaced with a mock.

## Review and next task

No independent review was requested for this coordinator registration task. Next available card is RAI-173 after the LIN-03 fold wiring merge; RAI-155.f and RAI-164 are also ready based on accepted dependencies.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to a new product source tree.
