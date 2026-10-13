# ROADMAP-SDK: work record

Status: done (planning changes and independent review). Date: 2026-10-12 UTC.
Baseline commit: `4b46dc7`; git checkout, initial `git status --short` empty.

## Resulting behavior

The embedding SDK for Scala/ZIO was incorporated into the existing roadmap: P0 audit/ADR,
P4.C external consumer and lifecycle, P7 shared workflow host/REPL/CI and application
scenario, P10 versioned artifacts/clean consumer acceptance. SDK-01–05 are explicitly
included in M4 exit. RAI-115 is registered as ready for a read-only audit and packet
for the next ADR card; this work did not implement the SDK.

Owner decisions: Raider + SDK, first scenario is CI; the current wave continues;
the project is fully open source. Detailed guides are deferred; MIT/license policy,
W01–W18, current lineage/TUI cards, and P11 after M4 are retained.
13,100 h is the previous envelope; incremental effort/remaining work must be estimated before coding.

## Changed paths

- `docs/roadmap-12k.md`: scope, phases, dependencies, and SDK acceptance.
- `docs/PLAN.md`, `docs/prompts/opencode-12k-parallel.txt`: aligned entrypoint;
  the prompt also reflects the current 13,100 h and spawn/worktree rules.
- `docs/tasks/backlog.json`, `docs/tasks/m1.md`, `docs/tasks/README.md`: RAI-115.
- `docs/tasks/roadmap-sdk-integration.md`: scope of the current planning card.
- `docs/work/board.md`: owner decisions and the next independent lane.
- This record. Runtime, build, gate policy, and public signatures were not changed.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Planning consistency | passed, 133 cards | `artifacts/plan-review/roadmap-sdk.json` |
| Changed-path classification | docs only, exit 0 | `make quality-changed`, session output |
| Docs gate execution | passed, plan-validate exit 0 | `artifacts/quality/20261012-191140-51789-0001/manifest.json` |
| Independent review | approved after explicit SDK-01–05 added to M4 exit | visible spawn `/root/roadmap_review`, final approval in owner session |
| Scope preservation | existing cards unchanged; one new audit card; no implementation edits | baseline diff vs `4b46dc7` |

## Commands

| Command | Exit | Evidence |
| --- | --- | --- |
| `git diff --check` | 0 | session output |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/roadmap-sdk.json` | 0 | planning report, 133 cards |
| `make quality-changed` | 0 | selected docs |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | manifest above, docs passed |

These results were obtained before this record was added. The actual runner
includes board/records in the fingerprint despite the operational allowlist described in the quality spec;
policy was not changed. Therefore, the manifest above is evidence of
its pre-record snapshot, not a final freshness claim. Final tree verification
after writing the record must save commands/exits/manifest path in
`artifacts/plan-review/roadmap-sdk-final.json`, with logs alongside (outside product inputs).
The final report with the commit relies on this final receipt; missing receipt
or nonzero exit does not mean passed. Do not change the record after the final run.

## Limitations / blockers

This is acceptance of a planning change. Scala/runtime/live tests were not run:
only planning documents and the backlog were changed. Users, dates, and team composition
are unspecified; pilots remain pending. The SDK API/artifacts have not yet been accepted.
The existing arithmetic/historical statuses across the roadmap were not re-audited;
13,100 h is not presented as a measured estimate-to-complete. No publication,
messages to third parties, or paid calls were made.

## Review and next task

Reviewer `/root/roadmap_review` independently reviewed code seams and the final diff;
the M4 acceptance finding was resolved, and the final verdict was approved.
The coordinator is the sole writer; the reviewer was read-only, with no shared mutable builds.
Next SDK card: RAI-115; current wave LIN-13/14/15 + RAI-113/114
continues. After the audit, create a separate ADR card with a reviewed public boundary,
then small implementation/consumer cards based on actual dependencies.
