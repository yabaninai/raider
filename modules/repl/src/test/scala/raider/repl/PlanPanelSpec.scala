package raider.repl

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.jobs.JobManager
import raider.runtime.loop.{
  AgentLoop,
  PlanApprovalError,
  PlanMode,
  PlanSession,
  PlanProposal
}
import raider.testkit.ScriptedSequenceBackend
import raider.ui.ShellTerminal
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/** RAI-110 behavioral acceptance (unit level; the PTY layer is PlanTabPtySpec):
  * the PlanPanel edges over a REAL PlanSession + JobManager + AgentLoop driven
  * by deterministic scripted fixtures (fixtureMode asserted by the backend; no
  * network, no spend).
  *
  * Covered here: PLANUI-01 approval semantics (exactly ONE execution job via
  * the interceptor), PLANUI-02 typed refusals (NoPlan / Cancelled /
  * AlreadyApproved / NotRunning / BadUsage; shell alive; repeated plan
  * possible). The PLANUI-03 streaming-vs-view discipline is structural here
  * (ONE writer: every outcome is ONE printAbove block) and asserted under a
  * real PTY in PlanTabPtySpec.
  */
object PlanPanelSpec extends ZIOSpecDefault:

  private val a = AttemptId("plan-ui-att")

  private val validPlanJson =
    """{"title":"Ship it","steps":["read the file","write the test"]}"""

  private val validProposal =
    PlanProposal("Ship it", Vector("read the file", "write the test"))

  /** Structured per-attempt capture is preserved in the redirected sbt log. */
  private def capture(event: String, fields: (String, String)*): UIO[Unit] =
    val json =
      (Vector("event" -> event) ++ fields)
        .map { case (key, value) =>
          s"\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }
        .mkString("{", ",", "}")
    ZIO.succeed(println(s"PLANUI02_CAPTURE $json"))

  private def jsonEscape(value: String): String =
    value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }

  /** Test-only observation of the read model's existing private outcomeRef. */
  private def outcomeRefValue(rig: Rig): String =
    val field = rig.panel.getClass.getDeclaredFields
      .find(_.getName.contains("outcomeRef"))
    field match
      case None => "unavailable:field-not-found"
      case Some(refField) =>
        refField.setAccessible(true)
        refField.get(rig.panel) match
          case ref: AtomicReference[?] => String.valueOf(ref.get())
          case other =>
            s"unavailable:${Option(other).fold("null")(_.getClass.getName)}"

  private def limits: BudgetLimits =
    BudgetLimits.make(maxAttempts = 16).fold(e => throw e, identity)

  /** The admission-facing form: Admission.make validates the Either itself. */
  private def limitTry: Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(maxAttempts = 16)

  private def planEvent(json: String) = Vector[ModelEvent](
    ModelEvent.Started(a),
    ModelEvent.TextDelta(a, json),
    ModelEvent.Finished(a, "stop")
  )

  /** Test terminal: scripted input lines, recorded printed blocks. */
  private final class ScriptedShellTerminal(
      inputs: Queue[String],
      printed: Ref[Vector[String]]
  ) extends ShellTerminal:

    def printAbove(lines: Vector[String]): UIO[Unit] =
      printed.update(_ ++ lines)

    def readLine(prompt: String): zio.Task[Option[String]] =
      inputs.take.map(Some(_))

    def close: UIO[Unit] = ZIO.unit

  private final case class Rig(
      panel: PlanPanel,
      session: PlanSession,
      manager: JobManager,
      terminal: ShellTerminal,
      inputs: Queue[String],
      printed: Ref[Vector[String]]
  ):
    def printedText: UIO[String] = printed.get.map(_.mkString("\n"))

    /** Waits until the transcript contains `substr` (bounded; honest bool). */
    def awaitPrinted(substr: String): ZIO[Any, Nothing, Boolean] =
      printedText
        .repeatUntil(_.contains(substr))
        .timeout(10.seconds)
        .map(_.isDefined)

    def labels: UIO[List[String]] = manager.adminView.map(_.map(_.label))

  private def rig(scripts: Vector[ModelEvent]*): ZIO[Any, RaiderError, Rig] =
    for
      backend <- ScriptedSequenceBackend(scripts*)
      r <- rigOver(backend)
    yield r

  private def rigOver(backend: ModelBackend): ZIO[Any, RaiderError, Rig] =
    for
      adm <- Admission.make(limitTry)
      manager <- JobManager.make()
      root = AgentLoop.newRoot()
      dir <- ZIO.attempt(Files.createTempDirectory("rai110-panel")).orDie
      loop = AgentLoop(
        backend,
        ToolRegistry.empty,
        "m",
        adm,
        root,
        CostEstimate.Unknown,
        planMode = Some(PlanMode.of(ToolRegistry.empty, dir, 2))
      )
      session <- PlanSession.make(loop, manager)
      panel <- PlanPanel.make(session, limits, 8)
      inputs <- Queue.unbounded[String]
      printed <- Ref.make(Vector.empty[String])
    yield Rig(
      panel,
      session,
      manager,
      // the INTERCEPTING terminal (the product wiring): plan commands are
      // consumed here and never reach the frozen router (RAI-110)
      panel.commandTerminal(ScriptedShellTerminal(inputs, printed)),
      inputs,
      printed
    )

  /** The documented STARTER duty: start one plan job and fork its watcher. */
  private def startTracked(rig: Rig, message: String): UIO[Unit] =
    rig.session
      .startPlan(List(RequestMessage("user", message)), limits, 8)
      .orDie
      .flatMap(job => rig.panel.track(job).forkDaemon.unit)

  /** Type raw lines into the interceptor and return the FIRST line the shell
    * would see (plan commands are consumed; a trailing "" sentinel passes
    * through byte-exact and unblocks the read).
    */
  private def typeThrough(
      rig: Rig,
      raws: String*
  ): ZIO[Any, Throwable, String] =
    rig.inputs.offerAll(raws.toList) *>
      rig.terminal.readLine("raider> ").flatMap {
        case Some(line) => ZIO.succeed(line)
        case None       => ZIO.fail(new IllegalStateException("unexpected EOF"))
      }

  /** Poll the panel snapshot until the phase matches (the watcher refreshes the
    * AtomicReference in the background; bounded honest bool).
    */
  private def awaitPhase(
      rig: Rig,
      phase: PlanTab.Phase
  ): ZIO[Any, Nothing, Boolean] =
    ZIO
      .succeed(rig.panel.snapshot.phase)
      .repeatUntil(_ == phase)
      .timeout(10.seconds)
      .map(_.isDefined)

  def spec = suite("PlanPanelSpec (RAI-110: plan tab + approval edges)")(
    suite("PLANUI-01 approval -> exactly one execution job")(
      test(
        ":approve through the interceptor starts exactly ONE execution job; outcome line printed; snapshot approved"
      ) {
        live {
          for
            r <- rig(planEvent(validPlanJson), planEvent("\"done\""))
            _ <- startTracked(r, "plan the change")
            ready <- awaitPhase(r, PlanTab.Phase.Ready)
            passthrough <- typeThrough(r, ":approve", ":tabs")
            approvedPrinted <- r.awaitPrinted("plan approved: execution job ")
            labelsAfter <- r.labels
            snap = r.panel.snapshot
            execDone <- r.session.state.flatMap {
              case PlanSession.Approved(exec, _) =>
                r.manager.await(exec).map(Some(_))
              case _ => ZIO.succeed(None)
            }
          yield assertTrue(
            ready,
            passthrough == ":tabs", // ONLY the plan command was consumed
            approvedPrinted,
            labelsAfter.sorted == List("plan", "plan-exec"),
            snap.phase == PlanTab.Phase.Approved,
            snap.proposal.contains(validProposal),
            snap.execution.isDefined,
            execDone.contains("\"done\"")
          )
        }
      },
      test(
        "a second :approve is a typed AlreadyApproved and starts no second job"
      ) {
        live {
          for
            r <- rig(planEvent(validPlanJson), planEvent("\"done\""))
            _ <- startTracked(r, "plan the change")
            _ <- awaitPhase(r, PlanTab.Phase.Ready)
            passthrough <- typeThrough(r, ":approve", ":approve", ":tabs")
            approved <- r.awaitPrinted("plan approved: execution job ")
            denied <- r.awaitPrinted("hint: plan already approved")
            labels <- r.labels
          yield assertTrue(
            passthrough == ":tabs",
            approved && denied,
            labels.count(_ == "plan-exec") == 1
          )
        }
      }
    ),
    suite("PLANUI-02 typed refusals; shell alive; repeated plan possible")(
      test(
        ":reject cancels the LIVE plan; approve-after-cancel is typed Cancelled; a repeated plan reaches approval"
      ) {
        live {
          for
            gate <- Promise.make[Nothing, Unit]
            underlying <- ScriptedSequenceBackend(
              planEvent(validPlanJson), // the repeated plan (call 2)
              planEvent("\"done\"") // the execution (call 3)
            )
            backend = GatedBackend(underlying, gate)
            r <- rigOver(backend)
            // plan1 parks inside dispatch (deterministic LIVE job)
            planJob <- r.session
              .startPlan(List(RequestMessage("user", "p1")), limits, 8)
              .orDie
            _ <- r.panel.track(planJob).forkDaemon.unit
            _ <- backend.requests
              .repeatUntil(_.size == 1)
              .timeout(Duration.fromSeconds(10))
            // reject the live plan through the interceptor
            seenAfterReject <- typeThrough(r, ":reject", "")
            rejectedPrinted <- r.awaitPrinted(
              "plan rejected: plan phase cancelled"
            )
            // RAI-119 determinism (precedent 71d0aa3, quiescence drain): the
            // printed outcome proves the CANCEL command ran; the interrupted
            // job's typed settlement (and the panel watcher's outcome write
            // that consumes it) can still be in flight when the NEXT plan
            // stage starts. Awaiting the cancelled job's terminal outcome
            // here drains that pipeline BEFORE the repeated plan exists —
            // two daemon watchers can then no longer interleave their
            // one-shared-outcome writes around the second track.
            cancelledSettled <- r.session.awaitPlan(planJob).either.map {
              case Left(_)  => true // typed cancellation surfaced
              case Right(_) => false // live plan settled as approval: never
            }
            st <- r.session.state
            // cancel != approval: approve is typed-denied
            _ <- typeThrough(r, ":approve", "")
            cancelDenied <- r.awaitPrinted(
              "hint: plan phase was cancelled: cancel is not approval"
            )
            noExec <- r.labels.map(!_.contains("plan-exec"))
            // a repeated plan is possible from Cancelled
            planJob2 <- r.session
              .startPlan(List(RequestMessage("user", "p2")), limits, 8)
              .orDie
            _ <- r.panel.track(planJob2).forkDaemon.unit
            _ <- capture(
              "plan2_started",
              "planJobId" -> planJob2.id.value,
              "sessionState" -> "Planning",
              "outcomeRef" -> outcomeRefValue(r)
            )
            settlement <- r.session.awaitPlan(planJob2).either
            _ <- capture(
              "plan2_settled",
              "planJobId" -> planJob2.id.value,
              "settlement" -> settlement.fold(_.toString, _.toString),
              "sessionState" -> "Planning",
              "outcomeRef" -> outcomeRefValue(r)
            )
            ready2 <- awaitPhase(r, PlanTab.Phase.Ready)
            _ <- capture(
              "panel_refresh_observed",
              "planJobId" -> planJob2.id.value,
              "phase" -> r.panel.snapshot.phase.toString,
              "ready2" -> ready2.toString,
              "outcomeRef" -> outcomeRefValue(r)
            )
            // RAI-119 determinism (precedent 91a6575, retry with a FRESH
            // render): the watcher refreshes the snapshot in the background;
            // under load the last refresh can lag the settled plan. On a miss
            // we force a fresh re-derive (panel.refresh) and wait again — the
            // assertion is NOT weakened (Phase.Ready must still hold).
            ready2Fresh <-
              if ready2 then ZIO.succeed(true)
              else
                r.panel.refresh *>
                  capture(
                    "manual_refresh",
                    "planJobId" -> planJob2.id.value,
                    "phase" -> r.panel.snapshot.phase.toString,
                    "outcomeRef" -> outcomeRefValue(r)
                  ) *> awaitPhase(r, PlanTab.Phase.Ready)
            _ <- typeThrough(r, ":approve", "")
            approved2 <- r.awaitPrinted("plan approved: execution job ")
            labels <- r.labels
            stateAfterApproval <- r.session.state
            transcriptAfterApproval <- r.printedText
            _ <- capture(
              "approval_outcome",
              "planJobId" -> planJob2.id.value,
              "approvalLine" -> transcriptAfterApproval.linesIterator
                .find(_.contains("plan approved: execution job "))
                .getOrElse("approval-denied-or-no-line"),
              "approvalPrintedApproved" -> approved2.toString,
              "ready2Fresh" -> ready2Fresh.toString,
              "approved2" -> approved2.toString,
              "sessionState" -> stateAfterApproval.toString,
              "labels" -> labels.mkString(","),
              "planExecPresent" -> labels.contains("plan-exec").toString,
              "outcomeRef" -> outcomeRefValue(r)
            )
          yield assertTrue(
            seenAfterReject == "",
            rejectedPrinted,
            cancelledSettled,
            st == PlanSession.Cancelled,
            cancelDenied,
            noExec,
            ready2Fresh,
            approved2,
            labels.sorted == List("plan", "plan", "plan-exec")
          )
        }
      },
      test(":approve from a virgin session is typed NoPlan; nothing starts") {
        live {
          for
            r <- rig(planEvent(validPlanJson))
            passthrough <- typeThrough(r, ":approve", "")
            denied <- r.awaitPrinted("hint: no approved plan to execute")
            labels <- r.labels
          yield assertTrue(passthrough == "", denied, labels.isEmpty)
        }
      },
      test(":reject with no live plan is the typed NotRunning hint") {
        live {
          for
            r <- rig(planEvent(validPlanJson))
            passthrough <- typeThrough(r, ":reject", "")
            hinted <- r.awaitPrinted(
              "hint: reject: no live plan phase in this session"
            )
            labels <- r.labels
          yield assertTrue(passthrough == "", hinted, labels.isEmpty)
        }
      },
      test(
        "argument-bearing plan commands are consumed with a typed usage hint; nothing starts"
      ) {
        live {
          for
            r <- rig(planEvent(validPlanJson))
            passthrough <- typeThrough(r, ":approve now", ":reject all", "")
            usage1 <- r.awaitPrinted(PlanPanel.usageLine(PlanCommand.Approve))
            usage2 <- r.awaitPrinted(PlanPanel.usageLine(PlanCommand.Reject))
            labels <- r.labels
          yield assertTrue(
            passthrough == "",
            usage1 && usage2,
            labels.isEmpty
          )
        }
      },
      test("not-plan lines pass through byte-exact with no printed block") {
        live {
          for
            r <- rig(planEvent(validPlanJson))
            passthrough <- typeThrough(r, "  :tabs  ")
            printedText <- r.printedText
          yield assertTrue(
            passthrough == "  :tabs  ", // byte-exact (SHELL-01 discipline)
            printedText.isEmpty
          )
        }
      }
    ),
    suite("typed outcome lines (pure renderers)")(
      test("approvalLines/rejectLines/usageLine render the ONE-line blocks") {
        val approved = PlanPanel.approvalLines(
          PlanPanel.ApprovalOutcome.Approved("job-9")
        )
        val denied = PlanPanel.approvalLines(
          PlanPanel.ApprovalOutcome.Denied(
            PlanApprovalError.AlreadyApproved("job-9")
          )
        )
        val rejected = PlanPanel.rejectLines(PlanPanel.RejectOutcome.Cancelled)
        val notRunning =
          PlanPanel.rejectLines(PlanPanel.RejectOutcome.NotRunning)
        val usage = PlanPanel.usageLine(PlanCommand.Approve)
        assertTrue(
          approved.size == 1 &&
            approved.head.startsWith("plan approved: execution job job-9 "),
          denied == Vector(
            "hint: plan already approved: execution job job-9 exists (exactly-once)"
          ),
          rejected.size == 1 && rejected.head.startsWith("plan rejected:"),
          notRunning == Vector(
            "hint: reject: no live plan phase in this session"
          ),
          usage == "hint: usage: :approve (the command takes no arguments)"
        )
      }
    )
  )

  /** Deterministic "inside dispatch" backend (the RAI-107 acceptance pattern):
    * records the request, then parks on the gate — a live plan job a cancel can
    * actually reach.
    */
  private final class GatedBackend(
      underlying: ScriptedSequenceBackend,
      gate: Promise[Nothing, Unit]
  ) extends ModelBackend:

    private val seen = Unsafe.unsafe { implicit u =>
      Ref.unsafe.make(Vector.empty[ModelRequest])
    }

    private val arrivals = Unsafe.unsafe { implicit u =>
      Ref.unsafe.make(0)
    }

    def capabilities: ModelCapabilities = underlying.capabilities

    // Only the FIRST request parks on the gate (the deterministic LIVE plan
    // that :reject cancels); later requests (the repeated plan, the execution)
    // pass through — the gate is never opened, so an unparked-by-design stream
    // is what makes the repeated plan reachable (coordinator fix).
    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.fromZIO(
        seen.update(_ :+ input) *>
          arrivals
            .updateAndGet(_ + 1)
            .flatMap(n => if n == 1 then gate.await else ZIO.unit)
      ) *>
        underlying.stream(input)

    def requests: ZIO[Any, Nothing, Vector[ModelRequest]] = seen.get

end PlanPanelSpec
