package raider.repl

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventHub, EventPayload}
import raider.ui.{SessionRegistry, TabId, TabView}
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** RAI-105 / P2U — test-scope PTY harness for unread badges on the PRODUCT
  * TabShell host: raider.repl.TabShellHost (the real product wiring:
  * ShellTerminal.jline + EventHub + SessionRegistry + the accepted
  * raider.ui.TabShell) driven under a pseudo-terminal by the Scala PTY driver
  * in TabShellUnreadPtySpec.
  *
  * Same pattern as raider.repl.TabShellHostPtyApp (RAI-102), with a
  * SCENARIO-driven feeder instead of a uniform one: the unread mechanics live
  * in the frozen layer (RAI-057 registry counters, RAI-097 badge rendering,
  * RAI-061 tab-bar pump), so this harness only stages deterministic event
  * schedules through the REAL hub (ADR-024 path, no bypass) and reports the
  * registry read model. Three scenarios (env RAI105_PTY_SCENARIO):
  *
  *   - p2u01 (default): alpha focused gets 4 events (unread stays 0 — focused
  *     tabs never bump); beta created (focus moves) gets 3 (still 0); alpha in
  *     the background gets 3 (badge "(3 unread)"); after the user re-focuses
  *     alpha (reset to 0) and leaves to beta, alpha gets 2 more (badge "(2
  *     unread)"). Phases are written to the report; the driver synchronizes on
  *     them.
  *   - p2u02: tab "gone" is created, focused, then hidden via `:close` + h;
  *     afterwards 3 events hit the hidden tab's job and 2 events hit a job that
  *     NEVER had a tab — the registry must show no phantom tabs.
  *   - p2u03: three tabs, focus on t1; concurrent bursts (60 Diagnostics each)
  *     into the background tabs t2 and t3 while the driver types — counters
  *     must stay consistent, rendering atomic.
  *
  * Report: continuously writes the registry read model (label/status/lastSeq/
  * unread/draft per tab + phase + hub drop counter) to `<argv(0)>` (atomic
  * tmp+rename every 100 ms) — the driver's oracle.
  */
