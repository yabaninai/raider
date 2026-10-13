package raider.provider.anthropic

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, MicroUsd}
import raider.runtime.loop.AgentLoop
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.json.ast.Json
import zio.test.*
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-133 TRC-03: cross-wire tool-round parity. The SAME scripted tool
  * scenario (user task -> model asks for `lookup` -> local echo tool runs ->
  * model answers) runs through the SAME AgentLoop over BOTH local stands — the
  * OpenAI-compatible wire (modules/provider-chat, sibling dependency) and the
  * Anthropic wire. Identical observable semantics are asserted per wire and
  * across wires: same final answer, tool executed once with the same payload
  * visible on each wire (role "tool" message vs native tool_result block),
  * identical attempt counts, settled ledger, no permit leak. Local JDK
  * HttpServer stands on the loopback only — no real network, no paid calls; 3
  * repetitions pin determinism.
  */
object CrossWireToolParitySpec extends ZIOSpecDefault:

  private val Key = "test-key-123"
  private val Model = "par-model"
  private val Answer = "parity final answer"
  private val ToolPayload = """{"echo":{"q":"x"}}"""

  private final case class Outcome(
      answer: String,
      attempts: Int,
      payloadOnWire: Boolean,
      settledActive: MicroUsd,
      modelFree: Int,
      toolFree: Int
  )

  // ---------- OpenAI-compatible stand (mirror of OpenAIChatStandSpec) ----------
  private final case class OaReq(model: String, messages: List[OaMsg])
  private final case class OaMsg(role: String, content: String)

  private object OaReq:
    given JsonDecoder[OaMsg] = DeriveJsonDecoder.gen[OaMsg]
    given JsonDecoder[OaReq] = DeriveJsonDecoder.gen[OaReq]

  private final case class OaHandle(
      url: String,
      server: HttpServer,
      bodies: ConcurrentLinkedQueue[String]
  ):
    def stop(): Unit = server.stop(0)

    def snapshot: List[String] =
      val it = bodies.iterator(); val out = List.newBuilder[String]
      while it.hasNext() do out += it.next()
      out.result()

  private def startOpenAiStand(): OaHandle =
    val bodies = new ConcurrentLinkedQueue[String]
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) =>
        try
          def reply(code: Int, body: String): Unit =
            val bytes = body.getBytes(UTF_8)
            exchange.sendResponseHeaders(code, bytes.length)
            exchange.getResponseBody.write(bytes)
          val auth =
            Option(exchange.getRequestHeaders.getFirst("Authorization"))
          auth match
            case Some(a) if a == s"Bearer $Key" =>
              val body =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              bodies.add(body)
              body.fromJson[OaReq] match
                case Left(_) => reply(400, """{"error":{"message":"bad"}}""")
                case Right(req) =>
                  val hasToolMsg = req.messages.exists(_.role == "tool")
                  if !hasToolMsg then
                    reply(
                      200,
                      """{"id":"chatcmpl-par-1","choices":[{"index":0,
                        "message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_p","type":"function",
                        "function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}}]},
                        "finish_reason":"tool_calls"}],
                        "usage":{"prompt_tokens":11,"completion_tokens":5}}"""
                    )
                  else
                    reply(
                      200,
                      """{"id":"chatcmpl-par-2","choices":[{"index":0,
                        "message":{"role":"assistant","content":"parity final answer"}
                        }],"finish_reason":"stop",
                        "usage":{"prompt_tokens":31,"completion_tokens":7}}"""
                    )
            case _ => reply(401, """{"error":{"message":"bad key"}}""")
        finally exchange.close()
    )
    server.start()
    OaHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      bodies
    )

  // ---------- Anthropic-compatible stand (mirror of AnthropicMessagesStandSpec) ----------
  private final case class AnReq(model: String, messages: List[AnMsg])
  private final case class AnMsg(role: String, content: Json)

  private object AnReq:
    given JsonDecoder[AnMsg] = DeriveJsonDecoder.gen[AnMsg]
    given JsonDecoder[AnReq] = DeriveJsonDecoder.gen[AnReq]

  private final case class AnHandle(
      url: String,
      server: HttpServer,
      bodies: ConcurrentLinkedQueue[String]
  ):
    def stop(): Unit = server.stop(0)

    def snapshot: List[String] =
      val it = bodies.iterator(); val out = List.newBuilder[String]
      while it.hasNext() do out += it.next()
      out.result()

  private def startAnthropicStand(): AnHandle =
    val bodies = new ConcurrentLinkedQueue[String]
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
            case Some(a) if a == Key =>
              val body =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              bodies.add(body)
              body.fromJson[AnReq] match
                case Left(_) =>
                  reply(
                    400,
                    """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""
                  )
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
                  if !hasToolResult then
                    reply(
                      200,
                      """{"id":"msg_par_1","type":"message","role":"assistant",
                        "content":[{"type":"text","text":"checking"},
                        {"type":"tool_use","id":"toolu_p","name":"lookup","input":{"q":"x"}}],
                        "model":"claude-stand","stop_reason":"tool_use",
                        "usage":{"input_tokens":11,"output_tokens":5}}"""
                    )
                  else
                    reply(
                      200,
                      """{"id":"msg_par_2","type":"message","role":"assistant",
                        "content":[{"type":"text","text":"parity final answer"}],
                        "model":"claude-stand","stop_reason":"end_turn",
                        "usage":{"input_tokens":31,"output_tokens":7}}"""
                    )
            case _ =>
              reply(
                401,
                """{"type":"error","error":{"type":"authentication_error","message":"bad key"}}"""
              )
        finally exchange.close()
    )
    server.start()
    AnHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      bodies
    )

  // ---------- shared loop driver ----------
  private def limits(): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxAttempts = 8,
      maxConcurrentLlm = 1,
      maxConcurrentTools = 1
    )

  private def echoLookup: Tool = new Tool:
    def name = "lookup"
    def version = 1
    def description = "parity echo tool"
    def recovery = RecoveryClass.ReadOnly
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(args: String) = ZIO.succeed(s"""{"echo":$args}""")

  private def runScenario(
      backend: ModelBackend,
      getBodies: () => List[String],
      wireTag: String
  ): ZIO[Any, Throwable, Outcome] =
    for
      adm <- Admission.make(limits())
      root = AgentLoop.newRoot()
      registry <- ZIO
        .fromEither(ToolRegistry.of(echoLookup))
        .mapError(e => new RuntimeException(e.toString))
      loop = AgentLoop(backend, registry, Model, adm, root)
      answer <- loop
        .runText(
          List(RequestMessage("user", "task")),
          limits().fold(e => throw new RuntimeException(e.toString), identity),
          maxRounds = 4
        )
        .mapError(e => new RuntimeException(e.toString))
      usage <- adm.budgetLedger.usage
      tries <- adm.attempts(root)
      modelFree <- adm.availableSlots(SlotKind.Model)
      toolFree <- adm.availableSlots(SlotKind.Tool)
      bodies = getBodies()
      // the payload rides inside a JSON string on BOTH wires (tool-role
      // message content on OpenAI, text block on Anthropic) — quotes are
      // escaped there, so match the escaped form
      payloadEscaped = ToolPayload.replace("\"", "\\\"")
      payloadOnWire = bodies.exists(b =>
        b.contains(ToolPayload) || b.contains(payloadEscaped)
      )
      _ <-
        if payloadOnWire then ZIO.unit
        else
          ZIO.fail(
            new RuntimeException(
              s"$wireTag: tool payload $ToolPayload never reached the wire; bodies=${bodies.mkString(" | ")}"
            )
          )
    yield Outcome(
      answer = answer,
      attempts = tries,
      payloadOnWire = payloadOnWire,
      settledActive = usage.reservedActive,
      modelFree = modelFree,
      toolFree = toolFree
    )

  private def runOnce: ZIO[Any, Throwable, (Outcome, Outcome)] =
    ZIO.scoped {
      for
        oa <- ZIO.acquireRelease(ZIO.succeed(startOpenAiStand()))(h =>
          ZIO.succeed(h.stop())
        )
        oaBackend <- raider.provider.chat.OpenAIChatBackend.make(
          raider.provider.chat.OpenAIChatBackend.Config.make(oa.url, Key)
        )
        oaOut <- runScenario(oaBackend, () => oa.snapshot, "openai")
        an <- ZIO.acquireRelease(ZIO.succeed(startAnthropicStand()))(h =>
          ZIO.succeed(h.stop())
        )
        anBackend <- AnthropicMessagesBackend.make(
          AnthropicMessagesBackend.Config
            .make(an.url, Key, callTimeoutMs = 10000L)
        )
        anOut <- runScenario(anBackend, () => an.snapshot, "anthropic")
      yield (oaOut, anOut)
    }

  def spec = suite("CrossWireToolParitySpec (RAI-133 TRC-03)")(
    test(
      "same tool scenario over OpenAI and Anthropic stands via the same AgentLoop — identical observable semantics; 3 repetitions"
    ) {
      def perWire(o: Outcome) = assertTrue(
        o.answer == Answer,
        o.attempts == 3, // 2 model dispatches + 1 tool
        o.payloadOnWire,
        o.settledActive == MicroUsd(0L),
        o.modelFree == 1,
        o.toolFree == 1
      )
      for
        (oa1, an1) <- runOnce
        extras <- ZIO.collectAll(Range.inclusive(1, 2).map(_ => runOnce))
      yield assertTrue(
        oa1.answer == an1.answer,
        oa1.attempts == an1.attempts,
        oa1.settledActive == an1.settledActive
      ) && perWire(oa1) && perWire(an1) && assertTrue(
        extras.forall { (oa, an) =>
          oa.answer == Answer && an.answer == Answer &&
          oa.attempts == 3 && an.attempts == 3 &&
          oa.payloadOnWire && an.payloadOnWire &&
          oa.settledActive == MicroUsd(0L) && an.settledActive == MicroUsd(0L)
        }
      )
    }
  )

end CrossWireToolParitySpec
