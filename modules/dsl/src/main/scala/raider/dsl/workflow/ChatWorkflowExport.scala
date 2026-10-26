package raider.dsl.workflow

import java.nio.charset.StandardCharsets
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  FileAlreadyExistsException,
  Path,
  StandardCopyOption
}

import scala.util.control.NonFatal

import raider.core.RaiderError

/** Result of a completed export: the published target directory, its files (in
  * §6.2 layout order) and the identity digests (mirrored into the manifest).
  */
final case class ExportResult(
    targetDir: Path,
    files: List[Path],
    sourceDigest: String,
    specDigest: String
)

/** Export write discipline (ADR-033 §6.2, CWF-C02/CWF-C03):
  *
  *   - the TARGET directory is created by an atomic STAGING + RENAME; an
  *     interrupted export leaves only a distinguishably incomplete staging
  *     directory and is never presented as a complete workflow;
  *   - an existing target (any non-existent check fails: file, directory,
  *     SYMLINK) is a typed refusal — export never overwrites user edits;
  *   - a leftover staging directory from a previous interruption is a typed
  * refusal naming the remnant (restart = explicit re-export after removal);
  *   - concurrent exports to the same target are NOT serialized — they are
  *     excluded fail-fast on the fixed staging name: exactly one export claims
  *     staging and publishes, while a loser's `Files.createDirectory` fails
  *     with `FileAlreadyExistsException` and refuses typed WITHOUT deleting the
  *     winner's in-flight staging (RAI-219); exports to different targets are
  *     independent;
  *   - parent directories are never created implicitly (no path escape by
  * construction): the target's parent must already exist and be a real
  * directory.
  *
  * Export dispatches nothing agent-shaped: it renders text and writes files.
  */
object ChatWorkflowExport:

  /** Fixed staging-directory suffix (inside the target's parent). The target
    * name prefix keeps concurrent exports to DIFFERENT names independent.
    */
  val StagingSuffix: String = ".raider-export-staging"

  /** Renders and writes the §6.2 layout (`<name>.scala`,
    * `<name>-manifest.json`, `<name>.input.json`) into `targetDir` (which must
    * not exist; its parent must exist).
    */
  def exportTo(
      selection: StsSelection,
      options: RenderOptions,
      targetDir: Path
  ): Either[RaiderError, ExportResult] =
    for
      rendered <- ChatWorkflowRenderer.render(selection, options)
      target = targetDir.normalize()
      parent <- requireParent(target)
      _ <- requireFreshTarget(target)
      // Staging binds to the TARGET DIRECTORY NAME: two concurrent exports to
      // the same target collide on the staging name (second refuses, §9.1)
      // even when their export names differ; exports to different targets
      // stay independent.
      staging = parent.resolve(
        "." + target.getFileName.toString + StagingSuffix
      )
      _ <- requireFreshStaging(staging)
      files = List(
        rendered.name + ".scala" -> rendered.source,
        rendered.name + "-manifest.json" -> rendered.manifestJson,
        rendered.name + ".input.json" -> rendered.inputExampleJson
      )
      _ <- writeStaging(staging, files)
      published <- publish(staging, target)
    yield ExportResult(
      targetDir = published,
      files = files.map(f => published.resolve(f._1)),
      sourceDigest = rendered.sourceDigest,
      specDigest = rendered.specDigest
    )

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
        // RAI-219 (mirror of the accepted RAI-213 fix in PortableChatExport):
        // the staging claim was lost — the winning concurrent export already
        // created the staging directory between this export's freshness check
        // and its own createDirectory (the check-then-create window), or
        // another obstruction occupies the path. Fail fast WITHOUT deleting:
        // the cleanup below must only ever remove the staging directory THIS
        // export created, never the winner's in-flight staging, so exactly
        // one export publishes (ADR-033 §6.2/§9.1).
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
        // Best-effort cleanup of THIS attempt's partial staging so the next
        // export is not blocked by our own torn write. Cleanup failures keep
        // the typed refusal either way — never a silent partial publish.
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

end ChatWorkflowExport
