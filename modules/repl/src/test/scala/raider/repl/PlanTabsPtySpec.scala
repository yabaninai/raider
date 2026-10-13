package raider.repl

import zio._
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test._

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

/** RAI-110 (ADR-028 §3) — PTY acceptance of the PRODUCT tab shell with the ONE
  * plan tab: raider.repl.TabShellHost.run(wiring, panel) under a real
  * pseudo-terminal (the accepted RAI-102 driver mechanics; the harness child is
  * raider.repl.PlanTabsPtyApp with the RAI-107 PlanSession behind the accepted
  * PlanPanel interceptor). Deterministic scripted fixtures only.
  *
  *   - PLANUI-01: the settled proposal is visible in the plan tab (`:aux`);
  *     `:approve` from the product shell starts EXACTLY ONE execution job (the
  *     report's labels carry exactly one `plan-exec`); exit 0.
  *   - PLANUI-02: typed refusals on the product surface — double approve (`plan
  *     already approved`), reject-after-approve (`no live plan phase`: cancel
  *     is not approval); the shell stays alive; still exactly one execution
  *     job; exit 0. (The virgin-session NoPlan refusal and the
  *     repeated-plan-after-Cancelled edge are covered in-process by
  *     PlanPanelSpec; an approve typed during the LIVE plan phase legitimately
  *     AWAITS the settled proposal per ADR-028 §2 — it is not a refusal.)
  *   - PLANUI-03: live hub burst while reviewing the plan — the tab renders,
  *     drafts survive, ONE writer (zero torn bar rows), prompt editable; exit
  *     0.
  *
  * Limitations (same class as RAI-102): line atomicity is judged on the final
  * cleaned transcript; capability queries are answered per chunk; Ctrl+C and
  * resize are covered by RAI-062 PTY-02 for the same shell. The "repeated plan
  * from Cancelled" edge is covered in-process by PlanPanelSpec (the PTY harness
  * script has exactly three scripted model calls).
  */
