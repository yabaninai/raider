package raider.cli.lineage

import zio.Chunk
import zio.json.{
  DecoderOps,
  DeriveJsonDecoder,
  DeriveJsonEncoder,
  EncoderOps,
  JsonDecoder,
  JsonEncoder
}
import zio.json.ast.Json

import raider.runtime.lineage.io.TruncatedTail

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** The pinned-environment SUMMARY a persisted catalog record carries (LIN-12,
  * docs/lineage.md §5+§6): the same fields as `toolchain.json` (LIN-08) —
  * toolchain (Scala/JDK), Raider version, model — projected from either
  * environment source of [[EnvPinning]]. Plain data, one record shape for chat
  * and headless runs (chat==headless, LIN8-01): the summary says WHICH
  * environment produced the record's verdicts, it never invents one (`model`
  * stays absent where no source knows it).
  */
final case class PinnedSummary(
    scalaVersion: String,
    jdkVersion: String,
    raiderVersion: String,
    model: Option[String]
)

object PinnedSummary:
  given JsonDecoder[PinnedSummary] = DeriveJsonDecoder.gen[PinnedSummary]
  given JsonEncoder[PinnedSummary] = DeriveJsonEncoder.gen[PinnedSummary]

  /** The summary of the environment the cataloging process runs in (what
    * [[EnvPinning.currentEnv]] sees — the honest witness of the verdicts).
    */
  def of(current: EnvPinning.CurrentEnv): PinnedSummary =
    PinnedSummary(
      current.scalaVersion,
      current.jdkVersion,
      current.raiderVersion,
      current.model
    )

  /** The summary of a pinned environment read from a `toolchain.json`. */
  def of(pinned: EnvPinning.PinnedEnv): PinnedSummary =
    PinnedSummary(
      pinned.scalaVersion,
      pinned.jdkVersion,
      pinned.raiderVersion,
      pinned.model
    )

end PinnedSummary

/** One PERSISTED record of the campaign-catalog (LIN-12, ADR-027 §5): the
  * scan-record identity the card fixes — run-dir, graph-hash, the verifyPartial
  * verdict (complete/tail/nodeCount), the pinned-env summary — plus the
  * identity fields of [[CatalogEntry]] (rootId/sessionId, mtime) so a reload
  * restores the FULL entry. chat==headless: ONE shape, no mode discriminator —
  * a consumer without a session id gets an honest absence, never an invented
  * value.
  *
  * @param path
  *   the run directory as the scan saw it (the merge/upsert KEY — two records
  *   for one run dir collapse into the last complete write).
  * @param tail
  *   the truncated-tail summary (line number + raw prefix length) for a partial
  *   entry, absent for a complete one.
  * @param pinnedEnv
  *   the environment summary of the cataloging process that produced the
  *   verdicts; absent when the caller has none.
  */
final case class CatalogRecord(
    path: String,
    rootId: String,
    sessionId: Option[String],
    nodeCount: Int,
    hash: String,
    complete: Boolean,
    tail: Option[CatalogRecord.TailInfo],
    scannedAtMs: Long,
    pinnedEnv: Option[PinnedSummary]
):

  /** Restores the FULL [[CatalogEntry]] the record was persisted from — the
    * roundtrip is identity by construction (LINC-01): every field is carried,
    * nothing is re-derived from the filesystem or the clock.
    */
  def toEntry: CatalogEntry =
    CatalogEntry(
      path = Path.of(path),
      rootId = rootId,
      sessionId = sessionId,
      nodeCount = nodeCount,
      hash = hash,
      complete = complete,
      tailReported = tail.map(t => TruncatedTail(t.lineNo, t.rawPrefixLen)),
      scannedAtMs = scannedAtMs
    )

object CatalogRecord:

  /** The truncated-tail summary (LIN8-B-02 data, never an exception). */
  final case class TailInfo(lineNo: Int, rawPrefixLen: Int)

  object TailInfo:
    given JsonDecoder[TailInfo] = DeriveJsonDecoder.gen[TailInfo]
    given JsonEncoder[TailInfo] = DeriveJsonEncoder.gen[TailInfo]

  given JsonDecoder[CatalogRecord] = DeriveJsonDecoder.gen[CatalogRecord]
  given JsonEncoder[CatalogRecord] = DeriveJsonEncoder.gen[CatalogRecord]
