package raider.cli.run

import raider.core.result.ExitCodes
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.io.LineageError

import zio._
import zio.json.DecoderOps
import zio.test._
import zio.test.TestAspect.{timeout, withLiveClock}
import zio.test.live

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

/** RAI-129 acceptance (docs/tasks/m1.md, §13 recovery remainder):
  *   - REC-01: resume over a COMPLETED run's journal — the observable state is
  *     recovered as named fields (terminal status, root/session, node counts,
  *     artifact pointers with real SHA-256 digests), with ZERO duplicate
  *     execution (a second resume renders byte-identically, and the product
  *     dispatch never creates an invocation root);
  *   - REC-02: a torn/incomplete tail is TYPED (truncated-tail marker naming
  *     the line, exit 26 — recovered to the last consistent point, no silent
  *     skips); a missing journal is an honest typed error (exit 21); an empty
  *     journal and a mid-file corrupt line are typed errors too;
  *   - REC-03: an interrupted run (the SIGTERM path: the runner-fiber interrupt
  *     behind Main's shutdown hook, the RAI-022-family fixture of
  *     [[HeadlessRunnerCrashSpec]]) resumed ×3 — byte-identical reports, stable
  *     exit code, and the run directory byte-identical across all three resumes
  *     (NO re-execution of side effects: the journal records N started tool
  *     calls, resume executes none and appends nothing);
  *   - the existing journal/runner semantics stay green (this spec is additive;
  *     the RUN/CIW/LIN suites run in the same `raiderCli` test pass).
  *
  * No network, no paid calls: the only backend is the mock fixture run.
  */
