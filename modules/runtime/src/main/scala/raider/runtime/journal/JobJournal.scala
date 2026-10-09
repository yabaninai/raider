package raider.runtime.journal

import raider.runtime.events.EventEnvelope
import raider.runtime.events.EventCodecs.given
import zio.{IO, Ref, Semaphore, UIO, ZIO}
import zio.json.{DecoderOps, EncoderOps}

import java.io.BufferedWriter
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}

/** Typed failure of the journal slice (RAI-087): only environment-level IO
  * failures are typed. Content-level distrust (a corrupt tail) is NOT an error
  * — [[JobJournal.replay]] reports it honestly as
  * [[JournalRecovery.uncertainMarker]].
  */
final case class JournalError(path: Path, message: String)

/** Honest marker of a record that could not be read during recovery: which
  * segment, which 1-based line, and why it was refused. The lost bytes stay
  * uncertain — they are never silently dropped OR silently trusted.
  */
final case class UncertainMarker(segment: String, lineNo: Long, reason: String)

/** Result of [[JobJournal.replay]]. `replayed` carries the parsed envelopes up
  * to the last good record — NOTHING is executed with them here (no domain
  * mutation, no automatic event application); the replay policy (seq control,
  * what to re-apply) is entirely the caller's.
  */
final case class JournalRecovery(
    replayed: Vector[EventEnvelope],
    truncatedTail: Boolean,
    uncertainMarker: Option[UncertainMarker]
)

/** Append-only job journal over JSONL files (RAI-087, P6.A slice).
  *
  * Layout: one directory holds dense segments `segment-N.jsonl` (N from 0); one
  * line = one [[EventEnvelope]] encoded with the frozen ADR-024 codecs
  * ([[EventCodecs]]). Old segments are preserved forever — rotation never
  * deletes history.
  *
  * Durability honesty (the no-fsync contract, same discipline as StateStore):
  * every append is flushed to the OS page cache (`BufferedWriter.flush`) and
  * NOTHING more. No `fsync`/`FileChannel.force` is implemented in this slice,
  * so neither appends nor the atomic tmp+move of [[JobJournal.checkpoint]] nor
  * the tail truncation of [[JobJournal.replay]] are claimed to survive power
  * loss. A torn last line after a crash is handled honestly by replay (see
  * below), not prevented here.
  *
  * Writer model: single in-process writer guarded by a semaphore — appends from
  * concurrent fibers are serialized whole (one line never interleaves with
  * another). Cross-process concurrent writers on one directory are out of
  * scope.
  *
  * Sequence honesty: the caller owns the per-job seq discipline ("контроль seq"
  * is external per RAI-087). The journal never assigns, renumbers or repairs
  * seqs; `append` echoes back the envelope's own `seq` as written.
  */
final class JobJournal private (
    dir: Path,
    maxBytes: Long,
    lock: Semaphore,
    writer: Ref[Option[JobJournal.Writer]],
    closed: Ref[Boolean]
):

  /** Appends one envelope as a single JSONL line and flushes it to the OS (NO
    * fsync — see the no-fsync contract above). Returns the envelope's own
    * `seq`. The typed-error channel is empty by contract ([[UIO]]): an
    * environment that cannot be written is a runtime defect, not a journal
    * state — failures surface as defects (`die`).
    *
    * Rotation bound: when the current segment already holds bytes and the
    * record would push it past `maxBytes`, the writer closes the segment and
    * starts `segment-(N+1).jsonl` first. A segment therefore holds at most
    * `maxBytes` bytes unless a single record itself exceeds the bound — a
    * segment always holds at least one record.
    */
  def append(env: EventEnvelope): UIO[Long] =
    lock.withPermit {
      closed.get.flatMap { done =>
        if done then
          ZIO.die(new IllegalStateException("JobJournal.append after close"))
        else
          val line = env.toJson + "\n"
          writer.get.flatMap {
            case None =>
              ZIO.die(new IllegalStateException("JobJournal writer missing"))
            case Some(current) =>
              rotateIfNeeded(current, line).flatMap { active =>
                writer.set(Some(active)) *>
                  ZIO
                    .attempt {
                      active.out.write(line)
                      active.out.flush() // no fsync contract: page cache only
                      ()
                    }
                    .orDie
                    .as(env.seq)
              }
          }
      }
    }

  /** Flushes and closes the writer. Idempotent; a closed journal never reopens
    * implicitly (a second `append` is a defect).
    */
  def close: UIO[Unit] =
    lock.withPermit {
      closed.set(true) *> writer.get.flatMap {
        case Some(current) =>
          ZIO.attempt(current.out.close()).orDie *> writer.set(None)
        case None => ZIO.unit
      }
    }

  /** The journal directory this journal writes into (segments live here as
    * `segment-N.jsonl`). Exposed for wiring (RAI-093): a checkpoint writer
    * needs a path next to the segments, and [[JobJournal.replay]] documents
    * that non-segment files in this directory (e.g. a checkpoint snapshot) are
    * ignored — never replayed, never deleted.
    */
  def path: Path = dir

  private def rotateIfNeeded(
      current: JobJournal.Writer,
      line: String
  ): UIO[JobJournal.Writer] =
    ZIO.attempt {
      val size = Files.size(JobJournal.segmentPath(dir, current.index))
      val incoming =
        line.getBytes(StandardCharsets.UTF_8).length.toLong
      if size > 0L && size + incoming > maxBytes then
        current.out.close()
        JobJournal.openSegment(dir, current.index + 1)
      else current
    }.orDie

