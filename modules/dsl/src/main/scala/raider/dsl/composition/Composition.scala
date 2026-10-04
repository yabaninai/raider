package raider.dsl.composition

import raider.core.{Program, RaiderError, Task}
import zio.{Cause, Exit as ZExit, ZIO}

/** Explicit outcome for batchCollect: domain failures become values, while
  * external interruption and defects are NEVER converted (runtime-contracts §5:
  * batchCollect transforms domain failures explicitly, swallowing nothing).
  */
enum BatchOutcome[+A]:
  case Succeeded(value: A)
  case Failed(error: RaiderError)

/** Typed composition (RAI-017, runtime-contracts §3, product-repl §4–6).
  *
  * One effect interpreter executes everything (Task.zio is the only boundary);
  * `all(Program, Program)` builds a Program with a tuple output, `all(Task,
  * Task)` builds a Task — no second execution semantics. Fail-fast: the first
  * failure interrupts the siblings (COMP-02). Construction is lazy — building
  * any of these dispatches nothing (API-03).
  */
object all:

  /** Two Tasks in parallel under one interpreter; fail-fast cancels siblings.
    */
  def apply[A, B](first: Task[A], second: Task[B]): Task[(A, B)] =
    Task(Task.zio(first).zipPar(Task.zio(second)))

  /** Heterogeneous arity-2 composition: Program[I,(A,B)] with tuple output. */
  def apply[I, A, B](
      first: Program[I, A],
      second: Program[I, B]
  ): Program[I, (A, B)] =
    new Program[I, (A, B)]:
      def apply(input: I): Task[(A, B)] = all(first(input), second(input))

object batch:

  /** Ordered bounded batch: results keep INPUT order regardless of completion
    * order; `parallelism` bounds concurrent dispatch; an empty batch dispatches
    * nothing (COMP-01). `parallelism < 1` is a typed error, not a silent clamp.
    */
  def apply[A, R](inputs: List[A], parallelism: Int)(
      run: A => Task[R]
  ): Task[List[R]] =
    Task(
      inputs match
        case Nil => ZIO.succeed(Nil)
        case nonEmpty =>
          validParallelism(parallelism) *>
            ZIO
              .foreachPar(nonEmpty)(a => Task.zio(run(a)))
              .withParallelism(parallelism)
    )

  /** batchCollect: domain failures become explicit Failed outcomes; external
    * interruption and defects are re-raised (never swallowed into results).
    */
  def collect[A, R](inputs: List[A], parallelism: Int)(
      run: A => Task[R]
  ): Task[List[BatchOutcome[R]]] =
    Task(
      inputs match
        case Nil => ZIO.succeed(Nil)
        case nonEmpty =>
          validParallelism(parallelism) *>
            ZIO
              .foreachPar(nonEmpty)(a => Task.zio(run(a)).exit.map(toOutcome))
              .withParallelism(parallelism)
              .flatMap { outcomes =>
                val reRaise =
                  outcomes.collectFirst { case Left(cause) => cause }
                reRaise match
                  case Some(cause) => ZIO.failCause(cause)
                  case None =>
                    ZIO.succeed(outcomes.map {
                      case Right(outcome) => outcome
                      case Left(_)        => unreachable
                    })
              }
    )

  private def validParallelism(n: Int): ZIO[Any, RaiderError, Unit] =
    if n < 1 then
      ZIO.fail(RaiderError.InputValidation(s"parallelism must be >= 1, got $n"))
    else ZIO.unit

  private def toOutcome[R](
      exit: ZExit[RaiderError, R]
  ): Either[Cause[RaiderError], BatchOutcome[R]] =
    exit match
      case ZExit.Success(value) => Right(BatchOutcome.Succeeded(value))
      case ZExit.Failure(cause) =>
        // pure typed domain failure -> explicit Failed outcome; anything else
        // (external interruption, defects) is re-raised unchanged (§5)
        if cause.isInterruptedOnly || cause.defects.nonEmpty
          || cause.failures.isEmpty
        then Left(cause)
        else Right(BatchOutcome.Failed(cause.failures.head))

  private def unreachable: BatchOutcome[Nothing] =
    throw new IllegalStateException("unreachable: Right after reRaise filter")

end batch
