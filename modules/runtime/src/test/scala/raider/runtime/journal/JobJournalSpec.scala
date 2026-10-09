package raider.runtime.journal

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{EventEnvelope, EventPayload, UsageSnapshot}
import raider.runtime.events.EventCodecs.given
import raider.runtime.jobs.JobStatus
import zio.json.EncoderOps
import zio.test.*
import zio.{UIO, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** RAI-087 acceptance (m2-p2.md JRNL-01..03) over the real filesystem.
  *
  * Determinism rules: no sleeps; the concurrency fixture asserts an
  * interleaving-independent invariant (every line parses after N parallel
  * appends — the writer lock keeps lines whole). Crash scenarios are simulated
  * by direct external byte writes (garbage tail / torn line), exactly the
  * on-disk shape a kill -9 leaves behind. File fixtures use per-test temp dirs.
  */
object JobJournalSpec extends ZIOSpecDefault:

  private val jobId = JobId("job-j")
  private val rootId = RootId("root-j")
  private val sessionId = SessionId("sess-j")

  private val payloads: List[EventPayload] = List(
    EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None),
    EventPayload.ModelCallStarted(
      1,
      "model-a",
      Some(3)
    ),
    EventPayload.ToolStarted("call-1", "bash", "ls -la"),
    EventPayload.ModelCallFinished(
      1,
      1200L,
      UsageSnapshot(MicroUsd(500L), MicroUsd(400L), unknown = false)
    ),
    EventPayload.Diagnostics("tail record"),
    EventPayload.Terminal(JobStatus.Succeeded)
  )

  private def envelope(seq: Long): EventEnvelope =
    EventEnvelope(
      1,
      seq,
      jobId,
      rootId,
      sessionId,
      1700000000000L + seq,
      payloads(((seq - 1) % payloads.size).toInt)
    )

  private def tempDir: UIO[Path] =
    ZIO.attempt(Files.createTempDirectory("raider-journal-spec")).orDie

  private def appendBytes(p: Path, text: String): UIO[Unit] =
    ZIO.attempt {
      Files.writeString(
        p,
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      ()
    }.orDie

  private def readText(p: Path): UIO[String] =
    ZIO.attempt(Files.readString(p, StandardCharsets.UTF_8)).orDie

  private def segmentFiles(dir: Path): UIO[List[Path]] =
    ZIO.attempt {
      val stream = Files.list(dir)
      try
        stream
          .iterator()
          .asScala
          .map(_.getFileName.toString)
          .filter(n => n.startsWith("segment-") && n.endsWith(".jsonl"))
          .toList
          .sorted
          .map(dir.resolve(_))
      finally stream.close()
    }.orDie

  def spec = suite("JobJournal")(
    suite("JRNL-01: append/replay roundtrip by seq")(
      test("replay returns every appended envelope, in order, without loss") {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            val expected = (1L to 6L).map(envelope).toVector
            for
              written <- ZIO.foreach(expected)(journal.append)
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              written == expected.map(_.seq),
              recovery.replayed == expected,
              recovery.replayed.map(_.seq) == expected.map(_.seq),
              !recovery.truncatedTail,
              recovery.uncertainMarker.isEmpty
            )
          }
        }
      },
      test(
        "roundtrip survives reopen: second journal continues the same segments"
      ) {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { first =>
            for
              _ <- ZIO.foreachDiscard(1L to 3L)(s => first.append(envelope(s)))
              _ <- first.close
              second <- JobJournal.open(dir)
              seq4 <- second.append(envelope(4L))
              _ <- second.close
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              seq4 == 4L,
              recovery.replayed.map(_.seq) == Vector(1L, 2L, 3L, 4L),
              !recovery.truncatedTail
            )
          }
        }
      }
    ),
    suite("JRNL-02: corrupt tail")(
      test(
        "garbage tail line is truncated with an Uncertain marker; appends keep working"
      ) {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            val segment = dir.resolve("segment-0.jsonl")
            for
              _ <- ZIO
                .foreachDiscard(1L to 3L)(s => journal.append(envelope(s)))
              _ <- appendBytes(segment, "\n{this is not json at all\n")
              recovery <- JobJournal.replay(dir)
              afterRepair <- readText(segment)
              second <- JobJournal.open(dir)
              seq4 <- second.append(envelope(4L))
              recovery2 <- JobJournal.replay(dir)
            yield assertTrue(
              recovery.replayed.map(_.seq) == Vector(1L, 2L, 3L),
              recovery.truncatedTail,
              recovery.uncertainMarker.isDefined,
              recovery.uncertainMarker.get.segment == "segment-0.jsonl",
              recovery.uncertainMarker.get.lineNo == 4L,
              !afterRepair.contains("not json"),
              afterRepair.endsWith("\n"),
              seq4 == 4L,
              recovery2.replayed.map(_.seq) == Vector(1L, 2L, 3L, 4L),
              !recovery2.truncatedTail,
              recovery2.uncertainMarker.isEmpty
            )
          }
        }
      },
      test(
        "garbage in a later segment stops the replay at the hole; later bytes preserved"
      ) {
        tempDir.flatMap { dir =>
          JobJournal.open(dir, maxBytes = 1L).flatMap { journal =>
            for
              // every record exceeds maxBytes=1 but a segment always holds one
              // record: each envelope lands in its own segment
              _ <- ZIO
                .foreachDiscard(1L to 3L)(s => journal.append(envelope(s)))
              garbage = dir.resolve("segment-1.jsonl")
              before <- readText(garbage)
              _ <- appendBytes(garbage, "\nGARBAGE\n")
              recovery <- JobJournal.replay(dir)
              after <- readText(garbage)
            yield assertTrue(
              before.endsWith("\n"),
              // segment-0 (seq 1) and line 1 of segment-1 (seq 2) replay;
              // the hole stops the replay BEFORE segment-2 (seq 3), whose
              // bytes are valid but unverifiable past the corruption
              recovery.replayed.map(_.seq) == Vector(1L, 2L),
              recovery.truncatedTail,
              recovery.uncertainMarker.get.segment == "segment-1.jsonl",
              recovery.uncertainMarker.get.lineNo == 2L,
              after.startsWith(before.take(before.indexOf("\n") + 1)),
              !after.contains("GARBAGE")
            )
          }
        }
      }
    ),
    suite("JRNL-03: crash-mid-append and bounded rotation")(
      test("torn line without newline is an honest Uncertain, not an error") {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            val segment = dir.resolve("segment-0.jsonl")
            for
              _ <- ZIO
                .foreachDiscard(1L to 3L)(s => journal.append(envelope(s)))
              torn = envelope(4L).toJson
                .substring(0, 40) // no newline, no JSON end
              _ <- appendBytes(segment, torn)
              recovery <- JobJournal.replay(dir)
              marker = recovery.uncertainMarker
            yield assertTrue(
              recovery.replayed.map(_.seq) == Vector(1L, 2L, 3L),
              recovery.truncatedTail,
              marker.isDefined,
              marker.get.lineNo == 4L,
              marker.get.reason.nonEmpty
            )
          }
        }
      },
      test(
        "complete record without trailing newline IS replayed (crash after bytes, before newline)"
      ) {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            val segment = dir.resolve("segment-0.jsonl")
            for
              _ <- ZIO
                .foreachDiscard(1L to 2L)(s => journal.append(envelope(s)))
              complete = envelope(3L).toJson // full JSON, no trailing newline
              _ <- appendBytes(segment, complete)
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              recovery.replayed.map(_.seq) == Vector(1L, 2L, 3L),
              !recovery.truncatedTail,
              recovery.uncertainMarker.isEmpty
            )
          }
        }
      },
      test("appends after torn-tail recovery land cleanly (no byte hole)") {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            val segment = dir.resolve("segment-0.jsonl")
            for
              _ <- ZIO
                .foreachDiscard(1L to 2L)(s => journal.append(envelope(s)))
              _ <- appendBytes(segment, "{\"seq\":3,\"half")
              _ <- JobJournal.replay(dir)
              seq3 <- journal.append(envelope(3L))
              after <- readText(segment)
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              seq3 == 3L,
              !after.contains("half"),
              recovery.replayed.map(_.seq) == Vector(1L, 2L, 3L),
              !recovery.truncatedTail
            )
          }
        }
      },
      test(
        "rotation at maxBytes bounds segments and preserves every old record"
      ) {
        tempDir.flatMap { dir =>
          val maxBytes = 500L
          JobJournal.open(dir, maxBytes = maxBytes).flatMap { journal =>
            val expected = (1L to 6L).map(envelope).toVector
            val maxRecord = expected
              .map(e =>
                e.toJson.getBytes(StandardCharsets.UTF_8).length.toLong + 1L
              )
              .max
            for
              written <- ZIO.foreach(expected)(journal.append)
              segments <- segmentFiles(dir)
              sizes <- ZIO
                .foreach(segments)(p => ZIO.attempt(Files.size(p)).orDie)
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              written == expected.map(_.seq),
              segments.size >= 3, // rotation actually happened, more than once
              segments.map(_.getFileName.toString) ==
                (0 until segments.size).map(i => s"segment-$i.jsonl").toList,
              // every closed segment is bounded by maxBytes (+ one record):
              sizes.dropRight(1).forall(_ <= maxBytes + maxRecord),
              recovery.replayed == expected,
              !recovery.truncatedTail
            )
          }
        }
      },
      test(
        "concurrent appends serialize under the writer lock: every line stays whole"
      ) {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            for
              _ <- ZIO
                .foreachParDiscard(1L to 40L)(s => journal.append(envelope(s)))
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              recovery.replayed.map(_.seq).toSet == (1L to 40L).toSet,
              recovery.replayed.size == 40,
              !recovery.truncatedTail,
              recovery.uncertainMarker.isEmpty
            )
          }
        }
      }
    ),
    suite("checkpoint")(
      test("atomic tmp+move: snapshot lands at the target, no tmp residue") {
        tempDir.flatMap { dir =>
          val target = dir.resolve("snapshots").resolve("job-j.json")
          for
            _ <- JobJournal.checkpoint("{\"snapshot\":\"v1\"}", target)
            text <- readText(target)
            tmpGone <- ZIO
              .attempt(!Files.exists(target.resolveSibling("job-j.json.tmp")))
              .orDie
          yield assertTrue(text == "{\"snapshot\":\"v1\"}", tmpGone)
        }
      },
      test("checkpoint replaces an existing snapshot atomically") {
        tempDir.flatMap { dir =>
          val target = dir.resolve("job-j.json")
          for
            _ <- JobJournal.checkpoint("{\"snapshot\":\"v1\"}", target)
            _ <- JobJournal.checkpoint("{\"snapshot\":\"v2\"}", target)
            text <- readText(target)
          yield assertTrue(text == "{\"snapshot\":\"v2\"}")
        }
      },
      test("replay ignores a checkpoint file living in the journal directory") {
        tempDir.flatMap { dir =>
          JobJournal.open(dir).flatMap { journal =>
            for
              _ <- JobJournal.checkpoint(
                "{\"snapshot\":\"v1\"}",
                dir.resolve("checkpoint.json")
              )
              _ <- ZIO
                .foreachDiscard(1L to 2L)(s => journal.append(envelope(s)))
              recovery <- JobJournal.replay(dir)
            yield assertTrue(
              recovery.replayed.map(_.seq) == Vector(1L, 2L),
              !recovery.truncatedTail
            )
          }
        }
      }
    ),
    suite("construction")(
      test("non-positive maxBytes is a construction-time programming error") {
        tempDir.flatMap { dir =>
          val opened = ZIO.succeed(
            scala.util.Try(JobJournal.open(dir, maxBytes = 0L))
          )
          opened.flatMap { t =>
            assertTrue(t.isFailure)
          }
        }
      },
      test("replay of a missing directory is an honest empty journal") {
        tempDir.flatMap { dir =>
          JobJournal.replay(dir.resolve("absent")).flatMap { recovery =>
            assertTrue(
              recovery.replayed.isEmpty,
              !recovery.truncatedTail,
              recovery.uncertainMarker.isEmpty
            )
          }
        }
      }
    )
  )
