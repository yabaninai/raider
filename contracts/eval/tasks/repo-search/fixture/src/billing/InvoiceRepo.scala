package billing

import common.Money
import scala.collection.mutable

final class InvoiceRepo:
  private val store = mutable.Map.empty[String, Invoice]

  def put(invoice: Invoice): Unit = store.update(invoice.id, invoice)

  def unpaid(): Vector[Invoice] =
    store.values.toVector
      .filter(i => i.state == InvoiceState.Open || i.state == InvoiceState.Sent)

  // Legacy import path from the old ledger CSV dumps.
  def importLegacyAmount(raw: String): Money = Money.legacyParse(raw)
