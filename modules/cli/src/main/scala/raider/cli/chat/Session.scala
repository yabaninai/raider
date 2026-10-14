package raider.cli.chat

import raider.core.{RaiderError, RequestMessage}
import zio.ZIO
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}

/** A persisted chat session (nightly Phase 2.2): full message history + model,
  * stored as one JSON document per session under
  * `~/.raider/sessions/<id>.json`.
  *
  * On-disk schema (RAI-077): `schemaVersion` 2 adds optional `workspace` and
  * `label`. Documents without a `schemaVersion` field are v1 and are migrated
  * in memory on load (see [[SavedSession.decodeDocument]]). Newer documents are
  * never read silently — they fail with a typed error.
  *
  * Schema v3 (RAI-155.b, ADR-033 §5.2, ADDITIVE): the exportable turn ledger
  * (`turns`) and user-registered named trusted checks (`checks`) are stored
  * beside the message history. v1/v2 documents load unchanged with the new
  * fields empty — they stay readable, but they carry no capture provenance, so
  * export selection from them is a typed refusal (the ledger is the only
  * export-provenance source; the product cannot invent absent execution
  * records). A v3 document with a present-but-malformed ledger decodes as a
  * typed corrupt-document error — never silently emptied.
  */
final case class SavedSession(
    id: String,
    messages: List[RequestMessage],
    model: String,
    timestamp: String,
    schemaVersion: Int = SavedSession.SchemaVersion,
    workspace: Option[String] = None,
    label: Option[String] = None,
    turns: List[TurnRecord] = Nil,
    checks: List[NamedCheck] = Nil
)

object SavedSession:

  /** Current on-disk schema version. v1 documents (no `schemaVersion` field)
    * are migrated in memory to this version on load; writes always emit this
    * version.
    */
  val SchemaVersion: Int = 3

  given JsonCodec[RequestMessage] = DeriveJsonCodec.gen[RequestMessage]
  given JsonCodec[SavedSession] = DeriveJsonCodec.gen[SavedSession]

  private val VersionField = "schemaVersion"

  /** Decode a stored session document with schema migration (RAI-077):
    *   - v1 documents (no `schemaVersion` field, or explicit `1`) are migrated
    *     in memory to the current version: `id`/`messages`/`model`/`timestamp`
    *     are preserved and the newer optional fields default to empty/`None`.
    *   - current-version documents decode as-is; a present-but-malformed turn
    *     ledger is a typed decode error (never silently emptied).
    *   - any other `schemaVersion` (newer than this build understands, or not a
    *     positive integer) is a typed decode error — a document written by a
    *     different schema is never read silently.
    *
    * The error string is a complete, user-facing explanation.
    */
  def decodeDocument(text: String): Either[String, SavedSession] =
    for
      ast <- text.fromJson[Json]
      version <- documentVersion(ast)
      _ <- Either.cond(
        version <= SchemaVersion,
        (),
        s"unsupported $VersionField $version (this build reads up to " +
          s"$SchemaVersion); the document was written by a newer Raider — " +
          "upgrade Raider to load this session"
      )
      session <- ast.as[SavedSession]
      _ <- TurnLedger
        .validateTurnFields(session.turns)
        .left
        .map(err => s"corrupt turn ledger: $err")
    yield session.copy(schemaVersion = SchemaVersion)

  /** Schema version declared by the document; v1 documents predate the field
    * and read as version 1.
    */
  private def documentVersion(ast: Json): Either[String, Int] =
    ast match
      case Json.Obj(fields) =>
        fields.collectFirst { case (`VersionField`, raw) => raw } match
          case None => Right(1)
          case Some(raw) =>
            raw.as[Int] match
              case Right(v) if v >= 1 => Right(v)
              case Right(v) =>
                Left(
                  s"$VersionField $v is not a valid schema version (must be >= 1)"
                )
              case Left(_) =>
                Left(
                  s"$VersionField must be an integer, got: ${raw.toString.take(60)}"
                )
      case _ => Left("session document is not a JSON object")

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
    * The document on disk is always the current schema version (RAI-077): an
    * in-memory `schemaVersion` value is normalized on write.
    */
  def save(base: Path, session: SavedSession): ZIO[Any, RaiderError, Path] =
    ZIO.fromEither(pathFor(base, session.id)).flatMap { target =>
      zio.ZIO
        .attempt {
          Files.createDirectories(base)
          val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
          val doc = session.copy(schemaVersion = SavedSession.SchemaVersion)
          Files.writeString(
            tmp,
            doc.toJson,
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
          SavedSession.decodeDocument(text).left.map { err =>
            RaiderError.InputValidation(
              s"session '${p.getFileName}' is corrupt: ${err.take(140)}"
            )
          }
        )

  def load(base: Path, id: String): ZIO[Any, RaiderError, SavedSession] =
    ZIO.fromEither(pathFor(base, id)).flatMap(readOne)

  /** All saved sessions sorted by id (list operation for :sessions). A corrupt
    * file — including one with an unsupported future schemaVersion — is SKIPPED
    * by the listing (a broken file must not hide the rest); loading it directly
    * still fails with a typed InputValidation.
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
