package raider.testkit.sdlc.forge

import ForgeProtocol.*
import zio.test.*

object LocalForgeStandSpec extends ZIOSpecDefault:

  private def request(
      id: String,
      kind: OperationKind,
      expected: String,
      tree: Option[String] = None,
      pr: Option[String] = None
  ): Request = Request(OperationId(id), kind, Preconditions(expected), tree, pr)

  def spec = suite("SDLC-FORGE generic protocol stand")(
    test(
      "SDLC-FORGE-01: commit, PR create/status and merge return typed applied outcomes"
    ) {
      val stand = LocalForgeStand("base")
      val commit = stand.commit(
        request("commit-1", OperationKind.Commit, "base", Some("candidate"))
      )
      val opened = stand.createPullRequest(
        request("pr-1", OperationKind.PullRequest, "candidate")
      )
      val prId = opened.pullRequestId.get
      val status = stand.pullRequestStatus(prId)
      val merged = stand.merge(
        request("merge-1", OperationKind.Merge, "candidate", pr = Some(prId))
      )
      assertTrue(
        commit.outcome == Outcome.Applied,
        commit.snapshot.ref == "candidate",
        opened.outcome == Outcome.Applied,
        status.contains(PullRequestState.Open),
        merged.outcome == Outcome.Applied,
        stand.pullRequestStatus(prId).contains(PullRequestState.Merged)
      )
    },
    test("SDLC-FORGE-02a: stale ref and conflict are typed non-successes") {
      val stand = LocalForgeStand("base")
      stand.inject(FaultMode.StaleRef)
      val stale = stand.commit(
        request("stale", OperationKind.Commit, "base", Some("candidate"))
      )
      stand.inject(FaultMode.Conflict)
      val conflict = stand.createPullRequest(
        request("conflict", OperationKind.PullRequest, stale.snapshot.ref)
      )
      assertTrue(
        stale.outcome == Outcome.Stale,
        conflict.outcome == Outcome.Conflict
      )
    },
    test(
      "SDLC-FORGE-02b: duplicate correlation is typed and does not repeat a mutation"
    ) {
      val stand = LocalForgeStand("base")
      val first =
        request("same-id", OperationKind.Commit, "base", Some("candidate"))
      val applied = stand.commit(first)
      val repeated = stand.commit(first)
      assertTrue(
        applied.outcome == Outcome.Applied,
        repeated.outcome == Outcome.Duplicate,
        repeated.snapshot.ref == "candidate"
      )
    },
    test(
      "SDLC-FORGE-02c: unknown-after-effect is terminal until read-only reconcile"
    ) {
      val stand = LocalForgeStand("base")
      val operation =
        request("uncertain", OperationKind.Commit, "base", Some("candidate"))
      stand.inject(FaultMode.UnknownOutcome)
      val timedOut = stand.commit(operation)
      val blindRetry = stand.commit(operation)
      val reconciled = stand.reconcile(operation.operationId)
      val retryAfterReconcile = stand.commit(operation)
      assertTrue(
        timedOut.outcome == Outcome.TimedOutUncertain,
        timedOut.snapshot.ref == "candidate",
        blindRetry.outcome == Outcome.ReconcileRequired,
        reconciled.outcome == Outcome.AlreadyApplied,
        retryAfterReconcile.outcome == Outcome.Duplicate,
        stand.reconcile(operation.operationId).snapshot.ref == "candidate"
      )
    },
    test(
      "SDLC-FORGE-03: stale state is read-only reconciled before a fresh retry"
    ) {
      val stand = LocalForgeStand("base")
      stand.inject(FaultMode.StaleRef)
      val stale = stand.commit(
        request(
          "first-attempt",
          OperationKind.Commit,
          "base",
          Some("old-candidate")
        )
      )
      // Reconciliation observes the advanced ref; the next mutation uses a new identity and fresh precondition.
      val observed = stand.reconcile(OperationId("first-attempt"))
      val retried = stand.commit(
        request(
          "fresh-attempt",
          OperationKind.Commit,
          observed.snapshot.ref,
          Some("new-candidate")
        )
      )
      assertTrue(
        stale.outcome == Outcome.Stale,
        observed.snapshot.ref == "base-advanced",
        retried.outcome == Outcome.Applied,
        retried.snapshot.ref == "new-candidate"
      )
    }
  )
