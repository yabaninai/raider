package raider.repl

import raider.repl.engine.{EvalResult, ReplEngine}
import org.jline.reader.{EndOfFileException, LineReader, LineReaderBuilder,
                         UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}
import zio.Unsafe

/** W10 REPL entrypoint (fast-path slice).
  *
  * One process, one ZIO runtime (zio.Runtime.default — the spike-proven single
  * interpreter), one ReplEngine over the real Scala 3.9.0 compiler.
  *
  * ARCHITECTURE (integration finding 2026-10-03): the in-process scala3-repl
  * classloader loads classpath classes SELF-FIRST, so facade statics exist
  * twice and must never be shared between this console world and the
  * interpreter world. The session is therefore CONSTRUCTED INSIDE the
  * interpreter by the prelude below; the console only feeds lines and prints
  * engine output. Live providers are NOT wired in this slice and the banner
  * says so (fixture/mock ScriptedModelBackend).
  *
  * Multiline input accumulates until braces/parens balance and the line does
  * not end in a continuation token; a blank line always submits the pending
  * buffer. Non-TTY stdin runs through a plain pipe reader (the Homebrew sbt
  * launcher redirects stdin from /dev/null on --batch, so piped smoke uses the
  * direct java classpath path — scripts/quality/repl_smoke.sh).
  */
