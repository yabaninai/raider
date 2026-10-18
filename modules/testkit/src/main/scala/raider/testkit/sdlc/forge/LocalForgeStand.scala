package raider.testkit.sdlc.forge

import ForgeProtocol.*

/** Deterministic in-process implementation of the vendor-neutral forge
  * protocol. All mutations first compare their supplied ref with a fresh
  * read-only snapshot. An uncertain operation cannot be submitted again until
  * reconcile has observed its effect or established that it did not happen.
  */
final class LocalForgeStand(initialRef: String = "main-0"):
  private var currentRef = initialRef
  private var pullRequests = Map.empty[String, PullRequestState]
  private var operations = Map.empty[OperationId, Response]
  private var uncertain = Set.empty[OperationId]
  private var nextFault: FaultMode = FaultMode.None
  private var nextPullRequest = 1

  private def snapshot: Snapshot = Snapshot(currentRef, pullRequests)

  /** Inject one deterministic fault into the next mutating request. */
  def inject(fault: FaultMode): Unit = synchronized { nextFault = fault }

  /** Read-only current state. Calling this resolves the retry barrier for an
    * uncertain operation only when no effect is found in its recorded state.
    */
  def reconcile(operationId: OperationId): Response = synchronized {
    val observed = operations.get(operationId) match
      case Some(applied)
          if applied.outcome == Outcome.TimedOutUncertain || applied.outcome == Outcome.Unknown =>
        val result =
          applied.copy(outcome = Outcome.AlreadyApplied, snapshot = snapshot)
        operations = operations.updated(operationId, result)
        uncertain -= operationId
        result
      case Some(existing) => existing.copy(snapshot = snapshot)
      case None =>
        uncertain -= operationId
        Response(operationId, Outcome.Rejected, snapshot)
    observed
  }

  /** Test/operator hook modelling another owner advancing the target ref. */
  def advanceRef(ref: String): Unit = synchronized { currentRef = ref }

  def commit(request: Request): Response = mutate(request)
  def createPullRequest(request: Request): Response = mutate(request)
  def merge(request: Request): Response = mutate(request)

  def pullRequestStatus(id: String): Option[PullRequestState] = synchronized {
    pullRequests.get(id)
  }

  private def mutate(request: Request): Response = synchronized {
    if uncertain.contains(request.operationId) then
      return Response(
        request.operationId,
        Outcome.ReconcileRequired,
        snapshot,
        request.pullRequestId
      )

    operations.get(request.operationId) match
      case Some(previous) =>
        return Response(
          request.operationId,
          Outcome.Duplicate,
          snapshot,
          previous.pullRequestId
        )
      case None => ()

    // This read-only comparison is required immediately before each mutation.
    val before = snapshot
    if request.preconditions.expectedRef != before.ref then
      return Response(
        request.operationId,
        Outcome.Stale,
        before,
        request.pullRequestId
      )

    val fault = nextFault
    nextFault = FaultMode.None
    if fault == FaultMode.StaleRef then
      currentRef = s"${currentRef}-advanced"
      return Response(
        request.operationId,
        Outcome.Stale,
        snapshot,
        request.pullRequestId
      )
    if fault == FaultMode.Conflict then
      return Response(
        request.operationId,
        Outcome.Conflict,
        snapshot,
        request.pullRequestId
      )
    if fault == FaultMode.Duplicate then
      return Response(
        request.operationId,
        Outcome.Duplicate,
        snapshot,
        request.pullRequestId
      )

    val applied = applyRequest(request, before)
    if applied.outcome != Outcome.Applied then applied
    else if fault == FaultMode.UnknownOutcome then
      val uncertainResponse = applied.copy(outcome = Outcome.TimedOutUncertain)
      operations = operations.updated(request.operationId, uncertainResponse)
      uncertain += request.operationId
      uncertainResponse
    else
      operations = operations.updated(request.operationId, applied)
      applied
  }

  private def applyRequest(request: Request, before: Snapshot): Response =
    request.kind match
      case OperationKind.Commit =>
        request.tree match
          case None => Response(request.operationId, Outcome.Rejected, before)
          case Some(tree) =>
            currentRef = tree
            Response(request.operationId, Outcome.Applied, snapshot)
      case OperationKind.PullRequest =>
        val id = request.pullRequestId.getOrElse(s"pr-${nextPullRequest}")
        if pullRequests.contains(id) then
          Response(request.operationId, Outcome.Conflict, before, Some(id))
        else
          nextPullRequest += 1
          pullRequests = pullRequests.updated(id, PullRequestState.Open)
          Response(request.operationId, Outcome.Applied, snapshot, Some(id))
      case OperationKind.Merge =>
        request.pullRequestId.flatMap(pullRequests.get) match
          case None =>
            Response(
              request.operationId,
              Outcome.Rejected,
              before,
              request.pullRequestId
            )
          case Some(PullRequestState.Merged) =>
            Response(
              request.operationId,
              Outcome.AlreadyApplied,
              before,
              request.pullRequestId
            )
          case Some(PullRequestState.Closed) =>
            Response(
              request.operationId,
              Outcome.Conflict,
              before,
              request.pullRequestId
            )
          case Some(PullRequestState.Open) =>
            pullRequests = pullRequests.updated(
              request.pullRequestId.get,
              PullRequestState.Merged
            )
            currentRef = s"${currentRef}-merged"
            Response(
              request.operationId,
              Outcome.Applied,
              snapshot,
              request.pullRequestId
            )
