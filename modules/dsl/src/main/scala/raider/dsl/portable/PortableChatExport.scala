package raider.dsl.portable

import java.nio.charset.StandardCharsets
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  FileAlreadyExistsException,
  Path,
  StandardCopyOption
}
import java.security.MessageDigest

import scala.util.control.NonFatal

import raider.core.RaiderError
import raider.dsl.workflow.StsSelection
import zio.json.*
import zio.json.ast.Json

/** The launcher/stand-side trusted references a portable export NAMES in its
  * document and manifest (RAI-203; ADR-036 trust rules): the export never
  * embeds credentials or argv — it records only the pinned profile, toolset,
  * check id and the pinned registry snapshot digest. A run must resolve these
  * names against an equal snapshot or the launcher refuses typed.
  */
final case class PortableExportTarget(
    profile: String,
    toolset: String,
    checkId: String,
    registrySnapshot: String
)

/** Export options: the artifact base name (R1 rules below). */
final case class PortableExportOptions(name: String)

/** Result of a completed portable export: the published target directory, its
  * files (document, manifest, input example) and the identity digests of the
  * two user-facing data artifacts.
  */
final case class PortableExportResult(
    targetDir: Path,
    files: List[Path],
    documentSha256: String,
    inputExampleSha256: String
)

/** Portable chat export (RAI-203): renders a validated `StsSelection` (the chat
  * workflow subset of ADR-033) as a self-contained portable workflow v1
  * document plus a closed manifest and an input example, WITHOUT weakening the
  * existing Scala export.
  *
  * Discipline:
  *   - the selection's named trusted checks are NOT representable in portable
  *     workflow v1 (checks enter a portable document only through the
  *     launcher-pinned registry, never through the document): an export that
  *     would lose them refuses typed (CWF-R3) and names the retained Scala
  *     export — nothing is silently dropped;
  *   - the rendered document is validated by [[PortableWorkflowValidator]] and
  *     the manifest by its own closed schema check BEFORE any byte is written;
  *     a self-check failure is an honest typed refusal, never a broken export;
  *   - the write discipline mirrors `ChatWorkflowExport` (ADR-033 §6.2): a
  *     staging + atomic rename publishes the target directory; an existing
  *     target (file, directory or symlink) and a leftover staging remnant are
  *     typed refusals; parent directories are never created; concurrent exports
  *     to the same target are NOT serialized — they are excluded fail-fast on
  *     the fixed staging name: exactly one export claims staging and publishes,
  *     while a loser's `Files.createDirectory` fails with
  *     `FileAlreadyExistsException` and refuses typed WITHOUT deleting the
  *     winner's in-flight staging (RAI-213);
  *   - export dispatches nothing agent-shaped: it renders text and writes
  *     files.
  */
