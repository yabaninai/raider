# ROADMAP-SDK-RESEARCH: extending the plan after the SDK review

Date: 2026-10-12. Baseline: `3a60f93`. Coordinator planning/research card.
Basis: the owner asked for a thoroughly developed plan, additional research,
and questions. Previous decisions: OSS Raider + SDK, CI first, without stopping
the current wave; detailed user guides later. Clarification from the owner in
this session: the first success is writing a Scala workflow and running it
locally and in CI. Host embedding remains an additional surface. Final answers:
AI-native SDLC DSL for code in `../tokenrouter`; generic local CI, forge
contract/stand first and adapter later; automatic PR/commit/merge,
version/changelog/local tag/artifacts, without pushing release tags,
publishing, or deploying; Scala-configured human checkpoints; experimental
pinned preview before M4; API breaks with migration are acceptable; no durable
resume; trusted local trace data.

## Result

Research into primary sources and actual code; an executable dependency packet;
separation of early feedback from milestone acceptance; and an updated roadmap.
RAI-115 is being executed separately by a visible worker in a worktree; this
result uses its reviewed audit and does not substitute acceptance with code or
mockups.

## Allowed paths

Coordinator: `docs/research/sdk-roadmap-research-2026-10-12.md`,
`docs/roadmap-12k.md`, `docs/PLAN.md`,
`docs/prompts/opencode-12k-parallel.txt`, `docs/tasks/backlog.json`,
`docs/tasks/m1.md`, `docs/tasks/README.md`, this card, `docs/work/board.md`,
`docs/work/records/ROADMAP-SDK-RESEARCH.md`, and
`docs/work/records/RAI-115.md`. The RAI-115 worker is the sole owner of
`docs/research/sdk-embedding-audit.md` in its branch; the coordinator merges
it after review. Other spawn lanes are read-only: alternatives and
dependency/acceptance review.

## Acceptance

- Competitor facts have primary URLs and dates; distinguish current docs,
  design, and releases; do not treat lack of a mention as proof a capability is
  absent.
- Source references include baseline/symbol references; architectural
  proposals do not become accepted APIs without a separate ADR card.
- Local/CI workflow is the first user path; shared SDK cases remain. Packages
  for implementation have concrete outcomes, prerequisites, and boundary
  owners.
- Do not weaken the current wave, gates, or license policy; additional hours
  are estimates, remaining hours stay unknown until reconciliation, and new
  owner decisions are recorded explicitly.
- `make quality-changed`, execute, and the plan validator pass on the final
  tree; independent review, work records, and final receipt refer to the
  verified inputs.

## Non-goals

Product implementation, changes to frozen APIs/registry/verifiers, publishing,
paid calls, contacting third parties, and new monetization. Committing the
planning changes continues previously authorized work; public publishing is a
separate action.
