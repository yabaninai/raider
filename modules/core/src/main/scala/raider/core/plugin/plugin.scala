package raider.core.plugin

import raider.core.RaiderError

import zio.json._

/** RAI-066 / ADR-025: narrow versioned plugin SPI — the MODEL + PREFLIGHT
  * slice.
  *
  * Everything in this package is pure data and pure validation (`Either`, no
  * effects): no jar loading, no ServiceLoader, no classloader, no install
  * lifecycle. Those belong to P4.B (loader/installer) and P4.C (SDK/fixtures)
  * and must implement exactly the frozen shapes of ADR-025 §5.
  *
  * RAI-084 (P4.A finish) extends this skeleton additively: the [[RaiderHook]]
  * SPI trait below and [[HookRunner]] (own file), the bounded executor for
  * declared hook capabilities. The runner still references no IO/loader/
  * classloader types (PLUG-03 constant-pool scan stays authoritative) and
  * PluginError remains frozen — hook failures are typed by the runner's own
  * `HookOutcome`/`HookFailure` families.
  *
  * Honesty boundaries (ADR-025 §1): the classloader is a compatibility
  * boundary, NOT a security sandbox; sha256 pinning is
  * integrity/reproducibility, NOT trust.
  */

/** API version of the Raider plugin SPI surface. A bump is a breaking SPI
  * change (new ADR). A plugin declares the inclusive range
  * `[minApiVersion..maxApiVersion]` it compiles against; preflight accepts a
  * plugin only when the current API version lies inside that range.
  */
object ApiVersion:
  val current: Int = 1

/** SemVer-like plugin version: `MAJOR.MINOR.PATCH`, each component a
  * non-negative integer without leading zeros. Pre-release/build metadata is
  * deliberately NOT supported in v1 — adding it is a wire/ABI decision (new
  * ADR), never a silent shape change.
  */
final case class PluginVersion(major: Int, minor: Int, patch: Int):
  override def toString: String = s"$major.$minor.$patch"

object PluginVersion:
  val Pattern = "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"

  def parse(s: String): Either[PluginError.InvalidVersion, PluginVersion] =
    if s.matches(Pattern) then
      val parts = s.split("\\.").map(_.toInt)
      Right(PluginVersion(parts(0), parts(1), parts(2)))
    else
      Left(
        PluginError.InvalidVersion(
          s,
          "expected MAJOR.MINOR.PATCH, components >= 0, no leading zeros"
        )
      )

  /** Wire form: the plain dotted string. */
  given JsonCodec[PluginVersion] = JsonCodec(
    JsonEncoder.string.contramap(_.toString),
    JsonDecoder.string.mapOrFail(PluginVersion.parse(_).left.map(_.detail))
  )

/** Capability namespaces a plugin can provide (ADR-025 §2.1: tools / profiles /
  * hooks). The wire keeps the lowercase kind string; preflight rejects anything
  * else as a typed UnknownCapability.
  */
enum CapabilityKind:
  case Tool
  case Profile
  case Hook

object CapabilityKind:

  def fromWire(kind: String): Option[CapabilityKind] = kind match
    case "tool"    => Some(Tool)
    case "profile" => Some(Profile)
    case "hook"    => Some(Hook)
    case _         => None

/** One declared capability: which namespace and the registry name it claims
  * (tool names share the immutable ToolRegistry namespace; profile/hook names
  * are namespaces of their own).
  */
final case class CapabilityRef(kind: String, name: String)

object CapabilityRef:
  given JsonCodec[CapabilityRef] = DeriveJsonCodec.gen[CapabilityRef]

/** What a compiled plugin declares about itself (the SPI handshake payload). A
  * LAZY descriptor: carrying or validating it loads nothing and dispatches
  * nothing (same rule as BundleWorkflow programs, API-03).
  */
final case class PluginDescriptor(
    name: String,
    version: PluginVersion,
    minApiVersion: Int,
    maxApiVersion: Int,
    capabilities: List[CapabilityRef]
)

object PluginDescriptor:
  given JsonCodec[PluginDescriptor] = DeriveJsonCodec.gen[PluginDescriptor]

/** Install-time metadata (JSON side). `artifact` is an opaque locator resolved
  * by the P4.B installer (local JAR in v1); `sha256` pins the artifact bytes
  * for integrity/reproducibility — it is NOT a trust statement and NOT a
  * sandbox: untrusted code stays untrusted until the owner explicitly approves
  * it (P4.B UI).
  */