object ResumeCommandSpec extends ZIOSpecDefault:

  // ----- fixtures -------------------------------------------------------------

  private def tempDir(prefix: String): ZIO[Scope, Nothing, Path] =
    ZIO
      .acquireRelease(
        ZIO.attempt(Files.createTempDirectory(prefix)).orDie
      )(d => ZIO.attempt(deleteRecursively(d)).orDie.ignore)

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

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  /** Deterministic recursive hash of a directory (sorted relPaths + content
    * digests) — the "run dir untouched" witness of REC-02/REC-03.
    */
  private def dirHash(root: Path): String =
    val stream = Files.walk(root)
    try
      val files =
        stream.iterator().asScala.toVector.filter(Files.isRegularFile(_))
      val lines = files.map { p =>
        val rel = root.relativize(p).toString
        val sha = sha256Hex(Files.readAllBytes(p))
        s"$rel:$sha"
      }.sorted
      sha256Hex(lines.mkString("\n").getBytes(UTF_8))
    finally stream.close()

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"$b%02x")
      .mkString

  private def readLines(p: Path): Vector[String] =
    Files.readAllLines(p, UTF_8).asScala.toVector

  private def decodeEnvelopes(p: Path): Vector[EventEnvelope] =
    readLines(p)
      .filter(_.trim.nonEmpty)
      .flatMap(_.fromJson[EventEnvelope].toOption)

  /** The RETAINED mid-run marker (RAI-227): journal bytes on disk persist after
    * the run completes, so awaiting them cannot lose the sample the way the
    * transient window poll could (a test fiber starved past the whole run under
    * host load then only ever saw the completed state — the RAI-227 flake:
    * `observed` false forever). `run.json` is written only when the run settles
    * — after the journal has streamed — so the first journal byte proves the
    * run passed through the mid-run window (events on disk, run.json still
    * absent).
    */
  private def eventsOnDisk(out: Path): Boolean =
    if !Files.exists(out) then false
    else
      val stream = Files.list(out)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isDirectory(_))
          .map(_.resolve("events.jsonl"))
          .find(Files.exists(_))
          .exists(f => Files.size(f) > 0L)
      finally stream.close()

  private def runDirsUnder(out: Path): Vector[Path] =
    if !Files.exists(out) then Vector.empty
    else
      val stream = Files.list(out)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isDirectory(_))
          .toVector
      finally stream.close()

  // ----- spec -----------------------------------------------------------------

  def spec = suite("raider resume (RAI-129 §13 recovery)")(
    test(
      "REC-01: resume of a completed run — terminal state recovered, artifact pointers with real digests, second resume byte-identical (zero duplicates)"
    ) {
      ZIO.scoped {
        for
          work <- tempDir("raider-resume-rec01")
          outcome <- live(HeadlessRunner.run(agentArgs(work)))
          runDir = outcome.workDir
          first = ResumeCommand.recover(runDir)
          second = ResumeCommand.recover(runDir)
          _ <- ZIO.succeed {
            if first.isLeft || outcome.exitCode != 0 then
              val listing =
                if Files.exists(runDir) then
                  Files.list(runDir).iterator().asScala.mkString(", ")
                else "<run dir missing>"
              java.lang.System.err.println(
                s"REC-01 diag: exit=${outcome.exitCode} workDir=$runDir files=[$listing]"
              )
          }
          // Safe pre-computation (no throwing inside assertTrue terms).
          runJsonPointer = first.toOption.flatMap(
            _.artifacts.find(_.name == "run.json")
          )
          digestMatches = runJsonPointer.exists(p =>
            p.sha256 == sha256Hex(
              Files.readAllBytes(runDir.resolve("run.json"))
            )
          )
          recoveredOk = (first, second) match
            case (Right(r), Right(r2)) =>
              r.terminal.contains(JobStatus.Succeeded) &&
              r.tail.isEmpty &&
              r.events > 0 &&
              r.lastSeq == r.events.toLong && // dense per-job seq from 1
              r.graph.nodes.size >= 1 && // the model round is restored state
              runJsonPointer.isDefined &&
              digestMatches &&
              r.artifacts.exists(_.name == "summary.md") &&
              r.artifacts.exists(_.name == ResumeCommand.JournalFileName) &&
              ResumeCommand.render(r) == ResumeCommand.render(r2) &&
              ResumeCommand.exitCode(Right(r)) == ExitCodes.Success
            case _ => false
        yield assertTrue(
          outcome.exitCode == ExitCodes.Success,
          first.isRight,
          recoveredOk
        )
      }
    },
    test(
      "REC-01: product dispatch — `raider resume` through HeadlessRunner returns the mapped exit code and NEVER creates an invocation root"
    ) {
      ZIO.scoped {
        for
          run <- tempDir("raider-resume-dispatch")
          outcome0 <- live(HeadlessRunner.run(agentArgs(run)))
          runDir = outcome0.workDir
          before = dirHash(runDir)
          parsed = CliArgs
            .parse(List("resume", runDir.toString))
            .fold(e => throw e, identity)
          dispatched <- HeadlessRunner.run(parsed)
          after = dirHash(runDir)
        yield assertTrue(
          parsed.target.isInstanceOf[CliArgs.Target.Resume],
          dispatched.exitCode == ExitCodes.Success,
          dispatched.result.isEmpty, // no new invocation root, no run.json
          before == after
        )
      }
    },
    test(
      "REC-02: torn tail — typed recovery from the last consistent point (exit 26, marker names the line), run dir not modified"
    ) {
      ZIO.scoped {
        for
          work <- tempDir("raider-resume-rec02-torn")
          outcome <- live(HeadlessRunner.run(agentArgs(work)))
          runDir = outcome.workDir
          journal = runDir.resolve(ResumeCommand.JournalFileName)
          // Craft the torn tail: cut the last 5 bytes (a partial Terminal
          // record without its newline — the crash-between-bytes shape).
          original = Files.readAllBytes(journal)
          _ <- ZIO.attempt(Files.write(journal, original.dropRight(5))).orDie
          before = dirHash(runDir)
          verdict = ResumeCommand.recover(runDir)
          after = dirHash(runDir)
          code = ResumeCommand.exec(List("resume", runDir.toString))
          // Independent expectations from the crafted file.
          totalLines = readLines(journal).count(_.trim.nonEmpty)
          goodEnvs = decodeEnvelopes(journal) // the last line does NOT decode
          yielded = verdict.toOption
          tornOk = yielded.exists { r =>
            r.tail.isDefined &&
            r.tail.get.lineNo == totalLines &&
            r.events == goodEnvs.size && // exactly the good prefix
            r.lastSeq == goodEnvs.lastOption.map(_.seq).getOrElse(0L) &&
            r.terminal.isEmpty && // the Terminal record WAS the torn tail
            ResumeCommand.exitCode(Right(r)) == ExitCodes.Uncertain &&
            ResumeCommand.render(r).contains("status: interrupted") &&
            ResumeCommand.render(r).contains("uncertain-tail:")
          }
        yield assertTrue(
          before == after, // read-only: the torn bytes stay preserved
          yielded.isDefined,
          tornOk,
          code == ExitCodes.Uncertain
        )
      }
    },
    test(
      "REC-02: missing journal — honest typed error, exit 21 (never a fabricated empty recovery)"
    ) {
      ZIO.scoped {
        for
          empty <- tempDir("raider-resume-rec02-missing")
          codeEmpty = ResumeCommand.exec(List("resume", empty.toString))
        yield assertTrue(
          ResumeCommand.recover(empty) == Left(
            ResumeCommand.Failure
              .JournalMissing(
                empty,
                empty.resolve(ResumeCommand.JournalFileName)
              )
          ),
          codeEmpty == ExitCodes.ConfigInvalid
        )
      }
    },
    test(
      "REC-02: empty journal and mid-file corruption are typed failures (no silent skips)"
    ) {
      ZIO.scoped {
        for
          emptyDir <- tempDir("raider-resume-empty")
          _ <- ZIO
            .attempt(
              Files.write(
                emptyDir.resolve(ResumeCommand.JournalFileName),
                Array.emptyByteArray
              )
            )
            .orDie
          work <- tempDir("raider-resume-corrupt")
          outcome <- live(HeadlessRunner.run(agentArgs(work)))
          runDir = outcome.workDir
          journal = runDir.resolve(ResumeCommand.JournalFileName)
          good = readLines(journal)
          // Corrupt the FIRST line while a VALID second line remains: the
          // undecodable line is then NOT the last one — a hard typed
          // MalformedLine, never a tail and never a silent skip.
          _ <- ZIO
            .attempt(
              Files.write(
                journal,
                s"{ not json\n${good(1)}\n".getBytes(UTF_8)
              )
            )
            .orDie
          corruptVerdict = ResumeCommand.recover(runDir)
          _ <- ZIO
            .attempt(
              Files
                .write(journal, good.mkString("", "\n", "\n").getBytes(UTF_8))
            )
            .orDie
          emptyVerdict = ResumeCommand.recover(emptyDir)
          corruptIsTyped = corruptVerdict.fold(
            f =>
              f match
                case ResumeCommand.Failure
                      .Journal(_, LineageError.MalformedLine(1, _)) =>
                  true
                case _ => false,
            _ => false
          )
          emptyIsTyped = emptyVerdict.fold(
            f =>
              f match
                case ResumeCommand.Failure.Journal(_, LineageError.Empty) =>
                  true
                case _ => false,
            _ => false
          )
        yield assertTrue(corruptIsTyped, emptyIsTyped)
      }
    },
    test("usage errors are typed with exit 21") {
      val noArgs = ResumeCommand.exec(List("resume"))
      val flagArg = ResumeCommand.exec(List("resume", "--workspace", "x"))
      val extra = ResumeCommand.exec(List("resume", "a", "b"))
      assertTrue(
        noArgs == ExitCodes.ConfigInvalid,
        flagArg == ExitCodes.ConfigInvalid,
        extra == ExitCodes.ConfigInvalid
      )
    },
    test(
      "REC-03: interrupted run (SIGTERM path) → resume ×3 — byte-identical reports, stable exit, run dir untouched, NO re-execution"
    ) {
      live {
        ZIO.scoped {
          for
            work <- tempDir("raider-resume-rec03")
            fiber <- HeadlessRunner.run(agentArgs(work)).forkDaemon
            _ <- {
              // RAI-227 determinism: wait on the RETAINED journal state, not
              // on the transient window sample. The journal bytes persist on
              // disk, so this await cannot miss; the previous window poll
              // could be starved past the entire run under host load (the
              // runner completed first) and false-fail on `observed` forever
              // after. The bound is 120 s — disclosed, well under the 300 s
              // suite cap; expiry leaves `observed` false and the test reds
              // honestly (no silent skips).
              val deadline = java.lang.System.currentTimeMillis() + 120000L
              def awaitJournal: UIO[Unit] =
                ZIO.attempt(eventsOnDisk(work)).orDie.flatMap { onDisk =>
                  if onDisk || java.lang.System
                      .currentTimeMillis() >= deadline
                  then ZIO.unit
                  else ZIO.sleep(zio.Duration.fromMillis(2L)) *> awaitJournal
                }
              awaitJournal
            }
            observed = eventsOnDisk(work)
            _ <- fiber.interrupt // the SIGTERM path (LIN3-A-02)
            runDir <- ZIO.attempt {
              runDirsUnder(work)
                .map(_.resolve("events.jsonl"))
                .find(Files.exists(_))
                .map(_.getParent)
                .getOrElse(throw new IllegalStateException("no run dir"))
            }.orDie
            before = dirHash(runDir)
            journal = runDir.resolve(ResumeCommand.JournalFileName)
            // Independent journal count of started tool calls (the side
            // effects the interrupted run already performed).
            toolStartsInJournal =
              decodeEnvelopes(journal).count(_.payload match
                case EventPayload.ToolStarted(_, _, _) => true
                case _                                 => false)
            r1 = ResumeCommand.recover(runDir)
            after1 = dirHash(runDir)
            r2 = ResumeCommand.recover(runDir)
            r3 = ResumeCommand.recover(runDir)
            after3 = dirHash(runDir)
            code = ResumeCommand.exec(List("resume", runDir.toString))
            resume3Ok = (r1, r2, r3) match
              case (Right(a), Right(b), Right(c)) =>
                val expectedExit =
                  if a.tail.isDefined then ExitCodes.Uncertain
                  else ExitCodes.Success
                ResumeCommand.render(a) == ResumeCommand.render(b) &&
                ResumeCommand.render(b) == ResumeCommand.render(c) &&
                ResumeCommand.exitCode(Right(a)) == expectedExit &&
                code == expectedExit && // ×3 stable exit code
                before == after1 &&
                after1 == after3 && // zero side effects across all resumes
                a.toolCallsStarted == toolStartsInJournal.toLong
              case _ => false
          yield assertTrue(
            observed,
            resume3Ok
          )
        }
      }
    }
  ) @@ withLiveClock @@ timeout(300.seconds)

end ResumeCommandSpec
