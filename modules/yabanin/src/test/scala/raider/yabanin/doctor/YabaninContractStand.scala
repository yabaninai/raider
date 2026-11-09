package raider.yabanin.doctor

import raider.yabanin.labels.LabelHeader
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/** Scenario knobs for the deterministic Yabanin gateway contract stand
  * (RAI-037). Names map 1:1 to the `scenarios` in
  * `contracts/yabanin/expected/doctor-golden.json`.
  */
enum YabaninScenario:
  case DoctorOk
  case AuthDenied
  case Forbidden
  case ModelsUnsupported
  case SessionKeysUnsupported
  case StaleDocs
  case LabelOverflow

/** Deterministic loopback HTTP stand mirroring the pinned Yabanin gateway
  * doctor surface (`contracts/yabanin/README.md` §3): `GET /gw/v1/models` and
  * `GET /gw/v1/yabanin/session-keys` under a fixed path prefix. Bound to an
  * OS-assigned loopback port; `stop()` on release.
  *
  * Every scenario serves the decision header `X-Yabanin-Decision` per the
  * golden expectations; scenarios where the models response already carries the
  * decision also set a *different* decision value on the session-keys response,
  * so the "first observation wins" rule (models, then session keys) is actually
  * observable in the doctor's record.
  *
  * Request logging records the method, path, label header value, and an
  * authorization *presence flag* only — no header values, no credentials
  * (contract §5: doctor reports and stand logs are safe to publish).
  */
final class YabaninContractStand private (
    server: HttpServer,
    val port: Int,
    scenario: YabaninScenario,
    executor: java.util.concurrent.ExecutorService
):

  /** Base URL including the pinned `/gw/v1` path prefix. */
  def baseUrl: String = s"http://127.0.0.1:$port/gw/v1"

  final case class RequestLog(
      method: String,
      path: String,
      labelsHeader: Option[String],
      hasAuth: Boolean
  )

  private val requestLog = new java.util.Vector[RequestLog]()

  def requests: Vector[RequestLog] =
    val b = Vector.newBuilder[RequestLog]
    val it = requestLog.iterator()
    while it.hasNext do b += it.next()
    b.result()

  private def decisionValue: String = "route=yb-flash policy=default"
  private def secondaryDecision: String = "route=sk-echo policy=default"

  private val ModelsOkBody =
    """{"object":"list","data":[{"id":"yb-flash","object":"model"},{"id":"yb-worker","object":"model"}],"docs_version":"2026-11-01"}"""

  private val ModelsStaleBody =
    """{"object":"list","data":[{"id":"yb-flash","object":"model"},{"id":"yb-worker","object":"model"}],"docs_version":"2025-01-01"}"""

  private val AuthBody =
    """{"error":{"code":"unauthorized","message":"authentication required"}}"""

  private val ForbiddenBody =
    """{"error":{"code":"forbidden","message":"policy denies this capability"}}"""

  private val UnsupportedBody =
    """{"error":{"code":"not_implemented","message":"endpoint not exposed here"}}"""

  private val LabelOverflowBody =
    """{"error":{"code":"label_overflow","message":"label header exceeds the effective cap"}}"""

  private val SessionKeysBody =
    """{"capability":"session-keys","mintable":false}"""

  private def modelsDecision: Option[String] = scenario match
    case YabaninScenario.AuthDenied | YabaninScenario.Forbidden |
        YabaninScenario.ModelsUnsupported | YabaninScenario.LabelOverflow =>
      None
    case _ => Some(decisionValue)

  private def sessionKeysDecision: Option[String] = scenario match
    case YabaninScenario.AuthDenied | YabaninScenario.Forbidden |
        YabaninScenario.SessionKeysUnsupported =>
      None
    case _ => Some(secondaryDecision)

  private def respond(
      ex: HttpExchange,
      status: Int,
      body: String,
      decision: Option[String]
  ): Unit =
    decision.foreach(v =>
      ex.getResponseHeaders.add(YabaninDoctor.DecisionHeader, v)
    )
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(
      status,
      if bytes.isEmpty then -1 else bytes.length.toLong
    )
    if bytes.nonEmpty then ex.getResponseBody.write(bytes)
    ex.close()

  private def handleModels(ex: HttpExchange): Unit =
    requestLog.add(
      RequestLog(
        ex.getRequestMethod,
        ex.getRequestURI.getPath,
        Option(ex.getRequestHeaders.getFirst(LabelHeader.HeaderName))
          .filter(_.nonEmpty),
        Option(ex.getRequestHeaders.getFirst("Authorization")).isDefined
      )
    )
    scenario match
      case YabaninScenario.AuthDenied => respond(ex, 401, AuthBody, None)
      case YabaninScenario.Forbidden  => respond(ex, 403, ForbiddenBody, None)
      case YabaninScenario.ModelsUnsupported =>
        respond(ex, 501, UnsupportedBody, None)
      case YabaninScenario.LabelOverflow =>
        respond(ex, 400, LabelOverflowBody, None)
      case YabaninScenario.StaleDocs =>
        respond(ex, 200, ModelsStaleBody, modelsDecision)
      case YabaninScenario.SessionKeysUnsupported | YabaninScenario.DoctorOk =>
        respond(ex, 200, ModelsOkBody, modelsDecision)

  private def handleSessionKeys(ex: HttpExchange): Unit =
    requestLog.add(
      RequestLog(
        ex.getRequestMethod,
        ex.getRequestURI.getPath,
        Option(ex.getRequestHeaders.getFirst(LabelHeader.HeaderName))
          .filter(_.nonEmpty),
        Option(ex.getRequestHeaders.getFirst("Authorization")).isDefined
      )
    )
    scenario match
      case YabaninScenario.AuthDenied => respond(ex, 401, AuthBody, None)
      case YabaninScenario.Forbidden  => respond(ex, 403, ForbiddenBody, None)
      case YabaninScenario.SessionKeysUnsupported =>
        respond(ex, 501, UnsupportedBody, None)
      case _ => respond(ex, 200, SessionKeysBody, sessionKeysDecision)

  def stop(): Unit =
    server.stop(0)
    // The fixed pool is non-daemon; without an explicit shutdown the JVM
    // would not exit after the test run.
    executor.shutdownNow()

object YabaninContractStand:

  /** Starts a stand for `scenario` bound to an OS-assigned loopback port. */
  def start(scenario: YabaninScenario): YabaninContractStand =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newFixedThreadPool(4)
    val stand = new YabaninContractStand(
      server,
      server.getAddress.getPort,
      scenario,
      executor
    )
    server.createContext("/gw/v1/models", ex => stand.handleModels(ex))
    server.createContext(
      "/gw/v1/yabanin/session-keys",
      ex => stand.handleSessionKeys(ex)
    )
    server.setExecutor(executor)
    server.start()
    stand
