package raider.dsl.lineage.store

import raider.dsl.lineage.data.LineageDataDocument

import zio.json.{DecoderOps, EncoderOps, JsonDecoder}
import zio.json.ast.Json

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Typed failure of the lineage recording store (LIN-02D, ADR-027 §6.5).
  *
  * Minimal by contract — exactly three cases, no lineage-data schema
  * extensions:
  *   - [[StoreError.Missing]] — the requested file does not exist;
  *   - [[StoreError.Corrupt]] — the file exists but does not decode as a
  *     lineage-data document (truncated tail, garbage tail, empty file, wrong
  *     shape). The detail carries the decode reason plus a bounded raw prefix
  *     of the file's OWN bytes; no secret material is ever mixed in (see
  *     [[LineageDataStore.read]]);
  *   - [[StoreError.Io]] — environment-level IO failure (create/write/move/
  *     read), including a refused overlapping write to the same target path.
  *
  * Content distrust beyond decode (schemaVersion != 1, broken dataRef) is NOT a
  * store error: that validation stays in the data layer
  * (`raider.dsl.lineage.data.LineageDataFile`). This slice is file mechanics
  * only.
  */
enum StoreError:
  case Missing(path: Path)
  case Corrupt(path: Path, detail: String)
  case Io(path: Path, detail: String)

/** Recording store for lineage-data.json (LIN-02D, ADR-027 §6.5): the durable
  * file mechanics the wave-2 event→document reducer will record through.
  * Write/read only — no reduction, no HeadlessRunner wiring, no replay policy
  * lives here.
  *
  * Atomicity: [[LineageDataStore.write]] serializes the document in the codec
  * canonical form (sorted `data` keys — every write of the same document is
  * byte-identical), lands the bytes in a UNIQUE tmp file created in the target
  * directory, then ATOMIC_MOVEs it onto the target. A reader of the target
  * always sees the previous or the new whole document, never a torn one;
  * writers to distinct paths never interact.
  *
  * Durability honesty (no-fsync contract, same discipline as JobJournal
  * checkpoint and LineageDataFile): the tmp write reaches the OS page cache and
  * NOTHING more. No `fsync`/`FileChannel.force` on the tmp file, the rename or
  * the directory is performed, so persistence is NOT claimed to survive power
  * loss — the atomic move orders concurrent visibility, not durability.
  *
  * Writer model: single writer per target path. Overlapping writes to the same
  * path from this JVM are refused: the loser gets [[StoreError.Io]] and never
  * blocks, never corrupts; the winner's document lands whole. The guard is one
  * in-process lock per canonical target path (kept for the JVM's lifetime, so
  * the map is bounded by the number of distinct targets this process writes).
  * Symlink aliasing of one file under two paths is out of scope of the guard.
  * Cross-process same-path writers are out of scope, as in JobJournal — the
  * tmp+move still keeps every observed file whole (last complete write wins).
  */
object LineageDataStore:

  private val PrefixMax = 200

  private val writeLocks = new ConcurrentHashMap[String, ReentrantLock]

  /** Per-target single-writer guard. `private[store]` so this slice's tests can
    * pin the guard and verify the deterministic refusal (LIN2-D-03) without
    * timing races.
    */
  private[store] def writeLockFor(path: Path): ReentrantLock =
    writeLocks.computeIfAbsent(
      path.toAbsolutePath.normalize.toString,
      _ => new ReentrantLock()
    )

  /** Atomically replaces the document at `path` with `doc` (see the object
    * scaladoc for the atomicity/durability/writer-model honesty). Returns
    * [[StoreError.Io]] on any environment IO failure, including a refused
    * overlapping write to the same target. Non-IO defects (a codec bug) are not
    * masked — they propagate.
    */
  def write(path: Path, doc: LineageDataDocument): Either[StoreError, Unit] =
    val lock = writeLockFor(path)
    if !lock.tryLock() then
      Left(
        StoreError.Io(
          path,
          "write refused: another writer already holds this target (single-writer contract)"
        )
      )
    else
      try
        try
          val dir =
            Option(path.toAbsolutePath.getParent).getOrElse(Path.of("."))
          Files.createDirectories(dir)
          val tmp =
            Files.createTempFile(dir, path.getFileName.toString + ".", ".tmp")
          try
            Files.writeString(tmp, doc.toJson, StandardCharsets.UTF_8)
            Files.move(
              tmp,
              path,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE
            )
            Right(())
          finally deleteTempQuietly(tmp)
        catch
          case error: IOException =>
            Left(StoreError.Io(path, String.valueOf(error.getMessage)))
      finally
        lock.unlock()
        ()

  /** Reads and decodes the document at `path` through the frozen lineage-data
    * codecs. Honest failure mode with NO auto-repair and NO auto-replay: a
    * corrupted or truncated file is reported typed and left untouched on disk;
    * what happens next is entirely the caller's decision.
    *   - absent file → [[StoreError.Missing]];
    *   - present but undecodable content (truncated tail, garbage tail, empty
    *     file, wrong shape) → [[StoreError.Corrupt]] — a decodable-looking
    *     prefix is never masked into success. The detail carries the decode
    *     reason and a bounded raw prefix (≤ [[PrefixMax]] chars, control
    *     characters blanked) composed ONLY from the inspected file's own
    *     leading bytes; the store never mixes environment values, credentials
    *     or unrelated paths into diagnostics;
    *   - unreadable environment (directory at the path, permission, other IO
    *     failure) → [[StoreError.Io]].
    *
    * Strict single-value semantics: the file must hold EXACTLY one JSON value
    * (plus insignificant surrounding whitespace). zio-json's string decoder
    * stops after the first value, which would silently mask a corrupted tail
    * ("valid prefix" success), so the content is decoded wrapped in a
    * one-element array: trailing garbage, a second document or stray commas
    * then fail the array grammar itself, and the single element is decoded with
    * the same frozen codecs via the JSON AST.
    */
  def read(path: Path): Either[StoreError, LineageDataDocument] =
    try
      val bytes = Files.readAllBytes(path)
      val text = new String(bytes, StandardCharsets.UTF_8)
      // See the strict single-value note above for the one-element wrapping.
      ("[" + text + "]").fromJson[Json] match
        case Right(Json.Arr(values)) if values.size == 1 =>
          summon[JsonDecoder[LineageDataDocument]]
            .fromJsonAST(values.head) match
            case Right(document) => Right(document)
            case Left(reason) =>
              Left(StoreError.Corrupt(path, corruptDetail(reason, text)))
        case Right(_) =>
          Left(
            StoreError.Corrupt(
              path,
              corruptDetail(
                "expected exactly one lineage-data JSON value",
                text
              )
            )
          )
        case Left(reason) =>
          Left(StoreError.Corrupt(path, corruptDetail(reason, text)))
    catch
      case _: NoSuchFileException => Left(StoreError.Missing(path))
      case error: IOException =>
        Left(StoreError.Io(path, String.valueOf(error.getMessage)))

  private def corruptDetail(reason: String, text: String): String =
    s"$reason | raw prefix: ${rawPrefix(text)}"

  private def rawPrefix(text: String): String =
    val head = text.take(PrefixMax).map {
      case c if c >= ' ' && c != '\u007f' => c
      case _                              => '?'
    }
    if text.length > PrefixMax then s"$head...(truncated)" else head

  // Best-effort cleanup: a failed secondary delete must never mask the
  // primary result (the tmp file was already moved on success).
  private def deleteTempQuietly(tmp: Path): Unit =
    try
      Files.deleteIfExists(tmp)
      ()
    catch case _: IOException => ()

end LineageDataStore
