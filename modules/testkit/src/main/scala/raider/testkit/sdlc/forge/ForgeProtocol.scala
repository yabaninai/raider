package raider.testkit.sdlc.forge

/** Stable operation identities and typed protocol outcomes for the local forge
  * stand.
  */
object ForgeProtocol:

  enum OperationKind:
    case Commit, PullRequest, Merge

  enum PullRequestState:
    case Open, Merged, Closed

  enum FaultMode:
    case None, StaleRef, Conflict, Duplicate, UnknownOutcome, UnknownResult

  enum AsyncState:
    case Pending, TimedOutUncertain

  enum Outcome:
    case Applied
    case AlreadyApplied
    case Rejected
    case Stale
    case Conflict
    case Duplicate
    case Denied
    case Failed
    case TimedOutUncertain
    case Unknown
    case ReconcileRequired

  final case class Preconditions(expectedRef: String)
  final case class OperationId(value: String)

  final case class Request(
      operationId: OperationId,
      kind: OperationKind,
      preconditions: Preconditions,
      tree: Option[String] = None,
      pullRequestId: Option[String] = None
  )

  final case class Snapshot(
      ref: String,
      pullRequests: Map[String, PullRequestState]
  )

  final case class Response(
      operationId: OperationId,
      outcome: Outcome,
      snapshot: Snapshot,
      pullRequestId: Option[String] = None
  )
