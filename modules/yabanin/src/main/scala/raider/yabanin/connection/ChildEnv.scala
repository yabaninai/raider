package raider.yabanin.connection

import raider.yabanin.labels.LabelHeader
import raider.yabanin.labels.LabelLimits

/** Builder for the child-only environment (`docs/ci-runtime.md`; contract §2).
  *
  * The child provider call receives a minimized environment built from an
  * explicit allowlist of parent names; the child environment is exactly the
  * entries built here — non-allowlisted parent variables never reach the
  * child. Credential values flow to the child only through the allowlisted
  * variable name itself (raw pass-through preserves provenance; values are
  * never copied into reports or logs by this builder).
  *
  * Constructed entries:
  *   - `RAIDER_PROVIDER=openai` (fixed);
  *   - `RAIDER_BASE_URL=<resolved gateway url>` (omitted, with a visible
  *     omission record, when no usable base URL resolved);
  *   - `RAIDER_OPENAI_EXTRA_HEADERS=X-Yabanin-Labels: <rendered header>`
  *     (only when the merged label header is non-empty).
  *
  * Every parent value replaced by an entry is recorded as a visible
  * override (name plus value kind, never the parent secret value itself);
  * allowlisted names with blank or missing parent values are recorded as
  * visible omissions.
  */
object ChildEnv:

  /** Parent variable names explicitly allowed to reach the child, in the
    * fixed order they are considered.
    */
  val Allowlist: Vector[String] = WrapperEnvNames.All

  /** Why an entry was placed into the child environment. */
  enum OverrideKind:
    /** Raw parent value passed through under its allowlisted name. */
    case PassThrough
    /** Fixed child provider kind. */
    case FixedProvider
    /** Resolved gateway base URL (replaces any parent value). */
    case ResolvedBaseUrl
    /** Merged label header for the gateway routing decision. */
    case LabelsHeader

    /** Wire spelling used in reports. */
    def encode: String = this match
      case PassThrough     => "pass-through"
      case FixedProvider   => "fixed-provider"
      case ResolvedBaseUrl => "resolved-base-url"
      case LabelsHeader    => "labels-header"

  /** A visible override: the child entry `name` replaces whatever a wholesale
    * environment inheritance would have provided. Carries the value kind,
    * never the parent secret value.
    */
  final case class EnvOverride(name: String, kind: OverrideKind)

  /** A visible omission: an allowlisted name (or the resolved base URL) that
    * could not be provided to the child.
    */
  final case class EnvOmission(name: String, detail: String)

  /** The built child environment. `entries` is the exact child environment:
    * every entry reaches the child, nothing else does. Order is deterministic
    * (allowlisted names in [[Allowlist]] order, then constructed entries in
    * fixed order).
    */
  final case class ChildEnvironment(
    entries: Vector[(String, String)],
    overrides: Vector[EnvOverride],
    omissions: Vector[EnvOmission],
  ):
    /** Entries as an ordered map (insertion order preserved). */
    def asOrderedMap: scala.collection.immutable.ListMap[String, String] =
      scala.collection.immutable.ListMap(entries*)

  /** Builds the child-only environment for `ctx` out of `parentEnv`. */
  def build(ctx: EffectiveContext, parentEnv: Map[String, String]): ChildEnvironment =
    val entries = Vector.newBuilder[(String, String)]
    val overrides = Vector.newBuilder[EnvOverride]
    val omissions = Vector.newBuilder[EnvOmission]

    Allowlist.foreach { name =>
      parentEnv.get(name) match
        case Some(value) if value.trim.nonEmpty =>
          entries += (name -> value)
          overrides += EnvOverride(name, OverrideKind.PassThrough)
        case Some(_) =>
          omissions += EnvOmission(
            name,
            s"$name is present in the parent environment but blank; not passed to the child",
          )
        case None =>
          omissions += EnvOmission(
            name,
            s"$name is absent from the parent environment; not passed to the child",
          )
    }

    entries += ("RAIDER_PROVIDER" -> "openai")
    overrides += EnvOverride("RAIDER_PROVIDER", OverrideKind.FixedProvider)

    ctx.gatewayUrl match
      case Some(url) =>
        entries += ("RAIDER_BASE_URL" -> url)
        overrides += EnvOverride("RAIDER_BASE_URL", OverrideKind.ResolvedBaseUrl)
      case None =>
        omissions += EnvOmission(
          "RAIDER_BASE_URL",
          "no usable gateway base URL resolved; the child provider call cannot target the gateway",
        )

    val header = ctx.labels.renderHeader
    if header.nonEmpty then
      entries += ("RAIDER_OPENAI_EXTRA_HEADERS" -> s"${LabelHeader.HeaderName}: $header")
      overrides += EnvOverride("RAIDER_OPENAI_EXTRA_HEADERS", OverrideKind.LabelsHeader)

    ChildEnvironment(entries.result(), overrides.result(), omissions.result())
