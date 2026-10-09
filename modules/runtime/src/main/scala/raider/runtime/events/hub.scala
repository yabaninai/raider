package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}
import zio.stm.{STM, TMap, TSemaphore, TRef}
import zio.stream.ZStream
import zio.{Duration, IO, Queue, UIO, ZIO}

/** Live in-process hub of the runtime→UI event seam (ADR-024 §2.2–§2.3, §5).
  *
  * Delivery model:
  *   - Every published envelope gets a per-job sequence `lastSeq + 1`, assigned
  *     atomically with the buffer offer under a per-job lock; sequences start
  *     at 1 and have no gaps, under any number of concurrent publishers of one
  *     job. A sequence is committed only when the event is actually accepted
  *     into its class buffer — a failed publish (backpressure timeout, dropped
  *     diagnostic) consumes no sequence, so delivered streams never gap.
  *   - Per-job UI state is maintained by folding every accepted envelope
  *     through [[SessionReducer.reduce]]; `snapshot`/`subscribe` read that
  *     state, so it stays exactly reconstructible from the delivered envelope
  *     stream (the stream is the source of truth, ADR-024 §2.2).
  *   - Publishers never await subscriber queues: backpressure is absorbed by
  *     the forwarder fibers, never by `publish` (invariant P1.C).
  *
  * There is intentionally no `stop()` in this slice: the hub is a
  * process-lifetime object (its forwarder fibers are daemons); teardown is
  * delegated to the enclosing application scope. This matches ADR-024 §5, whose
  * frozen `EventHub` surface has no lifecycle member.
  */
trait EventHub:

  /** Assign the next per-job sequence and route `payload` to its delivery
    * class. Reliable overflow surfaces as `EventError.Backpressure` after the
    * bounded wait; a malformed event (e.g. any event after Terminal) surfaces
    * as `EventError.Malformed` and enters no buffer.
    */
  def publish(
      jobId: JobId,
      rootId: RootId,
      sessionId: SessionId,
      payload: EventPayload
  ): IO[EventError, Unit]

  /** Loss-window-free subscription (ADR-024 §2.2): the subscriber queue is
    * registered and the snapshot read in one atomic transaction, then the
    * stream is filtered to envelopes with `seq > snapshot.lastSeq`, so every
    * event is either in the snapshot or in the stream — never both, never
    * neither. The returned stream must be run (or interrupted): interrupting it
    * unregisters the queue; never running it leaks one bounded queue.
    *
    * Arrival order at a subscriber is specified per delivery class only:
    * reliable and diagnostics envelopes of one job are relayed by two
    * independent forwarder fibers, so their relative arrival order is
    * unspecified. Consumers must reorder by `seq` before reducing.
    */
  def subscribe(
      jobId: JobId
  ): UIO[(SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])]

  /** Current folded state; `None` for a job that has published nothing. */
  def snapshot(jobId: JobId): UIO[Option[SessionSnapshot]]

  /** Count of diagnostics envelopes dropped on a full diagnostics buffer (drops
    * are counted, never silent — ADR-024 §2.3).
    */
  def droppedDiagnostics: UIO[Long]

