package raider.cli.chat

import zio.test.*
import zio.test.Assertion.*

import raider.dsl.workflow.ChatWorkflowExport

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** RAI-160 `:export-workflow` command checks (pure command layer + real
  * `ChatWorkflowExport.exportTo` writes): grammar, defaults, the no-selection
  * refusals (R2 legacy / R1 no snapshot), the §6.2 success message naming the
  * artifact + manifest + input example paths, and the inherited R5
  * never-overwrite / staging-remnant discipline surfaced through the command.
  * All FS facts are captured into vals BEFORE cleanup (the assertTrue macro
  * re-evaluates lazily).
  */
object ExportWorkflowCommandSpec extends ZIOSpecDefault:

  private val checks = List(
    NamedCheck("compile", List("sbt", "--batch", "compile"))
  )

  private val selection: TaskSelection =
    TurnLedger
      .validateSelection(
        List(
          TurnRecord(
            turnId = "t-1-ew",
            kind = TurnLedger.Kind.Intent,
            request = "make module X compile and keep checks green",
            jobId = "j1",
            status = TurnLedger.Status.Completed,
            configRefs = ConfigRefs("/tmp/ws", "openai", "test-model"),
            atMs = 0L
          ),
          TurnRecord(
            turnId = "t-2-ew",
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
      sel: Option[TaskSelection] = Some(selection),
      ledgerSize: Int = 2
  ): Vector[String] =
    ExportWorkflowCommand.execute(
      selection = sel,
      ledgerSize = ledgerSize,
      sessionId = "sess-ew-cmd",
      chatProvider = "openai",
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
    suite("ExportWorkflowCommand (RAI-160)")(
      test("parse: full grammar, all-defaults, and usage errors") {
        val full = ExportWorkflowCommand.parse(
          "--out d --name n --target-path p --summary s"
        )
        val empty = ExportWorkflowCommand.parse("")
        val unknown = ExportWorkflowCommand.parse("--nope x")
        val missingValue = ExportWorkflowCommand.parse("--out")
        assertTrue(
          full == Right(
            ExportWorkflowCommand.Args(
              out = Some("d"),
              name = Some("n"),
              targetPath = Some("p"),
              summary = Some("s")
            )
          ),
          empty == Right(ExportWorkflowCommand.Args()),
          unknown.isLeft && unknown.left.toOption.get.contains("usage"),
          missingValue.isLeft &&
            missingValue.left.toOption.get.contains("requires a value")
        )
      },
      test(
        "no selection: a legacy/empty ledger is the canonical CWF-R2 refusal; a captured-but-unselected ledger is CWF-R1 with the remedy"
      ) {
        val tmp = Files.createTempDirectory("ew-cmd-ns")
        val legacy = executeIn(
          tmp,
          "--out legacy --target-path a --summary b",
          sel = None,
          ledgerSize = 0
        )
        val unselected = executeIn(
          tmp,
          "--out unselected --target-path a --summary b",
          sel = None,
          ledgerSize = 2
        )
        val facts = assertTrue(
          legacy.size == 1,
          legacy.head.startsWith("export refused [RA-INP]: CWF-R2"),
          legacy.head.contains("cannot invent absent execution records"),
          unselected.size == 1,
          unselected.head.startsWith("export refused [RA-INP]: CWF-R1"),
          unselected.head.contains(":task mark") &&
            unselected.head.contains(":task select"),
          !Files.exists(tmp.resolve("legacy")),
          !Files.exists(tmp.resolve("unselected"))
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "successful export writes the §6.2 layout and the success message names the artifact, manifest and input example paths"
      ) {
        val tmp = Files.createTempDirectory("ew-cmd-ok")
        val lines = executeIn(
          tmp,
          "--out export-ok --target-path marker.txt --summary ew-probe-task"
        )
        val target = tmp.resolve("export-ok")
        val src = target.resolve("task_t_1_ew.scala")
        val manifest = target.resolve("task_t_1_ew-manifest.json")
        val input = target.resolve("task_t_1_ew.input.json")
        val srcText =
          if Files.isRegularFile(src) then
            Files.readString(src, StandardCharsets.UTF_8)
          else ""
        val facts = assertTrue(
          lines.nonEmpty && lines.head == s"workflow exported: $target",
          lines.exists(_.contains(s"artifact: $src")),
          lines.exists(_.contains(s"manifest: $manifest")),
          lines.exists(_.contains(s"input example: $input")),
          lines.exists(_.contains("spec sha256-")),
          Files.isRegularFile(src),
          Files.isRegularFile(manifest),
          Files.isRegularFile(input),
          // derived, not invented: the captured intent/refinement text is IN
          // the generated source
          srcText.contains("make module X compile and keep checks green"),
          srcText.contains("Refinement: do not touch the public API")
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "R5 inherited through the command: re-export to the same target refuses and never overwrites; a staging remnant refuses by name"
      ) {
        val tmp = Files.createTempDirectory("ew-cmd-r5")
        val first = executeIn(
          tmp,
          "--out export-r5 --target-path marker.txt --summary ew-probe-task"
        )
        val target = tmp.resolve("export-r5")
        val srcBefore =
          Files.readAllBytes(target.resolve("task_t_1_ew.scala"))
        val second = executeIn(
          tmp,
          "--out export-r5 --target-path marker.txt --summary ew-probe-task"
        )
        val remnantDir =
          tmp.resolve(".remnant-target" + ChatWorkflowExport.StagingSuffix)
        Files.createDirectory(remnantDir)
        val remnant = executeIn(
          tmp,
          "--out remnant-target --target-path marker.txt --summary ew-probe-task"
        )
        val facts = assertTrue(
          first.size > 1 && first.head.startsWith("workflow exported:"),
          second.size == 1,
          second.head.startsWith("export refused [RA-INP]: CWF-R5"),
          second.head.contains("never overwrites"),
          Files.readAllBytes(target.resolve("task_t_1_ew.scala")).toSeq ==
            srcBefore.toSeq,
          remnant.size == 1,
          remnant.head.startsWith("export refused [RA-INP]: CWF-R5"),
          remnant.head.contains("interrupted export remnant"),
          !Files.exists(tmp.resolve("remnant-target"))
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "missing externalized params: typed R1 naming the flags (never inferred from captured text)"
      ) {
        val tmp = Files.createTempDirectory("ew-cmd-noparams")
        val lines = executeIn(tmp, "--out export-noparams")
        val facts = assertTrue(
          lines.size == 1,
          lines.head.startsWith("export refused [RA-INP]: CWF-R1"),
          lines.head.contains("--target-path"),
          lines.head.contains("--summary"),
          !Files.exists(tmp.resolve("export-noparams"))
        )
        facts && assertTrue(cleanup(tmp))
      },
      test(
        "name: explicit --name wins; the default is derived from the intent turn id"
      ) {
        val tmp = Files.createTempDirectory("ew-cmd-name")
        val lines = executeIn(
          tmp,
          "--out named --name my_task --target-path a --summary b"
        )
        val defaults = executeIn(
          tmp,
          "--out defaulted --target-path a --summary b"
        )
        val facts = assertTrue(
          lines.head.startsWith("workflow exported:"),
          Files.isRegularFile(tmp.resolve("named").resolve("my_task.scala")),
          defaults.head.startsWith("workflow exported:"),
          Files.isRegularFile(
            tmp.resolve("defaulted").resolve("task_t_1_ew.scala")
          )
        )
        facts && assertTrue(cleanup(tmp))
      }
    )

end ExportWorkflowCommandSpec
