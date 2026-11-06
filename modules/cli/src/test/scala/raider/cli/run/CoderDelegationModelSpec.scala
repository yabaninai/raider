package raider.cli.run

import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test.*
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.CopyOnWriteArrayList
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import scala.jdk.CollectionConverters.*

/** RAI-257 (audit follow-up, docs/reviews/2026-11-01-readiness.md "P1: native
  * delegated children use a test model identifier"): the delegated child of a
  * LIVE-style headless coder run must request the CONFIGURED model, not the
  * historical `scripted:<agent>` label.
  *
  * Deterministic local Scala provider stand (JDK HttpServer on the loopback,
  * ephemeral port — the CoderDelegationRunSpec framing): no real network, no
  * paid calls, the credential rides only the injected test env. The stand
  * RECORDS the model id of every provider request and REQUIRES the configured
  * model (`stand-model`) for both the parent campaign and the delegated child:
  * a request with any other model id is refused with HTTP 400 (a provider
  * modeling error, never silently accepted), so the child can only succeed by
  * requesting the configured model. Routing is CONTENT-based (RAI-092
  * discipline): the child turn is the single-message `find x` request, never a
  * call index and never a model-string prefix.
  *
  * RED-FIRST evidence: against the unfixed wiring the child's recorded request
  * carries `scripted:scout`, the stand refuses it, the child settles Failed,
  * and the propagation assertions below fail.
  */
