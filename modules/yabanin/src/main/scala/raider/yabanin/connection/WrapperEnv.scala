package raider.yabanin.connection

import raider.yabanin.labels.*

/** The three recognized wrapper environment variable names (contract §2,
  * `docs/providers/gateway-baseline.md`).
  */
object WrapperEnvNames:
  /** Absolute base URL of the Yabanin gateway. */
  val GatewayUrl: String = "YABANIN_GATEWAY_URL"

  /** NAME of the environment variable reference holding the gateway
    * credential. The credential value itself is never copied into reports or
    * logs.
    */
  val ApiKeyRef: String = "YABANIN_API_KEY"

  /** Raw canonical label header text attributed to provenance `wrapper`. */
  val Labels: String = "YABANIN_LABELS"

  /** All recognized names, in the fixed order the builder considers them. */
  val All: Vector[String] = Vector(GatewayUrl, ApiKeyRef, Labels)

/** Lifecycle owner recognized from the environment: `wrapper` when any of the
  * three wrapper variables is present (a blank value still counts as
  * present), `standalone` otherwise.
  */
enum LifecycleOwner:
  case Wrapper
  case Standalone

  /** Wire spelling used in contract documents. */
  def encode: String = this match
    case Wrapper    => "wrapper"
    case Standalone => "standalone"

object LifecycleOwner:
  def decode(s: String): Option[LifecycleOwner] = s match
    case "wrapper"    => Some(Wrapper)
    case "standalone" => Some(Standalone)
    case _            => None

/** Names of visible context problems (contract §2). Bad values become
  * visible problems, never silent fakes.
  */
enum ContextProblemName:
  case InvalidGatewayUrl
  case BlankApiKeyRef
  case WrapperLabelsUnparsable
  case NoBaseUrl

  /** Wire spelling used in contract documents. */
  def encode: String = this match
    case InvalidGatewayUrl       => "invalid_gateway_url"
    case BlankApiKeyRef          => "blank_api_key_ref"
    case WrapperLabelsUnparsable => "wrapper_labels_unparsable"
    case NoBaseUrl               => "no_base_url"

/** A visible recognition problem. `detail` explains the rule that failed and
  * never echoes raw credential values or raw rejected URL values.
  */
final case class ContextProblem(name: ContextProblemName, detail: String)

/** The credential as an environment variable NAME reference. The referenced
  * value never appears in this record.
  */
final case class CredentialRef(reference: String, valuePresent: Boolean):
  /** Constant wire spelling (`kind` is fixed by the contract schema). */
  def kind: String = "env-name"

/** Canonical record of the wrapper environment recognition (contract §2).
  *
  * `labels` is the merged label set (wrapper first, then client, per contract
  * §1.3); `wrapperLabels` and `clientLabels` keep the per-source views for
  * provenance-aware consumers.
  */
final case class EffectiveContext(
  gatewayUrl: Option[String],
  credential: CredentialRef,
  wrapperLabels: LabelSet,
  clientLabels: LabelSet,
  labels: LabelSet,
  owner: LifecycleOwner,
  problems: Vector[ContextProblem],
)

/** Wrapper environment recognition (contract §2).
  *
  * Semantics:
  *   - A wrapper owner is recognized when any of the three wrapper variables
  *     is present in the environment; otherwise the owner is `standalone`.
  *   - Precedence: an explicitly configured base URL wins over
  *     `YABANIN_GATEWAY_URL` (the wrapper variable is consulted only when no
  *     explicit URL is configured).
  *   - A blank or unusable gateway URL yields `invalid_gateway_url`; a blank
  *     `YABANIN_API_KEY` yields `blank_api_key_ref`; unparsable
  *     `YABANIN_LABELS` yields `wrapper_labels_unparsable` while the rest of
  *     the profile still resolves; no usable base URL when a gateway was
  *     expected (wrapper profile active or an explicit URL was configured)
  *     yields `no_base_url`. In a pure standalone environment (no wrapper
  *     variables, no explicit URL) no problem is recorded: the optional
  *     profile is simply inactive.
  */
