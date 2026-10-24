package raider.repl

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.test.*

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Approval acceptance through the real chat entry and a deterministic local
  * OpenAI-compatible scripted stand. No credentials or paid requests leave the
  * test process.
  */
object ToolApprovalChatPtySpec extends ZIOSpecDefault:

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private val Token = raw"approval required \| ([^ |]+) \|".r
  private val Utf8 = StandardCharsets.UTF_8

  private enum Case(val tool: String, val args: String):

    case Approve
        extends Case(
          "fs_edit",
          """{"path":"approved.txt","content":"applied-by-tool","expected_sha256":""}"""
        )

    case Deny
        extends Case(
          "fs_edit",
          """{"path":"denied.txt","content":"must-not-apply","expected_sha256":""}"""
        )

    case ReadOnly extends Case("fs_read", """{"path":"seed.txt"}""")

  private final class Stand(scenario: Case):
    private val calls = AtomicInteger(0)

    private val server =
      HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) =>
        try
          val request = new String(exchange.getRequestBody.readAllBytes(), Utf8)
          val index = calls.getAndIncrement()
          val response =
            if index == 0 then toolResponse(scenario, request)
            else textResponse
          val bytes = response.getBytes(Utf8)
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        finally exchange.close()
    )
    server.start()

    def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
    def requestCount: Int = calls.get()
    def close(): Unit = server.stop(0)

    private def toolResponse(s: Case, request: String): String =
      require(request.contains("\"stream\":true"), "expected streaming request")
      val args = s.args.replace("\\", "\\\\").replace("\"", "\\\"")
      s"""data: {"id":"rai189","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-rai189","type":"function","function":{"name":"${s.tool}","arguments":"$args"}}]},"finish_reason":"tool_calls"}]}

data: [DONE]

"""

    private val textResponse =
      "data: {\"id\":\"rai189-final\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" scripted completion\"},\"finish_reason\":\"stop\"}]}\n\n" +
        "data: [DONE]\n\n"

  private final class Session(scenario: Case, run: String):

    private val root =
      Files.createTempDirectory(s"rai189-$run-${UUID.randomUUID()}")

    private val cwd = Files.createDirectories(root.resolve("workspace"))
    private val home = Files.createDirectories(root.resolve("home"))

    private val evidence =
      Files.createDirectories(Paths.get("target/rai189-pty"))

    private val stand = new Stand(scenario)
    Files.writeString(cwd.resolve("seed.txt"), "read-only fixture\n", Utf8)

    private val javaBin =
      Paths.get(System.getProperty("java.home"), "bin", "java")

    private val inner =
      s"/bin/stty rows 32 cols 100; exec '${javaBin.toString}' -cp '${System.getProperty("java.class.path")}' raider.repl.Main --chat"

    private val builder = new ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      "/bin/sh",
      "-c",
      inner
    )

    builder.redirectErrorStream(true)
    builder.directory(cwd.toFile)
    builder.environment().put("TERM", "xterm-256color")
    builder.environment().put("NO_COLOR", "1")
    builder.environment().put("HOME", home.toString)
    builder.environment().put("RAIDER_PROVIDER", "openai")
    builder.environment().put("RAIDER_API_KEY", "rai189-local-fixture-key")
    builder.environment().put("RAIDER_BASE_URL", stand.baseUrl)
    builder.environment().put("RAIDER_MODEL", "rai189-scripted")

    private val process = builder.start()
    private val input = process.getOutputStream
    private val bytes = ByteArrayOutputStream()
    private val lock = new Object

    private val pump = new Thread(() =>
      try
        val stream = process.getInputStream
        val buffer = new Array[Byte](8192)
        var n = stream.read(buffer)
        while n >= 0 do
          lock.synchronized(bytes.write(buffer, 0, n))
          n = stream.read(buffer)
      catch case _: java.io.IOException => ()
    )

    pump.setDaemon(true)
    pump.start()

    def transcript: String =
      Ansi.replaceAllIn(
        new String(lock.synchronized(bytes.toByteArray), Utf8),
        ""
      )

    def count: Int = stand.requestCount
    def exists(name: String): Boolean = Files.exists(cwd.resolve(name))
    def read(name: String): String = Files.readString(cwd.resolve(name), Utf8)

    def send(text: String): Unit = input.synchronized {
      input.write(text.getBytes(Utf8))
      input.flush()
    }

    def waitFor(fragment: String, seconds: Int = 60): Boolean =
      val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
      while System.nanoTime() < until do
        if transcript.contains(fragment) then return true
        Thread.sleep(80)
      transcript.contains(fragment)

    def waitForRequests(n: Int, seconds: Int = 60): Boolean =
      val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
      while System.nanoTime() < until do
        if count >= n then return true
        Thread.sleep(80)
      count >= n

    def waitForFile(name: String, seconds: Int = 60): Boolean =
      val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
      while System.nanoTime() < until do
        if exists(name) then return true
        Thread.sleep(80)
      exists(name)

    def drainAndRefresh(anchor: String, retries: Int = 3): Boolean =
      var attempt = 0
      while attempt < retries do
        if !waitFor(anchor, 15) then attempt = retries
        else
          var stable = 0
          var previous = transcript
          while stable < 3 do
            Thread.sleep(100)
            val current = transcript
            if current == previous then stable += 1 else stable = 0
            previous = current
          if transcript.contains(anchor) then return true
          attempt += 1
      transcript.contains(anchor)

    def token: Option[String] =
      Token.findFirstMatchIn(transcript).map(_.group(1))

    def save(label: String): Path =
      val path = evidence.resolve(s"$label.txt")
      Files.writeString(path, transcript, Utf8)
      path

    def close(): Unit =
      try
        if process.isAlive then
          send(":quit\r")
          if !process.waitFor(10, TimeUnit.SECONDS) then
            process.destroyForcibly()
      catch case _: Exception => process.destroyForcibly()
      finally
        stand.close()
        val walk = Files.walk(root)
        try
          walk
            .sorted(java.util.Comparator.reverseOrder[Path]())
            .forEach(Files.deleteIfExists(_))
        finally walk.close()

  def spec = suite("ToolApprovalChatPtySpec (RAI-189 product chat entry)")(
    test(
      "approval, typed refusal, read-only, and pending-wait P2W each repeat three times"
    ) {
      val results = Vector.newBuilder[Boolean]
      val paths = Vector.newBuilder[Path]
      for repetition <- 1 to 3 do
        val approved = new Session(Case.Approve, s"approve-$repetition")
        try
          val ready = approved.waitFor("you> ", 120)
          approved.send("apply the scripted edit\r")
          val pending = approved.waitFor("approval required |", 90)
          val oneRequest = approved.waitForRequests(1)
          val token = approved.token
          approved.send("draft-pending-界")
          val draftPreserved = approved.waitFor("draft-pending-界")
          val cardWhileTyping =
            approved.transcript.contains("approval required |")
          approved.send("\u0001\u000b")
          token.foreach(t => approved.send(s":approve $t\r"))
          val applied = approved.waitForFile("approved.txt", 90)
          val effect = approved.exists("approved.txt") &&
            approved.read("approved.txt").startsWith("applied-by-tool")
          val twoRequests = approved.waitForRequests(2)
          val drained = approved.drainAndRefresh("scripted completion")
          val out = approved.save(s"approve-$repetition")
          paths += out
          val checks = List(
            "ready" -> ready,
            "pending" -> pending,
            "oneRequest" -> oneRequest,
            "token" -> token.isDefined,
            "draftPreserved" -> draftPreserved,
            "cardWhileTyping" -> cardWhileTyping,
            "applied" -> applied,
            "effect" -> effect,
            "twoRequests" -> twoRequests,
            "drained" -> drained,
            "completion" -> approved.transcript.contains("scripted completion"),
            "noException" -> !approved.transcript.contains("Exception")
          )
          Files.writeString(
            out.resolveSibling(s"approve-$repetition.checks.txt"),
            checks.mkString("\n"),
            Utf8
          )
          results += checks.forall(_._2)
        finally approved.close()

        val denied = new Session(Case.Deny, s"deny-$repetition")
        try
          val ready = denied.waitFor("you> ", 120)
          denied.send("apply a denied scripted edit\r")
          val pending = denied.waitFor("approval required |", 90)
          val token = denied.token
          token.foreach(t => denied.send(s":deny $t\r"))
          val refusal = denied.waitFor("RA-TOOLDENY", 90)
          val noEffect = !denied.exists("denied.txt")
          val drained = denied.drainAndRefresh("RA-TOOLDENY")
          val out = denied.save(s"deny-$repetition")
          paths += out
          results += (ready && pending && token.isDefined && refusal && noEffect && drained &&
            !denied.transcript.contains("approval granted") &&
            !denied.transcript.contains("Exception"))
        finally denied.close()

        val readOnly = new Session(Case.ReadOnly, s"readonly-$repetition")
        try
          val ready = readOnly.waitFor("you> ", 120)
          readOnly.send("read the scripted fixture\r")
          val tool = readOnly.waitFor("tool card | fs_read", 90)
          val complete = readOnly.waitFor("scripted completion", 90)
          val drained = readOnly.drainAndRefresh("scripted completion")
          val out = readOnly.save(s"readonly-$repetition")
          paths += out
          results += (ready && tool && complete && drained && readOnly
            .waitForRequests(2) &&
            !readOnly.transcript.contains("approval required |") &&
            !readOnly.transcript.contains("Exception"))
        finally readOnly.close()

      val evidence = paths.result()
      assertTrue(
        results.result().size == 9,
        results.result().forall(identity),
        evidence.forall(Files.exists(_))
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(
    zio.Duration.fromSeconds(1800)
  )

end ToolApprovalChatPtySpec
