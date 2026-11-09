package raider.evalexport

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import zio.IO
import zio.json.*

/** One exported document: exactly the bytes written to disk. Content is
  * always canonical compact JSON with a trailing newline for non-empty files
  * (an empty JSONL section is a zero-byte file).
  */
final case class ExportFile(name: String, content: String):
  def bytes: Array[Byte]  = content.getBytes(UTF_8)
  def sha256: String      = Digests.sha256Hex(bytes)
  def byteCount: Long     = bytes.length.toLong

/** The six export documents of one run: manifest.json, episodes.jsonl,
  * calls.jsonl, tools.jsonl, verdicts.jsonl, reconciliation.jsonl (the last
  * one starts empty; see [[Reconciliation]]).
  */
final case class ExportBundle(files: Vector[ExportFile]):

  def file(name: String): Option[ExportFile] =
    files.find(_.name == name)

  def content(name: String): Either[model.ExportError, String] =
    file(name).map(_.content).toRight(model.ExportError.BundleMissingFile(name))

  /** Decode one JSON document (manifest). */
  def parseJson[A: JsonDecoder](name: String): Either[model.ExportError, A] =
    content(name).flatMap { text =>
      text.fromJson[A].left.map(msg => model.ExportError.DecodeFailure(name, msg))
    }

  /** Decode a JSONL document: one record per line, empty file = empty vector.
    * A blank line in the middle of the file is a decode failure (an honest
    * reader never skips what it cannot read).
    */
  def parseJsonl[A: JsonDecoder](name: String): Either[model.ExportError, Vector[A]] =
    content(name).flatMap { text =>
      ExportBundle.splitJsonl(text).foldLeft[Either[model.ExportError, Vector[A]]](Right(Vector.empty)) {
        (acc, line) =>
          for
            done <- acc
            rec <- line.fromJson[A].left.map(msg => model.ExportError.DecodeFailure(name, msg))
          yield done :+ rec
      }
    }

  /** Replace one file's content (used by the reconciliation append; every
    * other document stays byte-identical).
    */
  def withFile(name: String, content: String): ExportBundle =
    ExportBundle(files.map(f => if f.name == name then ExportFile(name, content) else f))

  /** True iff both bundles carry the same file set with byte-identical
    * content per file.
    */
  def identical(other: ExportBundle): Boolean =
    fileMap == other.fileMap

  def fileMap: Map[String, String] =
    files.map(f => f.name -> f.content).toMap

  /** Recompute the manifest section digests against actual section bytes
    * (EXPORT-01 evidence). reconciliation.jsonl is intentionally excluded
    * from the manifest pin: it is append-only and self-chained.
    */
  def verifyIntegrity(): Either[model.ExportError, Unit] =
    for
      manifest <- parseJson[wire.ManifestWire](ExportBundle.ManifestName)
      _ <- check(manifest.integrity.episodesSha256, ExportBundle.EpisodesName)
      _ <- check(manifest.integrity.callsSha256, ExportBundle.CallsName)
      _ <- check(manifest.integrity.toolsSha256, ExportBundle.ToolsName)
      _ <- check(manifest.integrity.verdictsSha256, ExportBundle.VerdictsName)
    yield ()

  private def check(expected: String, name: String): Either[model.ExportError, Unit] =
    for
      f <- file(name).toRight(model.ExportError.BundleMissingFile(name))
      actual = f.sha256
      _ <-
        if actual == expected then Right(())
        else Left(model.ExportError.IntegrityMismatch(name, expected, actual))
    yield ()

object ExportBundle:

  val ManifestName       = "manifest.json"
  val EpisodesName       = "episodes.jsonl"
  val CallsName          = "calls.jsonl"
  val ToolsName          = "tools.jsonl"
  val VerdictsName       = "verdicts.jsonl"
  val ReconciliationName = "reconciliation.jsonl"

  val AllNames: Vector[String] =
    Vector(ManifestName, EpisodesName, CallsName, ToolsName, VerdictsName, ReconciliationName)

  /** Split JSONL content into records; the single trailing newline of a
    * non-empty file is dropped, blank lines inside are kept (and fail decode).
    */
  def splitJsonl(content: String): Vector[String] =
    if content.isEmpty then Vector.empty
    else content.stripSuffix("\n").split("\n", -1).toVector

/** Blocking file IO for export bundles (ZIO-wrapped; the export transform
  * itself is pure — this object only persists/reads documents).
  */
object BundleIo:

  /** Write all bundle files into `dir` (creating it), UTF-8, overwrite. */
  def write(bundle: ExportBundle, dir: Path): IO[Throwable, Unit] =
    zio.ZIO.attempt {
      Files.createDirectories(dir)
      bundle.files.foreach(f => Files.writeString(dir.resolve(f.name), f.content, UTF_8))
    }.unit

  /** Read a bundle directory. A missing file is a typed
    * [[model.ExportError.BundleMissingFile]] — a partial run directory is
    * never silently accepted.
    */
  def read(dir: Path): IO[model.ExportError, ExportBundle] =
    ExportBundle.AllNames.foldLeft[zio.ZIO[Any, model.ExportError, Vector[ExportFile]]](
      zio.ZIO.succeed(Vector.empty)
    ) { (acc, name) =>
      for
        done   <- acc
        path   = dir.resolve(name)
        exists <- zio.ZIO
          .attempt(Files.exists(path))
          .mapError(t => model.ExportError.DecodeFailure(s"stat $name", t.getMessage))
        content <-
          if !exists then zio.ZIO.fail(model.ExportError.BundleMissingFile(name))
          else
            zio.ZIO
              .attempt(Files.readString(path, UTF_8))
              .mapError(t => model.ExportError.DecodeFailure(s"read $name", t.getMessage))
      yield done :+ ExportFile(name, content)
    }.map(ExportBundle(_))
