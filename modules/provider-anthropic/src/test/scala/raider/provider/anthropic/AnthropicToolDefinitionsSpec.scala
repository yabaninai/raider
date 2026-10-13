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

/** RAI-127: tool DEFINITIONS on the Anthropic wire (structured message
  * contract) — closing the W05 gap. Local JDK HttpServer stand on the loopback
  * (ephemeral port); no real network, no paid calls.
  *
  * The stand is DEFINITION-GATED: the stub model answers with a tool_use
  * message ONLY when the captured request carries the expected tools entry
  * (name + input_schema), so a green round proves the definitions actually
  * reached the wire. The second round (loop tool-result envelope → NATIVE
  * tool_result block) and the final text complete the full round over the stand
  * (ANTH-TD-01). An empty tools list keeps the legacy request format without a
  * tools field (ANTH-TD-02; byte-for-byte pinned at the unit level); an invalid
  * schema (empty/blank, unparsable, not a JSON object) is a typed
  * InputValidation failure BEFORE anything is sent. Streaming with tools keeps
  * the existing SSE invariants — fragmented frames, assembled input_json_delta
  * fragments, cumulative usage counted ONCE (ANTH-TD-03).
  */
object AnthropicToolDefinitionsSpec extends ZIOSpecDefault:

  private val Key = "td-key-127"

  /** The one schema the stub model looks for — the same bytes the backend must
    * ride onto the wire 1:1.
    */
  private val LookupSchema =
    """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""

  private val LookupDescription = "Look up a thing"

  private val lookupDef =
    ToolDefinition("lookup", LookupDescription, LookupSchema)

  private val ExpectedLookupSchema: Json =
    Json.decoder.decodeJson(LookupSchema).fold(sys.error(_), identity)

  // ---------- request wire view (tools included, unknown fields ignored) ---

  private final case class ReqTool(
      name: String,
      description: Option[String],
      input_schema: Option[Json]
  )

  private final case class ReqMessage(role: String, content: Json)

  private final case class ReqWire(
      model: String,
      max_tokens: Int,
      messages: List[ReqMessage],
      stream: Boolean,
      tools: Option[List[ReqTool]]
  )

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqTool] = DeriveJsonDecoder.gen[ReqTool]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  // ---------- stand responses ----------

  private val toolUseJson: String =
    """{"id":"msg_td_t1","type":"message","role":"assistant","content":[{"type":"text","text":"let me look that up"},{"type":"tool_use","id":"toolu_127","name":"lookup","input":{"q":"x"}}],"model":"claude-stand","stop_reason":"tool_use","usage":{"input_tokens":11,"output_tokens":5}}"""

  private val finalJson: String =
    """{"id":"msg_td_t2","type":"message","role":"assistant","content":[{"type":"text","text":"stand final answer"}],"model":"claude-stand","stop_reason":"end_turn","usage":{"input_tokens":31,"output_tokens":7}}"""

  private val noToolsJson: String =
    """{"id":"msg_td_none","type":"message","role":"assistant","content":[{"type":"text","text":"no definitions on wire"}],"model":"claude-stand","stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}"""

  private def streamNoToolsSse: Seq[String] = Seq(
    """{"type":"message_start","message":{"id":"amsg_td0","usage":{"input_tokens":4,"output_tokens":1}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"no definitions on wire"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
    """{"type":"message_stop"}"""
  )

  private def streamToolSse: Seq[String] = Seq(
    """{"type":"message_start","message":{"id":"amsg_td1","usage":{"input_tokens":12,"output_tokens":1}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"checking "}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_s1","name":"lookup"}}""",
    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\""}}""",
    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":":\"x\"}"}}""",
    """{"type":"content_block_stop","index":1}""",
    """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}""",
    """{"type":"message_delta","delta":{},"usage":{"output_tokens":9}}""",
    """{"type":"message_stop"}"""
  )

  private def streamFinalSse: Seq[String] = Seq(
    """{"type":"message_start","message":{"id":"amsg_td2","usage":{"input_tokens":28,"output_tokens":1}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stream final ✓"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":6}}""",
    """{"type":"message_stop"}"""
  )

  // ---------- stand ----------

  private final case class AnthropicStandHandle(
      url: String,
      server: HttpServer,
      captured: ConcurrentLinkedQueue[String]
  ):
    def stop(): Unit = server.stop(0)

    /** Snapshot of raw request bodies in arrival order. */
    def requests: List[String] =
      val it = captured.iterator()
      val out = List.newBuilder[String]
      while it.hasNext() do out += it.next()
      out.result()

  private def errBody(t: String, m: String): String =
    s"""{"type":"error","error":{"type":"$t","message":"$m"}}"""

  private def startStand(): AnthropicStandHandle =
    val captured = new ConcurrentLinkedQueue[String]
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
          def sse(frames: Seq[String]): Unit =
            exchange.getResponseHeaders
              .add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val bytes =
              frames.map(f => s"data: $f\n\n").mkString.getBytes(UTF_8)
            var i = 0
            // fragment EVERY frame into 2-byte chunks (transport invariant)
            while i < bytes.length do
              exchange.getResponseBody.write(
                bytes.slice(i, math.min(i + 2, bytes.length))
              )
              exchange.getResponseBody.flush()
              i += 2
            exchange.getResponseBody.flush()
          val auth =
            Option(exchange.getRequestHeaders.getFirst("x-api-key"))
          auth match
            case None =>
              reply(401, errBody("authentication_error", "missing x-api-key"))
            case Some(a) if a != Key =>
              reply(401, errBody("authentication_error", "invalid x-api-key"))
            case Some(_) =>
              val raw =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              captured.add(raw)
              raw.fromJson[ReqWire] match
                case Left(_) =>
                  reply(400, errBody("invalid_request_error", "bad body"))
                case Right(req) =>
                  val hasToolResult = req.messages.exists(m =>
                    blocksOf(m.content)
                      .exists(b => blockType(b).contains("tool_result"))
                  )
                  val hasDefinition = hasLookupDefinition(req)
                  req.model match
                    case "tools-stand" =>
                      if hasToolResult then reply(200, finalJson)
                      else if hasDefinition then reply(200, toolUseJson)
                      else reply(200, noToolsJson)
                    case "stream-tools-stand" =>
                      if hasToolResult then sse(streamFinalSse)
                      else if hasDefinition then sse(streamToolSse)
                      else sse(streamNoToolsSse)
                    case _ =>
                      reply(
                        400,
                        errBody("invalid_request_error", "no scenario")
                      )
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

  /** The stub model's definition gate: tool_use ONLY for exactly this entry. */
  private def hasLookupDefinition(req: ReqWire): Boolean =
    req.tools.exists(
      _.exists(t =>
        t.name == "lookup" && t.input_schema.contains(ExpectedLookupSchema)
      )
    )

  // ---------- harness ----------

  private def withStand[A](
      streaming: Boolean
  )(
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
            .make(handle.url, Key, callTimeoutMs = 5000L),
          streaming = streaming
        )
        .flatMap(use(_, handle))
    }

  def spec = suite("Anthropic tool definitions on wire (RAI-127)")(
    test(
      "ANTH-TD-01 unit: encodeRequest serializes N tools 1:1 — verbatim schema bytes, order preserved"
    ) {
      val schemaA =
        """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""
      val schemaB = """{ "type": "object" }""" // spacing must ride VERBATIM
      val input = ModelRequest(
        "m",
        List(RequestMessage("user", "find x")),
        64,
        List(
          ToolDefinition("lookup", "Look it up", schemaA),
          ToolDefinition("calc", "Calculate", schemaB)
        )
      )
      val expected =
        s"""{"model":"m","max_tokens":64,""" +
          s""""messages":[{"role":"user","content":"find x"}],""" +
          s""""tools":[{"name":"lookup","description":"Look it up",""" +
          s""""input_schema":$schemaA},""" +
          s"""{"name":"calc","description":"Calculate","input_schema":$schemaB}],""" +
          s""""stream":false}"""
      assertTrue(
        AnthropicMessagesBackend.encodeRequest(input, streaming = false) ==
          Right(expected)
      )
    },
    test(
      "ANTH-TD-02 unit: empty tools — byte-identical legacy request (no tools field)"
    ) {
      val input = ModelRequest("m", List(RequestMessage("user", "hi ✓")), 64)
      assertTrue(
        AnthropicMessagesBackend.encodeRequest(input, streaming = false) ==
          Right(
            """{"model":"m","max_tokens":64,""" +
              """"messages":[{"role":"user","content":"hi ✓"}],"stream":false}"""
          ),
        AnthropicMessagesBackend.encodeRequest(input, streaming = true) ==
          Right(
            """{"model":"m","max_tokens":64,""" +
              """"messages":[{"role":"user","content":"hi ✓"}],"stream":true}"""
          )
      )
    },
    test(
      "ANTH-TD-02 unit: invalid schemas (empty/blank/unparsable/non-object) → typed InputValidation"
    ) {
      val bad = List("", "   ", "not json{", "[1,2]", "null", "\"str\"")
      val results = bad.map { schema =>
        AnthropicMessagesBackend.encodeRequest(
          ModelRequest(
            "m",
            List(RequestMessage("user", "u")),
            8,
            List(ToolDefinition("lookup", "d", schema))
          ),
          streaming = false
        )
      }
      assertTrue(
        results.forall(_.isLeft),
        results.forall(_.fold(_.code == "RA-INP", _ => false))
      )
    },
    test(
      "ANTH-TD-01 stand: full tool round — definitions gate the tool_use, tool_result round completes"
    ) {
      withStand(streaming = false) { (backend, handle) =>
        for
          ev1 <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "tools-stand",
                  List(RequestMessage("user", "find x")),
                  64,
                  List(lookupDef)
                )
              )
              .runCollect
          )
          ev2 <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "tools-stand",
                  List(
                    RequestMessage("user", "find x"),
                    RequestMessage(
                      "tool",
                      """{"tool_call_id":"toolu_127","output":"stand tool output"}"""
                    )
                  ),
                  64,
                  List(lookupDef)
                )
              )
              .runCollect
          )
        yield
          val reqs = handle.requests
          val wire1 = reqs.head.fromJson[ReqWire]
          val wire2 = reqs.last.fromJson[ReqWire]
          val toolBlock = for
            req <- wire2.toOption
            msg <- req.messages.find(m =>
              blocksOf(m.content)
                .exists(b => blockType(b).contains("tool_result"))
            )
            block <- blocksOf(msg.content)
              .find(b => blockType(b).contains("tool_result"))
          yield block
          assertTrue(
            // stub answered tool_use ONLY because definitions arrived
            ev1.collect { case ModelEvent.ToolCallReady(_, id, n, args) =>
              (id, n, args)
            } == Vector(
              (ToolCallId("toolu_127"), "lookup", """{"q":"x"}""")
            ),
            ev1.last == ModelEvent
              .Finished(AttemptId("msg_td_t1"), "tool_use"),
            // definitions on the wire 1:1: name, description, input_schema
            wire1.map(_.tools) == Right(
              Some(
                List(
                  ReqTool(
                    "lookup",
                    Some(LookupDescription),
                    Some(ExpectedLookupSchema)
                  )
                )
              )
            ),
            // round 2: envelope → NATIVE tool_result block, correlation kept
            toolBlock.flatMap(b => fieldOf(b, "tool_use_id")) ==
              Some(Json.Str("toolu_127")),
            toolBlock.flatMap(b => fieldOf(b, "content")) ==
              Some(Json.Str("stand tool output")),
            ev2.collect { case ModelEvent.TextDelta(_, t) =>
              t
            }.mkString == "stand final answer",
            ev2.last == ModelEvent.Finished(AttemptId("msg_td_t2"), "end_turn"),
            reqs.size == 2
          )
      }
    },
    test(
      "ANTH-TD-02 stand: empty tools — request WITHOUT a tools field; stub emits no tool_use"
    ) {
      withStand(streaming = false) { (backend, handle) =>
        for events <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "tools-stand",
                  List(RequestMessage("user", "find x")),
                  64
                )
              )
              .runCollect
          )
        yield
          val reqs = handle.requests
          val parsed = reqs.head.fromJson[ReqWire]
          val topLevel = reqs.head.fromJson[Json]
          val hasToolsField = topLevel.toOption.exists {
            case Json.Obj(fields) => fields.exists(_._1 == "tools")
            case _                => false
          }
          assertTrue(
            events.collect { case ModelEvent.ToolCallReady(_, _, n, _) =>
              n
            }.isEmpty,
            events.collect { case ModelEvent.TextDelta(_, t) =>
              t
            }.mkString == "no definitions on wire",
            events.last == ModelEvent
              .Finished(AttemptId("msg_td_none"), "end_turn"),
            !hasToolsField,
            parsed.map(_.tools) == Right(None),
            parsed.map(_.max_tokens) == Right(64),
            parsed.map(_.stream) == Right(false)
          )
      }
    },
    test(
      "ANTH-TD-02 stand: invalid tool schema → typed InputValidation BEFORE any send"
    ) {
      withStand(streaming = false) { (backend, handle) =>
        val bad = List("", "   ", "not json{", "[1,2]", "\"str\"")
        for results <- ZIO.foreach(bad) { schema =>
            ZIO.scoped(
              backend
                .stream(
                  ModelRequest(
                    "tools-stand",
                    List(RequestMessage("user", "find x")),
                    64,
                    List(ToolDefinition("lookup", "d", schema))
                  )
                )
                .runCollect
                .either
            )
          }
        yield assertTrue(
          results.forall(_.isLeft),
          results.forall(_.fold(_.code == "RA-INP", _ => false)),
          handle.requests.isEmpty // nothing reached the wire
        )
      }
    },
    test(
      "ANTH-TD-03 stand: streaming round with tools — SSE invariants hold, definitions on the wire"
    ) {
      withStand(streaming = true) { (backend, handle) =>
        for
          ev1 <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "stream-tools-stand",
                  List(RequestMessage("user", "find x")),
                  64,
                  List(lookupDef)
                )
              )
              .runCollect
          )
          ev2 <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "stream-tools-stand",
                  List(
                    RequestMessage("user", "find x"),
                    RequestMessage(
                      "tool",
                      """{"tool_call_id":"toolu_s1","output":"stream tool output"}"""
                    )
                  ),
                  64,
                  List(lookupDef)
                )
              )
              .runCollect
          )
        yield
          val reqs = handle.requests
          val wire1 = reqs.head.fromJson[ReqWire]
          val wire2 = reqs.last.fromJson[ReqWire]
          assertTrue(
            // round 1: Started → text → assembled tool args → usage ONCE → honest stop
            ev1.head == ModelEvent.Started(AttemptId("amsg_td1")),
            ev1.collect { case ModelEvent.TextDelta(_, t) =>
              t
            }.mkString == "checking ",
            ev1.collect { case ModelEvent.ToolCallReady(_, id, n, args) =>
              (id, n, args)
            } == Vector(
              (ToolCallId("toolu_s1"), "lookup", """{"q":"x"}""")
            ),
            ev1.collect { case ModelEvent.UsageObserved(_, u, known) =>
              (u, known)
            } == Vector((Usage(12, 9, None), false)),
            ev1.last == ModelEvent.Finished(AttemptId("amsg_td1"), "tool_use"),
            // definitions rode the streaming request too
            wire1.map(_.tools) == Right(
              Some(
                List(
                  ReqTool(
                    "lookup",
                    Some(LookupDescription),
                    Some(ExpectedLookupSchema)
                  )
                )
              )
            ),
            wire1.map(_.stream) == Right(true),
            // round 2: tool_result → final text, end_turn
            ev2.collect { case ModelEvent.TextDelta(_, t) =>
              t
            }.mkString == "stream final ✓",
            ev2.last == ModelEvent.Finished(AttemptId("amsg_td2"), "end_turn"),
            wire2.map(_.tools.isDefined) == Right(true),
            reqs.size == 2
          )
      }
    }
  )