end CatalogRecord

/** Typed failures of the persisted-catalog file mechanics (LIN-12) — the SAME
  * taxonomy discipline as the frozen `LineageDataStore` (`Missing`/`Corrupt`/
  * `Io`), extended with the record identification LINC-02 requires:
  *   - [[CatalogStoreError.Missing]] — the requested catalog file does not
  *     exist (a new campaign — not an error of content);
  *   - [[CatalogStoreError.Corrupt]] — the file exists but is not a healthy
  *     catalog document. `record` NAMES the offending entry: `#<index>` for a
  *     damaged record (with its `path` member when one is readable) or
  *     [[CatalogStore.DocumentLevel]] for document-level damage. The detail
  *     carries the decode reason plus a bounded raw prefix of the file's OWN
  *     bytes; nothing is silently skipped and NOTHING is auto-repaired;
  *   - [[CatalogStoreError.Io]] — environment-level IO failure (create/
  *     write/move/read).
  */
enum CatalogStoreError:
  case Missing(path: Path)
  case Corrupt(path: Path, record: String, detail: String)
  case Io(path: Path, detail: String)

/** Typed failure of [[CatalogStore.refresh]]: the scan layer and the store
  * layer report SEPARATELY — a damaged tree is a different fact than a damaged
  * persisted catalog, and neither is flattened into the other (LINC-02).
  */
enum RefreshError:
  case Scan(error: CatalogError)
  case Store(error: CatalogStoreError)

/** Persistence of the lineage-tree catalog (LIN-12, docs/tasks/lineage.md,
  * docs/lineage.md §5, ADR-027 §5): the durable file mechanics scan records are
  * published through. Write/read only — no reduction, no replay policy, no CLI
  * surface lives here (the catalog stays readable by the replay CLI OVER the
  * persisted file: this store touches ONLY the catalog file, never a run
  * directory).
  *
  * Atomicity: every write serializes the WHOLE catalog document (frozen
  * canonical field order — every write of the same records is byte-identical),
  * lands the bytes in a UNIQUE tmp file created in the target directory, then
  * ATOMIC_MOVEs it onto the target. A reader always sees the previous or the
  * new whole document, never a torn one (LINC-03).
  *
  * Append model (the deliberate difference from `LineageDataStore.write`): an
  * append is a read-modify-write — upsert records by run-dir key over the
  * current file content. Refusing an overlapping writer (the data store's
  * last-writer-wins contract) would LOSE the loser's records, so concurrent
  * appends to one target QUEUE on a per-path in-process lock instead: each
  * append lands atomically on top of the previous, zero losses (LINC-03), and
  * the read order stays deterministic (mtime DESC, path ASC — the catalog
  * order). The lock is held only for the duration of one rewrite and kept for
  * the JVM's lifetime, so the map is bounded by the number of distinct targets
  * this process writes. Cross-process same-path writers remain out of scope, as
  * in `LineageDataStore` — the tmp+move still keeps every observed file whole
  * (last complete write wins).
  *
  * Durability honesty (no-fsync contract, same discipline as
  * `LineageDataStore`/JobJournal): the tmp write reaches the OS page cache and
  * NOTHING more — no `fsync` is performed, persistence is NOT claimed to
  * survive power loss; the atomic move orders concurrent visibility, not
  * durability.
  *
  * Content honesty (LINC-02): a damaged catalog file is a typed
  * [[CatalogStoreError.Corrupt]] naming the offending record, left untouched on
  * disk — no auto-repair, no silent skip, and [[CatalogStore.refresh]] refuses
  * to publish a fresh scan over damaged persisted state.
  */