end JobJournal

object JobJournal:

  /** Default segment bound: 8 MiB per segment. */
  val defaultMaxBytes: Long = 8L * 1024L * 1024L

  private val SegmentPrefix = "segment-"
  private val SegmentSuffix = ".jsonl"
  private val ReasonMax = 200

  private[journal] final case class Writer(index: Int, out: BufferedWriter)

  /** Opens (and lazily creates) the journal directory `path` and positions the
    * writer on the highest existing segment (or `segment-0.jsonl`). The segment
    * file is created eagerly; an empty directory is a valid fresh journal.
    * `maxBytes` must be positive — a non-positive bound is a programming error
    * at construction time, not a runtime condition.
    */
  def open(
      path: Path,
      maxBytes: Long = defaultMaxBytes
  ): IO[JournalError, JobJournal] =
    if maxBytes <= 0L then
      throw new IllegalArgumentException(
        s"maxBytes must be positive, got $maxBytes"
      )
    for
      _ <- ensureDir(path)
      segments <- listSegments(path)
      first <- openSegmentIO(path, segments.lastOption.map(_._1).getOrElse(0))
      writer <- Ref.make[Option[Writer]](Some(first))
      lock <- Semaphore.make(1L)
      closed <- Ref.make(false)
    yield new JobJournal(path, maxBytes, lock, writer, closed)

  /** Writes `snapshotJson` to `path` atomically: bytes land in a sibling
    * `<name>.tmp` which is then ATOMIC_MOVEd onto the target. A reader sees
    * either the previous or the new snapshot, never a half-written one. Like
    * every write in this slice, the rename carries no fsync contract.
    */
  def checkpoint(snapshotJson: String, path: Path): IO[JournalError, Unit] =
    ZIO
      .attempt {
        val parent = path.toAbsolutePath.getParent
        Option(parent).foreach(Files.createDirectories(_: java.nio.file.Path))
        val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
        Files.writeString(tmp, snapshotJson, StandardCharsets.UTF_8)
        Files.move(
          tmp,
          path,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
        ()
      }
      .refineOrDie { case e: java.io.IOException =>
        JournalError(path, String.valueOf(e.getMessage))
      }

  /** Recovery read: parses segments `segment-0.jsonl, segment-1.jsonl, ...` in
    * order, line by line, up to the LAST GOOD record.
    *
    *   - A line that fails to decode (including a torn final line without a
    *     trailing `\n` from a crash mid-append) ends the replay: everything
    *     from it on is reported via `truncatedTail` + `uncertainMarker` — an
    *     honest Uncertain, never an error and never a guess about the lost
    *     bytes.
    *   - The unreadable tail IS physically truncated (the segment file is cut
    *     at the end of the last good record) so that subsequent appends work;
    *     this file repair is the only mutation replay performs. No domain
    *     mutation, no event application, no seq fixing happens here — the
    *     returned envelopes are data for the caller's replay policy.
    *   - A COMPLETE line without a trailing newline is a complete record and is
    *     replayed normally (the crash happened after the bytes, before the
    *     newline — the record is fully readable).
    *   - If corruption sits before the final segment, later segments are
    *     preserved untouched but are NOT replayed past the hole; reconciliation
    *     across that gap is the caller's business.
    *   - A missing directory is an empty journal, not an error. Non-segment
    *     files (e.g. a checkpoint snapshot) in the same directory are ignored.
    */
  def replay(path: Path): IO[JournalError, JournalRecovery] =
    listSegments(path).flatMap { segments =>
      type Acc = (Vector[EventEnvelope], Option[UncertainMarker])
      ZIO
        .foldLeft(segments)(
          Vector.empty[EventEnvelope],
          Option.empty[UncertainMarker]
        ) {
          case (acc @ (_, Some(_)), _) => ZIO.succeed(acc)
          case ((envs, _), (_, file)) =>
            readSegment(file).map { case (good, bad) => (envs ++ good, bad) }
        }
        .map { case (envs, marker) =>
          JournalRecovery(envs, marker.isDefined, marker)
        }
    }

  // ------------------------------------------------------------------
  // Internals (single file, single package slice)
  // ------------------------------------------------------------------

  private def segmentPath(dir: Path, index: Int): Path =
    dir.resolve(s"$SegmentPrefix$index$SegmentSuffix")

  private def segmentIndex(name: String): Option[Int] =
    if name.startsWith(SegmentPrefix) && name.endsWith(SegmentSuffix) then
      val mid = name.substring(
        SegmentPrefix.length,
        name.length - SegmentSuffix.length
      )
      if mid.nonEmpty && mid.forall(c => c >= '0' && c <= '9') then
        Some(mid.toInt)
      else None
    else None

  private def ensureDir(path: Path): IO[JournalError, Unit] =
    ZIO
      .attempt {
        if Files.exists(path) then
          if !Files.isDirectory(path) then
            throw new java.io.IOException("journal path is not a directory")
        else Files.createDirectories(path)
        ()
      }
      .refineOrDie { case e: java.io.IOException =>
        JournalError(path, String.valueOf(e.getMessage))
      }

  /** All segments sorted by index; a missing directory yields Nil (empty
    * journal), never an error.
    */
  private def listSegments(path: Path): IO[JournalError, List[(Int, Path)]] =
    if !Files.exists(path) then ZIO.succeed(Nil)
    else
      ZIO
        .attempt {
          val stream = Files.list(path)
          try
            import scala.jdk.CollectionConverters.*
            stream
              .iterator()
              .asScala
              .filter(p => Files.isRegularFile(p))
              .flatMap(p =>
                segmentIndex(p.getFileName.toString).map(i => (i, p))
              )
              .toList
              .sortBy(_._1)
          finally stream.close()
        }
        .refineOrDie { case e: java.io.IOException =>
          JournalError(path, String.valueOf(e.getMessage))
        }

  private def openSegmentIO(
      dir: Path,
      index: Int
  ): IO[JournalError, Writer] =
    ZIO
      .attempt(openSegment(dir, index))
      .refineOrDie { case e: java.io.IOException =>
        JournalError(segmentPath(dir, index), String.valueOf(e.getMessage))
      }

  private def openSegment(dir: Path, index: Int): Writer =
    Writer(
      index,
      Files.newBufferedWriter(
        segmentPath(dir, index),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    )

  /** Reads one segment up to the last good record; on the first undecodable
    * line truncates the file to the end of the good prefix and reports the
    * Uncertain marker.
    */
  private def readSegment(
      file: Path
  ): IO[JournalError, (Vector[EventEnvelope], Option[UncertainMarker])] =
    ZIO
      .attempt {
        val bytes = Files.readAllBytes(file)
        val good = Vector.newBuilder[EventEnvelope]
        var offset = 0
        var lineNo = 0L
        var bad = Option.empty[UncertainMarker]
        while offset < bytes.length && bad.isEmpty do
          val start = offset
          val nl = indexOfNewline(bytes, offset)
          val end = if nl >= 0 then nl else bytes.length
          val text =
            new String(bytes, start, end - start, StandardCharsets.UTF_8)
          lineNo += 1
          text.fromJson[EventEnvelope] match
            case Right(env) => good += env
            case Left(reason) =>
              bad = Some(
                UncertainMarker(
                  file.getFileName.toString,
                  lineNo,
                  reason.take(ReasonMax)
                )
              )
              truncateTo(file, start.toLong)
          offset = if nl >= 0 then nl + 1 else bytes.length
        (good.result(), bad)
      }
      .refineOrDie { case e: java.io.IOException =>
        JournalError(file, String.valueOf(e.getMessage))
      }

  private def indexOfNewline(bytes: Array[Byte], from: Int): Int =
    var i = from
    while i < bytes.length && bytes(i) != '\n' do i += 1
    if i < bytes.length then i else -1

  private def truncateTo(file: Path, len: Long): Unit =
    val ch = FileChannel.open(file, StandardOpenOption.WRITE)
    try ch.truncate(len)
    finally ch.close()

end JobJournal
