# DOCS-HANDBOOK: practical user handbook

Owner request: 2026-10-13. Make the English user documentation easier to follow,
with concrete cases, Scala composition, observable results, and full generated
lineage. Baseline: main e8ec85e. This is documentation work; implementation,
contracts, protected fixtures, build scripts, and gate policy are outside scope.

## Deliverable and scope

A coherent path from setup to offline repair/audit, typed release checks, live
local CI, and lineage inspection. Each case states its purpose, prerequisites,
commands, expected outputs, evidence, and limits. Distinguish scripted examples,
recorded live evidence, and unified offline corpus evidence. Do not claim that
separate headless roots already use the unified exporter.

Allowed paths: README.md, docs/onboarding.md, docs/user-guide*.md,
docs/examples/ci-pipeline/README.md, docs/examples/ci-pipeline/native-projections.md,
this card, docs/work/records/DOCS-HANDBOOK.md. Preserve recorded captures and
protected unified artifacts byte-for-byte. New APIs or fabricated outputs are
not authorized.

## Execution

Coordinator owns README, this card, shared navigation, and integration. Eight
visible Luna lanes use isolated worktrees for writing, source audit, and review.
No new live calls or builds are needed to edit these existing verified cases.
The owner's earlier no-general-checks instruction remains in force for this
documentation-only task. Use source comparison, relative-link/anchor checks,
projection-to-capture comparison, whitespace, and English text inventory.
Report existing evidence with its original scope; do not claim fresh execution.

## Acceptance

A new user can select a case, prepare it, execute the documented command, find
its output, and explain what the result proves. Copyable code uses current
implemented signatures. Every result is labeled as expected, recorded, or
corpus-derived. Include the complete six historical native projections and link
the current complete unified DSL artifact with its distinct evidence scope.
Independent review must check provider/replay constraints and current wiring
follow-ups RAI-153/154 before integration. Merge accepted documentation into
main and publish through a normal push under the standing owner authorization.