object CatalogStore:

  /** The canonical catalog file name inside the campaigns root. The scan
    * ignores non-`work-*` entries, so the persisted catalog coexists with the
    * tree it catalogs in the SAME directory.
    */
  val FileName = "lineage-catalog.json"

  /** The `record` value of a document-level [[CatalogStoreError.Corrupt]] —
    * damage that cannot be attributed to one record (garbage, truncation, wrong
    * shape/schemaVersion).
    */
  val DocumentLevel = "(document)"

  private val SchemaVersion = 1

  private val PrefixMax = 200

  private val writeLocks = new ConcurrentHashMap[String, ReentrantLock]

  private def writeLockFor(path: Path): ReentrantLock =
    writeLocks.computeIfAbsent(
      path.toAbsolutePath.normalize.toString,
      _ => new ReentrantLock()
    )

  /** The catalog file of a campaigns root. */
  def catalogFile(runsRoot: Path): Path = runsRoot.resolve(FileName)

  /** Projects scan entries into persisted records (LIN-12 record shape):
    * run-dir, graph-hash, verifyPartial verdict, pinned-env summary — one shape
    * for chat and headless entries.
    */
  def recordsOf(
      entries: Vector[CatalogEntry],
      pinned: Option[PinnedSummary]
  ): Vector[CatalogRecord] =
    entries.map { entry =>
      CatalogRecord(
        path = entry.path.toString,
        rootId = entry.rootId,
        sessionId = entry.sessionId,
        nodeCount = entry.nodeCount,
        hash = entry.hash,
        complete = entry.complete,
        tail = entry.tailReported.map(t =>
          CatalogRecord.TailInfo(t.lineNo, t.rawPrefixLen)
        ),
        scannedAtMs = entry.scannedAtMs,
        pinnedEnv = pinned
      )
    }

  /** Reads and decodes the catalog at `path`. Honest failure mode with NO
    * auto-repair and NO silent skip: an absent file is
    * [[CatalogStoreError.Missing]], damaged content is
    * [[CatalogStoreError.Corrupt]] with the offending record NAMED (see the
    * enum scaladoc) and a bounded raw prefix of the file's own bytes, and
    * environment IO failures are [[CatalogStoreError.Io]]. The strict
    * single-value decode follows `LineageDataStore.read` (the one-element array
    * trick — a valid-looking prefix never masks a damaged tail).
    */
  def load(path: Path): Either[CatalogStoreError, Vector[CatalogRecord]] =
    try
      val text = Files.readString(path, StandardCharsets.UTF_8)
      decodeDocument(path, text)
    catch
      case _: NoSuchFileException => Left(CatalogStoreError.Missing(path))
      case error: IOException =>
        Left(CatalogStoreError.Io(path, String.valueOf(error.getMessage)))

  /** Appends `records` to the catalog at `file` — an atomic UPSERT keyed by the
    * record's run-dir path: a record for an already-cataloged run REPLACES the
    * stored one (the last complete write wins, whole, never mixed), new
    * run-dirs are added; the file is left whole at every instant and concurrent
    * appends queue on the per-target lock (see the object scaladoc — zero
    * losses, deterministic read order, LINC-03). Damaged persisted state
    * refuses the append TYPED and is left untouched (LINC-02); an empty batch
    * is a no-op that does not invent a file.
    */
  def append(
      file: Path,
      records: Vector[CatalogRecord]
  ): Either[CatalogStoreError, Unit] =
    if records.isEmpty then Right(())
    else updateLocked(file, records).map(_ => ())

  /** The scan+persist roundtrip of one campaigns root (LIN-12): loads the
    * persisted catalog FIRST — damaged state refuses TYPED before any fresh
    * scan can be published over it (LINC-02) — then scans the tree through
    * [[LineageCatalog.scan]] (typed [[RefreshError.Scan]] on damage), stamps
    * every record with THIS process's pinned-env summary
    * ([[EnvPinning.currentEnv]]) and upsert-appends atomically
    * ([[RefreshError.Store]] on file failure). Missing persisted state is the
    * normal first-campaign case: the file is created. Reload of the result is
    * identity (LINC-01) — a repeat refresh of an unchanged tree is
    * byte-identical.
    */
  def refresh(runsRoot: Path): Either[RefreshError, Vector[CatalogRecord]] =
    val file = catalogFile(runsRoot)
    for
      // The persisted state is probed FIRST: DAMAGED CONTENT refuses the
      // whole refresh before any fresh scan can be published over it
      // (LINC-02). Missing is the normal first-campaign case (nothing to
      // protect), and an unreadable probe (the root is not a directory) is
      // left to the SCAN below, which reports the real fact typed — the
      // catalog file of an invalid campaigns root is not the finding.
      _ <- load(file) match
        case Left(CatalogStoreError.Corrupt(path, record, detail)) =>
          Left(
            RefreshError.Store(CatalogStoreError.Corrupt(path, record, detail))
          )
        case _ => Right(())
      entries <- LineageCatalog.scan(runsRoot).left.map(RefreshError.Scan(_))
      fresh = recordsOf(
        entries,
        Some(PinnedSummary.of(EnvPinning.currentEnv()))
      )
      merged <- updateLocked(file, fresh).left.map(RefreshError.Store(_))
    yield merged

  // ------------------------------------------------------------------ private

  /** The locked read-modify-write core of [[append]] and [[refresh]]: the
    * blocking per-target lock makes each append land atomically on top of the
    * previous content — queuing, not refusing (see the object scaladoc).
    */
  private def updateLocked(
      file: Path,
      incoming: Vector[CatalogRecord]
  ): Either[CatalogStoreError, Vector[CatalogRecord]] =
    val lock = writeLockFor(file)
    lock.lock()
    try
      load(file) match
        case Right(existing) =>
          val merged = merge(existing, incoming)
          write(file, merged).map(_ => merged)
        case Left(CatalogStoreError.Missing(_)) =>
          val merged = sortRecords(incoming)
          write(file, merged).map(_ => merged)
        case Left(other) => Left(other)
    finally
      lock.unlock()
      ()

  /** Upsert by the run-dir path key, then the deterministic catalog order
    * (mtime DESC, path ASC — the SAME ordering [[LineageCatalog]] renders), so
    * a reload reads records in one deterministic order regardless of the append
    * interleaving (LINC-03).
    */
  private def merge(
      existing: Vector[CatalogRecord],
      incoming: Vector[CatalogRecord]
  ): Vector[CatalogRecord] =
    val byPath =
      scala.collection.mutable.LinkedHashMap.empty[String, CatalogRecord]
    existing.foreach(r => byPath.update(r.path, r))
    incoming.foreach(r => byPath.update(r.path, r))
    sortRecords(byPath.values.toVector)

  private def sortRecords(
      records: Vector[CatalogRecord]
  ): Vector[CatalogRecord] =
    records.sortBy(r => (-r.scannedAtMs, r.path))

  /** Atomically replaces the catalog at `path` with `records` — the
    * `LineageDataStore` discipline (canonical bytes → unique tmp file →
    * ATOMIC_MOVE), honest IOException mapping, best-effort tmp cleanup.
    */
  private def write(
      path: Path,
      records: Vector[CatalogRecord]
  ): Either[CatalogStoreError, Unit] =
    try
      val dir = Option(path.toAbsolutePath.getParent).getOrElse(Path.of("."))
      Files.createDirectories(dir)
      val tmp =
        Files.createTempFile(dir, path.getFileName.toString + ".", ".tmp")
      try
        Files.writeString(tmp, documentJson(records), StandardCharsets.UTF_8)
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
        Left(CatalogStoreError.Io(path, String.valueOf(error.getMessage)))

  /** The canonical document bytes: `{"schemaVersion":1,"records":[...]}` with
    * the derived codecs' fixed field order — every write of the same records is
    * byte-identical (the repeat-refresh identity, LINC-01).
    */
  private def documentJson(records: Vector[CatalogRecord]): String =
    val elements = records.map { r =>
      // The derived encoder is total over plain data; a Left here is a codec
      // defect and propagates loud (never masked, same rule as LineageDataStore).
      summon[JsonEncoder[CatalogRecord]].toJsonAST(r) match
        case Right(json) => json
        case Left(reason) =>
          sys.error(s"catalog codec defect: $reason")
    }
    Json
      .Obj(
        Chunk(
          "schemaVersion" -> Json.Num(BigDecimal(SchemaVersion)),
          "records" -> Json.Arr(Chunk.fromIterable(elements))
        )
      )
      .toJson

  private def decodeDocument(
      path: Path,
      text: String
  ): Either[CatalogStoreError, Vector[CatalogRecord]] =
    // See LineageDataStore.read for the one-element array trick: trailing
    // garbage or a second document fails the array grammar instead of being
    // masked as a "valid prefix" success.
    ("[" + text + "]").fromJson[Json] match
      case Right(Json.Arr(values)) if values.size == 1 =>
        values.head match
          case obj: Json.Obj => decodeCatalog(path, obj, text)
          case _ =>
            Left(
              corrupt(
                path,
                DocumentLevel,
                "catalog document must be a JSON object",
                text
              )
            )
      case Right(_) =>
        Left(
          corrupt(
            path,
            DocumentLevel,
            "expected exactly one catalog JSON value",
            text
          )
        )
      case Left(reason) => Left(corrupt(path, DocumentLevel, reason, text))

  private def decodeCatalog(
      path: Path,
      obj: Json.Obj,
      text: String
  ): Either[CatalogStoreError, Vector[CatalogRecord]] =
    for
      _ <- requireSchemaVersion(path, obj, text)
      elements <- member(obj, "records") match
        case Some(Json.Arr(elements)) => Right(elements)
        case Some(_) =>
          Left(
            corrupt(path, DocumentLevel, "'records' must be a JSON array", text)
          )
        case None =>
          Left(
            corrupt(
              path,
              DocumentLevel,
              "missing required field 'records'",
              text
            )
          )
      records <- elements.zipWithIndex.foldLeft[
        Either[CatalogStoreError, Vector[CatalogRecord]]
      ](Right(Vector.empty)) { (acc, indexed) =>
        val element = indexed._1
        val index = indexed._2
        acc.flatMap(records =>
          decodeRecord(path, element, index, text).map(records :+ _)
        )
      }
    yield records

  private def requireSchemaVersion(
      path: Path,
      obj: Json.Obj,
      text: String
  ): Either[CatalogStoreError, Unit] =
    member(obj, "schemaVersion") match
      case Some(Json.Num(v))
          if BigDecimal(v).isValidInt && BigDecimal(v).toInt == SchemaVersion =>
        Right(())
      case Some(Json.Num(_)) =>
        Left(
          corrupt(
            path,
            DocumentLevel,
            s"unsupported schemaVersion (expected $SchemaVersion)",
            text
          )
        )
      case Some(_) =>
        Left(
          corrupt(path, DocumentLevel, "'schemaVersion' must be a number", text)
        )
      case None =>
        Left(
          corrupt(
            path,
            DocumentLevel,
            "missing required field 'schemaVersion'",
            text
          )
        )

  /** Decodes ONE record element; a failure is a typed Corrupt naming the record
    * (index + its `path` member when readable) — never a silent skip (LINC-02).
    */
  private def decodeRecord(
      path: Path,
      element: Json,
      index: Int,
      text: String
  ): Either[CatalogStoreError, CatalogRecord] =
    summon[JsonDecoder[CatalogRecord]].fromJsonAST(element) match
      case Right(record) => Right(record)
      case Left(reason) =>
        Left(corrupt(path, identify(element, index), reason, text))

  private def identify(element: Json, index: Int): String =
    element match
      case obj: Json.Obj =>
        member(obj, "path") match
          case Some(Json.Str(p)) => s"#$index ($p)"
          case _                 => s"#$index"
      case _ => s"#$index"

  private def member(obj: Json.Obj, key: String): Option[Json] =
    obj.fields.collectFirst { case (`key`, value) => value }

  private def corrupt(
      path: Path,
      record: String,
      reason: String,
      text: String
  ): CatalogStoreError =
    CatalogStoreError.Corrupt(
      path,
      record,
      s"$reason | raw prefix: ${rawPrefix(text)}"
    )

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

end CatalogStore
