package raider.repl

import raider.repl.engine.{EvalResult, ReplEngine}
import zio.Unsafe

/** Console commands of the W10 REPL slice (fast-path).
  *
  * Commands never touch facade statics directly (see ReplCommands): the
  * interpreter world owns all REPL state, so every stateful command is an
  * engine evaluation of a `ReplCommands.print*` expression whose captured
  * output is printed verbatim. Each command returns `true` to keep the loop
  * alive (`false` only for :quit); unknown and not-in-slice commands answer
  * honestly instead of pretending.
  */
object Commands:

  val helpText: String =
    """Commands:
      |  :help            this text
      |  :jobs            list jobs started through the facade (id, status, label)
      |  :cancel <id>     request cancellation of a job (false if already terminal)
      |  :log <id>        show the trace (rounds, tools, results) of a job
      |  :type <expression>  the type of an expression, compiled but not run
      |  :approve         tab shell: approve the plan proposal (one execution job)
      |  :reject          tab shell: reject (cancel) the live plan phase
      |  :reset           clear interpreter bindings; facade prelude is re-imported
      |  :quit            exit the REPL (active jobs are reported, then dropped)
      |Everything else is evaluated as Scala by the in-process compiler.
      |Multiline input: keep typing; a blank line submits the pending buffer.
      |Not in this slice: send steering; the dotty :kind/:doc interpreter commands.""".stripMargin

  /** RAI-110 machine-readable plan-command registry (source-of-truth surface:
    * the RAI-111 palette freshness reads Commands, not a hand list; the tab
    * shell interceptor routes exactly these names through PlanPanel).
    */
  val planCommands: Vector[String] = Vector(":approve", ":reject")

  /** Handle one `:`-command. Returns false only for :quit. */
  def handle(line: String, engine: ReplEngine, rePrelude: () => Unit): Boolean =
    val parts = line.trim.split("\\s+").toList
    val command = parts.headOption.getOrElse("").toLowerCase
    command match
      case ":help" | ":h" =>
        println(helpText)
        true
      case ":jobs" =>
        printCaptured(engine, "raider.repl.facade.ReplCommands.printJobs()")
        true
      case ":cancel" =>
        parts.lift(1) match
          case None =>
            println("usage: :cancel <jobId>   (see :jobs)")
          case Some(raw) =>
            val id = raw.replace("\"", "")
            printCaptured(
              engine,
              s"raider.repl.facade.ReplCommands.printCancel(\"$id\")"
            )
        true
      case ":log" =>
        parts.lift(1) match
          case None =>
            println("usage: :log <jobId>   (see :jobs)")
          case Some(raw) =>
            val id = raw.replace("\"", "")
            printCaptured(
              engine,
              s"raider.repl.facade.ReplCommands.printLog(\"$id\")"
            )
        true
      case ":reset" =>
        Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(engine.reset()).getOrThrow()
        }
        rePrelude()
        println(
          "session state reset (fresh interpreter); facade prelude re-imported; earlier job handles are no longer listed"
        )
        true
      case ":quit" | ":q" | ":exit" =>
        false
      case ":type" =>
        // RAI-124: forward the query VERBATIM (trimmed) to the engine — the
        // dotty driver parses its own `:type` command and answers through
        // ReplCompiler.typeOf: the expression is COMPILED against the live
        // session state and NEVER RUN (no effects, no model calls, no binding
        // mutation; resN numbering untouched). A type error is the honest
        // compiler diagnostic; the empty argument is the driver's own usage
        // line (":type <expression>").
        printCaptured(engine, line.trim)
        true
      case ":approve" | ":reject" =>
        println(
          s"$command is not wired in this console slice: " +
            "plan approval is a tab-shell command (the Plan tab of TabShellHost)"
        )
        true
      case other =>
        println(s"unknown command $other — try :help")
        true

  /** Evaluate a Unit expression whose println output is the command result: the
    * engine captures it into the Value repr (printed once here; Unit evals
    * render nothing, so user resN numbering is untouched).
    */
  private def printCaptured(engine: ReplEngine, expr: String): Unit =
    val result =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(engine.eval(expr)).getOrThrow()
      }
    result match
      case EvalResult.Value(repr) =>
        val text = repr.trim
        if text.nonEmpty then println(text)
      case EvalResult.CompileError(msg) =>
        println(
          s"command failed (prelude broken?): ${msg.trim.linesIterator.take(3).mkString(" | ")}"
        )
      case EvalResult.RuntimeFailure(m) =>
        println(s"command failed: $m")

  // ----- RAI-124 `:type` input plumbing (pure; unit-tested without Main) ----

  /** True when the TRIMMED line opens a `:type` query: the exact `:type` (the
    * driver answers with its own usage line) or the `:type <expr>` form. A
    * near-miss (`:typeFoo`, `:typ`) is NOT a type query — it keeps the ordinary
    * unknown-command path.
    */
  def isTypeQuery(trimmedLine: String): Boolean =
    trimmedLine == ":type" || trimmedLine.startsWith(":type ")

  /** The single-line command source for a buffered `:type` query. The dotty
    * command grammar is one physical line (ParseResult's CommandExtract regex
    * only full-matches a single line), so the multiline pending buffer
    * flattens: real newlines become spaces, then trim (leading indented `:` or
    * trailing whitespace would otherwise fall out of the command grammar into a
    * syntax error). Honest residue: statements spread over lines need explicit
    * `;` separators after flattening — a missing one is an honest compiler
    * syntax error, never silent. Triple-quoted literals in a type query are
    * unaffected in practice: the query never runs, only the type is printed.
    */
  def typeQuerySource(buffer: String): String =
    buffer.replace('\n', ' ').trim

end Commands

/** RAI-110 (ADR-028 §3): the plan-approval command surface of the tab shell.
  * Commands is the source of truth ([[Commands.planCommands]]); the shell
  * interceptor (PlanPanel.commandTerminal) routes exactly these names to the
  * plan session — the frozen raider.ui CommandRouter never sees them.
  */
enum PlanCommand:
  case Approve
  case Reject

  /** The exact surface name (also the registry entry). */
  def name: String = this match
    case Approve => ":approve"
    case Reject  => ":reject"

/** Result of matching one raw input line against the plan-command registry
  * (total, pure; RAI-110). `NotPlan` lines pass through to the frozen router
  * untouched; `BadUsage` is a typed usage refusal (the line is consumed); a
  * `Parsed` command drives the PlanSession edge.
  */
enum PlanCommandMatch:
  case NotPlan
  case Parsed(command: PlanCommand)
  case BadUsage(command: PlanCommand)

object PlanCommands:

  /** Pure matcher over the [[Commands.planCommands]] registry: the FIRST
    * whitespace token decides (case-insensitive, like Commands.handle); a plan
    * command takes NO arguments (anything after it is a typed usage error); a
    * non-plan token (including `:approvex`) is `NotPlan` — never a partial
    * match.
    */
  def parse(line: String): PlanCommandMatch =
    val split = line.trim.split("\\s+", 2)
    val token = split(0).toLowerCase
    val rest = if split.length > 1 then split(1).trim else ""
    token match
      case ":approve" => decide(PlanCommand.Approve, rest)
      case ":reject"  => decide(PlanCommand.Reject, rest)
      case _          => PlanCommandMatch.NotPlan

  private def decide(cmd: PlanCommand, rest: String): PlanCommandMatch =
    if rest.isEmpty then PlanCommandMatch.Parsed(cmd)
    else PlanCommandMatch.BadUsage(cmd)

end PlanCommands
