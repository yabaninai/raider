# P0.A: фактическая матрица source/board/evidence (W01–W18, UX01–08)

Дата: 2026-10-05. Baseline: commit `7ee6c0c` + незакоммиченные planning-документы
(см. [ROADMAP-12K record](../work/records/ROADMAP-12K.md)). Метод: read-only
инспекция actual исходников тремя независимыми inventory-проходами (runtime
events/jobs/delegate; CLI/REPL/session/terminal IO; tools/build/gates/backlog),
сверка с board и registry. Это inventory, не acceptance: ни одна строка ниже не
создаёт нового зелёного статуса. Канонический порядок — [roadmap-12k](../roadmap-12k.md).

## 1. Матрица W01–W18

Легенда: **implemented** — код существует и покрыт тестами/evidence-записью;
**partial** — реализован slice, часть обязательных cases отсутствует;
**absent** — кода нет; **deferred-P11** — отложено roadmap-12k осознанно.

| ID | Область | Статус | Что доказано (evidence) | Что осталось до обязательной приёмки |
| --- | --- | --- | --- | --- |
| W01 | Установка | partial | pinned toolchain (sbt 1.11.4, Scala 3.9.0, ZIO 2.1.26); fat-jar `raider-cli.jar` smoke PASS (NIGHTLY-P5); GitHub Actions workflow существует | clean install/upgrade/uninstall rehearsal; `raider --help`; формальная W01-приёмка на candidate |
| W02 | Native coding loop | implemented* | AgentLoop + tool round; SELFDEV: coder agent с real fs_read/fs_search/proc_run и live openai провайдером (2026-10-04) | полная приёмка на одном candidate с hidden assertions; «*» — slice evidence, не full W02 |
| W03 | Файловые tools | partial | read/search/tree/edit/patch (unified diff, sha+atomic), RAI-014-SLICE 13/13; bounded tree со skip-list | multi-file patch remainder (RAI-015); continuation для truncation — проверить; partial-failure contract cases |
| W04 | Реальные процессы | partial | argv/cwd/env allowlist, exit/deadline, RAI-016-SLICE 6/6; `CleanupGuarantee.DirectChildOnly` | grandchild cleanup; child deadlines; OOB-cancel (§11 policy card) |
| W05 | Оба direct wires | implemented* | OpenAI: non-stream+SSE+tool_calls, live-verified (RAI-011/012); Anthropic: Messages, native tool_use/tool_result (RAI-013) | единая приёмка обоих wires на одном candidate; cancelled/error/usage matrix |
| W06 | Policy | partial | ToolDenied→exit 22, workspace containment, env allowlist, sandbox-capability reporting | secrets/protected files недоступность — cases; child policy intersection; noTTY NeedsApproval |
| W07 | Контекст проекта | partial | config slices (RAI-005-SLICE-A 13/13, CFG-01..03) | instructions/workspace/profile precedence (RAI-029), provenance, silent policy expansion cases |
| W08 | Sessions | partial | SessionStore: atomic save/load/list, corrupt-skip; compaction auto+manual (threshold 30/keep 20) | versioned schema (сейчас поля без version), journal/reopen, restore после crash, миграции |
| W09 | Сабагенты | partial | JobManager (bounded retention, cancel-wins race), delegate/await/cancel/list, maxLLM=1 no-deadlock, один root-ledger | `send` absent (typed refusal); watch — только initial+final; stuck child блокирует cancel при maxLLM=1; deadlines/OOB cancel |
| W10 | Настоящий REPL | implemented* | реальный dotty ReplDriver, multiline, bindings сохраняются, PTY smoke 13/13 (REPL-FAST) | completion возвращает Nil; `:type` отказ; streaming вывод не защищает edit buffer (см. §3) |
| W11 | DSL | partial | AgentAsk lazy, all/batch/openSession, andThen composition; ContractsSpec 35 PASS | полный golden-scenario corpus одной приёмкой; bare-Task UX cases |
| W12 | CI portability | partial | headless nonTTY, SIGTERM bounded (5s), artifacts run/events/summary/transcript, exit-мэппинг 18 семейств | GitHub/GitLab context fixtures (RAI-025), JUnit schema, trust preflight (RAI-023) |
| W13 | Расширение | partial | BundleLoader: jar + META-INF/raider-bundle.json + BundleWorkflow SPI (workflow packaging) | пользовательский tool/profile SPI (RAI-031) — absent; plugin lifecycle — absent |
| W14 | Root limits/accounting | implemented* | BudgetLedger: reservations/settlements/late/uncertain, UsageReport known/unknown, admission caps; stress 7/7 ×3 | одинаковое accounting в UI и CI на одном candidate |
| W15 | Самостоятельный вход | partial | `--agent scout/worker/coder`, chat/run без workflow; env-based setup в REPL | guided setup/doctor; UX01 rehearsal; --api-key в argv (см. §4) |
| W16 | Task fixtures | partial | CompileFixLoop (:fix ≤3), mock fixture marker в summary.md | corpus review/bugfix/new-file/refactor/research + hidden tests (RAI-046), verifier вне workspace |
| W17 | Gateway | deferred-P11 | исторические материалы сохранены; ADR-022/023 зарезервированы, файлов нет | всё: P11 после M4; ранний discovery/adapter/session keys запрещены |
| W18 | Итоговая интеграция | absent | — | все W01–W17 на одной immutable сборке; только после P11 |

