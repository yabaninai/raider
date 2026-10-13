package raider.runtime.mailbox

import raider.core.{JobId, MessageId}
import raider.runtime.jobs.JobStatus
import zio.{Chunk, IO, UIO}
import zio.stm.{STM, TRef}

/** One steering message bound for a child agent, identified by `id` for
  * exactly-once delivery: a re-send of an already-accepted id is an idempotent
  * ack, never a second queued message (SEND-01).
  */
final case class Message(id: MessageId, content: String)

/** Receipt of a completed `send`: the message is enqueued (or already accepted
  * under the same id) when the ack is returned — the ack never precedes the
  * enqueue, because both happen in one STM commit.
  */
final case class Ack(id: MessageId, duplicate: Boolean)

/** Turn-boundary phase of a mailbox, observable via `Mailbox.state`.
  *
  *   - Accepting — between turns; sends enqueue for the upcoming boundary.
  *   - Draining — a boundary drain is open; sends enqueue for the NEXT turn,
  *     never for the turn being drained (not lost, not stolen by a second drain
  *     inside the same window).
  *   - Closed — the job reached a terminal status; sends are refused typed
  *     ([[MailboxError.TerminalJob]]); messages acked earlier stay drainable.
  */
sealed trait MailboxState extends Product with Serializable

object MailboxState:
  case object Accepting extends MailboxState
  case object Draining extends MailboxState
  case object Closed extends MailboxState

/** Bounded per-child mailbox for steering messages applied strictly at turn
  * boundaries — between tool calls, never mid-tool-call (m1.md RAI-065,
  * ADR-024). STM throughout: every observable transition (enqueue, take, phase
  * change, close) is one atomic transaction, so a send always lands entirely
  * before or entirely after a boundary take.
  *
  * Guarantees:
  *   - exactly-once per `MessageId` for the mailbox lifetime: accepted ids are
  *     remembered across drains; a re-send is an idempotent ack (SEND-01);
  *   - conservation at the boundary: a message racing `drainOnTurnBoundary` is
  *     either in the drained batch or still pending for the next turn — never
  *     lost, never duplicated (SEND-03);
  *   - bounded: capacity is a constructor parameter; overflow is the typed
  *     [[MailboxError.Full]] refusal (SEND-02). Idempotent re-sends of accepted
  *     ids are acked even at capacity — a retry cannot make things worse;
  *   - terminal honesty: after `close`, sends are refused with
  *     [[MailboxError.TerminalJob]] suggesting a new turn; messages acked
  *     before the close remain retrievable by a final drain — an ack never
  *     becomes a loss.
  *
  * The wiring into DelegationToolset/REPL (who calls `drainOnTurnBoundary`
  * between tool calls, when `endTurn` runs, when `close` fires) is a separate
  * card; this slice owns only the mailbox itself.
  */
trait Mailbox:

  /** The child job this mailbox belongs to. */
  def jobId: JobId

  /** Maximum number of pending (not yet drained) messages; fixed at make. */
  def capacity: Int

  /** Accept `message`: enqueues and acks in one atomic transaction, or refuses
    * typed — [[MailboxError.Full]] at capacity, [[MailboxError.TerminalJob]]
    * after close. A repeated id is an idempotent ack (`duplicate = true`) and
    * consumes no capacity.
    */
  def send(message: Message): IO[MailboxError, Ack]

  /** Turn boundary: atomically take ALL pending messages in arrival order and
    * open the Draining window. These are the only messages the turn may apply.
    * While the window is open, sends queue for the next turn; a second drain
    * inside the same window returns nothing (it must not steal next-turn
    * messages). After the job is closed, a drain returns whatever was acked but
    * not yet taken (final drain).
    */
  def drainOnTurnBoundary: UIO[Chunk[Message]]

  /** Close the Draining window opened by `drainOnTurnBoundary`, returning to
    * Accepting. A no-op when no window is open (never resurrects a Closed
    * mailbox). The wiring calls it after it has applied the drained batch.
    */
  def endTurn: UIO[Unit]

  /** Mark the job terminal: all later sends are refused typed
    * ([[MailboxError.TerminalJob]]). Idempotent — the first terminal status
    * wins. Already-acked messages stay drainable.
    */
  def close(status: JobStatus.Terminal): UIO[Unit]

  /** Current turn-boundary phase. */
  def state: UIO[MailboxState]

  /** Number of pending (accepted, not yet drained) messages. */
  def pendingCount: UIO[Int]