object Main:

  private val Prompt = "raider> "
  private val Continuation = "     | "

  private val attemptId = "fixture-attempt-1"

  /** Prelude replayed through the engine itself (spike pattern): the scripted
    * backend, the session (installed into ReplCommands for the console
    * commands) and the facade `given` + ready agent handles all become
    * ordinary interpreter-world bindings. */
  /** If RAIDER_PROVIDER=openai, wire a LIVE backend + coding tools.
    * Otherwise fall back to the scripted fixture backend. */
  private def preludeSources: List[String] =
    val provider = Option(System.getenv("RAIDER_PROVIDER")).getOrElse("mock")
    val baseUrl  = Option(System.getenv("RAIDER_BASE_URL"))
                     .getOrElse("http://127.0.0.1:8081/v1")
    val model    = Option(System.getenv("RAIDER_MODEL")).getOrElse("local")

    if provider == "openai" then
      List(
        "import raider.repl.facade.*",
        "import raider.runtime.budget.BudgetLimits",
        """val __backend = raider.repl.MainBridge.backend""",
        """val __tools = raider.repl.MainBridge.tools""",
        """val __session = zio.Unsafe.unsafe { implicit u =>
           zio.Runtime.default.unsafe.run(
             ReplSession.make(__backend,
               BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48),
               __tools))
             .getOrThrow() }""",
        "ReplCommands.install(__session)",
        "given ReplSession = __session",
        """val scout = AgentRef("scout")""",
        """val worker = AgentRef("worker")""",
        """val coder = AgentRef("coder")"""
      )
    else
      List(
        "import raider.repl.facade.*",
        "import raider.runtime.budget.BudgetLimits",
        "import raider.testkit.ScriptedModelBackend",
        s"val __backend = zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(ScriptedModelBackend(raider.core.ModelEvent.Started(raider.core.AttemptId(\"$attemptId\")), raider.core.ModelEvent.TextDelta(raider.core.AttemptId(\"$attemptId\"), \"[scripted-fixture] mock answer\"), raider.core.ModelEvent.TextDelta(raider.core.AttemptId(\"$attemptId\"), \" (ScriptedModelBackend; no live network in this slice)\"), raider.core.ModelEvent.Finished(raider.core.AttemptId(\"$attemptId\"), \"stop\"))).getOrThrow() }",
        "val __session = zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(ReplSession.make(__backend, BudgetLimits.make())).getOrThrow() }",
        "ReplCommands.install(__session)",
        "given ReplSession = __session",
        "val scout = AgentRef(\"scout\")",
        "val worker = AgentRef(\"worker\")"
      )

  def main(args: Array[String]): Unit =
    // Setup live backend BEFORE the prelude (console world, full classpath)
    MainBridge.setupLive(
      Option(System.getenv("RAIDER_PROVIDER")).getOrElse("mock"),
      Option(System.getenv("RAIDER_BASE_URL"))
        .getOrElse("http://127.0.0.1:8081/v1"))
    val engine =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(ReplEngine.make()).getOrThrow()
      }
    // the banner's backend line needs the interpreter-world session, so the
    // prelude runs first
    runPrelude(engine)
    banner(engine)
    loop(engine)

  private def banner(engine: ReplEngine): Unit =
    println("Raider REPL (W10 fast-path slice)")
    println("  engine : scala3-repl 3.9.0 (in-process compiler)")
    printCaptured(engine, "raider.repl.facade.ReplCommands.printBackendLine()")
    println("""  try    : scout.ask("hello")   |   val j = worker.start("bg"); j.await()""")
    println("  cmds   : :help :jobs :cancel <id> :reset :quit")

  private def runPrelude(engine: ReplEngine): Unit =
    preludeSources.foreach(src => submit(src, engine, silent = true))

  private def loop(engine: ReplEngine): Unit =
    if System.console() == null then pipeLoop(engine)
    else ttyLoop(engine)

  /** Non-TTY path (piped smoke): plain stdin reader, no terminal detection
    * games. Same line semantics as the interactive loop: commands, multiline
    * buffer, blank-line submit. */
  private def pipeLoop(engine: ReplEngine): Unit =
    val in = java.io.BufferedReader(
      java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))
    val pending = new StringBuilder
    var running = true
    while running do
      print(if pending.isEmpty then Prompt else Continuation)
      System.out.flush()
      val line = in.readLine()
      if line == null then
        driveEof(pending)
        running = false
      else running = drive(engine, pending, line)
    shutdown(engine)

  /** Interactive TTY path: JLine line editor; Ctrl+C clears the pending buffer
    * and redraws the prompt (never kills the loop). */
  private def ttyLoop(engine: ReplEngine): Unit =
    val reader = LineReaderBuilder.builder().terminal(makeTerminal()).build()
    // JLine 4 does not bind Ctrl+C by default: without this the interrupt
    // byte is swallowed and the pending multiline buffer survives ^C.
    // "abort" is readLine's special Reference that throws UserInterruptException.
    val mainKeyMap = reader.getKeyMaps.get("main")
    if mainKeyMap != null then
      mainKeyMap.bind(new org.jline.reader.Reference("abort"), "\u0003")
    val pending = new StringBuilder
    var running = true
    while running do
      val line =
        try reader.readLine(if pending.isEmpty then Prompt else Continuation)
        catch
          case _: EndOfFileException => null
          case _: UserInterruptException =>
            if pending.nonEmpty then println("(pending input cleared)")
            pending.clear()
            ""
      if line == null then
        driveEof(pending)
        running = false
      else running = drive(engine, pending, line)
    shutdown(engine)

  /** One line of console input against the shared pending buffer; false = quit. */
  private def drive(engine: ReplEngine, pending: StringBuilder, line: String): Boolean =
    line match
      case "" =>
        if pending.nonEmpty then
          val src = pending.toString
          pending.clear()
          submit(src, engine)
        true
      case cmd if pending.isEmpty && cmd.trim.startsWith(":") =>
        Commands.handle(cmd, engine, () => runPrelude(engine))
      case input =>
        pending.append(input).append('\n')
        if complete(pending.toString) then
          val src = pending.toString
          pending.clear()
          submit(src, engine)
        true

  private def driveEof(pending: StringBuilder): Unit =
    if pending.nonEmpty then
      println("(EOF with pending input; buffer discarded)")

  private def makeTerminal(): Terminal =
    TerminalBuilder.terminal()

  /** Continuation heuristic (accepted W10 trade-off): char-count balance of
    * (), {} and [] plus no trailing continuation token. A wrong guess is never
    * sticky — a compile error preserves state, and a blank line always submits
    * the pending buffer as-is. */
  private def complete(src: String): Boolean =
    val trimmed = src.trim
    trimmed.nonEmpty &&
      Seq(('(', ')'), ('{', '}'), ('[', ']')).forall((o, c) =>
        src.count(_ == o) == src.count(_ == c)) &&
      !continuationEndings.exists(trimmed.endsWith)

  private val continuationEndings = Set(
    "=", "=>", ",", ".", "+", "-", "*", "/", "%", "&&", "||", "with", ":")

  private def submit(src: String, engine: ReplEngine, silent: Boolean = false): Unit =
    val result =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(engine.eval(src)).getOrThrow()
      }
    if !silent then
      result match
        case EvalResult.Value(repr)         => println(repr.trim)
        case EvalResult.CompileError(msg)   => println(msg.trim)
        case EvalResult.RuntimeFailure(msg) => println(s"runtime failure: ${msg.trim}")
    ()

  private def printCaptured(engine: ReplEngine, expr: String): Unit =
    val result =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(engine.eval(expr)).getOrThrow()
      }
    result match
      case EvalResult.Value(repr)        =>
        val text = repr.trim
        if text.nonEmpty then println(text)
      case EvalResult.CompileError(msg)  =>
        println(s"prelude broken? ${msg.trim.linesIterator.take(3).mkString(" | ")}")
      case EvalResult.RuntimeFailure(m)  =>
        println(s"command failed: $m")

  /** Honest shutdown: the interpreter world reports active jobs; they are
    * daemon fibers of this process and there is no durable resume here. */
  private def shutdown(engine: ReplEngine): Unit =
    printCaptured(engine, "raider.repl.facade.ReplCommands.printShutdownWarning()")
    println("bye")
    // deterministic exit: the JVM must not linger after the console loop ends
    Runtime.getRuntime.exit(0)
end Main
