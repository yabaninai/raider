package raider.yabanin.connection

import java.net.URI
import java.util.Locale

/** Validation and normalization of the Yabanin gateway base URL (contract §2,
  * `docs/providers/gateway-baseline.md`).
  *
  * A usable base URL is an absolute `http`/`https` URL with a host, no query
  * string, no fragment, and no user info. Trailing slashes on the path are
  * trimmed so the doctor can append `/models` and `/yabanin/session-keys`
  * verbatim. The scheme is lowercased; every other component is preserved
  * verbatim.
  *
  * Validation failures are typed and their messages describe the rule that
  * failed; the rejected input value is never echoed (contract §5).
  */
object GatewayUrl:

  /** Typed validation failure. Never carries or echoes the raw input value. */
  final case class GatewayUrlError(message: String)

  private val AllowedSchemes = Set("http", "https")

  /** Validates and normalizes a candidate base URL. */
  def validate(raw: String): Either[GatewayUrlError, String] =
    try
      val uri = URI.create(raw)
      if !uri.isAbsolute then Left(GatewayUrlError("gateway URL must be absolute (scheme://host)"))
      else if !AllowedSchemes.contains(Option(uri.getScheme).map(s => s.toLowerCase(Locale.ROOT)).getOrElse("")) then
        Left(GatewayUrlError("gateway URL scheme must be http or https"))
      else if Option(uri.getHost).forall(_.isEmpty) then Left(GatewayUrlError("gateway URL must include a host"))
      else if uri.getRawQuery ne null then Left(GatewayUrlError("gateway URL must not include query parameters"))
      else if uri.getRawFragment ne null then Left(GatewayUrlError("gateway URL must not include a fragment"))
      else if (uri.getUserInfo ne null) then Left(GatewayUrlError("gateway URL must not include user info"))
      else
        val path = Option(uri.getRawPath).getOrElse("")
        val trimmed = path.reverse.dropWhile(_ == '/').reverse
        val port = Option(uri.getPort).filter(_ != -1).map(p => s":$p").getOrElse("")
        Right(s"${uri.getScheme.toLowerCase(Locale.ROOT)}://${uri.getHost}$port$trimmed")
    catch
      case _: IllegalArgumentException =>
        Left(GatewayUrlError("gateway URL is not a usable RFC 3986 URI reference"))

  /** The URL path of `baseUrl` (may be empty), used to record
    * `observed_on` decision-header evidence without echoing credentials.
    */
  def pathOf(baseUrl: String): String = Option(URI.create(baseUrl).getPath).getOrElse("")
