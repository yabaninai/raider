package raider.yabanin.doctor

import raider.yabanin.labels.LabelSet

/** Classification result for `GET {base}/models` (`doctor-report.schema.json`,
  * property `models`).
  */
final case class ModelsProbe(
    classification: Capability,
    /** Observed HTTP status, or `None` when the call failed below HTTP. */
    httpStatus: Option[Int],
    /** Model ids from the catalog body when one was parseable; otherwise empty.
      */
    modelIds: Vector[String],
    /** docs_version reported by the catalog when present. */
    docsVersion: Option[String],
    /** Short explanation; never contains credential values. */
    detail: String
)

/** Classification result for `GET {base}/yabanin/session-keys`
  * (`doctor-report.schema.json`, property `session_keys`). The probe never
  * completes a mint.
  */
final case class SessionKeysProbe(
    classification: Capability,
    httpStatus: Option[Int],
    detail: String
)

/** The first gateway decision header observed, stored verbatim as routing
  * evidence. The doctor does not interpret or act on it (contract §3).
  */
final case class DecisionObservation(
    /** Fixed to the contract decision header name. */
    header: String,
    /** Header value verbatim. */
    value: String,
    /** URL path of the response the header was observed on. */
    observedOn: String
)

/** Structured doctor report (`contracts/yabanin/doctor-report.schema.json`).
  *
  * `fallback_attempted` is not a report field: it is structurally false. An
  * auth or policy refusal is a terminal capability fact, and there is no hidden
  * direct route to any other provider after a gateway refusal;
  * `DoctorReportDocument` emits the constant `false`.
  */
final case class DoctorReport(
    /** The probed base URL, trailing slashes trimmed. */
    baseUrl: String,
    /** The label set the doctor attached to its requests. */
    labels: LabelSet,
    models: ModelsProbe,
    sessionKeys: SessionKeysProbe,
    /** First decision header observed (models, then session keys), if any. */
    decision: Option[DecisionObservation]
)
