package raider.backend.opencode

import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

import zio.ZIO
import zio.Scope

/** Thin synchronous wrapper over the JDK java.net.http.HttpClient (no extra
 * dependencies), shared by the backend calls and the event stream. All
 * blocking work runs on the ZIO blocking pool; per-call timeouts come from
 * [[OcConfig]]. Request headers (which may carry Authorization) are never
 * logged or echoed.
 *
 * Error policy:
 *   - connection refused / connect timeout: definite non-delivery ->
 *     ProviderUnavailable;
 *   - response timeout or IO failure on a mutating call: delivery state
 *     unknown -> ToolOutcomeUncertain (never retried automatically);
 *   - HTTP 401/403 -> ProviderAuth; 400/404 -> InputValidation; 5xx ->
 *     ProviderUnavailable.
 */
final class OcHttp(cfg: OcConfig):

  final case class OcResponse(status: Int, contentType: String, body: String)

  final case class OcEventStream(status: Int, contentType: String, body: InputStream)

  private val client: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
      // Redirects are never followed automatically: a transparent 307/308
      // redirect would make the JDK silently re-send an identical mutation
      // (POST prompt_async / abort) to the redirect target, which violates
      // the adapter's no-mutation-replay discipline. Any 3xx therefore
      // surfaces as a typed status error instead.
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  private[opencode] def buildUri(path: String, extraQuery: Option[String]): URI =
    val dirQ = cfg.directory.map(d => "directory=" + OcConfig.urlEncode(d))
    val query = (dirQ, extraQuery) match
      case (Some(d), Some(e)) => Some(d + "&" + e)
      case (Some(d), None)    => Some(d)
      case (None, e)          => e
    URI.create(cfg.rootUrl + path + query.map(q => "?" + q).getOrElse(""))

  private def newRequestBuilder(
      method: String,
      uri: URI,
      accept: Option[String],
      jsonBody: Option[String],
      callTimeout: Option[Duration],
  ): HttpRequest.Builder =
    val b = HttpRequest.newBuilder().uri(uri)
    accept.foreach(a => b.header("Accept", a))
    cfg.auth.foreach(a =>
      b.header("Authorization", "Basic " + Base64.getEncoder.encodeToString((a.username + ":" + a.password).getBytes("UTF-8")))
    )
    jsonBody.foreach(body =>
      b.header("Content-Type", "application/json")
      b.method(method, HttpRequest.BodyPublishers.ofString(body))
    )
    if jsonBody.isEmpty then b.method(method, HttpRequest.BodyPublishers.noBody())
    callTimeout.foreach(b.timeout)
    b

  private def transportError(t: Throwable, mutation: Boolean): OcError =
    t match
      case _: HttpConnectTimeoutException =>
        OcError.ProviderUnavailable("connection attempt failed before delivery (connect timeout)")
      case _: ConnectException =>
        OcError.ProviderUnavailable("connection refused before delivery")
      case _: HttpTimeoutException =>
        if mutation
        then OcError.ToolOutcomeUncertain("no response within call timeout after sending; outcome unknown")
        else OcError.ProviderUnavailable("no response within call timeout")
      case other =>
        if mutation
        then OcError.ToolOutcomeUncertain(
          "transport failure after send; outcome unknown (" + other.getClass.getSimpleName + ")"
        )
        else OcError.ProviderUnavailable("transport failure (" + other.getClass.getSimpleName + ")")

  private[opencode] def statusError(status: Int, body: String, mutation: Boolean): OcError =
    status match
      case 401 | 403 =>
        OcError.ProviderAuth(s"server rejected credentials (HTTP $status)")
      case 400 | 404 =>
        val msg = serverErrorMessage(body).map(m => ": " + truncate(m)).getOrElse("")
        OcError.InputValidation(s"server rejected request (HTTP $status)$msg")
      case s if s >= 500 =>
        OcError.ProviderUnavailable(s"server error (HTTP $status)")
      case s =>
        if mutation
        then OcError.ToolOutcomeUncertain(s"unexpected status $status after mutation; outcome unknown")
        else OcError.ProviderUnavailable(s"unexpected HTTP status $status")

  private def send(request: HttpRequest, mutation: Boolean): ZIO[Any, OcError, HttpResponse[String]] =
    ZIO
      .attemptBlocking(client.send(request, HttpResponse.BodyHandlers.ofString()))
      .mapError(e => transportError(e, mutation))

  private[opencode] def truncate(s: String, max: Int = 200): String =
    if s.length <= max then s else s.take(max) + "..."

  def decodeBody[A](r: OcResponse)(using dec: zio.json.JsonDecoder[A]): ZIO[Any, OcError, A] =
    ZIO.fromEither(dec.decodeJson(r.body)).mapError { e =>
      OcError.OutputValidation("server response does not decode against the pinned shapes: " + truncate(e))
    }

  def get(path: String, extraQuery: Option[String] = None): ZIO[Any, OcError, OcResponse] =
    val request = newRequestBuilder("GET", buildUri(path, extraQuery), None, None, Some(Duration.ofMillis(cfg.callTimeoutMs))).build()
    send(request, mutation = false).flatMap(checkOk(mutation = false))

  def post(
      path: String,
      extraQuery: Option[String] = None,
      json: Option[String] = None,
      mutation: Boolean = false,
  ): ZIO[Any, OcError, OcResponse] =
    val request = newRequestBuilder(
      "POST",
      buildUri(path, extraQuery),
      None,
      json,
      Some(Duration.ofMillis(cfg.callTimeoutMs)),
    ).build()
    send(request, mutation = mutation).flatMap(checkOk(mutation = mutation))

  private def checkOk(mutation: Boolean)(r: HttpResponse[String]): ZIO[Any, OcError, OcResponse] =
    if r.statusCode() >= 200 && r.statusCode() < 300 then
      ZIO.succeed(
        OcResponse(
          r.statusCode(),
          r.headers().firstValue("content-type").orElse(""),
          r.body(),
        )
      )
    else ZIO.fail(statusError(r.statusCode(), r.body(), mutation))

  /** Opens the shared global event stream (GET /global/event). The caller
   * owns the returned InputStream and must close it (typically via a scope
   * finalizer). No call timeout is applied: the stream is long-lived; idle
   * supervision is the caller's job.
   */
  def openEventStream(): ZIO[Scope, OcError, OcEventStream] =
    val request = newRequestBuilder(
      "GET",
      buildUri("/global/event", None),
      Some("text/event-stream"),
      None,
      None,
    ).build()
    ZIO
      .attemptBlocking(client.send(request, HttpResponse.BodyHandlers.ofInputStream()))
      .mapError(e => transportError(e, mutation = false))
      .flatMap { r =>
        if r.statusCode() != 200 then
          val status = r.statusCode()
          ZIO
            .attemptBlocking(r.body().close())
            .ignore
            .zipRight(ZIO.fail(statusError(status, "", mutation = false)))
        else
          ZIO.succeed(
            OcEventStream(
              r.statusCode(),
              r.headers().firstValue("content-type").orElse(""),
              r.body(),
            )
          )
      }
