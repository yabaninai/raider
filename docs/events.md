<!-- RAI-072: документация ФАКТИЧЕСКИ реализованного поведения шва событий
     runtime→UI (ADR-024) и потоковой записи events.jsonl (RAI-060,
     LIN-03A-prep). Новых решений не вносит; wire-схема заморожена
     (schemaVersion=1, 13 payload-кейсов). Каждое утверждение снабжено
     ссылкой на конкретный file:line в этом дереве. -->

# События runtime→UI: конверт, hub, reducer, events.jsonl

Шов событий — in-process канал, по которому runtime доставляет UI/CLI-потребителю
конверты событий одного job. Источники истины: `modules/runtime/src/main/scala/raider/runtime/events/` (модель, hub, reducer, кодек), излучатели в
`modules/runtime/src/main/scala/raider/runtime/` и потоковая запись в
`modules/cli/src/main/scala/raider/cli/run/`. Документ описывает только
реализованное; схема событий (13 payload-кейсов, `schemaVersion=1`) заморожена.

## 1. Конверт EventEnvelope

Конверт — единственный wire-тип шва: `schemaVersion` (в этой редакции всегда 1),
`seq` (per-job, монотонный с 1, без дыр), идентификаторы маршрутизации
`jobId`/`rootId`/`sessionId`, информационный штамп `atMs` (epoch millis) и один
типизированный `payload` — `events.scala:16-24` в
`modules/runtime/src/main/scala/raider/runtime/events/events.scala`.
`SessionSnapshot` — in-memory состояние reducer'а, wire-документом не является
(`events.scala:92-107`).

## 2. 13 замороженных payload-кейсов

Дискриминация по полю `type` (`events.scala:26-30`). Полный список ровно из 13
кейсов (`events.scala:31-61`; полнота зафиксирована тестом
`EventCodecSpec.scala:52-66,72-75` в
`modules/runtime/src/test/scala/raider/runtime/events/`):

| Кейс | Поля | Назначение |
| --- | --- | --- |
| `JobStatusChanged` | `from`, `to`, `reason: Option[WaitReason]` | переход статуса job (`events.scala:33-37`) |
| `Waiting` | `reason: WaitReason` | job собирается ждать (UX03) (`events.scala:39`) |
| `ModelCallStarted` | `round`, `model`, `messageCount: Option[Int]` | начало model-раунда (`events.scala:40`) |
| `ModelCallFinished` | `round`, `durationMs`, `usage: UsageSnapshot` | конец model-вызова (`events.scala:41`) |
| `ToolStarted` | `callId`, `tool`, `argsSummary` (≤512 символов, redacted) | старт tool-вызова (`events.scala:43-47`) |
| `ToolProgress` | `callId`, `tool`, `tail: LineTail` | ограниченный хвост вывода (`events.scala:48`) |
| `ToolFinished` | `callId`, `tool`, `outcome: ToolOutcome` | итог tool-вызова (`events.scala:49`) |
| `RetryScheduled` | `attempt`, `delayMs`, `cause` | запланированный retry-backoff (`events.scala:50`) |
| `BudgetChanged` | `reserved`, `observed: MicroUsd`, `unknownAttempts` | состояние бюджета (`events.scala:52-56`) |
| `Delegated` | `childJobId`, `agent` | форк дочернего агента (`events.scala:58`) |
| `Diagnostics` | `text` (≤2000 символов) | нетипизированный текст (`events.scala:59`) |
| `DiagnosticsCoalesced` | `lost: Long` | маркер потерянных diagnostics (`events.scala:60`) |
| `Terminal` | `outcome: JobStatus.Terminal` | ровно один, последний (`events.scala:61`) |

Вспомогательные типы: `WaitReason` — `Slot|Admission|Approval|Budget|ChildSlot|Backpressure` (`events.scala:64-65`); `ToolOutcome` — `Succeeded|Failed(reason)|Cancelled|Timeout` (`events.scala:68-72`); `LineTail` — последние строки с честными счётчиками усечения `truncatedLines`/`truncatedChars` (`events.scala:77-81`); `UsageSnapshot` — `reserved`/`observed` в micro-USD плюс флаг `unknown` («unknown не coerсится в 0/false», `events.scala:86-90`).

## 3. Wire-формат (zio-json)

