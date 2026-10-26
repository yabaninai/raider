package raider.cli.processapi

import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Duration, IO, Promise, Ref, UIO, ZIO}

import java.nio.charset.StandardCharsets.UTF_8

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
  *     queue) and is written last, after the ordinary queue drains and input is
  *     closed;
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
      waiters: Vector[Promise[Nothing, Unit]]
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
      waiters = Vector.empty
    )

  private sealed trait Pick

  private object Pick:
    final case class Write(json: Json) extends Pick
    final case class WriteTerminal(json: Json) extends Pick
    case object Idle extends Pick
    case object Done extends Pick

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
          case Right(())               => wakeWaiters
          case Left(())                => awaitRoom(entry._2) *> receipt(frame)
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
    } *> wakeWaiters

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
    } *> wakeWaiters

  /** No further ordinary frames will be enqueued (clean EOF with no run). */
  def closeInput(): UIO[Unit] =
    state.update(_.copy(inputClosed = true)) *> wakeWaiters

  // ---------------------------------------------------------------------------
  // consumer
  // ---------------------------------------------------------------------------

  /** Drains the queue to stdout until input is closed and the terminal frame
    * (if any) is written, or the channel dies. Run on its own fiber. Every
    * consumed transition wakes the waiters (freed room, closed input, dead
    * channel).
    */
  def writer: ZIO[Any, Nothing, Unit] =
    ZIO.suspendSucceed {
      state.modify(pick).flatMap {
        case Pick.Write(json) =>
          boundedWrite(json).catchAll(markDead) *> wakeWaiters *> writer
        case Pick.WriteTerminal(json) =>
          boundedWrite(json).catchAll(markDead) *>
            terminalWritten.succeed(()).unit *>
            writerDone.succeed(()).unit *>
            wakeWaiters
        case Pick.Idle =>
          awaitUnless(hasWork) *> writer
        case Pick.Done =>
          writerDone.succeed(()).unit *> wakeWaiters
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
      wakeWaiters

  private def pick(s: State): (Pick, State) =
    if s.peerDead.isDefined then (Pick.Done, s)
    else if s.queue.nonEmpty then
      val head = s.queue.head
      (
        Pick.Write(head._1),
        s.copy(queue = s.queue.tail, queuedBytes = s.queuedBytes - head._2)
      )
    else if s.inputClosed && s.terminal.isDefined then
      (Pick.WriteTerminal(s.terminal.get), s.copy(terminal = None))
    else if s.inputClosed then (Pick.Done, s)
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
  // wakeup plumbing (waiter list + state-conditional registration: no lost
  // wakeups — the condition is checked atomically with the registration)
  // ---------------------------------------------------------------------------

  private def wakeWaiters: UIO[Unit] =
    state
      .modify { s =>
        val ws = s.waiters
        (ws, s.copy(waiters = Vector.empty))
      }
      .flatMap(ws => ZIO.foreachDiscard(ws)(_.succeed(()).unit))

  /** Waits until `cond` holds, atomically with the registration. */
  private def awaitUnless(cond: Outbound.State => Boolean): UIO[Unit] =
    for
      p <- Promise.make[Nothing, Unit]
      shouldWait <- state.modify { s =>
        if cond(s) then (false, s) else (true, s.copy(waiters = s.waiters :+ p))
      }
      _ <- if shouldWait then p.await else ZIO.unit
    yield ()

  private def hasRoom(s: State, entryBytes: Int): Boolean =
    s.queue.size < MaxOrdinaryFrames && s.queuedBytes + entryBytes <= MaxOrdinaryBytes

  private def hasWork(s: State): Boolean =
    s.queue.nonEmpty || s.inputClosed || s.peerDead.isDefined

  private def awaitRoom(entryBytes: Int): UIO[Unit] =
    awaitUnless(s => s.peerDead.isDefined || hasRoom(s, entryBytes))

  private def encoded(frame: Json): (Json, Int) =
    val bytes = frame.toJson.getBytes(UTF_8).length
    (frame, bytes)

  private def deadOf(s: State): ChannelDead =
    ChannelDead(s.peerDead.getOrElse("channel dead"))

end Outbound
