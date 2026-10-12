package raider.repl

import raider.runtime.loop.PlanProposal
import raider.ui.AuxTabProvider

/** RAI-110 (ADR-028 §3): the ONE plan tab — a pure [[AuxTabProvider]] showing
  * the `PlanProposal` of the LAST plan job of the plan session.
  *
  *   - The RAI-088 snapshot-reader discipline (same class as the LineageTab
  *     provider in raider.ui): `render` is a PURE function of an immutable
  *     [[Snapshot]] — no IO, no locks, no blocking, no throw. The mutable home
  *     of the snapshot is the [[PlanPanel]] (an AtomicReference written only by
  *     the panel's background refreshers); this object is the total view.
  *   - `available` is honest: only a session that HAS a plan fact shows the
  *     tab; otherwise the shell renders its typed unavailable hint and the
  *     content is never read (LIN-04A discipline).
  *   - The rendering never invents state: a proposal is shown only when the
  * session actually carries one (Ready/Approved), a rejection only with its
  * typed reason, and cancellation always says that cancel is not approval.
  */
object PlanTab:

  /** The tab title (the ONE plan tab; card RAI-110). */
  final val title: String = "Plan"

  /** The composition fact of the plan session, mirrored for the view. */
  enum Phase:
    case Idle // no plan was started in this session
    case Planning // a plan job is live, no settled outcome yet
    case Ready // the plan settled with a proposal, not yet approved
    case Approved // the approval edge created the ONE execution job
    case Cancelled // cancelled live plan (cancel != approval)
    case Rejected // the plan phase ended in a typed rejection/failure

    /** Stable machine-readable name (the PTY report / diagnostics oracle). */
    def name: String = this match
      case Phase.Idle      => "idle"
      case Phase.Planning  => "planning"
      case Phase.Ready     => "ready"
      case Phase.Approved  => "approved"
      case Phase.Cancelled => "cancelled"
      case Phase.Rejected  => "rejected"

  /** The immutable read model of the tab. `proposal` is present exactly when
    * the session carries a settled proposal (Ready/Approved); `rejection` is
    * the typed reason of a rejected/failed phase; `execution` is the id of the
    * ONE execution job after approval.
    */
  final case class Snapshot(
      phase: Phase,
      proposal: Option[PlanProposal],
      rejection: Option[String],
      execution: Option[String]
  )

  object Snapshot:
    val idle: Snapshot = Snapshot(Phase.Idle, None, None, None)

  /** Honest availability: anything but a virgin session shows the tab. */
  def available(snapshot: Snapshot): Boolean =
    snapshot.phase != Phase.Idle

  /** The total, pure renderer. Whitespace-trimmed honesty everywhere: a missing
    * proposal can only come from a rejected/failed phase, and the line says so
    * instead of rendering a blank screen.
    */
  def render(snapshot: Snapshot): String =
    val head = snapshot.phase match
      case Phase.Idle      => "plan phase: idle"
      case Phase.Planning  => "plan phase: planning"
      case Phase.Ready     => "plan phase: ready"
      case Phase.Approved  => "plan phase: approved"
      case Phase.Cancelled => "plan phase: cancelled"
      case Phase.Rejected  => "plan phase: rejected"
    val body = snapshot.phase match
      case Phase.Idle => Vector("no plan was started in this session yet")
      case Phase.Planning =>
        Vector("the proposal appears here when the plan phase completes")
      case Phase.Ready =>
        proposalLines(snapshot) :+
          "review the plan, then type :approve (execute) or :reject (cancel)"
      case Phase.Approved =>
        val exec = snapshot.execution.fold("unknown")(identity)
        proposalLines(snapshot) :+
          s"execution: job $exec (exactly one execution per plan)"
      case Phase.Cancelled =>
        Vector("cancel is not approval: a new plan can be started")
      case Phase.Rejected =>
        Vector(
          snapshot.rejection.fold("the plan phase failed")(r => s"reason: $r")
        )
    (head +: body).mkString("\n")

  private def proposalLines(snapshot: Snapshot): Vector[String] =
    snapshot.proposal match
      case None => Vector("no proposal recorded for this phase")
      case Some(p) =>
        Vector(s"proposal: ${p.title}", "steps:") ++
          p.steps.zipWithIndex.map((step, i) => f"  ${i + 1}%2d. $step")

  /** The provider over a PURE snapshot reader (no IO, RAI-088). */
  def provider(read: () => Snapshot): AuxTabProvider =
    new AuxTabProvider:
      def title: String = PlanTab.title

      def available: Boolean = PlanTab.available(read())

      def render: String = PlanTab.render(read())

end PlanTab
