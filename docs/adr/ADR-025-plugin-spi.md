# ADR-025: Plugin SPI — narrow versioned Scala SPI, manifest, and preflight

Status: **Frozen** (2026-10-09, after freeze review R1-R8 amendments) (freeze after independent review). Date: 2026-10-09.
Owner: lane 5 (P4.A, [roadmap-12k](../roadmap-12k.md) §P4.A, 450 h package).
Card: [RAI-066](../tasks/m2-p2.md) (PLUG-01..03). The tool
contract RAI-002.b ([`raider.core.tool`](../../modules/core/src/main/scala/raider/core/tool.scala))
and W13 “extension without core edits” (working-product §2) remain in force: this ADR does not change
`ToolRegistry` or add any loader in P4.A.

## 1. Context

Current state: `ToolRegistry` is immutable (`ToolRegistry.build` is the
only entry point; all registrations undergo typed `InputValidation` before use);
the repository has no JAR loader, ServiceLoader discovery, or
install lifecycle; the only way to add a tool today is to rebuild
Raider. This blocks W13 and Exit M2 (roadmap §P4): “choose a local JAR →
see source/version/capabilities → confirm trust → use the tool;
Raider is not rebuilt.”

Target requirements: P4.B/C — install→use in ≤3 actions without a rebuild (P4.B:
manager/CLI install/list/check/enable/disable/update/remove/rollback with pinned
versions/checksums; P4.C: SDK/template/fixture plugins and API-version
compatibility). The honesty constraint from roadmap §P4: a classloader is a
compatibility boundary, **not a security sandbox**; a Scala plugin has process
permissions; `System.exit`/native crashes are not guaranteed to be caught; the
workspace does not activate code by itself; plugin config does not expand
policy/root budget.

Card P4.A (RAI-066) is deliberately narrow: ADR + type/preflight skeleton in
`raider.core.plugin`; loader, lifecycle, and UI are separate cards after
independent review of this ADR.

## 2. Decision

### 2.1 Narrow versioned descriptor model

A plugin consists of a descriptor and implementations of the frozen SPI. The descriptor carries: `name`,
`version` (SemVer-like `MAJOR.MINOR.PATCH` without leading zeros; pre-release
metadata is not supported in v1 — adding it requires a separate ABI decision),
`capabilities` (declarations by namespace: `tool` / `profile` / `hook` are
registry names; tools share the immutable `ToolRegistry` namespace, while profiles
and hooks have their own namespaces), and `compatibility`, an inclusive range
`[minApiVersion..maxApiVersion]` relative to `ApiVersion.current = 1`. An API
version bump is a breaking SPI change (new ADR), never a silent shape change.

### 2.2 Manifest: JSON + sha256 pinning (NOT security)

Installation is described by a JSON manifest (`schemaVersion = 1`): name, version,
artifact locator (a local JAR in v1), `sha256` (64 lowercase hex), API
range, and capabilities. Sha256 pinning provides integrity and reproducibility
(protection against a corrupt file or silent replacement between review and
install). It **does not** turn untrusted code into trusted code: trust requires
separate explicit owner confirmation in the P4.B UI. This boundary is stated
in the model, not hidden in the installer.

#
**Digest domain (ratified, lane-15 D2):** the pin is computed over the jar **payload**: all non-directory entries except the embedded `META-INF/raider-plugin.json` itself, sorted by name, each as `name ++ 0x00 ++ len ++ 0x00 ++ content`, SHA-256, lowercase hex. The manifest lives INSIDE the jar at `META-INF/raider-plugin.json` and pins this payload digest (self-reference avoided). Integrity ≠ trust.

## 2.3 Preflight before any load

All P4.A decision logic consists of pure functions returning `Either[PluginError, Unit]`:

- **compatibility intersection**: `currentApi ∈ [minApiVersion..maxApiVersion]`,
  otherwise typed `IncompatibleApi` → `RA-CAP` (refuse at preflight, not a runtime
  surprise);
- **duplicate names**: plugin names are unique within the co-install set; capability
  names are unique within each namespace across the set and within a single descriptor
  (tool-name clashes must fail at preflight — `ToolRegistry.build` is immutable
  and rejects duplicates);
- **unknown capability**: kind outside `{tool, profile, hook}` — typed
  `UnknownCapability` → `RA-INP`;
- **validity**: non-empty name/artifact/capability-name, canonical sha256,
  `maxApiVersion ≥ minApiVersion ≥ 1`, versions ≥ 0 — typed `Malformed`/
  `InvalidVersion` → `RA-INP`.

