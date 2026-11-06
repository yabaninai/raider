package raider.repl.facade

import raider.core.*
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.JobStatus
import raider.testkit.ScriptedModelBackend
import zio.stream.ZStream
import zio.test.*
import zio.{Duration, Exit, Promise, Ref, Scope, ZIO}

import scala.jdk.CollectionConverters.*

import java.util.concurrent.CopyOnWriteArrayList

/** RAI-263 behavioral fixtures for the `Chat` close lifecycle (ADR-040): the
  * working-product §4 golden scenario gains real `close()` semantics —
  *
  *   - CHAT-01: the golden flow (two asks, `close()` twice) with history
  *     preserved and typed RA-INP refusal after sealing;
  *   - CHAT-02/03: the terminal-pick order — completion wins over a racing
  *     interrupt (reply stays in the history), otherwise the interrupt wins
  *     with typed `RaiderError.Cancelled` and no fabricated reply, while
  *     earlier history is never erased (product-repl §8) and delivered deltas
  *     are never lost;
  *   - CHAT-04: the lifecycle is chat-scoped — session jobs, their steering
  *     mailboxes and receipts are untouched by a chat close;
  *   - CHAT-05: `close()` is idempotent and safe from concurrent closers.
  *
  * Determinism discipline (the SteeringSpec/RAI-074 rules): the turn boundary
  * is REAL but CONTROLLED — the scripted backend parks on a promise inside the
  * model call, so closes happen at a known phase without sleeps; the blocking
  * facade runs exactly as the product does, gated by the test, never waited
  * blindly (10 s live-clock safety nets fail the test instead of hanging).
  */
