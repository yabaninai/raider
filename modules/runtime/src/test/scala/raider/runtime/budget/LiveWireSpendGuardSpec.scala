package raider.runtime.budget

import raider.core.RaiderError
import zio.test.*
import zio.{ZIO, durationInt}

object LiveWireSpendGuardSpec extends ZIOSpecDefault:
  private val usd = MicroUsd.ofDollars

  def spec = suite("LiveWireSpendGuard")(
    test("authorized live run is fixed to the owner cap and request maximum") {
      for snapshot <- LiveWireSpendGuard.authorized.snapshot
      yield assertTrue(
        snapshot.cap == usd(5),
        snapshot.maxRequests == 100,
        snapshot.requests == 0,
        snapshot.committed == MicroUsd(0)
      )
    },
    test("admits below and exact cap, then refuses before dispatch") {
      for
        guard <- ZIO.fromEither(LiveWireSpendGuard.make(usd(5), 100))
        dispatched <- ZIO.foldLeft(List(3L, 2L))(Vector.empty[Int]) {
          (acc, amount) =>
            guard
              .execute("openai-compatible", "direct", usd(amount))(
                ZIO.succeed(acc.size + 1)
              )(_ => Some(usd(amount)))
              .map(acc :+ _)
        }
        refused <- guard
          .execute("anthropic-compatible", "stream", usd(1))(
            ZIO.succeed(99)
          )(_ => Some(usd(1)))
          .exit
        snapshot <- guard.snapshot
      yield assertTrue(
        dispatched == Vector(1, 2),
        refused.isFailure,
        snapshot.requests == 2,
        snapshot.committed == usd(5),
        snapshot.reserved == MicroUsd(0),
        snapshot.stopped,
        snapshot.entries.size == 2
      )
    },
    test("enforces request count before dispatch") {
      for
        guard <- ZIO.fromEither(LiveWireSpendGuard.make(usd(5), 1))
        calls <- zio.Ref.make(0)
        _ <- guard.execute("openai-compatible", "direct", usd(1))(
          calls.update(_ + 1).as("ok")
        )(_ => Some(usd(1)))
        refused <- guard
          .execute("anthropic-compatible", "direct", usd(1))(
            calls.update(_ + 1).as("unexpected")
          )(_ => Some(usd(1)))
          .exit
        callCount <- calls.get
        snapshot <- guard.snapshot
      yield assertTrue(
        refused.isFailure,
        callCount == 1,
        snapshot.requests == 1,
        snapshot.committed == usd(1),
        snapshot.entries.size == 1
      )
    },
    test("parallel admission cannot reserve beyond the cap") {
      for
        guard <- ZIO.fromEither(LiveWireSpendGuard.make(usd(5), 100))
        calls <- zio.Ref.make(0)
        entered <- zio.Promise.make[Nothing, Unit]
        release <- zio.Promise.make[Nothing, Unit]
        results <- ZIO
          .foreachPar(1 to 8) { _ =>
            guard
              .execute("openai-compatible", "stream", usd(3))(
                calls.update(_ + 1) *> entered.succeed(()).unit *>
                  release.await.as("ok")
              )(_ => Some(usd(3)))
              .either
          }
          .fork
        _ <- entered.await
        refusalObserved <- guard.snapshot.repeatUntil(_.stopped)
        _ <- release.succeed(())
        completed <- results.join
        callCount <- calls.get
        snapshot <- guard.snapshot
      yield assertTrue(
        refusalObserved.requests == 1,
        completed.count(_.isRight) == 1,
        callCount == 1,
        snapshot.requests == 1,
        snapshot.committed == usd(3),
        snapshot.reserved == MicroUsd(0),
        snapshot.stopped
      )
    },
    test(
      "auth refusal is typed, recorded at zero, and does not leak reservation"
    ) {
      for
        guard <- ZIO.fromEither(LiveWireSpendGuard.make(usd(5), 100))
        result <- guard
          .execute("anthropic-compatible", "direct", usd(1))(
            ZIO.fail(
              RaiderError.ProviderAuth("provider rejected auth (HTTP 401)")
            )
          )(_ => None)
          .either
        snapshot <- guard.snapshot
      yield assertTrue(
        result.left.toOption.exists(_.isInstanceOf[RaiderError.ProviderAuth]),
        snapshot.requests == 1,
        snapshot.committed == MicroUsd(0),
        snapshot.reserved == MicroUsd(0),
        !snapshot.stopped,
        snapshot.entries.head.outcome == "provider-auth-refusal"
      )
    },
    test("unknown charge is conservatively kept and halts future calls") {
      for
        guard <- ZIO.fromEither(LiveWireSpendGuard.make(usd(5), 100))
        _ <- guard.execute("openai-compatible", "cancel", usd(2))(
          ZIO.succeed("partial")
        )(_ => None)
        refused <- guard
          .execute("anthropic-compatible", "direct", usd(1))(
            ZIO.succeed("unexpected")
          )(_ => Some(usd(1)))
          .exit
        snapshot <- guard.snapshot
      yield assertTrue(
        refused.isFailure,
        snapshot.committed == usd(2),
        snapshot.reserved == MicroUsd(0),
        snapshot.stopped,
        snapshot.entries.head.costStatus == LiveWireCostStatus.Unknown,
        snapshot.entries.head.charged.isEmpty
      )
    },
    test("cancellation during dispatch records uncertain spend and stops") {
      for
        guard <- ZIO.fromEither(LiveWireSpendGuard.make(usd(5), 100))
        entered <- zio.Promise.make[Nothing, Unit]
        wait <- zio.Promise.make[Nothing, Unit]
        fiber <- guard
          .execute("anthropic-compatible", "cancel-mid-stream", usd(2))(
            entered.succeed(()).unit *> wait.await.as("unreachable")
          )(_ => None)
          .fork
        _ <- entered.await
        _ <- fiber.interrupt
        snapshot <- guard.snapshot
        refused <- guard
          .execute("openai-compatible", "direct", usd(1))(
            ZIO.succeed("unexpected")
          )(_ => Some(usd(1)))
          .exit
      yield assertTrue(
        refused.isFailure,
        snapshot.requests == 1,
        snapshot.committed == usd(2),
        snapshot.reserved == MicroUsd(0),
        snapshot.stopped,
        snapshot.entries.head.costStatus == LiveWireCostStatus.Unknown,
        snapshot.entries.head.outcome == "uncertain-failure"
      )
    }
  )
