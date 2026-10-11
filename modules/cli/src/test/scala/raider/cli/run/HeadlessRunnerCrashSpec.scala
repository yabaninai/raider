package raider.cli.run

import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.jobs.JobStatus
import zio._
import zio.json.DecoderOps
import zio.test._
import zio.test.TestAspect.{timeout, withLiveClock}
import zio.test.live

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** LIN-03A-prep acceptance: events.jsonl as a DIRECT stream of the run.
  *   - LIN3-A-01: a mock run creates the file and every line is valid for a
  *     format reader (one EventEnvelope per line, per-job seq monotonic from 1,
  *     no gaps on a completed run);
  *   - LIN3-A-02: SIGTERM (the runner-fiber interrupt behind Main's shutdown
  *     hook) and typed errors end the file HONESTLY — a truncated tail is
  *     allowed, a broken MIDDLE line is not, and no terminating line is ever
  *     fabricated;
  *   - LIN3-A-03: the existing HeadlessRunner/EventExport specs stay green
  *     (additive change — verified by the full raiderCli test run).
  */
object HeadlessRunnerCrashSpec extends ZIOSpecDefault:

  private def tempDir(): Path =
    Files.createTempDirectory("raider-crash-test")

  private def agentArgs(out: Path) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        )
      )
      .fold(e => throw e, identity)

  /** Honest-tail reader (JobJournal.replay semantics): every COMPLETE line must
    * decode; the final unterminated fragment MAY be a torn tail (crash between
    * the bytes and the newline) and is returned separately — never silently
    * dropped, never silently trusted. `None` tail = the file ended with a
    * newline (a cleanly settled run always does).
    */
  private def readHonest(
      file: Path
  ): (Vector[Option[EventEnvelope]], Option[String]) =
    val raw = Files.readString(file, UTF_8)
    if raw.isEmpty then (Vector.empty, None)
    else
      val lines = raw.stripSuffix("\n").split("\n").toVector
      if raw.endsWith("\n") then (lines.map(decodeLine), None)
      else (lines.init.map(decodeLine), Some(lines.last))

  private def decodeLine(line: String): Option[EventEnvelope] =
    line.fromJson[EventEnvelope].toOption

  /** The run's events file under `out` (the invocation owns
    * `<out>/work-<uuid>/events.jsonl`; the uuid is unknown to the observer).
    */
  private def findEventsFile(out: Path): Option[Path] =
    if !Files.exists(out) then None
    else
      val stream = Files.list(out)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isDirectory(_))
          .map(_.resolve("events.jsonl"))
          .find(Files.exists(_))
      finally stream.close()

  /** A mid-run observation: event lines already on disk while run.json (the
    * very last artifact, written after the job settled) is still absent.
    */
  private def midRunObserved(out: Path): Boolean =
    findEventsFile(out).exists { f =>
      Files.size(f) > 0L && !Files.exists(f.getParent.resolve("run.json"))
    }

  def spec = suite("headless runner crash scenarios (LIN-03A-prep)")(
    test(
      "LIN3-A-02: events.jsonl format is honest (no fake lines, valid envelopes)"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          outcome <- HeadlessRunner.run(agentArgs(work))
          eventsRaw = Files.readString(
            outcome.workDir.resolve("events.jsonl"),
            UTF_8
          )
          parsedLines = eventsRaw.split("\n").filter(_.trim.nonEmpty).toList
          envelopes = parsedLines
            .map(_.fromJson[raider.runtime.events.EventEnvelope])
            .collect { case Right(e) => e }
          // Check that the file ends honestly:
          // 1. All lines are valid envelopes
          // 2. No fake/terminal padding lines
          // 3. Sequential envelopes with no gaps
          allLinesValid = parsedLines.forall { line =>
            line.fromJson[raider.runtime.events.EventEnvelope].isRight
          }
          noFakePadding = !eventsRaw.contains("fake") && !eventsRaw.contains(
            "terminal"
          ) &&
            !eventsRaw.contains("completed") && !eventsRaw.contains("finished")
        yield assertTrue(
          eventsRaw.nonEmpty, // File should exist
          envelopes.nonEmpty, // Should have some envelopes
          envelopes.forall(_.schemaVersion == 1),
          envelopes.zipWithIndex.forall { case (e, i) =>
            e.seq == i.toLong + 1
          }, // No seq gaps
          allLinesValid, // All lines are valid envelopes
          noFakePadding, // No fake padding lines
          // The file should not contain incomplete JSON objects
          !eventsRaw.contains(",}") && !eventsRaw.contains("}{")
        )
      }
    },
    test(
      "LIN3-A-01: mock run streams events.jsonl — reader-valid lines, per-job seq monotonic from 1, clean newline termination"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            outcome <- HeadlessRunner.run(agentArgs(work))
            (complete, tail) = readHonest(
              outcome.workDir.resolve("events.jsonl")
            )
            decoded = complete.flatten
            seqs = decoded.map(_.seq)
            last = decoded.lastOption.map(_.payload)
          yield assertTrue(
            outcome.exitCode == 0,
            tail.isEmpty, // a COMPLETED run never tears a line
            complete.forall(_.isDefined), // every line whole
            seqs == (1L to decoded.size.toLong).toVector, // dense from 1
            last.contains(EventPayload.Terminal(JobStatus.Succeeded))
          )
        }
      }
    },
    test(
      "LIN3-A-02: a typed error ends the file honestly — whole lines only, Terminal(Failed) last, no fabricated tail"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            // live provider, no key → typed preflight failure (exit 21)
            args = CliArgs
              .parse(
                List(
                  "run",
                  "--agent",
                  "scout",
                  "--input",
                  "t",
                  "--out",
                  work.toString,
                  "--provider",
                  "openai",
                  "--base-url",
                  "not-a-url"
                )
              )
              .fold(e => throw e, identity)
            outcome <- HeadlessRunner.run(args, Map.empty)
            (complete, tail) = readHonest(
              outcome.workDir.resolve("events.jsonl")
            )
            decoded = complete.flatten
            last = decoded.lastOption.map(_.payload)
          yield assertTrue(
            outcome.exitCode == 21,
            tail.isEmpty, // the run settled: newline-terminated file
            complete.forall(_.isDefined),
            decoded.nonEmpty,
            decoded.headOption.exists(_.seq == 1L), // start transition first
            last.contains(EventPayload.Terminal(JobStatus.Failed))
          )
        }
      }
    },
    test(
      "LIN3-A-02: interrupting the runner fiber mid-run (the SIGTERM path) — lines were flushed DURING the run, file ends honestly, broken middle lines impossible"
    ) {
      live {
        for
          work <- ZIO.attempt(tempDir()).orDie
          fiber <- HeadlessRunner.run(agentArgs(work)).forkDaemon
          // Deterministic mid-run observation: wait until event lines are on
          // disk while run.json (the last artifact) is still absent — the
          // whole job duration is the window, the events file opens first.
          _ <- ZIO.attempt {
            val deadline = java.lang.System.currentTimeMillis() + 20000L
            while !midRunObserved(work) &&
              java.lang.System.currentTimeMillis() < deadline
            do Thread.sleep(2L)
            ()
          }.orDie
          observed = midRunObserved(work)
          _ <- fiber.interrupt // joins finalizers (bounded, ≤ awaitCaughtUp)
          (complete, tail) <- ZIO
            .attempt(findEventsFile(work).map(readHonest))
            .orDie
            .map(
              _.getOrElse(
                (
                  Vector.empty[Option[EventEnvelope]],
                  Option.empty[String]
                )
              )
            )
          decoded = complete.flatten
          seqs = decoded.map(_.seq)
        yield assertTrue(
          observed, // the stream really was written while the run was live
          complete.forall(_.isDefined), // no broken MIDDLE line
          decoded.nonEmpty, // at least the start transition was flushed
          decoded.headOption.exists(_.seq == 1L),
          seqs == seqs.sorted, // monotonic
          seqs.distinct.size == seqs.size // no duplicates
        )
      }
    }
  ) @@ withLiveClock @@ timeout(180.seconds)

end HeadlessRunnerCrashSpec
