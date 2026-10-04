package raider.runtime.admission

import raider.core.{RaiderError, RootId}
import raider.runtime.budget.{BudgetLedger, BudgetLimits, CostEstimate, MicroUsd}
import zio.{Duration, Queue, Ref, UIO, ZIO}
import zio.stm.{TMap, TRef, STM}

/** Which scarce resource an operation needs; the permit is held only for the
  * matching operation, never for a parent's whole subtree (§6). */
enum SlotKind:
  case Model, Tool

/** Fair bounded admission + budget reservation (RAI-009, runtime-contracts §6).
  *
  * Acquisition order for every dispatch attempt (§6, verbatim):
  *   eligibility/queue-capacity → concurrency permit (deadline during wait)
  *   → recheck cancellation → atomic budget reservation → dispatch.
  *
  * Cancellation semantics (BUD-03): interrupt while WAITING frees nothing
  * (no permit was taken, no money reserved — deadline wait is interruptible);
  * cancel after the permit but before dispatch releases the permit and charges
  * nothing; cancel/interrupt DURING dispatch marks the attempt Uncertain and
  * keeps the reservation charged — "may have been paid" is never zeroed (§7).
  *
  * No-deadlock minimum (BUD-03): with maxConcurrentLlm=1 a parent holds the
  * model permit only around its own model call. Awaiting a child's join does
  * NOT hold the permit, so the child can run with a single slot.
  *
  * The permit pool is a bounded ZIO queue: take = fair FIFO acquire,
  * offer = release; an interrupted take leaves the queue untouched, so no
  * phantom permit can leak.
  */
