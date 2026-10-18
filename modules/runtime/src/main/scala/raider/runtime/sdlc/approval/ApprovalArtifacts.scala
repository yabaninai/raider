package raider.runtime.sdlc.approval

import raider.core.result.ExitCodes
import raider.core.sdlc.SdlcEvidence.CandidateIdentity
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

/** Approval is evidence for a checkpoint, never a capability or suspended run.
  */
object ApprovalArtifacts:
  val SchemaVersion = 2

  final case class Request(
      checkpointId: String,
      candidateDigest: String,
      expiresAt: String
  )

  object Request:
    given JsonCodec[Request] = DeriveJsonCodec.gen

  final case class RequestCarrier(
      schemaVersion: Int,
      `type`: String,
      id: String,
      data: Request
  )

  object RequestCarrier:
    given JsonCodec[RequestCarrier] = DeriveJsonCodec.gen

  final case class Decision(
      schemaVersion: Int,
      checkpointId: String,
      candidateDigest: String,
      expiresAt: String,
      approved: Boolean
  )

  object Decision:
    given JsonCodec[Decision] = DeriveJsonCodec.gen

  final case class CurrentState(
      candidate: CandidateIdentity,
      policyDigest: String,
      repositoryStateDigest: String,
      taskDigest: String,
      baseRevision: Option[String],
      evidenceDigests: List[String],
      evidenceCandidateDigest: String,
      ownerCapabilities: Set[String]
  )

  enum Refusal:
    case InvalidRequest(detail: String)
    case MissingDecision
    case InvalidDecision(detail: String)
    case Denied
    case Expired
    case CandidateMismatch
    case PolicyChanged
    case RepositoryChanged
    case TaskOrBaseChanged
    case EvidenceChanged
    case CapabilityMissing

  object Refusal:
    given JsonCodec[Refusal] = DeriveJsonCodec.gen

  final case class Authorized(candidateDigest: String, checkpointId: String)

  final case class NeedsApproval(
      request: Request,
      exitCode: Int = ExitCodes.NeedsApproval
  )

  /** Create the headless checkpoint artifact. Callers return NeedsApproval (25)
    * and stop; this function never blocks for input or retains a fiber.
    */
  def request(
      checkpointId: String,
      candidate: CandidateIdentity,
      expiresAt: Instant
  ): Either[Refusal, Request] =
    val digest = CandidateIdentity.digest(candidate)
    if checkpointId.trim.isEmpty then
      Left(Refusal.InvalidRequest("empty checkpoint id"))
    else if CandidateIdentity.validate(candidate).isLeft then
      Left(Refusal.InvalidRequest("invalid candidate identity"))
    else if !expiresAt.isAfter(Instant.EPOCH) then
      Left(Refusal.InvalidRequest("expiry must be after epoch"))
    else Right(Request(checkpointId, digest, expiresAt.toString))

  def validateRequest(value: Request): Either[Refusal, Unit] =
    if value.checkpointId.trim.isEmpty || !isDigest(value.candidateDigest) then
      Left(Refusal.InvalidRequest("invalid checkpoint or candidate digest"))
    else parseInstant(value.expiresAt).map(_ => ())

  def validateDecisionFile(
      request: Request,
      decision: Decision,
      now: Instant,
      current: CurrentState,
      requiredCapability: String
  ): Either[Refusal, Authorized] =
    import Refusal.*
    for
      _ <- validateRequest(request)
      _ <- Either.cond(
        decision.schemaVersion == SchemaVersion,
        (),
        InvalidDecision("unsupported schema version")
      )
      decisionExpiry <- parseInstant(decision.expiresAt).left.map(_ =>
        InvalidDecision("invalid expiry")
      )
      requestExpiry <- parseInstant(request.expiresAt).left.map(_ =>
        InvalidRequest("invalid expiry")
      )
      _ <- Either.cond(
        decision.checkpointId == request.checkpointId && decision.candidateDigest == request.candidateDigest && decision.expiresAt == request.expiresAt,
        (),
        InvalidDecision("decision does not match request")
      )
      _ <- Either.cond(
        now.isBefore(requestExpiry) && now.isBefore(decisionExpiry),
        (),
        Expired
      )
      _ <- Either.cond(decision.approved, (), Denied)
      currentDigest = CandidateIdentity.digest(current.candidate)
      _ <- Either.cond(
        currentDigest == request.candidateDigest,
        (),
        CandidateMismatch
      )
      _ <- Either.cond(
        current.candidate.policyDigest == current.policyDigest,
        (),
        PolicyChanged
      )
      _ <- Either.cond(
        current.candidate.treeDigest == current.repositoryStateDigest,
        (),
        RepositoryChanged
      )
      _ <- Either.cond(
        current.candidate.taskDigest == current.taskDigest && current.candidate.baseRevision == current.baseRevision,
        (),
        TaskOrBaseChanged
      )
      _ <- Either.cond(
        current.evidenceDigests.nonEmpty && current.evidenceCandidateDigest == currentDigest,
        (),
        EvidenceChanged
      )
      _ <- Either.cond(
        current.ownerCapabilities(requiredCapability),
        (),
        CapabilityMissing
      )
    yield Authorized(currentDigest, request.checkpointId)

  /** New-invocation entry point: load all current inputs again for every call.
    */
  def recheck(
      request: Request,
      decisionJson: Option[String],
      now: Instant,
      readCurrent: () => Either[Refusal, CurrentState],
      requiredCapability: String
  ): Either[Refusal, Authorized] =
    for
      raw <- decisionJson.toRight(Refusal.MissingDecision)
      decision <- decodeDecision(raw)
      current <- readCurrent()
      authorization <- validateDecisionFile(
        request,
        decision,
        now,
        current,
        requiredCapability
      )
    yield authorization

  def writeRequest(path: Path, value: Request): Either[Refusal, Unit] =
    validateRequest(value).flatMap { _ =>
      try
        Files.createDirectories(path.toAbsolutePath.getParent)
        val temporary = path.resolveSibling(path.getFileName.toString + ".tmp")
        val carrier = RequestCarrier(
          SchemaVersion,
          "approval-request",
          value.checkpointId,
          value
        )
        Files.writeString(temporary, carrier.toJson, UTF_8)
        Files.move(
          temporary,
          path,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
        Right(())
      catch
        case e: Exception =>
          Left(
            Refusal.InvalidRequest(
              s"request artifact write failed: ${e.getClass.getSimpleName}"
            )
          )
    }

  /** Headless checkpoint behavior: persist the request and return the stable
    * NeedsApproval outcome. No stdin read, wait, auto-approval, or
    * continuation.
    */
  def headlessCheckpoint(
      path: Path,
      checkpointId: String,
      candidate: CandidateIdentity,
      expiresAt: Instant
  ): Either[Refusal, NeedsApproval] =
    for
      value <- request(checkpointId, candidate, expiresAt)
      _ <- writeRequest(path, value)
    yield NeedsApproval(value)

  def decisionJson(value: Decision): String = value.toJson

  def decodeRequestArtifact(input: String): Either[Refusal, Request] =
    input
      .fromJson[RequestCarrier]
      .left
      .map(Refusal.InvalidRequest.apply)
      .flatMap { carrier =>
        if carrier.schemaVersion != SchemaVersion || carrier.`type` != "approval-request"
        then Left(Refusal.InvalidRequest("unsupported request carrier"))
        else if carrier.id != carrier.data.checkpointId then
          Left(Refusal.InvalidRequest("carrier id does not match checkpoint"))
        else validateRequest(carrier.data).map(_ => carrier.data)
      }

  private def decodeDecision(input: String): Either[Refusal, Decision] =
    Json.decoder.decodeJson(input) match
      case Right(Json.Obj(fields)) =>
        val allowed = Set(
          "schemaVersion",
          "checkpointId",
          "candidateDigest",
          "expiresAt",
          "approved"
        )
        fields.map(_._1).find(field => !allowed(field)) match
          case Some(field) =>
            Left(Refusal.InvalidDecision(s"unknown field: $field"))
          case None =>
            input.fromJson[Decision].left.map(Refusal.InvalidDecision.apply)
      case Right(_) =>
        Left(Refusal.InvalidDecision("decision must be a JSON object"))
      case Left(detail) => Left(Refusal.InvalidDecision(detail))

  private def isDigest(value: String): Boolean = value.matches("[0-9a-f]{64}")

  private def parseInstant(value: String): Either[Refusal, Instant] =
    try Right(Instant.parse(value))
    catch case _: Exception => Left(Refusal.InvalidRequest("invalid expiry"))
