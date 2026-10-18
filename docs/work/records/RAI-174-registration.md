# RAI-174: work record

Status: done. Date: 2026-10-18. Baseline commit/source: f75dfae.

## Resulting behavior

Registered RAI-174 as the next free Raider card (backlog maximum was RAI-173), with the additive, versioned event/run schema extension, v1 pinning and compatibility requirements, RAI-164 dependency, contracts gate, and RAI-143 precedent.

## Changed paths

- `docs/tasks/m1.md` — added RAI-174 next to RAI-164 with the house card labels and contract.
- `docs/tasks/backlog.json` — added RAI-174 as `todo`; updated the Raider card count.
- `docs/tasks/README.md` — added RAI-174 to the card index.
- `docs/work/records/RAI-174-registration.md` — recorded registration and validation evidence.
- No product API/schema was changed by this registration task.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| New unique next ID | Passed; baseline had 193 cards and maximum RAI ID 173; after registration the validator reports 194 cards. | `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` |
| Markdown/backlog/index consistency | Passed. | `artifacts/plan-review/report.json` |
| RAI-174 registration | Passed; card is in `docs/tasks/m1.md`, backlog status is `todo`, and README index includes it. | The three planning files above |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `python3 -c 'import json; d=json.load(open("docs/tasks/backlog.json")); cards=d["cards"]; print("count",len(cards),"max",max((int(x["id"].split("-")[1]) for x in cards if x["id"].startswith("RAI-") and x["id"].split("-")[1].isdigit()), default=0))'` | 0 | Baseline output: `count 193 max 173` |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` | 0 | `artifacts/plan-review/report.json`; output: `Plan package: passed; 194 cards` |
| `git diff --check` | 0 | No whitespace errors. |

## Limitations / blockers

This was plan registration only. The schema extension, v1 golden, decode/roundtrip fixtures, and contracts-profile execution remain future RAI-174 implementation obligations; RAI-164 completion follows RAI-174. No product/runtime check is claimed.

## Review and next task

No independent review was requested for this coordinator registration. Next task: implement RAI-174 after RAI-164's frozen-boundary reproducer and RAI-143 versioning precedent are considered in its assignment.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
