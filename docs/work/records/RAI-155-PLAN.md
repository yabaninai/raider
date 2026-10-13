# RAI-155 planning registration

Status: done (planning only). Date: 2026-10-14.
Baseline: 745a45302d2c1f7d97f18d4798d42726bea550f7.

## Result

Registered RAI-155 and six dependent delivery cards for chat capture, editable
Scala generation, local/CI execution, independent verification and a user guide.
The roadmap places this work within P4.C/P7/P10 and retains the current wave.
RAI-116 and RAI-140 acceptance precedes the export contract. No runtime capability
or command was added. The OpenCode prompt describes the complete implementation.

## Changed paths

- docs/tasks/backlog.json, docs/tasks/lineage.md, docs/tasks/README.md
- docs/roadmap-12k.md, docs/PLAN.md, docs/work/board.md
- docs/prompts/opencode-chat-to-workflow.txt
- docs/work/records/RAI-155-PLAN.md

## Checks

- `python3 scripts/plan/validate.py --out artifacts/chat-workflow-plan/plan-review.json`
  exited 0: 175 cards, planning package passed.
- `git diff --check` exited 0.
- A Python Unicode scan of changed planning files and the prompt found no Cyrillic.

Eight visible read-only audit lanes inspected chat persistence, bundle loading,
DSL composition, CI acceptance, lineage, trust boundaries, roadmap placement and
independent review. No builds, runtime tests or paid provider calls were performed.
The validation report is a local ignored artifact; these checks validate the plan,
not the future runtime. Implementation gates remain required by the cards.

## Limits and next step

All new cards remain todo. The next prerequisite is RAI-116 if not yet accepted,
then RAI-155.a after its other prerequisite is accepted. Estimate and phase budget
allocation must be reviewed before implementation. First delivery is a bounded
sequential task export, with no arbitrary conversation conversion, durable resume
or external SCM/release effects.

## Independent review

Visible reviewer `handbook_review` approved the final planning candidate after
one correction: CWF-01/D01/E01 now specify two inputs by two launchers (four fresh
runs with pairwise comparison for equal inputs), plus a fifth unedited control
that isolates the manual edit. Cards, roadmap and prompt use the same matrix.
