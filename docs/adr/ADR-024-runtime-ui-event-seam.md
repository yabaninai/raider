# ADR-024: Runtime→UI event seam (envelopes, snapshot+subscribe, progress)

Status: **Proposed** (freeze после independent review). Дата: 2026-10-06.
Owner: coordinator (P0.B, [roadmap-12k](../roadmap-12k.md) §P0.B, 160 h package).
Reviewers: independent, см. work record. Сохраняет силу [ADR-016](ADR-016-tui-renderer.md)
(inline renderer поверх JLine — этот ADR не меняет renderer-решение).

## 1. Контекст

Фактическое состояние (P0.A [gap matrix](../reviews/2026-10-05-p0a-gap-matrix.md)):
единственный механизм событий в runtime — один callback
`trace: TraceEvent => UIO[Unit]` (`AgentLoop.scala:60`, 11 подтипов `TraceEvent`,
JSON codec в `modules/core/trace.scala`); статусы job — pull-only
(`JobManager.snapshot/adminView`, `JobSnapshot(id, root, label, status, lateObservations)`,
`JobStatus` state machine с `canTransition`); live watch отсутствует (только
initial+final snapshot); события admission wait / budget / retry / delegation /
tool-failure не эмитятся; `ModelCall/ToolCall*` хардкодят `round = -1`.
UI-потребители (CLI chat, REPL echo, headless stderr) пишут в терминал без
единого writer и без подписки.

Целевые требования: P1.A (envelopes с root/session/job/event IDs и per-job
sequence; reducer/snapshot плюс subscribe без окна потери), P1.C (диагностику
можно coalesce/drop со счётчиком; terminal/approval/accounting — отдельный
надёжный путь; slow viewer не блокирует cancellation), UX03/UX08 (статус с
причиной ожидания, честный unknown), M1 (вкладки по этим событиям).

## 2. Решение

### 2.1 Новое семейство событий вместо расширения TraceEvent

`TraceEvent` остаётся как есть (совместимость: transcript.json, consumers в
CLI/REPL/DelegationToolset). События для UI — новое sealed-семейство в
`modules/runtime/.../events/` с обязательными полями маршрутизации
(jobId/rootId/sessionId) и per-job sequence. Причина: `TraceEvent` не несёт
jobId/seq, расширять его ломающими полями нельзя без версии схемы; UI нужны
причины ожидания и bounded progress, которых в `TraceEvent` нет.

### 2.2 Per-job sequence и отсутствие окна потери

Sequence назначает `EventHub` per job (монотонно с 1, без пропусков).
`subscribe` возвращает начальный `SessionSnapshot` (watermark = lastSeq) и
поток конвертов с `seq > watermark` — подписка без окна потери: подписчик
дедуплицирует по seq и не пропускает события, случившиеся между snapshot и
подключением к потоку. Reducer — чистая функция `reduce(snapshot, envelope)`;
снапшот всегда восстанавливаем из потока конвертов (источник истины для UI —
события, не догадки рендера).

### 2.3 Два класса доставки

- **Reliable (terminal/approval/accounting)**: публикация terminal/approval/
  budget-событий идёт в **отдельный reliable-буфер** с bounded ожиданием
  publisher'а (cap + таймаут); при переполнении — typed `EventError.Backpressure`,
  не молчаливая потеря. In-process под cap события этого класса не теряются и не
  переупорядочиваются относительно per-job seq; за cap'ом или при падении
  процесса гарантии нет (durable journal — вне scope, §7).
- **Diagnostics (текстовые хвосты, промежуточные строки)**: publish в отдельный
  diagnostics-буфер с немедленным drop при его переполнении + счётчик
  `droppedDiagnostics`; slow consumer никогда не блокирует model/tool/cancellation
  пути (diagnostics-буфер не может исчерпать reliable-буфер). Coalesce допускается
  для диагностики с явным счётчиком потерь в конверте-заменителе
  (`DiagnosticsCoalesced(lost: Long)`).
