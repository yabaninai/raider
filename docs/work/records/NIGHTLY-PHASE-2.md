# NIGHTLY-PHASE-2: work record

Status: done. Date: 2026-10-05T03:12Z. Baseline commit/source: 670c8cf (feat(phase-1): quality infrastructure).

## Resulting behavior

Core improvements (master prompt Phase 2, tasks 2.1–2.6):

- **2.1 Streaming CLI chat**: `raider chat` now uses the SSE `OpenAIChatStreamingBackend` wrapped in a new `StreamingDisplay` ModelBackend decorator — text deltas print to stdout in real time (flushed per delta), ready tool calls print `→ tool(args)` to stderr the moment the model emits them, tool results print `← tool: preview` on completion (tracer). The final answer is no longer re-printed after streaming. The decorator is transparent (events pass through unchanged; proven by fixtures).
- **2.2 Session persistence**: new `SessionStore` + `SavedSession` — atomic JSON documents (temp+rename) under `~/.raider/sessions/<id>.json` (base dir injectable for tests), strict id sanitization (no path traversal), typed errors for missing/corrupt sessions, corrupt files skipped by listing. Chat commands `:save <name>`, `:sessions`, `:load <name>`; CLI flag `raider chat --resume <name>` seeds the conversation.
- **2.3 Conversation compaction**: over 30 messages the oldest 10 are summarized by the model into one system message and the most recent 20 stay intact (middle dropped, documented); `:compact` forces it manually; automatic compaction runs before the model call in `processMessage`. Pure plan function `splitForCompaction` is fixture-tested. Summary failure leaves history untouched.
- **2.4 fs_patch**: new `UnifiedDiff` engine (hunk-header parsing, context matching with declared-anchor-first search, strictly increasing non-overlapping application) + `PatchTool` (same safety contract as fs_edit: required expected_sha256 with STALE refusal, workspace containment, size bound, atomic temp+rename). Registered in CodingToolset with JSON schema.
- **2.5 fs_tree**: new `TreeTool` — depth-capped (default 3, max 5), entry-capped (500, honest `truncated`), skip-list (.git/target/node_modules), glob file filter, dir+file symlink containment (escaped symlinks omitted, never followed). Registered in CodingToolset as `{"depth"?,"glob"?}`.
- **2.6 proc_run output parsing**: new `OutputParser` — sbt compile diagnostics (`[error] -- Error: file:line:col`, plus Scala-2-style lines) and sbt test summaries (`N tests passed. M tests failed. K tests ignored.`) become structured data embedded as a `parsed` field in proc_run's JSON result; non-matching output stays `{"type":"unknown"}` (never fabricated).
- Fixed a pre-existing tracer display defect: color interpolators referenced the `C` object itself (stderr showed `raider.cli.chat.ChatLoop$C$@…`); now reference the fields.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala` (streaming backend, session commands, compaction, color fix)
- `modules/cli/src/main/scala/raider/cli/chat/StreamingDisplay.scala` (new)
- `modules/cli/src/main/scala/raider/cli/chat/Session.scala` (new)
- `modules/cli/src/main/scala/raider/cli/Main.scala` (`--resume` flag)
- `modules/cli/src/test/scala/raider/cli/chat/StreamingDisplaySpec.scala` (new, 4 tests)
- `modules/cli/src/test/scala/raider/cli/chat/SessionStoreSpec.scala` (new, 7 tests)
- `modules/cli/src/test/scala/raider/cli/chat/CompactionSpec.scala` (new, 6 tests)
- `modules/tools/src/main/scala/raider/tools/files/edit/UnifiedDiff.scala` (new)
- `modules/tools/src/main/scala/raider/tools/files/edit/PatchTool.scala` (new)
- `modules/tools/src/main/scala/raider/tools/files/edit/EditTool.scala` (helpers widened to `private[edit]`)
- `modules/tools/src/main/scala/raider/tools/files/TreeTool.scala` (new)
- `modules/tools/src/main/scala/raider/tools/process/OutputParser.scala` (new)
- `modules/tools/src/main/scala/raider/tools/CodingToolset.scala` (fs_patch + fs_tree registration, parsed field)
- `modules/tools/src/test/.../PatchToolSpec.scala` (new, 9 tests), `TreeToolSpec.scala` (new, 6 tests), `OutputParserSpec.scala` (new, 7 tests)

Public API additions: `raider.cli.chat.{StreamingDisplay, SavedSession, SessionStore}`, `raider.tools.files.edit.{UnifiedDiff, PatchTool}`, `raider.tools.files.{TreeTool, TreeEntry, TreeResult}`, `raider.tools.process.OutputParser`; two new tool names in CodingToolset (`fs_patch`, `fs_tree`). No frozen core contract changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| sbt compile exit 0 (-Werror) | 0 | build log |
| sbt test 0 failures (full, 10 suites) | exit 0 | /tmp/test-full2.txt |
| StreamingDisplay fixtures (deltas order, ready-only tool notice, request passthrough, in-band Failed) | 4/4 passed | raiderCli test |
| SessionStore fixtures (roundtrip, overwrite, missing/corrupt typed, traversal refused, sorted list) | 7/7 passed | raiderCli test |
| Compaction fixtures (threshold/keep/force semantics) | 6/6 passed | raiderCli test |
| PatchTool fixtures (apply, multi-hunk, context mismatch + untouched file, stale sha, empty sha, escape, trailing newline) | 9/9 passed | raiderTools test |
| TreeTool fixtures (skip-list, glob, depth cap+clamp, entry cap, symlink escape) | 6/6 passed | raiderTools test |
| OutputParser fixtures (compile errors, test summaries, stderr scan, Unknown honesty, JSON form) | 7/7 passed | raiderTools test |
| make quality-changed QUALITY_EXECUTE=1 | 9/9 gates passed | artifacts/quality/20261005-030939/manifest.json |
| chat pipe smoke (mock provider): banner lists fs_patch/fs_tree, deltas stream, :save/:sessions/:compact respond | exit 0 | manual transcript |
| full-tree fast profile after ALL phase edits | passed | manifest above |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | build log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | /tmp/test-full2.txt |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-030939/manifest.json |
| `sbt --batch raiderTools/test` / `raiderCli/test` / `raiderRuntime/test` | 0 (46/27/69) | module logs |
| pipe smoke `printf 'hello\n:quit\n' | sbt raiderCli/runMain raider.cli.Main chat --provider mock` | 0 | transcript |

## Limitations / blockers

- One full-suite `sbt test` run exceeded a 25-minute wall-clock timeout while the §11 delegation cancel test ran under parallel module load; the immediately repeated full run passed cleanly (~2 min) with zero changes in between. No test was modified or relaxed. Transient resource contention, not a product regression.
- Compaction summarizes at most the oldest 10 messages (master-prompt semantics); when >10 messages overflow the keep-window the middle ones are dropped without summarization — documented in code.
- StreamingDisplay reuses the mock fallback backend when the streaming backend cannot be constructed; chat against a dead endpoint degrades to the mock marker (pre-existing behavior, unchanged).
- fs_patch requires an existing file (creation goes through fs_edit) — deliberate, documented in the tool description and errors.

## Review and next task

Self-executed nightly run; no reviewer assigned. Next: Phase 3 (REPL live backend wiring, system prompt, streaming).
