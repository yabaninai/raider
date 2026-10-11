package raider.cli.lineage

import scala.jdk.CollectionConverters.*

import java.io.IOException
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.Arrays

/** Typed failure of the LIN-06C workspace isolation slice (packet L52, ADR-027
  * §2 «live re-run: изолированная копия workspace, бюджет, diff-отчёт»). Closed
  * set on purpose, mirroring the [[LineageDataStore]] error discipline:
  *   - [[IsolationError.OriginalMissing]] — the workspace path does not exist
  *     (or is not a directory) at the moment of the call;
  *   - [[IsolationError.CopyFailed]] — an environment-level IO failure while
  *     staging or moving the isolated copy; `detail` carries the failing
  *     operation and the OS message, never file contents;
  *   - [[IsolationError.Tampered]] — `verifyUntouched` found an original file
  *     whose bytes changed, that disappeared, or that appeared after the
  *     snapshot; `file` is the workspace-RELATIVE path (workspace-scoped
  *     identity — no absolute environment paths leak into diagnostics).
  */
enum IsolationError:
  case OriginalMissing(path: Path)
  case CopyFailed(detail: String)
  case Tampered(file: Path)

/** Workspace isolation mechanics for the live re-run (LIN-06C, packet L52,
  * ADR-027 §2): a recorded campaign is replayed against a THROWAWAY COPY of the
  * workspace while the original stays byte-for-byte untouched. Pure file
  * mechanics only — no model, no network, no replay policy here (the harness
  * and the diff report live in [[ReplayHarness]]/[[RunDiffReport]]).
  *
  * Copy contract ([[WorkspaceIsolation.isolate]]): the tree is copied into a
  * unique staging directory under `parent` and then moved onto the unique
  * result target with `ATOMIC_MOVE` (same filesystem — a `rename(2)`), so a
  * concurrent observer of `parent` never sees a half-copied workspace. The
  * atomic move orders VISIBILITY, not durability: no `fsync` is performed (same
  * no-fsync honesty as [[LineageDataStore]]/JobJournal). Plain regular-file
  * trees only — symlink and special (non-regular, non-directory) entries are
  * REFUSED with [[IsolationError.CopyFailed]] rather than copied, so the copy
  * can never silently diverge from the original.
  *
  * Snapshot contract ([[WorkspaceIsolation.snapshot]]): a byte snapshot of
  * every regular file, keyed by workspace-RELATIVE [[Path]] (the original and
  * its isolated copy therefore share one key space). BOUNDED by file count: at
  * most [[WorkspaceIsolation.SnapshotMaxFiles]] regular files; exceeding the
  * bound, a missing/non-directory workspace, and symlink/special entries fail
  * fast with `IllegalStateException` — the signature has no error channel by
  * card contract, the bound is a resource guard for fixture-sized workspaces,
  * and a silently TRUNCATED snapshot would fake a clean `verifyUntouched`
  * verdict, which is exactly what it must never do.
  *
  * Verify contract ([[WorkspaceIsolation.verifyUntouched]]): re-reads every
  * snapshot file from the original and byte-compares ([[Arrays.equals]]) — a
  * modified or deleted file is `Tampered`; then lists the tree once more and
  * flags the first ADDED non-directory entry as `Tampered` too (added empty
  * directories are ignored — the card's guarantee is over file BYTES). The
  * first offender in deterministic order (sorted relative paths) wins.
  * Environment IO failures beyond the three typed cases (e.g. an unreadable
  * file) propagate — they are never masked as "untouched".
  */
