package raider.cli.run

import raider.core.{JobId, RootId}
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.jobs.StatusLedger
import zio._
import zio.json.{DecoderOps, EncoderOps}
import zio.test._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** LIN-03A-prep acceptance for the events.jsonl streaming sink: the file is a
  * direct flow of the run — every append lands WHOLE on disk immediately (write
  * + flush per line, JobJournal JSONL practice), lines never interleave, and a
  * closed sink never writes again.
  */
object EventsJsonlWriterSpec extends ZIOSpecDefault:

  private def env(seq: Long, text: String): EventEnvelope =
    EventEnvelope(
      schemaVersion = 1,
      seq = seq,
      jobId = JobId("j_writer"),
      rootId = RootId("r_writer"),
      sessionId = StatusLedger.sessionId,
      atMs = 1000L + seq,
      payload = EventPayload.Diagnostics(text)
    )

  private def tempDir(): java.nio.file.Path =
    Files.createTempDirectory("raider-evw")

  def spec = suite("events.jsonl streaming sink (LIN-03A-prep)")(
    test(
      "W-01: append is flushed per line — readable before close, one JSON object per line, byte-parity with the frozen codec"
    ) {
      for
        dir <- ZIO.attempt(tempDir()).orDie
        file = dir.resolve("events.jsonl")
        writer <- EventsJsonlWriter.open(file)
        size0 <- ZIO.attempt(Files.size(file)).orDie
        expected = (1L to 3L).map(i => env(i, s"e$i").toJson)
        _ <- ZIO.foreachDiscard(expected.zipWithIndex) { case (_, i) =>
          writer.append(env(i.toLong + 1L, s"e${i + 1}"))
        }
        raw <- ZIO.attempt(Files.readString(file, UTF_8)).orDie
        lines = raw.split("\n", -1).toVector // keep the trailing empty
        written <- writer.written
        _ <- writer.close
      yield assertTrue(
        size0 == 0L, // created eagerly, empty (JobJournal segment discipline)
        written == 3L,
        raw.endsWith("\n"), // every line newline-terminated
        lines.last == "", // trailing newline, not a torn record
        lines.init == expected, // byte-identical single-line documents
        lines.init
          .map(_.fromJson[EventEnvelope])
          .collect { case Right(e) => e.seq } == Vector(1L, 2L, 3L)
      )
    },
    test(
      "W-02: close is idempotent; append after close is a defect and writes nothing (no silent half-writes)"
    ) {
      for
        dir <- ZIO.attempt(tempDir()).orDie
        file = dir.resolve("events.jsonl")
        writer <- EventsJsonlWriter.open(file)
        _ <- writer.append(env(1L, "kept"))
        before <- ZIO.attempt(Files.readString(file, UTF_8)).orDie
        _ <- writer.close
        _ <- writer.close // idempotent
        exit <- writer.append(env(2L, "after-close")).exit
        after <- ZIO.attempt(Files.readString(file, UTF_8)).orDie
      yield assertTrue(
        !exit.isSuccess,
        after == before // the late append changed nothing on disk
      )
    },
    test(
      "W-03: open creates the events file with its missing parent directories"
    ) {
      for
        dir <- ZIO.attempt(tempDir()).orDie
        nested = dir.resolve("out").resolve("work-1").resolve("events.jsonl")
        writer <- EventsJsonlWriter.open(nested)
        exists <- ZIO.attempt(Files.exists(nested)).orDie
        _ <- writer.close
      yield assertTrue(exists)
    },
    test(
      "W-04: concurrent appends never interleave — 64 parallel lines, each whole, seq set complete"
    ) {
      for
        dir <- ZIO.attempt(tempDir()).orDie
        file = dir.resolve("events.jsonl")
        writer <- EventsJsonlWriter.open(file)
        _ <- ZIO.foreachParDiscard(1L to 64L)(i =>
          writer.append(env(i, s"d$i"))
        )
        raw <- ZIO.attempt(Files.readString(file, UTF_8)).orDie
        lines = raw.split("\n").toVector.filter(_.nonEmpty)
        decoded = lines.map(_.fromJson[EventEnvelope]).collect {
          case Right(e) => e.seq
        }
        written <- writer.written
        _ <- writer.close
      yield assertTrue(
        lines.size == 64, // no interleaved (torn mid-file) lines
        decoded.size == 64, // every whole line decodes
        decoded.sorted == (1L to 64L).toVector, // no loss, no duplication
        written == 64L
      )
    }
  ) @@ TestAspect.timeout(120.seconds)

end EventsJsonlWriterSpec
