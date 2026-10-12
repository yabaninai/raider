package raider.repl

import zio._
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** RAI-111 / PAL2 — PTY acceptance of the chat-command palette on the PRODUCT
  * chat REPL host: `raider.repl.Main` (the legacy W10 cycle — mock provider by
  * default, real in-process compiler, real JLine line editor with the RAI-068
  * EngineCompleter), entered through the test-scope launcher ReplChatPtyApp
  * (PTY winsize fix only — Main itself is frozen for this lane). Driver
  * mechanics mirror the RAI-106 reference (TabShellPalettePtySpec):
  * /usr/bin/script openpty relay, capability-query answering, ANSI-cleaning;
  * the oracle here is the transcript itself plus the executed-command output
  * (the chat cycle has no registry report file). No --tabs involvement: the
  * default entry is regression-protected by RAI-108's own acceptance (the
  * legacy loop is unchanged without the flag).
  *
  * Acceptance (card RAI-111):
  *   - PAL2-01: a chat command is found and executed in ≤2 presses — the prefix
  *     ":jo" + TAB completes to ":jobs" (press 1) and Enter (press 2) EXECUTES
  *     it (the "no jobs started in this session" listing is the execution
  *     oracle); same for ":ca" → ":cancel" usage. The freshness half of PAL2-01
  *     is the unit fixture ReplChatPaletteSpec (equality of the registry with
  *     the parsed Commands.helpText source of truth).
  *   - PAL2-02 (chat-cycle corroboration): an unknown command is a typed error
  *     ("unknown command :hlp — try :help"), the shell stays alive and keeps
  *     executing commands afterwards.
  *
  * Limitations (same class as the RAI-106 reference): line atomicity is judged
  * on the final cleaned transcript; capability queries are answered per read
  * chunk. Engine startup compiles the prelude — the first-prompt wait is
  * correspondingly generous.
  */
object ReplChatCyclePtySpec extends ZIOSpecDefault:

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

  // ----- the PTY session (script-run product chat REPL: raider.repl.Main) --

  private final class PtySession(evidenceDir: Path):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory("rai111-chat-pty-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.ReplChatPtyApp"
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
        evidenceDir.resolve("transcript-chat-cycle.txt"),
        cleanedText,
        StandardCharsets.UTF_8
      )
      Files.writeString(
        evidenceDir.resolve("pty-chat-cycle-summary.json"),
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
      .mkString(s"""{"name":"pal2-chat-cycle",""", ",", "}")

  private def boolJson(b: Boolean): String = if b then "true" else "false"

  private def crashed(text: String): Boolean =
    text.contains("Exception") || text.contains("at raider.")

  // ----- PAL2-01 (+ PAL2-02 chat-cycle corroboration) -----

  private def pal2: ZIO[Any, Throwable, TestResult] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI111_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None =>
          Files.createTempDirectory("rai111-chat-pty-ev")
      val session = PtySession(evidence)
      try
        // Engine startup: the prelude (scripted backend, session, facade
        // bindings) compiles through the real in-process compiler.
        val promptUp = session.waitFor(Prompt, 240.seconds)
        // PAL2-01, command 1: prefix ":jo" + TAB (press 1) must complete to
        // ":jobs"; Enter (press 2) executes it. The completion itself is NOT
        // asserted on the transcript (":jobs" also occurs in the banner) —
        // the EXECUTION OUTPUT is the discriminating oracle: a failed TAB
        // would submit the bare ":jo" and print an unknown-command hint
        // instead (RAI-106 P2P-01 oracle pattern).
        session.send(":jo\t")
        session.send("\r")
        val jobsRun =
          session.waitFor("no jobs started in this session", 30.seconds)
        // PAL2-01, command 2: ":ca" + TAB → ":cancel"; Enter → the usage line
        // (an unknown ":ca" would NOT print a usage line).
        session.send(":ca\t")
        session.send("\r")
        val cancelRun = session.waitFor("usage: :cancel <jobId>", 30.seconds)
        // PAL2-02 (chat-cycle corroboration): unknown → typed error, alive.
        session.send(":hlp\r")
        val unknown =
          session.waitFor("unknown command :hlp — try :help", 20.seconds)
        session.send(":help\r")
        val helpRun =
          session.waitFor("Everything else is evaluated as Scala", 20.seconds)
        session.send(":quit\r")
        val code = session.finishWait(60.seconds)
        val text = session.cleaned
        val unknownCount = countSubstr(text, "unknown command")
        session.persist(
          text,
          summaryJson(
            "promptUp" -> boolJson(promptUp),
            "jobsRun" -> boolJson(jobsRun),
            "cancelRun" -> boolJson(cancelRun),
            "unknown" -> boolJson(unknown),
            "unknownCount" -> unknownCount.toString,
            "helpRun" -> boolJson(helpRun),
            "noCrash" -> boolJson(!crashed(text)),
            "exit" -> code.toString
          )
        )
        assertTrue(promptUp) &&
        assertTrue(jobsRun) &&
        assertTrue(cancelRun) &&
        assertTrue(unknown && unknownCount == 1) &&
        assertTrue(helpRun) &&
        assertTrue(!crashed(text)) &&
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

  private def countSubstr(haystack: String, needle: String): Int =
    if needle.isEmpty then 0
    else
      var idx = haystack.indexOf(needle)
      var n = 0
      while idx >= 0 do
        n += 1
        idx = haystack.indexOf(needle, idx + needle.length)
      n

  def spec =
    suite(
      "ReplChatCyclePtySpec (RAI-111: chat palette on the product chat REPL)"
    )(
      test(
        "PAL2-01/02: :jo+TAB executes :jobs ≤2 presses; unknown typed; alive (PTY)"
      )(pal2)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(600.seconds)

end ReplChatCyclePtySpec
