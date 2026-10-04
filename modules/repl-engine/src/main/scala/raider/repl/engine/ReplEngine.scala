package raider.repl.engine

import zio.{UIO, ZIO}

/** Result of evaluating one source submission in the REPL engine. Errors are
  * values here (the engine never fails its ZIO channel): a compile error must
  * keep the session state, a runtime failure must not kill the console loop.
  */
enum EvalResult:
  case Value(repr: String)
  case CompileError(msg: String)
  case RuntimeFailure(msg: String)

/** Frozen contract (fast-REPL slice, phase 0). The engine wraps ONE in-process
  * scala3-repl instance: val/def/case class bindings, multiline submissions and
  * imports live across eval calls; a failed compile preserves prior bindings;
  * reset() clears the session state.
  *
  * Side effects (compiler work, output capture) happen strictly inside ZIO
  * descriptions; construction dispatches nothing.
  */
trait ReplEngine:
  def eval(source: String): ZIO[Any, Nothing, EvalResult]
  def completions(line: String): UIO[List[String]]
  def reset(): UIO[Unit]

object ReplEngine:

  /** Builds the real in-process engine over scala3-repl 3.9.0 (ReplDriver).
    * Driver/compiler setup is a side effect, so it happens inside the returned
    * effect, never at the call site; signatures of this contract stay frozen. */
  def make(): UIO[ReplEngine] = ZIO.succeed(new DottyReplEngine)
end ReplEngine
