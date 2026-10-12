package raider.repl

import zio._
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

/** RAI-111 / PAL2 — PTY acceptance of the chat-command palette on the PRODUCT
  * TabShell host. Separate fixture file by lane contract: the frozen
  * TabShellHostPtyApp/TabShellHostPtySpec (RAI-102) are consumed unmodified —
  * the child process IS the same product harness (TabShellHostPtyApp hosting
  * TabShellHost.run), which carries the palette terminal by default (now with
  * the RAI-111 combined nearest catalog). Driver mechanics mirror the RAI-106
  * reference (TabShellPalettePtySpec): /usr/bin/script openpty relay,
  * capability-query answering, ANSI-cleaning, registry report as oracle.
  *
  * Acceptance (card RAI-111):
  *   - PAL2-02: an unknown command is a typed error WITH exactly one suggestion
  *     — a near miss of a chat command (" did you mean: :jobs?") — and a chat
  *     command itself NEVER yields the self-suggestion; a far-out unknown keeps
  *     the plain hint; the shell stays alive.
  *   - PAL2-03: chat-command palette interactions (":jo" + TAB completing into
  *     the active draft) DURING active hub streaming — the draft survives
  *     byte-for-byte in the registry report (0 torn), the tab-bar renders are
  *     untorn (one writer), the hub drops nothing; exit 0.
  *
  * The frozen hint bar/available-commands line stays router-only (modules/ui is
  * frozen) — chat commands enter the hint surface through the combined nearest
  * suggestion, which is exactly what this spec pins.
  *
  * Limitations (same class as the RAI-106 reference): line atomicity is judged
  * on the final cleaned transcript; capability queries are answered per read
  * chunk.
  */
