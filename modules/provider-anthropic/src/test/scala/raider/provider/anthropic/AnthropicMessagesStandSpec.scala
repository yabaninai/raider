package raider.provider.anthropic

import raider.core.*
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.json.ast.Json
import zio.test.*
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-079: full tool-round acceptance for the NON-STREAMING
  * AnthropicMessagesBackend against a LOCAL Scala stand (JDK HttpServer on the
  * loopback, ephemeral port; language policy: provider test stands are Scala
  * inside the module). No real network, no paid calls.
  *
  * Unlike the RAI-013 slice (streaming + AgentLoop), this spec drives the
  * backend DIRECTLY and captures every request body on the stand so the wire
  * itself is verified: the loop tool-result envelope (role "tool") must reach
  * the provider as a NATIVE tool_result content block (type + tool_use_id +
  * string payload) inside a user message, round history must be preserved,
  * usage must be observed unpriced, and stand failures (HTTP 500 / truncated
  * body / bad auth) must fail typed and immediately — never hang, never leak
  * the key.
  */
object AnthropicMessagesStandSpec extends ZIOSpecDefault:

  private val Key = "test-key-123"

  /** Minimal request view for the stand's request-driven scenarios; message
    * content is raw JSON (plain string OR content-block array).
    */
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

  /** What the stand saw per authenticated request (headers + raw body). */
  private final case class Captured(
      apiKey: Option[String],
      apiVersion: Option[String],
      body: String
  )

  // ---------- stand ----------

  private final case class AnthropicStandHandle(
      url: String,
      server: HttpServer,
      captured: ConcurrentLinkedQueue[Captured]
  ):
    def stop(): Unit = server.stop(0)

    /** Snapshot in arrival order (single-threaded dispatcher per request). */
    def requests: List[Captured] =
      val it = captured.iterator()
      val out = List.newBuilder[Captured]
      while it.hasNext() do out += it.next()
      out.result()

  private def errBody(t: String, m: String): String =
    s"""{"type":"error","error":{"type":"$t","message":"$m"}}"""

  private val plainJson: String =
    """{"id":"msg_l20_plain","type":"message","role":"assistant","content":[{"type":"text","text":"stand hello"}],"model":"claude-stand","stop_reason":"end_turn","usage":{"input_tokens":17,"output_tokens":9}}"""

  private val toolUseJson: String =
    """{"id":"msg_l20_t1","type":"message","role":"assistant","content":[{"type":"text","text":"let me look that up"},{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"x"}}],"model":"claude-stand","stop_reason":"tool_use","usage":{"input_tokens":11,"output_tokens":5}}"""

  private val finalJson: String =
    """{"id":"msg_l20_t2","type":"message","role":"assistant","content":[{"type":"text","text":"stand final answer"}],"model":"claude-stand","stop_reason":"end_turn","usage":{"input_tokens":31,"output_tokens":7}}"""

  /** Local Anthropic-compatible stand: auth always enforced (x-api-key);
    * scenarios are request-driven (model name + tool_result presence) so one
    * server serves every test. The "trunc" scenario declares a longer body than
    * it writes and closes — a real mid-body disconnect.
    */
  private def startStand(): AnthropicStandHandle =
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
          val auth =
            Option(exchange.getRequestHeaders.getFirst("x-api-key"))
          val version =
            Option(exchange.getRequestHeaders.getFirst("anthropic-version"))
          auth match
            case None =>
              reply(401, errBody("authentication_error", "missing x-api-key"))
            case Some(a) if a != Key =>
              reply(401, errBody("authentication_error", "invalid x-api-key"))
            case Some(_) =>
              val raw =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              captured.add(Captured(auth, version, raw))
              raw.fromJson[ReqWire] match
                case Left(_) =>
                  reply(400, errBody("invalid_request_error", "bad body"))
                case Right(req) =>
                  // tool results travel as NATIVE tool_result blocks in a user
                  // message (§4) — detect them by block type, not by role
                  val hasToolResult = req.messages.exists(m =>
                    blocksOf(m.content)
                      .exists(b => blockType(b).contains("tool_result"))
                  )
                  req.model match
                    case "boom" =>
                      reply(500, errBody("api_error", "upstream exploded"))
                    case "garbage" => reply(200, "this is NOT json")
                    case "trunc"   =>
                      // declared > written, then closed: mid-body disconnect
                      exchange.sendResponseHeaders(200, 4096)
                      exchange.getResponseBody.write(
                        "partial".getBytes(UTF_8)
                      )
                    case _ if hasToolResult => reply(200, finalJson)
                    case "tool-model"       => reply(200, toolUseJson)
                    case _                  => reply(200, plainJson)
        finally exchange.close()
    )
    server.start()
    AnthropicStandHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      captured
    )

  // ---------- json helpers (version-agnostic over Json.Obj fields) ----------

  private def blocksOf(content: Json): collection.Seq[Json] = content match
    case Json.Arr(elements) => elements
    case _                  => Seq.empty

  private def fieldOf(block: Json, name: String): Option[Json] = block match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, v) => v }
    case _                => None

  private def blockType(block: Json): Option[String] =
    fieldOf(block, "type").collect { case Json.Str(t) => t }

  // ---------- harness ----------

  private def withStand[A](
      use: (AnthropicMessagesBackend, AnthropicStandHandle) => ZIO[
        Any,
        Throwable,
        A
      ]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.succeed(startStand()))(h =>
      ZIO.succeed(h.stop())
    ) { handle =>
      AnthropicMessagesBackend
        .make(
          AnthropicMessagesBackend.Config
            .make(handle.url, Key, callTimeoutMs = 5000L)
        )
        .flatMap(use(_, handle))
    }

  def spec = suite("Anthropic Messages tool round (local stand, RAI-079)")(
    test(
      "non-streaming text roundtrip: pinned headers honored, usage recorded unpriced"
    ) {
      withStand { (backend, handle) =>
        for
          events <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "plain-model",
                  List(RequestMessage("user", "hello ✓")),
                  64
                )
              )
              .runCollect
          )
          text = events.collect { case ModelEvent.TextDelta(_, t) =>
            t
          }.mkString
        yield
          val reqs = handle.requests
          val req0 = reqs.head.body.fromJson[ReqWire]
          assertTrue(
            text == "stand hello",
            events.head == ModelEvent.Started(AttemptId("msg_l20_plain")),
            events.exists {
              case ModelEvent.UsageObserved(_, u, known) =>
                u == Usage(17, 9, None) && !known
              case _ => false
            },
            events.last == ModelEvent
              .Finished(AttemptId("msg_l20_plain"), "end_turn"),
            reqs.size == 1,
            reqs.head.apiKey.contains(Key),
            reqs.head.apiVersion
              .contains(AnthropicMessagesBackend.PinnedApiVersion),
            req0.map(_.stream) == Right(false),
            req0.map(_.max_tokens) == Right(64)
          )
      }
    },
    test(
      "tool round: tool_use request → tool_result on the wire (native block, tool_use_id) → final text"
    ) {
      withStand { (backend, handle) =>
        for
          // round 1: model asks for a tool
          ev1 <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "tool-model",
                  List(RequestMessage("user", "find x")),
                  64
                )
              )
              .runCollect
          )
          // round 2: the test answers with the loop's tool-result envelope;
          // the backend must translate it into a NATIVE tool_result block
          ev2 <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "tool-model",
                  List(
                    RequestMessage("user", "find x"),
                    RequestMessage(
                      "tool",
                      """{"tool_call_id":"toolu_1","output":"stand tool output"}"""
                    )
                  ),
                  64
                )
              )
              .runCollect
          )
          text1 = ev1.collect { case ModelEvent.TextDelta(_, t) =>
            t
          }.mkString
          text2 = ev2.collect { case ModelEvent.TextDelta(_, t) =>
            t
          }.mkString
        yield
          val reqs = handle.requests
          val req1 = reqs.head.body.fromJson[ReqWire]
          val req2 =
            reqs.last.body.fromJson[ReqWire].fold(_ => None, Some(_))
          val toolBlock = for
            req <- req2
            msg <- req.messages.find(m =>
              blocksOf(m.content)
                .exists(b => blockType(b).contains("tool_result"))
            )
            block <- blocksOf(msg.content)
              .find(b => blockType(b).contains("tool_result"))
          yield block
          assertTrue(
            // round 1: text + tool_use, usage, honest stop_reason
            text1 == "let me look that up",
            ev1.head == ModelEvent.Started(AttemptId("msg_l20_t1")),
            ev1.collect {
              case ModelEvent.ToolCallReady(attempt, call, name, args) =>
                (attempt, call, name, args)
            } == Vector(
              (
                AttemptId("msg_l20_t1"),
                ToolCallId("toolu_1"),
                "lookup",
                """{"q":"x"}"""
              )
            ),
            ev1.exists {
              case ModelEvent.UsageObserved(_, u, known) =>
                u == Usage(11, 5, None) && !known
              case _ => false
            },
            ev1.last == ModelEvent.Finished(
              AttemptId("msg_l20_t1"),
              "tool_use"
            ),
            // round-1 wire: no tool_result anywhere yet
            reqs.size == 2,
            req1.map(r =>
              r.messages.exists(m =>
                blocksOf(m.content)
                  .exists(b => blockType(b).contains("tool_result"))
              )
            ) == Right(false),
            // round-2 wire: envelope → native tool_result block in a user msg
            req2.map(_.model) == Some("tool-model"),
            req2.map(_.stream) == Some(false),
            req2.map(_.max_tokens) == Some(64),
            req2.map(_.messages.count(_.role == "user")) == Some(2),
            req2.map(_.messages.head.content) == Some(Json.Str("find x")),
            toolBlock
              .flatMap(b => fieldOf(b, "tool_use_id")) == Some(
              Json.Str("toolu_1")
            ),
            toolBlock.flatMap(b => fieldOf(b, "content")) == Some(
              Json.Str("stand tool output")
            ),
            // final round: usage + end_turn
            ev2.head == ModelEvent.Started(AttemptId("msg_l20_t2")),
            text2 == "stand final answer",
            ev2.exists {
              case ModelEvent.UsageObserved(_, u, known) =>
                u == Usage(31, 7, None) && !known
              case _ => false
            },
            ev2.last == ModelEvent.Finished(AttemptId("msg_l20_t2"), "end_turn")
          )
      }
    },
    test(
      "500 → ProviderUnavailable; unparsable 200 body → StreamProtocol; mid-body disconnect → transport failure, never a hang"
    ) {
      withStand { (backend, _) =>
        for
          boom <- ZIO.scoped(
            backend
              .stream(
                ModelRequest("boom", List(RequestMessage("user", "u")), 8)
              )
              .runCollect
              .either
          )
          garbage <- ZIO.scoped(
            backend.stream(ModelRequest("garbage", Nil, 8)).runCollect.either
          )
          trunc <- ZIO.scoped(
            backend
              .stream(
                ModelRequest("trunc", List(RequestMessage("user", "u")), 8)
              )
              .runCollect
              .either
          )
        yield assertTrue(
          boom.isLeft,
          boom.fold(_.code == "RA-UNAVAIL", _ => false),
          boom.fold(_.detail.contains("HTTP 500"), _ => false),
          garbage.isLeft,
          garbage.fold(_.code == "RA-STREAM", _ => false),
          trunc.isLeft,
          trunc.fold(
            e =>
              e.code == "RA-UNAVAIL" && e.detail.contains("transport failure"),
            _ => false
          )
        )
      }
    },
    test(
      "wrong key → typed ProviderAuth; stand enforced auth; key never in details"
    ) {
      withStand { (_, handle) =>
        for
          bad <- AnthropicMessagesBackend.make(
            AnthropicMessagesBackend.Config
              .make(handle.url, "wrong-key", callTimeoutMs = 5000L)
          )
          res <- ZIO.scoped(
            bad
              .stream(
                ModelRequest("m", List(RequestMessage("user", "u")), 8)
              )
              .runCollect
              .either
          )
          detail = res.fold(_.detail, _ => "")
        yield assertTrue(
          res.isLeft,
          res.fold(_.code == "RA-AUTH", _ => false),
          !detail.contains("wrong-key"),
          handle.requests.isEmpty // rejected before any scenario dispatch
        )
      }
    }
  )
