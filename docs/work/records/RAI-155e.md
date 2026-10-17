# RAI-155.e: work record

Status: partial. Date: 2026-10-16. Assigned base: `4704dea`; actual worktree HEAD at start: `56496b8` (already contained `4704dea`). Refreshed by merging current `main` at `963d584` cleanly (merge commit `7676af0`).

## Resulting behavior

- E01 now routes all four captured chat turns through the real
  `OpenAIChatStreamingBackend` to the local Scala OpenAI-compatible SSE stand.
  The stand checks four capture requests; export/compile remain zero-provider
  operations. The full two-input/two-launcher plus unedited control passes.
- The E03 timeout was caused by test-clock `ZIO.sleep` calls that never advanced
  in this test context. The waits are now bounded wall-clock sleeps on blocking
  threads. SIGTERM runs in a fresh subprocess, has an independently bound delayed
  stand, terminates with the expected 143 exit, and does not retry. The test
  reproduces a product gap: SIGTERM leaves a `Failed` run with no artifact and no
  interrupted marker. That truthfulness criterion still fails; no product code
  was changed.
- The old `ChatWorkflowVerifierSpec` was retired. It duplicated E01/E02/E03 but
  depended on nonexistent `contracts/chat-workflow/verifier-expected.json` and
  contained contradictory legacy assertions. The replacement suites consume the
  unchanged `expectations/CWF-E01..03.json` corpus through the shared harness.
  The minimal protected expectation update changes only
  `CWF-E01.capture.standRequestsDuringCapture` from 0 to 4, because the real
  stand is now called for each of four turns; the red-to-green E01 run is the
  reproducer. The corpus-integrity suite passes.
- The merged `main` change in `ReplayHarness` was retained as-is. The adjacent
  drain/watermark behavior is unrelated to this lane's chat verifier; no conflict
  or force-resolution was needed.
- The worktree did not begin at the packet's stated tip `4704dea`; it already
  had a clean `56496b8` HEAD and contained the assigned tip. A first `main`
  refresh was already present at that point; the requested current `main`
  (`963d584`) was merged separately as `7676af0`.

## Changed paths

- `contracts/chat-workflow/expectations/CWF-E01.json`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierE01Spec.scala`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierE03Spec.scala`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierHarness.scala`
- Deleted `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierSpec.scala`
- `docs/work/records/RAI-155e.md`

No product runtime, public contract, build, gate policy, or task-card code was
changed. No paid provider calls were made.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Merge baseline | Passed; current `main` merged cleanly with no conflicts | `7676af0` merges `963d584`; earlier `56496b8` already contained assigned `4704dea` |
| CWF-E01 real chat capture and matrix | Passed: all four capture turns traverse the local OpenAI-compatible stand; export/compile do not call it | Targeted verifier command below; the same E01 case passed in the complete targeted run |
| CWF-E02 negative corpus | Passed: 3 CLI cases | `raiderCli/testOnly ...E01Spec ...E02Spec`; 4 CLI tests passed total |
| CWF-E03 interrupted capture/export and concurrency | Passed in targeted test process | Complete targeted verifier command: first E03 test passed |
| CWF-E03 deterministic repeat + SIGTERM | Overall failed: bounded child reached delayed stand and exited 143, but product emitted neither a run artifact nor an interrupted marker. The combined repeat/SIGTERM test therefore remains red; do not report the repeat subassertion as an independent pass from this run. | Complete targeted verifier command below; failure at `truthyArtifacts` |
| Corpus integrity | Passed: 4 tests | `raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec` |
| Legacy verifier mismatch | Resolved by retiring the duplicate suite; protected expectation corpus remains at its documented `expectations/CWF-E0*.json` layout | Deleted old spec; replacement suite + corpus integrity pass |
| `make quality-changed` | Passed; classified `contracts`, `static`, `unit` | Command output; no execution requested in this mode |
| `make quality-changed QUALITY_EXECUTE=1` | Failed: core contracts, compile fixtures, compile, formatting passed; project `sbt-test` failed, including the expected E03 product gap | `artifacts/quality/20261016-151649-7694-0001/manifest.json` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git merge main` | 0 | First refresh already in lane: `56496b8`; requested current-main refresh produced `7676af0` from `963d584`, no conflicts |
| `sbt --batch "raiderCli/Test/scalafmt" "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec raider.cli.chat.ChatWorkflowVerifierE02Spec raider.cli.chat.ChatWorkflowVerifierE03Spec" "raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec"` | 1; 5 passed, 1 failed. CWF-E01, E02, E03 capture/export passed; SIGTERM's truthful-artifact assertion failed. Sbt stopped before the trailing corpus command. | Console; explicit red retained |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec raider.cli.chat.ChatWorkflowVerifierE02Spec" "raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec"` | 0; 4 CLI tests and 4 corpus tests passed | Console |
| `make quality-changed` | 0 | Classified `contracts`, `docs`, `static`, `unit`; planning only |
| `make quality-changed QUALITY_EXECUTE=1` | 2 | `artifacts/quality/20261016-163323-63946-0001/manifest.json`; `sbt-test` failed (not timed out) on CWF-E03 SIGTERM truthful artifact. `sbt-compile`, formatter, fixture compile, plan validation, core contracts passed. Manifest SHA-256 `23aef3c448d3da488ec1385aa88463c8d3a7110a448d870577c3ce2a3d8c9530`; `sbt-test.log` SHA-256 `b71f8e59ce584e51a671f6dfd682728f424c42dc29321d89135f4cdd44187445`. |

## Limitations / blockers

- Product-side SIGTERM cleanup/reporting is outside RAI-155.e's verifier-only
  authorization. The independent E03 test is a minimal reproducer: start a real
  CLI subprocess on the local delayed stand, wait for its first HTTP request,
  send SIGTERM, wait for exit 143, and inspect its run directory. The process
  exits boundedly but writes neither an incomplete artifact nor the required
  interrupted marker. Track/fix under an authorized product card, then rerun E03.
- The expected SIGTERM gap makes the complete E03 and `quality-changed
  QUALITY_EXECUTE=1` gates red. The all-test gate ran approximately 605 seconds
  and ended with an assertion failure (`timed_out: false`), not a runner timeout.
  No checks were waived.
- No independent review has been performed. No evidence is promoted to final
  integrated-candidate acceptance.

## Review and next task

Next available work is the product-side bounded SIGTERM/artifact fix under its
own authorized card. After that fix, rerun E03, the targeted E01/E02/corpus
suites, both quality-changed commands, and the card-required `runtime`/`ci`
profiles. RAI-155.f remains blocked on accepted RAI-155.e.

Product-input hash and operational-record hash differ under the reviewed policy.
The command registry excludes `docs/work/records/**` from the product fingerprint;
the test/source/corpus are included. Changes to those product inputs require
fresh affected gates. This record does not turn the failed E03 gate green.
