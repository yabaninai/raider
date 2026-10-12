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

## LIN-11: LineageRenderer: компилируемые проекции графов без named-def корней

Status: ready. Milestone: LIN. Dependencies: LIN-02.

**Сценарий и контракт.** LineageRenderer эмитит синтаксически-валидную внешнюю область проекции для графов без named-def корней (comment-only тело объекта закрывается легальным statement, например `()`); проекция любого валидного графа компилируется pinned dotc 3.9.0; формат остальных проекций не меняется.

**Context.** LIN-09 contract-change request (reproducer: object X: + indented comment-only тело → dotc «indented definitions expected, eof found»); LIN2-01 compile-gate; docs/lineage.md §9.1.

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/proj/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN11-01: проекция графа plain-рана (model/tool-only, без named-def) компилируется pinned dotc 3.9.0 (compile-gate).
- Error/adversarial: LIN11-02: негативный compile-gate: повреждение тела проекции ловится гейтом (негатив остаётся негативом).
- Cancellation/concurrency: LIN11-03: повторная проекция байт-идентична; golden tiny-graph/deep-tree инварианты LIN2-01 не меняются.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Изменение frozen lineage-data schema, GraphHash/нормализации компаратора, формата существующих golden-проекций.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## RAI-109: replay в продукт-команду raider (CLI wiring LIN-10)

Status: ready. Milestone: LIN. Dependencies: LIN-09, LIN-10.

**Сценарий и контракт.** Подкоманда общего CLI: `raider replay <run-dir> --workspace <dir> [--data <file>]` — паритет флагов и exit-кодов LIN-10 (0/10/20/21/22/23/24/26) через делегирование в ReplayCli.exec (без дублирования логики); docs/lineage.md §10 обновлён на продуктовый вход; standalone ReplayCliMain сохраняется (переходный вход).

