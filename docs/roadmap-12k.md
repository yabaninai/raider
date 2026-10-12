# Raider: 12 000 часов до удобного ежедневного инструмента

Редакция владельца от 2026-10-05. Каноническая очередность дальнейшей разработки;
заменяет последовательность и бюджет [roadmap v2](roadmap-10k.md).
Результат: один локальный Raider с вкладками сабагентов, понятными промежуточными
статусами и устанавливаемыми плагинами. **Yabanin — исключительно P11, последняя
фаза после приёмки самостоятельного продукта.**

Это оценка оставшегося инженерного труда, а не уже выполненные часы, время работы
LLM или обещание непрерывной 12 000-часовой сессии. Реализация идёт небольшими
проверяемыми карточками. [Working acceptance](working-product.md),
[parallel runbook](parallel-development.md), [контракты](runtime-contracts.md)
и [gates](quality-gates.md) сохраняют силу с уточнением очередности ниже.
Запуск: [полный prompt OpenCode](prompts/opencode-12k-parallel.txt).

> **Ревизия 2026-10-10 (владелец, ADR-027):** добавлена фаза **P-LIN — Session
> lineage (дерево вызовов как валидная Scala-программа)**, **впереди всего
> плана**, +1 100 h (итого 13 100 h). Минимальный tab-host для вкладки Lineage
> вытягивается из P2 в P-LIN ограниченно (только одна вкладка, без черновиков).
> Остальные фазы не меняются по содержанию; накопительные суммы сдвинуты.
> Решения зафиксированы в [ADR-027](adr/ADR-027-session-lineage.md).

> **Ревизия исполнения 2026-10-12 (владелец):** доработки исполняются как
> **видимые spawn-субагенты** в интерактивной сессии владельца (`/raider-wave`,
> `.opencode/agents/raider-worker|raider-reviewer`; механика —
> [parallel-development.md §1b](parallel-development.md)). Фоновые bash-циклы
> `opencode run`/nohup-night-loop для исполнения карточек запрещены по
> умолчанию; ночной headless — только отдельным явным решением владельца.
> Одновременно с остатком P-LIN открыты параллельные lanes: **P2-остатки TUI**
> (per-tab drafts, scrollback bounds, unread badges, palette/help — P2.B/C
> остатки поверх принятых RAI-057/061/062), **P1-остатки** (RAI-059/060) и
> **P3** (RAI-063, send/steering) — зависимости §5 закрыты evidence
> (P0-WAVE0: ADR-024+renderer spike; W1: events). Это не меняет бюджет фаз:
> карточки регистрируются и принимаются по общим gates, часы списываются в
> свои фазы.

## 1. Что действительно есть

Осмотр исходников на commit `7ee6c0c`, 2026-10-05; это inventory, не новый runtime
acceptance. Board содержит более свежие session records, чем некоторые верхние
строки статуса. При каждом запуске сверять исходники, а не повторять старые оценки.

| Область | Найдено в исходниках | Следующее улучшение |
| --- | --- | --- |
| Native runtime | `modules/runtime/.../loop/AgentLoop.scala`, jobs/scopes/admission/budget | Проверить открытые cancel/ownership cases, связать с наблюдаемыми состояниями |
| Провайдеры | `modules/provider-chat`, `modules/provider-anthropic` | Сохранить оба direct wires и одинаковую приёмку |
| Coding workflow | `modules/tools`, CLI headless/chat, patch/process/search | Проверить полный цикл read → edit → failing test → fix → passing test |
| Scala REPL | `modules/repl-engine`, `modules/repl`; настоящий compiler и facade | Сохранить bindings/многострочный ввод, добавить completion при TUI |
| Сабагенты | JobManager, DelegationToolset, `start/await/cancel` | `send` пока отказывает; `watch` только initial/final snapshot; нужны живые вкладки |
| Статусы | TraceEvent, JobSnapshot, StreamingDisplay | Единая подписка, актуальное состояние, причины ожидания, bounded tool progress |
| История | `modules/cli/.../chat/Session.scala`: SessionStore и compaction | Миграции, восстановление workspace/tabs, честный статус после crash |
| Плагины | ToolRegistry, BundleLoader как отдельные основы | Нет реализованного пользовательского plugin lifecycle/loader |
| UI | JLine и [ADR-016](adr/ADR-016-tui-renderer.md), [дизайн](tui-design.md) | `modules/ui` ещё нет; текущий вывод линейный |

