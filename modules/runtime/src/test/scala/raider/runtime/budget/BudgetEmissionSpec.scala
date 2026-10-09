package raider.runtime.budget

import raider.core.*
import raider.runtime.events.{
  EventEnvelope,
  EventError,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot,
  UsageSnapshot
}
import zio.test.*
import zio.stream.ZStream
import zio.{Duration as ZDuration, Exit as ZExit, Fiber, Ref, ZIO}

/** RAI-059 acceptance (EMJ-02/EMJ-03, ADR-024 §2.6/§6): BudgetLedger emission
  * of `BudgetChanged(reserved, observed, unknownAttempts)` after
  * reserve/settle/late (and the other budget mutations) into the REAL EventHub.
  *
  * Routing convention (documented on [[BudgetLedger.emitBudgetChanged]]): the
  * ledger is one budget pool for one root, so snapshots are routed under the
  * synthetic job id `JobId(root.value)` — one root's budget events form one
  * gap-free per-"job" sequence.
  *
  * Determinism: emissions are forked (never blocks — EMJ-03), so each test
  * waits for the emitted event (a generous `eventually` outer bound around a
  * logically guaranteed delivery) BEFORE the next operation, making every
  * captured snapshot exact.
  */
object BudgetEmissionSpec extends ZIOSpecDefault:

  private val root = RootId("b-root")
  private def budgetJob: JobId = JobId(root.value)

  private val tenSec = ZDuration.fromSeconds(10)

  private def eventually(
      max: ZDuration
  )(cond: ZIO[Any, Nothing, Boolean]): ZIO[Any, String, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, String, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the generous outer bound")
            else ZIO.sleep(ZDuration.fromMillis(10)) *> loop
        }
      loop
    }

  /** Wait until the sink holds at least `n` envelopes (forked emission is
    * delivered by the hub's forwarder — a bounded, guaranteed condition).
    */
  private def awaitCount(
      sink: Ref[Vector[EventEnvelope]],
      n: Int
  ): ZIO[Any, String, Unit] =
    eventually(tenSec)(sink.get.map(_.size >= n))

  private def subscribeDrain(
      hub: EventHub,
      jobId: JobId
  ): ZIO[
    Any,
    Nothing,
    (SessionSnapshot, Ref[Vector[EventEnvelope]], Fiber.Runtime[Nothing, Unit])
  ] =
    for
      (snap, stream) <- hub.subscribe(jobId)
      sink <- Ref.make(Vector.empty[EventEnvelope])
      fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
    yield (snap, sink, fiber)

  private def failingHub: EventHub =
    new EventHub:
      def publish(
          jobId: JobId,
          rootId: RootId,
          sessionId: SessionId,
          payload: EventPayload
      ): ZIO[Any, EventError, Unit] =
        ZIO.fail(EventError.Backpressure(jobId, 1))
      def subscribe(
          jobId: JobId
      ): ZIO[
        Any,
        Nothing,
        (SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])
      ] =
        ZIO.succeed(
          (
            SessionReducer.initial(jobId, RootId(""), SessionId("")),
            ZStream.empty
          )
        )
      def snapshot(jobId: JobId): ZIO[Any, Nothing, Option[SessionSnapshot]] =
        ZIO.succeed(None)
      def droppedDiagnostics: ZIO[Any, Nothing, Long] = ZIO.succeed(0L)

  private def exitCode[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  def spec = suite("BudgetLedger emission RAI-059")(
    test(
      "EMJ-03 BudgetChanged on reserve/settle/late: exact payloads, gap-free seq, honest usage fold"
    ) {
      for
        limits <- ZIO.fromEither(BudgetLimits.make())
        hub <- EventHub.make()
        ledger <- BudgetLedger.make(limits, Some(hub))
        (snap0, sink, fiber) <- subscribeDrain(hub, budgetJob)
        r1 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(100L)))
        _ <- awaitCount(sink, 1)
        // unpriced attempt (no cap): unknown is tracked, never zero
        r2 <- ledger.reserve(root, CostEstimate.Unknown)
        _ <- awaitCount(sink, 2)
        _ <- ledger.settle(r1, MicroUsd(80L))
        _ <- awaitCount(sink, 3)
        _ <- ledger.settle(r2, MicroUsd(50L))
        _ <- awaitCount(sink, 4)
        // late settlement: r1 already Spent — counted, re-emitted snapshot
        _ <- ledger.settle(r1, MicroUsd(10L))
        _ <- awaitCount(sink, 5)
        usage <- ledger.usage
        envs <- sink.get
        _ <- fiber.interrupt
        snap <- hub.snapshot(budgetJob)
      yield
        val ordered = envs.sortBy(_.seq)
        assertTrue(
          // subscribed before any operation: empty watermark
          snap0.lastSeq == 0L,
          ordered.map(_.seq) == Vector(1L, 2L, 3L, 4L, 5L),
          ordered.map(_.payload) == Vector(
            EventPayload.BudgetChanged(MicroUsd(100L), MicroUsd(0L), 0L),
            EventPayload.BudgetChanged(MicroUsd(100L), MicroUsd(0L), 1L),
            EventPayload.BudgetChanged(MicroUsd(0L), MicroUsd(80L), 1L),
            EventPayload.BudgetChanged(MicroUsd(0L), MicroUsd(130L), 1L),
            EventPayload.BudgetChanged(MicroUsd(0L), MicroUsd(130L), 1L)
          ),
          // honest accounting behind the events
          usage.lateSettlements == 1,
          usage.unpricedAttempts == 1L,
          usage.reservedActive == MicroUsd(0L),
          usage.observedTotal == MicroUsd(130L),
          // the session usage folds the stream (source of truth); unknown
          // stays unknown (unpriced > 0)
          snap.exists { s =>
            s.usage == UsageSnapshot(
              MicroUsd(0L),
              MicroUsd(130L),
              unknown = true
            ) && s.lastSeq == 5L
          }
        )
    } @@ TestAspect.withLiveClock,
    test(
      "EMJ-02 typed budget errors with a hub: refused operations emit nothing, the error is untouched"
    ) {
      for
        capped <- ZIO.fromEither(
          BudgetLimits.make(hardUsdCap = Some(MicroUsd.ofDollars(1L)))
        )
        hub <- EventHub.make()
        ledger <- BudgetLedger.make(capped, Some(hub))
        (snap0, sink, fiber) <- subscribeDrain(hub, budgetJob)
        neg <- ledger
          .reserve(root, CostEstimate.Priced(MicroUsd(-5L)))
          .exit
        unknownUnderCap <- ledger.reserve(root, CostEstimate.Unknown).exit
        overCap <- ledger
          .reserve(root, CostEstimate.Priced(MicroUsd.ofDollars(2L)))
          .exit
        ok <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(100L)))
        _ <- awaitCount(sink, 1)
        envs <- sink.get
        _ <- fiber.interrupt
      yield
        val ordered = envs.sortBy(_.seq)
        assertTrue(
          snap0.lastSeq == 0L,
          // all three refusals kept their typed codes, emission untouched
          exitCode(neg) == Some("RA-CFG"),
          exitCode(unknownUnderCap) == Some("RA-PBUDGET"),
          exitCode(overCap) == Some("RA-PBUDGET"),
          // exactly ONE event: only the successful reserve emitted
          ordered.map(_.seq) == Vector(1L),
          ordered.map(_.payload) == Vector(
            EventPayload.BudgetChanged(MicroUsd(100L), MicroUsd(0L), 0L)
          )
        )
    } @@ TestAspect.withLiveClock,
    test(
      "EMJ-03 a failing hub (always Backpressure) never blocks or alters reserve/settle/release/markUncertain"
    ) {
      for
        limits <- ZIO.fromEither(BudgetLimits.make())
        ledger <- BudgetLedger.make(limits, Some(failingHub))
        r1 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(100L)))
        r2 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(40L)))
        _ <- ledger.settle(r1, MicroUsd(70L))
        _ <- ledger.release(r2)
        r3 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(30L)))
        _ <- ledger.markUncertain(r3)
        unknown <- ledger
          .settle(
            Reservation(ReservationId("rsv_missing"), root, MicroUsd(1L)),
            MicroUsd(5L)
          )
          .exit
        usage <- ledger.usage
      yield assertTrue(
        // the full mutation lifecycle works despite every publish failing
        usage.reservedActive == MicroUsd(0L),
        usage.observedTotal == MicroUsd(70L),
        usage.uncertainTotal == MicroUsd(30L),
        exitCode(unknown) == Some("RA-INP")
      )
    } @@ TestAspect.withLiveClock,
    test(
      "EMJ-03 release/markUncertain emit truthful snapshots (superset of reserve/settle/late)"
    ) {
      for
        limits <- ZIO.fromEither(BudgetLimits.make())
        hub <- EventHub.make()
        ledger <- BudgetLedger.make(limits, Some(hub))
        (snap0, sink, fiber) <- subscribeDrain(hub, budgetJob)
        r1 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(100L)))
        _ <- awaitCount(sink, 1)
        _ <- ledger.release(r1)
        _ <- awaitCount(sink, 2)
        r2 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(50L)))
        _ <- awaitCount(sink, 3)
        _ <- ledger.markUncertain(r2)
        _ <- awaitCount(sink, 4)
        envs <- sink.get
        usage <- ledger.usage
        _ <- fiber.interrupt
      yield
        val ordered = envs.sortBy(_.seq)
        assertTrue(
          ordered.map(_.seq) == Vector(1L, 2L, 3L, 4L),
          ordered.map(_.payload) == Vector(
            EventPayload.BudgetChanged(MicroUsd(100L), MicroUsd(0L), 0L),
            // full refund on release
            EventPayload.BudgetChanged(MicroUsd(0L), MicroUsd(0L), 0L),
            EventPayload.BudgetChanged(MicroUsd(50L), MicroUsd(0L), 0L),
            // uncertain: the reservation is no longer reserved
            EventPayload.BudgetChanged(MicroUsd(0L), MicroUsd(0L), 0L)
          ),
          usage.uncertainTotal == MicroUsd(50L),
          usage.lateSettlements == 0
        )
    } @@ TestAspect.withLiveClock,
    test("EMJ-02 no hub: zero overhead, the ledger behaves exactly as before") {
      for
        limits <- ZIO.fromEither(BudgetLimits.make())
        ledger <- BudgetLedger.make(limits)
        r1 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(100L)))
        _ <- ledger.settle(r1, MicroUsd(80L))
        usage <- ledger.usage
      yield assertTrue(
        usage.reservedActive == MicroUsd(0L),
        usage.observedTotal == MicroUsd(80L)
      )
    }
  )

end BudgetEmissionSpec
