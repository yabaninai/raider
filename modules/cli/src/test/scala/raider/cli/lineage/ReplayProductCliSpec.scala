package raider.cli.lineage

import raider.cli.run.{CliArgs, HeadlessRunner}
import raider.core.result.ExitCodes
import raider.dsl.lineage.data.LineageDataDocument
import raider.dsl.lineage.store.LineageDataStore
import raider.runtime.lineage.replay.GraphComparator
import zio.test.*
import zio.test.live
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

/** RAI-109 acceptance (docs/tasks/lineage.md): `raider replay <run-dir>
  * --workspace <dir> [--data <file>]` as a PRODUCT subcommand of the general
  * `raider` CLI (`raider.cli.Main`), delegating to the accepted LIN-10
  * [[ReplayCli.exec]] (no duplicated parse/render/exit logic).
  *   - LIN13-01 (MAIN): the product entry reports Match + graph-hash (exit 0)
  *     and Mismatch + FIRST divergent node (exit 10) — asserted as real PROCESS
  *     smokes through `raider.cli.Main replay ...`;
  *   - LIN13-02: a corrupt/missing lineage-data.json is a TYPED failure
  *     (`typed-error: ... StoreError.*` on stderr) with exit 21; a usage error
  *     (missing `--workspace`, unknown flag) is typed too with an invalid exit
  *     (21);
  *   - LIN13-03: PARALLEL `raider replay` processes over the SAME run dir and
  *     workspace stay isolated: unique staging per invocation, both verdicts
  *     pass `verifyUntouched`, and the original is byte-identical afterwards.
  *
  * No network, no paid calls: the only "model" is the record ([[ReplayHarness]]
  * fixture). The dispatch-level unit tests ([[HeadlessRunner]] on a
  * `CliArgs.Target.Replay`) prove the runner returns the mapped exit code
  * WITHOUT creating any run artifacts (result = None) — run/chat semantics are
  * untouched (the existing RUN/CIW/WRAP suites stay green in the same test
  * run).
  */