object Mailbox:

  /** Build a mailbox for `jobId`. `capacity` must be `> 0`; a non-positive
    * value throws [[IllegalArgumentException]] eagerly — a programming error,
    * not a runtime condition, so it does not use the (absent) error channel of
    * `UIO` (same rule as `EventHub.make`).
    */
  def make(jobId: JobId, capacity: Int): UIO[Mailbox] =
    if capacity <= 0 then
      throw new IllegalArgumentException(s"capacity must be > 0, got $capacity")
    TRef
      .make(Cell(Phase.Accepting, Vector.empty, Set.empty))
      .commit
      .map(ref => new Live(jobId, capacity, ref))

  /** Internal phase: the public [[MailboxState]] plus the terminal status
    * recorded by `close` (carried in the same cell so phase and status change
    * in one transaction).
    */
  private sealed trait Phase extends Product with Serializable

  private object Phase:
    case object Accepting extends Phase
    case object Draining extends Phase
    final case class Closed(terminal: JobStatus.Terminal) extends Phase

  /** One TRef holds the whole cell: phase, pending queue (arrival order) and
    * the id set for exactly-once dedup. A single ref makes every operation a
    * one-transaction read-modify-write — no multi-ref composition to reason
    * about. The pending queue is a Vector appended at the tail; a boundary
    * drain always takes ALL of it (drain = taking all pending), so the batch
    * preserves arrival order by construction.
    */
  private final case class Cell(
      phase: Phase,
      pending: Vector[Message],
      seen: Set[MessageId]
  )

  private final class Live(
      val jobId: JobId,
      val capacity: Int,
      cell: TRef[Cell]
  ) extends Mailbox:

    def send(message: Message): IO[MailboxError, Ack] =
      STM.atomically {
        cell.get.flatMap { c =>
          c.phase match
            case Phase.Closed(terminal) =>
              STM.fail(MailboxError.TerminalJob(jobId, terminal))
            case Phase.Accepting | Phase.Draining =>
              if c.seen.contains(message.id) then
                // Idempotent re-send: acked, no enqueue, no capacity use —
                // even when the queue is full (an accepted message is never
                // re-refused by a retry).
                STM.succeed(Ack(message.id, duplicate = true))
              else if c.pending.size >= capacity then
                STM.fail(MailboxError.Full(jobId, capacity, c.pending.size))
              else
                cell
                  .set(
                    c.copy(
                      pending = c.pending :+ message,
                      seen = c.seen + message.id
                    )
                  )
                  .as(Ack(message.id, duplicate = false))
        }
      }

    def drainOnTurnBoundary: UIO[Chunk[Message]] =
      STM.atomically {
        cell.get.flatMap { c =>
          c.phase match
            case Phase.Accepting =>
              cell
                .set(c.copy(pending = Vector.empty, phase = Phase.Draining))
                .as(Chunk.fromIterable(c.pending))
            case Phase.Draining =>
              // A boundary window is already open: a stray second drain must
              // not take messages that belong to the NEXT turn.
              STM.succeed(Chunk.empty)
            case Phase.Closed(_) =>
              // Final drain: messages acked before the close stay retrievable
              // — an ack is never silently converted into a loss.
              cell
                .set(c.copy(pending = Vector.empty))
                .as(Chunk.fromIterable(c.pending))
        }
      }

    def endTurn: UIO[Unit] =
      STM.atomically {
        cell.get.flatMap { c =>
          c.phase match
            case Phase.Draining => cell.set(c.copy(phase = Phase.Accepting))
            case _              => STM.unit
        }
      }

    def close(status: JobStatus.Terminal): UIO[Unit] =
      STM.atomically {
        cell.modify { c =>
          c.phase match
            case Phase.Closed(_) => ((), c)
            case _               => ((), c.copy(phase = Phase.Closed(status)))
        }
      }

    def state: UIO[MailboxState] =
      STM.atomically(cell.get.map(_.phase)).map {
        case Phase.Accepting => MailboxState.Accepting
        case Phase.Draining  => MailboxState.Draining
        case Phase.Closed(_) => MailboxState.Closed
      }

    def pendingCount: UIO[Int] =
      STM.atomically(cell.get.map(_.pending.size))

  end Live
