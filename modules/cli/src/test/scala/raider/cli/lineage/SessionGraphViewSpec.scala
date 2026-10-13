package raider.cli.lineage

import zio.*
import zio.json.*
import zio.test.*

import raider.core.{JobId, RootId, SessionId}
import raider.dsl.lineage.proj.{
  LineageRenderer,
  LineageView,
  NodeKind,
  NodeView
}
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

import SessionGraphView.ViewError

/** Golden E2E for the cli-layer SessionGraph → LineageView adapter (L22,
  * ADR-027 wave-2 item 2). Fixtures are the REAL golden documents under
  * `contracts/lineage/golden` — parsed from disk, never rebuilt from code — so
  * the test runs against the frozen wire revision (schemaVersion 1).
  *
  * session-graph.json is an informative companion (nodes carry only
  * id/kind/labels, no seq/atMs/payload), so the SessionGraph is built from the
  * frozen `lineage-data.json` (which carries every model field) and then
  * cross-checked against the companion's control/dataflow edge sets.
  *
  * SGB acceptance (RAI-150, ADR-031 §2.6/§2.4): the adapter POPULATES
  * `NodeView.inputs` from the graph's dataflow edges (unit graph: model → tool,
  * failed tool ← tool, multi-input order; golden deep-tree: the n3 ← n1 edge
  * crossing the delegation subtree) and degrades honestly to empty vectors
  * where the model carries no edges (tiny-graph has none). It also POPULATES
  * `NodeView.outcome` from the recorded `ToolOutcome` class (frozen
  * lineage-data v1 vocabulary: Succeeded/Failed/Cancelled/Timeout; None where
  * the graph carries no outcome), case for case the same mapping as the
  * LineageCompileGateSpec gate adapter — so product and gate agree on the same
  * frozen goldens, and the tiny-graph recorded-Failed tool renders the exact
  * `LineageStep.failed` shape the compile gate pins.
  */
