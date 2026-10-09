package orders

import common.Money
import java.time.Instant

final case class Order(id: String, customerId: String, placed: Instant, total: Money)

object Order:
  // TODO(ord-14): enforce a per-customer open-order limit at intake
  def placeholderTotal: Money = Money.Zero
