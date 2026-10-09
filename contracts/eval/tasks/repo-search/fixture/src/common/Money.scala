package common

/** Money value type shared by orders and billing. */
final case class Money(cents: Long, currency: String):
  def plus(other: Money): Money =
    require(currency == other.currency, s"currency mismatch: $currency vs ${other.currency}")
    copy(cents = cents + other.cents)

  def minus(other: Money): Money =
    plus(other.copy(cents = -other.cents))

object Money:
  val Zero: Money = Money(0, "CHF")

  /** Deprecated: kept for the legacy ledger import path; do not use in new code. */
  def legacyParse(raw: String): Money =
    val normalized = raw.trim.replace(',', '.')
    val dot = normalized.indexOf('.')
    if dot < 0 then Money(normalized.toLong * 100, "CHF")
    else
      val whole = normalized.take(dot).toLong
      val frac = normalized.drop(dot + 1)
      val fracCents = if frac.isEmpty then 0L else frac.take(2).padTo(2, '0').toLong
      Money(whole * 100 + fracCents, "CHF")
