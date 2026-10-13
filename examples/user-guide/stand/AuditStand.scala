package demo

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.CountDownLatch
import zio.json.*
import zio.json.ast.Json

/** Deterministic audit tool script; not a model. */
object AuditStand:

  private val ids = Vector(
    "inspect-tree",
    "read-payment-config",
    "find-plaintext-setting",
    "write-audit"
  )

  private def field(value: Json, name: String): Json = value match
    case Json.Obj(values) =>
      values.find(_._1 == name).map(_._2).getOrElse(Json.Null)
    case _ => Json.Null

  private def string(value: Json): String = value match
    case Json.Str(text) => text
    case _              => throw new IllegalArgumentException("expected string")

  private def array(value: Json): Vector[Json] = value match
    case Json.Arr(values) => values.toVector
    case _ => throw new IllegalArgumentException("expected array")

  private def requireField(value: Json, name: String, expected: Json): Unit =
    require(
      field(value, name) == expected,
      s"unexpected audit fixture result: $name"
    )

  private def response(
      tool: Option[(String, String, String)],
      text: String
  ): String =
    val message = tool match
      case Some((id, name, arguments)) =>
        s"""{"role":"assistant","content":null,"tool_calls":[{"id":${id.toJson},"type":"function","function":{"name":${name.toJson},"arguments":${arguments.toJson}}}]}"""
      case None => s"""{"role":"assistant","content":${text.toJson}}"""
    val finish = if tool.isDefined then "tool_calls" else "stop"
    s"""{"id":"scripted-audit","object":"chat.completion","model":"scripted-audit","choices":[{"index":0,"message":$message,"finish_reason":${finish.toJson}}]}"""

  private val finding =
    """{"schemaVersion":1,"status":"action_required","findings":[{"id":"PAY-001","severity":"high","evidence":{"path":"config/payments.conf","line":2,"matched":"allow_plaintext_fallback = true"},"impact":"Payment requests may use plaintext transport when the primary route is unavailable.","action":"Set allow_plaintext_fallback = false and verify the production transport policy before deployment."}]}"""

  private def next(request: Json): String =
    requireField(request, "model", Json.Str("scripted-audit"))
    val messages = array(field(request, "messages"))
    val results =
      messages.filter(m => field(m, "role") == Json.Str("tool")).map { m =>
        string(field(m, "content"))
          .fromJson[Json]
          .fold(
            _ => throw new IllegalArgumentException("invalid tool result JSON"),
            identity
          )
      }
    require(results.size <= ids.size, "too many audit fixture results")
    results.zipWithIndex.foreach { (result, i) =>
      requireField(result, "tool_call_id", Json.Str(ids(i)))
    }
    def output(i: Int): Json = field(results(i), "output")
    if results.nonEmpty then
      require(
        output(0).toJson.contains("payments.conf"),
        "tree missed audit target"
      )
    if results.size >= 2 then
      require(
        string(field(output(1), "content"))
          .contains("allow_plaintext_fallback = true"),
        "read missed unsafe setting"
      )
      require(
        string(field(output(1), "sha256")).matches("[a-f0-9]{64}"),
        "read omitted source hash"
      )
    if results.size >= 3 then
      require(
        output(2).toJson.contains("allow_plaintext_fallback = true"),
        "search missed unsafe setting"
      )
    if results.size >= 4 then
      requireField(output(3), "action", Json.Str("created"))
    val selected = results.size match
      case 0 => Some((ids(0), "fs_tree", """{"depth":3}"""))
      case 1 => Some((ids(1), "fs_read", """{"path":"config/payments.conf"}"""))
      case 2 =>
        Some(
          (
            ids(2),
            "fs_search",
            """{"query":"allow_plaintext_fallback = true","glob":"*.conf"}"""
          )
        )
      case 3 =>
        Some(
          (
            ids(3),
            "fs_edit",
            s"""{"path":"AUDIT.json","content":${finding.toJson},"expected_sha256":""}"""
          )
        )
      case _ => None
    response(
      selected,
      "[scripted-audit] Wrote AUDIT.json with one actionable finding and source evidence."
    )

  private def reply(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    try exchange.getResponseBody.write(bytes)
    finally exchange.close()

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: demo.AuditStand <state-dir>")
    val state = Path.of(args(0)).toAbsolutePath
    Files.createDirectories(state)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) =>
        try
          require(exchange.getRequestMethod == "POST", "POST required")
          val body = new String(
            exchange.getRequestBody.readNBytes(1024 * 1024 + 1),
            UTF_8
          )
          require(body.length <= 1024 * 1024, "fixture request too large")
          val request = body
            .fromJson[Json]
            .fold(
              _ => throw new IllegalArgumentException("invalid request JSON"),
              identity
            )
          reply(exchange, 200, next(request))
        catch
          case _: IllegalArgumentException =>
            reply(
              exchange,
              400,
              """{"error":"unexpected scripted audit sequence"}"""
            )
    )
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop(0)))
    server.start()
    val base = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
    Files.writeString(state.resolve("base-url.tmp"), base, UTF_8)
    Files.move(
      state.resolve("base-url.tmp"),
      state.resolve("base-url.txt"),
      StandardCopyOption.ATOMIC_MOVE
    )
    println(
      s"[scripted-audit] ready: $base (fixed tool decisions; no model, no paid calls)"
    )
    new CountDownLatch(1).await()
