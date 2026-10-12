package raider.repl

import raider.core.{AttemptId, JobId, ModelEvent, RequestMessage, SessionId}
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.events.{EventError, EventHub, EventPayload}
import raider.runtime.jobs.JobManager
import raider.runtime.jobs.JobStatus
import raider.runtime.loop.{AgentLoop, PlanMode, PlanSession}
import raider.core.ToolRegistry
import raider.testkit.ScriptedSequenceBackend
import raider.ui.TabView
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** RAI-110 (ADR-028 §3) — test-scope PTY harness for the PRODUCT tab shell with
  * the ONE plan tab: raider.repl.TabShellHost.run(wiring, panel) — the same
  * product call path as RAI-102, extended by the accepted PlanPanel composition
  * (the aux provider + the :approve/:reject interceptor over the accepted
  * RAI-107 PlanSession). Driven under a pseudo-terminal by PlanTabsPtySpec.
  *
  * Deterministic scripted fixtures only (no network, no paid calls): the plan
  * phase completes from a recorded JSON proposal; the approved execution
  * answers a scripted "done". A test-only feeder (the accepted RAI-102 pattern)
  * streams synthetic tab events through the REAL hub so PLANUI-03 can overlap
  * typing with a live burst.
  *
  * Report: continuous atomic JSON (panel phase/proposal/execution/rejection,
  * the JobManager labels, the hub drop counter) — the driver's oracle. A tab is
  * fed at most once per process (the feeder's job is ambient load, not a
  * feed-done oracle).
  */
object PlanTabsPtyApp:

  private val attempt = AttemptId("plan-pty-att")

  private val validPlanJson =
    """{"title":"Ship it","steps":["read the file","write the test"]}"""

  val EventsPerTab: Int = 220
  val DelayMs: Int = 15
  val Payload: String = "x" * 32

  private def planEvent(json: String): Vector[ModelEvent] = Vector(
    ModelEvent.Started(attempt),
    ModelEvent.TextDelta(attempt, json),
    ModelEvent.Finished(attempt, "stop")
  )

  private def setPtySize(rows: Int, cols: Int): Unit =
    try
      new ProcessBuilder("stty", "rows", rows.toString, "cols", cols.toString)
        .inheritIO()
        .start()
        .waitFor()
    catch case _: Exception => ()

  def main(args: Array[String]): Unit =
    val reportPath =
      Paths.get(if args.nonEmpty then args(0) else "plan-tabs-pty-report.json")
    setPtySize(rows = 40, cols = 120)
    val program =
      ZIO.scoped {
        for
          limits <- ZIO.fromEither(BudgetLimits.make(maxAttempts = 16)).orDie
          adm <- Admission.make(BudgetLimits.make(maxAttempts = 16)).orDie
          backend <- ScriptedSequenceBackend(
            planEvent(validPlanJson), // call 1: the plan proposal
            planEvent(validPlanJson), // call 2: a repeated plan (PLANUI-02)
            planEvent("\"done\"") // call 3: the approved execution answer
          )
          planDir <- ZIO.attempt(Files.createTempDirectory("rai110-pty-plan"))
          root = AgentLoop.newRoot()
          loop = AgentLoop(
            backend,
            ToolRegistry.empty,
            "m",
            adm,
            root,
            CostEstimate.Unknown,
            planMode = Some(PlanMode.of(ToolRegistry.empty, planDir, 2))
          )
          manager <- JobManager.make()
          session <- PlanSession.make(loop, manager)
          panel <- PlanPanel.make(session, limits, 8)
          wiring <- TabShellHost.wiring
          // STARTER duty (RAI-107 doc): startPlan → track — the watcher
          // publishes the settled proposal into the tab read model.
          planJob <- session
            .startPlan(
              List(RequestMessage("user", "plan the change")),
              limits,
              8
            )
            .orDie
          _ <- panel.track(planJob).forkDaemon
          // Ambient synthetic streams (RAI-102 feeder pattern, PLANUI-03).
          _ <- feederLoop(wiring, reportPath, panel, manager).fork
          _ <- TabShellHost.run(wiring, panel)
          _ <- writeReport(wiring, panel, manager, reportPath)
        yield ()
      }
    zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(program).getOrThrow()
    }

  private val fedRef: Ref[Set[JobId]] = Unsafe.unsafe { implicit u =>
    Ref.unsafe.make(Set.empty[JobId])
  }

  private def feederLoop(
      wiring: TabShellHost.Wiring,
      reportPath: Path,
      panel: PlanPanel,
      manager: JobManager
  ): ZIO[Scope, Nothing, Unit] =
    tick(wiring, reportPath, panel, manager).forever

  private def tick(
      wiring: TabShellHost.Wiring,
      reportPath: Path,
      panel: PlanPanel,
      manager: JobManager
  ): ZIO[Scope, Nothing, Unit] =
    for
      views <- wiring.registry.tabs
      fresh <- fedRef.modify { m =>
        val nu = views.filterNot(v => m.contains(v.jobId))
        (nu, m ++ nu.map(_.jobId))
      }
      _ <- ZIO.foreachDiscard(fresh) { v =>
        emitter(wiring.hub, v).catchAll(_ => ZIO.unit).fork
      }
      _ <- writeReport(wiring, panel, manager, reportPath)
      _ <- ZIO.sleep(Duration.fromMillis(150))
    yield ()

  private def emitter(
      hub: EventHub,
      view: TabView
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
            EventPayload.Diagnostics(s"ev ${view.label} $i payload $Payload")
          ) <* ZIO.sleep(Duration.fromMillis(DelayMs.toLong))
      }

  private def writeReport(
      wiring: TabShellHost.Wiring,
      panel: PlanPanel,
      manager: JobManager,
      reportPath: Path
  ): UIO[Unit] =
    for
      snap <- ZIO.succeed(panel.snapshot)
      labels <- manager.adminView.map(_.map(_.label).sorted)
      dropped <- wiring.hub.droppedDiagnostics
      phase = snap.phase.toString.toLowerCase
      proposalJson = snap.proposal
        .map(p =>
          s""""title":"${jsonEsc(p.title)}",""" +
            s""""steps":[${p.steps
                .map(s => "\"" + jsonEsc(s) + "\"")
                .mkString(",")}],"""
        )
        .getOrElse("")
      executionJson = snap.execution
        .map(e => s""""execution":"${jsonEsc(e)}",""")
        .getOrElse("")
      rejectionJson = snap.rejection
        .map(r => s""""rejection":"${jsonEsc(r)}",""")
        .getOrElse("")
      labelsJson = labels.map(l => "\"" + jsonEsc(l) + "\"").mkString(",")
      json =
        s"""{"app":"raider.repl.PlanTabsPtyApp","card":"RAI-110",""" +
          s""""phase":"$phase",$proposalJson$executionJson$rejectionJson""" +
          s""""labels":[$labelsJson],"hubDroppedDiagnostics":$dropped,""" +
          s""""tsMs":${java.lang.System.currentTimeMillis()}}"""
      _ <- ZIO.attempt(atomicWrite(reportPath, json)).ignore
    yield ()

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

end PlanTabsPtyApp
