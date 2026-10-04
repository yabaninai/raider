<!-- Translated from Russian original. Key terms preserved as-is. -->

# Runtime и контракты

## 1. Технологические решения

- Scala 3; baseline candidate — 3.9.0, JDK 21 LTS. RAI-001 проверяет опубликованные artifacts и совместимость. Итоговые exact версии фиксируются в build/ADR; никакого `latest`, snapshots или плавающих patch versions в CI.
- ZIO 2 для effects/fibers/scopes/STM, ZIO Test для проверок. Не создавать свой scheduler и не поддерживать одновременно Cats Effect.
- sbt multi-project как канонический build. Scala CLI — optional developer/reference launcher, не второй несовместимый build graph.
- REPL engine — pinned официальный Scala REPL через узкий adapter; JLine для ввода/terminal coordination. Compiler-specific imports только в `repl-engine`.
- HTTP implementation выбрать один в RAI-001: предпочтительно JDK HttpClient + изолированный ZIO adapter, если streaming/cancellation проходят spike; иначе documented effect-native client. Wire parser и client transport — разные классы.
- JSON/schema: выбрать одну согласованную пару codec/schema libraries, проверить derivation/REPL в spike. Не писать общий schema derivation framework.
- Persistence MVP: versioned append-only JSONL journal + atomic snapshots/artifacts; один writer. SQLite рассматривается по измерениям и необходимости запросов, не обязательна для первой версии.

Совместимость pinned Scala/ZIO/REPL/JLine проверяется реальным prototype; выбранные библиотеки сами по себе не доказывают рабочие signals/classloaders.

## 2. Границы модулей

```text
raider-core            contracts, IDs, errors, schemas, Agent/Task descriptions
raider-runtime         jobs, scopes, mailbox, budgets, loop, cancellation
raider-dsl             immutable builders, compositional helpers
raider-provider-chat   OpenAI-compatible Chat HTTP/SSE normalization
raider-provider-anthropic Anthropic-compatible Messages HTTP/SSE normalization
raider-ci              context, trust, checks, exit codes, artifacts, platform adapters
raider-yabanin         labels, auth/session keys, capability doctor, reconciliation
raider-tools           fs/search/edit/process primitives
raider-repl-engine     compiler/JLine integration only
raider-cli             headless run/doctor and bundle loading; no compiler
raider-repl            interactive facade, commands/rendering, compile launcher
raider-testkit         scripted backend, fake tools, virtual time, contract suite
```

Дополнительные modules только по соответствующим карточкам: `compat-pi`, `compat-opencode`, `backend-pi`, `backend-opencode`, `eval-export`.

Зависимости однонаправленные: core не знает о terminal, HTTP client, Yabanin, compiler или concrete tool implementation. Runtime зависит от core и абстрактных services. DSL не вызывает global runtime. CLI собирает layers. Testkit не попадает в production classpath.

Core/provider adapters независимы от Yabanin: [protocol contract](provider-protocols.md). Native ModelBackend нормализует оба wires; Yabanin layer добавляет optional gateway metadata/auth/accounting. Headless distribution исключает repl/repl-engine и их transitive compiler/JLine; dev distribution добавляет их. `raider-ci` не зависит от конкретного hosted CI SDK.

## 3. Типы и публичные signatures

Sketch, который должен стать compilable contract в RAI-002:

```scala
trait Program[I, O]:
  def apply(input: I): Task[O]
  def andThen[P](next: Program[O, P]): Program[I, P]
  def mapOutput[P](f: O => P): Program[I, P]

trait Agent[I, O] extends Program[I, O]

trait Workflow[I, O] extends Program[I, O]

trait Task[+A]:
  def map[B](f: A => B): Task[B]
  def flatMap[B](f: A => Task[B]): Task[B]

trait Job[A]:
  def id: JobId

trait Child[A]:
  def join: Task[A]

trait AgentBackend:
  def capabilities: BackendCapabilities
  def run(input: AgentRequest): ZStream[Scope, AgentError, AgentEvent]

trait ModelBackend:
  def capabilities: ModelCapabilities
  def stream(input: ModelRequest): ZStream[Scope, ModelError, ModelEvent]
```