В начале работы есть пользовательский untracked
`docs/research/yabanin-state-discovery.md`: сохранить, не считать принятым контрактом
или разрешением начинать интеграцию раньше P11.

## 2. Сценарий, ради которого делается продукт

Пользователь запускает `raider`, выбирает direct provider при первом входе и пишет
«исправь тесты в этом проекте». Главный агент создаёт scout/coder/reviewer;
каждый появляется во вкладке с именем, текущим действием и непрочитанными событиями.
Пользователь переключается между ними, читает ход работы, дописывает ограничение,
открывает diff и результат проверок, отменяет выбранного агента либо всю группу.
Для обычной задачи не требуется писать Scala; полноценный Scala REPL доступен
как режим для сложного workflow и использует тот же runtime.

```text
[Главный] [Scout: поиск] [Coder: тесты 12с ●] [Reviewer: ждёт слот] [+]
Проект scalaagent · 3 работающих · бюджет: известная сумма / неизвестно
Coder → proc_run: sbt test · вывод обновлён 0.4с назад
Изменения: 3 файла · проверки: выполняются · результат: ещё не готов
> Дополнение выбранному агенту…
Следующая вкладка · Новый агент · Команды · Плагины · Помощь
```

Это макет и будущий UX, не существующая команда с доказанной приёмкой.

### Измеримые требования удобства

| ID | Требование | Как принимать |
| --- | --- | --- |
| UX01 | Setup direct provider, без Yabanin и без написания workflow | 4 из 5 новых пользователей завершают offline training task за ≤10 минут; live credentials вводятся отдельно |
| UX02 | Переключение вкладки ≤2 нажатий, p95 ≤100 ms | 5 агентов, 1 000 переключений; не теряются черновик, history, Unicode и положение прокрутки |
| UX03 | Каждый агент имеет понятный текущий статус | Event→visible p95 ≤250 ms на закреплённой машине; состояние «ожидание» содержит причину |
| UX04 | Быстрый старт локального UI | Готовый prompt p95 ≤2 s warm, ≤5 s cold после установки; сетевой TTFT измеряется отдельно |
| UX05 | Плагин доступен без пересборки Raider | Установка доверенного fixture plugin и запуск его tool ≤3 действий после выбора источника; допускается явный restart |
| UX06 | Понятная отмена | Один жест cancel выбранного агента; cancel root отдельный; owned subtree/processes завершаются ≤5 s в controlled fixture ×20 |
| UX07 | Нет команд, которые надо помнить | Palette, hint bar, поиск действий, fallback keybindings, текстовые статусы без зависимости от цвета |
| UX08 | Видны промежуточный результат и причина проблемы | Diff/checks, retry delay, permission request, token usage; стоимость unknown отображается как unknown, не 0 |

Пороговые значения — требования к будущим проверкам. P0 фиксирует hardware/JDK/
terminal/load/число samples и способ замера; пороги нельзя ослабить после неудачи.
Human usability без участников остаётся pending. Progress показывает наблюдаемые
шаги, вывод tools и краткие summaries; не выдумывает проценты завершения или мысли модели.

## 3. Бюджет и результаты фаз

Часы включают разработку, integration/review, проверки, документацию и резерв.
Каждая строка уже включает свою долю общих расходов; повторного начисления за
coordinator или тесты нет. Сумма **13 100 h** (после ревизии 2026-10-10: +1 100 h на P-LIN), в том числе 1 200 h резерва (10%).

