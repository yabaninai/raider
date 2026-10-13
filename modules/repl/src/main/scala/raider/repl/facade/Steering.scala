package raider.repl.facade

import raider.core.{JobId, MessageId}
import raider.runtime.mailbox.{Mailbox, MailboxError}
import zio.Promise

import java.util.concurrent.ConcurrentHashMap

/** RAI-132: the steering channel of one facade-started job — the REPL facade
  * wiring over the ONE runtime steering mailbox (raider.runtime.mailbox,
  * consumed, never reimplemented).
  *
  * The mailbox cannot be created before the JobManager allocates the real
  * `JobId`, so the channel publishes it through `mailboxP` right after `start`
  * returns — the job's turn loop awaits the promise at its first boundary. This
  * is the same publish-then-await shape as the frozen delegation wiring
  * (DelegationToolset RAI-074): the task fiber can never start a model call
  * without a registered mailbox, and the REPL thread fulfills the promise
  * independently (no deadlock, no second runtime).
  *
  * `sealedP` resolves when the terminal watcher has closed the mailbox at the
  * OBSERVED terminal status — after it, sends are typed TerminalJob refusals
  * and every leftover receipt has been resolved (expired or applied).
  */
private[repl] final class SteerChannel(
    val mailboxP: Promise[Nothing, Mailbox],
    val sealedP: Promise[Nothing, Unit]
):

  /** Queued receipts not yet applied or expired, keyed by message id. The job's
    * turn loop removes and resolves them (Applied) at a boundary drain; the
    * terminal watcher resolves whatever remains (expired — the job settled
    * before a boundary could apply the message). Registration happens BEFORE
    * `Mailbox.send`, so a boundary drain racing the send can never miss the
    * receipt (an ack always has a resolvable receipt).
    */
  val pending: ConcurrentHashMap[MessageId, Promise[Nothing, Option[
    SteerReceipt.Applied
  ]]] =
    ConcurrentHashMap[MessageId, Promise[Nothing, Option[
      SteerReceipt.Applied
    ]]]()

object SteerChannel:

  /** Steering capacity of a facade job — the same default bound as the frozen
    * delegation wiring (DelegationToolset.DefaultMailboxCapacity): overflow is
    * the typed MailboxError.Full refusal, never a silent drop.
    */
  val Capacity: Int = 32

end SteerChannel

/** RAI-132 typed steering receipt: EVERY `send` ends [[Queued]] or [[Rejected]]
  * — never silent, never a bare exception; a [[Queued]] receipt later resolves
  * to [[Applied]] (drained at a turn boundary, included in the next model-turn
  * input as the `owner steering:` section) or — when the job settled first — to
  * an expired outcome surfaced through `Queued.settled` (an ack is never
  * silently converted into a loss: the message stayed drainable and the receipt
  * stays observable).
  */
enum SteerReceipt:
  def jobId: JobId
  def msgId: MessageId
  def text: String

  /** Acked by the runtime steering mailbox; awaits a turn boundary. `settled`
    * resolves with the [[Applied]] receipt, or `None` when the job reached a
    * terminal status before any boundary consumed the message.
    */
  case Queued(
      jobId: JobId,
      msgId: MessageId,
      text: String,
      pending: Int,
      duplicate: Boolean,
      settled: Promise[Nothing, Option[Applied]]
  ) extends SteerReceipt

  /** The message was drained at a turn boundary and became part of the next
    * model-turn input (the `owner steering:` section of the recorded request).
    */
  case Applied(jobId: JobId, msgId: MessageId, text: String)
      extends SteerReceipt

  /** Typed refusal — the runtime mailbox error verbatim (UnknownJob, Full,
    * TerminalJob). No dispatch happened.
    */
  case Rejected(
      jobId: JobId,
      msgId: MessageId,
      text: String,
      reason: MailboxError
  ) extends SteerReceipt

object SteerReceipt:

  /** One-line console rendering of a receipt (the `:send` answer). */
  def render(r: SteerReceipt): String =
    val quoted =
      "\"" + r.text.take(80) + (if r.text.length > 80 then "…" else "") + "\""
    r match
      case q: Queued =>
        val dup =
          if q.duplicate then " (duplicate id — already accepted)" else ""
        s"queued ${q.jobId.value} [${q.msgId.value}] $quoted — applies at " +
          s"the next model turn (pending ${q.pending})$dup"
      case a: Applied =>
        s"applied ${a.jobId.value} [${a.msgId.value}] $quoted — included in " +
          "the model input"
      case rj: Rejected =>
        s"rejected ${rj.jobId.value} [${rj.msgId.value}] $quoted — " +
          s"${rj.reason.toRaiderError.detail}"

end SteerReceipt
