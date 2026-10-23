# ROADMAP-CROSS-ECOSYSTEM: work record

Status: done (planning/research only). Date: 2026-10-23.
Baseline: `a351d3cc9439ab9868e7e8f75cf59b40da7010af`; initially clean Git tree.

## Resulting behavior

OpenCode's canonical roadmap, prompt and handoff now include the owner's
cross-ecosystem request. Primary-source LLM4S/LangChain4j research informs a
bounded portable-workflow/process-API proposal, eleven registered cards and
separate engineering/live/user acceptance. Product implementation remains
Scala/ZIO; no new API or release is claimed implemented. Existing CI/TUI work
and accepted chat export/distribution results remain in place.

RAI-196 and RAI-204 are ready; other new cards are todo with explicit
dependencies. RAI-206 additionally requires real JVM embedding demand.
Estimated RAI-196..205 effort is 444–888 engineering hours; optional RAI-206
adds 16–32. New capability implementation gaps need separate estimates/cards.

## Changed paths

- `docs/research/llm4s-langchain4j-2026-10-23.md`
- `docs/cross-ecosystem-plan.md`
- `docs/tasks/roadmap-cross-ecosystem.md`
- `docs/roadmap-12k.md`, `docs/PLAN.md`
- `docs/prompts/opencode-12k-parallel.txt`, `docs/prompts/README.md`
- `docs/handoff-opencode.md`
- `docs/tasks/backlog.json`, `docs/tasks/m1.md`, `docs/tasks/README.md`
- `docs/work/board.md`, this record

No product source, dependency, public schema, protected verifier or gate policy
changed. Documentation is English. No Git commit, push, hosted publication,
outreach or model call was performed.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Competitor evidence | Official guides/release notes inspected; current/experimental/release distinctions preserved | Research document with primary URLs and observation date |
| Reuse and scoped delivery | Existing backend/codec/export/CLI/REPL seams inspected; 11 new mirrored cards | Plan, backlog and M1 card sections |
| Initial metadata validation | Passed, 226 cards | `artifacts/plan-review/cross-ecosystem.json` |
| Initial docs gate | Passed; superseded for product-input identity after estimate correction | `artifacts/quality/20261023-052400-76089-0001/manifest.json` |
| Independent review | Approved after correcting repeated arithmetic total | Visible `cross_plan_review` subagent, round 2 |

## Commands

| Command | Exit | Result |
| --- | ---: | --- |
| `git status --short` | 0 | Initial tree clean; later only scoped documentation changes |
| `git rev-parse --short HEAD` | 0 | `a351d3c` |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/cross-ecosystem.json` | 0 | 226 cards; planning package passed |
| `make quality-changed` | 0 | Selected only docs for the changed product inputs |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | Initial docs/plan-validate passed; timeout false |
| `git diff --check` | 0 | No whitespace errors |

Final product-input checks after the estimate correction and record creation:

| Command | Exit | Manifest |
| --- | ---: | --- |
| `make quality-changed` | 0 | Selected docs |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261023-052533-77863-0001/manifest.json` |
| `make quality-profile PROFILES="docs"` | 0 | `artifacts/quality/20261023-052547-78491-0001/manifest.json` |

Both executions passed plan-validate with timeout false. Product-input
fingerprint: `e8706ac1d888e9b3bb2a3229132110eecc12ca99150806b98d34be89261d603f`.
This receipt/board update changes only operational audit metadata; a final docs
profile checks that metadata as well. The preliminary gate is not rebound to
changed product inputs.

## Limitations

This task performs planning validation, not runtime or competitor benchmarks.
No competitor package installation, human usability trial, foreign-language
consumer run or paid comparison was executed. All such results remain pending.
The runtime, format/protocol implementation and new SDKs remain future work.
Release evidence inspected was from current public pages, not executed binaries.

## Review and next task

Read-only visible agents researched each competitor; a separate reviewer
checked the completed plan/cards/source references. Round 1 found the incorrect
estimate sum; all three occurrences were corrected without inventing reserve.
Round 2 approved and reported a clean whitespace diff.

Next new task: RAI-196 architecture/reuse ADR; RAI-204 can run independently.
Reconcile current CI/TUI lane ownership before assigning either.

Product-input evidence and operational record/board hashes remain separate
under existing quality policy; no exclusion was added or broadened.
