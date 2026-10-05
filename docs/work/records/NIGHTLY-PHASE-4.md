# NIGHTLY-PHASE-4: work record

Status: done. Date: 2026-10-05T14:32Z. Baseline commit/source: 49cfe0c (feat(phase-3): REPL live backend).

## Resulting behavior

Error recovery & auto-retry (master prompt Phase 4, tasks 4.1–4.3):

- **4.1 Compilation error retry loop**: new `CompileFixLoop` (cli) — runs `sbt --batch compile` through the SAME proc_run tool the model uses, parses the output structurally (`OutputParser`'s `parsed` field), and on failure sends the errors back to the model as conversation context (fix attempt n/maxRounds), then re-compiles. Cycle repeats up to `maxRounds` (default 3) model fix requests; a passing compile ends the loop immediately and never spends a model call; persistent failure reports the remaining errors. Bound respected: total compiles = 1 + maxRounds. Exposed as `:fix` in the chat REPL (report printed). Missing proc_run is a typed InputValidation; unparsable tool output is a typed OutputValidation.
- **4.2 Rate limiting and backoff**: new `RateLimitRetries` ModelBackend decorator (runtime/display) — retries ONLY `ProviderRateLimit` (HTTP 429) with exponential backoff 1s → 2s → 4s, at most 3 retries (fixtures prove exactly maxRetries+1 attempts and the non-429 first-shot rule). Rationale documented in-code: a 429 means the request was NOT accepted, so retrying violates no mutational-call trust boundary; all other errors propagate unchanged on first occurrence. Wired into all three backend construction sites: headless runner (coder path), chat loop, REPL MainBridge.
- **4.3 Prompt engineering**: coder system prompts (headless `CoderSystemPrompt` + chat prompt) upgraded to the master-prompt workflow: tool reference incl. fs_patch/fs_tree, the 7-step READ→UNDERSTAND→CHANGE→COMPILE→FIX→TEST→SUMMARIZE workflow, and the four rules (never overwrite unread files, always use the last fs_read sha256, fix specific errors instead of rewriting, prefer fs_patch for small changes / fs_edit for new files).

**Flaky-test root cause fixed (found by the new fixtures' wall-clock honesty):** `AgentLoopDelegationSpec` "cancel_agent stops a parked child" intermittently hung the whole suite (observed 3× in gate runs). Root cause: the scripted `TurnBackend` selected turns by CALL INDEX, but parent/child dispatch under llm=2 is concurrent — under load the parent could receive the child's turn, after which the parked child was never cancelled and `awaitSettled` (no deadline) hung forever. Fix: the test routes turns by CONTENT (user text + tool_call_id c1/c2), making it load-independent; plus the settle wait is bounded (30s) so a lost settle signal now surfaces as a test FAILURE instead of an infinite hang. Verified: 5 consecutive clean runs of the spec (previously failing ~80% under repetition), full suite + all gates green twice.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/chat/CompileFixLoop.scala` (new)
- `modules/cli/src/test/scala/raider/cli/chat/CompileFixLoopSpec.scala` (new, 4 fixtures)
- `modules/runtime/src/main/scala/raider/runtime/display/RateLimitRetries.scala` (new)
- `modules/runtime/src/test/scala/raider/runtime/display/RateLimitRetriesSpec.scala` (new, 3 fixtures, live-clock)
- `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala` (`:fix` command, `MaxHistoryMessages` widened to `private[chat]`, prompt upgrade)
- `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala` (RateLimitRetries wiring, prompt upgrade)
- `modules/repl/src/main/scala/raider/repl/MainBridge.scala` (RateLimitRetries wiring)
- `modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala` (content-routed scripted turns + bounded settle wait — flakiness fix, see above)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| errors go back to the model; loop ends when compile passes | fixture passed (3 attempts, 2 model calls, error context in messages) | CompileFixLoopSpec |
| clean compile spends no model call | fixture passed | CompileFixLoopSpec |
| persistent failure: 1+maxRounds compiles, errors reported | fixture passed | CompileFixLoopSpec |
| missing proc_run → typed InputValidation | fixture passed | CompileFixLoopSpec |
| 429 then success retried; events pass through | fixture passed | RateLimitRetriesSpec |
| persistent 429: exactly 4 attempts then typed failure | fixture passed | RateLimitRetriesSpec |
| non-429 errors NOT retried (1 attempt) | fixture passed | RateLimitRetriesSpec |
| delegation cancel test stable under repetition | 5/5 clean runs | module logs |
| sbt compile exit 0 (-Werror) | 0 | build log |
| sbt full test | 10 suites, 0 failures | /tmp/test-p4b.txt |
| make quality-changed QUALITY_EXECUTE=1 | 9/9 gates (incl. sbt-test, after the earlier gate timeout was root-caused and fixed) | artifacts/quality/20261005-142853/manifest.json |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | build log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | /tmp/test-p4b.txt |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-142853/manifest.json |
| `sbt --batch raiderCli/test` / `raiderRepl/test` / `raiderRuntime/test` | 0 (31/21/72) | module logs |
| `sbt raiderRuntime/testOnly raider.runtime.loop.AgentLoopDelegationSpec` ×5 | 7/7 each | repetition logs |

## Limitations / blockers

- One quality-gate sbt-test run hit the gate's 15-minute timeout BEFORE the delegation flakiness was root-caused; after the fix the gate passes with margin. The timeout was never reported as passed.
- `CompileFixLoop` compiles via the proc_run tool only — a workspace without proc_run cannot use `:fix` (typed error, documented).
- `RateLimitRetries` sleeps are real-time in tests (`TestAspect.withLiveClock`): ~7s worst case for the persistent-429 fixture.
- 429 backoff applies at the model-call boundary only; tool calls are never auto-retried (mutational trust boundary unchanged).

## Review and next task

Self-executed nightly run; no reviewer assigned. Next: Phase 5 (packaging & CI: GitHub Actions, fat JAR, Makefile targets).
