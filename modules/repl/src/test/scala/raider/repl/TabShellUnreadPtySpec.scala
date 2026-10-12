package raider.repl

import zio._
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

/** RAI-105 / P2U — PTY acceptance of unread badges on the PRODUCT TabShell host
  * (raider.repl.TabShellHost) in a REAL pseudo-terminal. Same pattern as
  * TabShellHostPtySpec (RAI-102): the child process is the test-scope harness
  * raider.repl.TabShellUnreadPtyApp, run under `/usr/bin/script` (BSD utility:
  * openpty + setsid + dup2(slave onto 0/1/2) + exec), master relayed through
  * plain pipes, JLine capability queries answered per probe parity, transcripts
  * ANSI-cleaned, and the harness report (registry read model) is the oracle. No
  * new dependencies, no native glue, no mocked shell — the real product
  * TabShellHost renders the accepted RAI-097 badge "(N unread)" through the one
  * writer (RAI-061 printAbove).
  *
  * Acceptance (card RAI-105):
  *   - P2U-01: events in a BACKGROUND tab grow the badge; focus resets it; 0 is
  *     suppressed (no "(0 unread)" anywhere); deliveries to a FOCUSED tab never
  *     bump (PTY).
  *   - P2U-02: events for a hidden tab's job and for a job that never had a tab
  *     grow no phantom tabs; unknown-tab addressing surfaces the typed error;
  *     shell and run stay alive; exit 0.
  *   - P2U-03: concurrent bursts into several background tabs while typing —
  *     counters consistent (every delivered envelope counted exactly once, hub
  *     drops == 0), tab-bar rendering atomic (zero torn rows, ONE writer); exit
  *     0.
  *
  * Limitations (stated honestly, same class as the RAI-062/RAI-102 probes):
  * line atomicity is judged on the final cleaned transcript (a tear later
  * erased by JLine is not individually detectable); capability queries are
  * answered per read chunk; Ctrl+C/resize are out of P2U scope (covered for the
  * same shell by RAI-062 PTY-02).
  */
object TabShellUnreadPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "
  private val BurstEvents = TabShellUnreadPtyApp.BurstEventsPerTab

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  /** A full tab-bar row (RAI-097 statusText is free text inside brackets). */
  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  /** Capability queries JLine may emit, with canned answers (probe parity). */
  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-tabs\u001b\\")
  )

  // ----- report oracle (written by the harness, decoded here) -----

  final case class UnreadTabReport(
      label: String,
      status: String,
      lastSeq: Long,
      unread: Long,
      draft: String
  )

  object UnreadTabReport:
    given JsonDecoder[UnreadTabReport] = DeriveJsonDecoder.gen

  final case class UnreadReport(
      scenario: String,
      phase: String,
      hubDroppedDiagnostics: Long,
      tabs: List[UnreadTabReport]
  )

  object UnreadReport:
    given JsonDecoder[UnreadReport] = DeriveJsonDecoder.gen

  private def labels(rep: UnreadReport): List[String] = rep.tabs.map(_.label)

  private def tabOf(
      rep: UnreadReport,
      label: String
  ): Option[UnreadTabReport] = rep.tabs.find(_.label == label)

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
    * gives the rows exactly as they were rendered. stripTrailing only: the
    * LEADING space of a non-focused bar row is part of the row.
    */
  private def barScan(cleaned: String): (Int, Int) =
    val lines = cleaned.split("[\r\n]").map(_.stripTrailing).filter(_.nonEmpty)
    val rows = lines.count(l => RowFull.matches(l))
    val bad =
      lines.count(l => RowLike.findFirstIn(l).isDefined && !RowFull.matches(l))
    (rows, bad)

  /** Regex over the FLATTENED transcript must run line-anchored: rebuild the
    * line set first instead of multiline-regex over one big string.
    */
  private def lineMatching(cleaned: String, r: scala.util.matching.Regex): Int =
    cleaned.split("[\r\n]").count(l => r.matches(l.stripTrailing))

  // ----- the PTY session (script-run harness JVM) -----

  private final class PtySession(
      name: String,
      scenario: String,
      delayMs: Int,
      report: Path,
      evidenceDir: Path
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai105-pty-$name-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.TabShellUnreadPtyApp",
      report.toString
    )

    pb.redirectErrorStream(true)
    pb.directory(Files.createDirectories(home.resolve("cwd")).toFile)
    pb.environment().put("TERM", "xterm-256color")
    pb.environment().put("NO_COLOR", "1")
    pb.environment().put("HOME", home.toString)
    pb.environment().put("RAI105_PTY_SCENARIO", scenario)
    pb.environment().put("RAI105_PTY_DELAY_MS", delayMs.toString)

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

    /** Quiescence (transcript byte-stable for `stable` while not typing) — the
      * honest drain signal before :quit (precedent 71d0aa3): the settled report
      * proves the FEEDER delivered every event, but in-flight bar renders can
      * still be writing when :quit is typed, gluing its echo into a torn bar
      * row (the wave-4 "1 torn row" class). No badge/count marker is
      * deterministic mid-stream, but byte-stability is.
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

    def waitForReport(
        pred: UnreadReport => Boolean,
        timeout: Duration
    ): Option[UnreadReport] =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while JSystem.nanoTime() < deadline do
        readReport() match
          case Some(r) if pred(r) => return Some(r)
          case _                  => Thread.sleep(150)
      readReport().filter(pred)

    private def readReport(): Option[UnreadReport] =
      try
        if !Files.exists(report) then None
        else
          Files
            .readString(report, StandardCharsets.UTF_8)
            .fromJson[UnreadReport]
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

  /** Drive one session; persist evidence even on failure; always close.
    *
    * RAI-119 driver-infra fix (att1 reproducer 2026-10-12): the evidence dir
    * used to be created only by `persist` — AFTER the body — so the FIRST spec
    * running against a fresh `RAI105_PTY_EVIDENCE_DIR` spawned a child whose
    * every report write silently failed (`.ignore`), leaving the phase-oracle
    * blind: all `waitForReport` waits timed out (honest red, wrong cause).
    * Creating the dir BEFORE the child starts makes the report oracle
    * deterministic; assertions are untouched.
    */
  private def drive[A](name: String, scenario: String, delayMs: Int)(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI105_PTY_EVIDENCE_DIR") match
        case Some(dir) =>
          val p = Path.of(dir)
          Files.createDirectories(p)
          p
        case None => Files.createTempDirectory(s"rai105-pty-$name-ev")
      val report = evidence.resolve(s"report-$name.json")
      val session = PtySession(name, scenario, delayMs, report, evidence)
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

  // ----- P2U-01: background events grow the badge; focus resets; 0 hidden --

  private def p2u01: ZIO[Any, Throwable, TestResult] =
    drive("p2u01", "p2u01", TabShellUnreadPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new alpha\r")
      val createdA = s.waitFor("tab created: alpha", 20.seconds)
      // alpha is focused: 4 delivered events must NOT bump its counter.
      val a1Settled = s.waitForReport(
        rep =>
          rep.phase == "a1done" &&
            tabOf(rep, "alpha").exists(t => t.unread == 0L && t.lastSeq == 4L),
        45.seconds
      )
      s.send(":new beta\r")
      val createdB = s.waitFor("tab created: beta", 20.seconds)
      // beta focused gets 3 (no bump); background alpha gets 3 (bump to 3).
      // NOTE: the exact badge VALUES mid-stream are eventually-consistent by
      // design (the tab-bar pump renders concurrently with the consumer's
      // bumpUnread, so a render may show the previous counter); the report is
      // the precise oracle, the transcript proves badge RENDERING happened.
      val a2Settled = s.waitForReport(
        rep =>
          rep.phase == "a2done" &&
            tabOf(rep, "alpha").exists(t =>
              t.unread == 3L && t.lastSeq == 7L
            ) &&
            tabOf(rep, "beta").exists(t => t.unread == 0L && t.lastSeq == 3L),
        60.seconds
      )
      // Focus resets: switching to alpha drops its counter to 0.
      s.send(":switch alpha\r")
      val focusedA = s.waitFor("focused: alpha", 20.seconds)
      val resetSeen = s.waitForReport(
        rep =>
          rep.phase == "a2done" &&
            tabOf(rep, "alpha").exists(_.unread == 0L) &&
            tabOf(rep, "beta").exists(_.unread == 0L),
        20.seconds
      )
      s.send(":tabs\r")
      val tabsRender = s.waitFor("*1. alpha [", 15.seconds)
      // Leave to beta; the feeder's a3 batch bumps background alpha to 2.
      s.send(":switch beta\r")
      val focusedB = s.waitFor("focused: beta", 20.seconds)
      val a3Settled = s.waitForReport(
        rep =>
          rep.phase == "done" &&
            tabOf(rep, "alpha").exists(t =>
              t.unread == 2L && t.lastSeq == 9L
            ) &&
            tabOf(rep, "beta").exists(t => t.unread == 0L && t.lastSeq == 3L),
        45.seconds
      )
      // RAI-119 determinism (71d0aa3 complement): drain in-flight bar renders
      // BEFORE reading the badge marker — a mid-stream marker is
      // timing-dependent (PTYD-02 run9 reproducer: settled=true yet no
      // "(2 unread)" row for 20s under load; the forced :tabs re-render
      // surfaced it instantly).
      val drained = s.waitQuiet(1500.millis, 20.seconds)
      // Precedent 91a6575: the registry truth is already proven by a3Settled;
      // the transcript badge is a REAL acceptance requirement, so on a miss we
      // force a fresh bar render with :tabs and wait again — the assertion is
      // not weakened.
      val badge2 =
        if s.waitFor("(2 unread)", 20.seconds) then true
        else
          s.send(":tabs\r")
          s.waitFor("(2 unread)", 15.seconds)
      s.send(":tabs\r")
      val badge2Again = s.waitFor("(2 unread)", 15.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      // Badge rendering happened (any row with the RAI-097 badge) and the
      // stable final state renders exactly: background alpha "(2 unread)",
      // no suppressed-0 badge anywhere, focused events never bumped.
      val badgeRows =
        lineMatching(text, raw"^[* ]\d+\. \S+ \[[^\]]+\] \(\d+ unread\)$$".r)
      val rowAlpha2 =
        lineMatching(text, raw" 1\. alpha \[[^\]]+\] \(2 unread\)".r)
      val noZeroBadge = !text.contains("(0 unread)")
      val noFocusedBump = !text.contains("(4 unread)")
      val crashed = text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2u01",
          "promptUp" -> boolJson(promptUp),
          "createdA" -> boolJson(createdA),
          "a1Settled" -> boolJson(a1Settled.isDefined),
          "createdB" -> boolJson(createdB),
          "a2Settled" -> boolJson(a2Settled.isDefined),
          "focusedA" -> boolJson(focusedA),
          "resetSeen" -> boolJson(resetSeen.isDefined),
          "tabsRender" -> boolJson(tabsRender),
          "focusedB" -> boolJson(focusedB),
          "a3Settled" -> boolJson(a3Settled.isDefined),
          "drained" -> boolJson(drained),
          "badge2" -> boolJson(badge2),
          "badge2Again" -> boolJson(badge2Again),
          "rowAlpha2" -> rowAlpha2.toString,
          "badgeRows" -> badgeRows.toString,
          "noZeroBadge" -> boolJson(noZeroBadge),
          "noFocusedBump" -> boolJson(noFocusedBump),
          "noCrash" -> boolJson(!crashed),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(createdA && createdB) &&
      assertTrue(a1Settled.isDefined) &&
      assertTrue(a2Settled.isDefined) &&
      assertTrue(focusedA && focusedB) &&
      assertTrue(resetSeen.isDefined) &&
      assertTrue(tabsRender) &&
      assertTrue(a3Settled.isDefined) &&
      assertTrue(drained) &&
      assertTrue(badge2 && badge2Again) &&
      assertTrue(badgeRows >= 1) &&
      assertTrue(rowAlpha2 >= 1) &&
      assertTrue(noZeroBadge) &&
      assertTrue(noFocusedBump) &&
      assertTrue(!crashed) &&
      assertTrue(rows >= 20) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  // ----- P2U-02: events for hidden/never-tabbed jobs grow no phantom tabs ---

  private def p2u02: ZIO[Any, Throwable, TestResult] =
    drive("p2u02", "p2u02", TabShellUnreadPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new gone\r")
      val createdGone = s.waitFor("tab created: gone", 20.seconds)
      s.send(":new alpha\r")
      val createdAlpha = s.waitFor("tab created: alpha", 20.seconds)
      s.send(":switch gone\r")
      val focusedGone = s.waitFor("focused: gone", 20.seconds)
      s.send(":close\r")
      val closePrompt = s.waitFor("tab is running - [h]ide", 20.seconds)
      s.send("h\r")
      val hidden = s.waitFor("hidden: gone", 20.seconds)
      // Late events for the hidden tab's job + events for a never-tabbed job;
      // the registry read model must show exactly ["alpha"].
      val settled = s.waitForReport(
        rep => rep.phase == "done" && labels(rep) == List("alpha"),
        45.seconds
      )
      s.send(":tabs\r")
      val tabsRender = s.waitFor("1. alpha [", 15.seconds)
      // Typed unknown-tab handling at the surface (registry errors never
      // escape the executor; unknown labels are typed hints).
      s.send(":switch gone\r")
      val unknownGone = s.waitFor("hint: unknown tab: gone", 20.seconds)
      val hintListsTabs = s.waitFor("open tabs: alpha", 20.seconds)
      s.send(":switch phantom\r")
      val unknownPhantom = s.waitFor("hint: unknown tab: phantom", 20.seconds)
      s.send(":tabs\r")
      val stillOne = s.waitFor("1. alpha [", 15.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      // No phantom tab in the registry read model (authoritative) and the
      // post-late-events `:tabs` render lists exactly one tab.
      val noPhantom = settled.exists(rep => labels(rep) == List("alpha"))
      val crashed = text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2u02",
          "promptUp" -> boolJson(promptUp),
          "createdGone" -> boolJson(createdGone),
          "createdAlpha" -> boolJson(createdAlpha),
          "focusedGone" -> boolJson(focusedGone),
          "closePrompt" -> boolJson(closePrompt),
          "hidden" -> boolJson(hidden),
          "settled" -> boolJson(settled.isDefined),
          "tabsRender" -> boolJson(tabsRender),
          "unknownGone" -> boolJson(unknownGone),
          "hintListsTabs" -> boolJson(hintListsTabs),
          "unknownPhantom" -> boolJson(unknownPhantom),
          "stillOne" -> boolJson(stillOne),
          "noPhantom" -> boolJson(noPhantom),
          "noCrash" -> boolJson(!crashed),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(createdGone && createdAlpha) &&
      assertTrue(focusedGone) &&
      assertTrue(closePrompt && hidden) &&
      assertTrue(settled.isDefined) &&
      assertTrue(tabsRender) &&
      assertTrue(unknownGone && hintListsTabs && unknownPhantom) &&
      assertTrue(stillOne) &&
      assertTrue(noPhantom) &&
      assertTrue(!crashed) &&
      assertTrue(rows >= 4) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  // ----- P2U-03: concurrent bursts — consistent counters, atomic rendering -

  private def p2u03: ZIO[Any, Throwable, TestResult] =
    drive("p2u03", "p2u03", TabShellUnreadPtyApp.BurstDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new t1\r")
      val created1 = s.waitFor("tab created: t1", 20.seconds)
      s.send(":new t2\r")
      val created2 = s.waitFor("tab created: t2", 20.seconds)
      s.send(":new t3\r")
      val created3 = s.waitFor("tab created: t3", 20.seconds)
      s.send(":switch t1\r")
      val focused1 = s.waitFor("focused: t1", 20.seconds)
      // Focus committed; the feeder now fires both background bursts.
      val burstStarted =
        s.waitForReport(_.phase == "burst", 20.seconds)
      // Active input DURING the storm (bytes queue in the PTY, processed by
      // the one JLine loop).
      s.send("строка1\r")
      val draftDuring = s.waitFor("draft [t1]: строка1", 30.seconds)
      // Burst settled: every delivered envelope counted exactly once, the
      // focused tab untouched, hub dropped nothing.
      val settled = s.waitForReport(
        rep =>
          rep.phase == "done" &&
            rep.hubDroppedDiagnostics == 0L &&
            tabOf(rep, "t1").exists(t => t.unread == 0L && t.lastSeq == 0L) &&
            tabOf(rep, "t2").exists(t =>
              t.unread == BurstEvents.toLong &&
                t.lastSeq == BurstEvents.toLong
            ) &&
            tabOf(rep, "t3").exists(t =>
              t.unread == BurstEvents.toLong &&
                t.lastSeq == BurstEvents.toLong
            ),
        90.seconds
      )
      // RAI-119 determinism (71d0aa3 complement): drain the burst's in-flight
      // bar renders BEFORE the marker waits — quiescence is the honest "the
      // render pump caught up" signal, not a longer timeout.
      val burstDrained = s.waitQuiet(1500.millis, 30.seconds)
      // Coordinator determinism fix 2026-10-12 (third occurrence of this
      // flake, same class as the P2W-01 drain fix): under full-suite load the
      // per-event bar render can lag the settled report by more than the
      // marker timeout. The registry truth is already proven by `settled`;
      // the transcript badge is a REAL acceptance requirement, so on a miss
      // we force a fresh bar render with :tabs and wait again — the assertion
      // itself is not weakened (rowT2/rowT3 regexes still must match).
      val badge60 =
        if s.waitFor(s"($BurstEvents unread)", 30.seconds) then true
        else
          s.send(":tabs\r")
          s.waitFor(s"($BurstEvents unread)", 30.seconds)
      // The prompt stays editable AFTER the storm.
      s.send(" хвост2\r")
      val draftAfter =
        s.waitFor("draft [t1]: строка1 хвост2", 25.seconds)
      s.send(":tabs\r")
      val finalRender = s.waitFor(s"($BurstEvents unread)", 15.seconds)
      // RAI-119 determinism (precedent 71d0aa3): drain in-flight bar renders
      // BEFORE :quit — its echo must never glue into a torn bar row.
      val drained = s.waitQuiet(1500.millis, 30.seconds)
      s.send(":quit\r")
      val code = s.finishWait(30.seconds)
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      val rowT2 =
        lineMatching(text, raw" 2\. t2 \[[^\]]+\] \(60 unread\)".r)
      val rowT3 =
        lineMatching(text, raw" 3\. t3 \[[^\]]+\] \(60 unread\)".r)
      val rowT1NoBadge =
        lineMatching(text, raw"^\*1\. t1 \[[^\]]+\]$$".r)
      val noZeroBadge = !text.contains("(0 unread)")
      val draftEchoes = countSubstr(text, "draft [t1]: строка1 хвост2")
      val crashed = text.contains("Exception") || text.contains("at raider.")
      s.persist(
        text,
        summaryJson(
          "p2u03",
          "promptUp" -> boolJson(promptUp),
          "created1" -> boolJson(created1),
          "created2" -> boolJson(created2),
          "created3" -> boolJson(created3),
          "focused1" -> boolJson(focused1),
          "burstStarted" -> boolJson(burstStarted.isDefined),
          "draftDuring" -> boolJson(draftDuring),
          "settled" -> boolJson(settled.isDefined),
          "burstDrained" -> boolJson(burstDrained),
          "badge60" -> boolJson(badge60),
          "draftAfter" -> boolJson(draftAfter),
          "finalRender" -> boolJson(finalRender),
          "drained" -> boolJson(drained),
          "rowT2" -> rowT2.toString,
          "rowT3" -> rowT3.toString,
          "rowT1NoBadge" -> rowT1NoBadge.toString,
          "noZeroBadge" -> boolJson(noZeroBadge),
          "draftEchoes" -> draftEchoes.toString,
          "noCrash" -> boolJson(!crashed),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(created1 && created2 && created3) &&
      assertTrue(focused1) &&
      assertTrue(burstStarted.isDefined) &&
      assertTrue(draftDuring) &&
      assertTrue(settled.isDefined) &&
      assertTrue(burstDrained) &&
      assertTrue(badge60) &&
      assertTrue(draftAfter) &&
      assertTrue(finalRender) &&
      assertTrue(drained) &&
      assertTrue(rowT2 >= 1 && rowT3 >= 1) &&
      assertTrue(rowT1NoBadge >= 1) &&
      assertTrue(noZeroBadge) &&
      assertTrue(draftEchoes == 1) &&
      assertTrue(!crashed) &&
      assertTrue(rows >= 300) &&
      assertTrue(bad == 0) &&
      assertTrue(code == 0)
    }

  def spec =
    suite(
      "TabShellUnreadPtySpec (RAI-105: unread badges on the product host under PTY)"
    )(
      test(
        "P2U-01: background events grow the badge, focus resets, 0 suppressed (PTY)"
      )(p2u01),
      test(
        "P2U-02: events for hidden/never-tabbed jobs — no phantom tabs, typed errors, run alive (PTY)"
      )(p2u02),
      test(
        "P2U-03: concurrent bursts over several tabs — consistent counters, zero torn rows, one writer (PTY)"
      )(p2u03)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(900.seconds)

end TabShellUnreadPtySpec