## 2. Матрица UX01–08 (текущее состояние → цель)

| ID | Требование | Фактически сейчас | Разрыв |
| --- | --- | --- | --- |
| UX01 | Setup direct provider ≤10 мин | env `RAIDER_PROVIDER/BASE_URL/MODEL` в REPL; CLI flags `--provider/--base-url/--model/--api-key`; mock по умолчанию | нет guided setup/doctor; замеров нет |
| UX02 | Вкладки ≤2 нажатий, p95 ≤100 ms | `modules/ui` ABSENT; вкладок нет | весь P2; метрика неприменима до M1 |
| UX03 | Статус агента, event→visible p95 ≤250 ms | pull-only `JobSnapshot` (Queued/Running/Waiting/WaitingApproval/Cancelling/Terminal); подписки нет; ChatLoop печатает trace в stderr без различения причин | P1.A/B/C: envelopes, snapshot+subscribe, причины ожидания |
| UX04 | Старт UI p95 ≤2s warm | REPL старт не измерен на p95; PTY smoke есть, метрики нет | baseline замеров в P0.C |
| UX05 | Плагин ≤3 действий | plugin manager/SPI absent | весь P4 |
| UX06 | Cancel одним жестом ≤5s ×20 | cancel_agent/cancelAny/Admission.cancelRoot/scope-grace существуют; REPL foreground eval синхронный без cancel; proc cleanup DirectChildOnly | controlled fixture ×20; OOB subtree cancel (P3.B) |
| UX07 | Palette/hints/fallback | `:commands` REPL, help-текст chat; palette/hint bar отсутствуют | P2.B |
| UX08 | Промежуточный результат и причина | streaming deltas → stdout, tool events → stderr; usage — pull-only; стоимость unknown не отображается (UI нет) | P1.B/C: bounded tool progress, retry delay, honest unknown |

## 3. Send / watch / cancel — проверенные текущие проблемы

1. **send отсутствует намеренно**: `DelegationToolset.scala:33` («send_agent is
   deliberately absent»); REPL facade: `FacadeOps.scala:246-252` бросает
   `RaiderError.CapabilityUnsupported`. Обязательство P3.A (bounded mailbox),
   не регресс.
2. **watch ограничен**: `FacadeOps.scala:233-244` — snapshot + terminal snapshot;
   per-event streaming помечен «beta obligation, cut from W10». Живой
   подписки в runtime нет: единственный механизм — callback
   `trace: TraceEvent => UIO[Unit]` (`AgentLoop.scala:60`), pull-методы
   JobManager (`snapshot/adminView`), Promise-based await.
3. **cancel**: пути `JobManager.cancel/cancelAny` (STM-переход в Cancelling +
   interrupt), `Admission.cancelRoot`, `RaiderScope.close(grace)` с
   `uncertainChildren`. Задокументированный дефект: припаркованный child при
   maxLLM=1 держит единственный model slot и блокирует cancel_agent родителя
   (`AgentLoopDelegationSpec.scala:389-393`; тест гоняется при llm=2). Это
   обязательство P3.B (OOB cancel), должно остаться видимым до закрытия.
4. **События, которые не эмитятся вообще**: admission wait, budget
   reserve/settle, retry (RateLimitRetries emits nothing), delegation
   (ChildDelegated/ChildAwaited определены в core, но не эмитятся),
   `ToolCallFinished(success=false)`, tool timeout. `ModelCall/ToolCall*`
   хардкодят `round = -1` (AgentLoop.scala:168,215,241,266).

## 4. Аудит-кейсы P0 (обязательные)

