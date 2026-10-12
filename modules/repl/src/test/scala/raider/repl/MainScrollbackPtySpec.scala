package raider.repl

import zio._
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** RAI-113 — PTY acceptance of the PRODUCT REPL ENTRY (raider.repl.Main --tabs)
  * running the `:scrollback [tabId]` consumer step, modeled on the accepted
  * MainTabsPtySpec driver (RAI-108): TERM/NO_COLOR are driver-owned, capability
  * queries are answered, HOME is isolated, transcripts are ANSI-cleaned,
  * RAIDER_PROVIDER=mock is pinned (zero network, zero paid calls). The child is
  * the REAL product entry — `java -cp <test-classpath> raider.repl.Main --tabs`
  * — under `/usr/bin/script` (openpty + setsid + slave on 0/1/2); the wrapper
  * sets the winsize via stty before exec. The scrollback interceptor under test
  * is the product wiring (`TabShellHost.run(wiring,
  * ScrollbackCommands(registry))`).
  *
  * The 10k burst of P2SR-01 uses the scripted fixture stream's env knobs
  * (RAI113_SCRIPTED_TAB_LINES/RAI113_SCRIPTED_TAB_DELAY_MS — mock-gated by
  * construction, defaults unchanged), so the REAL entry demonstrates the
  * eviction counter of the bounded scrollback.
  *
  * Acceptance (card RAI-113):
  *   - P2SR-01: burst 10k lines → `:scrollback alpha` prints the EXACT header
  *     `scrollback [alpha]: last 500 of 10000 lines (9500 evicted, cap 500)`
  *     with a byte-exact 500-line window; the prompt stays alive; clean exit.
  *   - P2SR-02: `:scrollback ghost` and a MISSING id are typed
  *     `error: unknown tab …` lines; no phantom tabs; the shell stays alive and
  *     the command works again immediately after an error.
  *   - P2SR-03: rendering DURING streaming — one writer (0 torn bar rows),
  *     prompt editable; and WITHOUT --tabs the legacy loop is unchanged:
  *     `:scrollback` is the frozen `unknown command` answer, no tabs machinery.
  *
  * Limitations (same class as the accepted RAI-108 spec): line atomicity is
  * judged on the final cleaned transcript; capability queries are answered per
  * read chunk; Ctrl+C/resize are out of scope (covered for the same shell by
  * RAI-062 PTY-02).
  */
object MainScrollbackPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  /** A full tab-bar row (RAI-097 statusText is free text inside brackets). */
  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  /** The full payload tail of one window row (merge-immune content anchor: a
    * rendered row counts only if the whole 32-byte payload is contiguous).
    */
  private val PayloadTail: String = " payload " + ("x" * 32)

  /** Capability queries JLine may emit, with canned answers (accepted-spec
    * parity).
    */
  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-scrollback\u001b\\")
  )

  private def countSubstr(haystack: String, needle: String): Int =
    if needle.isEmpty then 0
    else
      var idx = haystack.indexOf(needle)
      var n = 0
      while idx >= 0 do
        n += 1
        idx = haystack.indexOf(needle, idx + needle.length)
      n

  /** Exact-line lookup over the trimmed rendered rows (immune to trailing
    * spaces and prompt-redraw artifacts).
    */
  private def hasExactLine(cleaned: String, line: String): Boolean =
    cleaned.split("[\r\n]").map(_.trim).contains(line)

  /** Window-line CONTENT count (merge-immune): every rendered window line
    * carries exactly one full payload tail; the header and every other
    * transcript row carry none. JLine printAbove calls are individually atomic,
    * but under heavy machine load the cleaned TRANSCRIPT can glue two rendered
    * rows into one visual line (the bar pump interleaves between them) — the
    * CONTENT stays byte-complete, so the exact window assertion counts payload
    * tails, not line breaks.
    */
  private def payloadTails(cleaned: String): Int =
    countSubstr(cleaned, PayloadTail)

  /** Terminal lines end with CR or LF; splitting on both gives the rendered
    * rows (MainTabsPtySpec barScan).
    */
  private def barScan(cleaned: String): (Int, Int) =
    val lines = cleaned.split("[\r\n]").map(_.stripTrailing).filter(_.nonEmpty)
    val rows = lines.count(l => RowFull.matches(l))
    val bad =
      lines.count(l => RowLike.findFirstIn(l).isDefined && !RowFull.matches(l))
    (rows, bad)

  // ----- the PTY session (script-run REAL product Main) -----

  private final class PtySession(
      name: String,
      tabs: Boolean,
      extraEnv: Map[String, String],
      evidenceDir: Path
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai113-pty-$name-home")

    /** The launch wrapper sets the pty winsize on the slave before exec — the
      * product entry itself is untouched.
      */
    private val inner =
      s"/bin/stty rows 40 cols 120; exec '$javaBin' -cp '$cp' raider.repl.Main${
          if tabs then " --tabs" else ""
        }"

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      "/bin/sh",
      "-c",
      inner
    )

    pb.redirectErrorStream(true)
    pb.directory(Files.createDirectories(home.resolve("cwd")).toFile)
    pb.environment().put("TERM", "xterm-256color")
    pb.environment().put("NO_COLOR", "1")
    pb.environment().put("HOME", home.toString)
    // Pinned mock provider: the scripted fixture backend, no network, no
    // paid calls — regardless of anything ambient in the driver's env.
    pb.environment().put("RAIDER_PROVIDER", "mock")
    extraEnv.foreach((k, v) => pb.environment().put(k, v))

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
        Thread.sleep(120)
      cleaned.contains(substr)

    /** Quiescence (transcript byte-stable for `stable` while not typing) — the
      * honest drain signal before :quit (RAI-105 contract-change rationale).
      */
    def waitQuiet(stable: Duration, timeout: Duration): Boolean =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      var lastLen = cleaned.length
      var lastQuiet = JSystem.nanoTime()
      while JSystem.nanoTime() < deadline do
        val cur = cleaned.length
        if cur != lastLen then
          lastLen = cur
          lastQuiet = JSystem.nanoTime()
        if JSystem.nanoTime() - lastQuiet >= stable.toNanos then return true
        Thread.sleep(150)
      false

    def finishWait(timeout: Duration): Int =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while proc.isAlive && JSystem.nanoTime() < deadline do Thread.sleep(150)
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
        evidenceDir.resolve(s"transcript-$name.txt"),
        cleanedText,
        StandardCharsets.UTF_8
      )
      Files.writeString(
        evidenceDir.resolve(s"pty-$name-summary.json"),
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

  private def summaryJson(name: String, fields: (String, String)*): String =
    fields
      .map((k, v) => s"\"$k\":$v")
      .mkString(s"""{"name":"$name",""", ",", "}")

  private def boolJson(b: Boolean): String = if b then "true" else "false"

  /** Drive one session; persist evidence even on failure; always close. */
  private def drive[A](
      name: String,
      tabs: Boolean,
      extraEnv: Map[String, String] = Map.empty
  )(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI113_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai113-pty-$name-ev")
      val session = PtySession(name, tabs, extraEnv, evidence)
      try body(session)
      catch
        case e: Throwable =>
          session.persist(
            session.cleaned,
            summaryJson(
              name,
              "error" -> ("\"" + String.valueOf(e.getMessage) + "\"")
            )
          )
          throw e
      finally session.close()
    }

  /** The honest crash anchors of the accepted specs. */
  private def crashed(text: String): Boolean =
    text.contains("Exception") || text.contains("at raider.")

  // ----- P2SR-01: 10k burst — exact header, byte-exact window, prompt alive

  private def p2sr01: ZIO[Any, Throwable, TestResult] =
    drive(
      "p2sr01",
      tabs = true,
      Map(
        "RAI113_SCRIPTED_TAB_LINES" -> "10000",
        "RAI113_SCRIPTED_TAB_DELAY_MS" -> "2"
      )
    ) { s =>
      val promptUp = s.waitFor(Prompt, 120.seconds)
      s.send(":new alpha\r")
      val created = s.waitFor("tab created: alpha", 30.seconds)
      // The 10k-line fixture burst (10k × 2ms ≈ 20s of live rendering);
      // quiescence = the stream settled before the render.
      val drained = s.waitQuiet(1500.millis, 120.seconds)
      s.send(":scrollback alpha\r")
      val header =
        s.waitFor(
          s"scrollback [alpha]: last 500 of 10000 lines (9500 evicted, cap 500)",
          30.seconds
        )
      // The prompt stays alive after the render.
      s.send("привет\r")
      val draftAlive = s.waitFor("draft [alpha]: привет", 25.seconds)
      s.send(":quit\r")
      val bye = s.waitFor("bye", 30.seconds)
      val code = s.finishWait(60.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      val headerCount =
        countSubstr(
          text,
          "scrollback [alpha]: last 500 of 10000 lines " +
            "(9500 evicted, cap 500)"
        )
      val window = payloadTails(text)
      val firstWindow = text.contains("ev alpha 9501" + PayloadTail)
      val lastWindow = text.contains("ev alpha 10000" + PayloadTail)
      s.persist(
        text,
        summaryJson(
          "p2sr01",
          "promptUp" -> boolJson(promptUp),
          "created" -> boolJson(created),
          "drained" -> boolJson(drained),
          "exactHeader" -> boolJson(header),
          "headerCount" -> headerCount.toString,
          "windowRows" -> window.toString,
          "firstWindowLine" -> boolJson(firstWindow),
          "lastWindowLine" -> boolJson(lastWindow),
          "draftAlive" -> boolJson(draftAlive),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "noCrash" -> boolJson(!crashed(text)),
          "bye" -> boolJson(bye),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(created && drained) &&
      assertTrue(header && headerCount == 1) &&
      assertTrue(window == 500) &&
      assertTrue(firstWindow && lastWindow) &&
      assertTrue(draftAlive) &&
      assertTrue(bad == 0) &&
      assertTrue(!crashed(text)) &&
      assertTrue(bye && code == 0)
    }

  // ----- P2SR-02: ghost/missing — typed UnknownTab; repeat works -----

  private def p2sr02: ZIO[Any, Throwable, TestResult] =
    drive("p2sr02", tabs = true) { s =>
      val promptUp = s.waitFor(Prompt, 120.seconds)
      // MISSING id first (no earlier unknown-tab line exists, so this wait is
      // race-free) — the same typed unknown-tab contract.
      s.send(":scrollback\r")
      val missing = s.waitFor("error: unknown tab", 20.seconds)
      val missingExact = hasExactLine(s.cleaned, "error: unknown tab")
      // Unknown tab with NO tabs open — typed, no phantom tabs.
      s.send(":scrollback ghost\r")
      val ghost1 = s.waitFor("error: unknown tab ghost", 20.seconds)
      // The shell stays alive: a tab opens and the command works again.
      s.send(":new alpha\r")
      val created = s.waitFor("tab created: alpha", 30.seconds)
      val drained = s.waitQuiet(1000.millis, 60.seconds)
      s.send(":scrollback alpha\r")
      val rendered = s.waitFor(
        "scrollback [alpha]: last 220 of 220 lines (0 evicted, cap 500)",
        30.seconds
      )
      // Error AGAIN, then success AGAIN — repeat-after-error works.
      s.send(":scrollback ghost\r")
      val ghost2 = s.waitFor("error: unknown tab ghost", 20.seconds)
      s.send(":scrollback 1\r")
      val renderedAgain = s.waitFor(
        "scrollback [alpha]: last 220 of 220 lines (0 evicted, cap 500)",
        30.seconds
      )
      s.send(":quit\r")
      val bye = s.waitFor("bye", 30.seconds)
      val code = s.finishWait(60.seconds)
      val text = s.cleaned
      val headerCount = countSubstr(
        text,
        "scrollback [alpha]: last 220 of 220 lines (0 evicted, cap 500)"
      )
      val ghostCount = countSubstr(text, "error: unknown tab ghost")
      val window = payloadTails(text) // 2 renders × 220 window lines
      s.persist(
        text,
        summaryJson(
          "p2sr02",
          "promptUp" -> boolJson(promptUp),
          "ghost1" -> boolJson(ghost1),
          "missing" -> boolJson(missing),
          "missingExact" -> boolJson(missingExact),
          "created" -> boolJson(created),
          "drained" -> boolJson(drained),
          "rendered" -> boolJson(rendered),
          "ghost2" -> boolJson(ghost2),
          "renderedAgain" -> boolJson(renderedAgain),
          "headerCount" -> headerCount.toString,
          "ghostCount" -> ghostCount.toString,
          "windowRows" -> window.toString,
          "noCrash" -> boolJson(!crashed(text)),
          "bye" -> boolJson(bye),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(ghost1 && missing && missingExact) &&
      assertTrue(created && drained) &&
      assertTrue(rendered && ghost2 && renderedAgain) &&
      assertTrue(headerCount == 2 && ghostCount == 2) &&
      assertTrue(window == 440) &&
      assertTrue(!crashed(text)) &&
      assertTrue(bye && code == 0)
    }

  // ----- P2SR-03: render during streaming — one writer; legacy regression ----

  private def p2sr03: ZIO[Any, Throwable, TestResult] =
    drive("p2sr03-tabs", tabs = true) { s =>
      val promptUp = s.waitFor(Prompt, 120.seconds)
      // Creating the tab starts its fixture stream (220 events @15ms ≈ 3.3s);
      // the render is issued immediately — genuinely DURING the streaming.
      s.send(":new busy\r")
      val created = s.waitFor("tab created: busy", 30.seconds)
      s.send(":scrollback busy\r")
      val headerShown = s.waitFor("scrollback [busy]: last ", 20.seconds)
      val text1 = s.cleaned
      // The rendered header carries the honest counter identity (a consistent
      // mid-burst snapshot: evicted + window == total).
      val midHeader =
        raw"scrollback \[busy\]: last (\d+) of (\d+) lines \((\d+) evicted, cap 500\)".r
      val identity = midHeader
        .findFirstMatchIn(text1)
        .exists { m =>
          m.group(3).toLong + m.group(1).toLong == m.group(2).toLong
        }
      // The prompt stays editable while/after the render.
      s.send("строка1\r")
      val draftAlive = s.waitFor("draft [busy]: строка1", 25.seconds)
      val drained = s.waitQuiet(1500.millis, 60.seconds)
      s.send(":quit\r")
      val bye = s.waitFor("bye", 30.seconds)
      val code = s.finishWait(60.seconds)
      val text = s.cleaned
      val (_, bad) = barScan(text)
      s.persist(
        text,
        summaryJson(
          "p2sr03-tabs",
          "promptUp" -> boolJson(promptUp),
          "created" -> boolJson(created),
          "headerShown" -> boolJson(headerShown),
          "counterIdentity" -> boolJson(identity),
          "draftAlive" -> boolJson(draftAlive),
          "drained" -> boolJson(drained),
          "barRowsMalformed" -> bad.toString,
          "noCrash" -> boolJson(!crashed(text)),
          "bye" -> boolJson(bye),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(created && headerShown && identity) &&
      assertTrue(draftAlive && drained) &&
      assertTrue(bad == 0) &&
      assertTrue(!crashed(text)) &&
      assertTrue(bye && code == 0)
    } *> drive("p2sr03-legacy", tabs = false) { s =>
      // Regression-PTY: WITHOUT --tabs the legacy loop is unchanged —
      // :scrollback is NOT a legacy command (frozen unknown-command answer),
      // no tabs machinery anywhere.
      val promptUp = s.waitFor(Prompt, 120.seconds)
      val bannerCmds =
        s.waitFor(
          "  cmds   : :help :jobs :cancel <id> :reset :quit",
          15.seconds
        )
      val noTabsBanner = !s.waitFor("  mode   : tabs", 3.seconds)
      s.send("val x = 1\r")
      val echoVal = s.waitFor("val x: Int = 1", 60.seconds)
      s.send(":scrollback\r")
      val unknownCmd =
        s.waitFor("unknown command :scrollback — try :help", 30.seconds)
      s.send(":quit\r")
      val bye = s.waitFor("bye", 20.seconds)
      val code = s.finishWait(60.seconds)
      val text = s.cleaned
      val noTabsMachinery =
        !text.contains("tab created:") && !text.contains("  mode   : tabs") &&
          !text.contains("scrollback [")
      s.persist(
        text,
        summaryJson(
          "p2sr03-legacy",
          "promptUp" -> boolJson(promptUp),
          "bannerCmds" -> boolJson(bannerCmds),
          "noTabsBanner" -> boolJson(noTabsBanner),
          "echoVal" -> boolJson(echoVal),
          "unknownCmd" -> boolJson(unknownCmd),
          "noTabsMachinery" -> boolJson(noTabsMachinery),
          "bye" -> boolJson(bye),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp && bannerCmds && noTabsBanner) &&
      assertTrue(echoVal && unknownCmd) &&
      assertTrue(noTabsMachinery) &&
      assertTrue(bye && code == 0)
    }

  def spec =
    suite("MainScrollbackPtySpec (RAI-113: :scrollback on the product entry)")(
      test(
        "P2SR-01: 10k burst — exact evicted header, byte-exact window, prompt alive (PTY)"
      )(p2sr01),
      test(
        "P2SR-02: ghost/missing — typed UnknownTab, no crash, repeat works (PTY)"
      )(p2sr02),
      test(
        "P2SR-03: render during streaming — one writer, editable prompt; legacy regression (PTY)"
      )(p2sr03)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(900.seconds)

end MainScrollbackPtySpec
