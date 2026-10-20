package raider.cli.chat

import raider.core.JobId
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot,
  StatusText
}
import raider.runtime.jobs.JobStatus
import raider.cli.run.EventExport
import zio.{Fiber, Ref, UIO, ZIO}

/** RAI-060 (STAT-01): the ONE-LINE chat status line, folded from real hub
  * envelopes.
  *
  * The renderer consumes the subscribed (loss-window-free) per-job envelope
  * stream in a background fiber, reorders by per-job `seq` (reliable and
  * diagnostics classes arrive on two independent hub forwarders), folds each
  * envelope through [[SessionReducer]] plus a tiny round/last-event track, and
  * hands every CHANGED line to a caller-provided sink.
  *
  * One-writer discipline: the renderer fiber NEVER prints. All output goes
  * through the sink (in chat mode: [[ChatWriter]], the same synchronized writer
  * the streamed text deltas use), so a status write can never interleave
  * mid-line with a streamed answer.
  *
  * Honest unknowns (ADR-024 §2.4): an unknown cost renders as `cost unknown` —
  * unknown is never coerced into $0. States rendered: `queued`, `model round
  * N`, `tool <name>`, `waiting(<reason>)`, `retry in <delay>ms`, `terminal
  * <outcome>`.
  */
object StatusLine:

  /** Fold state: the reducer snapshot plus what the snapshot alone cannot carry
    * — the last model round and the highest-seq payload seen (the status text
    * follows the LATEST event, because e.g. the loop emits `Waiting(Slot)`
    * right before every model/tool dispatch while the job status itself stays
    * Running).
    */
  final case class Fold(
      snapshot: SessionSnapshot,
      round: Option[Int],
      latest: Option[EventPayload]
  )

  object Fold:

    def initial(snapshot: SessionSnapshot): Fold =
      Fold(snapshot, None, None)

    /** Advance the fold by one seq-ordered envelope. A Malformed refusal
      * (impossible for an in-order stream) keeps the previous fold — the
      * reducer's verdict is respected, never guessed around.
      */
    def step(f: Fold, e: EventEnvelope): UIO[Fold] =
      SessionReducer
        .reduce(f.snapshot, e)
        .fold(
          _ => f,
          snap =>
            val round = e.payload match
              case EventPayload.ModelCallStarted(r, _, _)  => Some(r)
              case EventPayload.ModelCallFinished(r, _, _) => Some(r)
              case _                                       => f.round
            Fold(snap, round, Some(e.payload))
        )

  /** Render ONE line from a fold. Pure and total; never multi-line.
    *
    * RAI-097: every status word and the counters are single-sourced in
    * [[raider.runtime.events.StatusText]] — the chat carries NO local
    * status/cost formatting anymore. What remains local is the RAI-060
    * latest-event policy plus the loop-only facts a snapshot cannot carry: the
    * model round number and the retry backoff delay (the loop emits
    * `Waiting(Slot)` right before every model/tool dispatch while the job
    * status itself stays Running, so the LATEST event's word wins over the
    * folded status; the folded snapshot body takes over only when neither the
    * latest event nor the round/active-tool tracks carry a word). A folded
    * terminal status outranks everything: Terminal is exactly one event and the
    * last one (RAI-059 job authority).
    */
  def render(f: Fold): String =
    val snap = f.snapshot
    val body: String =
      snap.status match
        case t: JobStatus.Terminal => StatusText.terminalText(t)
        case _ =>
          word(f).getOrElse:
            snap.activeTool match
              case Some((tool, _)) => StatusText.toolText(tool)
              case None =>
                f.round.fold(StatusText.body(snap))(r => s"model round $r")
    s"$body | ${StatusText.counters(snap)}"

  /** The latest event's status word, via the frozen StatusText formatters. */
  private def word(f: Fold): Option[String] =
    f.latest match
      case Some(EventPayload.RetryScheduled(_, delayMs, _)) =>
        Some(s"retry in ${delayMs}ms") // loop-only fact: the backoff delay
      case Some(EventPayload.ModelCallStarted(r, _, _)) =>
        Some(s"model round $r") // loop-only fact: the round number
      case Some(EventPayload.ModelCallFinished(r, _, _)) =>
        Some(s"model round $r")
      case Some(EventPayload.ToolStarted(_, tool, _)) =>
        Some(StatusText.toolText(tool))
      case Some(EventPayload.ToolProgress(_, tool, _)) =>
        Some(StatusText.toolText(tool))
      case Some(EventPayload.Waiting(reason)) =>
        Some(StatusText.waitingText(Some(reason)))
      case Some(EventPayload.JobStatusChanged(_, to, reason)) =>
        StatusText.statusWord(to, reason)
      case _ => None

  /** A started renderer: its fiber and the highest seq folded so far (the
    * catch-up probe used before interrupting the fiber).
    */
  final case class Running(
      fiber: Fiber.Runtime[Nothing, Unit],
      foldedSeq: Ref[Long],
      exported: Ref[Vector[EventEnvelope]]
  )

  /** Subscribe (no loss window: snapshot + watermark in one transaction) and
    * run the render loop. MUST be started before the job body is released; the
    * returned fiber runs until interrupted.
    */
  def start(
      hub: EventHub,
      jobId: JobId,
      sink: String => UIO[Unit]
  ): UIO[Running] =
    for
      (snapshot, stream) <- hub.subscribe(jobId)
      state <- Ref.make(Fold.initial(snapshot))
      lastLine <- Ref.make(Option.empty[String])
      foldedSeq <- Ref.make(snapshot.lastSeq)
      exported <- Ref.make(EventExport.startTransition(snapshot).toVector)
      fiber <- EventExport
        .inOrder(snapshot.lastSeq)(stream)
        .foreach { e =>
          state.get
            .flatMap(cur =>
              Fold.step(cur, e).flatMap(next => state.set(next).as(next))
            )
            .flatMap { f =>
              val line = render(f)
              lastLine.get.flatMap { prev =>
                val write =
                  if prev.contains(line) then ZIO.unit else sink(line)
                write *> lastLine.set(Some(line)) *>
                  exported.update(_ :+ e) *> foldedSeq.set(e.seq)
              }
            }
        }
        .interruptible
        .forkDaemon
    yield Running(fiber, foldedSeq, exported)

end StatusLine

/** Single-writer terminal output for chat mode (RAI-060): streamed text deltas
  * and status lines share ONE synchronized writer, so concurrent fibers can
  * never interleave mid-line. Status lines are written to stderr — stdout is
  * the answer stream, and stderr is this CLI's existing live-observability
  * channel (trace → stderr); the write is atomic (printAbove-style: it can
  * never land inside a partially streamed stdout line). Prompt repaint is P2
  * territory (RAI-062 PTY), not silent here.
  */
final class ChatWriter:
  private val lock = new Object

  def text(t: String): Unit =
    lock.synchronized {
      print(t)
      System.out.flush()
    }

  def statusLine(line: String): Unit =
    lock.synchronized {
      System.err.println(s"${ChatWriter.Dim}[status] $line${ChatWriter.Reset}")
      System.err.flush()
    }

object ChatWriter:
  private val Reset = "\u001b[0m"
  private val Dim = "\u001b[2m"
