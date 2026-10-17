# RAI-155.e: work record

Status: review-fix implemented; required lane gates passed. Date: 2026-10-17. Lane: `lane/rai-155e`. Round-2 fix baseline: `5feae95`.

## Resulting behavior

- `ChatWorkflowVerifierHarness.loadE02` now reads the expectation bytes once, verifies their SHA-256 against the independently pinned verifier-code anchor, and only then decodes and returns `E02Doc`. A mismatch returns a typed `IllegalStateException` failure with `verifier input digest mismatch`; expectations are not used by verifier cases first.
- `ChatWorkflowVerifierE02Spec` mutates the real E02 expectation bytes and sends them through the same byte-loader called by `loadE02`. The test asserts the typed refusal. `ChatWorkflowVerifierCorpusSpec` retains its independent expectation-byte anchor and separate in-memory integrity assertion. This is narrow tamper evidence for verifier input bytes, not a sandbox or protection from changing verifier code/anchor.
- The prior record's claim that changed-verifier detection remained missing is removed. Round-1 corpus coverage and the round-2 real-load-path wiring are both resolved in this lane; independent round-3 review remains outstanding.
- Initial quality execution found formatting issues in both changed Scala files (manifest `artifacts/quality/20261017-205444-68392-0001/manifest.json`, failed only `scalafmt-check`; compile, tests, contracts and compile fixtures passed). Ran `sbt --batch scalafmtAll`, which reformatted only the two changed Scala test sources, then reran the required corpus, E02 ×3, E01 and E03 cases successfully. This failed pre-format manifest is retained as an honest trail and is not final evidence.
- No product APIs, public contracts, or frozen expectations were changed. No paid provider calls or root-checkout changes were made.

## Changed paths

- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierHarness.scala` — digest check in actual E02 load path.
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierE02Spec.scala` — negative mutation through the E02 byte-loading path.
- `docs/work/records/RAI-155e.md` — this coherent round-2 record.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| E02 expectation digest is checked before decode/use | Passed | `ChatWorkflowVerifierHarness.loadE02` routes bytes through `loadE02Bytes`; mismatch is typed failure. |
| Tampered expectation negative uses real E02 loader path | Passed ×3 | E02 test suite includes `changed verifier expectation is refused by the real E02 loader`; present in all three E02 runs. |
| Independent E02 corpus integrity | Passed | Corpus suite: 5 tests passed, 0 failed, 0 ignored. |
| E02 verifier cases | Passed ×3 | Each run: 4 tests passed, 0 failed, 0 ignored. |
| E01 regression | Passed | 1 test passed, 0 failed, 0 ignored. |
| E03 regression | Passed | 2 tests passed, 0 failed, 0 ignored. |
| Final quality gates and manifest verification | Passed | `artifacts/quality/20261017-211259-3659-0001/manifest.json` and `artifacts/quality/20261017-212405-16935-0001/manifest.json`; verifier output below. |

## Commands

These verifier runs were executed before the record was finalized:

| argv / command | Exit | Result |
| --- | ---: | --- |
| `sbt --batch "raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec"` | 0 | 5 passed; independent corpus mutation assertion remains green. |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE02Spec"` (run 1) | 0 | 4 passed, including tampered bytes rejected by real loader. |
| Same standalone E02 command (run 2) | 0 | 4 passed, including real-loader negative. |
| Same standalone E02 command (run 3) | 0 | 4 passed, including real-loader negative. |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec"` | 0 | 1 passed. |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE03Spec"` | 0 | 2 passed. |
| `make quality-changed QUALITY_EXECUTE=1` (pre-format attempt) | 2 | `artifacts/quality/20261017-205444-68392-0001/manifest.json`; failed `scalafmt-check`, other 4 gates passed. Fixed by formatting and reran all requested verifier tests. Not final evidence. |
| `sbt --batch scalafmtAll` | 0 | Reformatted the 2 changed CLI test sources. |

Final gate sequence (required order; record finalized before first command):

| argv / command | Exit | Log / manifest |
| --- | ---: | --- |
| `make quality-changed` | 0 | Classified the two modified CLI test files; selected contracts, static, unit. |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | Passed all 5 gates, 0 failures/skips; `artifacts/quality/20261017-211259-3659-0001/manifest.json`. |
| `make quality-profile PROFILES="static unit contracts ci"` | 0 | Passed all 6 gates, 0 failures/skips; `artifacts/quality/20261017-212405-16935-0001/manifest.json`. |
| `python3 scripts/quality/quality.py verify artifacts/quality/20261017-212405-16935-0001/manifest.json --require static,unit,contracts,ci` | 0 | `[verify] OK: manifest artifacts/quality/20261017-212405-16935-0001/manifest.json satisfies ['static', 'unit', 'contracts', 'ci'] (policy/toolchain/logs hashes checked, fingerprint fresh)` |

## Limitations / blockers

- Quality manifest verification was run successfully before recording its output here. As with all lane evidence, it applies to the checked product-input fingerprint and gate configuration; the subsequent operational-record update is not a product-input change.
- Fresh independent round-3 review is outstanding. Lane evidence is not integrated-candidate acceptance.
- Test output included ZIO test-clock warnings in existing EventHub calls and JDK/Scala `sun.misc.Unsafe` deprecation notices; these were warnings, not skipped/failed checks.
- No paid calls were made; E01/E02/E03 used local fixtures/stands only.

## Review and next task

Round-2 findings resolved by this lane; reviewer verdict pending. After review/acceptance of RAI-155.e, the next chain card is RAI-155.f. No push or merge to `main` is authorized.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
