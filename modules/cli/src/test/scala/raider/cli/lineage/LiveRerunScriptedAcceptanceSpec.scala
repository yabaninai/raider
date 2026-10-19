package raider.cli.lineage

import raider.core.RaiderError
import raider.dsl.lineage.data.{
  DataNode,
  LineageDataDocument,
  NodeData,
  NodeKind
}
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.SessionGraph

import scala.jdk.CollectionConverters.*

import zio.test.*
import zio.{Duration as ZDuration, Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-06 scripted acceptance (card LIN-15, ADR-027 §2 «live re-run») —
  * SCRIPTED backend only: the only "model" is the record itself
  * ([[ReplayModelBackend]] ordinal playback); NO live model, NO network, NO
  * paid calls. Every fixture is marked `SC` (scripted) in its ID.
  *
  * Complements (never duplicates) the LIN-06B/C/D unit specs
  * ([[ReplayHarnessSpec]]/[[WorkspaceIsolationSpec]]/[[IsolatedRerunSpec]]):
  *   - LIN6-01-SC: an isolated rerun of a PHASED record completes and the diff
  *     report is PER-PHASE ([[RunDiffReport]] whole-graph verdict + the
  *     [[PhaseReplay]]-based phase section) and DETERMINISTIC — a repeat rerun
  *     over a fresh isolated copy reports byte-for-byte identically; the live
  *     side honestly diverges at the phase grouping (no wire case —
  *     docs/lineage.md §8), which is the report's first divergence.
  *   - LIN6-02-SC budget: an EXHAUSTED budget stops the rerun TYPED
  *     ([[RerunError.Replay]] carrying the Admission `LocalBudgetExceeded`)
  *     BEFORE the mutating tool executes — the isolated copy never receives the
  *     dirt.
  *   - LIN6-02-SC guard: the PRODUCT-path isolation guard
  *     ([[IsolatedRerun.requireIsolatedTarget]]) refuses a mutating record
  *     aimed at a non-isolated target TYPED, BEFORE any execution (bytes and
  *     parent directory prove nothing ran); a faithful isolated copy is
  *     accepted; a ReadOnly record needs no isolation.
  *   - LIN6-03-SC: an interrupt issued MID-RERUN (while the mutating tool's
  *     dirt is landing in the isolated copy) leaves the ORIGINAL untouched
  *     (verifyUntouched discipline), the copy stays classified/consistent
  *     (complete dirt, faithful fixture file), and a rerun AFTER the
  *     cancellation gives a consistent verdict without duplicated side effects
  *     (no dirt in the original, byte-identical repeat report).
  */
object LiveRerunScriptedAcceptanceSpec extends ZIOSpecDefault:

  /** The expected side of the phaseless comparisons: the graph recorded in the
    * frozen fixture document.
    */
  private val cleanGraph = ReplayHarness.recordedGraph(ReplayHarness.document)

  /** The dirt file the scripted mutating round creates (same record convention
    * as IsolatedRerunSpec IR-02: the recorded RESPONSE carries an `fs_edit`
    * `tool_calls` envelope; empty `expected_sha256` = create).
    */
  private val DirtyFile = "dirty-after-isolate.txt"

  private val DirtyRound1Response =
    """{"tool_calls":[{"id":"call-1","name":"fs_edit",""" +
      """"arguments":{"path":"dirty-after-isolate.txt","content":"dirt","expected_sha256":""}}]}"""

  /** The scripted MUTATING record: same nodes as the frozen fixture, the
    * round-1 response swapped to the fs_edit envelope. Its data annotation
    * still says fs_read/ReadOnly — deliberately, so the guard is proven against
    * the RESPONSE the loop actually executes (the IR-02 lesson).
    */
  private def dirtyDocument: LineageDataDocument =
    ReplayHarness.withResponse(
      ReplayHarness.document,
      ReplayHarness.Round1Ref,
      DirtyRound1Response
    )

  private val dirtyGraph = ReplayHarness.recordedGraph(dirtyDocument)

  /** The scripted PHASED record (LIN6-01-SC): a Phase node (seq 2) wrapping the
    * round-1 subtree — the round-1 model call becomes its child; the tool call
    * stays the model call's child; the round-2 call stays a root. The phase
    * carries a PhaseData entry (the golden-convention name/note payload).
    */
  private val PhaseRef = s"${ReplayHarness.RecordJob}/phase-1"

  private def phasedDocument: LineageDataDocument =
    val base = ReplayHarness.document
    val phaseId = s"${ReplayHarness.RecordJob}:n2"
    base.copy(
      nodes = Vector(
        DataNode(
          id = phaseId,
          kind = NodeKind.Phase,
          parentId = None,
          seq = 2L,
          atMs = 500L,
          inputs = Vector.empty,
          dataRef = Some(PhaseRef)
        )
      ) ++
        Vector(
          base.nodes(0).copy(parentId = Some(phaseId)), // round-1 model call
          base.nodes(1), // the fs_read tool call (child of the model call)
          base.nodes(2) // round-2 model call (outside the phase)
        ),
      data = base.data.updated(
        PhaseRef,
        NodeData.PhaseData(name = "read-notes", note = Some("scripted"))
      )
    )

  private val phasedGraph = ReplayHarness.recordedGraph(phasedDocument)

  /** Exhausted ceilings (LIN6-02-SC): ONE attempt admits the round-1 model call
    * and nothing else — the tool dispatch is refused by the Admission budget
    * ([[RaiderError.LocalBudgetExceeded]] via maxAttempts).
    */
  private val ExhaustedLimits: BudgetLimits =
    BudgetLimits
      .make(maxAttempts = 1, maxConcurrentLlm = 1, maxConcurrentTools = 1)
      .fold(e => sys.error(s"fixture bug: limits: $e"), identity)

  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-lin15sc-ws")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  private def freshParent: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("raider-lin15sc-parent")).orDie
    )(p => ZIO.attempt(deleteRecursively(p)).orDie.ignore)

  private def freshForeign: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val dir = Files.createTempDirectory("raider-lin15sc-foreign")
        Files.writeString(dir.resolve("other.txt"), "not the fixture\n", UTF_8)
        dir
      }.orDie
    )(d => ZIO.attempt(deleteRecursively(d)).orDie.ignore)

  private def snapshotOf(ws: Path): ZIO[Any, Nothing, Map[Path, Array[Byte]]] =
    ZIO.attempt(WorkspaceIsolation.snapshot(ws)).orDie

  private def verifyUntouched(
      ws: Path,
      before: Map[Path, Array[Byte]]
  ): ZIO[Any, Nothing, Either[IsolationError, Unit]] =
    ZIO.attempt(WorkspaceIsolation.verifyUntouched(ws, before)).orDie

  /** [[IsolatedRerun.rerun]] with the typed error channel turned into a loud
    * fixture failure — used where the case EXPECTS a completed outcome.
    */
  private def rerunOf(
      doc: LineageDataDocument,
      expected: SessionGraph,
      ws: Path,
      parent: Path
  ): ZIO[Any, Nothing, RerunOutcome] =
    ZIO.attempt {
      IsolatedRerun.rerun(doc, expected, ws, parent) match
        case Right(outcome) => outcome
        case Left(error) =>
          throw new IllegalStateException(
            s"expected a successful rerun, got $error"
          )
    }.orDie

  /** Directories directly under `parent`, sorted (fixture observation). */
  private def listDirs(parent: Path): Vector[Path] =
    val stream = Files.list(parent)
    try
      stream
        .iterator()
        .asScala
        .toVector
        .filter(Files.isDirectory(_))
        .sortBy(
          _.toString
        )
    finally stream.close()

  /** Copies under `parent` that already carry the dirt file. */
  private def dirtDirs(parent: Path): Vector[Path] =
    listDirs(parent).filter(d => Files.exists(d.resolve(DirtyFile)))

  /** Bounded wait for the deterministic mid-rerun marker: the scripted fs_edit
    * has landed the dirt inside SOME isolated copy (the rerun is in flight,
    * past its mutating tool). Fails LOUD on timeout — a rerun that never
    * produces the dirt breaks the fixture's premises.
    */
  private def waitUntilAnyCopyHasDirt(parent: Path): ZIO[Any, String, Unit] =
    val deadline = System.nanoTime() + ZDuration.fromSeconds(10).toNanos
    def loop: ZIO[Any, String, Unit] =
      ZIO.attempt(dirtDirs(parent)).orDie.flatMap {
        case found if found.nonEmpty => ZIO.unit
        case _ =>
          if System.nanoTime() >= deadline then
            ZIO.fail(
              "the scripted mutating tool never landed dirt in any isolated copy within the outer bound"
            )
          else ZIO.sleep(ZDuration.fromMillis(5)) *> loop
      }
    loop

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("LiveRerunScriptedAcceptance")(
      test(
        "LIN6-01-SC: phased record reruns in isolation; the diff report is per-phase and byte-identical across reruns"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              before <- snapshotOf(ws)
              first <- rerunOf(phasedDocument, phasedGraph, ws, parent)
              second <- rerunOf(phasedDocument, phasedGraph, ws, parent)
              untouched <- verifyUntouched(ws, before)
              distinctCopies <-
                ZIO.attempt(first.isolatedPath != second.isolatedPath).orDie
            yield assertTrue(
              // the rerun COMPLETES; the whole-graph verdict honestly diverges
              // at the phase grouping (the replayed graph never carries phases)
              !first.`match`,
              first.report.contains("first-divergence: missing-node"),
              first.report.contains("job-1:n2"),
              // the per-phase section: identity, slice hash, whole-slice label
              first.report.contains(
                "phase job-rec:n2 name=read-notes subtree=resolved(1) hash="
              ),
              // per-child rows and the deferred tool obligation
              first.report.contains(
                "child job-rec:n4 resolved dataRef=job-rec/round-1"
              ),
              first.report.contains(
                "deferred job-rec:n7 tool=fs_read recovery=ReadOnly"
              ),
              // determinism: a repeat rerun over a FRESH isolated copy reports
              // byte-for-byte identically
              first.report == second.report,
              distinctCopies,
              untouched == Right(())
            )
          }
        }
      },
      test(
        "LIN6-02-SC budget: an exhausted budget stops the rerun typed before the mutating tool executes"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              before <- snapshotOf(ws)
              outcome <-
                ZIO
                  .attempt(
                    IsolatedRerun.rerun(
                      dirtyDocument,
                      dirtyGraph,
                      ws,
                      parent,
                      limits = ExhaustedLimits,
                      maxRounds = 1
                    )
                  )
                  .orDie
              copyDirtFree <- ZIO.attempt {
                // the budget refusal is BEFORE the tool dispatch: no copy
                // under parent ever receives the dirt
                listDirs(parent).forall(d =>
                  !Files.exists(d.resolve(DirtyFile))
                )
              }.orDie
              untouched <- verifyUntouched(ws, before)
            yield outcome match
              case Left(
                    RerunError.Replay(
                      err: RaiderError.LocalBudgetExceeded
                    )
                  ) =>
                assertTrue(
                  err.detail.contains("maxAttempts=1"),
                  copyDirtFree,
                  untouched == Right(())
                )
              case other =>
                throw new IllegalStateException(
                  s"expected typed Replay(LocalBudgetExceeded), got $other"
                )
          }
        }
      },
      test(
        "LIN6-02-SC guard: a mutating record aimed at a non-isolated target is refused typed BEFORE any execution"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              before <- snapshotOf(ws)
              // the record IS mutating — proven from the RESPONSE envelope,
              // not the (deliberately stale) data annotation
              tools = IsolatedRerun.mutatingTools(dirtyDocument)
              inPlace <-
                ZIO
                  .attempt(
                    IsolatedRerun.requireIsolatedTarget(dirtyDocument, ws, ws)
                  )
                  .orDie
              // the guard creates NOTHING and runs NOTHING: bytes unchanged,
              // parent still empty
              untouched <- verifyUntouched(ws, before)
              parentEmpty <- ZIO.attempt(listDirs(parent).isEmpty).orDie
              // a ReadOnly record needs no isolation: in-place is accepted
              readOnlyInPlace <-
                ZIO
                  .attempt(
                    IsolatedRerun
                      .requireIsolatedTarget(ReplayHarness.document, ws, ws)
                  )
                  .orDie
              // the sanctioned target: a faithful isolated copy is accepted
              copyPath <- ZIO.attempt {
                WorkspaceIsolation.isolate(ws, parent) match
                  case Right(p) => p
                  case Left(error) =>
                    throw new IllegalStateException(
                      s"fixture bug: isolate failed: $error"
                    )
              }.orDie
              copyAccepted <-
                ZIO
                  .attempt(
                    IsolatedRerun
                      .requireIsolatedTarget(dirtyDocument, ws, copyPath)
                  )
                  .orDie
              // a foreign directory is NOT a faithful copy of THIS original
              foreign <- freshForeign
              foreignRefused <-
                ZIO
                  .attempt(
                    IsolatedRerun
                      .requireIsolatedTarget(dirtyDocument, ws, foreign)
                  )
                  .orDie
            yield assertTrue(
              tools == Vector("fs_edit"),
              inPlace == Left(RerunError.MutatingUnisolated(Vector("fs_edit"))),
              untouched == Right(()),
              parentEmpty,
              readOnlyInPlace == Right(()),
              copyAccepted == Right(()),
              foreignRefused == Left(
                RerunError.MutatingUnisolated(Vector("fs_edit"))
              )
            )
          }
        }
      },
      test(
        "LIN6-03-SC: interrupt mid-rerun leaves the original untouched; the copy stays classified; a rerun after the cancel is consistent without duplicated effects"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              before <- snapshotOf(ws)
              // rerun #1 on a fiber; the scripted fs_edit dirt lands INSIDE
              // the isolated copy while the rerun is still in flight
              fiber <- ZIO
                .attempt(
                  IsolatedRerun.rerun(dirtyDocument, dirtyGraph, ws, parent)
                )
                .orDie
                .forkDaemon
              _ <- waitUntilAnyCopyHasDirt(parent)
              _ <- fiber.interrupt
              // state at cancellation time: the abandoned attempt's copies
              copiesAtCancel <- ZIO.attempt(listDirs(parent)).orDie
              untouchedAfterCancel <- verifyUntouched(ws, before)
              dirtInOriginalAfterCancel <-
                ZIO.attempt(Files.exists(ws.resolve(DirtyFile))).orDie
              // the copies stay CLASSIFIED/CONSISTENT: complete dirt, the
              // faithful fixture file intact, nothing else added
              copiesConsistent <- ZIO.attempt {
                copiesAtCancel.forall { copy =>
                  val dirt = copy.resolve(DirtyFile)
                  Files.isRegularFile(dirt) &&
                  Files.readString(dirt, UTF_8) == "dirt" &&
                  Files.readString(
                    copy.resolve(ReplayHarness.FixtureFile),
                    UTF_8
                  ) == ReplayHarness.FixtureFileContent
                }
              }.orDie
              // rerun AFTER the cancellation — the product path gives a
              // consistent verdict, no duplicated side effects
              second <- rerunOf(dirtyDocument, dirtyGraph, ws, parent)
              third <- rerunOf(dirtyDocument, dirtyGraph, ws, parent)
              dirtOriginalAtEnd <-
                ZIO.attempt(Files.exists(ws.resolve(DirtyFile))).orDie
              dirtInSecondCopy <-
                ZIO
                  .attempt(Files.exists(second.isolatedPath.resolve(DirtyFile)))
                  .orDie
              dirtInThirdCopy <-
                ZIO
                  .attempt(Files.exists(third.isolatedPath.resolve(DirtyFile)))
                  .orDie
              finalUntouched <- verifyUntouched(ws, before)
            yield assertTrue(
              copiesAtCancel.nonEmpty,
              copiesConsistent,
              // the original: never touched, by the cancelled attempt NOR later
              untouchedAfterCancel == Right(()),
              !dirtInOriginalAfterCancel,
              !dirtOriginalAtEnd,
              finalUntouched == Right(()),
              // consistent verdict after the cancel: same mismatch, same report
              !second.`match`,
              second.report == third.report,
              second.report.contains("first-divergence: field-mismatch(tool)"),
              second.report.contains("job-1:n7"),
              // exactly one dirt per isolated copy (no duplicate side effects)
              dirtInSecondCopy,
              dirtInThirdCopy
            )
          }
        }
      }
    ) @@ TestAspect.withLiveClock
    // RAI-178: readiness/export polls sleep on the ambient clock; live-clock
    // discipline (docs/work/records/RAI-178.md). Assertions unchanged.

end LiveRerunScriptedAcceptanceSpec
