package raider.cli.lineage

import raider.core.RaiderError
import raider.core.result.ExitCodes
import raider.dsl.lineage.data.LineageDataDocument
import raider.dsl.lineage.store.{LineageDataStore, StoreError}
import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.reduce.ReduceError
import raider.runtime.lineage.replay.GraphDiff

import zio.{UIO, ZIO}
import zio.Unsafe

import java.nio.file.{Files, Path}
import java.util.UUID

import scala.jdk.CollectionConverters.*

/** Standalone CLI entry of the replay chain (LIN-10, docs/tasks/lineage.md,
  * ADR-027 §4): reads the recorded `lineage-data.json` of a run directory,
  * replays it through the frozen [[ReplayHarness]] against a THROWAWAY copy of
  * the workspace, and reports the verdict.
  *
  * Recorded-only by construction: the "model" behind the replay is the record
  * itself ([[ReplayModelBackend]] plays the recorded answers by ordinal) — no
  * network, no provider, no paid calls. The verdict is the
  * `GraphComparator.compareNormalized` outcome over the graph recorded in the
  * document ([[ReplayHarness.recordedGraph]]) and the graph the replay actually
  * emitted: `Match` carries the shared NORMALIZED graph-hash; `Mismatch`
  * carries the first divergent node (normalized ids) via the deterministic
  * [[RunDiffReport]] text.
  *
  * Isolation (LIN10-03, the LIN-06C mechanics): the original workspace is never
  * written — [[WorkspaceIsolation.isolate]] stages a unique throwaway copy per
  * invocation (unique staging parent per run, so PARALLEL replays of the same
  * run directory never share a write target), the replay runs against the copy,
  * and [[WorkspaceIsolation.verifyUntouched]] byte-verifies the original
  * afterwards. A tampered original fails the whole command typed
  * ([[Failure.Isolation]]) regardless of the comparison outcome — a verdict
  * produced while the isolation contract is broken is not reportable.
  *
  * Honest scope: the harness replays the recorded campaign through the real
  * loop with the frozen emission layout (see [[ReplayHarness]] scaladoc — the
  * recorded node `seq` values must match the loop's emission seqs). A record
  * recorded through the same loop matches; anything else diverges LOUDLY at the
  * first differing field — that is the comparator working, not a defect.
  *
  * Typed failures (LIN10-02) use the closed [[Failure]] set over the frozen
  * error types (`StoreError`, `IsolationError`, `RaiderError`, `ReduceError`):
  * a missing or corrupt lineage-data file is a typed failure, never a crash
  * masked into an exit code and never a fake verdict. Runtime defects (broken
  * invariants, interrupted fibers) are NOT data — they propagate and crash the
  * JVM loudly.
  *
  * Exit codes reuse the frozen ci-runtime §8 mapping
  * (`raider.core.result.ExitCodes`) — no invented codes:
  *   - 0 — Match;
  *   - 10 — Mismatch (verifier rejection: the replayed chain diverged);
  *   - 21 — config/input invalid (missing/corrupt lineage-data, missing
  *     workspace, refused workspace shape, un-translatable record, argv usage);
  *   - 20 — runtime failure (IO environment, replay loop typed failure, reduce
  *     failure);
  *   - 22 — capability denial, 23 — budget, 24 — deadline (mapped from the
  *     typed `RaiderError` instance);
  *   - 26 — outcome uncertain (the original workspace was tampered during the
  *     replay — the verdict cannot be trusted).
  *
  * Standalone by card contract: this object owns its argv parsing and entry
  * point; wiring into the product `raider` command surface is a separate
  * coordinator card (`raider.cli.run` is another lane — untouched here).
  */
