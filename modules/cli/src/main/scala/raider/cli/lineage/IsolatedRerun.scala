package raider.cli.lineage

import raider.core.RaiderError
import raider.dsl.lineage.data.LineageDataDocument
import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.reduce.ReduceError

import zio.{Runtime, Unsafe}

import java.nio.file.Path

/** Typed failure of the LIN-06D isolated re-run pipeline (packet R4, ADR-027 §2
  * «live re-run»). The layers of [[IsolatedRerun.rerun]] propagate as DATA,
  * never as exceptions and never masked into a fake outcome:
  *   - [[RerunError.Isolation]] — the LIN-06C file mechanics failed: the
  *     workspace copy could not be staged ([[IsolationError.CopyFailed]]), the
  *     original was missing at the moment of the call
  *     ([[IsolationError.OriginalMissing]]), or the post-run `verifyUntouched`
  *     found the original modified/deleted/added-to
  *     ([[IsolationError.Tampered]]);
  *   - [[RerunError.Snapshot]] — the pre-run byte snapshot of the original hit
  *     the LIN-06C snapshot guard (symlink/special entry, file-count bound):
  *     the guard has no error channel by its own card contract, so the guard
  *     message is carried here instead of lying through another case;
  *   - [[RerunError.Replay]] — the replayed campaign failed to complete
  *     (budget/admission/model/tool — the loop's typed `RaiderError` channel);
  *   - [[RerunError.Reduce]] — the emitted envelopes did not reduce into a
  *     valid graph (the reducer's closed `ReduceError` set).
  */
enum RerunError:

  /** Isolation layer: staging/copy failure, missing original, or a post-run
    * `verifyUntouched` verdict of TAMPERED (see [[IsolationError]]).
    */
  case Isolation(error: IsolationError)

  /** The pre-run [[WorkspaceIsolation.snapshot]] guard refused the original
    * (bound/symlink/special entry) — the guard's `IllegalStateException`
    * message, carried typed instead of propagated raw.
    */
  case Snapshot(what: String)

  /** Replay layer: the loop could not complete the recorded campaign — the
    * typed `RaiderError` of the replay harness. A replay that cannot complete
    * is a failure, never a silent graph mismatch.
    */
  case Replay(error: RaiderError)

  /** Reduce layer: the replay emitted envelopes that do not reduce into a valid
    * [[SessionGraph]].
    */
  case Reduce(error: ReduceError)
end RerunError

/** Outcome of one isolated re-run: where the throwaway copy lives, whether the
  * replayed graph matched the expected (recorded) one, and the deterministic
  * text report ([[RunDiffReport.render]] on divergence, the
  * [[RunDiffReport.fromGraphs]] match message — shared normalized hash — on
  * agreement). `isolatedPath` stays ALIVE after the call: the caller owns the
  * copy's lifecycle (per the [[WorkspaceIsolation.isolate]] contract the
  * consuming CLI layer deletes it after the report is consumed).
  */
final case class RerunOutcome(
    isolatedPath: Path,
    `match`: Boolean,
    report: String
)

/** End-to-end isolated re-run of a recorded campaign (LIN-06D, packet R4,
  * ADR-027 §2): the recorded lineage-data document is replayed against a
  * THROWAWAY copy of the workspace while the original stays byte-for-byte
  * untouched, and the replayed graph is compared against the expected
  * (recorded) graph with a deterministic diff report.
  *
  * Pipeline ([[IsolatedRerun.rerun]], one `Either[RerunError, RerunOutcome]`):
  *   1. [[WorkspaceIsolation.isolate]] — copy the original into a unique
  *      directory under `parent` (atomic move, plain regular-file trees only);
  *   2. [[WorkspaceIsolation.snapshot]] — a byte snapshot of the ORIGINAL taken
  *      from the moment custody begins, for the post-run verify;
  *   3. the replay run — [[ReplayHarness.replay]] mechanics against the
  *      ISOLATED copy ([[ReplayModelBackend]] serves the recorded answers, the
  *      real coding toolset/loop/hub/job layer emit real envelopes,
  *      [[LineageReducer]] reduces them into the replayed graph);
  *   4. [[RunDiffReport.fromGraphs]] — `compareNormalized` between the
  *      caller-supplied expected graph and the replayed graph: a divergence
  *      renders as the deterministic [[RunDiffReport]] text (the FIRST
  *      normalized divergence), agreement yields the shared-normalized-hash
  *      match message;
  *   5. [[WorkspaceIsolation.verifyUntouched]] — the original is byte-verified
  *      against the step-2 snapshot; any drift is a typed
  *      [[RerunError.Isolation]] ([[IsolationError.Tampered]]), never a
  *      silently "clean" verdict.
  *
  * Determinism (IR-01): every layer is a pure function of (record, expected
  * graph, workspace bytes) — the backend replays by ordinal, the reducer and
  * `compareNormalized` neutralize run-varying identity (job/root/session ids,
  * wall-clock `atMs`), and the report render is clock-free. Two reruns of the
  * same record over equal workspaces therefore produce byte-identical match
  * reports (and the same first divergence on divergence).
  *
  * Ownership: the isolated copy is left in place under `parent`
  * ([[RerunOutcome.isolatedPath]]); the caller deletes it when the report has
  * been consumed. Defects of the replay runtime (interrupted fibers, broken
  * invariants) are NOT data: they propagate on the exception channel — a
  * crashed pipeline is loud, never a fabricated outcome.
  */
