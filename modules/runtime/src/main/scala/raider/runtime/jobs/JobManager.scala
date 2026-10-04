package raider.runtime.jobs

import raider.core.*
import zio.{Exit as ZExit, Fiber, Promise, UIO, ZIO}
import zio.stm.{TMap, TPromise, TRef, STM, ZSTM}

/** Job registry and atomic lifecycle (RAI-007).
  *
  * One JobManager owns every job fiber. Terminal completion is a single STM
  * transaction (state transition + typed result publication). When a final
  * completion races an acknowledged cancellation, cancel wins the status and
  * waiters receive a typed Cancelled outcome; the completion is counted as a
  * late observation and never overwrites a published outcome. `await` is
  * idempotent, supports any number of waiters without re-running the task, and
  * always resolves once the job reaches a terminal status (JOB-03 no lost waiter).
  *
  * Admission/permits/budget are NOT here (RAI-009); this card is the registry
  * and lifecycle only. Registration happens inside an uninterruptible section:
  * there is no window where the fiber runs before the registry knows about it.
  */
/** Internal registry record; one per job. */
private[runtime] final class Entry(
  val id: JobId,
  val root: RootId,
  val label: String,
  val order: Long,
  val status: TRef[JobStatus],
  val late: TRef[Int]
):
  def snapshot: UIO[JobSnapshot] =
    (status.get <*> late.get).commit.map { case (st, l) =>
      JobSnapshot(id, root, label, st, l) }

