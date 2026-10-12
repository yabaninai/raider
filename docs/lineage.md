<!-- LIN-08A: пользовательский документ + операционный spec session lineage.
     Контракт: ADR-027 (docs/adr/ADR-027-session-lineage.md) и карточки
     P-LIN (docs/tasks/lineage.md). Новых продуктовых решений не вносит.
     Команды/пути сверены с modules/cli (CliArgs.scala, HeadlessRunner.scala,
     EventExport.scala, ReplayCli.scala), events — raider.runtime.events
     (ADR-024 §5). -->

# Session lineage: дерево вызовов как валидная Scala-программа

Пользовательский документ и операционный spec слоя session lineage (ADR-027,
фаза P-LIN). Каждая сессия — chat, headless-ран, ночная кампания — получает
**SessionGraph**: дерево вызовов, материализуемое как **валидная Scala-программа**
(`lineage.scala`) плюс data-ресурс (`lineage-data.json`). Запуск этой программы
**воспроизводит цепочку** вызовов сессии.

Статус реализации — см. [§9](#9-статус-реализации-честно). Этот документ
описывает зафиксированные решения ADR-027, а не готовый продукт.

## 1. Три артефакта и источник истины

| Артефакт | Что это | Роль |
| --- | --- | --- |
| `events.jsonl` | Лог событий сессии (экспорт headless-раннера, schemaVersion 1) | **Единственный источник истины** |
| SessionGraph | Event-sourced граф сессии: редуктор `events.jsonl → граф` | Модель (в памяти, восстанавливается заново из событий) |
| `lineage.scala` + `lineage-data.json` | Проекция графа в валидную Scala-программу + data-ресурс | **Детерминированная проекция** (аналог `explain()` у Spark) |

Ключевые свойства:

- **События — источник истины; программа — проекция.** Ручные правки
  `lineage.scala` детектируются компаратором графов (повторная проекция из
  событий перестанет совпадать).
- **Данных в коде нет.** Все данные узлов — промпты LLM-раундов, аргументы
  тулколлов — хранятся в `lineage-data.json`. `lineage.scala` — только
  композиция Program/Task (RAI-017, заморожен: он цель проекции, не модель).
- **Программа валидна по построению и проверяется компиляцией** — периодически
  в ходе сессии и в CI (гейт LIN). Невалидная проекция — честный статус
  (виден в TUI до починки), а не молчаливый пропуск.

## 2. Слои (ADR-027 §3)

| Слой | Ответственность | Карточка |
| --- | --- | --- |
| 1. SessionGraph (модель) | Редуктор `events.jsonl → граф`. Узлы: `ModelCall \| ToolCall(kind, RecoveryClass) \| Delegation(subtree) \| Phase(name)`; рёбра — dataflow (`inputs: Vector[NodeId]`) + управление. Новые типы тулколлов регистрируются подтипами ToolCall-node без смены контракта | LIN-01 |
| 2. Проекция | `граф → валидная Scala`: узлы — композиции Program/Task, делегации — вложенные блоки, фазы — `def`-ы; данные — в `lineage-data.json` | LIN-02 |
| 3. Лайв-поддержка | Инкрементальное достраивание графа и проекции в ходе сессии; периодическая компиляция-валидация; артефакт всегда доступен из TUI-вкладки Lineage | LIN-03 |
| 4. Воспроизведение | replay-recorded / гибрид / live re-run; компаратор графов — приёмка | LIN-05–07 |

Компакция длинных сессий: узлы сворачиваются в именованные фазы (`Phase(name)`)
— они же служат границами компакции и единицами частичного replay.

## 3. Где лежат артефакты

Каталог рана задаётся флагом `--out`. Фактическое поведение headless-раннера
(`raider run ... --out <dir>`, сверено с `modules/cli/run/HeadlessRunner.scala`):

```text
<out>/work-<uuid>/          # каталог рана; параллельные раны не делят каталог
├── run.json                # итог рана (status, accounting, ci)
├── events.jsonl            # события сессии — источник истины (schemaVersion 1)
├── summary.md              # human-readable summary
└── transcript.json         # trace рана
```

Lineage-артефакты лежат **рядом с рансом**, в том же каталоге рана:

```text
<out>/work-<uuid>/lineage.scala        # проекция графа (валидная Scala)
<out>/work-<uuid>/lineage-data.json    # data-ресурс (промпты, данные узлов)
```

В терминах ADR-027 это записывается как `artifacts/<run>/lineage.scala` +
`artifacts/<run>/lineage-data.json` — типовой вызов:

```sh
raider run --agent coder --input "..." --out artifacts/<run>   # + --mock/--provider и др., см. §6
```

- `lineage.scala` и `lineage-data.json` **входят в evidence-fingerprint** рана
  наряду с `events.jsonl` (ADR-027 §2, строка «Хранение»).
- `events.jsonl` — канонический экспорт per-job `EventEnvelope` (реальные
  события `raider.runtime.events`, отсортированные по per-job seq; дубликаты
  отбрасываются, маркер coalescing сохраняется честно).

## 4. Режимы воспроизведения

| Режим | Статус | Что происходит | Критерий успеха |
| --- | --- | --- | --- |
| **replay-recorded** | Режим **по умолчанию**: бесплатно, детерминировано | Программа исполняется на записанных данных; ничего не вызывает | Идентичный граф вызовов (нормализованный graph-hash) |
| **гибрид** | Основной режим (аналог Spark-lineage) | LLM-узлы берутся из записи; **детерминированные тулколлы пересчитываются заново** | Идентичная форма дерева (узлы/рёбра/типы) |
| **live re-run** | Opt-in | Реальные вызовы; бюджет обязателен | **«Живой» граф** + diff-отчёт расхождений |

Семантика успеха — по решению владельца (ADR-027 §2): успех воспроизведения =
**тот же граф вызовов** (форма дерева: узлы/рёбра/типы). Тексты LLM в live-режиме
могут отличаться — сравнивается форма, не тексты.

- **Live re-run (opt-in) — только с изоляцией и бюджетом:** mutating-узлы
  исполняются **в изолированной копии workspace** (RecoveryClass = политика);
  отказ до исполнения, если mutating-узел не изолирован; остановка по исчерпании
  бюджета (LIN6-02).
- **Успех live re-run = «живой» граф:** расхождение с записью фиксируется в
  diff-отчёте «живой vs записанный», это результат, а не провал.
- **Для replay-верификации — наоборот:** расхождение графа = **провал** проверки
  с первым расходящимся узлом (LIN5-02). Live re-run не используется как
  «успешная replay-верификация».
- **Компаратор графов — приёмка** replay-режимов (LIN-05). Ручные правки
  проекции детектируются им же.

### 4.1 Частичный replay

Обязателен: replay **именованной фазы** или **поддерева** (LIN-07):

- только по поддереву фазы и её явному контракту входов;
- внешние зависимости — ошибка контракта, не пустая подстановка;
- непересекающиеся фазы replay-ятся изолированно друг от друга;
- конкурентные replay фаз изолированы.

## 5. Области и каталог деревьев

Lineage строится для всех областей сессий (ADR-027 §2, «Область»):

1. **Chat-сессии** — дерево вызовов интерактивной сессии.
2. **Headless-раны** — один ран = одно дерево (сегодня: `raider run --out ...`
   пишет `events.jsonl` рана; lineage-артефакты появляются с P-LIN).
3. **Ночные кампании** — **каталог деревьев**: множество ранов кампании с
   graph-hash и статусом воспроизводимости каждого (LIN8-01).

Требования к каталогу:

- формат записи каталога для chat и headless **одинаковый** (chat==headless);
- каждая запись несёт **graph-hash** и **статус воспроизводимости** (успешно
  replay-нут / расхождение / не проверялся);
- при параллельных ранах каталог пополняется **атомарным append** (LIN8-03).

### 5.1 Multi-job граф (честно)

`events.jsonl` одного рана — события **одного job**. События делегированных
детей собираются в общий граф **редуктором по rootId/jobId из journal-слоя**.
Единый multi-job export — владелец `cli/run/**`, волна-2, после freeze
read-API (ADR-027 §6.4). До этого каталог оперирует per-job/per-ран деревьями,
собранными редуктором.

## 6. Пиннинг окружения

Воспроизводимость привязана к окружению. Pinned-окружение фиксируется в
`toolchain.json` (LIN-08): toolchain (Scala/JDK), версия Raider, модель.

- При запуске воспроизведения **сверка pinned-версий происходит ДО исполнения**.
- Расхождение → **предупреждение до исполнения** + попытка воспроизведения
  («предупредить и попытаться», ADR-027 §2).
- Если воспроизведение выполнено в другом окружении, diff-отчёт несёт явную
  пометку **«воспроизведено в другом окружении»** (LIN8-02). Скрыть расхождение
  пиннинга нельзя.
- Проекция линчуется компилятором **с пиннингом** (LIN2-01): повторная
  проекция того же графа — байт-идентична.

## 7. TUI: вкладка Lineage

Одна вкладка Lineage (LIN-04) в минимальном tab-host (ограниченно вытянут из
P2 вперёд — решение владельца, ADR-027 §3.3):

- показывает: **дерево сессии**, исходник `lineage.scala`, **статус
  компиляции-валидации**;
- вкладка одна; черновики P2 и полноэкранные режимы — не в этой волне;
- статус невалидности **виден до починки** — честный статус, не молчаливый;
- доступ в живой сессии **без блокировки ввода**; переключение вкладок без
  рассинхрона (LIN4-01/03);
- артефакт поддерживается **лайв-инкрементально** в ходе сессии (ADR-027 §2,
  «Генерация»), консистентен последнему завершённому узлу при crash (LIN3-03);
- сбой компиляции не рушит сессию — статус виден (LIN3-02).

## 8. Честные ограничения

| Ограничение | Суть |
| --- | --- |
| **ClassLoader — НЕ sandbox** | Lineage-программа — это доверенный пользовательский код с полномочиями процесса (как REPL). Tool policy не является sandbox для произвольной Scala. Не запускать lineage неизвестного происхождения с ожиданием изоляции |
| **Restore не replay mutation** | Восстановление сессии/crash-archive восстанавливает граф и состояние, но **не повторяет** эффекты mutating-узлов автоматически (runtime-invariant 18) |
| **Live re-run не merge в рабочее дерево** | Изменения live-прогона остаются в изолированной копии workspace. Автоматического auto-merge/push нет (runtime-contracts §15) |
| **Не exactly-once** | Повтор эффектов произвольной Scala-лямбды при неоднозначном результате не заявляется; повторные мутационные вызовы не выполняются автоматически |
| **`argsSummary ≤512 redacted` — не полные данные** | Wire-события несут ограниченную сводку; полные данные узлов — только в `lineage-data.json`; dataflow — отдельные рёбра `inputs: Vector[NodeId]` в модели графа |
| **Phase-узлы без wire-события** | У событий нет phase-кейса (13 frozen payload cases, ADR-024); wire-расширение `PhaseMarked(name)` — отдельная schema-card волны-2 с independent review. Пока узлы Phase строит модель графа |
| **RecoveryClass не на wire** | Тулколлы не несут recovery-класс; до schema-card — статическая таксономия tool-name → RecoveryClass в lineage-слое (ReadOnly по умолчанию, Mutating для write/exec-классов), расширяется конфигом без смены frozen-событий |
| **Recording store — обязательное условие** | Durable-хранилище для `lineage-data.json` — slot-0 волны-2, обязательное до LIN-05 replay; иначе rework (ADR-027 §6.5) |
| **Не обещаем** | No exactly-once, no web/cloud, no distributed replay, no auto-merge — вне scope (runtime-contracts §15, ADR-027) |

## 9. Статус реализации (честно)

Состояние на snapshot 2026-10-12: волны W7–W21 P-LIN интегрированы и
закоммичены («P-LIN session lineage waves W7-W21»), evidence —
`docs/work/records/W7-INTEGRATION.md` и
`docs/work/records/W8-W9-INTEGRATION.md` (с addenda W10–W21). Каждый пункт
ниже проверяется указанным файлом.

### 9.1 Реализовано (по слоям ADR-027 §3, с file-ссылками)

**Слой 1 — SessionGraph, модель и редуктор (LIN-01):**

- `SessionGraph`: sealed `SessionNode` — `ModelCall | ToolCall(RecoveryClass) |
  Delegation | Phase`, validated/childrenOf/roots, dataflow-рёбра
  `inputs: Vector[NodeId]` —
  `modules/runtime/src/main/scala/raider/runtime/lineage/model/SessionGraph.scala`
- `GraphHash` — SHA-256 канонической формы (инъективное экранирование) —
  `modules/runtime/src/main/scala/raider/runtime/lineage/model/GraphHash.scala`
- чтение `events.jsonl` (`ParsedEvents`/`TruncatedTail`/`LineageError`) —
  `modules/runtime/src/main/scala/raider/runtime/lineage/io/LineageEventReader.scala`
- `LineageReducer`: 13 frozen payload-кейсов → граф, 9 typed ошибок,
  `reducePartial` (crash-префикс) —
  `modules/runtime/src/main/scala/raider/runtime/lineage/reduce/LineageReducer.scala`
- детерминированный test-корпус кампаний (seed/corruptedTail/seqGap) —
  `modules/runtime/src/test/scala/raider/runtime/lineage/corpus/LineageCorpus.scala`

**Слой 2 — проекция и данные (LIN-02):**

- контракт данных: `contracts/lineage/lineage-data.schema.json`
  (schemaVersion 1), golden `contracts/lineage/golden/{tiny-graph,deep-tree}/`,
  `contracts/lineage/README.md` (LIN-02A)
- типизированная модель + zio-json кодеки, канон = frozen schema —
  `modules/dsl/src/main/scala/raider/dsl/lineage/data/LineageData.scala`
- atomic tmp+move store, single-writer (`Missing`/`Corrupt`/`Io`) —
  `modules/dsl/src/main/scala/raider/dsl/lineage/store/LineageDataStore.scala`
- рендерер граф → `lineage.scala` —
  `modules/dsl/src/main/scala/raider/dsl/lineage/proj/LineageProjection.scala`
- **LIN2-01 достигнут**: golden-проекции компилируются pinned dotc 3.9.0,
  повторная проекция байт-идентична —
  `modules/cli/src/test/scala/raider/cli/lineage/LineageCompileGateSpec.scala`
- cli-адаптер граф → проекционный view —
  `modules/cli/src/main/scala/raider/cli/lineage/SessionGraphView.scala`

**Слой 3 — лайв-поддержка (LIN-03/04):**

- инкрементальный `LiveGraphBuilder` (пошаговый твин батч-редуктора, инвариант
  fold==batch) —
  `modules/runtime/src/main/scala/raider/runtime/lineage/live/LiveGraphBuilder.scala`
- headless-раннер: стриминг `events.jsonl` в ходе рана (`EventsJsonlWriter`) и
  lineage-строка с graph-hash в `summary.md` при settle —
  `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`
- TUI: `:aux`-seam (`AuxTab`/`TabShell`) — `modules/ui/src/main/scala/raider/ui/`;
  вкладка Lineage как чистый provider без IO (LIN-04B) —
  `modules/ui/src/main/scala/raider/ui/LineageTab.scala`

**Слой 4 — воспроизведение (LIN-05/06/07) и каталог (LIN-08):**

- `GraphComparator` (normalized, first-divergence) + `ReplayVerifier.verify` /
  `verifyPartial` —
  `modules/runtime/src/main/scala/raider/runtime/lineage/replay/{GraphComparator,ReplayVerifier}.scala`
- `RecordedSource` — DataRef-доступ к записанным модельным ответам —
  `modules/dsl/src/main/scala/raider/dsl/lineage/replay/RecordedSource.scala`
- `ReplayInterpreter` (replay-recorded: LLM-узлы из записи, integrity-verdict) —
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayInterpreter.scala`
- `ToolReplay`: детерминированный пересчёт `fs_read` против workspace, typed
  `Unsupported`/`ArgsUnreadable`/`ReadFailed` —
  `modules/cli/src/main/scala/raider/cli/lineage/ToolReplay.scala`
- `ReplayModelBackend`: записанные ответы как `ModelBackend` (ordinal-стратегия,
  typed `Overrun`/`Exhausted`) —
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayModelBackend.scala`
- `ReplayHarness` — **LIN5-01 исполнен end-to-end**: replay-ран через реальный
  loop (бюджет через `Admission`, изолированный fixture-workspace),
  normalized `Match`/`Mismatch` с первым расхождением —
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala`
- частичный replay (LIN-07): по поддереву —
  `modules/cli/src/main/scala/raider/cli/lineage/SubtreeReplay.scala`,
  по именованным фазам —
  `modules/cli/src/main/scala/raider/cli/lineage/PhaseReplay.scala`
- LIN-06 механика: изолированная копия workspace + `verifyUntouched` —
  `modules/cli/src/main/scala/raider/cli/lineage/WorkspaceIsolation.scala`;
  diff-отчёт «replayed vs записанный» —
  `modules/cli/src/main/scala/raider/cli/lineage/RunDiffReport.scala`
- каталог деревьев (scan runsRoot → verifyPartial-статусы, chat==headless) —
  `modules/cli/src/main/scala/raider/cli/lineage/LineageCatalog.scala`
- standalone replay-вход (LIN-10): CLI-команда над `ReplayHarness` — вердикт
  Match/Mismatch + graph-hash + первый расходящийся узел, typed-ошибки
  (`StoreError`/`IsolationError`/`RaiderError`/`ReduceError`), exit-коды по
  ci-runtime §8, изоляция workspace (`verifyUntouched`) —
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayCli.scala` (§10)

Каждый слой покрыт behavioral-спеками рядом с кодом
(`modules/*/src/test/scala/**/lineage/**`).

### 9.2 Чего ещё нет

| Чего нет | Факт |
| --- | --- |
| LIN-06 live re-run с живой моделью и бюджетом | Механика готова (изоляция `WorkspaceIsolation`, бюджет `Admission` в `ReplayHarness`, diff-отчёт `RunDiffReport`), но живого провайдера за replay-раннером нет: `ReplayModelBackend` играет только записи |
| Live-wire verification | Owner-обязательство (endpoint/бюджет владельца); не проводилась |
| Multi-job export | `events.jsonl` — события одного job; делегированные дети в единый export не собираются (owner `cli/run/**`, wave-2, ADR-027 §6.4); редуктор и каталог оперируют per-job/per-ран деревьями |
| Phase/recovery schema-card | Wire-события frozen (13 cases): Phase-узлы строит модель/редуктор, RecoveryClass — статическая таксономия tool-name→класс; wire-расширение — schema-card с independent review, не начата |
| TabShell-wiring в product REPL | `LineageTab`-provider и `:aux`-seam есть и покрыты тестами, но продуктовый REPL (`modules/cli/chat`) вкладку не подключает |
| Запись артефактов в каталог рана | HeadlessRunner пишет `events.jsonl` + lineage-строку в summary; `lineage.scala`/`lineage-data.json` рождаются в golden/тестах и compile-gate — продовый ран их рядом с рансом ещё не пишет (обязательство LIN-03/08, §10) |
| Прочее, честно | `LineageCatalog` — scan без persistence; конкурентное чтение проекции (LIN2-03) покрыто частично; replay-команда есть как **standalone-вход** `raider.cli.lineage.ReplayCliMain` (LIN-10, свой argv/exit-коды, §10; entry-раннер — sanctioned ZIOAppDefault после integrated-gate forbidden-apis фикса), но в продукт-команду `raider` она ещё не вписана — wiring общего CLI отдельная карточка |

Карточки P-LIN — `docs/tasks/lineage.md`; контракт — ADR-027. Compile-сторона
гейта LIN реализована (`LineageCompileGateSpec`); полные milestone-обязательства
фазы P-LIN (live-wire, multi-job, schema-cards, product wiring) остаются
открытыми — этот раздел фиксирует фактическое состояние, а не завершение фазы.

## 10. Фактические команды и пути (сверено с modules/cli)

| Команда/путь | Факт (источник в коде) |
| --- | --- |
| `raider run (--bundle <jar> --workflow <name> \| --agent scout\|worker\|coder) (--input <text> \| --input-json <file>) --out <dir> [--mock] [--provider mock\|openai\|anthropic] [--base-url URL] [--model NAME] [--workspace DIR] [--context FILE]` | `CliArgs.parse` (`modules/cli/run/CliArgs.scala`): `--out` обязателен; `--agent` взаимно исключает `--bundle/--workflow`; `--api-key` — типированная ошибка (секреты не в argv) |
| `<out>/work-<uuid>/` — каталог рана; `run.json`, `events.jsonl`, `summary.md`, `transcript.json` | `HeadlessRunner.writeArtifacts` / конкатенация `--out` + `work-<uuid>` (`modules/cli/run/HeadlessRunner.scala`) |
| `events.jsonl` — per-job `EventEnvelope`, schemaVersion 1, дубликаты отброшены | `EventExport.jsonl` (`modules/cli/run/EventExport.scala`) |
| `artifacts/<run>/lineage.scala`, `artifacts/<run>/lineage-data.json` — рядом с рансом, в evidence-fingerprint | Решение ADR-027 §2; запись в каталог рана — обязательство P-LIN (LIN-03/08) |
| `ReplayCli [replay] <run-dir> --workspace <dir> [--data <file>]` — standalone replay-recorded вход (LIN-10): читает `<run-dir>/lineage-data.json` (или `--data`), изолирует одноразовую копию `--workspace` (`WorkspaceIsolation.isolate`+`verifyUntouched`), проигрывает запись через `ReplayHarness` (recorded-only, модельных вызовов нет), печатает вердикт | `ReplayCli` (`modules/cli/lineage/ReplayCli.scala`): stdout — `verdict: match` + нормализованный graph-hash, либо `verdict: mismatch` + `RunDiffReport` (первый расходящийся узел, оба hash); stderr — `typed-error: …`; exit-коды по ci-runtime §8 (`raider.core.result.ExitCodes`): 0 = Match, 10 = Mismatch, 21 = битый/отсутствующий data.json (typed `StoreError.Missing`/`Corrupt`), 20/22/23/24/26 — остальные typed-исходы (`RaiderError`/`ReduceError`/изоляция); параллельные replay — уникальные staging-каталоги, оригинал не пишется (LIN10-03) |

Примеры:

```sh
# headless-ран с lineage-артефактами в artifacts/<run> (после P-LIN):
raider run --agent coder --input "Исправь regression и запусти проверки" \
  --out artifacts/<run> --mock

# replay-recorded — standalone-вход LIN-10 (recorded-only, без модельных вызовов):
java -cp <cli-classpath> raider.cli.lineage.ReplayCliMain artifacts/<run> \
  --workspace <workspace-рана>
#   verdict: match   + graph-hash, exit 0
#   verdict: mismatch + первый расходящийся узел, exit 10
#   typed-error (битый/отсутствующий lineage-data.json), exit 21
# (вписывание в продукт-команду `raider` — отдельная карточка, `run/**` — другая lane)
```

Флаги replay/live в продукт-команду `raider` этим не вводятся: standalone-вход
фиксирует свою поверхность в `raider.cli.lineage` (LIN-10); вписывание в общий
CLI и live/гибрид-флаги — отдельные карточки и смена публичного контракта
(отдельный ADR при необходимости).

## 11. Связанные документы

- [ADR-027: Session lineage](adr/ADR-027-session-lineage.md) — контракт и решения владельца.
- [P-LIN карточки](tasks/lineage.md) — LIN-01…LIN-08, acceptance и allowed paths.
- [Runtime contracts §14/§15](runtime-contracts.md) — ошибки; отложенные сложности.
- [Quality gates](quality-gates.md) — гейт LIN, stage-aware activation.
- [TUI design](tui-design.md) — режимы A/B, tab-host.