object WorkspaceIsolation:

  /** File-count bound of [[snapshot]] (documented per the LIN-06C card). */
  val SnapshotMaxFiles = 10000

  private val StagePrefix = "raider-isolate-stage-"
  private val ResultPrefix = "raider-isolated-"

  /** A full recursive copy of `original` in a unique directory under `parent`
    * (see the object scaladoc for the atomicity/link honesty). The caller owns
    * the result's lifecycle — the live re-run deletes it after the diff.
    */
  def isolate(original: Path, parent: Path): Either[IsolationError, Path] =
    if !Files.isDirectory(original) then
      Left(IsolationError.OriginalMissing(original))
    else
      try
        Files.createDirectories(parent)
        val stage = Files.createTempDirectory(parent, StagePrefix)
        try
          copyTree(original, stage)
          val target = reserveUniqueTarget(parent)
          try
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
            Right(target)
          catch
            case error: IOException =>
              deleteRecursivelyQuietly(stage)
              Left(
                IsolationError.CopyFailed(
                  s"atomic move failed: ${messageOf(error)}"
                )
              )
        catch
          case error: IOException =>
            deleteRecursivelyQuietly(stage)
            Left(IsolationError.CopyFailed(s"copy failed: ${messageOf(error)}"))
      catch
        case error: IOException =>
          Left(
            IsolationError.CopyFailed(s"staging failed: ${messageOf(error)}")
          )

  /** Byte snapshot of every regular file under `original`, keyed by the
    * workspace-relative path, in deterministic order (see the object scaladoc
    * for the bound/refusal contract). The arrays are fresh reads — treat them
    * as immutable.
    */
  def snapshot(
      original: Path,
      maxFiles: Int = SnapshotMaxFiles
  ): Map[Path, Array[Byte]] =
    if !Files.isDirectory(original) then
      throw new IllegalStateException(
        s"snapshot: workspace is not a directory: $original"
      )
    val entries = walkEntries(original)
    entries
      .find(Files.isSymbolicLink(_))
      .foreach(p => failSnapshot(original.relativize(p), "symlink entry"))
    entries
      .find(p => !Files.isDirectory(p) && !Files.isRegularFile(p))
      .foreach(p => failSnapshot(original.relativize(p), "special entry"))
    val files = entries.filter(Files.isRegularFile(_)).map(original.relativize)
    if files.size > maxFiles then
      throw new IllegalStateException(
        s"snapshot bound exceeded: ${files.size} regular files > maxFiles=$maxFiles " +
          s"(WorkspaceIsolation.SnapshotMaxFiles=$SnapshotMaxFiles)"
      )
    files
      .sortBy(_.toString)
      .map { rel =>
        rel -> Files.readAllBytes(original.resolve(rel))
      }
      .toMap

  /** Byte-verifies the original against a pre-run [[snapshot]] (see the object
    * scaladoc for the modified/deleted/added semantics and offender order).
    */
  def verifyUntouched(
      original: Path,
      snapshot: Map[Path, Array[Byte]]
  ): Either[IsolationError, Unit] =
    println(
      s"DEBUG-verify original=$original isDirectory=${Files.isDirectory(original)} " +
        s"exists=${Files.exists(original)} cwd=${new java.io.File(".").getAbsolutePath}"
    )
    if !Files.isDirectory(original) then
      Left(IsolationError.OriginalMissing(original))
    else
      firstModifiedOrDeleted(original, snapshot)
        .orElse(firstAdded(original, snapshot.keySet))
        .toLeft(())

  // Copying: entries sorted by relative path — a parent path is a strict
  // string prefix of all its descendants, so directories are always created
  // before their children regardless of the platform walk order.
  private def copyTree(source: Path, targetDir: Path): Unit =
    walkEntries(source).foreach { entry =>
      if Files.isSymbolicLink(entry) then
        throw new IOException(
          s"refusing symlink entry: ${source.relativize(entry)}"
        )
      val rel = source.relativize(entry)
      val dst = targetDir.resolve(rel)
      if Files.isDirectory(entry) then Files.createDirectories(dst)
      else if Files.isRegularFile(entry) then
        Files.copy(entry, dst, StandardCopyOption.REPLACE_EXISTING)
      else throw new IOException(s"refusing special entry: $rel")
    }

  /** All entries below `root` (the root itself excluded), sorted so parents
    * precede children. The stream is always closed; the walk does NOT follow
    * symlinks (default `Files.walk` semantics).
    */
  private def walkEntries(root: Path): Vector[Path] =
    val stream = Files.walk(root)
    try
      stream
        .iterator()
        .asScala
        .toVector
        .filter(_ != root)
        .sortBy(p => root.relativize(p).toString)
    finally stream.close()

  /** Unique result target: a fresh unique name reserved via the temp-dir API
    * and freed for the atomic move. The reserve-then-move naming gap is the
    * same single-writer assumption as the tmp+move in [[LineageDataStore]].
    */
  private def reserveUniqueTarget(parent: Path): Path =
    val candidate = Files.createTempDirectory(parent, ResultPrefix)
    Files.delete(candidate)
    candidate

  private def firstModifiedOrDeleted(
      original: Path,
      snapshot: Map[Path, Array[Byte]]
  ): Option[IsolationError.Tampered] =
    val tamperedOf: Path => Option[IsolationError.Tampered] = rel =>
      val current = original.resolve(rel)
      if !Files.isRegularFile(current) then
        Some(IsolationError.Tampered(rel)) // deleted or replaced
      else if Arrays.equals(Files.readAllBytes(current), snapshot(rel)) then
        None
      else Some(IsolationError.Tampered(rel))
    snapshot.keys.toVector
      .sortBy(_.toString)
      .iterator
      .map(tamperedOf)
      .collectFirst { case Some(tampered) => tampered }

  private def firstAdded(
      original: Path,
      snapshotKeys: Set[Path]
  ): Option[IsolationError.Tampered] =
    walkEntries(original).iterator
      // real directories only: a symlink-to-dir is an ADDED entry, not a dir
      .filterNot(p => !Files.isSymbolicLink(p) && Files.isDirectory(p))
      .map(original.relativize)
      .find(rel => !snapshotKeys.contains(rel))
      .map(IsolationError.Tampered(_))

  private def failSnapshot(rel: Path, kind: String): Nothing =
    throw new IllegalStateException(
      s"snapshot: $kind is not supported by the LIN-06C plain-tree contract: $rel"
    )

  private def deleteRecursivelyQuietly(root: Path): Unit =
    try
      val stream = Files.walk(root)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
        ()
      finally stream.close()
    catch case _: IOException => ()

  private def messageOf(error: IOException): String =
    String.valueOf(error.getMessage)

end WorkspaceIsolation