object TabShellChatPalettePtySpec extends ZIOSpecDefault:

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
    ("\u001b[>q", "\u001bP>|raider-chat-palette\u001b\\")
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
    private val home = Files.createTempDirectory(s"rai111-pty-$name-home")

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

    /** `:quit` with a retry: after a completion JLine4 may lag the accept one
      * keystroke behind, so the first `:quit` can be consumed by the pending
      * accept; a second one lands on the fresh prompt (RAI-106 pattern).
      */
    def quit(): Int =
      send(":quit\r")
      tryWait(8.seconds) match
        case Some(code) => code
        case None =>
          send(":quit\r")
          tryWait(8.seconds).getOrElse(finishWait(10.seconds))

    /** Wait for exit WITHOUT killing: None = still alive after the timeout. */
    def tryWait(timeout: Duration): Option[Int] =
      val deadline = JSystem.nanoTime() + timeout.toNanos
      while proc.isAlive && JSystem.nanoTime() < deadline do Thread.sleep(150)
      if proc.isAlive then None else Some(proc.exitValue())

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
      val evidence = sys.env.get("RAI111_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai111-pty-$name-ev")
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

  // ----- PAL2-02: unknown command — typed + exactly ONE suggestion -----

  private def pal202: ZIO[Any, Throwable, TestResult] =
    drive("pal2-02", TabShellHostPtyApp.DefaultDelayMs) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      // Near miss of a CHAT command: typed error + the combined-catalog hint.
      s.send(":jbs\r")
      val unknown = s.waitFor("hint: unknown command: :jbs", 20.seconds)
      val nearest = s.waitFor("  did you mean: :jobs?", 20.seconds)
      // Far-out unknown: no suggestion at all.
      s.send(":zzzz\r")
      val farOut = s.waitFor("hint: unknown command: :zzzz", 20.seconds)
      // A chat command itself (not routable in tabs mode) is typed, and must
      // NOT gain the absurd self-suggestion.
      s.send(":jobs\r")
      val chatName = s.waitFor("hint: unknown command: :jobs", 20.seconds)
      // The shell stays alive: a shell command still executes.
      s.send(":tabs\r")
      val alive =
        s.waitFor("no open tabs (start one with :new <label>)", 20.seconds)
      val code = s.quit()
      val text = s.cleaned
      val suggestions = countSubstr(text, "did you mean")
      s.persist(
        text,
        summaryJson(
          "pal2-02",
          "promptUp" -> boolJson(promptUp),
          "unknown" -> boolJson(unknown),
          "nearest" -> boolJson(nearest),
          "farOut" -> boolJson(farOut),
          "chatName" -> boolJson(chatName),
          "suggestions" -> suggestions.toString,
          "alive" -> boolJson(alive),
          "noCrash" -> boolJson(!crashed(text)),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp) &&
      assertTrue(unknown && nearest) &&
      assertTrue(farOut) &&
      assertTrue(chatName) &&
      assertTrue(suggestions == 1) &&
      assertTrue(alive) &&
      assertTrue(!crashed(text)) &&
      assertTrue(code == 0)
    }

  // ----- PAL2-03: chat palette during streaming — one writer, 0 torn -----

  private def pal203: ZIO[Any, Throwable, TestResult] =
    drive("pal2-03", 6) { s =>
      val promptUp = s.waitFor(Prompt, 60.seconds)
      s.send(":new busy\r")
      val created = s.waitFor("tab created: busy", 20.seconds)
      // The feeder streams ~1.3s of hub events from the next tick; these
      // palette interactions are queued into the PTY immediately, so their
      // rendering and the drafts happen UNDER the live stream:
      //   - a shell TAB completion (":he" + TAB, palette proper);
      //   - a chat-command near miss (":jo" → typed error + the combined-
      //     catalog did-you-mean ":jobs?" — the RAI-111 chat hint surface).
      s.send("строка1\r")
      s.send(":he\t")
      s.send("\r")
      s.send(":jo\r")
      s.send(" хвост\r")
      val draft1 = s.waitFor("draft [busy]: строка1", 30.seconds)
      // In TABS mode the completed ":help" executes the FROZEN router's help
      // (the "tabs: ..." hint line), NOT the legacy Commands.helpText prose —
      // the chat helpText surface belongs to the no-flag REPL cycle (its
      // palette/completion coverage lives in ReplChatCyclePtySpec).
      val helpShown = s.waitFor(":rename <label>", 30.seconds)
      // ":jo" is not routable in tabs mode: typed error, and the combined
      // nearest catalog surfaces the chat command exactly once.
      val typedChat = s.waitFor("hint: unknown command: :jo", 30.seconds)
      val nearest = s.waitFor("  did you mean: :jobs?", 30.seconds)
      val draft2 = s.waitFor("draft [busy]: строка1 хвост", 30.seconds)
      val rep = s.waitForReport(
        settled(List("busy"), Map("busy" -> "строка1 хвост")),
        90.seconds
      )
      // The prompt stays editable after the storm.
      s.send(" после-бури\r")
      val draft3 =
        s.waitFor("draft [busy]: строка1 хвост после-бури", 25.seconds)
      val settledFinal = s.waitForReport(
        settled(List("busy"), Map("busy" -> "строка1 хвост после-бури")),
        30.seconds
      )
      val code = s.quit()
      val text = s.cleaned
      val (rows, bad) = barScan(text)
      val suggestions = countSubstr(text, "did you mean")
      s.persist(
        text,
        summaryJson(
          "pal2-03",
          "promptUp" -> boolJson(promptUp),
          "created" -> boolJson(created),
          "draft1" -> boolJson(draft1),
          "helpShown" -> boolJson(helpShown),
          "typedChat" -> boolJson(typedChat),
          "nearest" -> boolJson(nearest),
          "draft2" -> boolJson(draft2),
          "reportSettled" -> boolJson(rep.isDefined),
          "draft3" -> boolJson(draft3),
          "settledFinal" -> boolJson(settledFinal.isDefined),
          "barRows" -> rows.toString,
          "barRowsMalformed" -> bad.toString,
          "suggestions" -> suggestions.toString,
          "noCrash" -> boolJson(!crashed(text)),
          "exit" -> code.toString
        )
      )
      assertTrue(promptUp && created) &&
      assertTrue(draft1 && draft2 && draft3) &&
      assertTrue(helpShown) &&
      assertTrue(typedChat && nearest && suggestions == 1) &&
      assertTrue(rep.isDefined && settledFinal.isDefined) &&
      assertTrue(rows >= 200) &&
      assertTrue(bad == 0) &&
      assertTrue(!crashed(text)) &&
      assertTrue(code == 0)
    }

  def spec =
    suite(
      "TabShellChatPalettePtySpec (RAI-111: chat palette on the product host)"
    )(
      test("PAL2-02: unknown command — typed + exactly one suggestion (PTY)")(
        pal202
      ),
      test(
        "PAL2-03: chat palette during streaming — 0 torn drafts, one writer (PTY)"
      )(pal203)
    ) @@ TestAspect.sequential @@ TestAspect
      .timeout(600.seconds)

end TabShellChatPalettePtySpec
