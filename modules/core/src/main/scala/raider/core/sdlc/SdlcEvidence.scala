package raider.core.sdlc

import zio.json.*

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

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

    /** Stable SHA-256 identity over a domain-tagged, length-prefixed UTF-8
      * encoding of every candidate field. Field order is the order below;
      * Option values have distinct presence markers, so absent and empty are
      * not conflated. This does not depend on JSON object ordering or a run ID.
      */
    def digest(value: CandidateIdentity): String =
      val fields = List(
        "repository" -> Some(value.repository),
        "worktree" -> Some(value.worktree),
        "baseRevision" -> value.baseRevision,
        "headRevision" -> value.headRevision,
        "revision" -> value.revision,
        "treeDigest" -> Some(value.treeDigest),
        "taskDigest" -> Some(value.taskDigest),
        "planDigest" -> Some(value.planDigest),
        "policyDigest" -> Some(value.policyDigest),
        "configDigest" -> Some(value.configDigest)
      )
      val bytes = new ByteArrayOutputStream()
      val out = new DataOutputStream(bytes)
      out.write("raider-sdlc-candidate-v1".getBytes(UTF_8))
      out.writeByte(0)
      fields.foreach { case (name, fieldValue) =>
        val nameBytes = name.getBytes(UTF_8)
        out.writeInt(nameBytes.length)
        out.write(nameBytes)
        fieldValue match
          case None => out.writeByte(0)
          case Some(text) =>
            out.writeByte(1)
            val textBytes = text.getBytes(UTF_8)
            out.writeInt(textBytes.length)
            out.write(textBytes)
      }
      out.flush()
      MessageDigest
        .getInstance("SHA-256")
        .digest(bytes.toByteArray)
        .map(b => f"${b & 0xff}%02x")
        .mkString

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

  enum ArtifactDecodeError:
    case InvalidJson(detail: String)
    case UnknownFields(fields: List[String])
    case UnsupportedSchemaVersion(version: Int)
    case EmptyKind

  object ArtifactDecodeError:
    given JsonCodec[ArtifactDecodeError] = DeriveJsonCodec.gen

  def artifact[A: JsonEncoder](kind: String, value: A): String =
    Artifact(schemaVersion, kind, value.toJson).toJson

  def decodeArtifact(json: String): Either[ArtifactDecodeError, Artifact] =
    import ArtifactDecodeError.*
    import zio.json.ast.Json
    Json.decoder.decodeJson(json).left.map(InvalidJson.apply).flatMap {
      case Json.Obj(fields) =>
        val known = Set("schemaVersion", "kind", "payload")
        val unknown = fields.map(_._1).filterNot(known).toList.sorted
        if unknown.nonEmpty then Left(UnknownFields(unknown))
        else
          json.fromJson[Artifact].left.map(InvalidJson.apply).flatMap { value =>
            if value.schemaVersion != schemaVersion then
              Left(UnsupportedSchemaVersion(value.schemaVersion))
            else if value.kind.trim.isEmpty then Left(EmptyKind)
            else Right(value)
          }
      case _ => Left(InvalidJson("evidence artifact must be a JSON object"))
    }

end SdlcEvidence
