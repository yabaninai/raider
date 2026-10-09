package raider.cli.run

import raider.runtime.budget.{MicroUsd, UsageReport}
import zio.test.*

/** RAI-082 acceptance (ACCT-01..03): the accounting view renders a
  * [[UsageReport]] truthfully — exact JSON/text for a known report, `unknown`
  * whenever an attempt went unpriced (real zeros must not mask it and
  * uncertainUsd must not be zeroed), and honest output at the boundaries.
  */
object AccountingViewSpec extends ZIOSpecDefault:

  def spec = suite("accounting view (RAI-082)")(
    test(
      "ACCT-01: known report → exact JSON object and one-line human text"
    ) {
      val report = UsageReport(
        reservedActive = MicroUsd(2_000_000L),
        observedTotal = MicroUsd(1_500_000L),
        uncertainTotal = MicroUsd(250_000L),
        unpricedAttempts = 0L,
        lateSettlements = 1,
        estimateViolations = 2
      )
      assertTrue(
        AccountingView.json(report) ==
          """{"reservedUsd":"2.000000","observedUsd":"1.500000","uncertainUsd":"0.250000","unpricedAttempts":0,"lateSettlements":1,"estimateViolations":2,"unknown":false}""",
        AccountingView.human(report) ==
          "cost: $1.500000 observed, $0.250000 uncertain (0 unpriced)"
      )
    },
    test(
      "ACCT-02: unpriced attempts → unknown=true; zero values never mask unknown; uncertainUsd is preserved, not zeroed"
    ) {
      val allZeroUnpriced = UsageReport(
        reservedActive = MicroUsd(0L),
        observedTotal = MicroUsd(0L),
        uncertainTotal = MicroUsd(0L),
        unpricedAttempts = 2L,
        lateSettlements = 0,
        estimateViolations = 0
      )
      val json =
        """{"reservedUsd":"0.000000","observedUsd":"0.000000","uncertainUsd":"0.000000","unpricedAttempts":2,"lateSettlements":0,"estimateViolations":0,"unknown":true}"""
      val withUncertain = UsageReport(
        reservedActive = MicroUsd(0L),
        observedTotal = MicroUsd(120_000L),
        uncertainTotal = MicroUsd(750_000L),
        unpricedAttempts = 1L,
        lateSettlements = 0,
        estimateViolations = 0
      )
      assertTrue(
        AccountingView.json(allZeroUnpriced) == json,
        AccountingView.human(allZeroUnpriced) ==
          "cost: unknown (2 unpriced attempts)",
        AccountingView.accounting(withUncertain).unknown,
        AccountingView.accounting(withUncertain).uncertainUsd ==
          "0.750000",
        AccountingView.human(withUncertain) ==
          "cost: unknown (1 unpriced attempts)"
      )
    },
    test(
      "ACCT-03: boundary honesty — fully-known zero report shows exact zeros; 0 observed with all unknown stays unknown; exact 6-digit rendering at Long scale"
    ) {
      val fullyKnownZero = UsageReport(
        reservedActive = MicroUsd(0L),
        observedTotal = MicroUsd(0L),
        uncertainTotal = MicroUsd(0L),
        unpricedAttempts = 0L,
        lateSettlements = 0,
        estimateViolations = 0
      )
      val zeroObservedAllUnknown = UsageReport(
        reservedActive = MicroUsd(0L),
        observedTotal = MicroUsd(0L),
        uncertainTotal = MicroUsd(0L),
        unpricedAttempts = 3L,
        lateSettlements = 0,
        estimateViolations = 0
      )
      val large = MicroUsd(123456789012345L)
      assertTrue(
        AccountingView.human(fullyKnownZero) ==
          "cost: $0.000000 observed, $0.000000 uncertain (0 unpriced)",
        !AccountingView.accounting(fullyKnownZero).unknown,
        AccountingView.accounting(zeroObservedAllUnknown).unknown,
        AccountingView
          .json(zeroObservedAllUnknown)
          .contains(""""observedUsd":"0.000000""""),
        AccountingView
          .json(zeroObservedAllUnknown)
          .contains(""""unknown":true"""),
        AccountingView.human(zeroObservedAllUnknown) ==
          "cost: unknown (3 unpriced attempts)",
        AccountingView.usd(large) == "123456789.012345"
      )
    }
  )

end AccountingViewSpec
