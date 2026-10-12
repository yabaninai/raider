package raider.repl

import zio._
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** RAI-124 / TYPE-03 — PTY acceptance of the `:type <expression>` command on
  * the PRODUCT chat REPL host: `raider.repl.Main` (the legacy W10 cycle — mock
  * provider by default, real in-process compiler, real JLine line editor),
  * entered through the test-scope launcher TypeCommandsPtyApp (PTY winsize fix
  * only). Driver mechanics mirror the RAI-106/RAI-111 reference
  * (ReplChatCyclePtySpec): /usr/bin/script openpty relay, capability-query
  * answering, ANSI-cleaning; the oracle is the transcript itself.
  *
  * Acceptance (card RAI-124):
  *   - TYPE-03a: a live-session query answers with the exact type and the
  *     prompt stays alive (":type List(1, 2)" → "List[Int]"; a session binding
  *     is visible: "val n = 7" then ":type n" → "Int").
  *   - TYPE-03b: DURING streaming — a background job is started through the
  *     facade (the scripted backend streams its answer to the job; the prompt
  *     remains interactive) and a :type query issued while it runs answers
  *     normally.
  *   - TYPE-03c: AFTER a compile error — an intentionally broken line is the
  *     honest compiler error, the prompt stays alive and the next :type works.
  *   - Freshness: :help lists :type and the empty query prints the driver's
  *     usage line — both visible on the live surface.
  *
  * Flake discipline: the test asserts only order-independent substring outcomes
  * on the final cleaned transcript; every step waits for its own oracle before
  * sending the next input. Evidence (transcript + summary JSON) is persisted
  * per run; the card requires ×3 runs with flake = 0 (recorded in the work
  * record).
  */
object TypeCommandsPtySpec extends ZIOSpecDefault:

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
    private val home = Files.createTempDirectory("rai124-type-pty-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.TypeCommandsPtyApp"
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

    /** Flake discipline for REPEATED oracles: waits until `substr` has been
      * seen at least `n` times on the cleaned transcript (a plain contains wait
      * would trivially pass on an earlier occurrence).
      */
    def waitForCount(substr: String, n: Int, timeout: Duration): Boolean =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while JSystem.nanoTime() < deadline do
        if countSubstr(cleaned, substr) >= n then return true
        Thread.sleep(150)
      countSubstr(cleaned, substr) >= n

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
        evidenceDir.resolve("transcript-type-commands.txt"),
        cleanedText,
        StandardCharsets.UTF_8
      )
      Files.writeString(
        evidenceDir.resolve("pty-type-commands-summary.json"),
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
      .mkString(s"""{"name":"rai124-type-pty",""", ",", "}")

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

  // ----- TYPE-03 -----

  private def type03: ZIO[Any, Throwable, TestResult] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI124_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None =>
          Files.createTempDirectory("rai124-type-pty-ev")
      val session = PtySession(evidence)
      try
        // Engine startup: the prelude (scripted backend, session, facade
        // bindings) compiles through the real in-process compiler.
        val promptUp = session.waitFor(Prompt, 240.seconds)

        // TYPE-03a: live-session query — exact type, prompt alive.
        session.send(":type List(1, 2)\r")
        val listType = session.waitFor("List[Int]", 30.seconds)
        // The binding probe uses a type name that occurs NOWHERE else on the
        // transcript (a plain "Int" would trivially match the banner/echoes).
        session.send("val counter = 7L\r")
        val bound = session.waitFor("val counter: Long = 7", 30.seconds)
        session.send(":type counter\r")
        val bindingType = session.waitFor("Long", 30.seconds)

        // TYPE-03b: DURING streaming — a background job streams through the
        // scripted backend while the prompt stays interactive; the query on a
        // session binding resolves while the job runs. (The started-job
        // oracle is the binding echo itself: start() returned the handle.)
        session.send("val j = worker.start(\"bg\")\r")
        val jobStarted =
          session.waitFor("val j: raider.runtime.jobs.JobHandle", 30.seconds)
        session.send(":type j\r")
        // JobHandle[String] renders qualified — the class name is the honest
        // discriminating substring.
        val jobType = session.waitFor("JobHandle[String]", 30.seconds)
        session.send("j.await()\r")
        val jobDone = session.waitFor("mock answer", 60.seconds)

        // TYPE-03c: AFTER a compile error — honest diagnostic, prompt alive,
        // the next query works (second "List[Int]" occurrence — count-based).
        // The broken line must NOT end in a continuation token ("=") — the
        // accepted W10 multiline heuristic would keep buffering it.
        session.send("val broken = = 1\r")
        val compileError = session.waitFor("Syntax Error", 30.seconds)
        session.send(":type List(1, 2)\r")
        val aliveAfterError =
          session.waitForCount("List[Int]", 2, 30.seconds)

        // Freshness on the live surface: the empty query prints the driver's
        // own usage line FIRST, then :help lists :type (the help line also
        // contains the usage token, so the order matters for honest oracles).
        session.send(":type\r")
        val usage = session.waitFor(":type <expression>", 30.seconds)
        session.send(":help\r")
        val helpListsType =
          session.waitFor(
            "the type of an expression, compiled but not run",
            30.seconds
          )

        session.send(":quit\r")
        val code = session.finishWait(60.seconds)
        val text = session.cleaned
        session.persist(
          text,
          summaryJson(
            "promptUp" -> boolJson(promptUp),
            "listType" -> boolJson(listType),
            "bound" -> boolJson(bound),
            "bindingType" -> boolJson(bindingType),
            "jobStarted" -> boolJson(jobStarted),
            "jobType" -> boolJson(jobType),
            "jobDone" -> boolJson(jobDone),
            "compileError" -> boolJson(compileError),
            "aliveAfterError" -> boolJson(aliveAfterError),
            "helpListsType" -> boolJson(helpListsType),
            "usage" -> boolJson(usage),
            "noCrash" -> boolJson(!crashed(text)),
            "exit" -> code.toString
          )
        )
        assertTrue(promptUp) &&
        assertTrue(listType) &&
        assertTrue(bound && bindingType) &&
        assertTrue(jobStarted && jobType && jobDone) &&
        assertTrue(compileError && aliveAfterError) &&
        assertTrue(helpListsType && usage) &&
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

  def spec =
    suite(
      "TypeCommandsPtySpec (RAI-124: :type on the live product chat REPL)"
    )(
      test(
        "TYPE-03: live/during-streaming/after-error queries; prompt alive; help freshness (PTY)"
      )(type03)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(600.seconds)

end TypeCommandsPtySpec
