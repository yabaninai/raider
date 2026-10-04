package raider.tools

import raider.core.*
import raider.tools.files.read.{ReadResult, ReadTool, Workspace}
import raider.tools.process.{ExecOutcome, ExecRequest, ProcessTool}
import raider.tools.search.SearchTool
import zio.{Scope, ZIO}
import zio.json.*

/** The coding toolset (RAI-014/015/016 slices wired for self-hosting): REAL
  * workspace-rooted tools behind the core Tool JSON contract, so a model-driven
  * AgentLoop can read, search and run commands inside the workspace it was
  * pointed at.
  *
  *   - `fs_read   {"path","offset"?,"max_lines"?}` — bounded ranged read with
  *     SHA-256 provenance; workspace containment (realpath, no
  *     symlink/traversal escapes); binary files refused as typed failures.
  *   - `fs_search {"query","glob"?,"max_matches"?}` — bounded search, declared
  *     truncation, single-line matches.
  *   - `proc_run  {"argv",...}` — structured argv (NO shell), cwd inside the
  *     workspace, empty-env + explicit allowlist, timeout with graceful-then-
  *     force kill, bounded output. This is the ONLY mutation path in this slice
  *     (argv is fully visible in every event/log — no hidden writes).
  *
  * All outputs are single-line JSON matched back by tool_call_id. Tool failures
  * are typed RaiderErrors (→ structured feedback for the loop).
  */
final class CodingToolset private (
    read: ReadTool,
    search: SearchTool,
    process: ProcessTool,
    edit: raider.tools.files.edit.EditTool
):

  def registry: Either[RaiderError, ToolRegistry] =
    ToolRegistry.build(List(fsReadT, fsSearchT, procRunT, fsEditT))

  // ---- fs_read ----
  private def fsReadInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[CodingToolset.FsReadArgs] match
      case Left(err) =>
        ZIO.fail(RaiderError.InputValidation(s"fs_read args: ${err.take(120)}"))
      case Right(a) =>
        read
          .read(
            a.path,
            a.offset.getOrElse(0),
            a.max_lines.getOrElse(CodingToolset.DefaultMaxLines)
          )
          .map(CodingToolset.encodeRead)

  // ---- fs_search ----
  private def fsSearchInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[CodingToolset.FsSearchArgs] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"fs_search args: ${err.take(120)}")
        )
      case Right(a) =>
        search
          .search(
            a.query,
            a.glob,
            a.max_matches.getOrElse(CodingToolset.DefaultMaxMatches)
          )
          .map(CodingToolset.encodeSearch)

  // ---- proc_run ----
  private def procRunInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[CodingToolset.ProcRunArgs] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"proc_run args: ${err.take(120)}")
        )
      case Right(a) =>
        val req = ExecRequest(
          argv = a.argv,
          cwdRelative = a.cwd,
          envAllowlist = a.env_allowlist.getOrElse(Nil),
          envValues = a.env_values.getOrElse(Map.empty),
          timeout = zio.Duration.fromSeconds(
            a.timeout_s.getOrElse(CodingToolset.DefaultTimeoutS)
          ),
          maxOutputBytes = 256 * 1024
        )
        process.exec(req).map(CodingToolset.encodeExec)

  private def fsEditInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[CodingToolset.FsEditArgs] match
      case Left(err) =>
        ZIO.fail(RaiderError.InputValidation(s"fs_edit args: ${err.take(120)}"))
      case Right(a) =>
        edit
          .write(a.path, a.content, a.expected_sha256)
          .map(raider.tools.CodingToolset.encodeEdit)

  private def toolOf(
      toolName: String,
      descr: String,
      schema: String,
      run: String => ZIO[Scope, RaiderError, String]
  ): Tool =
    new Tool:
      def name = toolName
      def version = 1
      def description = descr
      def recovery = RecoveryClass.ReadOnly
      def timeoutMs = 300000L
      def capabilities = ToolCapabilities(concurrentSafe = false)
      override def parametersJsonSchema = schema
      def invoke(argumentsJson: String) =
        // POLICY denials (containment, bounds, bad args) are structured
        // feedback to the model (§8/§10) — the run continues and the model
        // can correct itself. Crash-flavored failures stay typed failures.
        run(argumentsJson).either.map {
          case Right(output) => output
          case Left(e) if isPolicyDenial(e) =>
            s"""{"error":${e.code.toJson},"detail":${e.detail.toJson}}"""
          case Left(e) => throw e
        }
      private def isPolicyDenial(e: RaiderError): Boolean = e match
        case _: RaiderError.InputValidation  => true
        case _: RaiderError.OutputValidation => true
        case _: RaiderError.ToolDenied       => true
        case _                               => false

  private val FsReadSchema =
    """{"type":"object","properties":{"path":{"type":"string","description":"relative path inside workspace"},"offset":{"type":"integer","description":"start line (0-based)"},"max_lines":{"type":"integer","description":"max lines to read"}},"required":["path"]}"""

  private val FsSearchSchema =
    """{"type":"object","properties":{"query":{"type":"string","description":"literal text to search for"},"glob":{"type":"string","description":"file glob pattern e.g. *.scala"},"max_matches":{"type":"integer","description":"max results"}},"required":["query"]}"""

  private val ProcRunSchema =
    """{"type":"object","properties":{"argv":{"type":"array","items":{"type":"string"},"description":"command and arguments, NO shell"},"cwd":{"type":"string","description":"relative working directory"},"timeout_s":{"type":"integer","description":"timeout in seconds"}},"required":["argv"]}"""

  private val fsReadT = toolOf(
    "fs_read",
    "Read a text file inside the workspace (bounded, with sha256).",
    FsReadSchema,
    fsReadInvoke
  )

  private val fsSearchT = toolOf(
    "fs_search",
    "Search a literal query in workspace text files.",
    FsSearchSchema,
    fsSearchInvoke
  )

  private val procRunT = toolOf(
    "proc_run",
    "Run a command by explicit argv (NO shell) inside the workspace.",
    ProcRunSchema,
    procRunInvoke
  )

  private val FsEditSchemaStr =
    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"relative path\"},\"content\":{\"type\":\"string\",\"description\":\"new content\"},\"expected_sha256\":{\"type\":\"string\",\"description\":\"SHA-256 of current content; empty string to create\"}},\"required\":[\"path\",\"content\",\"expected_sha256\"]}"

  private val fsEditT = toolOf(
    "fs_edit",
    "Write content to a file (atomic, requires expected_sha256 for existing files; empty string to create).",
    FsEditSchemaStr,
    fsEditInvoke
  )