object EventHub:

  /** Bounded wait for reliable capacity before `EventError.Backpressure`. */
  val reliablePublishTimeout: Duration = Duration.fromSeconds(5)

  /** Capacity of each subscriber queue (created per `subscribe` call). */
  val subscriberQueueCapacity: Int = 1024

  private val SchemaVersion = 1

  /** Build a hub with two independent bounded buffers (ADR-024 §2.3).
    *
    * Both capacities must be `> 0`; a non-positive value throws
    * [[IllegalArgumentException]] eagerly — this is a programming error, not a
    * runtime condition, so it does not use the (absent) error channel of `UIO`.
    * The returned hub has no `stop()` (process-lifetime daemons, see
    * [[EventHub]]).
    */
  def make(
      reliableCapacity: Int = 256,
      diagnosticsCapacity: Int = 1024
  ): UIO[EventHub] =
    if reliableCapacity <= 0 then
      throw new IllegalArgumentException(
        s"reliableCapacity must be > 0, got $reliableCapacity"
      )
    if diagnosticsCapacity <= 0 then
      throw new IllegalArgumentException(
        s"diagnosticsCapacity must be > 0, got $diagnosticsCapacity"
      )
    for
      reliable <- Queue.bounded[EventEnvelope](reliableCapacity)
      diagnostics <- Queue.bounded[EventEnvelope](diagnosticsCapacity)
      // Reliable capacity is a TSemaphore, not awaited Queue.offer: the STM
      // acquire either commits fully or (on timeout/interruption) has no
      // effect at all, so a timed-out publisher provably never inserted an
      // envelope. With Queue.offer + .timeout the timeout can win the race
      // against an in-flight insertion, which would double-issue a sequence.
      // A taken permit guarantees the subsequent non-blocking offer succeeds.
      permits <- STM.atomically(TSemaphore.make(reliableCapacity.toLong))
      // Diagnostics capacity is gated by a TSemaphore for the same reason:
      // Queue.bounded.offer SUSPENDS on a full buffer (zio BackPressure
      // strategy — `offer` never returns false), so the drop path must be
      // decided by an atomic try-acquire, never by the offer result.
      diagPermits <- STM.atomically(TSemaphore.make(diagnosticsCapacity.toLong))
      states <- TMap.empty[JobId, SessionSnapshot].commit
      locks <- TMap.empty[JobId, TSemaphore].commit
      subscribers <-
        TMap.empty[JobId, Set[Queue[EventEnvelope]]].commit
      dropped <- TRef.make(0L).commit
      hub = new Live(
        reliable,
        diagnostics,
        permits,
        diagPermits,
        reliableCapacity,
        diagnosticsCapacity,
        states,
        locks,
        subscribers,
        dropped
      )
      // Forwarders are daemon-owned by the hub, not by the caller's scope.
      // `.interruptible` is mandatory: forkDaemon inside a (possibly nested)
      // uninterruptible mask would give the fiber a non-interruptible start.
      _ <- hub.start
    yield hub

  /** Routing table (ADR-024 §2.3, card RAI-055.b-i).
    *
    *   - RELIABLE — bounded-wait offer, typed Backpressure on timeout, no
    *     silent loss, per-job order preserved: `Terminal`, `Waiting`,
    *     `JobStatusChanged`, `BudgetChanged`, `RetryScheduled`, `Delegated`.
    *   - DIAGNOSTICS — non-blocking offer, drop + `droppedDiagnostics` counter
    *     when full: `ModelCallStarted`, `ModelCallFinished`, `ToolStarted`,
    *     `ToolProgress`, `ToolFinished`, `Diagnostics`, `DiagnosticsCoalesced`.
    */
  private def routeOf(payload: EventPayload): Route =
    payload match
      case EventPayload.Terminal(_)               => Route.Reliable
      case EventPayload.Waiting(_)                => Route.Reliable
      case EventPayload.JobStatusChanged(_, _, _) => Route.Reliable
      case EventPayload.BudgetChanged(_, _, _)    => Route.Reliable
      case EventPayload.RetryScheduled(_, _, _)   => Route.Reliable
      case EventPayload.Delegated(_, _)           => Route.Reliable
      case _                                      => Route.Diagnostics

  private sealed trait Route

  private object Route:
    case object Reliable extends Route
    case object Diagnostics extends Route

  private final class Live(
      reliable: Queue[EventEnvelope],
      diagnostics: Queue[EventEnvelope],
      permits: TSemaphore,
      diagPermits: TSemaphore,
      reliableCapacity: Int,
      diagnosticsCapacity: Int,
      states: TMap[JobId, SessionSnapshot],
      locks: TMap[JobId, TSemaphore],
      subscribers: TMap[JobId, Set[Queue[EventEnvelope]]],
      dropped: TRef[Long]
  ) extends EventHub:

    def publish(
        jobId: JobId,
        rootId: RootId,
        sessionId: SessionId,
        payload: EventPayload
    ): IO[EventError, Unit] =
      lockFor(jobId).flatMap(
        _.withPermit(publishLocked(jobId, rootId, sessionId, payload))
      )

    def subscribe(
        jobId: JobId
    ): UIO[(SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])] =
      for
        queue <- Queue.bounded[EventEnvelope](subscriberQueueCapacity)
        // Register + watermark read is ONE transaction: no loss window, and
        // no duplicate either (an envelope folded before this commit is
        // behind the watermark and filtered; after — delivered).
        state <- STM.atomically {
          for
            known <- subscribers.get(jobId)
            _ <- subscribers.put(
              jobId,
              known.getOrElse(Set.empty[Queue[EventEnvelope]]) + queue
            )
            snapshot <- states.get(jobId)
          yield snapshot
        }
        snapshot = state.getOrElse(
          // Placeholder ids for a job that has published nothing yet; the
          // reducer does not re-check ids, so the first real envelope (seq 1)
          // simply continues this state.
          SessionReducer.initial(jobId, RootId(""), SessionId(""))
        )
        unregister = STM.atomically {
          subscribers.get(jobId).flatMap {
            case None         => STM.unit
            case Some(queues) => subscribers.put(jobId, queues - queue)
          }
        }
      yield (
        snapshot,
        ZStream
          .fromQueue(queue)
          .filter(e => e.jobId == jobId && e.seq > snapshot.lastSeq)
          .ensuring(unregister)
      )

    def snapshot(jobId: JobId): UIO[Option[SessionSnapshot]] =
      STM.atomically(states.get(jobId))

    def droppedDiagnostics: UIO[Long] = STM.atomically(dropped.get)

    /** Forks the two forwarder fibers as hub-owned daemons (no `stop()` in this
      * slice; the hub is process-lifetime).
      *
      * Known failure mode of this slice: the forwarders are unsupervised — an
      * interrupted or otherwise defunct forwarder can leak one permit (its
      * `afterTake` release never runs) and lose the envelope it has just taken
      * (taken from the class buffer but never fanned out). Supervision and
      * restart are deferred to a later card.
      */
    def start: UIO[Unit] =
      for
        _ <- forwarder(
          reliable,
          permits.release.commit
        ).interruptible.forkDaemon
        _ <- forwarder(
          diagnostics,
          diagPermits.release.commit
        ).interruptible.forkDaemon
      yield ()

    /** One fiber per class buffer: relay envelopes FIFO into every subscriber
      * queue of the envelope's job. The offer to subscriber queues is awaited —
      * backpressure sits here, never on publishers. No STM lock is held while
      * offering: the subscriber set is read inside a short transaction that is
      * closed before any queue is touched.
      */
    private def forwarder(
        buffer: Queue[EventEnvelope],
        afterTake: UIO[Unit]
    ): UIO[Nothing] =
      buffer.take
        .flatMap(envelope => afterTake *> fanOut(envelope))
        .forever

    private def fanOut(envelope: EventEnvelope): UIO[Unit] =
      STM
        .atomically(subscribers.get(envelope.jobId).map(_.getOrElse(Set.empty)))
        .flatMap(queues => ZIO.foreachDiscard(queues)(_.offer(envelope).unit))

    private def publishLocked(
        jobId: JobId,
        rootId: RootId,
        sessionId: SessionId,
        payload: EventPayload
    ): IO[EventError, Unit] =
      for
        current <- STM.atomically(states.get(jobId))
        lastSeq = current.fold(0L)(_.lastSeq)
        base = current.getOrElse(
          SessionReducer.initial(jobId, rootId, sessionId)
        )
        atMs <- ZIO.succeed(java.lang.System.currentTimeMillis())
        envelope = EventEnvelope(
          SchemaVersion,
          lastSeq + 1L,
          jobId,
          rootId,
          sessionId,
          atMs,
          payload
        )
        // Fold BEFORE any route action: a malformed event (only "after
        // Terminal" is reachable here, since seq is always lastSeq + 1) enters
        // no buffer, commits no state and consumes no sequence.
        folded <- SessionReducer.reduce(base, envelope)
        _ <- routeOf(payload) match
          case Route.Reliable =>
            STM
              .atomically(permits.acquire)
              .timeout(reliablePublishTimeout)
              .flatMap {
                case Some(_) =>
                  // Permit taken ⇒ capacity free ⇒ this offer cannot fail.
                  // State commit goes BEFORE the buffer offer (RAI-055.b-iii
                  // F1): a subscriber's register+watermark transaction either
                  // commits before this put (watermark < n ⇒ its queue is
                  // visible to the forwarder's later set read ⇒ delivered by
                  // the stream) or after it (watermark ≥ n ⇒ envelope n is in
                  // its snapshot) — envelope n is never in neither.
                  STM.atomically(states.put(jobId, folded)).unit *>
                    reliable.offer(envelope).flatMap {
                      case true  => ZIO.unit
                      case false =>
                        // Invariant, unreachable: the permit guarantees the
                        // offer succeeds.
                        ZIO.fail(
                          EventError.Backpressure(jobId, reliableCapacity)
                        )
                    }
                case None =>
                  // Timed out BEFORE any state commit: the fold is discarded,
                  // no sequence is consumed (EVT-03).
                  ZIO.fail(EventError.Backpressure(jobId, reliableCapacity))
              }
          case Route.Diagnostics =>
            // Non-blocking: full buffer ⇒ drop and count; no sequence, no
            // fold, no publisher delay (ADR-024 §2.3). The try-acquire is one
            // STM transaction (available read + conditional acquire): either
            // it commits with a permit or commits `false` having taken
            // nothing — a publisher is never suspended here, unlike
            // Queue.bounded.offer, which suspends on a full buffer.
            STM
              .atomically {
                for
                  free <- diagPermits.available
                  acquired <-
                    if free > 0L then diagPermits.acquire.as(true)
                    else STM.succeed(false)
                yield acquired
              }
              .flatMap {
                case true =>
                  // Permit taken ⇒ capacity free ⇒ this offer cannot suspend.
                  // State commit BEFORE the offer (RAI-055.b-iii F1, same
                  // register+watermark race as the reliable route).
                  STM.atomically(states.put(jobId, folded)).unit *>
                    diagnostics.offer(envelope).flatMap {
                      case true  => ZIO.unit
                      case false =>
                        // Invariant, unreachable: the permit guarantees the
                        // offer succeeds.
                        ZIO.fail(
                          EventError.Backpressure(jobId, diagnosticsCapacity)
                        )
                    }
                case false =>
                  // Dropped BEFORE any state commit: no sequence, no fold
                  // commit, no publisher delay (EVT-03).
                  STM.atomically(dropped.update(_ + 1L)).unit
              }
      yield ()

    /** Per-job publish lock: serializes sequence assignment with the buffer
      * offer so events of one job enter a buffer strictly in sequence order,
      * under any concurrent publishers. Held across the bounded reliable wait
      * (concurrent publishers of one job queue behind it, each with its own
      * timeout window); different jobs never share a lock.
      */
    private def lockFor(jobId: JobId): UIO[TSemaphore] =
      STM.atomically {
        locks.get(jobId).flatMap {
          case Some(lock) => STM.succeed(lock)
          case None =>
            TSemaphore.make(1L).flatMap { lock =>
              locks.put(jobId, lock).as(lock)
            }
        }
      }

  end Live