| Фаза | Проверяемый результат | Часы | Накопительно |
| --- | --- | ---: | ---: |
| P-LIN | Session lineage: дерево вызовов = валидная Scala-программа, replay | 1 100 | 1 100 |
| P0 | Actual baseline, остаточные дефекты, согласованные seams и acceptance | 400 | 1 500 |
| P1 | Живые правдивые статусы и bounded tool progress | 900 | 2 400 |
| P2 | Вкладки, черновики, scrollback, первый простой TUI | 1 400 | 3 800 |
| P3 | Удобная координация сабагентов, steering/cancel/review | 1 000 | 4 800 |
| P4 | Plugin lifecycle, SDK и установка без пересборки | 1 400 | 6 200 |
| P5 | Опциональный локальный agent state и shared blackboard | 900 | 7 100 |
| P6 | История, восстановление, compaction и контроль бюджета | 1 100 | 8 200 |
| P7 | Полный standalone coding workflow, REPL и CI | 1 000 | 9 200 |
| P8 | Несколько проектов, нагрузки и длительная работа | 900 | 10 100 |
| P9 | Fullscreen при необходимости, доступность, onboarding и UX polish | 900 | 11 000 |
| P10 | Приёмка, packaging и стабильный standalone release candidate | 1 000 | 12 000 |
| P11 | Yabanin: discovery → optional adapter → итоговая приёмка | 1 100 | 13 100 |

Ориентировочная структура всех часов: implementation 7 200; проверки/review/
интеграция 3 000; docs/onboarding 600; резерв 1 200. Это две проекции одного бюджета,
не дополнительные часы. Резерв расходуется внутри фазы на выявленные риски,
остаток не превращается в новые функции. После фаз пересчитываются estimate-to-complete
и остаток; экономию не надо искусственно «дорабатывать» до 12 000.

Первые полезные поставки: M1 после P2 (вкладки+статусы), M2 после P4 (плагины),
M3 после P7 (полный standalone workflow), M4 после P10 (standalone candidate),
M5 после P11 (optional gateway suite). Это накопленная трудоёмкость, не календарь:
независимые пакеты фаз могут перекрываться. При 4 участниках по 30 продуктивных h/нед
арифметический минимум — 100 недель; зависимости, review и доступность людей дают
ориентир 26–36 месяцев. Это предположение для планирования, не гарантия ускорения LLM.

## 4. Пакеты работ и критерии выхода

Пакеты `P*.A/B/C` ниже — бюджетные work packages, не огромные coding assignments.
Перед исполнением coordinator режет пакет на карточки 8–24 h с одним observable
result, exact allowed files, normal/error/cancel cases и dependency IDs. Budget
child cards суммируется в родительский пакет; существующая работа повторно не оплачивается.
Новые карточки получают свободные IDs и metadata в `docs/tasks/backlog.json` с
обновлением соответствующих markdown/index по действующему validator.

### P-LIN — Session lineage: дерево вызовов как валидная Scala-программа, 1 100 h

Впереди всего плана по решению владельца 2026-10-10 (ADR-027; все решения — там).
Карточки: [docs/tasks/lineage.md](tasks/lineage.md); метаданные — в backlog.json
(milestone `LIN`). Текущая карточная кампания продолжается фоном — она закрывает
зависимости RAI-020/022.

- P-LIN.01 **140 h**: SessionGraph-модель (event-sourced): узлы ModelCall /
  ToolCall(kind, RecoveryClass) / Delegation / Phase; редуктор events → граф;
  фаззи-тесты на реальных events.jsonl. Расширяемость под будущие тулколлы.
- P-LIN.02 **160 h**: проекция граф → валидная Scala (композиции Program/Task,
  RAI-017 как цель компиляции); данные — lineage-data.json, не код.
- P-LIN.03 **120 h**: лайв-инкрементальная поддержка: граф и проекция растут в
  ходе сессии; периодическая инкрементальная компиляция-валидация.
- P-LIN.04 **180 h**: вкладка Lineage в TUI (минимальный tab-host, вытянут из
  P2 ограниченно: одна вкладка, без черновиков): дерево + исходник + статус
  валидности; доступ во время сессии.
- P-LIN.05 **120 h**: replay-recorded интерпретатор (default) + компаратор
  «воспроизведённый граф == записанный» (успех = тот же граф вызовов).
