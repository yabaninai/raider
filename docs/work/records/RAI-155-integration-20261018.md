# RAI-155 integration work record

Date: 2026-10-18. Coordinator integration on `main`, starting at `68d2fee`.

## Result

- Merged `lane/rai-155f` with the requested no-fast-forward merge. Review was
  approved; RAI-155.f is accepted. The parent remains blocked because the
  required integrated gates have a real documentation-link failure.
- RAI-155.a–f are all accepted. The .f lane record reports the five-run §6d
  matrix and real workflow IDs `wf_955e397c`, `wf_4236a29f`, and
  `wf_9ac3e838`. It is lane evidence, not a replacement for root integrated
  verification.

## Integrated commands and evidence

| Command | Exit/result |
| --- | --- |
| `make quality-changed` | 0; clean-tree classification, minimum fast |
| `make quality-changed QUALITY_EXECUTE=1` (120-second budget) | Terminated by timeout; not a pass |
| `make quality-changed QUALITY_EXECUTE=1` (one longer retry) | 2; manifest `artifacts/quality/20261018-004127-48453-0001/manifest.json`; plan-validate failed exit 1, other listed fast checks passed |
| `make quality-profile PROFILES="static unit contracts ci docs"` | 2; manifest `artifacts/quality/20261018-004900-54230-0001/manifest.json`; plan-validate failed exit 1, sbt-test/CLI-smoke/contracts/compile-fixtures/compile/format passed |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` | 1; `Plan package: failed; 193 cards` |
| Integrated RAI-155 verifier command | Not run: stopped after a real gate red. No verifier exit codes are claimed. |

The concrete failure is five missing relative artifact links in
`docs/examples/chat-workflow/README.md`: E01/E02/E03 logs, the E01 manifest,
and the E01 input JSON under `artifacts/rai-155f/`. The lane-only artifacts
were absent from the root checkout. This is distinct from the documented
MJE-03c and TAB-02 load exceptions; those do not account for the docs failure.

## Changed paths

- `docs/tasks/lineage.md` — RAI-155.f accepted; parent blocked pending integrated
  gates and verifier.
- `docs/work/board.md` — recorded merge, gate results, truthful parent status,
  parallel lane state, and next work.
- This work record.

## Limitations and handoff

No claim of parent acceptance or live-model quality. Restore the cited RAI-155.f
artifacts in the integrated evidence location or correct the links through the
authorized documentation/evidence path, rerun integrated gates, then run the
complete parent verifier. Continue LIN-03 via RAI-173 after its gate diagnostic;
RAI-157/136 remain load-held as recorded on the board.
