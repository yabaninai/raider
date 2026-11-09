package raider.runtime.conversation

import raider.core.{RaiderError, RequestMessage, RootId, SessionId}
import raider.runtime.journal.JobJournal
import zio.{Chunk, ZIO}
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, NoSuchFileException, Path}

/** The durable unit of a conversation session (RAI-036, SESSION-01): the full
  * prompt/reply transcript plus identity, stored as ONE JSON document per
  * conversation under `~/.raider/conversations/<sessionId>.json`.
  *
  * On-disk schema v1 (`contracts/sessions/session-archive.schema.json`, pinned
  * by `contracts/sessions/expected/archive-golden.json`): field order is
  * `schemaVersion, sessionId, rootId, model, label?, archivedAtMs, turns`
  * (`label` is omitted — never null — when absent). `turns` is the strict
  * prompt/reply pair list ([[ArchivedTurn]]); there is NO per-turn role field
  * because pairing IS the schema. The codec is deliberately strict about the
  * envelope (an explicit `schemaVersion` is REQUIRED — there is no v0 and no
  * migration for a brand-new schema) and lenient about unknown extra fields
  * inside the document (forward-compatible reads; the JSON Schema is stricter
  * and rejects unknown fields — this divergence is documented in
  * `contracts/sessions/README.md`).
  *
  * Honesty rules carried over from the CLI session store (RAI-077): newer
  * schema versions are never read silently (typed refusal), corrupt documents
  * are typed errors (never silently emptied), and writes are atomic.
  */
final case class ConversationArchive(
    schemaVersion: Int,
    sessionId: String,
    rootId: String,
    model: String,
    label: Option[String],
    archivedAtMs: Long,
    turns: List[ArchivedTurn]
)