object SessionGraphViewSpec extends ZIOSpecDefault:

  // ----- golden document codecs (test-local, zio-json, schemaVersion-1 shape) –

  private final case class DataDoc(
      schemaVersion: Int,
      runId: String,
      nodes: Vector[DataNode],
      data: Map[String, DataPayload]
  )

  private final case class DataNode(
      id: String,
      kind: String,
      parentId: Option[String],
      seq: Long,
      atMs: Long,
      inputs: Vector[String],
      dataRef: Option[String]
  )

  // One flattened shape for all four payload kinds; every field is optional
  // and only the fields of the node's own kind are consulted. Unknown payload
  // fields (e.g. `usage`) are ignored by zio-json.
  private final case class DataPayload(
      model: Option[String] = None,
      round: Option[Int] = None,
      tool: Option[String] = None,
      callId: Option[String] = None,
      recovery: Option[String] = None,
      outcome: Option[String] = None,
      resultTail: Option[String] = None,
      childJobId: Option[String] = None,
      agent: Option[String] = None,
      name: Option[String] = None,
      note: Option[String] = None
  )

  private final case class CompanionDoc(
      nodes: Vector[CompanionNode],
      edges: Vector[CompanionEdge]
  )

  private final case class CompanionNode(id: String, kind: String)
  private final case class CompanionEdge(from: String, to: String, kind: String)

  private given JsonDecoder[DataDoc] = DeriveJsonDecoder.gen[DataDoc]
  private given JsonDecoder[DataNode] = DeriveJsonDecoder.gen[DataNode]
  private given JsonDecoder[DataPayload] = DeriveJsonDecoder.gen[DataPayload]
  private given JsonDecoder[CompanionDoc] = DeriveJsonDecoder.gen[CompanionDoc]

  private given JsonDecoder[CompanionNode] =
    DeriveJsonDecoder.gen[CompanionNode]

  private given JsonDecoder[CompanionEdge] =
    DeriveJsonDecoder.gen[CompanionEdge]

  // ----- fixture plumbing ----------------------------------------------------

  /** Locates `contracts/lineage/golden/<name>` by walking up from the process
    * cwd (sbt runs tests with the module base as cwd; walking up keeps the
    * fixture honest under any working directory).
    */
  private def goldenDir(name: String): Either[String, Path] =
    val cwd = Path.of("").toAbsolutePath
    // ancestor walk via Option: getParent yields the null literal at the
    // filesystem root, and the DisableSyntax.noNulls literal is avoided by
    // wrapping each step in Option (same sanctioned pattern as
    // LineageRendererSpec) — the yielded dir sequence is unchanged.
    Iterator
      .iterate(Option(cwd))(_.flatMap(dir => Option(dir.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(root =>
        Files.isDirectory(
          root.resolve("contracts").resolve("lineage").resolve("golden")
        )
      )
      .map(
        _.resolve("contracts")
          .resolve("lineage")
          .resolve("golden")
          .resolve(name)
      )
      .toRight("contracts/lineage/golden not found above " + cwd)

  private def readFile(dir: Path, file: String): Either[String, String] =
    try Right(Files.readString(dir.resolve(file), StandardCharsets.UTF_8))
    catch case t: Throwable => Left(file + ": " + t)

  /** The golden pipeline: real JSON → SessionGraph → toView → render (twice).
    * Any parse/validation mismatch is an honest Left, never a fixture guess.
    * The view is carried out too, so the tests pin the POPULATED inputs
    * (SGB-01) against the frozen documents — never rebuilt from code.
    */
  private def goldenE2E(name: String): Either[String, GoldenOut] =
    for
      dir <- goldenDir(name)
      dataRaw <- readFile(dir, "lineage-data.json")
      graphRaw <- readFile(dir, "session-graph.json")
      dataDoc <- dataRaw.fromJson[DataDoc].left.map("lineage-data.json: " + _)
      companion <- graphRaw
        .fromJson[CompanionDoc]
        .left
        .map("session-graph.json: " + _)
      graph <- buildGraph(dataDoc)
      _ <- checkCompanion(graph, companion)
      view <- SessionGraphView.toView(graph).left.map("toView: " + _)
      first <- LineageRenderer.render(view).left.map("render: " + _)
      second <- LineageRenderer.render(view).left.map("render repeat: " + _)
    yield GoldenOut(view, first, second)

  private final case class GoldenOut(
      view: LineageView,
      first: String,
      second: String
  )

  /** Every view node of the tree, pre-order (fixture helper for input pins). */
  private def allNodes(roots: Vector[NodeView]): Vector[NodeView] =
    roots.flatMap(n => n +: allNodes(n.children))

  /** Builds the SessionGraph from the frozen lineage-data document. The
    * contract records only the outcome CLASS for tool calls (no failure
    * reason), so `Failed` maps to the empty reason — the view carries the
    * outcome class only (ADR-031 §2.5), and the fixture invents nothing beyond
    * that documented empty.
    */
  private def buildGraph(doc: DataDoc): Either[String, SessionGraph] =
    def payloadOf(dn: DataNode): DataPayload =
      dn.dataRef.flatMap(doc.data.get).getOrElse(DataPayload())

    def recoveryOf(dn: DataNode, raw: String): Either[String, RecoveryClass] =
      RecoveryClass.values
        .find(_.toString == raw)
        .toRight(
          s"tool_call ${dn.id}: unknown recovery class '$raw'"
        )

    def outcomeOf(
        dn: DataNode,
        raw: Option[String],
        tail: Option[String]
    ): Either[String, Option[ToolOutcome]] =
      raw match
        case None              => Right(None)
        case Some("Succeeded") => Right(Some(ToolOutcome.Succeeded))
        case Some("Cancelled") => Right(Some(ToolOutcome.Cancelled))
        case Some("Timeout")   => Right(Some(ToolOutcome.Timeout))
        // failure reason is not part of lineage-data v1 (resultTail may be null)
        case Some("Failed") =>
          Right(Some(ToolOutcome.Failed(tail.getOrElse(""))))
        case Some(other) =>
          Left(s"tool_call ${dn.id}: unknown tool outcome '$other'")

    doc.nodes
      .foldLeft[Either[String, Vector[SessionNode]]](Right(Vector.empty)) {
        (acc, dn) =>
          acc.flatMap { nodes =>
            val parent = dn.parentId.map(NodeId(_))
            val inputs = dn.inputs.map(NodeId(_))
            val payload = payloadOf(dn)
            dn.kind match
              case "phase" =>
                Right(
                  nodes :+ SessionNode.Phase(
                    NodeId(dn.id),
                    parent,
                    dn.seq,
                    payload.name.getOrElse(""),
                    payload.note
                  )
                )
              case "model_call" =>
                for
                  model <- payload.model.toRight(
                    s"model_call ${dn.id}: model missing"
                  )
                  round <- payload.round.toRight(
                    s"model_call ${dn.id}: round missing"
                  )
                  ref <- dn.dataRef.toRight(
                    s"model_call ${dn.id}: dataRef missing"
                  )
                yield nodes :+ SessionNode.ModelCall(
                  NodeId(dn.id),
                  parent,
                  dn.seq,
                  dn.atMs,
                  round,
                  model,
                  inputs,
                  DataRef(ref)
                )
              case "tool_call" =>
                for
                  tool <- payload.tool.toRight(
                    s"tool_call ${dn.id}: tool missing"
                  )
                  recRaw <- payload.recovery.toRight(
                    s"tool_call ${dn.id}: recovery missing"
                  )
                  recovery <- recoveryOf(dn, recRaw)
                  outcome <- outcomeOf(dn, payload.outcome, payload.resultTail)
                yield nodes :+ SessionNode.ToolCall(
                  NodeId(dn.id),
                  parent,
                  dn.seq,
                  dn.atMs,
                  payload.callId.getOrElse(""),
                  tool,
                  recovery,
                  outcome,
                  inputs,
                  dn.dataRef.map(DataRef(_))
                )
              case "delegation" =>
                for
                  jobId <- payload.childJobId.toRight(
                    s"delegation ${dn.id}: childJobId missing"
                  )
                  agent <- payload.agent.toRight(
                    s"delegation ${dn.id}: agent missing"
                  )
                yield nodes :+ SessionNode.Delegation(
                  NodeId(dn.id),
                  parent,
                  dn.seq,
                  dn.atMs,
                  JobId(jobId),
                  agent,
                  inputs
                )
              case other => Left(s"unknown golden node kind: $other")
          }
      }
      .map(nodes =>
        SessionGraph(RootId(doc.runId), SessionId("sess-" + doc.runId), nodes)
      )

  /** Cross-checks the built graph against the informative session-graph.json:
    * same node ids in the same order, `control` edges == parentId relations,
    * `dataflow` edges == inputs relations (order-insensitive on edges).
    */
  private def checkCompanion(
      g: SessionGraph,
      companion: CompanionDoc
  ): Either[String, Unit] =
    val modelIds = g.nodes.map(_.id.value)
    val companionIds = companion.nodes.map(_.id)
    if modelIds != companionIds then
      Left(
        "session-graph.json node set/order differs from lineage-data.json: " +
          modelIds.mkString(",") + " vs " + companionIds.mkString(",")
      )
    else
      val controlCompanion = companion.edges
        .collect { case CompanionEdge(f, t, "control") => f -> t }
        .sortBy(_._2)
      val controlModel = g.nodes
        .collect {
          case n if n.parent.isDefined => n.parent.get.value -> n.id.value
        }
        .sortBy(_._2)
      val dataflowCompanion = companion.edges
        .collect { case CompanionEdge(f, t, "dataflow") => f -> t }
        .sortBy(_._2)
      val dataflowModel = g.nodes
        .flatMap(n => n.inputs.map(i => i.value -> n.id.value))
        .sortBy(_._2)
      if controlCompanion != controlModel then
        Left(
          "control edges differ: companion " + controlCompanion + " vs model " + controlModel
        )
      else if dataflowCompanion != dataflowModel then
        Left(
          "dataflow edges differ: companion " + dataflowCompanion + " vs model " + dataflowModel
        )
      else Right(())

  // ----- unit fixture: one minimal graph exercising ALL four kinds -----------
  //
  // Dataflow edges (SGB-01/SGB-02): n2 ← n1 (model response feeds the tool
  // args), n3 ← n2 (cross-node tool → tool; n3 is the FAILED tool), n5 ←
  // (n1, n3) (multi-input, edge order preserved). n0 (phase), n1 (first
  // model), n4 (delegation) carry no edges — honest empty inputs.

  private def unitGraph(): SessionGraph =
    SessionGraph(
      RootId("run-unit-001"),
      SessionId("sess-unit-001"),
      Vector(
        SessionNode.Phase(NodeId("n0"), None, 1L, "investigate", None),
        SessionNode.ModelCall(
          NodeId("n1"),
          Some(NodeId("n0")),
          2L,
          120L,
          1,
          "mock-coder",
          Vector.empty,
          DataRef("d1")
        ),
        SessionNode.ToolCall(
          NodeId("n2"),
          Some(NodeId("n1")),
          3L,
          340L,
          "call-1",
          "read_file",
          RecoveryClass.ReadOnly,
          Some(ToolOutcome.Succeeded),
          Vector(NodeId("n1")),
          Some(DataRef("d2"))
        ),
        SessionNode.ToolCall(
          NodeId("n3"),
          Some(NodeId("n1")),
          4L,
          510L,
          "call-2",
          "edit_file",
          RecoveryClass.Mutating,
          Some(ToolOutcome.Failed("exit 1")),
          Vector(NodeId("n2")),
          None
        ),
        SessionNode.Delegation(
          NodeId("n4"),
          Some(NodeId("n2")),
          5L,
          700L,
          JobId("job-77"),
          "worker",
          Vector.empty
        ),
        SessionNode.ModelCall(
          NodeId("n5"),
          Some(NodeId("n4")),
          6L,
          900L,
          2,
          "mock-coder",
          Vector(NodeId("n1"), NodeId("n3")),
          DataRef("d5")
        )
      )
    )

  /** Finds a view node by id anywhere in the tree (pre-order); the unit and
    * golden ids are unique by graph validation, so at most one match exists.
    */
  private def findNode(roots: Vector[NodeView], id: String): Option[NodeView] =
    roots.collectFirst { case n if n.id == id => n }.orElse {
      val kids = roots.flatMap(_.children)
      if kids.isEmpty then None else findNode(kids, id)
    }

  // Expected view mirrors unitGraph's dataflow edges 1:1 (SGB-01): n2 ← n1,
  // n3 ← n2, n5 ← (n1, n3); honest empties on n0/n1/n4 (SGB-02). Outcomes are
  // the recorded classes (SGB-01): n2 Succeeded, n3 Failed; every other kind
  // carries None.
  private val unitExpected: Vector[NodeView] =
    Vector(
      NodeView(
        "n0",
        NodeKind.Phase("investigate"),
        None,
        Vector(
          NodeView(
            "n1",
            NodeKind.ModelCall("mock-coder", 1),
            Some("n0"),
            Vector(
              NodeView(
                "n2",
                NodeKind.ToolCall("read_file", "ReadOnly"),
                Some("n1"),
                Vector(
                  NodeView(
                    "n4",
                    NodeKind.Delegation("worker"),
                    Some("n2"),
                    Vector(
                      NodeView(
                        "n5",
                        NodeKind.ModelCall("mock-coder", 2),
                        Some("n4"),
                        Vector.empty,
                        Some("d5"),
                        inputs = Vector("n1", "n3"),
                        outcome = None
                      )
                    ),
                    None,
                    inputs = Vector.empty,
                    outcome = None
                  )
                ),
                Some("d2"),
                inputs = Vector("n1"),
                outcome = Some("Succeeded")
              ),
              NodeView(
                "n3",
                NodeKind.ToolCall("edit_file", "Mutating"),
                Some("n1"),
                Vector.empty,
                None,
                inputs = Vector("n2"),
                outcome = Some("Failed")
              )
            ),
            Some("d1"),
            inputs = Vector.empty,
            outcome = None
          )
        ),
        None,
        inputs = Vector.empty,
        outcome = None
      )
    )

  /** Typed-failure probe: Some(detail) iff the adapter returned the typed
    * Inconsistent error (never a crash, never a different variant).
    */
  private def inconsistentDetail(result: Either[ViewError, ?]): Option[String] =
    result.fold(
      {
        case ViewError.Inconsistent(detail) => Some(detail)
        case ViewError.UnknownKind(_)       => None
      },
      _ => None
    )

  // ----- expectations for the golden renders (derived from the frozen payloads)
  //
  // RAI-150: the pipeline renders through the REAL `SessionGraphView.toView`,
  // which populates `NodeView.inputs` AND `NodeView.outcome` (case for case
  // the RAI-141 GraphToView mapping), so these expectations are EXACTLY the
  // gate-pinned shapes of LineageCompileGateSpec for the same frozen goldens:
  // the tiny-graph recorded-Failed tool (n3, edit_file) renders the typed
  // `LineageStep.failed` short-circuit with the bounded reason phrase, and
  // the deep-tree n3 ← n1 cross-scope edge threads as the id literal.

  private val tinyExpectedNames = Vector(
    "object lineage_run_tiny_001:",
    "val phase_investigate: Program[Unit, Vector[StepReport]] =",
    "val program: Program[Unit, Vector[StepReport]] =",
    "LineageStep.phase(\"n0\", \"investigate\", None, Vector.empty, nesting = 1)",
    "LineageStep.model(\"n1\", \"mock-coder\", 1, Some(\"d1\"), Vector.empty, nesting = 1)",
    "LineageStep.tool(\"n2\", \"read_file\", \"ReadOnly\", Some(\"d2\"), Vector.empty, nesting = 1)",
    "LineageStep.failed(\"n3\", NodeKind.ToolCall(\"edit_file\", \"Mutating\"), Some(\"d3\"),",
    "reason = \"recorded outcome: Failed\"",
    "import raider.dsl.lineage.proj.runtime.{LineageStep, StepReport}"
  )

  private val tinyExpectedRefs =
    Vector("// data: d1", "// data: d2", "// data: d3")

  // Payload-only strings: prompts/responses/args/results/callIds live in the
  // data resource — they must never leak into the generated program.
  private val tinyForbiddenInline = Vector(
    "Find the entry point",
    "The entry point is raider.cli.Main",
    "path=README.md",
    "# Raider development entrypoints",
    "call-001",
    "call-002"
  )

  private val deepExpectedNames = Vector(
    "object lineage_run_deep_001:",
    "val phase_plan: Program[Unit, Vector[StepReport]] =",
    "val delegation_worker: Program[Unit, Vector[StepReport]] =",
    "val program: Program[Unit, Vector[StepReport]] =",
    "LineageStep.model(\"n1\", \"mock-coder\", 1, Some(\"d1\"), Vector.empty, nesting = 1)",
    "LineageStep.model(\"n3\", \"mock-coder\", 1, Some(\"d3\"), Vector(\"n1\"), nesting = 2)",
    "LineageStep.tool(\"n4\", \"grep_search\", \"Idempotent\", Some(\"d4\"), Vector.empty, nesting = 2)"
  )

  private val deepExpectedRefs =
    Vector("// data: d1", "// data: d3", "// data: d4")

  private val deepForbiddenInline = Vector(
    "Break the task",
    "Implement the subtask",
    "I’m preparing the change",
    "pattern=TODO limit=20",
    "nightly delegation dry-run",
    "call-091",
    "job-0042"
  )

  // ----- spec ----------------------------------------------------------------

  def spec = suite("SessionGraphView L22")(
    test(
      "VIEW-A-01: all four kinds map 1:1 into NodeKind (minimal unit graph)"
    ) {
      val result = SessionGraphView.toView(unitGraph())
      val repeat = SessionGraphView.toView(unitGraph())
      // pure + deterministic: content of two conversions is structurally equal
      // (LineageView itself is a trait instance — compare runId + roots)
      val contentA = result.map(v => (v.runId, v.roots))
      val contentB = repeat.map(v => (v.runId, v.roots))
      assertTrue(
        result.map(_.runId) == Right("run-unit-001"),
        result.map(_.roots) == Right(unitExpected),
        contentA == contentB,
        // adapter output renders end-to-end (delegation/phase become named
        // defs building sub-Programs — RAI-141 shape)
        result
          .flatMap(LineageRenderer.render)
          .map(
            _.contains(
              "val delegation_worker: Program[Unit, Vector[StepReport]] ="
            )
          ) ==
          Right(true)
      )
    },
    test(
      "VIEW-A-01: RecoveryClass taxonomy maps to the frozen contract strings"
    ) {
      assertTrue(
        SessionGraphView.recoveryName(RecoveryClass.ReadOnly) == "ReadOnly",
        SessionGraphView.recoveryName(RecoveryClass.Idempotent) == "Idempotent",
        SessionGraphView.recoveryName(RecoveryClass.Mutating) == "Mutating"
      )
    },
    test(
      "SGB-01: NodeView.inputs populated from dataflow edges, edge order preserved (unit graph)"
    ) {
      val view = SessionGraphView
        .toView(unitGraph())
        .fold(
          e => throw new IllegalStateException("toView failed: " + e),
          identity
        )
      val node = (id: String) =>
        findNode(view.roots, id).getOrElse(
          throw new IllegalStateException("view node missing: " + id)
        )
      assertTrue(
        // model response feeds the tool call (model → tool)
        node("n2").inputs == Vector("n1"),
        // cross-node tool → tool edge: the FAILED tool consumes read_file's output
        node("n3").inputs == Vector("n2"),
        // multi-input node: both edges, graph edge order (n1 before n3)
        node("n5").inputs == Vector("n1", "n3"),
        // inputs are the exact node ids — never payloads/dataRefs
        node("n2").dataRef == Some("d2") && node("n2").inputs == Vector("n1")
      )
    },
    test(
      "SGB-01: NodeView.outcome populated from the recorded ToolCall class (unit graph, incl. a failed tool)"
    ) {
      val view = SessionGraphView
        .toView(unitGraph())
        .fold(
          e => throw new IllegalStateException("toView failed: " + e),
          identity
        )
      val node = (id: String) =>
        findNode(view.roots, id).getOrElse(
          throw new IllegalStateException("view node missing: " + id)
        )
      val rendered = LineageRenderer
        .render(view)
        .fold(
          e => throw new IllegalStateException("render failed: " + e),
          identity
        )
      assertTrue(
        // the succeeded tool carries its class string...
        node("n2").outcome == Some("Succeeded"),
        // ...and the FAILED tool carries the failed class the renderer maps
        // to the LineageStep.failed short-circuit (ADR-031 §2.4)
        node("n3").outcome == Some("Failed"),
        // every non-tool kind carries None — no invented outcomes (SGB-02)
        node("n0").outcome.isEmpty,
        node("n1").outcome.isEmpty,
        node("n4").outcome.isEmpty,
        node("n5").outcome.isEmpty,
        // product adapter and gate adapter agree: the same recorded class
        // renders the exact LineageStep.failed shape the compile gate pins
        // (LineageCompileGateSpec tinyExpectedNames), with the bounded
        // payload-free reason phrase — the Failed reason "exit 1" (graph-side
        // only) never crosses the view boundary (ADR-031 §2.5)
        rendered.contains("LineageStep.failed(\"n3\""),
        rendered.contains("reason = \"recorded outcome: Failed\""),
        !rendered.contains("exit 1")
      )
    },
    test(
      "SGB-02: empty/absent dataflow edges degrade honestly (empty inputs, no invented values)"
    ) {
      val graph = unitGraph()
      val view = SessionGraphView
        .toView(graph)
        .fold(
          e => throw new IllegalStateException("toView failed: " + e),
          identity
        )
      val node = (id: String) =>
        findNode(view.roots, id).getOrElse(
          throw new IllegalStateException("view node missing: " + id)
        )
      val modelIds = graph.nodes.map(_.id.value).toSet
      assertTrue(
        // phase: no inputs by definition
        node("n0").inputs.isEmpty,
        // first model call: no upstream edges in the graph → empty, not invented
        node("n1").inputs.isEmpty,
        // delegation without edges: empty in the model, empty in the view
        node("n4").inputs.isEmpty,
        // every populated input references an existing node id
        allNodes(view.roots).forall(_.inputs.forall(modelIds.contains))
      )
    },
    test(
      "SGB-03: conversion is pure and deterministic (repeat ×3 identical, graph untouched)"
    ) {
      val graph = unitGraph()
      val snapshot = graph.nodes.map(n => n.id -> n.inputs)
      val a = SessionGraphView.toView(graph).map(v => (v.runId, v.roots))
      val b = SessionGraphView.toView(graph).map(v => (v.runId, v.roots))
      val c = SessionGraphView.toView(graph).map(v => (v.runId, v.roots))
      val untouched = graph.nodes.map(n => n.id -> n.inputs) == snapshot
      assertTrue(a == b, b == c, untouched)
    },
    test(
      "VIEW-A-02: golden tiny-graph E2E renders byte-identical, data-free text"
    ) {
      for out <- ZIO
          .fromEither(goldenE2E("tiny-graph"))
          .orDieWith(err =>
            new IllegalStateException("golden pipeline failed: " + err)
          )
      yield {
        val node = (id: String) =>
          findNode(out.view.roots, id).getOrElse(
            throw new IllegalStateException(
              "golden tiny-graph: view node " + id + " missing"
            )
          )
        assertTrue(
          out.first == out.second,
          out.first.getBytes(StandardCharsets.UTF_8).toSeq ==
            out.second.getBytes(StandardCharsets.UTF_8).toSeq,
          tinyExpectedNames.forall(out.first.contains),
          tinyExpectedRefs.forall(out.first.contains),
          tinyForbiddenInline.forall(s => !out.first.contains(s)),
          // frozen fixture has NO dataflow edges → every view input degrades
          // honestly to the empty vector (SGB-02 on real wire data)
          allNodes(out.view.roots).forall(_.inputs.isEmpty),
          // recorded outcome classes reach the view from the frozen wire
          // (SGB-01 on real data): the failed tool carries Failed, the
          // succeeded tool Succeeded, the model None
          node("n3").outcome == Some("Failed"),
          node("n2").outcome == Some("Succeeded"),
          node("n1").outcome.isEmpty
        )
      }
    },
    test(
      "VIEW-A-02: golden deep-tree E2E renders byte-identical, data-free text"
    ) {
      for out <- ZIO
          .fromEither(goldenE2E("deep-tree"))
          .orDieWith(err =>
            new IllegalStateException("golden pipeline failed: " + err)
          )
      yield {
        val n3 = findNode(out.view.roots, "n3").getOrElse(
          throw new IllegalStateException(
            "golden deep-tree: view node n3 missing"
          )
        )
        val n4 = findNode(out.view.roots, "n4").getOrElse(
          throw new IllegalStateException(
            "golden deep-tree: view node n4 missing"
          )
        )
        assertTrue(
          out.first == out.second,
          out.first.getBytes(StandardCharsets.UTF_8).toSeq ==
            out.second.getBytes(StandardCharsets.UTF_8).toSeq,
          deepExpectedNames.forall(out.first.contains),
          deepExpectedRefs.forall(out.first.contains),
          deepForbiddenInline.forall(s => !out.first.contains(s)),
          // the frozen fixture's single dataflow edge (n3 consumes n1's output,
          // crossing the delegation subtree boundary) reaches the view 1:1
          n3.inputs == Vector("n1"),
          // the tool's null outcome on the frozen wire degrades honestly to
          // None — no invented outcome class (SGB-02 on real wire data)
          n4.outcome.isEmpty
        )
      }
    },
    test("VIEW-A-03: duplicate node id → typed ViewError.Inconsistent") {
      val g = SessionGraph(
        RootId("run-bad-1"),
        SessionId("sess-bad-1"),
        Vector(
          SessionNode.Phase(NodeId("n0"), None, 1L, "a", None),
          SessionNode.Phase(NodeId("n0"), None, 2L, "b", None)
        )
      )
      val result = SessionGraphView.toView(g)
      assertTrue(
        result.isLeft,
        inconsistentDetail(result).exists(_.contains("n0"))
      )
    },
    test("VIEW-A-03: missing parent → typed ViewError.Inconsistent") {
      val g = SessionGraph(
        RootId("run-bad-2"),
        SessionId("sess-bad-2"),
        Vector(
          SessionNode.Phase(NodeId("n1"), Some(NodeId("ghost")), 1L, "a", None)
        )
      )
      val result = SessionGraphView.toView(g)
      assertTrue(
        result.isLeft,
        inconsistentDetail(result).exists(_.contains("ghost"))
      )
    },
    test("VIEW-A-03: dataflow cycle → typed ViewError.Inconsistent, no hang") {
      val g = SessionGraph(
        RootId("run-bad-3"),
        SessionId("sess-bad-3"),
        Vector(
          SessionNode.ModelCall(
            NodeId("c1"),
            None,
            1L,
            0L,
            1,
            "m",
            Vector(NodeId("c2")),
            DataRef("d1")
          ),
          SessionNode.ModelCall(
            NodeId("c2"),
            None,
            2L,
            10L,
            1,
            "m",
            Vector(NodeId("c1")),
            DataRef("d2")
          )
        )
      )
      val result = SessionGraphView.toView(g)
      assertTrue(
        result.isLeft,
        inconsistentDetail(result).exists(_.contains("c1"))
      )
    },
    test(
      "VIEW-A-03: parent-order violation (control cycle) → typed ViewError.Inconsistent"
    ) {
      val g = SessionGraph(
        RootId("run-bad-4"),
        SessionId("sess-bad-4"),
        Vector(
          SessionNode.Phase(NodeId("x"), Some(NodeId("y")), 1L, "child", None),
          SessionNode.Phase(NodeId("y"), None, 2L, "parent", None)
        )
      )
      val result = SessionGraphView.toView(g)
      assertTrue(
        result.isLeft,
        inconsistentDetail(result).exists(d =>
          d.contains("x") && d.contains("y")
        )
      )
    }
  )
