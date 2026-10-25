package raider.cli.chat

import raider.cli.portable.PortableTrustedRegistry
import raider.core.RaiderError
import raider.dsl.portable.{
  PortableChatExport,
  PortableExportOptions,
  PortableExportResult,
  PortableExportTarget
}
import raider.dsl.workflow.{
  ChatWorkflowExport,
  ExportResult,
  RenderOptions,
  StsParam
}

import java.nio.file.{Files, Path, Paths}

/** RAI-160: `:export-workflow [--out <dir>] [--name <name>] [--format
  * scala|portable] [--target-path <path>] [--summary <text>]` — the chat
  * command that wires the validated task selection snapshot (RAI-155.b) through
  * the TaskWorkflowAdapter into `ChatWorkflowExport.exportTo` (RAI-155.c;
  * ADR-033 §6 export semantics) or, with `--format portable`, into
  * `PortableChatExport.exportTo` (RAI-203).
  *
  * Discipline:
  *   - the command consumes ONLY the stored selection snapshot — a session
  * without one is refused (R2 for a legacy/empty ledger, R1 otherwise);
  *   - `targetPath`/`summary` are the externalized task params (ADR-033 §4.1/
  * §6.6): explicit at export time, NEVER inferred from captured text;
  *   - the export name defaults to a deterministic slug derived from the
  * intent turn id (provenance, not invention); `--out` defaults to
  * `chat-workflow-export` under the current directory (its parent must exist —
  * export never creates parents);
  *   - `--format scala` (default) keeps the native source export byte-for-byte;
  * `--format portable` renders the same validated snapshot as a portable
  * workflow v1 document + closed manifest + input example — a selection with
  * named trusted checks is refused there (CWF-R3, the checks are not
  * representable in portable v1 and the refusal names the retained Scala
  * option);
  *   - staging+rename, never-overwrite and the R5 path rules are INHERITED
  * unchanged from the two exporters; refusals surface typed with the structured
  * `CWF-R…` detail (ADR-033 §8);
  *   - the success message names the three §6.2 artifact paths (document,
  * manifest, input example) plus the identity digests.
  *
  * `execute` returns the lines the REPL prints (console-adapter discipline: the
  * handler stays a thin printer, and the fixture drives the exact command
  * path). Export dispatches nothing agent-shaped — it renders text and writes
  * files (CWF-C02/A02).
  */
