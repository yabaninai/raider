# ROADMAP-12K: Standalone Raider plan and OpenCode prompt

Status: partial (deliverables prepared; repository validation blocked).
Date: 2026-10-05. Type: documentation planning card.
Dependencies: current code, owner requirements, working-product and parallel runbook.

## Result

One coordinated package: 12,000 engineering hours, early tabs/statuses,
convenient plugins, an executable order for independent lanes, Yabanin strictly
last, and a ready-to-use OpenCode prompt. This package does not implement
product features.

## Allowed paths

- `docs/roadmap-12k.md`, `docs/prompts/opencode-12k-parallel.txt`.
- `docs/roadmap-10k.md`, `docs/PLAN.md`, `docs/handoff-opencode.md`,
  `docs/prompts/README.md`, `docs/prompts/MASTER-10K-STATE.md`,
  `docs/prompts/MASTER-10K-PHASE0.md`: links and notices about the new ordering.
- This card, `docs/work/board.md`, `docs/work/records/ROADMAP-12K.md`.

## Acceptance

- Normal: phases and packages total exactly 12,000 h; early milestones are
  verifiable; the prompt links to the canonical plan and specifies a coordinator
  plus up to 3 workers.
- Error: old prompts do not direct the executor to early Yabanin; existing code
  is not described as missing and the plan is not described as implementation.
- Concurrency: root/build/contracts/board are each owned by one person;
  writer isolation and review are mandatory; dependencies and integration order
  are explicit.

## Checks and boundaries

Plan validator; `make quality-changed`, then execute with saved baseline
inventory; independent plan review. Runtime/registry/public API are unchanged.
Unfinished W01–W18 requirements remain; staged acceptance is explicitly
separate. Packages in the new roadmap are planning; the coordinator registers
implementation child cards in the existing backlog before assignment. No new
green gates in this card. Do not edit the user's untracked research file.
