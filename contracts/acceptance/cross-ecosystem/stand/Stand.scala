// RAI-205 cross-ecosystem acceptance fixture: scripted OpenAI-compatible stand.
//
// Deterministic two-round chat-completions server, JDK httpserver only (same
// wire style as the PortableParitySpec test stand, but a standalone fixture
// for external consumers). It is NOT product code and has no Raider deps.
//
//   round 1 -> tool_calls: proc_run {"argv":["/usr/bin/touch","<marker>"]}
//   round 2 (request carries a role:"tool" message) -> final assistant text
//
// Streaming requests (the process-api compiled route) get SSE frames; plain
// requests (the portable workflow route) get one JSON completion that carries
// the scripted usage numbers, which feeds the TokenBudgetBackend RA-PBUDGET
// refusal path. Authorization must be "Bearer <key>"; every request is logged
// as one JSONL line with the Authorization value REDACTED to present/absent.

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.io.{File, PrintStream}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*

object Stand:

  private def fail(msg: String): Nothing =
    System.err.println(s"stand: $msg")
    sys.exit(2)

  private final case class Config(
      key: String,
      marker: String,
      contentFile: Option[String],
      delayMs: Long,
      usageIn: Long,
      usageOut: Long,
      log: Option[String]
  )

  private def parse(args: List[String]): Config =
    @scala.annotation.tailrec
    def loop(rest: List[String], m: Map[String, String]): Config =
      rest match
        case flag :: value :: tail if flag.startsWith("--") =>
          loop(tail, m + (flag -> value))
        case Nil =>
          def req(k: String) =
            m.getOrElse(k, fail(s"missing required flag $k"))
          Config(
            key = req("--key"),
            marker = req("--marker"),
            contentFile = m.get("--content-file"),
            delayMs = m.get("--delay-ms").fold(0L)(_.toLong),
            usageIn = m.get("--usage-in").fold(0L)(_.toLong),
            usageOut = m.get("--usage-out").fold(0L)(_.toLong),
            log = m.get("--log")
          )
        case other => fail(s"bad argv near ${other.take(2).mkString(" ")}")
    loop(args, Map.empty)

  private def js(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  private def sse(frames: List[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(StandardCharsets.UTF_8)

  private def toolRoundFrames(marker: String): List[String] = List(
    """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"argv\":"}}]},"finish_reason":null}]}""",
    s"""{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\\\"/usr/bin/touch\\\",\\\"${js(marker)}\\\"]]"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private def finalFrames(content: String): List[String] = List(
    s"""{"id":"f1","choices":[{"index":0,"delta":{"role":"assistant","content":"${js(content)}"},"finish_reason":null}]}""",
    """{"id":"f1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def plainToolRound(marker: String): Array[Byte] =
    s"""{"id":"p1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"callA","type":"function","function":{"name":"proc_run","arguments":"{\\"argv\\":[\\"/usr/bin/touch\\",\\"${js(marker)}\\"]}"}}]},"finish_reason":"tool_calls"}]}"""
      .getBytes(StandardCharsets.UTF_8)

  private def plainFinal(content: String, usageIn: Long, usageOut: Long): Array[Byte] =
    s"""{"id":"p2","choices":[{"index":0,"message":{"role":"assistant","content":"${js(content)}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":$usageIn,"completion_tokens":$usageOut,"total_tokens":${usageIn + usageOut}}}"""
      .getBytes(StandardCharsets.UTF_8)
  private def reply(ex: HttpExchange, code: Int, body: Array[Byte], sseCt: Boolean): Unit =
    ex.getResponseHeaders.add(
      "Content-Type",
      if sseCt then "text/event-stream" else "application/json"
    )
    ex.sendResponseHeaders(code, if body.isEmpty then -1 else body.length.toLong)
    if body.nonEmpty then ex.getResponseBody.write(body)

  def main(argv: Array[String]): Unit =
    val cfg = parse(argv.toList)
    val content = cfg.contentFile match
      case Some(p) => Files.readString(Paths.get(p), StandardCharsets.UTF_8)
      case None    => "stand final answer rai-205"
    val logOut: Option[PrintStream] =
      cfg.log.map(p => new PrintStream(Files.newOutputStream(Paths.get(p)), true))

    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      (ex: HttpExchange) =>
        try
          val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val auth = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          val authOk = auth == "Bearer " + cfg.key
          logOut.foreach { l =>
            l.println(s"""{"auth":"${if authOk then "present" else "absent"}","bytes":${body.length},"round":"${if body.contains("\"role\":\"tool\"") then "final" else "tool"}"}""")
          }
          if !authOk then
            reply(ex, 401, """{"error":{"message":"stand: bad api key"}}""".getBytes(StandardCharsets.UTF_8), sseCt = false)
          else
            if cfg.delayMs > 0 then Thread.sleep(cfg.delayMs)
            val isStream = body.contains("\"stream\":true")
            val isFinal = body.contains("\"role\":\"tool\"")
            if isStream then
              val frames = if isFinal then finalFrames(content) else toolRoundFrames(cfg.marker)
              reply(ex, 200, sse(frames), sseCt = true)
            else
              // Plain (non-SSE) wire: the process-api compiled route is
              // non-streaming (OpenAIChatBackend), so round discrimination is
              // by the role:"tool" result message. A body without it is a
              // round-1 dispatch and gets the scripted tool_calls; the
              // workflow route's plain request never reaches this branch in
              // the budget fixture because the coder agent's round 1 follows
              // the same tool round before the final text.
              val plain =
                if isFinal then plainFinal(content, cfg.usageIn, cfg.usageOut)
                else plainToolRound(cfg.marker)
              reply(ex, 200, plain, sseCt = false)
          ()
        finally ex.close()
    )
    server.setExecutor(Executors.newFixedThreadPool(4, (r: Runnable) =>
      val t = new Thread(r); t.setDaemon(true); t))
    server.start()
    val out = System.out
    out.println("PORT=" + server.getAddress.getPort)
    out.flush()
    // Park forever: the scenario driver stops the stand explicitly (SIGTERM).
    // Backgrounded processes get an immediate stdin EOF, so stdin must NOT be
    // the lifecycle signal.
    java.util.concurrent.CountDownLatch(1).await()