`PluginError` is its own sealed family (`RaiderError` is sealed; explicit
conversion through `toRaiderError`, as with `EventError` in ADR-024).

### 2.4 Controlled classloader (P4.B; contract only here)

The P4.B loader is a parent-delegating classloader with explicit exports: a plugin sees
the host API (`raider-core`) and declared exports, and its own dependencies from its
own JAR; `raider-*`, zio/zio-json, and JDK classes are provided by the host. This is a
COMPATIBILITY boundary (dependency/ABI isolation), **not a sandbox**: `System.exit`,
JVM-state modification, and native crashes are NOT caught — this is documented
plainly in the install flow, not hidden. Defects that can be caught become typed
failures; a hang results in bounded cancellation with an honest message. Granting
process permissions to trusted code is a deliberate decision; untrusted code
requires a separate process-isolation project outside this budget.

#
**ServiceLoader (ratified, lane-15 D4):** rejected as manifest-less discovery; permitted STRICTLY after manifest+sha+compatibility+duplicate-name preflight. Rules: exactly one `RaiderPlugin` provider per jar; the provided Tool set must EQUAL the declared tool capabilities — missing or undeclared entries are typed failures.

## 2.5 Resource lifecycle (P4.B/C)
**Status: decision, NOT yet implemented** (P4.B/C obligation — update/rollback CLI landed in RAI-101; drain/restart runtime semantics remain a design commitment, no reader may treat this section as existing behavior).

An update to an active plugin applies to new sessions after active jobs drain;
safe hot unloading of JVM code is not promised (updated classes use new
classloaders; old instances complete their lifecycle). Plugin config does not
expand policy/root budget: policy authority remains in core; hooks cannot
authorize what core policy forbids. The workspace does not activate code by
itself — activation requires an explicit owner install/enable; the plugin
version/configuration is pinned to the run.

### 2.6 Dependencies and compatibility matrix

ABI risks (Scala 3 ABI is not stable across versions) and dependency clashes
are addressed together: the host pins scalaVersion/toolchain; a plugin must declare
`[minApiVersion..maxApiVersion]`; classloader isolates plugin dependencies from
host and neighboring plugins (§2.4); release notes/ADR maintain an “API version ×
compatible toolchain/dependency rules” matrix. A clash or ABI mismatch is a typed
failure at preflight/load, never a silent class replacement.

## 3. Alternatives considered

1. **Java ServiceLoader without a manifest** — implicit discovery on the first
   classpath scan: no versioned handshake, no preflight before load,
   and no honest trust boundary; rejected.
2. **Script/non-JVM plugins** (JS/Lua) — a non-Java SDK is out of v1 (roadmap P4
   non-goals); rejected.
3. **Separate process + protocol** (MCP-style, RAI-053) — a different cost/latency
   profile; appropriate for untrusted servers, but it does not replace the in-process
   Scala SPI for W13; rejected as a replacement, remains a parallel path.
4. **No SPI (core edits only)** — violates W13/UX05 (rebuild for
   every custom tool); rejected.

## 4. Consequences and risks

- The registry remains immutable: install builds a **new** `ToolRegistry`
  (and profile/hook sets), rather than mutating the active one — update is atomic at
  the session boundary (§2.5).
- Risk of a false sense of security from sha256: the “integrity ≠ trust” boundary
  is stated in the manifest model and repeated in P4.B UI text.
- Risk of dependency clashes inside a plugin JAR (reflection/scripts, native
  libraries): the compatibility matrix (§2.6) must be maintained; there is no
  guarantee, only a typed failure when a clash is detected.
- Duplicate-name preflight resolves conflicts before load, but does not remove
  the need for uniqueness at runtime (installation order, enable/disable). P4.B
  reuses these same pure functions; a second validation is forbidden.

## 5. Frozen signatures (P4.B/C implement exactly this; changes require a new ADR)

