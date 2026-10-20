package raider.cli.lineage

import raider.core.{RootId, RaiderError}
import raider.core.result.ExitCodes
import raider.dsl.lineage.data.LineageDataDocument
import raider.dsl.lineage.store.{LineageDataStore, StoreError}
import raider.runtime.lineage.model.NodeId
import raider.runtime.lineage.replay.{
  Divergence,
  DivergenceReason,
  GraphComparator,
  GraphDiff
}
import zio.test.*
import zio.{Scope, ZIO}
import zio.json.EncoderOps

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-10 acceptance (docs/tasks/lineage.md): the standalone replay CLI over a
  * run directory holding `lineage-data.json`.
  *   - LIN10-01 (MAIN): a recorded document replays to a normalized `Match`
  *     with the shared graph-hash (exit 0); a mutated record diverges — the
  *     verdict is `Mismatch` (exit 10) naming the FIRST divergent node in
  *     normalized ids (the round-1 tool call, `job-1:n7`);
  *   - LIN10-02: a missing or corrupt lineage-data file is a TYPED failure
  *     (`StoreError.Missing`/`Corrupt`) with a nonzero exit (config-invalid 21)
  *     — never a crash, never a verdict;
  *   - LIN10-03: two PARALLEL replays of the same record over the SAME original
  *     workspace each run against its own throwaway copy (unique staging
  *     parents), both verify `verifyUntouched` on the original, and the
  *     original is byte-identical to the pre-run snapshot afterwards.
  *
  * No network, no paid calls: the only "model" is the record; the only IO is
  * bounded fixture-workspace reads through the isolated copies.
  */
