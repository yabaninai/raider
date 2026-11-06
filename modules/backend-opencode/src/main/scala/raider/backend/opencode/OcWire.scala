package raider.backend.opencode

import zio.Chunk
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.json.DeriveJsonDecoder
import zio.json.DeriveJsonEncoder
import zio.json.ast.Json

/** Wire codecs for the pinned OpenCode server surface (RAI-035). The pinned
 * reference is the opencode v1 HTTP API as published in the server docs and
 * mirrored by the JS SDK `types.gen.ts` at tag v1.19.4:
 *   - GlobalEvent = { directory: string, payload: Event } on GET /global/event
 *   - Session, AssistantMessage/UserMessage, ToolPart states, Permission
 *   - request bodies: POST /session, POST /session/{id}/prompt_async
 *
 * Decoding policy: fields that the pinned types mark required and that this
 * adapter's logic depends on are required here; identity/evidence fields the
 * settle logic does not need are optional for forward tolerance. Unknown
 * event TYPES decode as `OcBusEvent.Unknown` (never break the stream);
 * unparseable shapes fail typed (OC-OUT for responses, OC-STREAM for event
 * frames).
 */

final case class OcHealth(healthy: Boolean, version: String)
object OcHealth:
  given JsonDecoder[OcHealth] = DeriveJsonDecoder.gen

final case class OcSessionTime(created: Long, updated: Option[Long], completed: Option[Long])
object OcSessionTime:
  given JsonDecoder[OcSessionTime] = DeriveJsonDecoder.gen

final case class OcSession(
    id: String,
    projectID: String,
    directory: String,
    parentID: Option[String],
    title: String,
    version: String,
    time: OcSessionTime,
)
object OcSession:
  given JsonDecoder[OcSession] = DeriveJsonDecoder.gen

/** Flattened view of the pinned discriminated error shapes
 * ({name, data:{message?}}): ProviderAuthError, UnknownError,
 * MessageOutputLengthError, MessageAbortedError, APIError. Only name and the
 * best-effort message are kept; provider response bodies are never carried
 * into error details.
 */
final case class OcMessageError(name: String, message: String)
object OcMessageError:
  private final case class DataWire(message: Option[String]) derives JsonDecoder
  private final case class Wire(name: String, data: Option[DataWire]) derives JsonDecoder
  given JsonDecoder[OcMessageError] =
    summon[JsonDecoder[Wire]].map(w => OcMessageError(w.name, w.data.flatMap(_.message).getOrElse("")))

final case class OcTokenCache(read: Long, write: Long)
object OcTokenCache:
  given JsonDecoder[OcTokenCache] = DeriveJsonDecoder.gen

final case class OcTokens(input: Long, output: Long, reasoning: Long, cache: OcTokenCache)
object OcTokens:
  given JsonDecoder[OcTokens] = DeriveJsonDecoder.gen

final case class OcAssistantMessage(
    id: String,
    sessionID: String,
    parentID: Option[String],
    modelID: Option[String],
    providerID: Option[String],
    mode: Option[String],
    time: OcSessionTime,
    error: Option[OcMessageError],
    cost: Option[Double],
    tokens: Option[OcTokens],
    finish: Option[String],
)
object OcAssistantMessage:
  given JsonDecoder[OcAssistantMessage] = DeriveJsonDecoder.gen

sealed trait OcMessage:
  def messageSessionID: Option[String]
object OcMessage:
  final case class Assistant(info: OcAssistantMessage) extends OcMessage:
    val messageSessionID: Option[String] = Some(info.sessionID)
  final case class User(id: String, sessionID: String) extends OcMessage:
    val messageSessionID: Option[String] = Some(sessionID)

  given JsonDecoder[OcMessage] =
    Json.decoder.mapOrFail { ast =>
      ast.asObject match
        case None => Left("message info is not a JSON object")
        case Some(obj) =>
          obj.get("role").flatMap(_.asString) match
            case Some("assistant") =>
              summon[JsonDecoder[OcAssistantMessage]].fromJsonAST(ast).map(OcMessage.Assistant.apply)
            case Some("user") =>
              val id = optString(obj, "id")
              val sid = optString(obj, "sessionID")
              (id, sid) match
                case (Some(i), Some(s)) => Right(OcMessage.User(i, s))
                case _                  => Left("user message is missing id/sessionID")
            case other => Left(s"unsupported message role ${other.map(q => "'" + q + "'").getOrElse("<missing>")}")
    }

