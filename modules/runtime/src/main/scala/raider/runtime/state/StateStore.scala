package raider.runtime.state

import raider.core.{JobId, RootId}
import zio.json.{
  DecoderOps,
  DeriveJsonDecoder,
  DeriveJsonEncoder,
  EncoderOps,
  JsonDecoder,
  JsonEncoder
}
import zio.stm.{STM, TRef, TSemaphore}
import zio.{IO, UIO, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable

/** Optional agent state registry SPI (m1.md RAI-076, roadmap-12k P5.A): a
  * bounded String→String kv store with per-scope quotas, per-key monotonic
  * revisions and CAS writes (`putIfRevision`).
  *
  * Semantics:
  *   - Scopes ([[Scope]]) are distinct namespaces: `Agent(jobId)` private to
  *     one agent, `Root(rootId)` shared by a run's jobs, `Global` explicit only
  *     (the API never resolves a default scope).
  *   - Revisions are per-key and monotonic while the key exists: the first
  *     write assigns 1, every committed mutation assigns previous + 1. CAS:
  *     `putIfRevision(scope, key, expectedRev, value)` commits ONLY when the
  *     key's actual revision equals `expectedRev`; `None` means the key must
  *     NOT exist (create). The whole check-and-write is ONE STM transaction, so
  *     concurrent CAS racers produce exactly one winner and the losers get a
  *     typed [[StateError.RevisionConflict]] carrying the actual revision.
  *   - Quotas ([[Quotas]]) are constructor bounds per scope: `maxKeys`,
  *     `maxValueLen` and `maxTotalBytes` (both size bounds measured in UTF-8
  *     bytes). A write exceeding any bound fails typed
  *     [[StateError.QuotaExceeded]] BEFORE any state changes (whole-write, no
  *     partial application).
  *   - DEFAULT-OFF (optionality is law, roadmap P5 exit): there is no global
  *     registry, no background fiber and no file IO anywhere unless a caller
  *     explicitly constructs a store via [[StateStore.memory]] /
  *     [[StateStore.file]]. Not constructing one is observationally identical
  *     to the feature not existing.
  *
  * Durability honesty (roadmap P5 exit): the file backend persists every
  * committed mutation as one JSON document per key under
  * `<base>/{agent|root|global}/[<scopeId>/]<key>.json` via tmp-file +
  * `ATOMIC_MOVE`. Atomic rename is NOT claimed to survive power loss without an
  * fsync contract (none is implemented in this slice). The backend is an
  * in-process single-writer store: cross-process concurrent writers on one base
  * directory are out of scope. A document that cannot be loaded fails
  * construction with [[StateError.Corrupt]] and its bytes are never rewritten
  * or deleted by the store (no silent recreation). If a post-commit file write
  * fails, [[StateError.Io]] reports that memory may be ahead of disk.
  */
trait StateStore:

  /** Current value of `key` in `scope`; `None` when absent. Malformed scope
    * identity or key → [[StateError.ScopeViolation]].
    */
  def get(scope: Scope, key: String): IO[StateError, Option[String]]

  /** Current revision of `key` in `scope`; `None` when absent — the read-half
    * of the CAS discipline.
    */
  def revision(scope: Scope, key: String): IO[StateError, Option[Long]]

  /** Keys present in `scope`, sorted. */
  def keys(scope: Scope): IO[StateError, List[String]]

  /** Unconditional write (last committer wins): assigns the next monotonic
    * revision of `key` and returns it. Quotas are enforced like CAS writes.
    */
  def put(scope: Scope, key: String, value: String): IO[StateError, Long]

  /** Compare-and-swap write: commits only when the key's actual revision equals
    * `expectedRev` (`None` = key must not exist). Returns the new revision on
    * success; on mismatch fails [[StateError.RevisionConflict]] with the actual
    * revision.
    */
  def putIfRevision(
      scope: Scope,
      key: String,
      expectedRev: Option[Long],
      value: String
  ): IO[StateError, Long]

  /** Removes `key` from `scope`; `true` when a value was removed. The file
    * backend also removes the document.
    */
  def delete(scope: Scope, key: String): IO[StateError, Boolean]

end StateStore

object StateStore:

  /** Hard bounds of one scope. All three must be positive (a non-positive value
    * at construction is a programming error — see [[checkQuotas]]).
    */
  final case class Limits(maxKeys: Int, maxValueLen: Int, maxTotalBytes: Long)

  object Limits:

    /** Defaults per roadmap-12k P5: ≤64 KiB per value, 1024 keys, 1 MiB total
      * per scope.
      */
    val default: Limits =
      Limits(maxKeys = 1024, maxValueLen = 65536, maxTotalBytes = 1048576L)

  /** Per-scope quotas ([[StateStore.file]] / [[StateStore.memory]] are
    * parameterized by these; nothing else tunes them).
    */
  final case class Quotas(agent: Limits, root: Limits, global: Limits)

  object Quotas:
    val default: Quotas = Quotas(Limits.default, Limits.default, Limits.default)

  /** In-memory store (tests and in-process use). Pure construction: no file IO,
    * no background work.
    */
  def memory(quotas: Quotas = Quotas.default): UIO[StateStore] =
    checkQuotas(quotas)
    TRef
      .make(Map.empty[ScopeKey, Entry])
      .commit
      .map(state => MemoryStore(state, quotas))

  /** File-backed store: loads existing documents under `base` or fails typed
    * [[StateError.Corrupt]] — a corrupt document is never recreated or removed.
    * A missing `base` is NOT an error (first use creates it lazily on the first
    * write); a `base` that exists but is not a directory IS
    * [[StateError.Corrupt]].
    */
  def file(
      path: Path,
      quotas: Quotas = Quotas.default
  ): IO[StateError, StateStore] =
    checkQuotas(quotas)
    for
      loaded <- loadAll(path)
      state <- TRef.make(loaded).commit
      lock <- TSemaphore.make(1L).commit
    yield FileStore(path, state, quotas, lock)

  // ------------------------------------------------------------------
  // Internals (single file, single package slice)
  // ------------------------------------------------------------------

  private def checkQuotas(q: Quotas): Unit =
    def positive(l: Limits): Boolean =
      l.maxKeys > 0 && l.maxValueLen > 0 && l.maxTotalBytes > 0
    if !(positive(q.agent) && positive(q.root) && positive(q.global)) then
      throw new IllegalArgumentException(
        s"all state quotas must be positive, got $q — a programming error " +
          "at construction time, not a runtime condition"
      )

  private val KeyMax = 64
  private val IdMax = 128
  private val JsonSuffix = ".json"
  private val DocVersion = 1

  private def bytesOf(s: String): Int =
    s.getBytes(StandardCharsets.UTF_8).length

  /** Safe single path segment (Session.sanitize discipline): non-empty, bounded
    * length, no leading dot, `[A-Za-z0-9._-]` only — traversal and separators
    * cannot slip through.
    */
  private def checkSegment(s: String, max: Int, what: String): Option[String] =
    if s.isEmpty then Some(s"empty $what")
    else if s.length > max then Some(s"$what exceeds $max chars")
    else if s.startsWith(".") then Some(s"$what must not start with a dot")
    else if !s.forall(c =>
        c.isLetterOrDigit || c == '.' || c == '_' || c == '-'
      )
    then Some(s"$what must contain only [A-Za-z0-9._-]")
    else None

  private def checkScope(scope: Scope): Option[StateError] = scope match
    case Scope.Agent(j) =>
      checkSegment(j.value, IdMax, "agent jobId")
        .map(StateError.ScopeViolation(scope, _))
    case Scope.Root(r) =>
      checkSegment(r.value, IdMax, "root rootId")
        .map(StateError.ScopeViolation(scope, _))
    case Scope.Global => None

  private def checkKey(scope: Scope, key: String): Option[StateError] =
    checkSegment(key, KeyMax, "key").map(StateError.ScopeViolation(scope, _))

  private def loadAll(base: Path): IO[StateError, Map[ScopeKey, Entry]] =
    ZIO
      .attempt(scanBase(base))
      .refineOrDie { case e: java.io.IOException =>
        StateError.Io(base, String.valueOf(e.getMessage))
      }
      .absolve

  /** Synchronous scan; throws ONLY `IOException`s (surfaced as
    * [[StateError.Io]]), content-level distrust is returned as
    * [[StateError.Corrupt]].
    */
  private def scanBase(base: Path): Either[StateError, Map[ScopeKey, Entry]] =
    val acc = mutable.Map.empty[ScopeKey, Entry]
    if Files.exists(base) && !Files.isDirectory(base) then
      Left(StateError.Corrupt(base, "state base path is not a directory"))
    else
      val errors = List("agent", "root", "global").flatMap { dirName =>
        val classDir = base.resolve(dirName)
        if !Files.isDirectory(classDir) then Nil
        else
          dirName match
            case "global" => scanKeyFiles(classDir, Scope.Global, acc)
            case _ =>
              listSubdirectories(classDir).flatMap { dir =>
                val id = dir.getFileName.toString
                val scope =
                  if dirName == "agent" then Scope.Agent(JobId(id))
                  else Scope.Root(RootId(id))
                scanKeyFiles(dir, scope, acc)
              }
      }
      errors match
        case first :: _ => Left(first)
        case Nil        => Right(acc.toMap)

  private def listSubdirectories(dir: Path): List[Path] =
    val stream = Files.list(dir)
    try
      import scala.jdk.CollectionConverters.*
      stream.iterator().asScala.filter(Files.isDirectory(_)).toList
    finally stream.close()

  /** Only `*.json` documents are store state; a leftover `<key>.json.tmp` from
    * an interrupted write is ignored (atomic replace means it never carried a
    * committed value).
    */
  private def scanKeyFiles(
      dir: Path,
      scope: Scope,
      acc: mutable.Map[ScopeKey, Entry]
  ): List[StateError] =
    val stream = Files.list(dir)
    try
      import scala.jdk.CollectionConverters.*
      stream
        .iterator()
        .asScala
        .filter(p => p.getFileName.toString.endsWith(JsonSuffix))
        .toList
        .flatMap(loadDocument(scope, _, acc))
    finally stream.close()

  private def loadDocument(
      scope: Scope,
      path: Path,
      acc: mutable.Map[ScopeKey, Entry]
  ): List[StateError] =
    val text = Files.readString(path, StandardCharsets.UTF_8)
    text.fromJson[StateDoc] match
      case Left(err) =>
        List(
          StateError.Corrupt(
            path,
            s"unreadable state document (${err.take(140)})"
          )
        )
      case Right(doc) =>
        val name = path.getFileName.toString.stripSuffix(JsonSuffix)
        if doc.v != DocVersion then
          List(
            StateError.Corrupt(path, s"unsupported document version ${doc.v}")
          )
        else if doc.key != name then
          List(
            StateError.Corrupt(
              path,
              s"document key '${doc.key}' does not match file name '$name'"
            )
          )
        else if doc.rev < 1L then
          List(StateError.Corrupt(path, s"non-monotonic revision ${doc.rev}"))
        else
          acc.update(ScopeKey(scope, doc.key), Entry(doc.value, doc.rev))
          Nil

  /** Persisted document (one per key). `v` guards format drift. */
  private final case class StateDoc(
      v: Int,
      key: String,
      rev: Long,
      value: String
  )

  private object StateDoc:
    given JsonEncoder[StateDoc] = DeriveJsonEncoder.gen[StateDoc]
    given JsonDecoder[StateDoc] = DeriveJsonDecoder.gen[StateDoc]

  private final case class Entry(value: String, revision: Long)

  private final case class ScopeKey(scope: Scope, key: String)

  private sealed trait Mutation extends Product with Serializable

  private object Mutation:

    final case class Put(key: ScopeKey, value: String, revision: Long)
        extends Mutation

    final case class Delete(key: ScopeKey) extends Mutation

  private final case class WriteResult(revision: Long, mutation: Mutation)

  /** Shared read/CAS machinery. All mutating operations run through
    * [[runAndPersist]] so the file backend can hold its single-writer lock
    * across BOTH the STM commit and the document write — the persisted order of
    * documents always equals the commit order (in-process).
    */
  private abstract class BaseStore(
      state: TRef[Map[ScopeKey, Entry]],
      quotas: Quotas
  ) extends StateStore:

    protected def limitsOf(scope: Scope): Limits = scope match
      case Scope.Agent(_) => quotas.agent
      case Scope.Root(_)  => quotas.root
      case Scope.Global   => quotas.global

    protected def runAndPersist[A](
        txn: STM[StateError, A]
    )(post: A => IO[StateError, Unit]): IO[StateError, A] =
      STM.atomically(txn).flatMap(a => post(a).as(a))

    protected def afterCommit(mutation: Mutation): IO[StateError, Unit] =
      ZIO.unit

    private def validate(scope: Scope, key: String): IO[StateError, Unit] =
      ZIO.fromEither(
        checkScope(scope)
          .toLeft(())
          .flatMap(_ => checkKey(scope, key).toLeft(()))
      )

    final def get(scope: Scope, key: String): IO[StateError, Option[String]] =
      validate(scope, key) *>
        STM
          .atomically(state.get)
          .map(
            _.get(ScopeKey(scope, key)).map(_.value)
          )

    final def revision(
        scope: Scope,
        key: String
    ): IO[StateError, Option[Long]] =
      validate(scope, key) *>
        STM
          .atomically(state.get)
          .map(
            _.get(ScopeKey(scope, key)).map(_.revision)
          )

    final def keys(scope: Scope): IO[StateError, List[String]] =
      ZIO.fromEither(checkScope(scope).toLeft(())) *>
        STM.atomically(state.get).map { m =>
          m.iterator
            .collect { case (k, _) if k.scope == scope => k.key }
            .toList
            .sorted
        }

    final def put(
        scope: Scope,
        key: String,
        value: String
    ): IO[StateError, Long] =
      write(scope, key, value, cas = None)

    final def putIfRevision(
        scope: Scope,
        key: String,
        expectedRev: Option[Long],
        value: String
    ): IO[StateError, Long] =
      write(scope, key, value, cas = Some(expectedRev))

    final def delete(scope: Scope, key: String): IO[StateError, Boolean] =
      validate(scope, key).flatMap { _ =>
        runAndPersist(deleteTxn(ScopeKey(scope, key))) {
          case Some(r) => afterCommit(r.mutation)
          case None    => ZIO.unit
        }.map(_.isDefined)
      }

    private def write(
        scope: Scope,
        key: String,
        value: String,
        cas: Option[Option[Long]]
    ): IO[StateError, Long] =
      validate(scope, key).flatMap { _ =>
        val skey = ScopeKey(scope, key)
        runAndPersist(writeTxn(skey, scope, key, value, cas))(r =>
          afterCommit(r.mutation)
        ).map(_.revision)
      }

    /** ONE transaction: CAS check, quota enforcement and the write — a loser
      * changes nothing at all (no partial application).
      */
    private def writeTxn(
        skey: ScopeKey,
        scope: Scope,
        key: String,
        value: String,
        cas: Option[Option[Long]]
    ): STM[StateError, WriteResult] =
      state.get.flatMap { m =>
        val existing = m.get(skey)
        val actual = existing.map(_.revision)
        val casHolds = cas.fold(true)(expected => actual == expected)
        if !casHolds then STM.fail(StateError.RevisionConflict(key, actual))
        else
          val limits = limitsOf(scope)
          val valueBytes = bytesOf(value)
          val oldBytes = existing.fold(0L)(e => bytesOf(e.value).toLong)
          val totalBytes = m.iterator.foldLeft(0L) { case (acc, (k, e)) =>
            if k.scope == scope then acc + bytesOf(e.value).toLong else acc
          } - oldBytes + valueBytes
          val scopeKeyCount = m.count { case (k, _) => k.scope == scope }
          if valueBytes > limits.maxValueLen then
            STM.fail(
              StateError.QuotaExceeded(
                scope,
                s"maxValueLen=${limits.maxValueLen} bytes"
              )
            )
          else if existing.isEmpty && scopeKeyCount >= limits.maxKeys then
            STM.fail(
              StateError.QuotaExceeded(scope, s"maxKeys=${limits.maxKeys}")
            )
          else if totalBytes > limits.maxTotalBytes then
            STM.fail(
              StateError.QuotaExceeded(
                scope,
                s"maxTotalBytes=${limits.maxTotalBytes} bytes"
              )
            )
          else
            val newRev = existing.fold(1L)(_.revision + 1L)
            state
              .set(m.updated(skey, Entry(value, newRev)))
              .as(WriteResult(newRev, Mutation.Put(skey, value, newRev)))
      }

    private def deleteTxn(
        skey: ScopeKey
    ): STM[StateError, Option[WriteResult]] =
      state.get.flatMap { m =>
        if m.contains(skey) then
          val rev = m(skey).revision
          state
            .set(m.removed(skey))
            .as(Some(WriteResult(rev, Mutation.Delete(skey))))
        else STM.succeed(None)
      }

  end BaseStore

  private final class MemoryStore(
      state: TRef[Map[ScopeKey, Entry]],
      quotas: Quotas
  ) extends BaseStore(state, quotas)

  private final class FileStore(
      base: Path,
      state: TRef[Map[ScopeKey, Entry]],
      quotas: Quotas,
      lock: TSemaphore
  ) extends BaseStore(state, quotas):

    override protected def runAndPersist[A](
        txn: STM[StateError, A]
    )(post: A => IO[StateError, Unit]): IO[StateError, A] =
      lock.withPermit {
        STM.atomically(txn).flatMap(a => post(a).as(a))
      }

    override protected def afterCommit(
        mutation: Mutation
    ): IO[StateError, Unit] =
      mutation match
        case Mutation.Put(key, value, revision) =>
          writeAtomically(
            docPath(key),
            StateDoc(DocVersion, key.key, revision, value)
          )
        case Mutation.Delete(key) => removeQuietly(docPath(key))

    private def docPath(key: ScopeKey): Path = key.scope match
      case Scope.Agent(j) =>
        base.resolve("agent").resolve(j.value).resolve(key.key + JsonSuffix)
      case Scope.Root(r) =>
        base.resolve("root").resolve(r.value).resolve(key.key + JsonSuffix)
      case Scope.Global =>
        base.resolve("global").resolve(key.key + JsonSuffix)

    /** tmp-file + ATOMIC_MOVE (SessionStore pattern): a reader sees either the
      * old or the new document, never a half-written one.
      */
    private def writeAtomically(
        target: Path,
        doc: StateDoc
    ): IO[StateError, Unit] =
      ZIO
        .attempt {
          Files.createDirectories(target.getParent)
          val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
          Files.writeString(tmp, doc.toJson, StandardCharsets.UTF_8)
          Files.move(
            tmp,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
          ()
        }
        .refineOrDie { case e: java.io.IOException =>
          StateError.Io(target, String.valueOf(e.getMessage))
        }

    private def removeQuietly(target: Path): IO[StateError, Unit] =
      ZIO
        .attempt(Files.deleteIfExists(target))
        .refineOrDie { case e: java.io.IOException =>
          StateError.Io(target, String.valueOf(e.getMessage))
        }
        .unit

  end FileStore

end StateStore
