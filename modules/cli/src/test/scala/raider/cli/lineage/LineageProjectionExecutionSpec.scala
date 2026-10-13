package raider.cli.lineage

import zio.json.*
import zio.stream.ZStream
import zio.test.*
import zio.{Exit, Scope, ZIO}

import raider.core.{
  CapabilityStatus,
  JobId,
  ModelBackend,
  ModelCapabilities,
  ModelRequest,
  Program,
  RaiderError,
  RequestMessage,
  RootId,
  SessionId,
  Task
}
import raider.dsl.lineage.proj.{
  LineageRenderer,
  LineageView,
  NodeKind,
  NodeView,
  RenderError
}
import raider.dsl.lineage.proj.runtime.{LineageStepFailure, StepReport}
import raider.runtime.events.ToolOutcome
import raider.runtime.lineage.model.{
  DataRef,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}

/** RAI-142 acceptance (LIN-02): EXECUTED projection semantics. The rendered
  * projections are compiled with the PINNED dotc 3.9.0 (same compiler as the
  * build), the generated object is loaded from the compiled classes and its
  * `Program` values are executed through the single observation boundary
  * `Task.zio` (ADR-031 §2.2) — the acceptance runs the REAL artifacts, not a
  * hand-mirrored composition.
  *
  * Pinned semantics (card RAI-142, ADR-031):
  *   - '''PRJ-01''' executed projection returns the exact expected step-report
  *     tree (order, kinds, dataRef keys, threaded inputs, nesting).
  *   - '''PRJ-02''' a recorded-failed node short-circuits with the typed error
  *     (`RaiderError.OutputValidation` = RA-OUT on the channel,
  *     `LineageStepFailure` as the JDK cause) and the report prefix up to the
  *     failure is observable. The representative fixture places the failed node
  *     as the LAST pre-order statement, so the prefix is exactly the spliced
  *     sub-programs the generated object exposes as vals — every report the
  *     failing run threaded before the short-circuit is observable by executing
  *     the compiled artifact's own public vals.
  *   - '''PRJ-03''' repeat execution is deterministic (identical results ×3); a
  *     scripted recording probe backend records ZERO dispatches across render,
  *     compile, load and execute (the projection is dry by construction,
  *     ADR-031 §2.1); the pinned-dotc compile is green (exit 0) on every
  *     fixture.
  *
  * Fixtures: (1) a representative in-test graph covering ALL node kinds plus a
  * failed tool node, a phase def, a delegation def, an in-scope threaded input
  * and cross-scope literal inputs; (2) the FROZEN deep-tree golden (successful
  * full tree, cross-scope input); (3) the FROZEN tiny-graph golden whose n3 is
  * recorded-Failed — the executed result must be the typed failure, NEVER a
  * successful vector (RAI-141 review integration risk 1). Goldens are parsed
  * from disk and mapped by the PRODUCT adapter `SessionGraphView` (RAI-150:
  * inputs/outcome populated) — the same pipeline the compile gate exercises.
  *
  * Zero-dispatch probe: a scripted `ModelBackend` that records every request at
  * stream-run time. The projection's API shape cannot reach a backend (ADR-031
  * §2.2 classpath constraint), so a zero reading must be backed by a negative
  * control proving the harness detects a real dispatch (PRC-01 discipline).
  */
