package raider.cli.lineage

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

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** LIN2-01 compile-gate (ADR-027 wave-2): golden lineage projections compile
  * with the PINNED scalac (dotc 3.9.0 from `artifacts/tools/m2`), not only
  * inside the sbt build. The fixture is the REAL frozen golden documents under
  * `contracts/lineage/golden` (tiny-graph, deep-tree), parsed from disk — never
  * rebuilt from code (same discipline as the W9 SessionGraphViewSpec).
  *
  * Pipeline per golden graph: lineage-data.json + session-graph.json →
  * SessionGraph → view (GraphToView mapping, RAI-141 shape) → LineageRenderer →
  * .scala file under `modules/cli/target/lineage-compile/` → subprocess `java
  * -cp <pinned dotc jars> dotty.tools.dotc.Main -classpath <test classpath> -d
  * <fresh out dir> <source>`, bounded at 120s per file.
  *
  * Acceptance: LIN2-01-A both golden projections compile (exit 0) as EXECUTABLE
  * Programs (real `LineageStep` composition — RAI-141/REN-01) with NO
  * comment-only def bodies; LIN2-01-B the repeat render is byte-identical and
  * the repeat artifact re-compiles (exit 0); LIN2-01-C an artificially
  * truncated projection (object header torn) honestly does NOT compile (exit !=
  * 0 — a compile timeout or an IO failure is never counted as non-compilation
  * evidence).
  *
  * Classpath (RAI-141): the generated projection is no longer self-contained —
  * it references `raider.core.Program`, `raider.dsl.lineage.proj.runtime.*` and
  * (transitively) zio. The prescribed `-classpath` (pinned scala-library 3.9.0
  * first, then the test JVM's own `java.class.path`) carries all of them: cli
  * tests run FORKED (build.sbt `Test / fork := true`), so `java.class.path` is
  * the real module test classpath including the raider module classes and the
  * zio_3 jar; the pinned scala-library stays first and always wins class
  * resolution.
  *
  * Snapshot discrepancy (honest): the main-scope cli adapter
  * `raider.cli.lineage.SessionGraphView` (W9 lane L22) is NOT integrated into
  * this snapshot, and this lane's allowed paths are test-scope only, so the
  * adapter mapping lives in [[GraphToView]] below — the L22 conversion verbatim
  * in behavior, kept in one marked private object to be swapped for a direct
  * `SessionGraphView.toView` call once the adapter lands. RAI-141 note: the
  * test-local mapping additionally populates `NodeView.inputs` (dataflow edges)
  * and `NodeView.outcome` (recorded outcome class) from the golden documents —
  * the product adapter population is the follow-up integration card; this gate
  * exercises the populated contract. The tiny-graph golden carries a
  * recorded-Failed tool node (n3, edit_file), so the gate compiles a projection
  * containing a real `LineageStep.failed` short-circuit step.
  */
object LineageCompileGateSpec extends ZIOSpecDefault:

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

  /** Locates the build root (snapshot marker `artifacts/tools/m2`) or the
    * golden directory by walking up from the process cwd (sbt runs tests with
    * the module base as cwd; walking up keeps the fixture honest under any
    * working directory).
    */
  private def findAncestor(segments: String*): Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) =>
        dir
      }
      .find(dir => Files.isDirectory(dir.resolve(segments.mkString("/"))))
      .getOrElse(
        throw new IllegalStateException(
          "no ancestor of " + cwd + " contains " + segments.mkString("/")
        )
      )

  private def readFile(dir: Path, file: String): Either[String, String] =
    try Right(Files.readString(dir.resolve(file), StandardCharsets.UTF_8))
    catch case t: Throwable => Left(file + ": " + t)

  /** The golden pipeline: real JSON → SessionGraph → view → render (twice). Any
    * parse/validation mismatch is an honest Left, never a fixture guess.
    */
  private def goldenE2E(name: String): Either[String, Golden] =
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
      view <- GraphToView.toView(graph)
      first <- LineageRenderer.render(view).left.map("render: " + _)
      second <- LineageRenderer.render(view).left.map("render repeat: " + _)
    yield Golden(first, second)

  private def goldenDir(name: String): Either[String, Path] =
    val root = findAncestor("contracts", "lineage", "golden")
    val dir = root
      .resolve("contracts")
      .resolve("lineage")
      .resolve("golden")
      .resolve(name)
    if Files.isDirectory(dir) then Right(dir)
    else Left("golden dir not found: " + dir)

  private final case class Golden(first: String, second: String)

  /** Builds the SessionGraph from the frozen lineage-data document. The
    * contract records only the outcome CLASS for tool calls (no failure
    * reason), so `Failed` maps to the empty reason — the projection never reads
    * outcomes, and the fixture invents nothing beyond that documented empty.
    */
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

  // ----- test-local adapter stand-in (see object doc: snapshot discrepancy) --

  private object GraphToView:

    def toView(g: SessionGraph): Either[String, LineageView] =
      g.validated.left
        .map(e => "inconsistent session graph: " + e)
        .flatMap { graph =>
          def build(
              n: SessionNode,
              parentId: Option[String]
          ): Either[String, NodeView] =
            // widened to Matchable so a defensive unknown-kind branch stays
            // reachable-by-construction for future wire revisions (same trick
            // as LineageRenderer; SessionNode is sealed today).
            val node: Matchable = n
            val kind: Either[String, NodeKind] = node match
              case m: SessionNode.ModelCall =>
                Right(NodeKind.ModelCall(m.model, m.round))
              case t: SessionNode.ToolCall =>
                Right(NodeKind.ToolCall(t.tool, recoveryName(t.recovery)))
              case d: SessionNode.Delegation =>
                Right(NodeKind.Delegation(d.agent))
              case p: SessionNode.Phase =>
                Right(NodeKind.Phase(p.name))
              case other =>
                val describe =
                  Option(other).fold("null")(_.getClass.getSimpleName)
                Left("unsupported session node kind: " + describe)
            kind.flatMap { k =>
              graph.childrenOf
                .getOrElse(n.id.value, Vector.empty)
                .foldLeft[Either[String, Vector[NodeView]]](
                  Right(Vector.empty)
                ) { (acc, child) =>
                  acc.flatMap(vs => build(child, Some(n.id.value)).map(vs :+ _))
                }
                .map(children =>
                  NodeView(
                    n.id.value,
                    k,
                    parentId,
                    children,
                    dataRefOf(n),
                    // RAI-141: graph dataflow edges (ADR-031 §2.6) — threaded
                    // into the generated steps as bound values (in-scope) or
                    // id literals (cross-scope).
                    inputs = n.inputs.map(_.value),
                    // RAI-141: recorded outcome class (ADR-031 §2.4) — a
                    // non-success class makes the renderer emit
                    // LineageStep.failed with the bounded reason phrase.
                    outcome = outcomeClassOf(n)
                  )
                )
            }
          graph.roots
            .foldLeft[Either[String, Vector[NodeView]]](Right(Vector.empty)) {
              (acc, root) => acc.flatMap(vs => build(root, None).map(vs :+ _))
            }
            .map { rootViews =>
              new LineageView:
                def runId: String = graph.rootId.value
                def roots: Vector[NodeView] = rootViews
            }
        }

    /** RecoveryClass → recovery string of the frozen lineage-data v1 contract
      * (the enum case names themselves).
      */
    private def recoveryName(r: RecoveryClass): String =
      r match
        case RecoveryClass.ReadOnly   => "ReadOnly"
        case RecoveryClass.Idempotent => "Idempotent"
        case RecoveryClass.Mutating   => "Mutating"

    /** DataRef → key string; Delegation/Phase carry none in the wave-1 model.
      */
    private def dataRefOf(n: SessionNode): Option[String] =
      n match
        case m: SessionNode.ModelCall => Some(m.dataRef.value)
        case t: SessionNode.ToolCall  => t.dataRef.map(_.value)
        case _                        => None

    /** ToolOutcome → the recorded outcome class of the frozen lineage-data v1
      * vocabulary (the same strings the renderer accepts on
      * [[raider.dsl.lineage.proj.NodeView.outcome]]). Nodes without an outcome
      * (model calls, delegations, phases) map to None.
      */
    private def outcomeClassOf(n: SessionNode): Option[String] =
      n match
        case t: SessionNode.ToolCall =>
          t.outcome.map {
            case ToolOutcome.Succeeded => "Succeeded"
            case ToolOutcome.Failed(_) => "Failed"
            case ToolOutcome.Cancelled => "Cancelled"
            case ToolOutcome.Timeout   => "Timeout"
          }
        case _ => None

  // ----- pinned-dotc compile gate ---------------------------------------------

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

  /** The pinned dotc launcher classpath: compiler + interfaces + tasty-core +
    * scala-library + asm, plus the two sbt interface jars — the canonical
    * COMPILER_CP of scripts/build/compile.sh:23-24,35 (standalone dotc 3.9.0
    * needs the xsbti classes; verified by the LIN2-01 fixture run).
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

  /** The prescribed `-classpath` for the compiled projection: the test JVM's
    * own classpath (product classes + dependencies). Under sbt's non-forked
    * test JVM `java.class.path` is the launcher path and carries a Scala-2
    * scala-library whose `scala.Predef` dotc 3.9.0 cannot consume ("No
    * RuntimeVisibleAnnotations in classfile with ScalaSignature attribute"), so
    * the PINNED m2 scala-library 3.9.0 is prepended and always wins class
    * resolution. The generated projection is self-contained (the renderer emits
    * references to the standard library only), so this keeps the documented
    * classpath sufficient without relaxing anything.
    */
  private lazy val userClasspath: String =
    val lib = m2Jar(
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
    )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + File.pathSeparator + base

  private final case class Compiled(exit: Int, logTail: String)

  /** Compiles one generated .scala file with the pinned dotc in a subprocess;
    * fresh output dir per invocation (stale .class files can never mask a
    * failure); compiler output captured to a log beside the source; bounded at
    * [[CompileTimeoutSeconds]]. Left = timeout/launch failure (never evidence
    * of non-compilation).
    */
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
        Right(Compiled(proc.exitValue(), tail.mkString("\n")))
    catch case t: Throwable => Left("dotc launch failed: " + t)

  private def writeRender(tag: String, src: String): Either[String, Path] =
    try
      Files.createDirectories(compileDir)
      val p = compileDir.resolve(tag + ".scala")
      Files.write(p, src.getBytes(StandardCharsets.UTF_8))
      Right(p)
    catch case t: Throwable => Left(tag + ".scala write failed: " + t)

  /** Negative fixture: a structurally CORRUPTED render — the `object` header
    * line is torn into invalid Scala (`object ((:`), the way a torn or damaged
    * artifact would be. Deterministic for every golden: the header is the one
    * line whose corruption can never stay parseable. (Dropping a trailing `()`
    * line is NOT a reliable negative since LIN-03B/W11: the closing marker
    * became optional for the outer region.)
    */
  private def corruptObjectHeader(src: String): String =
    val lines = src.linesIterator.toVector
    val idx = lines.indexWhere(_.trim.startsWith("object "))
    if idx < 0 then
      throw new IllegalStateException("render has no object header to corrupt")
    lines
      .updated(idx, "object ((:")
      .mkString("", "\n", "\n")

  private def requireRight[A](e: Either[String, A], what: String): A =
    e match
      case Right(a) => a
      case Left(err) =>
        throw new IllegalStateException(what + " failed: " + err)

  /** REN-01 (RAI-141): comment-only def bodies are a renderer bug after this
    * card. Every `def`/`val` body that opens with `=` must be followed by at
    * least one NON-comment statement (the object body likewise), and the
    * renderer shape never emits a standalone `()` closer line. Violations are
    * hard gate failures, not soft assertions.
    */
  private def assertExecutable(src: String, what: String): Unit =
    val lines = src.linesIterator.toVector
    val problems = scala.collection.mutable.ArrayBuffer.empty[String]
    lines.zipWithIndex.foreach { (line, i) =>
      val t = line.trim
      val indent = line.takeWhile(_ == ' ').length
      if t == "()" then
        problems += what + ": standalone `()` closer line at " + (i + 1)
      val opensBody =
        (t.startsWith("def ") || t.startsWith("val ")) && t.endsWith("=")
      val opensObject = t.startsWith("object ") && t.endsWith(":")
      if opensBody || opensObject then
        lines.drop(i + 1).find(_.trim.nonEmpty) match
          case None =>
            problems += what + ": body opened at line " + (i + 1) +
              " has no statement"
          case Some(nl) =>
            val nIndent = nl.takeWhile(_ == ' ').length
            val commentOnly =
              nl.trim.startsWith("//") ||
                (nIndent <= indent && !opensObject) ||
                (opensObject && nIndent == 0)
            if commentOnly then
              problems += what + ": body opened at line " + (i + 1) +
                " is comment-only or empty: next line: " + nl
      ()
    }
    if problems.nonEmpty then
      throw new IllegalStateException(problems.mkString("; "))
    if !(src.contains("Program.fromFunction") && src.contains("LineageStep."))
    then
      throw new IllegalStateException(
        what + ": render carries no real Program/Step composition"
      )

  private def requireExit0(res: Either[String, Compiled], what: String): Unit =
    res match
      case Right(Compiled(0, _)) => ()
      case Right(Compiled(exit, tail)) =>
        throw new IllegalStateException(
          what + ": expected exit 0, got " + exit + "; dotc log tail:\n" + tail
        )
      case Left(err) => throw new IllegalStateException(what + ": " + err)

  // ----- expectations for the golden renders (derived from the frozen payloads)

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
    // RAI-141: n3's dataflow input n1 lives in the phase scope while n3 lives
    // in the delegation scope — a cross-scope dependency renders as the id
    // literal (in-scope dependencies render as bound values instead).
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

  def spec = suite("LIN2-01 lineage compile-gate (pinned dotc 3.9.0)")(
    test(
      "LIN2-01-A: golden tiny-graph projection compiles with pinned dotc (exit 0)"
    ) {
      val golden = requireRight(goldenE2E("tiny-graph"), "golden tiny pipeline")
      val path = requireRight(
        writeRender("tiny-graph", golden.first),
        "write tiny render"
      )
      requireExit0(dotc("tiny-graph", path, "out-tiny-graph"), "tiny compile")
      val src = golden.first
      assertExecutable(src, "tiny render")
      assertTrue(
        Files.exists(path),
        src.contains("object lineage_run_tiny_001:"),
        tinyExpectedNames.forall(src.contains),
        tinyExpectedRefs.forall(src.contains),
        tinyForbiddenInline.forall(s => !src.contains(s))
      )
    },
    test(
      "LIN2-01-A: golden deep-tree projection compiles with pinned dotc (exit 0)"
    ) {
      val golden = requireRight(goldenE2E("deep-tree"), "golden deep pipeline")
      val path = requireRight(
        writeRender("deep-tree", golden.first),
        "write deep render"
      )
      requireExit0(dotc("deep-tree", path, "out-deep-tree"), "deep compile")
      val src = golden.first
      assertExecutable(src, "deep render")
      assertTrue(
        Files.exists(path),
        src.contains("object lineage_run_deep_001:"),
        deepExpectedNames.forall(src.contains),
        deepExpectedRefs.forall(src.contains),
        deepForbiddenInline.forall(s => !src.contains(s))
      )
    },
    test(
      "LIN2-01-B: repeat render is byte-identical and the repeat artifact re-compiles (exit 0)"
    ) {
      val tiny = requireRight(goldenE2E("tiny-graph"), "golden tiny pipeline")
      val deep = requireRight(goldenE2E("deep-tree"), "golden deep pipeline")
      val repeats = Vector(("tiny-graph", tiny), ("deep-tree", deep)).map {
        (name, golden) =>
          val path = requireRight(
            writeRender(name + "-repeat", golden.second),
            "write repeat render " + name
          )
          name -> requireRight(
            dotc(name + "-repeat", path, "out-" + name + "-repeat"),
            "repeat compile " + name
          )
      }
      repeats.foreach { (name, compiled) =>
        if compiled.exit != 0 then
          throw new IllegalStateException(
            name + " repeat compilation: expected exit 0, got " + compiled.exit +
              "; dotc log tail:\n" + compiled.logTail
          )
      }
      assertTrue(
        tiny.first == tiny.second,
        tiny.first.getBytes(StandardCharsets.UTF_8).toSeq ==
          tiny.second.getBytes(StandardCharsets.UTF_8).toSeq,
        deep.first == deep.second,
        deep.first.getBytes(StandardCharsets.UTF_8).toSeq ==
          deep.second.getBytes(StandardCharsets.UTF_8).toSeq,
        repeats.forall((_, c) => c.exit == 0)
      )
    },
    test(
      "LIN2-01-C: corrupted projection (object header torn) honestly does NOT compile"
    ) {
      val outcomes = Vector("tiny-graph", "deep-tree").map { name =>
        val golden =
          requireRight(goldenE2E(name), "golden " + name + " pipeline")
        val truncated = corruptObjectHeader(golden.first)
        val path = requireRight(
          writeRender(name + "-truncated", truncated),
          "write truncated render " + name
        )
        name -> dotc(name + "-truncated", path, "out-" + name + "-truncated")
      }
      val violations = outcomes.flatMap { (name, res) =>
        res match
          case Right(Compiled(exit, _)) if exit != 0 => None
          case Right(Compiled(exit, _)) =>
            Some(
              name + ": corrupted projection compiled (exit " + exit +
                ") — negative contract violated"
            )
          case Left(err) =>
            Some(
              name + ": " + err + " (timeout/IO is NOT honest non-compilation evidence)"
            )
      }
      violations.foreach(v => throw new IllegalStateException(v))
      assertTrue(violations.isEmpty)
    }
  )
