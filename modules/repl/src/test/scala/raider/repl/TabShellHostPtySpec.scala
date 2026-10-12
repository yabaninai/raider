package raider.repl

import zio._
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

/** RAI-102 / P2W — PTY acceptance of the PRODUCT TabShell host
  * (raider.repl.TabShellHost) in a REAL pseudo-terminal, modeled on the
  * scripts/quality/tabs_pty_probe.py approach (RAI-062; that script is frozen
  * and untouched): TERM/NO_COLOR are driver-owned, capability queries are
  * answered, HOME is isolated, transcripts are ANSI-cleaned, and the harness
  * report (registry read model) is the oracle.
  *
  * The child process is the test-scope harness raider.repl.TabShellHostPtyApp,
  * which hosts exactly the PRODUCT wiring (TabShellHost.wiring +
  * TabShellHost.run: ShellTerminal.jline + EventHub + SessionRegistry +
  * raider.ui.TabShell). It runs under `/usr/bin/script`: the BSD utility
  * performs openpty + setsid + dup2(slave onto 0/1/2) + exec itself, so the
  * harness JVM sees a real PTY slave on its standard streams and this driver
  * relays the master through plain pipes — no new dependencies, no native glue,
  * no mocked shell.
  *
  * Acceptance (card RAI-102):
  *   - P2W-01: several tabs; switching A→B→A preserves the input line
  *     byte-for-byte (registry drafts, SHELL-01 semantics) under live hub
  *     streaming; exit 0.
  *   - P2W-02: unknown session/command — typed error with hints (SHELL-02);
  *     shell and run stay alive; exit 0.
  *   - P2W-03: hub event burst while actively typing — draft undamaged, ONE
  *     writer (tab-bar renders atomic: zero torn rows), prompt stays editable;
  *     scrollback lossless (hub drops == 0); exit 0.
  *
  * Limitations (stated honestly, same class as the RAI-062 probe): line
  * atomicity is judged on the final cleaned transcript (a tear later erased by
  * JLine is not individually detectable); capability queries are answered per
  * read chunk (a query split across two chunks would go unanswered and only
  * cost a JLine fallback delay); Ctrl+C/resize are out of P2W scope (covered
  * for the same shell by RAI-062 PTY-02).
  */
object TabShellHostPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "
  private val EventsPerTab = TabShellHostPtyApp.EventsPerTab
  private val Payload = TabShellHostPtyApp.Payload

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  /** A full tab-bar row (RAI-097 statusText is free text inside brackets). */
  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  /** Capability queries JLine may emit, with canned answers (probe parity):
    * Primary/Secondary DA, mode-2027 query, kitty keyboard query, XTVERSION.
    */
  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-tabs\u001b\\")
  )

  // ----- report oracle (written by the harness, decoded here) -----

  final case class PtyTabReport(
      label: String,
      status: String,
      lastSeq: Long,
      unread: Long,
      scrollbackLines: Int,
      scrollbackEvicted: Long,
      scrollback: List[String],
      draft: String,
      feedDone: Boolean
  )

  object PtyTabReport:
    given JsonDecoder[PtyTabReport] = DeriveJsonDecoder.gen

  final case class PtyReport(
      allFeedsDone: Boolean,
      hubDroppedDiagnostics: Long,
      tabs: List[PtyTabReport]
  )

  object PtyReport:
    given JsonDecoder[PtyReport] = DeriveJsonDecoder.gen

  private def expectedScrollback(label: String): List[String] =
    (1 to EventsPerTab).map(i => s"ev $label $i payload $Payload").toList

  /** Feeds settled: every tab fed exactly EventsPerTab+1 envelopes, tab still
    * Running, scrollback byte-exact and lossless, hub dropped nothing, and
    * every draft matches the expected bytes.
    */
  private def settled(
      labels: List[String],
      drafts: Map[String, String]
  ): PtyReport => Boolean = rep =>
    rep.allFeedsDone &&
      rep.tabs.map(_.label) == labels &&
      rep.hubDroppedDiagnostics == 0L &&
      rep.tabs.forall { t =>
        t.feedDone &&
        t.status == "Running" &&
        t.lastSeq == (1 + EventsPerTab).toLong &&
        t.scrollbackEvicted == 0L &&
        t.scrollbackLines == EventsPerTab &&
        t.scrollback == expectedScrollback(t.label) &&
        drafts.get(t.label).contains(t.draft)
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

  /** Terminal lines end with CR or LF (JLine's printAbove redraws erase the
    * prompt line with a bare CR before writing above it, so the flattened
    * stream reads "prompt CR row CR row LF prompt"); splitting on both gives
    * the rows exactly as they were rendered.
    */
  private def barScan(cleaned: String): (Int, Int) =
    // stripTrailing only: the LEADING space of a non-focused bar row is part
    // of the row (RAI-061 renderer); trimming it would count the row torn.
    val lines = cleaned.split("[\r\n]").map(_.stripTrailing).filter(_.nonEmpty)
    val rows = lines.count(l => RowFull.matches(l))
    val bad =
      lines.count(l => RowLike.findFirstIn(l).isDefined && !RowFull.matches(l))
    (rows, bad)

  // ----- the PTY session (script-run harness JVM) -----

  private final class PtySession(
      name: String,
      report: Path,
      evidenceDir: Path,
      delayMs: Int
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai102-pty-$name-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.TabShellHostPtyApp",
      report.toString
    )

    pb.redirectErrorStream(true)
    pb.directory(Files.createDirectories(home.resolve("cwd")).toFile)
    pb.environment().put("TERM", "xterm-256color")
    pb.environment().put("NO_COLOR", "1")
    pb.environment().put("HOME", home.toString)
    pb.environment().put("RAI102_PTY_DELAY_MS", delayMs.toString)

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

    /** Answer JLine capability queries with canned responses (probe parity). */
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

    def waitForReport(
        pred: PtyReport => Boolean,
        timeout: Duration
    ): Option[PtyReport] =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while JSystem.nanoTime() < deadline do
        readReport() match
          case Some(r) if pred(r) => return Some(r)
          case _                  => Thread.sleep(200)
      readReport().filter(pred)

    private def readReport(): Option[PtyReport] =
      try
        if !Files.exists(report) then None
        else
          Files
            .readString(report, StandardCharsets.UTF_8)
            .fromJson[PtyReport]
            .toOption
      catch case _: Exception => None

    /** Wait for exit; -9 means killed after the timeout (honest failure). */
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

    /** Persist evidence (transcript + final report) into the evidence dir. */
    def persist(cleanedText: String, summary: String): Unit =
      Files.createDirectories(evidenceDir)
      Files.writeString(
        evidenceDir.resolve(s"transcript-$name.txt"),
        cleanedText,
        StandardCharsets.UTF_8
      )
      if Files.exists(report) then
        Files.copy(
          report,
          evidenceDir.resolve(s"report-$name.final.json"),
          StandardCopyOption.REPLACE_EXISTING
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
  private def drive[A](name: String, delayMs: Int)(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI102_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai102-pty-$name-ev")
      val report = evidence.resolve(s"report-$name.json")
      val session = PtySession(name, report, evidence, delayMs)
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

  // ----- P2W-01: A→B→A preserves the input line byte-for-byte -----

  private def p2w01: ZIO[Any, Throwable, TestResult] =
    drive("p2w01", TabShellHostPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new alpha\r")
      val createdA = s.waitFor("tab created: alpha", 20.seconds)
      s.send(":new beta\r")
      val createdB = s.waitFor("tab created: beta", 20.seconds)
      s.send(":switch alpha\r")
      val focusedA = s.waitFor("focused: alpha", 20.seconds)
      // The A→B→A tour while the feeder streams through the real hub.
      s.send("привет😀\r")
      val draftA1 = s.waitFor("draft [alpha]: привет😀", 25.seconds)
      s.send(":next\r")
      val focusedB = s.waitFor("focused: beta", 20.seconds)
      s.send("мир漢\r")
      val draftB = s.waitFor("draft [beta]: мир漢", 25.seconds)
      s.send(":prev\r")
      val focusedBack = s.waitFor("focused: alpha", 20.seconds)
      s.send(" хвост\r")
      val draftA2 = s.waitFor("draft [alpha]: привет😀 хвост", 25.seconds)
      val rep = s.waitForReport(
        settled(
          List("alpha", "beta"),
          Map("alpha" -> "привет😀 хвост", "beta" -> "мир漢")
        ),
        90.seconds
      )
      s.send(":tabs\r")
      val promptLive = s.waitFor("*1. alpha [", 15.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      s.persist(
        text,
        summaryJson(
          "p2w01",
          "promptUp" -> boolJson(promptUp),
          "createdA" -> boolJson(createdA),
          "createdB" -> boolJson(createdB),
          "focusedA" -> boolJson(focusedA),
          "draftA1" -> boolJson(draftA1),
          "focusedB" -> boolJson(focusedB),
          "draftB" -> boolJson(draftB),
          "focusedBack" -> boolJson(focusedBack),
          "draftA2" -> boolJson(draftA2),
          "reportSettled" -> boolJson(rep.isDefined),
          "promptLive" -> boolJson(promptLive),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "exit" -> code.toString
        )
      )
      val echoA = countSubstr(text, "draft [alpha]: привет😀 хвост")
      val echoB = countSubstr(text, "draft [beta]: мир漢")
      assertTrue(promptUp) &&
      assertTrue(createdA) &&
      assertTrue(createdB) &&
      assertTrue(focusedA && focusedB && focusedBack) &&
      assertTrue(draftA1 && draftB && draftA2) &&
      assertTrue(echoA == 1 && echoB == 1) &&
      assertTrue(rep.isDefined) &&
      assertTrue(promptLive) &&
      assertTrue(rows >= 300) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  // ----- P2W-02: unknown session/command — typed error; shell alive -----

  private def p2w02: ZIO[Any, Throwable, TestResult] =
    drive("p2w02", TabShellHostPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new only\r")
      val created = s.waitFor("tab created: only", 20.seconds)
      // unknown session
      s.send(":switch ghost\r")
      val unknownTab = s.waitFor("hint: unknown tab: ghost", 20.seconds)
      val hintListsTabs = s.waitFor("open tabs: only", 20.seconds)
      // unknown command
      s.send(":bogus\r")
      val unknownCmd = s.waitFor("hint: unknown command: :bogus", 20.seconds)
      val hintPalette =
        s.waitFor("available commands: :tabs :new", 20.seconds)
      // bad tab number (third typed class, cheap)
      s.send(":switch 7\r")
      val badIndex =
        s.waitFor("bad tab number: 7 (open tabs: 1)", 20.seconds)
      // shell and run still alive
      s.send(":tabs\r")
      val tabsRender = s.waitFor("*1. only [", 15.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val crashed =
        text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2w02",
          "promptUp" -> boolJson(promptUp),
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
      assertTrue(created) &&
      assertTrue(unknownTab && hintListsTabs) &&
      assertTrue(unknownCmd && hintPalette) &&
      assertTrue(badIndex) &&
      assertTrue(tabsRender) &&
      assertTrue(!crashed) &&
      assertTrue(code == 0)
    }

  // ----- P2W-03: burst while typing — one writer, draft intact -----

  private def p2w03: ZIO[Any, Throwable, TestResult] =
    drive("p2w03", 6) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new busy\r")
      val createdBusy = s.waitFor("tab created: busy", 20.seconds)
      s.send(":new quiet\r")
      val createdQuiet = s.waitFor("tab created: quiet", 20.seconds)
      s.send(":switch quiet\r")
      val focusedQuiet = s.waitFor("focused: quiet", 20.seconds)
      // Active input DURING the burst: the feeder starts streaming on the
      // first :new; these bytes are typed while events flood the terminal
      // (queued by the PTY, processed by the one JLine loop).
      s.send("строка1\r")
      s.send(":next\r")
      val focusedBusy = s.waitFor("focused: busy", 20.seconds)
      s.send("бурст😀\r")
      val draftBusy = s.waitFor("draft [busy]: бурст😀", 25.seconds)
      val draftQuiet1 = s.waitFor("draft [quiet]: строка1", 25.seconds)
      // Burst over: feeds settled with the intermediate drafts byte-exact.
      val settledDuring =
        s.waitForReport(
          settled(
            List("busy", "quiet"),
            Map("busy" -> "бурст😀", "quiet" -> "строка1")
          ),
          90.seconds
        )
      // The prompt stays editable AFTER the storm: keep appending.
      s.send(":switch quiet\r")
      val backQuiet = s.waitFor("focused: quiet", 20.seconds)
      s.send(" хвост2\r")
      val quietFull =
        s.waitFor("draft [quiet]: строка1 хвост2", 25.seconds)
      s.send(" после-бури\r")
      val quietAfter =
        s.waitFor("draft [quiet]: строка1 хвост2 после-бури", 25.seconds)
      val settledFinal = s.waitForReport(
        settled(
          List("busy", "quiet"),
          Map("busy" -> "бурст😀", "quiet" -> "строка1 хвост2 после-бури")
        ),
        30.seconds
      )
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      s.persist(
        text,
        summaryJson(
          "p2w03",
          "promptUp" -> boolJson(promptUp),
          "createdBusy" -> boolJson(createdBusy),
          "createdQuiet" -> boolJson(createdQuiet),
          "focusedQuiet" -> boolJson(focusedQuiet),
          "focusedBusy" -> boolJson(focusedBusy),
          "draftBusy" -> boolJson(draftBusy),
          "draftQuiet1" -> boolJson(draftQuiet1),
          "settledDuring" -> boolJson(settledDuring.isDefined),
          "backQuiet" -> boolJson(backQuiet),
          "quietFull" -> boolJson(quietFull),
          "quietAfter" -> boolJson(quietAfter),
          "settledFinal" -> boolJson(settledFinal.isDefined),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(createdBusy && createdQuiet) &&
      assertTrue(focusedQuiet && focusedBusy && backQuiet) &&
      assertTrue(draftBusy && draftQuiet1) &&
      assertTrue(settledDuring.isDefined) &&
      assertTrue(quietFull && quietAfter) &&
      assertTrue(settledFinal.isDefined) &&
      assertTrue(rows >= 300) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  def spec =
    suite("TabShellHostPtySpec (RAI-102: product TabShell host under PTY)")(
      test("P2W-01: A→B→A keeps the input line byte-for-byte (PTY)")(p2w01),
      test("P2W-02: unknown session/command — typed error, run alive (PTY)")(
        p2w02
      ),
      test("P2W-03: burst while typing — one writer, prompt editable (PTY)")(
        p2w03
      )
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(600.seconds)

end TabShellHostPtySpec
