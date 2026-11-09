package raider.runtime.conversation

import raider.core.{Program, RaiderError, RequestMessage, RootId, SessionId, Task}
import zio.{Fiber, IO, Promise, Queue, Ref, Semaphore, UIO, ZIO}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Declared ceiling set of a conversation session (validated at construction,
  * RA-CFG — invalid bounds never build a conversation, mirroring BUD-02).
  *
  * The bounds are HONEST limits, not a compaction policy: a history that would
  * exceed `maxHistoryChars` (or a session past `maxTurns`) is refused with the
  * typed `LocalBudgetExceeded` — never silently truncated (RAI-030 compaction
  * is a separate card and is deliberately NOT implemented here).
  *
  * Character accounting ([[ConversationBounds.requestChars]]) counts role and
  * content lengths plus one separator per message; the same accounting is used
  * at submit time, at dispatch time (authoritative) and on archive reopen.
  */
final case class ConversationBounds(
    maxTurns: Int,
    maxHistoryChars: Int,
    queueCapacity: Int
)

object ConversationBounds:

  /** Product defaults: 256 turns, ~128 KiB of request characters, a bounded
    * queue of 64 pending turns (explicit backpressure — runtime-contracts §6).
    */
  val default: ConversationBounds =
    ConversationBounds(maxTurns = 256, maxHistoryChars = 131072, queueCapacity = 64)

  /** Validated construction; every bound must be positive (RA-CFG). */
  def make(
      maxTurns: Int = default.maxTurns,
      maxHistoryChars: Int = default.maxHistoryChars,
      queueCapacity: Int = default.queueCapacity
  ): Either[RaiderError, ConversationBounds] =
    def positive(name: String, n: Int): Either[RaiderError, Unit] =
      if n < 1 then
        Left(RaiderError.Configuration(s"$name must be >= 1, got $n"))
      else Right(())
    for
      _ <- positive("maxTurns", maxTurns)
      _ <- positive("maxHistoryChars", maxHistoryChars)
      _ <- positive("queueCapacity", queueCapacity)
    yield ConversationBounds(maxTurns, maxHistoryChars, queueCapacity)

  /** The history accounting shared by submit-time checks, dispatch-time checks
    * and archive reopen: role length + content length + one separator per
    * message.
    */
  def requestChars(messages: List[RequestMessage]): Int =
    messages.foldLeft(0)((acc, m) => acc + m.role.length + m.content.length + 1)

/** One serialized turn of a conversation: exactly one prompt and, when the
  * turn completes, one reply. Prompt/reply pairs are the durable unit of the
  * session archive (schemaVersion 1).
  */
final case class ArchivedTurn(prompt: String, reply: String)

/** Typed outcome of one conversation turn. Every accepted turn settles EXACTLY
  * once, with one of these outcomes — a queued turn is never dropped (its
  * receipt always resolves, at latest on close).
  */
sealed trait TurnOutcome

object TurnOutcome:
  /** The turn ran and produced a reply; the pair is part of the history. */
  final case class Done(reply: String) extends TurnOutcome

  /** The turn was cancelled before it settled (close, or an explicit
    * cancellation); no reply was fabricated.
    */
  case object Cancelled extends TurnOutcome

  /** The turn failed with its typed domain error; no reply was fabricated. */
  final case class Failed(error: RaiderError) extends TurnOutcome

/** Immutable id of one conversation turn (per-conversation counter). */
final case class TurnId(value: String) extends AnyVal

/** Receipt of an accepted turn (RAI-036): returned immediately by
  * [[Conversation.submit]]; `settled` resolves exactly once with the turn
  * outcome — at latest when the conversation is closed (queued turns are
  * cancelled, never dropped).
  */
final case class TurnReceipt(
    id: TurnId,
    prompt: String,
    settled: Promise[Nothing, TurnOutcome]
):
  /** Await the turn outcome (idempotent; any number of waiters). */
  def await: UIO[TurnOutcome] = settled.await

/** The per-turn execution function a conversation is built with (RAI-036):
  * the conversation owns NO provider logic — it owns ORDER. The runner is the
  * single execution point per turn (in the REPL wiring this is the facade's
  * one text path over [[raider.runtime.loop.AgentLoop]]; in runtime fixtures
  * it is a scripted runner). Because the dispatcher runs at most one runner
  * call at a time, a conversation holds at most ONE provider turn in flight,
  * regardless of the session's admission limits.
  */