sealed trait OcToolState
object OcToolState:
  final case class Pending(input: Option[Json]) extends OcToolState
  final case class Running(input: Option[Json], title: Option[String], start: Option[Long])
      extends OcToolState
  final case class Completed(
      input: Option[Json],
      output: Option[String],
      title: Option[String],
      start: Option[Long],
      end: Option[Long],
  ) extends OcToolState
  final case class Errored(
      input: Option[Json],
      error: Option[String],
      title: Option[String],
      start: Option[Long],
      end: Option[Long],
  ) extends OcToolState

  given JsonDecoder[OcToolState] =
    Json.decoder.mapOrFail { ast =>
      ast.asObject match
        case None => Left("tool state is not a JSON object")
        case Some(obj) =>
          obj.get("status").flatMap(_.asString).getOrElse("") match
            case "pending" => Right(OcToolState.Pending(obj.get("input")))
            case "running" =>
              Right(
                OcToolState.Running(obj.get("input"), optString(obj, "title"), optNestedTime(obj, "time", "start"))
              )
            case "completed" =>
              Right(
                OcToolState.Completed(
                  obj.get("input"),
                  optString(obj, "output"),
                  optString(obj, "title"),
                  optNestedTime(obj, "time", "start"),
                  optNestedTime(obj, "time", "end"),
                )
              )
            case "error" =>
              Right(
                OcToolState.Errored(
                  obj.get("input"),
                  optString(obj, "error"),
                  optString(obj, "title"),
                  optNestedTime(obj, "time", "start"),
                  optNestedTime(obj, "time", "end"),
                )
              )
            case other => Left(s"unsupported tool state status '$other'")
    }

sealed trait OcPart:
  def partSessionID: Option[String]
object OcPart:
  final case class Text(id: String, sessionID: String, messageID: String, text: Option[String])
      extends OcPart:
    val partSessionID: Option[String] = Some(sessionID)
  final case class Tool(
      id: String,
      sessionID: String,
      messageID: String,
      callID: String,
      tool: String,
      state: OcToolState,
  ) extends OcPart:
    val partSessionID: Option[String] = Some(sessionID)
  /** Degraded view for part types this card does not model (step-start,
   * step-finish, reasoning, patch, agent, subtask, file, ...) and for shapes
   * that fail their pinned decode. Never breaks the stream.
   */
  final case class Other(partType: String) extends OcPart:
    val partSessionID: Option[String] = None

  given JsonDecoder[OcPart] =
    Json.decoder.mapOrFail { ast =>
      ast.asObject match
        case None => Left("part is not a JSON object")
        case Some(obj) =>
          obj.get("type").flatMap(_.asString).getOrElse("") match
            case "text" =>
              (optString(obj, "id"), optString(obj, "sessionID"), optString(obj, "messageID")) match
                case (Some(id), Some(sid), Some(mid)) =>
                  Right(OcPart.Text(id, sid, mid, optString(obj, "text")))
                case _ => Right(OcPart.Other("text"))
            case "tool" =>
              val attempt =
                for
                  id   <- optString(obj, "id")
                  sid  <- optString(obj, "sessionID")
                  mid  <- optString(obj, "messageID")
                  call <- optString(obj, "callID")
                  tool <- optString(obj, "tool")
                  stateAst <- obj.get("state")
                  state <- summon[JsonDecoder[OcToolState]].fromJsonAST(stateAst).toOption
                yield OcPart.Tool(id, sid, mid, call, tool, state)
              attempt match
                case Some(toolPart) => Right(toolPart)
                case None           => Right(OcPart.Other("tool"))
            case other => Right(OcPart.Other(other))
    }

sealed trait OcBusEvent:
  /** The session this event correlates to, when the pinned shape carries one.
   */
  def sessionID: Option[String]
