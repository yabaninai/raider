package raider.dsl.portable

import java.nio.charset.StandardCharsets
import java.nio.file.{
  Files,
  LinkOption,
  NoSuchFileException,
  NotDirectoryException,
  Path
}
import java.security.MessageDigest

import scala.util.control.NonFatal

import raider.core.RaiderError
import zio.json.ast.Json

/** The launcher-side bundle verification result (RAI-214): the parsed closed
  * manifest plus the EXACT verified bytes of the two manifested artifacts. A
  * launcher that lowers these bytes (never a re-read) lowers precisely what the
  * manifest pinned.
  *
  * Byte-array fields compare by identity, not by value; use the digests (or
  * `java.util.Arrays.equals`) for comparison.
  */
final case class PortableVerifiedBundle(
    bundleDir: Path,
    manifestFileName: String,
    manifest: Json,
    documentFileName: String,
    inputExampleFileName: String,
    documentBytes: Array[Byte],
    inputExampleBytes: Array[Byte],
    documentSha256: String,
    inputExampleSha256: String
)

/** Where a chat-portable export bundle lives: the published directory and the
  * manifest file name inside it. The two data files are NAMED BY the manifest
  * and are resolved during verification, never by caller convention.
  */
final case class PortableBundlePaths(
    bundleDir: Path,
    manifestFileName: String
)

/** Typed bundle-verification refusal (RAI-214). `code` is a stable machine tag;
  * `message` is the human-readable English explanation; [[toRaiderError]] maps
  * the refusal into the ordinary typed input-validation error the launcher
  * already reports (CWF-R1 vocabulary, RA-INP).
  */
sealed trait PortableBundleRefusal:
  def code: String
  def message: String

  def toRaiderError: RaiderError.InputValidation =
    RaiderError.InputValidation("CWF-R1: " + message)

object PortableBundleRefusal:

  /** The bundle directory itself does not exist (or is not a directory). */
  final case class BundleDirMissing(bundleDir: Path)
      extends PortableBundleRefusal:
    val code = "bundle-dir-missing"
    val message = "the export bundle directory does not exist: " + bundleDir

  /** The bundle directory could not be listed. */
  final case class BundleUnreadable(bundleDir: Path, reason: String)
      extends PortableBundleRefusal:
    val code = "bundle-dir-unreadable"

    val message =
      "the export bundle directory could not be listed: " + bundleDir + ": " + reason

  /** The directory holds more entries than the verification bound allows. */
  final case class BundleTooLarge(entryCount: Int, capEntries: Int)
      extends PortableBundleRefusal:
    val code = "bundle-too-large"

    val message =
      "the export bundle holds " + entryCount + " entries, above the verification bound of " + capEntries + "; a published export holds exactly three files"

  /** No manifest file at the bundle's expected manifest name. */
  final case class ManifestMissing(manifestFileName: String)
      extends PortableBundleRefusal:
    val code = "bundle-manifest-missing"
    val message = "the export bundle manifest is missing: " + manifestFileName

  /** The manifest file is larger than the verification bound. */
  final case class ManifestOversized(
      manifestFileName: String,
      sizeBytes: Long,
      capBytes: Long
  ) extends PortableBundleRefusal:
    val code = "bundle-manifest-oversized"

    val message =
      "the export bundle manifest is " + sizeBytes + " bytes, above the verification bound of " + capBytes + ": " + manifestFileName

  /** The manifest is malformed, fails its closed schema check, or cannot be
    * located as a regular file sibling entry.
    */
  final case class ManifestInvalid(manifestFileName: String, reason: String)
      extends PortableBundleRefusal:
    val code = "bundle-manifest-invalid"

    val message =
      "the export bundle manifest failed its closed schema check (" + manifestFileName + "): " + reason

  /** A manifest-named artifact is absent from the bundle directory. */
  final case class EntryMissing(fileName: String) extends PortableBundleRefusal:
    val code = "bundle-entry-missing"

    val message =
      "the export bundle is incomplete; the manifest names a file that is absent: " + fileName

  /** A manifest-named artifact exists but is not a regular file (directory or
    * symlink — a published export holds regular files only).
    */
  final case class EntryNotRegularFile(fileName: String)
      extends PortableBundleRefusal:
    val code = "bundle-entry-not-regular-file"
    val message = "the manifest-named entry is not a regular file: " + fileName

  /** A manifest-named artifact exists but its bytes could not be read. */
  final case class EntryUnreadable(fileName: String, reason: String)
      extends PortableBundleRefusal:
    val code = "bundle-entry-unreadable"

    val message =
      "the manifest-named entry could not be read: " + fileName + ": " + reason

  /** The bytes on disk do not match the digest pinned in the manifest. */
  final case class DigestMismatch(
      fileName: String,
      expected: String,
      actual: String
  ) extends PortableBundleRefusal:
    val code = "bundle-digest-mismatch"

    val message =
      "the bundle bytes do not match the manifest digest for " + fileName + " (expected " + expected + ", actual " + actual + "); the bundle is tampered or corrupted"

  /** The directory holds entries the manifest does not name. */
  final case class ExtraEntries(entries: Vector[String])
      extends PortableBundleRefusal:
    val code = "bundle-extra-entries"

    val message =
      "the export bundle holds unmanifested entries: " + entries.mkString(", ")

  /** The manifest's declared file names cannot form a closed three-file bundle
    * (they collide with each other or with the manifest's own name).
    */
  final case class DeclaredNamesInvalid(reason: String)
      extends PortableBundleRefusal:
    val code = "bundle-declared-names-invalid"

    val message =
      "the manifest-declared artifact names cannot form a closed bundle: " + reason