- P-LIN.06 **140 h**: live re-run (opt-in): изолированная копия workspace для
  Mutating (RecoveryClass-политика), бюджет, diff-отчёт «живой vs записанный».
- P-LIN.07 **80 h**: частичный replay именованных фаз/поддеревьев.
- P-LIN.08 **60 h**: области: chat + headless + ночные кампании (каталог
  деревьев); сверка pinned-окружения, предупреждения, отчёты расхождений.
- Резерв **80 h**.

Exit: для каждой сессии — lineage.scala, компилирующаяся и воспроизводимая в
replay-режиме с идентичным графом; вкладка TUI показывает живое дерево;
live re-run в изоляции даёт diff-отчёт; CI-гейт валидирует компиляцию проекции.
Гибрид (LLM из записи + пересчёт детерминированных тулколлов) — основной режим.

### P0 — достоверная отправная точка, 400 h

- P0.A **120 h**: source/board/gate reconciliation; matrix W01–W18 «реализовано /
  доказано / осталось»; проверить текущие send/watch/cancel issues.
- P0.B **160 h**: согласовать runtime→UI event seam, snapshot+subscribe,
  ownership/UI focus, progress contract; ADR с независимым review, сохранять ADR-016.
- P0.C **120 h**: JLine inline renderer spike в PTY, установка baseline,
  воспроизводимые UX замеры и первые child cards; описать отдельные verifier cards.

Exit: один воспроизводимый baseline, настоящие exits/logs, frozen interfaces,
изоляция lanes, первые 3 independent packets. Дефекты protected registry лечатся
отдельной карточкой с независимым review; roadmap не меняет gate policy.

Обязательные audit cases: текущие Ctrl+C bindings и foreground/observer/edit
семантика; glyph width для Cyrillic/CJK/emoji/combining characters (одного `Char`
на cell недостаточно); provider/model inheritance у delegated child; setup без
неявного localhost probe и без credentials в argv (только env/helper references).

### P1 — статусы, 900 h

- P1.A **320 h**: envelopes с root/session/job/event IDs и per-job sequence;
  reducer/snapshot плюс subscribe без окна потери; совместимость schema.
- P1.B **330 h**: события admission/model/tool/retry/compaction/budget;
  streamed bounded process tail, redact и escape terminal control characters.
- P1.C **250 h**: status line/timeline и headless event export; burst/slow consumer/
  ordering/cancel tests, reconnect и terminal-state consistency.

Exit: UI различает queued, model, tool, waiting approval/child/slot, retry,
cancel requested и terminal status; UX03/08. Ledger является источником состояния;
accounting не вычисляется из потерь UI stream. Diagnostics можно coalesce/drop с
счётчиком потерь, terminal/approval/accounting требуют отдельного надёжного пути.
Slow viewer не блокирует cancellation. Нет неограниченной очереди ради «не терять».

### P2 — вкладки, 1 400 h

- P2.A **450 h**: SessionRegistry поверх существующих jobs, identity/focus,
  per-tab draft/history/scrollback, bounded buffers и unread badges.
- P2.B **500 h**: inline shell JLine, palette/help, new/switch/rename/close,
  отдельная навигация parent/child; input router на immutable session ID.
- P2.C **450 h**: подключение events/runtime и настоящее PTY испытание:
  Unicode/paste/multiline/resize/focus во время streaming; headless regression.

Exit M1: пользователь запускает задачу, видит 5 независимо работающих агентов,
переключается и возвращается к неизменённому черновику. Закрыть вкладку ≠ отменить
работу: running tab предлагает hide или cancel; закрытие root обрабатывает детей.
UI focus не меняет owner/budget/policy. Plain chat/REPL/run остаются доступными.

### P3 — управление сабагентами, 1 000 h

- P3.A **350 h**: bounded mailbox для `send`, message ID/ack и применение на
  границе turn; редактирование задания не подменяет уже выполняющийся tool call.
- P3.B **350 h**: out-of-band subtree cancel, deadlines, maxLLM=1 regression,
  fair admission, live watch; observer interrupt и owner cancellation различимы.
