package raider.repl

import raider.core.RequestMessage
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.{
  PlanApprovalError,
  PlanPhaseError,
  PlanProposal,
  PlanSession
}
import raider.ui.{AuxTabProvider, ShellTerminal}
import zio.{Ref, Task, UIO, ZIO}

import java.util.concurrent.atomic.AtomicReference

/** RAI-110 (ADR-028 §3): the plan panel — the composition between the product
  * tab shell and the RAI-107 [[PlanSession]].
  *
  * Responsibilities (and nothing more — no second runtime, ADR-028 non-goals):
  *   - the mutable home of the plan-tab read model ([[PlanTab.Snapshot]] in an
  *     AtomicReference; the provider reads it PURELY, refreshers write it);
  *   - the proposal watcher: the STARTER calls [[track]] right after
  *     `PlanSession.startPlan` (documented order: **startPlan → awaitPlan →
  *     approve**); the watcher awaits the settled plan via `awaitPlan` and
  *     publishes the typed outcome — `awaitPlan` never takes the session
  *     semaphore, so the watcher cannot deadlock the approval edge;
  *   - the typed approval edges: [[approve]] / [[reject]] wrap
  *     `PlanSession.approve` / `cancelPlan` and NEVER lose a typed refusal
  *     (`AlreadyApproved` / `NoPlan` / `Cancelled` become outcome values);
  *   - the plan-command terminal: [[commandTerminal]] intercepts exactly the
  *     [[Commands.planCommands]] names (`:approve` / `:reject`) BEFORE the
  *     frozen raider.ui router sees the line; everything else passes through
  *     byte-exact.
  *
  * REVIEWER RISK 3 (RAI-107): `approve` awaits the plan job WHILE HOLDING the
  * session semaphore. This panel never calls it from a callback of the same
  * session: the terminal interceptor dispatches each command on its OWN
  * background fiber ([[zio.ZIO.forkDaemon]]), the readLine path never holds the
  * gate, and the watcher uses only the gate-free `awaitPlan`. Concurrent
  * `:approve` lines are serialized by the session gate; the second observes
  * `Approved` and is typed-denied (exactly-once is the session's guarantee).
  *
  * The outcome lines go through the ONE writer (`ShellTerminal.printAbove`), so
  * concurrent bar renders cannot tear them.
  */
final class PlanPanel private (
    session: PlanSession,
    ceilings: BudgetLimits,
    maxRounds: Int,
    snapshotRef: AtomicReference[PlanTab.Snapshot],
    outcomeRef: AtomicReference[
      Option[(String, Either[PlanPhaseError, PlanProposal])]
    ],
    watchedRef: AtomicReference[Option[String]]
):

  /** The CURRENT read-model snapshot (pure, total — the provider's reader). */
  def snapshot: PlanTab.Snapshot = snapshotRef.get

  /** The plan tab over [[snapshot]] (pure provider, RAI-088). */
  def provider: AuxTabProvider = PlanTab.provider(() => snapshot)

  /** The plan session this panel composes (read-only access for wiring). */
  def root: raider.core.RootId = session.root

  /** Re-derive the snapshot from the live session state (best-effort refresh
    * for wiring/testing; the watcher keeps it current automatically).
    */
  def refresh: UIO[Unit] =
    session.state.flatMap(st =>
      ZIO.succeed {
        val outcome = outcomeRef.get
        snapshotRef.set(PlanPanel.derive(st, outcome))
      }
    )

  /** The proposal watcher body: dedupes per plan job, awaits the settled plan
    * (typed, gate-free) and refreshes the snapshot with the outcome. Fork me:
    * `session.startPlan(...).flatMap(job => panel.track(job).fork)` — the
    * caller owns the fiber (the harness now, the future :plan card later).
    */
  def track(job: PlanSession.PlanJob): UIO[Unit] =
    val id = job.id.value
    if watchedRef.getAndSet(Some(id)).contains(id) then ZIO.unit
    else
      refresh *> session.awaitPlan(job).either.flatMap { outcome =>
        ZIO.succeed(outcomeRef.set(Some((id, outcome)))) *> refresh
      }

  /** The approval edge (ADR-028: explicit, exactly-once). Total: the typed
    * refusal of the session (`AlreadyApproved` / `NoPlan` / `Cancelled`)
    * becomes the [[PlanPanel.ApprovalOutcome.Denied]] value — never a lost
    * error, never a crash. Uses the panel's wiring constants for the execution
    * job (see [[PlanPanel.make]]).
    */
  def approve: ZIO[Any, Nothing, PlanPanel.ApprovalOutcome] =
    session
      .approve(PlanPanel.executionMessages, ceilings, maxRounds)
      .fold(
        err => PlanPanel.ApprovalOutcome.Denied(err),
        exec => PlanPanel.ApprovalOutcome.Approved(exec.id.value)
      )
      .flatMap(outcome => refresh.as(outcome))

  /** The reject edge = `PlanSession.cancelPlan` (ADR-028: cancel != approval; a
    * repeated plan stays possible from Cancelled). `NotRunning` is the honest
    * typed answer when no LIVE plan job exists to cancel.
    */
  def reject: UIO[PlanPanel.RejectOutcome] =
    session.cancelPlan().flatMap {
      case true  => refresh.as(PlanPanel.RejectOutcome.Cancelled)
      case false => ZIO.succeed(PlanPanel.RejectOutcome.NotRunning)
    }

  /** The plan-command terminal decorator (RAI-110). Input: lines matching the
    * Commands plan registry are consumed here — dispatched on a background
    * fiber, outcome printed through the SAME ONE writer — and the NEXT line is
    * read, so the frozen router never sees a plan command and the prompt stays
    * live during the whole approval. `BadUsage` prints the typed usage hint and
    * consumes the line; `NotPlan` lines pass through byte-exact. EOF passes
    * through (the shell quits honestly).
    */
  def commandTerminal(underlying: ShellTerminal): ShellTerminal =
    new ShellTerminal:
      def printAbove(lines: Vector[String]): UIO[Unit] =
        underlying.printAbove(lines)

      def readLine(prompt: String): Task[Option[String]] =
        underlying.readLine(prompt).flatMap {
          case None => ZIO.succeed(None)
          case Some(raw) =>
            PlanCommands.parse(raw) match
              case PlanCommandMatch.NotPlan => ZIO.succeed(Some(raw))
              case PlanCommandMatch.BadUsage(cmd) =>
                underlying
                  .printAbove(Vector(PlanPanel.usageLine(cmd))) *>
                  readLine(prompt)
              case PlanCommandMatch.Parsed(cmd) =>
                dispatch(underlying, cmd).forkDaemon *> readLine(prompt)
        }

      def close: UIO[Unit] = underlying.close

  /** One plan command on its OWN fiber (reviewer risk 3 — see the class doc);
    * the typed outcome is printed as ONE atomic block through the ONE writer.
    */
  private def dispatch(
      underlying: ShellTerminal,
      command: PlanCommand
  ): UIO[Unit] =
    command match
      case PlanCommand.Approve =>
        approve.flatMap(o => underlying.printAbove(PlanPanel.approvalLines(o)))
      case PlanCommand.Reject =>
        reject.flatMap(o => underlying.printAbove(PlanPanel.rejectLines(o)))