object ChatCloseSpec extends ZIOSpecDefault:

  private val att = AttemptId("chat-close")
  private val Head = "[chat] answer "
  private val Tail = " close-ok"

  /** Deterministic gate backend: prompts listed in `parks` park inside the
    * model call — first the `parked` promise resolves (turn is in flight), then
    * an early delta is emitted, then the stream PARKS on `gate` (the test owns
    * the boundary). Any other prompt answers "plain-answer" immediately. Every
    * ModelRequest is recorded. `fixtureMode` marks scripted output, never a
    * live model answer.
    */
  private final class GateBackend(
      parks: Map[String, (Promise[Nothing, Unit], Promise[Nothing, Unit])],
      requests: Ref[List[ModelRequest]]
  ) extends ModelBackend:

    val fixtureMode: Boolean = true

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      val prompt = input.messages.last.content
      ZStream.unwrap {
        requests.update(_ :+ input).as {
          parks.get(prompt) match
            case Some((parked, gate)) =>
              ZStream.fromZIO(parked.succeed(()).unit).drain ++
                ZStream(
                  ModelEvent.Started(att),
                  ModelEvent.TextDelta(att, "[early] ")
                ) ++
                ZStream.fromZIO(gate.await).drain ++
                ZStream(
                  ModelEvent.TextDelta(att, "late-tail"),
                  ModelEvent.Finished(att, "stop")
                )
            case None =>
              ZStream(
                ModelEvent.Started(att),
                ModelEvent.TextDelta(att, "plain-answer"),
                ModelEvent.Finished(att, "stop")
              )
        }
      }

  /** Test sink: records every delivered delta; fulfills `early` exactly when
    * the parked call's first delta reached the renderer (the deterministic
    * "delivered before the close" barrier).
    */
  private final class RecordingSink(early: Promise[Nothing, Unit])
      extends TextRenderSink:
    val deltas = CopyOnWriteArrayList[String]()

    def textDelta(text: String): zio.UIO[Unit] =
      ZIO.succeed(deltas.add(text)).flatMap { _ =>
        if text == "[early] " then early.succeed(()).unit else zio.ZIO.unit
      }

  private final class Wired(
      val backend: GateBackend,
      val session: ReplSession,
      val requests: zio.UIO[List[ModelRequest]]
  )

  private def gatePair(): ZIO[
    Any,
    Nothing,
    (Promise[Nothing, Unit], Promise[Nothing, Unit])
  ] =
    for
      parked <- Promise.make[Nothing, Unit]
      gate <- Promise.make[Nothing, Unit]
    yield (parked, gate)

  private def wire(
      sink: Option[TextRenderSink] = None,
      echoStream: Boolean = false
  )(
      parks: (
          String,
          (Promise[Nothing, Unit], Promise[Nothing, Unit])
      )*
  ): ZIO[Any, RaiderError, Wired] =
    for
      reqs <- Ref.make(List.empty[ModelRequest])
      backend = GateBackend(parks.toMap, reqs)
      // maxConcurrentLlm = 2: a chat turn and a session job hold their Model
      // slots SIMULTANEOUSLY (the product default of 1 would serialize them —
      // the fixture needs both parked at once to close mid-flight).
      session <- ReplSession.make(
        backend,
        BudgetLimits.make(maxConcurrentLlm = 2),
        echoStream = echoStream,
        renderSink = sink
      )
    yield Wired(backend, session, reqs.get)

  private def goldenSession()
      : ZIO[Any, RaiderError, (ScriptedModelBackend, ReplSession)] =
    for
      backend <- ScriptedModelBackend(
        ModelEvent.Started(att),
        ModelEvent.TextDelta(att, Head),
        ModelEvent.TextDelta(att, Tail),
        ModelEvent.Finished(att, "stop")
      )
      session <- ReplSession.make(backend, BudgetLimits.make())
    yield (backend, session)

  /** Typed-cancelled probe over the blocking facade (the ask was interrupted by
    * close, not failed by the loop).
    */
  private def cancelledTyped(
      exit: Option[Exit[Throwable, String]]
  ): Boolean =
    exit match
      case Some(Exit.Failure(cause)) =>
        cause.failures.exists {
          case _: RaiderError.Cancelled => true
          case _                        => false
        }
      case _ => false

  private def postCloseRefusal(
      chat: Chat
  ): Option[RaiderError.InputValidation] =
    try
      chat.ask("after close")
      None
    catch
      case e: RaiderError.InputValidation => Some(e)
      case _: Throwable                   => None

  def spec = suite("ChatCloseSpec (RAI-263: chat close lifecycle)")(
    test(
      "CHAT-01 golden: two asks, close() twice — history preserved, post-close ask refuses typed (RA-INP)"
    ) {
      for
        (backend, session) <- goldenSession()
        chat = openSession(AgentRef("worker"))(using session)
        first = chat.ask("Study the parser")
        second = chat.ask("Now fix the edge case you found")
        _ = chat.close()
        _ = chat.close() // idempotent
        reqs <- backend.requests
        refusal = postCloseRefusal(chat)
      yield assertTrue(
        first == Head + Tail,
        second == Head + Tail,
        chat.messages.size == 4,
        chat.messages(0) == RequestMessage("user", "Study the parser"),
        chat.messages(1) == RequestMessage("assistant", Head + Tail),
        chat.messages(2) == RequestMessage(
          "user",
          "Now fix the edge case you found"
        ),
        chat.messages(3) == RequestMessage("assistant", Head + Tail),
        reqs.size == 2,
        reqs(0).messages == List(
          RequestMessage("system", FacadeOps.ReplSystemPrompt),
          RequestMessage("user", "Study the parser")
        ),
        reqs(1).messages == List(
          RequestMessage("system", FacadeOps.ReplSystemPrompt),
          RequestMessage("user", "Study the parser"),
          RequestMessage("assistant", Head + Tail),
          RequestMessage("user", "Now fix the edge case you found")
        ),
        refusal.isDefined
      )
    },
    test(
      "CHAT-02 close during an in-flight parked turn: typed Cancelled, no fabricated reply, earlier history intact, delivered deltas preserved, close bounded"
    ) {
      for
        sinkP <- Promise.make[Nothing, Unit]
        (parked, gate) <- gatePair()
        w <- wire(
          Some(RecordingSink(sinkP)),
          echoStream = true
        )(("chat-slow", (parked, gate)))
        chat = openSession(AgentRef("worker"))(using w.session)
        _ = chat.ask("warmup") // earlier turn: history survives cancels (§8)
        askFiber <- ZIO.attemptBlocking(chat.ask("chat-slow")).forkDaemon
        _ <- parked.await.timeout(
          Duration.fromSeconds(10)
        ) // deterministically INSIDE the parked model call
        early <- sinkP.await.timeout(Duration.fromSeconds(10)) // DELIVERED
        closeFiber <- ZIO.attemptBlocking(chat.close()).forkDaemon
        closed <- closeFiber.join.timeout(Duration.fromSeconds(10))
        exit <- askFiber.await.timeout(Duration.fromSeconds(10))
        deltas = recSink(w).deltas.asScala.toList
        refusal = postCloseRefusal(chat)
        reqs <- w.requests
      yield assertTrue(
        early.isDefined,
        closed.isDefined,
        exit.isDefined,
        cancelledTyped(exit),
        deltas.contains("[early] "), // buffered output was not lost
        chat.messages.size == 2, // warmup kept; no fabricated turn (§8)
        chat.messages(0) == RequestMessage("user", "warmup"),
        chat.messages(1) == RequestMessage("assistant", "plain-answer"),
        refusal.isDefined,
        reqs.size == 2 // warmup + the interrupted call
      )
    } @@ TestAspect.withLiveClock,
    test(
      "CHAT-03 completion wins: a turn that finishes before close keeps its reply; close afterwards is sealed and idempotent"
    ) {
      for
        (parked, gate) <- gatePair()
        w <- wire()(("chat-slow", (parked, gate)))
        chat = openSession(AgentRef("worker"))(using w.session)
        askFiber <- ZIO.attemptBlocking(chat.ask("chat-slow")).forkDaemon
        _ <- parked.await.timeout(Duration.fromSeconds(10))
        _ <- gate.succeed(()).unit // release: the turn completes
        reply <- askFiber.join
        _ = chat.close()
        refusal = postCloseRefusal(chat)
      yield assertTrue(
        reply == "[early] late-tail",
        chat.messages.size == 2,
        chat.messages(1) == RequestMessage("assistant", "[early] late-tail"),
        refusal.isDefined
      )
    } @@ TestAspect.withLiveClock,
    test(
      "CHAT-03b race: close vs completing turn settles exactly once — reply kept XOR typed Cancelled, never a half state, close always bounded"
    ) {
      ZIO
        .foreach((1 to 12).toList) { _ =>
          for
            sinkP <- Promise.make[Nothing, Unit]
            (parked, gate) <- gatePair()
            w <- wire(Some(RecordingSink(sinkP)))(("chat-slow", (parked, gate)))
            chat = openSession(AgentRef("worker"))(using w.session)
            askFiber <- ZIO.attemptBlocking(chat.ask("chat-slow")).forkDaemon
            _ <- parked.await.timeout(Duration.fromSeconds(10))
            closeFiber <- ZIO.attemptBlocking(chat.close()).forkDaemon
            _ <- gate.succeed(()).unit // release while the close is racing
            closed <- closeFiber.join.timeout(Duration.fromSeconds(10))
            exit <- askFiber.await.timeout(Duration.fromSeconds(10))
            (completed, cancelled) = exit match
              case Some(Exit.Success(text)) => (Some(text), false)
              case Some(Exit.Failure(cause)) =>
                (None, cancelledTyped(Some(Exit.Failure(cause))))
              case None => (None, false) // timed out: fails assertions below
          yield assertTrue(
            closed.isDefined,
            exit.isDefined,
            completed.isDefined || cancelled, // settled one way, never hung
            completed match
              case Some(text) =>
                text == "[early] late-tail" &&
                chat.messages.size == 2 &&
                chat.messages(1) == RequestMessage("assistant", text)
              case None => chat.messages.isEmpty // no fabricated reply
          )
        }
        .map(_.reduce(_ && _))
    } @@ TestAspect.withLiveClock,
    test(
      "CHAT-04 chat-scoped: closing the chat does not disturb session jobs — steering queued→applied, job completes, mailbox seals"
    ) {
      for
        (jparked, jgate) <- gatePair()
        (cparked, cgate) <- gatePair()
        w <- wire()(
          ("bg turn", (jparked, jgate)),
          ("chat-slow", (cparked, cgate))
        )
        handle = AgentRef("scout").start("bg turn")(using w.session)
        _ <- jparked.await.timeout(
          Duration.fromSeconds(10)
        ) // job mid-turn (Draining window open)
        receipt = handle.send("check y")(using w.session)
        chat = openSession(AgentRef("worker"))(using w.session)
        askFiber <- ZIO.attemptBlocking(chat.ask("chat-slow")).forkDaemon
        _ <- cparked.await.timeout(Duration.fromSeconds(10))
        closeFiber <- ZIO.attemptBlocking(chat.close()).forkDaemon
        closed <- closeFiber.join.timeout(Duration.fromSeconds(10))
        exit <- askFiber.await.timeout(Duration.fromSeconds(10))
        queued = Some(receipt).collect { case q: SteerReceipt.Queued => q }
        _ <- jgate.succeed(()).unit // release the job's turn; steering applies
        settled <- queued.fold(ZIO.none)(q => q.settled.await.map(Some(_)))
        channel = Option(FacadeOps.steer.get(handle.id))
        _ <- channel.fold(ZIO.unit)(_.sealedP.await) // terminal watcher done
        snap <- w.session.jobs.snapshotAny(handle)
        result = handle.await()(using w.session)
        reqs <- w.requests
      yield assertTrue(
        closed.isDefined,
        cancelledTyped(exit),
        queued.isDefined,
        // the chat close did NOT touch the job: its receipt went Applied at
        // the next boundary and the job completed normally
        settled.contains(
          Some(
            SteerReceipt.Applied(handle.id, queued.get.msgId, "check y")
          )
        ),
        snap.status == JobStatus.Succeeded,
        result == "plain-answer",
        // request sequence: job turn 1, chat turn (unaffected), job turn 2
        // with the steering section applied at the boundary
        reqs.size == 3,
        reqs(1).messages
          .count(_.content.contains("owner steering:")) == 0,
        reqs(2).messages
          .count(_.content.contains("owner steering:")) == 1,
        // the steering section carries the SAME msgId as the queued receipt
        // (ids come from the facade-wide counter, so read them from the
        // receipt instead of asserting a literal)
        reqs(2).messages.last.content.contains(
          "- [" + queued.get.msgId.value + "] check y"
        )
      )
    } @@ TestAspect.withLiveClock,
    test(
      "CHAT-04b close + job cancel concurrently: both settle typed and bounded; the queued receipt resolves expired; nothing hangs"
    ) {
      for
        (jparked, jgate) <- gatePair()
        (cparked, cgate) <- gatePair()
        w <- wire()(
          ("bg turn", (jparked, jgate)),
          ("chat-slow", (cparked, cgate))
        )
        handle = AgentRef("scout").start("bg turn")(using w.session)
        _ <- jparked.await.timeout(Duration.fromSeconds(10))
        receipt = handle.send("check y")(using w.session) // Queued
        chat = openSession(AgentRef("worker"))(using w.session)
        askFiber <- ZIO.attemptBlocking(chat.ask("chat-slow")).forkDaemon
        _ <- cparked.await.timeout(Duration.fromSeconds(10))
        closeFiber <- ZIO.attemptBlocking(chat.close()).forkDaemon
        cancelFiber <-
          ZIO.attemptBlocking(handle.cancel()(using w.session)).forkDaemon
        closed <- closeFiber.join.timeout(Duration.fromSeconds(10))
        cancelled <- cancelFiber.join.timeout(Duration.fromSeconds(10))
        _ <- w.session.jobs.awaitSettled(handle)
        channel = Option(FacadeOps.steer.get(handle.id))
        _ <- channel.fold(ZIO.unit)(_.sealedP.await)
        settled <- Some(receipt)
          .collect { case q: SteerReceipt.Queued =>
            q
          }
          .fold(ZIO.none)(q => q.settled.await.map(Some(_)))
        snap <- w.session.jobs.snapshotAny(handle)
        exit <- askFiber.await.timeout(Duration.fromSeconds(10))
        refusal = postCloseRefusal(chat)
      yield assertTrue(
        closed.isDefined,
        cancelled.contains(true),
        snap.status == JobStatus.Cancelled,
        settled.contains(None), // acked-but-never-applied → expired, visible
        exit.isDefined,
        cancelledTyped(exit),
        refusal.isDefined,
        chat.messages.isEmpty
      )
    } @@ TestAspect.withLiveClock,
    test(
      "CHAT-05 two concurrent closers settle once; the chat seals exactly once"
    ) {
      for
        (parked, gate) <- gatePair()
        w <- wire()(("chat-slow", (parked, gate)))
        chat = openSession(AgentRef("worker"))(using w.session)
        askFiber <- ZIO.attemptBlocking(chat.ask("chat-slow")).forkDaemon
        _ <- parked.await.timeout(Duration.fromSeconds(10))
        c1 <- ZIO.attemptBlocking(chat.close()).forkDaemon
        c2 <- ZIO.attemptBlocking(chat.close()).forkDaemon
        r1 <- c1.join.timeout(Duration.fromSeconds(10))
        r2 <- c2.join.timeout(Duration.fromSeconds(10))
        exit <- askFiber.await.timeout(Duration.fromSeconds(10))
        refusal = postCloseRefusal(chat)
      yield assertTrue(
        r1.isDefined,
        r2.isDefined,
        exit.isDefined,
        cancelledTyped(exit),
        refusal.isDefined,
        chat.messages.isEmpty
      )
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential

  /** The RecordingSink installed on the wired session (the deltas assertion in
    * CHAT-02 needs it without threading it through the whole comprehension).
    */
  private def recSink(w: Wired): RecordingSink =
    w.session.renderSink
      .getOrElse(
        throw new AssertionError("test sink missing from session")
      )
      .asInstanceOf[RecordingSink]
