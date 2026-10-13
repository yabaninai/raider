package raider.cli.run

import raider.runtime.events.EventCodecs.given
import raider.runtime.events.EventEnvelope
import raider.runtime.lineage.wire.LineageEnvelope
import zio.json.EncoderOps
import zio.{Ref, Semaphore, UIO, ZIO}

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** LIN-03A-prep: the run-local `events.jsonl` sink — the headless run's event
  * stream written INCREMENTALLY as a direct flow of the invocation, in the
  * JobJournal JSONL practice (one [[EventEnvelope]] per line, the frozen
  * ADR-024 zio-json codecs of `raider.runtime.events`, UTF-8, one JSON object
  * per line).
  *
  * Bounded rotate is deliberately NOT implemented here: `events.jsonl` is the
  * raw stream of one invocation, not the journal layer — bounding becomes
  * wave-2's concern at the JobJournal integration (LIN-03A contract).
  *
  * Durability honesty (the same no-fsync contract as the journal): every append
  * is one whole-line write + flush to the OS page cache and NOTHING more — no
  * fsync, so power loss is not survived by design. A crash or a cancellation
  * mid-run therefore leaves an HONEST truncated tail (the bytes already
  * flushed), never a fabricated terminating line. Per-line atomicity in-process
  * follows the journal writer model: a single writer guarded by a semaphore, so
  * one line never interleaves with another.
  *
  * Failure honesty: like the journal, the error channel is defects-only — an
  * environment that cannot be written is a runtime defect, not an events-file
  * state. Appends after [[close]] are defects (never silently dropped, never
  * silently reopened); `close` is idempotent.
  */
final class EventsJsonlWriter private (
    val path: Path,
    lock: Semaphore,
    out: Ref[Option[BufferedWriter]],
    lines: Ref[Long]
):

  /** Appends one envelope as a single JSONL line and flushes it to the OS (NO
    * fsync — see the no-fsync contract above). Returns the running count of
    * lines written (the per-job seq discipline belongs to the hub, not to the
    * sink; the count is what a consumer uses to observe its own progress).
    */
  def append(env: EventEnvelope): UIO[Long] =
    lock.withPermit {
      out.get.flatMap {
        case None =>
          ZIO.die(
            new IllegalStateException(
              s"EventsJsonlWriter.append after close: $path"
            )
          )
        case Some(w) =>
          ZIO.attempt {
            w.write(env.toJson + "\n")
            w.flush() // no fsync contract: page cache only
            ()
          }.orDie *> lines.updateAndGet(_ + 1L)
      }
    }

  /** RAI-144: appends one VERSIONED [[LineageEnvelope]] line — the exact sink
    * discipline of [[append]] (single locked whole-line write + flush, no
    * fsync, append-after-close is a defect), for the versioned ADR-032 wire. A
    * v1 envelope encodes here byte-identically to [[append]]'s frozen codec
    * (the pinned `LineageEnvelope` v1 form, ADR-032 §1), so a mixed
    * parent/child export is still one JSONL file of self-describing lines.
    */
  def appendLineage(env: LineageEnvelope): UIO[Long] =
    lock.withPermit {
      out.get.flatMap {
        case None =>
          ZIO.die(
            new IllegalStateException(
              s"EventsJsonlWriter.appendLineage after close: $path"
            )
          )
        case Some(w) =>
          ZIO.attempt {
            w.write(env.toJson + "\n")
            w.flush() // no fsync contract: page cache only
            ()
          }.orDie *> lines.updateAndGet(_ + 1L)
      }
    }

  /** Flushes and closes the writer. Idempotent; a closed sink never reopens
    * implicitly (a later append is a defect, as in the journal).
    */
  def close: UIO[Unit] =
    lock.withPermit {
      out.get.flatMap {
        case Some(w) => ZIO.attempt(w.close()).orDie *> out.set(None)
        case None    => ZIO.unit
      }
    }

  /** Lines written so far (including every line this sink flushed). */
  def written: UIO[Long] = lines.get

end EventsJsonlWriter

object EventsJsonlWriter:

  /** Opens the events file at `path`, creating missing parent directories (the
    * invocation's work dir may not exist yet) and the file itself EAGERLY, in
    * the journal segment discipline (CREATE + APPEND, UTF-8): a mid-run
    * observer sees the file from the first append on. IO failures surface as
    * defects — an unwritable artifact environment fails the run, exactly as the
    * late artifact write did before streaming existed.
    */
  def open(path: Path): UIO[EventsJsonlWriter] =
    ZIO
      .attempt {
        Option(path.toAbsolutePath.getParent)
          .foreach(Files.createDirectories(_: Path))
        Files.newBufferedWriter(
          path,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      }
      .orDie
      .flatMap { w =>
        for
          lock <- Semaphore.make(1L)
          out <- Ref.make[Option[BufferedWriter]](Some(w))
          lines <- Ref.make(0L)
        yield new EventsJsonlWriter(path, lock, out, lines)
      }

end EventsJsonlWriter
