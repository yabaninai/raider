# THROUGHPUT-20261101: execution-plan improvement

Owner request (2026-11-01 follow-up): confirm the plan correction and identify how to accelerate development. Extend the audited plan with a concrete throughput experiment and continuation instructions for the active OpenCode coordinator.

Baseline: root product `e41cb28`; audited documentation `0cdeb69` on `audit/20261101`. Existing uncommitted root audit documents and unrelated `LOCAL-YABANIN-20261028.md` must be preserved. This follow-up uses branch `audit/20261101-throughput` in the existing isolated audit checkout; root OpenCode integration ownership is unchanged.

Allowed paths: `docs/PLAN.md`, `docs/handoff-opencode.md`, this card, and `docs/work/records/THROUGHPUT-20261101*.md`. No product, gate-policy, build, backlog-status, parallel-runbook or budget changes. The concurrency proposal is scheduling below the existing maximum, not a new limit or performance guarantee. Gate consolidation is future separately reviewed work; all currently mandatory commands/checks remain mandatory.

Acceptance: cite measured repository causes of wasted work; give a practical scheduling/review/integration protocol, an outcome-based next target, and a measurable comparison without promised speedup; preserve critical acceptance and authorization boundaries; independent review; `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` for the final documentation, plus planning validation. Write the work record only after actual inspection/execution.
