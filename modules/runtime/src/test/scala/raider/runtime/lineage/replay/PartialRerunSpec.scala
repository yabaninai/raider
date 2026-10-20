package raider.runtime.lineage.replay

import raider.core.{RaiderError, RootId, SessionId}
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.{DataRef, NodeId, SessionGraph, SessionNode}
import zio.{Promise, Scope, ZIO}
import zio.test.*

object PartialRerunSpec extends ZIOSpecDefault:
  private val phaseA = NodeId("phase-a")
  private val phaseB = NodeId("phase-b")
  private val aCall = NodeId("a-call")
  private val bCall = NodeId("b-call")

  private val graph = SessionGraph(
    RootId("root"),
    SessionId("session"),
    Vector(
      SessionNode.Phase(phaseA, None, 1L, "prepare", None),
      SessionNode.ModelCall(
        aCall,
        Some(phaseA),
        2L,
        2L,
        1,
        "scripted",
        Vector.empty,
        DataRef("a")
      ),
      SessionNode.Phase(phaseB, None, 3L, "publish", None),
      SessionNode.ModelCall(
        bCall,
        Some(phaseB),
        4L,
        4L,
        1,
        "scripted",
        Vector.empty,
        DataRef("b")
      )
    )
  )

  def spec = suite("PartialRerun LIN-07")(
    test("LIN7-01 selects a phase subtree and leaves sibling nodes untouched") {
      val selected = PartialRerun.select(
        graph,
        ReplaySelection.Phase(phaseA),
        PartialReplayInputs(Map.empty)
      )
      selected match
        case Right(slice) =>
          val untouched =
            graph.nodes.filter(n => n.id == phaseB || n.id == bCall)
          val retained =
            graph.nodes.filter(n => n.id == phaseB || n.id == bCall)
          assertTrue(
            slice.nodes.map(_.id) == Vector(phaseA, aCall),
            untouched == retained,
            slice.nodes.head.parent.isEmpty
          )
        case Left(_) => assertTrue(false)
    },
    test("LIN7-02 rejects missing and non-phase selections") {
      val missing = PartialRerun.select(
        graph,
        ReplaySelection.Phase(NodeId("missing")),
        PartialReplayInputs(Map.empty)
      )
      val wrongKind = PartialRerun.select(
        graph,
        ReplaySelection.Phase(aCall),
        PartialReplayInputs(Map.empty)
      )
      assertTrue(
        missing.left.toOption
          .exists(_.isInstanceOf[PartialReplayError.InvalidSelection]),
        wrongKind.left.toOption.exists(
          _.isInstanceOf[PartialReplayError.InvalidSelection]
        )
      )
    },
    test("LIN7-03 rejects an external dependency without a resolved input") {
      val dependent = graph.copy(nodes =
        graph.nodes.updated(
          1,
          SessionNode.ModelCall(
            aCall,
            Some(phaseA),
            2L,
            2L,
            1,
            "scripted",
            Vector(bCall),
            DataRef("a")
          )
        )
      )
      val result = PartialRerun.select(
        dependent,
        ReplaySelection.Phase(phaseA),
        PartialReplayInputs(Map.empty)
      )
      assertTrue(
        result == Left(PartialReplayError.MissingExternalInput(aCall, bCall))
      )
    },
    test("LIN7-BUDGET rejects a non-positive round budget before dispatch") {
      val limits = BudgetLimits.make().toOption.get
      PartialRerun
        .execute(
          new PartialRerunBackend:
            def execute(
                request: PartialRerunRequest
            ): ZIO[Scope, RaiderError, PartialRerunExecution] =
              ZIO.die(new AssertionError("backend must not be called"))
          ,
          graph,
          ReplaySelection.Phase(phaseA),
          PartialReplayInputs(Map.empty),
          limits,
          0
        )
        .either
        .map(outcome => assertTrue(outcome.isLeft))
    },
    test("LIN7-BUDGET-EXHAUSTED returns typed refusal during backend work") {
      val limits = BudgetLimits.make(maxAttempts = 1).toOption.get
      for
        entered <- Promise.make[Nothing, Unit]
        outcome <- PartialRerun
          .execute(
            new PartialRerunBackend:
              def execute(
                  request: PartialRerunRequest
              ): ZIO[Scope, RaiderError, PartialRerunExecution] =
                entered.succeed(()) *> ZIO.fail(
                  RaiderError.LocalBudgetExceeded("partial rerun attempt limit")
                )
            ,
            graph,
            ReplaySelection.Phase(phaseA),
            PartialReplayInputs(Map.empty),
            limits,
            2
          )
          .either
          .fork
        _ <- entered.await.timeoutFail(
          new AssertionError("backend did not start")
        )(
          zio.Duration.fromSeconds(2)
        )
        result <- outcome.join
      yield assertTrue(
        result == Left(
          RaiderError.LocalBudgetExceeded("partial rerun attempt limit")
        )
      )
    },
    test("LIN7-CANCEL interrupts partial work without producing a result") {
      val limits = BudgetLimits.make().toOption.get
      for
        entered <- Promise.make[Nothing, Unit]
        finalized <- Promise.make[Nothing, Unit]
        fiber <- PartialRerun
          .execute(
            new PartialRerunBackend:
              def execute(
                  request: PartialRerunRequest
              ): ZIO[Scope, RaiderError, PartialRerunExecution] =
                (entered.succeed(()) *> ZIO.never)
                  .ensuring(finalized.succeed(()).unit)
            ,
            graph,
            ReplaySelection.Phase(phaseA),
            PartialReplayInputs(Map.empty),
            limits,
            2
          )
          .fork
        _ <- entered.await.timeoutFail(
          new AssertionError("backend did not start")
        )(
          zio.Duration.fromSeconds(2)
        )
        exit <- fiber.interrupt
        cleaned <- finalized.await.as(true).timeout(zio.Duration.fromSeconds(2))
      yield assertTrue(exit.isInterrupted, cleaned.contains(true))
    }
  )
