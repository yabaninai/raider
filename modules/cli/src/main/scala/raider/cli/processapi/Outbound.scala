package raider.cli.processapi

import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Duration, IO, Promise, Ref, UIO, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference

/** Bounded outbound frame queue for the process adapter (ADR-037 §5; fixtures
  * `slow-reader`, `broken-pipe`, `cancel-mid-run`).
  *
  * Invariants:
  *   - at most [[Outbound.MaxOrdinaryFrames]] ordinary frames and
  *     [[Outbound.MaxOrdinaryBytes]] encoded bytes are held in memory, at most
  *     one frame at 1 MiB;
  *   - receipts (accepted, error, cancelAck) are NEVER dropped: when the queue
  *     is full their admission WAITS (backpressure against the reader, not
  *     unbounded growth; the wait ends when the peer drains or the stall
  *     watchdog declares the channel dead);
  *   - progress observations never wait: on a full queue they COALESCE, and the
  *     next delivered observation carries `skipped {fromSequence, count}`
  *     covering exactly the coalesced range;
  *   - the terminal frame has a RESERVED slot (separate from the ordinary
  *     queue) and is written last, after the ordinary queue drains, input is
  *     closed, and every admitted receipt admission has landed (RAI-232: the
  *     writer's empty-point gate holds the terminal until `State.waiters` is
  *     empty);
  *   - every stdout write is bounded by the stall watchdog: a peer that stops
  *     draining is declared dead after `stallBound` and the session shuts down
  *     truthfully — the writer never loops on a broken pipe and never blocks
  *     without bound.
  */
object Outbound:

  val MaxOrdinaryFrames: Int = 64
  val MaxOrdinaryBytes: Int = 4 * 1024 * 1024

  /** The stdout channel died (EPIPE) or the peer stopped draining. */
  final case class ChannelDead(reason: String)

  private final case class State(
      queue: Vector[(Json, Int)],
      queuedBytes: Int,
      // Live-path coalescing bookkeeping: observations dropped under
      // backpressure, reported by the next delivered observation.
      gap: Option[(Long, Long)],
      lastEmittedSeq: Long,
      terminal: Option[Json],
      inputClosed: Boolean,
      peerDead: Option[String],
      // Receipts registered on a full queue. An entry is added when the
      // admission registers (atomically with the full-queue check) and is
      // removed only when the admission lands: the frame is enqueued, the
      // channel dies, or the waiting fiber is interrupted. The writer's
      // empty-point gate ([[pick]]) requires this vector to be empty before
      // it picks the terminal or finishes, so no admitted receipt can
      // outlive the writer (RAI-232).
      waiters: Vector[Promise[Nothing, Unit]],
      // The idle writer's parked promise, at most one. Kept apart from
      // `waiters` so the writer's own wakeup can never hold the terminal
      // gate closed.
      writerWait: Option[Promise[Nothing, Unit]]
  )

  private object State:

    val initial: State = State(
      Vector.empty,
      0,
      None,
      0L,
      None,
      inputClosed = false,
      peerDead = None,
      waiters = Vector.empty,
      writerWait = None
    )

  private sealed trait Pick

  private object Pick:
    final case class Write(json: Json) extends Pick
    final case class WriteTerminal(json: Json) extends Pick
    case object Idle extends Pick
    case object Done extends Pick

  /** RAI-246 test-only interleaving seam. Each hook is an
    * `AtomicReference[Option[Promise[Nothing, Unit]]]` initialized to `None` (=
    * disarmed). A disarmed seam site costs one volatile reference read and
    * short-circuits to a no-op: no scheduling point, no behavior change; a site
    * allocates or schedules only when a test in this package arms its hook. The
    * seam exists solely to make the race window between a woken waiter's
    * landing modify and the writer's inline terminal pick deterministically
    * enterable (red-first on the pre-RAI-232 base, green under the delivered
    * empty-point gate); it is not consulted by any production caller.
    */
  private[processapi] final class ProbeSeam:

    /** Signaled when the writer reaches the terminal pick (arrival signal). */
    val writerAtPick: AtomicReference[Option[Promise[Nothing, Unit]]] =
      new AtomicReference[Option[Promise[Nothing, Unit]]](None)

    /** While `Some`, the writer suspends post-pick/pre-write until this promise
      * succeeds.
      */
    val writerHold: AtomicReference[Option[Promise[Nothing, Unit]]] =
      new AtomicReference[Option[Promise[Nothing, Unit]]](None)

    /** Signaled when a receipt admission finds the queue full and is about to
      * park as a waiter (registration signal).
      */
    val waiterRegistered: AtomicReference[Option[Promise[Nothing, Unit]]] =
      new AtomicReference[Option[Promise[Nothing, Unit]]](None)

    /** While `Some`, a woken waiter suspends post-wake/pre-landing until this
      * promise succeeds.
      */
    val waiterHold: AtomicReference[Option[Promise[Nothing, Unit]]] =
      new AtomicReference[Option[Promise[Nothing, Unit]]](None)

  def make(
      writeOne: Json => IO[ChannelDead, Unit],
      stallBound: Duration
  ): UIO[Outbound] =
    for
      state <- Ref.make(State.initial)
      death <- Promise.make[Nothing, ChannelDead]
      terminalWritten <- Promise.make[Nothing, Unit]
      writerDone <- Promise.make[Nothing, Unit]
    yield new Outbound(
      writeOne,
      stallBound,
      state,
      death,
      terminalWritten,
      writerDone
    )

