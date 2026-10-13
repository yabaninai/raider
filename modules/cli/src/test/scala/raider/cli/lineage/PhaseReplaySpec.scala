package raider.cli.lineage

import zio.json.*
import zio.test.*

import raider.core.{JobId, RootId, SessionId}
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

/** LIN-07-full acceptance (phased partial replay, packet L53, ADR-027 §2):
  *
  *   - LIN7-F-01 — a two-phase fixture: `phases` lists both phase nodes in
  *     traversal order (roots→children, `(seq, id)`); `replayAll` yields TWO
  *     independent verdicts in the same order — the phase carrying an
  *     unresolved dataRef is `PartiallyResolved` with the exact failing node,
  *     the next phase is still fully `Resolved`; per-child rows cover all three
  *     labels (resolved / obligation / unresolved); every GraphHash comes from
  *     the phase's SubtreeReplay slice. Independence is checked by replaying
  *     the clean phase ALONE and matching its `replayAll` row.
  *   - LIN7-F-02 — a graph with NO phase nodes: `replayPhase` is
  *     `Left(PhaseError.NoPhases)` (even for an existing non-phase id — the
  *     NoPhases gate precedes the lookup) and `replayAll` is empty.
  *   - LIN7-F-03 — a repeat pass over the same graph + source yields IDENTICAL
  *     verdicts (pure functions, determinism by construction).
  *   - Golden integration — the frozen deep-tree carries exactly ONE phase (n0
  *     "plan"): `replayAll` returns the single verdict whose slice IS the whole
  *     tree (GraphHash equal to the original graph's), both model rounds
  *     resolve and the tool stays an obligation.
  *   - Typed errors — an unknown id and an existing NON-phase id are both
  *     `NodeNotFound` (the error names the requested id, never a synthesized
  *     verdict).
  *   - Defensive — a phase node inside a malformed parent-edge cycle
  *     (impossible for a validated graph) stops typed `Cycle` instead of
  *     hanging; `phases` still terminates and `replayAll` stays partial (the
  *     cyclic phase simply has no verdict row — `replayPhase` gives the typed
  *     reason).
  *
  * The two-phase fixture is built programmatically (the frozen golden deep-tree
  * carries one phase — not enough for the 2-phase acceptance); the golden
  * fixture is the REAL frozen document under `contracts/lineage/golden` parsed
  * from disk through the production codecs (never rebuilt from code) — the same
  * discipline as SubtreeReplaySpec.
  */
