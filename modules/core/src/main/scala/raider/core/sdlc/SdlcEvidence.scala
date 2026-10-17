package raider.core.sdlc

import zio.json.*

/** Stable, versioned evidence records for the accepted ADR-030 D4 contract. */
object SdlcEvidence:
  val schemaVersion: Int = 1

  final case class CandidateIdentity(
      repository: String,
      worktree: String,
      baseRevision: Option[String],
      headRevision: Option[String],
      revision: Option[String],
      treeDigest: String,
      taskDigest: String,
      planDigest: String,
      policyDigest: String,
      configDigest: String
  )

  object CandidateIdentity:
    given JsonCodec[CandidateIdentity] = DeriveJsonCodec.gen

    def validate(value: CandidateIdentity): Either[String, Unit] =
      val required = List(
        value.repository,
        value.worktree,
        value.treeDigest,
        value.taskDigest,
        value.planDigest,
        value.policyDigest,
        value.configDigest
      )
      if required.exists(_.trim.isEmpty) then
        Left("candidate identity contains an empty required value")
      else Right(())

  enum CheckOutcome:
    case Passed, Failed, Error, Skipped

  object CheckOutcome:
    given JsonCodec[CheckOutcome] = DeriveJsonCodec.gen

  final case class CheckResult(
      checkId: String,
      definitionDigest: String,
      argvDigest: String,
      outcome: CheckOutcome,
      exitCode: Option[Int],
      toolchainIdentity: String,
      verifierIdentity: String,
      candidate: CandidateIdentity,
      outputDigest: String,
      policyDigest: String
  )

  object CheckResult:
    given JsonCodec[CheckResult] = DeriveJsonCodec.gen

  enum ReviewDecision:
    case Acceptable, ChangesRequested, Rejected, Incomplete

  object ReviewDecision:
    given JsonCodec[ReviewDecision] = DeriveJsonCodec.gen

  final case class ReviewVerdict(
      reviewerIdentity: String,
      verdict: ReviewDecision,
      findingsDigest: String,
      taskDigest: String,
      planDigest: String,
      candidate: CandidateIdentity,
      checkResultDigests: List[String],
      policyDigest: String
  )

  object ReviewVerdict:
    given JsonCodec[ReviewVerdict] = DeriveJsonCodec.gen

  enum IntegrationOutcome:

    case Applied, AlreadyApplied, Rejected, Stale, Conflict, Denied, Failed,
      TimedOutUncertain, Unknown

  object IntegrationOutcome:
    given JsonCodec[IntegrationOutcome] = DeriveJsonCodec.gen

  final case class IntegrationResult(
      operationId: String,
      operationKind: String,
      candidate: CandidateIdentity,
      targetIdentity: String,
      expectedPreconditionsDigest: String,
      observedStateDigest: Option[String],
      outcome: IntegrationOutcome,
      evidenceDigests: List[String],
      policyDigest: String
  )

  object IntegrationResult:
    given JsonCodec[IntegrationResult] = DeriveJsonCodec.gen

  /** Evidence is valid only for exact candidate and policy identities. */
  def checksApply(
      candidate: CandidateIdentity,
      results: List[CheckResult]
  ): Boolean =
    results.nonEmpty && results.forall(r =>
      r.candidate == candidate &&
        r.policyDigest == candidate.policyDigest && r.outcome == CheckOutcome.Passed
    )

  def reviewApplies(
      candidate: CandidateIdentity,
      verdict: ReviewVerdict
  ): Boolean =
    verdict.candidate == candidate && verdict.taskDigest == candidate.taskDigest &&
      verdict.planDigest == candidate.planDigest && verdict.policyDigest == candidate.policyDigest

  /** JSON projection is versioned and intended to be embedded as an SDLC
    * artifact payload.
    */
  final case class Artifact(schemaVersion: Int, kind: String, payload: String)

  object Artifact:
    given JsonCodec[Artifact] = DeriveJsonCodec.gen

  def artifact[A: JsonEncoder](kind: String, value: A): String =
    Artifact(schemaVersion, kind, value.toJson).toJson

  def decodeArtifact(json: String): Either[String, Artifact] =
    json.fromJson[Artifact].left.map(identity).flatMap { value =>
      if value.schemaVersion != schemaVersion then
        Left(s"unsupported SDLC evidence schema ${value.schemaVersion}")
      else if value.kind.trim.isEmpty then
        Left("evidence kind must be non-empty")
      else Right(value)
    }

end SdlcEvidence