Кодеки — `EventCodecs` (`codec.scala:24-85` в
`modules/runtime/src/main/scala/raider/runtime/events/codec.scala`); правила
зафиксированы в докстринге `codec.scala:8-23` и проверены тестом
`EventCodecSpec.scala:98-116`:

- идентификаторы кодируются plain-строками (`jobId: "j_1"`) — core-кодеки
  `modules/core/src/main/scala/raider/core/codecs.scala:18-22`;
- payload дискриминируется полем `type` (`codec.scala:81`, вывод для enum);
- `JobStatus`/`JobStatus.Terminal` кодируются именами (`Queued`, `Running`,
  `Waiting`, `WaitingApproval`, `Cancelling`, `Succeeded`, `Failed`,
  `Cancelled`, `Interrupted`, `Uncertain`); неизвестное имя — ошибка декода
  (`codec.scala:29-71`);
- `MicroUsd` — plain integer (`codec.scala:26-27`);
- отсутствующие `Option`-поля опускаются в wire и декодируются как `None`
  (`codec.scala:18-19`, проверка `EventCodecSpec.scala:114-115`).

Пример строки events.jsonl (форма подтверждена тестом `EventCodecSpec.scala:98-116`):

```json
{"schemaVersion":1,"seq":1,"jobId":"j_1","rootId":"cli_ab12cd34","sessionId":"","atMs":1760000000000,"payload":{"type":"JobStatusChanged","from":"Queued","to":"Running"}}
```

Добавление кейса — schema-решение (новый schemaVersion), а не тихое изменение
формы (`events.scala:26-29`, `codec.scala:12-14`).

## 4. EventHub: публикация, подписка, маршрутизация

`EventHub` — живой in-process hub (`hub.scala:29-65` в
`modules/runtime/src/main/scala/raider/runtime/events/hub.scala`):

- `publish(jobId, rootId, sessionId, payload)` — присваивает `lastSeq + 1`
  атомарно с постановкой в буфер под per-job блокировкой; последовательность
  коммитится только при фактическом приёме в буфер класса доставки, поэтому
  доставленный поток без дыр (`hub.scala:31-41,10-16`); per-job lock
  сериализует publisher'ов одного job (`hub.scala:359-374`), разные job'ы
  не делят lock (`hub.scala:360-363`);
- `subscribe(jobId)` — регистрация очереди подписчика и чтение снимка в ОДНОЙ
  STM-транзакции, затем поток фильтруется по `seq > snapshot.lastSeq`: каждое
  событие либо в снимке, либо в потоке — never both, never neither
  (`hub.scala:43-57,183-219`);
- `snapshot(jobId)` — текущее свёрнутое состояние; `None` для job без событий
  (`hub.scala:59-60,221-222`);
- `droppedDiagnostics` — счётчик сброшенных diagnostics-конвертов; drop'ы
  считаются, никогда не молчат (`hub.scala:62-65,224,352-356`).

Классы доставки — таблица маршрутизации `routeOf` (`hub.scala:135-152`):

- RELIABLE (offer с ограниченным ожиданием 5 секунд, при таймауте — типизированный
  `EventError.Backpressure`, без тихой потери, per-job порядок): `Terminal`,
  `Waiting`, `JobStatusChanged`, `BudgetChanged`, `RetryScheduled`, `Delegated`;
- DIAGNOSTICS (non-blocking offer, при переполнении drop + счётчик):
  `ModelCallStarted`, `ModelCallFinished`, `ToolStarted`, `ToolProgress`,
  `ToolFinished`, `Diagnostics`, `DiagnosticsCoalesced`.

Константы: таймаут надёжной публикации 5 с (`hub.scala:70`), ёмкость очереди
подписчика 1024 (`hub.scala:73`), ёмкости буферов по умолчанию 256/1024, не-положительные значения — `IllegalArgumentException` на построении
(`hub.scala:85-96`). Publisher никогда не ждёт очередей подписчиков —
backpressure абсорбируют forwarder-волокна (`hub.scala:21-22,247-251`).

Относительный порядок прибытия reliable- и diagnostics-конвертов одного job
НЕ специфицирован (два независимых forwarder-волокна); потребитель обязан
пересортировать по `seq` перед свёрткой (`hub.scala:49-53`). Известный
недостаток среза: forwarder'ы не имеют supervision — дефектный forwarder может
потерять один конверт и один permit; supervision отложено на отдельную карточку
(`hub.scala:228-234`).