object ReplayProductCliSpec extends ZIOSpecDefault:

  // ----- fixtures (the same contract as ReplayCliSpec) -----------------------

  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-replay-product-ws")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  private def freshRunDir(prefix: String): ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO
        .attempt(Files.createTempDirectory(s"raider-replay-product-$prefix"))
        .orDie
    )(d => ZIO.attempt(deleteRecursively(d)).orDie.ignore)

  private def writeDoc(
      runDir: Path,
      doc: LineageDataDocument
  ): ZIO[Any, Nothing, Unit] =
    ZIO
      .attempt(
        LineageDataStore.write(runDir.resolve(ReplayCli.DataFileName), doc)
      )
      .orDie
      .flatMap {
        case Right(()) => ZIO.unit
        case Left(error) =>
          ZIO.dieMessage(s"fixture bug: store write failed: $error")
      }

  private def corruptDataFile(runDir: Path): ZIO[Any, Nothing, Path] =
    ZIO.attempt {
      val p = runDir.resolve(ReplayCli.DataFileName)
      Files.writeString(p, "{ this is not a lineage-data document", UTF_8)
      p
    }.orDie

  /** The normalized hash of the recorded graph (structural agreement ⇒ equal
    * hashes, so expected-vs-expected is the match hash — ReplayCliSpec rule).
    */
  private def selfHash(doc: LineageDataDocument): String =
    GraphComparator
      .compareNormalized(
        ReplayHarness.recordedGraph(doc),
        ReplayHarness.recordedGraph(doc)
      )
      .toOption
      .getOrElse("?")

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  // ----- process smoke harness -----------------------------------------------

  private lazy val m2Root: Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
      .getOrElse(throw new IllegalStateException("no artifacts/tools/m2"))
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")

  private lazy val userClasspath: String =
    val lib = m2Root
      .resolve("org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar")
    if !Files.isRegularFile(lib) then
      throw new IllegalStateException("missing pinned jar: " + lib)
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + java.io.File.pathSeparator + base

  /** Runs `raider.cli.Main replay ...` as a REAL process (the product entry
    * shape `raider replay ...`), capturing stdout/stderr separately. Left =
    * timeout/launch failure — never counted as an assertion outcome.
    */
  private def runRaiderReplay(
      argv: List[String]
  ): ZIO[Any, Nothing, Either[String, (Int, String, String)]] =
    ZIO.attempt {
      val outLog = Files.createTempFile("rai109-out", ".log")
      val errLog = Files.createTempFile("rai109-err", ".log")
      val pb = new ProcessBuilder(
        ("java" :: "-cp" :: userClasspath :: "raider.cli.Main" :: "replay" :: argv)*
      )
      pb.redirectOutput(outLog.toFile)
      pb.redirectError(errLog.toFile)
      val proc = pb.start()
      if !proc.waitFor(120L, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left("raider replay did not finish within 120s (destroyed)")
      else
        val out = Files.readString(outLog, UTF_8)
        val err = Files.readString(errLog, UTF_8)
        Files.deleteIfExists(outLog)
        Files.deleteIfExists(errLog)
        Right((proc.exitValue(), out, err))
    }.orDie

  // ----- spec ----------------------------------------------------------------

  def spec: Spec[TestEnvironment & Scope, Any] = suite("product raider replay")(
    test(
      "LIN13-01: `raider replay` process — Match + graph-hash, exit 0"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("match")
            _ <- writeDoc(runDir, ReplayHarness.document)
            res <- runRaiderReplay(
              List(runDir.toString, "--workspace", ws.toString)
            )
          yield res match
            case Right((code, out, err)) =>
              assertTrue(
                code == ExitCodes.Success,
                code == 0,
                out.contains("verdict: match"),
                out.contains(
                  s"graph-hash: ${selfHash(ReplayHarness.document)}"
                ),
                !err.contains("typed-error")
              )
            case Left(failure) =>
              throw new IllegalStateException(
                s"LIN13-01: process failed: $failure"
              )
        }
      }
    },
    test(
      "LIN13-01: `raider replay` process — Mismatch names the FIRST divergent node, exit 10"
    ) {
      val mutated = ReplayHarness.withResponse(
        ReplayHarness.document,
        ReplayHarness.Round1Ref,
        "Nothing to read; the workspace stays untouched."
      )
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("mismatch")
            _ <- writeDoc(runDir, mutated)
            res <- runRaiderReplay(
              List(runDir.toString, "--workspace", ws.toString)
            )
          yield res match
            case Right((code, out, _)) =>
              assertTrue(
                code == ExitCodes.TaskRejected,
                code == 10,
                out.contains("verdict: mismatch"),
                // the first divergence in normalized ids (the round-1 tool call)
                out.contains("job-1:n7")
              )
            case Left(failure) =>
              throw new IllegalStateException(
                s"LIN13-01/mismatch: process failed: $failure"
              )
        }
      }
    },
    test(
      "LIN13-02: `raider replay` — corrupt lineage-data.json is a typed error, exit 21"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("corrupt")
            _ <- corruptDataFile(runDir)
            res <- runRaiderReplay(
              List(runDir.toString, "--workspace", ws.toString)
            )
          yield res match
            case Right((code, _, err)) =>
              assertTrue(
                code == ExitCodes.ConfigInvalid,
                code == 21,
                err.contains("typed-error:"),
                err.contains("StoreError.Corrupt")
              )
            case Left(failure) =>
              throw new IllegalStateException(
                s"LIN13-02/corrupt: process failed: $failure"
              )
        }
      }
    },
    test(
      "LIN13-02: `raider replay` — missing lineage-data.json is a typed error, exit 21"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("missing")
            res <- runRaiderReplay(
              List(runDir.toString, "--workspace", ws.toString)
            )
          yield res match
            case Right((code, _, err)) =>
              assertTrue(
                code == 21,
                err.contains("typed-error:"),
                err.contains("StoreError.Missing")
              )
            case Left(failure) =>
              throw new IllegalStateException(
                s"LIN13-02/missing: process failed: $failure"
              )
        }
      }
    },
    test(
      "LIN13-02: `raider replay` — usage errors are typed with an invalid exit (missing --workspace, unknown flag)"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("usage")
            noWorkspace <- runRaiderReplay(List(runDir.toString))
            unknownFlag <- runRaiderReplay(
              List(
                runDir.toString,
                "--workspace",
                ws.toString,
                "--bogus",
                "x"
              )
            )
          yield (noWorkspace, unknownFlag) match
            case (Right((c1, _, e1)), Right((c2, _, e2))) =>
              assertTrue(
                c1 == ExitCodes.ConfigInvalid,
                c1 == 21,
                e1.contains("missing --workspace"),
                e1.contains("usage:"),
                c2 == ExitCodes.ConfigInvalid,
                c2 == 21,
                e2.contains("unknown flag: --bogus"),
                e2.contains("usage:")
              )
            case _ =>
              throw new IllegalStateException(
                "LIN13-02/usage: process launch failed"
              )
        }
      }
    },
    test(
      "LIN13-03: PARALLEL `raider replay` processes — isolated workspaces, verifyUntouched, no shared writes"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("parallel")
            _ <- writeDoc(runDir, ReplayHarness.document)
            // test-level pre-run snapshot of the ORIGINAL workspace
            preSnapshot <- ZIO
              .attempt(WorkspaceIsolation.snapshot(ws))
              .orDie
            // two REAL processes racing on the same run dir + workspace
            p1 = runRaiderReplay(
              List(runDir.toString, "--workspace", ws.toString)
            )
            p2 = runRaiderReplay(
              List(runDir.toString, "--workspace", ws.toString)
            )
            first <- p1.fork
            second <- p2.fork
            r1 <- first.join
            r2 <- second.join
            postVerify <- ZIO
              .attempt(WorkspaceIsolation.verifyUntouched(ws, preSnapshot))
              .orDie
          yield (r1, r2, postVerify) match
            case (
                  Right((c1, o1, _)),
                  Right((c2, o2, _)),
                  Right(())
                ) =>
              assertTrue(
                c1 == 0,
                c2 == 0,
                // determinism: same record → same normalized hash in both
                o1.contains(s"graph-hash: ${selfHash(ReplayHarness.document)}"),
                o2.contains(s"graph-hash: ${selfHash(ReplayHarness.document)}"),
                o1.contains("verdict: match"),
                o2.contains("verdict: match"),
                // the ORIGINAL workspace is byte-identical after BOTH replays
                postVerify.isRight
              )
            case _ =>
              throw new IllegalStateException(
                s"LIN13-03: process failure: $r1 / $r2 / $postVerify"
              )
        }
      }
    },
    test(
      "dispatch: CliArgs recognizes `replay` additively; run/chat parse unchanged"
    ) {
      val replay = CliArgs.parse(
        List("replay", "/tmp/run", "--workspace", "/tmp/ws")
      )
      val run = CliArgs.parse(
        List("run", "--agent", "scout", "--input", "t", "--out", "o", "--mock")
      )
      val bare = CliArgs.parse(List("replay"))
      assertTrue(
        replay.toOption.get.target.isInstanceOf[CliArgs.Target.Replay],
        replay.toOption.get.target
          .asInstanceOf[CliArgs.Target.Replay]
          .argv == List("replay", "/tmp/run", "--workspace", "/tmp/ws"),
        run.toOption.get.target.isInstanceOf[CliArgs.Target.Agent],
        bare.toOption.get.target.isInstanceOf[CliArgs.Target.Replay],
        // the run flag surface still rejects replay-only flags: `--workspace`
        // is a replay/chat flag (long-standing run parse rule, verified by
        // coordinator probe: parse returns Left Configuration for
        // `run --workspace`), NOT a run flag
        CliArgs
          .parse(List("run", "--workspace", "w", "--out", "o", "--mock"))
          .isLeft
      )
    },
    test(
      "dispatch: HeadlessRunner returns the ReplayCli exit code with NO run artifacts (result = None)"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("dispatch")
            _ <- writeDoc(runDir, ReplayHarness.document)
            good = CliArgs
              .parse(
                List("replay", runDir.toString, "--workspace", ws.toString)
              )
              .fold(e => throw e, identity)
            outcome <- HeadlessRunner.run(good)
            // usage-level failure through the same dispatch (exit 21)
            bad = CliArgs
              .parse(List("replay", runDir.toString))
              .fold(e => throw e, identity)
            badOutcome <- HeadlessRunner.run(bad)
          yield assertTrue(
            outcome.exitCode == 0,
            outcome.result.isEmpty,
            outcome.workDir == Paths.get(""),
            badOutcome.exitCode == 21,
            badOutcome.result.isEmpty
          )
        }
      }
    }
  )

end ReplayProductCliSpec
