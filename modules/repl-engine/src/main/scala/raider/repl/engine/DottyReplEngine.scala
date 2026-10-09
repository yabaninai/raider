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
  * The engine is a single mutable session (a plain var State, no locking): it
  * is meant to be owned by one fiber, not shared concurrently. NonFatal crashes
  * inside the driver (compiler internal errors) are mapped to RuntimeFailure so
  * the engine keeps its never-failing channel; Fatal errors propagate.
  */
final class DottyReplEngine extends ReplEngine:

  /** UI-bound completion contract: at most this many sorted candidates. */
  private val MaxCompletions = 50

  /** Cache entries per state before the coarse drop-all (bounded by design;
    * typing prefixes grows at most line-length many entries per state).
    */
  private val MaxCacheEntries = 32

  private val buffer = new ByteArrayOutputStream
  private val out = new PrintStream(buffer, true, StandardCharsets.UTF_8)

  private var driver = new CompletionDriver(
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

  /** Real completion (RAI-068) through the compiler itself: dotty
    * ReplDriver.completions(cursor, line)(using state) — scala3-repl 3.9.0
    * parses `line`, typechecks the visible scope of `state` and returns
    * Completion(label, description, symbols) candidates for val/def/method
    * member and bare-prefix queries. The frozen completions(line) contract pins
    * the cursor to the END of the line: the console only ever asks on Tab,
    * where the caret sits at the end of the typed prefix.
    *
    * Result discipline: labels are deduplicated, sorted lexicographically and
    * capped at MaxCompletions so the UI contract stays bounded; any compiler
    * crash degrades to Nil — completion must never be what kills a session
    * (COMP-02: invalid prefix -> empty list, no exception).
    *
    * Threading model (RAI-068 doc obligation): the console REPL is synchronous
    * — JLine invokes the Completer INSIDE readLine on the console thread, and
    * eval() runs on that same thread only after readLine returns. Completion
    * and eval are therefore serialized by control flow, never concurrent; the
    * cache below still synchronizes access as cheap insurance rather than
    * silently assuming the model.
    */
  def completions(line: String): UIO[List[String]] = ZIO.succeed {
    try
      completionCache.get(state, line) match
        case Some(hit) => hit
        case None =>
          val labels = driver
            .completeLabels(line.length, line, state)
            .distinct
            .sorted
            .take(MaxCompletions)
          completionCache.put(state, line, labels)
          labels
    catch case NonFatal(_) => Nil
  }

  def reset(): UIO[Unit] = ZIO.succeed {
    // A full driver rebuild, NOT state = initialState: the REPL classloader
    // keeps every compiled rs$line$N class, and a state-only reset restarts
    // the line counter — replayed prelude lines would collide with stale
    // class definitions (integration finding 2026-10-03). A fresh driver
    // brings a fresh classloader namespace with it.
    driver = new CompletionDriver(
      Array("-usejavacp", "-color:never"),
      out,
      Some(getClass.getClassLoader)
    )
    state = driver.initialState
    completionCache.invalidate()
  }

  private val completionCache = new StateKeyedCompletionCache(
    MaxCacheEntries
  )

  /** Bounded memo keyed by (state identity, prefix). State is an immutable
    * snapshot: every eval/reset produces a new instance, so REFERENCE identity
    * (eq) is the honest key — a changed state invalidates by failing the eq
    * check, no explicit invalidation needed (reset() still invalidates
    * defensively). Hard cap of MaxCacheEntries prefixes; overflow drops the
    * whole map (simplest correct bounded policy — completion stays O(1) per
    * repeat query, never grows with typing).
    */
  private final class StateKeyedCompletionCache(maxEntries: Int):
    private var cachedState: Option[State] = None
    private var entries: Map[String, List[String]] = Map.empty

    def get(current: State, prefix: String): Option[List[String]] =
      synchronized:
        if cachedState.exists(_ eq current) then entries.get(prefix) else None

    def put(current: State, prefix: String, value: List[String]): Unit =
      synchronized:
        if !cachedState.exists(_ eq current) then
          cachedState = Some(current)
          entries = Map.empty
        if entries.size >= maxEntries then entries = Map.empty
        entries = entries.updated(prefix, value)

    def invalidate(): Unit = synchronized:
      cachedState = None
      entries = Map.empty

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

/** API-visibility note (RAI-068, scala3-repl 3.9.0): the card sketch named
  * ReplDriver.completion(cursor, state); the actual 3.9.0 entrypoint is
  * `completions(cursor: Int, expr: String, state0: State): List[Completion]`
  * (plain three-argument method, not a using-clause) and it is PROTECTED —
  * javap shows public bytecode, but the Scala compiler enforces member
  * protection outside the dotty.tools.repl package. The accessible equivalent
  * is this trivial subclass bridge: same compiler code path, only visibility
  * handled, labels projected so dotty types stay internal to the engine.
  */
private[engine] final class CompletionDriver(
    settings: Array[String],
    out: PrintStream,
    loader: Option[ClassLoader]
) extends ReplDriver(settings, out, loader):

  def completeLabels(cursor: Int, line: String, state: State): List[String] =
    completions(cursor, line, state).map(_.label)

end CompletionDriver
