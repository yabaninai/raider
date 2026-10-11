<!-- RAI-072: документация ФАКТИЧЕСКИ реализованного поведения отмены заданий
     (RAI-007/008/059/063). Новых решений не вносит; каждое утверждение
     снабжено ссылкой file:line в этом дереве. -->

# Отмена заданий: JobManager, scopes, OOB-канал

Документ описывает реализованную механику отмены в
`modules/runtime/src/main/scala/raider/runtime/` (плюс UI-граница в
`modules/ui/`). Контрактная рамка — [runtime-contracts.md](runtime-contracts.md)
§4–§5; здесь — фактическое поведение кода.

## 1. Статусная машина и Cancelling

Реализованные статусы: `Queued`, `Running`, `Waiting`, `WaitingApproval`,
`Cancelling` и терминальные `Succeeded`, `Failed`, `Cancelled`, `Interrupted`,
`Uncertain` (`JobStatus.scala:12-27` в
`modules/runtime/src/main/scala/raider/runtime/jobs/JobStatus.scala`). Легальные
переходы проверяет единственный guard `canTransition` (`JobStatus.scala:29-42`):
в `Cancelling` можно из `Queued`/`Running`/`Waiting`/`WaitingApproval`; из
`Cancelling` — только в `Cancelled`/`Interrupted`/`Uncertain`. Терминальный
статус не переходит повторно; поздняя попытка завершения — late observation
(`JobStatus.scala:5-9`).

Отмена не пишет отдельный статус-текст: причина завершения кодируется типом
терминального статуса и typed-ошибкой (см. §3), а не произвольными строками.

## 2. JobManager.cancel / cancelAny

`JobManager` владеет каждым job-волокном; `cancel`/`cancelAny` — точка входа
отмены (`JobManager.scala:291-311` в
`modules/runtime/src/main/scala/raider/runtime/jobs/JobManager.scala`):

- Семантика: одна атомарная STM-транзакция `transitionSTM(entry, Cancelling)`;
  если транзакция отклонена guard'ом (job уже терминален или ребро
  недопустимо) — возвращается `false`, ничего не происходит; иначе — `true`
  (`JobManager.scala:294-296,305-308`).
- Событийный хук перехода в `Cancelling` вызывается ДО выдачи interrupt'а —
  порядок событий job'а равен порядку коммитов (`JobManager.scala:297-303`).
- `cancel` и `cancelAny` имеют одинаковую семантику; `cancelAny` —
  гетерогенный вариант для scope/admin-владения (`JobManager.scala:282-289`).

Регистрация job'а — в узкой uninterruptible-секции: нет окна, где волокно уже
работает, а реестр о нём не знает (`JobManager.scala:29-32,115-124`). Если
прервана сама регистрация в окне fork'а, entry не остаётся «ghost Running»:
срабатывает `abortGhost` — статус `Cancelled`, публикация typed-исхода
`RaiderError.Cancelled` (`JobManager.scala:136-142,334-351`).

## 3. Гонка отмены с завершением: cancel побеждает

Финальное завершение — одна тотальная STM-транзакция `settle`
(`JobManager.scala:166-221`):

- если статус уже терминальный — только late observation, статус не меняется,
  хук не вызывается (`JobManager.scala:200-204,225`);
- если завершение соревнуется с подтверждённой отменой (`Cancelling` уже
  закоммичен, и `canTransition` запрещает прямое `Succeeded`/`Failed`) —
  ПОБЕЖДАЕТ отмена: статус `Cancelled`, waiters получают typed-исход
  `RaiderError.Cancelled("...cancelled while completing")`, завершение
  фиксируется как late observation (`JobManager.scala:205-216,227-243`).

Следствия:

- один терминальный победитель, один root result completion; `Terminal`-событие
  ровно одно на job (хук вызывается один раз на закоммиченное изменение,
  терминальный статус не допускает дальнейших переходов —
  `JobManager.scala:33-39`).
- `await` идемпотентен, поддерживает любое число waiters и не перезапускает
  задачу (`JobManager.scala:22-27,273-278`).
- Дефект в watcher'е не оставляет waiters висеть: публикуется typed `Failed`
  (`JobManager.scala:145-161`).
- Реестр ограничен (`maxRetained = 256` по умолчанию): при переполнении
  вытесняются самые старые ТЕРМИНАЛЬНЫЕ записи; живые job'ы не вытесняются
  (`JobManager.scala:250-271,408-417`).

## 4. RaiderScope: поддерево и честная отчётность

`RaiderScope` (RAI-008) владеет форкнутыми детьми и финализаторами
(`RaiderScope.scala:26-35` в
`modules/runtime/src/main/scala/raider/runtime/scopes/RaiderScope.scala`):

- `fork` отказывает в новых форках после начала закрытия — typed
  `RaiderError.Cancelled("scope ... is closing")`; запуск+регистрация — один
  uninterruptible шаг (no-orphan) (`RaiderScope.scala:45-67`).
- `close(grace)`: отмена всех незашитых детей через `cancelAny`, ожидание
  терминального исхода в пределах grace, затем финализаторы LIFO
  (`RaiderScope.scala:77-115`). Весь cancel+settle ребёнка ограничен grace:
  ребёнок, застрявший в uninterruptible-регионе, не держит scope заложником —
  он отчётится `Uncertain`, никогда не фиктивным `Cancelled`
  (`RaiderScope.scala:84-96`).
