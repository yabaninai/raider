package raider.dsl.portable

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}

import scala.jdk.CollectionConverters.*

import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import raider.core.RaiderError
import raider.dsl.portable.PortableChatExport.sha256Hex
import raider.dsl.workflow.{
  NamedCheck,
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}

/** RAI-203/RAI-215 portable chat export: mapping fidelity (selection ->
  * portable workflow v1 document + closed manifest + input example), the
  * ADR-038 exact-match mapping of named checks onto pinned trustedCheck
  * references (purity, all-or-nothing refusals without fabrication), the
  * self-validation of the rendered artifacts, and the inherited R5
  * staging/never-overwrite discipline. FS facts are captured into vals BEFORE
  * cleanup (the assertTrue macro re-evaluates lazily).
  *
  * RAI-213 adds the staging-race discipline: a lost staging claim
  * (`FileAlreadyExistsException` inside `writeStaging`) refuses typed WITHOUT
  * deleting the winner's in-flight staging, proven by a deterministic
  * public-API pin and a latched two-thread concurrent-export race (latches, not
  * sleeps).
  */
object PortableChatExportSpec extends ZIOSpecDefault:

  /** The launcher marker check definition (ADR-038's worked example): the
    * pinned id plus the pinned argv vector. The digest below is the FROZEN
    * pinned snapshot (also asserted by the cli suite against
    * `PortableTrustedRegistry.checkSnapshot`) — pinning it here means any drift
    * in the canonical derivation breaks this suite.
    */
  private val markerArgv = List("/bin/test", "-f", "marker.txt")

  private val markerCheck =
    NamedCheck("workspace-marker-present", markerArgv)

  private val markerSnapshot =
    PortableChatExport.checkSnapshot("workspace-marker-present", markerArgv)

  private val FrozenMarkerSnapshot =
    "sha256:0c877972cf59788094e1d7653a1f01f6cd1b6c9108d117d5e069bc6b6bdaba0b"

  private val target = PortableExportTarget(
    profile = "coder",
    toolset = "workspace",
    checks =
      List(PortablePinnedCheck("workspace-marker-present", markerSnapshot))
  )

  /** A second pinned entry for the multi-gate document shape. */
  private val unitSnapshot =
    PortableChatExport.checkSnapshot(
      "unit-tests",
      List("sbt", "--batch", "test")
    )

  private val twoCheckTarget = PortableExportTarget(
    profile = "coder",
    toolset = "workspace",
    checks = List(
      PortablePinnedCheck("workspace-marker-present", markerSnapshot),
      PortablePinnedCheck("unit-tests", unitSnapshot)
    )
  )

  private val selection: StsSelection = StsSelection(
    sessionId = "sess-px",
    intent = StsTurn(
      "t-1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "make module X compile and keep checks green"
    ),
    refinements = List(
      StsTurn(
        "t-2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "do not touch the public API"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "modules/x"),
      StsParam("summary", "one-paragraph run summary")
    ),
    checks = Nil,
    agentStage = StsAgentStage(2),
    origin = StsOrigin(
      "openai-compatible",
      "test-model",
      Some("http://127.0.0.1:1/v1")
    )
  )

  private val selectionWithMarkerCheck =
    selection.copy(checks = List(markerCheck))

  private val selectionWithUnknownCheck = selection.copy(
    checks = List(NamedCheck("compile", List("sbt", "--batch", "compile")))
  )

  private val selectionWithBothChecks = selection.copy(
    checks = List(
      markerCheck,
      NamedCheck("unit-tests", List("sbt", "--batch", "test"))
    )
  )

  private def exportTo(
      tmp: Path,
      sel: StsSelection = selection,
      name: String = "portable-task",
      tgt: PortableExportTarget = target,
      outDir: Path = null
  ): Either[RaiderError, PortableExportResult] =
    PortableChatExport.exportTo(
      sel,
      PortableExportOptions(name),
      tgt,
      if outDir == null then tmp.resolve("out") else outDir,
      1750000000000L
    )

  private def cleanup(tmp: Path): Unit =
    Files
      .walk(tmp)
      .sorted(java.util.Comparator.reverseOrder[Path]())
      .forEach(p => Files.deleteIfExists(p))

  private def field(obj: Json, name: String): Option[Json] =
    obj match
      case o: Json.Obj => o.fields.collectFirst { case (`name`, v) => v }
      case _           => None

  private def stepsOf(doc: Json): List[Json] =
    field(doc, "steps")
      .collect { case Json.Arr(values) => values.toList }
      .getOrElse(Nil)

  private def stepById(doc: Json, id: String): Option[Json] =
    stepsOf(doc).find(step => field(step, "id").contains(Json.Str(id)))

  // -- RAI-213 staging-race fixtures -----------------------------------------

  private val RaceIterations = 30

  /** The exact rendered bundle for the standard fixture, keyed by artifact file
    * name. Both racing exports use identical inputs and rendering is pure, so
    * whichever thread wins must publish exactly these bytes. RAI-215 merged the
    * `gates` parameter into the renderers; for this fixture `selection.checks`
    * is empty, so the export's own gate resolution (`resolveGateChecks`) pins
    * exactly `target.checks` — passed here to keep the expected bundle
    * byte-exact under the widened signatures.
    */
  private def expectedBundle: List[(String, String)] =
    val documentText =
      PortableChatExport
        .documentJson(selection, target, target.checks)
        .toJson
    val inputText = PortableChatExport.inputExampleJson(selection).toJson
    val manifestText = PortableChatExport
      .manifestJson(
        selection,
        target,
        target.checks,
        "portable-task",
        documentText,
        inputText,
        1750000000000L
      )
      .toJson
    List(
      "portable-task.workflow.json" -> documentText,
      "portable-task-manifest.json" -> manifestText,
      "portable-task.input.json" -> inputText
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
        new Callable[Either[RaiderError, PortableExportResult]]:
          def call(): Either[RaiderError, PortableExportResult] =
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
        r.documentSha256 ==
          "sha256-" + sha256Hex(
            bundle.head._2.getBytes(StandardCharsets.UTF_8)
          ) &&
          r.inputExampleSha256 ==
          "sha256-" + sha256Hex(
            bundle.last._2.getBytes(StandardCharsets.UTF_8)
          )
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

  def spec = suite("PortableChatExport (RAI-203/RAI-215/RAI-213)")(
    test("document mapping: metadata, limits, steps, outputs, verdict") {
      val doc =
        PortableChatExport.documentJson(selection, target, target.checks)
      val metadata = field(doc, "metadata")
      val limits = field(doc, "limits")
      val outputs = field(doc, "outputs")
      val bindings = outputs.flatMap(field(_, "bindings"))
      val verdict = outputs.flatMap(field(_, "verdict"))
      val gate = stepById(doc, "gate")
      val run = stepById(doc, "run")
      assertTrue(
        field(doc, "format").contains(Json.Str("raider.portable-workflow")),
        metadata
          .flatMap(field(_, "exportedFrom"))
          .contains(
            Json.Str("raider.chat-selection")
          ),
        metadata
          .flatMap(field(_, "sessionId"))
          .contains(
            Json.Str("sess-px")
          ),
        limits
          .flatMap(field(_, "deadlineMs"))
          .contains(
            Json.Num(900000)
          ),
        limits
          .flatMap(field(_, "children"))
          .contains(
            Json.Num(0)
          ),
        stepsOf(doc).size == 3,
        gate.flatMap(field(_, "kind")).contains(Json.Str("trustedCheck")),
        gate
          .flatMap(field(_, "checkId"))
          .contains(Json.Str("workspace-marker-present")),
        gate
          .flatMap(field(_, "registrySnapshot"))
          .contains(Json.Str(markerSnapshot)),
        gate
          .flatMap(field(_, "input"))
          .contains(Json.Str("#/steps/act/output")),
        run.flatMap(field(_, "kind")).contains(Json.Str("sequence")),
        run
          .flatMap(field(_, "steps"))
          .contains(Json.Arr(Json.Str("act"), Json.Str("gate"))),
        bindings
          .flatMap(field(_, "result"))
          .contains(
            Json.Str("#/steps/run/output")
          ),
        verdict
          .flatMap(field(_, "from"))
          .contains(
            Json.Str("#/steps/run/output")
          ),
        verdict
          .flatMap(field(_, "authority"))
          .contains(
            Json.Str("trustedVerifier")
          )
      )
    },
    test("rendered document self-validates against the frozen v1 gate") {
      val text = PortableChatExport
        .documentJson(selection, target, target.checks)
        .toJson
      assertTrue(
        PortableWorkflowValidator.validate(text).isRight
      )
    },
    test("input example externalizes intent, refinements and named params") {
      val input = PortableChatExport.inputExampleJson(selection)
      val task = field(input, "task")
      assertTrue(
        task
          .flatMap(field(_, "intent"))
          .contains(
            Json.Str("make module X compile and keep checks green")
          ),
        task
          .flatMap(field(_, "targetPath"))
          .contains(
            Json.Str("modules/x")
          )
      )
    },
    test("manifest validates against its closed schema and digests match") {
      Files.createTempDirectory("pce-manifest") match
        case tmp =>
          try
            val documentText =
              PortableChatExport
                .documentJson(selection, target, target.checks)
                .toJson
            val inputText =
              PortableChatExport.inputExampleJson(selection).toJson
            val manifest = PortableChatExport.manifestJson(
              selection,
              target,
              target.checks,
              "portable-task",
              documentText,
              inputText,
              1750000000000L
            )
            val manifestText = manifest.toJson
            val checked = PortableChatExport.validateManifest(manifestText)
            val tamperedExtra = PortableChatExport.validateManifest(
              manifestText.replace(
                "\"schemaVersion\":1",
                "\"schemaVersion\":1,\"extra\":true"
              )
            )
            val malformedDigest = PortableChatExport.validateManifest(
              manifestText.replaceFirst("sha256:", "md5:")
            )
            val malformedSnapshot = PortableChatExport.validateManifest(
              manifestText.replace(markerSnapshot, "not-a-digest")
            )
            // The closed schema binds artifact SHAPE, not content: a
            // well-formed digest over different bytes still validates; the
            // content binding happens where the launcher compares digests
            // against the actual files (documented in the examples README).
            val reDigest = PortableChatExport.validateManifest(
              manifestText.replaceFirst(
                "sha256:[a-f0-9]{64}",
                "sha256:" + ("0" * 64)
              )
            )
            // The check-free path keeps the RAI-203 manifest shape: no
            // trustedChecks field.
            val hasTrustedChecks =
              manifestText.contains("trustedChecks")
            assertTrue(
              checked.isRight,
              tamperedExtra.isLeft,
              malformedDigest.isLeft,
              malformedSnapshot.isLeft,
              reDigest.isRight,
              !hasTrustedChecks
            )
          finally cleanup(tmp)
    },
    test(
      "ADR-038 mapping: exact match maps onto the pinned pair, is pure and folds duplicates; the derivation matches the frozen pinned digest"
    ) {
      val first = PortableChatExport.mapNamedChecks(
        List(markerCheck, markerCheck),
        target.checks
      )
      val second = PortableChatExport.mapNamedChecks(
        List(markerCheck, markerCheck),
        target.checks
      )
      assertTrue(
        PortableChatExport.checkSnapshot(
          "workspace-marker-present",
          markerArgv
        ) == FrozenMarkerSnapshot,
        first.isRight,
        first.toOption.get ==
          List(PortablePinnedCheck("workspace-marker-present", markerSnapshot)),
        first == second,
        PortableChatExport.gateStepIds(3) ==
          List("gate", "gate-2", "gate-3"),
        PortableChatExport.gateStepIds(1) == List("gate"),
        PortableChatExport.gateStepIds(0) == Nil
      )
    },
    test(
      "ADR-038 mapping: partial match (pinned name, different argv) refuses CWF-R3 without fabrication"
    ) {
      val partial = NamedCheck(
        "workspace-marker-present",
        List("/bin/test", "-f", "other.txt")
      )
      val refusal =
        PortableChatExport.mapNamedChecks(List(partial), target.checks)
      val detail = refusal.left.toOption.get.detail
      assertTrue(
        refusal.isLeft,
        refusal.left.toOption.get.code == "RA-INP",
        detail.contains("CWF-R3"),
        detail.contains("workspace-marker-present"),
        detail.contains("registrySnapshot differs"),
        detail.contains("Scala export"),
        // No fabricated pair: the refusal carries no suggestion to inline the
        // registered argv, and the export of the same selection refuses too.
        !detail.contains("/bin/test")
      )
    },
    test(
      "ADR-038 mapping: renamed check (pinned argv, different name) refuses without fabrication"
    ) {
      val renamed = NamedCheck("marker-present", markerArgv)
      val refusal =
        PortableChatExport.mapNamedChecks(List(renamed), target.checks)
      val detail = refusal.left.toOption.get.detail
      assertTrue(
        refusal.isLeft,
        detail.contains("CWF-R3"),
        detail.contains("marker-present"),
        detail.contains("no launcher-pinned trusted check has this id"),
        detail.contains("Scala export")
      )
    },
    test(
      "ADR-038 mapping: unknown registry check refuses CWF-R3 and keeps the Scala option"
    ) {
      val refusal =
        PortableChatExport.mapNamedChecks(
          selectionWithUnknownCheck.checks,
          target.checks
        )
      val detail = refusal.left.toOption.get.detail
      assertTrue(
        refusal.isLeft,
        detail.contains("CWF-R3"),
        detail.contains("compile"),
        detail.contains("no launcher-pinned trusted check has this id"),
        detail.contains("Scala export")
      )
    },
    test(
      "ADR-038 mapping: an advisory check refuses even on a definition-identical pin (mapping would strengthen it)"
    ) {
      val advisory =
        NamedCheck("workspace-marker-present", markerArgv, advisory = true)
      val refusal =
        PortableChatExport.mapNamedChecks(List(advisory), target.checks)
      val detail = refusal.left.toOption.get.detail
      assertTrue(
        refusal.isLeft,
        detail.contains("CWF-R3"),
        detail.contains("advisory"),
        detail.contains("Scala export")
      )
    },
    test(
      "ADR-038 document shape: two mapped checks render gate + gate-2, validate, and the manifest records trustedChecks"
    ) {
      val mapped =
        PortableChatExport.mapNamedChecks(
          selectionWithBothChecks.checks,
          twoCheckTarget.checks
        )
      val doc = mapped match
        case Right(gates) =>
          PortableChatExport.documentJson(
            selectionWithBothChecks,
            twoCheckTarget,
            gates
          )
        case Left(_) => Json.Obj()
      val gate = stepById(doc, "gate")
      val gate2 = stepById(doc, "gate-2")
      val run = stepById(doc, "run")
      val documentValid = mapped match
        case Right(gates) =>
          PortableWorkflowValidator.validate(
            PortableChatExport
              .documentJson(selectionWithBothChecks, twoCheckTarget, gates)
              .toJson
          )
        case Left(_) => Left("no gates")
      val manifest = mapped match
        case Right(gates) =>
          PortableChatExport
            .manifestJson(
              selectionWithBothChecks,
              twoCheckTarget,
              gates,
              "portable-task",
              PortableChatExport
                .documentJson(selectionWithBothChecks, twoCheckTarget, gates)
                .toJson,
              PortableChatExport
                .inputExampleJson(selectionWithBothChecks)
                .toJson,
              1750000000000L
            )
            .toJson
        case Left(_) => ""
      val manifestChecked = PortableChatExport.validateManifest(manifest)
      val tamperedTrustedCheck = PortableChatExport.validateManifest(
        manifest.replace(
          "\"checkId\":\"unit-tests\"",
          "\"checkId\":\"unit-tests\",\"argv\":[\"sbt\"]"
        )
      )
      assertTrue(
        mapped.isRight,
        mapped.toOption.get.size == 2,
        gate
          .flatMap(field(_, "checkId"))
          .contains(Json.Str("workspace-marker-present")),
        gate2.flatMap(field(_, "checkId")).contains(Json.Str("unit-tests")),
        gate2
          .flatMap(field(_, "registrySnapshot"))
          .contains(Json.Str(unitSnapshot)),
        run
          .flatMap(field(_, "steps"))
          .contains(
            Json.Arr(Json.Str("act"), Json.Str("gate"), Json.Str("gate-2"))
          ),
        documentValid.isRight,
        manifestChecked.isRight,
        manifest.contains("\"trustedChecks\":["),
        manifest.contains("\"checkId\":\"unit-tests\""),
        tamperedTrustedCheck.isLeft
      )
    },
    test(
      "an exact-match selection exports: the written document names the pinned pair and the manifest carries trustedChecks"
    ) {
      Files.createTempDirectory("pce-exact") match
        case tmp =>
          try
            val result = exportTo(tmp, sel = selectionWithMarkerCheck)
            val document = result.toOption.get.files
              .find(_.getFileName.toString == "portable-task.workflow.json")
              .get
            val manifest = result.toOption.get.files
              .find(_.getFileName.toString == "portable-task-manifest.json")
              .get
            val documentText =
              Files.readString(document, StandardCharsets.UTF_8)
            val manifestText =
              Files.readString(manifest, StandardCharsets.UTF_8)
            val documentIsFile = Files.isRegularFile(document)
            assertTrue(
              result.isRight,
              documentIsFile,
              documentText.contains("\"checkId\":\"workspace-marker-present\""),
              documentText.contains(FrozenMarkerSnapshot),
              documentText.contains("\"gate-\"") == false,
              manifestText.contains("\"trustedChecks\":[{"),
              manifestText.contains(FrozenMarkerSnapshot)
            )
          finally cleanup(tmp)
    },
    test(
      "named trusted checks that match nothing refuse CWF-R3 and keep the Scala option; nothing is written"
    ) {
      Files.createTempDirectory("pce-r3") match
        case tmp =>
          try
            val refusal = exportTo(tmp, sel = selectionWithUnknownCheck)
            val detail = refusal.left.toOption.get.detail
            val nothingWritten = !Files.exists(tmp.resolve("out"))
            assertTrue(
              refusal.isLeft,
              refusal.left.toOption.get.code == "RA-INP",
              detail.contains("CWF-R3"),
              detail.contains("compile"),
              detail.contains("Scala export"),
              nothingWritten
            )
          finally cleanup(tmp)
    },
    test("export names outside the slug rules refuse R1") {
      Files.createTempDirectory("pce-names") match
        case tmp =>
          try
            val empty = exportTo(tmp, name = "")
            val traversal = exportTo(tmp, name = "sub/dir")
            val tooLong = exportTo(tmp, name = "a" * 97)
            val emptyMsg = empty.left.toOption.get.detail
            val traversalMsg = traversal.left.toOption.get.detail
            val tooLongMsg = tooLong.left.toOption.get.detail
            assertTrue(
              empty.isLeft && emptyMsg.contains("CWF-R1"),
              traversal.isLeft && traversalMsg.contains("CWF-R1"),
              tooLong.isLeft && tooLongMsg.contains("CWF-R1")
            )
          finally cleanup(tmp)
    },
    test("provenance metadata over the 256-character bound refuses R1") {
      Files.createTempDirectory("pce-prov") match
        case tmp =>
          try
            val hugeModel =
              selection.copy(origin = selection.origin.copy(model = "m" * 257))
            val refused = exportTo(tmp, sel = hugeModel)
            val refusedMsg = refused.left.toOption.get.detail
            assertTrue(
              refused.isLeft && refusedMsg.contains("256-character")
            )
          finally cleanup(tmp)
    },
    test(
      "publish writes the three artifacts and the manifest digests match the files"
    ) {
      Files.createTempDirectory("pce-publish") match
        case tmp =>
          try
            val result = exportTo(tmp)
            val files = result.toOption.get.files
            val document = files
              .find(_.getFileName.toString == "portable-task.workflow.json")
              .get
            val manifest = files
              .find(_.getFileName.toString == "portable-task-manifest.json")
              .get
            val input = files
              .find(_.getFileName.toString == "portable-task.input.json")
              .get
            val documentBytes = Files.readAllBytes(document)
            val inputBytes = Files.readAllBytes(input)
            val documentDigest = sha256Hex(documentBytes)
            val inputDigest = sha256Hex(inputBytes)
            // FS facts captured BEFORE cleanup — the assertTrue macro
            // re-evaluates lazily.
            val documentIsFile = Files.isRegularFile(document)
            val manifestIsFile = Files.isRegularFile(manifest)
            val inputIsFile = Files.isRegularFile(input)
            val staged =
              Files.exists(tmp.resolve(".out.raider-portable-export-staging"))
            assertTrue(
              result.isRight,
              result.toOption.get.targetDir == tmp.resolve("out"),
              documentIsFile,
              manifestIsFile,
              inputIsFile,
              result.toOption.get.documentSha256 == "sha256-" + documentDigest,
              result.toOption.get.inputExampleSha256 == "sha256-" + inputDigest,
              !staged
            )
          finally cleanup(tmp)
    },
    test(
      "R5: missing parent, existing target, symlink target, staging remnant"
    ) {
      Files.createTempDirectory("pce-r5") match
        case tmp =>
          try
            val noParent = exportTo(
              tmp,
              name = "p1",
              outDir = tmp.resolve("missing").resolve("out")
            )
            val firstResult =
              PortableChatExport.exportTo(
                selection,
                PortableExportOptions("p2"),
                target,
                tmp.resolve("out2"),
                0L
              )
            val again = PortableChatExport.exportTo(
              selection,
              PortableExportOptions("p2"),
              target,
              tmp.resolve("out2"),
              0L
            )
            val linkTarget = tmp.resolve("real-dir")
            Files.createDirectory(linkTarget)
            val link = tmp.resolve("out3")
            Files.createSymbolicLink(link, linkTarget)
            val symlink = PortableChatExport.exportTo(
              selection,
              PortableExportOptions("p3"),
              target,
              link,
              0L
            )
            Files.createDirectory(
              tmp.resolve(".out4.raider-portable-export-staging")
            )
            val remnant = PortableChatExport.exportTo(
              selection,
              PortableExportOptions("p4"),
              target,
              tmp.resolve("out4"),
              0L
            )
            val noParentMsg = noParent.left.toOption.get.detail
            val againMsg = again.left.toOption.get.detail
            val remnantMsg = remnant.left.toOption.get.detail
            assertTrue(
              noParent.isLeft && noParentMsg.contains("never creates parent"),
              firstResult.isRight,
              again.isLeft && againMsg.contains("never overwrites user edits"),
              symlink.isLeft && symlink.left.toOption.get.detail
                .contains("symlink"),
              remnant.isLeft && remnantMsg.contains(
                "interrupted export remnant"
              )
            )
          finally cleanup(tmp)
    },
    test(
      "RAI-213: a lost staging claim refuses CWF-R5 WITHOUT deleting the existing staging (deterministic)"
    ) {
      Files.createTempDirectory("pce-r213-claim") match
        case tmp =>
          try
            val stagingDir =
              tmp.resolve(".out" + PortableChatExport.StagingSuffix)
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
      "RAI-213: latched two-thread export race — exactly one publishes, the loser refuses typed, nothing half-published, no staging remnant (latches, 30 repetitions)"
    ) {
      val pool = Executors.newFixedThreadPool(2)
      try
        val observations = (1 to RaceIterations).map { iteration =>
          val tmp = Files.createTempDirectory(s"pce-r213-race-$iteration")
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

end PortableChatExportSpec
