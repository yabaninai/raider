package raider.core

/** Structured error family — all 18 families of runtime-contracts §14
  * (Configuration … CapabilityUnsupported, Compilation, ProviderBudget,
  * ToolOutcomeUncertain, LocalBudgetExceeded, ChildFailed, JournalFailure).
  *
  * Every error carries a stable machine code, a public explanation slot and a
  * retryability flag; provider bodies/secrets never enter `detail`. Domain
  * failure, defect and interruption stay distinguishable via the sub-families.
  */
sealed trait RaiderError extends RuntimeException:
  def code: String
  def retryable: Boolean
  def detail: String

object RaiderError:
  final case class Configuration(what: String) extends RaiderError:
    val code = "RA-CFG"; val retryable = false; val detail = what

  final case class InputValidation(what: String) extends RaiderError:
    val code = "RA-INP"; val retryable = false; val detail = what

  final case class OutputValidation(what: String) extends RaiderError:
    val code = "RA-OUT"; val retryable = false; val detail = what

  final case class ProviderAuth(what: String) extends RaiderError:
    val code = "RA-AUTH"; val retryable = false; val detail = what

  final case class ProviderRateLimit(what: String) extends RaiderError:
    val code = "RA-RATE"; val retryable = true; val detail = what

  final case class ProviderUnavailable(what: String) extends RaiderError:
    val code = "RA-UNAVAIL"; val retryable = true; val detail = what

  final case class StreamProtocol(what: String) extends RaiderError:
    val code = "RA-STREAM"; val retryable = false; val detail = what

  final case class ToolDenied(what: String) extends RaiderError:
    val code = "RA-TOOLDENY"; val retryable = false; val detail = what

  final case class ToolFailed(what: String) extends RaiderError:
    val code = "RA-TOOLFAIL"; val retryable = false; val detail = what

  final case class DeadlineExceeded(what: String) extends RaiderError:
    val code = "RA-DEADLINE"; val retryable = false; val detail = what

  final case class Cancelled(what: String) extends RaiderError:
    val code = "RA-CANCEL"; val retryable = false; val detail = what

  final case class CapabilityUnsupported(what: String) extends RaiderError:
    val code = "RA-CAP"; val retryable = false; val detail = what

  final case class Compilation(what: String) extends RaiderError:
    val code = "RA-COMPILE"; val retryable = false; val detail = what

  final case class ProviderBudget(what: String) extends RaiderError:
    val code = "RA-PBUDGET"; val retryable = false; val detail = what

  final case class ToolOutcomeUncertain(what: String) extends RaiderError:
    val code = "RA-TOOLUNCERT"; val retryable = false; val detail = what

  final case class LocalBudgetExceeded(what: String) extends RaiderError:
    val code = "RA-LBUDGET"; val retryable = false; val detail = what

  final case class ChildFailed(what: String) extends RaiderError:
    val code = "RA-CHILD"; val retryable = false; val detail = what

  final case class JournalFailure(what: String) extends RaiderError:
    val code = "RA-JOURNAL"; val retryable = false; val detail = what
