package raider.provider.anthropic

import raider.core.*
import raider.provider.chat.stream.SseFramer
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.loop.AgentLoop
import zio.json.{DecoderOps, JsonDecoder, DeriveJsonDecoder}
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, ZIO}

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-013 slice acceptance (ANTH-01/02/03 skeleton) against a LOCAL Scala
  * stand (JDK HttpServer, loopback, ephemeral port). No network, no spend,
  * NEVER the llama.cpp endpoint. Frames are fragmented across 2-byte HTTP
  * chunks — including mid-multi-byte-UTF-8 boundaries.
  */
object AnthropicMessagesSpec extends ZIOSpecDefault:

  private val Key = "anthropic-key-1"
  private val ApiVersion = AnthropicMessagesBackend.PinnedApiVersion

  // ---------- stand ----------

  private final case class ReqWire(model: String, messages: List[ReqMessage])

  private final case class ReqMessage(
      role: String,
      content: Option[zio.json.ast.Json]
  )

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  private final case class StandHandle(
      url: String,
      server: HttpServer,
      clientClosed: AtomicBoolean,
      requestSeen: AtomicBoolean
  ):
    def stop(): Unit = server.stop(0)

  private def sseFrames(frames: Seq[String]): Array[Byte] =
    frames.map(f => s"data: $f\n\n").mkString.getBytes(UTF_8)

  private def toolSse: Seq[String] = Seq(
    """{"type":"message_start","message":{"id":"amsg_2","usage":{"input_tokens":15,"output_tokens":1}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"callA","name":"lookup"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":":\"x\"}"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"callB","name":"lookup"}}""",
    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"n\":1}"}}""",
    """{"type":"content_block_stop","index":1}""",
    """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
    """{"type":"message_delta","delta":{},"usage":{"output_tokens":12}}""",
    """{"type":"message_stop"}"""
  )

  private def finalTextSse: Seq[String] = Seq(
    """{"type":"message_start","message":{"id":"amsg_3","usage":{"input_tokens":31,"output_tokens":1}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"anthropic final"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}""",
    """{"type":"message_stop"}"""
  )

  private def startStand(): StandHandle =
    val closed = new AtomicBoolean(false)
    val requestSeen = new AtomicBoolean(false)
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/messages",
      (ex: HttpExchange) =>
        try
          requestSeen.set(true)
          def replyError(status: Int, t: String, m: String): Unit =
            val body =
              s"""{"type":"error","error":{"type":"$t","message":"$m"}}"""
                .getBytes(UTF_8)
            ex.sendResponseHeaders(status, body.length)
            ex.getResponseBody.write(body)
          def sse(frames: Seq[String]): Unit =
            ex.getResponseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val bytes = sseFrames(frames)
            var i = 0
            while i < bytes.length do // fragment EVERY frame into 2-byte chunks
              ex.getResponseBody.write(
                bytes.slice(i, math.min(i + 2, bytes.length))
              )
              ex.getResponseBody.flush()
              i += 2
            ex.getResponseBody.flush()
          def park(): Unit =
            try
              var i = 0
              while i < 100 && !closed.get do
                ex.getResponseBody.write(": keepalive\n\n".getBytes(UTF_8))
                ex.getResponseBody.flush()
                Thread.sleep(100)
                i += 1
            catch case _: Exception => closed.set(true)
          val auth = Option(ex.getRequestHeaders.getFirst("x-api-key"))
          val version =
            Option(ex.getRequestHeaders.getFirst("anthropic-version"))
          auth match
            case Some(a) if a != Key =>
              replyError(401, "authentication_error", "invalid x-api-key")
            case Some(_) if !version.contains(ApiVersion) =>
              replyError(
                400,
                "invalid_request_error",
                s"anthropic-version must be $ApiVersion"
              )
            case Some(_) =>
              val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
              body.fromJson[ReqWire] match
                case Left(_) =>
                  replyError(400, "invalid_request_error", "bad body")
                case Right(req) =>
                  // tool results travel as NATIVE tool_result blocks in a user
                  // message (§4) — detect them in the content, not the role
                  val hasToolMsg = req.messages.exists(m =>
                    m.content.exists(_.toString.contains("tool_result"))
                  )
                  req.model match
                    case "ok" =>
                      val text =
                        """{"id":"amsg_0","content":[{"type":"text","text":"anthropic ok"}],"stop_reason":"end_turn","usage":{"input_tokens":13,"output_tokens":4}}"""
                      val b = text.getBytes(UTF_8)
                      ex.sendResponseHeaders(200, b.length)
                      ex.getResponseBody.write(b)
                    case "tool" if !hasToolMsg =>
                      val text =
                        """{"id":"amsg_1","content":[{"type":"tool_use","id":"callA","name":"lookup","input":{"q":"x"}},{"type":"tool_use","id":"callB","name":"lookup","input":{"n":1}}],"stop_reason":"tool_use","usage":{"input_tokens":15,"output_tokens":6}}"""
                      val b = text.getBytes(UTF_8)
                      ex.sendResponseHeaders(200, b.length)
                      ex.getResponseBody.write(b)
                    case "tool" =>
                      val text =
                        """{"id":"amsg_9","content":[{"type":"text","text":"anthropic final"}],"stop_reason":"end_turn","usage":{"input_tokens":40,"output_tokens":3}}"""
                      val b = text.getBytes(UTF_8)
                      ex.sendResponseHeaders(200, b.length)
                      ex.getResponseBody.write(b)
                    case "stream-ok" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_1","usage":{"input_tokens":9,"output_tokens":1}}}""",
                          """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                          """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello "}}""",
                          """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"✓"}}""",
                          """{"type":"content_block_stop","index":0}""",
                          """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
                          """{"type":"message_stop"}"""
                        )
                      )
                    case "stream-tool" if !hasToolMsg => sse(toolSse)
                    case "stream-tool"                => sse(finalTextSse)
                    case "stream-maxtokens" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_4","usage":{"input_tokens":8,"output_tokens":1}}}""",
                          """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                          """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial out"}}""",
                          """{"type":"content_block_stop","index":0}""",
                          """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":3}}""",
                          """{"type":"message_stop"}"""
                        )
                      )
                    case "stream-error" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_5","usage":{"input_tokens":2,"output_tokens":1}}}""",
                          """{"type":"error","error":{"type":"overloaded_error","message":"overloaded now"}}"""
                        )
                      )
                    case "stream-truncated" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_6","usage":{"input_tokens":3,"output_tokens":1}}}""",
                          """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cut"}}"""
                        )
                      )
                      ex.getResponseBody.close()
                    case "stream-unknown-block" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_7","usage":{"input_tokens":1,"output_tokens":1}}}""",
                          """{"type":"content_block_start","index":0,"content_block":{"type":"mystery_block"}}"""
                        )
                      )
                    case "stream-unknown-delta" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_8","usage":{"input_tokens":1,"output_tokens":1}}}""",
                          """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                          """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","text":"hidden"}}"""
                        )
                      )
                    case "stream-parked" =>
                      sse(
                        Seq(
                          """{"type":"message_start","message":{"id":"amsg_10","usage":{"input_tokens":4,"output_tokens":1}}}""",
                          """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                          """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"half"}}""",
                          """{"type":"content_block_stop","index":0}"""
                        )
                      )
                      park() // parked between block_stop and message_stop
                    case _ =>
                      replyError(400, "invalid_request_error", "no scenario")
            case None =>
              replyError(401, "authentication_error", "missing x-api-key")
        finally ex.close()
    )
    server.start()
    StandHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      closed,
      requestSeen
    )

  private def lims() = BudgetLimits.make(maxAttempts = 8)

  private def withStand[A](
      streaming: Boolean,
      callTimeoutMs: Long = 30000L,
      maxFrameBytes: Int = 1 << 20,
      apiKey: String = Key,
      apiVersion: String = ApiVersion
  )(
      use: (AnthropicMessagesBackend, StandHandle) => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.succeed(startStand()))(h =>
      ZIO.succeed(h.stop())
    ) { handle =>
      AnthropicMessagesBackend
        .make(
          AnthropicMessagesBackend.Config
            .make(handle.url, apiKey, apiVersion, callTimeoutMs),
          maxFrameBytes,
          streaming
        )
        .flatMap(use(_, handle))
    }

  private def codeOf[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  def spec = suite("Anthropic Messages slice (RAI-013)")(
    suite("ANTH-01 native semantics")(
      test("non-streaming text: events, usage unpriced, end_turn") {
        withStand(streaming = false) { (backend, _) =>
          for
            events <- ZIO.scoped(
              backend
                .stream(
                  ModelRequest("ok", List(RequestMessage("user", "u")), 64)
                )
                .runCollect
            )
            text = events.collect { case ModelEvent.TextDelta(_, t) =>
              t
            }.mkString
          yield assertTrue(
            text == "anthropic ok",
            events.head == ModelEvent.Started(AttemptId("amsg_0")),
            events.exists {
              case ModelEvent.UsageObserved(_, u, known) =>
                u == Usage(13, 4, None) && !known
              case _ => false
            },
            events.last == ModelEvent.Finished(AttemptId("amsg_0"), "end_turn"),
            backend.capabilities.streaming == CapabilityStatus.Unsupported
          )
        }
      },
      test("non-streaming tool round via AgentLoop: native tool_use, 2 tools") {
        withStand(streaming = false) { (backend, _) =>
          for
            adm <- Admission.make(lims())
            root = AgentLoop.newRoot()
            echo = new Tool:
              def name = "lookup"; def version = 1; def description = ""
              def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
              def capabilities = ToolCapabilities(concurrentSafe = false)
              def invoke(args: String) = ZIO.succeed(s"""{"got":$args}""")
            registry = ToolRegistry.of(echo).fold(e => throw e, identity)
            loop = AgentLoop(backend, registry, "tool", adm, root)
            answer <- loop.runText(
              List(RequestMessage("user", "find x")),
              lims().fold(e => throw e, identity),
              4
            )
          yield assertTrue(answer == "anthropic final")
        }
      },
      test(
        "streaming: fragmented UTF-8 across mid-character chunk boundaries"
      ) {
        withStand(streaming = true) { (backend, _) =>
          for
            events <- ZIO.scoped(
              backend.stream(ModelRequest("stream-ok", Nil, 32)).runCollect
            )
            text = events.collect { case ModelEvent.TextDelta(_, t) =>
              t
            }.mkString
          yield assertTrue(
            text == "hello ✓", // multi-byte chars split across HTTP chunks
            events.last == ModelEvent.Finished(AttemptId("amsg_1"), "end_turn"),
            backend.capabilities.streaming == CapabilityStatus.Supported
          )
        }
      },
      test(
        "streaming tool round via AgentLoop; cumulative usage counted ONCE"
      ) {
        withStand(streaming = true) { (backend, _) =>
          for
            // wire-level first: tool_use blocks → ToolCallReady at block_stop
            events <- ZIO.scoped(
              backend.stream(ModelRequest("stream-tool", Nil, 32)).runCollect
            )
            calls = events.collect {
              case ModelEvent.ToolCallReady(_, id, name, args) =>
                (id, name, args)
            }
            usages = events.collect {
              case ModelEvent.UsageObserved(_, u, known) => (u, known)
            }
            adm <- Admission.make(lims())
            root = AgentLoop.newRoot()
            echo = new Tool:
              def name = "lookup"; def version = 1; def description = ""
              def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
              def capabilities = ToolCapabilities(concurrentSafe = false)
              def invoke(args: String) = ZIO.succeed(s"""{"got":$args}""")
            registry = ToolRegistry.of(echo).fold(e => throw e, identity)
            loop = AgentLoop(backend, registry, "stream-tool", adm, root)
            answer <- loop.runText(
              List(RequestMessage("user", "find x")),
              lims().fold(e => throw e, identity),
              4
            )
          yield assertTrue(
            calls == Vector(
              (ToolCallId("callA"), "lookup", """{"q":"x"}"""),
              (ToolCallId("callB"), "lookup", """{"n":1}""")
            ),
            usages == Vector(
              (Usage(15, 12, None), false)
            ), // CUMULATIVE 12, not 5+12
            answer == "anthropic final"
          )
        }
      },
      test("max_tokens truncation passes through honestly as stop_reason") {
        withStand(streaming = true) { (backend, _) =>
          for events <- ZIO.scoped(
              backend
                .stream(ModelRequest("stream-maxtokens", Nil, 8))
                .runCollect
            )
          yield assertTrue(
            events.last == ModelEvent.Finished(
              AttemptId("amsg_4"),
              "max_tokens"
            )
          )
        }
      }
    ),
    suite("ANTH-02 truthful errors")(
      test(
        "mid-stream error event after HTTP 200 → typed, anthropic type named"
      ) {
        withStand(streaming = true) { (backend, _) =>
          for res <- ZIO.scoped(
              backend
                .stream(ModelRequest("stream-error", Nil, 8))
                .runCollect
                .either
            )
          yield assertTrue(
            res.isLeft,
            res.fold(_.code == "RA-UNAVAIL", _ => false),
            res.fold(_.detail.contains("overloaded_error"), _ => false)
          )
        }
      },
      test("truncated stream (no message_stop) → typed StreamProtocol") {
        withStand(streaming = true) { (backend, _) =>
          for res <- ZIO.scoped(
              backend
                .stream(ModelRequest("stream-truncated", Nil, 8))
                .runCollect
                .either
            )
          yield assertTrue(
            res.isLeft,
            res.fold(_.code == "RA-STREAM", _ => false)
          )
        }
      },
      test(
        "wrong api key → ProviderAuth; wrong anthropic-version → typed 400"
      ) {
        withStand(streaming = false, apiKey = "wrong") { (backend, _) =>
          for
            auth <- ZIO.scoped(
              backend.stream(ModelRequest("ok", Nil, 8)).runCollect.either
            )
            version <- withStand(streaming = false, apiVersion = "1999-01-01") {
              (b2, _) =>
                ZIO.scoped(
                  b2.stream(ModelRequest("ok", Nil, 8)).runCollect.either
                )
            }
          yield assertTrue(
            auth.fold(_.code == "RA-AUTH", _ => false),
            version.isLeft,
            version.fold(_.detail.contains("400"), _ => false)
          )
        }
      },
      test(
        "unknown content block / delta type → typed, never a success string"
      ) {
        withStand(streaming = true) { (backend, _) =>
          for
            block <- ZIO.scoped(
              backend
                .stream(ModelRequest("stream-unknown-block", Nil, 8))
                .runCollect
                .either
            )
            delta <- ZIO.scoped(
              backend
                .stream(ModelRequest("stream-unknown-delta", Nil, 8))
                .runCollect
                .either
            )
          yield assertTrue(
            block.fold(_.detail.contains("mystery_block"), _ => false),
            delta.fold(_.detail.contains("thinking_delta"), _ => false)
          )
        }
      }
    ),
    suite("ANTH-03 cancellation")(
      test(
        "cancel between block_stop and message_stop: body closed, Uncertain kept"
      ) {
        live {
          withStand(streaming = true) { (backend, handle) =>
            for
              adm <- Admission.make(lims())
              root = AgentLoop.newRoot()
              loop = AgentLoop(
                backend,
                ToolRegistry.empty,
                "stream-parked",
                adm,
                root,
                CostEstimate.Priced(MicroUsd(450L))
              )
              fiber <- loop
                .runText(
                  List(RequestMessage("user", "u")),
                  lims().fold(e => throw e, identity),
                  4
                )
                .forkDaemon
              _ <- ZIO
                .succeed(handle.requestSeen.get)
                .repeatUntil(_ == true)
                .timeout(ZDuration.fromSeconds(10))
              _ <- ZIO.sleep(ZDuration.fromMillis(150)) // block_stop delivered
              _ <- fiber.interrupt
              _ <- fiber.await
              _ <- ZIO
                .succeed(handle.clientClosed.get)
                .repeatUntil(_ == true)
                .timeout(ZDuration.fromSeconds(10))
              usage <- adm.budgetLedger.usage
            yield assertTrue(
              usage.uncertainTotal == MicroUsd(450L), // charge kept, not zeroed
              usage.reservedActive == MicroUsd(0),
              handle.clientClosed.get
            )
          }
        }
      }
    ),
    suite("framer reuse (provider-protocols §4)")(
      test("SseFramer partition property holds for an anthropic corpus") {
        val corpus = sseFrames(toolSse)
        val expected =
          val framer = new SseFramer(1 << 20)
          val parser = new AnthropicStreamParser
          val fed = framer.feed(corpus).flatMap { frames =>
            frames.foldLeft[Either[RaiderError, Vector[ModelEvent]]](
              Right(Vector.empty)
            ) { (acc, f) =>
              acc.flatMap(evs => parser.feed(f).map(evs ++ _))
            }
          }
          for
            evs <- fed
            extra <- framer.finish()
            evs2 <- extra.foldLeft[Either[RaiderError, Vector[ModelEvent]]](
              Right(evs)
            ) { (acc, f) =>
              acc.flatMap(v => parser.feed(f).map(v ++ _))
            }
            tail <- parser.finish()
          yield evs2 ++ tail
        val ks = Vector(1, 3, 8, 64, 4096)
        val results = ks.map { k =>
          val framer = new SseFramer(1 << 20)
          val parser = new AnthropicStreamParser
          val fed = SseFramer
            .partitions(corpus, k)
            .foldLeft[Either[RaiderError, Vector[ModelEvent]]](
              Right(Vector.empty)
            ) { (acc, chunk) =>
              acc.flatMap(v =>
                framer.feed(chunk).flatMap { frames =>
                  frames.foldLeft[Either[RaiderError, Vector[ModelEvent]]](
                    Right(v)
                  ) { (a, f) =>
                    a.flatMap(cur => parser.feed(f).map(cur ++ _))
                  }
                }
              )
            }
          for
            evs <- fed
            extra <- framer.finish()
            evs2 <- extra.foldLeft[Either[RaiderError, Vector[ModelEvent]]](
              Right(evs)
            ) { (a, f) =>
              a.flatMap(cur => parser.feed(f).map(cur ++ _))
            }
            tail <- parser.finish()
          yield evs2 ++ tail
        }
        assertTrue(expected.isRight, results.forall(_ == expected))
      }
    )
  )
