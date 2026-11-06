package raider.backend.opencode

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Credentials for the OpenCode server's HTTP basic auth. The password is
  * supplied by the caller (env/helper reference) and is never logged, never
  * written to error details, and never echoed in tool output.
  */
final case class OcAuth(username: String, password: String)

object OcAuth:
  /** Default username per the `opencode serve` docs is `opencode`; callers
    * normally only supply the password.
    */
  def withPassword(password: String): OcAuth = OcAuth("opencode", password)

/** Lifecycle declaration for the adapter.
  */
sealed trait OcLifecycle
object OcLifecycle:
  /** Adapter attaches to an already-running OpenCode server. This is the only
    * lifecycle implemented by RAI-035; the adapter never starts and never
    * stops an `opencode serve` process.
    */
  case object External extends OcLifecycle

  /** Explicit marker for an adapter-owned `opencode serve` process. Declared
    * so the lifecycle distinction is visible in the type surface; a config
    * with this lifecycle is rejected by [[OcConfig.make]] until a separate
    * card implements process ownership.
    */
  final case class Owned(command: Vector[String]) extends OcLifecycle

/** Connection configuration for the OpenCode HTTP server adapter.
  */
final case class OcConfig(
    baseUrl: String,
    directory: Option[String],
    auth: Option[OcAuth],
    connectTimeoutMs: Long,
    callTimeoutMs: Long,
    eventIdleTimeoutMs: Long,
    settleDeadlineMs: Long,
    reconnectAttempts: Int,
    maxEventFrameBytes: Int,
    lifecycle: OcLifecycle,
):
  /** Base URL without a trailing slash; request URIs are built from it. */
  lazy val rootUrl: String = baseUrl.stripSuffix("/")

object OcConfig:

  val defaultConnectTimeoutMs: Long = 5000L
  val defaultCallTimeoutMs: Long = 10000L
  val defaultEventIdleTimeoutMs: Long = 15000L
  val defaultSettleDeadlineMs: Long = 60000L
  val defaultReconnectAttempts: Int = 3
  val defaultMaxEventFrameBytes: Int = 8 * 1024 * 1024

  /** Validates and builds a config. Fails typed; never throws.
    */
  def make(
      baseUrl: String,
      directory: Option[String] = None,
      auth: Option[OcAuth] = None,
      connectTimeoutMs: Long = defaultConnectTimeoutMs,
      callTimeoutMs: Long = defaultCallTimeoutMs,
      eventIdleTimeoutMs: Long = defaultEventIdleTimeoutMs,
      settleDeadlineMs: Long = defaultSettleDeadlineMs,
      reconnectAttempts: Int = defaultReconnectAttempts,
      maxEventFrameBytes: Int = defaultMaxEventFrameBytes,
      lifecycle: OcLifecycle = OcLifecycle.External,
  ): Either[OcError, OcConfig] =
    lifecycle match
      case owned: OcLifecycle.Owned =>
        Left(
          OcError.CapabilityUnsupported(
            "owned server lifecycle is not implemented by this adapter; start `opencode serve` yourself and use the External lifecycle (requested command: " +
              owned.command.mkString(" ") + ")"
          )
        )
      case OcLifecycle.External =>
        for
          root <- validateBaseUrl(baseUrl)
          _ <- positive("connectTimeoutMs", connectTimeoutMs)
          _ <- positive("callTimeoutMs", callTimeoutMs)
          _ <- positive("eventIdleTimeoutMs", eventIdleTimeoutMs)
          _ <- positive("settleDeadlineMs", settleDeadlineMs)
          _ <- Either.cond(reconnectAttempts >= 0, (), OcError.Configuration("reconnectAttempts must be >= 0"))
          _ <- Either.cond(maxEventFrameBytes > 0, (), OcError.Configuration("maxEventFrameBytes must be > 0"))
        yield OcConfig(
          root,
          directory,
          auth,
          connectTimeoutMs,
          callTimeoutMs,
          eventIdleTimeoutMs,
          settleDeadlineMs,
          reconnectAttempts,
          maxEventFrameBytes,
          lifecycle,
        )

  private def positive(name: String, value: Long): Either[OcError, Unit] =
    Either.cond(value > 0, (), OcError.Configuration(s"$name must be > 0 (got $value)"))

  private def validateBaseUrl(raw: String): Either[OcError, String] =
    try
      val uri = URI.create(raw)
      val scheme = Option(uri.getScheme).getOrElse("")
      val host = Option(uri.getHost).getOrElse("")
      if (scheme != "http" && scheme != "https") ||
        host.isEmpty ||
        uri.getQuery != null || uri.getFragment != null
      then Left(
        OcError.Configuration(
          s"baseUrl must be an absolute http(s) URL without query or fragment (got '$raw')"
        )
      )
      else Right(raw.stripSuffix("/"))
    catch
      case _: Throwable =>
        Left(OcError.Configuration(s"baseUrl is not a valid URI (got '$raw')"))

  /** Percent-encodes a value for use in a query string or a path segment.
    */
  private[opencode] def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
