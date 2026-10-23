# ROADMAP-CROSS-ECOSYSTEM: competitive research and executable plan

Date: 2026-10-23. Baseline: `a351d3cc9439ab9868e7e8f75cf59b40da7010af`.
Coordinator-owned planning card. Owner request: study LLM4S and LangChain4j
in depth and update the plan executed by OpenCode to compete beyond Scala,
including evaluating a powerful, convenient DSL independent of that ecosystem.

## Result required

Sourced competitor analysis, a bounded cross-ecosystem product proposal,
registered dependency-ordered cards with observable acceptance, and updated
canonical roadmap/prompt/handoff. Separate current implementation, design
direction, ADR decisions still owed, and actual user-demand evidence.

## Allowed paths and ownership

The coordinator alone writes this card, `docs/research/llm4s-langchain4j-2026-10-23.md`,
`docs/cross-ecosystem-plan.md`, `docs/roadmap-12k.md`, `docs/PLAN.md`,
`docs/handoff-opencode.md`, `docs/prompts/opencode-12k-parallel.txt`,
`docs/prompts/README.md`, `docs/tasks/backlog.json`, `docs/tasks/m1.md`,
`docs/tasks/README.md`, `docs/work/board.md`, and
`docs/work/records/ROADMAP-CROSS-ECOSYSTEM.md`.
Visible research/review subagents are read-only; no concurrent mutable builds.

## Acceptance

- Primary-source facts include URLs and observation date; current docs, tagged
  releases, experimental APIs, and future roadmap claims stay distinguishable.
- Cover typed inputs/outputs, composition, provider capabilities, tools,
  streaming/cancellation, persistence versus effect recovery, integration,
  packaging, and adoption. Map recommendations onto actual Raider code.
- Give non-Scala consumers a staged path without introducing a second runtime,
  silently changing Scala/ZIO implementation policy, or treating a new DSL
  grammar as already selected. Public contracts require separate ADR cards.
- Preserve active CI/TUI work, accepted results, protected gates, local-first
  execution, MIT policy, and P11 gateway ordering. New scope has estimates and
  stop/continue criteria; it is not charged to an invented remaining budget.
- Validate the final plan, run both mandatory quality-changed commands and the
  docs profile, obtain independent review, and record actual evidence.

## Non-goals

Runtime implementation, new dependencies/SDK packages, public contract changes,
protected verifier changes, publishing, live spend, outreach, and edits to
neighboring repositories. The result authorizes planning and the registered
work under its prerequisites, not claims of implemented capabilities.
