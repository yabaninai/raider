<!-- Work record. Original in Russian; key results in English below. -->

# REPL-LOOP (Phase B): work record

Status: done. Date: 2026-10-03. Baseline: root checkout without commits; previous last-passing gate: `artifacts/quality/20261003-214145` (RAI-010-A).

## Resulting behavior

The REPL facade (`scout.ask` / `worker.start` / `await`) runs through the single agent entry point `AgentLoop` (RAI-010.a), replacing `Runner.runText`:

- `ReplSession` gained `tools: ToolRegistry` (default = empty registry, source-compatible: default body in trait plus override in implementation); this freezes the session registry.
- `FacadeOps.textAttempt` (interpreter world) creates `AgentLoop(session.backend, session.tools, model, session.admission, AgentLoop.newRoot())` and calls `runText(messages, session.limits, session.limits.maxAttempts)`.
- With an empty registry, the loop behaves exactly like the former `runText` behavior (fixture equality in `AgentLoopSpec`); all prior REPL scenarios are identical.
- Tools-in-loop in REPL: a session with a non-empty registry serves a scripted tool round through ordinary `ask` (facade feature test); the wiring mechanism is covered. There are no console commands to register tools yet (RAI-018/020); the obligation is clarified, not removed.
- The static split is untouched: changes are only in the facade; `ReplCommands` / `Main` / `Commands` were not changed; `Runner.runText` remains as-is.

## Changed paths

```
7a0a5fd5… modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala
53d40fbb… modules/repl/src/main/scala/raider/repl/facade/ReplSession.scala
84ec56a9… modules/repl/src/test/scala/raider/repl/facade/FacadeOpsSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Facade tests (over the loop) | 7/7 (6 previous tests identical + new tools-in-loop test) | `raiderRepl/test` |
| Full sbt test | 111 tests, 0 failed, exit 0 | `/tmp/full-test-B.log` |
| Non-TTY smoke | exit 0, all assertions; `scout.ask` returns scripted answer through loop | `scripts/quality/repl_smoke.sh` → `/tmp/repl-smoke-B.log` |
| PTY smoke (real Java process) | 13/13 checks, exit 0 | `scripts/quality/repl_pty_smoke.py` → `/tmp/pty-smoke-B.log` |
| Quality gates | 6/6 passed | `artifacts/quality/20261003-214446/manifest.json` |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRepl/test"` | 0 | `/tmp/repl-test-B2.log` |
| `sh scripts/quality/repl_smoke.sh` | 0 | `/tmp/repl-smoke-B.log` |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 (13/13) | `/tmp/pty-smoke-B.log` |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (111) | `/tmp/full-test-B.log` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | `artifacts/quality/20261003-214446/` |

## Limitations / obligations

- Registering tools from the REPL console (`:tools`, DSL) is RAI-018/020; the session API already accepts a registry, so the wiring obligation is clarified (wiring is ready, surface is not), but is not fully closed.
- Live providers, journal, `:type`, and completion remain previous obligations (REPL-FAST record); unchanged.

## Review and next task

No independent review was conducted (coordinator session). Next phase: RAI-002 remainder (bundle/result schemas + launch-facade capability).