- P3.C **300 h**: delegate templates, lineage, передача результата и review diff;
  отдельные write workspaces для product agents, явный integration owner.

Exit: scout→coder→reviewer workflow; clarification reaches нужного ребёнка ровно
по message ID, не соседнюю вкладку; mailbox overflow — typed refusal; parent/child
cancel UX06 и budget tests. Не вводить второй scheduler. Если работа уже terminal,
send явно отклонён или предложен новый turn; никаких обещаний replay мутаций.

### P4 — удобные плагины, 1 400 h

- P4.A **450 h**: narrow versioned Scala SPI (tools/profiles/hooks), manifest,
  compatibility/duplicate-name preflight, controlled classloader/resource lifecycle.
- P4.B **500 h**: UI plugin manager и CLI install/list/check/enable/disable/update/
  remove/rollback, pinned versions/checksums, user vs workspace config precedence.
- P4.C **450 h**: SDK/template, документация, три local fixture plugins,
  совместимость API версий, crash/cleanup/failed-install/rollback acceptance.

Exit M2: из UI выбрать локальный JAR → увидеть source/version/capabilities →
подтвердить доверие → использовать tool; Raider не пересобирается, UX05.
Для удалённых источников bounded download/checksum, никакого shell install script.
Workspace не активирует code автоматически; plugin config не даёт расширить
policy/root budget. Hooks не разрешают то, что запрещено core policy. Обновление
активного plugin применяется к новым sessions после drain/restart, без обещания
безопасного hot unload JVM code. Версия/конфигурация plugin закреплены за run.

Classloader — граница совместимости, **не security sandbox**: Scala plugin имеет
полномочия процесса; System.exit/native crash не гарантированно перехватываются.
Дефекты, которые можно поймать, превращаются в typed failure; зависание требует
bounded cancellation и честного сообщения. Для недоверенного кода отдельный
process isolation проект за пределами этого бюджета. Публичный
marketplace, биллинг и SDK на других языках не нужны для удобного v1.

### P5 — локальное состояние, 900 h

- P5.A **300 h**: default-off state SPI, Memory/File backends, Agent/Root scopes,
  explicit Global, quotas, revision/CAS для конкурентных записей, atomic replace.
- P5.B **300 h**: state_get/put opt-in tools, provenance/inspect/reset/export,
  shared blackboard для parent/children; ограничения ключей и значений.
- P5.C **300 h**: lost-update/corruption/scope isolation tests, migration и SDK
  state backend seam; два агента координируются локально.

Exit: disabled не создаёт store/tools/background work; enabled переживает restart
в объявленной durability модели. Persisted state — недоверенные данные, не
инструкции/credentials. Размеры/число ключей ограничены. Remote/Yabanin отсутствует.
Atomic rename не объявляется защитой от потери питания без отдельного fsync contract.

### P6 — восстановление и контроль, 1 100 h

- P6.A **400 h**: versioned session/journal/checkpoint, tabs/history/plugin pins,
  crash recovery и миграции; раздельно restore history и resume execution.
- P6.B **350 h**: bounded context/compaction, root/tab budgets и unknown usage,
  reservations/refunds, согласование snapshot со state revisions.
- P6.C **350 h**: SIGTERM/kill -9/corrupt tail/disk-full cases; owned child cleanup,
  stale leases/statuses и observable partial failure.

Exit: после crash вкладка показывает interrupted/unknown, а не «выполняется» без
живого процесса. Чтение истории не перезапускает мутации; новая попытка требует
проверки postconditions/решения пользователя. Нет exactly-once/durable resume
произвольной Scala lambda. UI и CI показывают одинаковый root accounting.

### P7 — standalone workflow целиком, 1 000 h

- P7.A **350 h**: остаток tools/policy/project context; реальные read/edit/process
  и negative cases, protected files, deletion/partial patch errors.
- P7.B **350 h**: REPL compiler/completion и headless adapters над одним runtime;
  non-TTY/SIGTERM/exits/artifacts/JUnit/trust-boundary acceptance.
- P7.C **300 h**: corpus bugfix/refactor/new file/review, оба direct wires через
  Scala HTTP stand, installed package golden scenarios, UX01 rehearsal.

