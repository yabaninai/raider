package raider.runtime.jobs

import raider.core.RaiderError

/** Typed registry errors (RAI-007 JOB-02). */
sealed trait JobsError extends Product with Serializable:
  def toRaiderError: RaiderError

object JobsError:

  final case class UnknownJob(id: raider.core.JobId) extends JobsError:

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"unknown job ${id.value}")

  final case class InvalidTransition(
      id: raider.core.JobId,
      from: JobStatus,
      to: JobStatus
  ) extends JobsError:

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(
        s"illegal job transition ${id.value}: $from -> $to"
      )

  final case class AlreadyTerminal(id: raider.core.JobId, status: JobStatus)
      extends JobsError:

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(
        s"job ${id.value} already terminal ($status); completion recorded as late observation"
      )