final class Admission private (
  limits: BudgetLimits,
  ledger: BudgetLedger,
  modelSlots: Queue[Unit],
  toolSlots: Queue[Unit],
  queuedNow: TRef[Int],
  childrenOf: TMap[RootId, Int],
  attemptsOf: TMap[RootId, Int],
  cancelledRoots: TRef[Set[RootId]]
):

  private def slots(kind: SlotKind): Queue[Unit] = kind match
    case SlotKind.Model => modelSlots
    case SlotKind.Tool  => toolSlots

  private def maxSlots(kind: SlotKind): Int = kind match
    case SlotKind.Model => limits.maxConcurrentLlm
    case SlotKind.Tool  => limits.maxConcurrentTools

  // -- accounting brackets ---------------------------------------------------

  /** Explicit backpressure: refuse BEFORE waiting when the queue is full. */
  private val enterQueue: ZIO[Any, RaiderError, Unit] =
    (for
      n <- queuedNow.get
      _ <- if n >= limits.queueCapacity
           then STM.fail(RaiderError.LocalBudgetExceeded(
               s"admission queue full (${limits.queueCapacity}): backpressure"))
           else queuedNow.set(n + 1)
    yield ()).commit

  private val leaveQueue: UIO[Unit] = queuedNow.update(_ - 1).commit.unit

  private def registerChild(root: RootId): ZIO[Any, RaiderError, Unit] =
    (for
      n <- childrenOf.getOrElse(root, 0)
      _ <- if n + 1 > limits.maxChildren
           then STM.fail(RaiderError.LocalBudgetExceeded(
               s"maxChildren=${limits.maxChildren} exceeded for root ${root.value}"))
           else childrenOf.put(root, n + 1)
    yield ()).commit

  private def unregisterChild(root: RootId): UIO[Unit] =
    (for
      n <- childrenOf.getOrElse(root, 0)
      _ <- if n <= 1 then childrenOf.delete(root) else childrenOf.put(root, n - 1)
    yield ()).commit

  private def consumeAttempt(root: RootId): ZIO[Any, RaiderError, Unit] =
    (for
      n <- attemptsOf.getOrElse(root, 0)
      _ <- if n + 1 > limits.maxAttempts
           then STM.fail(RaiderError.LocalBudgetExceeded(
               s"maxAttempts=${limits.maxAttempts} exceeded for root ${root.value}"))
           else attemptsOf.put(root, n + 1)
    yield ()).commit

  private def recheckCancellation(root: RootId): ZIO[Any, RaiderError, Unit] =
    cancelledRoots.get.commit.flatMap { cancelled =>
      if cancelled.contains(root) then
        ZIO.fail(RaiderError.Cancelled(
          s"root ${root.value} is cancelled: no new dispatch (recheck after permit)"))
      else ZIO.unit
    }

  // -- public surface ---------------------------------------------------------

  /** Run one dispatch attempt for `root` under declared ceilings.
    *
    * `use` performs the actual (model/tool) work and returns its value together
    * with the OBSERVED cost; on success the reservation is settled (unused
    * estimate refunded) inside one atomic transaction. On any failure or
    * interruption the attempt is marked Uncertain (charge kept) — see the class
    * doc for the boundary rules.
    */
  def withSlot[A](kind: SlotKind, root: RootId, est: CostEstimate,
                  deadline: Option[Duration])
                 (use: ZIO[Any, RaiderError, (A, MicroUsd)]): ZIO[Any, RaiderError, A] =
    ZIO.acquireReleaseWith(enterQueue)(_ => leaveQueue) { _ =>
      slots(kind).take.timeout(deadline.getOrElse(Duration.Infinity)).flatMap {
        case None =>
          ZIO.fail(RaiderError.DeadlineExceeded(
            s"admission wait exceeded deadline for ${kind} slot (queued job cancelled, not stuck)"))
        case Some(()) =>
          ZIO.acquireReleaseWith(ZIO.unit)(_ => slots(kind).offer(()).unit) { _ =>
            ZIO.acquireReleaseWith(registerChild(root))(_ => unregisterChild(root)) { _ =>
              recheckCancellation(root) *>
                {
                  for
                    observed <- Ref.make[Option[MicroUsd]](None)
                    // consumeAttempt first: a refused reservation must not move
                    // money; an attempt that cannot reserve is still consumed
                    result <- ZIO.acquireReleaseWith(
                                consumeAttempt(root) *> ledger.reserve(root, est)
                              ) { res =>
                                ZIO.uninterruptible {
                                  // accounting in the bracket release (guaranteed
                                  // finalization on ANY exit path — BUD-03):
                                  // success settles exactly; failure/interrupt
                                  // marks Uncertain (charge kept, never zeroed)
                                  observed.get.flatMap {
                                    case Some(observedCost) =>
                                      ledger.settle(res, observedCost).orDie
                                    case None =>
                                      ledger.markUncertain(res)
                                  }
                                }
                              } { _ =>
                                use.tap { case (_, observedCost) =>
                                  observed.set(Some(observedCost))
                                }.map(_._1)
                              }
                  yield result
                }
            }
          }
      }
    }

  /** Convenience for free operations that still need the concurrency bound. */
  def withSlotFree[A](kind: SlotKind, root: RootId,
                      deadline: Option[Duration])
                     (use: ZIO[Any, RaiderError, A]): ZIO[Any, RaiderError, A] =
    withSlot(kind, root, CostEstimate.Priced(MicroUsd(0)), deadline)(
      use.map(a => (a, MicroUsd(0))))

  /** Mark a root cancelled: new dispatch attempts fail at the post-permit
    * recheck (§4 Cancelling blocks new model/tool/child actions). */
  def cancelRoot(root: RootId): UIO[Unit] =
    cancelledRoots.update(_ + root).commit.unit

  def clearCancellation(root: RootId): UIO[Unit] =
    cancelledRoots.update(_ - root).commit.unit

  /** Concurrent children currently admitted for `root`. */
  def activeChildren(root: RootId): UIO[Int] = childrenOf.getOrElse(root, 0).commit

  def attempts(root: RootId): UIO[Int] = attemptsOf.getOrElse(root, 0).commit

  def queued: UIO[Int] = queuedNow.get.commit

  def availableSlots(kind: SlotKind): UIO[Int] = slots(kind).size

  /** Static ceiling check for a planned nesting depth (no hidden state). */
  def checkDepth(depth: Int): Either[RaiderError, Unit] =
    if depth < 0 then Left(RaiderError.Configuration(s"depth must be >= 0, got $depth"))
    else if depth > limits.maxDepth then
      Left(RaiderError.LocalBudgetExceeded(
        s"maxDepth=${limits.maxDepth} exceeded at depth $depth"))
    else Right(())

  def budgetLedger: BudgetLedger = ledger

object Admission:
  def make(limits: BudgetLimits, ledger: BudgetLedger): UIO[Admission] =
    for
      modelSlots  <- Queue.bounded[Unit](limits.maxConcurrentLlm)
      toolSlots   <- Queue.bounded[Unit](limits.maxConcurrentTools)
      _           <- ZIO.foreachDiscard(1 to limits.maxConcurrentLlm)(_ => modelSlots.offer(()).unit)
      _           <- ZIO.foreachDiscard(1 to limits.maxConcurrentTools)(_ => toolSlots.offer(()).unit)
      queued      <- TRef.make[Int](0).commit
      children    <- TMap.empty[RootId, Int].commit
      attempts    <- TMap.empty[RootId, Int].commit
      cancelled   <- TRef.make[Set[RootId]](Set.empty).commit
    yield new Admission(limits, ledger, modelSlots, toolSlots,
                        queued, children, attempts, cancelled)

  /** Validated wiring: limits must pass BudgetLimits.make first (BUD-02 config). */
  def make(limits: Either[RaiderError, BudgetLimits]): ZIO[Any, RaiderError, Admission] =
    limits match
      case Left(err)    => ZIO.fail(err)
      case Right(valid) => BudgetLedger.make(valid).flatMap(make(valid, _))
end Admission
