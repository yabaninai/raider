package raider.cli.chat

import raider.core.JobId
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot,
  UsageSnapshot,
  WaitReason
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

  /** Render ONE line from a fold. Pure and total; never multi-line. The latest
    * envelope wins over the coarse job status (the job layer carries no model
    * round / wait reason — those live in the loop's events); a job with no
    * events at all renders as `queued`.
    */
  def render(f: Fold): String =
    val snap = f.snapshot
    val body =
      if JobStatus.terminal.contains(snap.status) then
        s"terminal ${lower(snap.status)}"
      else
        f.latest match
          case None =>
            if snap.status == JobStatus.Queued then "queued" else fallback(f)
          case Some(EventPayload.RetryScheduled(_, delayMs, _)) =>
            s"retry in ${delayMs}ms"
          case Some(EventPayload.ToolStarted(_, tool, _))  => s"tool $tool"
          case Some(EventPayload.ToolProgress(_, tool, _)) => s"tool $tool"
          case Some(EventPayload.Waiting(reason)) =>
            s"waiting(${lower(reason)})"
          case Some(EventPayload.ModelCallStarted(r, _, _)) =>
            s"model round $r"
          case Some(EventPayload.ModelCallFinished(r, _, _)) =>
            s"model round $r"
          case Some(EventPayload.JobStatusChanged(_, to, reason)) =>
            to match
              case JobStatus.Waiting =>
                s"waiting(${reason.fold("unknown")(lower)})"
              case JobStatus.WaitingApproval => "waiting(approval)"
              case JobStatus.Cancelling      => "cancelling"
              case _                         => fallback(f)
          case _ => fallback(f)
    s"$body | ${cost(snap.usage)}"

  private def fallback(f: Fold): String =
    f.snapshot.activeTool match
      case Some((tool, _)) => s"tool $tool"
      case None            => f.round.fold("running")(r => s"model round $r")

  private def lower(s: Any): String = s.toString.toLowerCase

  /** Honest cost rendering (ADR-024 §2.4): a cost nobody observed is UNKNOWN,
    * never $0 — the pristine (no BudgetChanged yet) snapshot and the loop's
    * `unknown = true` usage both render as `cost unknown`.
    */
  private def cost(u: UsageSnapshot): String =
    if u.unknown || (u.reserved.value == 0L && u.observed.value == 0L) then
      "cost unknown"
    else f"cost $$${u.observed.value.toDouble / 1000000.0}%.6f"

  /** A started renderer: its fiber and the highest seq folded so far (the
    * catch-up probe used before interrupting the fiber).
    */
  final case class Running(
      fiber: Fiber.Runtime[Nothing, Unit],
      foldedSeq: Ref[Long]
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
                write *> lastLine.set(Some(line)) *> foldedSeq.set(e.seq)
              }
            }
        }
        .interruptible
        .forkDaemon
    yield Running(fiber, foldedSeq)

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