trait TurnRunner:
  def run(messages: List[RequestMessage]): ZIO[Any, RaiderError, String]

object TurnRunner:
  def apply(
      f: List[RequestMessage] => ZIO[Any, RaiderError, String]
  ): TurnRunner =
    new TurnRunner:
      def run(messages: List[RequestMessage]) = f(messages)

/** Conversation: a durable, serialized multi-turn session implementing the
  * ONE execution contract [[Program]] (runtime-contracts §3; card RAI-036;
  * dsl-experience §8 — ask/start are the same verbs, turns are serialized).
  *
  * Ordering and lifecycle semantics:
  *   - `apply(input)` builds a LAZY `Task` (API-03): nothing dispatches at
  *     construction. Running the task accepts the turn (typed refusal if the
  *     conversation is sealed, the queue is full or the history would exceed
  *     the bounds — RA-INP / RA-LBUDGET) and settles when the turn settles.
  *   - Turns are executed STRICTLY one at a time in acceptance order by a
  *     single dispatcher fiber: turn N+1 starts only after turn N settled —
  *     one provider turn at a time, independent of admission limits (SESSION-01).
  *   - `close` seals the conversation (idempotent, safe from any fiber) and
  *     settles EVERY accepted turn: an in-flight turn resolves by the
  *     completion-wins race (a turn that finished before its interrupt lands
  *     keeps its reply in the history; otherwise typed `Cancelled`), and every
  *     queued turn resolves `Cancelled`. No accepted receipt is ever dropped
  *     (SESSION-03). The dispatcher is retired with a shutdown sentinel — it
  *     is never interrupted, so it cannot die holding an unsettled receipt.
  *   - `cancelTurn` requests cancellation of a known turn: a queued turn is
  *     skipped at dispatch, an in-flight turn is interrupted; the receipt
  *     still decides the final outcome (completion wins), so `true` means
  *     "cancellation delivered", never "reply erased".
  *   - There is NO implicit compaction and NO implicit restart: history grows
  *     until a bound refuses it, and reopening an archived session executes
  *     nothing until the next explicit ask (SESSION-02).
  *
  * Cancellation safety (runtime-contracts §5): turn-fiber registration is a
  * narrow uninterruptible section under the dispatch lock; close scans and
  * interrupts in-flight fibers under the same lock, so there is no interval in
  * which a turn fiber runs unregistered. The history is single-writer (the
  * dispatcher). One documented handshake window remains: a turn the dispatcher
  * has already dequeued but not yet registered when close scans completes
  * naturally (completion wins) instead of being cancelled — its receipt still
  * settles exactly once.
  */
