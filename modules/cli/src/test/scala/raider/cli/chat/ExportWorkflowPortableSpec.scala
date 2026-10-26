package raider.cli.chat

import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import raider.cli.portable.{PortableRun, PortableTrustedRegistry}
import raider.dsl.portable.{PortableChatExport, PortablePinnedCheck}

/** RAI-203/RAI-215 `:export-workflow --format portable` command checks over the
  * real command seam (`execute`) under the ADR-038 mapping: the chat
  * selection's named checks export onto THIS launcher's pinned trusted
  * references only by exact match (same id, same definition digest,
  * non-advisory). The card's acceptance cases are covered at command level: the
  * exact-match selection exports, validates, and runs green through native
  * lowering (`PortableRun`, mock provider); partial match, renamed check and
  * unknown registry each refuse CWF-R3 with the per-check reason while naming
  * the retained Scala option. A drift guard pins the dsl derivation against the
  * launcher registry's derivation on the frozen marker digest. The `--format`
  * grammar, the mock-transport R1 refusal and the shared default name
  * derivation stay from RAI-203. All FS facts are captured into vals BEFORE
  * cleanup (the assertTrue macro re-evaluates lazily).
  */
object ExportWorkflowPortableSpec extends ZIOSpecDefault:

  /** The pinned launcher marker check definition. The frozen digest below is
    * `PortableTrustedRegistry.workspaceMarkerSnapshot` (also frozen in
    * `PortableRunSpec`); pinning it here means a registry or derivation drift
    * breaks this suite before any product code can silently disagree.
    */
  private val MarkerArgv = List("/bin/test", "-f", "marker.txt")

  private val FrozenMarkerSnapshot =
    "sha256:0c877972cf59788094e1d7653a1f01f6cd1b6c9108d117d5e069bc6b6bdaba0b"

  private val exactChecks =
    List(NamedCheck("workspace-marker-present", MarkerArgv))

  private val partialChecks =
    List(
      NamedCheck(
        "workspace-marker-present",
        List("/bin/test", "-f", "other.txt")
      )
    )

  private val renamedChecks =
    List(NamedCheck("marker-present", MarkerArgv))

  private val unknownChecks =
    List(NamedCheck("compile", List("sbt", "--batch", "compile")))

  private def selectionFor(checks: List[NamedCheck]) = TurnLedger
    .validateSelection(
      List(
        TurnRecord(
          turnId = "t-1-px",
          kind = TurnLedger.Kind.Intent,
          request = "make module X compile and keep checks green",
          jobId = "j1",
          status = TurnLedger.Status.Completed,
          configRefs = ConfigRefs("/tmp/ws", "openai", "test-model"),
          atMs = 0L
        ),
        TurnRecord(
          turnId = "t-2-px",
          kind = TurnLedger.Kind.Refinement,
          request = "do not touch the public API",
          jobId = "j2",
          status = TurnLedger.Status.Completed,
          configRefs = ConfigRefs("/tmp/ws", "openai", "test-model"),
          atMs = 0L
        )
      ),
      checks,
      2,
      12
    )
    .toOption
    .get

  private val exactSelection = selectionFor(exactChecks)

  /** Runs the command against `cwd` (the fixture's temp root). */
  private def executeIn(
      cwd: Path,
      argLine: String,
      sel: TaskSelection = exactSelection,
      provider: String = "openai"
  ): Vector[String] =
    ExportWorkflowCommand.execute(
      selection = Some(sel),
      ledgerSize = 2,
      sessionId = "sess-px-cmd",
      chatProvider = provider,
      model = "test-model",
      baseUrl = "http://127.0.0.1:1/v1",
      argLine = argLine,
      cwd = cwd
    )

  private def cleanup(tmp: Path): Boolean =
    try
      Files
        .walk(tmp)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      true
    catch case NonFatal(_) => false

  private def runJsonOf(out: Path): String =
    val dirs =
      Files.list(out).iterator().asScala.toVector.filter(Files.isDirectory(_))
    val candidates = dirs.flatMap(d => Option(d.resolve("run.json")))
    candidates.find(Files.isRegularFile(_)) match
      case Some(f) => Files.readString(f, StandardCharsets.UTF_8)
      case None    => ""

  private def eventsLinesOf(out: Path): Vector[String] =
    val dirs =
      Files.list(out).iterator().asScala.toVector.filter(Files.isDirectory(_))
    dirs
      .flatMap(d => Option(d.resolve("events.jsonl")))
      .find(Files.isRegularFile(_))
      .map(f => Files.readAllLines(f, StandardCharsets.UTF_8).asScala.toVector)
      .getOrElse(Vector.empty)

  def spec =
    suite("ExportWorkflowCommand --format portable (RAI-203/RAI-215)")(
      test(
        "ADR-038 exact match: the command exports the pinned pair; the document and manifest carry it and self-validate"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-exact")
        val lines = executeIn(
          tmp,
          "--format portable --name px-flow --out export-px --target-path marker.txt --summary px-task"
        )
        val document = tmp.resolve("export-px").resolve("px-flow.workflow.json")
        val manifest = tmp.resolve("export-px").resolve("px-flow-manifest.json")
        val documentText =
          if Files.isRegularFile(document) then
            Files.readString(document, StandardCharsets.UTF_8)
          else ""
        val manifestText =
          if Files.isRegularFile(manifest) then
            Files.readString(manifest, StandardCharsets.UTF_8)
          else ""
        val documentValid =
          if documentText.nonEmpty then
            raider.dsl.portable.PortableWorkflowValidator.validate(documentText)
          else Left("no document")
        val manifestValid =
          if manifestText.nonEmpty then
            PortableChatExport.validateManifest(manifestText)
          else Left("no manifest")
        val facts = assertTrue(
          lines.head.startsWith("workflow exported (portable v1):"),
          lines.exists(_.contains("identity: document sha256-")),
          lines.exists(_.contains("workspace-marker-present")),
          Files.isRegularFile(document),
          Files.isRegularFile(manifest),
          documentText.contains("\"checkId\":\"workspace-marker-present\""),
          documentText.contains(FrozenMarkerSnapshot),
          manifestText.contains("\"trustedChecks\":[{"),
          manifestText.contains(FrozenMarkerSnapshot),
          documentValid.isRight,
          manifestValid.isRight
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "ADR-038 exact match runs green through native lowering: the exported artifacts go through PortableRun on the mock provider"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-run")
        val lines = executeIn(
          tmp,
          "--format portable --name px-run --out export-run --target-path marker.txt --summary px-task"
        )
        val document = tmp.resolve("export-run").resolve("px-run.workflow.json")
        val input = tmp.resolve("export-run").resolve("px-run.input.json")
        val ws = tmp.resolve("run-ws")
        val out = tmp.resolve("run-out")
        Files.createDirectories(ws)
        Files.createDirectories(out)
        // The pinned check's precondition: the marker must EXIST for a green
        // verdict — the trusted check decides, not the model.
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        for o <- PortableRun.run(
            PortableRun.Args(
              doc = document,
              input = input,
              out = out,
              workspace = ws,
              provider = "mock",
              model = "mock-model",
              baseUrl = None,
              attempts = 8,
              deadlineSeconds = None
            ),
            env = Map.empty
          )
        yield
          val runJson = runJsonOf(out)
          val events = eventsLinesOf(out)
          val facts = assertTrue(
            lines.head.startsWith("workflow exported (portable v1):"),
            o.exitCode == 0,
            o.error.isEmpty,
            runJson.contains("\"taskVerdict\":\"trusted-checks-passed\""),
            events.nonEmpty && events.last.contains("\"Terminal\"")
          )
          facts && assertTrue(cleanup(tmp))
      },
      test(
        "drift guard: the dsl derivation and the launcher registry derivation agree on the pinned marker digest"
      ) {
        val registry =
          PortableTrustedRegistry.checkSnapshot(
            "workspace-marker-present",
            MarkerArgv
          )
        val dsl =
          PortableChatExport.checkSnapshot(
            "workspace-marker-present",
            MarkerArgv
          )
        assertTrue(
          registry == FrozenMarkerSnapshot,
          dsl == FrozenMarkerSnapshot,
          registry == dsl,
          PortableTrustedRegistry.workspaceMarkerSnapshot == FrozenMarkerSnapshot,
          PortableTrustedRegistry.CheckWorkspaceMarker == "workspace-marker-present"
        )
      },
      test(
        "ADR-038 partial match: pinned name with a different argv refuses CWF-R3 naming the drift and the Scala option; nothing is written"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-partial")
        val portable = executeIn(
          tmp,
          "--format portable --out export-px --target-path marker.txt --summary px-task",
          sel = selectionFor(partialChecks)
        )
        val scalaStillWorks = executeIn(
          tmp,
          "--out export-scala --target-path marker.txt --summary px-task",
          sel = selectionFor(partialChecks)
        )
        val facts = assertTrue(
          portable.size == 1,
          portable.head.startsWith("export refused [RA-INP]: CWF-R3"),
          portable.head.contains("workspace-marker-present"),
          portable.head.contains("registrySnapshot differs"),
          portable.head.contains("Scala export"),
          !Files.exists(tmp.resolve("export-px")),
          scalaStillWorks.head.startsWith("workflow exported:"),
          Files.isRegularFile(
            tmp.resolve("export-scala").resolve("task_t_1_px.scala")
          )
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "ADR-038 renamed check: pinned argv under a different id refuses CWF-R3; nothing is written"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-renamed")
        val portable = executeIn(
          tmp,
          "--format portable --out export-px --target-path marker.txt --summary px-task",
          sel = selectionFor(renamedChecks)
        )
        val facts = assertTrue(
          portable.size == 1,
          portable.head.startsWith("export refused [RA-INP]: CWF-R3"),
          portable.head.contains("marker-present"),
          portable.head.contains(
            "no launcher-pinned trusted check has this id"
          ),
          portable.head.contains("Scala export"),
          !Files.exists(tmp.resolve("export-px"))
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "ADR-038 unknown registry: a check this launcher does not pin refuses CWF-R3, names the check and retains the Scala option"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-r3")
        val portable = executeIn(
          tmp,
          "--format portable --out export-px --target-path marker.txt --summary px-task",
          sel = selectionFor(unknownChecks)
        )
        val scalaStillWorks = executeIn(
          tmp,
          "--out export-scala --target-path marker.txt --summary px-task",
          sel = selectionFor(unknownChecks)
        )
        val facts = assertTrue(
          portable.size == 1,
          portable.head.startsWith("export refused [RA-INP]: CWF-R3"),
          portable.head.contains("compile"),
          portable.head.contains(
            "no launcher-pinned trusted check has this id"
          ),
          portable.head.contains("Scala export"),
          !Files.exists(tmp.resolve("export-px")),
          scalaStillWorks.head.startsWith("workflow exported:")
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "the mock transport refuses the portable export as CWF-R1 (a first-class transport origin is required)"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-mock")
        val lines = executeIn(
          tmp,
          "--format portable --out export-mock --target-path a --summary b",
          provider = "mock"
        )
        val facts = assertTrue(
          lines.size == 1,
          lines.head.startsWith("export refused [RA-INP]: CWF-R1"),
          lines.head.contains("'mock' transport"),
          lines.head.contains("first-class transport"),
          !Files.exists(tmp.resolve("export-mock"))
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "--format grammar: bogus value refuses with usage; nothing is written"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-fmt")
        val bogus = executeIn(
          tmp,
          "--format yaml --out export-fmt --target-path a --summary b"
        )
        val facts = assertTrue(
          bogus.size == 1,
          bogus.head.contains("unknown --format 'yaml'"),
          bogus.head.contains("scala, portable"),
          bogus.head.contains("usage:"),
          !Files.exists(tmp.resolve("export-fmt"))
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "the derived default name is shared by both formats: the Scala export lands under task_<intentId>"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-name")
        val lines = executeIn(
          tmp,
          "--out export-def --target-path a --summary b"
        )
        val artifact = tmp.resolve("export-def").resolve("task_t_1_px.scala")
        val artifactIsFile =
          if lines.head.startsWith("workflow exported:") then
            Files.isRegularFile(artifact)
          else false
        val artifactNamesSession =
          if Files.isRegularFile(artifact) then
            Files
              .readString(artifact, StandardCharsets.UTF_8)
              .contains(
                "sess-px-cmd"
              )
          else false
        val facts = assertTrue(
          lines.head.startsWith("workflow exported:"),
          artifactIsFile,
          artifactNamesSession
        )
        facts && assertTrue(cleanup(tmp))
      }
    )

end ExportWorkflowPortableSpec