Это два уровня, а не переименования одного интерфейса. `ModelBackend` выполняет один inference attempt через OpenAI/Anthropic wire. `AgentBackend` выполняет агентный запуск: native implementation содержит loop, который вызывает ModelBackend и Raider tools; pi/OpenCode implementation наблюдает внешний engine, владеющий своим loop/tools. Native loop не исполняет второй раз tool observations внешнего backend. `ModelEvent` содержит complete model/tool-request data; `AgentEvent` содержит lifecycle/output/tool-observation/usage evidence. Runtime root/scopes/budgets контролирует обе реализации с учётом capabilities.

RAI-002 закрепляет concrete event/error/request schemas, final completion и cancellation contract; `ZStream/Scope` imports explicit. `Job[A]` opaque/immutable handle с типизированным result reference; heterogeneous registry только на admin boundary с codecs. Публичные outputs не возвращают `Any`/unchecked casts. Endpoint compatibility не означает external engine compatibility; это разные suites и release capabilities.

Agent и Workflow реализуют общий `Program[I,O]` с `apply(input):Task[O]`, `andThen` и `mapOutput`. Public `ask/start` — extension methods из `raider.repl`, не члены core interface. Core Program не зависит от terminal/global ReplSession. `all(Program,Program)` строит Program с tuple output; `all(Task,Task)` строит Task. Один effect interpreter исполняет оба случая.

`Job.await` и Task `.run()` используют единую facade boundary. Не дублировать разные implementations для functional и method syntax. Facade обнаруживает попытку использования из workflow fiber и возвращает понятную ошибку; library workflows используют apply/fork/join. Type signatures запуска обязаны иметь `using ReplSession` или эквивалентную явную capability, установленную prelude.

Task — тонкая обёртка над выбранным ZIO effect с metadata/step hooks. Не обещать inspectable serialized AST для arbitrary Scala callbacks. Structured workflow plan IR вводить лишь в карточках durable recovery.

Отдельные IDs: SessionId, RootId, JobId, StepId, AttemptId, ToolCallId, MessageId, ArtifactId. RootId и parentJobId сохраняются во всех descendants/events; attempts одного model request различимы. Opaque types допустимы, если codecs/REPL printer остаются простыми.

## 4. Job state machine

Состояния выполнения:

```text
Queued → Running ↔ Waiting
                ↔ WaitingApproval
Queued/Running/Waiting/WaitingApproval → Cancelling
Running/Waiting → Succeeded | Failed
Cancelling → Cancelled | Interrupted | Uncertain
```

`Succeeded`, `Failed`, `Cancelled`, `Interrupted`, `Uncertain` terminal. Deadline/budget/protocol/safety причины записываются отдельно в StopReason, а не смешиваются в произвольные status strings.

- Succeeded: output validation завершена, обязательные child results получены, owned resources finalized.
- Failed: known unsuccessful outcome с structured error.
- Cancelled: acknowledged cancellation, cleanup completed.
- Interrupted: процесс завершён/восстановлен после crash, активное выполнение не существует.
- Uncertain: backend/tool мог выполнить действие, подтверждение результата/остановки отсутствует.

Отмена конкурирует с финальным success через единую atomic transition; один terminal winner, один root result completion. Late events сохраняются как late observations и не изменяют terminal outcome назад. Cancelling блокирует запуск новых model/tool/child действий.

`await` идемпотентен и допускает несколько waiters. Await terminal job не вызывает provider. Root completion не должно предшествовать обязательным finalizers; при grace timeout статус отражает незавершённую/неподтверждённую очистку.

## 5. Владение и cancellation

CI invocation или REPL session owns root jobs. Headless runner владеет тем же root scope и finalization, а не имитирует terminal session. REPL session owns root jobs. Root job owns root scope. `fork` принадлежит ближайшему workflow scope. Child не переживает владельца без explicit reassignment в будущем daemon API.

Регистрация child и finalizer должна быть cancellation-safe: нет промежутка, где fiber уже запущена, а registry/cleanup ещё не знает о ней. Использовать узкую uninterruptible registration section и вернуть interruptibility во внешние действия.

Не путать supervision failures с join failures: unawaited child error записывается, policy определяет propagation; `all` всегда fail-fast по умолчанию. `batchCollect` явным образом преобразует domain failures в Outcome и не глотает внешнее interruption.

HTTP cancellation: прекратить чтение, закрыть response body, cancel underlying request и завершить accounting с known/unknown usage. TCP close не доказывает, что upstream перестал начислять деньги.

Process cancellation: сначала graceful interrupt/terminate owned process group, затем bounded kill escalation. Работа с PID reuse и grandchildren требует platform contract. Не убивать чужие процессы и не считать запрошенную отмену подтверждённой.