- Отчёт `ScopeCloseReport` честный: `cancelledChildren` (подтверждённо
  завершённые), `uncertainChildren` (не терминальные в grace), счётчики и
  ошибки финализаторов, признак прерванных финализаторов; итоговый статус
  `Closed | Uncertain | Interrupted` (`RaiderScope.scala:9-24,109-115`).
- `scoped`: сбой тела сохраняется; при чистом теле незавершённая уборка
  превращает успех в typed-провал — `ToolOutcomeUncertain` для Uncertain и
  `Cancelled` для прерванной уборки — фиктивного успеха нет
  (`RaiderScope.scala:119-150`).

## 5. Делегирование: model-tool, OOB-канал, дедлайны

Дочерние агенты живут в том же `JobManager`/root, что и workflow-fork
(`DelegationToolset.scala:17-20` в
`modules/runtime/src/main/scala/raider/runtime/delegate/DelegationToolset.scala`).

- Model-tool `cancel_agent`: родитель тратит model-раунд; для неизвестного
  ребёнка — структурный JSON-фидбек, для известного — `jobs.cancelAny` и
  `{"child_id":...,"cancelled":true|false}` (`DelegationToolset.scala:276-297,333-334`).
- Out-of-band канал (RAI-063): `cancelOutOfBand(childId)` и
  `cancelAllOutOfBand` зовут `jobs.cancelAny` НАПРЯМУЮ — без model-tool раунда
  и без model-admission; отмена доступна и когда «припаркованный» ребёнок
  держит единственный model-slot (`DelegationToolset.scala:52-59,366-387`).
  Возврат: `false` — неизвестный или уже терминальный ребёнок;
  `cancelAllOutOfBand` возвращает id, чья отмена принята.
- Дедлайны детей (Scala-level API, публичная схема delegate-tool не меняется):
  `setChildDeadline` планирует owner-side watchdog, повторный вызов заменяет
  предыдущий (`DelegationToolset.scala:389-410`); watchdog по истечении
  дедлайна отменяет ребёнка вне model-admission и ограничивает cleanup тем же
  grace-паттерном, что `RaiderScope.close` (`DelegationToolset.scala:443-478`).
- Честный исход дедлайна — `DeadlineOutcome`: `Settled` (отменён и терминален в
  grace), `Uncertain` (не терминален в grace — ненаблюдаемо, не фиктивный
  `Cancelled`), `AlreadyTerminal` (дедлайн сработал после завершения)
  (`DelegationToolset.scala:412-429,646-647`); grace по умолчанию 5 с
  (`DelegationToolset.scala:671-672`).

## 6. События отмены и exit code

- Слой job публикует переходы через `StatusLedger`: `to: Cancelling` идёт как
  `JobStatusChanged(..., to = Cancelling)`, терминальный `to` — как
  `Terminal(outcome)`; ошибки публикации поглощаются
  (`StatusLedger.scala:36-50` в
  `modules/runtime/src/main/scala/raider/runtime/jobs/StatusLedger.scala`).
- Слой loop при отменённом запуске/пост-пермитной отмене root'а излучает
  `ToolFinished(callId, tool, Cancelled)`; прерванный вызов ничего не излучает
  (`LoopEvents.scala:200-220` в
  `modules/runtime/src/main/scala/raider/runtime/loop/LoopEvents.scala`).
- Headless exit code: `RaiderError.Cancelled` маппится в `ExitCodes.Uncertain`
  (= 26) (`HeadlessRunner.scala:83` в
  `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`;
  `result.scala:29` в `modules/core/src/main/scala/raider/core/`).

## 7. UI-граница: report-only

Слой вкладок никогда не отменяет сам: `:close` на живой вкладке возвращает
выбор `Hide`/`CancelRequested`; `CancelRequested` — ТОЛЬКО отчёт (типизированный
фидбек `CancelReported`), прямого cancel-binding к runtime в UI нет —
привязка (RAI-062) не реализована
(`SessionRegistry.scala:84-95,97-114` в
`modules/ui/src/main/scala/raider/ui/SessionRegistry.scala`;
`ShellExecutor.scala:164-198`; `ShellFeedback.scala:32-40,92-95`). Детали —
[tabs.md](tabs.md) §5.

## 8. Честные ограничения

- Ожидание прерывания не безгранично: волокно в uninterruptible-регионе
  задерживает cancel; scope/watchdog в этом случае честно отчётятся
  `Uncertain`, а не ждут вечно (`RaiderScope.scala:84-96`;
  `DelegationToolset.scala:443-478`).
- Cancel побеждает завершение в статусе, но не переписывает уже
  опубликованный исход: поздние завершения — только late observations в
  счётчике (`JobManager.scala:196-221`).
- Документируемая семантика не обещает durable resume или exactly-once для
  произвольных лямбд: отмена прерывает волокно; `await` ждёт опубликованный
  исход и не перезапускает задачу (`JobManager.scala:22-27,273-278`);
  автоматическое восстановление/повтор не реализованы (рамка —
  [runtime-contracts.md](runtime-contracts.md) §13).
