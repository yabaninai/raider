package raider.provider.anthropic

import raider.core.*
import zio.json.*

/** RAI-013: semantic state machine for the Anthropic Messages SSE stream
  * (provider-protocols §4): message_start → content_block_start/delta/stop
  * by index → message_delta → message_stop; ping ignored, error events fail
  * typed, unknown CONTENT semantics fail typed (never silently dropped),
  * unknown OPTIONAL events are ignored (documented).
  *
  * Usage is CUMULATIVE on this wire: message_start carries input_tokens,
  * each message_delta carries the output_tokens TOTAL so far — the parser
  * OVERWRITES, never sums (ANTH-03: usage must not double-count). One
  * UsageObserved is emitted at finish, from the last observed totals.
  *
  * Input JSON fragments (input_json_delta) accumulate per block index and
  * become ToolCallReady ONLY at the block's content_block_stop — partial
  * arguments are never executable (§8).
  */
final class AnthropicStreamParser:

  private var attempt        = AttemptId("anthropic-stream")
  private var sawStart       = false
  private var sawMessageStop = false
  private var stopReason: Option[String] = None
  private var inputTokens  = 0L
  private var outputTokens = 0L
  private val blocks       =
    scala.collection.mutable.SortedMap[Int, AnthropicStreamParser.BlockAcc]()

  def feed(data: String): Either[RaiderError, Vector[ModelEvent]] =
    data.fromJson[AnthropicStreamParser.EventWire] match
      case Left(err) =>
        Left(RaiderError.StreamProtocol(
          s"unparsable anthropic event: ${err.take(140)}"))
      case Right(ev) => handle(ev)

  def finish(): Either[RaiderError, Vector[ModelEvent]] =
    if !sawMessageStop then
      Left(RaiderError.StreamProtocol(
        "SSE ended before message_stop (truncated anthropic stream)"))
    else if !sawStart then
      Left(RaiderError.StreamProtocol("message_stop without message_start"))
    else
      stopReason match
        case None =>
          Left(RaiderError.StreamProtocol(
            "message_stop without any stop_reason (incomplete semantics)"))
        case Some(reason) =>
          Right(Vector(
            ModelEvent.UsageObserved(attempt,
              Usage(inputTokens, outputTokens, None), priceKnown = false),
            ModelEvent.Finished(attempt, reason)))

  private def handle(ev: AnthropicStreamParser.EventWire)
      : Either[RaiderError, Vector[ModelEvent]] =
    val evs = Vector.newBuilder[ModelEvent]
    ev.`type` match
      case "ping" => Right(Vector.empty) // documented ignore
      case "error" =>
        val t = ev.error.flatMap(_.`type`).getOrElse("unknown")
        val m = ev.error.flatMap(_.message).getOrElse("")
        Left(RaiderError.ProviderUnavailable(
          s"anthropic error event ($t): $m"))
      case "message_start" =>
        attempt = AttemptId(ev.message.flatMap(_.id).getOrElse("anthropic-stream"))
        sawStart = true
        ev.message.flatMap(_.usage).foreach(u =>
          u.input_tokens.foreach(inputTokens = _))
        Right(Vector(ModelEvent.Started(attempt)))
      case "content_block_start" =>
        ev.index match
          case None =>
            Left(RaiderError.StreamProtocol(
              "content_block_start without index"))
          case Some(idx) =>
            ev.content_block match
              case None =>
                Left(RaiderError.StreamProtocol(
                  "content_block_start without content_block"))
              case Some(cb) =>
                cb.`type` match
                  case "text" =>
                    blocks(idx) = AnthropicStreamParser.BlockAcc(kind =
                      AnthropicStreamParser.TextBlock)
                    Right(Vector.empty)
                  case "tool_use" =>
                    blocks(idx) = AnthropicStreamParser.BlockAcc(
                      kind = AnthropicStreamParser.ToolBlock,
                      toolId = Some(ToolCallId(
                        cb.id.getOrElse(s"tool_use_$idx"))),
                      toolName = Some(cb.name.getOrElse("")))
                    Right(Vector.empty)
                  case other =>
                    Left(RaiderError.StreamProtocol(
                      s"unknown content block type '$other' (index $idx)"))
      case "content_block_delta" =>
        ev.index match
          case None =>
            Left(RaiderError.StreamProtocol("content_block_delta without index"))
          case Some(idx) =>
            blocks.get(idx) match
              case None =>
                Left(RaiderError.StreamProtocol(
                  s"content_block_delta for unknown index $idx"))
              case Some(acc) =>
                ev.delta match
                  case None =>
                    Left(RaiderError.StreamProtocol(
                      "content_block_delta without delta"))
                  case Some(d) =>
                    d.`type` match
                      case Some("text_delta") =>
                        val t = d.text.getOrElse("")
                        if t.isEmpty then Right(Vector.empty)
                        else Right(Vector(ModelEvent.TextDelta(attempt, t)))
                      case Some("input_json_delta") if acc.kind == AnthropicStreamParser.ToolBlock =>
                        acc.args ++= d.partial_json.getOrElse("")
                        Right(Vector.empty)
                      case Some(other) =>
                        Left(RaiderError.StreamProtocol(
                          s"unsupported delta type '$other' (index $idx)"))
                      case None =>
                        Left(RaiderError.StreamProtocol(
                          "content_block_delta without delta type"))
      case "content_block_stop" =>
        ev.index match
          case None =>
            Left(RaiderError.StreamProtocol("content_block_stop without index"))
          case Some(idx) =>
            blocks.remove(idx) match
              case None =>
                Left(RaiderError.StreamProtocol(
                  s"content_block_stop for unknown index $idx"))
              case Some(acc) =>
                acc.kind match
                  case AnthropicStreamParser.TextBlock => Right(Vector.empty)
                  case AnthropicStreamParser.ToolBlock =>
                    Right(Vector(ModelEvent.ToolCallReady(attempt,
                      acc.toolId.getOrElse(ToolCallId(s"tool_use_$idx")),
                      acc.toolName.getOrElse(""), acc.args.result())))
      case "message_delta" =>
        ev.delta.foreach(d => d.stop_reason.foreach { r =>
          if stopReason.isEmpty then stopReason = Some(r)
        })
        ev.usage.foreach(u => u.output_tokens.foreach(outputTokens = _)) // CUMULATIVE: overwrite
        Right(Vector.empty)
      case "message_stop" =>
        sawMessageStop = true
        Right(Vector.empty)
      case other =>
        // unknown OPTIONAL event: fixed (ignored), never a success string (§4)
        Right(Vector.empty)