object CoderDelegationModelSpec extends ZIOSpecDefault:

  private val Key = "rai-257-stand-key"
  private val ConfiguredModel = "stand-model"

  /** Minimal request view for the stand's routing/recording. */
  private final case class ReqWire(model: String, messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  /** The delegated child's FIRST turn: exactly one user message with the
    * delegated input (child loops run without a system prompt and without prior
    * history).
    */
  private def isChildTurn(req: ReqWire): Boolean =
    req.messages.size == 1 && req.messages.head.content == "find x"

  private def toolResults(req: ReqWire): List[String] =
    req.messages.collect { case m if m.role == "tool" => m.content }

  /** The child's id from a delegate tool result (JobManager `j_<n>` ids). */
  private def childIdOf(results: List[String]): String =
    val r = """"child_id":"(j_\d+)"""".r
    results
      .flatMap(m => r.findFirstMatchIn(m).map(_.group(1)))
      .headOption
      .getOrElse("j_missing")

  /** The child's OBSERVED terminal status from an await_agent tool result — the
    * final answer quotes it verbatim, so the assertions see the honest child
    * outcome either way.
    */
  private def observedStatus(results: List[String]): String =
    val r = """"status":"(\w+)"""".r
    results
      .flatMap(m => r.findFirstMatchIn(m).map(_.group(1)))
      .headOption
      .getOrElse("Unknown")

  private def reply(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.sendResponseHeaders(code, bytes.length)
    exchange.getResponseBody.write(bytes)

  /** Records every request's model id; requires the configured model for ALL
    * callers (parent AND child); content-routed delegation script.
    */
  private def startStand()
      : (String, HttpServer, CopyOnWriteArrayList[ReqWire]) =
    val requests = new CopyOnWriteArrayList[ReqWire]()
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) =>
        try
          Option(exchange.getRequestHeaders.getFirst("Authorization")) match
            case None =>
              reply(
                exchange,
                401,
                """{"error":{"message":"missing api key"}}"""
              )
            case Some(a) if a != s"Bearer $Key" =>
              reply(
                exchange,
                401,
                """{"error":{"message":"incorrect api key"}}"""
              )
            case Some(_) =>
              val body =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              body.fromJson[ReqWire] match
                case Left(_) =>
                  reply(
                    exchange,
                    400,
                    """{"error":{"message":"bad request"}}"""
                  )
                case Right(req) =>
                  requests.add(req)
                  // RAI-257: the stand REQUIRES the configured model — a
                  // request under any other id is a refused modeling error
                  if req.model != ConfiguredModel then
                    reply(
                      exchange,
                      400,
                      s"""{"error":{"message":"model ${req.model} not deployed; expected $ConfiguredModel"}}"""
                    )
                  else
                    val results = toolResults(req)
                    if isChildTurn(req) then
                      // the child's single turn: answer with text
                      reply(
                        exchange,
                        200,
                        """{"id":"chatcmpl-rai257-child","choices":[{"index":0,
                          "message":{"role":"assistant","content":"found x"},
                          "finish_reason":"stop"}],
                          "usage":{"prompt_tokens":9,"completion_tokens":3}}"""
                      )
                    else if results.exists(_.contains("\"status\"")) then
                      // await_agent observed the child: quote its status
                      val st = observedStatus(results)
                      reply(
                        exchange,
                        200,
                        s"""{"id":"chatcmpl-rai257-final","choices":[{"index":0,
                          "message":{"role":"assistant",
                          "content":"child reported: $st"},
                          "finish_reason":"stop"}],
                          "usage":{"prompt_tokens":31,"completion_tokens":6}}"""
                      )
                    else if results.exists(_.contains("\"child_id\"")) then
                      // delegate returned: await the REAL child id
                      val cid = childIdOf(results)
                      reply(
                        exchange,
                        200,
                        s"""{"id":"chatcmpl-rai257-await","choices":[{"index":0,
                          "message":{"role":"assistant","content":null,
                          "tool_calls":[{"id":"call_2","type":"function",
                          "function":{"name":"await_agent",
                          "arguments":"{\\"child_id\\":\\"$cid\\"}"}}]},
                          "finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":21,"completion_tokens":4}}"""
                      )
                    else
                      // the campaign's first turn: delegate (NO model key —
                      // the parent default must propagate)
                      reply(
                        exchange,
                        200,
                        """{"id":"chatcmpl-rai257-delegate","choices":[{"index":0,
                          "message":{"role":"assistant","content":null,
                          "tool_calls":[{"id":"call_1","type":"function",
                          "function":{"name":"delegate",
                          "arguments":"{\"agent\":\"scout\",\"input\":\"find x\"}"}}]},
                          "finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":11,"completion_tokens":5}}"""
                      )
        finally exchange.close()
    )
    server.start()
    (s"http://127.0.0.1:${server.getAddress.getPort}/v1", server, requests)

  private def coderArgs(out: Path, ws: Path, baseUrl: String) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "coder",
          "--input",
          "delegate the search",
          "--out",
          out.toString,
          "--provider",
          "openai",
          "--base-url",
          baseUrl,
          "--model",
          ConfiguredModel,
          "--workspace",
          ws.toString
        )
      )
      .fold(e => throw e, identity)

  // Live clock: the real product runner polls with ZIO.sleep on the ambient
  // clock (EventExport.awaitCaughtUp) — the house pattern for specs that drive
  // HeadlessRunner (CoderDelegationRunSpec / RAI-178).
  def spec = suite(
    "RAI-257: delegated child requests the configured model through the product entry"
  )(
    test(
      "PROP-E2E: headless coder with a live-style stand propagates the configured model to the delegated child; the stand refuses any other model id"
    ) {
      ZIO.acquireReleaseWith(ZIO.succeed(startStand()))(h =>
        ZIO.succeed(h._2.stop(0))
      ) { stand =>
        val (baseUrl, server, requests) = stand
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-257-model"))
              .orDie
            ws <- ZIO
              .attempt(Files.createTempDirectory("rai-257-model-ws"))
              .orDie
            outcome <- HeadlessRunner.run(
              coderArgs(work, ws, baseUrl),
              Map("RAIDER_API_KEY" -> Key)
            )
            recorded = requests.asScala.toList
            childReqs = recorded.filter(isChildTurn)
            parentReqs = recorded.filterNot(isChildTurn)
          yield assertTrue(
            outcome.exitCode == 0,
            // the child SUCCEEDED under the configured model (the stand
            // refuses anything else with a modeling error)
            outcome.result.exists(r =>
              r.output.exists(_.json.contains("child reported: Succeeded"))
            ),
            // the child REALLY reached the provider, under the CONFIGURED id
            childReqs.nonEmpty,
            childReqs.forall(_.model == ConfiguredModel),
            // the parent stays on the configured id too (control: the stand
            // is live-style for BOTH caller sides)
            parentReqs.nonEmpty,
            parentReqs.forall(_.model == ConfiguredModel)
          )
        }
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(
    zio.Duration.fromSeconds(300)
  )

end CoderDelegationModelSpec