final class JobManager private (
  entries: TMap[JobId, Entry],
  seq: TRef[Long],
  maxRetained: Int
):
  private val counter = new java.util.concurrent.atomic.AtomicLong(0)

  private def makeEntry(root: RootId, label: String): UIO[Entry] =
    for
      statusRef <- TRef.make[JobStatus](JobStatus.Queued).commit
      lateRef   <- TRef.make[Int](0).commit
      order     <- seq.modify(n => (n + 1, n + 1)).commit
    yield Entry(JobId(s"j_${counter.incrementAndGet()}"), root, label,
                order, statusRef, lateRef)

  private def transitionSTM(entry: Entry, to: JobStatus): STM[JobsError, Unit] =
    for
      from <- entry.status.get
      _    <- if JobStatus.canTransition(from, to) then STM.unit
              else STM.fail(JobsError.InvalidTransition(entry.id, from, to))
      _    <- entry.status.set(to)
    yield ()

  /** Starts `task` as a job under `root`; registration is cancellation-safe. */
  def start[A](root: RootId, label: String, task: Task[A]): UIO[JobHandle[A]] =
    ZIO.uninterruptibleMask { restore =>
      for
        entry  <- makeEntry(root, label)
        _      <- entries.put(entry.id, entry).commit
        _      <- transitionSTM(entry, JobStatus.Running).commit.orDieWith(e =>
                   new RuntimeException(e.toString)) // impossible: entry is fresh Queued
        result <- Promise.make[Nothing, Either[RaiderError, A]]
        // The job fiber is daemon-owned by the JobManager/watcher, NOT by the
        // caller's ZIO scope: job lifetime is governed by cancel/settle here.
        // `.interruptible` is mandatory: forkDaemon inside (possibly nested,
        // e.g. scope.fork) uninterruptible masks would otherwise give the child
        // a non-interruptible start status and cancel() could never settle it.
        fiber  <- restore(
          Task.zio(task).interruptible.forkDaemon
            // F-5: if THIS registration is interrupted inside the fork window,
            // the entry must not remain a ghost Running record — settle it as
            // Cancelled and publish the typed outcome (edge check bypassed
            // deliberately: this is the registration-abort path).
            .onInterrupt(abortGhost(entry, result))
        )
        handle  = JobHandle(entry, fiber, result)
        _ <- (fiber.await.flatMap { exit =>
               settle(entry, result, exit)
             }.catchAllCause(cause =>
               // watcher must never leave waiters hanging: a defect here still
               // publishes a Failed outcome (defects are not silent success)
               result.succeed(Left(RaiderError.ToolFailed(
                 s"watcher defect for ${entry.id.value}: ${cause.prettyPrint.take(200)}"
               ))).unit)
           ).forkDaemon
      yield handle
    }

  private def settle[A](entry: Entry, result: Promise[Nothing, Either[RaiderError, A]],
                        exit: ZExit[RaiderError, A]): UIO[Unit] =
    val (terminal, outcome) = exit match
      case ZExit.Success(value) => (JobStatus.Succeeded, Right(value))
      case ZExit.Failure(cause) =>
        if cause.isInterrupted then
          (JobStatus.Cancelled, Left(RaiderError.Cancelled(s"job ${entry.id.value} interrupted")))
        else
          cause.failures.headOption match
            case Some(err) => (JobStatus.Failed, Left(err))
            case None      =>
              (JobStatus.Failed, Left(RaiderError.ToolFailed(
                s"job ${entry.id.value} died: ${cause.prettyPrint.take(200)}")))
    // Single atomic settle; the transaction is TOTAL (never fails, so the
    // late counter cannot be rolled back). §4: cancellation racing a final
    // success resolves to exactly one winner inside this one transition —
    // if Cancelling was requested, cancel wins and the success is recorded
    // as a late observation.
    (for
      from  <- entry.status.get
      late0 <- entry.late.get
      res   <-
        if JobStatus.terminal(from) then
          // first publisher already won; record the late observation only
          entry.late.set(late0 + 1).as(Left(None): Either[Option[JobStatus], JobStatus])
        else if !JobStatus.canTransition(from, terminal) then
          // cancel wins the race (e.g. Cancelling committed while the task was
          // completing): terminal status Cancelled, waiters DO get a typed
          // outcome, the completion itself is the late observation
          entry.late.set(late0 + 1) *>
            entry.status.set(JobStatus.Cancelled)
              .as(Left(Some(JobStatus.Cancelled)): Either[Option[JobStatus], JobStatus])
        else
          entry.status.set(terminal).as(Right(terminal): Either[Option[JobStatus], JobStatus])
    yield res)
      .commit
      .flatMap {
        case Left(None) =>
          ZIO.unit // late observation recorded; outcome untouched
        case Left(Some(_)) =>
          // cancel-wins race (status set to Cancelled above); waiters get the
          // typed outcome — pattern kept total under -Werror
          result.succeed(Left(RaiderError.Cancelled(
            s"job ${entry.id.value} cancelled while completing"))).unit *>
            evictIfNeeded(entry)
        case Right(_) =>
          result.succeed(outcome).unit *> evictIfNeeded(entry)
      }

  private def evictIfNeeded(finished: Entry): UIO[Unit] =
    // bounded retention: when over capacity, evict the OLDEST TERMINAL entries;
    // live jobs are never evicted from the registry.
    (for
      all <- entries.toList
      _   <- STM.when(all.size > maxRetained) {
               val overflow = all.size - maxRetained
               for
                 statuses <- STM.foreach(all.map(_._2))(_.status.get)
                 evictable = all.map(_._2).zip(statuses)
                   .filter { case (e, st) =>
                     JobStatus.terminal(st) || e.id == finished.id
                   }
                   .map(_._1)
                   .sortBy(_.order) // oldest first
                   .take(overflow)
                 _ <- STM.foreachDiscard(evictable)(e => entries.delete(e.id))
               yield ()
             }
    yield ()).commit.unit

  /** Await terminal outcome; idempotent, multiple waiters, never re-runs. */
  def await[A](job: JobHandle[A]): ZIO[Any, RaiderError, A] =
    job.result.await.flatMap {
      case Right(v) => ZIO.succeed(v)
      case Left(e)  => ZIO.fail(e)
    }

  def snapshot[A](job: JobHandle[A]): UIO[JobSnapshot] = job.entry.snapshot

  /** Heterogeneous variants for scope/admin ownership (no typed result access). */
  def snapshotAny(job: JobHandle[?]): UIO[JobSnapshot] = job.entry.snapshot

  /** Waits until a child's outcome is published (any type); complements cancelAny. */
  def awaitSettled(job: JobHandle[?]): UIO[Unit] = job.result.await.unit

  /** Requests cancellation of an owned subtree fiber; false if already terminal. */
  def cancelAny(job: JobHandle[?]): UIO[Boolean] =
    transitionSTM(job.entry, JobStatus.Cancelling).commit.foldZIO(
      _ => ZIO.succeed(false),
      _ => job.fiber.interrupt.as(true)
    )

  def cancel[A](job: JobHandle[A]): UIO[Boolean] =
    transitionSTM(job.entry, JobStatus.Cancelling).commit.foldZIO(
      _ => ZIO.succeed(false),
      _ => job.fiber.interrupt.as(true)
    )

  /** Heterogeneous admin view (codecs-only boundary). */
  def adminView: UIO[List[JobSnapshot]] =
    entries.toList.commit.flatMap { list =>
      ZIO.foreach(list.map(_._2))(_.snapshot).map(_.sortBy(_.id.value))
    }

  def lookup(id: JobId): UIO[Option[JobSnapshot]] =
    entries.get(id).commit.flatMap {
      case Some(e) => e.snapshot.map(Some(_))
      case None    => ZIO.none
    }

  /** Test/admin hook for lifecycle-edge verification (JOB-02). */
  private[runtime] def debugTransition[A](job: JobHandle[A], to: JobStatus) =
    transitionSTM(job.entry, to).commit

  private def abortGhost[A](entry: Entry,
                            result: Promise[Nothing, Either[RaiderError, A]]): UIO[Unit] =
    entry.status.set(JobStatus.Cancelled).commit *>
      result.succeed(Left(RaiderError.Cancelled(
        s"job ${entry.id.value} registration interrupted"))).unit

  /** Typed admin lookup by id: unknown ids are a typed error (JOB-02). */
  def snapshotById(id: JobId): ZIO[Any, JobsError, JobSnapshot] =
    entries.get(id).commit.flatMap {
      case Some(e) => e.snapshot
      case None    => ZIO.fail(JobsError.UnknownJob(id))
    }

  /** Test hook (F-7): exercise the PRODUCTION settle path with a synthetic exit. */
  private[runtime] def settleForTest[A](job: JobHandle[A],
                                        exit: zio.Exit[RaiderError, A]): UIO[Unit] =
    settle(job.entry, job.result, exit)

  /** Test hook (JOB-02): attempt a second terminal settle; must be rejected as
    * a late observation without changing the outcome. */
  private[runtime] def settleTwiceForTest[A](job: JobHandle[A]): ZIO[Any, JobsError, Unit] =
    // total transaction mirroring settle's late path (no rollback of the counter)
    (for
      from  <- job.entry.status.get
      late0 <- job.entry.late.get
      res   <- if JobStatus.terminal(from)
               then job.entry.late.set(late0 + 1).as(Option.empty[JobStatus])
               else job.entry.status.set(JobStatus.Succeeded).as(Some(JobStatus.Succeeded))
    yield res).commit.flatMap {
      case Some(_) => ZIO.unit
      case None =>
        job.entry.status.get.commit.flatMap(st =>
          ZIO.fail(JobsError.AlreadyTerminal(job.entry.id, st)))
    }

  /** Test hook: raw entry access. */
  private[runtime] def entryOf[A](job: JobHandle[A]): Entry = job.entry

end JobManager

/** Typed handle: fiber + typed deferred result bound to one entry. */
final class JobHandle[A] private[jobs] (
  private[jobs] val entry: Entry,
  private[jobs] val fiber: Fiber.Runtime[RaiderError, A],
  private[jobs] val result: Promise[Nothing, Either[RaiderError, A]]
) extends Job[A]:
  def id: JobId = entry.id
  def root: RootId = entry.root

object JobManager:
  def make(maxRetained: Int = 256): UIO[JobManager] =
    for
      entries <- TMap.empty[JobId, Entry].commit
      seq     <- TRef.make[Long](0L).commit
    yield new JobManager(entries, seq, maxRetained)
