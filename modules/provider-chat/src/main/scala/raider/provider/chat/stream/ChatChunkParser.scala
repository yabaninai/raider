package raider.provider.chat.stream

import raider.core.*
import zio.json.*

/** RAI-012: semantic Chat-Completions-chunk state machine, deliberately
  * separate from SSE framing (provider-protocols §3; reusable framing).
  *
  * Policy (finish_reason/DONE/EOF, card SSE-01/02):
  *   - first chunk's `id` fixes the attempt identity; later chunks follow it.
  *   - `delta.content` fragments become TextDelta immediately (normalized
  *     deltas).
  *   - `delta.tool_calls` fragments accumulate BY INDEX (interleaved indices
  *     supported); NOTHING is emitted while the stream runs — partial tool
  *     arguments are never executable (§8). Completed calls become
  *     ToolCallReady in index order ONLY at finish.
  *   - the FIRST finish_reason wins; a `[DONE]` sentinel confirms the end.
  *   - finish() fails typed StreamProtocol when the stream ended without a
  *     finish_reason (EOF-before-finish, or DONE-without-finish_reason).
  *   - usage-only chunks (empty choices) yield UsageObserved; token usage is
  *     recorded truthfully unpriced (no price table at this layer, never free).
  */
final class ChatChunkParser:

  private var attempt: Option[AttemptId] = None
  private var sawFinish: Option[String] = None
  private var sawDone = false

  private val calls =
    scala.collection.mutable.SortedMap[Int, ChatChunkParser.Acc]()

  def feed(data: String): Either[RaiderError, Vector[ModelEvent]] =
    if data.trim == "[DONE]" then
      sawDone = true
      Right(Vector.empty)
    else
      data.fromJson[ChatChunkParser.ChunkWire] match
        case Left(err) =>
          Left(
            RaiderError.StreamProtocol(
              s"unparsable chat chunk: ${err.take(140)}"
            )
          )
        case Right(c) =>
          val attemptId = attempt.getOrElse {
            val id = AttemptId(c.id.getOrElse("sse-stream"))
            attempt = Some(id)
            id
          }
          val evs = Vector.newBuilder[ModelEvent]
          if !started then
            started = true
            evs += ModelEvent.Started(attemptId)
          c.choices.foreach { ch =>
            ch.delta.content.foreach { t =>
              if t.nonEmpty then evs += ModelEvent.TextDelta(attemptId, t)
            }
            ch.delta.tool_calls.getOrElse(Nil).foreach { tc =>
              val acc = calls.getOrElseUpdate(
                tc.index,
                ChatChunkParser.Acc(None, new StringBuilder, new StringBuilder)
              )
              tc.id.foreach { id =>
                if acc.id.isEmpty then acc.id = Some(ToolCallId(id))
              }
              tc.function.foreach { f =>
                f.name.foreach(n => acc.name ++= n)
                f.arguments.foreach(a => acc.args ++= a)
              }
            }
            ch.finish_reason.foreach { r =>
              if sawFinish.isEmpty then sawFinish = Some(r)
            }
          }
          c.usage.foreach { u =>
            evs += ModelEvent.UsageObserved(
              attemptId,
              Usage(u.prompt_tokens, u.completion_tokens, None),
              priceKnown = false
            )
          }
          Right(evs.result())

  def finish(): Either[RaiderError, Vector[ModelEvent]] =
    sawFinish match
      case None =>
        if sawDone then
          Left(
            RaiderError.StreamProtocol(
              "SSE stream sent [DONE] without any finish_reason"
            )
          )
        else
          Left(
            RaiderError.StreamProtocol(
              "SSE stream ended before finish_reason/[DONE] (truncated stream)"
            )
          )
      case Some(reason) =>
        val attemptId = attempt.getOrElse(AttemptId("sse-stream"))
        val evs = Vector.newBuilder[ModelEvent]
        calls.toSeq.foreach { case (_, acc) =>
          val id = acc.id.getOrElse(ToolCallId(s"call_index_${calls.size}"))
          evs += ModelEvent.ToolCallReady(
            attemptId,
            id,
            acc.name.result(),
            acc.args.result()
          )
        }
        evs += ModelEvent.Finished(attemptId, reason)
        Right(evs.result())

  private var started = false

object ChatChunkParser:

  private final case class Acc(
      var id: Option[ToolCallId],
      name: StringBuilder,
      args: StringBuilder
  )

  // ---- wire shapes (explicit; unknown provider fields ignored) ----
  private final case class FnDeltaWire(
      name: Option[String],
      arguments: Option[String]
  )

  private object FnDeltaWire:
    given JsonDecoder[FnDeltaWire] = DeriveJsonDecoder.gen[FnDeltaWire]

  private final case class ToolDeltaWire(
      index: Int,
      id: Option[String],
      function: Option[FnDeltaWire]
  )

  private object ToolDeltaWire:
    given JsonDecoder[ToolDeltaWire] = DeriveJsonDecoder.gen[ToolDeltaWire]

  private final case class DeltaWire(
      content: Option[String],
      tool_calls: Option[List[ToolDeltaWire]]
  )

  private object DeltaWire:
    given JsonDecoder[DeltaWire] = DeriveJsonDecoder.gen[DeltaWire]

  private final case class ChoiceWire(
      index: Int,
      delta: DeltaWire,
      finish_reason: Option[String]
  )

  private object ChoiceWire:
    given JsonDecoder[ChoiceWire] = DeriveJsonDecoder.gen[ChoiceWire]

  private final case class UsageWire(
      prompt_tokens: Long,
      completion_tokens: Long
  )

  private object UsageWire:
    given JsonDecoder[UsageWire] = DeriveJsonDecoder.gen[UsageWire]

  private final case class ChunkWire(
      id: Option[String],
      choices: List[ChoiceWire],
      usage: Option[UsageWire]
  )

  private object ChunkWire:
    given JsonDecoder[ChunkWire] = DeriveJsonDecoder.gen[ChunkWire]

end ChatChunkParser
