package demo

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.CountDownLatch
import zio.json.*
import zio.json.ast.Json

/** Deterministic OpenAI-compatible fixture. It sequences real Raider tools from
  * observed results; it is not a model and cannot access the workspace itself.
  */
object CodingStand:

  private val ids = Vector(
    "read-source",
    "find-boundary",
    "compile-before",
    "test-before",
    "edit-discount",
    "compile-after",
    "test-after"
  )

  private var javaCommand = ""
  private var classpath = ""

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
      s"unexpected fixture result field: $name"
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
    s"""{"id":"scripted-billing","object":"chat.completion","model":"scripted-billing","choices":[{"index":0,"message":$message,"finish_reason":${finish.toJson}}]}"""

  private def compileArgs: String =
    List(
      javaCommand,
      "-cp",
      classpath,
      "dotty.tools.dotc.Main",
      "-classpath",
      classpath,
      "-Werror",
      "-d",
      ".",
      "src/main/scala/billing/Discount.scala",
      "src/test/scala/billing/DiscountSpec.scala"
    ).map(_.toJson).mkString("[", ",", "]")

  private def testArgs: String =
    List(
      javaCommand,
      "-cp",
      s".${java.io.File.pathSeparator}$classpath",
      "billing.DiscountSpec"
    ).map(_.toJson).mkString("[", ",", "]")

  private def next(request: Json): String =
    requireField(request, "model", Json.Str("scripted-billing"))
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
    require(results.size <= ids.size, "too many fixture tool results")
    results.zipWithIndex.foreach { (result, i) =>
      requireField(result, "tool_call_id", Json.Str(ids(i)))
    }
    def output(i: Int): Json = field(results(i), "output")
    def exit(i: Int, expected: Int): Unit =
      requireField(output(i), "exitCode", Json.Num(expected))
      requireField(output(i), "timedOut", Json.Bool(false))
    if results.nonEmpty then
      require(
        string(field(output(0), "content")).contains("s > 10000"),
        "unexpected billing source"
      )
      require(
        string(field(output(0), "sha256")).matches("[a-f0-9]{64}"),
        "missing read provenance"
      )
    if results.size >= 2 then
      require(
        output(1).toJson.contains("s > 10000"),
        "boundary search missed defect"
      )
    if results.size >= 3 then exit(2, 0)
    if results.size >= 4 then
      exit(3, 1)
      require(
        string(field(output(3), "stdout")).contains("FAIL exact-threshold"),
        "expected threshold regression failure"
      )
    if results.size >= 5 then
      requireField(output(4), "action", Json.Str("modified"))
    if results.size >= 6 then exit(5, 0)
    if results.size >= 7 then
      exit(6, 0)
      require(
        string(field(output(6), "stdout"))
          .contains("DiscountSpec: PASS 3 boundaries"),
        "boundary tests did not pass"
      )

    val selected: Option[(String, String, String)] = results.size match
      case 0 =>
        Some(
          (
            ids(0),
            "fs_read",
            """{"path":"src/main/scala/billing/Discount.scala"}"""
          )
        )
      case 1 =>
        Some(
          (
            ids(1),
            "fs_search",
            """{"query":"s > 10000","glob":"*.scala"}"""
          )
        )
      case 2 =>
        Some(
          (
            ids(2),
            "proc_run",
            s"""{"argv":$compileArgs,"cwd":".","timeout_s":30}"""
          )
        )
      case 3 =>
        Some(
          (
            ids(3),
            "proc_run",
            s"""{"argv":$testArgs,"cwd":".","timeout_s":10}"""
          )
        )
      case 4 =>
        val sha = string(field(output(0), "sha256"))
        val fixed = """package billing
object Discount:
  def discountCents(s: Int): Int = if s >= 10000 then 1000 else 0
"""
        Some(
          (
            ids(4),
            "fs_edit",
            s"""{"path":"src/main/scala/billing/Discount.scala","content":${fixed.toJson},"expected_sha256":${sha.toJson}}"""
          )
        )
      case 5 =>
        Some(
          (
            ids(5),
            "proc_run",
            s"""{"argv":$compileArgs,"cwd":".","timeout_s":30}"""
          )
        )
      case 6 =>
        Some(
          (
            ids(6),
            "proc_run",
            s"""{"argv":$testArgs,"cwd":".","timeout_s":10}"""
          )
        )
      case _ => None
    response(
      selected,
      "[scripted-billing] The exact $100 boundary now receives the documented discount; all three boundary checks pass."
    )

  private def reply(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    try exchange.getResponseBody.write(bytes)
    finally exchange.close()

  def main(args: Array[String]): Unit =
    require(
      args.length == 3,
      "usage: demo.CodingStand <state-dir> <java> <runtime-classpath>"
    )
    val state = Path.of(args(0)).toAbsolutePath
    javaCommand = args(1)
    classpath = args(2)
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
            // Never log request bodies, headers or credential values.
            reply(
              exchange,
              400,
              """{"error":"unexpected scripted fixture sequence"}"""
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
      s"[scripted-billing] ready: $base (fixed tool decisions; no model, no paid calls)"
    )
    new CountDownLatch(1).await()
