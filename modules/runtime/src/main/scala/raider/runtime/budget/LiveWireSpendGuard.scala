package raider.runtime.budget

import raider.core.RaiderError
import zio.{Exit, IO, UIO, ZIO}

import scala.collection.mutable.ArrayBuffer

enum LiveWireCostStatus:
  case Observed, Estimated, Unknown

final case class LiveWireSpendEntry(
    requestNumber: Int,
    provider: String,
    scenario: String,
    reserved: MicroUsd,
    charged: Option[MicroUsd],
    costStatus: LiveWireCostStatus,
    outcome: String
)

final case class LiveWireSpendSnapshot(
    cap: MicroUsd,
    maxRequests: Int,
    requests: Int,
    committed: MicroUsd,
    reserved: MicroUsd,
    stopped: Boolean,
    entries: Vector[LiveWireSpendEntry]
)

/** Atomic per-request hard stop for an explicitly capped live-wire run.
  *
  * `projectedCost` must be a conservative upper bound for one response. The
  * bound is reserved before `effect` is evaluated. Successful responses append
  * exactly one settlement entry; uncertain failures keep the reservation
  * charged and stop future dispatch. Provider-auth failures are recorded as
  * zero-cost responses. This guard is process-local and must be shared by all
  * providers in one run.
  */
final class LiveWireSpendGuard private (
    cap: MicroUsd,
    maxRequests: Int
):
  private var requests = 0
  private var committed = 0L
  private var reserved = 0L
  private var stopped = false
  private val entries = ArrayBuffer.empty[LiveWireSpendEntry]

  private def admit(
      provider: String,
      scenario: String,
      projectedCost: MicroUsd
  ): Either[RaiderError, Int] = synchronized:
    if stopped then
      Left(RaiderError.ProviderBudget("live-wire spend guard is stopped"))
    else if projectedCost.value < 0 then
      Left(RaiderError.Configuration("projectedCost must be non-negative"))
    else if requests >= maxRequests then
      stopped = true
      Left(
        RaiderError.ProviderBudget(
          s"live-wire request cap $maxRequests reached; dispatch refused"
        )
      )
    else if projectedCost.value > cap.value - committed - reserved then
      stopped = true
      Left(
        RaiderError.ProviderBudget(
          s"live-wire USD cap ${cap} would be exceeded; dispatch refused"
        )
      )
    else
      requests += 1
      reserved += projectedCost.value
      Right(requests)

  private def settle(
      number: Int,
      provider: String,
      scenario: String,
      projectedCost: MicroUsd,
      charge: Option[MicroUsd],
      status: LiveWireCostStatus,
      outcome: String
  ): Unit = synchronized:
    reserved -= projectedCost.value
    charge match
      case Some(amount) if amount.value >= 0 =>
        if amount.value > projectedCost.value then stopped = true
        committed = Math.addExact(committed, amount.value)
      case _ =>
        stopped = true
        committed = Math.addExact(committed, projectedCost.value)
    entries += LiveWireSpendEntry(
      number,
      provider,
      scenario,
      projectedCost,
      charge,
      status,
      outcome
    )

  /** Runs one provider operation only after an atomic cap and request check. */
  def execute[A](
      provider: String,
      scenario: String,
      projectedCost: MicroUsd
  )(
      effect: IO[RaiderError, A]
  )(
      observedCost: A => Option[MicroUsd]
  ): IO[RaiderError, A] = ZIO.uninterruptibleMask { restore =>
    ZIO
      .fromEither(admit(provider, scenario, projectedCost))
      .flatMap: requestNumber =>
        val failedAttempt: Exit[RaiderError, A] => UIO[Unit] =
          case Exit.Failure(cause) =>
            val knownNoCharge = cause.failureOption.exists:
              case _: RaiderError.ProviderAuth => true
              case _                           => false
            ZIO.succeed(
              settle(
                requestNumber,
                provider,
                scenario,
                projectedCost,
                if knownNoCharge then Some(MicroUsd(0L)) else None,
                if knownNoCharge then LiveWireCostStatus.Observed
                else LiveWireCostStatus.Unknown,
                if knownNoCharge then "provider-auth-refusal"
                else "uncertain-failure"
              )
            )
          case Exit.Success(_) => ZIO.unit

        restore(effect)
          .onExit(failedAttempt)
          .flatMap: value =>
            val actual = observedCost(value)
            val status =
              if actual.isDefined then LiveWireCostStatus.Observed
              else LiveWireCostStatus.Unknown
            ZIO.succeed(
              settle(
                requestNumber,
                provider,
                scenario,
                projectedCost,
                actual,
                status,
                "response"
              )
            ) *> (actual match
              case Some(amount) if amount.value > projectedCost.value =>
                ZIO.fail(
                  RaiderError.ProviderBudget(
                    "observed live-wire cost exceeded its pre-call bound"
                  )
                )
              case _ => ZIO.succeed(value))
  }

  def snapshot: UIO[LiveWireSpendSnapshot] = ZIO.succeed(synchronized:
    LiveWireSpendSnapshot(
      cap,
      maxRequests,
      requests,
      MicroUsd(committed),
      MicroUsd(reserved),
      stopped,
      entries.toVector
    ))

object LiveWireSpendGuard:
  val AuthorizedCap: MicroUsd = MicroUsd(5_000_000L)
  val AuthorizedMaxRequests: Int = 100

  /** The owner-authorized RAI-184 run ceiling. */
  def authorized: LiveWireSpendGuard =
    new LiveWireSpendGuard(AuthorizedCap, AuthorizedMaxRequests)

  def make(
      cap: MicroUsd,
      maxRequests: Int
  ): Either[RaiderError, LiveWireSpendGuard] =
    if cap.value < 0 then
      Left(RaiderError.Configuration("live-wire cap must be non-negative"))
    else if maxRequests <= 0 then
      Left(RaiderError.Configuration("maxRequests must be positive"))
    else Right(new LiveWireSpendGuard(cap, maxRequests))