final class Conversation private[conversation] (
    val sessionId: SessionId,
    val root: RootId,
    val label: Option[String],
    val bounds: ConversationBounds,
    runner: TurnRunner,
    queue: Queue[Conversation.DispatcherMsg],
    history: Ref[List[RequestMessage]],
    sealedRef: Ref[Boolean],
    inFlight: Ref[Map[TurnId, Fiber[Any, Unit]]],
    dispatchLock: Semaphore,
    lifecycleLock: Semaphore,
    closedP: Promise[Nothing, Unit]
) extends Program[String, String]:

  import Conversation.{AssistantRole, DispatcherMsg, QueuedTurn, UserRole}

  private val turnSeq = AtomicLong(0)
  private val knownTurns = ConcurrentHashMap[TurnId, Unit]()
  private val cancelledFlags = ConcurrentHashMap[TurnId, Unit]()

  // ------------------------------------------------------------------
  // Program contract (the ONE execution contract, runtime-contracts §3)
  // ------------------------------------------------------------------

  /** Lazy turn submission (API-03): running the task accepts the turn and
    * settles with it. Submission refusals surface typed on the Task error
    * channel; turn failures/cancellations settle through the receipt and are
    * re-raised here as typed errors.
    */
  def apply(input: String): Task[String] =
    Task(
      submit(input).flatMap(_.settled.await).flatMap {
        case TurnOutcome.Done(reply) => ZIO.succeed(reply)
        case TurnOutcome.Cancelled =>
          ZIO.fail(
            RaiderError.Cancelled(
              s"conversation ${sessionId.value}: the turn was cancelled before it settled (close or explicit cancellation); no reply was fabricated"
            )
          )
        case TurnOutcome.Failed(err) => ZIO.fail(err)
      }
    )

  // ------------------------------------------------------------------
  // Turn lifecycle
  // ------------------------------------------------------------------

  /** Accepts one turn and returns its receipt immediately (the `start` verb's
    * background shape). Typed refusals: sealed conversation (RA-INP), blank
    * prompt (RA-INP), history bound exceeded at submit (RA-LBUDGET fast path),
    * full queue (RA-LBUDGET). The bounds are re-checked at dispatch — the
    * authoritative refusal lands on the receipt, never as silent compaction.
    */
  def submit(prompt: String): IO[RaiderError, TurnReceipt] =
    if prompt.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("conversation turn prompt is empty"))
    else
      lifecycleLock.withPermit {
        sealedRef.get.flatMap {
          case true =>
            ZIO.fail(
              RaiderError.InputValidation(
                s"conversation ${sessionId.value} is sealed: submit after close is refused; " +
                  "open a new conversation or reopen an archive explicitly"
              )
            )
          case false =>
            for
              current <- history.get
              _ <- ZIO.fromEither(checkBounds(current, prompt))
              size <- queue.size
              _ <-
                if size >= bounds.queueCapacity then
                  ZIO.fail(
                    RaiderError.LocalBudgetExceeded(
                      s"conversation ${sessionId.value} turn queue is full " +
                        s"(capacity=${bounds.queueCapacity}); the turn is refused, not parked forever"
                    )
                  )
                else ZIO.unit
              id = TurnId(s"t-${turnSeq.incrementAndGet()}")
              p <- Promise.make[Nothing, TurnOutcome]
              turn = QueuedTurn(id, prompt, p)
              _ <- queue.offer(DispatcherMsg.Enqueue(turn))
              _ <- ZIO.succeed(knownTurns.put(id, ()))
            yield TurnReceipt(id, prompt, p)
        }
      }

  /** Requests cancellation of a known turn (SESSION-03). `true` = the request
    * was delivered to a turn of this conversation (queued or in-flight); the
    * receipt still decides the final outcome — a turn that completed before
    * the request landed keeps its reply (completion wins). `false` = the id is
    * unknown to this conversation. Idempotent.
    */
  def cancelTurn(id: TurnId): UIO[Boolean] =
    if !knownTurns.containsKey(id) then ZIO.succeed(false)
    else
      dispatchLock.withPermit {
        if cancelledFlags.containsKey(id) then ZIO.succeed(true) // idempotent
        else
          ZIO.succeed(cancelledFlags.put(id, ())).flatMap { _ =>
            inFlight.get.flatMap { flight =>
              flight.get(id) match
                case Some(f) => f.interrupt.unit.as(true)
                case None =>
                  ZIO.succeed(true) // still queued: honored at dispatch time
            }
          }
      }

  /** Seals the conversation: idempotent, safe from any fiber. Every accepted
    * turn settles — the in-flight one by the completion-wins race, every
    * queued one as `Cancelled`. Waits for the interrupted turn fibers (bounded
    * close), then retires the dispatcher via a shutdown sentinel (never an
    * interrupt). Later submits refuse typed (RA-INP).
    */
  def close: UIO[Unit] =
    lifecycleLock.withPermit {
      sealedRef.get.flatMap {
        case true => ZIO.unit
        case false =>
          for
            _ <- sealedRef.set(true)
            drained <- queue.takeAll
            _ <- ZIO.foreachDiscard(drained) {
              case DispatcherMsg.Enqueue(t) =>
                t.settled.succeed(TurnOutcome.Cancelled).unit
              case DispatcherMsg.Shutdown => ZIO.unit
            }
            _ <- dispatchLock.withPermit {
              inFlight.get.flatMap { flight =>
                ZIO.foreachDiscard(flight.values)(_.interrupt.unit)
              }
            }
            // The queue was just drained, so the sentinel offer cannot fail;
            // the dispatcher (the only taker) exits after its current turn.
            _ <- queue.offer(DispatcherMsg.Shutdown).unit
            _ <- closedP.succeed(())
          yield ()
      }
    }

  // ------------------------------------------------------------------
  // Introspection
  // ------------------------------------------------------------------

  /** The conversation so far: user and assistant messages in turn order. Reads
    * are safe from any fiber; only the dispatcher appends.
    */
  def snapshot: UIO[List[RequestMessage]] = history.get

  def isSealed: UIO[Boolean] = sealedRef.get

  /** Resolves once the conversation is sealed (close bookkeeping done). */
  def whenSealed: UIO[Unit] = closedP.await

  def queuedTurns: UIO[Int] = queue.size

  // ------------------------------------------------------------------
  // Dispatcher internals
  // ------------------------------------------------------------------

  /** Strict FIFO dispatcher: one turn at a time (SESSION-01). The
    * dispatch-critical section — cancelled-flag check plus fiber registration
    * (runtime-contracts §5) — runs under `dispatchLock`; the turn itself runs
    * OUTSIDE the lock so close/cancel never wait for a running provider turn.
    * Registration is fork-then-update INSIDE the permit: close/cancelTurn scan
    * `inFlight` under the same lock, so they can never miss a registered turn.
    * The loop ends only on the shutdown sentinel: close never interrupts this
    * fiber, so it can never die holding an unsettled receipt.
    */
  private def dispatchLoop: UIO[Unit] =
    queue.take.flatMap {
      case DispatcherMsg.Shutdown => ZIO.unit
      case DispatcherMsg.Enqueue(turn) =>
        dispatchLock.withPermit {
          if cancelledFlags.containsKey(turn.id) then
            turn.settled.succeed(TurnOutcome.Cancelled).as(None)
          else
            // Registration atomicity comes from dispatchLock alone: close and
            // cancelTurn scan inFlight under the SAME lock, so a turn fiber is
            // always visible to them once the permit is released. The fork MUST
            // NOT sit inside ZIO.uninterruptible: in ZIO 2.1.26 a child forked
            // from an uninterruptible region inherits the mask and becomes
            // UNINTERRUPTIBLE while parked, so Fiber.interrupt from close never
            // completes (pinned by InterruptReproSpec).
            turnBody(turn).forkDaemon.flatMap { f =>
              inFlight.update(_.updated(turn.id, f)).as(Some(f))
            }
        }.flatMap {
          case None => dispatchLoop
          case Some(f) =>
            f.await.unit *> inFlight.update(_ - turn.id) *> dispatchLoop
        }
    }

  /** One turn body: the ONE runner call, history append, receipt settle. The
    * receipt is completed exactly once; the interrupt finalizer completes it
    * `Cancelled` only when completion did not win (Promise.succeed is a no-op
    * on an already-settled promise).
    */
  private def turnBody(turn: QueuedTurn): UIO[Unit] =
    runTurn(turn)
      .fold(
        err => TurnOutcome.Failed(err),
        reply => TurnOutcome.Done(reply)
      )
      .flatMap(outcome => turn.settled.succeed(outcome).unit)
      .onInterrupt(turn.settled.succeed(TurnOutcome.Cancelled).unit)

  private def runTurn(turn: QueuedTurn): ZIO[Any, RaiderError, String] =
    for
      snap <- history.get
      _ <- ZIO.fromEither(checkBounds(snap, turn.prompt))
      promptMsg = RequestMessage(UserRole, turn.prompt)
      reply <- runner.run(snap :+ promptMsg)
      _ <- history.set(
        snap :+ promptMsg :+ RequestMessage(AssistantRole, reply)
      )
    yield reply

  /** The bounds check shared by submit (fast path) and dispatch
    * (authoritative): a bound violation is a typed refusal — history is never
    * compacted and no turn is silently dropped.
    */
  private def checkBounds(
      current: List[RequestMessage],
      prompt: String
  ): Either[RaiderError, Unit] =
    val pairs = current.size / 2
    if pairs + 1 > bounds.maxTurns then
      Left(
        RaiderError.LocalBudgetExceeded(
          s"conversation ${sessionId.value} would exceed maxTurns=${bounds.maxTurns} " +
            s"(has $pairs completed turns); the turn is refused, not compacted — " +
            "raise the bound explicitly"
        )
      )
    else
      val projected =
        ConversationBounds.requestChars(current) + prompt.length + UserRole.length + 1
      if projected > bounds.maxHistoryChars then
        Left(
          RaiderError.LocalBudgetExceeded(
            s"conversation ${sessionId.value} history would exceed " +
              s"maxHistoryChars=${bounds.maxHistoryChars} (projected $projected chars); " +
              "the turn is refused, not compacted — raise the bound explicitly"
          )
        )
      else Right(())