object ConversationArchive:

  /** Current on-disk schema version. Writes always emit this version; reads
    * refuse anything else (there is no predecessor to migrate).
    */
  val SchemaVersion: Int = 1

  // ------------------------------------------------------------------
  // Construction from a live conversation
  // ------------------------------------------------------------------

  /** Builds the archive from a conversation snapshot (the (user, assistant)
    * message list of [[Conversation.snapshot]]). The history must be a strict
    * prompt/reply sequence — the dispatcher guarantees this; a violating
    * snapshot is a typed refusal (RA-INP), never a guessed transcript.
    * Pure: the caller supplies the snapshot and the timestamp, so the archive
    * layer has no clock and no concurrency of its own.
    */
  def fromSnapshot(
      sessionId: SessionId,
      rootId: RootId,
      label: Option[String],
      model: String,
      history: List[RequestMessage],
      archivedAtMs: Long
  ): Either[RaiderError, ConversationArchive] =
    pairTurns(history).map { turns =>
      ConversationArchive(
        schemaVersion = SchemaVersion,
        sessionId = sessionId.value,
        rootId = rootId.value,
        model = model,
        label = label,
        archivedAtMs = archivedAtMs,
        turns = turns
      )
    }

  /** The strict (user, assistant) pairing check: an uneven tail or a wrong
    * role order is a typed refusal — the archive never invents a reply for a
    * prompt or a prompt for a reply.
    */
  private def pairTurns(
      history: List[RequestMessage]
  ): Either[RaiderError, List[ArchivedTurn]] =
    def loop(
        rest: List[RequestMessage],
        acc: List[ArchivedTurn]
    ): Either[RaiderError, List[ArchivedTurn]] =
      rest match
        case Nil => Right(acc.reverse)
        case a :: b :: tail
            if a.role == Conversation.UserRole &&
              b.role == Conversation.AssistantRole =>
          loop(tail, ArchivedTurn(a.content, b.content) :: acc)
        case _ =>
          Left(
            RaiderError.InputValidation(
              "conversation history is not a strict user/assistant prompt-reply sequence"
            )
          )
    loop(history, Nil)

  /** The archive's turns as an explicit request-history seed (reopen):
    * pairing is inherent to the archive schema, so this is total.
    */
  def toSeed(archive: ConversationArchive): List[RequestMessage] =
    archive.turns.flatMap { t =>
      List(
        RequestMessage(Conversation.UserRole, t.prompt),
        RequestMessage(Conversation.AssistantRole, t.reply)
      )
    }

  /** Reopen bound check (SESSION-02): the seeded history must fit the target
    * conversation's declared bounds — an oversized archive is a typed refusal
    * BEFORE any turn runs (no implicit restart, no silent compaction).
    */
  def fitsBounds(
      archive: ConversationArchive,
      bounds: ConversationBounds
  ): Either[RaiderError, Unit] =
    Conversation.validateSeed(toSeed(archive), bounds)

  // ------------------------------------------------------------------
  // Codec (manual AST: exact field order, absent-not-null label)
  // ------------------------------------------------------------------

  /** Compact one-line JSON document in the pinned field order; the emitted
    * `schemaVersion` is always [[SchemaVersion]]. This exact encoding is
    * byte-pinned by `contracts/sessions/expected/archive-golden.json`.
    */
  def encodeDocument(archive: ConversationArchive): String =
    val labelField: Chunk[(String, Json)] = archive.label match
      case Some(text) => Chunk("label" -> Json.Str(text))
      case None       => Chunk.empty
    val doc = Json.Obj(
      Chunk(
        "schemaVersion" -> Json.Num(BigDecimal(SchemaVersion)),
        "sessionId" -> Json.Str(archive.sessionId),
        "rootId" -> Json.Str(archive.rootId),
        "model" -> Json.Str(archive.model)
      ) ++ labelField ++ Chunk(
        "archivedAtMs" -> Json.Num(BigDecimal(archive.archivedAtMs)),
        "turns" -> Json.Arr(Chunk.fromIterable(
          archive.turns.map { t =>
            Json.Obj(
              "prompt" -> Json.Str(t.prompt),
              "reply" -> Json.Str(t.reply)
            )
          }
        ))
      )
    )
    doc.toJson

  /** Decodes a stored archive document. The envelope is strict:
    *   - the document must be a JSON object with an EXPLICIT integer
    *     `schemaVersion` (no field = no v0 to migrate; refuse);
    *   - `schemaVersion` greater than [[SchemaVersion]] is a typed refusal
    *     naming the required upgrade — a document written by a newer Raider is
    *     never read silently;
    *   - unknown EXTRA fields inside the document are ignored (forward
    *     compatibility; the JSON Schema is stricter — documented divergence).
    *
    * All refusals are complete, user-facing explanations (RAI-077 style).
    */
  def decodeDocument(text: String): Either[String, ConversationArchive] =
    for
      ast <- text.fromJson[Json]
      _ <- ast match
        case _: Json.Obj => Right(())
        case _           => Left("session archive document is not a JSON object")
      versionRaw <- requiredField(ast, "schemaVersion")
      version <- versionRaw.as[Int].left.map { _ =>
        s"archive schemaVersion must be the integer 1, got: " +
          compact(versionRaw).take(60)
      }
      _ <- version match
        case 1 => Right(())
        case v if v > SchemaVersion =>
          Left(
            s"archive schemaVersion $v is newer than this Raider build " +
              s"(reads up to $SchemaVersion); upgrade Raider to load this archive"
          )
        case v =>
          Left(
            s"archive schemaVersion $v is not a valid schema version (must be >= 1)"
          )
      sessionId <- stringField(ast, "sessionId")
      rootId <- stringField(ast, "rootId")
      model <- stringField(ast, "model")
      label <- optionalStringField(ast, "label")
      archivedAtRaw <- requiredField(ast, "archivedAtMs")
      archivedAtMs <- archivedAtRaw.as[Long].left.map { _ =>
        s"archive field 'archivedAtMs' must be a non-negative integer, got: " +
          compact(archivedAtRaw).take(60)
      }
      _ <-
        if archivedAtMs >= 0L then Right(())
        else
          Left(
            s"archive field 'archivedAtMs' must be a non-negative integer, got: $archivedAtMs"
          )
      turnsRaw <- requiredField(ast, "turns")
      turns <- decodeTurns(turnsRaw)
    yield ConversationArchive(
      schemaVersion = SchemaVersion,
      sessionId = sessionId,
      rootId = rootId,
      model = model,
      label = label,
      archivedAtMs = archivedAtMs,
      turns = turns
    )

  private def requiredField(ast: Json, name: String): Either[String, Json] =
    ast match
      case Json.Obj(fields) =>
        fields.collectFirst { case (`name`, v) => v }.toRight(
          s"archive field '$name' is missing (schemaVersion 1 requires it explicitly)"
        )
      case _ => Left("archive document is not a JSON object")

  /** The `turns` array: each element a strict prompt/reply object, reported
    * with a 1-based position on refusal.
    */
  private def decodeTurns(raw: Json): Either[String, List[ArchivedTurn]] =
    raw match
      case Json.Arr(values) =>
        values.toList.zipWithIndex
          .foldLeft[Either[String, List[ArchivedTurn]]](Right(Nil)) {
            case (acc, (el, idx)) =>
              acc.flatMap(turnsSoFar =>
                decodeTurn(el, idx).map(turn => turn :: turnsSoFar)
              )
          }
          .map(_.reverse)
      case _ =>
        Left("archive field 'turns' must be an array of prompt/reply objects")

  private def decodeTurn(el: Json, idx: Int): Either[String, ArchivedTurn] =
    el match
      case obj: Json.Obj =>
        stringField(obj, "prompt").flatMap { prompt =>
          stringField(obj, "reply").map(reply => ArchivedTurn(prompt, reply))
        }
      case _ =>
        Left(
          s"archive turn ${idx + 1} must be an object with string fields 'prompt' and 'reply'"
        )

  private def stringField(ast: Json, name: String): Either[String, String] =
    requiredField(ast, name).flatMap {
      case Json.Str(s) => Right(s)
      case other =>
        Left(
          s"archive field '$name' must be a string, got: ${compact(other).take(60)}"
        )
    }

  private def optionalStringField(
      ast: Json,
      name: String
  ): Either[String, Option[String]] =
    ast match
      case Json.Obj(fields) =>
        fields.collectFirst { case (`name`, v) => v } match
          case None         => Right(None)
          case Some(Json.Str(s)) => Right(Some(s))
          case Some(other) =>
            Left(
              s"archive field '$name' must be a string, got: ${compact(other).take(60)}"
            )
      case _ => Left("archive document is not a JSON object")

  private def compact(json: Json): String = json.toJson

