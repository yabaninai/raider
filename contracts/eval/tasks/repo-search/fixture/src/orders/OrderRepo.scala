package orders

import common.Money
import java.time.Instant
import scala.collection.mutable

final class OrderRepo:
  private val store = mutable.Map.empty[String, Order]

  def put(order: Order): Unit = store.update(order.id, order)

  def find(id: String): Option[Order] = store.get(id)

  // TODO(ord-21): replace the linear scan with an index once totals are cached
  def ordersAbove(amount: Money): Vector[Order] =
    store.values.toVector.filter(_.total.cents > amount.cents).sortBy(_.placed)

  // Legacy migration helper; new code must not call this.
  def importLegacyTotal(raw: String): Money = Money.legacyParse(raw)