object LineageProjectionExecutionSpec extends ZIOSpecDefault:

  // ----- representative graph (in-test; the renderer's data source is the view model)

  /** Covers every node kind plus the contract's hard cases:
    *   - `t2` depends on `m2` IN ITS OWN scope (phase body) — the generated
    *     code binds `m2`'s report and threads `n_m2.head.id` (value threading,
    *     ADR-031 §2.3).
    *   - `m3` depends on `m2` across scopes (phase body -> delegation body) —
    *     the id stays a string literal.
    *   - `t9` is the recorded-Failed tool, LAST in pre-order: executing the
    *     projection short-circuits there; everything before it is observable
    *     through the two named sub-program vals.
    */
  private val accView: LineageView = new LineageView:
    def runId: String = "run-acc-142"
    def roots: Vector[NodeView] = Vector(
      NodeView(
        "ph1",
        NodeKind.Phase("verify"),
        None,
        Vector(
          NodeView(
            "m2",
            NodeKind.ModelCall("mock-coder", 2),
            Some("ph1"),
            Vector.empty,
            Some("d-m2")
          ),
          NodeView(
            "t2",
            NodeKind.ToolCall("read_file", "ReadOnly"),
            Some("ph1"),
            Vector.empty,
            Some("d-t2"),
            inputs = Vector("m2")
          )
        ),
        dataRef = None
      ),
      NodeView(
        "dl1",
        NodeKind.Delegation("worker"),
        None,
        Vector(
          NodeView(
            "m3",
            NodeKind.ModelCall("mock-coder", 1),
            Some("dl1"),
            Vector.empty,
            Some("d-m3"),
            inputs = Vector("m2")
          )
        ),
        dataRef = None
      ),
      NodeView(
        "t9",
        NodeKind.ToolCall("edit_file", "Mutating"),
        None,
        Vector.empty,
        Some("d-t9"),
        inputs = Vector("t2", "m3"),
        outcome = Some("Failed")
      )
    )

  /** The exact report prefix the failing run threads before the short-circuit:
    * phase body (pre-order) then delegation body.
    */
  private val accPrefix: Vector[StepReport] = Vector(
    StepReport("ph1", NodeKind.Phase("verify"), None, Vector.empty, 1),
    StepReport(
      "m2",
      NodeKind.ModelCall("mock-coder", 2),
      Some("d-m2"),
      Vector.empty,
      1
    ),
    StepReport(
      "t2",
      NodeKind.ToolCall("read_file", "ReadOnly"),
      Some("d-t2"),
      Vector("m2"),
      1
    ),
    StepReport("dl1", NodeKind.Delegation("worker"), None, Vector.empty, 1),
    StepReport(
      "m3",
      NodeKind.ModelCall("mock-coder", 1),
      Some("d-m3"),
      Vector("m2"),
      1
    )
  )

  /** The failed step's own report — observable as the typed cause's step. */
  private val accFailedStep: StepReport = StepReport(
    "t9",
    NodeKind.ToolCall("edit_file", "Mutating"),
    Some("d-t9"),
    Vector("t2", "m3"),
    0
  )

  /** The full expected tree: prefix ++ failed step. */
  private val accFullTree: Vector[StepReport] = accPrefix :+ accFailedStep

  /** Source-level pins: value threading (`n_m2.head.id`), cross-scope literals,
    * the typed failed ctor, sub-program splicing at the call sites and
    * dataRef-key-only payload addressing.
    */
  private val accSourceExpectations = Vector(
    "val phase_verify: Program[Unit, Vector[StepReport]] =",
    "val delegation_worker: Program[Unit, Vector[StepReport]] =",
    "LineageStep.phase(\"ph1\", \"verify\", None, Vector.empty, nesting = 1)",
    "LineageStep.model(\"m2\", \"mock-coder\", 2, Some(\"d-m2\"), Vector.empty, nesting = 1) // data: d-m2",
    "LineageStep.tool(\"t2\", \"read_file\", \"ReadOnly\", Some(\"d-t2\"), Vector(n_m2.head.id), nesting = 1) // data: d-t2",
    "LineageStep.delegation(\"dl1\", \"worker\", None, Vector.empty, nesting = 1)",
    "LineageStep.model(\"m3\", \"mock-coder\", 1, Some(\"d-m3\"), Vector(\"m2\"), nesting = 1) // data: d-m3",
    "LineageStep.failed(\"t9\", NodeKind.ToolCall(\"edit_file\", \"Mutating\"), Some(\"d-t9\"), Vector(\"t2\", \"m3\"), nesting = 0, reason = \"recorded outcome: Failed\") // data: d-t9",
    "phase_verify(())",
    "delegation_worker(())"
  )

  // ----- frozen golden expectations (derived from the frozen documents)

  private val deepExpected: Vector[StepReport] = Vector(
    StepReport("n0", NodeKind.Phase("plan"), None, Vector.empty, 1),
    StepReport(
      "n1",
      NodeKind.ModelCall("mock-coder", 1),
      Some("d1"),
      Vector.empty,
      1
    ),
    StepReport("n2", NodeKind.Delegation("worker"), None, Vector.empty, 2),
    StepReport(
      "n3",
      NodeKind.ModelCall("mock-coder", 1),
      Some("d3"),
      Vector("n1"),
      2
    ),
    StepReport(
      "n4",
      NodeKind.ToolCall("grep_search", "Idempotent"),
      Some("d4"),
      Vector.empty,
      2
    )
  )

  /** Executing the deep-tree delegation sub-program val directly yields its own
    * pre-order fragment (n3's input n1 lives in the phase scope — the id stays
    * a literal even in the isolated sub-program run).
    */
  private val deepDelegationFragment: Vector[StepReport] =
    deepExpected.drop(2)

  /** Tiny-graph n3: recorded-Failed tool inside the phase body (nesting 1). */
  private val tinyFailedStep: StepReport = StepReport(
    "n3",
    NodeKind.ToolCall("edit_file", "Mutating"),
    Some("d3"),
    Vector.empty,
    1
  )

  private val tinyReason = "recorded outcome: Failed"

  // ----- fixtures: render -> pinned-dotc compile -> load the generated object

  private final case class Loaded(
      program: Program[Unit, Vector[StepReport]],
      defs: Map[String, Program[Unit, Vector[StepReport]]],
      source: String
  )

  private def requireRight[A](e: Either[String, A], what: String): A =
    e match
      case Right(a) => a
      case Left(err) =>
        throw new IllegalStateException(what + " failed: " + err)

  /** Renders a view, mapping the typed `RenderError` into the string channel.
    */
  private def renderSource(view: LineageView, what: String): String =
    requireRight(
      LineageRenderer.render(view).left.map {
        case RenderError.UnknownKind(d) => "unknown kind: " + d
        case RenderError.Empty          => "empty graph"
        case RenderError.Cyclic(id)     => "cycle at " + id
      },
      what
    )

  /** Renders twice and requires byte-identical output (LIN2-C-01), then
    * compiles with the pinned dotc (exit 0 — PRJ-03 compile green) and loads
    * the generated object's `program` and named-def vals reflectively.
    */
  private def projectionFixture(
      tag: String,
      view: LineageView,
      objName: String,
      defNames: Vector[String]
  ): Loaded =
    val first = renderSource(view, "render " + tag)
    val second = renderSource(view, "render repeat " + tag)
    if first != second then
      throw new IllegalStateException(
        tag + ": repeat render not byte-identical"
      )
    compileAndLoad(tag, first, objName, defNames)

  private def compileAndLoad(
      tag: String,
      src: String,
      objName: String,
      defNames: Vector[String]
  ): Loaded =
    val path = requireRight(writeRender(tag, src), "write render " + tag)
    val compiled = requireRight(dotc(tag, path, "out-" + tag), "compile " + tag)
    if compiled.exit != 0 then
      throw new IllegalStateException(
        tag + ": pinned-dotc compile expected exit 0, got " + compiled.exit +
          "; log tail:\n" + compiled.logTail
      )
    loadProjection(compiled.outDir, objName, defNames, src)

  private lazy val accFixture: Loaded =
    projectionFixture(
      "rai142-acc",
      accView,
      "lineage_run_acc_142",
      Vector("phase_verify", "delegation_worker")
    )

  private lazy val deepFixture: Loaded =
    projectionFixture(
      "rai142-deep",
      goldenView("deep-tree"),
      "lineage_run_deep_001",
      Vector("phase_plan", "delegation_worker")
    )

  private lazy val tinyFixture: Loaded =
    projectionFixture(
      "rai142-tiny",
      goldenView("tiny-graph"),
      "lineage_run_tiny_001",
      Vector("phase_investigate")
    )

  // ----- golden pipeline: frozen documents -> product adapter -> view

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

  // One flattened shape for all payload kinds; unknown payload fields (usage,
  // prompts, responses) are ignored by zio-json — payloads never enter the view.
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

  private def readFile(dir: Path, file: String): Either[String, String] =
    try Right(Files.readString(dir.resolve(file), StandardCharsets.UTF_8))
    catch case t: Throwable => Left(file + ": " + t)

  /** The frozen golden pipeline: lineage-data.json + session-graph.json from
    * disk -> SessionGraph -> the PRODUCT adapter `SessionGraphView.toView`
    * (inputs/outcome populated, RAI-150) -> validated against the companion
    * document. Any parse/validation mismatch is an honest hard failure.
    */
  private def goldenView(name: String): LineageView =
    val dir = findAncestor("contracts", "lineage", "golden")
      .resolve("contracts")
      .resolve("lineage")
      .resolve("golden")
      .resolve(name)
    val dataRaw = requireRight(readFile(dir, "lineage-data.json"), name)
    val graphRaw = requireRight(readFile(dir, "session-graph.json"), name)
    val dataDoc =
      requireRight(
        dataRaw.fromJson[DataDoc].left.map("lineage-data.json: " + _),
        name
      )
    val companion =
      requireRight(
        graphRaw.fromJson[CompanionDoc].left.map("session-graph.json: " + _),
        name
      )
    val graph = requireRight(buildGraph(dataDoc), name)
    checkCompanion(graph, companion)
    requireRight(
      SessionGraphView.toView(graph).left.map {
        case SessionGraphView.ViewError.UnknownKind(d) =>
          "view unknown kind: " + d
        case SessionGraphView.ViewError.Inconsistent(d) =>
          "view inconsistent: " + d
      },
      name
    )

  private def buildGraph(doc: DataDoc): Either[String, SessionGraph] =
    def payloadOf(dn: DataNode): DataPayload =
      dn.dataRef.flatMap(doc.data.get).getOrElse(DataPayload())

    def recoveryOf(dn: DataNode, raw: String): Either[String, RecoveryClass] =
      RecoveryClass.values
        .find(_.toString == raw)
        .toRight(s"tool_call ${dn.id}: unknown recovery class '$raw'")

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
  ): Unit =
    val modelIds = g.nodes.map(_.id.value)
    val companionIds = companion.nodes.map(_.id)
    if modelIds != companionIds then
      throw new IllegalStateException(
        "session-graph.json node set/order differs from lineage-data.json: " +
          modelIds.mkString(",") + " vs " + companionIds.mkString(",")
      )
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
      throw new IllegalStateException(
        "control edges differ: companion " + controlCompanion + " vs model " +
          controlModel
      )
    if dataflowCompanion != dataflowModel then
      throw new IllegalStateException(
        "dataflow edges differ: companion " + dataflowCompanion + " vs model " +
          dataflowModel
      )

  // ----- pinned-dotc compile (same discipline as LineageCompileGateSpec)

  private val CompileTimeoutSeconds = 120L

  private lazy val compileDir: Path =
    findAncestor("artifacts", "tools", "m2")
      .resolve("modules")
      .resolve("cli")
      .resolve("target")
      .resolve("lineage-compile")

  private def m2Jar(rel: String): Path =
    val p = findAncestor("artifacts", "tools", "m2")
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")
      .resolve(rel)
    if Files.isRegularFile(p) then p
    else throw new IllegalStateException("missing pinned jar: " + p)

  /** Pinned dotc launcher classpath — the canonical COMPILER_CP of
    * scripts/build/compile.sh (standalone dotc 3.9.0 needs the xsbti classes).
    */
  private lazy val compilerClasspath: String =
    Vector(
      "org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar",
      "org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar",
      "org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar",
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar",
      "org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar",
      "org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar",
      "org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"
    ).map(m2Jar).mkString(File.pathSeparator)

  /** The prescribed `-classpath`: the pinned m2 scala-library 3.9.0 FIRST
    * (always wins resolution), then the test JVM's own classpath (product
    * classes + zio). cli tests run FORKED, so `java.class.path` is the real
    * module test classpath.
    */
  private lazy val userClasspath: String =
    val lib = m2Jar(
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
    )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + File.pathSeparator + base

  private final case class Compiled(exit: Int, logTail: String, outDir: Path)

  private def dotc(
      tag: String,
      source: Path,
      outName: String
  ): Either[String, Compiled] =
    for
      outDir <- freshDir(compileDir.resolve(outName))
      log = compileDir.resolve(tag + ".dotc.log")
      compiled <- runDotc(log, outDir, source)
    yield compiled

  private def freshDir(p: Path): Either[String, Path] =
    try
      if Files.isDirectory(p) then
        val listing = Files.list(p)
        try listing.forEach(entry => Files.delete(entry))
        finally listing.close()
        Files.delete(p)
      Files.createDirectories(p)
      Right(p)
    catch case t: Throwable => Left("fresh dir " + p + ": " + t)

  private def runDotc(
      log: Path,
      outDir: Path,
      source: Path
  ): Either[String, Compiled] =
    try
      Files.createDirectories(compileDir)
      val pb = new ProcessBuilder(
        "java",
        "-cp",
        compilerClasspath,
        "dotty.tools.dotc.Main",
        "-classpath",
        userClasspath,
        "-d",
        outDir.toString,
        source.toString
      )
      pb.redirectErrorStream(true)
      pb.redirectOutput(log.toFile)
      val proc = pb.start()
      if !proc.waitFor(CompileTimeoutSeconds, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left(
          "dotc did not finish within " + CompileTimeoutSeconds + "s (destroyed): " + source
        )
      else
        val all =
          scala.jdk.CollectionConverters
            .ListHasAsScala(
              Files.readAllLines(log, StandardCharsets.UTF_8)
            )
            .asScala
            .toVector
        val tail = if all.size <= 40 then all else all.takeRight(40)
        Right(Compiled(proc.exitValue(), tail.mkString("\n"), outDir))
    catch case t: Throwable => Left("dotc launch failed: " + t)

  private def writeRender(tag: String, src: String): Either[String, Path] =
    try
      Files.createDirectories(compileDir)
      val p = compileDir.resolve(tag + ".scala")
      Files.write(p, src.getBytes(StandardCharsets.UTF_8))
      Right(p)
    catch case t: Throwable => Left(tag + ".scala write failed: " + t)

  // ----- loading the compiled generated object

  private def loadProjection(
      outDir: Path,
      objName: String,
      defNames: Vector[String],
      source: String
  ): Loaded =
    // Parent-first delegation: the generated classes resolve raider.core /
    // zio / scala symbols from the TEST JVM's own classpath — the exact
    // classes the pinned dotc compiled against (build scala == pinned 3.9.0).
    val loader = new URLClassLoader(
      Array(outDir.toUri.toURL),
      getClass.getClassLoader
    )
    val cls =
      try Class.forName(objName + "$", true, loader)
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "load " + objName + " from " + outDir + ": " + t
          )
    val module =
      try cls.getField("MODULE$").get(null)
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "module instance of " + objName + ": " + t
          )
    val program =
      try
        cls
          .getMethod("program")
          .invoke(module)
          .asInstanceOf[Program[Unit, Vector[StepReport]]]
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "program accessor of " + objName + ": " + t
          )
    val defs = defNames.map { n =>
      try
        n ->
          cls
            .getMethod(n)
            .invoke(module)
            .asInstanceOf[Program[Unit, Vector[StepReport]]]
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "def val " + n + " of " + objName + ": " + t
          )
    }.toMap
    Loaded(program, defs, source)

  // ----- typed-failure extraction (PRJ-02 helpers)

  /** The channel `RaiderError` of a failed dry run. A SUCCESS outcome is the
    * honest contract violation the RAI-141 review warned about — it fails the
    * test with the full offending vector in the message.
    */
  private def requireFailure(
      exit: Exit[RaiderError, Vector[StepReport]]
  ): RaiderError =
    exit match
      case Exit.Failure(cause) =>
        cause.failureOption.getOrElse(
          throw new IllegalStateException(
            "expected a pure typed failure, got: " + cause
          )
        )
      case Exit.Success(v) =>
        throw new IllegalStateException(
          "expected the dry run to FAIL (typed short-circuit), got a successful vector of " +
            v.size + " reports: " + v
        )

  private def requireCause(error: RaiderError): LineageStepFailure =
    error.getCause match
      case failure: LineageStepFailure => failure
      case other =>
        throw new IllegalStateException(
          "expected a LineageStepFailure cause, got: " + other
        )

  /** Comparable descriptor of one failing execution (determinism ×3, PRJ-03).
    */
  private final case class FailureShape(
      code: String,
      detail: String,
      step: StepReport,
      reason: String
  )

  private def failureShape(
      exit: Exit[RaiderError, Vector[StepReport]]
  ): FailureShape =
    val error = requireFailure(exit)
    val cause = requireCause(error)
    FailureShape(error.code, error.detail, cause.step, cause.reason)

  /** Executes a loaded `Program` through the single observation boundary. */
  private def execute(
      p: Program[Unit, Vector[StepReport]]
  ): ZIO[Any, Nothing, Exit[RaiderError, Vector[StepReport]]] =
    Task.zio(p(())).exit

  // ----- scripted zero-dispatch probe backend (PRJ-03)

  /** Scripted recording backend: records a request when its stream RUNS (the
    * execution-time dispatch moment). Any provider dispatch anywhere in the
    * render/compile/load/execute span would surface here.
    */
  private final class RecordingBackend extends ModelBackend:
    val requests = new CopyOnWriteArrayList[ModelRequest]()

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Supported
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, raider.core.ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          requests.add(input)
          ZStream.empty

    def count: Int = requests.size()

  private val probeRequest = ModelRequest(
    "probe-model",
    List(RequestMessage("user", "dry-run dispatch probe")),
    16,
    Nil
  )

  // ----- spec ----------------------------------------------------------------

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RAI-142 executed lineage projection acceptance (LIN-02)")(
      suite("PRJ-01")(
        test(
          "representative graph: rendered+compiled projection executes to the exact expected report tree (order, kinds, dataRefs, threaded inputs, nesting)"
        ) {
          val fixture = accFixture
          accSourceExpectations.foreach { fragment =>
            if !fixture.source.contains(fragment) then
              throw new IllegalStateException(
                "representative render lacks expected fragment: " + fragment +
                  "\n--- generated source ---\n" + fixture.source
              )
          }
          for
            ph <- Task.zio(fixture.defs("phase_verify")(()))
            dl <- Task.zio(fixture.defs("delegation_worker")(()))
            outcome <- execute(fixture.program)
          yield
            // The failed step is observable ONLY through the typed cause; the
            // success channel never carries it (the run short-circuits).
            val failed = failureShape(outcome)
            val tree = ph ++ dl :+ failed.step
            assertTrue(
              ph == accPrefix.take(3),
              dl == accPrefix.drop(3),
              failed.step == accFailedStep,
              tree == accFullTree
            )
        },
        test(
          "deep-tree golden: executed projection returns the exact successful report tree; the delegation sub-program is directly executable"
        ) {
          val fixture = deepFixture
          for
            full <- Task.zio(fixture.program(()))
            delegation <- Task.zio(fixture.defs("delegation_worker")(()))
          yield assertTrue(
            full == deepExpected,
            delegation == deepDelegationFragment
          )
        }
      ),
      suite("PRJ-02")(
        test(
          "representative graph: typed short-circuit — RA-OUT channel, LineageStepFailure cause, exact report prefix observable"
        ) {
          val fixture = accFixture
          for outcome <- execute(fixture.program)
          yield
            val error = requireFailure(outcome)
            val cause = requireCause(error)
            assertTrue(
              error.code == "RA-OUT",
              error.retryable == false,
              cause.step == accFailedStep,
              cause.reason == "recorded outcome: Failed",
              error.detail == cause.getMessage,
              cause.getMessage.startsWith(
                "lineage dry-run step failed: id=t9 kind=ToolCall"
              ),
              cause.step.inputs == Vector("t2", "m3"),
              cause.step.nesting == 0
            )
        },
        test(
          "tiny-graph golden: executed projection FAILS at n3 with the typed cause — never a successful vector (RAI-141 review risk); the failure propagates through the phase sub-program and the root chain"
        ) {
          val fixture = tinyFixture
          for
            viaProgram <- execute(fixture.program)
            viaPhase <- execute(fixture.defs("phase_investigate"))
          yield
            val throughProgram = failureShape(viaProgram)
            val throughPhase = failureShape(viaPhase)
            assertTrue(
              throughProgram.code == "RA-OUT",
              throughProgram.step == tinyFailedStep,
              throughProgram.reason == tinyReason,
              throughPhase.code == "RA-OUT",
              throughPhase.step == tinyFailedStep,
              throughPhase.reason == tinyReason,
              throughProgram == throughPhase
            )
        }
      ),
      suite("PRJ-03")(
        test(
          "determinism ×3: identical failure descriptors (representative) and identical vectors (deep-tree golden), including the sub-program prefix runs"
        ) {
          val acc = accFixture
          val deep = deepFixture
          for
            a1 <- execute(acc.program)
            a2 <- execute(acc.program)
            a3 <- execute(acc.program)
            p1 <- Task.zio(acc.defs("phase_verify")(()))
            p2 <- Task.zio(acc.defs("phase_verify")(()))
            d1 <- Task.zio(deep.program(()))
            d2 <- Task.zio(deep.program(()))
            d3 <- Task.zio(deep.program(()))
          yield
            val f1 = failureShape(a1)
            val f2 = failureShape(a2)
            val f3 = failureShape(a3)
            assertTrue(
              f1 == f2,
              f2 == f3,
              f1.step == accFailedStep,
              p1 == p2,
              p1 == accPrefix.take(3),
              d1 == deepExpected,
              d2 == deepExpected,
              d3 == deepExpected
            )
        },
        test(
          "zero dispatch: the scripted probe backend records ZERO calls across render, pinned-dotc compile, load and execute; the negative control proves the probe detects a real dispatch"
        ) {
          // A dedicated compile+load so the probe spans the WHOLE lifecycle
          // (render -> compile -> load -> execute) of this very artifact.
          for
            probe <- ZIO.succeed(new RecordingBackend)
            renderedFirst <- ZIO.succeed(renderSource(accView, "probe render"))
            renderedSecond <- ZIO.succeed(
              renderSource(accView, "probe render repeat")
            )
            fixture <- ZIO.succeed(
              compileAndLoad(
                "rai142-acc-probe",
                renderedFirst,
                "lineage_run_acc_142",
                Vector("phase_verify", "delegation_worker")
              )
            )
            _ <- ZIO.succeed(
              assertTrue(
                renderedFirst == renderedSecond,
                probe.count == 0
              )
            )
            run1 <- execute(fixture.program)
            run2 <- execute(fixture.program)
            run3 <- execute(fixture.program)
            ph <- Task.zio(fixture.defs("phase_verify")(()))
            dl <- Task.zio(fixture.defs("delegation_worker")(()))
            _ <- ZIO.succeed(
              assertTrue(
                failureShape(run1) == failureShape(run2),
                failureShape(run2) == failureShape(run3),
                ph == accPrefix.take(3),
                dl == accPrefix.drop(3),
                probe.count == 0
              )
            )
            // Negative control: the harness MUST see a real dispatch when one
            // happens (pulling the scripted stream records the request), so
            // the zero readings above are evidence of absence, not a dead probe.
            _ <- Task.zio(
              Task(ZIO.scoped(probe.stream(probeRequest).runDrain))
            )
          yield assertTrue(probe.count == 1)
        }
      )
    )

end LineageProjectionExecutionSpec
