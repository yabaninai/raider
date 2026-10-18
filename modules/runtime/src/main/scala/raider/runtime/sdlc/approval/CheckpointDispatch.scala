package raider.runtime.sdlc.approval

import raider.core.sdlc.SdlcEvidence.CandidateIdentity

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant

enum CheckpointOutcome:
  case Authorized(value: ApprovalArtifacts.Authorized)
  case NeedsApproval(value: ApprovalArtifacts.NeedsApproval)
  case Refused(reason: ApprovalArtifacts.Refusal)

/** Trusted invocation inputs for a checkpoint at a workflow/launcher boundary.
  * The owner supplies the current-state reader and capability; neither a
  * request artifact nor a decision file can supply either authority.
  */
final case class CheckpointDispatch(
    checkpointId: String,
    candidate: CandidateIdentity,
    expiresAt: Instant,
    requestArtifact: Path,
    decisionFile: Option[Path],
    requiredCapability: String,
    readCurrent: () => Either[
      ApprovalArtifacts.Refusal,
      ApprovalArtifacts.CurrentState
    ],
    now: () => Instant = () => Instant.now()
):
  import ApprovalArtifacts.*

  /** Evaluate exactly once per invocation. No continuation or running fiber is
    * retained. The next invocation must construct a fresh instance and reader.
    */
  def evaluate(): CheckpointOutcome =
    decisionFile match
      case None =>
        request(checkpointId, candidate, expiresAt) match
          case Left(refusal) => CheckpointOutcome.Refused(refusal)
          case Right(_) =>
            headlessCheckpoint(
              requestArtifact,
              checkpointId,
              candidate,
              expiresAt
            ) match
              case Right(value) => CheckpointOutcome.NeedsApproval(value)
              case Left(reason) => CheckpointOutcome.Refused(reason)
      case Some(path) =>
        val artifacts =
          try
            val requestValue = decodeRequestArtifact(
              Files.readString(requestArtifact, UTF_8)
            )
            val decisionJson = Files.readString(path, UTF_8)
            requestValue.map(value => (value, decisionJson))
          catch
            case _: java.nio.file.NoSuchFileException =>
              Left(Refusal.MissingDecision)
            case e: Exception =>
              Left(
                Refusal.InvalidDecision(
                  s"cannot read approval artifacts: ${e.getClass.getSimpleName}"
                )
              )
        artifacts match
          case Left(reason) => CheckpointOutcome.Refused(reason)
          case Right((requestValue, json)) =>
            if requestValue.checkpointId != checkpointId then
              CheckpointOutcome.Refused(
                Refusal.InvalidRequest(
                  "checkpoint id does not match request artifact"
                )
              )
            else
              recheck(
                requestValue,
                Some(json),
                now(),
                readCurrent,
                requiredCapability
              ) match
                case Right(value) => CheckpointOutcome.Authorized(value)
                case Left(reason) => CheckpointOutcome.Refused(reason)

end CheckpointDispatch
