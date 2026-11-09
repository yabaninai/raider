package raider.runtime.conversation

import zio.{Promise, Semaphore, ZIO}
import zio.test.Live.live
import zio.test.*

/** Pinned ZIO 2.1.26 semantics guard for the conversation dispatcher (RAI-036).
  *
  * A fiber forked from inside `ZIO.uninterruptible` inherits the
  * uninterruptible mask and CANNOT be interrupted while parked:
  * `Fiber.interrupt` from another fiber never completes. The dispatcher
  * registers turn fibers WITHOUT an uninterruptible wrapper (atomicity comes
  * from `dispatchLock`) precisely so close/cancelTurn can interrupt a parked
  * runner turn; these tests pin both halves of that reasoning so the wrapper is
  * never reintroduced.
  */
object InterruptReproSpec extends ZIOSpecDefault:

  override def spec =
    suite("InterruptSemanticsPin")(
      test("plain fork + interrupt of a promise-parked child completes") {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          f <- (parked.succeed(()) *> gate.await).forkDaemon
          _ <- parked.await
          res <- live(f.interrupt.timeout(zio.Duration.fromSeconds(3)))
        yield assertTrue(res.isDefined)
      },
      test(
        "fork inside uninterruptible parks forever: interrupt cannot complete"
      ) {
        for
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          lock <- Semaphore.make(1L)
          fHolder <- zio.Ref.make(Option.empty[zio.Fiber[Any, Unit]])
          _ <- lock.withPermit {
            ZIO.uninterruptible {
              (parked.succeed(()) *> gate.await).forkDaemon.flatMap(f =>
                fHolder.set(Some(f))
              )
            }
          }
          _ <- parked.await
          fOpt <- fHolder.get
          res <- fOpt match
            case Some(f) =>
              // The pinned trap: the interrupt cannot be delivered, so the
              // live 3s timeout fires and the result is EMPTY. If this ever
              // flips to defined, ZIO changed the fork-inheritance semantics
              // and the dispatcher's no-uninterruptible-fork guard must be
              // re-evaluated against the new behavior.
              live(f.interrupt.timeout(zio.Duration.fromSeconds(3)))
            case None => ZIO.succeed(None)
        yield assertTrue(res.isEmpty)
      }
    )
