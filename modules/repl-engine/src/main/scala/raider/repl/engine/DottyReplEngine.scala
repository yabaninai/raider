package raider.repl.engine

import dotty.tools.repl.{ReplDriver, State}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal
import zio.{UIO, ZIO}

/** Real in-process engine over dotty.tools.repl.ReplDriver (scala3-repl 3.9.0).
  *
  * One persistent driver owns the session: eval() feeds the source to it and
  * reassigns the returned immutable State, so val/def/case-class bindings and
  * imports live across calls; a rejected compile leaves the prior State in
  * place. Driver output is captured through a PrintStream over an in-memory
  * buffer and classified into the frozen EvalResult cases.
  *
 * Classification is an honest heuristic, not a lexer: dotty prints
 * `-- [E###] ... Error:` diagnostic headers for compile failures and a bare
 * "<qualified class>: message" first line for a submission that ended in a
  * caught exception. A submission that succeeds but merely *renders* such a
  * line (e.g. printing an exception's toString) would be misclassified as
  * RuntimeFailure; that trade-off is accepted for this slice.
  *
  * The engine is a single mutable session (a plain var State, no locking):
  * it is meant to be owned by one fiber, not shared concurrently. NonFatal
  * crashes inside the driver (compiler internal errors) are mapped to
  * RuntimeFailure so the engine keeps its never-failing channel; Fatal errors
  * propagate.
  */
final class DottyReplEngine extends ReplEngine:

  private val buffer = new ByteArrayOutputStream
  private val out = new PrintStream(buffer, true, StandardCharsets.UTF_8)
  private var driver = new ReplDriver(
    Array("-usejavacp", "-color:never"),
    out,
    Some(getClass.getClassLoader)
  )

  private var state: State = driver.initialState

  def eval(source: String): ZIO[Any, Nothing, EvalResult] = ZIO.succeed {
    buffer.reset()
    try
      state = driver.run(source)(using state)
      out.flush()
      classify(buffer.toString(StandardCharsets.UTF_8))
    catch
      case NonFatal(e) =>
        EvalResult.RuntimeFailure(s"${e.getClass.getName}: ${e.getMessage}")
  }

  // Deliberate cut of this slice, not a stub to grow silently: the driver does
  // expose completions(offset, line, state), but the frozen completions(line)
  // contract needs its own offset/state decisions — deferred to a later slice.
  def completions(line: String): UIO[List[String]] = ZIO.succeed(Nil)

  def reset(): UIO[Unit] = ZIO.succeed {
    // A full driver rebuild, NOT state = initialState: the REPL classloader
    // keeps every compiled rs$line$N class, and a state-only reset restarts
    // the line counter — replayed prelude lines would collide with stale
    // class definitions (integration finding 2026-10-03). A fresh driver
    // brings a fresh classloader namespace with it.
    driver = new ReplDriver(
      Array("-usejavacp", "-color:never"),
      out,
      Some(getClass.getClassLoader)
    )
    state = driver.initialState
  }

  private def classify(text: String): EvalResult =
    if DiagnosticHeader.findFirstIn(text).isDefined then
      EvalResult.CompileError(text)
    else
      firstNonBlankLine(text) match
        case Some(line) if ExceptionPrefix.findFirstIn(line).isDefined =>
          EvalResult.RuntimeFailure(text)
        case _ =>
          EvalResult.Value(text)

  private def firstNonBlankLine(text: String): Option[String] =
    text.linesIterator.find(_.trim.nonEmpty).map(_.trim)

  /** 3.9 diagnostic headers look like `-- [E007] Type Mismatch Error: ---`. */
  private val DiagnosticHeader = raw"(?m)^-- \[E\d+\][^\n]*Error".r

  private val ExceptionPrefix = """^[\w$.]+(?:Exception|Error): """.r

end DottyReplEngine
