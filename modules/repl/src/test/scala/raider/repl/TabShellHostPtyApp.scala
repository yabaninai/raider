package raider.repl

import raider.core.{JobId, SessionId}
import raider.runtime.events.{EventError, EventHub, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.ui.{SessionRegistry, TabView}
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** RAI-102 / P2W — test-scope PTY harness for the PRODUCT TabShell host:
  * raider.repl.TabShellHost (the real product wiring: ShellTerminal.jline +
  * EventHub + SessionRegistry + the accepted raider.ui.TabShell) driven under a
  * pseudo-terminal by the Scala PTY driver in TabShellHostPtySpec.
  *
  * Same pattern as raider.ui.TabShellPtyApp (RAI-062), moved to the product
  * module: test scope only, no product code imports test classes (the
  * dependency direction is the other way around — this app CALLS the product
  * `TabShellHost.wiring`/`TabShellHost.run`). The streaming side is the PRODUCT
  * seam: a test-only feeder watches the registry and, for every tab the user
  * opens with `:new`, publishes a synthetic runtime stream through the REAL hub
  * (one JobStatusChanged to Running + N Diagnostics lines) — the ADR-024 path,
  * no `:burst` bypass.
  *
  * Report: continuously writes the registry read model to `<argv(0)>` (atomic
  * tmp+rename every tick) — the driver's oracle for draft integrity, event
  * counts, scrollback losslessness and the hub drop counter. `feedDone` means
  * this app's emitter for the tab finished.
  */
object TabShellHostPtyApp:

  /** Diagnostics lines streamed per tab (the card's burst/async event load). */
  val EventsPerTab: Int = 220

  /** Inter-event delay: overridden per test via RAI102_PTY_DELAY_MS (P2W-03
    * shrinks it so the burst overlaps active typing).
    */
  val DefaultDelayMs: Int = 15

  /** Diagnostics payload — byte-exact scrollback oracle for the driver. */
  val Payload: String = "x" * 32

  private def eventLine(label: String, i: Int): String =
    s"ev $label $i payload $Payload"

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
      Paths.get(if args.nonEmpty then args(0) else "tabs-host-pty-report.json")
    val delayMs =
      Option(java.lang.System.getenv("RAI102_PTY_DELAY_MS"))
        .flatMap(_.toIntOption)
        .getOrElse(DefaultDelayMs)
    setPtySize(rows = 40, cols = 120)
    val program =
      ZIO.scoped {
        for
          wiring <- TabShellHost.wiring
          fed <- Ref.make(Map.empty[JobId, Boolean])
          // Test-only synthetic runtime: real job streams are published into
          // the same hub by the runtime (ADR-024); the feeder emulates them.
          _ <- feederLoop(
            wiring.hub,
            wiring.registry,
            fed,
            reportPath,
            delayMs
          ).fork
          _ <- TabShellHost.run(wiring)
          // Final consistent snapshot while hub/registry are still alive.
          _ <- writeReport(wiring, fed, reportPath)
        yield ()
      }
    zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(program).getOrThrow()
    }
    // Explicit: hub forwarders are process-lifetime daemons (no stop());
    // returning from main exits the JVM with code 0 (forbidden-apis clean).

  /** Watch the registry; start ONE emitter per unseen tab job; refresh the
    * report every tick. Scoped: everything dies with the shell's scope.
    */
  private def feederLoop(
      hub: EventHub,
      registry: SessionRegistry,
      fed: Ref[Map[JobId, Boolean]],
      reportPath: Path,
      delayMs: Int
  ): ZIO[Scope, Nothing, Unit] = {
    val tick =
      for
        views <- registry.tabs
        fresh <- ZIO.foldLeft(views)(List.empty[TabView]) { (acc, v) =>
          fed
            .modify { m =>
              if m.contains(v.jobId) then (None, m)
              else (Some(v), m + (v.jobId -> false))
            }
            .map(_.fold(acc)(v => acc :+ v))
        }
        _ <- ZIO.foreachDiscard(fresh) { v =>
          emitter(hub, v, delayMs)
            .catchAll(_ => ZIO.unit) // the report's lastSeq is the oracle
            .ensuring(fed.update(m => m.updated(v.jobId, true)))
            .fork
        }
        _ <- writeReport(TabShellHost.Wiring(hub, registry), fed, reportPath)
        _ <- ZIO.sleep(Duration.fromMillis(150))
      yield ()
    tick.forever
  }

  /** One synthetic runtime stream for one tab: Running + N Diagnostics lines
    * (never a Terminal — the tab stays Running like a live job would).
    */
  private def emitter(
      hub: EventHub,
      view: TabView,
      delayMs: Int
  ): ZIO[Any, EventError, Unit] =
    val sessionId = SessionId("sess-" + view.jobId.value)
    hub
      .publish(
        view.jobId,
        view.rootId,
        sessionId,
        EventPayload.JobStatusChanged(
          JobStatus.Queued,
          JobStatus.Running,
          None
        )
      ) *>
      ZIO.foreachDiscard(1 to EventsPerTab) { i =>
        hub
          .publish(
            view.jobId,
            view.rootId,
            sessionId,
            EventPayload.Diagnostics(eventLine(view.label, i))
          ) <* ZIO.sleep(Duration.fromMillis(delayMs.toLong))
      }

  /** Dump the registry read model + hub drop counter as one JSON document.
    * Report write failures are intentionally not fatal: the driver validates
    * content and times out on a stale/missing report — a FAIL, never a fake
    * pass.
    */
  private def writeReport(
      wiring: TabShellHost.Wiring,
      fed: Ref[Map[JobId, Boolean]],
      reportPath: Path
  ): UIO[Unit] =
    for
      views <- wiring.registry.tabs
      m <- fed.get
      tabsJson <- ZIO.foreach(views)(tabJson(wiring.registry, m))
      dropped <- wiring.hub.droppedDiagnostics
      allDone = views.nonEmpty && views.forall(v =>
        m.get(v.jobId).contains(true)
      )
      json =
        s"""{"app":"raider.repl.TabShellHostPtyApp","card":"RAI-102",""" +
          s""""eventsPerTab":$EventsPerTab,"allFeedsDone":$allDone,""" +
          s""""hubDroppedDiagnostics":$dropped,""" +
          s""""tsMs":${java.lang.System.currentTimeMillis()},""" +
          s""""tabs":[${tabsJson.mkString(",")}]}"""
      _ <- ZIO.attempt(atomicWrite(reportPath, json)).ignore
    yield ()

  private def tabJson(
      registry: SessionRegistry,
      fed: Map[JobId, Boolean]
  )(v: TabView): UIO[String] =
    for
      draft <- registry.draftGet(v.id).option
      scr <- registry.scrollback(v.id).option
    yield
      val lines = scr.fold(Vector.empty[String])(_.lines)
      val items = lines.map(l => "\"" + jsonEsc(l) + "\"").mkString(",")
      s"""{"label":"${jsonEsc(v.label)}",""" +
        s""""tabId":"${jsonEsc(v.id.value)}",""" +
        s""""jobId":"${jsonEsc(v.jobId.value)}",""" +
        s""""status":"${v.status}","lastSeq":${v.lastSeq},""" +
        s""""unread":${v.unreadCount},""" +
        s""""scrollbackLines":${lines.size},""" +
        s""""scrollbackEvicted":${scr.fold(0L)(_.evicted)},""" +
        s""""scrollback":[$items],""" +
        s""""draft":"${jsonEsc(draft.getOrElse(""))}",""" +
        s""""feedDone":${fed.get(v.jobId).contains(true)}}"""

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

end TabShellHostPtyApp
