# ROADMAP-SDK: Embedding SDK in existing Raider phases

Date: 2026-10-12. Type: coordinator planning card.
Baseline: `4b46dc7`. Basis: owner request to study the project, clarify
priorities, fit the work into the current roadmap, and commit the plan changes.
Owner answers: Raider + SDK, first scenario is CI; do not stop the current wave;
the project is fully open source. Team composition, participants, and dates are not yet defined.

## Result

The roadmap/backlog/entry prompt are aligned: the embedding SDK receives scope and
observable acceptance within P0/P4/P7/P10, and user pilots within P0/P7/P9.
The nearest ready card is RAI-115, an audit of the SDK boundary. The current wave
continues. Detailed user documentation is deferred at the owner's request.

## Allowed paths and ownership

The coordinator is the sole editor of `docs/roadmap-12k.md`, `docs/PLAN.md`,
`docs/prompts/opencode-12k-parallel.txt`, `docs/tasks/backlog.json`,
`docs/tasks/m1.md`, `docs/tasks/README.md`, this card, `docs/work/board.md`,
and the final `docs/work/records/ROADMAP-SDK.md`. An independent spawned reviewer
reads the source material and final diff; there are no mutable workers/builds.
One planning task does not create eight artificial implementation lanes;
the isolation and parallel execution rules for product cards remain in force.

## Acceptance

- Normal: SDK-01–05 are mapped to existing phases, RAI-115 is registered
  synchronously in JSON/Markdown/index; the next ADR and dependencies are specified.
- Error: the plan does not claim the SDK is published, demand is proven, or replay
  of arbitrary Scala effects is implemented; no new runtime/gates/public API.
- Concurrency: existing card IDs/acceptance and active wave are preserved;
  the audit can proceed independently; contracts/build/board have a single owner.
- Budget: top-level phases/budgets are not increased; incremental effort estimates and
  reallocation of remaining work are required before implementation; existing cases are not removed.

## Checks and non-goals

`python3 scripts/plan/validate.py`, `make quality-changed`, then
`make quality-changed QUALITY_EXECUTE=1`, docs profile, and independent review.
Create the work record after execution; if the runner includes records in the product
fingerprint, repeat affected checks after writing the record. Do not change
gate policy to exclude operational files.

Non-goals: SDK/RAI-115 implementation, rewriting README/API guides, external
publication, messages to potential users, paid calls, a new ADR for public signatures
without a separate card, and changes to the neighboring Yabanin repository.
