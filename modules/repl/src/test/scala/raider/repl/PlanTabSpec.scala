package raider.repl

import raider.runtime.loop.PlanProposal
import raider.ui.AuxTabRenderer
import zio.test._

/** RAI-110: the PURE plan-tab view (renderer/provider over the read model) and
  * the Commands plan-command registry surface (source of truth for the RAI-111
  * palette). No IO, no runtime — total functions only.
  */
object PlanTabSpec extends ZIOSpecDefault:

  private val proposal = PlanProposal(
    "Ship it",
    Vector("read the file", "write the test")
  )

  private def snap(
      phase: PlanTab.Phase,
      proposalOpt: Option[PlanProposal] = None,
      rejection: Option[String] = None,
      execution: Option[String] = None
  ): PlanTab.Snapshot =
    PlanTab.Snapshot(phase, proposalOpt, rejection, execution)

  def spec = suite("PlanTabSpec (RAI-110: plan tab view + command registry)")(
    suite("availability honesty (LIN-04A discipline)")(
      test("idle is unavailable; every other phase is available") {
        assertTrue(
          !PlanTab.available(PlanTab.Snapshot.idle),
          PlanTab.available(snap(PlanTab.Phase.Planning)),
          PlanTab.available(snap(PlanTab.Phase.Ready, Some(proposal))),
          PlanTab.available(
            snap(PlanTab.Phase.Approved, Some(proposal), execution = Some("j1"))
          ),
          PlanTab.available(snap(PlanTab.Phase.Cancelled)),
          PlanTab.available(
            snap(PlanTab.Phase.Rejected, rejection = Some("bad"))
          )
        )
      },
      test("the provider delegates title/available/render to the readers") {
        var current = PlanTab.Snapshot.idle
        val p = PlanTab.provider(() => current)
        val before = p.available
        current = snap(PlanTab.Phase.Ready, Some(proposal))
        assertTrue(
          p.title == "Plan",
          !before,
          p.available,
          p.render == PlanTab.render(current)
        )
      }
    ),
    suite("pure renderer (total, typed)")(
      test("idle says honestly that no plan was started") {
        val text = PlanTab.render(PlanTab.Snapshot.idle)
        assertTrue(
          text.linesIterator.toVector ==
            Vector(
              "plan phase: idle",
              "no plan was started in this session yet"
            )
        )
      },
      test("planning promises the proposal instead of pretending") {
        val text = PlanTab.render(snap(PlanTab.Phase.Planning))
        assertTrue(
          text.linesIterator.toVector ==
            Vector(
              "plan phase: planning",
              "the proposal appears here when the plan phase completes"
            )
        )
      },
      test("ready shows title + numbered steps + the typed review hint") {
        val text = PlanTab.render(snap(PlanTab.Phase.Ready, Some(proposal)))
        val lines = text.linesIterator.toVector
        assertTrue(
          lines ==
            Vector(
              "plan phase: ready",
              "proposal: Ship it",
              "steps:",
              "   1. read the file",
              "   2. write the test",
              "review the plan, then type :approve (execute) or :reject (cancel)"
            )
        )
      },
      test("approved carries the ONE execution job id") {
        val text =
          PlanTab.render(
            snap(
              PlanTab.Phase.Approved,
              Some(proposal),
              execution = Some("plan-exec-7")
            )
          )
        val lines = text.linesIterator.toVector
        assertTrue(
          lines(0) == "plan phase: approved",
          lines(5) ==
            "execution: job plan-exec-7 (exactly one execution per plan)"
        )
      },
      test("cancelled says that cancel is not approval") {
        val text = PlanTab.render(snap(PlanTab.Phase.Cancelled))
        assertTrue(
          text.linesIterator.toVector ==
            Vector(
              "plan phase: cancelled",
              "cancel is not approval: a new plan can be started"
            )
        )
      },
      test("rejected carries the typed reason; missing reason stays honest") {
        val withReason = PlanTab.render(
          snap(
            PlanTab.Phase.Rejected,
            rejection = Some("plan rejected after 2 attempt(s)")
          )
        )
        val without = PlanTab.render(snap(PlanTab.Phase.Rejected))
        assertTrue(
          withReason.linesIterator.toVector ==
            Vector(
              "plan phase: rejected",
              "reason: plan rejected after 2 attempt(s)"
            ),
          without.linesIterator.toVector ==
            Vector(
              "plan phase: rejected",
              "the plan phase failed"
            )
        )
      },
      test(
        "a phase that should carry a proposal but has none renders the honest line"
      ) {
        val text = PlanTab.render(snap(PlanTab.Phase.Ready, None))
        assertTrue(
          text.linesIterator.toVector.contains(
            "no proposal recorded for this phase"
          )
        )
      },
      test("the rendered block survives the frozen AuxTabRenderer splitting") {
        val screen =
          AuxTabRenderer.screen(
            PlanTab.title,
            PlanTab.render(snap(PlanTab.Phase.Ready, Some(proposal)))
          )
        assertTrue(
          screen.head == "aux tab [Plan]:",
          screen(2) == "proposal: Ship it",
          screen.length == 7
        )
      }
    ),
    suite("Commands plan-command registry (source of truth for RAI-111)")(
      test("the registry lists exactly the two plan commands") {
        assertTrue(
          Commands.planCommands == Vector(":approve", ":reject")
        )
      },
      test("helpText documents both commands (freshness anchor)") {
        assertTrue(
          Commands.helpText.linesIterator.exists(_.contains(":approve")),
          Commands.helpText.linesIterator.exists(_.contains(":reject"))
        )
      },
      test(
        "parse: exact commands parse; arguments are typed BadUsage; lookalikes and plain text are NotPlan"
      ) {
        assertTrue(
          PlanCommands.parse(":approve") ==
            PlanCommandMatch.Parsed(PlanCommand.Approve),
          PlanCommands.parse("  :reject  ") ==
            PlanCommandMatch.Parsed(PlanCommand.Reject),
          PlanCommands.parse(":Approve") ==
            PlanCommandMatch.Parsed(PlanCommand.Approve),
          PlanCommands.parse(":approve now") ==
            PlanCommandMatch.BadUsage(PlanCommand.Approve),
          PlanCommands.parse(":reject all") ==
            PlanCommandMatch.BadUsage(PlanCommand.Reject),
          PlanCommands.parse(":approvex") == PlanCommandMatch.NotPlan,
          PlanCommands.parse(":tabs") == PlanCommandMatch.NotPlan,
          PlanCommands.parse("hello world") == PlanCommandMatch.NotPlan,
          PlanCommands.parse("") == PlanCommandMatch.NotPlan
        )
      },
      test("command names match the registry entries") {
        assertTrue(
          PlanCommand.Approve.name == ":approve",
          PlanCommand.Reject.name == ":reject",
          Commands.planCommands.forall(n =>
            n == PlanCommand.Approve.name || n == PlanCommand.Reject.name
          )
        )
      }
    )
  )

end PlanTabSpec