end Outbound

final class Outbound private (
    writeOne: Json => IO[Outbound.ChannelDead, Unit],
    stallBound: Duration,
    state: Ref[Outbound.State],
    death: Promise[Nothing, Outbound.ChannelDead],
    terminalWritten: Promise[Nothing, Unit],
    writerDone: Promise[Nothing, Unit]
):

  import Outbound.*

  // RAI-246 test-only seam state; every hook stays `None` (disarmed) in
  // production paths (tests in this package arm it per instance via
  // OutboundSpec).
  private[processapi] val probeSeam: Outbound.ProbeSeam = new Outbound.ProbeSeam

  // ---------------------------------------------------------------------------
  // producers
  // ---------------------------------------------------------------------------

  /** Admits a receipt-class frame (accepted, error, cancelAck), waiting while
    * the ordinary queue is full. Receipts are never dropped; the wait ends when
    * the peer drains or the stall watchdog marks the channel dead.
    */
  def receipt(frame: Json): IO[ChannelDead, Unit] =
    val entry = encoded(frame)
    ZIO.suspendSucceed {
      state
        .modify { s =>
          val entryBytes = entry._2
          if s.peerDead.isDefined then (Left(deadOf(s)), s)
          else if hasRoom(s, entryBytes) then
            (
              Right(()),
              s.copy(
                queue = s.queue :+ entry,
                queuedBytes = s.queuedBytes + entryBytes
              )
            )
          else (Left(()), s)
        }
        .flatMap {
          case Right(()) => wakeChannel
          case Left(())  =>
            // RAI-246 test-only seam: signal that this admission found the
            // queue full and is about to park as a waiter. Inert when disarmed.
            probeWaiterRegisteredSeam *> admitWaiter(entry)
          case Left(dead: ChannelDead) => ZIO.fail(dead)
        }
    }

  /** Live-path progress/status observation: coalesces under backpressure
    * instead of waiting (ADR-037 §5 — progress may coalesce, receipts and
    * cancellation state never).
    */
  def observation(
      requestId: String,
      sequence: Long,
      kind: String,
      message: String
  ): UIO[Unit] =
    state.modify { s =>
      if s.peerDead.isDefined then ((), s)
      else
        val probeBytes =
          encoded(
            Protocol.observation(
              requestId,
              Protocol.Observation(sequence, kind, message, None)
            )
          )._2
        if hasRoom(s, probeBytes) then
          val frame = Protocol.observation(
            requestId,
            Protocol.Observation(
              sequence,
              kind,
              message,
              s.gap.map { case (from, count) => Protocol.Skipped(from, count) }
            )
          )
          val bytes = encoded(frame)._2
          (
            (),
            s.copy(
              queue = s.queue :+ ((frame, bytes)),
              queuedBytes = s.queuedBytes + bytes,
              gap = None,
              lastEmittedSeq = sequence
            )
          )
        else
          val merged = s.gap match
            case Some((from, count)) => Some((from, count + 1L))
            case None                => Some((sequence, 1L))
          ((), s.copy(gap = merged))
    } *> wakeChannel

  /** Flushes a still-open coalescing gap as one final observation before the
    * terminal frame, so the "next observation reports the skipped range"
    * contract holds even when no live observation follows the saturation.
    */
  def flushGap(requestId: String): IO[ChannelDead, Unit] =
    state.get.flatMap { s =>
      s.gap match
        case None => ZIO.unit
        case Some((from, count)) =>
          val frame = Protocol.observation(
            requestId,
            Protocol.Observation(
              s.lastEmittedSeq,
              "progress",
              s"coalesced $count observation(s) while the peer was slow",
              Some(Protocol.Skipped(from, count))
            )
          )
          receipt(frame)
    }

  /** Installs the terminal frame (at most once) and closes ordinary input; the
    * writer emits it last, after the queue drains. A dead channel makes this a
    * no-op (the terminalWritten promise is already failed).
    */
  def terminal(frame: Json): UIO[Unit] =
    state.modify { s =>
      if s.terminal.isEmpty && s.peerDead.isEmpty then
        ((), s.copy(terminal = Some(frame), inputClosed = true))
      else ((), s)
    } *> wakeChannel

  /** No further ordinary frames will be enqueued (clean EOF with no run). */
  def closeInput(): UIO[Unit] =
    state.update(_.copy(inputClosed = true)) *> wakeChannel

  // ---------------------------------------------------------------------------
  // consumer
  // ---------------------------------------------------------------------------

  /** Drains the queue to stdout until input is closed and the terminal frame
    * (if any) is written, or the channel dies. Run on its own fiber. Every
    * consumed transition wakes the waiters (freed room, closed input, dead
    * channel). The empty-point gate in [[pick]] keeps the writer from picking
    * the terminal (or finishing) while admitted receipts are still pending, so
    * the terminal is always the last frame on the wire (RAI-232).
    */
  def writer: ZIO[Any, Nothing, Unit] =
    ZIO.suspendSucceed {
      state.modify(pick).flatMap {
        case Pick.Write(json) =>
          boundedWrite(json).catchAll(markDead) *> wakeChannel *> writer
        case Pick.WriteTerminal(json) =>
          // RAI-246 test-only seam: signal arrival at the terminal pick and,
          // when armed, suspend post-pick/pre-write. Inert when disarmed.
          probeTerminalPickSeam *>
            boundedWrite(json).catchAll(markDead) *>
            terminalWritten.succeed(()).unit *>
            writerDone.succeed(()).unit *>
            wakeChannel
        case Pick.Idle =>
          wakeChannel *> awaitWriterWork *> writer
        case Pick.Done =>
          writerDone.succeed(()).unit *> wakeChannel
      }
    }

  private def boundedWrite(json: Json): IO[ChannelDead, Unit] =
    writeOne(json)
      .timeoutFail(
        ChannelDead(
          s"peer did not accept a frame within ${stallBound.toString}; declaring the channel dead"
        )
      )(stallBound)

  /** Marks the channel dead (idempotent): wakes every waiter, records the death
    * and surfaces it to the session so it can settle truthfully (bounded
    * cleanup, exit 26). The writer loop unwinds through `Done` and completes
    * `writerDone`, which releases [[terminalWrittenOrDone]].
    */
  private def markDead(dead: ChannelDead): UIO[Unit] =
    state.update(s =>
      if s.peerDead.isEmpty then s.copy(peerDead = Some(dead.reason)) else s
    ) *>
      death.succeed(dead).unit *>
      wakeChannel

  private def pick(s: State): (Pick, State) =
    if s.peerDead.isDefined then (Pick.Done, s)
    else if s.queue.nonEmpty then
      val head = s.queue.head
      (
        Pick.Write(head._1),
        s.copy(queue = s.queue.tail, queuedBytes = s.queuedBytes - head._2)
      )
    // Empty-point gate: with input closed, ordinary frames may still be
    // pending as registered-but-not-landed waiter admissions (RAI-232).
    // The terminal may only be picked — and the writer may only finish —
    // once every admission has landed and the queue has drained, so the
    // terminal frame is always the last one written.
    else if s.inputClosed && s.waiters.isEmpty && s.terminal.isDefined then
      (Pick.WriteTerminal(s.terminal.get), s.copy(terminal = None))
    else if s.inputClosed && s.waiters.isEmpty then (Pick.Done, s)
    else (Pick.Idle, s)

  // ---------------------------------------------------------------------------
  // observation surface for the session and tests
  // ---------------------------------------------------------------------------

  /** Completes with `true` when the terminal frame's bytes are on the wire, or
    * `false` when the writer finished without a terminal (clean no-run path or
    * dead channel — the writer loop always reaches `Done`).
    *
    * The two promises complete back-to-back on the writer fiber, so when the
    * race starts after both are already done the winner of `raceFirst` is a
    * scheduling coin flip; a `false` outcome is therefore re-checked against
    * the actual terminal state, making the answer deterministic: `true`
    * whenever the terminal was written, `false` only when the writer ended
    * without writing it.
    */
  val terminalWrittenOrDone: UIO[Boolean] =
    ZIO
      .raceFirst(
        terminalWritten.await.as(true),
        List(writerDone.await.as(false))
      )
      .flatMap {
        case true  => ZIO.succeed(true)
        case false => terminalWritten.poll.map(_.isDefined)
      }

  /** Completes with the death reason when the writer declared the peer dead
    * (the session settles truthfully on this).
    */
  val channelDeath: UIO[ChannelDead] = death.await

  /** Test/diagnostic surface: current ordinary queue depth. */
  def queueDepth: UIO[Int] = state.get.map(_.queue.size)

  /** Test/diagnostic surface: current encoded bytes held in the queue. */
  def queuedBytes: UIO[Int] = state.get.map(_.queuedBytes)

  /** Test/diagnostic surface: the open coalescing gap, if any. */
  def openGap: UIO[Option[(Long, Long)]] = state.get.map(_.gap)

  /** Test surface: marks the channel dead exactly like a stalled/broken write
    * would, so receipt waiters and the session observe the same failure.
    */
  def failChannelForTest(reason: String): UIO[Unit] =
    markDead(ChannelDead(reason))

  // ---------------------------------------------------------------------------
  // RAI-246 test-only seam sites (inert unless the per-instance hooks are
  // armed by a test; production callers never touch ProbeSeam)
  // ---------------------------------------------------------------------------

  /** Signals waiter registration when armed; no-op otherwise. */
  private def probeWaiterRegisteredSeam: UIO[Unit] =
    ZIO.suspendSucceed {
      probeSeam.waiterRegistered.get() match
        case None       => ZIO.unit
        case Some(hook) => hook.succeed(()).unit
    }

  /** Suspends a woken waiter while the hold latch is armed; no-op otherwise. */
  private def probeWaiterHoldSeam: UIO[Unit] =
    ZIO.suspendSucceed {
      probeSeam.waiterHold.get() match
        case None       => ZIO.unit
        case Some(hook) => hook.await.unit
    }

  /** Signals the writer's arrival at the terminal pick and suspends it there
    * while the hold latch is armed; no-op otherwise. Unlike the waiter-side
    * landing hold ([[probeWaiterHoldSeam]]), this hold deliberately registers
    * no `onInterrupt` cleanup: by suspension time the pick has already removed
    * the terminal from `State` and the writer holds no gate or seam
    * registration, so an interrupt simply unwinds the writer fiber exactly as
    * an interrupt inside `boundedWrite` would — nothing can pin the empty-point
    * gate. The landing hold, by contrast, suspends while the waiter is still
    * registered in `State.waiters` (pinning that gate) and therefore needs its
    * interrupt removal.
    */
  private def probeTerminalPickSeam: UIO[Unit] =
    ZIO.suspendSucceed {
      val arrived = probeSeam.writerAtPick.get()
      val hold = probeSeam.writerHold.get()
      (arrived match
        case None    => ZIO.unit
        case Some(p) => p.succeed(()).unit
      ) *>
        // No onInterrupt cleanup here (see scaladoc): the writer pins nothing.
        (hold match
          case None    => ZIO.unit
          case Some(p) => p.await.unit)
    }

  // ---------------------------------------------------------------------------
  // wakeup plumbing (waiter list with landing-tracked removal + state-
  // conditional registration: no lost wakeups — the condition is checked
  // atomically with the registration — and a writer-side quiescence gate:
  // no admitted receipt outlives the writer, RAI-232)
  // ---------------------------------------------------------------------------

  /** Wakes every registered receipt waiter and the parked idle writer. Waiter
    * promises stay in `State.waiters` until their admission lands (enqueue,
    * channel death, or interruption), so the empty-point gate can see pending
    * admissions; re-succeeding a promise is a no-op. The writer's parked
    * promise is cleared here and re-registered when it suspends again.
    */
  private def wakeChannel: UIO[Unit] =
    state
      .modify { s =>
        val ws = s.waiters
        val w = s.writerWait
        ((ws, w), s.copy(writerWait = None))
      }
      .flatMap { case (ws, w) =>
        ZIO.foreachDiscard(ws)(_.succeed(()).unit) *>
          w.fold(ZIO.unit)(_.succeed(()).unit)
      }

  /** Admits `entry` on behalf of a receipt that found the queue full. The
    * waiter promise is appended to `State.waiters` atomically with the
    * full-queue check and stays there — across stolen-slot re-checks — until
    * the entry is enqueued, the channel dies, or the waiting fiber is
    * interrupted (the interrupt finalizer removes it and wakes the writer, so
    * the terminal gate cannot be held closed by a dead waiter). Each wake
    * re-runs the admission from scratch: whatever freed the room (writer write,
    * terminal install, death) has already signalled through [[wakeChannel]].
    */
  private def admitWaiter(entry: (Json, Int)): IO[ChannelDead, Unit] =
    ZIO.uninterruptibleMask { restore =>
      Promise.make[Nothing, Unit].flatMap { p =>
        def step: IO[ChannelDead, Unit] =
          state
            .modify { s =>
              val entryBytes = entry._2
              if s.peerDead.isDefined then (Left(deadOf(s)), removeWaiter(s, p))
              else if hasRoom(s, entryBytes) then
                (
                  Right(()),
                  removeWaiter(s, p).copy(
                    queue = s.queue :+ entry,
                    queuedBytes = s.queuedBytes + entryBytes
                  )
                )
              else (Left(()), parkWaiter(s, p))
            }
            .flatMap {
              case Right(()) => wakeChannel
              case Left(()) =>
                restore(p.await).onInterrupt(
                  state.update(removeWaiter(_, p)) *> wakeChannel
                ) *>
                  // RAI-246 test-only seam: hold the woken waiter between its
                  // wake and its landing modify. The interrupt cleanup mirrors
                  // the registration removal so an interrupted probe cannot
                  // pin the empty-point gate closed. Inert when disarmed.
                  probeWaiterHoldSeam.onInterrupt(
                    state.update(removeWaiter(_, p)) *> wakeChannel
                  ) *> step
              case Left(dead: ChannelDead) => ZIO.fail(dead)
            }
        step
      }
    }

  /** Parks the idle writer until it has work or the empty-point gate can open
    * (queue progress, input closed with every admitted receipt landed, or a
    * dead channel), atomically with the registration in `State.writerWait`.
    */
  private def awaitWriterWork: UIO[Unit] =
    for
      p <- Promise.make[Nothing, Unit]
      shouldWait <- state.modify { s =>
        if writerMayProceed(s) then (false, s)
        else (true, s.copy(writerWait = Some(p)))
      }
      _ <- if shouldWait then p.await else ZIO.unit
    yield ()

  private def writerMayProceed(s: State): Boolean =
    s.queue.nonEmpty || s.peerDead.isDefined ||
      (s.inputClosed && s.waiters.isEmpty)

  private def removeWaiter(s: State, p: Promise[Nothing, Unit]): State =
    s.copy(waiters = s.waiters.filter(_ != p))

  private def parkWaiter(s: State, p: Promise[Nothing, Unit]): State =
    if s.waiters.contains(p) then s
    else s.copy(waiters = s.waiters :+ p)

  private def hasRoom(s: State, entryBytes: Int): Boolean =
    s.queue.size < MaxOrdinaryFrames && s.queuedBytes + entryBytes <= MaxOrdinaryBytes

  private def encoded(frame: Json): (Json, Int) =
    val bytes = frame.toJson.getBytes(UTF_8).length
    (frame, bytes)

  private def deadOf(s: State): ChannelDead =
    ChannelDead(s.peerDead.getOrElse("channel dead"))

end Outbound
