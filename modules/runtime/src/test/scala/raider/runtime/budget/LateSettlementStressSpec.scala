package raider.runtime.budget

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, Promise, ZIO}

/** RAI-009.b (review fixes): stress interrupt-takers and
  * late-settlement/idempotence fixtures over the FROZEN RAI-009 code (no
  * production changes). Deterministic ordering via observable
  * availableSlots/reservedActive/queued bars (repeatUntil), not sleeps. */
object LateSettlementStressSpec extends ZIOSpecDefault:

  private val root = RootId("r-009b")

  private def codeOf[A](exit: ZExit[RaiderError, A]): Option[String] = exit match
    case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
    case ZExit.Success(_)     => None

  private def lims(llm: Int = 1, attempts: Int = 8): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(maxConcurrentLlm = llm, maxAttempts = attempts)

  def spec = suite("RAI-009.b stress + late-settlement/idempotence")(
    suite("stress interrupt-takers")(
      test("50 concurrent queue-parked takers interrupted at once leak nothing") {
        live {
          for
            adm        <- Admission.make(lims())
            holderGate <- Promise.make[Nothing, Unit]
            holder <- adm.withSlot(SlotKind.Model, root,
                       CostEstimate.Priced(MicroUsd(500L)), None) {
                       holderGate.await.as(((), MicroUsd(0L)))
                     }.forkDaemon
            // deterministic: holder owns the only slot and its reservation
            _ <- adm.budgetLedger.usage
                   .repeatUntil(_.reservedActive == MicroUsd(500L))
                   .timeout(ZDuration.fromSeconds(10))
            waiters <- ZIO.foreach(1 to 50) { i =>
              adm.withSlot(SlotKind.Model, root,
                CostEstimate.Priced(MicroUsd(10L * i)), None) {
                ZIO.succeed(((), MicroUsd(0L)))
              }.forkDaemon
            }
            // all 50 parked in the fair queue (1 holder bracket + 50 waiters)
            _ <- adm.queued.repeatUntil(_ == 51).timeout(ZDuration.fromSeconds(10))
            _ <- ZIO.foreachDiscard(waiters)(_.interrupt)
            // deterministic: every interrupted leaveQueue has completed
            _ <- adm.queued.repeatUntil(_ == 1).timeout(ZDuration.fromSeconds(10))
            midUsage <- adm.budgetLedger.usage
            midSlots <- adm.availableSlots(SlotKind.Model)
            _ <- holder.interrupt
            _ <- holder.await
            endUsage <- adm.budgetLedger.usage
            endSlots <- adm.availableSlots(SlotKind.Model)
          yield assertTrue(
            midUsage.reservedActive == MicroUsd(500L), // waiters charged nothing
            midUsage.uncertainTotal == MicroUsd(0),
            midSlots == 0, // holder keeps the slot; interrupted takes took nothing
            endUsage.reservedActive == MicroUsd(0),
            endUsage.uncertainTotal == MicroUsd(500L), // holder Uncertain, kept
            endSlots == 1) // exactly the initial pool: no phantom loss
        }
      },
      test("mixed settle/Uncertain outcomes under concurrency stay exact (6+6 runs)") {
        live {
          for
            // attempts=64: 12 dispatches of this root are all admissible, so
            // the ONLY outcome difference is settle vs interrupt-Uncertain
            adm <- Admission.make(lims(llm = 8, attempts = 64))
            // 6 runs complete with an exact observed settle
            _ <- ZIO.foreach(1 to 6) { _ =>
              adm.withSlot(SlotKind.Model, root,
                CostEstimate.Priced(MicroUsd(100L)), None) {
                ZIO.succeed(((), MicroUsd(40L)))
              }
            }
            // 6 runs park in dispatch (ZIO.never AFTER the reservation)
            parked <- ZIO.foreach(1 to 6) { _ =>
              adm.withSlot(SlotKind.Model, root,
                CostEstimate.Priced(MicroUsd(100L)), None) {
                ZIO.never
              }.forkDaemon
            }
            // deterministic: every parked run holds its reservation
            _ <- adm.budgetLedger.usage
                   .repeatUntil(_.reservedActive == MicroUsd(600L))
                   .timeout(ZDuration.fromSeconds(10))
            _ <- ZIO.foreachDiscard(parked)(_.interrupt)
            _ <- ZIO.foreachDiscard(parked)(_.await)
            usage <- adm.budgetLedger.usage
          yield assertTrue(
            usage.observedTotal == MicroUsd(240L),   // 6 × 40 exact
            usage.uncertainTotal == MicroUsd(600L),  // 6 × 100 kept, never zeroed
            usage.reservedActive == MicroUsd(0),
            usage.estimateViolations == 0)
        }
      }
    ),
    suite("late settlement / idempotence (ledger)")(
      test("settle after settle is a late observation, never a rewrite") {
        for
          ledger <- BudgetLedger.make(BudgetLimits.make(maxConcurrentLlm = 1).fold(e => throw e, identity))
          r      <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(100L)))
          _      <- ledger.settle(r, MicroUsd(40L))
          again  <- ledger.settle(r, MicroUsd(40L)).exit
          _      <- ledger.release(r)
          _      <- ledger.markUncertain(r)
          u      <- ledger.usage
        yield assertTrue(
          again.isSuccess,
          u.observedTotal == MicroUsd(40L), // NOT doubled
          u.reservedActive == MicroUsd(0L), // never negative
          u.uncertainTotal == MicroUsd(0L), // NOT charged after terminal
          u.lateSettlements == 3)           // second settle + release + markUncertain
      },
      test("release-then-settle and uncertain-then-settle are late, not double-counted") {
        for
          ledger <- BudgetLedger.make(BudgetLimits.make(maxConcurrentLlm = 1).fold(e => throw e, identity))
          r2     <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(70L)))
          _      <- ledger.release(r2)
          s2     <- ledger.settle(r2, MicroUsd(70L)).exit
          r3     <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(30L)))
          _      <- ledger.markUncertain(r3)
          s3     <- ledger.settle(r3, MicroUsd(30L)).exit
          u      <- ledger.usage
        yield assertTrue(
          s2.isSuccess, s3.isSuccess,
          u.observedTotal == MicroUsd(0L),   // released money never settles
          u.uncertainTotal == MicroUsd(30L), // exactly once
          u.lateSettlements == 2)            // r2 settle-after-release + r3 settle-after-uncertain
      },
      test("settle of an unknown reservation is typed InputValidation") {
        for
          ledger <- BudgetLedger.make(BudgetLimits.make(maxConcurrentLlm = 1).fold(e => throw e, identity))
          ghost   = Reservation(ReservationId("rsv_ghost"), root, MicroUsd(1L))
          res    <- ledger.settle(ghost, MicroUsd(5L)).exit
          u      <- ledger.usage
        yield assertTrue(
          res.isFailure,
          codeOf(res) == Some("RA-INP"),
          u.observedTotal == MicroUsd(0L))
      },
      test("unpriced reservation: observed settles once; terminal ops after are late") {
        for
          ledger <- BudgetLedger.make(BudgetLimits.make(maxConcurrentLlm = 1).fold(e => throw e, identity))
          r      <- ledger.reserve(root, CostEstimate.Unknown)
          _      <- ledger.settle(r, MicroUsd(1234L))
          _      <- ledger.release(r)
          _      <- ledger.settle(r, MicroUsd(1234L)).exit
          u      <- ledger.usage
        yield assertTrue(
          u.unpricedAttempts == 1L,
          u.observedTotal == MicroUsd(1234L), // written once, kept
          u.reservedActive == MicroUsd(0L),
          u.lateSettlements == 2)             // release + second settle
      },
      test("cancel/clear cycles are idempotent; dispatch works after clear") {
        for
          adm <- Admission.make(lims())
          _   <- adm.cancelRoot(root)
          _   <- adm.cancelRoot(root) // idempotent
          blocked <- adm.withSlotFree(SlotKind.Model, root, None)(ZIO.succeed(())).exit
          _   <- adm.clearCancellation(root)
          _   <- adm.clearCancellation(root) // idempotent
          ok  <- adm.withSlot(SlotKind.Model, root,
                  CostEstimate.Priced(MicroUsd(25L)), None) {
                  ZIO.succeed(((), MicroUsd(25L)))
                }
          u   <- adm.budgetLedger.usage
        yield assertTrue(
          blocked.isFailure,
          codeOf(blocked) == Some("RA-CANCEL"),
          ok == (),
          u.observedTotal == MicroUsd(25L),
          u.reservedActive == MicroUsd(0L))
      }
    )
  )