У hub нет `stop()`: это process-lifetime объект, teardown делегирован
окружающему scope (`hub.scala:24-27,129-133`).

## 5. SessionReducer: свёртка потока в снимок

Чистая per-job свёртка `(snapshot, envelope) => snapshot` без эффектов
(`reducer.scala:8-20,47-64` в
`modules/runtime/src/main/scala/raider/runtime/events/reducer.scala`):

- `seq < lastSeq` → `EventError.Malformed` (регрессия); `seq == lastSeq` —
  идемпотентно; новое событие после терминального снимка → `Malformed`
  (Terminal ровно один и последний) (`reducer.scala:47-64`);
- начальное состояние — `Queued`, пустой хвост, нули (`reducer.scala:29-45`);
- свёртка полей: статус/причина ожидания из `JobStatusChanged`/`Waiting`,
  `usage` из `ModelCallFinished`/`BudgetChanged` (unknown = `unknownAttempts > 0`),
  `activeTool` из `ToolStarted`/`ToolProgress`/`ToolFinished`,
  `Terminal` фиксирует статус и сбрасывает причину ожидания
  (`reducer.scala:69-103`);
- прогресс-хвост ограничен: последние 8 строк (`maxTailLines`), строка
  обрезается на 2000 символов (`maxTailLineChars`); каждая вытесненная строка и
  каждый обрезанный символ учитываются в `truncatedLines`/`truncatedChars`
  (`reducer.scala:23-27,111-129`).

## 6. Ошибки шва

`EventError` — собственная sealed-семья (ядро `RaiderError` заморожено, конверсия
только явная): `Backpressure(jobId, cap)` — переполнение reliable-буфера по
таймауту, и `Malformed(msg)` — регрессия seq, событие после Terminal,
неизвестное wire-значение
(`modules/runtime/src/main/scala/raider/runtime/events/errors.scala:12-31`).

## 7. Излучатели: кто публикует события

- Слой job: `StatusLedger.onTransition` мостит хук переходов `JobManager` в hub —
  терминальный `to` публикуется как `Terminal(outcome)`, прочие — как
  `JobStatusChanged(from, to, reason = None)`; ошибки публикации поглощаются
  (`StatusLedger.scala:7-29,36-50` в
  `modules/runtime/src/main/scala/raider/runtime/jobs/`). Сессия слоя job —
  пустая (`StatusLedger.scala:32-33`).
- Слой loop: `LoopEvents` привязывает один запуск `AgentLoop` к job/root/session
  и излучает `Waiting(Slot)`, `RetryScheduled`, `ModelCallStarted/Finished`,
  `ToolStarted/Finished`, `Diagnostics` (`LoopEvents.scala:21-39,150-240` в
  `modules/runtime/src/main/scala/raider/runtime/loop/`). Любая ошибка
  публикации поглощается — запуск не ломается (`LoopEvents.scala:95-99`);
  usage всегда `unknown = true` (loop стоимость не наблюдает,
  `LoopEvents.scala:41-44,175-182`). Гигиенические границы: `argsSummary` ≤512,
  `Diagnostics` ≤2000, `cause`/`reason` ≤512 (`LoopEvents.scala:244-253`).
  Отказы tool'а публикуются как `ToolFinished(Failed("refused: …"))`, включая
  отказ без предшествующего `ToolStarted` (`LoopEvents.scala:200-214,255-263`).
- Делегирование: `DelegationEvents` публикует `Delegated(childJobId, agent)`
  на форк и `Diagnostics` на наблюдаемое завершение ребёнка
  (`DelegationToolset.scala:68-71` в
  `modules/runtime/src/main/scala/raider/runtime/delegate/`).

## 8. Headless-ран: потоковый events.jsonl

`raider run` выполняет весь запуск (preflight включительно) как ОДНУ job
JobManager'а с реальным hub (`HeadlessRunner.scala:117-123` в
`modules/cli/src/main/scala/raider/cli/run/`); состояние invocation живёт в
`<out>/work-<uuid8>` (`HeadlessRunner.scala:112-116`) — параллельные запуски
каталогов не делят.

