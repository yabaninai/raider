package raider.cli.run

import raider.runtime.budget.{MicroUsd, UsageReport}
import zio.json.{DeriveJsonEncoder, EncoderOps, JsonEncoder}

/** RAI-082 (P6.B slice): the `accounting` section of run.json as a typed value.
  * Money is rendered EXACTLY from integer micro-USD into decimal USD strings
  * with 6 fractional digits (BUD-01: no Double ever touches money). The field
  * order here is the on-disk key order of the JSON object.
  */
final case class Accounting(
    reservedUsd: String,
    observedUsd: String,
    uncertainUsd: String,
    unpricedAttempts: Long,
    lateSettlements: Int,
    estimateViolations: Int,
    unknown: Boolean
)

object Accounting:
  given JsonEncoder[Accounting] = DeriveJsonEncoder.gen[Accounting]

/** RAI-082: pure rendering of a root's truthful [[UsageReport]] snapshot
  * (runtime-contracts §7) into (a) the run.json `accounting` JSON object and
  * (b) a one-line human summary. Every rendered value is read from the report —
  * nothing is invented — and unknown cost is never displayed as zero: when any
  * attempt went unpriced the whole view is `unknown`, while the real
  * observed/uncertain amounts stay in the report unchanged (uncertainUsd is NOT
  * zeroed by the unknown flag; unknown is NOT free).
  */
object AccountingView:

  /** Exact micro-USD → decimal USD string with 6 fractional digits
    * (`MicroUsd(1500000)` → `"1.500000"`). Pure Long integer arithmetic; the
    * quotient/remainder split never overflows, even at Long.MinValue, so the
    * text is exact for the full Long range. A negative ledger value renders
    * with its sign, never silently clamped.
    */
  def usd(m: MicroUsd): String =
    val v = m.value
    val q = v / 1_000_000L
    val r0 = v % 1_000_000L
    val r = if r0 < 0 then -r0 else r0
    val frac = "%06d".format(r)
    if v < 0 then s"-$q.$frac" else s"$q.$frac"

  /** The typed `accounting` object for run.json. `unknown` is true iff the
    * report has unpriced attempts; when it is true the observed/uncertain
    * strings are still the report's real amounts (honest zeros or honest
    * costs), never a fabricated value.
    */
  def accounting(report: UsageReport): Accounting =
    Accounting(
      reservedUsd = usd(report.reservedActive),
      observedUsd = usd(report.observedTotal),
      uncertainUsd = usd(report.uncertainTotal),
      unpricedAttempts = report.unpricedAttempts,
      lateSettlements = report.lateSettlements,
      estimateViolations = report.estimateViolations,
      unknown = report.unpricedAttempts > 0
    )

  /** JSON object text for the run.json `accounting` section (no surrounding
    * document — the wiring card embeds it as one field).
    */
  def json(report: UsageReport): String =
    accounting(report).toJson

  /** One-line human summary. Unpriced attempts hide the money line entirely
    * (`cost: unknown (N unpriced attempts)`) — a partial or unknown cost is
    * never shown as a number; only a fully-priced report may print amounts.
    */
  def human(report: UsageReport): String =
    if report.unpricedAttempts > 0 then
      s"cost: unknown (${report.unpricedAttempts} unpriced attempts)"
    else
      val o = usd(report.observedTotal)
      val u = usd(report.uncertainTotal)
      s"cost: $$$o observed, $$$u uncertain " +
        s"(${report.unpricedAttempts} unpriced)"

end AccountingView
