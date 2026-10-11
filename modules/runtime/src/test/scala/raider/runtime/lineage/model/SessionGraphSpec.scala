package raider.runtime.lineage.model

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.ToolOutcome
import zio.test.*

/** LIN-01A acceptance (docs/tasks/lineage.md, packet L1): model construction
  * and invariants (LIN1-A-01), typed violations (LIN1-A-02), hash determinism +
  * sibling-reorder normalization (LIN1-A-03).
  */
object SessionGraphSpec extends ZIOSpecDefault:

  private val rootId = RootId("root-lin")
  private val sessionId = SessionId("sess-lin")

  private val phase = SessionNode.Phase(
    NodeId("ph-1"),
    None,
    1L,
    "setup",
    None
  )

  private val model = SessionNode.ModelCall(
    NodeId("mc-1"),
    Some(NodeId("ph-1")),
    2L,
    10L,
    1,
    "test-model",
    Vector(NodeId("ph-1")),
    DataRef("d-model-1")
  )

  private val toolRead = SessionNode.ToolCall(
    NodeId("tc-1"),
    Some(NodeId("mc-1")),
    3L,
    20L,
    "call-1",
    "fs_read",
    RecoveryClass.ReadOnly,
    Some(ToolOutcome.Succeeded),
    Vector(NodeId("mc-1")),
    Some(DataRef("d-tool-1"))
  )

  private val toolWrite = SessionNode.ToolCall(
    NodeId("tc-2"),
    Some(NodeId("mc-1")),
    4L,
    30L,
    "call-2",
    "fs_edit",
    RecoveryClass.Mutating,
    Some(ToolOutcome.Failed("boom")),
    Vector(NodeId("tc-1")),
    None
  )

  private val delegation = SessionNode.Delegation(
    NodeId("del-1"),
    Some(NodeId("ph-1")),
    5L,
    40L,
    JobId("child-1"),
    "scout",
    Vector.empty
  )

  private def graph(nodes: SessionNode*) =
    SessionGraph(rootId, sessionId, nodes.toVector)

  private val valid = graph(phase, model, toolRead, toolWrite, delegation)

  override def spec = suite("SessionGraphSpec")(
    suite("LIN1-A-01 construction")(
      test("valid graph passes validation and exposes structure") {
        val v = valid.validated
        assertTrue(
          v.isRight,
          valid.roots.map(_.id) == Vector(NodeId("ph-1")),
          valid.childrenOf("ph-1").map(_.id) == Vector(
            NodeId("mc-1"),
            NodeId("del-1")
          ),
          valid.childrenOf("mc-1").map(_.id) == Vector(
            NodeId("tc-1"),
            NodeId("tc-2")
          )
        )
      },
      test("phase carries no inputs by definition") {
        assertTrue(phase.inputs.isEmpty)
      }
    ),
    suite("LIN1-A-02 typed violations")(
      test("duplicate id") {
        val bad = graph(phase, model, model.copy(seq = 6L))
        assertTrue(
          bad.validated == Left(GraphError.Duplicate(NodeId("mc-1")))
        )
      },
      test("missing parent") {
        val bad = graph(model)
        assertTrue(
          bad.validated == Left(
            GraphError.MissingParent(NodeId("mc-1"), NodeId("ph-1"))
          )
        )
      },
      test("parent after child") {
        val bad = graph(model, phase)
        assertTrue(
          bad.validated == Left(
            GraphError.ParentOrderViolation(NodeId("mc-1"), NodeId("ph-1"))
          )
        )
      },
      test("dangling dataflow input is a MissingParent") {
        val dangling = toolRead.copy(inputs = Vector(NodeId("ghost")))
        val bad = graph(phase, model, dangling)
        assertTrue(
          bad.validated == Left(
            GraphError.MissingParent(NodeId("tc-1"), NodeId("ghost"))
          )
        )
      },
      test("dataflow cycle") {
        val a = toolRead.copy(inputs = Vector(NodeId("tc-2")))
        val b = toolWrite.copy(inputs = Vector(NodeId("tc-1")))
        val bad = graph(phase, model, a, b)
        assertTrue(
          bad.validated == Left(GraphError.DataflowCycle(NodeId("tc-1")))
        )
      }
    ),
    suite("LIN1-A-03 GraphHash determinism")(
      test("same graph hashes identically") {
        val h1 = GraphHash(valid)
        val h2 = GraphHash(valid)
        assertTrue(
          h1 == h2,
          h1.length == 64,
          h1.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
        )
      },
      test("sibling reorder (same parents/seqs) normalizes to equal hash") {
        val reordered = graph(phase, model, toolWrite, toolRead, delegation)
        val reorderedValid = reordered.validated
        assertTrue(
          reorderedValid.isRight,
          GraphHash(reordered) == GraphHash(valid)
        )
      },
      test("content change changes hash") {
        val other = valid.copy(nodes = valid.nodes.map {
          case m: SessionNode.ModelCall => m.copy(model = "other-model")
          case otherNode                => otherNode
        })
        assertTrue(GraphHash(other) != GraphHash(valid))
      },
      test("different root/session identity changes hash") {
        val otherRoot =
          valid.copy(rootId = RootId("root-other"))
        val otherSession =
          valid.copy(sessionId = SessionId("sess-other"))
        assertTrue(
          GraphHash(otherRoot) != GraphHash(valid),
          GraphHash(otherSession) != GraphHash(valid)
        )
      }
    )
  )