## 6. Concurrency и лимиты

Есть разные counters:

- active jobs и queued jobs;
- одновременно выполняющиеся model HTTP calls;
- одновременно выполняющиеся tools/processes;
- root descendants, глубина дерева, inbox capacity;
- retries/repair attempts, steps, wall time, tokens, money reservations.

Model/tool permit удерживается только на соответствующей операции. Родитель, ожидающий child, не держит permit для исполнения ребёнка. Минимальный acceptance: maxConcurrentLlm=1, parent делегирует ребёнку и ждёт — deadlock отсутствует.

Acquisition order: eligibility/deadline → concurrency permit → recheck cancellation → atomic budget reservation → dispatch. Cancellation в любой точке освобождает reservation/permit по фактическому dispatch status. Нельзя удерживать денежную reservation весь срок ожидания очереди без обоснованной политики.

Bounded queues: explicit backpressure, fair admission между roots; один recursive agent не вытесняет всех. Large fanout проверяется до запуска. Deadlines отменяют queued jobs, не ждут получения semaphore.

Parallel mutating tools по умолчанию сериализуются в workspace. Параллельные coding agents получают отдельные worktrees; merge/diff review — explicit step. Общий context agent не даёт права конкурентно переписывать один файл.

## 7. Root budget ledger

Ledger учитывает весь root и descendants; резервации atomic. Деньги — целые micro-USD или exact decimal, не Double для суммирования. Unit tests проверяют rounding, overflow и отсутствие negative counters.

Данные стоимости различимы:

- estimated/reserved — до/во время вызова;
- observed — подтверждённые данные gateway/provider;
- uncertain — попытка могла быть оплачена, сумма неизвестна;
- unpriced — usage известен, цена отсутствует.

Unknown не равен zero. Retry и structured-output repair — новые оплачиваемые attempts, расходуют общий cap. Резервация включает conservative input estimate и explicit max output tokens при доступной цене. При hard monetary cap и неизвестной/неограниченной цене запуск отклоняется или требует иной явно выбранной политики; «жёсткий USD cap» не выводится из post-hoc usage.

Доступность gateway billing может отставать. Local ledger обеспечивает дисциплину launch, gateway enforce — свою границу. Глобальный cross-process hard budget нельзя обещать на одном process-local STM ledger. P2 потребует shared authority/admission, а не polling analytics.

## 8. Native agent loop

1. Freeze AgentVersion, context snapshot, tool registry, resolved model role и limits.
2. Собрать structured messages, включая relevant instructions/skills/artifact references.
3. Зарезервировать attempt, вызвать backend, нормализовать streaming events.
4. Если есть tool calls: собрать полные arguments, validate schema, policy и дедлайн.
5. Выполнить разрешённые tools, сопоставить результат с tool_call_id, записать события.
6. На безопасной границе применить steering messages; проверить completion/budget/loop limits.
7. Если есть final answer: decode/validate output; bounded repair при configured policy.
8. Завершить outcome, cleanup, journal и cost reconciliation.

Partial streaming tool arguments никогда не выполняются. Несколько calls одного шага не автоматически параллельны: нужна tool capability `readOnly/concurrentSafe`; mutation порядок явный. Отсутствующий tool, invalid args и denial дают structured feedback модели с bounded attempts, не silent ignore.

## 9. Backend protocol

Normalized events: Started, TextDelta, ToolCallDelta/ToolCallReady, UsageObserved, MetadataObserved, Finished, Failed. Provider reasoning — optional metadata с provider-specific retention policy; не считать reasoning основным ответом и не требовать раскрытия скрытого рассуждения.

BackendCapabilities указывает streaming, tools, structured output, steering, cancellation acknowledgement, accounting precision и context/session support. Capabilities имеют состояния supported/unsupported/unknown; неизвестное не становится true.

External backend may own tool execution. В таком режиме Raider tool policy не считается enforcement внутри внешнего процесса; проверяется mapping/configuration backend или tool proxy. Unsupported guarantees видны в doctor/launch report.

External retries и gateway failover могут добавить attempts, не видимые client напрямую. Accounting отражает coverage; adapter не выдумывает точную upstream attempt count.

## 10. Tools и полномочия

Tool[I,O] содержит name/version, input schema/codec, output codec, description, effect handler, timeout, capabilities и recovery class:

- pure/read-only;
- idempotent с явным idempotency key;
- mutating non-idempotent.

Tool invocation включает principal/capabilities, workspace, root/job IDs, cancellation и sanitized context. Tool не получает raw parent credentials без необходимости.

