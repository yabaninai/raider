package raider.core.config

import raider.core.RaiderError
import raider.core.schemas.supportedSchemaVersions
import zio.json._

/** Independent provider config (RAI-005.a, runtime-contracts §2/§14,
  * provider-protocols §1–2).
  *
  * Immutable and versioned. Credentials are stored ONLY as environment
  * variable NAME references — a config document can never carry a secret.
  * Resolution is bounded (explicit env map in, redacted diagnostics out).
  * Overrides can only NARROW ceilings (CFG-03): widening is rejected.
  */
object config:

  enum WireKind:
    case OpenAICompatible, AnthropicCompatible

  object WireKind:
    given JsonCodec[WireKind] = DeriveJsonCodec.gen[WireKind]

  /** Role names are lowercase identifiers; model roles map to profiles. */
  final case class RoleBinding(profile: String, modelOverride: Option[String] = None,
                                requiredOption: Option[String] = None)

  object RoleOption:
    val known: Set[String] = Set("json_mode", "tools", "streaming")

  final case class Ceilings(
    maxSteps: Int,
    maxOutputTokens: Int,
    maxConcurrentModelCalls: Int,
    hardCapMicroUsd: Option[Long]
  ):
    /** CFG-03: overrides may only narrow; widening is refused. */
    def narrowed(by: Ceilings): Either[RaiderError, Ceilings] =
      def widen[A](ours: A, theirs: A, name: String)(using ord: Ordering[A]): Boolean =
        ord.gt(theirs, ours)
      if widen(maxSteps, by.maxSteps, "maxSteps")
        || widen(maxOutputTokens, by.maxOutputTokens, "maxOutputTokens")
        || widen(maxConcurrentModelCalls, by.maxConcurrentModelCalls, "maxConcurrentModelCalls")
      then Left(RaiderError.InputValidation(
        "override widens ceilings: overrides may only narrow capabilities"))
      else
        val cap = (hardCapMicroUsd, by.hardCapMicroUsd) match
          case (Some(a), Some(b)) if b < a => Some(b)
          case (Some(a), Some(b))          => Some(a)
          case (a, b)                      => a.orElse(b)
        Right(copy(maxSteps = math.min(maxSteps, by.maxSteps),
                   maxOutputTokens = math.min(maxOutputTokens, by.maxOutputTokens),
                   maxConcurrentModelCalls =
                     math.min(maxConcurrentModelCalls, by.maxConcurrentModelCalls),
                   hardCapMicroUsd = cap))

  /** Credential = reference to an env var NAME. The value never lives here. */
  final case class CredentialRef(envVar: String)
  object CredentialRef:
    private val Name = "[A-Z_][A-Z0-9_]*"
    def parse(raw: String): Either[RaiderError, CredentialRef] =
      if raw.matches(Name) then Right(CredentialRef(raw))
      else Left(RaiderError.Configuration(
        s"credential env ref must match $Name, got a non-conforming name"))

  /** Non-local endpoints must be https; the API prefix is preserved (§2). */
  final case class Endpoint(url: String)
  object Endpoint:
    private val Local = List("localhost", "127.0.0.1", "::1", "[::1]")
    def parse(raw: String): Either[RaiderError, Endpoint] =
      val trimmed = raw.trim.stripSuffix("/")
      if trimmed.isEmpty then Left(RaiderError.Configuration("endpoint URL is empty"))
      else
        val scheme = trimmed.takeWhile(_ != ':')
        val host = trimmed
          .drop(scheme.length).dropWhile(c => c == ':' || c == '/')
          .takeWhile(c => c != ':' && c != '/')
        val local = Local.exists(h => host == h || host.startsWith(s"$h:"))
        if scheme != "https" && !local then
          Left(RaiderError.Configuration(
            s"insecure remote endpoint (http on non-local host): diagnostics redacted"))
        else Right(Endpoint(trimmed))

  final case class ProviderProfile(
    name: String,
    wire: WireKind,
    endpoint: Endpoint,
    apiPrefix: String,
    credential: CredentialRef,
    model: String,
    priceKnown: Boolean
  )

  final case class RaiderConfig(
    schemaVersion: Int,
    roles: Map[String, RoleBinding],
    profiles: Map[String, ProviderProfile],
    ceilings: Ceilings
  ):
    def role(name: String): Option[RoleBinding] = roles.get(name)

    /** Role resolution applies the binding's model override (mixed-role setup
      * without service calls). */
    def profileForRole(roleName: String): Option[ProviderProfile] =
      for
        binding <- roles.get(roleName)
        profile <- profiles.get(binding.profile)
      yield binding.modelOverride match
        case Some(model) => profile.copy(model = model)
        case None        => profile

  // ---- wire (versioned envelope validated BEFORE payload use) ----
  final case class ConfigWire(
    schemaVersion: Int,
    roles: Map[String, RoleBinding],
    profiles: List[ProviderProfile],
    ceilings: Ceilings
  )
  object ConfigWire:
    given JsonCodec[RoleBinding]            = DeriveJsonCodec.gen[RoleBinding]
    given JsonCodec[Ceilings]               = DeriveJsonCodec.gen[Ceilings]
    given JsonCodec[CredentialRef]          = DeriveJsonCodec.gen[CredentialRef]
    given JsonCodec[Endpoint]               = DeriveJsonCodec.gen[Endpoint]
    given JsonCodec[ProviderProfile]        = DeriveJsonCodec.gen[ProviderProfile]
    given JsonCodec[ConfigWire]             = DeriveJsonCodec.gen[ConfigWire]

  object RaiderConfig:

    val defaultCeilings: Ceilings =
      Ceilings(maxSteps = 32, maxOutputTokens = 8192,
               maxConcurrentModelCalls = 1, hardCapMicroUsd = None)

    /** Total validation before any dispatch (CFG-02). */
    def build(wire: ConfigWire): Either[RaiderError, RaiderConfig] =
      if !supportedSchemaVersions.contains(wire.schemaVersion) then
        Left(RaiderError.InputValidation(
          s"unsupported schemaVersion ${wire.schemaVersion}"))
      else if wire.roles.isEmpty then
        Left(RaiderError.InputValidation("config must define at least one role"))
      else
        val duplicateProfiles = wire.profiles.map(_.name).groupBy(identity)
          .collect { case (n, ns) if ns.size > 1 => n }.toList.sorted
        if duplicateProfiles.nonEmpty then
          Left(RaiderError.InputValidation(
            s"duplicate profile names: ${duplicateProfiles.mkString(", ")}"))
        else validateCeilings(wire.ceilings).flatMap { ceilings =>
          wire.profiles.foldLeft[Either[RaiderError, Map[String, ProviderProfile]]](
            Right(Map.empty)) { (acc, p) =>
            acc.flatMap(m => validProfile(p).map(v => m + (v.name -> v)))
          }.flatMap { profiles =>
            wire.roles.toList.foldLeft[Either[RaiderError, Map[String, RoleBinding]]](
              Right(Map.empty)) { (acc, r) =>
              acc.flatMap { m =>
                if !profiles.contains(r._2.profile) then
                  Left(RaiderError.InputValidation(
                    s"role '${r._1}' references missing profile '${r._2.profile}'"))
                else r._2.requiredOption match
                  case Some(opt) if !RoleOption.known.contains(opt) =>
                    Left(RaiderError.InputValidation(
                      s"unknown required option '$opt' for role '${r._1}'"))
                  case _ => Right(m + (r._1 -> r._2))
              }
            }.map(roles => RaiderConfig(wire.schemaVersion, roles, profiles, ceilings))
          }
        }

    private def validProfile(p: ProviderProfile): Either[RaiderError, ProviderProfile] =
      if p.name.trim.isEmpty then
        Left(RaiderError.Configuration("profile name must be non-empty"))
      else if p.model.trim.isEmpty then
        Left(RaiderError.Configuration(
          "profile model must be non-empty (diagnostics redacted)"))
      else for _  <- Endpoint.parse(p.endpoint.url)
                  _  <- CredentialRef.parse(p.credential.envVar)
                  pf <- if p.apiPrefix.nonEmpty && !p.apiPrefix.startsWith("/") then
                          Left(RaiderError.Configuration(
                            s"apiPrefix must start with '/' (prefix preserved as configured)"))
                        else Right(p)
            yield pf

    private def validateCeilings(c: Ceilings): Either[RaiderError, Ceilings] =
      if c.maxSteps < 1 then
        Left(RaiderError.Configuration(s"maxSteps must be >= 1, got ${c.maxSteps}"))
      else if c.maxOutputTokens < 1 then
        Left(RaiderError.Configuration(
          s"maxOutputTokens must be >= 1, got ${c.maxOutputTokens}"))
      else if c.maxConcurrentModelCalls < 1 then
        Left(RaiderError.Configuration(
          s"maxConcurrentModelCalls must be >= 1, got ${c.maxConcurrentModelCalls}"))
      else c.hardCapMicroUsd match
        case Some(v) if v < 0 =>
          Left(RaiderError.Configuration(s"hardCapMicroUsd must be >= 0, got $v"))
        case _ => Right(c)

    def decode(json: String): Either[RaiderError, RaiderConfig] =
      json.fromJson[ConfigWire] match
        case Left(err) => Left(RaiderError.InputValidation(s"malformed config: $err"))
        case Right(w)  => build(w)

  /** Bounded resolution against an EXPLICIT env map (no ambient reads in core).
    * Diagnostics are redacted: presence and a constant mask, never the value. */
  object credentials:
    final case class Resolved(profile: String, envVar: String, present: Boolean):
      override def toString: String =
        s"Resolved($profile, $envVar, present=$present, value=<redacted>)"

    def resolve(config: RaiderConfig, env: Map[String, String])
        : Either[RaiderError, Map[String, Resolved]] =
      config.profiles.toList.foldLeft[Either[RaiderError, Map[String, Resolved]]](
        Right(Map.empty)) { (acc, p) =>
        acc.flatMap { m =>
          val ref = p._2.credential
          env.get(ref.envVar) match
            case Some(_) => Right(m + (p._1 -> Resolved(p._1, ref.envVar, present = true)))
            case None =>
              Left(RaiderError.ProviderAuth(
                s"missing credential: env var '${ref.envVar}' for profile '${p._1}' is not set"))
        }
      }
end config

export config.{WireKind, RoleBinding, RoleOption, Ceilings, CredentialRef,
               Endpoint, ProviderProfile, RaiderConfig, ConfigWire, credentials}
