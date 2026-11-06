# THROUGHPUT-20261101: development execution-plan work record

Status: done — reviewed execution-plan update; no throughput improvement has yet been measured. Date: 2026-11-01 UTC. Baseline: root product `e41cb28`; prior audited documentation `0cdeb69`; branch `audit/20261101-throughput` in `artifacts/audit-worktrees/audit-20261101`.

## Resulting behavior

Added a two-batch throughput experiment to PLAN and the OpenCode handoff: concrete installed coding scenario; 8+ visible ready lanes with an initial 2–4-heavy-JVM scheduling trial and one integrated full-suite slot; serialized cold compiles; immediate review and coherent integration batches; private incremental build state; measurement of accepted-scenario lead time, review/build/retry cost. Gate deduplication is explicitly a future separate protected-runner card, with all current mandatory checks retained. Nonblocking research can be timeboxed, but required acceptance failures cannot be deferred to manufacture readiness.

## Changed paths

- `docs/PLAN.md`: throughput section and bounded experiment.
- `docs/handoff-opencode.md`: execution prompt and practical protocol.
- `docs/tasks/development-throughput-20261101.md`: narrow documentation assignment.
- `docs/work/records/THROUGHPUT-20261101*.md`: this execution and independent review.

No runtime, build, public API, gate policy, task acceptance, authorization, concurrency maximum or dependency change.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Prior plan correction exists in root | Confirmed by source reads and Git status; local uncommitted audit documents remain | `docs/PLAN.md`, `docs/handoff-opencode.md`; root `e41cb28` |
| Slowdown evidence | RAI-252 records host load 5–198 and failed/interrupted runs; Zinc study records copy-local poison during overlapping cold compiles | `docs/work/records/RAI-252.md` §Limitations; `docs/research/zinc-concurrency-2026-10-28.md` §2 |
| Existing parallelism policy preserved | New heavy-job schedule stays below maximum; 8+ visible lanes when ready, reviewers retained; no shared targets | `docs/parallel-development.md` §§1/1a |
| No weakened acceptance | Mandatory classify/execute/profiles and independent review unchanged; gate consolidation requires separate review | PLAN throughput items 3–5 |
| Plan validation | Passed; 274 cards | `artifacts/audit-20261101/throughput-plan.json` |

## Commands

| Command | Exit | Evidence |
| --- | ---: | --- |
| `git status --short` and current source/document inspections | 0 | Confirmed existing work and preserved unrelated LOCAL-YABANIN record |
| `python3 scripts/plan/validate.py --out artifacts/audit-20261101/throughput-plan.json` | 0 | Planning package passed |
| `git diff --check` | 0 | No whitespace errors |
| `make quality-changed` | 0 | Exactly docs selected |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | Documentation gate passed; `artifacts/quality/20261101-141946-43045-0001/manifest.json` |

## Limitations / blockers

No throughput benchmark or speedup has been measured by this change. The 2–4-heavy-JVM schedule is a trial, not an optimum or a fix for Zinc poisoning. Existing OpenCode workers were not interrupted; admission changes are for subsequent slots/batches. This follow-up launches no product implementations and cannot confirm that the other interactive session has already reread the files. All full runtime/live/provider/publication limitations from the readiness audit remain.

## Review and next task

Independent review: **APPROVE**, commit `61b91ef`, record `THROUGHPUT-20261101-review.md`; no required changes. The root handoff copies only the two amended plan documents and the new card/records after comparing the existing root files to the audited baseline. Final root check logs are retained under `artifacts/audit-worktrees/audit-20261101/artifacts/throughput-20261101/`. Existing OpenCode coordinator should reread PLAN/handoff, finish its current integration, register the scoped evidence and native-coder follow-ups, and measure the next two batches. The root product checkout and board remain owned by that session.
