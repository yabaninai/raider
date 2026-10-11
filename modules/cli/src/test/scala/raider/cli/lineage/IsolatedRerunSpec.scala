package raider.cli.lineage

import raider.dsl.lineage.data.LineageDataDocument
import raider.runtime.lineage.model.SessionGraph

import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-06D acceptance (packet R4, ADR-027 §2): the end-to-end isolated re-run —
  * fixture → [[IsolatedRerun.rerun]] — over the LIN-06B/06C mechanics
  * ([[ReplayHarness]] replay, [[WorkspaceIsolation]] copy/verify,
  * [[RunDiffReport]] render):
  *   - IR-01 (MAIN): the recorded campaign reruns to a normalized MATCH with a
  *     byte-stable report (two independent reruns — fresh isolated copies —
  *     report byte-for-byte identically) and the original workspace is
  *     untouched;
  *   - IR-02: a DIRTY run — the recorded round-1 response is an `fs_edit`
  *     `tool_calls` envelope, so the replay CREATES a file in the workspace it
  *     runs against — lands the dirt in the ISOLATED copy only (the original
  *     stays byte-for-byte untouched, `verifyUntouched` clean), and the
  *     diverging run reports the FIRST normalized divergence
  *     (field-mismatch(tool) at the round-1 tool call node `job-1:n7`);
  *   - IR-03: a missing workspace fails TYPED
  *     ([[RerunError.Isolation]]([[IsolationError.OriginalMissing]])) before
  *     anything is created under `parent`.
  *
  * No network, no paid calls: the only "model" is the record itself; the only
  * IO is a bounded read/write inside fixture-sized workspaces.
  */
object IsolatedRerunSpec extends ZIOSpecDefault:

  /** The expected side of every comparison: the graph recorded in the frozen
    * fixture document (the same construction the harness pins).
    */
  private val expectedGraph: SessionGraph =
    ReplayHarness.recordedGraph(ReplayHarness.document)

  /** The dirty-run residue file the mutated round-1 response creates. */
  private val DirtyFile = "dirty-after-isolate.txt"

  /** The LIN-06D dirty round: same envelope shape/call id as the recording, but
    * the tool is `fs_edit` (empty `expected_sha256` = create). The replay
    * executes it for real — against the ISOLATED copy.
    */
  private val DirtyRound1Response =
    """{"tool_calls":[{"id":"call-1","name":"fs_edit",""" +
      """"arguments":{"path":"dirty-after-isolate.txt","content":"dirt","expected_sha256":""}}]}"""

  private def dirtyDocument: LineageDataDocument =
    ReplayHarness.withResponse(
      ReplayHarness.document,
      ReplayHarness.Round1Ref,
      DirtyRound1Response
    )

  /** Isolated fixture workspace: fresh temp dir + the fixture file, deleted
    * after the use.
    */
  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-rerun-lin06d")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  /** Fresh parent directory for the isolated copies. */
  private def freshParent: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("raider-rerun-parent")).orDie
    )(p => ZIO.attempt(deleteRecursively(p)).orDie.ignore)

  /** Pre-run byte snapshot of a workspace (the external evidence baseline). */
  private def snapshotOf(ws: Path): ZIO[Any, Nothing, Map[Path, Array[Byte]]] =
    ZIO.attempt(WorkspaceIsolation.snapshot(ws)).orDie

  /** [[IsolatedRerun.rerun]] with the typed error channel turned into a loud
    * fixture failure — the acceptance cases below expect SPECIFIC outcomes,
    * never an unspecific error.
    */
  private def rerunOf(
      doc: LineageDataDocument,
      ws: Path,
      parent: Path
  ): ZIO[Any, Nothing, RerunOutcome] =
    ZIO.attempt {
      IsolatedRerun.rerun(doc, expectedGraph, ws, parent) match
        case Right(outcome) => outcome
        case Left(error) =>
          throw new IllegalStateException(
            s"expected a successful rerun, got $error"
          )
    }.orDie

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("IsolatedRerun")(
    test(
      "IR-01: rerun of the recorded campaign matches, report is byte-stable across reruns, original untouched"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            parent <- freshParent
            before <- snapshotOf(ws)
            first <- rerunOf(ReplayHarness.document, ws, parent)
            second <- rerunOf(ReplayHarness.document, ws, parent)
            untouched <-
              ZIO.attempt(WorkspaceIsolation.verifyUntouched(ws, before)).orDie
            // FS checks are bound EAGERLY (zio.test assertTrue args evaluate
            // lazily — after this scope's finalizers delete the temp dirs)
            copyIsDir <-
              ZIO.attempt(Files.isDirectory(first.isolatedPath)).orDie
          yield assertTrue(
            first.`match`,
            second.`match`,
            // determinism (IR-01): two independent reruns — fresh isolated
            // copies, fresh backends — report byte-for-byte identically
            first.report == second.report,
            first.report.startsWith("graphs match: no divergence to report"),
            // the copy is alive, unique and NOT the original
            first.isolatedPath != ws,
            copyIsDir,
            // external double-check of the pipeline's own step 5
            untouched == Right(())
          )
        }
      }
    },
    test(
      "IR-02: dirty run dirties the isolated copy only; the diverging report carries the first divergence"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            parent <- freshParent
            before <- snapshotOf(ws)
            outcome <- rerunOf(dirtyDocument, ws, parent)
            dirtInCopy <-
              ZIO
                .attempt(Files.exists(outcome.isolatedPath.resolve(DirtyFile)))
                .orDie
            dirtInOriginal <-
              ZIO.attempt(Files.exists(ws.resolve(DirtyFile))).orDie
            untouched <-
              ZIO.attempt(WorkspaceIsolation.verifyUntouched(ws, before)).orDie
          yield assertTrue(
            // the graphs differ: the replayed round-1 tool call is fs_edit
            !outcome.`match`,
            // the FIRST normalized divergence: field-mismatch(tool) at the
            // round-1 tool call node (canonical job map: job-1:n7)
            outcome.report.contains("first-divergence: field-mismatch(tool)"),
            outcome.report.contains("job-1:n7"),
            outcome.report.contains("expected: fs_read"),
            outcome.report.contains("actual: fs_edit"),
            // the dirt landed in the ISOLATED copy only
            dirtInCopy,
            !dirtInOriginal,
            // rerun's step 5 passed (Right outcome) AND the external check agrees
            untouched == Right(())
          )
        }
      }
    },
    test(
      "IR-03: a missing workspace fails typed before anything is created under parent"
    ) {
      live {
        ZIO.scoped {
          for
            parent <- freshParent
            missing = parent.resolve("no-such-workspace")
            outcome <- ZIO
              .attempt(
                IsolatedRerun.rerun(
                  ReplayHarness.document,
                  expectedGraph,
                  missing,
                  parent
                )
              )
              .orDie
            parentEmpty <- ZIO.attempt {
              val stream = Files.list(parent)
              try !stream.iterator().hasNext
              finally stream.close()
            }.orDie
          yield outcome match
            case Left(
                  RerunError.Isolation(IsolationError.OriginalMissing(path))
                ) =>
              assertTrue(path == missing, parentEmpty)
            case other =>
              throw new IllegalStateException(
                s"expected typed OriginalMissing, got $other"
              )
        }
      }
    }
  )

end IsolatedRerunSpec