object ReplayCli:

  /** File name of the recorded data resource inside a run directory (the LIN-09
    * artifact layout: the lineage artifacts sit NEXT TO the run artifacts).
    */
  val DataFileName = "lineage-data.json"

  /** Run label of the mismatch report (deterministic — part of the rendered
    * [[RunDiffReport]] text).
    */
  val RunLabel = "replay-cli"

  /** Parsed command line: the run directory (holding [[DataFileName]] unless
    * [[Config.dataFile]] overrides), the ORIGINAL workspace the record was made
    * against, and an optional explicit data-file path.
    */
  final case class Config(
      runDir: Path,
      workspace: Path,
      dataFile: Option[Path]
  )

  /** Closed typed-failure set of the CLI layer (LIN10-02): one case per frozen
    * error source, no stringly-typed catch-all. `Record` covers a schema-valid
    * document that does not translate into a recorded graph
    * ([[ReplayHarness.recordedGraph]] refuses it) — an input-validity fact, not
    * a runtime defect.
    */
  enum Failure:

    /** The lineage-data file could not be read/decoded
      * (`Missing`/`Corrupt`/`Io` — the frozen [[LineageDataStore]] taxonomy).
      */
    case Data(error: StoreError)

    /** The pre-run byte snapshot of the original workspace was refused by the
      * LIN-06C guard (symlink/special entry, file-count bound).
      */
    case SnapshotGuard(what: String)

    /** A schema-valid record that [[ReplayHarness.recordedGraph]] refuses
      * (missing dataRef, kind/data mismatch, unknown recovery/outcome, invalid
      * graph).
      */
    case Record(what: String)

    /** Isolation layer: staging/copy failure, missing original, or the post-run
      * `verifyUntouched` verdict of TAMPERED.
      */
    case Isolation(error: IsolationError)

    /** The replay loop failed typed (`RaiderError` channel; the typed
      * [[ReplayBackendError]] instance rides the cause chain and is recovered
      * for rendering).
      */
    case Replay(error: RaiderError)

    /** The replayed envelopes do not reduce into a valid graph. */
    case Reduce(error: ReduceError)
  end Failure

  /** The command outcome (LIN10-01): a `Match` with the shared normalized
    * graph-hash, a `Mismatch` with the first divergence
    * (`RunDiffReport.render`-able), or a typed [[Failure]].
    */
  enum Verdict:
    case Match(graphHash: String, nodes: Int)
    case Mismatch(diff: GraphDiff, meta: RunDiffMeta)
    case Failed(failure: Failure)
  end Verdict

  private val Usage =
    s"""usage: ReplayCli [replay] <run-dir> --workspace <dir> [--data <file>]
       |
       |Replays a recorded lineage campaign (recorded-only: no model calls).
       |Reads <run-dir>/$DataFileName (or --data <file>), isolates a throwaway
       |copy of --workspace, replays through ReplayHarness, prints the verdict.
       |
       |  <run-dir>   run directory holding the recorded lineage-data.json
       |  --workspace original workspace the record was made against (required)
       |  --data      explicit lineage-data file path (default: <run-dir>/$DataFileName)
       |
       |Exit codes (ci-runtime §8): 0 = match, 10 = mismatch,
       |20/21/22/23/24/26 = typed failure, nonzero always on failure.""".stripMargin

  /** Parses argv (own standalone parser — the product `raider.cli.run.CliArgs`
    * is another lane and is NOT touched). Accepts an optional leading literal
    * `replay` (the future product subcommand shape), exactly one positional run
    * directory, the required `--workspace <dir>` and the optional
    * `--data <file>`. Anything else is a usage error rendered with the usage
    * text.
    */
  def parse(argv: Seq[String]): Either[String, Config] =
    val args = argv.toList match
      case "replay" :: tail => tail
      case other            => other
    def loop(
        rest: List[String],
        runDir: Option[Path],
        workspace: Option[Path],
        data: Option[Path]
    ): Either[String, Config] =
      rest match
        case Nil =>
          (runDir, workspace) match
            case (Some(rd), Some(ws)) => Right(Config(rd, ws, data))
            case (None, _)            => Left(s"missing <run-dir>\n${Usage}")
            case (_, None) => Left(s"missing --workspace <dir>\n${Usage}")
        case ("--workspace" | "--data") :: Nil =>
          Left(s"missing value for ${rest.head}\n${Usage}")
        case "--workspace" :: value :: tail =>
          loop(tail, runDir, Some(Path.of(value)), data)
        case "--data" :: value :: tail =>
          loop(tail, runDir, workspace, Some(Path.of(value)))
        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n${Usage}")
        case positional :: tail =>
          runDir match
            case Some(_) =>
              Left(s"unexpected extra argument: $positional\n${Usage}")
            case None => loop(tail, Some(Path.of(positional)), workspace, data)
    loop(args, None, None, None)

  /** Runs the replay chain for `config` — the whole pipeline as DATA, typed
    * failures on [[Failure]], runtime defects loud (see the object scaladoc).
    * Total over its error channel: every known failure mode becomes a
    * [[Verdict.Failed]].
    *
    * Order mirrors the frozen LIN-06D pipeline ([[IsolatedRerun]]): read record
    * → recorded graph → isolate copy → snapshot original → replay → verdict →
    * `verifyUntouched`; a failed verify overrides any comparison verdict.
    */
  def run(config: Config): ZIO[Any, Nothing, Verdict] =
    ZIO
      .scoped {
        for
          doc <- readDoc(dataPathOf(config))
          expected <- recordedGraphOf(doc)
          parent <- ZIO.acquireRelease(acquireStagingParent)(
            releaseStagingParent
          )
          isolated <- isolate(config.workspace, parent)
          before <- snapshotOf(config.workspace)
          replayRun <- ReplayHarness
            .replay(isolated, doc)
            .mapError(Failure.Replay(_))
          verdict = compareStep(expected, replayRun)
          verified <- verifyIsolated(config.workspace, before).either
        yield verified match
          case Right(())   => verdict
          case Left(error) => Verdict.Failed(Failure.Isolation(error))
      }
      .fold(failure => Verdict.Failed(failure), identity)

  private def dataPathOf(config: Config): Path =
    config.dataFile.getOrElse(config.runDir.resolve(DataFileName))

  private def readDoc(path: Path): ZIO[Any, Failure, LineageDataDocument] =
    ZIO.fromEither(LineageDataStore.read(path)).mapError(Failure.Data(_))

  /** The graph recorded in the document. [[ReplayHarness.recordedGraph]]
    * reports refusal conditions via `sys.error` (its fixture-bug channel); for
    * a CLI input those are INPUT facts, so they are recovered into
    * [[Failure.Record]] instead of crashing — a broken record is a typed
    * nonzero exit, never a stack trace and never a verdict.
    */
  private def recordedGraphOf(
      doc: LineageDataDocument
  ): ZIO[Any, Failure, SessionGraph] =
    ZIO
      .attempt(ReplayHarness.recordedGraph(doc))
      .refineOrDie { case error: RuntimeException =>
        Failure.Record(String.valueOf(error.getMessage))
      }

  private def isolate(original: Path, parent: Path): ZIO[Any, Failure, Path] =
    ZIO
      .fromEither(WorkspaceIsolation.isolate(original, parent))
      .mapError(error => Failure.Isolation(error))

  private def snapshotOf(
      original: Path
  ): ZIO[Any, Failure, Map[Path, Array[Byte]]] =
    ZIO
      .attempt(WorkspaceIsolation.snapshot(original))
      .refineOrDie { case error: IllegalStateException =>
        Failure.SnapshotGuard(String.valueOf(error.getMessage))
      }

  private def verifyIsolated(
      original: Path,
      before: Map[Path, Array[Byte]]
  ): ZIO[Any, IsolationError, Unit] =
    ZIO.fromEither(WorkspaceIsolation.verifyUntouched(original, before))

  /** The comparison verdict (before the isolation verify): the replayed graph
    * and the normalized comparison are DATA on [[ReplayHarness.ReplayRun]].
    */
  private def compareStep(
      expected: SessionGraph,
      replayRun: ReplayHarness.ReplayRun
  ): Verdict =
    replayRun.graph match
      case Left(error) => Verdict.Failed(Failure.Reduce(error))
      case Right(actual) =>
        replayRun.comparison match
          case Some(Right(hash)) => Verdict.Match(hash, actual.nodes.size)
          case Some(Left(diff)) =>
            Verdict.Mismatch(
              diff,
              RunDiffMeta(
                runId = RunLabel,
                expectedNodes = expected.nodes.size,
                actualNodes = actual.nodes.size
              )
            )
          // Unreachable by harness construction (comparison is defined
          // whenever the graph reduced); carried typed, never guessed.
          case None =>
            Verdict.Failed(
              Failure.Record(
                "harness invariant broken: reduced graph without a comparison"
              )
            )

  // ---------------------------------------------------------------------------
  // Staging parent: a unique per-invocation directory under java.io.tmpdir —
  // created by WorkspaceIsolation.isolate itself (createDirectories), deleted
  // (with the throwaway copy inside) when the run's scope closes. The UUID
  // keeps PARALLEL replays on disjoint parents — no shared write target.
  // ---------------------------------------------------------------------------

  private val StagingPrefix = "raider-replay-cli-"

  private def acquireStagingParent: UIO[Path] =
    ZIO.succeed(
      Path.of(
        System.getProperty("java.io.tmpdir"),
        s"$StagingPrefix${UUID.randomUUID()}"
      )
    )

  private def releaseStagingParent(parent: Path): UIO[Unit] =
    ZIO.succeed(deleteRecursivelyQuietly(parent))

  private def deleteRecursivelyQuietly(root: Path): Unit =
    try
      val stream = Files.walk(root)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
        ()
      finally stream.close()
    catch case _: java.io.IOException => ()

  // ---------------------------------------------------------------------------
  // Rendering and exit codes
  // ---------------------------------------------------------------------------

  /** The deterministic report text. Verdicts go to stdout, typed failures to
    * stderr (the HeadlessRunner convention): `main` routes by outcome kind.
    */
  def render(verdict: Verdict): String =
    verdict match
      case Verdict.Match(hash, nodes) =>
        s"verdict: match\ngraph-hash: $hash\nnodes: $nodes"
      case Verdict.Mismatch(diff, meta) =>
        s"verdict: mismatch\n${RunDiffReport.render(diff, meta)}"
      case Verdict.Failed(failure) =>
        s"typed-error: ${renderFailure(failure)}"

  private def renderFailure(failure: Failure): String =
    failure match
      case Failure.Data(StoreError.Missing(path)) =>
        s"lineage-data missing: $path (StoreError.Missing)"
      case Failure.Data(StoreError.Corrupt(path, detail)) =>
        s"lineage-data corrupt at $path: $detail (StoreError.Corrupt)"
      case Failure.Data(StoreError.Io(path, detail)) =>
        s"lineage-data read failed at $path: $detail (StoreError.Io)"
      case Failure.SnapshotGuard(what) =>
        s"workspace snapshot refused: $what"
      case Failure.Record(what) =>
        s"record does not reduce into a recorded graph: $what"
      case Failure.Isolation(IsolationError.OriginalMissing(path)) =>
        s"workspace missing: $path (IsolationError.OriginalMissing)"
      case Failure.Isolation(IsolationError.CopyFailed(detail)) =>
        s"workspace isolation copy failed: $detail (IsolationError.CopyFailed)"
      case Failure.Isolation(IsolationError.Tampered(file)) =>
        s"workspace tampered during replay: $file (IsolationError.Tampered)"
      case Failure.Replay(error) =>
        ReplayBackendError.fromRaiderError(error) match
          case Some(replayError) =>
            s"replay failed: ${replayError.getMessage} (ReplayBackendError)"
          case None =>
            s"replay failed: ${error.code} ${error.detail} (${error.retryable})"
      case Failure.Reduce(error) =>
        s"replay events do not reduce: $error"

  /** Exit code of a verdict — the frozen ci-runtime §8 mapping only (see the
    * object scaladoc): 0 Match, 10 Mismatch, else the typed failure's class.
    */
  def exitCode(verdict: Verdict): Int =
    verdict match
      case Verdict.Match(_, _)     => ExitCodes.Success
      case Verdict.Mismatch(_, _)  => ExitCodes.TaskRejected
      case Verdict.Failed(failure) => failureExitCode(failure)

  private def failureExitCode(failure: Failure): Int =
    failure match
      case Failure.Data(_: StoreError.Missing) => ExitCodes.ConfigInvalid
      case Failure.Data(_: StoreError.Corrupt) => ExitCodes.ConfigInvalid
      case Failure.Data(_: StoreError.Io)      => ExitCodes.RuntimeFailure
      case Failure.SnapshotGuard(_)            => ExitCodes.ConfigInvalid
      case Failure.Record(_)                   => ExitCodes.ConfigInvalid
      case Failure.Isolation(IsolationError.OriginalMissing(_)) =>
        ExitCodes.ConfigInvalid
      case Failure.Isolation(IsolationError.CopyFailed(_)) =>
        ExitCodes.RuntimeFailure
      case Failure.Isolation(IsolationError.Tampered(_)) =>
        ExitCodes.Uncertain
      case Failure.Replay(error) => replayExitCode(error)
      case Failure.Reduce(_)     => ExitCodes.RuntimeFailure

  private def replayExitCode(error: RaiderError): Int =
    error match
      case _: RaiderError.ProviderBudget | _: RaiderError.LocalBudgetExceeded =>
        ExitCodes.BudgetExhausted
      case _: RaiderError.DeadlineExceeded      => ExitCodes.DeadlineExceeded
      case _: RaiderError.CapabilityUnsupported => ExitCodes.PolicyDenied
      case _: RaiderError.Configuration | _: RaiderError.InputValidation |
          _: RaiderError.OutputValidation =>
        ExitCodes.ConfigInvalid
      case _ => ExitCodes.RuntimeFailure

  /** Standalone entry: verdict lines on stdout (flushed — `System.exit` does
    * not flush piped stdout), typed failures and usage on stderr, then the
    * mapped exit code. Runtime defects crash loudly via `getOrThrow` — never
    * mapped to a quiet code.
    */
  def main(argv: Array[String]): Unit =
    parse(argv.toList) match
      case Left(usageError) =>
        System.err.println(usageError)
        System.err.flush()
        System.exit(ExitCodes.ConfigInvalid)
      case Right(config) =>
        Unsafe.unsafe { implicit unsafe =>
          val verdict = zio.Runtime.default.unsafe.run(run(config)).getOrThrow()
          verdict match
            case Verdict.Failed(failure) =>
              val code = failureExitCode(failure)
              System.err.println(render(verdict))
              System.err.println(s"exit $code: ${failureExitReason(code)}")
              System.err.flush()
              System.exit(code)
            case _ =>
              System.out.println(render(verdict))
              System.out.flush()
              System.exit(exitCode(verdict))
        }

  /** The ci-runtime §8 reason word for a failure exit code (diagnostic line on
    * stderr — keeps the stable mapping visible in shell transcripts).
    */
  private def failureExitReason(code: Int): String =
    code match
      case ExitCodes.TaskRejected     => "mismatch"
      case ExitCodes.RuntimeFailure   => "runtime-failure"
      case ExitCodes.ConfigInvalid    => "config-invalid"
      case ExitCodes.PolicyDenied     => "policy-denied"
      case ExitCodes.BudgetExhausted  => "budget-exhausted"
      case ExitCodes.DeadlineExceeded => "deadline-exceeded"
      case ExitCodes.Uncertain        => "outcome-uncertain"
      case other                      => s"failure-$other"

end ReplayCli
