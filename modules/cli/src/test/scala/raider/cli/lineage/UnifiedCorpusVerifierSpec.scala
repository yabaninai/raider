package raider.cli.lineage

import zio.test.*
import zio.{Exit, ZIO}

import raider.core.{JobId, Program, RaiderError, Task}
import raider.dsl.lineage.proj.{
  FanOutGroup,
  NodeKind,
  ProvenancePointer,
  SegmentEntry,
  UnifiedProjection,
  UnifiedProjectionInput
}
import raider.dsl.lineage.proj.runtime.StepReport
import raider.runtime.lineage.io.LineageWireReader
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.reduce.{
  LineageReduction,
  LineageReducer,
  ReduceError
}
import raider.runtime.lineage.wire.LineageEnvelope

import zio.json.{DeriveJsonCodec, JsonCodec, DecoderOps}

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** RAI-146 independent acceptance VERIFIER of the unified multi-job lineage
  * (card RAI-146; ADR-031 + ADR-032). The frozen corpus lives in
  * `contracts/lineage/unified/` (versioned, sha256-manifested — see its
  * README); this spec is the protected acceptance surface that checks the
  * product pipeline (versioned reader → strict/partial reducer → graph view →
  * unified projection → pinned-dotc compile → `Task.zio` execution) against the
  * corpus.
  *
  * Pinned semantics:
  *   - '''VR-01''' positive corpus: corpus integrity (manifest sha256), exact
  *     executed report trees (×3), graph-hash pins, provenance resolution
  *     (verbatim ADR-032 pointers, per-linked-segment work_dir+events pairs),
  *     recorded fan-out carried and composed as `batch`, generated-source pins
  *     and byte-hash pins.
  *   - '''VR-02''' negative controls fail TYPED and stay negatives: corrupted
  *     correlation (`MissingCorrelation`), invented edge (`UnknownParent` — a
  *     stolen correlation key without a `Delegated` introduction never joins),
  *     torn segment (byte-torn tail and crash-prefix cut: reader
  *     `TruncatedTail` fact, strict `MissingTerminal`, LIN5-03 typed partial
  *     keeps only completed nodes), unlinked segment (case-3: separate native
  *     root, disjoint trees, typed `Unlinked` entry).
  *   - '''VR-03''' verifier green ×3 (every projection executes three times per
  *     run; the suite is run three consecutive times for the record) and
  *     concurrent verification is ISOLATED: every dotc compile runs in its own
  *     unique temp directory (no shared fixed paths), and two cases verified in
  *     parallel produce the same pinned results as sequentially.
  *
  * The expectations are independent contract-semantics derivations from the
  * frozen streams (see the corpus README), never snapshots of generator
  * internals — a generator regression cannot shift its own acceptance target
  * without a corpus review.
  */
