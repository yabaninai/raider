package raider.repl

import zio._
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

/** RAI-103 / P2D — PTY acceptance of PER-TAB DRAFTS on the PRODUCT surface
  * (raider.repl.TabShellHost), modeled on TabShellHostPtySpec (RAI-102; that
  * driver is frozen and untouched — this is a separate file reusing its
  * pattern): the child JVM runs the test-scope harness
  * raider.repl.TabShellDraftsPtyApp under `/usr/bin/script` (openpty + setsid +
  * dup2(slave onto 0/1/2) + exec), so the product shell sees a REAL PTY; the
  * driver relays the master through plain pipes, answers JLine capability
  * queries, ANSI-cleans the transcript and polls the harness report (registry
  * read model, drafts byte-exact) as the oracle. Mock-only: the streaming side
  * is the test feeder through the REAL hub — no network, no paid calls.
  *
  * Acceptance (card RAI-103):
  *   - P2D-01: A→B→A switching preserves draft A byte-for-byte under LIVE hub
  *     streaming (multibyte payloads, appended in three chunks); drafts are
  *     tab-isolated; every full draft echoed exactly once; exit 0.
  *   - P2D-02: `:close` on a tab WITH a draft offers the hide/cancel dialogue;
  *     REFUSING (three invalid answers → the safe non-destructive default)
  *     keeps the tab open, its draft byte-exact and the sibling draft
  *     untouched; streaming continued through the dialogue; prompt still
  *     editable after the refusal; exit 0.
  *   - P2D-03 (UX02): 20 streaming mock jobs at once while actively typing —
  *     drafts of the typed tabs land byte-exact and stay editable after the
  *     storm; all 20 drafts isolated and exact, hub drops == 0, tab bar rows
  *     rendered without tears; exit 0.
  *
  * Limitations (same class as the frozen RAI-102 driver, stated honestly): line
  * atomicity is judged on the final cleaned transcript (a tear later erased by
  * JLine is not individually detectable); capability queries are answered per
  * read chunk; the close-dialogue re-ask count is a lower bound (background bar
  * renders redraw the dialogue prompt line, so only the printAbove'd lines are
  * exactly countable).
  */
object TabShellDraftsPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "
  private val Events = TabShellDraftsPtyApp.DefaultEventsPerTab
  private val Payload = TabShellDraftsPtyApp.Payload

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  /** A full tab-bar row (RAI-097 statusText is free text inside brackets). */
  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  /** The close dialogue prompt (TabShell.Config().closePrompt, RAI-061). */
  private val ClosePrompt =
    "tab is running - [h]ide (job keeps running) or [c]ancel-request?"

  /** Capability queries JLine may emit, with canned answers (RAI-102 parity):
    * Primary/Secondary DA, mode-2027 query, kitty keyboard query, XTVERSION.
    */
  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-drafts\u001b\\")
  )

  // ----- report oracle (written by the harness, decoded here) -----

  final case class PtyTabReport(
      label: String,
      tabId: String,
      jobId: String,
      status: String,
      lastSeq: Long,
      unread: Long,
      scrollbackLines: Int,
      scrollbackEvicted: Long,
      draft: String,
      draftLen: Int,
      feedDone: Boolean
  )

  object PtyTabReport:
    given JsonDecoder[PtyTabReport] = DeriveJsonDecoder.gen

  final case class PtyReport(
      eventsPerTab: Int,
      allFeedsDone: Boolean,
      hubDroppedDiagnostics: Long,
      tabs: List[PtyTabReport]
  )

  object PtyReport:
    given JsonDecoder[PtyReport] = DeriveJsonDecoder.gen

  /** Feeds settled: every tab fed exactly eventsPerTab+1 envelopes, tab still
    * Running, scrollback byte-count exact and lossless, hub dropped nothing,
    * and every draft matches the expected bytes (unspecified → empty).
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
        t.lastSeq == (1 + rep.eventsPerTab).toLong &&
        t.scrollbackEvicted == 0L &&
        t.scrollbackLines == rep.eventsPerTab &&
        drafts.getOrElse(t.label, "") == t.draft
      }

  /** Streaming live mid-tour: every tab already Running (the hub folded at
    * least the start-of-stream status), drafts exact so far — feed completion
    * NOT required (that is the point: the tour happens DURING streaming).
    */
  private def draftsNow(
      labels: List[String],
      drafts: Map[String, String]
  ): PtyReport => Boolean = rep =>
    rep.tabs.map(_.label) == labels &&
      rep.hubDroppedDiagnostics == 0L &&
      rep.tabs.forall { t =>
        t.status == "Running" &&
        drafts.getOrElse(t.label, "") == t.draft
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
    * prompt line with a bare CR before writing above it); splitting on both
    * gives the rows exactly as they were rendered.
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
      eventsPerTab: Int,
      delayMs: Int
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai103-pty-$name-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.TabShellDraftsPtyApp",
      report.toString
    )

    pb.redirectErrorStream(true)
    pb.directory(Files.createDirectories(home.resolve("cwd")).toFile)
    pb.environment().put("TERM", "xterm-256color")
    pb.environment().put("NO_COLOR", "1")
    pb.environment().put("HOME", home.toString)
    pb.environment().put("RAI103_PTY_EVENTS", eventsPerTab.toString)
    pb.environment().put("RAI103_PTY_DELAY_MS", delayMs.toString)

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

    /** Answer JLine capability queries with canned responses (RAI-102 parity).
      */
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
  private def drive[A](name: String, eventsPerTab: Int, delayMs: Int)(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI103_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai103-pty-$name-ev")
      // Create the evidence dir BEFORE the child starts: the harness writes
      // its report there from its first feeder tick, and report-write
      // failures are intentionally non-fatal (ignored) — a missing parent
      // dir must not silently hollow out the oracle.
      Files.createDirectories(evidence)
      val report = evidence.resolve(s"report-$name.json")
      val session = PtySession(name, report, evidence, eventsPerTab, delayMs)
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

  // ----- P2D-01: A→B→A preserves draft A byte-for-byte under live streaming -----

  private def p2d01: ZIO[Any, Throwable, TestResult] =
    drive("p2d01", Events, TabShellDraftsPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new alpha\r")
      val createdA = s.waitFor("tab created: alpha", 20.seconds)
      s.send(":new beta\r")
      val createdB = s.waitFor("tab created: beta", 20.seconds)
      s.send(":switch alpha\r")
      val focusedA = s.waitFor("focused: alpha", 20.seconds)
      // Draft A, first chunk (multibyte), while both feeders stream.
      s.send("café😀 résumé漢字\r")
      val draftA1 = s.waitFor("draft [alpha]: café😀 résumé漢字", 25.seconds)
      // Streaming is LIVE here (both tabs Running) and A's draft is exact.
      val liveMid = s.waitForReport(
        draftsNow(
          List("alpha", "beta"),
          Map("alpha" -> "café😀 résumé漢字")
        ),
        30.seconds
      )
      // A→B: draft B is tab-isolated.
      s.send(":next\r")
      val focusedB = s.waitFor("focused: beta", 20.seconds)
      s.send("résumé✓\r")
      val draftB = s.waitFor("draft [beta]: résumé✓", 25.seconds)
      // B→A: append to draft A — bytes survive the tour.
      s.send(":prev\r")
      val focusedBack = s.waitFor("focused: alpha", 20.seconds)
      s.send(" 漢字-tail\r")
      val draftA2 =
        s.waitFor("draft [alpha]: café😀 résumé漢字 漢字-tail", 25.seconds)
      val rep = s.waitForReport(
        settled(
          List("alpha", "beta"),
          Map("alpha" -> "café😀 résumé漢字 漢字-tail", "beta" -> "résumé✓")
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
          "p2d01",
          "promptUp" -> boolJson(promptUp),
          "createdA" -> boolJson(createdA),
          "createdB" -> boolJson(createdB),
          "focusedA" -> boolJson(focusedA),
          "draftA1" -> boolJson(draftA1),
          "streamingLiveMidTour" -> boolJson(liveMid.isDefined),
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
      val echoA = countSubstr(text, "draft [alpha]: café😀 résumé漢字 漢字-tail")
      val echoB = countSubstr(text, "draft [beta]: résumé✓")
      assertTrue(promptUp) &&
      assertTrue(createdA) &&
      assertTrue(createdB) &&
      assertTrue(focusedA && focusedB && focusedBack) &&
      assertTrue(draftA1) &&
      assertTrue(liveMid.isDefined) &&
      assertTrue(draftB && draftA2) &&
      assertTrue(echoA == 1 && echoB == 1) &&
      assertTrue(rep.isDefined) &&
      assertTrue(promptLive) &&
      assertTrue(rows >= 300) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  // ----- P2D-02: close with a draft — hide/cancel dialogue; refusal preserves -----

  private def p2d02: ZIO[Any, Throwable, TestResult] =
    drive("p2d02", Events, TabShellDraftsPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new alpha\r")
      val createdA = s.waitFor("tab created: alpha", 20.seconds)
      s.send(":new beta\r")
      val createdB = s.waitFor("tab created: beta", 20.seconds)
      // beta (focused after :new) gets its own draft first.
      s.send("draft-beta漢\r")
      val draftB = s.waitFor("draft [beta]: draft-beta漢", 25.seconds)
      s.send(":switch alpha\r")
      val focusedA = s.waitFor("focused: alpha", 20.seconds)
      s.send("draft-alpha😀\r")
      val draftA = s.waitFor("draft [alpha]: draft-alpha😀", 25.seconds)
      // Close the RUNNING tab that holds a draft: the dialogue must offer
      // hide AND cancel (RAI-061 closePrompt).
      s.send(":close\r")
      val dialogue = s.waitFor(ClosePrompt, 20.seconds)
      // Refuse: three invalid answers — re-asked, then the SAFE default
      // (cancel-request, report only) applies; nothing hidden/cancelled.
      s.send("nope\r")
      Thread.sleep(400)
      val reasked = countSubstr(s.cleaned, ClosePrompt) >= 2
      s.send("x\r")
      Thread.sleep(400)
      val reasked2 = countSubstr(s.cleaned, ClosePrompt) >= 3
      s.send("y\r")
      val hintDefault = s.waitFor(
        "hint: answer h (hide) or c (cancel); defaulting to cancel-request (report only)",
        20.seconds
      )
      val cancelReported = s.waitFor("cancel requested for: alpha", 20.seconds)
      val tabStays = s.waitFor("the tab stays open", 20.seconds)
      // The refusal touched nothing: feeds stream to completion, BOTH drafts
      // byte-exact (alpha's own, beta's untouched sibling), hub drops 0.
      val rep = s.waitForReport(
        settled(
          List("alpha", "beta"),
          Map("alpha" -> "draft-alpha😀", "beta" -> "draft-beta漢")
        ),
        90.seconds
      )
      // The tab is still open and focused, sibling listed.
      s.send(":tabs\r")
      val alphaStillOpen = s.waitFor("*1. alpha [", 15.seconds)
      val betaStillThere = s.waitFor("2. beta [", 15.seconds)
      // The prompt is editable and the draft still accumulates after refusal.
      s.send(" after-refusal\r")
      val draftA2 =
        s.waitFor("draft [alpha]: draft-alpha😀 after-refusal", 25.seconds)
      val rep2 = s.waitForReport(
        settled(
          List("alpha", "beta"),
          Map("alpha" -> "draft-alpha😀 after-refusal", "beta" -> "draft-beta漢")
        ),
        30.seconds
      )
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val crashed =
        text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2d02",
          "promptUp" -> boolJson(promptUp),
          "createdA" -> boolJson(createdA),
          "createdB" -> boolJson(createdB),
          "draftB" -> boolJson(draftB),
          "focusedA" -> boolJson(focusedA),
          "draftA" -> boolJson(draftA),
          "dialogue" -> boolJson(dialogue),
          "reasked" -> boolJson(reasked),
          "reasked2" -> boolJson(reasked2),
          "hintDefault" -> boolJson(hintDefault),
          "cancelReported" -> boolJson(cancelReported),
          "tabStays" -> boolJson(tabStays),
          "reportSettled" -> boolJson(rep.isDefined),
          "alphaStillOpen" -> boolJson(alphaStillOpen),
          "betaStillThere" -> boolJson(betaStillThere),
          "draftA2" -> boolJson(draftA2),
          "reportSettledAfter" -> boolJson(rep2.isDefined),
          "noCrash" -> boolJson(!crashed),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(createdA && createdB) &&
      assertTrue(draftB && focusedA && draftA) &&
      assertTrue(dialogue) &&
      assertTrue(reasked && reasked2) &&
      assertTrue(hintDefault && cancelReported && tabStays) &&
      assertTrue(rep.isDefined) &&
      assertTrue(alphaStillOpen && betaStillThere) &&
      assertTrue(draftA2) &&
      assertTrue(rep2.isDefined) &&
      assertTrue(!crashed) &&
      assertTrue(code == 0)
    }

  // ----- P2D-03 (UX02): 20 streaming jobs while typing — drafts stable -----

  private def p2d03: ZIO[Any, Throwable, TestResult] =
    // 20 concurrent emitters x (1+40) events @ 40ms: a storm that spans the
    // whole typing tour (each emitter ~1.6s, staggered by the feeder ticks).
    drive("p2d03", 40, 40) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      val labels = (1 to 20).map(i => f"t$i%02d").toList
      labels.foreach { l =>
        s.send(s":new $l\r")
        Thread.sleep(30)
      }
      val created20 = s.waitFor("tab created: t20", 30.seconds)
      // Active input DURING the storm: these lines are typed while 20
      // emitters flood the terminal (queued by the PTY, processed by the one
      // JLine loop) — no waiting between the sends.
      s.send(":switch t01\r")
      Thread.sleep(80)
      s.send("draft01😀\r")
      Thread.sleep(80)
      s.send(":next\r")
      Thread.sleep(80)
      s.send("draft02漢\r")
      Thread.sleep(80)
      s.send(":next\r")
      Thread.sleep(80)
      s.send("draft03é\r")
      Thread.sleep(80)
      s.send(":switch t20\r")
      Thread.sleep(80)
      s.send("draft20✓\r")
      val draft01 = s.waitFor("draft [t01]: draft01😀", 30.seconds)
      val draft20 = s.waitFor("draft [t20]: draft20✓", 30.seconds)
      // Storm settled: ALL 20 feeds done, ALL 20 drafts isolated and
      // byte-exact (17 empty, 4 typed), lossless scrollback, hub drops 0.
      val expectedDrafts = Map(
        "t01" -> "draft01😀",
        "t02" -> "draft02漢",
        "t03" -> "draft03é",
        "t20" -> "draft20✓"
      )
      val rep = s.waitForReport(settled(labels, expectedDrafts), 120.seconds)
      // The prompt stays editable AFTER the storm (UX02): focus t05 and
      // append — leading space preserved byte-exact (draft was empty).
      s.send(":switch t05\r")
      val focused05 = s.waitFor("focused: t05", 20.seconds)
      s.send(" addition\r")
      val draft05 = s.waitFor("draft [t05]:  addition", 25.seconds)
      val rep2 = s.waitForReport(
        settled(labels, expectedDrafts + ("t05" -> " addition")),
        30.seconds
      )
      s.send(":tabs\r")
      val allTwenty = s.waitFor("20. t20 [", 15.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      s.persist(
        text,
        summaryJson(
          "p2d03",
          "promptUp" -> boolJson(promptUp),
          "created20" -> boolJson(created20),
          "draft01" -> boolJson(draft01),
          "draft20" -> boolJson(draft20),
          "reportSettled" -> boolJson(rep.isDefined),
          "focused05" -> boolJson(focused05),
          "draft05" -> boolJson(draft05),
          "reportSettledAfter" -> boolJson(rep2.isDefined),
          "allTwenty" -> boolJson(allTwenty),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(created20) &&
      assertTrue(draft01 && draft20) &&
      assertTrue(rep.isDefined) &&
      assertTrue(focused05 && draft05) &&
      assertTrue(rep2.isDefined) &&
      assertTrue(allTwenty) &&
      assertTrue(rows >= 8000) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  def spec =
    suite(
      "TabShellDraftsPtySpec (RAI-103: per-tab drafts on the product host under PTY)"
    )(
      test(
        "P2D-01: A→B→A keeps draft A byte-for-byte under live streaming (PTY)"
      )(
        p2d01
      ),
      test(
        "P2D-02: close with a draft offers hide/cancel; refusal preserves drafts (PTY)"
      )(p2d02),
      test(
        "P2D-03: 20 streaming jobs while typing — drafts stable, prompt editable (PTY)"
      )(
        p2d03
      )
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(1500.seconds)

end TabShellDraftsPtySpec
