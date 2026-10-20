package raider.cli.run

import raider.core.{JobId, RootId, SessionId}
import raider.dsl.lineage.data.{
  LineageDataDocument,
  LineageDataFile,
  NodeData,
  NodeKind
}
import raider.dsl.lineage.proj.{LineageRenderer}
import raider.dsl.lineage.store.LineageDataStore
import raider.cli.lineage.SessionGraphView
import raider.cli.lineage.LineageSnapshot
import raider.runtime.events.{EventEnvelope, EventCodecs, ToolOutcome}
import raider.runtime.events.EventCodecs.given
import raider.runtime.lineage.model.{DataRef, NodeId, SessionGraph, SessionNode}
import raider.runtime.lineage.reduce.LineageReducer
import raider.runtime.lineage.wire.{ArtifactPointer, LineagePayload}
import zio.ZIO
import zio.json.{DecoderOps, EncoderOps, JsonDecoder}
import zio.json.ast.Json
import zio.test._

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
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

  private def checkProducerConcurrency: zio.UIO[Boolean] = ZIO.scoped {
    for
      base <- ZIO.attempt(tempDir()).orDie
      chatWorkspace = base.resolve("chat-workspace")
      headlessOut = base.resolve("headless-out")
      campaignOut = base.resolve("campaign-out")
      _ <- ZIO.attempt {
        Files.createDirectories(headlessOut)
        Files.createDirectories(campaignOut)
      }.orDie
      jar <- ZIO.attempt(makeBundleJar(base)).orDie
      produced <- ZIO.collectAllPar(
        List(
          raider.cli.chat.TaskCaptureSpec.produceChatFixture(chatWorkspace),
          HeadlessRunner.run(mockAgentArgs(headlessOut)).map(_.workDir),
          HeadlessRunner.run(bundleArgs(jar, campaignOut)).map(_.workDir)
        )
      )
      chatDir = produced(0)
      headlessDir = produced(1)
      campaignDir = produced(2)
      checked <- ZIO.attempt {
        def entry(out: Path, scope: raider.cli.lineage.CatalogScope) =
          raider.cli.lineage.CatalogStore
            .load(raider.cli.lineage.CatalogStore.catalogFile(out))
            .fold(
              error => throw new IllegalStateException(error.toString),
              identity
            )
            .find(_.scope == scope)
        val chatEntry = entry(
          chatWorkspace.resolve(".raider").resolve("runs"),
          raider.cli.lineage.CatalogScope.Chat
        )
        val headlessEntry = entry(
          headlessOut,
          raider.cli.lineage.CatalogScope.Headless
        )
        val campaignEntry = entry(
          campaignOut,
          raider.cli.lineage.CatalogScope.Campaign
        )
        val snapshots = Vector(chatDir, headlessDir).map(
          raider.cli.lineage.LineageSnapshot.read
        )
        val pins = Vector(chatDir, headlessDir, campaignDir).map(dir =>
          raider.cli.lineage.EnvPinning.readPinned(
            dir.resolve("toolchain.json")
          )
        )
        (chatEntry, headlessEntry, campaignEntry, snapshots, pins)
      }.orDie
      (chatEntry, headlessEntry, campaignEntry, snapshots, pins) = checked
      oldChat = snapshots(0).toOption.get
      reached <- ZIO.succeed(new CountDownLatch(1))
      hold <- ZIO.succeed(new CountDownLatch(1))
      interrupted <- ZIO.attemptBlockingInterrupt {
        raider.cli.lineage.LineageSnapshot.publishAfterGeneration(
          chatDir,
          Files.readAllBytes(oldChat.dataPath),
          Files.readAllBytes(oldChat.sourcePath)
        ) {
          reached.countDown()
          hold.await()
        }
      }.fork
      window <- ZIO.attemptBlocking(reached.await(10, TimeUnit.SECONDS)).orDie
      _ <- interrupted.interrupt
      after = Vector(chatDir, headlessDir).map(
        raider.cli.lineage.LineageSnapshot.read
      )
      afterPins <- ZIO.attempt {
        Vector(chatDir, headlessDir, campaignDir).map(dir =>
          raider.cli.lineage.EnvPinning.readPinned(
            dir.resolve("toolchain.json")
          )
        )
      }.orDie
      _ <- ZIO.succeed(hold.countDown())
    yield produced.distinct.size == 3 &&
      chatDir != headlessDir && headlessDir != campaignDir && chatDir != campaignDir &&
      chatEntry.exists(_.path == chatDir.toString) &&
      headlessEntry.exists(_.path == headlessDir.toString) &&
      campaignEntry.exists(_.path == campaignDir.toString) &&
      chatEntry.exists(_.scope == raider.cli.lineage.CatalogScope.Chat) &&
      headlessEntry.exists(
        _.scope == raider.cli.lineage.CatalogScope.Headless
      ) &&
      campaignEntry.exists(
        _.scope == raider.cli.lineage.CatalogScope.Campaign
      ) &&
      snapshots.forall(_.isRight) &&
      raider.cli.lineage.LineageSnapshot.read(campaignDir).isLeft &&
      pins.forall(_.isRight) &&
      snapshots.forall(
        _.toOption.exists(s =>
          Files.isReadable(s.dataPath) && Files.isReadable(s.sourcePath)
        )
      ) &&
      window &&
      after(0).toOption.exists(_.generation == oldChat.generation) &&
      after.drop(1).forall(_.isRight) &&
      afterPins == pins &&
      after.forall(_.isRight) &&
      raider.cli.lineage.LineageSnapshot.read(campaignDir).isLeft
  }

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

  // ----- RAI-154 (WIR-03) fixtures -------------------------------------------

  /** A phase-bearing campaign graph: a model round, a wire-native phase under
    * it, and a completed tool call under the phase — the unified shape the
    * reducer builds for v2 `phase_started` streams (ADR-032).
    */
  private def phaseGraph: SessionGraph =
    SessionGraph(
      RootId("cli_rai154"),
      SessionId("sess_rai154"),
      Vector(
        SessionNode.ModelCall(
          NodeId("j_1:n1"),
          None,
          1L,
          1000L,
          1,
          "mock-model",
          Vector.empty,
          DataRef("j_1/round-1")
        ),
        SessionNode.Phase(
          NodeId("j_1:n2"),
          Some(NodeId("j_1:n1")),
          2L,
          "implement",
          Some("the build step")
        ),
        SessionNode.ToolCall(
          NodeId("j_1:n3"),
          Some(NodeId("j_1:n2")),
          3L,
          2000L,
          "c1",
          "bash",
          raider.runtime.lineage.model.RecoveryClass.Mutating,
          Some(ToolOutcome.Succeeded),
          Vector(NodeId("j_1:n1")),
          Some(DataRef("j_1/call-c1"))
        )
      )
    ).validated
      .fold(e => throw new IllegalStateException(s"fixture: $e"), identity)

  /** The ADR-032 cross-run provenance record the exporter emits for a run with
    * joined child segments (root run — `parentRunId` absent, never guessed).
    */
  private val provenanceRecord: LineagePayload.RunProvenance =
    LineagePayload.RunProvenance(
      None,
      Vector(
        ArtifactPointer("work_dir", "/tmp/raider-unified-seg"),
        ArtifactPointer("events", "/tmp/raider-unified-seg/events.jsonl")
      )
    )

  /** The raw lineage-data.json bytes of a run dir (fixture-level loudness: a
    * missing file is a test error, never a silent pass).
    */
  private def rawDocument(work: Path): String =
    Files.readString(work.resolve("lineage-data.json"), UTF_8)

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
            val pin = raider.cli.lineage.EnvPinning.readPinned(
              outcome.workDir.resolve("toolchain.json")
            )
            val catalog = raider.cli.lineage.CatalogStore.load(
              raider.cli.lineage.CatalogStore.catalogFile(work)
            )
            (doc, src, summary, pin, catalog)
          }.orDie
          (doc, src, summary, pin, catalog) = checked
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
          pin == Right(
            raider.cli.lineage.EnvPinning.PinnedEnv(
              raider.cli.lineage.EnvPinning.currentEnv().scalaVersion,
              raider.cli.lineage.EnvPinning.currentEnv().jdkVersion,
              raider.cli.lineage.EnvPinning.currentEnv().raiderVersion,
              None
            )
          ),
          summary.contains("- toolchain pin: toolchain.json"),
          catalog.toOption.exists(
            _.exists(record =>
              record.path == outcome.workDir.toString &&
                record.scope == raider.cli.lineage.CatalogScope.Headless &&
                record.pinnedEnv.exists(
                  _.scalaVersion == pin.toOption.get.scalaVersion
                )
            )
          ),
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
              case Right(_) =>
                val committed = LineageSnapshot
                  .read(work)
                  .fold(
                    e => throw new IllegalStateException(e.toString),
                    identity
                  )
                dotcCompile(committed.sourcePath) match
                  case Right(0) => Right(0)
                  case Right(code) =>
                    Left(s"dotc exit $code for ${committed.sourcePath}")
                  case Left(err) => Left(err)
              case Left(e) => Left("writer refused: " + e)
          }.orDie
          doc <- ZIO.attempt(documentOf(work)).orDie
        yield assertTrue(
          written.isRight,
          Files.isRegularFile(work.resolve(LineageSnapshot.CommitName)),
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
          catalog <- ZIO
            .attempt(
              raider.cli.lineage.CatalogStore.load(
                raider.cli.lineage.CatalogStore.catalogFile(work)
              )
            )
            .orDie
        yield assertTrue(
          outcome.exitCode == 0,
          summary.contains("- lineage artifacts: unavailable ("),
          !Files.exists(outcome.workDir.resolve("lineage.scala")),
          !Files.exists(outcome.workDir.resolve("lineage-data.json")),
          Files.isRegularFile(outcome.workDir.resolve("toolchain.json")),
          catalog.toOption.exists(
            _.exists(record =>
              record.path == outcome.workDir.toString &&
                record.scope == raider.cli.lineage.CatalogScope.Campaign &&
                record.complete
            )
          )
        )
      }
    },
    test(
      "LIN8B-01: catalog-referenced run replays with a clean matching pin and an annotated divergent pin"
    ) {
      ZIO.scoped {
        for
          output <- ZIO.attempt(tempDir()).orDie
          runDir <- ZIO
            .attempt(
              Files.createDirectory(output.resolve("work-catalog-replay"))
            )
            .orDie
          document = raider.cli.lineage.ReplayHarness.document
          graph <- ZIO
            .attempt(
              raider.cli.lineage.ReplayHarness.recordedGraph(document)
            )
            .orDie
          _ <- ZIO.attempt {
            val published = raider.cli.lineage.LineageSnapshot.publish(
              runDir,
              document.toJson.getBytes(UTF_8),
              "// catalog replay fixture\n".getBytes(UTF_8)
            )
            published.fold(
              error => throw new IllegalStateException(error.toString),
              identity
            )
            raider.cli.lineage.ToolchainPin
              .write(runDir, None)
              .fold(
                error => throw new IllegalStateException(error),
                identity
              )
            val entry = raider.cli.lineage.CatalogEntry(
              path = runDir,
              rootId = graph.rootId.value,
              sessionId = Some(graph.sessionId.value),
              nodeCount = graph.nodes.size,
              hash = raider.runtime.lineage.model.GraphHash(graph),
              complete = true,
              tailReported = None,
              scannedAtMs = 0L
            )
            val file = raider.cli.lineage.CatalogStore.catalogFile(output)
            raider.cli.lineage.CatalogStore
              .append(
                file,
                raider.cli.lineage.CatalogStore.recordsOf(
                  Vector(entry),
                  Some(
                    raider.cli.lineage.PinnedSummary.of(
                      raider.cli.lineage.EnvPinning.currentEnv()
                    )
                  ),
                  raider.cli.lineage.CatalogScope.Campaign
                )
              )
              .fold(
                error => throw new IllegalStateException(error.toString),
                identity
              )
          }.orDie
          workspace <- ZIO.attempt {
            val path =
              Files.createTempDirectory("raider-lin8b-replay-workspace")
            Files.writeString(
              path.resolve(raider.cli.lineage.ReplayHarness.FixtureFile),
              raider.cli.lineage.ReplayHarness.FixtureFileContent,
              UTF_8
            )
            path
          }.orDie
          catalog <- ZIO
            .attempt(
              raider.cli.lineage.CatalogStore.load(
                raider.cli.lineage.CatalogStore.catalogFile(output)
              )
            )
            .orDie
          referenced = catalog.toOption.toVector.flatten.find(_.complete)
          referencedRun = referenced
            .map(record => Path.of(record.path))
            .getOrElse(output)
          matching <- raider.cli.lineage.ReplayCli.run(
            raider.cli.lineage.ReplayCli.Config(referencedRun, workspace, None)
          )
          _ <- ZIO.attempt {
            val current = raider.cli.lineage.EnvPinning.currentEnv()
            Files.writeString(
              referencedRun.resolve("toolchain.json"),
              s"""{"scala":"3.8.2-cts","jdk":"${current.jdkVersion}","raider":"${current.raiderVersion}"}""",
              UTF_8
            )
          }.orDie
          divergent <- raider.cli.lineage.ReplayCli.run(
            raider.cli.lineage.ReplayCli.Config(referencedRun, workspace, None)
          )
        yield assertTrue(
          referenced.exists(
            _.scope == raider.cli.lineage.CatalogScope.Campaign
          ),
          referenced.exists(_.path == runDir.toString),
          matching.isInstanceOf[raider.cli.lineage.ReplayCli.Verdict.Match],
          !raider.cli.lineage.ReplayCli
            .render(matching)
            .contains("reproduced in a different environment"),
          divergent.isInstanceOf[
            raider.cli.lineage.ReplayCli.Verdict.DifferentEnvironment
          ],
          raider.cli.lineage.ReplayCli
            .render(divergent)
            .startsWith("reproduced in a different environment:"),
          raider.cli.lineage.ReplayCli
            .render(divergent)
            .contains("verdict: match")
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
          producerIsolation <- checkProducerConcurrency
        yield assertTrue(
          outcomes.map(_.exitCode).forall(_ == 0),
          outcomes.map(_.workDir).distinct.size == 6,
          checked.forall { (o, doc, proj, src, tmpLeftovers) =>
            doc.isRight &&
            doc.map(_.runId) == Right(o.result.get.rootId.value) &&
            proj == Right(src) &&
            tmpLeftovers == 0
          },
          producerIsolation
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
          !Files.exists(outcome.workDir.resolve("lineage-data.json")),
          !Files.exists(outcome.workDir.resolve("toolchain.json"))
        )
      }
    },
    // ----- RAI-154 / WIR-03: the unified lineage-data writer -------------------
    test(
      "WIR-03: a phase-bearing graph writes lineage-data v2 with real phase entries (name/note bound as PhaseData, no invented recovery), a v2 document without the provenance record carries no v2-only fields, and the projection compiles with the pinned dotc"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          written <- ZIO
            .attempt(
              RunLineageArtifacts.write(work, phaseGraph, Vector.empty, None)
            )
            .orDie
          checked <- ZIO.attempt {
            written match
              case Right(w) =>
                val doc = validatedDocumentOf(work) match
                  case Right(d) => d
                  case Left(e)  => throw new IllegalStateException(e)
                (w, doc, rawDocument(work), sourceOf(work))
              case Left(e) => throw new IllegalStateException(s"refused: $e")
          }.orDie
          (w, doc, raw, src) = checked
          phaseNode = doc.nodes.find(_.kind == NodeKind.Phase)
          phaseEntry = phaseNode.flatMap(_.dataRef).flatMap(doc.data.get)
          compileExit <- ZIO.attempt(dotcCompile(w.source)).orDie
        yield assertTrue(
          Files.isRegularFile(w.source) && Files.isRegularFile(w.data),
          doc.schemaVersion == 2,
          doc.provenance.isEmpty,
          // the phase is a REAL node: reducer id convention, parent, notated
          phaseNode.isDefined,
          phaseNode.map(_.id) == Some("j_1:n2"),
          phaseNode.flatMap(_.parentId) == Some("j_1:n1"),
          phaseNode.flatMap(_.dataRef) == Some("j_1/phase-2"),
          phaseNode.map(_.atMs) == Some(0L), // no timestamp in the wave-1 model
          phaseEntry == Some(
            NodeData.PhaseData(
              "implement",
              Some("the build step"),
              recovery = None
            )
          ),
          // byte-stability: the stored bytes are exactly the codec's
          // canonical form of the document
          raw == doc.toJson,
          // a v2 document without the record carries NO v2-only fields: no
          // top-level provenance, and the phase entry carries NO recovery key
          // (the tool entry's v1 recovery vocabulary is unaffected)
          !raw.contains("\"provenance\""),
          !raw.contains("\"invocationId\""),
          !raw.contains(
            "\"j_1/phase-2\":{\"name\":\"implement\",\"note\":\"the build step\",\"recovery\""
          ),
          // the rendered projection is produced verbatim and compiles
          src.contains("phase_implement"),
          compileExit == Right(0)
        )
      }
    },
    test(
      "WIR-03: a phase-bearing run with the exported provenance record writes schemaVersion 2 WITH the record and validates through the frozen reader"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          written <- ZIO
            .attempt(
              RunLineageArtifacts.write(
                work,
                phaseGraph,
                Vector.empty,
                Some(provenanceRecord)
              )
            )
            .orDie
          checked <- ZIO.attempt {
            written match
              case Right(_) =>
                val doc = validatedDocumentOf(work) match
                  case Right(d) => d
                  case Left(e)  => throw new IllegalStateException(e)
                val raw = rawDocument(work)
                (doc, raw)
              case Left(e) => throw new IllegalStateException(s"refused: $e")
          }.orDie
          (doc, raw) = checked
        yield assertTrue(
          doc.schemaVersion == 2,
          doc.provenance.isDefined,
          doc.provenance
            .flatMap(_.parentRunId)
            .isEmpty, // root run, not guessed
          doc.provenance.map(_.artifacts.size) == Some(2),
          raw.contains("\"provenance\""),
          doc.nodes.exists(_.kind == NodeKind.Phase),
          doc.data.values.exists {
            case NodeData.PhaseData("implement", _, _) => true
            case _                                     => false
          }
        )
      }
    },
    test(
      "WIR-03 regression pin: delegation-free, phase-free documents stay frozen v1 byte-for-byte"
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
          checked <- ZIO.attempt {
            written match
              case Right(_) =>
                val doc = validatedDocumentOf(work) match
                  case Right(d) => d
                  case Left(e)  => throw new IllegalStateException(e)
                (doc, rawDocument(work))
              case Left(e) => throw new IllegalStateException(s"refused: $e")
          }.orDie
          (doc, raw) = checked
        yield assertTrue(
          doc.schemaVersion == 1,
          raw.startsWith("{\"schemaVersion\":1"),
          raw == doc.toJson,
          !raw.contains("\"provenance\""),
          !raw.contains("phase"),
          !raw.contains("invocationId")
        )
      }
    },
    test(
      "WIR-03: refusals stay typed — a structurally inconsistent graph refuses with ArtifactError.View and invents no files"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          // Raw construction: duplicate ids pass the constructor, fail the
          // view's structural validation (data, never a crash)
          broken = SessionGraph(
            RootId("cli_rai154_broken"),
            SessionId("sess_rai154_broken"),
            Vector(
              SessionNode.ModelCall(
                NodeId("j_1:n1"),
                None,
                1L,
                1000L,
                1,
                "mock-model",
                Vector.empty,
                DataRef("j_1/round-1")
              ),
              SessionNode.ModelCall(
                NodeId("j_1:n1"),
                None,
                2L,
                2000L,
                2,
                "mock-model",
                Vector.empty,
                DataRef("j_1/round-2")
              )
            )
          )
          refused <- ZIO
            .attempt(
              RunLineageArtifacts.write(work, broken, Vector.empty, None)
            )
            .orDie
        yield assertTrue(
          refused.fold(
            {
              case _: RunLineageArtifacts.ArtifactError.View => true
              case _                                         => false
            },
            _ => false
          ),
          !Files.exists(work.resolve("lineage.scala")),
          !Files.exists(work.resolve("lineage-data.json"))
        )
      }
    }
  ) @@ TestAspect.withLiveClock
  // RAI-178: HeadlessRunner.run's ensurings poll on the ambient clock;

  // live-clock discipline (docs/work/records/RAI-178.md). Assertions unchanged.

end LineageArtifactsSpec
