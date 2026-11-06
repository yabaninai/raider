package raider.backend.opencode

/** Self-contained typed error family for the OpenCode backend adapter
  * (RAI-035). The module deliberately does not depend on raider.core, so this
  * family mirrors the RaiderError families that a later integration card will
  * map 1:1 (OC-CFG~RA-CFG, OC-AUTH~RA-AUTH, OC-UNAVAIL~RA-UNAVAIL,
  * OC-STREAM~RA-STREAM, OC-INP~RA-INP, OC-OUT~RA-OUT, OC-DEADLINE~RA-DEADLINE,
  * OC-CANCEL~RA-CANCEL, OC-TOOLUNCERT~RA-TOOLUNCERT).
  *
  * Rules carried over from the runtime contract:
  *   - error details are structured and never contain credentials;
  *   - server response bodies are truncated before embedding;
  *   - ambiguous mutation outcomes are ToolOutcomeUncertain and are never
  *     retried automatically (no repeated mutating calls after an ambiguous
  *     result).
  */
abstract class OcError(val code: String, val retryable: Boolean, val detail: String)
    extends RuntimeException(s"$code: $detail", null, false, false):
  override def getMessage: String = s"$code: $detail"

object OcError:
  /** Bad local configuration (bad base URL, unusable server version, refused
    * lifecycle). Blocks launch.
    */
  final case class Configuration(override val detail: String)
      extends OcError("OC-CFG", retryable = false, detail)

  /** Malformed local request arguments, or a definitive server rejection of a
    * request (HTTP 400/404).
    */
  final case class InputValidation(override val detail: String)
      extends OcError("OC-INP", retryable = false, detail)

  /** Server response did not decode against the pinned wire shapes.
    */
  final case class OutputValidation(override val detail: String)
      extends OcError("OC-OUT", retryable = false, detail)

  /** Server rejected credentials (HTTP 401/403). Detail never carries the
    * credential values.
    */
  final case class ProviderAuth(override val detail: String)
      extends OcError("OC-AUTH", retryable = true, detail)

  /** Transport-level failure with a definite non-delivery or a server-side
    * outage (connection refused, HTTP 5xx, stream read failure).
    */
  final case class ProviderUnavailable(override val detail: String)
      extends OcError("OC-UNAVAIL", retryable = true, detail)

  /** The event stream violated the SSE/pinned-event contract (bad frame,
    * undecodable event, wrong content type).
    */
  final case class StreamProtocol(override val detail: String)
      extends OcError("OC-STREAM", retryable = true, detail)

  /** A caller-provided deadline was exhausted.
    */
  final case class DeadlineExceeded(override val detail: String)
      extends OcError("OC-DEADLINE", retryable = false, detail)

  /** The caller cancelled the waiting operation.
    */
  final case class Cancelled(override val detail: String)
      extends OcError("OC-CANCEL", retryable = false, detail)

  /** A mutating request may or may not have been processed by the server.
    * Never retried automatically; carries the identifiers needed for manual or
    * read-only reconciliation.
    */
  final case class ToolOutcomeUncertain(override val detail: String)
      extends OcError("OC-TOOLUNCERT", retryable = false, detail)

  /** A declared-but-unimplemented capability was requested.
    */
  final case class CapabilityUnsupported(override val detail: String)
      extends OcError("OC-CAPS", retryable = false, detail)