object ExportWorkflowCommand:

  val usageText: String =
    "usage: :export-workflow [--out <dir>] [--name <name>] " +
      "[--format scala|portable] [--target-path <path>] " +
      "[--summary <text — consumes the rest of the line>]"

  /** Default target directory (under the current directory; the parent of the
    * target — here the cwd — must already exist, R5).
    */
  val DefaultOutDir: String = "chat-workflow-export"

  val FormatScala: String = "scala"
  val FormatPortable: String = "portable"

  final case class Args(
      out: Option[String] = None,
      name: Option[String] = None,
      format: Option[String] = None,
      targetPath: Option[String] = None,
      summary: Option[String] = None
  )

  /** Pure argv parse. `Left` is the usage text (the `TaskCommands.Usage`
    * pattern); an empty line is a valid all-defaults export attempt.
    */
  def parse(line: String): Either[String, Args] =
    val toks = line.trim.split("\\s+").toList.filter(_.nonEmpty)
    @scala.annotation.tailrec
    def loop(
        rest: List[String],
        acc: Args
    ): Either[String, Args] = rest match
      case Nil                   => Right(acc)
      case "--out" :: v :: tail  => loop(tail, acc.copy(out = Some(v)))
      case "--name" :: v :: tail => loop(tail, acc.copy(name = Some(v)))
      case "--format" :: v :: tail =>
        if v == FormatScala || v == FormatPortable then
          loop(tail, acc.copy(format = Some(v)))
        else
          Left(
            s"unknown --format '$v' (supported: scala, portable); $usageText"
          )
      case "--target-path" :: v :: tail =>
        loop(tail, acc.copy(targetPath = Some(v)))
      // The summary is free TEXT (the externalized per-run task instance,
      // ADR-033 §6.6): it consumes the REST of the line (the `:task check`
      // argv precedent) and must therefore be the last flag.
      case "--summary" :: rest if rest.nonEmpty =>
        loop(Nil, acc.copy(summary = Some(rest.mkString(" "))))
      case "--summary" :: Nil =>
        Left(s"--summary requires a value; $usageText")
      case flag :: Nil if flag.startsWith("--") =>
        Left(s"$flag requires a value; $usageText")
      case other :: _ =>
        Left(s"unknown argument '$other'; $usageText")
    loop(toks, Args())

  /** The exported export name when `--name` is absent: `task_` + the sanitized
    * intent turn id — deterministic provenance (same selection -> same name),
    * no invented content. The renderer slugifies/sanitizes again.
    */
  def defaultName(selection: TaskSelection): String =
    val cleaned = selection.intent.turnId.map(c =>
      if c.isLetterOrDigit || c == '_' then c else '_'
    )
    val slug =
      if cleaned.isEmpty || cleaned.head.isDigit then "_" + cleaned
      else cleaned
    "task_" + slug

  private def buildParams(
      args: Args
  ): Either[RaiderError, List[StsParam]] =
    (args.targetPath.map(_.trim), args.summary.map(_.trim)) match
      case (Some(tp), Some(s)) if tp.nonEmpty && s.nonEmpty =>
        Right(
          List(StsParam("targetPath", tp), StsParam("summary", s))
        )
      case _ =>
        Left(
          RaiderError.InputValidation(
            "CWF-R1: the externalized task params 'targetPath' and 'summary' " +
              "are required at export time (ADR-033 §4.1/§6.6 — they vary per " +
              "run and are never inferred from captured text); pass " +
              "--target-path <path> --summary <text>"
          )
        )

  /** The command body minus the REPL: parse -> snapshot/refusal checks ->
    * adapter -> exportTo. Returns the exact output lines. `cwd` is the
    * resolution base for a relative `--out` (production passes the process cwd;
    * the fixture seam passes its own root).
    */
  def execute(
      selection: Option[TaskSelection],
      ledgerSize: Int,
      sessionId: String,
      chatProvider: String,
      model: String,
      baseUrl: String,
      argLine: String,
      cwd: Path = Paths.get("").toAbsolutePath
  ): Vector[String] =
    parse(argLine) match
      case Left(usage) => Vector(usage)
      case Right(args) =>
        selection match
          case None =>
            Vector(refusalLine(noSelectionRefusal(ledgerSize)))
          case Some(sel) =>
            run(sel, sessionId, chatProvider, model, baseUrl, args, cwd)

  /** R2 for a legacy/empty ledger (the canonical TurnLedger text), R1 when a
    * ledger exists but nothing was selected yet.
    */
  def noSelectionRefusal(ledgerSize: Int): RaiderError =
    if ledgerSize <= 0 then
      // Reuse the canonical R2 refusal text of the select path (ADR-033 §5.3).
      TurnLedger.validateSelection(Nil, Nil, 1, 1).swap.toOption.get
    else
      RaiderError.InputValidation(
        "CWF-R1: no task selection in this session; export consumes the " +
          "validated snapshot, never the raw ledger — build one explicitly " +
          "with :task mark <n> intent + :task select <n>"
      )

  /** One export attempt over a concrete snapshot. The format flag dispatches
    * between the native Scala export (default) and the portable v1 export.
    */
  def run(
      selection: TaskSelection,
      sessionId: String,
      chatProvider: String,
      model: String,
      baseUrl: String,
      args: Args,
      cwd: Path
  ): Vector[String] =
    if args.format.getOrElse(FormatScala) == FormatPortable then
      exportPortableSelection(
        selection,
        sessionId,
        chatProvider,
        model,
        baseUrl,
        args,
        cwd
      ) match
        case Left(err)     => Vector(refusalLine(err))
        case Right(result) => portableSuccessLines(result)
    else
      exportSelection(
        selection,
        sessionId,
        chatProvider,
        model,
        baseUrl,
        args,
        cwd
      ) match
        case Left(err)     => Vector(refusalLine(err))
        case Right(result) => successLines(result)

  private def refusalLine(err: RaiderError): String =
    s"export refused [${err.code}]: ${err.detail.take(400)}"

  /** The full export pipeline (also the fixture/adapter seam): adapter ->
    * exportTo. Pure data in, `ExportResult` or a typed refusal out.
    */
  def exportSelection(
      selection: TaskSelection,
      sessionId: String,
      chatProvider: String,
      model: String,
      baseUrl: String,
      args: Args,
      cwd: Path
  ): Either[RaiderError, ExportResult] =
    for
      origin <- TaskWorkflowAdapter.toStsOrigin(chatProvider, model, baseUrl)
      params <- buildParams(args)
      sts <- TaskWorkflowAdapter.toStsSelection(
        sessionId,
        origin,
        selection,
        params
      )
      name = args.name.getOrElse(defaultName(selection))
      outDir = cwd.resolve(args.out.getOrElse(DefaultOutDir))
      result <- ChatWorkflowExport.exportTo(sts, RenderOptions(name), outDir)
    yield result

  /** Success message: names the artifact, the manifest and the input example
    * (the §6.2 layout order of `ExportResult.files`), plus identity digests.
    */
  def successLines(result: ExportResult): Vector[String] =
    val scala = result.files.find(_.getFileName.toString.endsWith(".scala"))
    val manifest =
      result.files.find(_.getFileName.toString.endsWith("-manifest.json"))
    val input =
      result.files.find(_.getFileName.toString.endsWith(".input.json"))
    Vector(
      s"workflow exported: ${result.targetDir}",
      s"  artifact: ${scala.map(_.toString).getOrElse("(missing)")}",
      s"  manifest: ${manifest.map(_.toString).getOrElse("(missing)")}",
      s"  input example: ${input.map(_.toString).getOrElse("(missing)")}",
      s"  identity: spec sha256-${result.specDigest}, source sha256-${result.sourceDigest}",
      "Edit the artifact, then compile with 'raider compile <source> --out <dir>' " +
        "and run with 'raider workflow run --classes <dir> --input <file> --out <out>'."
    )

  /** The portable export pipeline (RAI-203): the same adapter seam, then
    * `PortableChatExport.exportTo` with THIS launcher's pinned trusted
    * references (profile `coder`, toolset `workspace`, check
    * `workspace-marker-present`, pinned snapshot digest). The document only
    * NAMES these authorities; a run must resolve them against the same pins.
    *
    * By construction this pipeline refuses CWF-R3 for every supported chat
    * selection: ADR-033 §4.1 requires at least one NAMED trusted check in a
    * validated selection and portable v1 has no user-named check construct, so
    * `requireRepresentable` refuses while naming the retained Scala option. The
    * command-level value of the portable format is the explained refusal plus
    * this seam; the portable happy path (check-free selections from other
    * launchers, the shipped recipes) is exercised directly against
    * `PortableChatExport`.
    */
  def exportPortableSelection(
      selection: TaskSelection,
      sessionId: String,
      chatProvider: String,
      model: String,
      baseUrl: String,
      args: Args,
      cwd: Path
  ): Either[RaiderError, PortableExportResult] =
    for
      origin <- TaskWorkflowAdapter.toStsOrigin(chatProvider, model, baseUrl)
      params <- buildParams(args)
      sts <- TaskWorkflowAdapter.toStsSelection(
        sessionId,
        origin,
        selection,
        params
      )
      name = args.name.getOrElse(defaultName(selection))
      outDir = cwd.resolve(args.out.getOrElse(DefaultOutDir))
      result <- PortableChatExport.exportTo(
        sts,
        PortableExportOptions(name),
        PortableExportTarget(
          PortableTrustedRegistry.ProfileCoder,
          PortableTrustedRegistry.ToolsetWorkspace,
          PortableTrustedRegistry.CheckWorkspaceMarker,
          PortableTrustedRegistry.workspaceMarkerSnapshot
        ),
        outDir,
        System.currentTimeMillis()
      )
    yield result

  /** Success message for a portable export: the three artifact paths, the
    * identity digests, and the validate/run next steps of the shipped launcher
    * (including the pinned marker-check precondition).
    */
  def portableSuccessLines(result: PortableExportResult): Vector[String] =
    val document =
      result.files.find(_.getFileName.toString.endsWith(".workflow.json"))
    val manifest =
      result.files.find(_.getFileName.toString.endsWith("-manifest.json"))
    val input =
      result.files.find(_.getFileName.toString.endsWith(".input.json"))
    Vector(
      s"workflow exported (portable v1): ${result.targetDir}",
      s"  document: ${document.map(_.toString).getOrElse("(missing)")}",
      s"  manifest: ${manifest.map(_.toString).getOrElse("(missing)")}",
      s"  input example: ${input.map(_.toString).getOrElse("(missing)")}",
      s"  identity: document ${result.documentSha256}, input example ${result.inputExampleSha256}",
      "Edit the document, then validate with 'raider workflow check --doc <file>' " +
        "and run with 'raider workflow run --doc <file> --input <file> --out <out> " +
        "--workspace <dir>' (create marker.txt in the workspace first: the pinned " +
        "check is 'workspace-marker-present')."
    )

  /** True when the three §6.2 files of a finished export are all present. */
  def completeExport(result: ExportResult): Boolean =
    result.files.forall(f => Files.isRegularFile(f))

end ExportWorkflowCommand
