package raider.cli.run

import raider.core.{JobId, RootId, SessionId}
import raider.dsl.lineage.data.{LineageDataDocument, LineageDataFile}
import raider.dsl.lineage.proj.{LineageRenderer}
import raider.dsl.lineage.store.LineageDataStore
import raider.cli.lineage.SessionGraphView
import raider.runtime.events.{EventEnvelope, EventCodecs}
import raider.runtime.events.EventCodecs.given
import raider.runtime.lineage.model.{DataRef, NodeId, SessionGraph, SessionNode}
import raider.runtime.lineage.reduce.LineageReducer
import zio.ZIO
import zio.json.{DecoderOps, JsonDecoder}
import zio.json.ast.Json
import zio.test._

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream, Manifest}

/** LIN-09 acceptance (lineage artifacts written by the settled headless run,
  * ADR-027 §2/§6.5):
  *   - LIN9-01: a settled mock run leaves VALID lineage.scala +
  *     lineage-data.json next to its events.jsonl — the document decodes
  *     through the frozen store/validators (schemaVersion 1, ref integrity),
  *     carries the canonical generator/required-nullable shapes, and the
  *     projection is EXACTLY the frozen SessionGraphView → LineageRenderer
  *     pipeline output re-derived from the run's own events.jsonl checkpoint
  *     (the same pipeline the LIN2-01 compile gate compiles for goldens). A
  *     delegation-bearing graph's produced FILE is compiled with the PINNED
  *     dotc 3.9.0 (exit 0) — compile-gate semantics on the artifact.
  *   - LIN9-02: a settled run whose campaign reduces to a nodeless graph
  *     (bundle run: control-plane events only) refuses the projection TYPED —
  *     `lineage artifacts: unavailable (...)` in summary.md — and the run keeps
  *     its normal exit code; no artifact files are invented.
  *   - LIN9-03: parallel runs into one --out are isolated: every run dir holds
  *     its own whole artifacts (per-run atomic tmp+move), each consistent with
  *     its own checkpoint, no tmp leftovers.
  *
  * Honest wire limitation (documented in [[RunLineageArtifacts]]): the frozen
  * headless wire exports no model prompt/response text, so the tests PIN the
  * captured-empty shape (empty strings, null resultTail) instead of letting a
  * regression silently invent payloads. The frozen renderer emits a
  * comment-only object body for nodeless-of-named-defs graphs; dotc rejects
  * such bodies — that frozen-layer gap is reported for the dsl owner, the
  * writer passes the renderer output through VERBATIM (never edits it).
  */