object AnthropicStreamParser:

  private[anthropic] sealed trait BlockKind
  private[anthropic] case object TextBlock extends BlockKind
  private[anthropic] case object ToolBlock extends BlockKind

  private[anthropic] final case class BlockAcc(
    kind: BlockKind,
    toolId: Option[ToolCallId] = None,
    toolName: Option[String]   = None,
    args: StringBuilder        = new StringBuilder
  )

  // ---- wire shapes (flat; unknown fields ignored by design) ----
  private[anthropic] final case class UsageWire(input_tokens: Option[Long],
                                     output_tokens: Option[Long])
  private[anthropic] object UsageWire:
    given JsonDecoder[UsageWire] = DeriveJsonDecoder.gen[UsageWire]

  private[anthropic] final case class MsgWire(id: Option[String],
                                   usage: Option[UsageWire])
  private[anthropic] object MsgWire:
    given JsonDecoder[MsgWire] = DeriveJsonDecoder.gen[MsgWire]

  private[anthropic] final case class BlockWire(`type`: String, id: Option[String],
                                     name: Option[String])
  private[anthropic] object BlockWire:
    given JsonDecoder[BlockWire] = DeriveJsonDecoder.gen[BlockWire]

  private[anthropic] final case class DeltaWire(`type`: Option[String],
                                     text: Option[String],
                                     partial_json: Option[String],
                                     stop_reason: Option[String])
  private[anthropic] object DeltaWire:
    given JsonDecoder[DeltaWire] = DeriveJsonDecoder.gen[DeltaWire]

  private[anthropic] final case class ErrWire(`type`: Option[String],
                                   message: Option[String])
  private[anthropic] object ErrWire:
    given JsonDecoder[ErrWire] = DeriveJsonDecoder.gen[ErrWire]

  /** Probe for the error envelope (also used by the non-streaming decoder
    * for error-after-200). */
  private[anthropic] final case class ErrorProbe(`type`: Option[String],
                                                 error: Option[ErrWire]):
    def isError: Boolean    = `type`.contains("error")
    def typeName: String    = error.flatMap(_.`type`).getOrElse("unknown")
    def errorMessage: String = error.flatMap(_.message).getOrElse("")
  private[anthropic] object ErrorProbe:
    given JsonDecoder[ErrorProbe] = DeriveJsonDecoder.gen[ErrorProbe]

  private[anthropic] final case class EventWire(
    `type`: String,
    message: Option[MsgWire],
    index: Option[Int],
    content_block: Option[BlockWire],
    delta: Option[DeltaWire],
    usage: Option[UsageWire],
    error: Option[ErrWire]
  )
  private[anthropic] object EventWire:
    given JsonDecoder[EventWire] = DeriveJsonDecoder.gen[EventWire]
end AnthropicStreamParser
