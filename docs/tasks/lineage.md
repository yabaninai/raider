<!-- Cards for P-LIN (roadmap-12k rev 2026-10-10, ADR-027). Same rules as m0.md. -->

# P-LIN: Session lineage — дерево вызовов как валидная Scala-программа

Приоритет: впереди всего плана (решение владельца 2026-10-10, ADR-027).
Контракт и решения: [ADR-027](../adr/ADR-027-session-lineage.md). Общий DoD как
в [индексе](README.md); гейты — по [quality-gates](../quality-gates.md).

## LIN-01: SessionGraph-модель и редуктор events → граф

Status: ready. Milestone: LIN. Dependencies: RAI-004.

**Сценарий и контракт.** Event-sourced граф сессии: узлы ModelCall/ToolCall(kind, RecoveryClass)/Delegation/Phase; редуктор events.jsonl → граф детерминирован; node kinds расширяемы без смены контракта.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/runtime/src/test/scala/raider/runtime/lineage/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN1-01: реальный events.jsonl кампании → граф; повтор идентичен (hash).
- Error/adversarial: LIN1-02: битые события — типированная ошибка, не молчаливый пропуск.
- Cancellation/concurrency: LIN1-03: параллельные дети — корректные поддеревья.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-02: Проекция граф → валидная Scala-программа

Status: todo. Milestone: LIN. Dependencies: LIN-01, RAI-017.

