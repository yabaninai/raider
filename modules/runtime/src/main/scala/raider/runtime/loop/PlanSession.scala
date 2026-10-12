package raider.runtime.loop

import raider.core.{RaiderError, RequestMessage, RootId}
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.{JobHandle, JobManager}
import raider.core.Task
import zio.json.EncoderOps
import PlanProposal.given
import zio.{Ref, Semaphore, UIO, ZIO}

/** RAI-107 / ADR-028 §2: the explicit approval transition after a successful
  * plan phase.
  *
  * This is composition over the EXISTING job machinery, not a second state
  * machine: the plan phase and the execution are two ordinary
  * [[JobManager]] jobs on one root ([[PlanSession.root]]), their lifecycle
  * statuses/transitions stay owned by `JobManager.canTransition` untouched.
  * The session itself tracks exactly four composition facts (Idle / Planning /
  * Approved / Cancelled) and guards exactly the ADR-028 edges:
  *   - execution NEVER starts before [[approve]] — approval is the only
  *     edge that creates the execution job (no auto-approve, no timer);
  *   - [[approve]] creates EXACTLY ONE execution job with the persisted plan
  *     as its context; a second approval is a typed
  *     [[PlanApprovalError.AlreadyApproved]] (exactly-once);
  *   - [[cancelPlan]] interrupts the plan phase like any ordinary job
  *     (bounded cleanup by the existing admission brackets, quality-gates
  *     §7.4/7.5); `cancel != approval` — approving a cancelled session is a
  *     typed [[PlanApprovalError.Cancelled]];
  *   - a cancelled session may plan again ([[startPlan]] from `Cancelled`) —
  *     a repeated plan never resurrects the cancelled job, it starts a new one
  *     on the same root (same budget, honest attempt accounting).
  */
