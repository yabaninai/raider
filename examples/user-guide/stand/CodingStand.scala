package demo

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.CountDownLatch
import zio.json.*
import zio.json.ast.Json

/** Scripted loopback fixture, not a model: tool decisions follow recorded results.
  * The server cannot read or edit the workspace; only the real Raider tools do.
  * Every response checks the preceding tool result before choosing the next step.
  */
object CodingStand:
  private val ids = Vector("read-greeting", "search-typo", "check-before", "fix-greeting", "check-after")

  private def field(value: Json, name: String): Json = value match
    case Json.Obj(values) => values.find(_._1 == name).map(_._2).getOrElse(Json.Null)
    case _ => Json.Null

  private def string(value: Json): String = value match
    case Json.Str(text) => text
    case _ => throw new IllegalArgumentException("expected string")

  private def array(value: Json): Vector[Json] = value match
    case Json.Arr(values) => values.toVector
    case _ => throw new IllegalArgumentException("expected array")

  private def requireField(value: Json, name: String, expected: Json): Unit =
    require(field(value, name) == expected, s"unexpected fixture result field: $name")

  private def response(tool: Option[(String, String, String)], text: String): String =
    val message = tool match
      case Some((id, name, arguments)) =>
        s"""{"role":"assistant","content":null,"tool_calls":[{"id":${id.toJson},"type":"function","function":{"name":${name.toJson},"arguments":${arguments.toJson}}}]}"""
      case None => s"""{"role":"assistant","content":${text.toJson}}"""
    val finish = if tool.isDefined then "tool_calls" else "stop"
    s"""{"id":"scripted-docs","object":"chat.completion","model":"scripted-docs","choices":[{"index":0,"message":$message,"finish_reason":${finish.toJson}}]}"""

  private def next(request: Json): String =
    requireField(request, "model", Json.Str("scripted-docs"))
    val messages = array(field(request, "messages"))
    val results = messages.filter(m => field(m, "role") == Json.Str("tool")).map { m =>
      string(field(m, "content")).fromJson[Json].fold(
        _ => throw new IllegalArgumentException("invalid tool result JSON"), identity)
    }
    require(results.size <= ids.size, "too many fixture tool results")
    results.zipWithIndex.foreach { (result, i) =>
      requireField(result, "tool_call_id", Json.Str(ids(i)))
    }
    def output(i: Int): Json = field(results(i), "output")
    if results.nonEmpty then
      require(string(field(output(0), "content")) == "Hello Raiedr!\n", "unexpected fixture input")
      require(string(field(output(0), "sha256")).matches("[a-f0-9]{64}"), "missing read provenance")
    if results.size >= 2 then
      require(output(1).toJson.contains("Raiedr"), "search did not find typo")
    if results.size >= 3 then
      requireField(output(2), "exitCode", Json.Num(1))
      requireField(output(2), "timedOut", Json.Bool(false))
    if results.size >= 4 then requireField(output(3), "action", Json.Str("modified"))
    if results.size >= 5 then
      requireField(output(4), "exitCode", Json.Num(0))
      requireField(output(4), "timedOut", Json.Bool(false))

    val selected: Option[(String, String, String)] = results.size match
      case 0 => Some((ids(0), "fs_read", """{"path":"greeting.txt"}"""))
      case 1 => Some((ids(1), "fs_search", """{"query":"Raiedr","glob":"*.txt"}"""))
      case 2 => Some((ids(2), "proc_run", """{"argv":["cmp","greeting.txt","expected.txt"],"timeout_s":10}"""))
      case 3 =>
        val sha = string(field(output(0), "sha256"))
        val fixed = "Hello Raider!\n".toJson
        Some((ids(3), "fs_edit", s"""{"path":"greeting.txt","content":$fixed,"expected_sha256":${sha.toJson}}"""))
      case 4 => Some((ids(4), "proc_run", """{"argv":["cmp","greeting.txt","expected.txt"],"timeout_s":10}"""))
      case _ => None
    response(selected, "[scripted-docs] Read, searched, observed check exit 1, edited with SHA-256, observed check exit 0.")

  private def reply(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    try exchange.getResponseBody.write(bytes)
    finally exchange.close()

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: demo.CodingStand <state-dir>")
    val state = Path.of(args(0)).toAbsolutePath
    Files.createDirectories(state)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions", (exchange: HttpExchange) =>
      try
        require(exchange.getRequestMethod == "POST", "POST required")
        val body = new String(exchange.getRequestBody.readNBytes(1024 * 1024 + 1), UTF_8)
        require(body.length <= 1024 * 1024, "fixture request too large")
        val request = body.fromJson[Json].fold(
          _ => throw new IllegalArgumentException("invalid request JSON"), identity)
        reply(exchange, 200, next(request))
      catch
        case _: IllegalArgumentException =>
          // Neither request bodies nor headers/credentials are logged.
          reply(exchange, 400, """{"error":"unexpected scripted fixture sequence"}"""))
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop(0)))
    server.start()
    val base = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
    Files.writeString(state.resolve("base-url.tmp"), base, UTF_8)
    Files.move(state.resolve("base-url.tmp"), state.resolve("base-url.txt"), StandardCopyOption.ATOMIC_MOVE)
    println(s"[scripted-docs] ready: $base (no model, no paid calls)")
    new CountDownLatch(1).await()
