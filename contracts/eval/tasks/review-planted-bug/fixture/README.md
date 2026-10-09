# Checkout coupon table (source of truth)

`src/Checkout.scala` must implement exactly these discounts:

| Coupon code | Discount granted |
| --- | --- |
| `WELCOME10` | 10% of the cart subtotal |
| `VIP20` | 20% of the cart subtotal |

Any other code (or no code) grants no discount. The total charged is the
subtotal minus the granted discount.
