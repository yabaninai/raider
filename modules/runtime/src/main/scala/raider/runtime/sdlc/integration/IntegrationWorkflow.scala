package raider.runtime.sdlc.integration

import raider.core.sdlc.SdlcEvidence
import raider.core.sdlc.SdlcEvidence.*

/** Workflow-owned rules are the only authority for integration requirements. */
final case class IntegrationRules(
    targetBranch: String,
    requiredChecks: Set[String],
    reviewRequired: Boolean,
    policyDigest: String,
    capabilities: Set[String]
)

final case class IntegrationCandidate(
    identity: CandidateIdentity,
    checks: List[CheckResult],
    reviews: List[ReviewVerdict]
)

enum Verb:
  case Commit, PullRequestCreate, PullRequestStatus, Merge

enum Refusal:
  case MissingChecks(ids: Set[String])
  case MissingReview
  case CapabilityDenied(capability: String)
  case StaleRef(expected: String, observed: String)
  case Conflict(detail: String)
  case Duplicate(operationId: String)
  case UnknownOutcome(operationId: String)
  case ForgeRejected(outcome: String)

final case class ForgeSnapshot(
    repository: String,
    targetBranch: String,
    targetRef: String,
    headRef: String,
    candidateDigest: Option[String],
    pullRequests: Map[String, String],
    policyDigest: String,
    capabilities: Set[String]
)

final case class ForgeMutation(
    operationId: String,
    verb: Verb,
    expected: ForgeSnapshot,
    candidateDigest: String,
    pullRequestId: Option[String]
)

enum ForgeOutcome:

  case Applied, AlreadyApplied, Rejected, Stale, Conflict, Duplicate, Denied,
    Failed, TimedOutUncertain, Unknown, ReconcileRequired

final case class ForgeResponse(
    operationId: String,
    outcome: ForgeOutcome,
    snapshot: ForgeSnapshot,
    pullRequestId: Option[String] = None
)

/** Narrow forge boundary; reconcile is observational and mutations are
  * single-shot.
  */
trait ForgePort:
  def reconcile(): Either[String, ForgeSnapshot]
  def mutate(request: ForgeMutation): ForgeResponse
  def pullRequestStatus(id: String): Either[String, Option[String]]

final case class StageRecord(
    verb: Verb,
    candidate: CandidateIdentity,
    result: IntegrationResult,
    refusal: Option[Refusal],
    pullRequestId: Option[String] = None
)

final case class IntegrationRun(
    records: List[StageRecord],
    pullRequestId: Option[String]
)

/** Bounded commit → PR-create → PR-status → merge orchestration. Each attempted
  * mutation is preceded by a fresh read and is issued at most once.
  */
