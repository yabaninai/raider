package raider.runtime.budget

import raider.core.{RaiderError, RootId}
import zio.{UIO, ZIO}
import zio.stm.{TMap, TRef, STM}

/** Exact money in integer micro-USD (runtime-contracts §7). Double is forbidden
  * for money: sums must be exact, so everything below is Long arithmetic with
  * explicit overflow checks (BUD-01 exact rounding/overflow).
  */
final case class MicroUsd(value: Long) extends AnyVal:

  def +(other: MicroUsd): Either[RaiderError, MicroUsd] =
    BudgetLedger.checkedAdd(this, other)

  override def toString: String = s"${value}µ$$"

object MicroUsd:
  def ofDollars(d: Long): MicroUsd = MicroUsd(Math.multiplyExact(d, 1_000_000L))

/** Price knowledge for one dispatch attempt (§7: the four cost states).
  *
  *   - Priced: a conservative exact estimate is known before the call.
  *   - Unknown: no price is known. Unknown is NOT zero. Under a required hard
  *     USD cap this refuses admission (BUD-02); without a cap the attempt may
  *     run and is tracked as unpriced usage, never as free.
  */
enum CostEstimate:
  case Priced(amount: MicroUsd)
  case Unknown

/** Immutable ceiling set validated at construction (BUD-02: negative/overflow
  * config never launches — Configuration, not silent clamping).
  */
final case class BudgetLimits(
    hardUsdCap: Option[MicroUsd],
    maxConcurrentLlm: Int,
    maxConcurrentTools: Int,
    maxChildren: Int,
    maxDepth: Int,
    maxAttempts: Int,
    queueCapacity: Int
)

object BudgetLimits:

  private def positive(
      name: String,
      n: Int,
      minimum: Int
  ): Either[RaiderError, Unit] =
    if n < minimum then
      Left(RaiderError.Configuration(s"$name must be >= $minimum, got $n"))
    else Right(())

  def make(
      hardUsdCap: Option[MicroUsd] = None,
      maxConcurrentLlm: Int = 1,
      maxConcurrentTools: Int = 1,
      maxChildren: Int = 64,
      maxDepth: Int = 16,
      maxAttempts: Int = 8,
      queueCapacity: Int = 256
  ): Either[RaiderError, BudgetLimits] =
    hardUsdCap match
      case Some(MicroUsd(v)) if v < 0 =>
        Left(RaiderError.Configuration(s"hardUsdCap must be >= 0, got $v"))
      case _ =>
        for
          _ <- positive("maxConcurrentLlm", maxConcurrentLlm, 1)
          _ <- positive("maxConcurrentTools", maxConcurrentTools, 1)
          _ <- positive("maxChildren", maxChildren, 1)
          _ <- positive("maxDepth", maxDepth, 1)
          _ <- positive("maxAttempts", maxAttempts, 1)
          _ <- positive("queueCapacity", queueCapacity, 1)
        yield BudgetLimits(
          hardUsdCap,
          maxConcurrentLlm,
          maxConcurrentTools,
          maxChildren,
          maxDepth,
          maxAttempts,
          queueCapacity
        )

final case class ReservationId(value: String) extends AnyVal

/** Handle for one reserved attempt; immutable, settle path decides the outcome.
  */
final case class Reservation(id: ReservationId, root: RootId, amount: MicroUsd)

enum SettleStatus:
  case Reserved

  /** Observed cost confirmed; difference to the reservation was refunded. */
  case Spent(observed: MicroUsd)

  /** Released before dispatch: reservation fully refunded, nothing charged. */
  case Released

  /** The attempt may have been paid but the amount is unconfirmed (cancel
    * during dispatch, provider died mid-call). The reservation stays charged:
    * unknown is never treated as zero (§7).
    */
  case Uncertain

/** Truthful accounting snapshot. */
final case class UsageReport(
    reservedActive: MicroUsd,
    observedTotal: MicroUsd,
    uncertainTotal: MicroUsd,
    unpricedAttempts: Long,
    lateSettlements: Int,
    estimateViolations: Int
)

/** Root budget ledger (RAI-009, runtime-contracts §7).
  *
  * All mutations are single STM transactions: reservation, settlement, refund
  * and the hard-cap check are one atomic step, so concurrent children can never
  * overshoot the declared cap (BUD-01). Overflow is checked explicitly — a
  * wrapping Long is a defect, not a budget answer.
  *
  * Non-goals (card): cross-process distributed caps; post-hoc polling as
  * admission. Gateway billing may lag; this ledger enforces local launch
  * discipline only and reports uncertain/unpriced honestly.
  */
