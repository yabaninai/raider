package raider.provider.chat

import raider.core.*
import raider.runtime.loop.AgentLoop
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, MicroUsd}
import raider.runtime.budget.CostEstimate
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-011 feasibility slice acceptance against a LOCAL Scala stand (JDK
  * HttpServer on the loopback, ephemeral port; language policy: provider test
  * stands are Scala inside the module). No real network, no paid calls, and
  * NEVER the local llama.cpp endpoint. */
object OpenAIChatStandSpec extends ZIOSpecDefault:

  private val Key = "test-key-123"

  /** Minimal request view for the stand's request-driven scenarios. */
  private final case class ReqWire(model: String,
                                   messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)
  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  /** Local OpenAI-compatible stand: auth always enforced; scenarios are
    * request-driven (model name) so one server serves every test. */
  private def startStand(): OpenAIStandHandle =
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext("/v1/chat/completions", (exchange: HttpExchange) =>
      try
        def reply(code: Int, body: String): Unit =
          val bytes = body.getBytes(UTF_8)
          exchange.sendResponseHeaders(code, bytes.length)
          exchange.getResponseBody.write(bytes)
        val auth = Option(exchange.getRequestHeaders.getFirst("Authorization"))
        auth match
          case None =>
            reply(401, """{"error":{"message":"missing api key"}}""")
          case Some(a) if a != s"Bearer $Key" =>
            reply(401, """{"error":{"message":"incorrect api key"}}""")
          case Some(_) =>
            val body = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
            body.fromJson[ReqWire] match
              case Left(_) =>
                reply(400, """{"error":{"message":"bad request"}}""")
              case Right(req) =>
                val hasToolMsg = req.messages.exists(_.role == "tool")
                req.model match
                  case "boom" =>
                    reply(500, """{"error":{"message":"upstream exploded"}}""")
                  case "garbage" => reply(200, "this is NOT json")
                  case "tool-model" if !hasToolMsg =>
                    reply(200,
                      """{"id":"chatcmpl-stand-t1","choices":[{"index":0,
                        "message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_9","type":"function",
                        "function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}}]},
                        "finish_reason":"tool_calls"}],
                        "usage":{"prompt_tokens":11,"completion_tokens":5}}""")
                  case _ =>
                    val text = if hasToolMsg then "stand final answer"
                               else "stand hello"
                    reply(200,
                      s"""{"id":"chatcmpl-stand-1","choices":[{"index":0,
                        "message":{"role":"assistant","content":"$text"},
                        "finish_reason":"stop"}],
                        "usage":{"prompt_tokens":17,"completion_tokens":9}}""")
      finally exchange.close()
    )
    server.start()
    OpenAIStandHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server)

  private final case class OpenAIStandHandle(url: String, server: HttpServer):
    def stop(): Unit = server.stop(0)

  private def lims() = BudgetLimits.make(maxAttempts = 8)

  private def limsV() = BudgetLimits.make(maxAttempts = 8).fold(e => throw e, identity)

  private def withStand[A]
      (use: (OpenAIChatBackend, OpenAIStandHandle) => ZIO[Any, Throwable, A])
      : ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed(startStand()))(h => ZIO.succeed(h.stop())) { handle =>
        OpenAIChatBackend.make(
          OpenAIChatBackend.Config.make(handle.url, Key)).flatMap(use(_, handle))
      }

  def spec = suite("OpenAI-compatible transport slice (local stand)")(
    test("text roundtrip: normalized events, usage recorded unpriced, auth header accepted") {
      withStand { (backend, _) =>
        for
          events <- ZIO.scoped(backend.stream(
            ModelRequest("fast-model", List(RequestMessage("user", "hello ✓")), 64))
            .runCollect)
          text = events.collect { case ModelEvent.TextDelta(_, t) => t }.mkString
          hasUsage = events.exists {
            case ModelEvent.UsageObserved(_, u, known) =>
              u == Usage(17, 9, None) && !known
            case _ => false
          }
        yield assertTrue(
          text == "stand hello",
          events.head == ModelEvent.Started(AttemptId("chatcmpl-stand-1")),
          hasUsage,
          events.last == ModelEvent.Finished(AttemptId("chatcmpl-stand-1"), "stop"),
          backend.capabilities.streaming == CapabilityStatus.Unsupported)
      }
    },
    test("wrong key → typed ProviderAuth; stand enforced auth (no key in details)") {
      withStand { (_, handle) =>
        for
          bad <- OpenAIChatBackend.make(
                   OpenAIChatBackend.Config.make(handle.url, "wrong-key"))
          res <- ZIO.scoped(bad.stream(
                   ModelRequest("m", List(RequestMessage("user", "u")), 8)).runCollect.either)
          detail = res.fold(_.detail, _ => "")
        yield assertTrue(
          res.isLeft,
          res.fold(_.code == "RA-AUTH", _ => false),
          !detail.contains("wrong-key"))
      }
    },
    test("500 → ProviderUnavailable; unparsable 200 body → StreamProtocol") {
      withStand { (backend, _) =>
        for
          boom <- ZIO.scoped(backend.stream(
                    ModelRequest("boom", Nil, 8)).runCollect.either)
          garbage <- ZIO.scoped(backend.stream(
                       ModelRequest("garbage", Nil, 8)).runCollect.either)
        yield assertTrue(
          boom.fold(_.code == "RA-UNAVAIL", _ => false),
          garbage.fold(_.code == "RA-STREAM", _ => false))
      }
    },
    test("capstone: AgentLoop completes a real HTTP tool round against the stand") {
      withStand { (backend, _) =>
        for
          adm  <- Admission.make(lims())
          root  = AgentLoop.newRoot()
          echo = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) = ZIO.succeed(s"""{"echo":$args}""")
          registry = ToolRegistry.of(echo).fold(e => throw e, identity)
          loop = AgentLoop(backend, registry, "tool-model", adm, root)
          answer <- loop.runText(List(RequestMessage("user", "find x")),
                       limsV(), 4)
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          answer == "stand final answer", // round1 tool_calls → round2 text
          usage.reservedActive == MicroUsd(0),
          usage.unpricedAttempts == 2L) // no price table ⇒ tracked unpriced, never free
      }
    }
  )
