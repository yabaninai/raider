package raider.yabanin.doctor

import raider.yabanin.labels.LabelHeader
import raider.yabanin.labels.LabelSet
import zio.json.DeriveJsonDecoder
import zio.json.JsonDecoder

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

/** The Yabanin capability doctor (contract §3, `contracts/yabanin/README.md`).
  *
  * Issues exactly two plain GETs against the base URL — `/models` first, then
  * `/yabanin/session-keys` — classifies each endpoint, records the first
  * `X-Yabanin-Decision` header verbatim, and returns the report. The doctor
  * never spends tokens, never completes a session-key mint, never retries, and
  * never falls back to any direct provider route: `fallback_attempted` is
  * structurally false ([[DoctorReport]] carries no such field).
  *
  * Credential handling: when [[ProbeInput.credentialValue]] is present it is
  * attached to the outgoing requests only and is never written to the report,
  * the detail strings, or any log (contract §5: doctor reports are safe to
  * publish).
  */
object YabaninDoctor:

  /** The gateway decision header recorded as routing evidence. */
  val DecisionHeader: String = "X-Yabanin-Decision"

  /** Probe inputs.
    *
    * @param baseUrl
    *   the gateway base URL; expected to be already validated/normalized by
    *   `raider.yabanin.connection.GatewayUrl`. Trailing slashes are trimmed
    *   defensively here.
    * @param labels
    *   the canonical label set attached to both requests (rendered into the
    *   `X-Yabanin-Labels` header; omitted when the header is empty).
    * @param credentialValue
    *   the gateway credential value, resolved by the caller from the
    *   environment variable NAME reference (`YABANIN_API_KEY`). Attached to
    *   outgoing requests only; never recorded anywhere.
    * @param expectedDocsVersion
    *   the pinned models-catalog docs_version this contract snapshot was
    *   verified against (doctor-golden.json `pin`); a 200 response without a
    *   match classifies as `stale_models_docs`.
    */
  final case class ProbeInput(
      baseUrl: String,
      labels: LabelSet,
      credentialValue: Option[String],
      expectedDocsVersion: String,
      requestTimeoutMillis: Int = 10000
  )

  /** Runs the two probes and returns the report. Never throws: call-level
    * failures classify as `transport_failure` with a null HTTP status.
    */
  def probe(input: ProbeInput): DoctorReport =
    val base = input.baseUrl.reverse.dropWhile(_ == '/').reverse
    val client =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    try
      val (modelsProbe, modelsDecision) = classifyModels(
        call(client, base, "/models", input),
        input.expectedDocsVersion
      )
      val (sessionKeysProbe, sessionKeysDecision) = classifySessionKeys(
        call(client, base, "/yabanin/session-keys", input)
      )
      // First observation wins: models first, then session keys.
      val decision =
        modelsDecision.orElse(sessionKeysDecision).map { case (value, path) =>
          DecisionObservation(DecisionHeader, value, path)
        }
      DoctorReport(base, input.labels, modelsProbe, sessionKeysProbe, decision)
    finally client.close()

  /** Same-module classification hook for contract tests: classifies a synthetic
    * models response so status/body combinations the fixed two-call surface
    * cannot produce (e.g. a plain 400 without the `label_overflow` code) stay
    * verifiable against the contract table.
    */
  private[doctor] def testExposeClassifyModels(
      status: Int,
      body: String,
      expectedDocsVersion: String
  ): Capability =
    classifyModels(
      CallOutcome(Some(RawResponse(status, body, None, "/test")), None),
      expectedDocsVersion
    )._1.classification

  // -------------------------------------------------------------------
  // One HTTP call
  // -------------------------------------------------------------------

  private final case class RawResponse(
      status: Int,
      body: String,
      decisionValue: Option[String],
      path: String
  )

  private final case class CallOutcome(
      response: Option[RawResponse],
      failure: Option[String]
  ):

    def decision: Option[(String, String)] =
      response.flatMap(r => r.decisionValue.map(_ -> r.path))

  private def call(
      client: HttpClient,
      base: String,
      resource: String,
      input: ProbeInput
  ): CallOutcome =
    try
      val uri = URI.create(s"$base$resource")
      val builder = HttpRequest
        .newBuilder(uri)
        .timeout(Duration.ofMillis(input.requestTimeoutMillis.toLong))
        .header("Accept", "application/json")
        .GET()
      val header = input.labels.renderHeader
      if header.nonEmpty then builder.header(LabelHeader.HeaderName, header)
      input.credentialValue.filter(_.trim.nonEmpty).foreach { value =>
        builder.header("Authorization", s"Bearer $value")
      }
      val resp = client.send(
        builder.build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
      )
      val decision =
        resp.headers().firstValue(DecisionHeader).toScala.filter(_.nonEmpty)
      CallOutcome(
        Some(
          RawResponse(resp.statusCode(), resp.body(), decision, uri.getPath)
        ),
        None
      )
    catch case NonFatal(t) => CallOutcome(None, Some(t.getClass.getSimpleName))

  // -------------------------------------------------------------------
  // Classification (contract §3 table)
  // -------------------------------------------------------------------

  private val UnsupportedStatuses = Set(501, 404, 405)

  private def classifyModels(
      outcome: CallOutcome,
      expectedDocsVersion: String
  ): (ModelsProbe, Option[(String, String)]) =
    outcome match
      case CallOutcome(Some(r), _) =>
        val probe =
          r.status match
            case 401 =>
              ModelsProbe(
                Capability.AuthDenied,
                Some(401),
                Vector.empty,
                None,
                "the gateway refused authentication for the model catalog (401)"
              )
            case 403 =>
              ModelsProbe(
                Capability.Forbidden,
                Some(403),
                Vector.empty,
                None,
                "the gateway denied the model catalog by policy (403)"
              )
            case s if UnsupportedStatuses.contains(s) =>
              ModelsProbe(
                Capability.CapabilityUnsupported,
                Some(s),
                Vector.empty,
                None,
                s"the gateway does not expose the model catalog here (HTTP $s)"
              )
            case 400 if errorCodeOf(r.body).contains("label_overflow") =>
              ModelsProbe(
                Capability.LabelOverflow,
                Some(400),
                Vector.empty,
                None,
                "the gateway rejected the label header as overflowing (label_overflow)"
              )
            case 200 =>
              val (modelIds, docsVersion) = parseCatalog(r.body)
              docsVersion match
                case Some(v) if v == expectedDocsVersion =>
                  ModelsProbe(
                    Capability.Ok,
                    Some(200),
                    modelIds,
                    docsVersion,
                    s"model catalog matches the pinned docs_version $expectedDocsVersion"
                  )
                case _ =>
                  ModelsProbe(
                    Capability.StaleModelsDocs,
                    Some(200),
                    modelIds,
                    docsVersion,
                    "model catalog docs_version is missing or does not match the pinned expectation; model ids are still recorded"
                  )
            case s =>
              ModelsProbe(
                Capability.TransportFailure,
                Some(s),
                Vector.empty,
                None,
                s"unexpected models response (HTTP $s); classified as transport_failure"
              )
        (probe, outcome.decision)
      case CallOutcome(None, failure) =>
        (
          ModelsProbe(
            Capability.TransportFailure,
            None,
            Vector.empty,
            None,
            s"the models call failed below HTTP (${failure.getOrElse("no outcome recorded")}); classified as transport_failure"
          ),
          None
        )

  private def classifySessionKeys(
      outcome: CallOutcome
  ): (SessionKeysProbe, Option[(String, String)]) =
    outcome match
      case CallOutcome(Some(r), _) =>
        val probe =
          r.status match
            case 200 =>
              SessionKeysProbe(
                Capability.Ok,
                Some(200),
                "session-keys capability is exposed; the probe never completes a mint"
              )
            case 401 =>
              SessionKeysProbe(
                Capability.AuthDenied,
                Some(401),
                "the gateway refused authentication for session-keys (401)"
              )
            case 403 =>
              SessionKeysProbe(
                Capability.Forbidden,
                Some(403),
                "the gateway denied session-keys access by policy (403)"
              )
            case s if UnsupportedStatuses.contains(s) =>
              SessionKeysProbe(
                Capability.CapabilityUnsupported,
                Some(s),
                s"the gateway does not expose session-keys here (HTTP $s)"
              )
            case 400 if errorCodeOf(r.body).contains("label_overflow") =>
              SessionKeysProbe(
                Capability.LabelOverflow,
                Some(400),
                "the gateway rejected the label header as overflowing (label_overflow)"
              )
            case s =>
              SessionKeysProbe(
                Capability.TransportFailure,
                Some(s),
                s"unexpected session-keys response (HTTP $s); classified as transport_failure"
              )
        (probe, outcome.decision)
      case CallOutcome(None, failure) =>
        (
          SessionKeysProbe(
            Capability.TransportFailure,
            None,
            s"the session-keys call failed below HTTP (${failure.getOrElse("no outcome recorded")}); classified as transport_failure"
          ),
          None
        )

  // -------------------------------------------------------------------
  // Body parsing (tolerant: an unparseable body is still classified)
  // -------------------------------------------------------------------

  private final case class CatalogModel(id: String) derives JsonDecoder

  private final case class Catalog(
      data: Option[Vector[CatalogModel]],
      docs_version: Option[String]
  ) derives JsonDecoder

  private final case class ErrorCodeField(code: String) derives JsonDecoder

  private final case class ErrorBody(
      error: Option[ErrorCodeField],
      code: Option[String]
  ) derives JsonDecoder

  /** Parses the OpenAI-compatible catalog shape `data[].id` plus the
    * `docs_version` field. An unparseable body yields empty ids and no version;
    * classification still applies.
    */
  private def parseCatalog(body: String): (Vector[String], Option[String]) =
    summon[JsonDecoder[Catalog]].decodeJson(body) match
      case Left(_) => (Vector.empty, None)
      case Right(catalog) =>
        val ids = catalog.data.getOrElse(Vector.empty).map(_.id)
        (ids, catalog.docs_version)

  /** Extracts the body error code for the 400 `label_overflow` classification
    * (`{"error":{"code":"label_overflow"}}` or a top-level `{"code":...}`).
    */
  private def errorCodeOf(body: String): Option[String] =
    summon[JsonDecoder[ErrorBody]].decodeJson(body) match
      case Left(_)       => None
      case Right(parsed) => parsed.error.map(_.code).orElse(parsed.code)