object WrapperEnv:

  /** Resolves the effective context from the parent environment.
    *
    * @param env
    *   the parent environment map (presence is decided by key existence; a
    *   blank value counts as present-but-blank)
    * @param explicitBaseUrl
    *   explicitly configured base URL, if any; wins over the wrapper variable
    * @param clientPairs
    *   Raider-side (client) label pairs in insertion order
    * @param limits
    *   label limits (the contract pins [[LabelLimits.Default]])
    */
  def resolve(
    env: Map[String, String],
    explicitBaseUrl: Option[String],
    clientPairs: Vector[(String, String)],
    limits: LabelLimits,
  ): EffectiveContext =
    val problems = Vector.newBuilder[ContextProblem]

    val anyWrapperVar = WrapperEnvNames.All.exists(env.contains)
    val owner = if anyWrapperVar then LifecycleOwner.Wrapper else LifecycleOwner.Standalone

    // Base URL: explicit configuration wins over the wrapper variable.
    val gatewayUrl: Option[String] = explicitBaseUrl match
      case Some(raw) =>
        GatewayUrl.validate(raw) match
          case Right(url) => Some(url)
          case Left(err) =>
            problems += ContextProblem(
              ContextProblemName.InvalidGatewayUrl,
              s"explicitly configured base URL is unusable: ${err.message}",
            )
            None
      case None =>
        env.get(WrapperEnvNames.GatewayUrl) match
          case Some(raw) =>
            if raw.trim.isEmpty then
              problems += ContextProblem(
                ContextProblemName.InvalidGatewayUrl,
                s"${WrapperEnvNames.GatewayUrl} is present but blank",
              )
              None
            else
              GatewayUrl.validate(raw) match
                case Right(url) => Some(url)
                case Left(err) =>
                  problems += ContextProblem(
                    ContextProblemName.InvalidGatewayUrl,
                    s"${WrapperEnvNames.GatewayUrl} is unusable: ${err.message}",
                  )
                  None
          case None => None

    // Credential: the wrapper variable holds the NAME of the variable that
    // carries the value. Presence is visible; the value is never recorded.
    val keyValue = env.get(WrapperEnvNames.ApiKeyRef)
    val valuePresent = keyValue.exists(_.trim.nonEmpty)
    if keyValue.exists(_.trim.isEmpty) then
      problems += ContextProblem(
        ContextProblemName.BlankApiKeyRef,
        s"${WrapperEnvNames.ApiKeyRef} is present but blank; no credential reference resolves",
      )
    val credential = CredentialRef(WrapperEnvNames.ApiKeyRef, valuePresent)

    // Wrapper labels: strict canonical decode; a failure is a visible
    // problem and the profile continues without wrapper labels.
    val wrapperSet: LabelSet = env.get(WrapperEnvNames.Labels) match
      case Some(raw) =>
        LabelHeader.parseRaw(raw, limits) match
          case Right(pairs) => LabelHeader.fromWrapper(pairs, limits)
          case Left(err) =>
            problems += ContextProblem(
              ContextProblemName.WrapperLabelsUnparsable,
              s"${WrapperEnvNames.Labels} is not canonical header text: ${err.message}; the profile continues without wrapper labels",
            )
            LabelSet.empty
      case None => LabelSet.empty

    val clientSet = LabelHeader.fromClient(clientPairs, limits)
    val merged = LabelHeader.merge(wrapperSet, clientSet, limits)

    // No usable base URL when a gateway was expected but none resolved.
    if gatewayUrl.isEmpty && (owner == LifecycleOwner.Wrapper || explicitBaseUrl.isDefined) then
      problems += ContextProblem(
        ContextProblemName.NoBaseUrl,
        "no usable gateway base URL resolved from explicit configuration or the wrapper environment",
      )

    EffectiveContext(gatewayUrl, credential, wrapperSet, clientSet, merged, owner, problems.result())
