package raider.cli.release

import raider.core.{Program, Task}
import raider.core.sdlc.{SdlcEvidence, SdlcStages}
import raider.runtime.sdlc.approval.{
  ApprovalArtifacts,
  CheckpointDispatch,
  CheckpointOutcome
}

import java.nio.file.Files
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Adapter from the typed SDLC stage contract to the local-only release
  * service.
  */
object LocalReleaseHandler:
  private val CheckpointId = "local-release"

  def apply(
      request: SdlcStages.Context => ReleaseStage.Request,
      dispatch: CheckpointDispatch
  ): SdlcStages.Handler =
    Program.fromFunction[SdlcStages.Context, SdlcStages.StageResult](
      "local-release"
    ) { context =>
      val authorization = dispatch.evaluate() match
        case CheckpointOutcome.Authorized(value) =>
          val digest = SdlcEvidence.CandidateIdentity.digest(context.candidate)
          if value.checkpointId != CheckpointId then
            Left(
              ApprovalArtifacts.Refusal.InvalidRequest(
                "checkpoint does not authorize local release"
              )
            )
          else if value.candidateDigest != digest then
            Left(ApprovalArtifacts.Refusal.CandidateMismatch)
          else if !value.expiresAt.isAfter(java.time.Instant.now()) then
            Left(ApprovalArtifacts.Refusal.Expired)
          else Right(digest)
        case CheckpointOutcome.NeedsApproval(_) =>
          Left(ApprovalArtifacts.Refusal.MissingDecision)
        case CheckpointOutcome.Refused(reason) => Left(reason)
      val result = for
        _ <- authorization
        digest <- SdlcStages.authorizeLocalRelease(context)
      yield digest
      result match
        case Left(refusal) =>
          Task.succeed(
            SdlcStages.StageResult(
              context,
              successful = false,
              s"release refused: $refusal"
            )
          )
        case Right(candidateDigest) =>
          val configured = request(context)
          val evidence = context.typedEvidence
          val checks = evidence.collect {
            case SdlcEvidence.Record.Check(value) => value
          }
          val review = evidence.collect {
            case SdlcEvidence.Record.Review(value) => value
          }
          val integration = evidence.collect {
            case SdlcEvidence.Record.Integration(value) => value
          }
          val accepted = ReleaseStage.AcceptanceEvidence(
            context.candidate.revision
              .orElse(context.candidate.headRevision)
              .getOrElse(""),
            checks.map(_.outputDigest).sorted.mkString(","),
            review.map(_.findingsDigest).sorted.mkString(","),
            integration.map(_.operationId).sorted.mkString(",")
          )
          val action =
            ReleaseStage.release(configured.copy(acceptance = accepted)).map {
              result =>
                val checksumFile = result.directory.resolve("SHA256SUMS")
                val digest = MessageDigest
                  .getInstance("SHA-256")
                  .digest(Files.readAllBytes(checksumFile))
                  .map(b => f"${b & 0xff}%02x")
                  .mkString
                val record = SdlcEvidence.Record.LocalRelease(
                  SdlcEvidence.LocalReleaseResult(
                    result.version,
                    result.tag,
                    result.revision,
                    candidateDigest,
                    digest,
                    localOnly = true
                  )
                )
                SdlcStages.StageResult(
                  context,
                  successful = true,
                  "local release created",
                  typedEvidence = List(record)
                )
            }
          Task(
            action.either.flatMap {
              case Right(result) => zio.ZIO.succeed(result)
              case Left(error: ReleaseStage.Interrupted) =>
                zio.ZIO.succeed(
                  SdlcStages.StageResult(
                    context,
                    successful = false,
                    error.getMessage,
                    interrupted = true
                  )
                )
              case Left(error) =>
                zio.ZIO.fail(
                  raider.core.RaiderError.InputValidation(error.getMessage)
                )
            }
          )
    }

end LocalReleaseHandler