- Cancellation проверяется вне model admission и не ждёт потребителей (инвариант
  P1.C: slow viewer не блокирует cancel).

### 2.4 Причины ожидания и bounded progress

`WaitReason` — исчерпывающий enum причин (Slot, Admission, Approval, Budget,
ChildSlot, Backpressure). Progress — только наблюдаемые факты: состояние tool
call и bounded хвост вывода (`LineTail`: последние N строк, счётчики
truncated), без выдуманных процентов и без мыслей модели. Стоимость —
`UsageSnapshot(reserved, observed, unknown: Boolean)`; unknown передаётся как
unknown, никогда не превращается в 0.

### 2.5 UI focus — только состояние интерфейса

Фокус вкладки/сессии — данные UI-слоя (`SessionRegistry`, RAI-057). Фокус не
меняет owner, budget, policy, admission и не отменяет/не приостанавливает jobs.
Переключение фокуса — операция реестра, а не runtime.

### 2.6 Схема и совместимость

`schemaVersion = 1`; JSON codec (zio-json) для конвертов; круглый trip обязателен
(контракт-тесты). Существующие TraceEvent-эмиттеры не меняются в рамках P1.A;
их интеграция с hub — отдельные карточки P1.B/P2.C (эмиттеры admission/model/
tool/retry/compaction/budget), каждая с cancellation/ownership/budget тестами.
Планируемый шов для реальной эмиссии: опциональный single-listener hook
JobManager (`onTransition(jobId, from, to) => UIO[Unit]`, неблокирующий вызов
после STM-коммита) — его добавление будет отдельной runtime-карточкой с
матрицей cancellation-тестов, не входит в freeze ниже.

## 3. Рассмотрённые альтернативы

1. **Расширить TraceEvent полями jobId/seq** — ломает существующих потребителей
   и совместимость схемы; отклонено.
2. **Единый zio.Hub со стратегией await** — slow consumer блокирует publisher
   (нарушение P1.C); отклонено.
3. **Только pull/polling снапшотов** — не даёт p95 ≤250 ms (UX03) без busy-loop;
   отклонено (pull остаётся для resync).
4. **Актор/кастомная шина с неограниченной очередью** — запрещено
   quality-gates §7.15 (queues bounded); отклонено.

## 4. Последствия и риски

- Риск потери диагностики при burst — осознанно, со счётчиком (не тишина).
- Риск дублей при replay подписки — дедупликация по (jobId, seq) обязательна у
  подписчика; reducer идемпотентен к повтору того же seq (typed error на seq
  назад).
- Порядок: события одной job обрабатываются строго по seq; события разных jobs
  не упорядочены между собой (reducer per job).
- Доставка reliable-событий ограничена cap'ом: ожидание publisher'а ограничено
  (bounded); при
  таймауте — typed `EventError.Backpressure`, не молчаливая потеря.

## 5. Frozen signatures (P1/P2 реализуют ровно это; изменения — новый ADR)