final class PlanSession private (
    loop: AgentLoop,
    manager: JobManager,
    stateRef: Ref[PlanSession.State],
    gate: Semaphore
):

  def root: RootId = loop.root

  /** Current composition fact (read-only view for tests/wiring). */
  def state: UIO[PlanSession.State] = stateRef.get

  /** Starts ONE plan-phase job on the session root. Legal from `Idle` and
    * `Cancelled` (a repeated plan); anything else is a typed refusal BEFORE
    * any work (no second concurrent plan, no plan after approval).
    */
  def startPlan(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, RaiderError, PlanSession.PlanJob] =
    gate.withPermit {
      stateRef.get.flatMap {
        case PlanSession.Idle | PlanSession.Cancelled =>
          // artifactsDir / repair bound / enforcement registry all live in the
          // loop's PlanMode config (RA-CFG) — the session adds no knobs.
          val task = loop
            .runPlan(messages, ceilings, maxRounds)
            .either
          manager
            .start[Either[PlanPhaseError, PlanProposal]](
              loop.root,
              PlanSession.planLabel,
              Task(task)
            )
            .flatMap(handle => stateRef.set(PlanSession.Planning(handle)).as(handle))
        case other =>
          ZIO.fail(
            RaiderError.InputValidation(
              s"plan session is ${PlanSession.stateName(other)}: " +
                "startPlan is legal only from Idle or Cancelled"
            )
          )
      }
    }

  /** Await the plan job's typed outcome (unpacks the session's Either into the
    * plan-phase error channel; an interrupted job surfaces as `Cancelled`).
    */
  def awaitPlan(job: PlanSession.PlanJob): ZIO[Any, PlanPhaseError, PlanProposal] =
    manager.await(job).foldZIO(
      err => ZIO.fail(PlanPhaseError.Underlying(err)),
      {
        case Right(proposal) => ZIO.succeed(proposal)
        case Left(rejected)  => ZIO.fail(rejected)
      }
    )

  /** The approval edge (ADR-028: explicit, owner-driven, exactly-once).
    *
    * From `Planning`: first awaits the plan job — a successful proposal
    * creates the execution job (ordinary mode, full registry, SAME root and
    * admission, plan JSON as the leading context message); a rejected/failed
    * plan is a typed [[PlanApprovalError.NoPlan]]. From `Cancelled`:
    * [[PlanApprovalError.Cancelled]] (cancel is not approval). From `Approved`:
    * [[PlanApprovalError.AlreadyApproved]] — the second execution job is never
    * created. From `Idle`: [[PlanApprovalError.NoPlan]].
    */
  def approve(
      executionMessages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, PlanApprovalError, PlanSession.ExecJob] =
    gate.withPermit {
      stateRef.get.flatMap {
        case PlanSession.Approved(exec, _) =>
          ZIO.fail(PlanApprovalError.AlreadyApproved(exec.id.value))
        case PlanSession.Cancelled => ZIO.fail(PlanApprovalError.Cancelled)
        case PlanSession.Idle =>
          ZIO.fail(
            PlanApprovalError.NoPlan("no plan job was started in this session")
          )
        case PlanSession.Planning(handle) =>
          manager.await(handle).foldZIO(
            err => ZIO.fail(PlanApprovalError.NoPlan(err.detail)),
            {
              case Right(proposal) =>
                startExecution(proposal, executionMessages, ceilings, maxRounds)
              case Left(rejected) =>
                ZIO.fail(PlanApprovalError.NoPlan(rejected.detail))
            }
          )
      }
    }

  /** Exactly-once execution-job creation. Runs under the session gate: two
    * concurrent approvals are serialized, the second observes `Approved`.
    */
  private def startExecution(
      proposal: PlanProposal,
      executionMessages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, PlanApprovalError, PlanSession.ExecJob] =
    // The plan travels to the executor as its leading context message —
    // structured JSON, the same document that was persisted by PlanStore.
    val context = RequestMessage(
      "user",
      s"Approved plan — execute it step by step: ${proposal.toJson}"
    )
    val task = loop.runText(
      context +: executionMessages,
      ceilings,
      maxRounds
    )
    manager
      .start[String](loop.root, PlanSession.execLabel, Task(task))
      .flatMap { exec =>
        stateRef.set(PlanSession.Approved(exec, proposal)).as(exec)
      }

  /** Cancel the running plan phase (bounded cleanup: the ordinary interrupt
    * path of the existing admission/job brackets; §7.4/7.5 invariants). The
    * session becomes `Cancelled` only when the interrupt was ACTUALLY
    * delivered to a live plan job; an already-settled plan leaves the session
    * untouched (its outcome stays honest). Returns whether a live plan job was
    * cancelled.
    */
  def cancelPlan(): UIO[Boolean] =
    gate.withPermit {
      stateRef.get.flatMap {
        case PlanSession.Planning(handle) =>
          manager.cancel(handle).flatMap { cancelled =>
            ZIO
              .when(cancelled)(stateRef.set(PlanSession.Cancelled))
              .as(cancelled)
          }
        case _ => ZIO.succeed(false)
      }
    }

end PlanSession

object PlanSession:

  /** Plan-phase job handle: the typed plan outcome travels in the job result. */
  type PlanJob = JobHandle[Either[PlanPhaseError, PlanProposal]]

  /** Execution job handle: ordinary `runText` answer. */
  type ExecJob = JobHandle[String]

  /** The four composition facts of one session (see the class doc). */
  sealed trait State
  case object Idle extends State
  final case class Planning(plan: PlanJob) extends State
  final case class Approved(execution: ExecJob, proposal: PlanProposal)
      extends State
  case object Cancelled extends State

  private[loop] val planLabel: String = "plan"
  private[loop] val execLabel: String = "plan-exec"

  private[loop] def stateName(s: State): String = s match
    case Idle           => "Idle"
    case Planning(_)    => "Planning"
    case Approved(_, _) => "Approved"
    case Cancelled      => "Cancelled"

  /** Creates a session over a plan-enabled loop (its `planMode` supplies the
    * enforcement registry, the artifacts directory and the repair bound) and
    * the ordinary job registry. No runtime is created here — both the loop and
    * the manager are caller-owned singletons.
    */
  def make(loop: AgentLoop, manager: JobManager): UIO[PlanSession] =
    for
      stateRef <- Ref.make[PlanSession.State](PlanSession.Idle)
      gate <- Semaphore.make(1L)
    yield PlanSession(loop, manager, stateRef, gate)

end PlanSession
