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
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** LIN5-E acceptance (LIN-05E, ADR-027 §2 replay-recorded default):
  *
  *   - LIN5-E-01 — golden tiny-graph + deep-tree: interpret resolves every
  *     ModelCall node through the recorded data resource (verdict
  *     AllResolvable, the replayed snapshots equal `source.modelOutput`), and a
  *     repeat interpret is identical (case equality + byte-identical render).
  *   - LIN5-E-02 — typed integrity failures: a ModelCall without a usable
  *     dataRef (the frozen graph model has a required `DataRef`, so "no ref" is
  *     the empty ref value) reports `MissingDataRef(nodeId)`; an unknown ref
  *     reports `Unresolved(ref)`; a ref pointing at non-model data reports
  *     `KindMismatch` — never a fabricated answer.
  *   - LIN5-E-03 — ToolCall nodes are obligations for the NEXT card only: the
  *     report carries the per-node `deferred-tool-recompute` status with
  *     graph-level identity (tool, recovery, ref) and NO recorded tool data (no
  *     callIds, no args, no result tails — asserted against the golden
  *     payloads).
  *
  * Golden fixtures are the REAL frozen documents under
  * `contracts/lineage/golden` parsed from disk through the production codecs
  * (never rebuilt from code) — the same discipline as LineageCompileGateSpec.
  */
object ReplayInterpreterSpec extends ZIOSpecDefault:

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
    * payload-driven mapping the W10 compile gate documents; outcome CLASS only
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

  // ----- synthetic graphs (LIN5-E-02) ------------------------------------------

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

  private def singleModel(ref: String, id: String = "m1"): SessionGraph =
    SessionGraph(
      RootId("run-synth-001"),
      SessionId("sess-run-synth-001"),
      Vector(
        SessionNode.ModelCall(
          NodeId(id),
          None,
          1,
          0,
          1,
          "mock-coder",
          Vector.empty,
          DataRef(ref)
        )
      )
    )

  private def countModelCalls(g: SessionGraph): Int =
    g.nodes.count(_.isInstanceOf[SessionNode.ModelCall])

  // ----- spec ------------------------------------------------------------------

  def spec = suite("LIN5-E replay-recorded interpreter")(
    test(
      "LIN5-E-01: golden tiny-graph replays fully from the record, repeat is byte-identical"
    ) {
      val (graph, source) = golden("tiny-graph")
      val r1 = ReplayInterpreter.interpret(graph, source)
      val r2 = ReplayInterpreter.interpret(graph, source)
      val recorded = source.modelOutput("d1").toOption.get
      assertTrue(
        r1.verdict == ReplayVerdict.AllResolvable,
        countModelCalls(graph) == 1,
        r1.replayed.map(_.nodeId) == Vector("n1"),
        r1.replayed.map(_.dataRef) == Vector("d1"),
        r1.replayed.head.data == recorded,
        r1.nodesWalked == graph.nodes.size,
        r1 == r2,
        r1.render == r2.render,
        r1.render.getBytes(StandardCharsets.UTF_8).toSeq ==
          r2.render.getBytes(StandardCharsets.UTF_8).toSeq
      )
    },
    test(
      "LIN5-E-01: golden deep-tree replays both model rounds, delegation/phase stay structural"
    ) {
      val (graph, source) = golden("deep-tree")
      val r1 = ReplayInterpreter.interpret(graph, source)
      val r2 = ReplayInterpreter.interpret(graph, source)
      assertTrue(
        r1.verdict == ReplayVerdict.AllResolvable,
        countModelCalls(graph) == 2,
        r1.replayed.map(_.dataRef) == Vector("d1", "d3"),
        r1.replayed.forall(r =>
          r.data == source.modelOutput(r.dataRef).toOption.get
        ),
        r1.replayed.map(_.nodeId) == Vector("n1", "n3"),
        // structural kinds are walked but contribute no replayed data
        r1.nodesWalked == 5,
        r1.deferredTools.map(_.nodeId) == Vector("n4"),
        r1 == r2,
        r1.render.getBytes(StandardCharsets.UTF_8).toSeq ==
          r2.render.getBytes(StandardCharsets.UTF_8).toSeq
      )
    },
    test(
      "LIN5-E-02: ModelCall without a usable dataRef reports MissingDataRef(nodeId)"
    ) {
      val report =
        ReplayInterpreter.interpret(singleModel(""), syntheticSource)
      val again =
        ReplayInterpreter.interpret(singleModel(""), syntheticSource)
      assertTrue(
        report.verdict == ReplayVerdict.MissingDataRef("m1"),
        report.replayed.isEmpty,
        report == again
      )
    },
    test(
      "LIN5-E-02: unknown dataRef reports Unresolved(ref), not a substitute answer"
    ) {
      val report =
        ReplayInterpreter.interpret(singleModel("nope"), syntheticSource)
      assertTrue(
        report.verdict == ReplayVerdict.Unresolved("nope"),
        report.replayed.isEmpty
      )
    },
    test(
      "LIN5-E-02: dataRef pointing at non-model data reports KindMismatch (typed)"
    ) {
      val report =
        ReplayInterpreter.interpret(singleModel("d2"), syntheticSource)
      report.verdict match
        case ReplayVerdict.KindMismatch(ref, expected, actual) =>
          assertTrue(
            ref == "d2",
            expected == "ModelCallData",
            actual == "ToolCallData",
            report.replayed.isEmpty
          )
        case other =>
          throw new IllegalStateException(
            "expected KindMismatch, got: " + other
          )
    },
    test(
      "LIN5-E-03: golden ToolCall nodes are deferred obligations, never fake results"
    ) {
      val (graph, source) = golden("tiny-graph")
      val report = ReplayInterpreter.interpret(graph, source)
      val toolPayloads = report.render
      val forbidden = Vector(
        // recorded ToolCallData payloads must not leak into the report
        "call-001",
        "call-002",
        "path=README.md",
        "path=docs/notes.md",
        "# Raider development entrypoints",
        "argsSummary",
        "resultTail"
      )
      assertTrue(
        report.verdict == ReplayVerdict.AllResolvable,
        report.deferredTools ==
          Vector(
            DeferredToolCall(
              "n2",
              "read_file",
              RecoveryClass.ReadOnly,
              Some("d2")
            ),
            DeferredToolCall(
              "n3",
              "edit_file",
              RecoveryClass.Mutating,
              Some("d3")
            )
          ),
        forbidden.forall(s => !toolPayloads.contains(s)),
        // the tool source entries exist but were never consulted into the report
        source.has("d2") && source.has("d3")
      )
    },
    test(
      "LIN5-E-03: deferred-tool-recompute status is rendered per tool node"
    ) {
      val (graph, source) = golden("tiny-graph")
      val report = ReplayInterpreter.interpret(graph, source)
      val rendered = report.render
      assertTrue(
        rendered.contains(
          "n2 read_file recovery=ReadOnly status=deferred-tool-recompute"
        ),
        rendered.contains(
          "n3 edit_file recovery=Mutating status=deferred-tool-recompute"
        )
      )
    }
  )

end ReplayInterpreterSpec
