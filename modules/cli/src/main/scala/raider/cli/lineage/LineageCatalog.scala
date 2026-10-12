package raider.cli.lineage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import raider.core.result.ExitCodes
import raider.runtime.lineage.io.TruncatedTail
import raider.runtime.lineage.replay.{ReplayError, ReplayVerifier}

/** One catalog record of the lineage-tree catalog (LIN-08B, packet L43, ADR-027
  * §5, docs/lineage.md §5): the deterministic identity and reproducibility
  * status of ONE recorded run. The SAME record shape serves chat sessions and
  * headless runs (chat==headless, LIN8-01) — identity fields only, no payloads
  * (ADR-027 §2: prompts/tool data stay in the data resource, never inline).
  *
  * @param path
  *   the run directory (the HeadlessRunner layout `work-<uuid>` under the
  *   scanned runs root) — the cataloged tree's home, its `events.jsonl`
  *   checkpoint sits directly inside.
  * @param rootId
  *   the campaign root identity — the start-transition's rootId (the reducer
  *   takes the root job from the FIRST envelope, so the reduced graph's rootId
  *   IS the start transition's).
  * @param sessionId
  *   the session identity, when the recording carries one. `Option` because the
  *   chat==headless record format must render an honest `—` for a consumer that
  *   has none; today [[ReplayVerifier.verifyPartial]] always yields one from
  *   the reduced graph, so entries built by [[LineageCatalog]] are always
  *   `Some`.
  * @param nodeCount
  *   nodes of the reduced graph — for a complete run the FULL campaign graph,
  *   for a partial one exactly the COMPLETED nodes of the readable prefix
  *   (LIN5-03), never a guess.
  * @param hash
  *   the canonical [[raider.runtime.lineage.model.GraphHash]] hex of the
  *   reduced graph — the reproducibility anchor a replay is verified against.
  * @param complete
  *   true = the recording is intact and the graph is the whole campaign; false =
  *   a torn tail was tolerated as a partial graph (honest status, the tail is
  *   in [[tailReported]]).
  * @param tailReported
  *   the truncated-tail marker (line number + raw prefix length) when the
  *   recording's last line was torn — reported, never silently dropped.
  * @param scannedAtMs
  *   the events.jsonl FS mtime (epoch millis) — the run's own last write as the
  *   filesystem saw it, NEVER a wall-clock sample taken during the scan, so
  *   rescans of an unchanged tree are byte-identical.
  */
final case class CatalogEntry(
    path: Path,
    rootId: String,
    sessionId: Option[String],
    nodeCount: Int,
    hash: String,
    complete: Boolean,
    tailReported: Option[TruncatedTail],
    scannedAtMs: Long
)

/** Typed failures of [[LineageCatalog.scan]]. Content distrust is typed; the
  * only non-typed failures are environment-level IO surfaces (unreadable
  * files/directories raise the underlying `IOException`, the module-wide
  * honesty rule of the lineage layers).
  */
enum CatalogError:

  /** The scanned runs root does not exist: a configuration refusal, NOT an
    * empty success — an absent place and a place with no runs are different
    * facts and the catalog reports them differently.
    */
  case NotFound(runsRoot: Path)

  /** The scanned runs root exists but is not a directory. */
  case NotADirectory(runsRoot: Path)

  /** A `work-*` run's events.jsonl is DAMAGED beyond a torn tail (malformed
    * line before the tail, unsupported schemaVersion, seq gap, reduce failure):
    * an honest typed refusal of the whole scan — the damaged run is named,
    * nothing is silently skipped, no "plausible" entry is invented for it. A
    * torn TAIL is not this case: verifyPartial catalogs it as a partial entry
    * (LIN8-B-02).
    */
  case RunUnreadable(eventsPath: Path, detail: String)
end CatalogError

/** The lineage-tree catalog (LIN-08B, ADR-027 §5): a PURE function over a runs
  * directory — no persistence, no new wire format, no daemon. It walks the
  * HeadlessRunner layout (`<runsRoot>/work-<uuid>/events.jsonl`), reduces every
  * checkpoint through [[ReplayVerifier.verifyPartial]] (the SAME deterministic
  * pipeline as replay verification) and renders the shared chat/headless table
  * (LIN8-01). Persistence (LIN-12) lives in [[CatalogStore]] — this object
  * stays the pure scan over the tree.
  *
  * Scan decisions (documented, deliberate):
  *   - a MISSING runsRoot is the typed `CatalogError.NotFound` — different fact
  *     than empty, never flattened into `Right(empty)`;
  *   - an EXISTING runsRoot with no runs is `Right(Vector.empty)` — a truthful
  *     empty catalog;
  *   - only subdirectories named `work-*` are runs (the runner's layout); files
  *     and foreign names are not trees and are ignored;
  *   - a `work-*` directory WITHOUT events.jsonl is skipped: the runner leaves
  *     no lineage for preflight-failure families (no tree exists), so there is
  *     nothing honest to catalog — nothing is invented for it;
  *   - each found checkpoint goes through [[ReplayVerifier.verifyPartial]]: an
  *     intact file is a complete entry, a torn tail is a partial entry with the
  *     tail reported (LIN8-B-02 — data, never an exception), and damage BEYOND
  *     a torn tail refuses the whole scan TYPED (`CatalogError.RunUnreadable`)
  *     instead of skipping or papering over;
  *   - `scannedAtMs` and the sort key come from the FS mtime of events.jsonl,
  *     never from the clock — a rescan of an unchanged tree is identical
  *     (LIN8-B-01);
  *   - ordering: mtime DESC (newest run first), path ASC as the deterministic
  *     tie-break.
  */
