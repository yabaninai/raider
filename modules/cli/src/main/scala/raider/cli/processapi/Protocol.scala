package raider.cli.processapi

import zio.json.JsonDecoder
import zio.json.ast.Json

import scala.collection.mutable

/** RAI-201 wire model of the local stdio process adapter (ADR-037 §2, §4, §6).
  *
  * Client frames are decoded STRICTLY: the top-level key set of every frame
  * must exactly match the allowlist of its `type`, and `submit.input` stays an
  * opaque JSON object at this layer — its inner allowlist (`schemaVersion`,
  * `task`) is enforced only at dispatch ([[validateSubmitInput]]), so a refused
  * submit never dispatches a root and never echoes a forbidden field's VALUE.
  * Adapter→client frames are built as explicit JSON ASTs so the emitted key
  * sets are exactly the documented ones.
  */
object Protocol:

  val ProtocolMajor: Int = 1
  val ProtocolMinor: Int = 0
  val AdapterVersion: String = "raider-process-api-1"

  val MaxRequestIdLength: Int = 128
  val MaxTaskLength: Int = 100000
  val MaxMessageLength: Int = 512
  val MaxTypeEchoLength: Int = 32

  // ---------------------------------------------------------------------------
  // client → adapter frames
  // ---------------------------------------------------------------------------

  final case class ClientVersion(major: Int, minor: Int)

  sealed trait ClientFrame:
    def requestId: String

  final case class Hello(
      requestId: String,
      supported: Vector[ClientVersion],
      requiredCapabilities: Vector[String]
  ) extends ClientFrame

  final case class Submit(requestId: String, input: Json) extends ClientFrame

  final case class Observe(requestId: String, afterSequence: Long)
      extends ClientFrame

  final case class Cancel(requestId: String) extends ClientFrame

  // ---------------------------------------------------------------------------
  // protocol failures (ADR-037 §6): every failure has a wire `code`, a bounded
  // human `message` and a fatality. Fatal failures terminate the session
  // (executionStatus failed, exit 20 RuntimeFailure always — a live root is
  // cancelled with bounded cleanup before the terminal frame); non-fatal ones
  // are answered with an error frame and the session continues.
  // ---------------------------------------------------------------------------

  sealed trait ProtocolFailure:
    def code: String
    def message: String
    def fatal: Boolean

  final case class UnsupportedVersion(got: String) extends ProtocolFailure:
    val code = "unsupported_version"
    val fatal = true

    def message: String =
      s"unsupported protocolVersion $got; this adapter negotiates 1.0 only"

  case object HandshakeRequired extends ProtocolFailure:
    val code = "handshake_required"
    val message = "the first frame must be hello"
    val fatal = false

  case object HelloAlreadyNegotiated extends ProtocolFailure:
    val code = "hello_already_negotiated"
    val message = "hello was already accepted"
    val fatal = false

  final case class UnknownFrameType(boundedType: String)
      extends ProtocolFailure:
    val code = "unknown_type"
    val fatal = false
    def message: String = s"unknown frame type '$boundedType'"

  final case class MalformedFrame(reason: String) extends ProtocolFailure:
    val code = "malformed_frame"
    val fatal = true
    def message: String = s"malformed JSON frame: $reason"

  case object InvalidUtf8 extends ProtocolFailure:
    val code = "invalid_utf8"
    val message = "frame is not valid UTF-8"
    val fatal = true

  final case class OversizeFrame(observedBytes: Long) extends ProtocolFailure:
    val code = "oversize_frame"
    val fatal = true

    def message: String =
      s"frame payload exceeds the ${Framing.FrameMaxBytes}-byte bound " +
        s"(observed $observedBytes bytes); payload not logged"

  final case class UnknownField(path: String) extends ProtocolFailure:
    val code = "unknown_field"
    val fatal = false
    def message: String = s"unknown field '$path' is not accepted in this frame"

  final case class InvalidFrame(reason: String) extends ProtocolFailure:
    val code = "invalid_frame"
    val fatal = false
    def message: String = s"frame rejected: $reason"

  final case class InputRejected(reason: String) extends ProtocolFailure:
    val code = "input_rejected"
    val fatal = false
    def message: String = s"submit rejected before dispatch: $reason"

  final case class PreflightFailed(reason: String) extends ProtocolFailure:
    val code = "preflight_failed"
    val fatal = false
    def message: String = s"run preflight failed: $reason"

  final case class ObserveFailed(reason: String) extends ProtocolFailure:
    val code = "observe_failed"
    val fatal = false
    def message: String = s"observe refused: $reason"

  case object DuplicateSubmit extends ProtocolFailure:
    val code = "duplicate_submit"

    val message =
      "this process invocation already owns a root; additional submits are refused"

    val fatal = false

  final case class UnsupportedCapability(capability: String)
      extends ProtocolFailure:
    val code = "unsupported_capability"
    val fatal = true

    def message: String =
      s"required capability '$capability' is not supported by this adapter"

  final case class Internal(reason: String) extends ProtocolFailure:
    val code = "internal_error"
    val fatal = false
    def message: String = s"internal error: $reason"

  // ---------------------------------------------------------------------------
  // decoding (strict per-frame allowlists)
  // ---------------------------------------------------------------------------

  private val CommonKeys = Set("protocolVersion", "type", "requestId")

  private val HelloKeys =
    CommonKeys + "supported" + "requiredCapabilities"

  private val SubmitKeys = CommonKeys + "input"
  private val ObserveKeys = CommonKeys + "afterSequence"
  private val CancelKeys = CommonKeys

  val SupportedCapabilities: Set[String] = Set("observe", "cancel")

  /** Decodes one inbound frame (already parsed as JSON AST). */
  def decode(frame: Json): Either[ProtocolFailure, ClientFrame] =
    frame match
      case obj: Json.Obj => decodeObj(obj.fields.toMap)
      case _             => Left(MalformedFrame("frame must be a JSON object"))

  /** Best-effort requestId correlation for error replies on frames that fail to
    * decode (ADR-037 §3 — "correlation where known"). This is not validation:
    * an absent or non-string requestId simply carries no correlation.
    */
  def correlation(frame: Json): Option[String] =
    frame match
      case obj: Json.Obj =>
        obj.fields.toMap.get("requestId") match
          case Some(Json.Str(s))
              if s.nonEmpty && s.length <= MaxRequestIdLength =>
            Some(s)
          case _ => None
      case _ => None

  private def decodeObj(
      fields: Map[String, Json]
  ): Either[ProtocolFailure, ClientFrame] =
    for
      version <- decodeVersion(fields.get("protocolVersion"))
      _ <-
        if version.major != ProtocolMajor || version.minor != ProtocolMinor
        then Left(UnsupportedVersion(s"${version.major}.${version.minor}"))
        else Right(())
      typeStr <- fields.get("type") match
        case Some(Json.Str(s)) => Right(s)
        case Some(_)           => Left(InvalidFrame("type must be a string"))
        case None              => Left(InvalidFrame("missing type"))
      _ <- typeStr match
        case "hello" | "submit" | "observe" | "cancel" => Right(())
        case other => Left(UnknownFrameType(bounded(other, MaxTypeEchoLength)))
      requestId <- decodeRequestId(fields.get("requestId"))
      frame <- typeStr match
        case "hello"   => decodeHello(fields, requestId)
        case "submit"  => decodeSubmit(fields, requestId)
        case "observe" => decodeObserve(fields, requestId)
        case "cancel"  => decodeCancel(fields, requestId)
        case _ => Left(UnknownFrameType(bounded(typeStr, MaxTypeEchoLength)))
    yield frame

  private def decodeVersion(
      json: Option[Json]
  ): Either[ProtocolFailure, ClientVersion] =
    json match
      case Some(obj: Json.Obj) =>
        val f = obj.fields.toMap
        (f.get("major"), f.get("minor")) match
          case (Some(Json.Num(maj)), Some(Json.Num(min))) =>
            val (m1, m2) =
              (scala.math.BigDecimal(maj), scala.math.BigDecimal(min))
            if m1.isValidInt && m2.isValidInt
            then Right(ClientVersion(m1.toInt, m2.toInt))
            else Left(InvalidFrame("protocolVersion numbers out of range"))
          case _ =>
            Left(InvalidFrame("protocolVersion must be {major, minor} numbers"))
      case Some(_) => Left(InvalidFrame("protocolVersion must be an object"))
      case None    => Left(InvalidFrame("missing protocolVersion"))

  private def decodeRequestId(
      json: Option[Json]
  ): Either[ProtocolFailure, String] =
    json match
      case Some(Json.Str(s)) =>
        if s.isEmpty then Left(InvalidFrame("requestId must not be empty"))
        else if s.length > MaxRequestIdLength then
          Left(
            InvalidFrame(s"requestId exceeds $MaxRequestIdLength characters")
          )
        else Right(s)
      case Some(_) => Left(InvalidFrame("requestId must be a string"))
      case None    => Left(InvalidFrame("missing requestId"))

  /** Rejects extra/missing keys for one frame type; extra keys name the field
    * PATH (`hello.<name>`), missing keys are invalid_frame.
    */
  private def checkKeys(
      frameType: String,
      fields: Map[String, Json],
      allowed: Set[String]
  ): Either[ProtocolFailure, Unit] =
    val extra = fields.keySet.diff(allowed)
    if extra.nonEmpty then
      Left(UnknownField(s"$frameType.${extra.toVector.sorted.head}"))
    else
      val missing = allowed.diff(fields.keySet)
      if missing.nonEmpty then
        Left(InvalidFrame(s"missing field '${missing.toVector.sorted.head}'"))
      else Right(())

  private def decodeHello(
      fields: Map[String, Json],
      requestId: String
  ): Either[ProtocolFailure, ClientFrame] =
    for
      _ <- checkKeys("hello", fields, HelloKeys)
      supported <- fields.get("supported") match
        case Some(Json.Arr(items)) =>
          items.toList.foldM(Vector.empty[ClientVersion]) { (acc, item) =>
            decodeVersion(Some(item)).map(acc :+ _)
          }
        case Some(_) => Left(InvalidFrame("supported must be an array"))
        case None    => Left(InvalidFrame("missing supported"))
      _ <-
        if supported.isEmpty then
          Left(InvalidFrame("supported must list at least one version"))
        else Right(())
      required <- fields.get("requiredCapabilities") match
        case Some(Json.Arr(items)) =>
          items.toList.foldM(Vector.empty[String]) { (acc, item) =>
            item match
              case Json.Str(s) => Right(acc :+ s)
              case _ =>
                Left(InvalidFrame("requiredCapabilities must be strings"))
          }
        case Some(_) =>
          Left(InvalidFrame("requiredCapabilities must be an array"))
        case None => Left(InvalidFrame("missing requiredCapabilities"))
    yield Hello(requestId, supported, required)

  private def decodeSubmit(
      fields: Map[String, Json],
      requestId: String
  ): Either[ProtocolFailure, ClientFrame] =
    for
      _ <- checkKeys("submit", fields, SubmitKeys)
      input <- fields.get("input") match
        case Some(obj: Json.Obj) => Right(obj: Json)
        case Some(_) => Left(InvalidFrame("input must be an object"))
        case None    => Left(InvalidFrame("missing input"))
    yield Submit(requestId, input)

  private def decodeObserve(
      fields: Map[String, Json],
      requestId: String
  ): Either[ProtocolFailure, ClientFrame] =
    for
      _ <- checkKeys("observe", fields, ObserveKeys)
      after <- fields.get("afterSequence") match
        case Some(Json.Num(n)) =>
          val v = scala.math.BigDecimal(n)
          if v.isValidLong && v >= 0 then Right(v.toLongExact)
          else
            Left(InvalidFrame("afterSequence must be a non-negative integer"))
        case Some(_) =>
          Left(InvalidFrame("afterSequence must be a number"))
        case None => Left(InvalidFrame("missing afterSequence"))
    yield Observe(requestId, after)

  private def decodeCancel(
      fields: Map[String, Json],
      requestId: String
  ): Either[ProtocolFailure, ClientFrame] =
    checkKeys("cancel", fields, CancelKeys).map(_ => Cancel(requestId))

  /** Dispatch-time validation of `submit.input` (ADR-037 §4): the inner
    * allowlist is exactly `{schemaVersion, task}`; a violation is a refusal
    * that never dispatches a root and never echoes the offending value.
    */
  def validateSubmitInput(input: Json): Either[ProtocolFailure, String] =
    input match
      case obj: Json.Obj =>
        val fields = obj.fields.toMap
        val extra = fields.keySet.diff(Set("schemaVersion", "task"))
        if extra.nonEmpty then
          Left(
            InputRejected(
              s"unknown field in input: '${extra.toVector.sorted.head}'"
            )
          )
        else
          for
            _ <- fields.get("schemaVersion") match
              case Some(Json.Num(n))
                  if scala.math.BigDecimal(n) == BigDecimal(1) =>
                Right(())
              case Some(Json.Num(n)) =>
                Left(
                  InputRejected(
                    s"unsupported input.schemaVersion ${scala.math.BigDecimal(n)}"
                  )
                )
              case Some(_) =>
                Left(InputRejected("input.schemaVersion must be a number"))
              case None => Left(InputRejected("missing input.schemaVersion"))
            task <- fields.get("task") match
              case Some(Json.Str(s)) =>
                if s.isEmpty then
                  Left(InputRejected("input.task must not be empty"))
                else if s.length > MaxTaskLength then
                  Left(
                    InputRejected(
                      s"input.task exceeds $MaxTaskLength characters"
                    )
                  )
                else Right(s)
              case Some(_) =>
                Left(InputRejected("input.task must be a string"))
              case None => Left(InputRejected("missing input.task"))
          yield task
      case _ => Left(InputRejected("input must be a JSON object"))

  // ---------------------------------------------------------------------------
  // adapter → client frames (explicit ASTs: exact key sets, field names only,
  // never client-controlled values except bounded requestId/type echoes)
  // ---------------------------------------------------------------------------

  final case class Skipped(fromSequence: Long, count: Long)

  final case class Observation(
      sequence: Long,
      kind: String,
      message: String,
      skipped: Option[Skipped]
  )

  final case class ArtifactRef(
      path: String,
      sha256: String,
      mediaType: String,
      sizeBytes: Long,
      provenance: String
  )

  final case class TerminalBody(
      executionStatus: String,
      taskVerdict: String,
      safetyVerdict: String,
      protocolVerdict: String,
      accountingStatus: String,
      cleanupStatus: String,
      exitCode: Int,
      artifacts: Vector[ArtifactRef],
      primaryError: Option[String],
      secondaryError: Option[String]
  )

  private def versionJson: Json =
    Json.Obj(
      "major" -> Json.Num(ProtocolMajor),
      "minor" -> Json.Num(ProtocolMinor)
    )

  private def base(
      frameType: String,
      requestId: Option[String]
  ): mutable.ListBuffer[(String, Json)] =
    val b = mutable.ListBuffer[(String, Json)]()
    b += ("protocolVersion" -> versionJson)
    b += ("type" -> Json.Str(frameType))
    requestId.foreach(r => b += ("requestId" -> Json.Str(r)))
    b

  def helloAck(requestId: String): Json =
    val fields = base("helloAck", Some(requestId))
    fields += ("negotiated" -> versionJson)
    fields += (
      "capabilities" -> Json.Arr(
        Json.Str("observe"),
        Json.Str("cancel")
      )
    )
    fields += ("adapterVersion" -> Json.Str(AdapterVersion))
    Json.Obj(fields.toVector*)

  def accepted(requestId: String, rootId: String): Json =
    Json.Obj(
      (base("accepted", Some(requestId)) :+ ("rootId" -> Json.Str(
        rootId
      ))).toVector*
    )

  def observation(requestId: String, o: Observation): Json =
    val fields = base("observation", Some(requestId))
    fields += ("sequence" -> Json.Num(o.sequence))
    fields += (
      "event" -> Json.Obj(
        "kind" -> Json.Str(o.kind),
        "message" -> Json.Str(o.message)
      )
    )
    o.skipped.foreach { s =>
      fields += (
        "skipped" -> Json.Obj(
          "fromSequence" -> Json.Num(s.fromSequence),
          "count" -> Json.Num(s.count)
        )
      )
    }
    Json.Obj(fields.toVector*)

  def cancelAck(requestId: String, status: String): Json =
    Json.Obj(
      (base("cancelAck", Some(requestId)) :+ ("status" -> Json.Str(
        status
      ))).toVector*
    )

  def terminal(requestId: String, rootId: String, body: TerminalBody): Json =
    val fields = base("terminal", Some(requestId))
    fields += ("rootId" -> Json.Str(rootId))
    fields += ("executionStatus" -> Json.Str(body.executionStatus))
    fields += ("taskVerdict" -> Json.Str(body.taskVerdict))
    fields += ("safetyVerdict" -> Json.Str(body.safetyVerdict))
    fields += ("protocolVerdict" -> Json.Str(body.protocolVerdict))
    fields += ("accountingStatus" -> Json.Str(body.accountingStatus))
    fields += ("cleanupStatus" -> Json.Str(body.cleanupStatus))
    fields += ("exitCode" -> Json.Num(body.exitCode))
    fields += (
      "artifacts" -> Json.Arr(
        body.artifacts.map { a =>
          Json.Obj(
            "path" -> Json.Str(a.path),
            "sha256" -> Json.Str(a.sha256),
            "mediaType" -> Json.Str(a.mediaType),
            "sizeBytes" -> Json.Num(a.sizeBytes),
            "provenance" -> Json.Str(a.provenance)
          )
        }*
      )
    )
    body.primaryError.foreach(e => fields += ("primaryError" -> Json.Str(e)))
    body.secondaryError.foreach(e =>
      fields += ("secondaryError" -> Json.Str(e))
    )
    Json.Obj(fields.toVector*)

  def error(requestId: Option[String], failure: ProtocolFailure): Json =
    val fields = base("error", requestId)
    fields += ("code" -> Json.Str(failure.code))
    fields += ("message" -> Json.Str(failure.message))
    Json.Obj(fields.toVector*)

  /** Bounded, single-value redaction helper: truncates to `max` characters.
    * Used for generated diagnostics only — client-controlled field VALUES are
    * never echoed at all.
    */
  def bounded(s: String, max: Int = MaxMessageLength): String =
    if s.length <= max then s else s.take(max) + "..."

  private implicit class FoldOps[A](private val list: List[Json])
      extends AnyVal:

    def foldM[B](
        zero: B
    )(f: (B, Json) => Either[ProtocolFailure, B]): Either[ProtocolFailure, B] =
      list.foldLeft[Either[ProtocolFailure, B]](Right(zero))((acc, item) =>
        acc.flatMap(f(_, item))
      )

end Protocol
