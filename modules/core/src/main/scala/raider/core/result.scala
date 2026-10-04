package raider.core

import zio.json._
import zio.json.ast.Json
import raider.core.codecs.given

/** Run result schema (RAI-002 remainder; ci-runtime §7 result.json).
  *
  * The result document records the VERIFIER's verdict, never the model's
  * claim: model output is not a verdict, so there is deliberately no field
  * where a model answer could masquerade as one. `executionStatus` (§4
  * terminal set), `taskVerdict`, check outcomes and the stable exit code are
  * separate fields; at most one primary structured error.
  */
object result:

  /** Stable exit-code mapping (ci-runtime §8). No catch-all zero: 0 is
    * declared success only. */
  object ExitCodes:
    val Success               = 0
    val TaskRejected          = 10
    val RuntimeFailure        = 20
    val ConfigInvalid         = 21
    val PolicyDenied          = 22
    val BudgetExhausted       = 23
    val DeadlineExceeded      = 24
    val NeedsApproval         = 25
    val Uncertain             = 26
    val ArtifactWriteFailure  = 27
    val SigInt                = 130
    val SigTerm               = 143

    val all: Set[Int] = Set(Success, TaskRejected, RuntimeFailure,
      ConfigInvalid, PolicyDenied, BudgetExhausted, DeadlineExceeded,
      NeedsApproval, Uncertain, ArtifactWriteFailure, SigInt, SigTerm)

  enum ExecutionStatus:
    case Succeeded, Failed, Cancelled, Interrupted, Uncertain

  object ExecutionStatus:
    given JsonCodec[ExecutionStatus] = DeriveJsonCodec.gen[ExecutionStatus]

  enum CheckOutcome:
    case Passed, Failed, Errored, Skipped

  object CheckOutcome:
    given JsonCodec[CheckOutcome] = DeriveJsonCodec.gen[CheckOutcome]

  final case class CheckResult(id: String, outcome: CheckOutcome, detail: String)

  /** Artifact manifest entry (ci-runtime §7 artifacts.json). */
  final case class ArtifactEntry(
    artifactId: ArtifactId, sha256: String, mediaType: String,
    sizeBytes: Long, path: String)

  /** Validated output with its explicit codec identity (result.json). */
  final case class OutputPayload(codec: String, codecVersion: Int, json: String)

  final case class RunResult(
    schemaVersion: Int,
    rootId: RootId,
    status: ExecutionStatus,
    exitCode: Int,
    taskVerdict: Option[String],
    checks: List[CheckResult],
    artifacts: List[ArtifactEntry],
    output: Option[OutputPayload],
    error: Option[RaiderError],
    startedAt: String,
    finishedAt: String
  )

  object CheckResult:
    given JsonCodec[CheckResult] = DeriveJsonCodec.gen[CheckResult]

  object ArtifactEntry:
    given JsonCodec[ArtifactEntry] = DeriveJsonCodec.gen[ArtifactEntry]

  object OutputPayload:
    given JsonCodec[OutputPayload] = DeriveJsonCodec.gen[OutputPayload]

  object RunResult:
    given JsonCodec[RunResult] = DeriveJsonCodec.gen[RunResult]

    private val sha256Hex = "[0-9a-f]{64}"

    /** Structural validation BEFORE the document is trusted or written. */
    def validate(r: RunResult): Either[RaiderError, Unit] =
      if !schemas.supportedSchemaVersions.contains(r.schemaVersion) then
        Left(RaiderError.InputValidation(
          s"unsupported schemaVersion ${r.schemaVersion}"))
      else if r.rootId.value.trim.isEmpty then
        Left(RaiderError.InputValidation("rootId must be non-empty"))
      else if !ExitCodes.all.contains(r.exitCode) then
        Left(RaiderError.InputValidation(
          s"unknown exitCode ${r.exitCode}; declared mapping: " +
            ExitCodes.all.toList.sorted.mkString(", ")))
      else if r.startedAt.trim.isEmpty || r.finishedAt.trim.isEmpty then
        Left(RaiderError.InputValidation("timestamps must be non-empty"))
      else if r.checks.exists(_.id.trim.isEmpty) then
        Left(RaiderError.InputValidation("check ids must be non-empty"))
      else if r.artifacts.exists(a => !a.sha256.matches(sha256Hex)) then
        Left(RaiderError.InputValidation(
          "artifact sha256 must be lowercase 64-hex"))
      else if r.artifacts.exists(_.sizeBytes < 0) then
        Left(RaiderError.InputValidation("artifact sizeBytes must be >= 0"))
      else if r.artifacts.exists(_.path.trim.isEmpty) then
        Left(RaiderError.InputValidation("artifact path must be non-empty"))
      else r.output match
        case Some(out) =>
          if out.codec.trim.isEmpty then
            Left(RaiderError.InputValidation("output codec must be non-empty"))
          else if out.codecVersion < 1 then
            Left(RaiderError.InputValidation("output codecVersion must be >= 1"))
          else Json.decoder.decodeJson(out.json) match
            case Left(err) =>
              Left(RaiderError.InputValidation(
                s"output payload is not valid JSON: $err"))
            case Right(_) => Right(())
        case None => Right(())
end result
