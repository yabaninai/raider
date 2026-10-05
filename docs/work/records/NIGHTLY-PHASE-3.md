# NIGHTLY-PHASE-3: work record

Status: done. Date: 2026-10-05T03:20Z. Baseline commit/source: 231d944 (feat(phase-2): core improvements).

## Resulting behavior

REPL live backend (master prompt Phase 3, tasks 3.1–3.3):

- **3.1 Live provider wiring**: `RAIDER_PROVIDER=openai` now constructs the SSE STREAMING backend (`OpenAIChatStreamingBackend`) through `MainBridge.setupLive` (ThreadLocal across the classloader split — pre-existing mechanism, now streaming). `RAIDER_MODEL` flows through a new `MainBridge.liveModel` ThreadLocal into the session; the facade sends the configured model name instead of the historical `scripted:<agent>` label (which remains the default for scripted sessions). Coding tools registry crosses via the existing ThreadLocal path.
- **3.2 System prompt**: `FacadeOps.ReplSystemPrompt` ("You are a coding agent in a Scala 3 + ZIO project. Tools: fs_read, fs_search, fs_edit, fs_patch, fs_tree, proc_run. Be concise. Use tools before answering.") is prepended to EVERY facade model call (fixture-proven).
- **3.3 Streaming in REPL**: sessions created with `echoStream = true` (live prelude) wrap the backend in the shared `StreamingDisplay` — model text deltas print to stdout as they arrive, ready tool calls print `→ tool(args)` to stderr, tool results print `✓/✗ ← tool: preview` to stderr via a facade tracer. Scripted default stays silent (`echoStream = false`), keeping fixture transcripts deterministic.

Additive, source-compatible surface changes (frozen fast-REPL contract extended with defaulted members only): `ReplSession.model` (default `"scripted"`), `ReplSession.echoStream` (default `false`), `ReplSession.make(model, echoStream)` params.

`StreamingDisplay` moved from `raider.cli.chat` to `raider.runtime.display` so the REPL facade and the CLI share one implementation (both depend on runtime; no new dependency edges).

## Changed paths

- `modules/runtime/src/main/scala/raider/runtime/display/StreamingDisplay.scala` (moved from cli, package renamed)
- `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala` (import only)
- `modules/cli/src/test/scala/raider/cli/chat/StreamingDisplaySpec.scala` (import only)
- `modules/repl/src/main/scala/raider/repl/facade/ReplSession.scala` (model + echoStream, defaulted)
- `modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala` (system prompt, echo backend, tracer, live model)
- `modules/repl/src/main/scala/raider/repl/MainBridge.scala` (streaming backend, model ThreadLocal)
- `modules/repl/src/main/scala/raider/repl/Main.scala` (prelude passes model + echoStream=true on the live branch)
- `modules/repl/src/test/scala/raider/repl/facade/ReplLiveWiringSpec.scala` (new, 5 fixtures)
- `modules/repl/src/test/scala/raider/repl/facade/FacadeOpsSpec.scala`, `DslErgonomicsSpec.scala` (message-index updates for the system prompt — intentional behavior change per 3.2)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| session defaults keep frozen behavior (model=scripted, echo=false) | fixture passed | raiderRepl ReplLiveWiringSpec |
| every facade ask prepends the REPL system prompt | fixture passed | ReplLiveWiringSpec |
| live model name reaches the request; scripted label preserved | fixtures passed | ReplLiveWiringSpec |
| raiderRepl suite | 21 passed, 0 failed | module log |
| repl_smoke.sh (scripted branch unchanged transcript) | PASS, all assertions | /tmp/repl-smoke3.txt |
| sbt compile exit 0 (-Werror) | 0 | build log |
| sbt full test | 10 suites, 0 failures | /tmp/test-p3.txt |
| make quality-changed QUALITY_EXECUTE=1 | 9/9 gates | artifacts/quality/20261005-031750/manifest.json |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | build log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | /tmp/test-p3.txt |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-031750/manifest.json |
| `sh scripts/quality/repl_smoke.sh` | 0 | /tmp/repl-smoke3.txt |
| `sbt --batch raiderRepl/test` | 0 (21) | module log |

## Limitations / blockers

- Live-provider behavior is exercised through unit fixtures (recording backend + wiring assertions) and the scripted smoke; a REAL live-model round on 127.0.0.1:8081 is NOT part of this record — the llama.cpp stand is not guaranteed running in this environment and product tests must not call it (master prompt rule). The live path reuses the exact streaming backend + AgentLoop combination proven by the provider-chat HTTP stand fixtures.
- The interpreter-world echo prints deltas via zio Console on the REPL thread; background (`start()`/`:jobs`) asks share the same sinks — interleaving between concurrent jobs is possible in interactive use (cosmetic, single-user REPL).
- System prompt changes recorded request shapes; fixtures that assert exact message lists were updated to the new shape (documented above).

## Review and next task

Self-executed nightly run; no reviewer assigned. Next: Phase 4 (error recovery & auto-retry: compilation retry loop, rate-limit backoff, prompt engineering).
