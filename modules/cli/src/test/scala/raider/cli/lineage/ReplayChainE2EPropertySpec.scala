package raider.cli.lineage

import raider.runtime.lineage.replay.GraphComparator
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** W22-R8 end-to-end property sweep over the replay harness on the existing
  * production code (packet R8; tests only — no production change): the frozen
  * L48 record is replayed through the REAL stack ([[ReplayHarness.replay]] —
  * unmodified [[raider.runtime.loop.AgentLoop]], [[ReplayModelBackend]], the
  * real `fs_read` tool against an isolated fixture workspace, the real
  * hub/job-layer emission stack, the real reducer and normalized comparator)
  * for 12 seed-indexed runs (packet floor: ≥ 10):
  *   - every run is a normalized Match whose hash equals the canonical
  *     self-comparison hash of the recorded graph;
  *   - the REPEAT run (fresh backend/hub/admission/manager/workspace) yields
  *     the SAME hash — H-3 determinism swept (LIN5-01-H-3 × 12).
  *
  * No network, no paid calls: the only "model" is the record itself; the only
  * IO is a bounded read of the fixture workspace.
  */
object ReplayChainE2EPropertySpec extends ZIOSpecDefault:

  /** Seeds 1..12 — one run pair (run + repeat) per seed. */
  private val Seeds = (1 to 12).toVector

  /** Isolated fixture workspace: fresh temp dir + the fixture file, deleted
    * after the use (same discipline as ReplayHarnessSpec).
    */
  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-replay-w22-r8")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  /** The canonical normalized hash BOTH a replay and its repeat must land on:
    * the recorded graph compared against itself.
    */
  private val expectedHash: String =
    GraphComparator
      .compareNormalized(
        ReplayHarness.recordedGraph(ReplayHarness.document),
        ReplayHarness.recordedGraph(ReplayHarness.document)
      )
      .toOption
      .getOrElse(
        throw new IllegalStateException("recorded self-compare failed")
      )

  /** SEQUENTIAL on purpose: in one observed cold-start full-suite run, 2 of the
    * 12 seed tests flaked when the real loop/hub/toolset replays ran
    * concurrently under first-run JIT/zinc load in the same JVM. Sequencing the
    * suite is deliberate protection against THIS self-contention: 12 seed tests
    * × 2 replays of the real stack must not contend with EACH OTHER on top of
    * whatever else the suite JVM is doing. The deterministic contract
    * (normalized Match, repeat → same hash) is scheduling-independent;
    * sequencing only bounds self-inflicted relay latency inside
    * `ReplayHarness.eventuallyTerminal`'s outer bound.
    */
  def spec = suite("ReplayChainE2EProperty")(
    Seeds.map { seed =>
      test(
        s"PROP-01 seed $seed: harness replay is a normalized Match; repeat yields the same hash"
      ) {
        live {
          ZIO.scoped {
            for
              ws1 <- freshWorkspace
              run1 <- ReplayHarness.replay(ws1)
              ws2 <- freshWorkspace
              run2 <- ReplayHarness.replay(ws2)
            yield (run1.comparison, run2.comparison) match
              case (Some(Right(h1)), Some(Right(h2))) =>
                assertTrue(
                  h1 == expectedHash,
                  h2 == expectedHash,
                  h1 == h2,
                  run1.answer == ReplayHarness.Round2FinalResponse,
                  run2.answer == ReplayHarness.Round2FinalResponse,
                  run1.droppedDiagnostics == 0L,
                  run2.droppedDiagnostics == 0L,
                  run1.graph.map(_.nodes.size) == Right(3),
                  run2.graph.map(_.nodes.size) == Right(3),
                  run1.envelopeCount == 14,
                  run2.envelopeCount == 14
                )
              case (c1, c2) =>
                throw new IllegalStateException(
                  s"seed $seed: expected two normalized Matches, got $c1 vs $c2; " +
                    s"answers=${run1.answer}/${run2.answer}, " +
                    s"envelopes=${run1.envelopeCount}/${run2.envelopeCount}, " +
                    s"dropped=${run1.droppedDiagnostics}/${run2.droppedDiagnostics}"
                )
          }
        }
      }
    }
  ) @@ TestAspect.sequential

end ReplayChainE2EPropertySpec