```scala
package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}   // существующие ids (modules/core/ids.scala)
import raider.runtime.jobs.JobStatus
import zio.{IO, UIO, ZStream}

final case class EventEnvelope(
  schemaVersion: Int,        // всегда 1 в этой редакции
  seq: Long,                 // per-job, монотонный с 1, без пропусков
  jobId: JobId,
  rootId: RootId,
  sessionId: SessionId,
  atMs: Long,                // epoch millis, информационное
  payload: EventPayload
)

enum EventPayload:
  case JobStatusChanged(from: JobStatus, to: JobStatus, reason: Option[WaitReason])
  case Waiting(reason: WaitReason)
  case ModelCallStarted(round: Int, model: String, messageCount: Option[Int])
  case ModelCallFinished(round: Int, durationMs: Long, usage: UsageSnapshot)
  case ToolStarted(callId: String, tool: String, argsSummary: String) // redacted, ≤512 chars
  case ToolProgress(callId: String, tool: String, tail: LineTail)
  case ToolFinished(callId: String, tool: String, outcome: ToolOutcome)
  case RetryScheduled(attempt: Int, delayMs: Long, cause: String)
  case BudgetChanged(reserved: MicroUsd, observed: MicroUsd, unknownAttempts: Long)
  case Delegated(childJobId: JobId, agent: String)
  case Diagnostics(text: String)                                       // ≤2000 chars
  case DiagnosticsCoalesced(lost: Long)
  case Terminal(outcome: JobStatus.Terminal)                           // ровно один, последним

enum WaitReason { case Slot, Admission, Approval, Budget, ChildSlot, Backpressure }

enum ToolOutcome { case Succeeded, Failed(reason: String), Cancelled, Timeout }

final case class LineTail(lines: Vector[String], truncatedLines: Int, truncatedChars: Long)
final case class UsageSnapshot(reserved: MicroUsd, observed: MicroUsd, unknown: Boolean)

final case class SessionSnapshot(
  jobId: JobId, rootId: RootId, sessionId: SessionId,
  status: JobStatus, waitReason: Option[WaitReason],
  lastSeq: Long,
  activeTool: Option[(String, String)],   // (tool, callId)
  progressTail: LineTail,
  usage: UsageSnapshot,
  updatedAtMs: Long
)

/** Собственное sealed-семейство по образцу JobsError (RaiderError sealed —
  * подтипы в другом файле невозможны); конвертация явная. */
sealed trait EventError:
  def toRaiderError: RaiderError
object EventError:
  final case class Backpressure(jobId: JobId, cap: Int) extends EventError:
    def toRaiderError: RaiderError = RaiderError.CapabilityUnsupported(
      s"event backpressure for ${jobId.value} (reliable cap $cap)")
  final case class Malformed(msg: String) extends EventError:
    def toRaiderError: RaiderError = RaiderError.InputValidation(msg)
  // NOTE: точные конструкторы RaiderError сверяются с modules/core/errors.scala
  // при реализации; менять RaiderError запрещено (frozen core).

trait EventHub:
  def publish(jobId: JobId, rootId: RootId, sessionId: SessionId,
              payload: EventPayload): IO[EventError, Unit]
  def subscribe(jobId: JobId): UIO[(SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])]
  def snapshot(jobId: JobId): UIO[Option[SessionSnapshot]]
  def droppedDiagnostics: UIO[Long]

object EventHub:
  /** Два независимых bounded-буфера: reliable и diagnostics. Diagnostics-full
    * → drop+счётчик (никогда не блокирует publisher); reliable-full → bounded
    * ожидание, затем EventError.Backpressure. */
  def make(reliableCapacity: Int = 256,
           diagnosticsCapacity: Int = 1024): UIO[EventHub]

object SessionReducer:
  def initial(jobId: JobId, rootId: RootId, sessionId: SessionId): SessionSnapshot
  def reduce(s: SessionSnapshot, e: EventEnvelope): IO[EventError, SessionSnapshot]
```

(`MicroUsd` переиспользуется из `raider.runtime.budget.BudgetLedger`;
`JobId/RootId/SessionId` — из `raider.core` (ids.scala, exported);
`JobStatus`/`JobStatus.Terminal` — из `raider.runtime.jobs`; новых money-типов и
дубликатов ids нет.)

## 6. Acceptance (в карточках RAI-055/057 и последующих карточках P1.B/P2.C)

EVT-01..04 (см. backlog) + JSON roundtrip + инварианты: единственный Terminal
последним по seq; reduce идемпотентен к дублю seq (typed error при регрессе);
drop только у diagnostics со счётчиком; slow consumer не влияет на timing
cancellation (замер в тесте с виртуальным clock).

## 7. Не входит

Реальная эмиссия из AgentLoop/JobManager (P1.B/P2.C), терминальный рендеринг
(P2.B/C, ADR-016), durable journal (P6), изменение gate policy. Этап gates для
RAI-055 — component (static/unit/contracts/runtime).