final case class PluginManifest(
    schemaVersion: Int,
    name: String,
    version: PluginVersion,
    artifact: String,
    sha256: String,
    minApiVersion: Int,
    maxApiVersion: Int,
    capabilities: List[CapabilityRef]
)

object PluginManifest:
  val supportedSchemaVersions: Set[Int] = Set(1)
  given JsonCodec[PluginManifest] = DeriveJsonCodec.gen[PluginManifest]

/** Typed preflight failures (own sealed family, RaiderError is sealed —
  * explicit conversion, ADR-024 EventError pattern). Mapping: malformed input
  * and duplicates are InputValidation (RA-INP); a plugin whose API range does
  * not cover the running host is CapabilityUnsupported (RA-CAP).
  */
sealed trait PluginError:
  def detail: String
  def toRaiderError: RaiderError

object PluginError:

  final case class Malformed(what: String) extends PluginError:
    def detail: String = what

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"plugin: $what")

  final case class InvalidVersion(version: String, why: String)
      extends PluginError:
    def detail: String = s"invalid plugin version '$version': $why"

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"plugin: $detail")

  final case class UnknownCapability(kind: String) extends PluginError:

    def detail: String =
      s"unknown capability kind '$kind'; known: tool, profile, hook"

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"plugin: $detail")

  final case class DuplicateName(namespace: String, name: String)
      extends PluginError:
    def detail: String = s"duplicate $namespace name '$name'"

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"plugin: $detail")

  final case class IncompatibleApi(
      name: String,
      min: Int,
      max: Int,
      current: Int
  ) extends PluginError:

    def detail: String =
      s"plugin '$name' supports API [$min..$max], current API is $current"

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(s"plugin: $detail")

/** The narrow v1 SPI surface: a plugin entry class implements this and returns
  * its descriptor. NOTHING in v1 discovers or instantiates implementations —
  * instance loading is the P4.B controlled classloader's job; tool/profile/
  * hook instance types come from their own SPI cards (RAI-028/031). The trait
  * carries no lifecycle, no effects, no host services.
  */
trait RaiderPlugin:
  def descriptor: PluginDescriptor

/** The narrow v1 hook SPI (RAI-084, additive skeleton extension): one declared
  * plugin hook capability instance implements this. `name` is the registry name
  * of the hook capability (its own namespace, ADR-025 §2.1) — HookRunner
  * dispatches under exactly this name and refuses any other (typed refusal,
  * never a silent dispatch).
  *
  * `apply` is one synchronous invocation: read-only input JSON string in,
  * output JSON string out. NOTHING else crosses the seam — no policy, no
  * budget, no registry or host handles. Enforcing the per-call bound is the
  * runner's job ([[HookRunner]]), not the trait's; the trait promises nothing
  * about termination itself.
  */
trait RaiderHook:
  /** The declared hook capability name this instance serves. */
  def name: String

  /** One synchronous hook invocation: input JSON verbatim, output JSON verbatim
    * (the runner never parses or validates either side).
    */
  def apply(inputJson: String): String

/** Pure preflight validation — the only decision logic frozen in P4.A. Every
  * entry point is a total function `... => Either[PluginError, Unit]`; none of
  * them reads files, loads classes or touches a registry.
  */
