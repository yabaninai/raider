package raider.compat.pi

import java.nio.file.Path

import zio.json.DeriveJsonCodec
import zio.json.JsonCodec

/** One pinned corpus file (path relative to the corpus root + sha256). */
final case class CorpusFile(path: String, sha256: String, role: String)

object CorpusFile:
  given JsonCodec[CorpusFile] = DeriveJsonCodec.gen[CorpusFile]

/** Immutable import-time provenance of the vendored pi profile corpus.
  *
  * Constructed by the caller from `vendor/pi-profiles/PROVENANCE.md`; the
  * importer embeds it (plus the observed corpus inventory) into every report.
  * `corpusFiles` defaults to empty and is filled by the importer.
  */
final case class PiProvenance(
    upstreamRepo: String,
    upstreamPath: String,
    upstreamCommit: String,
    license: String,
    licenseCopyright: String,
    vendoredCorpus: String,
    fetchedOn: String,
    corpusFiles: List[CorpusFile] = Nil
)

object PiProvenance:
  given JsonCodec[PiProvenance] = DeriveJsonCodec.gen[PiProvenance]

/** Typed import errors (no silent drops, no stringly-typed failures). */
sealed trait PiImportError extends scala.util.control.NoStackTrace

object PiImportError:

  final case class CorpusMissing(root: Path) extends PiImportError:

    override def getMessage: String =
      s"pi corpus root not found or contains no files: $root"