object CodingToolset:

  val DefaultMaxLines = 400
  val DefaultMaxMatches = 40
  val DefaultTimeoutS = 120

  /** Build the toolset rooted at `workspaceRoot` (absolute or relative CWD). */
  def make(workspaceRoot: String): ZIO[Any, RaiderError, CodingToolset] =
    for
      ws <- Workspace.make(workspaceRoot)
      read = new ReadTool(ws)
      search = new SearchTool(ws)
      process = new ProcessTool(ws)
      edit = new raider.tools.files.edit.EditTool(ws)
    yield new CodingToolset(read, search, process, edit)

  // ---- args wires ----
  private final case class FsReadArgs(
      path: String,
      offset: Option[Int] = None,
      max_lines: Option[Int] = None
  )

  private object FsReadArgs:
    given JsonDecoder[FsReadArgs] = DeriveJsonDecoder.gen[FsReadArgs]

  private final case class FsSearchArgs(
      query: String,
      glob: Option[String] = None,
      max_matches: Option[Int] = None
  )

  private object FsSearchArgs:
    given JsonDecoder[FsSearchArgs] = DeriveJsonDecoder.gen[FsSearchArgs]

  private final case class FsEditArgs(
      path: String,
      content: String,
      expected_sha256: String
  )

  private object FsEditArgs:
    given JsonDecoder[FsEditArgs] = DeriveJsonDecoder.gen[FsEditArgs]

  private final case class ProcRunArgs(
      argv: List[String],
      cwd: Option[String] = None,
      env_allowlist: Option[List[String]] = None,
      env_values: Option[Map[String, String]] = None,
      timeout_s: Option[Int] = None
  )

  private object ProcRunArgs:
    given JsonDecoder[ProcRunArgs] = DeriveJsonDecoder.gen[ProcRunArgs]

  // ---- result encoders (single-line JSON) ----
  private[tools] def encodeRead(r: ReadResult): String =
    val contentJson = r.content.mkString("\n").toJson // properly escaped
    s"""{"totalLines":${r.totalLines},"truncated":${r.truncated},""" +
      s""""sha256":"${r.sha256}","bytes":${r.bytes},"content":$contentJson}"""

  private[tools] def encodeSearch(r: raider.tools.search.SearchResult): String =
    val ms = r.matches
      .map { m =>
        s"""{"file":${m.file.toJson},"line":${m.lineNumber},"text":${m.line.toJson}}"""
      }
      .mkString("[", ",", "]")
    s"""{"matches":$ms,"truncated":${r.truncated},"filesScanned":${r.filesScanned}}"""

  private[tools] def encodeEdit(r: raider.tools.files.edit.EditResult): String =
    val action = if r.created then "created" else "modified"
    s"{\"action\":\"$action\",\"path\":\"${r.path}\",\"old_sha256\":\"${r.oldSha256}\",\"new_sha256\":\"${r.newSha256}\",\"bytes\":${r.bytes}}"

  private[tools] def encodeExec(o: ExecOutcome): String =
    s"""{"exitCode":${o.exitCode.map(_.toString).getOrElse("null")},""" +
      s""""stdout":${o.stdout.toJson},"stderr":${o.stderr.toJson},""" +
      s""""truncated":${o.truncated},"timedOut":${o.timedOut}}"""

end CodingToolset
