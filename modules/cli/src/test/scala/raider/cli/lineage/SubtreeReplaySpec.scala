package raider.cli.lineage

import zio.json.*
import zio.test.*

import raider.core.{RootId, SessionId}
import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind
}
import raider.dsl.lineage.replay.RecordedSource
import raider.runtime.events.ToolOutcome
import raider.runtime.lineage.model.{
  DataRef,
  GraphHash,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** LIN7 acceptance (partial replay of a subtree/phase, ADR-027 §2):
  *
  *   - LIN7-01 — golden deep-tree: `subtree` of the phase node synthesizes the
  *     whole tree as a valid subgraph (every child present, dataflow kept,
  *     GraphHash stable across repeat calls and equal to the original graph
  *     because the slice covers it fully); a mid-tree slice (the delegation)
  *     keeps its descendants, stays `validated`, and cuts only the dataflow
  *     edge crossing the slice boundary; `verify` resolves every ModelCall
  *     (`Resolved`) and lists ToolCall nodes as obligations.
  *   - LIN7-02 — `subtree` of a leaf is a single-node graph (the leaf becomes
  *     the slice root, its parent edge is cut); GraphHash applies to the
  *     synthesized graph; `verify` resolves zero model calls and lists the leaf
  *     tool as an obligation.
  *   - LIN7-03 — an unknown NodeId is a typed `SubtreeError.NodeNotFound`,
  *     never a synthesized empty graph.
  *   - LIN7-04 — a broken dataRef inside the subtree fails typed and with the
  *     EXACT node: `Unresolved` for a ref missing from the recorded document,
  *     `KindMismatch` for a ref pointing at non-model data, `MissingDataRef`
  *     for the empty-ref node; a mixed subtree reports only the failing nodes
  *     (`PartiallyResolved`), the resolving ones stay silent.
  *   - Defensive — a parent-edge cycle in a malformed graph (impossible by
  *     construction, since construction never validates) terminates with a
  *     typed `SubtreeError.Cycle` instead of a hang.
  *
  * Golden fixtures are the REAL frozen documents under
  * `contracts/lineage/golden` parsed from disk through the production codecs
  * (never rebuilt from code) — the same discipline as ReplayInterpreterSpec.
  */
object SubtreeReplaySpec extends ZIOSpecDefault:

  // ----- golden fixture plumbing ----------------------------------------------

  private def findAncestor(segments: String*): Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isDirectory(dir.resolve(segments.mkString("/"))))
      .getOrElse(
        throw new IllegalStateException(
          "no ancestor of " + cwd + " contains " + segments.mkString("/")
        )
      )

  private def golden(name: String): (SessionGraph, RecordedSource) =
    val dir = findAncestor("contracts", "lineage", "golden")
      .resolve("contracts")
      .resolve("lineage")
      .resolve("golden")
      .resolve(name)
    val raw =
      Files.readString(dir.resolve("lineage-data.json"), StandardCharsets.UTF_8)
    val doc = raw.fromJson[LineageDataDocument] match
      case Right(d) => d
      case Left(e)  => throw new IllegalStateException(name + ": " + e)
    val graph = graphOf(doc) match
      case Right(g) =>
        g.validated.fold(
          err => throw new IllegalStateException(name + ": " + err),
          identity
        )
      case Left(e) => throw new IllegalStateException(name + ": " + e)
    (graph, RecordedSource(doc))

  /** Builds the SessionGraph from the frozen lineage-data document (the same
    * payload-driven mapping ReplayInterpreterSpec documents; outcome CLASS only
    * — lineage-data v1 records no failure reason, so `Failed` maps to the empty
    * reason). A payload of the wrong variant for a node kind is an honest
    * fixture failure, never a guess.
    */
  private def graphOf(
      doc: LineageDataDocument
  ): Either[String, SessionGraph] =
    def payload(dn: DataNode): Either[String, NodeData] =
      dn.dataRef
        .flatMap(ref => doc.data.get(ref))
        .toRight(s"${dn.kind} ${dn.id}: dataRef missing in document")

    def recovery(raw: String): Either[String, RecoveryClass] =
      RecoveryClass.values
        .find(_.toString == raw)
        .toRight(s"unknown recovery class '$raw'")

    def outcome(
        raw: Option[String],
        tail: Option[String]
    ): Either[String, Option[ToolOutcome]] = raw match
      case None              => Right(None)
      case Some("Succeeded") => Right(Some(ToolOutcome.Succeeded))
      case Some("Cancelled") => Right(Some(ToolOutcome.Cancelled))
      case Some("Timeout")   => Right(Some(ToolOutcome.Timeout))
      case Some("Failed") =>
        Right(Some(ToolOutcome.Failed(tail.getOrElse(""))))
      case Some(other) => Left(s"unknown tool outcome '$other'")

    doc.nodes
      .foldLeft[Either[String, Vector[SessionNode]]](
        Right(Vector.empty)
      ) { (acc, dn) =>
        acc.flatMap { nodes =>
          val parent = dn.parentId.map(NodeId(_))
          val inputs = dn.inputs.map(NodeId(_))
          dn.kind match
            case NodeKind.Phase =>
              payload(dn).flatMap {
                case p: NodeData.PhaseData =>
                  Right(
                    nodes :+ SessionNode.Phase(
                      NodeId(dn.id),
                      parent,
                      dn.seq,
                      p.name,
                      p.note
                    )
                  )
                case other => Left(s"phase ${dn.id}: $other is not PhaseData")
              }
            case NodeKind.ModelCall =>
              payload(dn).flatMap {
                case m: NodeData.ModelCallData =>
                  Right(
                    nodes :+ SessionNode.ModelCall(
                      NodeId(dn.id),
                      parent,
                      dn.seq,
                      dn.atMs,
                      m.round,
                      m.model,
                      inputs,
                      DataRef(dn.dataRef.getOrElse(""))
                    )
                  )
                case other =>
                  Left(s"model_call ${dn.id}: $other is not ModelCallData")
              }
            case NodeKind.ToolCall =>
              payload(dn).flatMap {
                case t: NodeData.ToolCallData =>
                  for
                    recoveryClass <- recovery(t.recovery)
                    toolOutcome <- outcome(t.outcome, t.resultTail)
                  yield nodes :+ SessionNode.ToolCall(
                    NodeId(dn.id),
                    parent,
                    dn.seq,
                    dn.atMs,
                    t.callId,
                    t.tool,
                    recoveryClass,
                    toolOutcome,
                    inputs,
                    dn.dataRef.map(DataRef(_))
                  )
                case other =>
                  Left(s"tool_call ${dn.id}: $other is not ToolCallData")
              }
            case NodeKind.Delegation =>
              payload(dn).flatMap {
                case d: NodeData.DelegationData =>
                  Right(
                    nodes :+ SessionNode.Delegation(
                      NodeId(dn.id),
                      parent,
                      dn.seq,
                      dn.atMs,
                      raider.core.JobId(d.childJobId),
                      d.agent,
                      inputs
                    )
                  )
                case other =>
                  Left(s"delegation ${dn.id}: $other is not DelegationData")
              }
        }
      }
      .map(nodes =>
        SessionGraph(RootId(doc.runId), SessionId("sess-" + doc.runId), nodes)
      )

  // ----- synthetic fixtures (LIN7-03/04, defensive cycle) ----------------------

  /** The recorded source of the synthetic fixtures: d1 is a real model answer,
    * d2 is TOOL data (a KindMismatch target), nothing else exists.
    */
  private def syntheticSource: RecordedSource = RecordedSource(
    LineageDataDocument(
      schemaVersion = 1,
      generator = GeneratorInfo(
        "raider",
        "0.1.0-SNAPSHOT",
        "3.9.0",
        "21"
      ),
      runId = "run-synth-001",
      nodes = Vector.empty,
      data = Map(
        "d1" -> NodeData.ModelCallData(
          model = "mock-coder",
          round = 1,
          prompt = "synthetic prompt",
          response = "synthetic response",
          usage = None
        ),
        "d2" -> NodeData.ToolCallData(
          tool = "read_file",
          callId = "synth-call-1",
          recovery = "ReadOnly",
          outcome = Some("Succeeded"),
          argsSummary = "path=synth.md",
          resultTail = None
        )
      )
    )
  )

  private def model(
      id: String,
      parent: Option[NodeId],
      seq: Long,
      ref: String,
      inputs: Vector[NodeId] = Vector.empty
  ): SessionNode.ModelCall =
    SessionNode.ModelCall(
      NodeId(id),
      parent,
      seq,
      0,
      1,
      "mock-coder",
      inputs,
      DataRef(ref)
    )

  private def syntheticGraph(nodes: SessionNode*): SessionGraph =
    SessionGraph(
      RootId("run-synth-001"),
      SessionId("sess-run-synth-001"),
      nodes.toVector
    )

  private def isSha256Hex(s: String): Boolean =
    s.length == 64 && s.forall(c => c.isDigit || ('a' to 'f').contains(c))

  // ----- spec ------------------------------------------------------------------

  def spec = suite("LIN7 subtree partial replay")(
    test(
      "LIN7-01: golden deep-tree, subtree(phase n0) covers the whole tree, hash stable, verify Resolved"
    ) {
      val (graph, source) = golden("deep-tree")
      val sub1 = SubtreeReplay.subtree(graph, NodeId("n0"))
      val sub2 = SubtreeReplay.subtree(graph, NodeId("n0"))
      val slice1 = sub1.fold(
        err => throw new IllegalStateException("expected a slice, got " + err),
        identity
      )
      val slice2 = sub2.fold(
        err => throw new IllegalStateException("expected a slice, got " + err),
        identity
      )
      val verdict = SubtreeReplay.verify(slice1, source)
      val again = SubtreeReplay.verify(slice2, source)
      assertTrue(
        slice1.validated == Right(slice1),
        // the phase node n0 is the graph root: the slice IS the whole tree
        slice1.nodes.map(_.id) == graph.nodes.map(_.id),
        slice1 == graph,
        // hash stable across repeat synthesis and equal for the full slice
        GraphHash(slice1) == GraphHash(graph),
        GraphHash(slice1) == GraphHash(slice2),
        isSha256Hex(GraphHash(slice1)),
        // verify: both model rounds resolve, the tool stays an obligation
        verdict == SubtreeVerdict.Resolved(
          2,
          Vector(
            DeferredToolCall(
              "n4",
              "grep_search",
              RecoveryClass.Idempotent,
              Some("d4")
            )
          )
        ),
        verdict == again
      )
    },
    test(
      "LIN7-01: mid-tree slice (delegation n2) keeps descendants, cuts the boundary dataflow edge, stays valid"
    ) {
      val (graph, source) = golden("deep-tree")
      val sliceE = SubtreeReplay.subtree(graph, NodeId("n2"))
      val slice = sliceE.fold(
        err => throw new IllegalStateException("expected a slice, got " + err),
        identity
      )
      val n3 = slice.nodes.find(_.id == NodeId("n3")).get
      val n2 = slice.nodes.find(_.id == NodeId("n2")).get
      val verdict = SubtreeReplay.verify(slice, source)
      assertTrue(
        slice.validated == Right(slice),
        slice.nodes
          .map(_.id) == Vector(NodeId("n2"), NodeId("n3"), NodeId("n4")),
        // the slice root loses its parent edge; descendants keep theirs
        n2.parent.isEmpty,
        n3.parent == Some(NodeId("n2")),
        // dataflow n1 -> n3 crosses the slice boundary: cut, not dangling
        n3.inputs.isEmpty,
        // kinds/seqs/dataRefs survive verbatim
        slice.nodes.map(_.seq) == Vector(3L, 4L, 5L),
        SubtreeReplay.verify(slice, source) == SubtreeReplay
          .verify(slice, source),
        GraphHash(slice) == GraphHash(slice),
        // one model call (d3) resolves; the tool is an obligation
        verdict == SubtreeVerdict.Resolved(
          1,
          Vector(
            DeferredToolCall(
              "n4",
              "grep_search",
              RecoveryClass.Idempotent,
              Some("d4")
            )
          )
        )
      )
    },
    test(
      "LIN7-02: subtree(leaf n4) is a single-node graph, hashable, verify lists the tool obligation"
    ) {
      val (graph, source) = golden("deep-tree")
      val sliceE = SubtreeReplay.subtree(graph, NodeId("n4"))
      val slice = sliceE.fold(
        err => throw new IllegalStateException("expected a slice, got " + err),
        identity
      )
      val verdict = SubtreeReplay.verify(slice, source)
      assertTrue(
        slice.validated == Right(slice),
        slice.nodes == Vector(
          // the leaf becomes the slice root: parent edge cut, everything else verbatim
          SessionNode.ToolCall(
            NodeId("n4"),
            None,
            5,
            1180,
            "call-091",
            "grep_search",
            RecoveryClass.Idempotent,
            None,
            Vector.empty,
            Some(DataRef("d4"))
          )
        ),
        slice.nodes.size == 1,
        isSha256Hex(GraphHash(slice)),
        verdict == SubtreeVerdict.Resolved(
          0,
          Vector(
            DeferredToolCall(
              "n4",
              "grep_search",
              RecoveryClass.Idempotent,
              Some("d4")
            )
          )
        )
      )
    },
    test(
      "LIN7-03: unknown NodeId is a typed NodeNotFound, never a synthesized empty graph"
    ) {
      val (graph, _) = golden("deep-tree")
      val result = SubtreeReplay.subtree(graph, NodeId("nope"))
      assertTrue(
        result == Left(SubtreeError.NodeNotFound(NodeId("nope")))
      )
    },
    test(
      "LIN7-04: broken dataRef in the subtree is PartiallyResolved with the exact node (Unresolved)"
    ) {
      val sub = syntheticGraph(
        model("m1", None, 1, "d1"),
        model("m2", None, 2, "nope")
      )
      val verdict = SubtreeReplay.verify(sub, syntheticSource)
      assertTrue(
        verdict == SubtreeVerdict.PartiallyResolved(
          Vector(SubtreeFailure.Unresolved("m2", "nope")),
          Vector.empty
        )
      )
    },
    test(
      "LIN7-04: dataRef pointing at non-model data is PartiallyResolved (KindMismatch with the node)"
    ) {
      val sub = syntheticGraph(model("m1", None, 1, "d2"))
      val verdict = SubtreeReplay.verify(sub, syntheticSource)
      assertTrue(
        verdict == SubtreeVerdict.PartiallyResolved(
          Vector(
            SubtreeFailure.KindMismatch(
              "m1",
              "d2",
              "ModelCallData",
              "ToolCallData"
            )
          ),
          Vector.empty
        )
      )
    },
    test(
      "LIN7-04: empty dataRef node is PartiallyResolved (MissingDataRef), the source is never consulted"
    ) {
      val sub = syntheticGraph(model("m1", None, 1, ""))
      val verdict = SubtreeReplay.verify(sub, syntheticSource)
      assertTrue(
        verdict == SubtreeVerdict.PartiallyResolved(
          Vector(SubtreeFailure.MissingDataRef("m1")),
          Vector.empty
        )
      )
    },
    test(
      "LIN7-04: mixed subtree — failing node reported, resolving node and tool obligation still listed"
    ) {
      val sub = syntheticGraph(
        model("ok", None, 1, "d1"),
        model("broken", Some(NodeId("ok")), 2, "nope"),
        SessionNode.ToolCall(
          NodeId("t1"),
          Some(NodeId("broken")),
          3,
          0,
          "call-1",
          "read_file",
          RecoveryClass.ReadOnly,
          None,
          Vector.empty,
          None
        )
      )
      val verdict = SubtreeReplay.verify(sub, syntheticSource)
      assertTrue(
        verdict == SubtreeVerdict.PartiallyResolved(
          Vector(SubtreeFailure.Unresolved("broken", "nope")),
          Vector(
            DeferredToolCall("t1", "read_file", RecoveryClass.ReadOnly, None)
          )
        )
      )
    },
    test(
      "defensive: a parent-edge cycle in a malformed graph stops with typed Cycle, not a hang"
    ) {
      // construction never validates: a <-> b parent edges are representable
      val bad = syntheticGraph(
        model("a", Some(NodeId("b")), 1, "d1"),
        model("b", Some(NodeId("a")), 2, "d1")
      )
      val result = SubtreeReplay.subtree(bad, NodeId("a"))
      assertTrue(
        result == Left(SubtreeError.Cycle(NodeId("a")))
      )
    },
    test(
      "defensive: subtree of a graph whose root is requested twice is deterministic"
    ) {
      val (graph, source) = golden("deep-tree")
      val a = SubtreeReplay.subtree(graph, NodeId("n1"))
      val b = SubtreeReplay.subtree(graph, NodeId("n1"))
      assertTrue(
        a == b,
        a.map(s => SubtreeReplay.verify(s, source)) ==
          b.map(s => SubtreeReplay.verify(s, source))
      )
    }
  )

end SubtreeReplaySpec
