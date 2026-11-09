package raider.yabanin.doctor

import raider.yabanin.labels.*
import zio.Scope
import zio.ZIO
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*
import raider.yabanin.doctor.DoctorReportDocument.given

import java.net.InetAddress
import java.net.ServerSocket

/** Capability doctor contract coverage (RAI-037, contract §3, YBCON-02). Runs
  * the real doctor (real loopback sockets via java.net.http) against the
  * deterministic `YabaninContractStand` for every scenario pinned in
  * `expected/doctor-golden.json`, asserting classifications, statuses, decision
  * evidence, fallback_attempted = false, the canonical labels header on the
  * wire, exact request discipline, and report hygiene (no credential material
  * in the encoded document).
  */
object DoctorSpec extends ZIOSpecDefault:

  /** Fixture credential value; never a real credential, and asserted absent
    * from every encoded report.
    */
  private val FixtureCredential = "yb-fixture-doctor-credential"

  private def probeStand(
      scenario: YabaninScenario
  ): (YabaninContractStand, DoctorReport) =
    val golden = Goldens.doctor
    val stand = YabaninContractStand.start(scenario)
    try
      val labels = LabelHeader.parseRaw(
        golden.pin.labels_fixture_raw,
        LabelLimits.Default
      ) match
        case Right(pairs) =>
          LabelHeader.fromWrapper(
            pairs.map(l => (l.key, l.value)),
            LabelLimits.Default
          )
        case Left(err) =>
          throw new IllegalStateException(
            s"pin labels do not decode: ${err.message}"
          )
      val input = YabaninDoctor.ProbeInput(
        baseUrl = stand.baseUrl,
        labels = labels,
        credentialValue = Some(FixtureCredential),
        expectedDocsVersion = golden.pin.expected_models_docs_version
      )
      val report = YabaninDoctor.probe(input)
      (stand, report)
    finally stand.stop()

  private def assertScenario(
      scenario: YabaninScenario,
      name: String
  ): TestResult =
    val golden = Goldens.doctor
    val pin = golden.pin
    val pinned = golden.scenarios
      .find(_.name == name)
      .getOrElse(
        throw new IllegalStateException(
          s"scenario $name missing from doctor-golden.json"
        )
      )
    val want = pinned.expected
    val (stand, report) = probeStand(scenario)

    val modelsOk =
      if report.models.classification.encode == want.models.classification then
        assertTrue(true)
      else
        assertTrue(
          s"$name: models = ${report.models.classification.encode}, want ${want.models.classification}" == ""
        )
    val statusOk =
      if report.models.httpStatus.contains(want.models.http_status) then
        assertTrue(true)
      else
        assertTrue(
          s"$name: models status = ${report.models.httpStatus}, want ${want.models.http_status}" == ""
        )
    val idsOk = want.models.model_ids match
      case Some(ids) => assertTrue(report.models.modelIds == ids)
      case None      => assertTrue(report.models.modelIds.isEmpty)
    val docsOk = assertTrue(
      report.models.docsVersion == want.models.docs_version
    )

    val skOk =
      if report.sessionKeys.classification.encode == want.session_keys.classification
      then assertTrue(true)
      else
        assertTrue(
          s"$name: session_keys = ${report.sessionKeys.classification.encode}, want ${want.session_keys.classification}" == ""
        )
    val skStatusOk = assertTrue(
      report.sessionKeys.httpStatus.contains(want.session_keys.http_status)
    )

    val decisionOk = want.decision match
      case Some(d) =>
        report.decision match
          case Some(observed) =>
            if observed.header == golden.decision_header && observed.value == d.value && observed.observedOn == d.observed_on
            then assertTrue(true)
            else
              assertTrue(
                s"$name: decision = (${observed.header}, ${observed.value}, ${observed.observedOn}); want (${d.value}, ${d.observed_on})" == ""
              )
          case None =>
            assertTrue(s"$name: decision missing, want ${d.value}" == "")
      case None =>
        report.decision match
          case None => assertTrue(true)
          case Some(o) =>
            assertTrue(s"$name: unexpected decision ${o.value}" == "")

    // Wire discipline: exactly two GETs, models first, session keys second;
    // the canonical sorted labels header on both; auth present. This is the
    // observable no-fallback fact: the doctor contacted only these two
    // gateway endpoints and nothing else.
    val reqs = stand.requests
    val expectedLabelsHeader = pin.expected_labels_header
    val wireOk =
      assertTrue(reqs.map(_.method).forall(_ == "GET")) &&
        assertTrue(
          reqs.map(_.path) == Vector(
            "/gw/v1/models",
            "/gw/v1/yabanin/session-keys"
          )
        ) &&
        assertTrue(reqs.size == 2) &&
        assertTrue(
          reqs.forall(_.labelsHeader.contains(expectedLabelsHeader))
        ) &&
        assertTrue(reqs.forall(_.hasAuth))

    // Report hygiene: the credential value never appears in the encoded
    // report; fallback_attempted is the structural constant false.
    val json =
      summon[JsonEncoder[DoctorReport]].encodeJson(report, None).toString
    val docFields = DoctorReportDocument.toAst(report) match
      case Json.Obj(fields) => fields.map(_._1).toVector
      case _                => Vector.empty
    val hygieneOk =
      assertTrue(!json.contains(FixtureCredential)) &&
        assertTrue(json.contains("\"fallback_attempted\":false")) &&
        assertTrue(
          docFields == Vector(
            "base_url",
            "labels",
            "models",
            "session_keys",
            "decision",
            "fallback_attempted"
          )
        ) &&
        assertTrue(json.contains(s"\"base_url\":\"${stand.baseUrl}\""))

    assertTrue(baseUrlOf(report) == stand.baseUrl) &&
    modelsOk && statusOk && idsOk && docsOk && skOk && skStatusOk && decisionOk && wireOk && hygieneOk

  private def baseUrlOf(report: DoctorReport): String = report.baseUrl

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Doctor")(
    test(
      "doctor-golden labels fixture parses and renders the pinned canonical header"
    ) {
      val golden = Goldens.doctor
      LabelHeader.parseRaw(
        golden.pin.labels_fixture_raw,
        LabelLimits.Default
      ) match
        case Right(pairs) =>
          val set = LabelHeader.fromWrapper(
            pairs.map(l => (l.key, l.value)),
            LabelLimits.Default
          )
          assertTrue(set.renderHeader == golden.pin.expected_labels_header)
        case Left(err) =>
          assertTrue(s"pin labels unparsable: ${err.message}" == "")
    },
    test("doctor-ok: catalog ok, decision recorded from models first") {
      ZIO.attemptBlocking(assertScenario(YabaninScenario.DoctorOk, "doctor-ok"))
    },
    test("doctor-auth-denied: 401 distinguished, decision null, no fallback") {
      ZIO.attemptBlocking(
        assertScenario(YabaninScenario.AuthDenied, "doctor-auth-denied")
      )
    },
    test("doctor-forbidden: 403 distinguished, decision null, no fallback") {
      ZIO.attemptBlocking(
        assertScenario(YabaninScenario.Forbidden, "doctor-forbidden")
      )
    },
    test("doctor-models-unsupported: 501 on models, session-keys still ok") {
      ZIO.attemptBlocking(
        assertScenario(
          YabaninScenario.ModelsUnsupported,
          "doctor-models-unsupported"
        )
      )
    },
    test("doctor-session-keys-unsupported: 501 on session-keys, models ok") {
      ZIO.attemptBlocking(
        assertScenario(
          YabaninScenario.SessionKeysUnsupported,
          "doctor-session-keys-unsupported"
        )
      )
    },
    test(
      "doctor-stale-docs: docs_version mismatch is stale_models_docs with ids recorded"
    ) {
      ZIO.attemptBlocking(
        assertScenario(YabaninScenario.StaleDocs, "doctor-stale-docs")
      )
    },
    test(
      "doctor-label-overflow: 400 label_overflow distinguished from other 400s"
    ) {
      ZIO.attemptBlocking(
        assertScenario(YabaninScenario.LabelOverflow, "doctor-label-overflow")
      )
    },
    test(
      "call-level failure below HTTP classifies transport_failure with null status"
    ) {
      ZIO.attemptBlocking {
        // Grab an OS-assigned port and release it: nothing listens there.
        val socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = socket.getLocalPort
        socket.close()
        val report = YabaninDoctor.probe(
          YabaninDoctor.ProbeInput(
            s"http://127.0.0.1:$port",
            LabelSet.empty,
            None,
            Goldens.doctor.pin.expected_models_docs_version
          )
        )
        val json =
          summon[JsonEncoder[DoctorReport]].encodeJson(report, None).toString
        assertTrue(
          report.models.classification == Capability.TransportFailure
        ) &&
        assertTrue(report.models.httpStatus.isEmpty) &&
        assertTrue(
          report.sessionKeys.classification == Capability.TransportFailure
        ) &&
        assertTrue(report.sessionKeys.httpStatus.isEmpty) &&
        assertTrue(json.contains("\"http_status\":null")) &&
        assertTrue(json.contains("\"decision\":null")) &&
        assertTrue(json.contains("\"fallback_attempted\":false"))
      }
    },
    test(
      "a plain 400 without the label_overflow code is transport_failure, not label_overflow"
    ) {
      ZIO.attemptBlocking {
        // The classification table folds "anything else" into
        // transport_failure; a generic 400 body must not be misread as the
        // overflow capability. The fixed two-call stand surface cannot serve
        // this combination, so the same-module hook classifies it directly.
        val outcome = YabaninDoctor.testExposeClassifyModels(
          400,
          """{"error":{"code":"bad_request","message":"generic"}}""",
          Goldens.doctor.pin.expected_models_docs_version
        )
        assertTrue(outcome == Capability.TransportFailure)
      }
    }
  )
