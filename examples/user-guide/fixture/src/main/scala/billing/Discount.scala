package billing

object Discount:
  def discountCents(s: Int): Int = if s > 10000 then 1000 else 0
