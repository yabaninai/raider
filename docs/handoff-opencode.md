# Continue Raider development through OpenCode

## Current request

The 2026-10-23 owner revision adds a competitive path beyond Scala based on
[LLM4S/LangChain4j research](research/llm4s-langchain4j-2026-10-23.md).
Use the [cross-ecosystem plan](cross-ecosystem-plan.md),
[roadmap §6e](roadmap-12k.md#6e-cross-ecosystem-workflows), and
[canonical prompt](prompts/opencode-12k-parallel.txt).
The implementation remains Scala 3 + ZIO. Portable workflows and a local
process boundary are proposed; contracts and evidence precede claims.

Open OpenCode in the repository root and provide:

```text
Execute @docs/prompts/opencode-12k-parallel.txt with @docs/cross-ecosystem-plan.md
and @docs/roadmap-12k.md section 6e. First inspect git status, the current board,
active workers/worktrees and actual implementation. Preserve in-flight CI/TUI
work; do not reopen accepted RAI-155/160/193. Start new ready RAI-196 and RAI-204
when their paths are exclusively assigned. Freeze ADRs before portable/protocol
implementation. Continue through narrow cards, visible workers, independent
review and integrated gates. Keep demand/live quality separate from offline checks.
```

Use `/raider-wave` for visible worktree workers under
[parallel-development](parallel-development.md). Respect current capacity and
heavy-build limits there; a spawned session alone is not an isolated checkout.
The coordinator owns shared contracts/build/board and merges sequentially.
Do not start background `opencode run` loops. Fewer ready cards than capacity
means fewer lanes; do not invent work to fill slots.

## Current baseline and next actions

At planning baseline `a351d3c`, runtime, CLI, real REPL, chat export and local
JVM distribution exist. The release is local, not publicly hosted. Current
First CI 186/195/187, live-wire 184 and TUI-X 188..192 obligations remain;
read the board rather than treating this dated snapshot as permanent.

New sequence: 196 architecture/reuse ADR; 197 portable contract; 198 validation
and lowering; 199 CLI parity. In parallel after 196: 200 protocol ADR then 201
adapter, and 202 capability audit/contract. 203 adds portable export/recipes;
204 improves installed onboarding; 205 verifies integrated and external-consumer
results; 206 is a demand-gated Java embedding design spike. Exact dependencies,
paths, gates and exclusions are in [cards](tasks/m1.md) and
[backlog](tasks/backlog.json), not inferred from card numbering.

The proposed DSL starts with a bounded schema-backed data format, not a new
expression grammar. Native Scala remains the full authoring surface. Every
adapter uses the existing runtime/policy/accounting/evidence. No Python or
TypeScript product implementation, daemon, durable-closure recovery or new
paid budget is authorized by the roadmap revision.

## Evidence and handoff

Before completion run `make quality-changed`,
`make quality-changed QUALITY_EXECUTE=1`, and the card's required profiles.
`python3 scripts/plan/validate.py --out artifacts/plan-review/report.json`
validates planning metadata, not runtime behavior. Do not weaken protected
verifiers to make a card pass. A shared candidate needs its own evidence;
lane logs alone do not establish integrated acceptance.

Record independent review, source identity, actual commands/exits/manifests,
remaining obligations and next runnable action. Engineering readiness, live
provider verification, model/framework comparisons, foreign-language usability
and repeat user demand are separate results. Do not publish/contact people or
spend on model comparisons without applicable authorization. Yabanin stays P11;
neighboring repository edits need separate cards.

## Resume

```text
Continue docs/prompts/opencode-12k-parallel.txt with docs/cross-ecosystem-plan.md.
Inspect active workers, worktrees, current source and board; preserve pending work.
Resume the nearest dependency-ready card. Integrate reviewed results and rerun
required checks. Save active lanes, source hashes, evidence and exact next action
if the session ends; do not call incomplete work finished.
```

The older `/raider-finish` and `/raider-build` aliases retain historical finish
obligations. They do not override the current roadmap, cross-ecosystem revision,
visible-worker mechanics or Yabanin-last order.
