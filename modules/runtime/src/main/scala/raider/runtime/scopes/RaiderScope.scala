package raider.runtime.scopes

import raider.core.*
import raider.runtime.jobs.{JobHandle, JobManager, JobStatus}
import zio.{Duration, Exit as ZExit, UIO, ZIO}
import zio.stm.{TMap, TRef}

/** Truthful evidence of a scope close (SCOPE-02). */
final case class ScopeCloseReport(
    cancelledChildren: List[raider.core.JobId],
    uncertainChildren: List[raider.core.JobId],
    finalizersRun: Int,
    finalizerFailures: List[String],
    finalizersInterrupted: Boolean
):

  def status: ScopeCloseStatus =
    if finalizersInterrupted then ScopeCloseStatus.Interrupted
    else if uncertainChildren.nonEmpty || finalizerFailures.nonEmpty
    then ScopeCloseStatus.Uncertain
    else ScopeCloseStatus.Closed

enum ScopeCloseStatus:
  case Closed, Uncertain, Interrupted

/** Structured scopes and subtree cancellation (RAI-008, runtime-contracts §5).
  *
  * A scope owns forked children and finalizers. Scope exit (body success, body
  * failure or interruption) cancels unjoined children, waits for their terminal
  * outcome within a bounded grace, then runs finalizers in reverse order.
  *
  * Evidence rules (SCOPE-02): a child not terminal within the grace is reported
  * Uncertain — never a fake Cancelled; a failed/interrupted finalizer is
  * reported and the scope outcome reflects it. No new forks once closing
  * started.
  */
final class RaiderScope private[runtime] (
    val name: String,
    manager: JobManager,
    children: TMap[raider.core.JobId, JobHandle[?]],
    finalizers: TRef[List[UIO[Unit]]],
    closing: TRef[Boolean]
):

  /** Fork a child job into this scope. Launch+registration is one
    * uninterruptible step: there is no window where the child runs but the
    * scope does not know about it (SCOPE-03 no-orphan). Refuses new forks once
    * closing.
    */
  def fork[A](
      label: String,
      task: Task[A]
  ): ZIO[Any, RaiderError, JobHandle[A]] =
    ZIO.uninterruptible(
      for
        closed <- closing.get.commit
        _ <- ZIO.when(closed)(
          ZIO.fail(
            RaiderError.Cancelled(
              s"scope '$name' is closing: new forks refused"
            )
          )
        )
        handle <- manager.start(RootId(s"scope:$name"), label, task)
        _ <- children.put(handle.id, handle).commit
      yield handle
    )

  /** Await a scoped child (idempotent, typed). */
  def join[A](child: JobHandle[A]): ZIO[Any, RaiderError, A] =
    manager.await(child)

  /** Register a finalizer (runs LIFO at close; cancellation-safe add). */
  def addFinalizer(finalizer: UIO[Unit]): UIO[Unit] =
    finalizers.update(_ :+ finalizer).commit.unit

  /** Close: cancel unjoined children with bounded grace, then run finalizers
    * LIFO. Every child either terminates (terminal snapshot recorded) or is
    * reported uncertain; nothing is silently dropped.
    */
  def close(grace: Duration): UIO[ScopeCloseReport] =
    for
      _ <- closing.set(true).commit
      kids <- children.toList.commit.map(_.map(_._2))
      cancels <- ZIO.foreach(kids) { child =>
        // the WHOLE cancel+settle is bounded by the grace: a child
        // stuck in an uninterruptible region cannot hold the scope
        // hostage; it is reported uncertain instead (SCOPE-02)
        (manager.cancelAny(child) *> manager.awaitSettled(child))
          .timeout(grace)
          .flatMap { settled =>
            manager.snapshotAny(child).map { snap =>
              if settled.isDefined || JobStatus.terminal(snap.status) then
                (Right(snap.id): Either[raider.core.JobId, raider.core.JobId])
              else (Left(snap.id): Either[raider.core.JobId, raider.core.JobId])
            }
          }
      }
      fins <- finalizers.getAndSet(Nil).commit // take ownership atomically
      finRes <- ZIO.foreach(fins.reverse) { f =>
        f.exit.map(exit =>
          exit match
            case ZExit.Failure(cause) if cause.isInterrupted => (false, None)
            case ZExit.Failure(cause) =>
              (false, Some(cause.prettyPrint.take(120)))
            case ZExit.Success(_) => (true, None)
        )
      }
    yield ScopeCloseReport(
      cancelledChildren = cancels.collect { case Right(id) => id },
      uncertainChildren = cancels.collect { case Left(id) => id },
      finalizersRun = finRes.count(_._1),
      finalizerFailures = finRes.collect { case (false, Some(msg)) => msg },
      finalizersInterrupted = finRes.exists(r => !r._1 && r._2.isEmpty)
    )

end RaiderScope

object RaiderScope:

  /** Run `body` with a fresh scope; on ANY exit (success/failure/interruption)
    * the scope closes with the given grace (mandatory finalizers). The body's
    * domain failure is preserved; cleanup evidence turns a successful body into
    * a typed Uncertain/Interrupted failure rather than fake success.
    */
  def scoped[A](name: String, manager: JobManager, grace: Duration)(
      body: RaiderScope => ZIO[Any, RaiderError, A]
  ): ZIO[Any, RaiderError, A] =
    for
      scope <- make(name, manager)
      exit <- body(scope).exit
      report <- scope.close(grace)
      result <- exit match
        case ZExit.Failure(cause) => ZIO.failCause(cause) // body failure wins
        case ZExit.Success(value) =>
          report.status match
            case ScopeCloseStatus.Closed => ZIO.succeed(value)
            case ScopeCloseStatus.Uncertain =>
              ZIO.fail(
                RaiderError.ToolOutcomeUncertain(
                  s"scope '$name' cleanup incomplete: uncertain=" +
                    report.uncertainChildren.map(_.value).mkString(",") +
                    " finalizerFailures=" + report.finalizerFailures.size
                )
              )
            case ScopeCloseStatus.Interrupted =>
              ZIO.fail(
                RaiderError.Cancelled(s"scope '$name' cleanup was interrupted")
              )
    yield result

  def make(name: String, manager: JobManager): UIO[RaiderScope] =
    for
      children <- TMap.empty[raider.core.JobId, JobHandle[?]].commit
      fins <- TRef.make(List.empty[UIO[Unit]]).commit
      closing <- TRef.make(false).commit
    yield RaiderScope(name, manager, children, fins, closing)