| Кейс | Факт |
| --- | --- |
| Ctrl+C bindings | REPL: JLine `Reference("abort")` на keymap `main` (`repl/Main.scala:150-156`); UserInterruptException очищает pending. Foreground eval синхронный — отмены нет (spike-only). CLI chat: raw BufferedReader, обработки сигналов ввода нет; SIGTERM hook есть (bounded 5s) |
| Glyph width | Обработки width нет нигде; будущий renderer обязан вести Cyrillic/CJK/emoji/combining (один `Char` ≠ cell). Замер — P0.C spike |
| Provider/model inheritance у child | Child-цикл получает тот же `ModelBackend` (DelegationToolset.make); передача model-строки в child `runText` из parent-конфигурации — уточнить в P1-карточке (в inventory не подтверждено явно) |
| Setup без localhost probe, credentials не в argv | НАЙДЕН ДЕФЕКТ: `run/CliArgs.scala:67` принимает `--api-key` в argv (default "no-key") — секрет может попасть в shell history/process listing. Нужна карточка: env/helper references only, deprecate argv-флаг. Неявного localhost probe не обнаружено |
| Terminal writers | Единого writer нет: 4 независимых пути (REPL facade stdout/stderr, ChatLoop stdout/stderr+println, HeadlessRunner stderr, ReplCommands println); синхронизации нет |

## 5. Gate registry / runner reconciliation

- Registry (`scripts/quality-policy/registry.json`): 12 gates, 11 профилей,
  **все stage=component**; milestone/full/release/security/eval/performance/ui/plugin
  профилей как исполняемых нет (есть только как metadata в backlog).
- **Подтверждён дефект runner** (`scripts/quality/quality.py`):
  `classify` (:91-106) возвращает dict, ключи — имена профилей (значения — пустые
  списки); `cmd_execute` (:186-190) ищет в нём **путь файла** → всегда miss →
  `__unmapped__` → «changed files map to no profile» → exit 1 **до** исполнения
  gate. С любыми изменениями (git или manifest) `quality.py execute` всегда
  падает; зелёные прогоны NIGHTLY были на чистом дереве (changes=[] → fast).
- **Второй дефект runner**: `fingerprint(None)` заполняет `changes` только при
  base manifest (:84-99) — git-diff не реализован, поэтому `make quality-changed`
  без manifest **всегда** печатает «no changes vs base» даже на грязном дереве
  (проверено 2026-10-05 на текущем checkout с изменёнными tracked-файлами).
  Spec quality-gates §2 требует учитывать staged/unstaged/untracked/deleted/renamed.
  Оба дефекта фиксирует RAI-054 (отдельная карточка, independent review —
  изменение gate-инфраструктуры). До интеграции фикса обязательный
  `make quality-changed QUALITY_EXECUTE=1` после карточек невыполним честно.
- Board vs code: строки статуса согласуются (accepted slices существуют и
  покрыты тестами; тесты запускаются — full 188 green по записям). Устаревшее
  «продуктового runtime нет» в шапке AGENTS.md сам документ объявляет
  не-вечным; actual — runtime/providers/tools/REPL/CLI/session persistence есть.

## 6. Выводы для P0.B/C и первых карточек

1. P0.B (event seam) опирается на: существующий `TraceEvent` (11 типов, JSON
   codec), `JobStatus` state machine с `canTransition`, pull-snapshots,
   callback-эмиттер в AgentLoop. Frozen seam должен добавить: envelopes
   (root/session/job/event IDs, per-job sequence), snapshot+subscribe без окна
   потери, причины ожидания (admission/slot/approval), bounded tool progress,
   честный unknown для стоимости. ADR: **ADR-024** (017–023 зарезервированы
   историческим roadmap).
2. P0.C (renderer spike) опирается на: JLine 4.0.14 (repl-engine), существующий
   PTY-хелпер паттерн (`scripts/quality/repl_pty_smoke.py`: pty+select+ISIG off,
   isolated HOME, TERM фикс). Замерять: redisplay при фоновом выводе, draft
   сохранение, width Unicode, redraw latency p95.
3. Первые implementation-карточки (зарегистрированы RAI-054..057): runner-fix,
   event envelopes/hub, renderer spike, session registry skeleton. Порядок по
   DAG §5 roadmap: P0.B review → P1.A; P2.A/B могут идти на frozen interfaces +
   scripted source параллельно.
4. Ничего из вышеперечисленного не является «готовым продуктом»: это inventory
   для планирования; acceptance каждой вещи — отдельная карточка с actual gates.