object IsolatedRerun:

  /** The report run label for every isolated re-run (deterministic — part of
    * the byte-stable report text).
    */
  val RunId = "isolated-rerun"

  /** The full pipeline — see the object scaladoc for the layer order and the
    * ownership/determinism contracts.
    */
  def rerun(
      recorded: LineageDataDocument,
      expectedGraph: SessionGraph,
      originalWorkspace: Path,
      parent: Path
  ): Either[RerunError, RerunOutcome] =
    WorkspaceIsolation.isolate(originalWorkspace, parent) match
      case Left(error) => Left(RerunError.Isolation(error))
      case Right(isolated) =>
        snapshotOriginal(originalWorkspace).flatMap { before =>
          replay(isolated, recorded).flatMap { run =>
            run.graph.left.map(RerunError.Reduce(_)).flatMap { actual =>
              val (matched, report) = verdict(expectedGraph, actual)
              verifyUntouched(originalWorkspace, before).map { _ =>
                RerunOutcome(isolated, matched, report)
              }
            }
          }
        }

  /** Byte snapshot of the original (step 2): the baseline `verifyUntouched`
    * compares against — taken after the copy exists, so any later drift of the
    * original is attributable to the rerun window, not to pre-copy history. The
    * LIN-06C guard has no error channel; its refusal is carried typed
    * ([[RerunError.Snapshot]]).
    */
  private def snapshotOriginal(
      original: Path
  ): Either[RerunError, Map[Path, Array[Byte]]] =
    try Right(WorkspaceIsolation.snapshot(original))
    catch
      case error: IllegalStateException =>
        Left(RerunError.Snapshot(String.valueOf(error.getMessage)))

  /** The replay run (step 3): the [[ReplayHarness]] mechanics against the
    * ISOLATED copy, executed to completion on the default runtime. The typed
    * `RaiderError` channel becomes [[RerunError.Replay]]; defects stay on the
    * exception channel (see the object scaladoc).
    */
  private def replay(
      isolated: Path,
      recorded: LineageDataDocument
  ): Either[RerunError, ReplayHarness.ReplayRun] =
    try
      Right(
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(ReplayHarness.replay(isolated, recorded))
            .getOrThrow()
        }
      )
    catch case error: RaiderError => Left(RerunError.Replay(error))

  /** Compare + render (step 4): a divergence renders the deterministic report
    * (match = false); agreement yields the shared-normalized-hash message
    * (match = true) — a Match is not a report, per [[RunDiffReport]].
    */
  private def verdict(
      expected: SessionGraph,
      actual: SessionGraph
  ): (Boolean, String) =
    RunDiffReport.fromGraphs(expected, actual, RunId) match
      case Right(report) => (false, report.render)
      case Left(message) => (true, message)

  /** Byte-verify the original (step 5): any modified/deleted/added entry is
    * [[RerunError.Isolation]] ([[IsolationError.Tampered]]) — the rerun's core
    * guarantee, checked before any success is reported.
    */
  private def verifyUntouched(
      original: Path,
      before: Map[Path, Array[Byte]]
  ): Either[RerunError, Unit] =
    WorkspaceIsolation
      .verifyUntouched(original, before)
      .left
      .map(RerunError.Isolation(_))

end IsolatedRerun