Minimum tools: scoped fs.read, fs.search, fs.edit с expected content hash, exec.run(argv, cwd, timeout). Shell syntax — отдельный explicit capability `exec.shell`, не способ вызвать любую команду в базовом tool. Validate paths, symlinks, traversals, output bytes, subprocess env allowlist и process lifetime.

Path prefix string не является workspace containment. Для чтения resolve real path; мутации учитывают symlink/TOCTOU и ownership. Portable policy ограничивает доверенные tools; сильная изоляция потребует OS/container sandbox. Ни compiler, ни загруженный Scala-plugin этим не ограничены.

## 11. Делегирование самой моделью

Model-callable tools: delegate, await_agent, send_agent, cancel_agent, list_agents. Они используют тот же JobManager/root ledger, что workflow `fork`.

delegate принимает registered AgentId/Version и encoded input; runtime проверяет schema, allowed child profile, depth/count, context policy и capabilities intersection. Возвращает child handle ID и state, не весь transcript.

Ownership остаётся за job/scope инициатора. Await child suspends parent loop и не держит model slot. У ребёнка нет автоматического права повторно делегировать; это отдельная policy. Context.TaskOnly — default, Context.Summary/Artifacts — explicit, full transcript — opt-in с size/redaction controls.

Промпт с именем агента не создаёт executable callback. При отсутствующем registry name — structured error. Детали cross-agent messages защищены scope/owner access checks.

## 12. Расширения

Extension package содержит manifest: ID, semantic version, API version range, dependencies, provided agents/tools/providers/middleware, permissions, resources и lifecycle.

Лёгкий уровень: Markdown agents/skills/prompts и config. Runtime слой: compiled Scala module через small SPI + effectful scoped acquisition. Внешний уровень: process/HTTP backend. TypeScript расширения автоматически в JVM не загружаются.

Registry snapshots immutable и versioned. Reload definitions применим к новым jobs; running jobs продолжают с pinned snapshot. Reload compiled plugins/classloaders во время работы отложен; первая версия допускает restart для binary changes.

Events notification-only; middleware transformer/decision interfaces отдельные. Defined stable ordering priority + extension ID; error policy documented. Timeout middleware не должен заблокировать cancellation. Mandatory ceilings применяются после user middleware и не могут им обходиться.

## 13. Journal, artifacts и recovery

Event envelope: schema_version, event_id, session_id, root_id, job_id, parent_job_id, step_id, attempt_id, monotonic per-root sequence, timestamp, event_type, sanitized payload.

Sequence assignment/persistence единым writer. Critical state transitions/events flush до сообщения о завершении. Token delta events могут coalesce; aggregate outcome/usage/tool decisions не теряются. Journal I/O failure → explicit degraded/failed execution; не выдавать подтверждённую сохранность.

Raw prompts/transcripts хранить отдельно по policy, не в telemetry по умолчанию. Artifacts имеют ID, SHA-256, media type, size, provenance и workspace-relative location; пределы размера и redaction. Journal не должен сохранять live Java object refs или arbitrary closures.

После journal/archive RAI-027: при restart показать архив, пометить незавершённые jobs Interrupted/Uncertain. Не выполнить автоматически последний tool. P1 replay fixtures — без внешних side effects. P2 resume — только serialized plans/registered named steps с documented recovery policy. Automatic restoration arbitrary `val`, classes и JVM stack не обещается.

## 14. Errors

Structured error families: Configuration, Compilation, InputValidation, OutputValidation, ProviderAuth, ProviderRateLimit, ProviderBudget, ProviderUnavailable, StreamProtocol, ToolDenied, ToolFailed, ToolOutcomeUncertain, DeadlineExceeded, LocalBudgetExceeded, ChildFailed, Cancelled, JournalFailure, CapabilityUnsupported.

Ошибка включает stable code, public explanation, retryability, safe details, root/job/attempt IDs. Secret/provider body с credentials не попадает в public details. Domain failure, defect и interruption различимы; programmer defect не маскируется как успешный текстовый ответ.

## 15. Отложенные сложности

Не делать в MVP: distributed scheduling, daemon attach, arbitrary durable Scala replay, self-modifying plugins, auto-merge/push, custom DSL parser, macro/direct-style transformation, общий cross-effect API, самостоятельное повторение gateway routing, marketplace/signing infrastructure. Их отсутствие не мешает полноценному local REPL и native agent loop.
