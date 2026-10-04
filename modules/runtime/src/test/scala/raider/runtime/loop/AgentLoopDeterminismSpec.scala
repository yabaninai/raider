package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.testkit.{GatedModelBackend, GatedTool, ScriptedSequenceBackend}
import zio.test.*
import zio.{Exit as ZExit, Duration as ZDuration, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** RAI-006.b (MOCK-03) acceptance over the Phase-A loop: deterministic
  * cancel-mid-dispatch accounting WITHOUT real sleeps.
  *
  * Tests here do NOT use `live{}`: ZIO Test's default TestClock governs the
  * spec runtime, and the gated primitives (record-then-park) give race-free
  * control points, so every cancellation lands exactly where the test aims.
  */
object AgentLoopDeterminismSpec extends ZIOSpecDefault:

  private val a = AttemptId("det-att")
  private val c1 = ToolCallId("call-1")

  private def lims(attempts: Int = 8): BudgetLimits =
    BudgetLimits.make(maxAttempts = attempts).fold(e => throw e, identity)

  def spec = suite("AgentLoop deterministic cancellation (MOCK-03)")(
    test("cancel DURING model dispatch: Uncertain charge kept — zero sleeps") {
      for
        backend <- GatedModelBackend(
          Vector[ModelEvent](ModelEvent.TextDelta(a, "late"))
        )
        adm <- Admission.make(BudgetLimits.make(maxAttempts = 8))
        root = AgentLoop.newRoot()
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          CostEstimate.Priced(MicroUsd(400L))
        )
        fiber <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 4)
          .forkDaemon
        // recorded ⇒ parked inside dispatch (deterministic control point)
        _ <- backend.requests.repeatUntil(_.size == 1)
        _ <- fiber.interrupt
        _ <- fiber.await
        usage <- adm.budgetLedger.usage
        free <- adm.availableSlots(SlotKind.Model)
        // open AFTER the fiber is dead: late gate release mutates nothing
        _ <- backend.open(0)
        after <- adm.budgetLedger.usage
      yield assertTrue(
        usage.uncertainTotal == MicroUsd(
          400L
        ), // may have been paid: NOT zeroed
        usage.reservedActive == MicroUsd(0),
        free == 1, // no permit leak
        after == usage
      ) // late settlement attempts cannot rewrite the outcome
    },
    test(
      "cancel DURING tool dispatch: prior model round settled, slots released"
    ) {
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](
            ModelEvent.Started(a),
            ModelEvent.ToolCallReady(a, c1, "gated", "1"),
            ModelEvent.Finished(a, "r1")
          )
        )
        adm <- Admission.make(BudgetLimits.make(maxAttempts = 8))
        tool <- GatedTool(name = "gated")
        root = AgentLoop.newRoot()
        loop = AgentLoop(
          backend,
          ToolRegistry.of(tool).fold(e => throw e, identity),
          "m",
          adm,
          root,
          CostEstimate.Priced(MicroUsd(100L))
        )
        fiber <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 4)
          .forkDaemon
        // the tool recorded its call ⇒ parked inside tool dispatch
        _ <- tool.started.repeatUntil(_.nonEmpty)
        _ <- fiber.interrupt
        _ <- fiber.await
        usage <- adm.budgetLedger.usage
        mFree <- adm.availableSlots(SlotKind.Model)
        tFree <- adm.availableSlots(SlotKind.Tool)
        _ <- tool.open // late open mutates nothing (fiber already settled)
        after <- adm.budgetLedger.usage
      yield assertTrue(
        usage.reservedActive == MicroUsd(
          0
        ), // round-1 model settled before tool
        usage.uncertainTotal == MicroUsd(
          0
        ), // tool was free: nothing kept/zeroed
        mFree == 1 && tFree == 1,
        after == usage
      )
    },
    test(
      "virtual deadline: tool timeout fires via TestClock, typed, slot released"
    ) {
      val start = new AtomicInteger(0)
      val sleeper = new Tool:
        def name = "sleepy"; def version = 1; def description = ""
        def recovery = RecoveryClass.ReadOnly; def timeoutMs = 1000L
        def capabilities = ToolCapabilities(concurrentSafe = false)
        // TestClock-governed park: 1 hour never elapses unless adjusted
        def invoke(args: String) =
          ZIO.succeed(start.incrementAndGet()) *>
            ZIO.sleep(ZDuration.fromSeconds(3600)).as("""{"ok":true}""")
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](
            ModelEvent.Started(a),
            ModelEvent.ToolCallReady(a, c1, "sleepy", "1"),
            ModelEvent.Finished(a, "r1")
          )
        )
        adm <- Admission.make(BudgetLimits.make(maxAttempts = 8))
        root = AgentLoop.newRoot()
        loop = AgentLoop(
          backend,
          ToolRegistry.of(sleeper).fold(e => throw e, identity),
          "m",
          adm,
          root
        )
        fiber <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 4)
          .exit
          .forkDaemon
        _ <- ZIO.succeed(start.get).repeatUntil(_ == 1)
        _ <- TestClock.adjust(ZDuration.fromMillis(1001))
        res <- fiber.join
        usage <- adm.budgetLedger.usage
        tFree <- adm.availableSlots(SlotKind.Tool)
      yield assertTrue(
        res.isFailure,
        exitCodeOf(res) == Some("RA-DEADLINE"),
        usage.reservedActive == MicroUsd(0),
        tFree == 1
      )
    },
    test(
      "finish-vs-cancel: gate opened first ⇒ clean success, cancel afterwards is a no-op"
    ) {
      for
        backend <- GatedModelBackend(
          Vector[ModelEvent](
            ModelEvent.Started(a),
            ModelEvent.TextDelta(a, "final"),
            ModelEvent.Finished(a, "stop")
          )
        )
        adm <- Admission.make(BudgetLimits.make(maxAttempts = 8))
        root = AgentLoop.newRoot()
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          CostEstimate.Priced(MicroUsd(50L))
        )
        fiber <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 4)
          .forkDaemon
        _ <- backend.requests.repeatUntil(_.size == 1)
        _ <- backend.open(0)
        text <- fiber.join
        usage <- adm.budgetLedger.usage
        lateInterrupt <- fiber.interrupt // no-op: fiber already completed
      yield assertTrue(
        text == "final",
        usage.reservedActive == MicroUsd(0), // settled exactly
        usage.uncertainTotal == MicroUsd(0),
        lateInterrupt == ZExit.Success(text)
      ) // interrupting a finished fiber does not fake cancellation
    }
  )

  private def exitCodeOf[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None