Exit M3: mapped W01–W16 на одном candidate с actual case evidence, без dependency
на gateway. Existing accepted slices переиспользовать и закрывать только gaps.
Live verification и OpenCode comparison имеют отдельные бюджет/статус, не выводятся
из offline tests. Незавершённые W не прятать за количеством green unit tests.

### P8 — масштабы ежедневной работы, 900 h

- P8.A **300 h**: multi-workspace tabs/worktrees, resource limits/fairness,
  предупреждение конфликтов и последовательная интеграция.
- P8.B **300 h**: benchmark event burst, bounded history/memory, reconnect;
  10k events/s stress на закреплённой машине, без выдачи stress за обычный load.
- P8.C **300 h**: 8-hour offline soak, slow terminal/process/plugin, сбор leak/
  latency metrics, фиксы в существующих компонентах.

Exit: caps реально соблюдаются; нет orphan children, утечек observers и потерянных
terminal statuses. Thresholds/baseline определены до прогона. Удалённый executor
и распределённый scheduler в эту фазу не входят.

### P9 — простота использования, 900 h

- P9.A **300 h**: полноэкранный renderer поверх того же controller, если M1
  feedback подтверждает пользу; иначе улучшить inline по тем же UX cases.
- P9.B **300 h**: accessible status labels, keyboard remap/fallback, plain mode,
  Russian/English help, поиск по истории/действиям, plugin onboarding.
- P9.C **300 h**: usability sessions, terminal matrix и приоритетные UX fixes;
  первое использование, ошибка провайдера, failed plugin и восстановление.

Exit: UX01–08 подтверждены применимыми PTY/human evidence; выбор renderer не
откладывает M1. Комбинации клавиш проверять на macOS/Linux, tmux и заявленных
terminal emulators; неподдержанный terminal использует понятный plain fallback.

### P10 — standalone release candidate, 1 000 h

- P10.A **350 h**: clean install/upgrade/uninstall, reproducible packaging,
  SBOM/notices и существующая license policy, portable examples.
- P10.B **350 h**: независимая приёмка всех standalone claims на immutable
  candidate, security/compatibility regressions и найденные defects.
- P10.C **300 h**: docs/troubleshooting/migration, onboarding evidence, release
  rehearsal и optional capped direct live/comparison при наличии разрешения.

Exit M4: `standalone_engineering_ready` только при полноте W01–W16 + UX/plugin/
state advertised cases; archived source/toolchain/policy/artifact hashes.
Это не старый общий `engineering_ready` для W01–W17. W17 и полная W18 остаются
pending до P11. Публикация не подразумевается созданием release candidate.

### P11 — Yabanin, последняя фаза, 1 100 h

**Hard dependency: M4 accepted.** До этого никаких Yabanin-specific client
modules, discovery задач в соседнем repo, ADR-023 implementation, labels/session
keys, remote state или accounting sync. Существующие исторические материалы
сохраняются; direct custom base URL и generic plugin/state seams не интеграция.

- P11.A **250 h**: refresh read-only discovery actual deployment/contracts,
  ADR интеграции, offline schemas/stand и capabilities; не доверять старому OpenAPI.
- P11.B **500 h**: optional transport profile, attribution, key lifecycle с одним
  owner, bounded accounting reconciliation и eval export по доказанным схемам.
- P11.C **350 h**: offline deny/timeout/drift fixtures, full integrated candidate,
  capped live только при endpoint/режиме/бюджете, docs и отключение adapter.

Exit M5: W17 и W18 на конечном candidate; обе direct конфигурации сохраняют
готовность без Yabanin. Auth/policy denial никогда не приводит к скрытому direct
fallback. Usage имеет pending/partial/complete; timeout не становится complete.
Если native remote state API отсутствует, не выдумывать KV endpoint: File остаётся
основным store, attribution/export описывается именно так. Реальный remote store
получает контракт/карточку только при доказанной поддержке и остатке бюджета.
Внешний unavailable deployment оставляет live gateway pending, offline acceptance
отдельна. Изменения Yabanin — отдельные YB-карточки и отдельное разрешённое исполнение,
их часы не маскируются в 12 000 Raider hours; нет прямого доступа к его DB/Redis.

