package raider.repl

import zio._
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** RAI-132 / STR-03 — PTY acceptance of `:send` during ACTIVE streaming on the
  * PRODUCT chat REPL host: `raider.repl.Main` (the legacy no-tabs cycle — mock
  * provider by default, real in-process compiler, real JLine line editor),
  * entered through the test-scope launcher SteerPtyApp (PTY winsize fix only).
  * Driver mechanics mirror the RAI-111 reference (ReplChatCyclePtySpec):
  * /usr/bin/script openpty relay, capability-query answering, ANSI cleaning;
  * the oracle is the transcript itself.
  *
  * The streaming window is REAL but CONTROLLED without sleeps-as-assertions:
  * the interpreter-world fixture backend paces 10 text deltas 250ms apart, so
  * the driver's `:send` (issued right after the first delta appears) always
  * lands MID-TURN — an ~2.3s overlap window makes the race deterministic (×3
  * flake=0 obligation). The receipt print happens on the REPL thread (the one
  * console writer); the job fiber prints only its streamed deltas.
  *
  * Streaming visibility note (honest instrumentation, RAI-132 finding): the
  * PRODUCT delta echo (FacadeOps.echoBackend → zio.Console.print →
  * ZIO.attemptBlockingIO) is swallowed in this environment for
  * INTERPRETER-world background fibers — reproduced with a zero-facade repro (a
  * forked `zio.Console.printLine` never surfaces, while raw
  * `java.lang.System.out` does). The suppression is pre-existing (the W10
  * `Task(textAttempt)` start path used the identical echo call chain on the
  * same JobManager fiber) and is NOT changed here. The fixture backend
  * therefore prints its paced deltas via raw System.out+flush — the SAME job
  * fiber, the SAME interleaving surface the product echo would write to — so
  * the transcript shows the true async feed. The echo itself works in a plain
  * JVM runtime (unit-level verified); the REPL-interpreter finding belongs to a
  * separate card.
  *
  * Acceptance (card RAI-132, STR-03):
  *   - a background steerable job streams deltas while the prompt is free;
  *   - `:send <id> <text>` during active streaming answers with the QUEUED
  *     receipt as ONE intact line (no prompt tear, no delta interleaved into
  *     the receipt);
  *   - steering is APPLIED at the turn boundary: the job runs a SECOND streamed
  *     turn (observable as a second full delta pass) — never silent;
  *   - `:jobs` marks the running job `[steerable]` exactly once (gone when
  *     terminal);
  *   - the legacy no-tabs cycle stays fully alive afterwards (commands, Scala
  *     evals, clean exit 0) — the no-tabs regression obligation.
  */
object SteerCommandsPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-repl-chat\u001b\\")
  )

  // ----- the PTY session (script-run product REPL: raider.repl.Main) --------

  private final class PtySession(evidenceDir: Path):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory("rai132-steer-pty-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.SteerPtyApp"
    )

    pb.redirectErrorStream(true)
    pb.directory(Files.createDirectories(home.resolve("cwd")).toFile)
    pb.environment().put("TERM", "xterm-256color")
    pb.environment().put("NO_COLOR", "1")
    pb.environment().put("HOME", home.toString)
    // no RAIDER_* variables: the default mock/scripted provider path — no
    // network, no credentials, no paid calls.

    private val rawLock = new Object
    private val raw = new java.io.ByteArrayOutputStream()
    private val proc = pb.start()
    private val toApp = proc.getOutputStream
    private val fromApp = proc.getInputStream

    private val pump = new Thread(() => pumpLoop())
    pump.setDaemon(true)
    pump.start()

    private def pumpLoop(): Unit =
      try
        val chunk = new Array[Byte](8192)
        var n = fromApp.read(chunk)
        while n >= 0 do
          rawLock.synchronized(raw.write(chunk, 0, n))
          answerQueries(chunk, n)
          n = fromApp.read(chunk)
      catch case _: java.io.IOException => () // process exit
      end try

    private def answerQueries(chunk: Array[Byte], n: Int): Unit =
      val text = new String(chunk, 0, n, StandardCharsets.ISO_8859_1)
      Queries.foreach { case (q, a) =>
        if text.contains(q) then
          sendRaw(a.getBytes(StandardCharsets.ISO_8859_1))
      }

    private def sendRaw(bytes: Array[Byte]): Unit =
      toApp.synchronized:
        toApp.write(bytes)
        toApp.flush()

    def send(s: String): Unit = sendRaw(s.getBytes(StandardCharsets.UTF_8))

    def cleaned: String =
      val bytes = rawLock.synchronized(raw.toByteArray)
      Ansi.replaceAllIn(new String(bytes, StandardCharsets.UTF_8), "")

    def waitFor(substr: String, timeout: Duration): Boolean =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while JSystem.nanoTime() < deadline do
        if cleaned.contains(substr) then return true
        Thread.sleep(150)
      cleaned.contains(substr)

    def finishWait(timeout: Duration): Int =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while proc.isAlive && JSystem.nanoTime() < deadline do Thread.sleep(200)
      if proc.isAlive then
        proc.destroy()
        proc.waitFor(5, TimeUnit.SECONDS)
        if proc.isAlive then proc.destroyForcibly()
        proc.waitFor(5, TimeUnit.SECONDS)
        -9
      else proc.exitValue()

    def persist(cleanedText: String, summary: String): Unit =
      Files.createDirectories(evidenceDir)
      Files.writeString(
        evidenceDir.resolve("transcript-steer.txt"),
        cleanedText,
        StandardCharsets.UTF_8
      )
      Files.writeString(
        evidenceDir.resolve("pty-steer-summary.json"),
        summary,
        StandardCharsets.UTF_8
      )

    def close(): Unit =
      try
        if proc.isAlive then
          proc.destroy()
          proc.waitFor(3, TimeUnit.SECONDS)
          if proc.isAlive then proc.destroyForcibly()
        toApp.close()
        fromApp.close()
        deleteRecursively(home)
      catch case _: Exception => ()

    private def deleteRecursively(path: Path): Unit =
      try
        val stream = Files.walk(path)
        try
          stream
            .sorted(java.util.Comparator.reverseOrder[Path]())
            .forEach(p => Files.deleteIfExists(p))
        finally stream.close()
      catch case _: Exception => ()

  end PtySession

  private def summaryJson(fields: (String, String)*): String =
    fields
      .map((k, v) => s"\"$k\":$v")
      .mkString(s"""{"name":"str3-steer-pty",""", ",", "}")

  private def boolJson(b: Boolean): String = if b then "true" else "false"

  private def crashed(text: String): Boolean =
    text.contains("Exception") || text.contains("at raider.")

  private def countSubstr(haystack: String, needle: String): Int =
    if needle.isEmpty then 0
    else
      var idx = haystack.indexOf(needle)
      var n = 0
      while idx >= 0 do
        n += 1
        idx = haystack.indexOf(needle, idx + needle.length)
      n

  /** Interpreter-world fixture wiring: a PACED scripted backend (10 deltas,
    * 250ms apart — the controlled streaming window) in a session with
    * echoStream=true, then a background steerable start. Each line is the exact
    * console submission (one physical line; the driver sends it + Enter).
    */
  private def setupLines: Vector[String] = Vector(
    "import raider.core.{AttemptId, CapabilityStatus, ModelBackend, ModelCapabilities, ModelEvent, ModelRequest, RaiderError}",
    "import raider.runtime.budget.BudgetLimits",
    "val __att = AttemptId(\"st3\")",
    "val __slow = new ModelBackend { def capabilities = ModelCapabilities(CapabilityStatus.Supported, CapabilityStatus.Supported, CapabilityStatus.Unknown); def stream(input: ModelRequest) = zio.stream.ZStream.range(0, 11).flatMap { n => if n < 10 then zio.stream.ZStream.fromZIO(zio.ZIO.sleep(zio.Duration.fromMillis(250)).flatMap(_ => zio.ZIO.succeed { java.lang.System.out.print(\"[delta-\" + (n + 1) + \"] \"); java.lang.System.out.flush() }).as(ModelEvent.TextDelta(__att, \"[delta-\" + (n + 1) + \"] \"))) else zio.stream.ZStream(ModelEvent.Finished(__att, \"stop\")) } }",
    "val __ssession = zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(ReplSession.make(__slow, BudgetLimits.make(), raider.core.ToolRegistry.empty, \"scripted\", true)).getOrThrow() }",
    "val j = scout.start(\"bg\")(using __ssession)"
  )

  // ----- STR-03 -----

  private def str3: ZIO[Any, Throwable, TestResult] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI132_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None =>
          Files.createTempDirectory("rai132-steer-pty-ev")
      val session = PtySession(evidence)
      try
        // Engine startup: the prelude (scripted backend, session, facade
        // bindings) compiles through the real in-process compiler.
        val promptUp = session.waitFor(Prompt, 240.seconds)
        setupLines.foreach(line => session.send(line + "\r"))
        val wired =
          session.waitFor("val j:", 60.seconds) &&
            session.waitFor("[delta-1] ", 60.seconds)
        // :jobs marks the running job steerable
        session.send(":jobs\r")
        val markedSteerable = session.waitFor("[steerable]", 30.seconds)
        val jobId = raw"j_\d+".r
          .findFirstMatchIn(session.cleaned)
          .map(_.group(0))
          .getOrElse("j_missing")
        // :send DURING active streaming — the receipt must be ONE intact line
        session.send(s":send $jobId hello-stream\r")
        val queued = session.waitFor("queued ", 30.seconds)
        val text = session.cleaned
        val receiptIntact =
          raw"""queued j_\d+ \[st-\d+\] "hello-stream" — applies at the next model turn \(pending \d+\)""".r
            .findFirstIn(text)
            .isDefined
        // steering is APPLIED at the boundary: the job runs a SECOND streamed
        // turn (the same paced pass again) — the steering text became part of
        // the next model-turn input, observable on the transcript; a rejection
        // line would prove the opposite (typed receipts are never silent)
        val secondPass = waitForCount(session, "[delta-10] ", 2, 120.seconds)
        val streamUnaffected = countSubstr(session.cleaned, "[delta-1] ") >= 2
        // drain the job (foreground await), then the terminal :jobs
        session.send("j.await()\r")
        val awaited = session.waitFor("val res", 60.seconds)
        session.send(":jobs\r")
        val terminalListed = session.waitFor("Succeeded", 30.seconds)
        session.send(":help\r")
        val helpRun =
          session.waitFor("Everything else is evaluated as Scala", 20.seconds)
        session.send("1 + 1\r")
        val evalRun = session.waitFor("val res1: Int = 2", 30.seconds)
        session.send(":quit\r")
        val code = session.finishWait(60.seconds)
        val finalText = session.cleaned
        val steerableMarks = countSubstr(finalText, "[steerable]")
        val receipts = countSubstr(finalText, "queued ")
        val notRejected = !finalText.contains("rejected ")
        session.persist(
          finalText,
          summaryJson(
            "promptUp" -> boolJson(promptUp),
            "wired" -> boolJson(wired),
            "markedSteerable" -> boolJson(markedSteerable),
            "jobId" -> ("\"" + jobId + "\""),
            "queued" -> boolJson(queued),
            "receiptIntact" -> boolJson(receiptIntact),
            "secondPass" -> boolJson(secondPass),
            "notRejected" -> boolJson(notRejected),
            "streamUnaffected" -> boolJson(streamUnaffected),
            "awaited" -> boolJson(awaited),
            "terminalListed" -> boolJson(terminalListed),
            "helpRun" -> boolJson(helpRun),
            "evalRun" -> boolJson(evalRun),
            "steerableMarks" -> steerableMarks.toString,
            "queuedReceipts" -> receipts.toString,
            "noCrash" -> boolJson(!crashed(finalText)),
            "exit" -> code.toString
          )
        )
        assertTrue(promptUp) &&
        assertTrue(wired) &&
        assertTrue(markedSteerable) &&
        assertTrue(jobId.startsWith("j_")) &&
        assertTrue(queued && receiptIntact) &&
        assertTrue(secondPass) &&
        assertTrue(notRejected) &&
        assertTrue(streamUnaffected) &&
        assertTrue(awaited) &&
        assertTrue(terminalListed) &&
        assertTrue(steerableMarks == 1) &&
        assertTrue(receipts == 1) &&
        assertTrue(helpRun) &&
        assertTrue(evalRun) &&
        assertTrue(!crashed(finalText)) &&
        assertTrue(code == 0)
      catch
        case e: Throwable =>
          session.persist(
            session.cleaned,
            summaryJson("error" -> ("\"" + String.valueOf(e.getMessage) + "\""))
          )
          throw e
      finally session.close()
    }

  private def waitForCount(
      s: PtySession,
      substr: String,
      n: Int,
      timeout: Duration
  ): Boolean =
    val deadline = JSystem.nanoTime() + timeout.toNanos
    while JSystem.nanoTime() < deadline do
      if countSubstr(s.cleaned, substr) >= n then return true
      Thread.sleep(150)
    countSubstr(s.cleaned, substr) >= n

  def spec =
    suite(
      "SteerCommandsPtySpec (RAI-132 STR-03: :send during active streaming, product no-tabs cycle)"
    )(
      test(
        "STR-03: :send mid-stream — queued receipt intact, second streamed turn, [steerable] marked once, legacy cycle alive (PTY)"
      )(str3)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(600.seconds)

end SteerCommandsPtySpec
