package raider.yabanin.doctor

/** Classification of one doctor probe endpoint (contract §3 and the
  * `classification` enum in `contracts/yabanin/doctor-report.schema.json`).
  */
enum Capability:
  case Ok
  case AuthDenied
  case Forbidden
  case CapabilityUnsupported
  case StaleModelsDocs
  case LabelOverflow
  case TransportFailure

  /** Wire spelling used in contract documents. */
  def encode: String = this match
    case Ok                    => "ok"
    case AuthDenied            => "auth_denied"
    case Forbidden             => "forbidden"
    case CapabilityUnsupported => "capability_unsupported"
    case StaleModelsDocs       => "stale_models_docs"
    case LabelOverflow         => "label_overflow"
    case TransportFailure      => "transport_failure"

object Capability:

  def decode(s: String): Option[Capability] =
    Capability.values.find(_.encode == s)