object TabShellUnreadPtyApp:

  /** Inter-event delay for staged scenarios (p2u01/p2u02). */
  val DefaultDelayMs: Int = 250

  /** Inter-event delay for the concurrent burst (p2u03). */
  val BurstDelayMs: Int = 3

  /** Diagnostics events per background tab in the p2u03 burst. */
  val BurstEventsPerTab: Int = 60

  private def scenario: String =
    Option(java.lang.System.getenv("RAI105_PTY_SCENARIO")).getOrElse("p2u01")

  private def delayMs: Int =
    Option(java.lang.System.getenv("RAI105_PTY_DELAY_MS"))
      .flatMap(_.toIntOption)
      .getOrElse(if scenario == "p2u03" then BurstDelayMs else DefaultDelayMs)

  /** Test-owned terminal geometry (probe parity: the PTY driver owns the size).
    * A `script`-run PTY has NO winsize set (0x0), which degrades JLine
    * rendering (redraw junk), so the harness sets it on its own stdin — the PTY
    * slave — BEFORE the product host builds the ShellTerminal. Failures are
    * ignored (a non-tty run keeps JLine's defaults; checks then fail on
    * content, never fake-pass).
    */
  private def setPtySize(rows: Int, cols: Int): Unit =
    try
      new ProcessBuilder("stty", "rows", rows.toString, "cols", cols.toString)
        .inheritIO()
        .start()
        .waitFor()
    catch case _: Exception => ()

  def main(args: Array[String]): Unit =
    val reportPath =
      Paths.get(
        if args.nonEmpty then args(0) else "tabs-unread-pty-report.json"
      )
    setPtySize(rows = 40, cols = 120)
    val program =
      ZIO.scoped {
        for
          wiring <- TabShellHost.wiring
          phase <- Ref.make("boot")
          // Reporter: the ONE writer of the report file (no tmp collisions).
          _ <- reporter(wiring, phase, reportPath).fork
          _ <- feeder(wiring.hub, wiring.registry, phase, delayMs).fork
          _ <- TabShellHost.run(wiring)
          // Final consistent snapshot while hub/registry are still alive.
          _ <- writeReport(wiring, phase, reportPath)
        yield ()
      }
    zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(program).getOrThrow()
    }
    // Explicit: hub forwarders are process-lifetime daemons (no stop());
    // returning from main exits the JVM with code 0 (forbidden-apis clean).

  // ----- scenario feeders (phases are the driver's synchronization points) --

  private def feeder(
      hub: EventHub,
      registry: SessionRegistry,
      phase: Ref[String],
      delayMs: Int
  ): UIO[Unit] =
    val run = scenario match
      case "p2u02" => feedP2u02(hub, registry, phase, delayMs)
      case "p2u03" => feedP2u03(hub, registry, phase, delayMs)
      case _       => feedP2u01(hub, registry, phase, delayMs)
    // A stuck schedule (a logically impossible driver/feeder race aside)
    // leaves the phase frozen mid-scenario: the driver then fails on its own
    // generous timeouts — an honest FAIL, never a fake pass.
    run.catchAll(_ => ZIO.unit)

  /** P2U-01 schedule: focused deliveries never bump; background deliveries
    * bump; focus resets. See the class doc for the exact phase ladder.
    */
  private def feedP2u01(
      hub: EventHub,
      registry: SessionRegistry,
      phase: Ref[String],
      delayMs: Int
  ): ZIO[Any, Throwable, Unit] =
    for
      a <- awaitTab(registry, "alpha")
      _ <- phase.set("a1")
      _ <- party(hub, a, 4, delayMs) // alpha FOCUSED → unread stays 0
      _ <- phase.set("a1done")
      b <- awaitTab(registry, "beta")
      _ <- phase.set("b1")
      _ <- party(hub, b, 3, delayMs) // beta FOCUSED (:new focuses) → 0
      _ <- phase.set("a2")
      _ <- party(hub, a, 3, delayMs) // alpha background → unread 3
      _ <- phase.set("a2done")
      // The a3 batch must fire only AFTER the driver re-focused alpha
      // (resetting the counter) and left to beta again — otherwise beta is
      // ALREADY focused here (since :new beta) and the wait below would pass
      // immediately, racing the driver's reset checks.
      _ <- awaitFocusLabel(registry, "alpha") // user typed :switch alpha
      _ <- awaitFocusLabel(registry, "beta") // user typed :switch beta
      _ <- phase.set("a3")
      _ <- party(hub, a, 2, delayMs) // alpha background again → unread 2
      _ <- phase.set("done")
    yield ()

  /** P2U-02 schedule: events for a HIDDEN tab's job and for a job that never
    * had a tab — the registry must grow no phantom tabs.
    */
  private def feedP2u02(
      hub: EventHub,
      registry: SessionRegistry,
      phase: Ref[String],
      delayMs: Int
  ): ZIO[Any, Throwable, Unit] =
    for
      gone <- awaitTab(registry, "gone") // before :close hides it
      _ <- phase.set("wait-hide")
      _ <- awaitCond("await-hide", 60_000L) {
        registry.tabs.map(ts =>
          if ts.forall(_.id != gone.id) then Some(()) else None
        )
      }
      _ <- phase.set("late")
      _ <- party(hub, gone, 3, delayMs) // job keeps running, tab is gone
      _ <- phase.set("phantom")
      _ <- partyJob(
        hub,
        JobId("phantom-never-tab"),
        RootId("phantom-root"),
        "phantom",
        2,
        delayMs
      ) // never had a tab
      _ <- phase.set("done")
    yield ()

  /** P2U-03 schedule: once 3 tabs exist and the user focused t1, fire TWO
    * concurrent Diagnostics bursts into the background tabs t2/t3.
    */
  private def feedP2u03(
      hub: EventHub,
      registry: SessionRegistry,
      phase: Ref[String],
      delayMs: Int
  ): ZIO[Any, Throwable, Unit] =
    for
      _ <- awaitCond("wait3", 60_000L) {
        for
          tabs <- registry.tabs
          focused <- focusLabel(registry)
        yield
          if tabs.map(_.label) == List("t1", "t2", "t3") && focused.contains(
              "t1"
            )
          then Some(())
          else None
      }
      t2 <- awaitTab(registry, "t2")
      t3 <- awaitTab(registry, "t3")
      _ <- phase.set("burst")
      _ <-
        party(hub, t2, BurstEventsPerTab, delayMs) zipPar party(
          hub,
          t3,
          BurstEventsPerTab,
          delayMs
        )
      _ <- phase.set("done")
    yield ()

  // ----- schedule helpers ---------------------------------------------------

  /** N Diagnostics into one tab's job (per-event delay; drop-class payload —
    * the driver asserts hubDroppedDiagnostics == 0, so a drop fails honestly).
    */
  private def party(
      hub: EventHub,
      view: TabView,
      n: Int,
      delayMs: Int
  ): ZIO[Any, Throwable, Unit] =
    partyJob(hub, view.jobId, view.rootId, view.label, n, delayMs)

  private def partyJob(
      hub: EventHub,
      jobId: JobId,
      rootId: RootId,
      label: String,
      n: Int,
      delayMs: Int
  ): ZIO[Any, Throwable, Unit] =
    val sessionId = SessionId("sess-" + label)
    ZIO.foreachDiscard(1 to n) { i =>
      hub
        .publish(
          jobId,
          rootId,
          sessionId,
          EventPayload.Diagnostics(s"unread ev $label $i")
        )
        .catchAll(_ => ZIO.unit) <* ZIO.sleep(
        Duration.fromMillis(delayMs.toLong)
      )
    }

  /** Poll until a tab with this label exists (logically guaranteed: the shell
    * creates it on `:new`; the bound only types a defective hang).
    */
  private def awaitTab(
      registry: SessionRegistry,
      label: String
  ): ZIO[Any, Throwable, TabView] =
    awaitCond(s"awaitTab:$label", 60_000L) {
      registry.tabs.map(_.find(_.label == label))
    }

  private def awaitFocusLabel(
      registry: SessionRegistry,
      label: String
  ): ZIO[Any, Throwable, Unit] =
    awaitCond(s"awaitFocus:$label", 60_000L) {
      focusLabel(registry).map(l =>
        if l.contains(label) then Some(()) else None
      )
    }

  private def focusLabel(registry: SessionRegistry): UIO[Option[String]] =
    for
      focused <- registry.focused
      label <- focused match
        case None     => ZIO.succeed(None)
        case Some(id) => registry.tab(id).option.map(_.map(_.label))
    yield label

  /** Poll an Optional result with real sleeps; fails typed after `boundMs` (the
    * feeder converts it into a stuck phase — an honest FAIL at the driver).
    */
  private def awaitCond[A](name: String, boundMs: Long)(
      cond: UIO[Option[A]]
  ): ZIO[Any, Throwable, A] =
    val deadline = java.lang.System.nanoTime() + boundMs * 1_000_000L
    def loop: ZIO[Any, Throwable, A] =
      cond.flatMap {
        case Some(a) => ZIO.succeed(a)
        case None =>
          if java.lang.System.nanoTime() >= deadline then
            ZIO.fail(new IllegalStateException(s"schedule stuck at $name"))
          else ZIO.sleep(Duration.fromMillis(50)) *> loop
      }
    loop

  // ----- report (the ONE writer; the driver's oracle) -----------------------

  private def reporter(
      wiring: TabShellHost.Wiring,
      phase: Ref[String],
      reportPath: Path
  ): UIO[Unit] =
    (writeReport(wiring, phase, reportPath) *>
      ZIO.sleep(Duration.fromMillis(100))).forever

  /** Dump the registry read model + phase + hub drop counter as one JSON
    * document. Report write failures are intentionally not fatal: the driver
    * validates content and times out on a stale/missing report — a FAIL, never
    * a fake pass.
    */
  private def writeReport(
      wiring: TabShellHost.Wiring,
      phase: Ref[String],
      reportPath: Path
  ): UIO[Unit] =
    for
      views <- wiring.registry.tabs
      ph <- phase.get
      dropped <- wiring.hub.droppedDiagnostics
      tabsJson <- ZIO.foreach(views)(tabJson(wiring.registry))
      json =
        s"""{"app":"raider.repl.TabShellUnreadPtyApp","card":"RAI-105",""" +
          s""""scenario":"$scenario","phase":"$ph",""" +
          s""""hubDroppedDiagnostics":$dropped,""" +
          s""""tsMs":${java.lang.System.currentTimeMillis()},""" +
          s""""tabs":[${tabsJson.mkString(",")}]}"""
      _ <- ZIO.attempt(atomicWrite(reportPath, json)).ignore
    yield ()

  private def tabJson(registry: SessionRegistry)(v: TabView): UIO[String] =
    for draft <- registry.draftGet(v.id).option
    yield s"""{"label":"${jsonEsc(v.label)}",""" +
      s""""tabId":"${jsonEsc(v.id.value)}",""" +
      s""""jobId":"${jsonEsc(v.jobId.value)}",""" +
      s""""status":"${v.status}","lastSeq":${v.lastSeq},""" +
      s""""unread":${v.unreadCount},""" +
      s""""draft":"${jsonEsc(draft.getOrElse(""))}"}"""

  private def atomicWrite(path: Path, content: String): Unit =
    val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
    Files.writeString(tmp, content, StandardCharsets.UTF_8)
    Files.move(
      tmp,
      path,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )

  private def jsonEsc(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => if c < ' ' then f"\\u${c.toInt}%04x" else c.toString
    }

end TabShellUnreadPtyApp