final class BudgetLedger private (
    limits: BudgetLimits,
    reserved: TRef[Long],
    observed: TRef[Long],
    uncertain: TRef[Long],
    unpriced: TRef[Long],
    late: TRef[Int],
    violations: TRef[Int],
    records: TMap[ReservationId, (RootId, Long, TRef[SettleStatus])]
):
  private val counter = new java.util.concurrent.atomic.AtomicLong(0)

  /** Atomic admission-side reservation (BUD-01/BUD-02). */
  def reserveSTM(
      root: RootId,
      est: CostEstimate
  ): STM[RaiderError, Reservation] =
    est match
      case CostEstimate.Unknown =>
        // unknown price under a required hard cap is a refused launch (BUD-02)
        limits.hardUsdCap match
          case Some(_) =>
            STM.fail(
              RaiderError.ProviderBudget(
                "unknown price under required hard USD cap: launch refused (BUD-02)"
              )
            )
          case None =>
            for
              _ <- unpriced.update(_ + 1)
              st <- TRef.make[SettleStatus](SettleStatus.Reserved)
              id = ReservationId(s"rsv_unpriced_${counter.incrementAndGet()}")
              // settle-able record with zero reservation: observed usage of an
              // unpriced attempt is still recorded truthfully (never dropped)
              _ <- records.put(id, (root, 0L, st))
            yield Reservation(id, root, MicroUsd(0))
      case CostEstimate.Priced(amount) =>
        for
          current <- reserved.get
          _ <-
            if amount.value < 0
            then
              STM.fail(
                RaiderError.Configuration(
                  s"negative cost estimate ${amount}: rejected"
                )
              )
            else STM.unit
          next <- amount.value > Long.MaxValue - current match
            case true =>
              STM.fail(
                RaiderError.LocalBudgetExceeded(
                  s"cost reservation overflow: $current + ${amount}"
                )
              )
            case false => STM.succeed(current + amount.value)
          _ <- limits.hardUsdCap match
            case Some(cap) if next > cap.value =>
              STM.fail(
                RaiderError.ProviderBudget(
                  s"hard USD cap ${cap} would be exceeded: reserved $current + ${amount}"
                )
              )
            case _ => STM.unit
          _ <- reserved.set(next)
          st <- TRef.make[SettleStatus](SettleStatus.Reserved)
          id = ReservationId(s"rsv_${counter.incrementAndGet()}")
          _ <- records.put(id, (root, amount.value, st))
        yield Reservation(id, root, amount)

  def reserve(
      root: RootId,
      est: CostEstimate
  ): ZIO[Any, RaiderError, Reservation] =
    reserveSTM(root, est).commit

  /** Terminal settlement with observed cost; refund of the unused reservation
    * is part of the same transaction. Observed above the estimate is recorded
    * truthfully and counted as an estimate violation (cap governs the NEXT
    * admission, not a silent rewrite of history).
    */
  def settle(
      res: Reservation,
      observedCost: MicroUsd
  ): ZIO[Any, RaiderError, Unit] =
    (for
      currentObserved <- observed.get
      overflow = observedCost.value > Long.MaxValue - currentObserved
      _ <-
        if observedCost.value < 0 || overflow
        then
          STM.fail(
            RaiderError.LocalBudgetExceeded(
              s"observed cost out of range: ${observedCost}"
            )
          )
        else STM.unit
      entry <- records
        .get(res.id)
        .flatMap {
          case Some((root, amount, st)) => STM.succeed((root, amount, st))
          case None =>
            STM.fail(
              RaiderError
                .InputValidation(s"unknown reservation ${res.id.value}")
            )
        }
      _ <- entry match
        case (_, amount, st) =>
          st.get.flatMap {
            case SettleStatus.Reserved =>
              for
                _ <- st.set(SettleStatus.Spent(observedCost))
                _ <- reserved.update(_ - amount)
                _ <- observed.update(_ + observedCost.value)
                _ <- STM.when(observedCost.value > amount)(
                  violations.update(_ + 1)
                )
              yield ()
            case other =>
              // late settlement: counted, never rewrites the outcome (§4 style)
              late.update(_ + 1).as(())
          }
    yield ()).commit

  /** Attempt released before any dispatch: full refund. */
  def release(res: Reservation): UIO[Unit] =
    (for
      entry <- records.get(res.id)
      _ <- entry match
        case Some((_, amount, st)) =>
          st.get.flatMap {
            case SettleStatus.Reserved =>
              for
                _ <- st.set(SettleStatus.Released)
                _ <- reserved.update(_ - amount)
              yield ()
            case _ => late.update(_ + 1)
          }
        case None => STM.unit
    yield ()).commit

  /** Cancel/ambiguity during dispatch: the attempt may have been paid; the
    * reservation stays charged and is reported as Uncertain (never zeroed).
    */
  def markUncertain(res: Reservation): UIO[Unit] =
    (for
      entry <- records.get(res.id)
      _ <- entry match
        case Some((root, amount, st)) =>
          st.get.flatMap {
            case SettleStatus.Reserved =>
              for
                _ <- st.set(SettleStatus.Uncertain)
                _ <- reserved.update(_ - amount)
                _ <- uncertain.update(_ + amount)
              yield ()
            case _ => late.update(_ + 1)
          }
        case None => STM.unit
    yield ()).commit

  def usage: UIO[UsageReport] =
    (for
      r <- reserved.get
      o <- observed.get
      u <- uncertain.get
      p <- unpriced.get
      l <- late.get
      v <- violations.get
    yield UsageReport(MicroUsd(r), MicroUsd(o), MicroUsd(u), p, l, v)).commit

  def hardCap: Option[MicroUsd] = limits.hardUsdCap

object BudgetLedger:

  private[budget] def checkedAdd(
      a: MicroUsd,
      b: MicroUsd
  ): Either[RaiderError, MicroUsd] =
    if b.value > 0 && a.value > Long.MaxValue - b.value then
      Left(RaiderError.LocalBudgetExceeded(s"money overflow: ${a} + ${b}"))
    else if b.value < 0 && a.value < Long.MinValue - b.value then
      Left(RaiderError.LocalBudgetExceeded(s"money underflow: ${a} + ${b}"))
    else Right(MicroUsd(a.value + b.value))

  def make(limits: BudgetLimits): UIO[BudgetLedger] =
    for
      r <- TRef.make[Long](0L).commit
      o <- TRef.make[Long](0L).commit
      u <- TRef.make[Long](0L).commit
      p <- TRef.make[Long](0L).commit
      l <- TRef.make[Int](0).commit
      v <- TRef.make[Int](0).commit
      rs <- TMap.empty[ReservationId, (RootId, Long, TRef[SettleStatus])].commit
    yield new BudgetLedger(limits, r, o, u, p, l, v, rs)

end BudgetLedger