end PlanPanel

object PlanPanel:

  /** The typed outcome of `:approve` (RAI-110: shell-visible, test-oracle). */
  enum ApprovalOutcome:
    case Approved(execJobId: String)
    case Denied(error: PlanApprovalError)

  /** The typed outcome of `:reject`. */
  enum RejectOutcome:
    case Cancelled
    case NotRunning

  /** The execution job's user message (wiring constant: the plan JSON travels
    * as the leading context message added by PlanSession itself).
    */
  val executionMessages: List[RequestMessage] =
    List(RequestMessage("user", "Execute the approved plan."))

  /** ONE typed line per approval outcome (the printAbove block). */
  def approvalLines(outcome: ApprovalOutcome): Vector[String] =
    outcome match
      case ApprovalOutcome.Approved(id) =>
        Vector(
          s"plan approved: execution job $id started " +
            "(exactly one execution per plan)"
        )
      case ApprovalOutcome.Denied(err) => Vector(s"hint: ${err.detail}")

  /** ONE typed line per reject outcome. */
  def rejectLines(outcome: RejectOutcome): Vector[String] =
    outcome match
      case RejectOutcome.Cancelled =>
        Vector(
          "plan rejected: plan phase cancelled " +
            "(cancel is not approval; a new plan can be started)"
        )
      case RejectOutcome.NotRunning =>
        Vector("hint: reject: no live plan phase in this session")

  /** The typed usage hint for an argument-bearing plan command. */
  def usageLine(command: PlanCommand): String =
    s"hint: usage: ${command.name} (the command takes no arguments)"

  /** Pure derivation of the view snapshot from the session state + the last
    * settled plan outcome (of the CURRENTLY tracked job only).
    */
  private def derive(
      st: PlanSession.State,
      outcome: Option[(String, Either[PlanPhaseError, PlanProposal])]
  ): PlanTab.Snapshot =
    st match
      case PlanSession.Idle => PlanTab.Snapshot.idle
      case PlanSession.Cancelled =>
        PlanTab.Snapshot(PlanTab.Phase.Cancelled, None, None, None)
      case PlanSession.Approved(exec, proposal) =>
        PlanTab.Snapshot(
          PlanTab.Phase.Approved,
          Some(proposal),
          None,
          Some(exec.id.value)
        )
      case PlanSession.Planning(handle) =>
        outcome.collect { case (id, o) if id == handle.id.value => o } match
          case Some(Right(proposal)) =>
            PlanTab.Snapshot(PlanTab.Phase.Ready, Some(proposal), None, None)
          case Some(Left(err)) =>
            PlanTab.Snapshot(
              PlanTab.Phase.Rejected,
              None,
              Some(err.detail),
              None
            )
          case None =>
            PlanTab.Snapshot(PlanTab.Phase.Planning, None, None, None)

  /** Build the panel over a plan-enabled session. `ceilings`/`maxRounds` are
    * the wiring constants of the FUTURE execution job (the approval edge needs
    * them; the panel adds no knobs of its own).
    */
  def make(
      session: PlanSession,
      ceilings: BudgetLimits,
      maxRounds: Int
  ): UIO[PlanPanel] =
    for state <- session.state
    yield
      val initial = derive(state, None)
      new PlanPanel(
        session,
        ceilings,
        maxRounds,
        new AtomicReference[PlanTab.Snapshot](initial),
        new AtomicReference[Option[
          (String, Either[PlanPhaseError, PlanProposal])
        ]](None),
        new AtomicReference[Option[String]](None)
      )

end PlanPanel
