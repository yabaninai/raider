package raider.runtime.conversation

import raider.core.{RaiderError, RequestMessage, Task}
import zio.test.*
import zio.{Duration, Promise, Ref, ZIO}

/** RAI-036 runtime concurrency acceptance (SESSION-01/03): strict one-at-a-time
  * dispatch under concurrent starts, receipt conservation under close storms
  * and races, dispatcher survival after cancellations, typed backpressure, and
  * root ownership (no orphaned conversations).
  *
  * Determinism rules: park-on-promise runners pin dispatcher state; no sleeps;
  * racy tests carry a test-level timeout so a lost wakeup FAILS instead of
  * hanging CI.
  */
object ConversationConcurrencySpec extends ZIOSpecDefault:

  private def makeConv(
      bounds: ConversationBounds = ConversationBounds.default,
      runner: TurnRunner =
        TurnRunner(msgs => ZIO.succeed(s"reply-${msgs.size}"))
  ): ZIO[Any, RaiderError, Conversation] =
    Conversation.make(Conversation.newRoot(), bounds, runner)

  private def user(text: String) = RequestMessage("user", text)
  private def assistant(text: String) = RequestMessage("assistant", text)

  private def isDone(out: TurnOutcome): Boolean = out match
    case _: TurnOutcome.Done => true
    case _                   => false

  private def isInputValidation(e: RaiderError): Boolean = e match
    case _: RaiderError.InputValidation => true
    case _                              => false

  private def isLocalBudget(e: RaiderError): Boolean = e match
    case _: RaiderError.LocalBudgetExceeded => true
    case _                                  => false

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
    suite("ConversationConcurrency")(
      test(
        "eight concurrent starts: max in flight == 1, dispatch sees growing prefixes"
      ) {
        for
          inFlight <- Ref.make(0)
          maxInFlight <- Ref.make(0)
          sizes <- Ref.make(List.empty[Int])
          runner = TurnRunner(msgs =>
            for
              _ <- inFlight.update(_ + 1)
              cur <- inFlight.get
              _ <- maxInFlight.update(cur max _)
              _ <- sizes.update(_ :+ msgs.size)
              _ <- ZIO.yieldNow
              _ <- inFlight.update(_ - 1)
            yield s"r${msgs.size}"
          )
          conv <- makeConv(runner = runner)
          receipts <- ZIO.foreachPar((1 to 8).toList)(i => conv.submit(s"p$i"))
          outcomes <- ZIO.foreachPar(receipts)(r => r.settled.await)
          max <- maxInFlight.get
          seen <- sizes.get
        yield assertTrue(
          outcomes.forall(isDone),
          max == 1,
          // Acceptance order under foreachPar is scheduler-dependent: the
          // invariant is that each dispatch sees a DISTINCT growing prefix
          // (1, 3, ..., 15 messages), never the same prefix twice.
          seen.sorted == List(1, 3, 5, 7, 9, 11, 13, 15)
        )
      },
      test(
        "close storm (32 fibers): every accepted receipt settles, none dropped"
      ) {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = gatedRunner(gate, parked, entered)
          conv <- makeConv(runner = runner)
          r1 <- conv.submit("storm")
          _ <- parked.await // turn 1 parked in the runner
          queued <- ZIO.foreach((1 to 4).toList)(i => conv.submit(s"q$i"))
          _ <- ZIO.foreachPar((1 to 32).toList)(_ => conv.close)
          _ <- gate.succeed(
            ()
          ) // release nothing: the parked fiber is already gone
          o1 <- r1.settled.await
          oq <- ZIO.foreach(queued)(_.settled.await)
          sealedNow <- conv.isSealed
        yield assertTrue(
          o1 == TurnOutcome.Cancelled,
          oq.forall(_ == TurnOutcome.Cancelled),
          sealedNow
        )
      } @@ TestAspect.timeout(Duration.fromSeconds(10)),
      test(
        "cancel in-flight: receipt cancels, dispatcher survives, next turn Done"
      ) {
        for
          gate <- Promise.make[Nothing, Unit]
          p1 <- Promise.make[Nothing, Unit]
          p2 <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = TurnRunner(msgs =>
            for
              n <- entered.updateAndGet(_ + 1)
              _ <- (if n == 1 then p1.succeed(()) else p2.succeed(()))
              _ <- gate.await
            yield s"reply-${msgs.size}"
          )
          conv <- makeConv(runner = runner)
          r1 <- conv.submit("one")
          _ <- p1.await
          c1 <- conv.cancelTurn(r1.id)
          o1 <- r1.settled.await
          r2 <- conv.submit("two")
          _ <- p2.await
          _ <- gate.succeed(()) // let turn 2 complete
          o2 <- r2.settled.await
          snap <- conv.snapshot
          _ <- conv.close
        yield assertTrue(
          c1,
          o1 == TurnOutcome.Cancelled,
          isDone(o2),
          snap == List(user("two"), assistant("reply-1"))
        )
      } @@ TestAspect.timeout(Duration.fromSeconds(10)),
      test("a receipt awaited from many fibers settles exactly once") {
        for
          conv <- makeConv()
          r <- conv.submit("multi")
          outs <- ZIO.foreachPar((1 to 64).toList)(_ => r.settled.await)
          snap <- conv.snapshot
        yield assertTrue(
          outs.distinct.size == 1,
          outs.head == TurnOutcome.Done("reply-1"),
          snap.size == 2
        )
      },
      test("snapshot strictly alternates after concurrent turns settle") {
        for
          conv <- makeConv()
          receipts <- ZIO.foreachPar((1 to 8).toList)(i => conv.submit(s"p$i"))
          outcomes <- ZIO.foreachPar(receipts)(_.settled.await)
          snap <- conv.snapshot
        yield assertTrue(
          outcomes.forall(isDone),
          snap.size == 16,
          Conversation.validateSeed(snap, ConversationBounds.default).isRight
        )
      },
      test("bounded queue refuses overflow typed, never parks submits") {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          entered <- Ref.make(0)
          runner = gatedRunner(gate, parked, entered)
          bounds <- ZIO.fromEither(ConversationBounds.make(queueCapacity = 4))
          conv <- makeConv(bounds = bounds, runner = runner)
          r1 <- conv.submit("in-flight")
          _ <- parked.await // dispatcher parked: queue drained to 0
          queued <- ZIO.foreach((1 to 4).toList)(i => conv.submit(s"q$i"))
          refusals <- ZIO.foreach((1 to 6).toList)(i =>
            conv.submit(s"over$i").either
          )
          _ <- gate.succeed(())
          _ <- r1.settled.await
          oq <- ZIO.foreach(queued)(_.settled.await)
          _ <- conv.close
        yield assertTrue(
          queued.size == 4,
          oq.forall(isDone),
          refusals.forall {
            case Left(e) => isLocalBudget(e)
            case _       => false
          }
        )
      } @@ TestAspect.timeout(Duration.fromSeconds(10)),
      test("root open/close race: no orphans, refusals typed (SESSION-03)") {
        for
          root <- ConversationRoot.make()
          runner = TurnRunner(msgs => ZIO.succeed(s"r-${msgs.size}"))
          results <- ZIO.foreachPar((1 to 8).toList)(_ =>
            root.open(ConversationBounds.default, runner).either
          )
          _ <- root.close
          opened = results.collect { case Right(c) => c }
          sealedKids <- ZIO.foreach(opened)(_.isSealed)
          sealedRoot <- root.isSealed
          _ <- ZIO.foreachDiscard(opened)(_.whenSealed)
        yield assertTrue(
          sealedRoot,
          sealedKids.forall(identity),
          results.forall {
            case Right(_) => true
            case Left(e)  => isInputValidation(e)
          }
        )
      } @@ TestAspect.timeout(Duration.fromSeconds(10)),
      test("whenSealed resolves for waiters that raced close") {
        for
          conv <- makeConv()
          w1 <- conv.whenSealed.fork
          _ <- conv.close
          r1 <- w1.join
          w2 <- conv.whenSealed.fork
          r2 <- w2.join
        yield assertTrue(r1 == (), r2 == ())
      }
    )

end ConversationConcurrencySpec
