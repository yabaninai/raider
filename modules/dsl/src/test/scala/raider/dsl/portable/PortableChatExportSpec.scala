package raider.dsl.portable

import java.nio.file.{Files, Path}

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

/** RAI-203 portable chat export: mapping fidelity (selection -> portable
  * workflow v1 document + closed manifest + input example), self-validation of
  * the rendered artifacts, the CWF-R3 refusal that keeps the Scala option when
  * named checks are present, and the inherited R5 staging/never-overwrite
  * discipline. FS facts are captured into vals BEFORE cleanup (the assertTrue
  * macro re-evaluates lazily).
  */
object PortableChatExportSpec extends ZIOSpecDefault:

  private val snapshot =
    "sha256:" + ("ab" * 32)

  private val target = PortableExportTarget(
    profile = "coder",
    toolset = "workspace",
    checkId = "workspace-marker-present",
    registrySnapshot = snapshot
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

  private val selectionWithChecks = selection.copy(
    checks = List(NamedCheck("compile", List("sbt", "--batch", "compile")))
  )

  private def exportTo(
      tmp: Path,
      sel: StsSelection = selection,
      name: String = "portable-task"
  ): Either[RaiderError, PortableExportResult] =
    PortableChatExport.exportTo(
      sel,
      PortableExportOptions(name),
      target,
      tmp.resolve("out"),
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

  def spec = suite("PortableChatExport (RAI-203)")(
    test("document mapping: metadata, limits, steps, outputs, verdict") {
      val doc = PortableChatExport.documentJson(selection, target)
      val metadata = field(doc, "metadata")
      val limits = field(doc, "limits")
      val steps = field(doc, "steps")
      val outputs = field(doc, "outputs")
      val bindings = outputs.flatMap(field(_, "bindings"))
      val verdict = outputs.flatMap(field(_, "verdict"))
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
        steps.exists {
          case Json.Arr(values) => values.size == 3; case _ => false
        },
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
      val text = PortableChatExport.documentJson(selection, target).toJson
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
              PortableChatExport.documentJson(selection, target).toJson
            val inputText =
              PortableChatExport.inputExampleJson(selection).toJson
            val manifest = PortableChatExport.manifestJson(
              selection,
              target,
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
              manifestText.replace("sha256:" + ("ab" * 32), "not-a-digest")
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
            assertTrue(
              checked.isRight,
              tamperedExtra.isLeft,
              malformedDigest.isLeft,
              malformedSnapshot.isLeft,
              reDigest.isRight
            )
          finally cleanup(tmp)
    },
    test("named trusted checks refuse CWF-R3 and keep the Scala option") {
      val refusal = PortableChatExport.requireRepresentable(
        selectionWithChecks
      )
      assertTrue(
        refusal.isLeft,
        refusal.left.toOption.get.code == "RA-INP",
        refusal.left.toOption.get.detail.contains("CWF-R3"),
        refusal.left.toOption.get.detail.contains("compile"),
        refusal.left.toOption.get.detail.contains("Scala export")
      )
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
            val result = PortableChatExport.exportTo(
              selection,
              PortableExportOptions("portable-task"),
              target,
              tmp.resolve("out"),
              1750000000000L
            )
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
            val noParent = PortableChatExport.exportTo(
              selection,
              PortableExportOptions("p1"),
              target,
              tmp.resolve("missing").resolve("out"),
              0L
            )
            val first = PortableChatExport.exportTo(
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
              first.isRight,
              again.isLeft && againMsg.contains("never overwrites user edits"),
              symlink.isLeft && symlink.left.toOption.get.detail
                .contains("symlink"),
              remnant.isLeft && remnantMsg.contains(
                "interrupted export remnant"
              )
            )
          finally cleanup(tmp)
    }
  )

end PortableChatExportSpec
