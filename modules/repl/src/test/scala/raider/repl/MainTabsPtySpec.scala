package raider.repl

import zio._
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** RAI-108 — PTY acceptance of the PRODUCT REPL ENTRY (raider.repl.Main) with
  * the explicit --tabs flag, modeled on the accepted TabShellHostPtySpec driver
  * (RAI-102): TERM/NO_COLOR are driver-owned, capability queries are answered,
  * HOME is isolated, transcripts are ANSI-cleaned. The decisive difference to
  * RAI-102: the child process is the REAL product entry —
  * `java -cp <test-classpath> raider.repl.Main --tabs` — NOT a test-scope
  * harness app. It runs under `/usr/bin/script` (openpty + setsid + dup2 of the
  * slave onto 0/1/2 + exec); the launch wrapper sets the pty winsize via stty
  * on the slave BEFORE exec'ing the untouched product Main (the RAI-102 harness
  * did the same inside the harness app; product code stays unmodified).
  * RAIDER_PROVIDER=mock is pinned in the child env — the scripted/mock backend,
  * zero network, zero paid calls.
  *
  * Oracles are the cleaned transcript + exit code (the product entry writes no
  * test reports): draft byte-exactness through the shell's own DraftEchoed
  * render (`draft [<label>]: <bytes>`), the burst's real delivery through the
  * bar renders (the pump re-renders per delivered hub envelope, so row counts
  * prove events actually streamed), atomic rows (0 torn) for the ONE-writer
  * invariant, and quiescence for drain.
  *
  * Acceptance (card RAI-108):
  *   - P2E-01: `Main --tabs` (product entry) lifts the shell; A→B→A keeps the
  *     draft byte-for-byte; honest mode banner (tabs mode + scripted stream +
  *     backend line); exit 0.
  *   - P2E-02: unknown session/command — typed errors (`error: no tabs are
  *     open`, `hint: unknown tab`, `hint: unknown command` with the palette
  *     hint, `bad tab number`); shell stays alive; exit 0.
  *   - P2E-03: event burst while actively typing on the product entry — ONE
  *     writer (0 torn bar rows), prompt stays editable; and WITHOUT --tabs the
  *     legacy loop behaves exactly like the frozen repl_smoke oracle (same
  *     banner, same eval/job anchors, no tabs machinery), regression-PTY.
  *
  * Limitations (same class as the accepted RAI-102 spec): line atomicity is
  * judged on the final cleaned transcript; capability queries are answered per
  * read chunk; Ctrl+C/resize are out of scope (covered for the same shell by
  * RAI-062 PTY-02).
  */
object MainTabsPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  /** A full tab-bar row (RAI-097 statusText is free text inside brackets). */
  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  /** Capability queries JLine may emit, with canned answers (accepted-spec
    * parity): Primary/Secondary DA, mode-2027 query, kitty keyboard query,
    * XTVERSION.
    */
  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-tabs\u001b\\")
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

  /** Terminal lines end with CR or LF (JLine printAbove redraws erase the
    * prompt line with a bare CR first); splitting on both gives the rendered
    * rows. Leading space of a non-focused row is part of the row.
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
      evidenceDir: Path
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai108-pty-$name-home")

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
      * honest drain signal before :quit (coordinator determinism fix, RAI-105
      * contract-change, same rationale as RAI-102).
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
  private def drive[A](name: String, tabs: Boolean)(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI108_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai108-pty-$name-ev")
      val session = PtySession(name, tabs, evidence)
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

  /** Shared tail of the tabs sessions: bar integrity, quiescent drain, the
    * honest `bye` shutdown, no crash — inputs gathered for the caller.
    */
  private def finishTabs(
      s: PtySession,
      name: String,
      extra: (String, String)*
  ): (String, Int, Int, Int, Boolean) =
    s.send(":tabs\r")
    val promptLive = s.waitFor("*1. ", 15.seconds)
    val drained = s.waitQuiet(1500.millis, 45.seconds)
    s.send(":quit\r")
    val bye = s.waitFor("bye", 20.seconds)
    val code = s.finishWait(60.seconds)
    val text = s.cleaned
    val (rows, bad) = barScan(text)
    val crashed = text.contains("Exception") || text.contains("at raider.")
    s.persist(
      text,
      summaryJson(
        name,
        (extra ++ Vector(
          "promptLive" -> boolJson(promptLive),
          "drained" -> boolJson(drained),
          "bye" -> boolJson(bye),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "noCrash" -> boolJson(!crashed),
          "exit" -> code.toString
        ))*
      )
    )
    (text, code, rows, bad, bye)

  // ----- P2E-01: product entry --tabs; A→B→A keeps the draft byte-for-byte

  private def p2e01: ZIO[Any, Throwable, TestResult] =
    drive("p2e01", tabs = true) { s =>
      val promptUp = s.waitFor(Prompt, 120.seconds)
      // Honest banner: mode, stream source, backend line.
      val bannerMode =
        s.waitFor("  mode   : tabs — product TabShell", 15.seconds)
      val bannerStream =
        s.waitFor(
          "  stream : scripted fixture feed per tab (mock provider; no network)",
          15.seconds
        )
      val bannerBackend = s.waitFor("backend: fixture/mock", 15.seconds)
      val bannerNote =
        s.waitFor(
          "  note   : Scala eval loop runs WITHOUT --tabs (legacy mode)",
          15.seconds
        )
      s.send(":new alpha\r")
      val createdA = s.waitFor("tab created: alpha", 20.seconds)
      s.send(":new beta\r")
      val createdB = s.waitFor("tab created: beta", 20.seconds)
      s.send(":switch alpha\r")
      val focusedA = s.waitFor("focused: alpha", 20.seconds)
      // The A→B→A tour while the fixture stream runs through the REAL hub.
      s.send("résumé😀\r")
      val draftA1 = s.waitFor("draft [alpha]: résumé😀", 25.seconds)
      s.send(":next\r")
      val focusedB = s.waitFor("focused: beta", 20.seconds)
      s.send("tea漢\r")
      val draftB = s.waitFor("draft [beta]: tea漢", 25.seconds)
      s.send(":prev\r")
      val focusedBack = s.waitFor("focused: alpha", 20.seconds)
      s.send(" tail\r")
      val draftA2 = s.waitFor("draft [alpha]: résumé😀 tail", 25.seconds)
      val (text, code, rows, bad, bye) = finishTabs(s, "p2e01")
      val echoA = countSubstr(text, "draft [alpha]: résumé😀 tail")
      val echoB = countSubstr(text, "draft [beta]: tea漢")
      assertTrue(promptUp) &&
      assertTrue(bannerMode && bannerStream && bannerBackend && bannerNote) &&
      assertTrue(createdA && createdB) &&
      assertTrue(focusedA && focusedB && focusedBack) &&
      assertTrue(draftA1 && draftB && draftA2) &&
      assertTrue(echoA == 1 && echoB == 1) &&
      assertTrue(rows >= 300) &&
      assertTrue(bad == 0) &&
      assertTrue(bye) &&
      assertTrue(code == 0)
    }

  // ----- P2E-02: typed errors; shell and run alive -----

  private def p2e02: ZIO[Any, Throwable, TestResult] =
    drive("p2e02", tabs = true) { s =>
      val promptUp = s.waitFor(Prompt, 120.seconds)
      // Draft with NO tabs: typed registry failure.
      s.send("café\r")
      val noTabs = s.waitFor("error: no tabs are open", 20.seconds)
      s.send(":new only\r")
      val created = s.waitFor("tab created: only", 20.seconds)
      // unknown session
      s.send(":switch ghost\r")
      val unknownTab = s.waitFor("hint: unknown tab: ghost", 20.seconds)
      val hintListsTabs = s.waitFor("open tabs: only", 20.seconds)
      // unknown command (+ palette hint line on the real entry's terminal)
      s.send(":bogus\r")
      val unknownCmd = s.waitFor("hint: unknown command: :bogus", 20.seconds)
      val hintPalette =
        s.waitFor("available commands: :tabs :new", 20.seconds)
      // bad tab number
      s.send(":switch 7\r")
      val badIndex =
        s.waitFor("bad tab number: 7 (open tabs: 1)", 20.seconds)
      // shell and run still alive
      s.send(":tabs\r")
      val tabsRender = s.waitFor("*1. only [", 15.seconds)
      s.send(":quit\r")
      val code = s.finishWait(60.seconds)
      val text = s.cleaned
      val crashed = text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2e02",
          "promptUp" -> boolJson(promptUp),
          "noTabs" -> boolJson(noTabs),
          "created" -> boolJson(created),
          "unknownTab" -> boolJson(unknownTab),
          "hintListsTabs" -> boolJson(hintListsTabs),
          "unknownCmd" -> boolJson(unknownCmd),
          "hintPalette" -> boolJson(hintPalette),
          "badIndex" -> boolJson(badIndex),
          "tabsRender" -> boolJson(tabsRender),
          "noCrash" -> boolJson(!crashed),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(noTabs && created) &&
      assertTrue(unknownTab && hintListsTabs) &&
      assertTrue(unknownCmd && hintPalette) &&
      assertTrue(badIndex) &&
      assertTrue(tabsRender) &&
      assertTrue(!crashed) &&
      assertTrue(code == 0)
    }

  // ----- P2E-03: burst while typing on the product entry + legacy regression

  private def p2e03: ZIO[Any, Throwable, TestResult] =
    drive("p2e03-tabs", tabs = true) { s =>
      val promptUp = s.waitFor(Prompt, 120.seconds)
      // Creating tabs starts their fixture streams (220 events @15ms each);
      // the typing below genuinely overlaps the double burst.
      s.send(":new busy\r")
      val createdBusy = s.waitFor("tab created: busy", 20.seconds)
      s.send(":new quiet\r")
      val createdQuiet = s.waitFor("tab created: quiet", 20.seconds)
      s.send(":switch quiet\r")
      val focusedQuiet = s.waitFor("focused: quiet", 20.seconds)
      // Active input DURING the burst.
      s.send("cafébar\r")
      val draftQuiet1 = s.waitFor("draft [quiet]: cafébar", 25.seconds)
      s.send(":next\r")
      val focusedBusy = s.waitFor("focused: busy", 20.seconds)
      s.send("crème😀\r")
      val draftBusy = s.waitFor("draft [busy]: crème😀", 25.seconds)
      // The prompt stays editable AFTER the storm: keep typing on quiet.
      s.send(":switch quiet\r")
      val backQuiet = s.waitFor("focused: quiet", 20.seconds)
      s.send(" tail2\r")
      val quietFull =
        s.waitFor("draft [quiet]: cafébar tail2", 25.seconds)
      val (text, code, rows, bad, bye) = finishTabs(
        s,
        "p2e03-tabs",
        "promptUp" -> boolJson(promptUp),
        "createdBusy" -> boolJson(createdBusy),
        "createdQuiet" -> boolJson(createdQuiet),
        "focusedQuiet" -> boolJson(focusedQuiet),
        "draftQuiet1" -> boolJson(draftQuiet1),
        "focusedBusy" -> boolJson(focusedBusy),
        "draftBusy" -> boolJson(draftBusy),
        "backQuiet" -> boolJson(backQuiet),
        "quietFull" -> boolJson(quietFull)
      )
      val echoQuietFull =
        countSubstr(text, "draft [quiet]: cafébar tail2")
      val echoBusy = countSubstr(text, "draft [busy]: crème😀")
      assertTrue(promptUp) &&
      assertTrue(createdBusy && createdQuiet) &&
      assertTrue(focusedQuiet && focusedBusy && backQuiet) &&
      assertTrue(draftQuiet1 && draftBusy && quietFull) &&
      assertTrue(echoQuietFull == 1 && echoBusy == 1) &&
      assertTrue(rows >= 300) &&
      assertTrue(bad == 0) &&
      assertTrue(bye) &&
      assertTrue(code == 0)
    } *> drive("p2e03-legacy", tabs = false) { s =>
      // Regression-PTY: WITHOUT --tabs the legacy loop is the frozen
      // repl_smoke oracle — same banner, same eval/job anchors, and NO tabs
      // machinery anywhere in the transcript.
      val promptUp = s.waitFor(Prompt, 120.seconds)
      val bannerHead =
        s.waitFor("Raider REPL (W10 fast-path slice)", 15.seconds)
      val bannerCmds =
        s.waitFor(
          "  cmds   : :help :jobs :cancel <id> :reset :quit",
          15.seconds
        )
      val bannerBackend = s.waitFor("backend: fixture/mock", 15.seconds)
      val noTabsBanner = !s.waitFor("  mode   : tabs", 3.seconds)
      // The frozen smoke script, replayed under a real PTY.
      s.send("val x = 1\r")
      val echoVal = s.waitFor("val x: Int = 1", 60.seconds)
      s.send("x\r")
      val res0 = s.waitFor("val res0: Int = 1", 60.seconds)
      s.send("scout.ask(\"ping\")\r")
      val scripted = s.waitFor("[scripted-fixture] mock answer", 60.seconds)
      s.send("val j = worker.start(\"bg\")\r")
      val jobStarted = s.waitFor("JobHandle", 60.seconds)
      s.send("j.await()\r")
      val awaited = s.waitFor("val res2:", 60.seconds)
      s.send(":jobs\r")
      val jobs = s.waitFor("Succeeded", 30.seconds)
      s.send(":bogus\r")
      val unknownCmd =
        s.waitFor("unknown command :bogus — try :help", 30.seconds)
      s.send(":quit\r")
      val bye = s.waitFor("bye", 20.seconds)
      val code = s.finishWait(60.seconds)
      val text = s.cleaned
      val noTabsMachinery =
        !text.contains("tab created:") && !text.contains("  mode   : tabs")
      val crashed = text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2e03-legacy",
          "promptUp" -> boolJson(promptUp),
          "bannerHead" -> boolJson(bannerHead),
          "bannerCmds" -> boolJson(bannerCmds),
          "bannerBackend" -> boolJson(bannerBackend),
          "noTabsBanner" -> boolJson(noTabsBanner),
          "echoVal" -> boolJson(echoVal),
          "res0" -> boolJson(res0),
          "scripted" -> boolJson(scripted),
          "jobStarted" -> boolJson(jobStarted),
          "awaited" -> boolJson(awaited),
          "jobs" -> boolJson(jobs),
          "unknownCmd" -> boolJson(unknownCmd),
          "noTabsMachinery" -> boolJson(noTabsMachinery),
          "noCrash" -> boolJson(!crashed),
          "bye" -> boolJson(bye),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(bannerHead && bannerCmds && bannerBackend) &&
      assertTrue(noTabsBanner) &&
      assertTrue(echoVal && res0 && scripted) &&
      assertTrue(jobStarted && awaited && jobs) &&
      assertTrue(unknownCmd) &&
      assertTrue(noTabsMachinery) &&
      assertTrue(!crashed) &&
      assertTrue(bye) &&
      assertTrue(code == 0)
    }

  def spec =
    suite("MainTabsPtySpec (RAI-108: product entry --tabs under PTY)")(
      test("P2E-01: --tabs lifts the shell; A→B→A draft byte-for-byte (PTY)")(
        p2e01
      ),
      test("P2E-02: typed errors; shell and run alive (PTY)")(p2e02),
      test(
        "P2E-03: burst while typing — one writer, editable prompt; legacy no-flag regression (PTY)"
      )(p2e03)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(900.seconds)

end MainTabsPtySpec
