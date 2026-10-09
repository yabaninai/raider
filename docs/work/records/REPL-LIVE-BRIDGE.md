# REPL-LIVE-BRIDGE: work record

Status: done. Date: 2026-10-08. Baseline: working tree at 7ee6c0c (dirty, W1 lane aftermath; repl files clean before this task).

## Resulting behavior

`raider repl` with `RAIDER_PROVIDER=openai` now works end-to-end against a live OpenAI-compatible endpoint. Before this fix the live branch of the prelude read console-world statics (`MainBridge.backend/tools/liveModel`); the in-process scala3-repl classloader loads `raider.repl.MainBridge` SELF-FIRST, so the interpreter world saw its own never-set ThreadLocals (`liveModel` → `"scripted"`, `backend` → `null`), the session was built with a null backend, and every `scout.ask` died with `NullPointerException` at `StreamingDisplay.scala:29`. Root cause matches the integration finding already recorded in the class doc (facade statics exist twice) — Phase-3 live wiring violated it; the scripted branch complied, the live branch did not.

Fix: the live prelude now CONSTRUCTS config/streaming backend (with RateLimitRetries) and CodingToolset INSIDE the interpreter world via prelude strings, exactly like the scripted branch constructs its backend. Console-world `MainBridge` had no remaining users and was deleted. The banner backend line is honest by contract: scripted fixtures say so; live sessions print `live — OpenAI-compatible streaming, model <RAIDER_MODEL>` instead of `unknown backend`.

## Changed paths

- `modules/repl/src/main/scala/raider/repl/Main.scala` — live prelude inline wiring; removed `MainBridge.setupLive` call; class doc updated (regression finding 2026-10-08).
- `modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala` — `printBackendLine` names the live model; never prints bare "unknown".
- `modules/repl/src/main/scala/raider/repl/MainBridge.scala` — deleted (unused after fix; misleading ThreadLocal-is-JVM-wide premise).

No public API/schema changes. No changes outside `modules/repl/**`.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Bridge differential probe (fix baseline) | interpreter world: `liveModel = "scripted"`, `backend = null`, `MainBridge.getClass.getClassLoader = dotty.tools.repl.AbstractFileClassLoader`, thread `main` (split confirmed, not a ZIO thread hop) | session transcript 2026-10-08 |
| Live round trip | `scout.ask("Ответь ровно одним словом: живой?")` → streamed `Да`, `res0: String = "Да"` via local llama.cpp (127.0.0.1:8081, Qwen3.8-27B BF16) | session transcript 2026-10-08 |
| raiderRepl unit tests | 21 passed / 0 failed (incl. `configured live model name replaces the scripted label`) | sbt raiderRepl/test |
| Non-TTY smoke | PASS (exit 0, all assertions) | scripts/quality/repl_smoke.sh output |
| PTY smoke | 13/13 PASS (compile-error-keeps-state, ask, start-nonblocking, await, jobs, reset-prelude, idle-ctrlc, clean-exit …) | scripts/quality/repl_pty_smoke.py output |
| quality-changed EXECUTE | passed 7/7, profiles contracts/docs/self/static/unit | artifacts/quality/20261008-210837-43784-0001/manifest.json |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch --error raiderRepl/compile` | 0 | — |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch raiderRepl/test` | 0 | `21 tests passed. 0 tests failed.` |
| `sh scripts/quality/repl_smoke.sh` | 0 | `[smoke] non-TTY smoke PASS` |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 | `checks: 13/13 passed` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (after one scalafmt round-trip; first run failed scalafmt-check, formatted via `sbt scalafmtAll`, rerun green) | artifacts/quality/20261008-204652-42339-0001 (fail) → artifacts/quality/20261008-210837-43784-0001 (pass) |
| piped live probe: `printf 'scout.ask(...)\\n:exit\\n' \| env RAIDER_PROVIDER=openai RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=<qwen-gguf> java -cp <raiderRepl fullClasspath> raider.repl.Main` | 0 | transcript above |

## Limitations / blockers

- Live verification is against the owner's LOCAL llama.cpp stand only (no paid remote spend). OpenAI-compatible remote endpoints use the same wire and remain pending owner-configured live-wire verification (standing obligation).
- `printBackendLine` still identifies any non-scripted backend by the session's configured model name; a future non-OpenAI backend would need the match extended. Honest-labeling obligation is covered, backend-type introspection is not attempted.
- The `-usejavacp` self-first classloader behavior is the standing constraint; any future cross-world transfer must go through prelude-evaluated construction, never console-world statics.

## Review and next task

No independent review yet for this fix (owner-attended debugging session; all gates green). Recommend folding into the next wave review alongside W1 lane results. Next ready cards per board: RAI-059 (jobs/budget/delegation emitters), RAI-063 (OOB cancel/deadlines), RAI-061 (tab shell).
