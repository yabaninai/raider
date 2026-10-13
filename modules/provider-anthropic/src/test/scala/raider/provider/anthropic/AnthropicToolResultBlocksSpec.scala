package raider.provider.anthropic

import raider.core.*
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.json.ast.Json
import zio.test.*
import zio.{Chunk, ZIO}

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-133: tool_result content is a STRUCTURED text-block array on the
  * Anthropic wire. TRC-01 pins the exact rendered block JSON on a live stand
  * round (payload verbatim inside "text", tool_use_id intact, round completes).
  * TRC-02 pins encoder tolerance at unit level: the loop envelope payload may
  * be any JSON (string or object) — both render into the text block with the
  * payload carried verbatim; a malformed envelope stays a typed InputValidation
  * failure (existing behavior, not weakened). Local JDK HttpServer stand on the
  * loopback only — no real network, no paid calls.
  */
object AnthropicToolResultBlocksSpec extends ZIOSpecDefault:

  private val Key = "test-key-123"

  private final case class ReqWire(
      model: String,
      max_tokens: Int,
      messages: List[ReqMessage],
      stream: Boolean
  )

  private final case class ReqMessage(role: String, content: Json)

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  private final case class Captured(body: String)

  private final case class StandHandle(
      url: String,
      server: HttpServer,
      captured: ConcurrentLinkedQueue[Captured]
  ):
    def stop(): Unit = server.stop(0)

    def requests: List[Captured] =
      val it = captured.iterator()
      val out = List.newBuilder[Captured]
      while it.hasNext() do out += it.next()
      out.result()

  private def errBody(t: String, m: String): String =
    s"""{"type":"error","error":{"type":"$t","message":"$m"}}"""

  private val toolUseJson: String =
    """{"id":"msg_blk_t1","type":"message","role":"assistant","content":[{"type":"text","text":"checking"},{"type":"tool_use","id":"toolu_b1","name":"lookup","input":{"q":"x"}}],"model":"claude-stand","stop_reason":"tool_use","usage":{"input_tokens":11,"output_tokens":5}}"""

  private val finalJson: String =
    """{"id":"msg_blk_t2","type":"message","role":"assistant","content":[{"type":"text","text":"blocks final"}],"model":"claude-stand","stop_reason":"end_turn","usage":{"input_tokens":31,"output_tokens":7}}"""

  private def startStand(): StandHandle =
    val captured = new ConcurrentLinkedQueue[Captured]
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/messages",
      (exchange: HttpExchange) =>
        try
          def reply(code: Int, body: String): Unit =
            val bytes = body.getBytes(UTF_8)
            exchange.sendResponseHeaders(code, bytes.length)
            exchange.getResponseBody.write(bytes)
          val auth = Option(exchange.getRequestHeaders.getFirst("x-api-key"))
          auth match
            case None =>
              reply(401, errBody("authentication_error", "missing x-api-key"))
            case Some(a) if a != Key =>
              reply(401, errBody("authentication_error", "invalid x-api-key"))
            case Some(_) =>
              val raw =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              captured.add(Captured(raw))
              raw.fromJson[ReqWire] match
                case Left(_) =>
                  reply(400, errBody("invalid_request_error", "bad body"))
                case Right(req) =>
                  val hasToolResult = req.messages.exists(m =>
                    m.content match
                      case Json.Arr(blocks) =>
                        blocks.exists {
                          case Json.Obj(fields) =>
                            fields.exists((k, v) =>
                              k == "type" && v == Json.Str("tool_result")
                            )
                          case _ => false
                        }
                      case _ => false
                  )
                  if req.model == "boom" then
                    reply(500, errBody("api_error", "upstream exploded"))
                  else if hasToolResult then reply(200, finalJson)
                  else reply(200, toolUseJson)
        finally exchange.close()
    )
    server.start()
    StandHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      captured
    )

  private def fieldOf(block: Json, name: String): Option[Json] = block match
    case Json.Obj(fields) => fields.find((k, _) => k == name).map(_._2)
    case _                => None

  private def blocksOf(content: Json): collection.Seq[Json] = content match
    case Json.Arr(blocks) => blocks
    case other            => Seq(other)

  /** One full tool round against the stand; returns (events, requests). */
  private def round(
      handle: StandHandle
  ): ZIO[Any, Throwable, (Vector[ModelEvent], List[Captured])] =
    for
      backend <- AnthropicMessagesBackend.make(
        AnthropicMessagesBackend.Config
          .make(handle.url, Key, callTimeoutMs = 10000L)
      )
      events <- ZIO.scoped(
        backend
          .stream(
            ModelRequest(
              "block-model",
              List(
                RequestMessage("user", "find x"),
                RequestMessage(
                  "assistant",
                  """{"text":"checking","tool_calls":[{"id":"toolu_b1","name":"lookup","arguments":"{\"q\":\"x\"}"}]}"""
                ),
                RequestMessage(
                  "tool",
                  """{"tool_call_id":"toolu_b1","output":{"echo":{"q":"x"}}}"""
                )
              ),
              64,
              List(
                ToolDefinition(
                  name = "lookup",
                  description = "test echo tool",
                  parametersJsonSchema = """{"type":"object"}"""
                )
              )
            )
          )
          .runCollect
          .map(_.toVector)
      )
    yield (events, handle.requests)

  def spec = suite("AnthropicToolResultBlocksSpec (RAI-133)")(
    test(
      "TRC-01: tool_result content is an exact structured text-block array on the wire; payload verbatim; round completes"
    ) {
      ZIO.scoped {
        ZIO
          .acquireRelease(ZIO.succeed(startStand()))(h => ZIO.succeed(h.stop()))
          .flatMap { handle =>
            for
              (events, reqs) <- round(handle)
              toolReq = reqs.find(_.body.contains("tool_result"))
              rawExact = toolReq.exists(
                _.body.contains(
                  """{"type":"tool_result","tool_use_id":"toolu_b1","content":[{"type":"text","text":"{\"echo\":{\"q\":\"x\"}}"}]}"""
                )
              )
              parsed <- ZIO
                .fromOption(toolReq)
                .flatMap(c =>
                  ZIO
                    .fromEither(c.body.fromJson[ReqWire])
                    .mapError(e => new RuntimeException(e))
                )
              toolMsg = parsed.messages.find { m =>
                blocksOf(m.content).exists { b =>
                  fieldOf(b, "type").exists(_ == Json.Str("tool_result"))
                }
              }
              block = toolMsg.flatMap { m =>
                blocksOf(m.content).find { b =>
                  fieldOf(b, "type").exists(_ == Json.Str("tool_result"))
                }
              }
              blockDefined = block.isDefined
              blockId = block.flatMap(b => fieldOf(b, "tool_use_id"))
              blockContent = block.flatMap(b => fieldOf(b, "content"))
              expectedContent = Some(
                Json.Arr(
                  Chunk(
                    Json.Obj(
                      ("type", Json.Str("text")),
                      ("text", Json.Str("{\"echo\":{\"q\":\"x\"}}"))
                    )
                  )
                )
              )
              finalText = events.collect { case ModelEvent.TextDelta(_, t) =>
                t
              }.mkString
              lastEvent = events.lastOption
            yield assertTrue(
              // exact rendered block JSON on the wire (bit-for-bit payload)
              rawExact,
              blockDefined,
              blockId == Some(Json.Str("toolu_b1")),
              blockContent == expectedContent,
              // the round completes: final text after the tool_result round
              finalText == "blocks final",
              lastEvent == Some(
                ModelEvent.Finished(AttemptId("msg_blk_t2"), "end_turn")
              )
            )
          }
      }
    },
    test(
      "TRC-02: encoder tolerance — payload may be a JSON string or object (both verbatim in the text block); malformed envelope stays typed"
    ) {
      // encodeRequest lives on the companion object (private[anthropic]) —
      // no backend instance or network needed
      val base = List(RequestMessage("user", "find x"))
      val objEnv = RequestMessage(
        "tool",
        """{"tool_call_id":"t1","output":{"echo":{"q":"x"}}}"""
      )
      val strEnv = RequestMessage(
        "tool",
        """{"tool_call_id":"t1","output":"plain string output"}"""
      )
      val badEnv = RequestMessage("tool", "not our envelope at all")
      val encObj = AnthropicMessagesBackend.encodeRequest(
        ModelRequest("m", base :+ objEnv, 64),
        streaming = false
      )
      val encStr = AnthropicMessagesBackend.encodeRequest(
        ModelRequest("m", base :+ strEnv, 64),
        streaming = false
      )
      val encBad = AnthropicMessagesBackend.encodeRequest(
        ModelRequest("m", base :+ badEnv, 64),
        streaming = false
      )
      assertTrue(
        encObj.toOption.get.contains(
          """{"type":"text","text":"{\"echo\":{\"q\":\"x\"}}"}"""
        ),
        encStr.toOption.get.contains(
          """{"type":"text","text":"\"plain string output\""}"""
        ),
        encBad match
          case Left(RaiderError.InputValidation(msg)) =>
            msg.contains("tool message envelope is not ours")
          case _ => false
      )
    }
  )

end AnthropicToolResultBlocksSpec
