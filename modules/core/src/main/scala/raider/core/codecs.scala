package raider.core

import zio.json._

/** Canonical JSON codecs for core contracts (RAI-002 freeze, zio-json 0.10.0).
  *
  * Wire stability rules:
  *  - IDs encode as plain strings.
  *  - ModelEvent uses an explicit `type` discriminator; adding a case is a
  *    schema decision (new schemaVersion), never a silent shape change.
  *  - RaiderError encodes as a flat wire object `{code, detail, retryable}`;
  *    decoding an unknown code fails loudly (no best-effort guessing).
  *  - Usage fields are kebab/lower camel as declared; token counts are integers.
  */
object codecs:

  given JsonCodec[SessionId] = JsonCodec.string.transform(SessionId.apply, _.value)
  given JsonCodec[RootId] = JsonCodec.string.transform(RootId.apply, _.value)
  given JsonCodec[JobId] = JsonCodec.string.transform(JobId.apply, _.value)
  given JsonCodec[StepId] = JsonCodec.string.transform(StepId.apply, _.value)
  given JsonCodec[AttemptId] = JsonCodec.string.transform(AttemptId.apply, _.value)
  given JsonCodec[ToolCallId] = JsonCodec.string.transform(ToolCallId.apply, _.value)
  given JsonCodec[MessageId] = JsonCodec.string.transform(MessageId.apply, _.value)
  given JsonCodec[ArtifactId] = JsonCodec.string.transform(ArtifactId.apply, _.value)

  given JsonCodec[Usage] = DeriveJsonCodec.gen[Usage]

  given JsonCodec[ModelEvent] = DeriveJsonCodec.gen[ModelEvent]

  // ---- errors: flat wire object, unknown code is a decode failure ----
  private final case class ErrorWire(code: String, detail: String, retryable: Boolean)
  private object ErrorWire:
    given JsonCodec[ErrorWire] = DeriveJsonCodec.gen[ErrorWire]

  private def toWire(e: RaiderError): ErrorWire = ErrorWire(e.code, e.detail, e.retryable)

  private val fromWire: ErrorWire => Either[String, RaiderError] = w =>
    w.code match
      case "RA-CFG"       => Right(RaiderError.Configuration(w.detail))
      case "RA-INP"       => Right(RaiderError.InputValidation(w.detail))
      case "RA-OUT"       => Right(RaiderError.OutputValidation(w.detail))
      case "RA-AUTH"      => Right(RaiderError.ProviderAuth(w.detail))
      case "RA-RATE"      => Right(RaiderError.ProviderRateLimit(w.detail))
      case "RA-UNAVAIL"   => Right(RaiderError.ProviderUnavailable(w.detail))
      case "RA-STREAM"    => Right(RaiderError.StreamProtocol(w.detail))
      case "RA-TOOLDENY"  => Right(RaiderError.ToolDenied(w.detail))
      case "RA-TOOLFAIL"  => Right(RaiderError.ToolFailed(w.detail))
      case "RA-DEADLINE"  => Right(RaiderError.DeadlineExceeded(w.detail))
      case "RA-CANCEL"    => Right(RaiderError.Cancelled(w.detail))
      case "RA-CAP"       => Right(RaiderError.CapabilityUnsupported(w.detail))
      case "RA-COMPILE"   => Right(RaiderError.Compilation(w.detail))
      case "RA-PBUDGET"   => Right(RaiderError.ProviderBudget(w.detail))
      case "RA-TOOLUNCERT" => Right(RaiderError.ToolOutcomeUncertain(w.detail))
      case "RA-LBUDGET"   => Right(RaiderError.LocalBudgetExceeded(w.detail))
      case "RA-CHILD"     => Right(RaiderError.ChildFailed(w.detail))
      case "RA-JOURNAL"   => Right(RaiderError.JournalFailure(w.detail))
      case other          => Left(s"unknown error code '$other'")

  given JsonCodec[RaiderError] = JsonCodec(
    JsonEncoder[ErrorWire].contramap(toWire),
    JsonDecoder[ErrorWire].mapOrFail(fromWire)
  )

end codecs