object Preflight:

  /** Structural validation of one descriptor: non-empty name, sane API bounds,
    * valid version components, known capability kinds with non-empty unique
    * names.
    */
  def descriptor(d: PluginDescriptor): Either[PluginError, Unit] =
    if d.name.trim.isEmpty then
      Left(PluginError.Malformed("plugin name must be non-empty"))
    else if d.minApiVersion < 1 then
      Left(PluginError.Malformed("minApiVersion must be >= 1"))
    else if d.maxApiVersion < d.minApiVersion then
      Left(PluginError.Malformed("maxApiVersion must be >= minApiVersion"))
    else
      checkVersion(d.name, d.version).flatMap(_ =>
        checkCapabilities(d.name, d.capabilities)
      )

  /** Compatibility intersection with the running host API version (inclusive
    * bounds). This is the versioned handshake: load-time refuse, never a
    * runtime surprise.
    */
  def compatibility(
      d: PluginDescriptor,
      currentApi: Int = ApiVersion.current
  ): Either[PluginError, Unit] =
    if currentApi < d.minApiVersion || currentApi > d.maxApiVersion then
      Left(
        PluginError.IncompatibleApi(
          d.name,
          d.minApiVersion,
          d.maxApiVersion,
          currentApi
        )
      )
    else Right(())

  /** Structural validation of one install manifest: supported schemaVersion,
    * non-empty name/artifact, canonical 64-char lowercase hex sha256, sane
    * bounds, valid version, known unique capabilities.
    */
  def manifest(m: PluginManifest): Either[PluginError, Unit] =
    if !PluginManifest.supportedSchemaVersions.contains(m.schemaVersion) then
      Left(
        PluginError.Malformed(
          s"unsupported manifest schemaVersion ${m.schemaVersion}; " +
            s"supported: ${PluginManifest.supportedSchemaVersions.toList.sorted.mkString(",")}"
        )
      )
    else if m.name.trim.isEmpty then
      Left(PluginError.Malformed("plugin name must be non-empty"))
    else if m.artifact.trim.isEmpty then
      Left(PluginError.Malformed("artifact must be non-empty"))
    else if !m.sha256.matches("[0-9a-f]{64}") then
      Left(
        PluginError.Malformed("sha256 must be 64 lowercase hex characters")
      )
    else if m.minApiVersion < 1 then
      Left(PluginError.Malformed("minApiVersion must be >= 1"))
    else if m.maxApiVersion < m.minApiVersion then
      Left(PluginError.Malformed("maxApiVersion must be >= minApiVersion"))
    else
      checkVersion(m.name, m.version).flatMap(_ =>
        checkCapabilities(m.name, m.capabilities)
      )

  /** Install decision for one manifest: manifest validity AND compatibility
    * with the current API. (Trust confirmation and checksum verification
    * against the actual artifact bytes are P4.B installer steps, not this.)
    */
  def install(
      m: PluginManifest,
      currentApi: Int = ApiVersion.current
  ): Either[PluginError, Unit] =
    manifest(m).flatMap(_ => compatibility(descriptorOf(m), currentApi))

  /** Co-installation of a descriptor set (what a registry built from several
    * plugins would contain): every descriptor valid and compatible, plugin
    * names unique across the set, capability names unique per namespace across
    * the set — tool names are a single ToolRegistry namespace, so a clash must
    * fail at preflight, never at registry build.
    */
  def coinstall(
      ds: List[PluginDescriptor],
      currentApi: Int = ApiVersion.current
  ): Either[PluginError, Unit] =
    ds.foldLeft[Either[PluginError, Unit]](Right(()))((acc, d) =>
      acc.flatMap(_ => descriptor(d).flatMap(_ => compatibility(d, currentApi)))
    ).flatMap(_ => checkCrossPluginDuplicates(ds))

  private def descriptorOf(m: PluginManifest): PluginDescriptor =
    PluginDescriptor(
      m.name,
      m.version,
      m.minApiVersion,
      m.maxApiVersion,
      m.capabilities
    )

  private def checkVersion(
      pluginName: String,
      v: PluginVersion
  ): Either[PluginError, Unit] =
    if v.major < 0 || v.minor < 0 || v.patch < 0 then
      Left(
        PluginError.InvalidVersion(
          v.toString,
          s"plugin '$pluginName' has negative version components"
        )
      )
    else Right(())

  private def checkCapabilities(
      pluginName: String,
      caps: List[CapabilityRef]
  ): Either[PluginError, Unit] =
    caps.find(_.name.trim.isEmpty) match
      case Some(_) =>
        Left(
          PluginError
            .Malformed(s"plugin '$pluginName' has an empty capability name")
        )
      case None =>
        caps.find(c => CapabilityKind.fromWire(c.kind).isEmpty) match
          case Some(bad) => Left(PluginError.UnknownCapability(bad.kind))
          case None =>
            firstDup(caps.map(c => (c.kind, c.name))) match
              case Some((kind, name)) =>
                Left(PluginError.DuplicateName(kind, name))
              case None => Right(())

  private def checkCrossPluginDuplicates(
      ds: List[PluginDescriptor]
  ): Either[PluginError, Unit] =
    firstDup(ds.map(_.name)) match
      case Some(name) => Left(PluginError.DuplicateName("plugin", name))
      case None =>
        firstDup(ds.flatMap(_.capabilities.map(c => (c.kind, c.name)))) match
          case Some((kind, name)) => Left(PluginError.DuplicateName(kind, name))
          case None               => Right(())

  private def firstDup[A: Ordering](as: List[A]): Option[A] =
    as.groupBy(identity)
      .collect { case (a, group) if group.size > 1 => a }
      .toList
      .sorted
      .headOption

end Preflight
