# W1-EVENTS-TABS: work record

Status: done. Date: 2026-10-07/08 UTC. Baseline: интегрированное дерево
[P0-WAVE0](P0-WAVE0.md). Исполнение: coordinator + 3 параллельных writer в
изолированных snapshot-lanes (lane-a3/b3/c3, base manifests 249 файлов),
независимое ревью обеих фаз (первичное + fix-раунды). Transport-инфраструктура
за сессию: 4 сбоя (500 «Context size exceeded»/abort) — все восстановлены
restart-раундами «assess+continue» без потери работы.

## Resulting behavior

1. **RAI-057 (P2.A, accepted)** — `raider.ui.SessionRegistry`: вкладочные данные
   над реальным EventHub (без моков): create/focus/focusNext/focusPrev
   (переключение = 1 вызов), draft ops, tabs()-view (label/jobId/статус/unread/
   lastSeq), close-семантика (running → RunningChoice(Hide|CancelRequested),
   root → список затронутых child-вкладок по rootId); bounded history 200 /
   scrollback 500 с честными eviction-счётчиками; dedup+seq-сортировка перед
   фолдом; фокус = только UI-состояние (тест-инвариант). Найден и исправлен
   баг partial-кода (runFold vs runFoldZIO — события не применялись).
2. **RAI-058 (P1.B-1, accepted)** — эмиссия из AgentLoop/RateLimitRetries в
   EventHub: Waiting(Slot) перед ожиданием слота (RELIABLE), Model/Tool события
   с реальным round (hardcode -1 устранён, включая trace), ToolFinished на
   failure/timeout/interrupt путях (Cancelled через onInterrupt-finalizer —
   catchAllCause-вариант partial был некорректен: прерывание в ZIO не ловится),
   RetryScheduled(attempt, delayMs, cause), честный unknown-usage (никогда не
   выдуманная стоимость). Option[...]=None — нулевой overhead, все 40+ call-site
   совместимы. 109/109 runtime-тестов (99+10 EMIT), специ ×3 стабильны.
3. **RAI-064 (M0, accepted)** — credentials только env/helper: `--api-key`
   удалён из argv (typed RA-CFG с подсказкой, значение никогда не эхо);
   `RAIDER_API_KEY` или `RAIDER_API_KEY_CMD` (первая строка stdout; stderr
   drained daemon-потоком; bounded 10s → typed timeout, процесс уничтожается);
   preflight ДО любого spend; маска first4+***; mock без ключа. Redaction
   fixture (sentinel отсутствует во всех артефактах). Live-пробы T1..T6/R1..R3:
   refusal-before-spend подтверждён (events.jsonl только run.started/finished).

Публичные контракты: не менялись (новые параметры — trailing с defaults).
TraceEvent: типы/codec без изменений; значения round изменились -1→реальный
(санкционировано карточкой; transcript-потребители проверены ревью).

## Changed paths

- `modules/ui/src/main/scala/raider/ui/SessionRegistry.scala` (новый), `modules/ui/src/test/scala/raider/ui/SessionRegistrySpec.scala` (новый), `modules/ui/src/main/scala/raider/ui/package.scala` (удалён placeholder)
- `modules/runtime/src/main/scala/raider/runtime/loop/{LoopEvents.scala (новый), AgentLoop.scala}`, `modules/runtime/src/main/scala/raider/runtime/display/RateLimitRetries.scala`, `modules/runtime/src/test/scala/raider/runtime/{loop/AgentLoopEmissionSpec,display/RateLimitRetriesEmissionSpec}.scala` (новые)
- `modules/cli/src/main/scala/raider/cli/run/{Credentials.scala (новый), CliArgs.scala, HeadlessRunner.scala}`, `Main.scala`, `chat/ChatLoop.scala`, tests: `run/{CredentialsSpec.scala (новый), HeadlessRunnerSpec.scala}`
- `docs/tasks/{backlog.json,m0.md,m1.md,m2-p2.md,README.md}`, `docs/work/board.md`, этот record

## Acceptance evidence

| ID | Result | Evidence |
| --- | --- | --- |
| TAB-01..03 | 5/5 ui-тестов (×2 стабильность), фокус-инвариант, eviction-консервация exact | lane-a3-evidence/WORK-RAI-057-lane-a3.md |
| EMIT-01..03 | 109/109 runtime; EMIT ×3 без flakiness; maxLLM=1 в каждом fixture | lane-b3-evidence/{final-test.log,emit-run1..3.log} |
| SETUP-01..03 | 44/44 cli; sentinel-redaction; refusal до spend (T3: events только started/finished, 30ms) | lane-c3-evidence/{logs,argv-verify,reviewfix-verify} |
| Review fixes | D1-1 (unread при create), D3-1 (helper stderr drain + bounded timeout), D3-4 relabel | lane-a3/c3-evidence + ревью-вердикт |
| Integrated gates | EXECUTE passed 7/7; profiles static/unit/contracts/runtime/ci passed (incl. cli-smoke) | artifacts/quality/20261008-001211-39194-0001, artifacts/quality/20261008-001358-40602-0001 |
| Root demo | mock run exit 0; live без ключа → exit 21 RA-CFG до spend; --api-key → typed error без эха секрета | stdout/артефакты этой сессии |

## Commands

| argv | Exit | Manifest/log |
| --- | --- | --- |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261008-001211-39194-0001/manifest.json |
| `make quality-profile PROFILES="static unit contracts runtime ci"` | 0 | artifacts/quality/20261008-001358-40602-0001/manifest.json |
| `java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent scout --provider mock --input demo --out artifacts/demo-w1` | 0 (Succeeded) | artifacts/demo-w1/work-*/run.json |
| то же с `--provider openai --base-url http://127.0.0.1:9/v1` без ключа | Failed/21 (RA-CFG preflight) | /tmp/t-rai64/work-*/run.json |
| то же с `--api-key sk-secret123` | RA-CFG: --api-key removed… (без эха значения) | stdout |
| `python3 scripts/plan/validate.py` | 0 | artifacts/plan-review/ |

## Limitations / blockers

- Terminal/JobStatusChanged/budget/delegation события — **RAI-059** (готов, не
  запущен); без него UI на живых прогонах не запускать (Terminal — авторитет
  job-слоя; ревью-caution).
- `security`-профиль отсутствует в registry (component_profiles) — обязательство
  RAI-064 остаётся открытым на milestone-уровне; изменение gate policy —
  отдельная карточка с независимым ревью (не делалась).
- Reorder gap-stall и forwarder supervision (hub) — задокументированные
  ограничения, future card.
- ToolProgress/LineTail и DiagnosticsCoalesced не эмитятся (нет producing
  surface в MVP) — швы зафиксированы.
- Unit-тест на 10s helper-timeout отсутствует (поведение доказано probe R2);
  решение о добавлении — на усмотрение карточки гейтов.
- Transport-инфраструктура: 4 сбоя сессии; в отчётах приёма это отражено.

## Review and next task

Независимое ревью: все три ACCEPTED (D1-1/D3-1/D3-4 устранены в lanes,
D2/D3-2/D3-3 — info/обязательства). Next по DAG: **RAI-059** (готов) →
**RAI-060** (status line, после 058+059 — 058 принят) и **RAI-061** (tab shell,
после 057 — принят; JLine-dep для raiderUi добавит coordinator). После 059+060
открывается RAI-062 → **M1 demo**.
