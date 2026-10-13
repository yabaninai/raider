package billing

object DiscountSpec:

  private def check(actual: Int, expected: Int, label: String): Boolean =
    if actual == expected then true
    else
      println(s"FAIL $label")
      false

  def main(args: Array[String]): Unit =
    val results = List(
      check(Discount.discountCents(9999), 0, "below-threshold"),
      check(Discount.discountCents(10000), 1000, "exact-threshold"),
      check(Discount.discountCents(10001), 1000, "above-threshold")
    )
    if results.forall(identity) then println("DiscountSpec: PASS 3 boundaries")
    else sys.exit(1)
