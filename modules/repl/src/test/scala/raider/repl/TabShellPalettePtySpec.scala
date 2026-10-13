package raider.repl

import zio._
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

/** RAI-106 / P2P — PTY acceptance of the palette/help surface on the PRODUCT
  * TabShell host. Separate fixture file by lane contract: the frozen
  * TabShellHostPtyApp/TabShellHostPtySpec (RAI-102) are consumed unmodified —
  * the child process IS the same product harness (TabShellHostPtyApp hosting
  * TabShellHost.run), which now carries the RAI-106 palette terminal by
  * default. Driver mechanics mirror the RAI-102 reference: /usr/bin/script
  * openpty relay, capability-query answering, ANSI-cleaning, registry report as
  * oracle (see TabShellHostPtySpec for the full rationale).
  *
  * Acceptance (card RAI-106):
  *   - P2P-01: the palette finds and executes a command in ≤2 presses (prefix +
  *     TAB + Enter) and `:help` renders every ACTUALLY registered command
  *     (freshness cross-checked against the probed CommandRouter catalog).
  *   - P2P-02: an unknown command is a typed error WITH the nearest-command
  *     hint; a far-out unknown keeps the plain hint; the shell stays alive.
  *   - P2P-03: palette interactions DURING active hub streaming — the input
  *     line survives byte-for-byte (report oracle), tab-bar renders are untorn
  *     (one writer), the hub drops nothing; exit 0.
  *
  * Limitations (same class as the RAI-102 reference): line atomicity is judged
  * on the final cleaned transcript; capability queries are answered per read
  * chunk.
  */
object TabShellPalettePtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "
  private val EventsPerTab = TabShellHostPtyApp.EventsPerTab
  private val Payload = TabShellHostPtyApp.Payload

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-palette\u001b\\")
  )

  // ----- report oracle (same schema as the RAI-102 harness) -----

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

  private def barScan(cleaned: String): (Int, Int) =
    val lines = cleaned.split("[\r\n]").map(_.stripTrailing).filter(_.nonEmpty)
    val rows = lines.count(l => RowFull.matches(l))
    val bad =
      lines.count(l => RowLike.findFirstIn(l).isDefined && !RowFull.matches(l))
    (rows, bad)

  // ----- the PTY session (script-run harness JVM; child = RAI-102 app) -----

  private final class PtySession(
      name: String,
      report: Path,
      evidenceDir: Path,
      delayMs: Int
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai106-pty-$name-home")

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
    // The frozen harness reads RAI102_PTY_DELAY_MS for its emitter pace.
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

    /** Wait for exit WITHOUT killing: None = still alive after the timeout. */
    def tryWait(timeout: Duration): Option[Int] =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while proc.isAlive && JSystem.nanoTime() < deadline do Thread.sleep(150)
      if proc.isAlive then None else Some(proc.exitValue())

    /** `:quit` with a retry: after a completion listing JLine4 may lag the
      * accept one keystroke behind, so the first `:quit` can be consumed by the
      * pending accept; a second one lands on the fresh prompt.
      */
    def quit(): Int =
      send(":quit\r")
      tryWait(8.seconds) match
        case Some(code) => code
        case None =>
          send(":quit\r")
          tryWait(8.seconds).getOrElse(finishWait(10.seconds))

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

  private def drive[A](name: String, delayMs: Int)(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI106_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai106-pty-$name-ev")
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

  private def crashed(text: String): Boolean =
    text.contains("Exception") || text.contains("at raider.")

  // ----- P2P-01: palette finds + executes ≤2 presses; help is fresh -----

  private def p2p01: ZIO[Any, Throwable, TestResult] =
    drive("p2p01", TabShellHostPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      // Unique prefix + TAB + Enter = a command found and run in 2 presses.
      s.send(":pl\t\r")
      val pluginsRun = s.waitFor("(no plugins)", 20.seconds)
      s.send(":he\t\r")
      val helpRun = s.waitFor("commands:", 20.seconds)
      s.send(":st\t\r")
      val stateFound =
        s.waitFor("hint: missing argument for :state", 20.seconds)
      // Direct :help: the runtime freshness surface — every help line of the
      // renderer must be on screen (help cannot rot relative to the catalog).
      s.send(":help\r")
      val helpBody = s.waitFor(":quit               exit the shell", 20.seconds)
      // The full palette listing: ':' + TAB TAB lists the whole catalog.
      s.send(":\t\t")
      val paletteListed = s.waitFor(":rename", 15.seconds)
      val paletteState = s.waitFor(":state", 15.seconds)
      // Leave the listing through typed input only: the completer leaves a
      // completed candidate in the buffer, and its accept may lag one
      // keystroke behind the listing, so Enter + retried `:quit` and the
      // FINAL transcript are the oracle for a typed, alive resolution.
      s.send("\r")
      s.waitFor("no auxiliary tab", 10.seconds)
      val code = s.quit()
      val text = s.cleaned
      val helpLinesOnScreen =
        raider.ui.TabBarRenderer.helpLines.forall(l => text.contains(l.trim))
      val listingAccepted =
        text.contains("hint: no auxiliary tab is registered") ||
          text.contains("hint: unknown command") ||
          text.contains("no open tabs (start one with :new <label>)")
      s.persist(
        text,
        summaryJson(
          "p2p01",
          "promptUp" -> boolJson(promptUp),
          "pluginsRun" -> boolJson(pluginsRun),
          "helpRun" -> boolJson(helpRun),
          "stateFound" -> boolJson(stateFound),
          "helpBody" -> boolJson(helpBody),
          "helpLinesOnScreen" -> boolJson(helpLinesOnScreen),
          "paletteListed" -> boolJson(paletteListed),
          "paletteState" -> boolJson(paletteState),
          "listingAccepted" -> boolJson(listingAccepted),
          "noCrash" -> boolJson(!crashed(text)),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(pluginsRun) &&
      assertTrue(helpRun && stateFound) &&
      assertTrue(helpBody && helpLinesOnScreen) &&
      assertTrue(paletteListed && paletteState && listingAccepted) &&
      assertTrue(!crashed(text)) &&
      assertTrue(code == 0)
    }

  // ----- P2P-02: unknown command — typed error + nearest hint, shell alive -

  private def p2p02: ZIO[Any, Throwable, TestResult] =
    drive("p2p02", TabShellHostPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":hlp\r")
      val unknown = s.waitFor("hint: unknown command: :hlp", 20.seconds)
      val nearest = s.waitFor("  did you mean: :help?", 20.seconds)
      s.send(":zzzz\r")
      val farOut = s.waitFor("hint: unknown command: :zzzz", 20.seconds)
      // Unknown command must NOT have produced a second suggestion.
      s.send(":tabs\r")
      val alive =
        s.waitFor("no open tabs (start one with :new <label>)", 20.seconds)
      // Prefix completion of a near-miss token still finds the command.
      s.send(":plug\t\r")
      val prefixFind = s.waitFor("(no plugins)", 20.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val suggestions = countSubstr(text, "did you mean")
      s.persist(
        text,
        summaryJson(
          "p2p02",
          "promptUp" -> boolJson(promptUp),
          "unknown" -> boolJson(unknown),
          "nearest" -> boolJson(nearest),
          "farOut" -> boolJson(farOut),
          "suggestions" -> suggestions.toString,
          "alive" -> boolJson(alive),
          "prefixFind" -> boolJson(prefixFind),
          "noCrash" -> boolJson(!crashed(text)),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(unknown && nearest) &&
      assertTrue(farOut && suggestions == 1) &&
      assertTrue(alive) &&
      assertTrue(prefixFind) &&
      assertTrue(!crashed(text)) &&
      assertTrue(code == 0)
    }

  // ----- P2P-03: palette during active streaming — one writer, intact input

  private def p2p03: ZIO[Any, Throwable, TestResult] =
    drive("p2p03", 6) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new busy\r")
      val created = s.waitFor("tab created: busy", 20.seconds)
      // The feeder streams ~1.3s of hub events from the next tick; these
      // palette interactions are queued into the PTY immediately, so their
      // rendering and the drafts happen UNDER the live stream.
      s.send(":he\t")
      s.send("\r")
      s.send("cafélog\r")
      s.send(":pl\t")
      s.send("\r")
      s.send(" queue\r")
      val helpShown = s.waitFor("commands:", 30.seconds)
      val draft1 = s.waitFor("draft [busy]: cafélog", 30.seconds)
      val pluginsShown = s.waitFor("(no plugins)", 30.seconds)
      val draft2 = s.waitFor("draft [busy]: cafélog queue", 30.seconds)
      val rep = s.waitForReport(
        settled(List("busy"), Map("busy" -> "cafélog queue")),
        90.seconds
      )
      // The prompt stays editable after the storm.
      s.send(" after-echo\r")
      val draft3 =
        s.waitFor("draft [busy]: cafélog queue after-echo", 25.seconds)
      val settledFinal = s.waitForReport(
        settled(List("busy"), Map("busy" -> "cafélog queue after-echo")),
        30.seconds
      )
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      s.persist(
        text,
        summaryJson(
          "p2p03",
          "promptUp" -> boolJson(promptUp),
          "created" -> boolJson(created),
          "helpShown" -> boolJson(helpShown),
          "draft1" -> boolJson(draft1),
          "pluginsShown" -> boolJson(pluginsShown),
          "draft2" -> boolJson(draft2),
          "reportSettled" -> boolJson(rep.isDefined),
          "draft3" -> boolJson(draft3),
          "settledFinal" -> boolJson(settledFinal.isDefined),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "noCrash" -> boolJson(!crashed(text)),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp && created) &&
      assertTrue(helpShown && pluginsShown) &&
      assertTrue(draft1 && draft2 && draft3) &&
      assertTrue(rep.isDefined && settledFinal.isDefined) &&
      assertTrue(rows >= 200) &&
      assertTrue(bad == 0) &&
      assertTrue(!crashed(text)) &&
      assertTrue(code == 0)
    }

  def spec =
    suite("TabShellPalettePtySpec (RAI-106: palette/help on the product host)")(
      test("P2P-01: palette finds and executes ≤2 presses; help fresh (PTY)")(
        p2p01
      ),
      test("P2P-02: unknown command — typed error + nearest hint (PTY)")(
        p2p02
      ),
      test("P2P-03: palette during streaming — input intact, one writer (PTY)")(
        p2p03
      )
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(600.seconds)

end TabShellPalettePtySpec