object PhaseReplaySpec extends ZIOSpecDefault:

  // ----- golden fixture plumbing (same discipline as SubtreeReplaySpec) -------

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
    * payload-driven mapping SubtreeReplaySpec documents). A payload of the
    * wrong variant for a node kind is an honest fixture failure, never a guess.
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
                      JobId(d.childJobId),
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

  // ----- synthetic fixtures (LIN7-F-01/02/03, defensive cycle) -----------------

  /** The recorded source of the synthetic fixtures: the only existing model
    * entry is `d-good`; `d-missing` deliberately has NO entry (the LIN7-F-01
    * unresolved case).
    */
  private def fixtureSource: RecordedSource = RecordedSource(
    LineageDataDocument(
      schemaVersion = 1,
      generator = GeneratorInfo(
        "raider",
        "0.1.0-SNAPSHOT",
        "3.9.0",
        "21"
      ),
      runId = "run-lin7f-001",
      nodes = Vector.empty,
      data = Map(
        "d-good" -> NodeData.ModelCallData(
          model = "mock-coder",
          round = 1,
          prompt = "lin7-f prompt",
          response = "lin7-f response",
          usage = None
        )
      )
    )
  )

  private def phase(
      id: String,
      parent: Option[NodeId],
      seq: Long,
      name: String
  ): SessionNode.Phase =
    SessionNode.Phase(NodeId(id), parent, seq, name, None)

  private def model(
      id: String,
      parent: Option[NodeId],
      seq: Long,
      ref: String
  ): SessionNode.ModelCall =
    SessionNode.ModelCall(
      NodeId(id),
      parent,
      seq,
      0,
      1,
      "mock-coder",
      Vector.empty,
      DataRef(ref)
    )

  private def tool(
      id: String,
      parent: Option[NodeId],
      seq: Long
  ): SessionNode.ToolCall =
    SessionNode.ToolCall(
      NodeId(id),
      parent,
      seq,
      0,
      "call-" + id,
      "read_file",
      RecoveryClass.ReadOnly,
      None,
      Vector.empty,
      None
    )

  private def delegation(
      id: String,
      parent: Option[NodeId],
      seq: Long
  ): SessionNode.Delegation =
    SessionNode.Delegation(
      NodeId(id),
      parent,
      seq,
      0,
      JobId("job-l53"),
      "worker",
      Vector.empty
    )

  /** LIN7-F-01 fixture: TWO phase nodes (roots, seqs 1 and 4) each with their
    * own subtree. Phase p1 carries the broken round (model `m1` with the
    * unresolved ref `d-missing` + a tool obligation); phase p2 is fully clean
    * (a structural delegation + a resolving model). Validated like every
    * SessionGraph — a fixture bug fails loudly.
    */
  private def twoPhaseGraph: SessionGraph =
    SessionGraph(
      RootId("run-lin7f-001"),
      SessionId("sess-run-lin7f-001"),
      Vector(
        phase("p1", None, 1, "plan"),
        model("m1", Some(NodeId("p1")), 2, "d-missing"),
        tool("t1", Some(NodeId("p1")), 3),
        phase("p2", None, 4, "execute"),
        delegation("dg1", Some(NodeId("p2")), 5),
        model("m2", Some(NodeId("p2")), 6, "d-good")
      )
    ).validated.fold(
      err => throw new IllegalStateException("fixture bug: " + err),
      identity
    )

  /** LIN7-F-02 fixture: the same shape WITHOUT any phase node. */
  private def phaselessGraph: SessionGraph =
    SessionGraph(
      RootId("run-lin7f-002"),
      SessionId("sess-run-lin7f-002"),
      Vector(
        model("m1", None, 1, "d-good"),
        model("m2", Some(NodeId("m1")), 2, "d-good")
      )
    ).validated.fold(
      err => throw new IllegalStateException("fixture bug: " + err),
      identity
    )

  private def isSha256Hex(s: String): Boolean =
    s.length == 64 && s.forall(c => c.isDigit || ('a' to 'f').contains(c))

  private val source = fixtureSource

  // ----- spec ------------------------------------------------------------------

  def spec = suite("LIN7-full phased partial replay")(
    test(
      "LIN7-F-01: phases lists both phase nodes in traversal order (roots→children, seq,id)"
    ) {
      val graph = twoPhaseGraph
      assertTrue(
        PhaseReplay.phases(graph) ==
          Vector(NodeId("p1") -> "plan", NodeId("p2") -> "execute")
      )
    },
    test(
      "LIN7-F-01: replayAll — two independent verdicts in traversal order; the unresolved phase does not break the resolved one"
    ) {
      val graph = twoPhaseGraph
      val verdicts = PhaseReplay.replayAll(graph, source)
      val slice1 = SubtreeReplay.subtree(graph, NodeId("p1")).toOption.get
      val slice2 = SubtreeReplay.subtree(graph, NodeId("p2")).toOption.get
      val expectedP1 = PhaseVerdict(
        phaseId = NodeId("p1"),
        name = "plan",
        graphHash = GraphHash(slice1),
        subtree = SubtreeVerdict.PartiallyResolved(
          Vector(SubtreeFailure.Unresolved("m1", "d-missing")),
          Vector(
            DeferredToolCall(
              "t1",
              "read_file",
              RecoveryClass.ReadOnly,
              None
            )
          )
        ),
        children = Vector(
          NodeVerdict.Unresolved("m1", "d-missing"),
          NodeVerdict.Obligation(
            "t1",
            Some(
              DeferredToolCall("t1", "read_file", RecoveryClass.ReadOnly, None)
            )
          )
        )
      )
      val expectedP2 = PhaseVerdict(
        phaseId = NodeId("p2"),
        name = "execute",
        graphHash = GraphHash(slice2),
        subtree = SubtreeVerdict.Resolved(1, Vector.empty),
        children = Vector(
          NodeVerdict.Obligation("dg1", None),
          NodeVerdict.Resolved("m2", "d-good")
        )
      )
      assertTrue(
        verdicts == Vector(expectedP1, expectedP2),
        // the second verdict is fully Resolved DESPITE the first phase's
        // unresolved dataRef — LIN7 "partial" independence
        verdicts(1).subtree == SubtreeVerdict.Resolved(1, Vector.empty),
        // independence: replaying p2 ALONE equals its replayAll row
        PhaseReplay.replayPhase(graph, NodeId("p2"), source) ==
          Right(expectedP2),
        // traversal order of the verdicts == order of `phases`
        verdicts.map(_.phaseId) == PhaseReplay.phases(graph).map(_._1),
        // the phase GraphHash is the SubtreeReplay slice hash, sha256 hex
        verdicts(0).graphHash == GraphHash(slice1),
        verdicts.forall(v => isSha256Hex(v.graphHash))
      )
    },
    test(
      "LIN7-F-02: a graph without phases is Left(NoPhases); replayAll is empty"
    ) {
      val graph = phaselessGraph
      assertTrue(
        PhaseReplay.phases(graph) == Vector.empty,
        // even an EXISTING non-phase id gets NoPhases — the gate precedes lookup
        PhaseReplay.replayPhase(graph, NodeId("m1"), source) ==
          Left(PhaseError.NoPhases),
        PhaseReplay.replayAll(graph, source) == Vector.empty
      )
    },
    test(
      "LIN7-F-03: a repeat pass over the same inputs yields identical verdicts"
    ) {
      val graph = twoPhaseGraph
      val a = PhaseReplay.replayAll(graph, source)
      val b = PhaseReplay.replayAll(graph, source)
      val pa = PhaseReplay.replayPhase(graph, NodeId("p1"), source)
      val pb = PhaseReplay.replayPhase(graph, NodeId("p1"), source)
      assertTrue(
        a.nonEmpty,
        a == b,
        pa == pb
      )
    },
    test(
      "golden deep-tree: one phase (n0 plan); its slice IS the whole tree, hash equals the graph hash, child row Resolved"
    ) {
      val (graph, goldenSource) = golden("deep-tree")
      val verdicts = PhaseReplay.replayAll(graph, goldenSource)
      assertTrue(
        PhaseReplay.phases(graph) == Vector(NodeId("n0") -> "plan"),
        verdicts.map(_.phaseId) == Vector(NodeId("n0")),
        verdicts.map(_.name) == Vector("plan"),
        verdicts.map(_.subtree) == Vector(
          SubtreeVerdict.Resolved(
            2,
            Vector(
              DeferredToolCall(
                "n4",
                "grep_search",
                RecoveryClass.Idempotent,
                Some("d4")
              )
            )
          )
        ),
        // n0's only DIRECT child is the model round n1
        verdicts.map(_.children) ==
          Vector(Vector(NodeVerdict.Resolved("n1", "d1"))),
        // the phase slice covers the whole tree: hash of the phase == hash of the graph
        verdicts.map(_.graphHash) == Vector(GraphHash(graph)),
        PhaseReplay.replayPhase(graph, NodeId("n0"), goldenSource) ==
          Right(verdicts.head)
      )
    },
    test(
      "typed errors: unknown id and existing non-phase id are both NodeNotFound, never a synthesized verdict"
    ) {
      val graph = twoPhaseGraph
      assertTrue(
        PhaseReplay.replayPhase(graph, NodeId("nope"), source) ==
          Left(PhaseError.NodeNotFound(NodeId("nope"))),
        // m1 exists but is a ModelCall, not a phase
        PhaseReplay.replayPhase(graph, NodeId("m1"), source) ==
          Left(PhaseError.NodeNotFound(NodeId("m1")))
      )
    },
    test(
      "defensive: a phase inside a malformed parent cycle stops typed Cycle, not a hang; replayAll stays partial"
    ) {
      // construction never validates: p-bad <-> m parent edges are representable
      val bad = SessionGraph(
        RootId("run-lin7f-003"),
        SessionId("sess-run-lin7f-003"),
        Vector(
          phase("p-ok", None, 1, "ok"),
          phase("p-bad", Some(NodeId("m")), 2, "bad"),
          model("m", Some(NodeId("p-bad")), 3, "d-good")
        )
      )
      assertTrue(
        // traversal from the roots terminates; the cyclic pair is unreachable
        PhaseReplay.phases(bad) == Vector(NodeId("p-ok") -> "ok"),
        // replayAll stays partial: only the reachable phase has a verdict
        PhaseReplay.replayAll(bad, source).map(_.phaseId) ==
          Vector(NodeId("p-ok")),
        // the cyclic phase asked DIRECTLY fails typed, never hangs
        PhaseReplay.replayPhase(bad, NodeId("p-bad"), source) ==
          Left(PhaseError.Cycle(NodeId("p-bad")))
      )
    }
  )

end PhaseReplaySpec
