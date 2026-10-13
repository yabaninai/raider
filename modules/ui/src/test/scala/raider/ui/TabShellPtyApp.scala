package raider.ui

import raider.core.{JobId, SessionId}
import raider.runtime.events.{EventError, EventHub, EventPayload}
import raider.runtime.jobs.JobStatus
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** RAI-062 / P2.C — test-scope PTY harness: the REAL [[TabShell]] (JLine via
  * [[ShellTerminal.jline]]) over a real [[EventHub]] + [[SessionRegistry]],
  * driven under a pseudo-terminal by scripts/quality/tabs_pty_probe.py.
  *
  * This is the same pattern as raider.repl.spike.InlineRendererSpike (RAI-056):
  * test scope only, no product code, no new dependencies. Unlike the spike, the
  * streaming side is the PRODUCT seam: a background feeder watches the registry
  * and, for every tab the user opens with `:new`, publishes a synthetic runtime
  * stream through the hub (one `JobStatusChanged` to Running + N `Diagnostics`
  * lines), so the shell's tab-bar pump delivers the events while the user keeps
  * typing — exactly the ADR-024 path, no `:burst` bypass.
  *
  * Report: the feeder continuously writes the registry read model (per-tab
  * label/ids/status/lastSeq/unread/history/scrollback/draft, hub drop counter,
  * per-tab feed-done flags) to `<argv(0)>` — atomic tmp+rename every tick, so
  * the probe can poll consistent state at any moment. The draft field carries
  * the UI-local draft byte-exact (UTF-8, JSON-escaped): the probe's oracle for
  * draft integrity. `feedDone` means this app's emitter for the tab finished.
  *
  * There is deliberately no `:feed` command: TabShell owns the loop and its
  * palette is frozen (RAI-061); auto-start feeding needs no interception.
  */
object TabShellPtyApp:

  /** Diagnostics lines streamed per tab (the card's "200+ events"). */
  val EventsPerTab: Int = 220

  /** Inter-event delay: 220 * 15ms ≈ 3.3s of streaming per tab, staggered by
    * tab creation, so the probe's two typing tours overlap active streams.
    */
  val DefaultDelayMs: Int = 15

  /** Diagnostics payload — byte-exact scrollback oracle for the probe. */
  val Payload: String = "x" * 32

  private def eventLine(label: String, i: Int): String =
    s"ev $label $i payload $Payload"

  def main(args: Array[String]): Unit =
    val reportPath =
      Paths.get(if args.nonEmpty then args(0) else "tabs-pty-report.json")
    val delayMs =
      Option(java.lang.System.getenv("TABS_PTY_DELAY_MS"))
        .flatMap(_.toIntOption)
        .getOrElse(DefaultDelayMs)
    val program =
      ZIO.scoped {
        for
          hub <- EventHub.make()
          registry <- SessionRegistry.make(hub)
          fed <- Ref.make(Map.empty[JobId, Boolean])
          terminal <- ShellTerminal.jline
          _ <- feederLoop(hub, registry, fed, reportPath, delayMs).fork
          resolver = CloseChoiceResolver.fromTerminal(
            terminal,
            TabShell.Config().closePrompt
          )
          _ <- TabShell.run(hub, registry, terminal, resolver)
          // Final consistent snapshot while hub/registry are still alive.
          _ <- writeReport(hub, registry, fed, reportPath)
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
        _ <- writeReport(hub, registry, fed, reportPath)
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
    * Report write failures are intentionally not fatal: the probe validates
    * content and times out on a stale/missing report — a FAIL, never a fake
    * pass.
    */
  private def writeReport(
      hub: EventHub,
      registry: SessionRegistry,
      fed: Ref[Map[JobId, Boolean]],
      reportPath: Path
  ): UIO[Unit] =
    for
      views <- registry.tabs
      m <- fed.get
      tabsJson <- ZIO.foreach(views)(tabJson(registry, m))
      dropped <- hub.droppedDiagnostics
      allDone = views.nonEmpty && views.forall(v =>
        m.get(v.jobId).contains(true)
      )
      json =
        s"""{"app":"raider.ui.TabShellPtyApp","card":"RAI-062",""" +
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
      hist <- registry.history(v.id).option
      scr <- registry.scrollback(v.id).option
    yield
      val lines = scr.fold(Vector.empty[String])(_.lines)
      val items = lines.map(l => "\"" + jsonEsc(l) + "\"").mkString(",")
      s"""{"label":"${jsonEsc(v.label)}",""" +
        s""""tabId":"${jsonEsc(v.id.value)}",""" +
        s""""jobId":"${jsonEsc(v.jobId.value)}",""" +
        s""""rootId":"${jsonEsc(v.rootId.value)}",""" +
        s""""status":"${v.status}","lastSeq":${v.lastSeq},""" +
        s""""unread":${v.unreadCount},""" +
        s""""historyEntries":${hist.fold(0)(_.entries.size)},""" +
        s""""historyEvicted":${hist.fold(0L)(_.evicted)},""" +
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

end TabShellPtyApp
