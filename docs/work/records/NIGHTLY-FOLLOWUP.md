# NIGHTLY-FOLLOWUP: work record

Status: done. Date: 2026-10-05T15:35Z. Baseline commit/source: 17e0241 (docs(phase-6)).

## Resulting behavior

Follow-up items from the nightly run's own records, plus two defects found during the follow-up verification:

1. **Coder prompt single-sourced**: new `raider.cli.CoderPrompts` — the chat loop and the headless `--agent coder` path send exactly the same Phase-4.3 system prompt (the two previously duplicated texts drifted in their first lines). The REPL facade keeps its own shorter prompt by design (interactive-exploratory surface), noted in the object's ScalaDoc.
2. **`make quality-full` executed end-to-end for the first time** (test + scalafmt + forbidden-apis + executed gates + REPL/CLI smokes): exit 0.
3. **Makefile coursier-cache defect fixed**: `COURSIER_CACHE=${COURSIER_CACHE:-/tmp/cc-master}` inside recipes is expanded BY MAKE — with the env var unset it produced `COURSIER_CACHE=:-/tmp/cc-master`, which coursier treats as a relative cache and materialized a 223 MB `https/` tree inside the repository (it slipped into commit 1ed9a67). Fix: `COURSIER_CACHE ?= /tmp/cc-master` as a make variable; recipes use `$(COURSIER_CACHE)`; `/https/` added to `.gitignore`; the junk directory removed from index and tree. Verified: `make raider-jar` without the env prefix no longer creates the directory.
4. **Second flaky delegation test fixed** (same race class as the parked-child test, caught by the full-suite run during follow-up): "max_children exceeded" routed scripted turns by CALL INDEX — under load the child's spawned call landed after a parent turn, the parent received the child's turn ("c1") as a FINAL answer and never issued delegate #2. Now routed by content/tool_call_id (`call-1`/`call-2`), with an externally declared request recorder (for-comprehensions widen `new ModelBackend {}` to the base type). 6/6 clean spec repetitions.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/CoderPrompts.scala` (new)
- `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala` (prompt import)
- `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala` (prompt import, private copy deleted)
- `Makefile` (COURSIER_CACHE variable fix, .PHONY updated)
- `.gitignore` (`/https/`)
- `https/**` (deleted — accidentally committed coursier cache)
- `modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala` (max_children test: content-routed turns + external recorder)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| raiderCli suite after prompt consolidation | 31/31 passed | module log |
| `make quality-full` end-to-end | exit 0 (test, scalafmt, forbidden-apis 100 files, gates 9/9, both smokes) | /tmp/quality-full.txt |
| `make raider-jar` without env prefix | exit 0, no `https/` re-created, jar hash da2ef30f… | /tmp/jar3.txt |
| delegation spec repetitions after max_children fix | 7/7 × 6 | repetition logs |
| sbt full test | 10 suites, 0 failures | /tmp/test-final2.txt |
| `make quality-changed QUALITY_EXECUTE=1` | 9/9 gates | artifacts/quality/20261005-153110/manifest.json |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-full` | 0 | /tmp/quality-full.txt |
| `make raider-jar` (no env prefix) | 0 | /tmp/jar3.txt |
| `sbt --batch test` | 0 | /tmp/test-final2.txt |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-153110/manifest.json |

## Limitations / blockers

- Commit 1ed9a67 contains the `https/` cache junk in HISTORY (removed in the working tree and in this follow-up commit). The local repository's `.git` carries the extra objects (~187 MB); no remote push has occurred. Rewriting local history was deliberately NOT done without an explicit owner request — flag it if the branch will be pushed and history size matters.
- The delegation spec's remaining tests may carry the same call-index pattern (others use single-turn or strictly ordered flows); they have not shown the race in repetition, but the pattern is documented in `docs/contributing.md` for future contributors.

## Review and next task

Self-executed follow-up. Suggested next cards: (a) audit remaining TurnBackend-based specs for call-index routing, (b) `CompileFixLoop` in the headless coder path behind an explicit flag, (c) the `maxLLM=1` stuck-child cancel channel (tracked obligation).
