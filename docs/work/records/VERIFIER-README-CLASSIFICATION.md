# VERIFIER-README-CLASSIFICATION: work record

Status: done (exact classifier correction, independently reviewed).
Date: 2026-10-12 UTC / 2026-10-13 Europe/Moscow. Baseline: `073d617`.
Card: [verifier-readme-classification](../../tasks/verifier-readme-classification.md).
Coordinator owns protected policy; independent Luna reviewer approved before edit.

## Resulting behavior / changed paths

`scripts/quality-policy/registry.json`: add exact `^README.md` alternative to
the existing docs key, preserving `^README` and all gate/profile/stage/exclude
definitions. Segment-prefix matching no longer refuses a README.md edit as unmapped.
No classifier implementation, threshold, verifier fixture or gate changes.
Other changes: card, this record, coordinator board entry.

## Acceptance evidence / commands

| Criterion / command | Actual result | Evidence |
| --- | --- | --- |
| VREADME-01 exact positives/negatives | README.md and README → docs; README-other.md and README.md.bak remain unmapped | `wt/user-docs-current/artifacts/user-examples/readme-classifier-check.json` |
| VREADME-02 preservation | compared 602 paths; all existing classifications except README.md unchanged; non-path policy definitions equal | same property report; coordinator inline Python session, exit 0 |
| Independent review | approve exact alternative before edit, final diff approve | `/root/user_docs_review_luna`, owner session |
| Initial `make quality-changed QUALITY_EXECUTE=1` | exit 2 (make), README.md unmapped | quality-pre-record.log; retained failed classification |
| Broad `make quality-profile PROFILES='fast docs security'` | exit 2 (make); 10 gates passed, sbt-test failed on existing SSE idle timeout | `wt/user-docs-current/artifacts/quality/20261013-012310-88826-0001/manifest.json` |
| `make quality-changed` after correction | exit 0, docs/self/static | quality-changed-reviewed.log |
| `make quality-changed QUALITY_EXECUTE=1` after correction | exit 0, all selected gates passed | `wt/user-docs-current/artifacts/quality/20261013-013224-92854-0001/manifest.json` |

VREADME-03 full conservative root validation is bound to the final receipt
`artifacts/user-docs-final/receipt.json` after merge/records. Its required commands
include changed/execute (clean-tree fast), explicit docs/security and gallery.
Missing/nonzero receipt is not passed. Initial failure is not relabeled; the
separately reproduced SSE defect is fixed under TRANSPORT-SSE-IDLE with independent
review and fresh lane full sbt-test. No test or threshold was weakened.

## Limitations / next task

Correction only fixes this exact filename. Unknown new paths still refuse.
Prior manifests bind their own snapshots, not subsequent source/record changes;
final main receipt is mandatory. Main inputs remain unchanged after final checks.
Next source-docs follow-up is maintaining the current guide alongside API changes;
SDK workflow/launch design remains RAI-116, outside this verifier correction.
