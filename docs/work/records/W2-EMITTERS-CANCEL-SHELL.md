# W2-EMITTERS-CANCEL-SHELL: work record

Status: done. Date: 2026-10-09 UTC. Baseline: интегрированное дерево
[W1](W1-EVENTS-TABS.md). Исполнение: coordinator + 3 writer (облачные агенты;
local-qwen по решению владельца не использовался) в изолированных snapshot-lanes
(a4/b4/c4, base 256 файлов). Инциденты: reboot машины стёр temp-lanes (потеря
только одного partial-файла lane-b4; восстановлено), повторные 500/abort
инфраструктуры — все restart'ами без потерь.

## Resulting behavior

1. **RAI-059 (P1.B-2, accepted)** — job-уровень стал источником событий:
   `JobManager.make(onTransition)` — хук после STM-коммита (None = нулевой
   overhead); `StatusLedger` → JobStatusChanged + ровно один Terminal(outcome)
   на job (поздние наблюдения не эмитят); sessionId=SessionId("") плейсхолдер;
   `BudgetLedger` → BudgetChanged(reserved, observed, unknownAttempts) на
   forked daemon (accounting не блокируется); `DelegationToolset` →
   Delegated(childJobId, agent) на форке + ChildAwaited-эквивалент Diagnostics
   в per-root пуле (root-пул никогда не получает Terminal — инвариант для
   RAI-062). 127/127 в lane, 137/137 на integrated (после merge с 063).
2. **RAI-063 (P3.B, accepted)** — `cancelOutOfBand`/`cancelAllOutOfBand`:
   cancel ВНЕ model admission (REPL/UI/owner канал), припаркованный child при
   maxLLM=1 отменяется ≤5s ×20 без второго слота (CANCEL-01); child deadlines:
   owner-side watchdog + bounded grace (RaiderScope паттерн), Uncertain честный;
   latest-wins replace задокументирован; observer-interrupt ≠ owner-cancel —
   залочено тестами; cancelInvoke/tool-схемы не изменены. 119/119 в lane.
3. **RAI-061 (P2.B, accepted)** — `raider.ui.TabShell`: JLine-изоляция за
   ShellTerminal (один writer, только printAbove); чистые CommandRouter +
   TabBarRenderer (тестируемы без терминала); команды `:tabs :new :switch
   :next :prev :rename :close :help :quit`; close running → Hide|CancelRequested
   (не отменяет сама); root close перечисляет children; draft байт-точен (raw
   line сохранён — найден и исправлен баг trim); SessionRegistry.rename
   (аддитивно, RAI-057 тесты нетронуты). 16/16 ui.
4. **Merge DelegationToolset** (обе lanes трогали файл): diff3 + ручной union —
   ревью подтвердило «pure additions» с обеих сторон, BUD-03 gate первый,
   cancelInvoke байт-неизменен, параметры root/defaultChildDeadline/deadlineGrace/
   delegationEvents с defaults; все 25 call-site не сломаны.

## Changed paths

- `modules/runtime/src/main/scala/raider/runtime/jobs/{JobManager.scala,StatusLedger.scala}`, `budget/BudgetLedger.scala`, `delegate/{DelegationToolset.scala,DelegationEvents.scala}`
- `modules/runtime/src/test/scala/raider/runtime/{jobs/JobEmissionSpec,budget/BudgetEmissionSpec,delegate/DelegationEmissionSpec,delegate/DelegationCancelSpec}.scala`
- `modules/ui/src/main/scala/raider/ui/{SessionRegistry.scala (mod),CommandRouter,ShellCommand,ShellExecutor,ShellFeedback,ShellTerminal,TabBarRenderer,TabShell}.scala`, `modules/ui/src/test/scala/raider/ui/TabShellSpec.scala`
- `build.sbt` (JLine 4.0.14 → raiderUi, coordinator)
- `docs/tasks/*`, `docs/work/board.md`, этот record

## Acceptance evidence

| ID | Result | Evidence |
| --- | --- | --- |
| EMJ-01..03 | 18 emission-тестов; cancel/success race 100 iter — без потери/дубля Terminal; hook-absent parity | lane-a4-evidence/work-record-RAI-059.md |
| CANCEL-01..03 | ×20 ≤5s; Uncertain honesty; observer≠owner; no-deadlock ×3 | lane-b4-evidence/work-record.md |
| SHELL-01..03 | 16/16 ui; draft byte-exact; one-writer; close decisions | lane-c4-evidence/WORK-RECORD-RAI-061.md |
| Integrated gates | EXECUTE 7/7 (финал после cleanup); static/unit/contracts/runtime/ci passed | artifacts/quality/20261009-100041-70734-0001 и execute-манифест этой сессии |
| Review | Все три ACCEPTED; merge проверен как pure-addition с обеих сторон | ревью-отчёт сессии |

## Commands

| argv | Exit |
| --- | --- |
| `sbt --batch raiderRuntime/test` | 0 — 137 passed |
| `sbt --batch raiderUi/test` | 0 — 16 passed |
| `sbt --batch scalafmtCheckAll` | 0 |
| `make quality-changed QUALITY_EXECUTE=1` | 0 |
| `make quality-profile PROFILES="static unit contracts runtime ci"` | 0 |

## Limitations / blockers

- **Delegated-race**: мгновенно завершающийся child может опередить публикацию
  Delegated (post-Terminal Malformed, молчаливое поглощение) — обязательство
  RAI-062 (подписка на child stream до возврата delegate или эмиссия из spawn).
- **Root-пул инвариант** (JobId(rootId.value) без Terminal) — сохранять в 062.
- setChildDeadline latest-wins не покрыт тестом; watchdog-мапы не чистятся
  (growth в очень долгоживущем toolset) — мелкие будущие карточки.
- TabBarRenderer: lag unread на один bump (view race, задокументирован;
  счётчик реестра точен).
- Yabanin.ai демо заблокировано на стороне владельца: агент-ключу не задан
  дневной бюджет (`agent_budget_not_configured`); локальный quickstart-стенд
  поднят (mock, 127.0.0.1:8082).

## Review and next task

Ревью: ACCEPTED ×3 (cleanup dup-imports применён до финального EXECUTE).
Next по DAG: **RAI-060** (status line/headless export; deps 058+059 — оба
accepted → READY) и **RAI-062** (PTY-интеграция вкладок + M1 demo; deps
060+061). Дополнительно: обязательства RAI-062 из ревью (Delegated-race,
root-пул). После RAI-060+062 — **M1 demo** (5 вкладок, живые статусы).