**Сценарий и контракт.** Детерминированная проекция графа в lineage.scala: узлы — композиции Program/Task (RAI-017 frozen), делегации — вложенные блоки, фазы — defs; данные — lineage-data.json.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/**` `contracts/lineage/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN2-01: проекция компилируется scalac (пиннинг); повтор — байт-идентичен.
- Error/adversarial: LIN2-02: неизвестный node kind — отказ, не невалидный код.
- Cancellation/concurrency: LIN2-03: конкурентное чтение — снапшот.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-03: Лайв-инкрементальная поддержка графа и проекции

Status: todo. Milestone: LIN. Dependencies: LIN-01, LIN-02.

**Сценарий и контракт.** Инкрементальное достраивание графа и проекции в ходе сессии; периодическая инкрементальная компиляция-валидация; артефакт консистентен последнему завершённому узлу при crash.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/cli/src/main/scala/raider/cli/run/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN3-01: артефакт соответствует графу после каждого раунда; ≥200 раундов.
- Error/adversarial: LIN3-02: сбой компиляции не рушит сессию; статус невалидности виден.
- Cancellation/concurrency: LIN3-03: crash между узлами — консистентность последнего узла.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-04: Вкладка Lineage в TUI

Status: todo. Milestone: LIN. Dependencies: LIN-03.

**Сценарий и контракт.** Минимальный tab-host (одна вкладка, без черновик­ов P2): дерево сессии, исходник lineage.scala, статус валидности; доступ в живой сессии без блокировки ввода.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/ui/**` `modules/cli/src/main/scala/raider/cli/chat/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN4-01: вкладка живая, без блокировки ввода.
- Error/adversarial: LIN4-02: невалидная проекция — честный статус.
- Cancellation/concurrency: LIN4-03: переключение вкладок без рассинхрона.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-05: Replay-recorded интерпретатор и компаратор графов

Status: todo. Milestone: LIN. Dependencies: LIN-02, LIN-03.

**Сценарий и контракт.** Исполнение lineage-программы в replay-recorded (default): LLM из записи, детерминированные тулколлы пересчитываются; успех = идентичный граф вызовов (компаратор graph-hash).

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `scripts/quality/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN5-01: replay — идентичный нормализованный graph-hash.
- Error/adversarial: LIN5-02: расхождение — провал с первым расходящимся узлом.
- Cancellation/concurrency: LIN5-03: прерывание — консистентный частичный граф + чекпоинт.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-06: Live re-run, изоляция мутаций, diff-отчёт

Status: todo. Milestone: LIN. Dependencies: LIN-05.

**Сценарий и контракт.** Opt-in live re-run: реальные вызовы; Mutating — только в изолированной копии WS (RecoveryClass-политика); бюджет; diff-отчёт живого vs записанного графа.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/tools/src/main/scala/raider/tools/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN6-01: live в изоляции завершается; diff-отчёт пофазово.
- Error/adversarial: LIN6-02: Mutating вне изоляции — отказ до исполнения; бюджет — остановка.
- Cancellation/concurrency: LIN6-03: отмена — копия классифицирована, повтор без дублей.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-07: Частичный replay фаз/поддеревьев

Status: todo. Milestone: LIN. Dependencies: LIN-05.

**Сценарий и контракт.** Replay именованной фазы/поддерева с явным контрактом входов; изоляция непересекающихся фаз; ошибки контракта вместо пустых подстановок.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN7-01: replay фазы только по её поддереву и входам.
- Error/adversarial: LIN7-02: внешние зависимости — ошибка контракта.
- Cancellation/concurrency: LIN7-03: concurrent replay фаз изолирован.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-08: Области (chat/headless/кампании), пиннинг, каталог

Status: todo. Milestone: LIN. Dependencies: LIN-03, LIN-05.

**Сценарий и контракт.** Lineage для chat, headless, ночных кампаний (каталог деревьев); сверка pinned-окружения: предупреждение + попытка + отчёт расхождений.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/**` `modules/runtime/src/main/scala/raider/runtime/lineage/**` `docs/lineage.md` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN8-01: каталог с graph-hash и статусами; chat==headless формат.
- Error/adversarial: LIN8-02: пиннинг расходится — предупреждение и маркировка.
- Cancellation/concurrency: LIN8-03: каталог при параллельных ранах — atomic append.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** См. docs/tasks/lineage.md карточку.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-09: Запись lineage-артефактов рядом с продовым раном

Status: ready. Milestone: LIN. Dependencies: LIN-03.

**Сценарий и контракт.** HeadlessRunner при settle пишет lineage.scala (проекция) и lineage-data.json рядом с events.jsonl/summary рана; запись атомарная в каталог рана; сбой проекции/записи не меняет exit-код рана, но честно отражается в summary; формат chat==headless.

**Context.** docs/lineage.md §9.2 «Запись артефактов в каталог рана» и §10; обязательство LIN-03; ADR-027.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/test/scala/raider/cli/**` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN9-01: продовый ран оставляет валидные lineage.scala + lineage-data.json (frozen schema, compile-gate зелёный на артефактах).
- Error/adversarial: LIN9-02: сбой проекции — typed диагностика в summary; ран завершается штатным exit-кодом.
- Cancellation/concurrency: LIN9-03: параллельные раны — артефакты в разных каталогах ранов, без пересечений (atomic per-run).

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Каталог кампаний (LIN-08 scan/persistence), live-wire, изменение frozen lineage-schema.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-10: CLI-командная поверхность replay

Status: ready. Milestone: LIN. Dependencies: LIN-05.

**Сценарий и контракт.** Standalone CLI-вход replay-цепочки: флаги/команда запускают ReplayHarness по lineage-data.json из рана/каталога, выводят вердикт Match/Mismatch с первым расходящимся узлом и graph-hash, exit-код различает исходы; recorded-only, без модельных вызовов.

**Context.** docs/lineage.md §9.2 «CLI-командная поверхность replay»; ReplayHarness/ReplayModelBackend готовы (§9.1, LIN5-01 end-to-end); ADR-027.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/**` `docs/lineage.md` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN10-01: replay по артефактам LIN-09 — Match + graph-hash; Mismatch печатает первый расходящийся узел.
- Error/adversarial: LIN10-02: отсутствующий/битый lineage-data.json — typed error и невалидный (не нулевой) exit.
- Cancellation/concurrency: LIN10-03: параллельные replay — изолированные workspace (verifyUntouched), без общих записей.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Live-модель за replay (LIN-06), изменение ReplayHarness-контракта, второй runner/scheduler.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.