object ReplayCliSpec extends ZIOSpecDefault:

  private val MutatedRound1Response =
    "Nothing to read; the workspace stays untouched."

  /** Isolated fixture workspace: fresh temp dir + the fixture file, deleted
    * after the use (the same fixture contract as [[ReplayHarnessSpec]]).
    */
  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-replay-cli-ws")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  /** A fresh empty run directory, deleted after the use. */
  private def freshRunDir(prefix: String): ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory(s"raider-replay-cli-$prefix")).orDie
    )(d => ZIO.attempt(deleteRecursively(d)).orDie.ignore)

  /** Publishes a complete data/source generation through the reader protocol.
    */
  private def writeDoc(
      runDir: Path,
      doc: LineageDataDocument
  ): ZIO[Any, Nothing, Unit] =
    ZIO
      .attempt(
        LineageSnapshot.publish(
          runDir,
          doc.toJson.getBytes(UTF_8),
          doc.runId.getBytes(UTF_8)
        )
      )
      .orDie
      .flatMap {
        case Right(()) => ZIO.unit
        case Left(error) =>
          ZIO.dieMessage(s"fixture bug: store write failed: $error")
      }

  private def writeToolchain(runDir: Path, scalaVersion: String): Unit =
    val current = EnvPinning.currentEnv()
    Files.writeString(
      runDir.resolve("toolchain.json"),
      s"""{"scala":"$scalaVersion","jdk":"${current.jdkVersion}","raider":"${current.raiderVersion}"}""",
      UTF_8
    )

  /** The normalized hash of the graph recorded in `doc` (structural agreement ⇒
    * equal hashes — the comparator invariant, so expected-vs-expected is the
    * match hash).
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

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReplayCli")(
    test(
      "LIN10-01: recorded document replays to Match with the normalized graph-hash (exit 0)"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("run")
            _ <- writeDoc(runDir, ReplayHarness.document)
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield verdict match
            case ReplayCli.Verdict.Match(hash, nodes) =>
              val expectedHash = selfHash(ReplayHarness.document)
              val rendered = ReplayCli.render(verdict)
              assertTrue(
                hash == expectedHash,
                nodes == 3,
                ReplayCli.exitCode(verdict) == ExitCodes.Success,
                rendered == s"verdict: match\ngraph-hash: $hash\nnodes: 3"
              )
            case other =>
              throw new IllegalStateException(
                s"LIN10-01: expected Match, got $other"
              )
        }
      }
    },
    test(
      "LIN10-01: mutated record diverges at the round-1 tool call (Mismatch, exit 10)"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            mutated = ReplayHarness.withResponse(
              ReplayHarness.document,
              ReplayHarness.Round1Ref,
              MutatedRound1Response
            )
            runDir <- freshRunDir("mutated")
            _ <- writeDoc(runDir, mutated)
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield verdict match
            case ReplayCli.Verdict.Mismatch(diff, meta) =>
              val rendered = ReplayCli.render(verdict)
              assertTrue(
                diff.firstDivergence.reason == DivergenceReason.MissingNode,
                diff.firstDivergence.nodeId == Some(NodeId("job-1:n7")),
                diff.expectedHash != diff.actualHash,
                meta.runId == ReplayCli.RunLabel,
                meta.expectedNodes == 3,
                meta.actualNodes == 1,
                ReplayCli.exitCode(verdict) == ExitCodes.TaskRejected,
                ReplayCli.exitCode(verdict) != 0,
                rendered.startsWith("verdict: mismatch"),
                rendered.contains(diff.actualHash),
                rendered.contains("job-1:n7")
              )
            case other =>
              throw new IllegalStateException(
                s"LIN10-01: expected Mismatch, got $other"
              )
        }
      }
    },
    test("LIN8-02: matching pinned environment replays without a report") {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("pin-match")
            _ <- writeDoc(runDir, ReplayHarness.document)
            _ <- ZIO
              .attempt(
                writeToolchain(runDir, EnvPinning.currentEnv().scalaVersion)
              )
              .orDie
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield assertTrue(
            verdict.isInstanceOf[ReplayCli.Verdict.Match],
            !ReplayCli
              .render(verdict)
              .contains("reproduced in a different environment"),
            ReplayCli.exitCode(verdict) == ExitCodes.Success
          )
        }
      }
    },
    test(
      "LIN8-02: divergent pinned environment warns, attempts replay, and annotates its report"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("pin-mismatch")
            _ <- writeDoc(runDir, ReplayHarness.document)
            _ <- ZIO.attempt(writeToolchain(runDir, "3.8.2-cts")).orDie
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield verdict match
            case ReplayCli.Verdict.DifferentEnvironment(inner, fields) =>
              assertTrue(
                inner.isInstanceOf[ReplayCli.Verdict.Match],
                fields == Vector(
                  ("scala", "3.8.2-cts", EnvPinning.currentEnv().scalaVersion)
                ),
                ReplayCli
                  .render(verdict)
                  .startsWith("reproduced in a different environment:"),
                ReplayCli.render(verdict).contains("verdict: match"),
                ReplayCli.exitCode(verdict) == ExitCodes.Success
              )
            case other =>
              throw new IllegalStateException(
                s"expected reported pin divergence, got $other"
              )
        }
      }
    },
    test(
      "LIN8B-02: absent legacy pin replays without a fabricated pin report"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("legacy-no-pin")
            _ <- writeDoc(runDir, ReplayHarness.document)
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield assertTrue(
            verdict.isInstanceOf[ReplayCli.Verdict.Match],
            !ReplayCli.render(verdict).contains("environment"),
            ReplayCli.exitCode(verdict) == ExitCodes.Success
          )
        }
      }
    },
    test("LIN8B-02: malformed pin is a typed ReplayCli failure report") {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("malformed-pin")
            _ <- writeDoc(runDir, ReplayHarness.document)
            _ <- ZIO
              .attempt(
                Files.writeString(
                  runDir.resolve("toolchain.json"),
                  "{broken",
                  UTF_8
                )
              )
              .orDie
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield verdict match
            case ReplayCli.Verdict.Failed(
                  ReplayCli.Failure.Pinning(
                    EnvPinning.PinError.Malformed(path, _)
                  )
                ) =>
              val report = ReplayCli.render(verdict)
              assertTrue(
                path == runDir.resolve("toolchain.json"),
                report.startsWith("typed-error:"),
                report.contains("pinned environment invalid"),
                ReplayCli.exitCode(verdict) == ExitCodes.ConfigInvalid,
                ReplayCli.exitCode(verdict) != ExitCodes.Success
              )
            case other =>
              throw new IllegalStateException(
                s"expected typed malformed-pin report, got $other"
              )
        }
      }
    },
    test(
      "LIN3B-02: missing commit pointer is an explicit invalid snapshot"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("empty")
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield verdict match
            case ReplayCli.Verdict.Failed(
                  ReplayCli.Failure.Snapshot(
                    LineageSnapshot.Error.Missing(path)
                  )
                ) =>
              val rendered = ReplayCli.render(verdict)
              assertTrue(
                path == runDir.resolve(LineageSnapshot.CommitName),
                ReplayCli.exitCode(verdict) == ExitCodes.ConfigInvalid,
                ReplayCli.exitCode(verdict) != 0,
                rendered.startsWith("typed-error:"),
                rendered.contains("lineage snapshot invalid")
              )
            case other =>
              throw new IllegalStateException(
                s"LIN10-02: expected typed Missing, got $other"
              )
        }
      }
    },
    test(
      "LIN3B-02: malformed commit pointer is explicitly invalid"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("corrupt")
            _ <- ZIO
              .attempt(
                Files.writeString(
                  runDir.resolve(LineageSnapshot.CommitName),
                  "broken manifest\n",
                  UTF_8
                )
              )
              .orDie
            verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
          yield verdict match
            case ReplayCli.Verdict.Failed(
                  ReplayCli.Failure
                    .Snapshot(LineageSnapshot.Error.Invalid(_, _))
                ) =>
              val rendered = ReplayCli.render(verdict)
              assertTrue(
                ReplayCli.exitCode(verdict) == ExitCodes.ConfigInvalid,
                ReplayCli.exitCode(verdict) != 0,
                rendered.startsWith("typed-error:"),
                rendered.contains("lineage snapshot invalid")
              )
            case other =>
              throw new IllegalStateException(
                s"LIN10-02: expected typed Corrupt, got $other"
              )
        }
      }
    },
    test(
      "LIN10-03: parallel replays of one record over one original workspace stay isolated"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            runDir <- freshRunDir("parallel")
            _ <- writeDoc(runDir, ReplayHarness.document)
            // test-level pre-run snapshot of the ORIGINAL — the CLI must
            // leave it byte-identical even under parallel replays
            preSnapshot <- ZIO.attempt(WorkspaceIsolation.snapshot(ws)).orDie
            config = ReplayCli.Config(runDir, ws, None)
            (v1, v2) <- ReplayCli.run(config).zipPar(ReplayCli.run(config))
            postVerify <-
              ZIO
                .attempt(WorkspaceIsolation.verifyUntouched(ws, preSnapshot))
                .orDie
          yield (v1, v2, postVerify) match
            case (
                  ReplayCli.Verdict.Match(h1, _),
                  ReplayCli.Verdict.Match(h2, _),
                  Right(())
                ) =>
              assertTrue(
                // determinism: same record, same normalized hash
                h1 == h2,
                // both verdicts passed the CLI's own verifyUntouched (a
                // tampered original would have failed them typed), and the
                // test-level verify confirms the original is untouched
                postVerify.isRight,
                ReplayCli.exitCode(v1) == ExitCodes.Success,
                ReplayCli.exitCode(v2) == ExitCodes.Success
              )
            case _ =>
              throw new IllegalStateException(
                s"LIN10-03: expected two Matches over an untouched original, " +
                  s"got $v1 / $v2 / $postVerify"
              )
        }
      }
    },
    test("parse: standalone argv shape") {
      val runDir = Path.of("/tmp/run")
      val ws = Path.of("/tmp/ws")
      val data = Path.of("/tmp/other.json")
      assertTrue(
        ReplayCli.parse(Seq("replay", "/tmp/run", "--workspace", "/tmp/ws")) ==
          Right(ReplayCli.Config(runDir, ws, None)),
        ReplayCli.parse(Seq("/tmp/run", "--workspace", "/tmp/ws")) ==
          Right(ReplayCli.Config(runDir, ws, None)),
        ReplayCli.parse(
          Seq("/tmp/run", "--workspace", "/tmp/ws", "--data", "/tmp/other.json")
        ) == Right(ReplayCli.Config(runDir, ws, Some(data))),
        ReplayCli.parse(Seq.empty).isLeft,
        ReplayCli.parse(Seq("/tmp/run")).isLeft,
        ReplayCli.parse(Seq("/tmp/run", "--workspace")).isLeft,
        ReplayCli
          .parse(Seq("/tmp/run", "--workspace", "/tmp/ws", "extra"))
          .isLeft,
        ReplayCli
          .parse(Seq("/tmp/run", "--bogus", "--workspace", "/tmp/ws"))
          .isLeft
      )
    },
    test("exitCode: frozen ci-runtime §8 mapping over the typed failure set") {
      val diff = GraphDiff(
        Divergence(
          RootId("root-rec"),
          Some(NodeId("job-1:n7")),
          DivergenceReason.MissingNode
        ),
        "expected",
        "actual"
      )
      val mismatch = ReplayCli.Verdict.Mismatch(
        diff,
        RunDiffMeta(ReplayCli.RunLabel, 3, 1)
      )
      def failed(failure: ReplayCli.Failure): Int =
        ReplayCli.exitCode(ReplayCli.Verdict.Failed(failure))
      assertTrue(
        ReplayCli.exitCode(
          ReplayCli.Verdict.Match("abc", 3)
        ) == ExitCodes.Success,
        ReplayCli.exitCode(mismatch) == ExitCodes.TaskRejected,
        failed(
          ReplayCli.Failure.Data(StoreError.Missing(Path.of("/tmp/x")))
        ) == ExitCodes.ConfigInvalid,
        failed(
          ReplayCli.Failure.Data(StoreError.Corrupt(Path.of("/tmp/x"), "bad"))
        ) == ExitCodes.ConfigInvalid,
        failed(
          ReplayCli.Failure.Data(StoreError.Io(Path.of("/tmp/x"), "boom"))
        ) == ExitCodes.RuntimeFailure,
        failed(
          ReplayCli.Failure.Replay(
            RaiderError.StreamProtocol("replay backend overrun")
          )
        ) == ExitCodes.RuntimeFailure,
        failed(
          ReplayCli.Failure.Replay(RaiderError.ProviderBudget("over"))
        ) == ExitCodes.BudgetExhausted,
        failed(
          ReplayCli.Failure.Replay(RaiderError.DeadlineExceeded("late"))
        ) == ExitCodes.DeadlineExceeded,
        failed(
          ReplayCli.Failure.Replay(
            RaiderError.CapabilityUnsupported("no tools")
          )
        ) == ExitCodes.PolicyDenied,
        failed(
          ReplayCli.Failure.Isolation(IsolationError.Tampered(Path.of("f")))
        ) == ExitCodes.Uncertain,
        failed(
          ReplayCli.Failure.Isolation(
            IsolationError.OriginalMissing(Path.of("/tmp/nowhere"))
          )
        ) == ExitCodes.ConfigInvalid,
        failed(
          ReplayCli.Failure.Isolation(
            IsolationError.CopyFailed("copy failed: no space")
          )
        ) == ExitCodes.RuntimeFailure,
        failed(
          ReplayCli.Failure.SnapshotGuard("snapshot bound exceeded")
        ) == ExitCodes.ConfigInvalid,
        failed(
          ReplayCli.Failure.Record("node has no dataRef")
        ) == ExitCodes.ConfigInvalid
      )
    }
  )

end ReplayCliSpec