**Context.** LIN-10 accepted (standalone entry, reviewer risk 1: wiring отдельной карточкой); docs/lineage.md §10; ci-runtime §8 exit-маппинг.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/**` `docs/lineage.md` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN13-01: продуктовый вход даёт Match+graph-hash/exit 0 и Mismatch/exit 10 с первым расходящимся узлом (process-смоки через `raider replay`).
- Error/adversarial: LIN13-02: битый/отсутствующий lineage-data.json — typed + exit 21; usage/незвестный флаг — typed, exit невалидный.
- Cancellation/concurrency: LIN13-03: параллельные `raider replay` — изолированные workspace (verifyUntouched), без общих записей.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Live/гибрид-флаги replay (LIN-06), второй runner, смена frozen exit-маппинга, headless JLine-правило.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-12: Каталог деревьев: persistence (LIN-08 remainder)

Status: ready. Milestone: LIN. Dependencies: LIN-08, LIN-09, LIN-10.

**Сценарий и контракт.** LineageCatalog получает persistence: scan-записи (run-dir, graph-hash, verifyPartial-вердикт, pinned-env сводка) сохраняются атомарно (tmp+move/single-writer паттерн LineageDataStore) в каталог кампаний; конкурентные appends атомарны без потерь; reload roundtrip консистентен; chat==headless формат; битый persisted-файл — typed ошибка, не молча.

**Context.** docs/lineage.md §9.2 (LineageCatalog — scan без persistence) и §5; LIN-08 accepted-в-коде; LIN-09/10 дали артефакты и CLI-поверхность.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/cli/src/test/scala/raider/cli/**` `modules/runtime/src/test/scala/raider/runtime/**` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LINC-01: persist+reload roundtrip — записи идентичны (graph-hash, вердикты); каталог читается replay-CLI поверх.
- Error/adversarial: LINC-02: повреждённый persisted-каталог — typed error с указанием записи; scan не падает молча.
- Cancellation/concurrency: LINC-03: параллельные appends из нескольких ранов — atomic, 0 потерь, детерминированный порядок чтения.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Изменение frozen lineage-data schema, live-wire, UI каталога, распределённый каталог.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-13: docs/lineage.md §9.2 sync — честность статусов реализации

Status: ready. Milestone: LIN. Dependencies: LIN-09, RAI-108, RAI-109.

**Сценарий и контракт.** §9.2 привести в соответствие интегрированному состоянию на базовом коммите lane: TabShell-wiring закрыт (RAI-108 --tabs), запись артефактов рана закрыта (LIN-09: lineage.scala/lineage-data.json рядом с events.jsonl/summary), продуктовый вход raider replay (RAI-109) сверить; каждый пункт — evidence-ссылка на record или file:line; открытые пункты (live-wire, multi-job export, phase/recovery schema-card, LIN2-03 coverage, LIN-12 persistence) остаются честно открытыми со ссылками на карточки; ничего не заявлять сверх интегрированного состояния.

**Context.** docs/lineage.md §9.2 snapshot застал W21: строки TabShell-wiring и «запись артефактов в каталог рана» устарели после RAI-108/LIN-09 (оба accepted, records в docs/work/records/).

**Allowed paths.** `docs/lineage.md` До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: DOC-13-01: каждая строка §9.2 несёт evidence-ссылку (record path или file:line), проверяемую на базовом коммите.
- Error/adversarial: DOC-13-02: ни один in-flight/owner-gated пункт не помечен закрытым; расхождение факта и текста — finding, не умолчание.
- Cancellation/concurrency: DOC-13-03: docs-профиль зелёный; якоря/ссылки валидны; diff только в docs/lineage.md.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Продуктовый код, изменение контрактов ADR-027/схем, §9.1-переписывание сверх фактов, board/tasks-файлы.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-14: LIN2-03 remainder: конкурентное чтение проекции — snapshot-покрытие

Status: ready. Milestone: LIN. Dependencies: LIN-02.

**Сценарий и контракт.** Тестовое покрытие LIN2-03 («конкурентное чтение — снапшот»): property-спека — конкурентные чтения LineageDataStore/проекции во время атомарной tmp+move записи видят либо старый, либо новый ПОЛНЫЙ документ, никогда частичный; чтение не маскирует гонку как Missing/Corrupt; детерминированный seed, ≥100 итераций, 3 повтора без flake. Вскрытый дефект — не чинить молча в этой карточке: typed reproducer + contract-change request (фикс — отдельная карточка).

**Context.** docs/lineage.md §9.2 «Прочее, честно»: LIN2-03 покрыт частично; store атомарен (tmp+move), формальное конкурентное покрытие отсутствует.

**Allowed paths.** `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN2-03-01: property ≥100 итераций concurrent read/write — 0 частичных чтений (каждое чтение — валидный документ schemaVersion 1).
- Error/adversarial: LIN2-03-02: 3 повтора зелёные (flake=0); при воспроизведённом дефекте — typed reproducer в record, ассерты не ослаблены.
- Cancellation/concurrency: LIN2-03-03: повторная проекция чтений байт-идентична для неизменного документа; новые файлы только в allowed paths.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Изменение LineageDataStore/кодеков/frozen schema, изменение LIN-12 области, утилиты вне спек.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## LIN-15: LIN-06 scripted acceptance — LIN6-01/02/03 (бюджет/изоляция/отмена)

Status: ready. Milestone: LIN. Dependencies: LIN-06.

**Сценарий и контракт.** Deterministic offline acceptance LIN-06 на готовой механике (IsolatedRerun/WorkspaceIsolation/RunDiffReport/ReplayHarness/Admission), scripted backend с ЯВНОЙ маркировкой scripted: LIN6-01 — isolated rerun завершается, пофазовый diff-отчёт живой-vs-записанный; LIN6-02 — исчерпание бюджета останавливает rerun typed (RerunError.Replay поверх Admission), mutating-узел вне изоляции — отказ ДО исполнения; LIN6-03 — отмена (interrupt) посреди rerun: копия остаётся классифицированной/консистентной, повторный rerun без дублей побочных эффектов. Минимальные typed-фиксы продуктовых файлов allowed paths допустимы, если acceptance вскрывает дефект; каждый фикс — с reproducer в record.

**Context.** W22-review: объекты L52 (LIN-06C)/L53 (LIN-07-full)/L54 accepted; F-W22R1-1 закрыт 5ff5318; IsolatedRerunSpec покрывает happy-path/tamper, но LIN6-02 (бюджет) и LIN6-03 (отмена/повтор) acceptance-кейсы не исполнены как фикстуры.

**Allowed paths.** `modules/cli/src/test/scala/raider/cli/lineage/**` `modules/cli/src/main/scala/raider/cli/lineage/**` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: LIN6-01(sc): rerun в изоляции завершается; diff-отчёт пофазовый и детерминированный (повтор — идентичен).
- Error/adversarial: LIN6-02(sc): бюджет исчерпан — typed остановка до выхода за бюджет; mutating вне изоляции — typed отказ до исполнения (продуктовый путь, не только тест-хелпер).
- Cancellation/concurrency: LIN6-03(sc): interrupt посреди rerun — нет orphan-записей в оригинал; повторный rerun даёт консистентный вердикт без дублей.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Живая модель за replay (owner live-wire), LIN-12 каталог, новые wire-события, ослабление verifyUntouched/snapshot-границ.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## RAI-120: IsolatedRerun canonical-path guard (alias-дыра requireIsolatedTarget)

Status: ready. Milestone: LIN. Dependencies: LIN-15.

**Сценарий и контракт.** Review-finding LIN-15 (minor, блокер для будущего live-rerun CLI): requireIsolatedTarget сравнивает target==original синтаксически (Path equality) — алиас оригинала (ws.resolve("..").resolve(ws.getFileName), symlink) проходит байт-проверку как «faithful copy», и mutating replay исполнился бы IN-PLACE. Фикс: canonical-сравнение (Files.isSameFile/toRealPath с обработкой MissingFile) ДО исполнения, typed отказ существующим MutatingUnisolated; reproducer-тест алиаса и symlink-алиаса из review; verifyUntouched-дисциплина и существующие LIN6-фикстуры не ослабляются.

**Context.** LIN-15 review finding 1 (обоснование: до подключения guard'а к пользовательским target в CLI-слое); reproducer: guard с target = original.resolve("..").resolve(name) возвращает Right сегодня.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: ISO-01: алиас-репродуктор reviewer'а — typed отказ ДО исполнения (байты оригинала неизменны, parent без dirt).
- Error/adversarial: ISO-02: symlink-алиас оригинала — typed отказ; несуществующий target — honest typed-ошибка (не молчаливый pass).
- Cancellation/concurrency: ISO-03: легитимная изолированная копия проходит guard — существующие LiveRerunScriptedAcceptanceSpec/IsolatedRerunSpec зелёные ×3 без правок ассертов.

**Required gates.** После foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification с каждым required profile. full/release aggregates разрешены, если реально включают весь список. External prerequisites explicit; unavailable не passed.

**Non-goals.** Живая модель (LIN-06 live-wire — owner), смена семантики WorkspaceIsolation/isolate, новые typed-кейсы вне guard'а, каталог LIN-12.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.
