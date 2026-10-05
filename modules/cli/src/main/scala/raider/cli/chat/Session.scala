package raider.cli.chat

import raider.core.{RaiderError, RequestMessage}
import zio.ZIO
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}

/** A persisted chat session (nightly Phase 2.2): full message history + model,
  * stored as one JSON document per session under
  * `~/.raider/sessions/<id>.json`.
  */
final case class SavedSession(
    id: String,
    messages: List[RequestMessage],
    model: String,
    timestamp: String
)

object SavedSession:
  given JsonCodec[RequestMessage] = DeriveJsonCodec.gen[RequestMessage]
  given JsonCodec[SavedSession] = DeriveJsonCodec.gen[SavedSession]

/** Storage for SavedSession documents. All operations take an explicit base
  * directory (production default: ~/.raider/sessions) so tests use a temp dir
  * and never touch the user's real home.
  *
  * Trust boundaries honored: session ids are strictly sanitized (single path
  * segment, no traversal — a `:load ../../etc/x` must never escape the store),
  * and writes are atomic (temp file + rename) so a crash mid-save never leaves
  * a half-written document.
  */
object SessionStore:

  /** Production base dir. */
  def defaultBase: Path =
    Path.of(System.getProperty("user.home"), ".raider", "sessions")

  /** Strict single-segment id: letters, digits, dot, underscore, dash; no
    * leading dot; 1..64 chars. Returns None for anything else (traversal,
    * separators, empty).
    */
  def sanitize(id: String): Option[String] =
    val ok = id.nonEmpty && id.length <= 64 &&
      !id.startsWith(".") &&
      id.forall(c => c.isLetterOrDigit || c == '.' || c == '_' || c == '-')
    if ok then Some(id) else None

  private def pathFor(base: Path, id: String): Either[RaiderError, Path] =
    sanitize(id) match
      case None =>
        Left(
          RaiderError.InputValidation(
            s"invalid session id '$id' (allowed: [A-Za-z0-9._-], no leading dot)"
          )
        )
      case Some(clean) => Right(base.resolve(s"$clean.json"))

  /** Atomic save: serialize, write to `<id>.json.tmp`, rename over the target.
    */
  def save(base: Path, session: SavedSession): ZIO[Any, RaiderError, Path] =
    ZIO.fromEither(pathFor(base, session.id)).flatMap { target =>
      zio.ZIO
        .attempt {
          Files.createDirectories(base)
          val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
          Files.writeString(
            tmp,
            session.toJson,
            UTF_8
          )
          Files.move(
            tmp,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
          target
        }
        .refineOrDie { case e: java.io.IOException =>
          RaiderError.JournalFailure(s"session save failed: ${e.getMessage}")
        }
    }

  private def readOne(p: Path): ZIO[Any, RaiderError, SavedSession] =
    zio.ZIO
      .attempt(Files.readString(p, UTF_8))
      .refineOrDie {
        case _: java.nio.file.NoSuchFileException =>
          RaiderError.InputValidation(
            s"no such session: ${p.getFileName.toString.stripSuffix(".json")}"
          )
        case e: java.io.IOException =>
          RaiderError.JournalFailure(s"session read failed: ${e.getMessage}")
      }
      .flatMap: text =>
        zio.ZIO.fromEither(
          text.fromJson[SavedSession].left.map { err =>
            RaiderError.InputValidation(
              s"session '${p.getFileName}' is corrupt: ${err.take(140)}"
            )
          }
        )

  def load(base: Path, id: String): ZIO[Any, RaiderError, SavedSession] =
    ZIO.fromEither(pathFor(base, id)).flatMap(readOne)

  /** All saved sessions sorted by id (list operation for :sessions). A corrupt
    * file is SKIPPED by the listing (a broken file must not hide the rest);
    * loading it directly still fails with a typed InputValidation.
    */
  def list(base: Path): ZIO[Any, RaiderError, List[SavedSession]] =
    zio.ZIO
      .attempt {
        if !Files.isDirectory(base) then Nil
        else
          import scala.jdk.CollectionConverters.*
          Files
            .list(base)
            .iterator()
            .asScala
            .filter(p => p.getFileName.toString.endsWith(".json"))
            .toList
      }
      .refineOrDie { case e: java.io.IOException =>
        RaiderError.JournalFailure(s"session list failed: ${e.getMessage}")
      }
      .flatMap(files => ZIO.foreach(files)(p => readOne(p).either))
      .map: results =>
        val ok = results.collect { case Right(s: SavedSession) => s }
        ok.sortBy(_.id)
