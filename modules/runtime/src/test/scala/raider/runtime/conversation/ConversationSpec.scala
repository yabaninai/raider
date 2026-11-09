package raider.runtime.conversation

import raider.core.{RaiderError, RequestMessage, SessionId, Task}
import zio.test.*
import zio.{Duration, Promise, Ref, ZIO}

/** RAI-036 runtime acceptance (m1.md SESSION-01/02/03) over the conversation
  * core: context continuity and strict serialization (SESSION-01), honest typed
  * bounds and refusals with no compaction (SESSION-02), receipt conservation,
  * cancellation and root ownership (SESSION-03).
  *
  * Determinism rules: park-on-promise runners pin the dispatcher state before
  * every assertion; no sleeps; every await resolves by construction.
  */
object ConversationSpec extends ZIOSpecDefault:

  private def makeConv(
      bounds: ConversationBounds = ConversationBounds.default,
      runner: TurnRunner =
        TurnRunner(msgs => ZIO.succeed(s"reply-${msgs.size}")),
      seed: List[RequestMessage] = Nil,
      label: Option[String] = None,
      sessionId: SessionId = Conversation.newSessionId()
  ): ZIO[Any, RaiderError, Conversation] =
    Conversation.make(
      Conversation.newRoot(),
      bounds,
      runner,
      label = label,
      seed = seed,
      sessionId = sessionId
    )

  private def user(text: String) = RequestMessage("user", text)
  private def assistant(text: String) = RequestMessage("assistant", text)

  private def isInputValidation(e: RaiderError): Boolean = e match
    case _: RaiderError.InputValidation => true
    case _                              => false

  private def isLocalBudget(e: RaiderError): Boolean = e match
    case _: RaiderError.LocalBudgetExceeded => true
    case _                                  => false

  private def isConfiguration(e: RaiderError): Boolean = e match
    case _: RaiderError.Configuration => true
    case _                            => false

  private def isDone(out: TurnOutcome): Boolean = out match
    case _: TurnOutcome.Done => true
    case _                   => false

  /** A runner that parks every call until `gate`, signalling `parked` first —
    * used to pin exact dispatcher state before asserting.
    */
  private def gatedRunner(
      gate: Promise[Nothing, Unit],
      parked: Promise[Nothing, Unit],
      entered: Ref[Int]
  ): TurnRunner =
    TurnRunner(msgs =>
      for
        _ <- entered.update(_ + 1)
        _ <- parked.succeed(())
        _ <- gate.await
      yield s"reply-${msgs.size}"
    )

  override def spec: Spec[Any, Any] =
    suite("Conversation")(
      // ------------------------------------------------------------------
      // SESSION-01: context continuity, strict serialization, Program contract
      // ------------------------------------------------------------------
      test("two turns build context through the ONE Program contract") {
        for
          seen <- Ref.make(List.empty[Int])
          runner = TurnRunner(msgs =>
            seen.update(_ :+ msgs.size).as(s"reply-${msgs.size}")
          )
          conv <- makeConv(runner = runner)
          r1 <- Task.zio(conv.apply("first")).either
          r2 <- Task.zio(conv.apply("second")).either
          snap <- conv.snapshot
          sizes <- seen.get
        yield assertTrue(
          r1 == Right("reply-1"),
          r2 == Right("reply-3"),
          sizes == List(1, 3),
          snap == List(
            user("first"),
            assistant("reply-1"),
            user("second"),
            assistant("reply-3")
          )
        )
      },
      test("construction and Task building dispatch nothing (API-03)") {
        for
          entered <- Ref.make(0)
          runner = TurnRunner(msgs => entered.update(_ + 1).as("never"))
          conv <- makeConv(runner = runner)
          _ = conv.apply("lazy")
          n <- entered.get
        yield assertTrue(n == 0)
      },
      test(
        "turns are strictly serialized in acceptance order (one in flight)"
      ) {
        for
          inFlight <- Ref.make(0)
          maxInFlight <- Ref.make(0)
          dispatchLog <- Ref.make(List.empty[String])
          runner = TurnRunner(msgs =>
            for
              _ <- inFlight.update(_ + 1)
              cur <- inFlight.get
              _ <- maxInFlight.update(cur max _)
              _ <- dispatchLog.update(log => msgs.last.content :: log)
              _ <- ZIO.yieldNow
              _ <- inFlight.update(_ - 1)
            yield s"reply-${msgs.last.content}"
          )
          conv <- makeConv(runner = runner)
          receipts <- ZIO.foreach(List("a", "b", "c", "d"))(conv.submit)
          outcomes <- ZIO.foreach(receipts)(r => r.settled.await)
          max <- maxInFlight.get
          order <- dispatchLog.get.map(_.reverse)
        yield assertTrue(
          outcomes.forall(isDone),
          max == 1,
          order == List("a", "b", "c", "d")
        )
      },
      test("receipts carry per-conversation turn ids in acceptance order") {
        for
          conv <- makeConv()
          r1 <- conv.submit("a")
          r2 <- conv.submit("b")
          o1 <- r1.settled.await
          o2 <- r2.settled.await
        yield assertTrue(
          r1.id == TurnId("t-1"),
          r2.id == TurnId("t-2"),
          r1.prompt == "a",
          o1 == TurnOutcome.Done("reply-1"),
          o2 == TurnOutcome.Done("reply-3")
        )
      },
      test("seed history is accepted without executing anything") {
        for
          entered <- Ref.make(0)
          runner = TurnRunner(msgs => entered.update(_ + 1).as("never"))
          seed = List(user("s1"), assistant("a1"))
          conv <- makeConv(runner = runner, seed = seed)
          snap0 <- conv.snapshot
          n0 <- entered.get
          o <- conv.submit("real").flatMap(_.settled.await)
          snap1 <- conv.snapshot
          n1 <- entered.get
        yield assertTrue(
          n0 == 0,
          snap0 == seed,
          // This runner always replies "never" while counting entries: the
          // assertions pin THAT reply, proving the seed was never replayed
          // through the runner and the real turn ran exactly once.
          o == TurnOutcome.Done("never"),
          snap1 == seed :+ user("real") :+ assistant("never"),
          n1 == 1
        )
      },
      // ------------------------------------------------------------------
      // SESSION-02: honest typed bounds and refusals, never compaction
      // ------------------------------------------------------------------
      test("seed must strictly alternate user/assistant and fit bounds") {
        val odd = List(user("a"))
        val wrongOrder = List(assistant("a"), user("b"))
        val valid = List(user("a"), assistant("b"))
        val oversized =
          List
            .fill(3)((user("x"), assistant("y")))
            .flatMap((a, b) => List(a, b))
        val tight = ConversationBounds.make(maxTurns = 2).toOption.get
        def classify(e: Either[RaiderError, Unit]): String = e match
          case Left(ee) if isInputValidation(ee) => "input"
          case Left(ee) if isLocalBudget(ee)     => "budget"
          case Left(_)                           => "other"
          case Right(_)                          => "ok"
        assertTrue(
          classify(
            Conversation.validateSeed(odd, ConversationBounds.default)
          ) == "input",
          classify(
            Conversation.validateSeed(wrongOrder, ConversationBounds.default)
          ) == "input",
          classify(
            Conversation.validateSeed(valid, ConversationBounds.default)
          ) == "ok",
          classify(Conversation.validateSeed(oversized, tight)) == "budget"
        )
      },
      test("make with an invalid seed is a typed refusal") {
        for err <- makeConv(seed = List(user("only"))).flip
        yield assertTrue(isInputValidation(err))
      },
      test("oversized history is refused typed, never compacted") {
        for
          bounds <- ZIO.fromEither(
            ConversationBounds.make(maxHistoryChars = 10)
          )
          conv <- makeConv(bounds = bounds)
          err <- conv.submit("123456").flip
          snap <- conv.snapshot
        yield assertTrue(isLocalBudget(err), snap.isEmpty)
      },
      test("maxTurns bound refuses the turn after the ceiling") {
        for
          bounds <- ZIO.fromEither(ConversationBounds.make(maxTurns = 1))
          conv <- makeConv(bounds = bounds)
          ok <- conv.submit("first").flatMap(_.settled.await)
          err <- conv.submit("second").flip
        yield assertTrue(isDone(ok), isLocalBudget(err))
      },
      test("full turn queue is a typed budget refusal, not silent parking") {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = gatedRunner(gate, parked, entered)
          bounds <- ZIO.fromEither(ConversationBounds.make(queueCapacity = 1))
          conv <- makeConv(bounds = bounds, runner = runner)
          _ <- conv.submit("a")
          _ <- parked.await // dispatcher took "a" and is parked in the runner
          _ <- conv.submit("b") // queued (capacity 1)
          err <- conv.submit("c").flip // queue full
          _ <-
            conv.close // hygiene: retire the parked dispatcher deterministically
        yield assertTrue(isLocalBudget(err))
      } @@ TestAspect.timeout(Duration.fromSeconds(10)),
      test("submit after close is refused typed (RA-INP)") {
        for
          conv <- makeConv()
          _ <- conv.close
          err <- conv.submit("after").flip
        yield assertTrue(isInputValidation(err))
      },
      test("blank prompt is refused typed") {
        for
          conv <- makeConv()
          err <- conv.submit("   ").flip
        yield assertTrue(isInputValidation(err))
      },
      test(
        "bounds are validated at construction (RA-CFG) with declared defaults"
      ) {
        assertTrue(
          ConversationBounds
            .make(maxTurns = 0)
            .fold(isConfiguration, _ => false),
          ConversationBounds
            .make(queueCapacity = 0)
            .fold(isConfiguration, _ => false),
          ConversationBounds
            .make(maxHistoryChars = 0)
            .fold(isConfiguration, _ => false),
          ConversationBounds.make().isRight,
          ConversationBounds.default == ConversationBounds(256, 131072, 64)
        )
      },
      test("requestChars accounting counts role, content and separators") {
        val msgs = List(user("abc"), assistant("de"))
        assertTrue(
          ConversationBounds.requestChars(msgs) == (4 + 3 + 1) + (9 + 2 + 1)
        )
      },
      // ------------------------------------------------------------------
      // SESSION-03: receipt conservation, cancellation, root ownership
      // ------------------------------------------------------------------
      test("close settles every accepted receipt; queued turns cancel") {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = gatedRunner(gate, parked, entered)
          conv <- makeConv(runner = runner)
          r1 <- conv.submit("first")
          _ <- parked.await // turn 1 parked in the runner
          r2 <- conv.submit("second") // queued behind the parked turn
          _ <- conv.close
          o1 <- r1.settled.await
          o2 <- r2.settled.await
          snap <- conv.snapshot
          sealedNow <- conv.isSealed
        yield assertTrue(
          o1 == TurnOutcome.Cancelled,
          o2 == TurnOutcome.Cancelled,
          snap.isEmpty,
          sealedNow
        )
      } @@ TestAspect.timeout(Duration.fromSeconds(10)),
      test("a settled turn keeps its reply through close (completion wins)") {
        for
          conv <- makeConv()
          r <- conv.submit("keep me")
          o <- r.settled.await
          _ <- conv.close
          after <- r.settled.await
          snap <- conv.snapshot
        yield assertTrue(
          o == TurnOutcome.Done("reply-1"),
          after == o,
          snap == List(user("keep me"), assistant("reply-1"))
        )
      },
      test("close racing a running turn settles it exactly once, consistent") {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = gatedRunner(gate, parked, entered)
          conv <- makeConv(runner = runner)
          r <- conv.submit("race")
          _ <- parked.await
          closeF <- conv.close.fork
          _ <- gate.succeed(()) // release the runner into the close race
          _ <- closeF.join
          o <- r.settled.await
          snap <- conv.snapshot
        yield assertTrue(
          o match
            case TurnOutcome.Done(_) => snap.size == 2
            case _                   => snap.isEmpty
        )
      },
      test(
        "cancel: unknown id false; queued cancel skips dispatch; dispatcher survives"
      ) {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = gatedRunner(gate, parked, entered)
          conv <- makeConv(runner = runner)
          r1 <- conv.submit("first")
          _ <- parked.await
          r2 <- conv.submit("second") // queued
          unknown <- conv.cancelTurn(TurnId("t-999"))
          c2 <- conv.cancelTurn(r2.id)
          _ <- gate.succeed(()) // let turn 1 complete
          o1 <- r1.settled.await
          o2 <- r2.settled.await
          n <- entered.get
          r3 <- conv.submit("third") // dispatcher must still be alive
          o3 <- r3.settled.await
          _ <- conv.close // hygiene
        yield assertTrue(
          unknown == false,
          c2 == true,
          isDone(o1),
          o2 == TurnOutcome.Cancelled,
          n == 1, // turn 2 never reached the runner
          isDone(o3)
        )
      },
      test(
        "fromSnapshot refuses histories that are not strict prompt/reply pairs"
      ) {
        val uneven = List(user("a"), assistant("b"), user("c"))
        val wrongOrder = List(assistant("a"), user("b"))
        val e1 = ConversationArchive.fromSnapshot(
          Conversation.newSessionId(),
          Conversation.newRoot(),
          None,
          "scripted",
          uneven,
          0L
        )
        val e2 = ConversationArchive.fromSnapshot(
          Conversation.newSessionId(),
          Conversation.newRoot(),
          None,
          "scripted",
          wrongOrder,
          0L
        )
        assertTrue(
          e1.fold(isInputValidation, _ => false),
          e2.fold(isInputValidation, _ => false)
        )
      },
      test(
        "root opens children and close seals them all (SESSION-03 ownership)"
      ) {
        for
          root <- ConversationRoot.make()
          c1 <- root.open(
            ConversationBounds.default,
            TurnRunner(msgs => ZIO.succeed("r1")),
            label = Some("one")
          )
          c2 <- root.open(
            ConversationBounds.default,
            TurnRunner(msgs => ZIO.succeed("r2"))
          )
          kids <- root.snapshot
          r1 <- Task.zio(c1.apply("q")).either
          _ <- c2.close // individual child close is allowed
          _ <- root.close
          sealed1 <- c1.isSealed
          sealed2 <- c2.isSealed
          rootSealed <- root.isSealed
          err <- root
            .open(ConversationBounds.default, TurnRunner(_ => ZIO.succeed("x")))
            .flip
          snap <- root.snapshot
        yield assertTrue(
          kids.map(_.sessionId) == List(c1.sessionId, c2.sessionId),
          r1 == Right("r1"),
          sealed1,
          sealed2,
          rootSealed,
          isInputValidation(err),
          snap.size == 2
        )
      }
    )

end ConversationSpec