final class IntegrationWorkflow(port: ForgePort, rules: IntegrationRules):

  private def preconditions(
      candidate: IntegrationCandidate
  ): Either[Refusal, Unit] =
    val digest = CandidateIdentity.digest(candidate.identity)
    val passed = candidate.checks
      .filter(r =>
        r.candidate == candidate.identity && r.policyDigest == rules.policyDigest &&
          r.outcome == CheckOutcome.Passed
      )
      .map(_.checkId)
      .toSet
    val missing = rules.requiredChecks -- passed
    if missing.nonEmpty then Left(Refusal.MissingChecks(missing))
    else if rules.reviewRequired && !candidate.reviews.exists(r =>
        r.candidate == candidate.identity && r.taskDigest == candidate.identity.taskDigest &&
          r.planDigest == candidate.identity.planDigest &&
          r.policyDigest == rules.policyDigest && r.verdict == ReviewDecision.Acceptable
      )
    then Left(Refusal.MissingReview)
    else if !SdlcEvidence.checksApply(
        candidate.identity,
        candidate.checks.filter(r => rules.requiredChecks.contains(r.checkId))
      ) && rules.requiredChecks.nonEmpty
    then Left(Refusal.MissingChecks(rules.requiredChecks))
    else if digest.isEmpty then
      Left(Refusal.Conflict("invalid candidate identity"))
    else Right(())

  private def integrationResult(
      operationId: String,
      verb: Verb,
      candidate: CandidateIdentity,
      expected: Option[ForgeSnapshot],
      observed: Option[ForgeSnapshot],
      outcome: IntegrationOutcome,
      evidence: List[String]
  ): IntegrationResult =
    IntegrationResult(
      operationId,
      verb.toString,
      candidate,
      rules.targetBranch,
      expected.map(snapshotDigest).getOrElse("precondition-refused"),
      observed.map(snapshotDigest),
      outcome,
      evidence,
      rules.policyDigest
    )

  private def snapshotDigest(s: ForgeSnapshot): String =
    List(
      s.repository,
      s.targetBranch,
      s.targetRef,
      s.headRef,
      s.candidateDigest.getOrElse(""),
      s.policyDigest,
      s.capabilities.toList.sorted.mkString(","),
      s.pullRequests.toList.sorted.mkString(",")
    ).mkString("|")

  private def refused(
      verb: Verb,
      candidate: CandidateIdentity,
      id: String,
      reason: Refusal,
      expected: Option[ForgeSnapshot] = None,
      observed: Option[ForgeSnapshot] = None
  ): StageRecord =
    val outcome = reason match
      case Refusal.StaleRef(_, _)      => IntegrationOutcome.Stale
      case Refusal.Conflict(_)         => IntegrationOutcome.Conflict
      case Refusal.Duplicate(_)        => IntegrationOutcome.Conflict
      case Refusal.UnknownOutcome(_)   => IntegrationOutcome.Unknown
      case Refusal.CapabilityDenied(_) => IntegrationOutcome.Denied
      case _                           => IntegrationOutcome.Rejected
    StageRecord(
      verb,
      candidate,
      integrationResult(id, verb, candidate, expected, observed, outcome, Nil),
      Some(reason)
    )

  private def runMutation(
      candidate: IntegrationCandidate,
      verb: Verb,
      id: String,
      prId: Option[String]
  ): Either[StageRecord, StageRecord] =
    preconditions(candidate).left
      .map(refused(verb, candidate.identity, id, _))
      .flatMap { _ =>
        val capability = verb match
          case Verb.Commit            => "commit"
          case Verb.PullRequestCreate => "pull-request"
          case Verb.Merge             => "merge"
          case Verb.PullRequestStatus => ""
        if !rules.capabilities.contains(capability) then
          Left(
            refused(
              verb,
              candidate.identity,
              id,
              Refusal.CapabilityDenied(capability)
            )
          )
        else
          port.reconcile() match
            case Left(detail) =>
              Left(
                refused(verb, candidate.identity, id, Refusal.Conflict(detail))
              )
            case Right(fresh) =>
              val candidateDigest = CandidateIdentity.digest(candidate.identity)
              val expectedTarget =
                candidate.identity.baseRevision.getOrElse(fresh.targetRef)
              if fresh.repository != candidate.identity.repository ||
                fresh.targetBranch != rules.targetBranch ||
                fresh.policyDigest != rules.policyDigest ||
                fresh.capabilities != rules.capabilities ||
                fresh.targetRef != expectedTarget ||
                fresh.candidateDigest.exists(_ != candidateDigest) ||
                candidate.identity.headRevision.exists(_ != fresh.headRef)
              then
                Left(
                  refused(
                    verb,
                    candidate.identity,
                    id,
                    Refusal.StaleRef(expectedTarget, fresh.targetRef),
                    Some(fresh),
                    Some(fresh)
                  )
                )
              else
                val response = port.mutate(
                  ForgeMutation(id, verb, fresh, candidateDigest, prId)
                )
                response.outcome match
                  case ForgeOutcome.Applied | ForgeOutcome.AlreadyApplied =>
                    val typedOutcome = response.outcome match
                      case ForgeOutcome.AlreadyApplied =>
                        IntegrationOutcome.AlreadyApplied
                      case _ => IntegrationOutcome.Applied
                    Right(
                      StageRecord(
                        verb,
                        candidate.identity,
                        integrationResult(
                          id,
                          verb,
                          candidate.identity,
                          Some(fresh),
                          Some(response.snapshot),
                          typedOutcome,
                          candidate.checks.map(checkDigest) ++ candidate.reviews
                            .map(reviewDigest)
                        ),
                        None,
                        response.pullRequestId
                      )
                    )
                  case ForgeOutcome.Stale =>
                    Left(
                      refused(
                        verb,
                        candidate.identity,
                        id,
                        Refusal.StaleRef(
                          fresh.targetRef,
                          response.snapshot.targetRef
                        ),
                        Some(fresh),
                        Some(response.snapshot)
                      )
                    )
                  case ForgeOutcome.Conflict =>
                    Left(
                      refused(
                        verb,
                        candidate.identity,
                        id,
                        Refusal.Conflict(
                          "forge state conflicts with requested operation"
                        ),
                        Some(fresh),
                        Some(response.snapshot)
                      )
                    )
                  case ForgeOutcome.Duplicate =>
                    Left(
                      refused(
                        verb,
                        candidate.identity,
                        id,
                        Refusal.Duplicate(id),
                        Some(fresh),
                        Some(response.snapshot)
                      )
                    )
                  case ForgeOutcome.TimedOutUncertain | ForgeOutcome.Unknown |
                      ForgeOutcome.ReconcileRequired =>
                    // Unknown results are reconciled read-only; they are never retried here.
                    val observed = port.reconcile().toOption
                    Left(
                      refused(
                        verb,
                        candidate.identity,
                        id,
                        Refusal.UnknownOutcome(id),
                        Some(fresh),
                        observed
                      )
                    )
                  case other =>
                    Left(
                      refused(
                        verb,
                        candidate.identity,
                        id,
                        Refusal.ForgeRejected(other.toString),
                        Some(fresh),
                        Some(response.snapshot)
                      )
                    )
      }

  private def executeLocked(
      candidate: IntegrationCandidate,
      operationPrefix: String
  ): IntegrationRun =
    val stages = List(
      Verb.Commit,
      Verb.PullRequestCreate,
      Verb.PullRequestStatus,
      Verb.Merge
    )
    var records = List.empty[StageRecord]
    var prId: Option[String] = None
    var stopped = false
    stages.zipWithIndex.foreach { case (verb, index) =>
      if !stopped then
        if verb == Verb.PullRequestStatus then
          val id = prId.getOrElse("")
          port.reconcile() match
            case Left(detail) =>
              records :+= refused(
                verb,
                candidate.identity,
                s"$operationPrefix-status",
                Refusal.Conflict(detail)
              ); stopped = true
            case Right(snapshot) =>
              port.pullRequestStatus(id) match
                case Right(Some(_)) =>
                  records :+= StageRecord(
                    verb,
                    candidate.identity,
                    integrationResult(
                      s"$operationPrefix-status",
                      verb,
                      candidate.identity,
                      Some(snapshot),
                      Some(snapshot),
                      IntegrationOutcome.Applied,
                      Nil
                    ),
                    None
                  )
                case _ =>
                  records :+= refused(
                    verb,
                    candidate.identity,
                    s"$operationPrefix-status",
                    Refusal.Conflict("pull request status unavailable"),
                    Some(snapshot),
                    Some(snapshot)
                  ); stopped = true
        else
          runMutation(
            candidate,
            verb,
            s"$operationPrefix-${index + 1}",
            prId
          ) match
            case Left(record) => records :+= record; stopped = true
            case Right(record) =>
              records :+= record
              if verb == Verb.PullRequestCreate then prId = record.pullRequestId
    }
    IntegrationRun(records, prId)

  /** A workflow instance is the single integration owner for its repository. */
  def execute(
      candidate: IntegrationCandidate,
      operationPrefix: String
  ): IntegrationRun = synchronized {
    executeLocked(candidate, operationPrefix)
  }

  private def checkDigest(value: CheckResult): String =
    s"${value.checkId}:${value.definitionDigest}:${value.outputDigest}:${CandidateIdentity.digest(value.candidate)}"

  private def reviewDigest(value: ReviewVerdict): String =
    s"${value.reviewerIdentity}:${value.findingsDigest}:${CandidateIdentity.digest(value.candidate)}"
