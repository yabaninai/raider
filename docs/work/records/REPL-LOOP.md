<!-- Work record. Original in Russian; key results in English below. -->

# REPL-LOOP (Phase B): work record

Status: done. Date: 2026-10-03. Baseline: root checkout без коммитов;
предыдущий last-passing gate: artifacts/quality/20261003-214145 (RAI-010-A).

## Resulting behavior

REPL-фасад (scout.ask / worker.start / await) исполняется через ЕДИНУЮ
агентную точку `AgentLoop` (RAI-010.a) вместо `Runner.runText`:

- `ReplSession` получил `tools: ToolRegistry` (default = empty registry,
  source-совместимо: тело по умолчанию в traitе + override в impl) — это
  freeze-точка реестра сессии;
- `FacadeOps.textAttempt` (мир интерпретатора) строит
  `AgentLoop(session.backend, session.tools, model, session.admission,
  AgentLoop.newRoot())` и вызывает `runText(messages, session.limits,
  session.limits.maxAttempts)`;
- с пустым реестром loop вырождается В ТОЧНОСТИ в прежнее runText-поведение
  (fixture-равенство в AgentLoopSpec) — все прежние REPL-сценарии идентичны;
- tools-in-loop в REPL: сессия с непустым реестром обслуживает scripted
  tool-раунд через обычный `ask` (facade-фича-тест) — механизм wiring'а
  закрыт; консольных команд регистрации tools пока нет (RAI-018/020),
  obligation уточнён, не удалён;
- статик-сплит не тронут: изменения только в фасаде; ReplCommands/Main/
  Commands не менялись; Runner.runText остаётся как есть (не сломан).

## Changed paths

```
7a0a5fd5…  modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala
53d40fbb…  modules/repl/src/main/scala/raider/repl/facade/ReplSession.scala
84ec56a9…  modules/repl/src/test/scala/raider/repl/facade/FacadeOpsSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Facade-тесты (поверх loop) | 7/7 (6 прежних идентичны + новый tools-in-loop) | raiderRepl/test |
| Полный sbt test | 111 тестов, 0 failed, exit 0 | /tmp/full-test-B.log |
| Non-TTY smoke | exit 0, все assertions; `scout.ask` отдаёт scripted answer через loop | scripts/quality/repl_smoke.sh → /tmp/repl-smoke-B.log |
| PTY smoke (реальный java-процесс) | 13/13 checks, exit 0 | scripts/quality/repl_pty_smoke.py → /tmp/pty-smoke-B.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-214446/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRepl/test"` | 0 | /tmp/repl-test-B2.log |
| `sh scripts/quality/repl_smoke.sh` | 0 | /tmp/repl-smoke-B.log |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 (13/13) | /tmp/pty-smoke-B.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (111) | /tmp/full-test-B.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-214446/ |

## Limitations / obligations

- Регистрация tools из консоли REPL (`:tools`, DSL) — RAI-018/020; session
  API уже принимает реестр, obligation УТОЧНЁН (wiring готов, поверхности
  нет), не закрыт полностью.
- Live-провайдеры, journal, :type, completion — прежние obligations
  (REPL-FAST record), не менялись.

## Review and next task

Независимый review не проводился (координаторская сессия). Следующая фаза:
RAI-002 remainder (bundle/result schemas + launch-facade capability).