object OcBusEvent:
  case object ServerConnected extends OcBusEvent:
    val sessionID: Option[String] = None
  final case class SessionCreated(info: OcSession) extends OcBusEvent:
    val sessionID: Option[String] = Some(info.id)
  final case class SessionUpdated(info: OcSession) extends OcBusEvent:
    val sessionID: Option[String] = Some(info.id)
  final case class SessionIdle(sid: String) extends OcBusEvent:
    override def sessionID: Option[String] = Some(sid)
  final case class SessionStatus(sid: String, status: String) extends OcBusEvent:
    override def sessionID: Option[String] = Some(sid)
  final case class SessionError(sid: Option[String], error: Option[OcMessageError])
      extends OcBusEvent:
    override def sessionID: Option[String] = sid
  final case class MessageUpdated(info: OcMessage) extends OcBusEvent:
    override def sessionID: Option[String] = info.messageSessionID
  final case class MessagePartUpdated(part: OcPart, delta: Option[String]) extends OcBusEvent:
    override def sessionID: Option[String] = part.partSessionID
  final case class PermissionUpdated(permissionID: String, sid: String) extends OcBusEvent:
    override def sessionID: Option[String] = Some(sid)
  final case class Unknown(eventType: String) extends OcBusEvent:
    override def sessionID: Option[String] = None

  given JsonDecoder[OcBusEvent] =
    Json.decoder.mapOrFail { ast =>
      ast.asObject match
        case None => Left("bus event is not a JSON object")
        case Some(obj) =>
          val eventType = obj.get("type").flatMap(_.asString).getOrElse("")
          val props = obj.get("properties").collect { case o: Json.Obj => o }.getOrElse(Json.Obj.empty)
          eventType match
            case "server.connected" => Right(OcBusEvent.ServerConnected)
            case "session.created" =>
              summon[JsonDecoder[OcSession]].fromJsonAST(props).map(OcBusEvent.SessionCreated.apply)
            case "session.updated" =>
              summon[JsonDecoder[OcSession]].fromJsonAST(props).map(OcBusEvent.SessionUpdated.apply)
            case "session.idle" =>
              requiredString(props, "sessionID").map(OcBusEvent.SessionIdle.apply)
            case "session.status" =>
              for
                sid <- requiredString(props, "sessionID")
                statusAst <- props.get("status").toRight("session.status is missing status")
                status <- statusAst.asObject match
                  case None => Left("session.status status is not an object")
                  case Some(so) =>
                    requiredString(so, "type").map(t => OcBusEvent.SessionStatus(sid, t))
              yield status
            case "session.error" =>
              val sid = optString(props, "sessionID")
              val err = props.get("error").flatMap { e =>
                summon[JsonDecoder[OcMessageError]].fromJsonAST(e).toOption
              }
              Right(OcBusEvent.SessionError(sid, err))
            case "message.updated" =>
              props.get("info") match
                case None => Left("message.updated is missing info")
                case Some(infoAst) =>
                  summon[JsonDecoder[OcMessage]].fromJsonAST(infoAst).map(OcBusEvent.MessageUpdated.apply)
            case "message.part.updated" =>
              props.get("part") match
                case None => Left("message.part.updated is missing part")
                case Some(partAst) =>
                  summon[JsonDecoder[OcPart]].fromJsonAST(partAst).map { part =>
                    OcBusEvent.MessagePartUpdated(part, optString(props, "delta"))
                  }
            case "permission.updated" =>
              for
                pid <- requiredString(props, "id")
                sid <- requiredString(props, "sessionID")
              yield OcBusEvent.PermissionUpdated(pid, sid)
            case other => Right(OcBusEvent.Unknown(other))
    }

final case class OcGlobalEvent(directory: Option[String], payload: OcBusEvent)
object OcGlobalEvent:
  given JsonDecoder[OcGlobalEvent] =
    Json.decoder.mapOrFail { ast =>
      ast.asObject match
        case None => Left("global event is not a JSON object")
        case Some(obj) =>
          for
            payloadAst <- obj.get("payload").toRight("global event is missing payload")
            payload    <- summon[JsonDecoder[OcBusEvent]].fromJsonAST(payloadAst)
          yield OcGlobalEvent(optString(obj, "directory"), payload)
    }

