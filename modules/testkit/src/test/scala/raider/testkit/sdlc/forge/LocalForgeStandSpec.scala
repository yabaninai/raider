package raider.testkit.sdlc.forge

import ForgeProtocol.*
import zio.test.*
import zio.test.TestClock

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
      "SDLC-FORGE-02f: unknown result blocks retry until read-only reconcile observes no effect"
    ) {
      val stand = LocalForgeStand("base")
      val operation =
        request(
          "unknown-result",
          OperationKind.Commit,
          "base",
          Some("candidate")
        )
      stand.inject(FaultMode.UnknownResult)
      val unknown = stand.commit(operation)
      val blindRetry = stand.commit(operation)
      val reconciled = stand.reconcile(operation.operationId)
      val retryAfterReconcile = stand.commit(operation)
      assertTrue(
        unknown.outcome == Outcome.Unknown,
        unknown.snapshot.ref == "base",
        blindRetry.outcome == Outcome.ReconcileRequired,
        reconciled.outcome == Outcome.Rejected,
        reconciled.snapshot.ref == "base",
        retryAfterReconcile.outcome == Outcome.Duplicate
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
    },
    test(
      "SDLC-FORGE-02e: asynchronous operation stays pending before timeout and reconciles"
    ) {
      val stand = LocalForgeStand("base")
      val operation = request(
        "async-timeout",
        OperationKind.Commit,
        "base",
        Some("candidate")
      )
      for
        fiber <- stand.commitAsync(operation, zio.Duration.fromMillis(200)).fork
        _ <- TestClock.adjust(zio.Duration.fromMillis(50))
        pending = stand.asyncState(operation.operationId)
        _ <- TestClock.adjust(zio.Duration.fromMillis(200))
        timedOut <- fiber.join
        blocked = stand.commit(operation)
        reconciled = stand.reconcile(operation.operationId)
      yield assertTrue(
        pending.contains(AsyncState.Pending),
        timedOut.outcome == Outcome.TimedOutUncertain,
        timedOut.snapshot.ref == "candidate",
        blocked.outcome == Outcome.ReconcileRequired,
        reconciled.outcome == Outcome.AlreadyApplied
      )
    },
    test(
      "SDLC-FORGE-02d: disposable Git commit, PR status, merge, and stale real ref"
    ) {
      zio.ZIO.succeed {
        val stand = DisposableGitForgeStand.create()
        try {
          val base = stand.refs("main")
          val committed = stand.commit(
            request(
              "git-commit",
              OperationKind.Commit,
              base,
              Some("candidate tree")
            )
          )
          val candidate = stand.refs("main")
          val opened = stand.createPullRequest(
            request(
              "git-pr",
              OperationKind.PullRequest,
              candidate,
              pr = Some("review-1")
            )
          )
          val merge = stand.merge(
            request(
              "git-merge",
              OperationKind.Merge,
              candidate,
              pr = opened.pullRequestId
            )
          )
          val stale = stand.commit(
            request(
              "git-stale",
              OperationKind.Commit,
              candidate,
              Some("must-not-commit")
            )
          )
          val hasPrRef = stand.refs.contains("pr/review-1")
          val mergedRef = stand.refs("main")
          val prMerged = stand
            .pullRequestStatus("review-1")
            .contains(PullRequestState.Merged)
          val reconciledMerge =
            stand.reconcile(OperationId("git-merge")).outcome
          assertTrue(
            committed.outcome == Outcome.Applied,
            candidate != base,
            opened.outcome == Outcome.Applied,
            hasPrRef,
            merge.outcome == Outcome.Applied,
            prMerged,
            mergedRef != candidate,
            stale.outcome == Outcome.Stale,
            reconciledMerge == Outcome.AlreadyApplied
          )
        } finally stand.close()
      }
    },
    test(
      "SDLC-FORGE-03: competing owners serialize and one observes a stale ref"
    ) {
      val stand = LocalForgeStand("base")
      for responses <- zio.ZIO.foreachPar(List("owner-a", "owner-b")) { owner =>
          zio.ZIO.succeed {
            stand.commit(
              request(
                owner,
                OperationKind.Commit,
                "base",
                Some(s"candidate-$owner")
              )
            )
          }
        }
      yield assertTrue(
        responses.count(_.outcome == Outcome.Applied) == 1,
        responses.count(_.outcome == Outcome.Stale) == 1,
        stand
          .reconcile(
            responses.find(_.outcome == Outcome.Applied).get.operationId
          )
          .snapshot
          .ref
          .startsWith("candidate-owner-")
      )
    }
  )
