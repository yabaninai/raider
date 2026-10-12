package raider.cli.lineage

import raider.dsl.lineage.data.{LineageDataDocument, NodeData}
import raider.runtime.lineage.model.{RecoveryClass, SessionGraph, SessionNode}

import java.nio.charset.StandardCharsets
import java.nio.file.{FileSystems, Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Typed failure of deterministic tool-call recomputation (LIN-05F, ADR-027
  * §2–3). Honest by construction: a call this slice cannot re-execute is a
  * typed error, never a fabricated result.
  *
  *   - [[RecomputeError.Unsupported]] — the tool is not a deterministic
  *     read-only read this slice knows (mutating tools, processes, unknown
  *     names). Carries the tool name; recompute NEVER emulates them.
  *   - [[RecomputeError.ArgsUnreadable]] — the recorded call args cannot be
  *     determined: no dataRef on the node, an unresolved ref, a ref pointing at
  *     non-`ToolCallData` (the `SessionNode.ToolCall` model carries NO args by
  *     design — ADR-027 §2 keeps full args only in the data resource), or an
  *     argsSummary outside the supported `fs_read`/`fs_search` forms.
  *   - [[RecomputeError.ReadFailed]] — the real bounded workspace read failed
  *     (missing path, directory, traversal/symlink escape, binary content, IO
  *     error). Mirrors the ReadTool failure discipline: never a faked
  *     successful read.
  */
enum RecomputeError:
  case Unsupported(tool: String)
  case ArgsUnreadable(nodeId: String, detail: String)
  case ReadFailed(nodeId: String, detail: String)

/** Verdict of comparing a recomputed [[NodeData.ToolCallData]] against the
  * recorded one (LIN-05F):
  *
  *   - [[ToolComparison.Match]] — every compared field is equal;
  *   - [[ToolComparison.Mismatch]] — at least one field drifted; `fields` lists
  *     the drifted names in the fixed comparison order (tool, callId, recovery,
  *     outcome, argsSummary, resultTail);
  *   - [[ToolComparison.NoRecord]] — no recorded source exists for the node (no
  *     document passed to the replay), so there is nothing to compare against —
  *     reported honestly instead of guessed.
  */
enum ToolComparison:
  case Match
  case Mismatch(fields: Vector[String])
  case NoRecord

object ToolComparison:

  /** Compares a freshly recomputed tool source against the recorded one.
    * `recorded = None` (nothing recorded under the node) is
    * [[ToolComparison.NoRecord]].
    */
  def compareRecorded(
      recomputed: NodeData.ToolCallData,
      recorded: Option[NodeData.ToolCallData]
  ): ToolComparison =
    recorded match
      case None => ToolComparison.NoRecord
      case Some(rec) =>
        val drifted = Vector(
          "tool" -> (rec.tool == recomputed.tool),
          "callId" -> (rec.callId == recomputed.callId),
          "recovery" -> (rec.recovery == recomputed.recovery),
          "outcome" -> (rec.outcome == recomputed.outcome),
          "argsSummary" -> (rec.argsSummary == recomputed.argsSummary),
          "resultTail" -> (rec.resultTail == recomputed.resultTail)
        ).collect { case (field, false) => field }
        if drifted.isEmpty then ToolComparison.Match
        else ToolComparison.Mismatch(drifted)

end ToolComparison

/** Deterministic tool-call recomputation seam (LIN-05F, ADR-027 §2–3): the next
  * slice after [[ReplayInterpreter]]'s deferred tools. An implementation
  * re-executes ONE recorded tool call against the CURRENT workspace and
  * rebuilds the fresh [[NodeData.ToolCallData]] source — deterministic
  * read-only tools only.
  *
  * The ONLY permitted IO is bounded reads from `workspace`; no networks, no
  * model access, no environment reads. Non-deterministic or unknown tools must
  * fail typed ([[RecomputeError.Unsupported]]), never be emulated.
  */
trait ToolRecompute:

  /** Recomputes the recorded call `node` against `workspace`. */
  def recompute(
      node: SessionNode.ToolCall,
      workspace: Path
  ): Either[RecomputeError, NodeData.ToolCallData]

end ToolRecompute

/** The fs_read-like recompute of this slice: re-reads the recorded call's file
  * from the CURRENT workspace (bounded, ReadTool discipline) and assembles the
  * fresh [[NodeData.ToolCallData]].
  *
  * The recorded document is needed for the call ARGS (the graph model carries
  * none — ADR-027 §2): the path/offset/max_lines are parsed from the recorded
  * `argsSummary` of the node's dataRef and re-executed. The recorded RESULT is
  * deliberately NOT consulted here — recomputation must stay independent of the
  * recorded answer so the comparison is meaningful.
  *
  * Read discipline (mirrors `raider.tools.files.read.ReadTool`): workspace
  * containment via realpath (traversal and symlink escapes denied), regular
  * files only, NUL byte refused as binary, ranged read bounded by
  * `HardMaxLines`, `\r` stripped per line. Supported argsSummary form: space
  * -separated `key=value` pairs with keys `path` (required), `offset`
  * (optional, >= 0), `max_lines` (optional, 1..HardMaxLines); anything else is
  * a typed [[RecomputeError.ArgsUnreadable]]. The recomputed `argsSummary` is
  * the canonical re-emission (`path`, then `offset`, then `max_lines`) so
  * canonical-form drift is also detectable. `resultTail` is bounded to the LAST
  * `TailMaxChars` characters of the read slice (the frozen `ToolCallData` has
  * no truncation flag — the bound is documented here, never hidden behind
  * invented fields).
  *
  * Supported tool set of THIS engine: exactly `fs_read` (the deterministic
  * read-only read of the coding toolset). `fs_search` has its own engine
  * ([[FsSearchRecompute]], LIN-02D2) registered alongside in
  * `ToolReplay.replayTools`; everything else — including the mutating/process
  * tools — is [[RecomputeError.Unsupported]].
  */
final class FsReadRecompute(recorded: LineageDataDocument)
    extends ToolRecompute:

  def recompute(
      node: SessionNode.ToolCall,
      workspace: Path
  ): Either[RecomputeError, NodeData.ToolCallData] =
    if node.tool != FsReadRecompute.Tool then
      Left(RecomputeError.Unsupported(node.tool))
    else
      for
        args <- FsReadRecompute.resolveArgs(node, recorded)
        tail <- FsReadRecompute.readBounded(
          workspace,
          args.path,
          args.offset.getOrElse(0),
          args.maxLines.getOrElse(FsReadRecompute.DefaultMaxLines),
          node.id.value
        )
      yield NodeData.ToolCallData(
        tool = node.tool,
        callId = node.callId,
        recovery = node.recovery.toString,
        outcome = Some("Succeeded"), // a completed bounded read IS a success
        argsSummary = FsReadRecompute.canonicalArgs(args),
        resultTail = Some(tail)
      )

end FsReadRecompute

object FsReadRecompute:

  val Tool: String = "fs_read"

  /** Same hard line bound as [[raider.tools.files.read.ReadTool]]. */
  val HardMaxLines: Int = 50_000
  val DefaultMaxLines: Int = 1_000

  /** Bound of the recomputed `resultTail` (last-N characters; diagnostics
    * -scale bound, far above any fixture-sized read).
    */
  val TailMaxChars: Int = 2_000

  private[lineage] final case class FsReadArgs(
      path: String,
      offset: Option[Int],
      maxLines: Option[Int]
  )

  /** Resolves the recorded call args of `node` from the data resource. */
  private[lineage] def resolveArgs(
      node: SessionNode.ToolCall,
      doc: LineageDataDocument
  ): Either[RecomputeError, FsReadArgs] =
    recordedCallData(node, doc).flatMap(data =>
      parseArgs(data.argsSummary, node.id.value)
    )

  /** Shared data-resource resolution (LIN-02D2): the graph model carries no
    * args — the recorded `ToolCallData` under the node's dataRef is the only
    * args source of every recompute engine ([[FsSearchRecompute]] included).
    */
  private[lineage] def recordedCallData(
      node: SessionNode.ToolCall,
      doc: LineageDataDocument
  ): Either[RecomputeError, NodeData.ToolCallData] =
    node.dataRef match
      case None =>
        Left(
          RecomputeError.ArgsUnreadable(
            node.id.value,
            "tool_call node has no dataRef; fs_read args live only in the data resource"
          )
        )
      case Some(ref) =>
        doc.data.get(ref.value) match
          case None =>
            Left(
              RecomputeError.ArgsUnreadable(
                node.id.value,
                s"dataRef '${ref.value}' is missing in the recorded document"
              )
            )
          case Some(data: NodeData.ToolCallData) =>
            Right(data)
          case Some(other) =>
            val kind = other match
              case _: NodeData.ModelCallData  => "ModelCallData"
              case _: NodeData.DelegationData => "DelegationData"
              case _: NodeData.PhaseData      => "PhaseData"
              case _: NodeData.ToolCallData   => "ToolCallData"
            Left(
              RecomputeError.ArgsUnreadable(
                node.id.value,
                s"dataRef '${ref.value}' resolves to $kind, not ToolCallData"
              )
            )

  /** Parses the supported fs_read argsSummary form; anything else is typed. */
  private[lineage] def parseArgs(
      summary: String,
      nodeId: String
  ): Either[RecomputeError, FsReadArgs] =
    def fail(detail: String): Either[RecomputeError, FsReadArgs] =
      Left(RecomputeError.ArgsUnreadable(nodeId, detail))

    val parsed = summary.split(" ").toVector.filter(_.nonEmpty)
    if parsed.isEmpty then fail("argsSummary is empty")
    else
      val collected =
        parsed.foldLeft[Either[String, Map[String, String]]](
          Right(Map.empty)
        ) { (acc, kv) =>
          acc.flatMap { map =>
            kv.split("=", 2) match
              case Array(key, value) =>
                if map.contains(key) then Left(s"duplicate args key '$key'")
                else Right(map + (key -> value))
              case _ => Left(s"argsSummary pair '$kv' is not key=value")
          }
        }
      collected
        .flatMap { map =>
          val unknown = map.keySet -- Set("path", "offset", "max_lines")
          if unknown.nonEmpty then
            Left(
              s"unsupported args keys: ${unknown.toVector.sorted.mkString(",")}"
            )
          else
            map.get("path") match
              case None => Left("argsSummary has no path")
              case Some(path) =>
                def intOf(key: String): Either[String, Option[Int]] =
                  map.get(key) match
                    case None => Right(None)
                    case Some(raw) =>
                      try
                        val v = Integer.parseInt(raw)
                        Right(Some(v))
                      catch
                        case _: NumberFormatException =>
                          Left(s"args key '$key' is not an integer: $raw")
                intOf("offset").flatMap { offset =>
                  intOf("max_lines").flatMap { maxLines =>
                    checkRanges(offset, maxLines).map(_ =>
                      FsReadArgs(path, offset, maxLines)
                    )
                  }
                }
        }
        .left
        .map(detail => RecomputeError.ArgsUnreadable(nodeId, detail))

  /** Bound checks of the optional fs_read args (ReadTool validation rules). */
  private def checkRanges(
      offset: Option[Int],
      maxLines: Option[Int]
  ): Either[String, Unit] =
    offset match
      case Some(v) if v < 0 => Left(s"offset must be >= 0, got $v")
      case _ =>
        maxLines match
          case Some(v) if v < 1 => Left(s"max_lines must be >= 1, got $v")
          case Some(v) if v > HardMaxLines =>
            Left(s"max_lines must be <= $HardMaxLines, got $v")
          case _ => Right(())

  /** Canonical args re-emission: `path`, then `offset`, then `max_lines`. */
  private[lineage] def canonicalArgs(args: FsReadArgs): String =
    val base = "path=" + args.path
    val withOffset = args.offset.fold(base)(o => base + " offset=" + o)
    args.maxLines.fold(withOffset)(m => withOffset + " max_lines=" + m)

  /** The real bounded workspace read — ReadTool discipline without the ZIO
    * surface (the replay path is synchronous Either). See the class scaladoc
    * for the full rule list.
    */
  private[lineage] def readBounded(
      workspace: Path,
      relativePath: String,
      offsetLine: Int,
      maxLines: Int,
      nodeId: String
  ): Either[RecomputeError, String] =
    def failed(detail: String): Left[RecomputeError, String] =
      Left(RecomputeError.ReadFailed(nodeId, detail))
    try
      val root = workspace.toRealPath()
      if relativePath.isBlank then failed("relative path is blank")
      else if relativePath.contains('\u0000') then
        failed("relative path contains NUL")
      else
        val raw = root.resolve(relativePath).normalize()
        if !raw.startsWith(root) then
          failed(s"path escapes workspace (traversal): $relativePath")
        else if !Files.isRegularFile(raw) then
          failed(s"not a regular file: $relativePath")
        else
          val real = raw.toRealPath()
          if !real.startsWith(root) then
            failed(s"symlink escapes workspace: $relativePath")
          else
            val bytes = Files.readAllBytes(real)
            if bytes.contains(0.toByte) then
              failed(
                s"binary file (NUL byte) is not read as text: $relativePath"
              )
            else
              val text = new String(bytes, StandardCharsets.UTF_8)
              val lines =
                if text.isEmpty then Vector.empty
                else text.split("\n", -1).toVector.map(_.stripSuffix("\r"))
              val from = math.min(offsetLine, lines.length)
              val until = math.min(from + maxLines, lines.length)
              val joined = lines.slice(from, until).mkString("\n")
              Right(tail(joined))
    catch
      case error: java.io.IOException =>
        failed(s"read failed for '$relativePath': ${String.valueOf(error)}")

  /** Last `TailMaxChars` characters — the honest tail within the frozen
    * `ToolCallData` shape.
    */
  private def tail(text: String): String =
    if text.length <= TailMaxChars then text
    else text.substring(text.length - TailMaxChars)

end FsReadRecompute

/** The fs_search recompute (LIN-02D2): re-runs the recorded call's bounded
  * search against the CURRENT workspace (SearchTool discipline) and assembles
  * the fresh [[NodeData.ToolCallData]].
  *
  * The recorded document is needed for the call ARGS (the graph model carries
  * none — ADR-027 §2): `query`/`glob`/`max_matches` are parsed from the
  * recorded `argsSummary` of the node's dataRef and re-executed. The recorded
  * RESULT is deliberately NOT consulted — recomputation stays independent of
  * the recorded answer so the comparison is meaningful.
  *
  * Search discipline (mirrors `raider.tools.search.SearchTool`): the walk is
  * rooted at the workspace REAL path and never follows symlinks; only regular
  * non-symlink files are scanned (an inside symlink is SKIPPED, never read — no
  * out-of-workspace read can occur); production bounds are mirrored (global
  * match bound, `HardMaxFiles` walk cap, `HardMaxScanned` scan cap, 2 MiB
  * per-file scan cap, 500-char line snippets, NUL-byte binary files skipped);
  * truncation is DECLARED in the tail, never hidden. An escape-shaped glob
  * (blank, NUL, absolute path, `..` traversal or an uncompilable pattern) is a
  * typed [[RecomputeError.ArgsUnreadable]] BEFORE any walk, and a scanned file
  * whose realpath escapes the root is a typed [[RecomputeError.ReadFailed]]
  * instead of a read.
  *
  * Determinism: the workspace walk is collected bounded and sorted by relative
  * path (the production walk order is filesystem-defined), so match order is
  * the canonical `(file, line)` order and a repeat replay over an unchanged
  * tree renders byte-identically. Supported argsSummary form: space-separated
  * `key=value` pairs with keys `query` (required, literal text — a query
  * containing a space is outside the summary form and typed
  * [[RecomputeError.ArgsUnreadable]]), `glob` (optional, matched against file
  * NAMES exactly as the production tool does), `max_matches` (optional,
  * 1..10000). An EMPTY query is the production-defined empty result, not an
  * error. The recomputed `argsSummary` is the canonical re-emission (`query`,
  * then `glob`, then `max_matches`) so canonical-form drift is detectable.
  * `resultTail` is the canonical rendering of the recomputed search — one
  * `file:line:snippet` per match plus a final `truncated=<b> files_scanned=<n>`
  * stats line — bounded to the LAST [[TailMaxChars]] characters (the frozen
  * `ToolCallData` has no truncation flag — the bound is documented here, never
  * hidden behind invented fields).
  */
final class FsSearchRecompute(recorded: LineageDataDocument)
    extends ToolRecompute:

  def recompute(
      node: SessionNode.ToolCall,
      workspace: Path
  ): Either[RecomputeError, NodeData.ToolCallData] =
    if node.tool != FsSearchRecompute.Tool then
      Left(RecomputeError.Unsupported(node.tool))
    else
      for
        args <- FsSearchRecompute.resolveArgs(node, recorded)
        tail <- FsSearchRecompute.searchBounded(
          workspace,
          args,
          node.id.value
        )
      yield NodeData.ToolCallData(
        tool = node.tool,
        callId = node.callId,
        recovery = node.recovery.toString,
        outcome = Some("Succeeded"), // a completed bounded search IS a success
        argsSummary = FsSearchRecompute.canonicalArgs(args),
        resultTail = Some(tail)
      )

end FsSearchRecompute

object FsSearchRecompute:

  val Tool: String = "fs_search"

  /** Bounds mirrored from the production search (`raider.tools.search.
    * SearchTool` and the `CodingToolset` defaults) — recomputation must enforce
    * the same limits, not weaker ones.
    */
  val HardMaxMatches: Int = 10_000
  val DefaultMaxMatches: Int = 40
  val HardMaxFiles: Int = 50_000
  val HardMaxScanned: Int = 20_000
  val MaxScanFileBytes: Long = 2L * 1024 * 1024
  val MaxLineSnippet: Int = 500

  /** Bound of the recomputed `resultTail` (last-N characters; the same
    * diagnostics-scale discipline as [[FsReadRecompute.TailMaxChars]]).
    */
  val TailMaxChars: Int = 2_000

  private[lineage] final case class FsSearchArgs(
      query: String,
      glob: Option[String],
      maxMatches: Option[Int]
  )

  /** Resolves the recorded call args of `node` from the data resource. */
  private[lineage] def resolveArgs(
      node: SessionNode.ToolCall,
      doc: LineageDataDocument
  ): Either[RecomputeError, FsSearchArgs] =
    FsReadRecompute
      .recordedCallData(node, doc)
      .flatMap(data => parseArgs(data.argsSummary, node.id.value))

  /** Parses the supported fs_search argsSummary form; anything else is typed.
    */
  private[lineage] def parseArgs(
      summary: String,
      nodeId: String
  ): Either[RecomputeError, FsSearchArgs] =
    def fail(detail: String): Either[RecomputeError, FsSearchArgs] =
      Left(RecomputeError.ArgsUnreadable(nodeId, detail))

    val parsed = summary.split(" ").toVector.filter(_.nonEmpty)
    if parsed.isEmpty then fail("argsSummary is empty")
    else
      val collected =
        parsed.foldLeft[Either[String, Map[String, String]]](
          Right(Map.empty)
        ) { (acc, kv) =>
          acc.flatMap { map =>
            kv.split("=", 2) match
              case Array(key, value) =>
                if map.contains(key) then Left(s"duplicate args key '$key'")
                else Right(map + (key -> value))
              case _ => Left(s"argsSummary pair '$kv' is not key=value")
          }
        }
      collected
        .flatMap { map =>
          val unknown = map.keySet -- Set("query", "glob", "max_matches")
          if unknown.nonEmpty then
            Left(
              s"unsupported args keys: ${unknown.toVector.sorted.mkString(",")}"
            )
          else
            map.get("query") match
              case None => Left("argsSummary has no query")
              case Some(query) =>
                val glob = map.get("glob")
                globCheck(glob).flatMap { _ =>
                  def intOf(key: String): Either[String, Option[Int]] =
                    map.get(key) match
                      case None => Right(None)
                      case Some(raw) =>
                        try
                          val v = Integer.parseInt(raw)
                          Right(Some(v))
                        catch
                          case _: NumberFormatException =>
                            Left(s"args key '$key' is not an integer: $raw")
                  intOf("max_matches").flatMap { maxMatches =>
                    checkRange(maxMatches).map(_ =>
                      FsSearchArgs(query, glob, maxMatches)
                    )
                  }
                }
        }
        .left
        .map(detail => RecomputeError.ArgsUnreadable(nodeId, detail))

  /** Escape-shaped globs are typed BEFORE any walk: blank, NUL, absolute path
    * or `..` traversal segments cannot be honored inside the workspace; an
    * uncompilable pattern is malformed args, not a search failure.
    */
  private def globCheck(glob: Option[String]): Either[String, Unit] =
    glob match
      case None => Right(())
      case Some(g) =>
        if g.isBlank then Left("args glob is blank")
        else if g.contains('\u0000') then Left("args glob contains NUL")
        else if g.startsWith("/") then
          Left(s"glob escapes workspace (absolute path): $g")
        else if g.split("/").contains("..") then
          Left(s"glob escapes workspace (traversal): $g")
        else
          try
            FileSystems.getDefault.getPathMatcher("glob:" + g)
            Right(())
          catch
            case _: IllegalArgumentException =>
              Left(s"args glob is not a valid glob pattern: $g")

  /** Bound check of the optional `max_matches` (SearchTool validation rule). */
  private def checkRange(maxMatches: Option[Int]): Either[String, Unit] =
    maxMatches match
      case Some(v) if v < 1 => Left(s"max_matches must be >= 1, got $v")
      case Some(v) if v > HardMaxMatches =>
        Left(s"max_matches must be <= $HardMaxMatches, got $v")
      case _ => Right(())

  /** Canonical args re-emission: `query`, then `glob`, then `max_matches`. */
  private[lineage] def canonicalArgs(args: FsSearchArgs): String =
    val base = "query=" + args.query
    val withGlob = args.glob.fold(base)(g => base + " glob=" + g)
    args.maxMatches.fold(withGlob)(m => withGlob + " max_matches=" + m)

  /** The real bounded workspace search — SearchTool discipline without the ZIO
    * surface (the replay path is synchronous Either). See the class scaladoc
    * for the full rule list.
    */
  private[lineage] def searchBounded(
      workspace: Path,
      args: FsSearchArgs,
      nodeId: String
  ): Either[RecomputeError, String] =
    def failed(detail: String): Left[RecomputeError, String] =
      Left(RecomputeError.ReadFailed(nodeId, detail))
    try
      val root = workspace.toRealPath()
      if args.query.isEmpty then
        // Defined production behavior: an empty query matches nothing.
        Right(render(Vector.empty, truncated = false, filesScanned = 0))
      else
        val matcher =
          args.glob.map(g => FileSystems.getDefault.getPathMatcher("glob:" + g))
        // Bounded deterministic collection of the walk: capped at
        // HardMaxFiles like the production walk (hitting the cap is DECLARED
        // truncation), sorted by relative path ("" is the root itself —
        // skipped below as a directory).
        val (collected, walkCapped) =
          Using.resource(Files.walk(root)) { stream =>
            val it = stream.iterator().asScala
            val buf = Vector.newBuilder[(String, Path)]
            var walked = 0
            var capped = false
            while it.hasNext && !capped do
              val p = it.next()
              walked += 1
              if walked > HardMaxFiles then capped = true
              else buf += (root.relativize(p).toString -> p)
            (buf.result(), capped)
          }
        val sorted = collected.sortBy(_._1)
        var truncated = walkCapped
        val found = Vector.newBuilder[String]
        var filesScanned = 0
        var matchesLeft = args.maxMatches.getOrElse(DefaultMaxMatches)
        // The typed refusal of a scanned file whose realpath escapes the
        // root — unreachable for honest trees, but a READ of the outside is
        // never attempted (defense-in-depth, SearchTool skips symlinks).
        var escape: Option[String] = None
        val it = sorted.iterator
        while it.hasNext && !truncated && escape.isEmpty && matchesLeft > 0 do
          val (rel, p) = it.next()
          if Files.isRegularFile(p) && !Files.isSymbolicLink(p) then
            if matcher.forall(_.matches(p.getFileName)) then
              filesScanned += 1
              if filesScanned > HardMaxScanned then truncated = true
              else if !p.toRealPath().startsWith(root) then escape = Some(rel)
              else
                matchesLeft = scanFile(p, rel, args.query, matchesLeft, found)
                if matchesLeft == 0 then
                  // Production rule: an exhausted match bound is DECLARED
                  // truncation (there may be more matches beyond it).
                  truncated = true
        escape match
          case Some(rel) =>
            failed(s"resolved path escapes workspace: $rel")
          case None =>
            Right(render(found.result(), truncated, filesScanned))
    catch
      case scala.util.control.NonFatal(error) =>
        failed(s"search failed: ${String.valueOf(error)}")

  /** Single-file bounded scan: size-capped, binary (NUL) skipped, literal
    * substring match, 500-char snippets (SearchTool rules). Returns the matches
    * left after this file.
    */
  private def scanFile(
      p: Path,
      rel: String,
      query: String,
      maxLeft: Int,
      found: scala.collection.mutable.Builder[String, Vector[String]]
  ): Int =
    var left = maxLeft
    if Files.size(p) <= MaxScanFileBytes then
      val bytes = Files.readAllBytes(p)
      if !bytes.contains(0.toByte) then
        val text = new String(bytes, StandardCharsets.UTF_8)
        val lines =
          if text.isEmpty then Vector.empty
          else text.split("\n", -1).toVector
        var lineNo = 0
        val it = lines.iterator
        while it.hasNext && left > 0 do
          val line = it.next()
          lineNo += 1
          if line.indexOf(query) >= 0 then
            found += rel + ":" + lineNo + ":" + line.take(MaxLineSnippet)
            left -= 1
    left

  /** Canonical tail: one `file:line:snippet` per match, then the declared
    * truncation + scanned-file stats, bounded to the LAST [[TailMaxChars]].
    */
  private def render(
      matches: Vector[String],
      truncated: Boolean,
      filesScanned: Int
  ): String =
    val lines = matches :+ s"truncated=$truncated files_scanned=$filesScanned"
    tail(lines.mkString("\n"))

  /** Last `TailMaxChars` characters — the honest tail within the frozen
    * `ToolCallData` shape (same discipline as FsReadRecompute).
    */
  private def tail(text: String): String =
    if text.length <= TailMaxChars then text
    else text.substring(text.length - TailMaxChars)

end FsSearchRecompute

/** One replayed ToolCall node (LIN-05F): the recompute outcome (when it ran)
  * and the comparison verdict against the recorded source.
  */
final case class ToolReplayEntry(
    nodeId: String,
    tool: String,
    callId: String,
    recovery: RecoveryClass,
    recomputed: Option[NodeData.ToolCallData],
    comparison: Either[RecomputeError, ToolComparison]
)

/** Result of one tool-replay pass (LIN-05F): every ToolCall node of the graph
  * in `(seq, id)` order with its recompute/compare outcome. Pure data over
  * already-loaded inputs plus bounded workspace reads — a repeat pass over the
  * same graph + record + workspace yields an equal report and a byte-identical
  * [[render]] (no map iteration, no timestamps).
  */
final case class ToolReplayReport(
    rootId: String,
    sessionId: String,
    toolNodes: Int,
    entries: Vector[ToolReplayEntry]
):

  /** Canonical deterministic text form of the report: entries in traversal
    * order, fixed field order — two equal reports always render
    * byte-identically.
    */
  def render: String =
    val out = new StringBuilder
    out
      .append("tool-replay root=")
      .append(rootId)
      .append(" session=")
      .append(sessionId)
      .append(" tools=")
      .append(toolNodes)
      .append('\n')
    entries.foreach { entry =>
      out
        .append("node ")
        .append(entry.nodeId)
        .append(" tool=")
        .append(entry.tool)
        .append(" callId=")
        .append(entry.callId)
        .append(" recovery=")
        .append(entry.recovery)
        .append(' ')
        .append(ToolReplay.statusLabel(entry.comparison))
        .append('\n')
    }
    out.result()

end ToolReplayReport

/** Tool-replay slice (LIN-05F, ADR-027 §2–3): recomputes the deterministic
  * read-only tool calls a [[ReplayInterpreter]] pass deferred, against the
  * CURRENT workspace, and compares each fresh result with the recorded source.
  *
  * Traversal: ToolCall nodes sorted by `(seq, id)` — independent of the `nodes`
  * vector emission order. Per node:
  *   - no recorded document passed → [[ToolComparison.NoRecord]] (the graph
  *     model carries no args, so without the record there is nothing to
  *     re-execute — reported honestly, never guessed);
  *   - otherwise the registered recompute engine for the node's tool NAME runs
  *     (`fs_read` since LIN-05F, `fs_search` since LIN-02D2); a recompute
  *     failure is the entry's typed error (no comparison); a success is
  *     compared against the recorded `ToolCallData` under the node's dataRef (a
  *     ref resolving to non-tool data cannot be compared and is reported as
  *     [[RecomputeError.ArgsUnreadable]] from the recompute);
  *   - a tool WITHOUT a registered engine stays honestly
  *     [[RecomputeError.Unsupported]] — the dispatch never grows silently.
  */
object ToolReplay:

  /** Replays the ToolCall nodes of `graph` against `workspace`. */
  def replayTools(
      graph: SessionGraph,
      recorded: Option[LineageDataDocument],
      workspace: Path
  ): ToolReplayReport =
    val toolNodes = graph.nodes
      .collect { case t: SessionNode.ToolCall => t }
      .sortBy(t => (t.seq, t.id.value))
    // Registered deterministic read-only engines, dispatched by the node's
    // tool NAME (LIN-02D2: fs_search joined fs_read). A tool name absent
    // from this map is Unsupported — never emulated.
    val engines: Map[String, ToolRecompute] = recorded match
      case None => Map.empty
      case Some(doc) =>
        Map(
          FsReadRecompute.Tool -> FsReadRecompute(doc),
          FsSearchRecompute.Tool -> FsSearchRecompute(doc)
        )
    val entries = toolNodes.map { node =>
      val rec: Option[NodeData.ToolCallData] = recorded.flatMap { doc =>
        node.dataRef
          .flatMap(ref => doc.data.get(ref.value))
          .collect { case t: NodeData.ToolCallData => t }
      }
      // ONE recompute per node: the fresh source feeds both the entry and
      // the comparison (recompute is real workspace IO — never re-run it).
      val outcome: (
          Option[NodeData.ToolCallData],
          Either[RecomputeError, ToolComparison]
      ) =
        (recorded, engines.get(node.tool)) match
          case (Some(_), Some(rc)) =>
            rc.recompute(node, workspace) match
              case Left(err) => (None, Left(err))
              case Right(fresh) =>
                (
                  Some(fresh),
                  Right(ToolComparison.compareRecorded(fresh, rec))
                )
          case (Some(_), None) =>
            (None, Left(RecomputeError.Unsupported(node.tool)))
          case _ => (None, Right(ToolComparison.NoRecord))
      ToolReplayEntry(
        node.id.value,
        node.tool,
        node.callId,
        node.recovery,
        outcome._1,
        outcome._2
      )
    }
    ToolReplayReport(
      graph.rootId.value,
      graph.sessionId.value,
      toolNodes.size,
      entries
    )

  /** Stable text label of an entry verdict (used by
    * [[ToolReplayReport.render]]).
    */
  private[lineage] def statusLabel(
      verdict: Either[RecomputeError, ToolComparison]
  ): String = verdict match
    case Right(ToolComparison.Match) => "match"
    case Right(ToolComparison.Mismatch(fields)) =>
      "mismatch fields=" + fields.mkString(",")
    case Right(ToolComparison.NoRecord) => "no-record"
    case Left(RecomputeError.Unsupported(tool)) =>
      "error=unsupported tool=" + tool
    case Left(RecomputeError.ArgsUnreadable(_, detail)) =>
      "error=args-unreadable detail=" + detail
    case Left(RecomputeError.ReadFailed(_, detail)) =>
      "error=read-failed detail=" + detail

end ToolReplay