end Conversation

object Conversation:

  val UserRole: String = "user"
  val AssistantRole: String = "assistant"

  private[conversation] final case class QueuedTurn(
      id: TurnId,
      prompt: String,
      settled: Promise[Nothing, TurnOutcome]
  )

  /** Dispatcher mailbox messages: real turns plus the shutdown sentinel that
    * retires the dispatcher without an interrupt.
    */
  private[conversation] sealed trait DispatcherMsg

  private[conversation] object DispatcherMsg:
    case class Enqueue(turn: QueuedTurn) extends DispatcherMsg
    case object Shutdown extends DispatcherMsg

  private val sessionSeq = AtomicLong(0)
  private val rootSeq = AtomicLong(0)

  /** Fresh conversation-session id (single path segment, archive-safe). */
  def newSessionId(): SessionId =
    SessionId(s"conv-${java.util.UUID.randomUUID()}")

  /** Fresh conversation root id (the ownership anchor for child sessions). */
  def newRoot(): RootId =
    RootId(s"conversation_${rootSeq.incrementAndGet()}")

  /** Builds a conversation with the given bounds and runner, seeded with an
    * EXPLICIT prior history (archive reopen). The seed is validated —
    * alternation and bounds are typed refusals (RA-INP / RA-LBUDGET); seeding
    * executes nothing (no implicit restart — SESSION-02). The dispatcher fiber
    * is a daemon: it lives exactly until the shutdown sentinel.
    */
  def make(
      root: RootId,
      bounds: ConversationBounds,
      runner: TurnRunner,
      label: Option[String] = None,
      seed: List[RequestMessage] = Nil,
      sessionId: SessionId = newSessionId()
  ): IO[RaiderError, Conversation] =
    ZIO.fromEither(validateSeed(seed, bounds)).flatMap { _ =>
      for
        queue <- Queue.bounded[DispatcherMsg](bounds.queueCapacity)
        history <- Ref.make(seed)
        sealedRef <- Ref.make(false)
        inFlight <- Ref.make(Map.empty[TurnId, Fiber[Any, Unit]])
        dispatchLock <- Semaphore.make(1L)
        lifecycleLock <- Semaphore.make(1L)
        closedP <- Promise.make[Nothing, Unit]
        conv = new Conversation(
          sessionId,
          root,
          label,
          bounds,
          runner,
          queue,
          history,
          sealedRef,
          inFlight,
          dispatchLock,
          lifecycleLock,
          closedP
        )
        fiber <- conv.dispatchLoop.forkDaemon
      yield conv
    }

  /** The strict (user, assistant) pairing a seed/archive must satisfy. */
  def validateSeed(
      seed: List[RequestMessage],
      bounds: ConversationBounds
  ): Either[RaiderError, Unit] =
    def loop(rest: List[RequestMessage]): Either[RaiderError, Unit] =
      rest match
        case Nil => Right(())
        case List(_) =>
          Left(
            RaiderError.InputValidation(
              "seed history must alternate user/assistant messages (ends unmatched)"
            )
          )
        case a :: b :: tail =>
          if a.role == UserRole && b.role == AssistantRole then loop(tail)
          else
            Left(
              RaiderError.InputValidation(
                s"seed history must alternate $UserRole/$AssistantRole messages"
              )
            )
    if seed.size / 2 > bounds.maxTurns then
      Left(
        RaiderError.LocalBudgetExceeded(
          s"seed history has ${seed.size / 2} turns, over the bound maxTurns=${bounds.maxTurns}"
        )
      )
    else if ConversationBounds.requestChars(seed) > bounds.maxHistoryChars then
      Left(
        RaiderError.LocalBudgetExceeded(
          s"seed history exceeds maxHistoryChars=${bounds.maxHistoryChars} " +
            s"(${ConversationBounds.requestChars(seed)} chars)"
        )
      )
    else loop(seed)

end Conversation