## 5. Параллельный граф исполнения

Порядок milestone не запрещает подготовку независимых компонентов. Зависимость
означает accepted contract/component с evidence, а не просто начатую фазу.

| Пакет | Можно начинать реализацию после | Что можно вести параллельно |
| --- | --- | --- |
| P0.A/B/C | Inspection; B/C используют факты A, review до freeze | Inventory, pure renderer spike, acceptance design |
| P1.A | P0.B | P2.A/B на frozen interfaces и scripted source |
| P1.B | P1.A | UI components; после интеграции нужен P1.C |
| P1.C | P1.A + P1.B; UI часть после P0.C | Independent ordering/backpressure fixtures |
| P2.A/B | P0.B + P0.C | P1 event implementation; scripted source до P2.C |
| P2.C | P1.C + P2.A/B | P4.A отдельным owner plugin SPI |
| P3.A/B | P0.B + P1.A | P2 frontend; P3.C ждёт P2.C и A/B |
| P4.A/B/C | P0 baseline; B после A; SDK/tests C после A, final acceptance C после B | P1/P2/P3 при отдельных files; M2 ждёт M1 и plugin UI integration |
| P5 | P1.C + P4.A; P5.C plugin seam после P4.C | P3/P4 отдельные modules; core store не зависит от remote backend |
| P6 | P2.C + P3 + P5 | P7 tools/REPL/CI gaps, затем общий recovery acceptance |
| P7 | P0 gap matrix для независимых fixes | Итог M3 после M2 + P3 + P5 + P6 |
| P8 | P3 + P6 | P7 corpus и P9 UX research |
| P9 | M2 для UX work, P8 для финальной load matrix | P10 packaging preparation |
| P10 | M3 + P8 + P9 для финальной приёмки | Docs и packaging заранее, full candidate последовательно |
| P11 | Только M4 | A сначала; B по frozen ADR; C после B; никакой ранней integration lane |

Типичные waves:

| Wave | Lane A | Lane B | Lane C / review |
| --- | --- | --- | --- |
| W0 | Actual inventory / defects | Renderer spike без runtime edits | Acceptance/ADR review |
| W1 | Events/reducer | Tab UI на scripted events | Plugin SPI design/review |
| W2 | Runtime emission/progress | Session registry + UI binding | Plugin loader |
| W3 | Steering/cancellation | Plugin manager/SDK | Integration review/PTY |
| W4 | Local state / recovery | Tools/REPL/CI gaps | Corpus/independent review |
| W5 | Multi-workspace/soak | UX/onboarding | Packaging/compatibility review |
| W6 | Candidate defects | Release acceptance | Docs/usability verification |
| W7, после M4 | Yabanin adapter | Offline fixtures по frozen contract | Independent review; live при разрешении |

Максимум coordinator + 8–10 активных spawn-воркеров (решение владельца
2026-10-12, supersede лимит «до трёх»; reviewer занимает слот; механика —
parallel-development.md §1a/§1b). Не держать все writer-слоты постоянно ценой
отсутствия review. Root checkout, shared contracts/build/gate registry/board
принадлежат coordinator либо одному явно назначенному owner. Разработка
защищённых verifiers — отдельная карточка и fresh review; implementation
worker не может переписать ожидаемый результат своего теста.

Каждый writer получает отдельный worktree или копию immutable snapshot с absolute
cwd, allowed files и base hash; никакого общего `target/`, изменяемых hardlinks,
случайного перехода обратно в root. Если tool не умеет child cwd, использовать
подтверждённый isolation механизм; не выдумывать параметры OpenCode. При отсутствии
изоляции writers идут последовательно, read-only review остаётся параллельным.

Integration: base/path/hash check → fresh diff review → последовательное применение
→ compile/contracts + affected gates → runnable demo wave. Patch, конфликтующий с
новым root, перебазируется и повторно проверяется. Green lane не заменяет green
integrated candidate. Не добавлять CI/runtime scheduler ради orchestration разработки.

