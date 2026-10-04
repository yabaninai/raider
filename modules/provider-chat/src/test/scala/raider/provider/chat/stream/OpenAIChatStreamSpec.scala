package raider.provider.chat.stream

import raider.core.*
import raider.provider.chat.OpenAIChatBackend
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

/** RAI-012 slice acceptance: SSE-01 (any chunk partition ⇒ identical
  * normalized events), SSE-02 (truncated/oversize/invalid typed; partial tool
  * args never execute), SSE-03 (cancel closes the body, truthful accounting).
  * The stand is Scala (JDK HttpServer, loopback, ephemeral port); frames are
  * deliberately fragmented across HTTP chunks. */
object OpenAIChatStreamSpec extends ZIOSpecDefault:

  private val Key = "stream-key-1"

  // ---------- pure framer+parser helpers ----------

  /** Feed frames to the parser, ACCUMULATING its events (not just the error
    * channel). */
  private def pump(parser: ChatChunkParser, frames: Vector[String],
      evs: Vector[ModelEvent]): Either[RaiderError, Vector[ModelEvent]] =
    frames.foldLeft[Either[RaiderError, Vector[ModelEvent]]](Right(evs)) {
      (acc, f) => acc.flatMap(v => parser.feed(f).map(v ++ _))
    }

  /** Feed byte chunks through framer+parser; fresh pair per call. */
  private def playChunks(chunks: Vector[Array[Byte]], frameLimit: Int = 1 << 20)
      : Either[RaiderError, Vector[ModelEvent]] =
    val framer = new SseFramer(frameLimit)
    val parser = new ChatChunkParser
    val fed = chunks.foldLeft[Either[RaiderError, Vector[ModelEvent]]](
      Right(Vector.empty)) { (acc, chunk) =>
      acc.flatMap(evs => framer.feed(chunk).flatMap(pump(parser, _, evs)))
    }
    for
      evs   <- fed
      extra <- framer.finish()
      evs2  <- pump(parser, extra, evs)
      tail  <- parser.finish()
    yield evs2 ++ tail

  private def play(corpus: String, frameLimit: Int = 1 << 20)
      : Either[RaiderError, Vector[ModelEvent]] =
    playChunks(SseFramer.partitions(corpus.getBytes(UTF_8),
      corpus.length.max(1)), frameLimit)

  private val textCorpus =
    """: keepalive comment

      |data: {"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

      |data: {"id":"c1","choices":[{"index":0,"delta":{"content":"St"},"finish_reason":null}]}

      |data: {"id":"c1","choices":[{"index":0,"delta":{"content":"ream"},"finish_reason":null}]}

      |data: {"id":"c1","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

      |data: {"id":"c1","choices":[],"usage":{"prompt_tokens":21,"completion_tokens":7}}

      |data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

      |data: [DONE]

      |""".stripMargin.replace("\r\n", "\n")

  private val toolFrames = Vector(
    """{"id":"t","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"lookup","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"t","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\""}}]},"finish_reason":null}]}""",
    """{"id":"t","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"callB","function":{"name":"lookup","arguments":":1}"}}]},"finish_reason":null}]}""",
    """{"id":"t","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"x\"}"}}]},"finish_reason":null}]}""",
    """{"id":"t","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
    "[DONE]")

  private def toolCorpus(frames: Vector[String], tail: String): String =
    frames.map(f => s"data: $f\n\n").mkString + tail

  // ---------- stand ----------

  private final case class ReqWire(model: String, messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)
  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire]    = DeriveJsonDecoder.gen[ReqWire]

  private final case class StandHandle(
    url: String, server: HttpServer,
    clientClosed: AtomicBoolean, requestSeen: AtomicBoolean):
    def stop(): Unit = server.stop(0)

  private def sseFrames(frames: Seq[String]): Array[Byte] =
    frames.map(f => s"data: $f\n\n").mkString.getBytes(UTF_8)

  private def startStand(): StandHandle =
    val closed      = new AtomicBoolean(false)
    val requestSeen = new AtomicBoolean(false)
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext("/v1/chat/completions", (ex: HttpExchange) =>
      try
        requestSeen.set(true)
        def sse(frames: Seq[String]): Unit =
          ex.getResponseHeaders.add("Content-Type", "text/event-stream")
          ex.sendResponseHeaders(200, 0) // chunked
          val out   = ex.getResponseBody
          val bytes = sseFrames(frames)
          // deliberately fragment EVERY frame across 2-byte HTTP chunks
          var i = 0
          while i < bytes.length do
            val half = math.min(2, bytes.length - i)
            out.write(bytes.slice(i, i + half))
            out.flush()
            i += half
          out.flush()
        def park(silent: Boolean): Unit =
          try
            var i = 0
            while i < 100 && !closed.get do
              if !silent then
                ex.getResponseBody.write(": keepalive\n\n".getBytes(UTF_8))
                ex.getResponseBody.flush()
              Thread.sleep(100)
              i += 1
          catch case _: Exception => closed.set(true)
        Option(ex.getRequestHeaders.getFirst("Authorization")) match
          case Some(a) if a != s"Bearer $Key" =>
            ex.sendResponseHeaders(401, -1)
          case Some(_) =>
            val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
            body.fromJson[ReqWire] match
              case Left(_) => ex.sendResponseHeaders(400, -1)
              case Right(req) =>
                val hasToolMsg = req.messages.exists(_.role == "tool")
                req.model match
                  case "stream-text" =>
                    sse(Seq(
                      """{"id":"s1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
                      """{"id":"s1","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}""",
                      """{"id":"s1","choices":[{"index":0,"delta":{"content":"lo stream"},"finish_reason":null}]}""",
                      """{"id":"s1","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3}}""",
                      """{"id":"s1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                      "[DONE]"))
                  case "stream-tool" if !hasToolMsg =>
                    sse(Seq(
                      """{"id":"s2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"lookup","arguments":""}}]},"finish_reason":null}]}""",
                      """{"id":"s2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\""}}]},"finish_reason":null}]}""",
                      """{"id":"s2","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"callB","function":{"name":"lookup","arguments":":1}"}}]},"finish_reason":null}]}""",
                      """{"id":"s2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"x\"}"}}]},"finish_reason":null}]}""",
                      """{"id":"s2","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                      "[DONE]"))
                  case "stream-tool" =>
                    // round 2: the conversation already carries tool results
                    sse(Seq(
                      """{"id":"s3","choices":[{"index":0,"delta":{"content":"stream final"},"finish_reason":null}]}""",
                      """{"id":"s3","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                      "[DONE]"))
                  case "stream-final" =>
                    sse(Seq(
                      """{"id":"s3","choices":[{"index":0,"delta":{"content":"stream final"},"finish_reason":null}]}""",
                      """{"id":"s3","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                      "[DONE]"))
                  case "stream-truncated" =>
                    sse(Seq(
                      """{"id":"s4","choices":[{"index":0,"delta":{"content":"cut"},"finish_reason":null}]}"""))
                    ex.getResponseBody.close() // abrupt: no finish, no DONE
                  case "stream-garbage" =>
                    sse(Seq("""{not json at all""", "[DONE]"))
                  case "stream-parked" =>
                    sse(Seq(
                      """{"id":"s6","choices":[{"index":0,"delta":{"content":"half"},"finish_reason":null}]}"""))
                    park(silent = false) // keepalive comments keep it open
                  case "stream-silent" =>
                    sse(Seq(
                      """{"id":"s7","choices":[{"index":0,"delta":{"content":"one"},"finish_reason":null}]}"""))
                    park(silent = true) // true silence: idle timeout must fire
                  case _ =>
                    ex.sendResponseHeaders(400, -1)
          case _ =>
            ex.sendResponseHeaders(401, -1)
      finally ex.close()
    )
    server.start()
    StandHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server, closed, requestSeen)

  private def lims() = BudgetLimits.make(maxAttempts = 8)

  private def withStreamStand[A](
      callTimeoutMs: Long = 30000L, maxFrameBytes: Int = 1 << 20)
      (use: (OpenAIChatStreamingBackend, StandHandle) => ZIO[Any, Throwable, A])
      : ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed(startStand()))(h => ZIO.succeed(h.stop())) { handle =>
        OpenAIChatStreamingBackend.make(
          OpenAIChatBackend.Config.make(handle.url, Key, callTimeoutMs),
          maxFrameBytes).flatMap(use(_, handle))
      }

  def spec = suite("OpenAI SSE streaming slice (RAI-012)")(
    suite("pure framer+parser")(
      test("SSE-01: every chunk partition of the corpus yields identical events") {
        val expected = play(textCorpus)
        val ks       = Vector(1, 2, 3, 5, 7, 13, 64, 1024)
        val results = ks.map { k =>
          playChunks(SseFramer.partitions(textCorpus.getBytes(UTF_8), k))
        }
        assertTrue(
          expected.isRight,
          results.forall(_ == expected),
          // sanity: the corpus really produced text + usage + Finished stop
          expected.map(_.collect {
            case ModelEvent.TextDelta(_, t) => t
          }.mkString).contains("Stream!"),
          expected.map(_.exists {
            case ModelEvent.UsageObserved(_, u, known) =>
              u == Usage(21, 7, None) && !known
            case _ => false
          }).contains(true),
          expected.map(_.last).contains(
            ModelEvent.Finished(AttemptId("c1"), "stop")))
      },
      test("SSE-02: tool args accumulate; ToolCallReady only at finish, index order") {
        val parser = new ChatChunkParser
        val mid = toolFrames.foldLeft(Vector.empty[ModelEvent]) { (evs, f) =>
          evs ++ parser.feed(f).fold(e => throw e, identity)
        }
        val tail = parser.finish().fold(e => throw e, identity)
        assertTrue(
          mid.exists { case ModelEvent.Started(AttemptId("t")) => true
                       case _ => false },
          !mid.exists { case _: ModelEvent.ToolCallReady => true; case _ => false },
          tail.collect {
            case ModelEvent.ToolCallReady(_, id, name, args) => (id, name, args)
          } == Vector(
            (ToolCallId("callA"), "lookup", """{"q":"x"}"""),
            (ToolCallId("callB"), "lookup", ":1}")),
          tail.last == ModelEvent.Finished(AttemptId("t"), "tool_calls"))
      },
      test("SSE-02: EOF-before-finish and DONE-without-finish are typed failures") {
        val eofParser = new ChatChunkParser
        val _         = eofParser.feed(
          """{"id":"e","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}]}""")
        val eofRes = eofParser.finish()
        val doneParser = new ChatChunkParser
        val _2         = doneParser.feed("[DONE]")
        val doneRes    = doneParser.finish()
        assertTrue(
          eofRes.swap.exists(_.code == "RA-STREAM"),
          doneRes.swap.exists(_.code == "RA-STREAM"))
      },
      test("SSE-02: invalid JSON frame is a typed StreamProtocol failure") {
        val parser = new ChatChunkParser
        val res    = parser.feed("{not json")
        assertTrue(res.swap.exists(_.code == "RA-STREAM"))
      },
      test("SSE-02: oversized frame fails typed without unbounded buffering") {
        val framer = new SseFramer(128)
        val big    = Array.fill[Byte](300)('x')
        val res    = framer.feed(big)
        assertTrue(res.swap.exists(_.detail.contains("maxFrameBytes=128")))
      }
    ),
    suite("integration over HTTP (fragmented frames)")(
      test("streamed text assembles with usage; finish stop") {
        withStreamStand() { (backend, _) =>
          for
            events <- ZIO.scoped(backend.stream(
              ModelRequest("stream-text", List(RequestMessage("user", "u")), 32))
              .runCollect)
            text = events.collect { case ModelEvent.TextDelta(_, t) => t }.mkString
          yield assertTrue(
            text == "Hello stream",
            events.exists {
              case ModelEvent.UsageObserved(_, u, known) =>
                u == Usage(11, 3, None) && !known
              case _ => false
            },
            events.last == ModelEvent.Finished(AttemptId("s1"), "stop"),
            backend.capabilities.streaming == CapabilityStatus.Supported)
        }
      },
      test("AgentLoop completes an HTTP streaming tool round") {
        withStreamStand() { (backend, _) =>
          for
            adm  <- Admission.make(lims())
            root  = AgentLoop.newRoot()
            echo = new Tool:
              def name = "lookup"; def version = 1; def description = ""
              def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
              def capabilities = ToolCapabilities(concurrentSafe = false)
              def invoke(args: String) = ZIO.succeed(s"""{"got":$args}""")
            registry = ToolRegistry.of(echo).fold(e => throw e, identity)
            loop = AgentLoop(backend, registry, "stream-tool", adm, root)
            answer <- loop.runText(List(RequestMessage("user", "find x")),
                         lims().fold(e => throw e, identity), 4)
          yield assertTrue(answer == "stream final")
        }
      },
      test("truncated stream (EOF before finish/DONE) → typed StreamProtocol") {
        withStreamStand() { (backend, _) =>
          for
            res <- ZIO.scoped(backend.stream(
                     ModelRequest("stream-truncated", Nil, 16)).runCollect.either)
          yield assertTrue(
            res.isLeft, res.fold(_.code == "RA-STREAM", _ => false))
        }
      },
      test("invalid JSON chunk → typed StreamProtocol") {
        withStreamStand() { (backend, _) =>
          for
            res <- ZIO.scoped(backend.stream(
                     ModelRequest("stream-garbage", Nil, 16)).runCollect.either)
          yield assertTrue(
            res.isLeft, res.fold(_.code == "RA-STREAM", _ => false))
        }
      },
      test("oversized frame over HTTP → typed StreamProtocol") {
        // frame 1 of stream-text is ~95 bytes: a 32-byte cap trips on its
        // partial data line before any blank line completes (with correct
        // incremental reads frames no longer accumulate past their terminator)
        withStreamStand(maxFrameBytes = 32) { (backend, _) =>
          for
            res <- ZIO.scoped(backend.stream(
                     ModelRequest("stream-text", Nil, 16)).runCollect.either)
          yield assertTrue(
            res.isLeft, res.fold(_.code == "RA-STREAM", _ => false))
        }
      },
      test("idle timeout between chunks → typed DeadlineExceeded") {
        live {
          // "stream-silent": one chunk, then true silence — 500ms bound fires
          withStreamStand(callTimeoutMs = 500) { (backend, _) =>
            for
              res <- ZIO.scoped(backend.stream(
                       ModelRequest("stream-silent", Nil, 16)).runCollect.exit
                       .timeout(ZDuration.fromSeconds(10)))
            yield assertTrue(
              res.isDefined,
              res.exists { case ZExit.Failure(cause) =>
                cause.failures.headOption.map(_.code).contains("RA-DEADLINE")
                case _ => false
              })
          }
        }
      },
      test("cancel mid-stream: body closed stand-side, loop accounting Uncertain") {
        live {
          withStreamStand() { (backend, handle) =>
            for
              adm  <- Admission.make(lims())
              root  = AgentLoop.newRoot()
              loop = AgentLoop(backend, ToolRegistry.empty, "stream-parked",
                       adm, root, CostEstimate.Priced(MicroUsd(300L)))
              fiber <- loop.runText(List(RequestMessage("user", "u")),
                          lims().fold(e => throw e, identity), 4).forkDaemon
              _ <- ZIO.succeed(handle.requestSeen.get).repeatUntil(_ == true)
                    .timeout(ZDuration.fromSeconds(10))
              // let the first chunk arrive: it is flushed right after handling
              _ <- ZIO.sleep(ZDuration.fromMillis(150))
              _ <- fiber.interrupt
              _ <- fiber.await
              _ <- ZIO.succeed(handle.clientClosed.get)
                     .repeatUntil(_ == true).timeout(ZDuration.fromSeconds(10))
              usage <- adm.budgetLedger.usage
            yield assertTrue(
              usage.uncertainTotal == MicroUsd(300L), // charge kept, never zeroed
              usage.reservedActive == MicroUsd(0),
              handle.clientClosed.get) // SSE-03: response closed
          }
        }
      }
    )
  )
