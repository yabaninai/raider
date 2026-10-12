package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.jobs.{JobManager, JobStatus}
import raider.testkit.ScriptedSequenceBackend
import zio.json.EncoderOps
import PlanProposal.given
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, Promise, Ref, Scope, ZIO}
import zio.stream.ZStream

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** RAI-107 (ADR-028) acceptance: PLAN-01 read-only enforcement by the frozen
  * RecoveryClass, PLAN-02 structured PlanProposal with bounded repair and
  * atomic persistence, PLAN-03 explicit approval transition with exactly-one
  * execution job and bounded cancel cleanup. Deterministic scripted fixtures
  * only (fixtureMode asserted by the backend); no network, no spend.
  */
object AgentLoopPlanModeSpec extends ZIOSpecDefault:

  private val a = AttemptId("plan-att")
  private val c1 = ToolCallId("call-1")
  private val c2 = ToolCallId("call-2")
  private val c3 = ToolCallId("call-3")

  private def limits(
      attempts: Int = 16
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(maxAttempts = attempts)

  private def lims(attempts: Int = 16): BudgetLimits =
    limits(attempts).fold(e => throw e, identity)

  private def registryOf(tools: Tool*): ToolRegistry =
    ToolRegistry.of(tools*).fold(e => throw e, identity)

  private def planTempDir(): Path =
    Files.createTempDirectory("raider-plan-test")

  private def planEvent(json: String) = Vector[ModelEvent](
    ModelEvent.Started(a),
    ModelEvent.TextDelta(a, json),
    ModelEvent.Finished(a, "stop")
  )

  private def toolRoundEvent(callId: ToolCallId, tool: String, args: String) =
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.ToolCallReady(a, callId, tool, args),
      ModelEvent.Finished(a, "tool_round")
    )

  private val validPlanJson =
    """{"title":"Ship it","steps":["read the file","write the test"]}"""

  private def newPlanLoop(
      backend: ModelBackend,
      registry: ToolRegistry,
      adm: Admission,
      root: RootId,
      dir: Path,
      maxRepair: Int = 2,
      est: CostEstimate = CostEstimate.Unknown
  ): AgentLoop =
    AgentLoop(
      backend,
      registry,
      "m",
      adm,
      root,
      est,
      planMode = Some(PlanMode.of(registry, dir, maxRepair))
    )

  def spec = suite("AgentLoop plan mode RAI-107")(
    suite("PLAN-01 read-only enforcement (frozen RecoveryClass)")(
      test(
        "Mutating/Idempotent calls → typed RA-TOOLDENY feedback, loop alive; ReadOnly executes; model sees only ReadOnly defs"
      ) {
        for
          dir <- ZIO.attempt(planTempDir())
          writeCount <- Ref.make(0)
          touchCount <- Ref.make(0)
          lookupCount <- Ref.make(0)
          writer = new Tool:
            def name = "write_file"; def version = 1; def description = ""
            def recovery = RecoveryClass.Mutating; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              writeCount.update(_ + 1).as("""{"written":true}""")
          toucher = new Tool:
            def name = "touch_file"; def version = 1; def description = ""
            def recovery = RecoveryClass.Idempotent; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              touchCount.update(_ + 1).as("""{"touched":true}""")
          lookup = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              lookupCount.update(_ + 1).as("""{"result":"ok"}""")
          backend <- ScriptedSequenceBackend(
            toolRoundEvent(c1, "write_file", "1"),
            toolRoundEvent(c2, "touch_file", "2"),
            toolRoundEvent(c3, "lookup", "3"),
            planEvent(validPlanJson)
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = newPlanLoop(
            backend,
            registryOf(writer, toucher, lookup),
            adm,
            root,
            dir
          )
          proposal <- loop.runPlan(
            List(RequestMessage("user", "plan the change")),
            lims(),
            maxRounds = 8
          )
          reqs <- backend.requests
          exists <- ZIO.succeed(Files.exists(PlanStore.artifactPath(dir, root)))
          w <- writeCount.get; t <- touchCount.get; l <- lookupCount.get
          // rounds accumulate history: the FINAL request carries both denials
          // (once each) plus the executed ReadOnly result
          finalMsgs = reqs.last.messages.filter(_.role == "tool")
        yield assertTrue(
          proposal.title == "Ship it",
          proposal.steps == Vector("read the file", "write the test"),
          exists,
          reqs.size == 4, // 3 tool rounds + the final plan answer
          // surface narrowing: only the ReadOnly def is advertised (every round)
          reqs.forall(_.tools.map(_.name) == List("lookup")),
          // each non-ReadOnly call was denied, not executed, loop stayed alive
          finalMsgs.count(_.content.contains("RA-TOOLDENY")) == 2,
          finalMsgs.count(_.content.contains("tool_denied")) == 2,
          finalMsgs.exists(_.content.contains("write_file")),
          finalMsgs.exists(_.content.contains("touch_file")),
          finalMsgs.exists(_.content.contains(""""result":"ok"""")),
          w == 0,
          t == 0,
          l == 1 // the ReadOnly tool really executed
        )
      },
      test(
        "ordinary runText on a plan-enabled loop executes Mutating (enforcement is plan-phase only)"
      ) {
        for
          dir <- ZIO.attempt(planTempDir())
          writeCount <- Ref.make(0)
          writer = new Tool:
            def name = "write_file"; def version = 1; def description = ""
            def recovery = RecoveryClass.Mutating; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              writeCount.update(_ + 1).as("""{"written":true}""")
          backend <- ScriptedSequenceBackend(
            toolRoundEvent(c1, "write_file", "1"),
            planEvent(validPlanJson)
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = newPlanLoop(
            backend,
            registryOf(writer),
            adm,
            root,
            dir
          )
          answer <- loop.runText(
            List(RequestMessage("user", "do it")),
            lims(),
            maxRounds = 4
          )
          w <- writeCount.get
        yield assertTrue(
          answer == validPlanJson,
          w == 1 // ordinary mode: full registry, no denial
        )
      },
      test(
        "runPlan on a loop without PlanMode → typed refusal BEFORE any call (RA-CFG: off by default)"
      ) {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](ModelEvent.TextDelta(a, "never"))
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
          res <- loop
            .runPlan(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
          reqs <- backend.requests
        yield assertTrue(
          res.isFailure,
          exitPlanCode(res) == Some("RA-INP"),
          reqs.isEmpty
        )
      }
    ),
    suite("PLAN-02 structured proposal, bounded repair, atomic persistence")(
      test(
        "malformed candidate → structured plan_rejected repair → valid plan persisted atomically"
      ) {
        for
          dir <- ZIO.attempt(planTempDir())
          backend <- ScriptedSequenceBackend(
            planEvent("I think we need some stuff"),
            planEvent(validPlanJson)
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = newPlanLoop(backend, ToolRegistry.empty, adm, root, dir)
          proposal <- loop.runPlan(
            List(RequestMessage("user", "plan")),
            lims(),
            maxRounds = 4
          )
          reqs <- backend.requests
          artifact = PlanStore.artifactPath(dir, root)
          stored <- ZIO.attempt(Files.readString(artifact))
          reparsed = PlanProposal.parse(stored)
          entries <- ZIO.attempt(
            Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector
          )
        yield assertTrue(
          proposal.title == "Ship it",
          reqs.size == 2, // repair = a fresh billable model attempt (§7.11)
          reqs(1).messages(1) == RequestMessage(
            AgentLoop.AssistantRole,
            "I think we need some stuff"
          ),
          reqs(1).messages(2).role == AgentLoop.UserRole,
          reqs(1).messages(2).content.contains("plan_rejected"),
          reqs(1).messages(2).content.contains("RA-PLAN-REJECTED"),
          reparsed == Right(proposal),
          // atomic tmp+move leaves exactly the artifact, never a torn tmp
          entries == Vector(artifact.getFileName.toString),
          stored.endsWith("\n")
        )
      },
      test(
        "persistent malformed → typed PlanRejected, artifact never written, attempts bounded"
      ) {
        for
          dir <- ZIO.attempt(planTempDir())
          backend <- ScriptedSequenceBackend(
            planEvent("nope one"),
            planEvent("nope two")
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = newPlanLoop(
            backend,
            ToolRegistry.empty,
            adm,
            root,
            dir,
            maxRepair = 1
          )
          res <- loop
            .runPlan(List(RequestMessage("user", "plan")), lims(), maxRounds = 4)
            .exit
          reqs <- backend.requests
          exists <- ZIO.succeed(
            Files.list(dir).iterator().asScala.hasNext
          )
        yield assertTrue(
          res.isFailure,
          // the typed rejection carries the LAST reason (its exact wording is
          // the parser's) and the exact attempt count
          exitPlanError(res).exists {
            case PlanPhaseError.PlanRejected(reason, attempts) =>
              attempts == 2 && reason.nonEmpty
            case _ => false
          },
          exitPlanCode(res) == Some("RA-PLAN-REJECTED"),
          reqs.size == 2, // 1 + maxRepairAttempts, not one more
          !exists // nothing persisted on rejection
        )
      },
      test(
        "repair budget is a hard bound: maxRepairAttempts+1 model calls, never unbounded (§7.11)"
      ) {
        ZIO
          .foreach(0 to 3) { maxRepair =>
            for
              dir <- ZIO.attempt(planTempDir())
              malformed = Vector
                .tabulate(maxRepair + 1)(i => planEvent(s"garbage-$i"))
              backend <- ScriptedSequenceBackend(malformed*)
              adm <- Admission.make(limits())
              root = AgentLoop.newRoot()
              loop = newPlanLoop(
                backend,
                ToolRegistry.empty,
                adm,
                root,
                dir,
                maxRepair = maxRepair
              )
              res <- loop
                .runPlan(List(RequestMessage("user", "p")), lims(), maxRounds = 4)
                .exit
              reqs <- backend.requests
              exists <- ZIO.succeed(
                Files.list(dir).iterator().asScala.hasNext
              )
            yield assertTrue(
              res.isFailure,
              exitPlanError(res).exists {
                case PlanPhaseError.PlanRejected(_, attempts) =>
                  attempts == maxRepair + 1
                case _ => false
              },
              reqs.size == maxRepair + 1,
              !exists
            )
          }
          .map(_.reduce(_ && _))
      },
      test("maxRepairAttempts < 0 → Configuration before any call") {
        for
          dir <- ZIO.attempt(planTempDir())
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](ModelEvent.TextDelta(a, "never"))
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = newPlanLoop(
            backend,
            ToolRegistry.empty,
            adm,
            root,
            dir,
            maxRepair = -1
          )
          res <- loop
            .runPlan(List(RequestMessage("user", "p")), lims(), maxRounds = 4)
            .exit
          reqs <- backend.requests
        yield assertTrue(
          res.isFailure,
          exitPlanCode(res) == Some("RA-CFG"),
          reqs.isEmpty
        )
      },
      test(
        "proposal structure: non-JSON / schema mismatch / empty title / empty steps / empty step are rejected; valid roundtrips"
      ) {
        val rejected = List(
          "not json at all",
          "{}",
          "{\"title\":\"\",\"steps\":[\"a\"]}",
          "{\"title\":\"t\",\"steps\":[]}",
          "{\"title\":\"t\",\"steps\":[\"a\",\"\"]}",
          "[\"just a list\"]"
        )
        val accepted = PlanProposal("Ship it", Vector("one", "two"))
        val roundtrip = PlanProposal.parse(accepted.toJson)
        assertTrue(
          rejected.map(PlanProposal.parse).forall(_.isLeft),
          roundtrip == Right(accepted)
        )
      }
    ),
    suite("PLAN-03 approval transition, exactly-one execution, bounded cancel")(
      test(
        "no approval → no execution job; approval → exactly one execution job with the plan as context; second approval typed-denied"
      ) {
        for
          dir <- ZIO.attempt(planTempDir())
          backend <- ScriptedSequenceBackend(
            planEvent(validPlanJson), // plan phase: one model call
            planEvent("\"done\"") // execution phase final
          )
          adm <- Admission.make(limits())
          manager <- JobManager.make()
          root = AgentLoop.newRoot()
          loop = newPlanLoop(backend, ToolRegistry.empty, adm, root, dir)
          session <- PlanSession.make(loop, manager)
          planJob <- session.startPlan(
            List(RequestMessage("user", "plan the change")),
            lims(),
            maxRounds = 4
          )
          // BEFORE approval: the plan phase ran (1 model call) and NOTHING
          // else did — no execution job exists anywhere
          _ <- backend.requests
            .repeatUntil(_.size == 1)
            .timeout(ZDuration.fromSeconds(5))
          proposal <- session.awaitPlan(planJob)
          before <- manager.adminView.map(_.map(_.label).sorted)
          reqsBefore <- backend.requests
          st <- session.state
          exec <- session.approve(
            List(RequestMessage("user", "do it")),
            lims(),
            maxRounds = 4
          )
          execResult <- manager.await(exec)
          after <- manager.adminView
          reqsAfter <- backend.requests
          planSnap <- manager.snapshot(planJob)
          execSnap <- manager.snapshot(exec)
          second <- session
            .approve(List(RequestMessage("user", "again")), lims(), maxRounds = 4)
            .flip
          reqsFinal <- backend.requests
          viewFinal <- manager.adminView
        yield assertTrue(
          before == List("plan"),
          reqsBefore.size == 1, // execution never started without approval
          st == PlanSession.Planning(planJob), // plan done, not yet approved
          proposal.title == "Ship it",
          execResult == "\"done\"",
          after.map(_.label).sorted == List("plan", "plan-exec"),
          execSnap.root == planSnap.root, // same root = same root budget
          // plan travels to the executor as the leading context message
          reqsAfter(1).messages(0).role == AgentLoop.UserRole,
          reqsAfter(1).messages(0).content.contains("Ship it"),
          reqsAfter(1).messages(0).content.contains("read the file"),
          reqsAfter(1).messages(1) == RequestMessage("user", "do it"),
          second == PlanApprovalError.AlreadyApproved(exec.id.value),
          reqsFinal.size == 2, // the denied second approval started nothing
          viewFinal.size == 2 // exactly one execution job exists
        )
      },
      test(
        "startPlan guards: no second plan while one is active, none after approval"
      ) {
        for
          dir <- ZIO.attempt(planTempDir())
          backend <- ScriptedSequenceBackend(
            planEvent(validPlanJson),
            planEvent("\"done\"")
          )
          adm <- Admission.make(limits())
          manager <- JobManager.make()
          root = AgentLoop.newRoot()
          loop = newPlanLoop(backend, ToolRegistry.empty, adm, root, dir)
          session <- PlanSession.make(loop, manager)
          planJob <- session.startPlan(
            List(RequestMessage("user", "p")),
            lims(),
            maxRounds = 4
          )
          second <- session
            .startPlan(List(RequestMessage("user", "p2")), lims(), maxRounds = 4)
            .flip
          _ <- session.awaitPlan(planJob)
          exec <- session.approve(
            List(RequestMessage("user", "go")),
            lims(),
            maxRounds = 4
          )
          third <- session
            .startPlan(List(RequestMessage("user", "p3")), lims(), maxRounds = 4)
            .flip
          view <- manager.adminView
        yield assertTrue(
          second.detail.contains("Planning"),
          third.detail.contains("Approved"),
          view.size == 2 // no ghost jobs from refused starts
        )
      },
      test(
        "cancel plan phase: bounded cleanup (no orphan, no permit leak, Uncertain kept), cancel != approval, repeated plan possible"
      ) {
        live {
          for
            dir <- ZIO.attempt(planTempDir())
            gate <- Promise.make[Nothing, Unit]
            underlying <- ScriptedSequenceBackend(
              planEvent(validPlanJson), // would finish if not interrupted
              planEvent(validPlanJson) // the repeated plan
            )
            backend = GatedBackend(underlying, gate)
            adm <- Admission.make(limits())
            manager <- JobManager.make()
            root = AgentLoop.newRoot()
            loop = newPlanLoop(
              backend,
              ToolRegistry.empty,
              adm,
              root,
              dir,
              est = CostEstimate.Priced(MicroUsd(250L))
            )
            session <- PlanSession.make(loop, manager)
            planJob <- session.startPlan(
              List(RequestMessage("user", "p")),
              lims(),
              maxRounds = 4
            )
            // deterministic: request recorded ⇒ fiber is inside model dispatch
            _ <- backend.requests
              .repeatUntil(_.size == 1)
              .timeout(ZDuration.fromSeconds(5))
            cancelled <- session.cancelPlan()
            outcome <- manager.await(planJob).exit
            usage <- adm.budgetLedger.usage
            modelFree <- adm.availableSlots(SlotKind.Model)
            toolFree <- adm.availableSlots(SlotKind.Tool)
            st <- session.state
            approveAfterCancel <- session
              .approve(
                List(RequestMessage("user", "go")),
                lims(),
                maxRounds = 4
              )
              .flip
            view1 <- manager.adminView
            reqsAfterCancel <- backend.requests
            // the repeated plan: release the gate, start a NEW plan job
            _ <- gate.succeed(()).unit
            planJob2 <- session.startPlan(
              List(RequestMessage("user", "p again")),
              lims(),
              maxRounds = 4
            )
            proposal2 <- session.awaitPlan(planJob2)
            view2 <- manager.adminView
            exists <- ZIO.succeed(
              Files.exists(PlanStore.artifactPath(dir, root))
            )
          yield assertTrue(
            cancelled,
            exitPlanCancelled(outcome), // job settled as typed Cancelled
            usage.reservedActive == MicroUsd(0),
            usage.uncertainTotal == MicroUsd(250L), // interrupted dispatch: never zeroed
            modelFree == 1 && toolFree == 1, // no permit leak (§7.4/7.5)
            st == PlanSession.Cancelled,
            approveAfterCancel == PlanApprovalError.Cancelled,
            view1.map(_.status).forall(JobStatus.terminal.contains), // no orphan Running
            reqsAfterCancel.size == 1, // the cancelled dispatch started nothing new
            proposal2.title == "Ship it",
            view2.size == 2, // cancelled plan + repeated plan, no ghosts
            view2.map(_.label) == List("plan", "plan"),
            exists
          )
        }
      }
    )
  )

  /** A backend whose stream parks on a gate after recording the request —
    * makes "inside dispatch" deterministic for the cancel test.
    */
  private final class GatedBackend(
      underlying: ScriptedSequenceBackend,
      gate: Promise[Nothing, Unit]
  ) extends ModelBackend:

    private val seen = zio.Unsafe.unsafe { implicit u =>
      Ref.unsafe.make(Vector.empty[ModelRequest])
    }

    def capabilities: ModelCapabilities = underlying.capabilities

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.fromZIO(seen.update(_ :+ input) *> gate.await) *>
        underlying.stream(input)

    def requests: ZIO[Any, Nothing, Vector[ModelRequest]] = seen.get

  private def exitPlanCode(
      exit: ZExit[PlanPhaseError, ?]
  ): Option[String] =
    exit match
      case ZExit.Failure(cause) =>
        cause.failures.headOption.map(_.code)
      case ZExit.Success(_) => None

  private def exitPlanError(
      exit: ZExit[PlanPhaseError, ?]
  ): Option[PlanPhaseError] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption
      case ZExit.Success(_)     => None

  private def exitPlanCancelled(exit: ZExit[RaiderError, ?]): Boolean =
    exit match
      case ZExit.Failure(cause) =>
        cause.failures.headOption.exists(_.code == "RA-CANCEL")
      case ZExit.Success(_) => false