## 6. Порядок приёмки и совместимость со старым backlog

| Новый scope | Старые obligations, которые надо переиспользовать |
| --- | --- |
| P0/P1 | RAI-002/004/006/009/010; schema/runtime/budget evidence |
| P2/P3 | RAI-007/008/017–021; W08–W11; send/watch остаются обязательствами |
| P4/P5 | RAI-028/029/031 и новые scoped state cards; W13 |
| P6 | RAI-027/030/036; W08/W14 |
| P7/P10 | RAI-014–016/022–026/041–047; W01–W16 |
| P11 | RAI-037–039; W17/W18; YB companion work отдельно |

Это scope mapping, не автоматическое закрытие старых карточек. Coordinator сверяет
точные acceptance и не переносит «accepted slice» на весь parent. Старый F4 gateway
workstream и MASTER-10K-STATE discovery уступают этому порядку. W17 отложен, не удалён;
полный `engineering_ready` допустим только после W01–W17 и joint W18. Отдельно вести
`live_verified.direct-chat`, `direct-anthropic`, `gateway`, `opencode_comparison`.

После каждой карточки: сначала `make quality-changed`, затем
`make quality-changed QUALITY_EXECUTE=1`, с настоящим baseline manifest для snapshot
lanes. Дополнительно actual applicable profiles; наличие имени в spec не означает
существующий runner. Сейчас registry находится в `scripts/quality-policy/registry.json`;
новые ui/plugin/performance/security/release cases должны получить отдельные
reviewed verifier cards, нельзя придумать `make acceptance-working` как уже готовый gate.
Документный validator не доказывает runtime, а текущий fast не вся W-приёмка.

Проверки по риску: runtime — cancellation/ownership/budget; TUI/REPL — настоящий
compiler/PTY; CI — nonTTY/signals/exits/artifacts/trust; plugins — compatibility,
failure/cleanup/install integrity; recovery — crash/no mutation replay; providers —
реальный локальный Scala HTTP stand. Mock/replay по умолчанию. Paid product calls
только при явном режиме и cap. Gate failure/missing tool/timeout — не passed.

Work record создаётся после выполнения. Evidence привязано к source/policy/toolchain
snapshot и actual commands/exits/log hashes. Текущий runner нельзя считать полностью
соответствующим spec только по зелёному self-check; P0 проверяет и этот gap. Изменение
inputs после тестов требует affected rerun, operational exclusions только по reviewed
точному allowlist. Ни board, ни количество spawned workers не доказательство готовности.

## 7. Контроль масштаба

Не входят в этот бюджет: web/cloud console, hosted marketplace, remote distributed
execution, новый DSL parser/macros, second runtime, другие продуктовые языки,
переделка Yabanin. Эти части прежнего roadmap уступают удобству локального продукта.
Все product modules/tests/HTTP stands — Scala 3 + ZIO; использовать существующую
license policy, не добавлять dependencies без конкретной карточки и review.

Главные риски и действия: event loss → snapshot/sequence tests; JLine конфликт ввода
→ ранний PTY spike; plugin ABI/dependencies → narrow SPI/compatibility matrix;
state corruption → versioned fixtures/CAS; parallel merge churn → freeze и ownership;
устаревший board → actual inventory; внешний Yabanin blocker → standalone уже принят.

Раз в wave coordinator публикует: accepted results, активные lane/card/path,
blocker с причиной, used/reserved/remaining estimate и следующий runnable increment.
Отклонение бюджета пакета >20% вызывает перепланирование остатка и явное описание
tradeoff, но не скрытое ослабление acceptance. Пока есть independent ready task,
внешний blocker не повод прекращать всю разработку.

Источники для executor integration: официальные [OpenCode agents](https://opencode.ai/docs/agents/)
и [commands](https://opencode.ai/docs/commands/), проверены 2026-10-05. Primary agent
switch и child-session navigation различаются; ни один не гарантирует filesystem
isolation. Фактические tools/версию проверять в следующей сессии; интерфейс Raider
проектируется отдельно от интерфейса OpenCode.
