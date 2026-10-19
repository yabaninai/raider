# LIN-04: work record

Status: partial (parent acceptance blocked). Date: 2026-10-18. Baseline commit/source: `0f73be7a2b429b40bb7636b94b0313cce34594f1` (worktree `wt/lin-04`, branch `lane/lin-04`, clean at start).

## Resulting behavior

No product behavior changed in this lane. The existing `LineageTab` is a pure `AuxTabProvider` over `connected: () => Boolean` and `projection: () => String`. It re-reads on each `:aux`, skips projection reads when disconnected, and renders non-empty text or an explicit empty-state line. Its tests use a scripted `ShellTerminal` and verify tab switching, but do not exercise a real PTY or a live lineage producer.

The parent acceptance remains incomplete: no composition in the interactive TUI attaches the provider to `LiveLineageProjection` or to a `LineageSnapshot` pointer-resolved committed pair. `modules/repl/.../TabShellHost.scala` confirms the product host runs with `aux = None` (except the separate plan-tab overload). Its explicit product wiring path is outside this card's allowed paths. No equivalent adapter was found in `modules/cli/src/main/scala/raider/cli/chat/**`; the live projector is in `modules/cli/src/main/scala/raider/cli/run/**`, also outside the card's path boundary. Adding a direct path reader in UI would violate the no-fixed-path/pointer protocol requirement and the UI's pure-provider contract.

ADR-024's honest unknown convention is applied by the generic empty/unavailable hints, but the existing tab has no typed projection-validity status to render. A scripted terminal test is not the required real PTY acceptance. Therefore LIN4-01, LIN4-02 (for invalid live projection), LIN4-03 in the live producer case, and the requested PTY acceptance are not claimed as passed.

## Changed paths

- `docs/work/records/LIN-04.md` (this record only).
- Product/API/schema changes: none. No frozen schema, shared file, or out-of-scope path changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN4-01 | Pending: provider is not connected to a live producer in product host; live input responsiveness not demonstrated | `modules/repl/src/main/scala/raider/repl/TabShellHost.scala` runs the default host without an aux provider |
| LIN4-02 | Partial only: empty/unavailable hints exist; honest invalid-projection status is not wired | `modules/ui/src/main/scala/raider/ui/LineageTab.scala`; no live status source is supplied |
| LIN4-03 | Partial only: scripted `:aux` → `:tabs` round-trip tests exist; no live-source switching acceptance | `modules/ui/src/test/scala/raider/ui/LineageTabSpec.scala` |
| PTY acceptance | Not run/not met: existing LineageTabSpec uses a scripted terminal, not a PTY | No lineage-specific real-PTY fixture exists in the allowed UI/CLI-chat paths |
| LIN-04A / LIN-04B prior slices | Code present in baseline; their separate accepted work records were not present in this worktree inventory | `AuxTabProvider`, `TabShell`, `LineageTab`; records search in `docs/work/records/**` found no LIN-04A/B record |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` (pre-edit classification) | 0 | Clean-tree minimum `fast`; output reported no changes vs HEAD |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `fast` passed; manifest `artifacts/quality/20261018-063745-6409-0001/manifest.json`, SHA-256 `81054e5191e987a34892b21e60bce5d538aee0347daf484ce52b2c4d75f752bb` |
| `make quality-profile PROFILES="static unit contracts runtime repl"` | 0 | All requested profiles passed; manifest `artifacts/quality/20261018-064717-22621-0001/manifest.json`, SHA-256 `ff98705549c63a45923f7a43e9b3375be882ed581421628f52e9c9a6d0da0c11` |
| `sbt --batch "raiderUi/testOnly raider.ui.LineageTabSpec"` | 0 | 5 tests passed, 0 failed, 0 ignored; confirms the existing scripted-terminal provider slice only |
| Real PTY lineage acceptance | Not run | No eligible lineage PTY fixture was available in the assigned paths |

## Limitations / blockers

The parent cannot be completed inside the supplied allowed paths: the registered TUI product host is in `modules/repl`, while the live projection and pointer-resolved snapshot reader are in `modules/cli/src/main/scala/raider/cli/run/**` and `modules/cli/src/main/scala/raider/cli/lineage/**`. The card allows `modules/ui/**` and `modules/cli/src/main/scala/raider/cli/chat/**`, not those composition/producer paths. A follow-up scope must authorize the relevant composition and source adapter paths (or create a separately scoped adapter/host card) and a real PTY fixture before claiming parent completion. No test timeout, skip, or scripted terminal result is treated as a pass for that missing acceptance.

## Review and next task

Reviewer verdict: pending independent review. Next action: coordinator to register/authorize the narrow TUI-host + pointer-resolved lineage adapter/PTY completion scope; then rerun integrated candidate gates. This lane does not claim parent acceptance or merge/push.

The operational record is excluded from product-input fingerprinting only under the exact `docs/work/records/**` allowlist; it does not rebind any evidence to a changed product snapshot.
