# Coordinator integration: TabShell scalafix fix and RAI-188

Status: complete. Date: 2026-10-23. Baseline commit/source: `6f4e560`.

## Resulting behavior

`TabShell.loop` now binds the optional chat action with a local `val` before branching. The semantics are unchanged; scalafix's Scala Meta parser can parse the source. RAI-188 is accepted on the integrated candidate. RAI-189/190 are ready after verifying their sole prerequisite RAI-188. RAI-207 registers the protected CWF-E02 migration as blocked by RAI-195 acceptance, with independent review required.

## Changed paths

- `modules/ui/src/main/scala/raider/ui/TabShell.scala`
- `docs/tasks/m0.md`
- `docs/tasks/m1.md`
- `docs/tasks/m2-p2.md`
- `docs/tasks/README.md`
- `docs/tasks/backlog.json`
- `docs/work/board.md`
- `docs/work/records/RAI-188.md`
- `docs/work/records/COORD-TABSHELL-RAI188-20261023.md`

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Scalafix parser compatibility | Passed; changed source processed by scalafix, UI test compilation succeeded | Combined sbt command below |
| RAI-188 PTY surfaces | Passed: 8 tests, 0 failed, 0 ignored, including `MainTabsPtySpec` and `ChatLayoutPtySpec` | Combined sbt command below |
| RAI-189/190 dependencies | Both sole dependencies point to RAI-188; prerequisite now accepted, so both are ready | `docs/tasks/backlog.json`; plan validator |
| RAI-207 protected conflict route | Registered, todo, dependent on RAI-195; migration explicitly deferred until acceptance and requires independent review | `docs/tasks/m0.md`, `docs/tasks/backlog.json` |
| Plan package | Passed, 227 cards | `artifacts/plan-review/report.json` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | ---: | --- |
| `sbt --batch raiderUi/scalafixAll raiderUi/Test/compile 'raiderRepl/testOnly raider.repl.MainTabsPtySpec raider.repl.ChatLayoutPtySpec'` | 0 | scalafix success; compile success; 8 PTY tests passed |
| `make quality-changed` | 0 | Selected `contracts`, `static`, `unit` for `TabShell.scala` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | All 5 gates passed, timeout false; `artifacts/quality/20261023-063518-83301-0001/manifest.json` |
| `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` | 0 | All 8 gates passed, timeout false; `artifacts/quality/20261023-064307-8972-0001/manifest.json` |
| `make quality-changed` (after metadata/board synchronization) | 0 | Selected `contracts`, `docs`, `static`, `unit` |
| `make quality-changed QUALITY_EXECUTE=1` (after metadata/board synchronization) | 0 | All 6 gates passed, timeout false; `artifacts/quality/20261023-070459-91326-0001/manifest.json` |
| `make quality-profile PROFILES="docs"` | 0 | `plan-validate` passed, timeout false; `artifacts/quality/20261023-070459-91327-0001/manifest.json` |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` | 0 | 227 cards |
| `git diff --check` | 0 | Clean at the initial code-change check |

## Limitations / blockers

TAB-02 (RAI-172) remains a documented conditional exception and was not observed red in the integrated runs. MJE-03c remains a historical intermittent watch and was not observed red. RAI-204 remains in progress: the card requests a `release` profile, while that profile is absent from the current registered gate set; profile applicability/registration is routed to a separate policy question. No protected CWF-E02 expectation or verifier file was changed here.

## Review and next task

The RAI-188 merge `6f4e560` carries an independent review approval. RAI-188 is accepted; RAI-189 and RAI-190 are ready. RAI-196 is in review, RAI-204 is partial/in progress, and RAI-207 remains unstarted pending RAI-195 acceptance and independent review.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree.