end ConversationArchive

/** Storage for conversation archive documents, mirroring the CLI
  * [[raider.cli.chat.SessionStore]] trust discipline: strict single-segment
  * session ids (no traversal — an id never escapes the store), atomic writes
  * via [[raider.runtime.journal.JobJournal.checkpoint]] (sibling `.tmp` +
  * ATOMIC_MOVE), typed refusals for missing/corrupt documents, and a listing
  * that skips corrupt files instead of hiding the healthy ones. All operations
  * take an explicit base directory (production default:
  * `~/.raider/conversations`) so tests use a temp dir and never touch the
  * user's real home.
  */
final case class ConversationStore(base: Path):

  /** Atomically writes the archive document as `<sessionId>.json`. */
  def save(archive: ConversationArchive): ZIO[Any, RaiderError, Path] =
    ZIO.fromEither(ConversationStore.pathFor(base, archive.sessionId)).flatMap {
      target =>
        JobJournal
          .checkpoint(ConversationArchive.encodeDocument(archive), target)
          .mapError { e =>
            RaiderError.JournalFailure(
              s"conversation archive save failed for " +
                s"'${target.getFileName}': ${String.valueOf(e.message)}"
            )
          }
          .as(target)
    }

  /** Loads one archive by id: a missing document and a corrupt document are
    * DISTINCT typed errors (RA-INP) — never a silent empty session.
    */
  def load(id: String): ZIO[Any, RaiderError, ConversationArchive] =
    ZIO.fromEither(ConversationStore.pathFor(base, id)).flatMap { target =>
      ZIO
        .attempt(Files.readString(target, UTF_8))
        .refineOrDie {
          case _: NoSuchFileException =>
            RaiderError.InputValidation(
              s"no archived conversation '$id' under $base"
            )
          case e: java.io.IOException =>
            RaiderError.JournalFailure(
              s"conversation archive read failed: ${String.valueOf(e.getMessage)}"
            )
        }
        .flatMap: text =>
          ZIO.fromEither(
            ConversationArchive
              .decodeDocument(text)
              .left
              .map { err =>
                RaiderError.InputValidation(
                  s"archived conversation '$id' is corrupt: ${err.take(140)}"
                )
              }
          )
    }

  /** Ids of all readable archives, sorted (list operation for a future
    * `:conversations` command). A corrupt file — including an unsupported
    * future schemaVersion — is SKIPPED by the listing; loading it directly
    * still fails with a typed InputValidation.
    */
  def list(): ZIO[Any, RaiderError, List[SessionId]] =
    ZIO
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
        RaiderError.JournalFailure(
          s"conversation archive list failed: ${String.valueOf(e.getMessage)}"
        )
      }
      .flatMap: files =>
        ZIO.foreach(files)(readArchiveId)
      .map: results =>
        results.flatten.sortBy(_.value)

  /** Total reader for listing: a file that cannot be read or decoded (corrupt,
    * unsupported future schema, unreadable permissions) yields `None` and is
    * skipped — a broken file must not hide the healthy ones.
    */
  private def readArchiveId(p: Path): ZIO[Any, Nothing, Option[SessionId]] =
    ZIO
      .attempt(Files.readString(p, UTF_8))
      .option
      .flatMap {
        case None => ZIO.none
        case Some(text) =>
          ZIO.succeed(
            ConversationArchive
              .decodeDocument(text)
              .toOption
              .map(archive => SessionId(archive.sessionId))
          )
      }

end ConversationStore

object ConversationStore:

  /** Production base dir. */
  def defaultBase: Path =
    Path.of(System.getProperty("user.home"), ".raider", "conversations")

  /** Strict single-segment id: letters, digits, dot, underscore, dash; no
    * leading dot; 1..64 chars. Returns None for anything else (traversal,
    * separators, empty) — identical rule to the CLI session store.
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

end ConversationStore
