package raider.runtime.sdlc.integration

import raider.core.sdlc.SdlcEvidence.*
import raider.runtime.sdlc.integration.*
import raider.testkit.sdlc.forge.ForgeProtocol.*
import raider.testkit.sdlc.forge.LocalForgeStand
import zio.test.*

/** Independent RAI-169 acceptance against the real testkit protocol stand. The
  * adapter is test-only: it translates the integration port to protocol
  * requests and keeps the stand, rather than a fake port, as mutation owner.
  */
object LocalForgeAcceptanceSpec extends ZIOSpecDefault:

  private val candidate = CandidateIdentity(
    "local-repository",
    "isolated-worktree",
    Some("base"),
    None,
    Some("candidate-tree"),
    "tree-digest",
    "task-digest",
    "plan-digest",
    "policy-digest",
    "config-digest"
  )

  private val check = CheckResult(
    "unit",
    "definition-digest",
    "argv-digest",
    CheckOutcome.Passed,
    Some(0),
    "scala-toolchain",
    "independent-verifier",
    candidate,
    "output-digest",
    "policy-digest"
  )

  private val review = ReviewVerdict(
    "independent-reviewer",
    ReviewDecision.Acceptable,
    "findings-digest",
    candidate.taskDigest,
    candidate.planDigest,
    candidate,
    List("unit-evidence"),
    candidate.policyDigest
  )

  private final class LocalStandPort(
      val stand: LocalForgeStand,
      fault: Option[(Verb, FaultMode)] = None
  ) extends ForgePort:
    private var operation = 0
    private var pullRequests = Map.empty[String, String]
    private var pendingFault = fault
    private var currentCandidate: Option[String] = None
    private var mergedTarget: Option[String] = None
    private var uncertainOperation: Option[OperationId] = None
    var trace = List.empty[String]
    var mutationVerbs = List.empty[Verb]
    var lastRequest: Option[Request] = None
    var blindRetryOutcome: Option[Outcome] = None

    private def nextReadId(): OperationId =
      operation += 1
      OperationId(s"adapter-read-$operation")

    private def toSnapshot(value: Snapshot): ForgeSnapshot =
      val pullStates = pullRequests.map { case (id, _) =>
        id -> stand.pullRequestStatus(id).fold("Missing")(_.toString)
      }
      ForgeSnapshot(
        "local-repository",
        "main",
        mergedTarget.getOrElse("base"),
        value.ref,
        currentCandidate,
        pullStates,
        "policy-digest",
        Set("commit", "pull-request", "merge")
      )

    def reconcile(): Either[String, ForgeSnapshot] =
      trace :+= "reconcile"
      val id = uncertainOperation.getOrElse(nextReadId())
      val observed = stand.reconcile(id)
      observed.outcome match
        case Outcome.AlreadyApplied | Outcome.Rejected =>
          uncertainOperation = None
        case _ => ()
      Right(toSnapshot(observed.snapshot))

    def mutate(request: ForgeMutation): ForgeResponse =
      trace :+= s"mutate:${request.verb}"
      mutationVerbs :+= request.verb
      val expected = request.expected.headRef
      val protocolId = OperationId(request.operationId)
      val kind = request.verb match
        case Verb.Commit            => OperationKind.Commit
        case Verb.PullRequestCreate => OperationKind.PullRequest
        case Verb.Merge             => OperationKind.Merge
        case Verb.PullRequestStatus => OperationKind.PullRequest
      val protocolRequest = Request(
        protocolId,
        kind,
        Preconditions(expected),
        tree =
          Option.when(request.verb == Verb.Commit)(request.candidateDigest),
        pullRequestId = request.pullRequestId
      )
      lastRequest = Some(protocolRequest)
      pendingFault.filter(_._1 == request.verb).foreach { case (_, mode) =>
        stand.inject(mode)
        pendingFault = None
      }
      val response = request.verb match
        case Verb.Commit            => stand.commit(protocolRequest)
        case Verb.PullRequestCreate => stand.createPullRequest(protocolRequest)
        case Verb.Merge             => stand.merge(protocolRequest)
        case Verb.PullRequestStatus =>
          Response(
            protocolId,
            Outcome.Rejected,
            stand.reconcile(nextReadId()).snapshot
          )
      response.outcome match
        case Outcome.Applied | Outcome.AlreadyApplied =>
          if request.verb == Verb.Commit then
            currentCandidate = Some(request.candidateDigest)
          if request.verb == Verb.Merge then
            mergedTarget = Some(response.snapshot.ref)
        case Outcome.TimedOutUncertain | Outcome.Unknown |
            Outcome.ReconcileRequired =>
          uncertainOperation = Some(protocolId)
        case _ => ()
      if response.outcome == Outcome.TimedOutUncertain ||
        response.outcome == Outcome.Unknown
      then
        blindRetryOutcome = Some(
          request.verb match
            case Verb.Commit => stand.commit(protocolRequest).outcome
            case Verb.PullRequestCreate =>
              stand.createPullRequest(protocolRequest).outcome
            case Verb.Merge             => stand.merge(protocolRequest).outcome
            case Verb.PullRequestStatus => Outcome.Rejected
        )
      response.pullRequestId.foreach(id => pullRequests += id -> id)
      ForgeResponse(
        request.operationId,
        response.outcome match
          case Outcome.Applied           => ForgeOutcome.Applied
          case Outcome.AlreadyApplied    => ForgeOutcome.AlreadyApplied
          case Outcome.Rejected          => ForgeOutcome.Rejected
          case Outcome.Stale             => ForgeOutcome.Stale
          case Outcome.Conflict          => ForgeOutcome.Conflict
          case Outcome.Duplicate         => ForgeOutcome.Duplicate
          case Outcome.Denied            => ForgeOutcome.Denied
          case Outcome.Failed            => ForgeOutcome.Failed
          case Outcome.TimedOutUncertain => ForgeOutcome.TimedOutUncertain
          case Outcome.Unknown           => ForgeOutcome.Unknown
          case Outcome.ReconcileRequired => ForgeOutcome.ReconcileRequired,
        toSnapshot(response.snapshot),
        response.pullRequestId
      )

    def pullRequestStatus(id: String): Either[String, Option[String]] =
      trace :+= "status"
      Right(stand.pullRequestStatus(id).map(_.toString))

    def retryLastRequest(): Option[Outcome] = lastRequest.map { request =>
      request.kind match
        case OperationKind.Commit => stand.commit(request).outcome
        case OperationKind.PullRequest =>
          stand.createPullRequest(request).outcome
        case OperationKind.Merge => stand.merge(request).outcome
    }

  private val rules = IntegrationRules(
    "main",
    Set("unit"),
    true,
    "policy-digest",
    Set("commit", "pull-request", "merge")
  )

  private def run(
      port: ForgePort,
      checks: List[CheckResult] = List(check),
      reviews: List[ReviewVerdict] = List(review),
      integrationRules: IntegrationRules = rules,
      prefix: String = "verify"
  ): IntegrationRun =
    IntegrationWorkflow(port, integrationRules).execute(
      IntegrationCandidate(candidate, checks, reviews),
      prefix
    )

  private def withPort[A](fault: Option[(Verb, FaultMode)] = None)(
      f: LocalStandPort => A
  ): A =
    val port = LocalStandPort(new LocalForgeStand("base"), fault)
    f(port)

  def spec = suite("RAI-169 independent SDLC acceptance")(
    test(
      "SDLC-V-01: commit, PR creation/status, and merge run over LocalForgeStand"
    ) {
      withPort() { port =>
        val result = run(port)
        val verbs = List(
          Verb.Commit,
          Verb.PullRequestCreate,
          Verb.PullRequestStatus,
          Verb.Merge
        )
        assertTrue(
          result.records.map(_.verb) == verbs,
          result.records.forall(_.refusal.isEmpty),
          result.records.forall(_.result.candidate == candidate),
          result.records.forall(_.result.policyDigest == rules.policyDigest),
          result.records
            .filter(record =>
              record.refusal.isEmpty && record.verb != Verb.PullRequestStatus
            )
            .forall(_.result.evidenceDigests.nonEmpty),
          result.pullRequestId.nonEmpty,
          port.mutationVerbs == List(
            Verb.Commit,
            Verb.PullRequestCreate,
            Verb.Merge
          ),
          port.trace.indexOf("reconcile") < port.trace.indexOf("mutate:Commit"),
          port.trace.indexWhere(
            _ == "reconcile",
            port.trace.indexOf("mutate:Commit") + 1
          ) <
            port.trace.indexOf("mutate:PullRequestCreate"),
          port.trace.indexWhere(
            _ == "reconcile",
            port.trace.indexOf("mutate:PullRequestCreate") + 1
          ) <
            port.trace.indexOf("mutate:Merge"),
          port.stand
            .pullRequestStatus(result.pullRequestId.get)
            .contains(PullRequestState.Merged)
        )
      }
    },
    test(
      "SDLC-V-02: missing checks, review, and owner capability refuse before any mutation"
    ) {
      withPort() { port =>
        val missingCheck = run(port, checks = Nil)
        val missingReview = run(port, reviews = Nil)
        val noCapability = run(
          port,
          integrationRules = rules.copy(capabilities = Set("merge"))
        )
        assertTrue(
          missingCheck.records.head.refusal
            .exists(_.isInstanceOf[Refusal.MissingChecks]),
          missingReview.records.head.refusal.contains(Refusal.MissingReview),
          noCapability.records.head.refusal
            .contains(Refusal.CapabilityDenied("commit")),
          port.mutationVerbs.isEmpty
        )
      }
    },
    test(
      "SDLC-V-02: stale-ref fault is a typed refusal with no subsequent mutation"
    ) {
      withPort(Some(Verb.Commit -> FaultMode.StaleRef)) { port =>
        val result = run(port)
        assertTrue(
          result.records.last.refusal.exists(_.isInstanceOf[Refusal.StaleRef]),
          port.mutationVerbs == List(Verb.Commit),
          port.trace.take(2) == List("reconcile", "mutate:Commit")
        )
      }
    },
    test(
      "SDLC-V-02: conflict and duplicate faults stop the workflow as typed refusals"
    ) {
      val conflict =
        withPort(Some(Verb.PullRequestCreate -> FaultMode.Conflict)) { port =>
          val result = run(port)
          (result.records.last.refusal, port.mutationVerbs)
        }
      val duplicate =
        withPort(Some(Verb.PullRequestCreate -> FaultMode.Duplicate)) { port =>
          val result = run(port)
          (result.records.last.refusal, port.mutationVerbs)
        }
      assertTrue(
        conflict._1.exists(_.isInstanceOf[Refusal.Conflict]),
        conflict._2 == List(Verb.Commit, Verb.PullRequestCreate),
        duplicate._1.exists(_.isInstanceOf[Refusal.Duplicate]),
        duplicate._2 == List(Verb.Commit, Verb.PullRequestCreate)
      )
    },
    test(
      "SDLC-V-03: uncertain and unknown results reconcile read-only and never retry"
    ) {
      val uncertain = withPort(Some(Verb.Commit -> FaultMode.UnknownOutcome)) {
        port =>
          val result = run(port)
          (
            result,
            port.mutationVerbs,
            port.trace,
            port.blindRetryOutcome,
            port.retryLastRequest()
          )
      }
      val unknown = withPort(Some(Verb.Commit -> FaultMode.UnknownResult)) {
        port =>
          val result = run(port)
          (
            result,
            port.mutationVerbs,
            port.trace,
            port.blindRetryOutcome,
            port.retryLastRequest()
          )
      }
      assertTrue(
        uncertain._1.records.last.refusal
          .exists(_.isInstanceOf[Refusal.UnknownOutcome]),
        uncertain._1.records.last.result.outcome == IntegrationOutcome.TimedOutUncertain,
        uncertain._2 == List(Verb.Commit),
        uncertain._3.count(_ == "mutate:Commit") == 1,
        uncertain._3.count(_ == "reconcile") == 2,
        uncertain._4.contains(Outcome.ReconcileRequired),
        uncertain._5.contains(Outcome.Duplicate),
        unknown._1.records.last.refusal
          .exists(_.isInstanceOf[Refusal.UnknownOutcome]),
        unknown._1.records.last.result.outcome == IntegrationOutcome.Unknown,
        unknown._2 == List(Verb.Commit),
        unknown._3.count(_ == "mutate:Commit") == 1,
        unknown._3.count(_ == "reconcile") == 2,
        unknown._4.contains(Outcome.ReconcileRequired),
        unknown._5.contains(Outcome.Duplicate)
      )
    }
  )
