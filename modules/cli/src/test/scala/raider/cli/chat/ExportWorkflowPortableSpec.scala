package raider.cli.chat

import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** RAI-203 `:export-workflow --format portable` command checks over the real
  * command seam (`execute`). The frozen supported selection shape (ADR-033
  * §4.1, enforced by `TurnLedger.validateSelection`) requires at least one
  * NAMED trusted check, and portable v1 has no user-named check construct — so
  * through the chat command the portable format refuses CWF-R3 BY CONSTRUCTION
  * while explaining the step and retaining the Scala option (the card's
  * required behavior). The portable exporter's own happy path, identity
  * digests, R5 no-overwrite/staging discipline and manifest binding are covered
  * directly against `PortableChatExport` in `PortableChatExportSpec` (dsl
  * layer), and the recipes prove the exported documents validate/edit/run
  * (XE-RECIPE-01..03). The command-level checks here are the explained refusal,
  * the retained Scala option, the `--format` grammar, the mock-transport R1
  * refusal and the shared name derivation. All FS facts are captured into vals
  * BEFORE cleanup (the assertTrue macro re-evaluates lazily).
  */
object ExportWorkflowPortableSpec extends ZIOSpecDefault:

  private val checks = List(
    NamedCheck("compile", List("sbt", "--batch", "compile"))
  )

  private val selection = TurnLedger
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

  /** Runs the command against `cwd` (the fixture's temp root). */
  private def executeIn(
      cwd: Path,
      argLine: String,
      provider: String = "openai"
  ): Vector[String] =
    ExportWorkflowCommand.execute(
      selection = Some(selection),
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

  def spec =
    suite("ExportWorkflowCommand --format portable (RAI-203)")(
      test(
        "the portable format refuses CWF-R3 by construction on a supported selection, names the checks and retains the Scala option; nothing is written"
      ) {
        val tmp = Files.createTempDirectory("px-cmd-r3")
        val portable = executeIn(
          tmp,
          "--format portable --out export-px --target-path marker.txt --summary px-task"
        )
        val scalaStillWorks = executeIn(
          tmp,
          "--out export-scala --target-path marker.txt --summary px-task"
        )
        val scalaArtifact =
          tmp.resolve("export-scala").resolve("task_t_1_px.scala")
        val scalaArtifactIsFile =
          if scalaStillWorks.head.startsWith("workflow exported:") then
            Files.isRegularFile(scalaArtifact)
          else false
        val facts = assertTrue(
          portable.size == 1,
          portable.head.startsWith("export refused [RA-INP]: CWF-R3"),
          portable.head.contains("compile"),
          portable.head.contains("Scala export"),
          !Files.exists(tmp.resolve("export-px")),
          scalaStillWorks.head.startsWith("workflow exported:"),
          scalaArtifactIsFile
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