Поток событий пишется инкрементально в `<workDir>/events.jsonl`
(`HeadlessRunner.scala:203-277`; writer — `EventsJsonlWriter.scala:35-111` в
том же каталоге):

- файл открывается eagerly (CREATE + APPEND, UTF-8, родительские каталоги
  создаются) — наблюдатель видит файл с первой записи (`EventsJsonlWriter.scala:83-109`);
- каждый конверт — одна JSONL-строка: запись + flush в page cache, БЕЗ fsync
  (крах/cancel оставляет честный обрезанный хвост уже сброшенных строк, без
  фабрикации завершающей строки) (`EventsJsonlWriter.scala:22-33,42-63`);
- один writer под семафором — строки не чередуются; append после `close` —
  defect, `close` идемпотентен (`EventsJsonlWriter.scala:26-33,47-74`);
- порядок строк: job-тело держится на барьере до регистрации подписчика;
  стартовый переход `Queued→Running` восстанавливается ИЗ снимка подписки
  (`startTransition`, `EventExport.scala:77-94` в том же каталоге) и пишется
  первой строкой; остальное приходит по подписке и пересортируется в строгий
  per-job seq-порядок (`EventExport.inOrder`, `EventExport.scala:21-53`);
  оседавший запуск плотен с 1 и заканчивается Terminal-конвертом
  (`HeadlessRunner.scala:180-191,255-266`);
- наблюдённый перед оседанием drop-счётчик hub экспортируется как настоящий
  `DiagnosticsCoalesced`-конверт (best-effort, diagnostics-класс, никогда не
  после Terminal) (`HeadlessRunner.scala:224-233`);
- перед завершением — ограниченное wall-clock ожидание догона наблюдателя
  (`awaitCaughtUp`, `EventExport.scala:96-128`), затем коллектор прерывается
  (`HeadlessRunner.scala:267-274`);
- summary.md несёт строку `events: N event envelope(s) streamed to
  events.jsonl (schemaVersion 1)` (`HeadlessRunner.scala:573`).

Пересортировка `inOrder` держит ограниченную pending-карту и сливает по мере
появления ожидаемого seq; доставленный hub-поток без дыр (dropped diagnostic не
потреблял seq), поэтому слив завершается; cap 4096 — чисто защитный
(`EventExport.scala:21-53`). `EventExport.jsonl` — каноническая сериализация
коллекции конвертов (сортировка по seq, дубликаты отбрасываются защитно,
`EventExport.scala:55-66`).

Политика переполнения для подписчика headless-рана соответствует hub-семантике:
медленный подписчик давит backpressure на forwarder, diagnostics-переполнение
дропается и считается внутри hub, reliable-конверты (статус/Terminal/approval)
молча не теряются (`HeadlessRunner.scala:193-201`).

## 9. Границы реализованного (честно)

- Шов — in-process: внешнего transport'а (WebSocket/HTTP-stream) у событий нет;
  единственная файловая поверхность — `events.jsonl` одного headless-рана.
- CLI-команды подписки/повтора событий отсутствуют: `raider run` пишет
  `events.jsonl`; отдельного `raider events` в дереве нет
  (`CliArgs.scala:92-102` — только подкоманды `run`/`chat`, в
  `modules/cli/src/main/scala/raider/cli/run/`).
- Forwarder'ы hub без supervision (`hub.scala:228-234`); bounded rotate
  events.jsonl не реализован — ограничение отложено на слой JobJournal
  (`EventsJsonlWriter.scala:17-20`).
- Журналирование излучений (JobJournal, RAI-093) — отдельный опциональный
  канал `LoopEvents` с собственным journal-local seq; оно не часть
  events.jsonl и не восстанавливает hub-состояние (`LoopEvents.scala:46-78`).

## 10. Тесты, фиксирующие поведение

`EventCodecSpec` (roundtrip всех 13 кейсов и wire-формы,
`modules/runtime/src/test/scala/raider/runtime/events/EventCodecSpec.scala`),
`EventHubSpec`/`SessionReducerSpec` (порядок, backpressure, drop-счётчик,
правила seq — там же), `EventExportSpec`/`EventsJsonlWriterSpec`/
`HeadlessRunnerSpec`/`HeadlessRunnerCrashSpec` (reorder, первая строка,
стриминг, честный хвост — `modules/cli/src/test/scala/raider/cli/run/`).