object PlanTabsPtySpec extends ZIOSpecDefault:

  private val Prompt = "raider> "

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private val RowFull = raw"^[* ]\d+\. \S+ \[[^\]]+\]( \(\d+ unread\))?$$".r
  private val RowLike = raw"^[* ]\d+\. |\(\d+ unread\)".r

  private val Queries: Vector[(String, String)] = Vector(
    ("\u001b[c", "\u001b[?62;22c"),
    ("\u001b[>c", "\u001b[?0c"),
    ("\u001b[?2027$p", "\u001b[?2027;0$y"),
    ("\u001b[?u", "\u001b[?0u"),
    ("\u001b[>q", "\u001bP>|raider-plan-tabs\u001b\\")
  )

  // ----- report oracle (written by the harness, decoded here) -----

  final case class PtyReport(
      phase: String,
      title: Option[String],
      steps: Option[List[String]],
      execution: Option[String],
      rejection: Option[String],
      labels: List[String],
      hubDroppedDiagnostics: Long
  )

  object PtyReport:
    given JsonDecoder[PtyReport] = DeriveJsonDecoder.gen

  private def summaryJson(name: String, fields: (String, String)*): String =
    fields
      .map((k, v) => s"\"$k\":$v")
      .mkString(s"""{"name":"$name",""", ",", "}")

  private def boolJson(b: Boolean): String = if b then "true" else "false"

  private def barScan(cleaned: String): (Int, Int) =
    val lines = cleaned.split("[\r\n]").map(_.stripTrailing).filter(_.nonEmpty)
    val rows = lines.count(l => RowFull.matches(l))
    val bad =
      lines.count(l => RowLike.findFirstIn(l).isDefined && !RowFull.matches(l))
    (rows, bad)

  // ----- the PTY session (script-run harness JVM) -----

  private final class PtySession(
      name: String,
      report: Path,
      evidenceDir: Path
  ):

    private val javaBin =
      Paths.get(JSystem.getProperty("java.home"), "bin", "java").toString

    private val cp = JSystem.getProperty("java.class.path")
    private val home = Files.createTempDirectory(s"rai110-pty-$name-home")

    private val pb = new java.lang.ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      javaBin,
      "-cp",
      cp,
      "raider.repl.PlanTabsPtyApp",
      report.toString
    )

    pb.redirectErrorStream(true)
    pb.directory(Files.createDirectories(home.resolve("cwd")).toFile)
    pb.environment().put("TERM", "xterm-256color")
    pb.environment().put("NO_COLOR", "1")
    pb.environment().put("HOME", home.toString)

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

  /** Drive one session; persist evidence even on failure; always close. */
  private def drive[A](name: String)(
      body: PtySession => A
  ): ZIO[Any, Throwable, A] =
    ZIO.attempt {
      val evidence = sys.env.get("RAI110_PTY_EVIDENCE_DIR") match
        case Some(dir) => Path.of(dir)
        case None      => Files.createTempDirectory(s"rai110-pty-$name-ev")
      val report = evidence.resolve(s"report-$name.json")
      val session = PtySession(name, report, evidence)
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

  def spec = suite("PlanTabsPtySpec (RAI-110: plan tab + approval under PTY)")(
    test(
      "PLANUI-01: the settled plan is visible in the tab; :approve starts exactly ONE execution job (PTY)"
    ) {
      drive("planui01") { s =>
        val promptUp = s.waitFor(Prompt, 120.seconds)
        val ready = s.waitForReport(_.phase == "ready", 60.seconds)
        // The plan tab (aux) shows the proposal: title, numbered steps, hint.
        s.send(":aux\r")
        val proposalVisible = s.waitFor("proposal: Ship it", 20.seconds)
        val stepsVisible = s.waitFor("1. read the file", 20.seconds)
        val hintVisible =
          s.waitFor("review the plan, then type :approve", 20.seconds)
        // Approve from the PRODUCT shell: exactly one execution job.
        s.send(":approve\r")
        val approvedPrinted =
          s.waitFor("plan approved: execution job ", 30.seconds)
        val approvedReport = s.waitForReport(
          rep => rep.phase == "approved" && rep.execution.isDefined,
          30.seconds
        )
        val oneExec = s.waitForReport(
          rep => rep.labels.count(_ == "plan-exec") == 1,
          30.seconds
        )
        s.send(":quit\r")
        val code = s.finishWait(30.seconds)
        val text = s.cleaned
        val (rows, bad) = barScan(text)
        s.persist(
          text,
          summaryJson(
            "planui01",
            "promptUp" -> boolJson(promptUp),
            "ready" -> boolJson(ready.isDefined),
            "proposalVisible" -> boolJson(proposalVisible),
            "stepsVisible" -> boolJson(stepsVisible),
            "hintVisible" -> boolJson(hintVisible),
            "approvedPrinted" -> boolJson(approvedPrinted),
            "approvedReport" -> boolJson(approvedReport.isDefined),
            "oneExec" -> boolJson(oneExec.isDefined),
            "barRows" -> rows.toString,
            "barRowsMalformed" -> bad.toString,
            "exit" -> code.toString
          )
        )
        assertTrue(promptUp) &&
        assertTrue(ready.isDefined) &&
        assertTrue(proposalVisible && stepsVisible && hintVisible) &&
        assertTrue(approvedPrinted) &&
        assertTrue(approvedReport.isDefined) &&
        assertTrue(oneExec.isDefined) &&
        assertTrue(code == 0)
      }
    },
    test(
      "PLANUI-02: typed refusals — approve-before-plan, double approve, reject-after-approve; exactly one execution job; shell alive (PTY)"
    ) {
      drive("planui02") { s =>
        val promptUp = s.waitFor(Prompt, 120.seconds)
        val ready = s.waitForReport(_.phase == "ready", 60.seconds)
        s.send(":aux\r")
        val proposalVisible = s.waitFor("proposal: Ship it", 20.seconds)
        s.send(":approve\r")
        val approvedPrinted =
          s.waitFor("plan approved: execution job ", 30.seconds)
        // double approve: typed AlreadyApproved, no second job
        s.send(":approve\r")
        val already = s.waitFor("hint: plan already approved", 20.seconds)
        // reject after approval: no live plan phase (cancel is not approval)
        s.send(":reject\r")
        val rejectNoLive =
          s.waitFor(
            "hint: reject: no live plan phase in this session",
            20.seconds
          )
        val oneExec = s.waitForReport(
          rep => rep.labels.count(_ == "plan-exec") == 1,
          30.seconds
        )
        s.send(":bogus\r")
        val shellAlive = s.waitFor("hint: unknown command: :bogus", 20.seconds)
        s.send(":quit\r")
        val code = s.finishWait(30.seconds)
        s.persist(
          s.cleaned,
          summaryJson(
            "planui02",
            "promptUp" -> boolJson(promptUp),
            "ready" -> boolJson(ready.isDefined),
            "proposalVisible" -> boolJson(proposalVisible),
            "approvedPrinted" -> boolJson(approvedPrinted),
            "already" -> boolJson(already),
            "rejectNoLive" -> boolJson(rejectNoLive),
            "oneExec" -> boolJson(oneExec.isDefined),
            "shellAlive" -> boolJson(shellAlive),
            "exit" -> code.toString
          )
        )
        assertTrue(promptUp) &&
        assertTrue(ready.isDefined) &&
        assertTrue(proposalVisible) &&
        assertTrue(approvedPrinted) &&
        assertTrue(already) &&
        assertTrue(rejectNoLive) &&
        assertTrue(oneExec.isDefined) &&
        assertTrue(shellAlive) &&
        assertTrue(code == 0)
      }
    },
    test(
      "PLANUI-03: live hub burst while reviewing the plan — draft survives, ONE writer, prompt editable (PTY)"
    ) {
      drive("planui03") { s =>
        val promptUp = s.waitFor(Prompt, 120.seconds)
        // A tab whose synthetic stream bursts in the background (RAI-102
        // feeder): typing + plan review overlap the live hub traffic.
        s.send(":new busy\r")
        val created = s.waitFor("tab created: busy", 20.seconds)
        s.send("cafélog\r")
        val draftDuring = s.waitFor("draft [busy]: cafélog", 30.seconds)
        val ready = s.waitForReport(_.phase == "ready", 60.seconds)
        s.send(":aux\r")
        val proposalVisible = s.waitFor("proposal: Ship it", 20.seconds)
        s.send(" queue2\r")
        val draftAfter = s.waitFor("draft [busy]: cafélog queue2", 30.seconds)
        s.send(":approve\r")
        val approvedPrinted =
          s.waitFor("plan approved: execution job ", 30.seconds)
        s.send(":quit\r")
        val code = s.finishWait(30.seconds)
        val text = s.cleaned
        val (rows, bad) = barScan(text)
        val quiet = s.waitQuiet(1500.millis, 20.seconds)
        val rep = s.waitForReport(_.phase == "approved", 10.seconds)
        s.persist(
          text,
          summaryJson(
            "planui03",
            "promptUp" -> boolJson(promptUp),
            "created" -> boolJson(created),
            "draftDuring" -> boolJson(draftDuring),
            "ready" -> boolJson(ready.isDefined),
            "proposalVisible" -> boolJson(proposalVisible),
            "draftAfter" -> boolJson(draftAfter),
            "approvedPrinted" -> boolJson(approvedPrinted),
            "quiet" -> boolJson(quiet),
            "hubDrops" -> rep.fold(-1L)(_.hubDroppedDiagnostics).toString,
            "barRows" -> rows.toString,
            "barRowsMalformed" -> bad.toString,
            "exit" -> code.toString
          )
        )
        assertTrue(promptUp) &&
        assertTrue(created) &&
        assertTrue(draftDuring) &&
        assertTrue(ready.isDefined) &&
        assertTrue(proposalVisible) &&
        assertTrue(draftAfter) &&
        assertTrue(approvedPrinted) &&
        assertTrue(bad == 0) &&
        assertTrue(rep.isDefined && rep.get.hubDroppedDiagnostics == 0L) &&
        assertTrue(code == 0)
      }
    }
  )

end PlanTabsPtySpec