end PortableBundleRefusal

/** Launcher-side manifest digest verification for chat-portable export bundles
  * (RAI-214; additive consumer of the frozen `chat-portable-export-manifest-v1`
  * schema — no schema change).
  *
  * Discipline:
  *   - the closed manifest schema check is REUSED from the producer
  *     ([[PortableChatExport.validateManifest]]), so verification and export
  *     can never drift apart on schema interpretation;
  *   - verification is a PURE function over the bundle directory: no locks, no
  *     shared mutable state, no atomics — concurrent verifications of the same
  *     bundle are independent and deterministic;
  *   - verification is BOUNDED: the directory listing stops at
  *     [[MaxBundleEntries]] entries and the manifest read at
  *     [[MaxManifestBytes]] bytes, both typed refusals, never unbounded scans;
  *   - every entry is required to be a REGULAR FILE resolved without following
  *     symlinks (a published export holds regular files only), and the
  *     directory must CLOSE under the manifest: exactly the manifest plus the
  *     two manifested artifacts, anything else is a typed refusal;
  *   - refusals are TOTAL and typed ([[PortableBundleRefusal]]); a refusal
  *     precedes any lowering work by construction — the launcher calls this
  *     before [[PortableWorkflowLowerer.lower]] and lowers the VERIFIED bytes
  *     returned here, never a re-read (this closes the verify-to-lower drift
  *     window; bytes may still drift after verification returns, which is
  *     inherent to any file-backed check);
  *   - verification dispatches nothing agent-shaped and performs no network or
  *     paid work.
  */
