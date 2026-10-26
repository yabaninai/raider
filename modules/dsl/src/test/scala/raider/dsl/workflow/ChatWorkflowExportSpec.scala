package raider.dsl.workflow

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import zio.test.*
import zio.test.Assertion.*

import raider.core.RaiderError

/** RAI-219 chat export staging-race discipline (mirror of the accepted RAI-213
  * fix in `PortableChatExport`): a lost staging claim
  * (`FileAlreadyExistsException` inside `writeStaging`) refuses typed WITHOUT
  * deleting the winner's in-flight staging, so exactly one export publishes.
  * Proven by a deterministic dangling-symlink pin (the same check-then-create
  * window the race hits, forced without timing) and a latched two-thread
  * concurrent export race (latches, no sleeps, 30 repetitions): exactly one
  * publishes, the loser refuses typed (CWF-R5), the winner's staging stays
  * intact, and the published bundle is byte-exact. FS facts are captured into
  * vals BEFORE cleanup (the assertTrue macro re-evaluates lazily).
  */
object ChatWorkflowExportSpec extends ZIOSpecDefault:

  // ----- representative STS fixture (ADR-033 §11.4 reference lifecycle) -----

  private val fixture: StsSelection = StsSelection(
    sessionId = "sess-20261026-export-race",
    intent = StsTurn(
      "t1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Make the failing widget module compile again and keep its checks green"
    ),
    refinements = List(
      StsTurn(
        "t2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Do not touch the public API"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "src/main/scala/widget/Widget.scala"),
      StsParam(
        "summary",
        "Widget.scala does not compile after the 0.4 refactor"
      )
    ),
    checks = List(
      NamedCheck("compile", List("sbt", "--batch", "compile"))
    ),
    agentStage = StsAgentStage(maxRounds = 6),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "mock-coder",
      baseUrl = Some("http://127.0.0.1:8901/v1")
    )
  )

  private val options: RenderOptions = RenderOptions("race_export")

  private def exportTo(tmp: Path): Either[RaiderError, ExportResult] =
    ChatWorkflowExport.exportTo(fixture, options, tmp.resolve("out"))

  private def cleanup(tmp: Path): Unit =
    Files
      .walk(tmp)
      .sorted(java.util.Comparator.reverseOrder[Path]())
      .forEach(p => Files.deleteIfExists(p))

  // -- RAI-219 staging-race fixtures -----------------------------------------

  private val RaceIterations = 30

  /** The exact rendered bundle for the standard fixture, keyed by artifact file
    * name. Both racing exports use identical inputs and rendering is pure, so
    * whichever thread wins must publish exactly these bytes.
    */
  private lazy val expectedRendered: RenderedExport =
    ChatWorkflowRenderer.render(fixture, options) match
      case Right(r)    => r
      case Left(error) => throw new IllegalStateException(error.detail)

  private def expectedBundle: List[(String, String)] =
    List(
      expectedRendered.name + ".scala" -> expectedRendered.source,
      expectedRendered.name + "-manifest.json" -> expectedRendered.manifestJson,
      expectedRendered.name + ".input.json" -> expectedRendered.inputExampleJson
    )

  /** FS facts of one latched two-thread race, all captured before cleanup (the
    * assertTrue macro re-evaluates lazily).
    */
  private case class RaceObservation(
      published: Int,
      loserTyped: Boolean,
      parentExactlyTarget: Boolean,
      targetExactlyBundle: Boolean,
      winnerDigestsMatch: Boolean
  )

  private def listNames(dir: Path): List[String] =
    val stream = Files.list(dir)
    try stream.iterator().asScala.map(p => p.getFileName.toString).toList
    finally stream.close()

  /** One race iteration: two threads released by ONE latch run full exports to
    * the same target; every verdict is read from the filesystem only after both
    * threads are joined (latches, not sleeps).
    */
  private def raceOnce(
      pool: java.util.concurrent.ExecutorService,
      tmp: Path
  ): RaceObservation =
    val start = new CountDownLatch(1)
    val futures = (1 to 2).map { _ =>
      pool.submit(
        new Callable[Either[RaiderError, ExportResult]]:
          def call(): Either[RaiderError, ExportResult] =
            start.await()
            exportTo(tmp)
      )
    }
    start.countDown()
    val outcomes = futures.map(_.get(60, TimeUnit.SECONDS))
    val published = outcomes.collect { case Right(r) => r }
    val refusals = outcomes.collect { case Left(e) => e }
    val targetDir = tmp.resolve("out")
    val bundle = expectedBundle
    val publishedResult = published.headOption
    val winnerDigestsMatch = publishedResult match
      case None => false
      case Some(r) =>
        bundle.forall { case (fileName, text) =>
          Files.readAllBytes(targetDir.resolve(fileName)).toSeq ==
            text.getBytes(StandardCharsets.UTF_8).toSeq
        } &&
        r.sourceDigest == expectedRendered.sourceDigest &&
        r.specDigest == expectedRendered.specDigest
    RaceObservation(
      published = published.size,
      loserTyped = refusals.size == 1 &&
        refusals.head.code == "RA-INP" &&
        refusals.head.detail.contains("CWF-R5"),
      parentExactlyTarget = listNames(tmp).sorted == List("out"),
      targetExactlyBundle = publishedResult.nonEmpty &&
        listNames(targetDir).sorted == bundle.map(_._1).sorted,
      winnerDigestsMatch = winnerDigestsMatch
    )

  def spec = suite("ChatWorkflowExport (RAI-219 staging-race)")(
    test(
      "RAI-219: a lost staging claim refuses CWF-R5 WITHOUT deleting the existing staging (deterministic dangling-symlink pin)"
    ) {
      Files.createTempDirectory("cwf-r219-claim") match
        case tmp =>
          try
            val stagingDir =
              tmp.resolve(".out" + ChatWorkflowExport.StagingSuffix)
            // Deterministic stand-in for the winner's in-flight staging as it
            // presents inside writeStaging: the freshness check sees nothing
            // (a dangling link does not exist for Files.exists) while
            // Files.createDirectory still fails with
            // FileAlreadyExistsException — the same check-then-create window
            // the two-thread race hits, forced without timing.
            Files.createSymbolicLink(stagingDir, tmp.resolve("absent-target"))
            val refused = exportTo(tmp)
            val refusedMsg = refused.left.toOption.get.detail
            // FS facts captured BEFORE cleanup (lazy assertTrue).
            val stagingIntact = Files.isSymbolicLink(stagingDir)
            val nothingPublished = !Files.exists(tmp.resolve("out"))
            assertTrue(
              refused.isLeft && refusedMsg.contains("CWF-R5"),
              refusedMsg.contains("staging claim lost"),
              refusedMsg.contains("WITHOUT deleting"),
              stagingIntact,
              nothingPublished
            )
          finally cleanup(tmp)
    },
    test(
      "RAI-219: latched two-thread export race — exactly one publishes, the loser refuses typed, the winner's staging stays intact, byte-exact bundle (latches, no sleeps, 30 repetitions)"
    ) {
      val pool = Executors.newFixedThreadPool(2)
      try
        val observations = (1 to RaceIterations).map { iteration =>
          val tmp = Files.createTempDirectory(s"cwf-r219-race-$iteration")
          try raceOnce(pool, tmp)
          finally cleanup(tmp)
        }
        assertTrue(
          observations.forall(o =>
            o.published == 1 &&
              o.loserTyped &&
              o.parentExactlyTarget &&
              o.targetExactlyBundle &&
              o.winnerDigestsMatch
          )
        )
      finally pool.shutdownNow()
    }
  )

end ChatWorkflowExportSpec
