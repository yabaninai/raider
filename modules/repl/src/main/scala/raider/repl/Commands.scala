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
      |  :reset           clear interpreter bindings; facade prelude is re-imported
      |  :quit            exit the REPL (active jobs are reported, then dropped)
      |Everything else is evaluated as Scala by the in-process compiler.
      |Multiline input: keep typing; a blank line submits the pending buffer.
      |Not in this slice: :type, job steering (send), completion, live providers.""".stripMargin

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
        println(":type is not in the W10 slice (tracked as a board obligation)")
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

end Commands
