package raider.core

import zio.json._

/** Versioned JSON envelopes and the normalized CI context schema (RAI-002).
  *
  * Every persisted/exchanged document carries schemaVersion; decoding validates
  * the version against the supported set BEFORE touching the payload — unknown
  * versions are a typed InputValidation failure, not a best-effort parse.
  */
object schemas:

  val supportedSchemaVersions: Set[Int] = Set(1)

  final case class VersionedEnvelope(schemaVersion: Int, payload: String)
  object VersionedEnvelope:
    given JsonCodec[VersionedEnvelope] = DeriveJsonCodec.gen[VersionedEnvelope]

    def encodeValidated(schemaVersion: Int, payloadJson: String)
        : Either[RaiderError, String] =
      if supportedSchemaVersions.contains(schemaVersion) then
        Right(VersionedEnvelope(schemaVersion, payloadJson).toJson)
      else Left(RaiderError.InputValidation(
        s"unsupported schemaVersion $schemaVersion; supported=${supportedSchemaVersions.mkString(",")}"))

    def decodeValidated(json: String): Either[RaiderError, VersionedEnvelope] =
      json.fromJson[VersionedEnvelope] match
        case Left(err) => Left(RaiderError.InputValidation(s"malformed envelope: $err"))
        case Right(env) =>
          if supportedSchemaVersions.contains(env.schemaVersion) then Right(env)
          else Left(RaiderError.InputValidation(
            s"unsupported schemaVersion ${env.schemaVersion}; supported=${supportedSchemaVersions.mkString(",")}"))

  /** Minimal normalized CI context (ci-runtime §4 subset frozen for M0):
    * required identity fields non-empty; optional fields nullable. */
  final case class RunContext(
    schemaVersion: Int,
    system: String,
    instanceId: Option[String],
    repositoryId: Option[String],
    pipelineId: Option[String],
    jobId: Option[String],
    matrixKey: Option[String],
    attempt: Int,
    declaredTrustLevel: String
  )
  object RunContext:
    given JsonCodec[RunContext] = DeriveJsonCodec.gen[RunContext]

    def validate(ctx: RunContext): Either[RaiderError, Unit] =
      if !supportedSchemaVersions.contains(ctx.schemaVersion) then
        Left(RaiderError.InputValidation(s"unsupported schemaVersion ${ctx.schemaVersion}"))
      else if ctx.system.trim.isEmpty then
        Left(RaiderError.InputValidation("system must be a non-empty string"))
      else if ctx.attempt < 1 then
        Left(RaiderError.InputValidation("attempt must be >= 1"))
      else if ctx.declaredTrustLevel.trim.isEmpty then
        Left(RaiderError.InputValidation("declaredTrustLevel must be a non-empty string"))
      else Right(())

end schemas