```scala
package raider.core.plugin

import raider.core.RaiderError
import zio.json._

object ApiVersion:
  val current: Int = 1

final case class PluginVersion(major: Int, minor: Int, patch: Int):
  override def toString: String  // "MAJOR.MINOR.PATCH"
object PluginVersion:
  val Pattern: String            // ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$
  def parse(s: String): Either[PluginError.InvalidVersion, PluginVersion]
  given JsonCodec[PluginVersion] // wire: "1.4.2"

enum CapabilityKind:
  case Tool, Profile, Hook
object CapabilityKind:
  def fromWire(kind: String): Option[CapabilityKind]  // "tool"|"profile"|"hook"

final case class CapabilityRef(kind: String, name: String)
object CapabilityRef: given JsonCodec[CapabilityRef]

final case class PluginDescriptor(
    name: String,
    version: PluginVersion,
    minApiVersion: Int,
    maxApiVersion: Int,
    capabilities: List[CapabilityRef]
)
object PluginDescriptor: given JsonCodec[PluginDescriptor]

final case class PluginManifest(
    schemaVersion: Int,           // supported: 1
    name: String,
    version: PluginVersion,
    artifact: String,             // opaque locator (v1: local JAR path)
    sha256: String,               // 64 lowercase hex; integrity, NOT trust
    minApiVersion: Int,
    maxApiVersion: Int,
    capabilities: List[CapabilityRef]
)
object PluginManifest:
  val supportedSchemaVersions: Set[Int] = Set(1)
  given JsonCodec[PluginManifest]

sealed trait PluginError:                 // errors are data; no effects
  def detail: String
  def toRaiderError: RaiderError          // Malformed/InvalidVersion/Unknown/
                                          // Duplicate -> RA-INP;
                                          // IncompatibleApi -> RA-CAP
object PluginError:
  final case class Malformed(what: String)
  final case class InvalidVersion(version: String, why: String)
  final case class UnknownCapability(kind: String)
  final case class DuplicateName(namespace: String, name: String)
  final case class IncompatibleApi(name: String, min: Int, max: Int, current: Int)

trait RaiderPlugin:                       // entire SPI v1: descriptor; discovery/
  def descriptor: PluginDescriptor        // instantiation is done by the P4.B loader

object Preflight:                         // all pure: Either, zero IO
  def descriptor(d: PluginDescriptor): Either[PluginError, Unit]
  def compatibility(d: PluginDescriptor,
      currentApi: Int = ApiVersion.current): Either[PluginError, Unit]
  def manifest(m: PluginManifest): Either[PluginError, Unit]
  def install(m: PluginManifest,
      currentApi: Int = ApiVersion.current): Either[PluginError, Unit]
  def coinstall(ds: List[PluginDescriptor],
      currentApi: Int = ApiVersion.current): Either[PluginError, Unit]
```


**5b. Hook SPI (ratified, RAI-084):** `trait RaiderHook { def name: String; def apply(inputJson: String): String }` (read-only JSON in/out; no policy/budget access through the seam). `HookRunner` executes with a bounded per-call timeout (default 2000ms, per-call bound > 0), cooperative interrupt ONLY (the deprecated stop API is never used), one daemon worker per invocation — a timed-out hook's worker may outlive the call (honest, documented). Outcomes: `HookOutcome`/`HookFailure` sealed families (Completed/Failed{TimedOut,Threw,Undeclared,NotAHook,InvalidTimeout,Interrupted}); host channel reuses the EXISTING `PluginError.Malformed` — frozen §5 family is NOT extended. Classloader remains a compatibility boundary, not a sandbox.

## 6. Acceptance (RAI-066: PLUG-01..03)

- PLUG-01: manifest model roundtrip (zio-json, string wire format for versions);
  a valid SPI descriptor compiles (test `FixturePlugin extends
  RaiderPlugin`).
- PLUG-02: duplicate plugin/tool names, invalid versions (parse + components),
  unknown capability, API range mismatch, malformed manifest — typed
  `PluginError` with verified `RA-INP`/`RA-CAP` mapping.
- PLUG-03: a side-effect-free skeleton by design: signatures without
  effect types plus constant-pool scanning of compiled `raider.core.plugin`
  classes for IO/loader/classloader types (`java.io.File`, `java.nio.file`,
  `ServiceLoader`, `URLClassLoader`, `ProcessBuilder`, `zio.ZIO`, …); references
  to `java/io/Serializable` are allowed (case-class marker; grants no permissions).


**Open contract (freeze note):** who discovers/instantiates `RaiderHook` providers for hooks is NOT defined in v1 — the loader deliberately does not load hooks; `HookRunner` runs a handed-in instance. An owner card is required before hook *dispatch* work freezes (discovery exists in `plugin/loader/HookDiscovery`, RAI-095).

## 7. Out of scope

JAR loader and ServiceLoader discovery, install/rollback UI and CLI (P4.B),
hook execution and hook semantics (separate P4.B/C card), security sandbox and
process isolation for untrusted code, marketplace/billing, SDKs for non-JVM
languages, a second scheduler/runtime, changes to `ToolRegistry`/gate policy.
Gates for RAI-066 are component stage (static/unit/contracts/docs).