object PortableChatExport:

  /** Manifest kind marker (closed). */
  val ManifestKind: String = "raider.chat-portable-export"

  /** Portable document version exported by this renderer. */
  val DocumentVersion: String = "1.0"

  /** Fixed staging-directory suffix (inside the target's parent), distinct from
    * the Scala export's suffix so both export flavors never collide.
    */
  val StagingSuffix: String = ".raider-portable-export-staging"

  private val DocumentSuffix = ".workflow.json"
  private val ManifestSuffix = "-manifest.json"
  private val InputSuffix = ".input.json"

  private val namePattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,95}".r
  private val identifierPattern = "[A-Za-z][A-Za-z0-9._-]{0,63}".r
  private val sha256Pattern = "sha256:[a-f0-9]{64}".r
  private val fileNamePattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r

  /** The one agent stage: the pinned profile/toolset authority named by the
    * export target.
    */
  private val ActStepId = "act"
  private val GateStepId = "gate"
  private val RunStepId = "run"

  /** Export pipeline: representability -> naming/provenance checks -> render ->
    * self-validate -> staging -> atomic publish.
    */
  def exportTo(
      selection: StsSelection,
      options: PortableExportOptions,
      target: PortableExportTarget,
      targetDir: Path,
      exportedAtMs: Long
  ): Either[RaiderError, PortableExportResult] =
    for
      _ <- requireRepresentable(selection)
      _ <- requireValidName(options.name)
      _ <- requireBoundedProvenance(selection, exportedAtMs)
      documentText = documentJson(selection, target).toJson
      inputText = inputExampleJson(selection).toJson
      manifestText =
        manifestJson(
          selection,
          target,
          options.name,
          documentText,
          inputText,
          exportedAtMs
        ).toJson
      _ <- PortableWorkflowValidator
        .validate(documentText)
        .left
        .map(d =>
          refuse(
            "R1",
            "the rendered portable document failed its own gate (" + d.code +
              ") at " + d.documentPath + ": " + d.suggestedCorrection
          )
        )
      _ <- validateManifest(manifestText).left.map(msg =>
        refuse(
          "R1",
          "the rendered export manifest failed its own closed schema: " + msg
        )
      )
      normalized = targetDir.normalize()
      parent <- requireParent(normalized)
      _ <- requireFreshTarget(normalized)
      staging = parent.resolve(
        "." + normalized.getFileName.toString + StagingSuffix
      )
      _ <- requireFreshStaging(staging)
      files = List(
        options.name + DocumentSuffix -> documentText,
        options.name + ManifestSuffix -> manifestText,
        options.name + InputSuffix -> inputText
      )
      _ <- writeStaging(staging, files)
      published <- publish(staging, normalized)
    yield PortableExportResult(
      targetDir = published,
      files = files.map(f => published.resolve(f._1)),
      documentSha256 =
        "sha256-" + sha256Hex(documentText.getBytes(StandardCharsets.UTF_8)),
      inputExampleSha256 =
        "sha256-" + sha256Hex(inputText.getBytes(StandardCharsets.UTF_8))
    )

  /** R3: named trusted checks are not representable in portable workflow v1.
    * The refusal names the unsupported construct and keeps the Scala option.
    */
  def requireRepresentable(
      selection: StsSelection
  ): Either[RaiderError, Unit] =
    if selection.checks.nonEmpty then
      val names = selection.checks.map(_.name).mkString(", ")
      Left(
        refuse(
          "R3",
          "this selection names " + selection.checks.size + " trusted check(s) (" +
            names + "): portable workflow v1 has no user-named check construct — " +
            "checks enter a portable document only through the launcher-pinned " +
            "registry, so exporting them would silently drop them; re-export " +
            "without :task mark'ed checks, or keep the existing Scala export " +
            "which renders named checks natively"
        )
      )
    else Right(())

  private def requireValidName(name: String): Either[RaiderError, Unit] =
    if namePattern.matches(name) then Right(())
    else
      Left(
        refuse(
          "R1",
          "export name must match [A-Za-z0-9][A-Za-z0-9._-]{0,95}: '" + name + "'"
        )
      )

  /** R1/R4: the provenance metadata is bounded, non-secret config references
    * only (provider kind, model, session id) — credential values are forbidden.
    */
  private def requireBoundedProvenance(
      selection: StsSelection,
      exportedAtMs: Long
  ): Either[RaiderError, Unit] =
    val values = List(
      "sessionId" -> selection.sessionId,
      "providerKind" -> selection.origin.providerKind,
      "model" -> selection.origin.model
    )
    val oversized = values.collect { case (n, v) if v.length > 256 => n }
    if oversized.nonEmpty then
      Left(
        refuse(
          "R1",
          "provenance metadata exceeds the 256-character bound: " +
            oversized.mkString(", ")
        )
      )
    else if exportedAtMs < 0 then
      Left(
        refuse("R1", "exportedAtMs must be a non-negative epoch millis value")
      )
    else Right(())

  /** The portable workflow v1 document: one pinned agent stage + one pinned
    * gate check folded by a sequence; the run verdict is the sequence output
    * under trustedVerifier authority. All per-run content (intent, refinements,
    * task params) stays in the input example, never inlined into the document.
    */
  def documentJson(
      selection: StsSelection,
      target: PortableExportTarget
  ): Json =
    Json.Obj(
      "format" -> Json.Str("raider.portable-workflow"),
      "version" -> Json.Str(DocumentVersion),
      "metadata" -> Json.Obj(
        "exportedFrom" -> Json.Str("raider.chat-selection"),
        "sessionId" -> Json.Str(selection.sessionId),
        "providerKind" -> Json.Str(selection.origin.providerKind),
        "model" -> Json.Str(selection.origin.model)
      ),
      "limits" -> Json.Obj(
        "deadlineMs" -> Json.Num(BigDecimal(900000)),
        "attempts" -> Json.Num(BigDecimal(4)),
        "parallelism" -> Json.Num(BigDecimal(1)),
        "children" -> Json.Num(BigDecimal(0)),
        "tokens" -> Json.Num(BigDecimal(50000))
      ),
      "schemas" -> Json.Obj(
        "inputs" -> Json.Obj(
          "task" -> Json.Obj(
            "type" -> Json.Str("object"),
            "additionalProperties" -> Json.Bool(false),
            "required" -> Json.Arr(Json.Str("intent")),
            "properties" -> Json.Obj(
              "intent" -> Json.Obj("type" -> Json.Str("string")),
              "refinements" -> Json.Obj(
                "type" -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string"))
              ),
              "targetPath" -> Json.Obj("type" -> Json.Str("string")),
              "summary" -> Json.Obj("type" -> Json.Str("string"))
            )
          )
        ),
        "outputs" -> Json.Obj(
          "actReport" -> Json.Obj(
            "type" -> Json.Str("object"),
            "additionalProperties" -> Json.Bool(false),
            "required" -> Json.Arr(Json.Str("text")),
            "properties" -> Json.Obj(
              "text" -> Json.Obj("type" -> Json.Str("string"))
            )
          ),
          "gateVerdict" -> verdictSchema,
          "flowVerdict" -> verdictSchema
        )
      ),
      "steps" -> Json.Arr(
        Json.Obj(
          "id" -> Json.Str(ActStepId),
          "kind" -> Json.Str("agent"),
          "profile" -> Json.Str(target.profile),
          "toolset" -> Json.Str(target.toolset),
          "input" -> Json.Str("#/inputs/task"),
          "structuredOutput" -> Json.Bool(true),
          "outputSchema" -> Json.Str("actReport")
        ),
        Json.Obj(
          "id" -> Json.Str(GateStepId),
          "kind" -> Json.Str("trustedCheck"),
          "checkId" -> Json.Str(target.checkId),
          "registrySnapshot" -> Json.Str(target.registrySnapshot),
          "input" -> Json.Str("#/steps/act/output"),
          "outputSchema" -> Json.Str("gateVerdict")
        ),
        Json.Obj(
          "id" -> Json.Str(RunStepId),
          "kind" -> Json.Str("sequence"),
          "steps" -> Json.Arr(
            Json.Str(ActStepId),
            Json.Str(GateStepId)
          ),
          "outputSchema" -> Json.Str("flowVerdict")
        )
      ),
      "outputs" -> Json.Obj(
        "bindings" -> Json.Obj(
          "result" -> Json.Str("#/steps/run/output")
        ),
        "verdict" -> Json.Obj(
          "from" -> Json.Str("#/steps/run/output"),
          "authority" -> Json.Str("trustedVerifier")
        )
      )
    )

  private def verdictSchema: Json =
    Json.Obj(
      "type" -> Json.Str("object"),
      "additionalProperties" -> Json.Bool(false),
      "required" -> Json.Arr(Json.Str("status"), Json.Str("detail")),
      "properties" -> Json.Obj(
        "status" -> Json.Obj(
          "type" -> Json.Str("string"),
          "enum" -> Json.Arr(Json.Str("passed"), Json.Str("failed"))
        ),
        "detail" -> Json.Obj("type" -> Json.Str("string"))
      )
    )

  /** The input example: the per-run task instance externalized from the
    * selection (intent + refinements + named params), ready for
    * `raider workflow run --input <file>`.
    */
  def inputExampleJson(selection: StsSelection): Json =
    val params = selection.taskParams.collect {
      case p if p.name == "targetPath" || p.name == "summary" =>
        p.name -> Json.Str(p.value)
    }
    Json.Obj(
      "task" -> Json.Obj(
        List(
          "intent" -> Json.Str(selection.intent.text),
          "refinements" -> Json.Arr(
            selection.refinements.map(t => Json.Str(t.text))*
          )
        ) ++ params*
      )
    )

  /** The closed export manifest: provenance + identity digests + the pinned
    * trusted references. No credential values, no argv.
    */
  def manifestJson(
      selection: StsSelection,
      target: PortableExportTarget,
      name: String,
      documentText: String,
      inputText: String,
      exportedAtMs: Long
  ): Json =
    Json.Obj(
      "schemaVersion" -> Json.Num(BigDecimal(1)),
      "kind" -> Json.Str(ManifestKind),
      "exportedAtMs" -> Json.Num(BigDecimal(exportedAtMs)),
      "sessionId" -> Json.Str(selection.sessionId),
      "origin" -> Json.Obj(
        "providerKind" -> Json.Str(selection.origin.providerKind),
        "model" -> Json.Str(selection.origin.model),
        "baseUrl" -> {
          selection.origin.baseUrl match
            case Some(url) => Json.Str(url)
            case None      => Json.Null
        }
      ),
      "selection" -> Json.Obj(
        "intentTurnId" -> Json.Str(selection.intent.turnId),
        "refinementTurnIds" -> Json.Arr(
          selection.refinements.map(t => Json.Str(t.turnId))*
        ),
        "maxRoundsBound" -> Json.Num(BigDecimal(selection.agentStage.maxRounds))
      ),
      "document" -> Json.Obj(
        "fileName" -> Json.Str(name + DocumentSuffix),
        "sha256" -> Json.Str(
          "sha256:" + sha256Hex(documentText.getBytes(StandardCharsets.UTF_8))
        )
      ),
      "inputExample" -> Json.Obj(
        "fileName" -> Json.Str(name + InputSuffix),
        "sha256" -> Json.Str(
          "sha256:" + sha256Hex(inputText.getBytes(StandardCharsets.UTF_8))
        )
      ),
      "trusted" -> Json.Obj(
        "profile" -> Json.Str(target.profile),
        "toolset" -> Json.Str(target.toolset),
        "checkId" -> Json.Str(target.checkId),
        "registrySnapshot" -> Json.Str(target.registrySnapshot)
      )
    )

  /** The closed manifest schema check (mirrors
    * `contracts/workflow-portable/schema/chat-portable-export-manifest-v1.schema.json`):
    * every field closed, types and bounds exact.
    */
  def validateManifest(text: String): Either[String, Json] =
    text.fromJson[Json] match
      case Left(_) => Left("the manifest is not well-formed JSON")
      case Right(json) =>
        json match
          case root: Json.Obj =>
            val expected =
              Set(
                "schemaVersion",
                "kind",
                "exportedAtMs",
                "sessionId",
                "origin",
                "selection",
                "document",
                "inputExample",
                "trusted"
              )
            val actual = root.fields.map(_._1).toSet
            if actual != expected then
              Left(
                "manifest fields must be exactly " + expected.mkString(", ") +
                  "; found: " + actual.mkString(", ")
              )
            else
              for
                _ <- requireInt(root, "schemaVersion", 1, 1)
                _ <- requireConst(root, "kind", ManifestKind)
                _ <- requireInt(root, "exportedAtMs", 0, Long.MaxValue)
                _ <- requireString(root, "sessionId", 1, 256)
                origin <- childObj(
                  root,
                  "origin",
                  Set("providerKind", "model", "baseUrl")
                )
                _ <- for
                  _ <- requireString(origin, "providerKind", 1, 256)
                  _ <- requireString(origin, "model", 1, 256)
                  _ <- baseUrlOk(origin)
                yield ()
                sel <- childObj(
                  root,
                  "selection",
                  Set("intentTurnId", "refinementTurnIds", "maxRoundsBound")
                )
                _ <- for
                  _ <- requireString(sel, "intentTurnId", 1, 128)
                  _ <- refinementIdsOk(sel)
                  _ <- requireInt(sel, "maxRoundsBound", 1, 100)
                yield ()
                document <- childObj(
                  root,
                  "document",
                  Set("fileName", "sha256")
                )
                _ <- requireArtifact(document)
                inputExample <- childObj(
                  root,
                  "inputExample",
                  Set("fileName", "sha256")
                )
                _ <- requireArtifact(inputExample)
                trusted <- childObj(
                  root,
                  "trusted",
                  Set("profile", "toolset", "checkId", "registrySnapshot")
                )
                _ <- for
                  _ <- requireIdentifier(trusted, "profile")
                  _ <- requireIdentifier(trusted, "toolset")
                  _ <- requireIdentifier(trusted, "checkId")
                  _ <- requireSha256(trusted, "registrySnapshot")
                yield ()
              yield json
          case _ => Left("the manifest must be a JSON object")

  // -- manifest check helpers ------------------------------------------------

  private def strOf(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst { case (`key`, Json.Str(v)) => v }

  private def intOf(obj: Json.Obj, key: String): Option[Long] =
    obj.fields.collectFirst { case (`key`, Json.Num(v)) =>
      val bd = scala.math.BigDecimal(v)
      if bd.isWhole && bd.isValidLong then bd.toLong else -1L
    }

  private def childObj(
      obj: Json.Obj,
      key: String,
      fields: Set[String]
  ): Either[String, Json.Obj] =
    obj.fields.collectFirst { case (`key`, child: Json.Obj) => child } match
      case None => Left("manifest field '" + key + "' must be a JSON object")
      case Some(child) =>
        val actual = child.fields.map(_._1).toSet
        if actual != fields then
          Left(
            "manifest '" + key + "' fields must be exactly " +
              fields.mkString(", ") + "; found: " + actual.mkString(", ")
          )
        else Right(child)

  private def requireConst(
      obj: Json.Obj,
      key: String,
      value: String
  ): Either[String, Unit] =
    strOf(obj, key) match
      case Some(`value`) => Right(())
      case other =>
        Left(
          "manifest '" + key + "' must be \"" + value + "\"; found: " + other
        )

  private def requireInt(
      obj: Json.Obj,
      key: String,
      min: Long,
      max: Long
  ): Either[String, Unit] =
    intOf(obj, key) match
      case Some(v) if v >= min && v <= max => Right(())
      case other =>
        Left(
          "manifest '" + key + "' must be an integer in [" + min + ", " + max + "]; found: " + other
        )

  private def requireString(
      obj: Json.Obj,
      key: String,
      min: Int,
      max: Int
  ): Either[String, Unit] =
    strOf(obj, key) match
      case Some(v) if v.length >= min && v.length <= max => Right(())
      case other =>
        Left(
          "manifest '" + key + "' must be a string of length [" + min + ", " + max + "]; found: " + other
        )

  private def baseUrlOk(origin: Json.Obj): Either[String, Unit] =
    origin.fields.collectFirst { case ("baseUrl", v) => v } match
      case Some(Json.Null) => Right(())
      case Some(Json.Str(url)) =>
        if url.length <= 2048 then Right(())
        else Left("manifest 'origin.baseUrl' must be at most 2048 characters")
      case Some(other) =>
        Left(
          "manifest 'origin.baseUrl' must be a string or null; found: " + other
        )
      case None => Left("manifest 'origin.baseUrl' is required")

  private def refinementIdsOk(sel: Json.Obj): Either[String, Unit] =
    sel.fields.collectFirst { case ("refinementTurnIds", Json.Arr(values)) =>
      values
    } match
      case None =>
        Left("manifest 'selection.refinementTurnIds' must be an array")
      case Some(values) =>
        if values.size > 4096 then
          Left(
            "manifest 'selection.refinementTurnIds' must have at most 4096 entries"
          )
        else
          val bad = values.exists {
            case Json.Str(id) => id.isEmpty || id.length > 128
            case _            => true
          }
          if bad then
            Left(
              "manifest 'selection.refinementTurnIds' entries must be strings of length [1, 128]"
            )
          else Right(())

  private def requireArtifact(artifact: Json.Obj): Either[String, Unit] =
    for
      _ <- strOf(artifact, "fileName") match
        case Some(f) if fileNamePattern.matches(f) => Right(())
        case other =>
          Left(
            "manifest artifact 'fileName' must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}; found: " + other
          )
      _ <- requireSha256(artifact, "sha256")
    yield ()

  private def requireIdentifier(
      obj: Json.Obj,
      key: String
  ): Either[String, Unit] =
    strOf(obj, key) match
      case Some(v) if identifierPattern.matches(v) => Right(())
      case other =>
        Left(
          "manifest '" + key + "' must match [A-Za-z][A-Za-z0-9._-]{0,63}; found: " + other
        )

  private def requireSha256(obj: Json.Obj, key: String): Either[String, Unit] =
    strOf(obj, key) match
      case Some(v) if sha256Pattern.matches(v) => Right(())
      case other =>
        Left(
          "manifest '" + key + "' must match sha256:[a-f0-9]{64}; found: " + other
        )

  // -- staging + publish (ADR-033 §6.2 discipline, mirrors ChatWorkflowExport)

  private def requireParent(targetDir: Path): Either[RaiderError, Path] =
    Option(targetDir.getParent) match
      case None =>
        Left(
          refuse(
            "R5",
            "target directory has no parent: " + targetDir +
              "; export writes under an existing directory"
          )
        )
      case Some(parent) =>
        try
          if !Files.isDirectory(parent) then
            Left(
              refuse(
                "R5",
                "target parent is not an existing directory: " + parent +
                  "; export never creates parent directories"
              )
            )
          else Right(parent)
        catch
          case NonFatal(t) =>
            Left(
              refuse(
                "R5",
                "target parent is not usable: " + parent + ": " + t.getClass.getSimpleName
              )
            )

  private def requireFreshTarget(targetDir: Path): Either[RaiderError, Unit] =
    if Files.exists(targetDir) then
      val kind =
        if Files.isSymbolicLink(targetDir) then "symlink"
        else if Files.isDirectory(targetDir) then "directory"
        else "file"
      Left(
        refuse(
          "R5",
          "target already exists (" + kind + "): " + targetDir +
            "; export never overwrites user edits (ADR-033 §6.2)"
        )
      )
    else Right(())

  private def requireFreshStaging(staging: Path): Either[RaiderError, Unit] =
    if Files.exists(staging) then
      Left(
        refuse(
          "R5",
          "interrupted export remnant present: " + staging +
            "; the incomplete export was never published — remove the remnant and re-export explicitly (ADR-033 §6.2/§9)"
        )
      )
    else Right(())

  private def writeStaging(
      staging: Path,
      files: List[(String, String)]
  ): Either[RaiderError, Unit] =
    try
      Files.createDirectory(staging)
      files.foreach { (fileName, content) =>
        Files.write(
          staging.resolve(fileName),
          content.getBytes(StandardCharsets.UTF_8)
        )
      }
      Right(())
    catch
      case _: FileAlreadyExistsException =>
        // RAI-213: the staging claim was lost — the winning concurrent export
        // already created the staging directory between this export's
        // freshness check and its own createDirectory (the check-then-create
        // window), or another obstruction occupies the path. Fail fast
        // WITHOUT deleting: the cleanup below must only ever remove the
        // staging directory THIS export created, never the winner's in-flight
        // staging, so exactly one export publishes (ADR-033 §6.2/§9.1).
        Left(
          refuse(
            "R5",
            "staging claim lost: " + staging + " already exists (a concurrent " +
              "export claimed the staging name, or a remnant/obstruction " +
              "appeared after the freshness check); refusing WITHOUT deleting " +
              "it — inspect the staging path explicitly before re-exporting " +
              "(ADR-033 §6.2/§9.1)"
          )
        )
      case NonFatal(t) =>
        deleteQuietly(staging)
        Left(
          refuse(
            "R5",
            "staging write failed (nothing was published): " +
              t.getClass.getSimpleName + ": " + String.valueOf(t.getMessage)
          )
        )

  private def publish(
      staging: Path,
      targetDir: Path
  ): Either[RaiderError, Path] =
    try
      try Right(Files.move(staging, targetDir, StandardCopyOption.ATOMIC_MOVE))
      catch
        case _: AtomicMoveNotSupportedException =>
          Right(Files.move(staging, targetDir))
    catch
      case _: FileAlreadyExistsException =>
        deleteQuietly(staging)
        Left(
          refuse(
            "R5",
            "target appeared concurrently: " + targetDir +
              "; export never overwrites (ADR-033 §6.2/§9.1)"
          )
        )
      case NonFatal(t) =>
        deleteQuietly(staging)
        Left(
          refuse(
            "R5",
            "publish failed (staging removed, nothing published): " +
              t.getClass.getSimpleName
          )
        )

  private def deleteQuietly(dir: Path): Unit =
    try
      if Files.isDirectory(dir) then
        val listing = Files.list(dir)
        try listing.forEach(p => Files.deleteIfExists(p))
        finally listing.close()
        Files.deleteIfExists(dir)
      else Files.deleteIfExists(dir)
    catch case NonFatal(_) => ()

  private def refuse(code: String, what: String): RaiderError =
    RaiderError.InputValidation("CWF-" + code + ": " + what)

  /** Lowercase hex SHA-256 over the exact bytes. */
  def sha256Hex(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"${b & 0xff}%02x").mkString

end PortableChatExport
