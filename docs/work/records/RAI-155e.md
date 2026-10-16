# RAI-155.e: work record

Status: partial. Date: 2026-10-16. Baseline commit: `facbe76`.

## Resulting behavior

Resumed the existing in-flight verifier implementation without resetting the
worktree. The current lane contains a three-scenario Scala acceptance suite
(CWF-E01..E03), a Scala local HTTP/SSE stand, and a JSON expectation corpus.
The E01 end-to-end matrix completed successfully in targeted runs. The new
E02 export-refusal, fresh-process preflight, hostile-text, invalid-source,
stale-build, and corrupt-build tests completed successfully when run
individually as a suite. The work is not complete: the legacy verifier spec
fails on mismatched paths/assertions, and the E03 SIGTERM/repeat test timed
out.

## Changed paths

- `contracts/chat-workflow/README.md`
- `contracts/chat-workflow/expectations/CWF-E01.json`
- `contracts/chat-workflow/expectations/CWF-E02.json`
- `contracts/chat-workflow/expectations/CWF-E03.json`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierE01Spec.scala`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierE02Spec.scala`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierE03Spec.scala`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierHarness.scala`
- `modules/cli/src/test/scala/raider/cli/chat/ChatWorkflowVerifierSpec.scala`
- `modules/dsl/src/test/scala/raider/dsl/workflow/ChatWorkflowVerifierCorpusSpec.scala`
- `docs/work/records/RAI-155e.md`

No product implementation, build, gate policy, board, or task-card files were
changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| CWF-E01 matrix | Passed in targeted test run; actual four-turn harness currently uses scripted chat replies, so it does not meet the card's real local-stand chat-capture requirement. | Targeted sbt output (console); quality manifest below |
| CWF-E02 export refusals and preflight negatives | Passed in targeted test run. | Targeted sbt output (console) |
| CWF-E02 hostile text / invalid source / stale and corrupt build (new E02 suite) | Passed (suite run); the separate legacy spec still raises `NoSuchFileException` for `.../vrf-inj.../vrf_inj.scala` after export. | Targeted sbt output (console) |
| CWF-E03 interrupted capture and snapshot/concurrent export | Passed in targeted test run. | Targeted sbt output (console) |
| CWF-E03 fresh-process SIGTERM and deterministic repeats | Failed: test exceeded its 600-second timeout. | Targeted sbt output (console) |
| Legacy `ChatWorkflowVerifierSpec` | Failed: reads absent `contracts/chat-workflow/verifier-expected.json`; also has a pre-repair effects assertion mismatch and a missing injection source path. | Targeted sbt output (console) |
| quality execute | Failed: `scalafmt-check` exit 1 and `sbt-test` timed out. | `artifacts/quality/20261016-060534-39372-0001/manifest.json` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git -C <worktree> log --oneline -1` and `git -C <worktree> status --short` | 0; baseline `facbe76`, only the assigned initial untracked verifier/corpus paths | Console |
| `make quality-changed` | 0; classified `contracts`, `static`, `unit` | Console |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec raider.cli.chat.ChatWorkflowVerifierE02Spec raider.cli.chat.ChatWorkflowVerifierE03Spec raider.cli.chat.ChatWorkflowVerifierSpec" "raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec"` | 1; 5 tests passed, 1 failed before the sbt command timed out; E03 timeout and legacy failures detailed above | Console |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE03Spec"` | 1; first E03 test passed, SIGTERM/repeat test timed out at 600 seconds | Console |
| `make quality-changed QUALITY_EXECUTE=1` | 2; core contracts, compile fixtures, sbt compile passed; scalafmt-check failed; sbt-test failed on timeout | `artifacts/quality/20261016-060534-39372-0001/manifest.json` |
| `sbt --batch "raiderCli/Test/scalafmt" "raiderDsl/Test/scalafmt"` | 0; one Scala source reformatted | Console; affected checks must be rerun after this source change |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec raider.cli.chat.ChatWorkflowVerifierE02Spec" "raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec"` | 0; E01/E02: 4 tests passed; corpus integrity: 4 tests passed | Console |
| `make quality-changed QUALITY_EXECUTE=1` (after formatting) | 2; core contracts, compile fixtures, plan validation, sbt compile, and scalafmt-check passed; `sbt-test` timed out | `artifacts/quality/20261016-062226-60649-0001/manifest.json` |

## Limitations / blockers

- CWF-E01's chat capture uses a scripted `ModelBackend`, rather than routing
  captured chat through the local OpenAI-compatible stand. It is not an
  independent completion of the required live-entrypoint fixture.
- The `ChatWorkflowVerifierSpec` legacy suite references a corpus filename
  that does not exist; its no-repair and injection checks also fail as
  implemented. The corpus README and test sources do not agree on the
  expectation document layout.
- E03 fails to finish inside its declared timeout. No passing SIGTERM evidence
  is claimed.
- The required gate execution is red because the full `sbt-test` timed out;
  the latest manifest records the post-format source. No paid/live provider
  call was made.

## Review and next task

No independent review has been performed. Next: repair the verifier-side
path/corpus issues without touching product code, replace scripted capture with
actual chat capture through the loopback stand, make the SIGTERM case bounded
and assert the required truthful exit/artifact behavior, then rerun the
targeted suite, `make quality-changed`, `make quality-changed
QUALITY_EXECUTE=1`, and the RAI-155.e static/unit/contracts/runtime/ci
profiles. Product defects must be returned as reproducers, not fixed in this
verifier lane.
