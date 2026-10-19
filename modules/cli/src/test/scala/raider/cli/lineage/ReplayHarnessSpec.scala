package raider.cli.lineage

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.model.NodeId
import raider.runtime.lineage.replay.{DivergenceReason, GraphComparator}
import zio.test.*
import zio.{Ref, Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-06B-1 acceptance (packet L48, ADR-027 §2): the end-to-end replay harness
  * replays a recorded lineage-data document through an UNMODIFIED AgentLoop
  * ([[ReplayModelBackend]] + the REAL coding `fs_read` tool against an isolated
  * fixture workspace + the REAL hub/job-layer emission stack) and compares the
  * replayed graph against the graph recorded in the SAME document:
  *   - LIN5-01-H (MAIN): the replay reproduces the recorded graph —
  *     `compareNormalized` Match, 14 envelopes (1 job-layer status + 12 loop
  *     emissions + 1 Terminal), zero dropped diagnostics, dense campaign;
  *   - LIN5-01-H-2: swapping the recorded round-1 response (the tool_use round)
  *     in the document makes the replay diverge — the first divergence is the
  *     round-1 tool call node (MissingNode in the corresponding round), both
  *     hashes differ;
  *   - LIN5-01-H-3: determinism — two independent replays (fresh backend, hub,
  *     admission, job manager, isolated workspaces) yield the SAME normalized
  *     hash.
  *
  * No network, no paid calls: the only "model" is the record itself; the only
  * IO is a bounded read of the fixture workspace.
  */
object ReplayHarnessSpec extends ZIOSpecDefault:

  /** Isolated fixture workspace: fresh temp dir + the fixture file, deleted
    * after the use.
    */
  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-replay-l48")
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

  private val MutatedRound1Response =
    "Nothing to read; the workspace stays untouched."

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReplayHarness")(
    test(
      "terminal is not settled until every committed sequence reaches the sink"
    ) {
      live {
        val job = JobId("watermark-job")
        val root = RootId("watermark-root")
        val session = SessionId("watermark-session")
        for
          hub <- EventHub.make()
          _ <- hub.publish(job, root, session, EventPayload.Diagnostics("tail"))
          _ <- hub.publish(
            job,
            root,
            session,
            EventPayload.Terminal(JobStatus.Succeeded)
          )
          terminal = EventEnvelope(
            1,
            2L,
            job,
            root,
            session,
            0L,
            EventPayload.Terminal(JobStatus.Succeeded)
          )
          diagnostic = EventEnvelope(
            1,
            1L,
            job,
            root,
            session,
            0L,
            EventPayload.Diagnostics("tail")
          )
          sink <- Ref.make(Vector(terminal))
          release <- (ZIO.sleep(zio.Duration.fromMillis(120)) *>
            sink.update(_ :+ diagnostic)).fork
          started = System.nanoTime()
          _ <- ReplayHarness.eventuallyTerminal(hub, job, 0L, sink)
          elapsedMs = (System.nanoTime() - started) / 1000000L
          _ <- release.join
        yield assertTrue(elapsedMs >= 80L)
      }
    },
    test(
      "LIN5-01-H: replay of the recorded document reproduces the recorded graph (normalized Match)"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            run <- ReplayHarness.replay(ws)
          yield
            val recorded = ReplayHarness.recordedGraph(ReplayHarness.document)
            run.comparison match
              case Some(Right(hash)) =>
                assertTrue(
                  run.answer == ReplayHarness.Round2FinalResponse,
                  // 1 JobStatusChanged + 12 loop emissions + 1 Terminal
                  run.envelopeCount == 14,
                  run.droppedDiagnostics == 0L,
                  run.graph.map(_.nodes.size) == Right(3),
                  // the hash is the normalized hash of BOTH sides (the
                  // hash==GraphHash(normalize) identity is pinned by the
                  // runtime GraphComparatorSpec LIN-06B-1 corpus property)
                  hash == GraphComparator
                    .compareNormalized(recorded, recorded)
                    .toOption
                    .getOrElse("?"),
                  hash == GraphComparator
                    .compareNormalized(
                      run.graph.toOption.get,
                      recorded
                    )
                    .toOption
                    .getOrElse("?")
                )
              case other =>
                throw new IllegalStateException(
                  s"expected normalized Match, got $other; graph=${run.graph}, " +
                    s"answer=${run.answer}, envelopes=${run.envelopeCount}, " +
                    s"dropped=${run.droppedDiagnostics}"
                )
        }
      }
    },
    test(
      "LIN5-01-H-2: swapping the recorded round-1 response diverges at the round-1 tool call"
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
            run <- ReplayHarness.replay(ws, mutated)
          yield run.comparison match
            case Some(Left(diff)) =>
              assertTrue(
                // the swapped response is a plain final text: the loop ends
                // after round 1 (no tool call, no round 2)
                run.answer == MutatedRound1Response,
                // 1 JobStatusChanged + 5 loop emissions + 1 Terminal
                run.envelopeCount == 7,
                // the first divergence is the recorded round-1 tool call,
                // in NORMALIZED ids, missing on the replayed side
                diff.firstDivergence.reason == DivergenceReason.MissingNode,
                diff.firstDivergence.nodeId == Some(NodeId("job-1:n7")),
                diff.expectedHash != diff.actualHash
              )
            case other =>
              throw new IllegalStateException(
                s"expected normalized Mismatch, got $other; graph=${run.graph}"
              )
        }
      }
    },
    test(
      "LIN5-01-H-3: two independent replays produce the same normalized hash"
    ) {
      live {
        ZIO.scoped {
          for
            ws1 <- freshWorkspace
            ws2 <- freshWorkspace
            run1 <- ReplayHarness.replay(ws1)
            run2 <- ReplayHarness.replay(ws2)
          yield (run1.comparison, run2.comparison) match
            case (Some(Right(h1)), Some(Right(h2))) =>
              assertTrue(
                h1 == h2,
                run1.answer == run2.answer,
                run1.envelopeCount == run2.envelopeCount
              )
            case _ =>
              throw new IllegalStateException(
                s"expected two Matches: ${run1.comparison} vs ${run2.comparison}"
              )
        }
      }
    }
  ) @@ TestAspect.withLiveClock
  // RAI-178: export/ready polls sleep on the ambient clock; live-clock

  // discipline (docs/work/records/RAI-178.md). Assertions unchanged.

end ReplayHarnessSpec