final case class OcMessageWithParts(info: OcMessage, parts: Vector[OcPart])
object OcMessageWithParts:
  given JsonDecoder[OcMessageWithParts] =
    Json.decoder.mapOrFail { ast =>
      ast.asObject match
        case None => Left("message entry is not a JSON object")
        case Some(obj) =>
          for
            infoAst <- obj.get("info").toRight("message entry is missing info")
            info    <- summon[JsonDecoder[OcMessage]].fromJsonAST(infoAst)
          yield
            val parts = obj.get("parts").flatMap(_.asArray).getOrElse(Chunk.empty).flatMap {
              p => summon[JsonDecoder[OcPart]].fromJsonAST(p).toOption
            }
            OcMessageWithParts(info, parts.toVector)
    }

// ---------------------------------------------------------------------------
// Request bodies (encoders)
// ---------------------------------------------------------------------------

final case class OcModelRef(providerID: String, modelID: String)
object OcModelRef:
  given JsonEncoder[OcModelRef] = DeriveJsonEncoder.gen

final case class OcTextPart(text: String)
object OcTextPart:
  /** Manual encoder to pin exact wire shape/order: {"type":"text","text":...}.
   * (encodeJson is final in zio-json; implement unsafeEncode instead.)
   */
  given JsonEncoder[OcTextPart] = new JsonEncoder[OcTextPart]:
    def unsafeEncode(p: OcTextPart, indent: Option[Int], out: zio.json.internal.Write): Unit =
      out.write(
        Json.Obj(
          Chunk(
            "type" -> Json.Str("text"),
            "text" -> Json.Str(p.text),
          )
        ).toString
      )

/** Body for POST /session/{id}/prompt_async. `messageID` is a client-chosen
 * identifier; the adapter never assumes the server deduplicates on it (no
 * exactly-once claim). Unset optional fields are omitted from the JSON.
 */
final case class OcPromptBody(
    model: Option[OcModelRef],
    agent: Option[String],
    parts: List[OcTextPart],
    messageID: Option[String],
    noReply: Option[Boolean],
    system: Option[String],
    tools: Option[Map[String, Boolean]],
)
object OcPromptBody:
  given JsonEncoder[OcPromptBody] = DeriveJsonEncoder.gen

/** Body for POST /session.
 */
final case class OcCreateSessionBody(parentID: Option[String], title: Option[String])
object OcCreateSessionBody:
  given JsonEncoder[OcCreateSessionBody] = DeriveJsonEncoder.gen

final case class OcSessionStatusInfo(@zio.json.jsonField("type") status: String)
object OcSessionStatusInfo:
  given JsonDecoder[OcSessionStatusInfo] = DeriveJsonDecoder.gen

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private[opencode] def optString(obj: Json.Obj, field: String): Option[String] =
  obj.get(field).flatMap(_.asString)

private[opencode] def optTime(obj: Json.Obj, field: String): Option[Long] =
  obj.get(field).flatMap(_.asNumber).map(n => scala.math.BigDecimal(n.value).toLong)

/** Reads `obj.<parent>.<field>` as a number (e.g. `state.time.start`). */
private[opencode] def optNestedTime(obj: Json.Obj, parent: String, field: String): Option[Long] =
  obj.get(parent).flatMap(_.asObject).flatMap(o => optTime(o, field))

private[opencode] def requiredString(obj: Json.Obj, field: String): Either[String, String] =
  optString(obj, field) match
    case Some(v) => Right(v)
    case None    => Left(s"required field '$field' is missing or not a string")

/** Best-effort extraction of a human-readable message from a server error
 * body of the pinned shape {"name":..., "data":{"message":...}}; used for
 * typed error details (truncated).
 */
private[opencode] def serverErrorMessage(body: String): Option[String] =
  Json.decoder
    .decodeJson(body)
    .toOption
    .flatMap(_.asObject)
    .flatMap { obj =>
      val name = optString(obj, "name")
      val message = obj.get("data").flatMap(_.asObject).flatMap(o => optString(o, "message"))
      name.map(n => message.fold(n)(m => s"$n: $m"))
    }