object PortableBundleVerifier:

  /** Upper bound on directory entries accepted for verification (a published
    * export holds exactly three files: manifest, document, input example).
    */
  val MaxBundleEntries: Int = 16

  /** Upper bound on manifest bytes accepted for parsing (a produced manifest is
    * a few kilobytes; 64 KiB leaves generous headroom for provenance).
    */
  val MaxManifestBytes: Long = 65536L

  /** The manifest name convention for an exported document file:
    * `X.workflow.json` is accompanied by `X-manifest.json`. Documents outside
    * the export naming convention have no bundle contract (`None`).
    */
  def manifestNameFor(documentFileName: String): Option[String] =
    val suffix = ".workflow.json"
    if documentFileName.endsWith(
        suffix
      ) && documentFileName.length > suffix.length
    then Some(documentFileName.stripSuffix(suffix) + "-manifest.json")
    else None

  /** Detects whether a launcher `--doc` path carries the bundle contract: the
    * sibling manifest per the export naming convention exists. Pure document
    * paths (no sibling manifest) yield `None` and the launcher keeps its
    * existing un-manifested behavior. Existence is checked WITHOUT resolving
    * the entry type — a non-regular sibling is detected as a bundle and then
    * refused typed by [[verifyBundle]] instead of being silently ignored.
    */
  def detectForDocument(documentPath: Path): Option[PortableBundlePaths] =
    val docName = documentPath.getFileName.toString
    manifestNameFor(docName).flatMap { manifestName =>
      Option(documentPath.getParent).flatMap { bundleDir =>
        if Files.exists(bundleDir.resolve(manifestName)) then
          Some(PortableBundlePaths(bundleDir, manifestName))
        else None
      }
    }

  /** Verifies one chat-portable export bundle: the manifest parses and passes
    * its closed schema check, the directory closes under the manifest, and
    * every manifested artifact's on-disk bytes match the pinned sha256 digest.
    * On success returns the parsed manifest plus the verified bytes so the
    * launcher can lower exactly what was verified.
    */
  def verifyBundle(
      bundle: PortableBundlePaths
  ): Either[PortableBundleRefusal, PortableVerifiedBundle] =
    for
      entries <- listBounded(bundle.bundleDir)
      manifestJson <- readManifest(bundle, entries)
      documentName <- declaredName(
        manifestJson,
        bundle.manifestFileName,
        "document"
      )
      inputName <- declaredName(
        manifestJson,
        bundle.manifestFileName,
        "inputExample"
      )
      _ <- requireClosed(
        entries,
        bundle.manifestFileName,
        documentName,
        inputName
      )
      documentBytes <- readEntry(bundle.bundleDir, documentName)
      inputBytes <- readEntry(bundle.bundleDir, inputName)
      documentDigest <- requireDigest(
        documentName,
        documentBytes,
        pinnedDigest(manifestJson, "document")
      )
      inputDigest <- requireDigest(
        inputName,
        inputBytes,
        pinnedDigest(manifestJson, "inputExample")
      )
    yield PortableVerifiedBundle(
      bundleDir = bundle.bundleDir,
      manifestFileName = bundle.manifestFileName,
      manifest = manifestJson,
      documentFileName = documentName,
      inputExampleFileName = inputName,
      documentBytes = documentBytes,
      inputExampleBytes = inputBytes,
      documentSha256 = documentDigest,
      inputExampleSha256 = inputDigest
    )

  // -- steps -----------------------------------------------------------------

  private def listBounded(
      bundleDir: Path
  ): Either[PortableBundleRefusal, Vector[String]] =
    if !Files.isDirectory(bundleDir) then
      Left(PortableBundleRefusal.BundleDirMissing(bundleDir))
    else
      try
        val stream = Files.newDirectoryStream(bundleDir)
        try
          val names = Vector.newBuilder[String]
          val iter = stream.iterator()
          var count = 0
          var over = false
          while iter.hasNext && !over do
            names += iter.next().getFileName.toString
            count += 1
            if count > MaxBundleEntries then over = true
          if over then
            Left(PortableBundleRefusal.BundleTooLarge(count, MaxBundleEntries))
          else Right(names.result())
        finally stream.close()
      catch
        case _: NotDirectoryException =>
          Left(PortableBundleRefusal.BundleDirMissing(bundleDir))
        case NonFatal(t) =>
          Left(
            PortableBundleRefusal.BundleUnreadable(
              bundleDir,
              t.getClass.getSimpleName
            )
          )

  private def readManifest(
      bundle: PortableBundlePaths,
      entries: Vector[String]
  ): Either[PortableBundleRefusal, Json] =
    if !entries.contains(bundle.manifestFileName) then
      Left(PortableBundleRefusal.ManifestMissing(bundle.manifestFileName))
    else
      val manifestPath = bundle.bundleDir.resolve(bundle.manifestFileName)
      for
        _ <- requireRegularFile(bundle.manifestFileName, manifestPath)
        size <- manifestSize(bundle.manifestFileName, manifestPath)
        _ <-
          if size > MaxManifestBytes then
            Left(
              PortableBundleRefusal.ManifestOversized(
                bundle.manifestFileName,
                size,
                MaxManifestBytes
              )
            )
          else Right(())
        text <- readText(bundle.manifestFileName, manifestPath)
        json <- PortableChatExport
          .validateManifest(text)
          .left
          .map(reason =>
            PortableBundleRefusal
              .ManifestInvalid(bundle.manifestFileName, reason)
          )
      yield json

  private def manifestSize(
      manifestFileName: String,
      manifestPath: Path
  ): Either[PortableBundleRefusal, Long] =
    try Right(Files.size(manifestPath))
    catch
      case _: NoSuchFileException =>
        Left(PortableBundleRefusal.ManifestMissing(manifestFileName))
      case NonFatal(t) =>
        Left(
          PortableBundleRefusal.EntryUnreadable(
            manifestFileName,
            t.getClass.getSimpleName
          )
        )

  private def declaredName(
      manifest: Json,
      manifestFileName: String,
      field: String
  ): Either[PortableBundleRefusal, String] =
    manifest match
      case obj: Json.Obj =>
        obj.fields.collectFirst { case (`field`, child: Json.Obj) =>
          child.fields.collectFirst { case ("fileName", Json.Str(name)) =>
            name
          }
        }.flatten match
          case Some(name) => Right(name)
          case None =>
            Left(
              PortableBundleRefusal.ManifestInvalid(
                manifestFileName,
                "the manifest is missing its declared " + field + " fileName"
              )
            )
      case _ =>
        Left(
          PortableBundleRefusal.ManifestInvalid(
            manifestFileName,
            "the manifest is not a JSON object"
          )
        )

  private def requireClosed(
      entries: Vector[String],
      manifestName: String,
      documentName: String,
      inputName: String
  ): Either[PortableBundleRefusal, Unit] =
    if documentName == inputName then
      Left(
        PortableBundleRefusal.DeclaredNamesInvalid(
          "the document and the input example are declared under one name: " + documentName
        )
      )
    else if documentName == manifestName || inputName == manifestName then
      Left(
        PortableBundleRefusal.DeclaredNamesInvalid(
          "a declared artifact name collides with the manifest name: " + manifestName
        )
      )
    else
      val expected = Set(manifestName, documentName, inputName)
      val extras = entries.filterNot(expected.contains).sorted
      if extras.isEmpty then Right(())
      else
        Left(
          PortableBundleRefusal.ExtraEntries(
            extras.take(MaxBundleEntries).toVector
          )
        )

  private def readEntry(
      bundleDir: Path,
      fileName: String
  ): Either[PortableBundleRefusal, Array[Byte]] =
    val path = bundleDir.resolve(fileName)
    for
      _ <- requireRegularFile(fileName, path)
      bytes <-
        try Right(Files.readAllBytes(path))
        catch
          case NonFatal(t) =>
            Left(
              PortableBundleRefusal
                .EntryUnreadable(fileName, t.getClass.getSimpleName)
            )
    yield bytes

  private def requireRegularFile(
      fileName: String,
      path: Path
  ): Either[PortableBundleRefusal, Unit] =
    try
      val attrs = Files.readAttributes(
        path,
        classOf[java.nio.file.attribute.BasicFileAttributes],
        LinkOption.NOFOLLOW_LINKS
      )
      if attrs.isRegularFile then Right(())
      else Left(PortableBundleRefusal.EntryNotRegularFile(fileName))
    catch
      case _: NoSuchFileException =>
        Left(PortableBundleRefusal.EntryMissing(fileName))
      case NonFatal(t) =>
        Left(
          PortableBundleRefusal.EntryUnreadable(
            fileName,
            t.getClass.getSimpleName
          )
        )

  private def readText(
      fileName: String,
      path: Path
  ): Either[PortableBundleRefusal, String] =
    try Right(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    catch
      case NonFatal(t) =>
        Left(
          PortableBundleRefusal.EntryUnreadable(
            fileName,
            t.getClass.getSimpleName
          )
        )

  private def pinnedDigest(manifest: Json, field: String): String =
    manifest match
      case obj: Json.Obj =>
        obj.fields
          .collectFirst { case (`field`, child: Json.Obj) =>
            child.fields.collectFirst { case ("sha256", Json.Str(digest)) =>
              digest
            }
          }
          .flatten
          .getOrElse("")
      case _ => ""

  private def requireDigest(
      fileName: String,
      bytes: Array[Byte],
      expected: String
  ): Either[PortableBundleRefusal, String] =
    val actual = "sha256:" + PortableChatExport.sha256Hex(bytes)
    if MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8)
      )
    then Right(actual)
    else Left(PortableBundleRefusal.DigestMismatch(fileName, expected, actual))

end PortableBundleVerifier
