package raider.cli.lineage

import raider.core.RaiderError
import raider.dsl.lineage.data.{LineageDataDocument, NodeData}
import raider.dsl.lineage.replay.RecordedSource
import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.reduce.ReduceError
import raider.runtime.budget.BudgetLimits

import zio.json.JsonDecoder
import zio.json.ast.Json
import zio.{Runtime, Unsafe}

import java.io.IOException
import java.nio.file.{Files, NoSuchFileException, Path}

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

  /** LIN6-02 policy refusal, issued BEFORE any dispatch: the record carries
    * Mutating tool nodes ([[IsolatedRerun.mutatingTools]]) but the requested
    * replay target is not the isolated copy of the original — the ADR-027 §2
    * isolation policy ("Mutating only in an isolated workspace copy")
    * refuses. `tools` lists the mutating tool names in deterministic (sorted,
    * distinct) order; no workspace paths — the caller knows the target (the
    * same no-absolute-path discipline as [[IsolationError.Tampered]]).
    */
  case MutatingUnisolated(tools: Vector[String])
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
    * ownership/determinism contracts. `limits`/`maxRounds` pass through to
    * [[ReplayHarness.replay]] (defaults = the frozen fixture ceilings);
    * LIN6-02(sc) exhausts the budget typed by passing smaller ones.
    */
  def rerun(
      recorded: LineageDataDocument,
      expectedGraph: SessionGraph,
      originalWorkspace: Path,
      parent: Path,
      limits: BudgetLimits = ReplayHarness.FixtureLimits,
      maxRounds: Int = ReplayHarness.FixtureMaxRounds
  ): Either[RerunError, RerunOutcome] =
    WorkspaceIsolation.isolate(originalWorkspace, parent) match
      case Left(error) => Left(RerunError.Isolation(error))
      case Right(isolated) =>
        snapshotOriginal(originalWorkspace).flatMap { before =>
          replay(isolated, recorded, limits, maxRounds).flatMap { run =>
            run.graph.left.map(RerunError.Reduce(_)).flatMap { actual =>
              val (matched, report) = verdict(recorded, expectedGraph, actual)
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
      recorded: LineageDataDocument,
      limits: BudgetLimits,
      maxRounds: Int
  ): Either[RerunError, ReplayHarness.ReplayRun] =
    try
      Right(
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(ReplayHarness.replay(isolated, recorded, limits, maxRounds))
            .getOrThrow()
        }
      )
    catch case error: RaiderError => Left(RerunError.Replay(error))

  /** Compare + render (step 4): a divergence renders the deterministic report
    * (match = false); agreement yields the shared-normalized-hash message
    * (match = true) — a Match is not a report, per [[RunDiffReport]]. The
    * whole-graph verdict is followed by the PHASED section (LIN6-01sc):
    * [[phasedSection]] renders one verdict per named phase of the recorded
    * document — empty for phaseless records, so existing phaseless reports stay
    * byte-identical.
    */
  private def verdict(
      recorded: LineageDataDocument,
      expected: SessionGraph,
      actual: SessionGraph
  ): (Boolean, String) =
    RunDiffReport.fromGraphs(expected, actual, RunId) match
      case Right(report) => (false, report.render + phasedSection(recorded))
      case Left(message) => (true, message + phasedSection(recorded))

  /** The per-phase section of the rerun report (LIN6-01sc): for every phase
    * recorded in the document, one [[PhaseVerdict]] row — the phase identity,
    * its slice GraphHash, the whole-slice verify label, one line per DIRECT
    * child (resolved / obligation / unresolved) and one line per deferred tool
    * obligation of the slice. Built from the RECORDED side only
    * ([[PhaseReplay]] over the doc-built graph + [[RecordedSource]]): the live
    * replayed graph never carries phase grouping (no wire case —
    * docs/lineage.md §8), so phases are reported as recorded-side structure.
    * Pure function of the record — a repeat render is byte-identical.
    */
  private def phasedSection(recorded: LineageDataDocument): String =
    val verdicts =
      PhaseReplay.replayAll(
        ReplayHarness.recordedGraph(recorded),
        RecordedSource(recorded)
      )
    if verdicts.isEmpty then ""
    else verdicts.map(renderPhase).mkString("\n", "\n", "")

  /** One phase → its deterministic report lines (see [[phasedSection]]). */
  private def renderPhase(v: PhaseVerdict): String =
    val label = v.subtree match
      case SubtreeVerdict.Resolved(count, _) => s"resolved($count)"
      case SubtreeVerdict.PartiallyResolved(fs, _) =>
        s"partially-resolved(${fs.size})"
    val rows = v.children.map(renderChildRow) ++
      deferredRows(v.subtree)
    rows.mkString(
      s"phase ${v.phaseId.value} name=${v.name} subtree=$label hash=${v.graphHash}",
      "\n  ",
      ""
    )

  private def renderChildRow(row: NodeVerdict): String = row match
    case NodeVerdict.Resolved(nodeId, dataRef) =>
      s"child $nodeId resolved dataRef=$dataRef"
    case NodeVerdict.Obligation(nodeId, None) =>
      s"child $nodeId obligation structural"
    case NodeVerdict.Obligation(nodeId, Some(deferred)) =>
      s"child $nodeId obligation tool=${deferred.tool} recovery=${deferred.recovery}"
    case NodeVerdict.Unresolved(nodeId, dataRef) =>
      s"child $nodeId unresolved dataRef=$dataRef"

  /** Deferred tool obligations of the slice (grandchildren included) — the
    * per-child rows above only cover DIRECT children.
    */
  private def deferredRows(verdict: SubtreeVerdict): Vector[String] =
    val tools = verdict match
      case SubtreeVerdict.Resolved(_, tools)       => tools
      case SubtreeVerdict.PartiallyResolved(_, ts) => ts
    tools.map(t =>
      s"deferred ${t.nodeId} tool=${t.tool} recovery=${t.recovery}"
    )

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

  // -- LIN6-02: the isolation policy guard (product path) --------------------

  /** Static exec-class tool names of the ADR-027 §6.2 taxonomy (frozen until
    * the wave-2 schema card). Mirrors the classification LineageReducer applies
    * when it WRITES the recorded `recovery` verdict — the two sets cite the
    * same ADR section; unifying them is a schema-card concern (the reducer's
    * classifier is private to the runtime module).
    */
  private val ExecTools = Set("bash", "sh", "process", "python")

  /** Static write-class tool names of the ADR-027 §6.2 taxonomy. */
  private val WriteTools = Set(
    "edit",
    "fs_edit",
    "write",
    "fs_write",
    "apply_patch",
    "patch",
    "mv",
    "rm",
    "mkdir",
    "touch"
  )

  private def isMutatingTool(name: String): Boolean =
    ExecTools.contains(name) || WriteTools.contains(name)

  /** The Mutating tool names the replay of `recorded` would EXECUTE for real
    * (LIN6-02 policy input), deterministic (sorted, distinct). Two signals,
    * because the recorded annotation can under-report what the loop actually
    * runs (the replay executes the recorded RESPONSE envelope — the IR-02
    * record's annotation says fs_read while its response carries fs_edit):
    *   - every `tool_calls` envelope found in the recorded responses (the
    *     [[ReplayModelBackend]] record convention), classified through the §6.2
    *     static taxonomy;
    *   - every `ToolCallData` entry whose recorded `recovery` is `Mutating`
    *     (the recording-time verdict — trusted verbatim).
    */
  def mutatingTools(recorded: LineageDataDocument): Vector[String] =
    val fromResponses = recorded.data.values.flatMap {
      case m: NodeData.ModelCallData => responseToolNames(m.response)
      case _                         => Vector.empty
    }
    val fromAnnotations = recorded.data.values.collect {
      case t: NodeData.ToolCallData if t.recovery == "Mutating" => t.tool
    }
    (fromResponses ++ fromAnnotations)
      .filter(isMutatingTool)
      .toVector
      .sorted
      .distinct

  /** LIN6-02 typed pre-execution refusal (ADR-027 §2: "Mutating nodes only in an
    * isolated workspace copy; refusal before execution"): the PRODUCT-path
    * guard for calling layers that aim the harness mechanics at a chosen
    * target. A record whose [[mutatingTools]] is non-empty may only be replayed
    * against a FAITHFUL isolated copy of `original` — the target must be a
    * different directory whose bytes match the original's current snapshot
    * (exactly what [[WorkspaceIsolation.isolate]] produces). Refused typed
    * ([[RerunError.MutatingUnisolated]]) BEFORE any dispatch — the guard reads
    * files and never starts a replay, a toolset or a backend.
    *
    * A ReadOnly record needs no isolation: `Right(())` without checks (the
    * replay-recorded default contract, LIN-10 precedent).
    *
    * Canonical identity (RAI-120, review finding LIN-15): the in-place refusal
    * compares `target` against `original` through [[isCanonicalAlias]] —
    * device/inode identity ([[Files.isSameFile]]), not syntactic `Path`
    * equality — so a syntactic alias (`ws/../ws`) or a symlink alias of the
    * original is refused like literal in-place, BEFORE the byte check could
    * pass it as a «faithful copy».
    */
  def requireIsolatedTarget(
      recorded: LineageDataDocument,
      original: Path,
      target: Path
  ): Either[RerunError, Unit] =
    val tools = mutatingTools(recorded)
    if tools.isEmpty then Right(())
    else if isCanonicalAlias(target, original) then
      Left(
        RerunError.MutatingUnisolated(tools)
      ) // replay in place (aliases too)
    else
      try
        val before = WorkspaceIsolation.snapshot(original)
        WorkspaceIsolation.verifyUntouched(target, before) match
          case Right(()) => Right(()) // faithful isolated copy
          case Left(_)   => Left(RerunError.MutatingUnisolated(tools))
      catch
        case error: IllegalStateException =>
          // snapshot guard refusal (symlink/special/bound) — carried typed,
          // the same discipline as [[IsolatedRerun.snapshotOriginal]]
          Left(RerunError.Snapshot(String.valueOf(error.getMessage)))

  /** Canonical identity of `target` vs `original` (RAI-120):
    * [[Files.isSameFile]] — the directory-entry-level comparison that syntactic
    * `Path` equality misses. Case discipline:
    *   - syntactic alias / symlink alias of the original → `true` (refused
    *     in-place by the guard);
    *   - a MISSING path → `false`: honestly not the original — the byte check
    *     in [[requireIsolatedTarget]] then refuses it typed
    *     ([[RerunError.MutatingUnisolated]]), never a silent pass;
    *   - an undecidable IO failure → `true`: the guard FAILS CLOSED — a
    *     mutating replay is never risked on an unknown identity answer.
    */
  private def isCanonicalAlias(target: Path, original: Path): Boolean =
    try Files.isSameFile(target, original)
    catch
      case _: NoSuchFileException => false // missing path: not the original
      case _: IOException         => true // undecidable: refuse (fail closed)

  /** The tool names of a recorded response's `tool_calls` envelope, if it IS
    * one (the same record convention [[ReplayModelBackend]] replays: a JSON
    * object with a non-empty `tool_calls` array of objects carrying `name`).
    * Anything else is a plain-text response — no names.
    */
  private def responseToolNames(response: String): Vector[String] =
    summon[JsonDecoder[Json]]
      .decodeJson(response)
      .toOption
      .flatMap {
        case Json.Obj(fields) =>
          fields.collectFirst { case (_, calls: Json.Arr) =>
            calls.elements
          } match
            case None => None
            case Some(items) =>
              val names = items.flatMap {
                case Json.Obj(member) =>
                  member.collectFirst { case ("name", Json.Str(v)) => v }
                case _ => None
              }
              if names.isEmpty then None else Some(names.toVector)
        case _ => None
      }
      .getOrElse(Vector.empty)

end IsolatedRerun