object LineageArtifactsSpec extends ZIOSpecDefault:

  private def tempDir(): Path =
    Files.createTempDirectory("raider-lin9-test")

  private def mockAgentArgs(out: Path) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        )
      )
      .fold(e => throw e, identity)

  // ----- bundle fixture (test-time packaging, no runtime compiler) ----------

  private def fixtureClassesDir: Path =
    val loc = Path.of(
      classOf[
        raider.fixtures.DemoWorkflow
      ].getProtectionDomain.getCodeSource.getLocation.toURI
    )
    if Files.isDirectory(loc) then loc
    else throw new IllegalStateException(s"fixture location is not a dir: $loc")

  private def makeBundleJar(dir: Path): Path =
    val jarPath = dir.resolve("demo-bundle.jar")
    val mf = new Manifest()
    mf.getMainAttributes.putValue("Manifest-Version", "1.0")
    val jos = new JarOutputStream(Files.newOutputStream(jarPath), mf)
    val manifestJson =
      """{"schemaVersion":1,"bundleId":"demo","entries":[
        |{"name":"inspect","mainClass":"raider.fixtures.DemoWorkflow","version":1}],
        |"declaredTrustLevel":"trusted-local"}""".stripMargin
    def putEntry(name: String, bytes: Array[Byte]): Unit =
      jos.putNextEntry(new JarEntry(name))
      jos.write(bytes)
      jos.closeEntry()
    putEntry(BundleLoader.ManifestEntry, manifestJson.getBytes(UTF_8))
    val root = fixtureClassesDir
    Files.walk(root).forEach { p =>
      if Files.isRegularFile(p) then
        val rel = root.relativize(p).toString.replace('\\', '/')
        putEntry(rel, Files.readAllBytes(p))
    }
    jos.close()
    jarPath

  private def bundleArgs(jar: Path, out: Path) =
    CliArgs
      .parse(
        List(
          "run",
          "--bundle",
          jar.toString,
          "--workflow",
          "inspect",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        )
      )
      .fold(e => throw e, identity)

  // ----- artifact readers ----------------------------------------------------

  private def sourceOf(workDir: Path): String =
    Files.readString(workDir.resolve("lineage.scala"), UTF_8)

  private def documentOf(
      workDir: Path
  ): Either[String, LineageDataDocument] =
    LineageDataStore.read(workDir.resolve("lineage-data.json")).left.map {
      case e => s"store: $e"
    }

  private def validatedDocumentOf(
      workDir: Path
  ): Either[String, LineageDataDocument] =
    documentOf(workDir).flatMap(doc =>
      LineageDataFile
        .read(workDir.resolve("lineage-data.json"))
        .left
        .map { e =>
          s"lineage-data validation: $e"
        }
        .map(_ => doc)
    )

  /** Re-reads the run's events.jsonl checkpoint with the frozen wire codec. */
  private def envelopesOf(workDir: Path): Vector[EventEnvelope] =
    val lines =
      Files.readString(workDir.resolve("events.jsonl"), UTF_8).split("\n")
    val decoded =
      lines.filter(_.trim.nonEmpty).toList.map(_.fromJson[EventEnvelope])
    decoded.collectFirst { case Left(err) => err } match
      case Some(err) =>
        throw new IllegalStateException(s"bad events.jsonl line: $err")
      case None => ()
    decoded.map(_.toOption.get).toVector

  /** The frozen projection pipeline over a checkpoint: events → graph → view →
    * render — the exact bytes a healthy artifact must hold.
    */
  private def renderFromCheckpoint(
      workDir: Path
  ): Either[String, String] =
    LineageReducer.reduce(envelopesOf(workDir)) match
      case Left(e) => Left(s"reduce: $e")
      case Right(graph) =>
        SessionGraphView.toView(graph) match
          case Left(e) => Left(s"view: $e")
          case Right(view) =>
            LineageRenderer.render(view) match
              case Left(e)  => Left(s"render: $e")
              case Right(s) => Right(s)

  private def summaryOf(workDir: Path): String =
    Files.readString(workDir.resolve("summary.md"), UTF_8)

  // ----- pinned-dotc compile (the LIN2-01 gate mechanics, test-local) --------

  private lazy val m2Root: Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
      .getOrElse(throw new IllegalStateException("no artifacts/tools/m2"))
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")

  private def m2Jar(rel: String): Path =
    val p = m2Root.resolve(rel)
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
    val lib = m2Jar(
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
    )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + File.pathSeparator + base

  /** Compiles one produced .scala file with the pinned dotc in a subprocess
    * (fresh out dir, 120s bound). Left = timeout/launch failure — NEVER counted
    * as non-compilation (same discipline as LineageCompileGateSpec).
    */
  private def dotcCompile(source: Path): Either[String, Int] =
    try
      val outDir = Files.createTempDirectory("lin9-dotc-out")
      val log = Files.createTempFile("lin9-dotc", ".log")
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
      if !proc.waitFor(120L, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left("dotc did not finish within 120s (destroyed)")
      else
        val exit = proc.exitValue()
        if exit != 0 then
          val tail =
            scala.jdk.CollectionConverters
              .ListHasAsScala(Files.readAllLines(log, UTF_8))
              .asScala
              .toVector
              .takeRight(20)
              .mkString("\n")
          Right(exit) // caller inspects; log tail carried in the assertion
        else Right(exit)
    catch case t: Throwable => Left("dotc launch failed: " + t)

  // ----- fixtures ------------------------------------------------------------

  /** A validated delegation-bearing campaign graph (the golden deep-tree shape:
    * named-def root, comment-kind child) — the smallest graph whose frozen
    * projection is a COMPILING program.
    */
  private def delegationGraph: SessionGraph =
    SessionGraph(
      RootId("cli_lineage9"),
      SessionId("sess_lineage9"),
      Vector(
        SessionNode.Delegation(
          NodeId("j_1:n2"),
          None,
          2L,
          1000L,
          JobId("j_2"),
          "worker",
          Vector.empty
        ),
        SessionNode.ModelCall(
          NodeId("j_2:n4"),
          Some(NodeId("j_1:n2")),
          4L,
          2000L,
          1,
          "mock-model",
          Vector(NodeId("j_1:n2")),
          DataRef("j_2/round-1")
        )
      )
    ).validated
      .fold(e => throw new IllegalStateException(s"fixture: $e"), identity)

  // ----- spec ----------------------------------------------------------------

  def spec = suite("run lineage artifacts (LIN-09)")(
    test(
      "LIN9-01: mock run leaves valid lineage.scala + lineage-data.json next to events.jsonl"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          outcome <- HeadlessRunner.run(mockAgentArgs(work))
          checked <- ZIO.attempt {
            val doc = validatedDocumentOf(outcome.workDir) match
              case Right(d) => d
              case Left(e) =>
                throw new IllegalStateException("invalid document: " + e)
            val src = sourceOf(outcome.workDir)
            val summary = summaryOf(outcome.workDir)
            (doc, src, summary)
          }.orDie
          (doc, src, summary) = checked
          rootId = outcome.result.get.rootId.value
          // the canonical generator block of the frozen schema
          gen = doc.generator
          // the model node's data entry must hold the canonical key set
          modelEntry = doc.data.get("j_1/round-1")
        yield assertTrue(
          outcome.exitCode == 0,
          Files.isRegularFile(outcome.workDir.resolve("lineage.scala")),
          Files.isRegularFile(outcome.workDir.resolve("lineage-data.json")),
          doc.schemaVersion == 1,
          doc.runId == rootId,
          doc.nodes.nonEmpty,
          doc.nodes.flatMap(_.dataRef).toSet == doc.data.keySet,
          gen.name == "raider",
          gen.scalaVersion == "3.9.0",
          gen.version.matches(raw"[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?"),
          gen.jdkVersion.matches(raw"[0-9]+"),
          modelEntry.isDefined,
          // honest captured-empty wire shape (nothing invented)
          modelEntry.get
            .asInstanceOf[
              raider.dsl.lineage.data.NodeData.ModelCallData
            ]
            .prompt == "",
          modelEntry.get
            .asInstanceOf[
              raider.dsl.lineage.data.NodeData.ModelCallData
            ]
            .response == "",
          modelEntry.get
            .asInstanceOf[
              raider.dsl.lineage.data.NodeData.ModelCallData
            ]
            .usage
            .isDefined,
          summary.contains("- lineage: graph "),
          summary.contains(
            "- lineage artifacts: lineage.scala, lineage-data.json (schemaVersion 1)"
          ),
          // payload discipline: the user input never goes inline
          !src.contains("task-42"),
          src.contains("// data: j_1/round-1"),
          src.startsWith(
            "// Generated by Raider lineage projection (ADR-027)."
          )
        )
      }
    },
    test(
      "LIN9-01: artifact equals the frozen pipeline output re-derived from its own events.jsonl checkpoint"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          outcome <- HeadlessRunner.run(mockAgentArgs(work))
          expected <- ZIO.attempt(renderFromCheckpoint(outcome.workDir)).orDie
          actual <- ZIO
            .attempt(sourceOf(outcome.workDir))
            .orDie
        yield assertTrue(
          expected.isRight,
          expected.fold(_ => "", identity) == actual
        )
      }
    },
    test(
      "LIN9-01: produced lineage.scala compiles with the pinned dotc 3.9.0 (delegation-bearing graph)"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          written <- ZIO
            .attempt(
              RunLineageArtifacts.write(
                work,
                delegationGraph,
                Vector.empty,
                None
              )
            )
            .orDie
          exit <- ZIO.attempt {
            written match
              case Right(w) =>
                dotcCompile(w.source) match
                  case Right(0) => Right(0)
                  case Right(code) =>
                    Left(s"dotc exit $code for ${w.source}")
                  case Left(err) => Left(err)
              case Left(e) => Left("writer refused: " + e)
          }.orDie
          doc <- ZIO.attempt(documentOf(work)).orDie
        yield assertTrue(
          written.isRight,
          exit == Right(0),
          doc.isRight,
          doc.map(_.nodes.size) == Right(2),
          doc.map(_.data.keySet) == Right(Set("j_2/round-1")),
          doc.map(
            _.nodes
              .filter(_.kind == raider.dsl.lineage.data.NodeKind.Delegation)
              .flatMap(_.dataRef)
          ) == Right(Vector.empty)
        )
      }
    },
    test(
      "LIN9-02: nodeless campaign (bundle run) — typed diagnostics in summary, normal exit, no artifacts invented"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          jar <- ZIO.attempt(makeBundleJar(work)).orDie
          outcome <- HeadlessRunner.run(bundleArgs(jar, work))
          summary <- ZIO.attempt(summaryOf(outcome.workDir)).orDie
        yield assertTrue(
          outcome.exitCode == 0,
          summary.contains("- lineage artifacts: unavailable ("),
          !Files.exists(outcome.workDir.resolve("lineage.scala")),
          !Files.exists(outcome.workDir.resolve("lineage-data.json"))
        )
      }
    },
    test(
      "LIN9-03: parallel runs into one --out — per-run artifacts, no interference, no tmp leftovers"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          outcomes <- ZIO
            .collectAllPar(
              (1 to 6).toList.map(_ => HeadlessRunner.run(mockAgentArgs(work)))
            )
          checked <- ZIO.attempt {
            outcomes.map { o =>
              val doc = validatedDocumentOf(o.workDir)
              val proj = renderFromCheckpoint(o.workDir)
              val src =
                Files.readString(o.workDir.resolve("lineage.scala"), UTF_8)
              val tmpLeftovers =
                val listing = Files.list(o.workDir)
                try
                  val arr =
                    scala.jdk.CollectionConverters
                      .IteratorHasAsScala(listing.iterator())
                      .asScala
                      .toVector
                  arr.count(p => p.getFileName.toString.endsWith(".tmp"))
                finally listing.close()
              (o, doc, proj, src, tmpLeftovers)
            }
          }.orDie
        yield assertTrue(
          outcomes.map(_.exitCode).forall(_ == 0),
          outcomes.map(_.workDir).distinct.size == 6,
          checked.forall { (o, doc, proj, src, tmpLeftovers) =>
            doc.isRight &&
            doc.map(_.runId) == Right(o.result.get.rootId.value) &&
            proj == Right(src) &&
            tmpLeftovers == 0
          }
        )
      }
    },
    test(
      "regression: preflight-failed run invents no artifacts and no artifacts line"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          args = CliArgs
            .parse(
              List(
                "run",
                "--agent",
                "scout",
                "--input",
                "task-42",
                "--out",
                work.toString,
                "--provider",
                "openai",
                "--base-url",
                "not-a-url"
              )
            )
            .fold(e => throw e, identity)
          outcome <- HeadlessRunner.run(args, Map.empty)
          summary <- ZIO.attempt(summaryOf(outcome.workDir)).orDie
        yield assertTrue(
          outcome.exitCode == 21,
          !summary.contains("lineage artifacts"),
          !summary.contains("lineage:"),
          !Files.exists(outcome.workDir.resolve("lineage.scala")),
          !Files.exists(outcome.workDir.resolve("lineage-data.json"))
        )
      }
    }
  )

end LineageArtifactsSpec
