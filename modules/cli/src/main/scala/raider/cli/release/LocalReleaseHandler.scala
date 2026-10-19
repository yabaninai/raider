package raider.cli.release

import raider.core.{Program, Task}
import raider.core.sdlc.{SdlcEvidence, SdlcStages}

import java.nio.file.Files
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Adapter from the typed SDLC stage contract to the local-only release
  * service.
  */
object LocalReleaseHandler:

  def apply(
      request: SdlcStages.Context => ReleaseStage.Request
  ): SdlcStages.Handler =
    Program.fromFunction[SdlcStages.Context, SdlcStages.StageResult](
      "local-release"
    ) { context =>
      SdlcStages.authorizeLocalRelease(context) match
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
            action.mapError(error =>
              raider.core.RaiderError.InputValidation(error.getMessage)
            )
          )
    }

end LocalReleaseHandler
