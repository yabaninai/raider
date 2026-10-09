package raider.runtime.events

import raider.core.{JobId, RaiderError}

/** Typed event-seam failures (ADR-024 §5).
  *
  * Own sealed family on purpose: `RaiderError` is frozen core and its subtypes
  * live in a single core file, so new runtime error subtypes cannot extend it.
  * The conversion to `RaiderError` is explicit (`toRaiderError`), never
  * implicit.
  */
sealed trait EventError:
  def toRaiderError: RaiderError

object EventError:

  /** Reliable-buffer overflow: bounded wait exceeded, typed — never a silent
    * loss (ADR-024 §2.3).
    */
  final case class Backpressure(jobId: JobId, cap: Int) extends EventError:

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(
        s"event backpressure for ${jobId.value} (reliable cap $cap)"
      )

  /** Malformed event stream: seq regression, event after terminal, unknown wire
    * value — the reducer refuses to guess.
    */
  final case class Malformed(msg: String) extends EventError:
    def toRaiderError: RaiderError = RaiderError.InputValidation(msg)
