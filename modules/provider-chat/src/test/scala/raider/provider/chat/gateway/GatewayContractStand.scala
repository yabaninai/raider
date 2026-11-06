package raider.provider.chat.gateway

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.{Task, ZIO}
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import scala.jdk.CollectionConverters.*

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList

/** GW17 (RAI-287): deterministic LOCAL Yabanin gateway contract stand.
  *
  * Serves the ACCEPTED gateway wire only — the OpenAI-compatible Chat
  * Completions profile the live matrix accepted in RAI-184 (POST
  * `<base>/chat/completions` with a Bearer child credential; gateway-style JSON
  * error bodies; per-response usage objects) plus the GET `/models` liveness
  * endpoint the live setup exercised (HTTP 200). No enriched `kind="yabanin"`
  * features (RAI-037/038 stay out of scope), no session minting, no accounting
  * endpoints: the stand EXPOSES only the accepted surface, so a request to
  * anything else lands in the request log as a foreign path and fails the
  * contract.
  *
  * Boundaries: loopback only, ephemeral port (bind 0), no external network, no
  * spend. Deterministic: every scenario is keyed on the request itself (auth
  * header, model id, presence of a tool result) — never on time or RNG.
  *
  * Ownership: one instance per test, acquired/released by the caller
  * (ZIO.acquireReleaseWith) so startup/teardown are cancellation-safe; no fixed
  * shared ports or paths.
  *
  * Request log: method, path, Authorization header, X-Yabanin-* headers and the
  * raw body of EVERY request are recorded (thread-safe) — the lifecycle
  * (no-mint) and the no-hidden-fallback legs assert on this log. The key in the
  * log is a fixture value, never a real credential.
  */
final class GatewayContractStand private[gateway] (
    val baseUrl: String,
    private[gateway] val server: HttpServer,
    private[gateway] val log: CopyOnWriteArrayList[
      GatewayContractStand.RequestLog
    ]
):
  def stop(): Unit = server.stop(0)

  /** The full request log in arrival order. */
  def requests: Vector[GatewayContractStand.RequestLog] = log.asScala.toVector

  /** Paths hit, in order — the lifecycle/no-mint and no-probe assertions. */
  def paths: Vector[String] = requests.map(_.path)

object GatewayContractStand:

  /** Fixture child credential — an obviously fake value, never a secret. */
  val ChildKey = "yb-child-gw17-fixture-key"

  final case class RequestLog(
      method: String,
      path: String,
      authorization: Option[String],
      yabaninHeaders: Map[String, String],
      body: String
  )

  /** Minimal request view the stand needs to pick a deterministic scenario
    * (model id; presence of a tool-role message = round 2 of a tool round).
    */
  private final case class ReqWire(model: String, messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  /** Start the stand on `http://127.0.0.1:<ephemeral>/gw/v1`. Bearer auth is
    * enforced against `key` on every completions call (401 otherwise, exactly
    * the live stand's behavior for a bad child credential).
    */
  def start(key: String = ChildKey): Task[GatewayContractStand] =
    ZIO.attempt {
      val log = new CopyOnWriteArrayList[RequestLog]
      val server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      val basePath = "/gw/v1"

      def record(ex: HttpExchange, body: String): RequestLog =
        val auth =
          Option(ex.getRequestHeaders.getFirst("Authorization"))
        val yb = ex.getRequestHeaders.keySet.asScala.toVector
          .filter(_.toLowerCase.startsWith("x-yabanin-"))
          .map(n => n -> Option(ex.getRequestHeaders.getFirst(n)).getOrElse(""))
          .toMap
        val entry =
          RequestLog(
            ex.getRequestMethod,
            ex.getRequestURI.getPath,
            auth,
            yb,
            body
          )
        log.add(entry)
        entry

      def reply(ex: HttpExchange, code: Int, payload: String): Unit =
        val bytes = payload.getBytes(UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.length)
        ex.getResponseBody.write(bytes)

      // GET /gw/v1/models — the liveness endpoint the live setup evidenced.
      server.createContext(
        s"$basePath/models",
        (ex: HttpExchange) =>
          try
            record(ex, "")
            reply(
              ex,
              200,
              """{"object":"list","data":[{"id":"gw-flash","object":"model"}]}"""
            )
          finally ex.close()
      )

      // POST /gw/v1/chat/completions — the accepted wire (RAI-184).
      server.createContext(
        s"$basePath/chat/completions",
        (ex: HttpExchange) =>
          try
            val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
            record(ex, body)
            def deny(status: Int, message: String): Unit =
              reply(
                ex,
                status,
                s"""{"error":{"message":"$message"}}"""
              )
            ex.getRequestMethod match
              case "POST" =>
                Option(ex.getRequestHeaders.getFirst("Authorization")) match
                  case None =>
                    deny(401, "missing child credential")
                  case Some(a) if a != s"Bearer $key" =>
                    deny(401, "invalid child credential")
                  case Some(_) =>
                    body.fromJson[ReqWire] match
                      case Left(_) => deny(400, "malformed request body")
                      case Right(req) =>
                        req.model match
                          // policy refusal: gateway-side model allowlist
                          case "gw-policy-deny" =>
                            deny(
                              403,
                              "policy: model not permitted for this child"
                            )
                          case "gw-boom" =>
                            deny(500, "upstream exploded")
                          case "gw-tool" =>
                            val hasToolMsg =
                              req.messages.exists(_.role == "tool")
                            if !hasToolMsg then
                              reply(
                                ex,
                                200,
                                """{"id":"chatcmpl-gw17-t1","choices":[{"index":0,
                                "message":{"role":"assistant","content":null,
                                "tool_calls":[{"id":"call_gw17","type":"function",
                                "function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}}]},
                                "finish_reason":"tool_calls"}],
                                "usage":{"prompt_tokens":13,"completion_tokens":4}}"""
                              )
                            else
                              reply(
                                ex,
                                200,
                                """{"id":"chatcmpl-gw17-t2","choices":[{"index":0,
                                "message":{"role":"assistant",
                                "content":"gateway tool final"},
                                "finish_reason":"stop"}],
                                "usage":{"prompt_tokens":19,"completion_tokens":6}}"""
                              )
                          // default: deterministic text answer + usage object
                          case _ =>
                            reply(
                              ex,
                              200,
                              """{"id":"chatcmpl-gw17-1","choices":[{"index":0,
                              "message":{"role":"assistant","content":"gateway ok"},
                              "finish_reason":"stop"}],
                              "usage":{"prompt_tokens":23,"completion_tokens":11}}"""
                            )
              case _ => deny(405, "method not allowed")
          finally ex.close()
      )

      server.start()
      new GatewayContractStand(
        s"http://127.0.0.1:${server.getAddress.getPort}$basePath",
        server,
        log
      )
    }

end GatewayContractStand