object LineageCatalog:

  /** The runner's work-directory prefix (HeadlessRunner: `work-<uuid>`). */
  private val RunDirPrefix = "work-"

  /** The run's lineage checkpoint (RAI-060/LIN-03A: streamed events.jsonl). */
  private val EventsFile = "events.jsonl"

  /** Scans `runsRoot` and catalogs every run that has a tree. See the object
    * scaladoc for the per-case decisions; deterministic by construction.
    */
  def scan(runsRoot: Path): Either[CatalogError, Vector[CatalogEntry]] =
    if !Files.exists(runsRoot) then Left(CatalogError.NotFound(runsRoot))
    else if !Files.isDirectory(runsRoot) then
      Left(CatalogError.NotADirectory(runsRoot))
    else
      val stream = Files.list(runsRoot)
      val runDirs =
        try
          stream
            .iterator()
            .asScala
            .filter(p =>
              Files.isDirectory(p) &&
                p.getFileName.toString.startsWith(RunDirPrefix)
            )
            .toVector
            .sortBy(_.getFileName.toString)
        finally stream.close()
      runDirs
        .foldLeft[Either[CatalogError, Vector[CatalogEntry]]](
          Right(Vector.empty)
        ) { (acc, dir) =>
          acc.flatMap(entries => scanRun(dir).map(entries ++ _))
        }
        .map(sortEntries)

  /** Deterministic catalog order: mtime DESC, then path ASC. */
  private def sortEntries(entries: Vector[CatalogEntry]): Vector[CatalogEntry] =
    entries.sortBy(entry => (-entry.scannedAtMs, entry.path.toString))

  /** Catalogs ONE run directory: `None` = the directory has no tree (no
    * events.jsonl — see the object scaladoc, nothing honest to catalog);
    * `Some(entry)` = a complete or partial catalog record; content damage
    * beyond a torn tail is a typed refusal.
    */
  private def scanRun(
      dir: Path
  ): Either[CatalogError, Option[CatalogEntry]] =
    val events = dir.resolve(EventsFile)
    if !Files.exists(events) then Right(None)
    else
      val mtimeMs = Files.getLastModifiedTime(events).toMillis
      ReplayVerifier.verifyPartial(events) match
        case Left(err) =>
          Left(CatalogError.RunUnreadable(events, describe(err)))
        case Right(partial) =>
          Right(
            Some(
              CatalogEntry(
                path = dir,
                rootId = partial.rootId.value,
                sessionId = Some(partial.sessionId.value),
                nodeCount = partial.nodeCount,
                hash = partial.hash,
                complete = partial.complete,
                tailReported = partial.tail,
                scannedAtMs = mtimeMs
              )
            )
          )

  /** One-line typed description of a verification refusal (for
    * `CatalogError.RunUnreadable.detail`). The underlying error cases render
    * themselves (`LineageError`/`ReduceError` are informative case data); this
    * only names the failing layer.
    */
  private def describe(e: ReplayError): String = e match
    case ReplayError.Read(err)       => s"read: $err"
    case ReplayError.Truncated(tail) => s"truncated tail at line ${tail.lineNo}"
    case ReplayError.Reduce(err)     => s"reduce: $err"
    case ReplayError.HashMismatch(exp, act) =>
      s"hash mismatch: expected $exp, actual $act"

  /** The shared chat==headless table render (LIN8-01): fixed column order,
    * uniform column alignment (every line has the same length), and the honest
    * `—` for a field the entry does not carry — a missing value is shown as
    * missing, never as an invented one. An EMPTY catalog renders as an EMPTY
    * table (`Vector.empty`) — the caller's exit semantics stay honest
    * ([[exitCode]]).
    */
  def formatTable(entries: Vector[CatalogEntry]): Vector[String] =
    if entries.isEmpty then Vector.empty
    else
      val header = Vector(
        "PATH",
        "ROOT",
        "SESSION",
        "NODES",
        "HASH",
        "STATUS",
        "TAIL",
        "MTIME_MS"
      )
      val rows = entries.map { e =>
        Vector(
          e.path.toString,
          e.rootId,
          e.sessionId.getOrElse("\u2014"),
          e.nodeCount.toString,
          e.hash,
          if e.complete then "complete" else "partial",
          e.tailReported.fold("\u2014")(t =>
            s"line ${t.lineNo} (${t.rawPrefixLen} chars)"
          ),
          e.scannedAtMs.toString
        )
      }
      val widths = header.indices.map { i =>
        math.max(
          header(i).length,
          rows.map(row => row(i).length).maxOption.getOrElse(0)
        )
      }
      def render(cells: Vector[String]): String =
        cells
          .zip(widths)
          .map((cell, w) => cell.padTo(w, ' '))
          .mkString("  ")
      render(header) +: rows.map(render)

  /** Honest exit semantics of a catalog invocation (ci-runtime §8 families, no
    * catch-all zero):
    *   - `Right(entries)` — INCLUDING the empty catalog — is `Success` (0): a
    *     truthful empty answer is a successful answer, not a failure;
    *   - `NotFound` / `NotADirectory` are `ConfigInvalid` (21): the operator
    *     pointed the catalog at a place that cannot be a runs root;
    *   - `RunUnreadable` is `TaskRejected` (10): the scanned data is damaged
    *     and is refused as data.
    */
  def exitCode(result: Either[CatalogError, Vector[CatalogEntry]]): Int =
    result.fold(
      {
        case _: CatalogError.RunUnreadable => ExitCodes.TaskRejected
        case _                             => ExitCodes.ConfigInvalid
      },
      _ => ExitCodes.Success
    )

end LineageCatalog
