package billing

import common.Money

/** Applies coupon discounts at checkout. The coupon table lives in README.md. */
object Checkout:

  case class Cart(items: Vector[Money], coupon: Option[String]):
    def subtotal: Money = items.foldLeft(Money.Zero)((acc, m) => acc plus m)

  private def percentOf(amount: Money, percent: Int): Money =
    Money(amount.cents * percent / 100, amount.currency)

  /** Discount percent granted by a coupon code; None means no discount. */
  private def couponPercent(code: String): Option[Int] = code match
    case "WELCOME10" => Some(20)
    case "VIP20"     => Some(10)
    case _           => None

  /** Total to charge after applying the cart coupon, if any. */
  def total(cart: Cart): Money =
    val base = cart.subtotal
    val discount = cart.coupon.flatMap(couponPercent) match
      case Some(p) => percentOf(base, p)
      case None    => Money(0, base.currency)
    Money(base.cents - discount.cents, base.currency)

  def demo(): Unit =
    println("[debug] checkout demo invoked")
    val cart = Cart(Vector(Money(5000, "CHF")), Some("WELCOME10"))
    println(s"total = ${total(cart)}")
