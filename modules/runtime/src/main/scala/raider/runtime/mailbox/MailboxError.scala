package raider.runtime.mailbox

import raider.core.{JobId, RaiderError}
import raider.runtime.jobs.JobStatus

/** Typed mailbox failures (m1.md RAI-065, SEND-02).
  *
  * Own sealed family on the pattern of JobsError/EventError: `RaiderError` is
  * frozen core and its subtypes live in a single core file, so the conversion
  * is explicit (`toRaiderError`), never implicit. Every refusal is typed — the
  * sender always knows whether its message was accepted or refused; nothing is
  * silently dropped.
  */
sealed trait MailboxError extends Product with Serializable:
  def toRaiderError: RaiderError

object MailboxError:

  /** Bounded overflow: the child already holds `capacity` pending messages.
    * Typed refusal, never a silent loss (SEND-02); the sender may retry after
    * the next turn boundary frees capacity.
    */
  final case class Full(jobId: JobId, capacity: Int, pending: Int)
      extends MailboxError:

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(
        s"mailbox of ${jobId.value} full (capacity $capacity, pending $pending)"
      )

  /** Send into a terminal job is refused explicitly with the terminal status —
    * the caller is told to start a new turn (a fresh delegation) instead of
    * steering a dead child (SEND-02).
    */
  final case class TerminalJob(jobId: JobId, status: JobStatus.Terminal)
      extends MailboxError:

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(
        s"job ${jobId.value} already terminal ($status); send refused — start a new turn"
      )

  /** No mailbox exists for the job. Reserved for the wiring card that registers
    * per-child mailboxes at the delegation seam (RAI-065 non-goal here); the
    * family carries the case so wiring inherits one error type.
    */
  final case class UnknownJob(jobId: JobId) extends MailboxError:

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"unknown job ${jobId.value}")