object UnifiedCorpusVerifierSpec extends ZIOSpecDefault:

  // ----- expected-document schema (frozen corpus side) -------------------------

  final case class ExpectedArtifact(kind: String, path: String)

  final case class ExpectedProvenance(
      parentRunId: Option[String],
      artifacts: Vector[ExpectedArtifact]
  )

  final case class ExpectedSegment(
      jobId: String,
      invocationId: Option[String],
      status: String,
      detail: Option[String]
  )

  final case class ExpectedFan(
      parentId: Option[String],
      members: Vector[String],
      parallelism: Int
  )

  final case class ExpectedReport(
      id: String,
      kind: String,
      dataRef: Option[String],
      inputs: Vector[String],
      nesting: Int
  )

  final case class ExpectedCase(
      caseId: String,
      streamFile: String,
      runId: String,
      graphHash: String,
      provenance: Option[ExpectedProvenance],
      segments: Vector[ExpectedSegment],
      fanOut: Vector[ExpectedFan],
      reportTree: Vector[ExpectedReport],
      sourcePins: Vector[String],
      sourceAbsent: Vector[String],
      generatedSourceSha256: String
  )

  final case class ExpectedTail(lineNo: Int, length: Int)

  final case class ExpectedReader(
      envelopeCount: Int,
      truncatedTail: Option[ExpectedTail]
  )

  final case class ExpectedError(kind: String, job: String, seq: Option[Long])

  final case class ExpectedPartial(
      graphHash: String,
      provenance: Option[ExpectedProvenance],
      segments: Vector[ExpectedSegment],
      fanOut: Vector[ExpectedFan],
      reportTree: Vector[ExpectedReport],
      sourcePins: Vector[String],
      sourceAbsent: Vector[String],
      generatedSourceSha256: String
  )

  final case class ExpectedNeg(
      caseId: String,
      streamFile: String,
      reader: ExpectedReader,
      strictError: Option[ExpectedError],
      partialError: Option[ExpectedError],
      partial: Option[ExpectedPartial]
  )

  final case class ExpectedManifest(
      corpus: String,
      version: Int,
      files: Map[String, String]
  )

  object ExpectedArtifact:
    given JsonCodec[ExpectedArtifact] = DeriveJsonCodec.gen[ExpectedArtifact]

  object ExpectedProvenance:

    given JsonCodec[ExpectedProvenance] =
      DeriveJsonCodec.gen[ExpectedProvenance]

  object ExpectedSegment:
    given JsonCodec[ExpectedSegment] = DeriveJsonCodec.gen[ExpectedSegment]

  object ExpectedFan:
    given JsonCodec[ExpectedFan] = DeriveJsonCodec.gen[ExpectedFan]

  object ExpectedReport:
    given JsonCodec[ExpectedReport] = DeriveJsonCodec.gen[ExpectedReport]

  object ExpectedCase:
    given JsonCodec[ExpectedCase] = DeriveJsonCodec.gen[ExpectedCase]

  object ExpectedTail:
    given JsonCodec[ExpectedTail] = DeriveJsonCodec.gen[ExpectedTail]

  object ExpectedReader:
    given JsonCodec[ExpectedReader] = DeriveJsonCodec.gen[ExpectedReader]

  object ExpectedError:
    given JsonCodec[ExpectedError] = DeriveJsonCodec.gen[ExpectedError]

  object ExpectedPartial:
    given JsonCodec[ExpectedPartial] = DeriveJsonCodec.gen[ExpectedPartial]

  object ExpectedNeg:
    given JsonCodec[ExpectedNeg] = DeriveJsonCodec.gen[ExpectedNeg]

  object ExpectedManifest:
    given JsonCodec[ExpectedManifest] = DeriveJsonCodec.gen[ExpectedManifest]

  // ----- corpus access ---------------------------------------------------------

  private def corpusRoot: Path =
    findAncestor("contracts", "lineage", "unified")
      .resolve("contracts")
      .resolve("lineage")
      .resolve("unified")

  private def corpusFile(rel: String): Path = corpusRoot.resolve(rel)

  private def readCorpusText(rel: String): String =
    new String(Files.readAllBytes(corpusFile(rel)), UTF_8)

  private def decodeCorpus[A: JsonCodec](rel: String, what: String): A =
    readCorpusText(rel).fromJson[A] match
      case Right(v) => v
      case Left(err) =>
        throw new IllegalStateException(s"$what decode failed: $err")

  private def sha256Text(s: String): String =
    val d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8))
    d.map(b => f"${b & 0xff}%02x").mkString

  private def sha256File(p: Path): String =
    val d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))
    d.map(b => f"${b & 0xff}%02x").mkString

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

  // ----- expectation -> product-type conversion --------------------------------

  private def kindOf(s: String): NodeKind =
    s.split(":", 3).toVector match
      case Vector("phase", name)       => NodeKind.Phase(name)
      case Vector("delegation", agent) => NodeKind.Delegation(agent)
      case Vector("model", model, round) =>
        NodeKind.ModelCall(model, round.toInt)
      case Vector("tool", tool, recovery) => NodeKind.ToolCall(tool, recovery)
      case _ =>
        throw new IllegalArgumentException("bad expected kind: " + s)

  private def reportOf(r: ExpectedReport): StepReport =
    StepReport(r.id, kindOf(r.kind), r.dataRef, r.inputs, r.nesting)

  private def treeOf(rs: Vector[ExpectedReport]): Vector[StepReport] =
    rs.map(reportOf)

  private def segmentOf(s: ExpectedSegment): SegmentEntry =
    SegmentEntry(
      s.jobId,
      s.invocationId,
      s.status match
        case "linked" => SegmentEntry.Status.Linked
        case "incomplete" =>
          SegmentEntry.Status.Incomplete(
            s.detail.getOrElse("missing detail for incomplete segment")
          )
        case "unlinked" =>
          SegmentEntry.Status.Unlinked(
            s.detail.getOrElse("missing detail for unlinked segment")
          )
        case other =>
          throw new IllegalArgumentException("bad expected status: " + other)
    )

  private def fanOf(f: ExpectedFan): FanOutGroup =
    FanOutGroup(f.parentId, f.members, f.parallelism)

  private def pointersOf(
      p: Option[ExpectedProvenance]
  ): Vector[ProvenancePointer] =
    p.fold(Vector.empty[ProvenancePointer])(
      _.artifacts.map(a => ProvenancePointer(a.kind, a.path))
    )

  private def provenancePair(
      p: Option[ExpectedProvenance]
  ): Option[(Option[String], Vector[(String, String)])] =
    p.map(e => (e.parentRunId, e.artifacts.map(a => (a.kind, a.path))))

  private def typedErrorOf(e: ExpectedError): ReduceError =
    e.kind match
      case "MissingCorrelation" =>
        ReduceError.MissingCorrelation(e.job, e.seq.getOrElse(-1L))
      case "UnknownParent" =>
        ReduceError.UnknownParent(e.job, e.seq.getOrElse(-1L))
      case "MissingTerminal" => ReduceError.MissingTerminal(e.job)
      case other =>
        throw new IllegalArgumentException("bad expected error kind: " + other)

  // ----- product pipeline (reader -> reducer -> view -> projection) ------------

  private def readStream(rel: String): Parsed =
    LineageWireReader.read(corpusFile(rel)) match
      case Right(p) =>
        Parsed(
          p.envelopes,
          p.truncatedTail.map(t => (t.lineNo, t.rawPrefixLen))
        )
      case Left(err) =>
        throw new IllegalStateException("stream read refused: " + err)

  private final case class Parsed(
      envelopes: Vector[LineageEnvelope],
      truncatedTail: Option[(Int, Int)]
  )

  private def project(
      envs: Vector[LineageEnvelope],
      partial: Boolean,
      fanOut: Vector[FanOutGroup],
      provenance: Vector[ProvenancePointer],
      segments: Vector[SegmentEntry]
  ): Either[ReduceError, (String, String, String, LineageReduction)] =
    val reduction =
      if partial then LineageReducer.reducePartialWire(envs)
      else LineageReducer.reduceWire(envs)
    reduction.flatMap { red =>
      SessionGraphView.toView(red.graph) match
        case Left(ve) =>
          throw new IllegalStateException("unified view refused: " + ve)
        case Right(view) =>
          UnifiedProjection.generate(
            UnifiedProjectionInput(
              view.runId,
              view,
              fanOut,
              provenance,
              segments
            )
          ) match
            case Left(ue) =>
              throw new IllegalStateException(
                "unified projection refused: " + ue
              )
            case Right(gen) =>
              Right(
                (
                  gen.source,
                  GraphHash(red.graph),
                  gen.objectName,
                  red
                )
              )
    }

  // ----- pinned-dotc compile + load + execute (RAI-142 discipline; VR-03
  // isolation: every compile gets its OWN unique temp directory — no shared
  // fixed paths, so concurrent verifications never collide) --------------------

  private final case class Loaded(
      program: Program[Unit, Vector[StepReport]]
  )

  private val CompileTimeoutSeconds = 120L

  private def m2Jar(rel: String): Path =
    val p = findAncestor("artifacts", "tools", "m2")
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")
      .resolve(rel)
    if Files.isRegularFile(p) then p
    else throw new IllegalStateException("missing pinned jar: " + p)

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

  private lazy val userClasspath: String =
    val lib =
      m2Jar("org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar")
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + File.pathSeparator + base

  private def compileSource(
      tag: String,
      src: String,
      objName: String
  ): Loaded =
    val dir = Files.createTempDirectory("rai146-verify-" + tag + "-")
    val source = dir.resolve(tag + ".scala")
    Files.write(source, src.getBytes(UTF_8))
    val outDir = dir.resolve("out")
    Files.createDirectories(outDir)
    val log = dir.resolve("dotc.log")
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
      throw new IllegalStateException(
        tag + ": pinned dotc did not finish within " + CompileTimeoutSeconds + "s"
      )
    if proc.exitValue() != 0 then
      val tail =
        scala.jdk.CollectionConverters
          .ListHasAsScala(Files.readAllLines(log, UTF_8))
          .asScala
          .toVector
          .takeRight(40)
          .mkString("\n")
      throw new IllegalStateException(
        tag + ": pinned-dotc compile expected exit 0, got " + proc.exitValue() +
          "; log tail:\n" + tail
      )
    val loader =
      new URLClassLoader(Array(outDir.toUri.toURL), getClass.getClassLoader)
    val cls =
      try Class.forName(objName + "$", true, loader)
      catch
        case t: Throwable =>
          throw new IllegalStateException("load " + objName + ": " + t)
    val module =
      try cls.getField("MODULE$").get(null) // scalafix:ok DisableSyntax.null
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
    Loaded(program)

  /** The single observation boundary (ADR-031 §2.1): `Task.zio` on the built
    * Program. Pure dry run — no provider, no tool, no I/O.
    */
  private def runProgram(
      p: Program[Unit, Vector[StepReport]]
  ): ZIO[Any, Throwable, Vector[StepReport]] =
    Task.zio(p(())).exit.flatMap {
      case Exit.Success(v) => ZIO.succeed(v)
      case Exit.Failure(cause) =>
        ZIO.fail(
          new IllegalStateException("projection execution failed: " + cause)
        )
    }

  /** Blocking process work runs off the fiber pool (VR-03 parallelism). */
  private def compileIsolated(
      tag: String,
      src: String,
      objName: String
  ): ZIO[Any, Throwable, Loaded] =
    ZIO.attemptBlocking(compileSource(tag, src, objName))

  // ----- positive-case verification (VR-01) ------------------------------------

  private def compileTag(caseId: String): String = "rai146-" + caseId

  private def verifyPositive(
      exp: ExpectedCase
  ): ZIO[Any, Throwable, Vector[StepReport]] =
    for
      parsed <- ZIO.attemptBlocking(readStream(exp.streamFile))
      _ <- ZIO.attempt {
        if parsed.truncatedTail.isDefined then
          throw new IllegalStateException(
            exp.caseId + ": positive stream must not have a truncated tail"
          )
        if parsed.envelopes.isEmpty then
          throw new IllegalStateException(exp.caseId + ": empty stream")
      }
      genAndHash <- ZIO.attempt {
        project(
          parsed.envelopes,
          partial = false,
          exp.fanOut.map(fanOf),
          pointersOf(exp.provenance),
          exp.segments.map(segmentOf)
        )
      }
      (source, graphHash, objName, red) = genAndHash match
        case Right(v) => v
        case Left(err) =>
          throw new IllegalStateException(
            exp.caseId + ": strict reduce refused: " + err
          )
      _ <- ZIO.attempt {
        // provenance resolution: the pinned record resolves verbatim; a case
        // without a record carries NONE (never a guess)
        if red.provenance.isEmpty && exp.provenance.isDefined then
          throw new IllegalStateException(
            exp.caseId + ": pinned provenance missing in the reduction"
          )
        if red.provenance.isDefined && exp.provenance.isEmpty then
          throw new IllegalStateException(
            exp.caseId + ": reduction carries an unpinned provenance record"
          )
        exp.provenance.foreach(p =>
          checkProvenanceResolution(exp.caseId, red, p)
        )
      }
      _ <- ZIO.attempt {
        // generated-source byte pin + substring pins
        if sha256Text(source) != exp.generatedSourceSha256 then
          throw new IllegalStateException(
            exp.caseId + ": generated source sha256 mismatch (pinned " +
              exp.generatedSourceSha256 + ", got " + sha256Text(source) + ")"
          )
        exp.sourcePins.foreach { pin =>
          if !source.contains(pin) then
            throw new IllegalStateException(
              exp.caseId + ": generated source misses pinned line: " + pin
            )
        }
        exp.sourceAbsent.foreach { absent =>
          if source.contains(absent) then
            throw new IllegalStateException(
              exp.caseId + ": generated source must not contain: " + absent
            )
        }
      }
      loaded <- compileIsolated(
        compileTag(exp.caseId),
        source,
        objName
      )
      t1 <- runProgram(loaded.program)
      t2 <- runProgram(loaded.program)
      t3 <- runProgram(loaded.program)
      expectedTree = treeOf(exp.reportTree)
    yield
      if graphHash != exp.graphHash then
        throw new IllegalStateException(
          exp.caseId + ": graph hash mismatch (pinned " + exp.graphHash +
            ", got " + graphHash + ")"
        )
      if t1 != expectedTree then
        throw new IllegalStateException(
          exp.caseId + ": executed tree mismatch (#1); expected " +
            expectedTree.size + " reports, got " + t1.size + "; first diff: " +
            diffHint(expectedTree, t1)
        )
      if t2 != expectedTree then
        throw new IllegalStateException(
          exp.caseId + ": executed tree mismatch (#2)"
        )
      if t3 != expectedTree then
        throw new IllegalStateException(
          exp.caseId + ": executed tree mismatch (#3)"
        )
      t1

  private def diffHint(
      expected: Vector[StepReport],
      actual: Vector[StepReport]
  ): String =
    val n = math.min(expected.size, actual.size)
    (0 until n)
      .find(i => expected(i) != actual(i)) match
      case Some(i) => s"index $i: expected ${expected(i)} got ${actual(i)}"
      case None =>
        s"prefix equal; expected size ${expected.size}, actual size ${actual.size}"

  /** Provenance resolution (VR-01): the reduced record equals the pinned
    * pointer list exactly (parentRunId + verbatim ADR-032 pointers, ADR-032
    * §3's "provenance equality by its own acceptance surface"), and every
    * work_dir pointer resolves to its paired events artifact
    * (`<work_dir>/events.jsonl`).
    */
  private def checkProvenanceResolution(
      caseId: String,
      red: raider.runtime.lineage.reduce.LineageReduction,
      exp: ExpectedProvenance
  ): Unit =
    val actual = red.provenance.map(p =>
      (p.parentRunId, p.artifacts.map(a => (a.kind, a.path)))
    )
    val expectedPair = Some(
      (exp.parentRunId, exp.artifacts.map(a => (a.kind, a.path)))
    )
    if actual != expectedPair then
      throw new IllegalStateException(
        caseId + ": provenance record mismatch: " + actual + " != " + expectedPair
      )
    red.provenance.foreach { p =>
      val workDirs = p.artifacts.filter(_.kind == "work_dir")
      val events = p.artifacts.filter(_.kind == "events")
      if workDirs.isEmpty then
        throw new IllegalStateException(caseId + ": no work_dir pointers")
      if workDirs.size != events.size then
        throw new IllegalStateException(
          caseId + ": work_dir/events pointer count mismatch: " +
            workDirs.size + " vs " + events.size
        )
      workDirs.foreach { w =>
        if !events.exists(_.path == w.path + "/events.jsonl") then
          throw new IllegalStateException(
            caseId + ": unresolved artifact pair for " + w.path
          )
      }
    }

  // ----- spec ------------------------------------------------------------------

  private val manifestRel = "manifest.json"

  def spec: Spec[TestEnvironment, Any] =
    suite("RAI-146 unified lineage corpus verifier (VR-01/VR-02/VR-03)")(
      test(
        "corpus integrity: every manifest file hashes to its pinned sha256 (frozen surface)"
      ) {
        ZIO
          .attemptBlocking {
            val m = decodeCorpus[ExpectedManifest](manifestRel, "manifest")
            if m.corpus != "raider-unified-lineage-corpus" then
              throw new IllegalStateException("unknown corpus id: " + m.corpus)
            if m.version != 1 then
              throw new IllegalStateException(
                "unknown corpus version: " + m.version
              )
            val mismatches = m.files.toVector.flatMap { (rel, sha) =>
              val actual = sha256File(corpusFile(rel))
              if actual == sha then Vector.empty
              else Vector(s"$rel: pinned $sha, actual $actual")
            }
            if m.files.size != 19 then
              throw new IllegalStateException(
                "manifest inventory size changed: " + m.files.size
              )
            if mismatches.nonEmpty then
              throw new IllegalStateException(
                "corpus integrity failed:\n" + mismatches.mkString("\n")
              )
            true
          }
          .map(r => assertTrue(r))
      },
      test(
        "VR-01 case-1 billing: exact 23-report tree x3, graph-hash pin, provenance resolution, recorded batch fan-outs, pinned-dotc compile green"
      ) {
        val exp = decodeCorpus[ExpectedCase](
          "cases/case-1-billing.expected.json",
          "case-1 expected"
        )
        for tree <- verifyPositive(exp)
        yield assertTrue(
          tree.size == 23,
          tree(0).id == "camp-billing:n1",
          tree(0).kind == NodeKind.Phase("baseline-check"),
          tree(0).nesting == 1,
          tree.exists(r => r.kind == NodeKind.ToolCall("bash", "Mutating")),
          // batch fan-outs visible in executed input order (COMP-01)
          tree
            .map(_.id)
            .slice(17, 23) == Vector(
            "camp-billing:n15",
            "j-boundary-1:n1",
            "camp-billing:n16",
            "j-boundary-2:n1",
            "camp-billing:n17",
            "j-boundary-3:n1"
          )
        )
      },
      test(
        "VR-01 case-2 delegation tree: nested delegation under a model-round parent; validated fan-out under a NON-DEF parent composes as recorded batch (f2)"
      ) {
        val exp = decodeCorpus[ExpectedCase](
          "cases/case-2-delegation-tree.expected.json",
          "case-2 expected"
        )
        for tree <- verifyPositive(exp)
        yield assertTrue(
          tree.size == 7,
          tree(0).nesting == 0,
          tree(2).id == "j-worker-a:n1",
          tree(2).nesting == 2,
          tree(2).kind == NodeKind.Delegation("subworker"),
          tree(3).inputs == Vector("j-worker-a:n1"),
          // every validated fanOut group appears exactly once as one batch(
          tree.map(_.id).take(4) == Vector(
            "camp-orchestrator:n1",
            "camp-orchestrator:n3",
            "j-worker-a:n1",
            "j-leaf:n1"
          )
        )
      },
      test(
        "VR-01 + VR-02(unlinked) case-3: unified tree separates the never-introduced stray; stray projects separately; typed Unlinked entry; trees disjoint"
      ) {
        val exp = decodeCorpus[ExpectedCase](
          "cases/case-3-unlinked.expected.json",
          "case-3 expected"
        )
        val strayExp = decodeCorpus[ExpectedCase](
          "cases/case-3-stray.expected.json",
          "stray expected"
        )
        for
          tree <- verifyPositive(exp)
          strayTree <- verifyPositive(strayExp)
        yield assertTrue(
          tree.size == 3,
          strayTree.size == 1,
          strayTree(0).id == "j-stray:n1",
          // separation: zero overlap between the two executed trees
          tree.forall(r => !strayTree.exists(s => s.id == r.id)),
          !tree.exists(r => r.id.startsWith("j-stray:")),
          !tree.exists(r => r.dataRef.exists(_.startsWith("j-stray/")))
        )
      },
      test(
        "VR-01 case-4 pure fan-out roots: single-element fan scope flattens (f1 reproducer — bare batch would not typecheck); compiles + executes exactly"
      ) {
        val exp = decodeCorpus[ExpectedCase](
          "cases/case-4-pure-fanout-roots.expected.json",
          "case-4 expected"
        )
        for tree <- verifyPositive(exp)
        yield assertTrue(
          tree.size == 4,
          tree.map(_.nesting) == Vector(1, 1, 1, 1),
          tree(0).kind == NodeKind.Delegation("worker"),
          tree(2).kind == NodeKind.Delegation("worker")
        )
      },
      test(
        "VR-02 neg-1 corrupted correlation: invocationId stripped -> typed MissingCorrelation (strict AND partial), no silent merge"
      ) {
        val exp = decodeCorpus[ExpectedNeg](
          "negatives/neg-1-corrupted-correlation.expected.json",
          "neg-1 expected"
        )
        for r <- ZIO.attemptBlocking {
            val parsed = readStream(exp.streamFile)
            val strict = LineageReducer.reduceWire(parsed.envelopes)
            val partial = LineageReducer.reducePartialWire(parsed.envelopes)
            (parsed, strict, partial)
          }
        yield
          val expectedErr = typedErrorOf(exp.strictError.get)
          val expectedErrPartial = typedErrorOf(exp.partialError.get)
          assertTrue(
            r._1.envelopes.size == exp.reader.envelopeCount,
            r._1.truncatedTail.isEmpty,
            r._2 == Left(expectedErr),
            r._3 == Left(expectedErrPartial)
          )
      },
      test(
        "VR-02 neg-2 invented edge: stolen correlation key without a Delegated introduction -> typed UnknownParent, never joined"
      ) {
        val exp = decodeCorpus[ExpectedNeg](
          "negatives/neg-2-invented-edge.expected.json",
          "neg-2 expected"
        )
        for r <- ZIO.attemptBlocking {
            val parsed = readStream(exp.streamFile)
            val strict = LineageReducer.reduceWire(parsed.envelopes)
            val partial = LineageReducer.reducePartialWire(parsed.envelopes)
            (parsed, strict, partial)
          }
        yield
          val expectedErr = typedErrorOf(exp.strictError.get)
          assertTrue(
            r._1.envelopes.size == exp.reader.envelopeCount,
            r._2 == Left(expectedErr),
            r._3 == Left(typedErrorOf(exp.partialError.get))
          )
      },
      test(
        "VR-02 neg-3a torn tail: reader reports TruncatedTail(48,40); strict refuses MissingTerminal; typed partial keeps the FULL graph (hash == case-1 pin) and projects deterministically"
      ) {
        val exp = decodeCorpus[ExpectedNeg](
          "negatives/neg-3a-torn-tail.expected.json",
          "neg-3a expected"
        )
        val partial = exp.partial.get
        for
          r <- ZIO.attemptBlocking {
            val parsed = readStream(exp.streamFile)
            val strict = LineageReducer.reduceWire(parsed.envelopes)
            val partialRed = LineageReducer.reducePartialWire(parsed.envelopes)
            (parsed, strict, partialRed)
          }
          (source, graphHash, objName, red) <- ZIO.attempt {
            project(
              r._1.envelopes,
              partial = true,
              partial.fanOut.map(fanOf),
              pointersOf(partial.provenance),
              partial.segments.map(segmentOf)
            ) match
              case Right(v) => v
              case Left(err) =>
                throw new IllegalStateException(
                  "partial reduce refused: " + err
                )
          }
          _ <- ZIO.attempt {
            if sha256Text(source) != partial.generatedSourceSha256 then
              throw new IllegalStateException(
                "neg-3a: partial source sha mismatch"
              )
            partial.sourcePins.foreach { pin =>
              if !source.contains(pin) then
                throw new IllegalStateException("neg-3a: missing pin: " + pin)
            }
            partial.sourceAbsent.foreach { a =>
              if source.contains(a) then
                throw new IllegalStateException(
                  "neg-3a: must not contain: " + a
                )
            }
          }
          loaded <- compileIsolated(compileTag("neg-3a"), source, objName)
          t1 <- runProgram(loaded.program)
          t2 <- runProgram(loaded.program)
          t3 <- runProgram(loaded.program)
        yield
          val expectedTree = treeOf(partial.reportTree)
          // provenance resolution on the partial reduction (the record
          // survived the byte cut)
          checkProvenanceResolution("neg-3a", red, partial.provenance.get)
          assertTrue(
            r._1.envelopes.size == exp.reader.envelopeCount,
            r._1.truncatedTail.isDefined,
            r._1.truncatedTail.get == (
              exp.reader.truncatedTail.get.lineNo,
              exp.reader.truncatedTail.get.length
            ),
            r._2 == Left(typedErrorOf(exp.strictError.get)),
            graphHash == partial.graphHash,
            graphHash == "8febe295b01e5bc7da743dfd170c601d42e6a27bba1896a9eedb101d274c3d5b",
            t1 == expectedTree,
            t2 == expectedTree,
            t3 == expectedTree,
            t1.size == 23
          )
      },
      test(
        "VR-02 neg-3b detached segment: crash prefix cuts the boundary trio and the provenance record; typed partial keeps 16 completed nodes; Incomplete entry carried; no invented boundaries"
      ) {
        val exp = decodeCorpus[ExpectedNeg](
          "negatives/neg-3b-detached-segment.expected.json",
          "neg-3b expected"
        )
        val partial = exp.partial.get
        for
          r <- ZIO.attemptBlocking {
            val parsed = readStream(exp.streamFile)
            val strict = LineageReducer.reduceWire(parsed.envelopes)
            val partialRed = LineageReducer.reducePartialWire(parsed.envelopes)
            (parsed, strict, partialRed)
          }
          (source, graphHash, objName, red) <- ZIO.attempt {
            project(
              r._1.envelopes,
              partial = true,
              partial.fanOut.map(fanOf),
              pointersOf(partial.provenance),
              partial.segments.map(segmentOf)
            ) match
              case Right(v) => v
              case Left(err) =>
                throw new IllegalStateException(
                  "partial reduce refused: " + err
                )
          }
          _ <- ZIO.attempt {
            if sha256Text(source) != partial.generatedSourceSha256 then
              throw new IllegalStateException(
                "neg-3b: partial source sha mismatch"
              )
            partial.sourcePins.foreach { pin =>
              if !source.contains(pin) then
                throw new IllegalStateException("neg-3b: missing pin: " + pin)
            }
            partial.sourceAbsent.foreach { a =>
              if source.contains(a) then
                throw new IllegalStateException(
                  "neg-3b: must not contain: " + a
                )
            }
          }
          loaded <- compileIsolated(compileTag("neg-3b"), source, objName)
          t1 <- runProgram(loaded.program)
          t2 <- runProgram(loaded.program)
          t3 <- runProgram(loaded.program)
        yield
          val expectedTree = treeOf(partial.reportTree)
          // the provenance record was cut: the partial carries NONE, never a guess
          assertTrue(
            r._1.envelopes.size == exp.reader.envelopeCount,
            r._1.truncatedTail.isEmpty,
            r._2 == Left(typedErrorOf(exp.strictError.get)),
            red.provenance.isEmpty,
            graphHash == partial.graphHash,
            t1 == expectedTree,
            t2 == expectedTree,
            t3 == expectedTree,
            t1.size == 16,
            !t1.exists(_.id.startsWith("camp-billing:n14")),
            !t1.exists(_.id.startsWith("camp-billing:n15"))
          )
      },
      test(
        "VR-03 concurrent verification: two cases verified in parallel (unique temp dirs each) produce the pinned results; repeat execution identical"
      ) {
        val exp1 = decodeCorpus[ExpectedCase](
          "cases/case-1-billing.expected.json",
          "case-1 expected"
        )
        val exp4 = decodeCorpus[ExpectedCase](
          "cases/case-4-pure-fanout-roots.expected.json",
          "case-4 expected"
        )
        for results <- ZIO
            .foreachPar(Vector(exp1, exp4))(verifyPositive)
        yield
          val t1 = results(0)
          val t4 = results(1)
          assertTrue(
            t1 == treeOf(exp1.reportTree),
            t4 == treeOf(exp4.reportTree),
            t1.size == 23,
            t4.size == 4
          )
      }
    ) @@ TestAspect.timeout(zio.Duration.fromSeconds(900))

end UnifiedCorpusVerifierSpec
