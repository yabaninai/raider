# Architecture notes

- `orders` and `billing` both depend on `common`; they never depend on each
  other directly. Order totals are converted to invoice amounts by the caller.
- Invoices are stateful: `Open -> Sent -> Paid`, with `Void` as a terminal
  escape hatch. The state machine is defined in `billing/Invoice.scala`.
- Legacy ledger imports still go through the deprecated money parser; the
  plan is to retire it once the CSV importer is rewritten.
