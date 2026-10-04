package raider.runtime.jobs

import raider.core.{JobId, RootId}

/** Job lifecycle (runtime-contracts §4).
  *
  * Terminal statuses never transition again; a late completion attempt is
  * recorded as a late observation and does not resurrect the outcome.
  */
sealed trait JobStatus extends Product with Serializable
object JobStatus:
  case object Queued extends JobStatus
  case object Running extends JobStatus
  case object Waiting extends JobStatus
  case object WaitingApproval extends JobStatus
  case object Cancelling extends JobStatus

  sealed trait Terminal extends JobStatus
  case object Succeeded extends Terminal
  case object Failed extends Terminal
  case object Cancelled extends Terminal
  case object Interrupted extends Terminal
  case object Uncertain extends Terminal

  val terminal: Set[JobStatus] = Set(Succeeded, Failed, Cancelled, Interrupted, Uncertain)

  /** Legal edges of the §4 state machine. */
  def canTransition(from: JobStatus, to: JobStatus): Boolean = (from, to) match
    case (Queued, Running) | (Queued, Cancelling) => true
    case (Running, Waiting) | (Running, WaitingApproval) | (Running, Cancelling)
       | (Running, Succeeded) | (Running, Failed) => true
    case (Waiting, Running) | (Waiting, Cancelling)
       | (Waiting, Succeeded) | (Waiting, Failed) => true
    case (WaitingApproval, Running) | (WaitingApproval, Cancelling) => true
    case (Cancelling, Cancelled) | (Cancelling, Interrupted) | (Cancelling, Uncertain) => true
    case _ => false

/** Admin-boundary snapshot: the only heterogeneous view of a job. */
final case class JobSnapshot(id: JobId, root: RootId, label: String,
                             status: JobStatus, lateObservations: Int)
