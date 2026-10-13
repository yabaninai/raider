# ROADMAP-SDK-RESEARCH: work record

Status: done (research and roadmap revision). Date: 2026-10-12 UTC.
Initial baseline: `3a60f93a2197a0097e4a9f326260dd9fd7844578`, root initially clean.
Integrated audit baseline: `e2deeb30738cbd0beb3316f4e354d3416ac3adf4`.
Scope: [planning card](../../tasks/roadmap-sdk-research.md).

## Resulting behavior

Raider and its OSS SDK are integrated into the existing roadmap with the first user-facing result: author a Scala workflow and run it locally/in generic local CI. The primary scenario is AI-native SDLC for TokenRouter code; gateway remains P11. Scala 3/ZIO are pinned; experimental API has migration support; early preview before M4; trusted local trace data; no durable crash resume. The owner confirmed automatic commit/PR/merge, Scala-configured checkpoints, and release version/changelog/local tag/artifacts, without pushing release tags, package/image publication, or deployment.

WF-01/02 describe a separate preview; SDK-01–05 retain the host embedding proof. M3 explicitly requires WF-01/02 + SDLC-01–04; M4 also requires SDLC-05 and SDK-01–05. PR/merge engineering is checked with a generic contract on a Scala stand/disposable Git repository; a real forge remains pending adapter selection and acceptance. RAI-115 is accepted, RAI-116 is ready for workflow/launch ADR, RAI-117 is todo for the SDLC contract. Before RAI-117 acceptance, the coordinator will register per-case implementation/verifier IDs, owners, dependencies, exact allowed paths, and estimates. Implementation awaits the reviewed contract, estimate, and verifier packets. The current wave has not been reordered.

Primary-source research compares Scala/JVM agent libraries, maturity of their documented APIs, checkpoints/recovery, and real SDLC-authoring differentiation. Static source audit identified launch/lifecycle/admission/string-bundle/accounting gaps. TokenRouter was read without changes; dirty input hashes are recorded separately from HEAD. The existing release workflow publishes images and deploys staging on pushed `v*`; after this finding, the owner chose local-only tags. The full SDLC kit/remote adapter and remaining budget are unknown; 13,100 hours is not presented as a confirmed remainder.

## Changed paths

- `docs/research/sdk-roadmap-research-2026-10-12.md`: decisions, primary sources, positioning hypothesis, SDLC requirements, delivery/measurement, and target inventory.
- `docs/research/sdk-embedding-audit.md`: separate reviewed RAI-115 lane, merged.
- `docs/roadmap-12k.md`, `docs/PLAN.md`, `docs/prompts/opencode-12k-parallel.txt`: phases, dependencies, preview, and milestones.
- `docs/tasks/backlog.json`, `docs/tasks/m1.md`, `docs/tasks/README.md`: RAI-115 accepted, RAI-116 ready, RAI-117 todo; 135 cards total.
- `docs/tasks/roadmap-sdk-research.md`: scope/acceptance for this planning card.
- `docs/work/board.md`, `docs/work/records/RAI-115.md`, this record.

Public API/schema, runtime, build, protected fixtures, and registry were not changed.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Owner decisions | Scope/delivery/autonomy/forge answers incorporated | research §1 and roadmap §6b/§6c |
| Primary-source research | consulted 2026-10-12; documented/development/released distinctions retained | research §2–6, primary URLs |
| Actual code audit | independently reviewed and merged | RAI-115 record and `c156f40` / `e2deeb3` |
| TokenRouter read-only inventory | HEAD and 7 working hashes; 2 inspected dirty files explicitly marked | research §9 |
| Planning validator | passed, 135 cards | `artifacts/plan-review/sdk-research-precheck.json` |
| Integrated changed/docs execution | passed; docs selected | manifests below |
| Independent planning review | approved to commit after fixes | visible spawn `/root/plan_dependencies`, final owner-session verdict |

## Commands

Checks actually run before adding records and the final wording fixes:

| Command | Exit | Log / manifest |
| --- | --- | --- |
| `git diff --check` | 0 | coordinator and independent reviewer session output |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/sdk-research-precheck.json` | 0 | 135 cards |
| `make quality-changed QUALITY_BASE=3a60f93` | 0 | `artifacts/plan-review/sdk-research-pre-record-0.log`, docs |
| `make quality-changed QUALITY_EXECUTE=1 QUALITY_BASE=3a60f93` | 0 | `artifacts/quality/20261012-194329-79713-0001/manifest.json` |
| `make quality-profile PROFILES=docs` | 0 | `artifacts/quality/20261012-194343-79788-0001/manifest.json` |

These manifests apply to the pre-record snapshot, not the final tree. The actual runner hashes the board/records and nested worktree inputs; the operational allowlist described in the spec was not overridden for this task. After writing records, diff check, validator, changed, execute, and docs profile are repeated; commands/exits, logs, manifest paths and hashes are recorded outside product inputs in `artifacts/plan-review/sdk-research-final.json`. Only a passing final receipt confirms the final tree; if absent or failed, status is pending. These records are not changed after the final run. A commit does not replace gate evidence.

## Limitations / blockers

This is an accepted plan and audit, not an SDK/runtime release. Product tests, third-party libraries, TokenRouter build/tests, and live/paid model calls were not run. Remote branch rules were not checked; forge/CI system was not selected; unknowns are not marked passed. Detailed user guides are deferred. The preview does not promise support for older Scala compilers, durable resume, sandboxing, or exactly-once external effects. External OSS demand/pilots, remaining engineering hours, and public registry ownership are not confirmed. No target edits, pushes, publication, or messages to third parties were made.

## Review and next task

The coordinator is the sole writer for the shared plan/board; the audit writer worked in its own worktree. Other lanes performed read-only primary-source research, target discovery, and dependency/acceptance review. Reviewer findings about M3/M4 obligations, verifier ownership, SDK/UI ADR dependencies, stale statuses, and P11 scope were addressed; final verdict from `/root/plan_dependencies`: approve to commit, no blockers.

Next SDK card: RAI-116, then RAI-117. Current LIN-13/14/15 and RAI-113/114 continue. Public signatures and downstream implementation are accepted by future cards with separate evidence and review.
