package billing

import common.Money
import java.time.Instant

enum InvoiceState:
  case Open, Sent, Paid, Void

final case class Invoice(
    id: String,
    orderId: String,
    issued: Instant,
    amount: Money,
    state: InvoiceState
)

object Invoice:
  // TODO(bil-07): add reminder scheduling for overdue Open invoices
  def draft(orderId: String, amount: Money): Invoice =
    Invoice(s"INV-$orderId", orderId, Instant.now(), amount, InvoiceState.Open)
