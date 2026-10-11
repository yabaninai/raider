# Raider Plugin SDK

Audience: developers writing Raider plugins in Scala 3. This is the practical
guide for the narrow versioned plugin SPI frozen in
[ADR-025](adr/ADR-025-plugin-spi.md) and implemented by
[`raider.core.plugin`](../modules/core/src/main/scala/raider/core/plugin/plugin.scala)
(model + preflight), the
[PluginLoader](../modules/core/src/main/scala/raider/core/plugin/loader/PluginLoader.scala)
(loading, RAI-075) and
[PluginCli](../modules/cli/src/main/scala/raider/cli/run/PluginCli.scala)
(catalog management, RAI-075/083). Working fixtures live in
`examples/plugins/` and their executable contract in
`contracts/plugins/FixturePluginsSpec.scala`.

## The SPI at a glance

A plugin is a jar that declares a descriptor, pins its own payload bytes, and
provides implementations of the frozen v1 interfaces:

```scala
package raider.core.plugin

trait RaiderPlugin:                    // exactly ONE provider per jar
  def descriptor: PluginDescriptor     // lazy: loading nothing on construction

trait RaiderHook:                      // one declared hook capability
  def name: String                     // registry name (own namespace)
  def apply(inputJson: String): String // sync JSON-string -> JSON-string

// tools implement raider.core.Tool (RAI-002.b):
//   name/version/description/recovery/timeoutMs/capabilities
//   invoke(argumentsJson): ZIO[Scope, RaiderError, String]
```

Everything is Scala 3 + ZIO; the host provides `raider-core`, `zio` and
`zio-json` classes through parent delegation, so your jar carries only your
own classes (no fat jar needed).

Sources: `plugin.scala:184-186` (`RaiderPlugin`), `plugin.scala:199-206`
(`RaiderHook`), `tool.scala:40-47` (`Tool`, frozen RAI-002.b contract).
Unless noted, all file:line anchors in this guide refer to files under
`modules/core/src/main/scala/raider/core/` (`plugin.scala` =
`raider/core/plugin/plugin.scala`, `PluginLoader.scala` =
`raider/core/plugin/loader/PluginLoader.scala`, `HookRunner.scala` =
`raider/core/plugin/HookRunner.scala`, `tool.scala` =
`raider/core/tool.scala`); `PluginCli.scala`/`Main.scala` live under
`modules/cli/src/main/scala/raider/cli/` (`run/` for PluginCli), and
`result.scala` is `raider/core/result.scala`.

## Descriptor vs install manifest

Two related JSON shapes, do not confuse them:

1. The **descriptor** (`PluginDescriptor`) is what your `RaiderPlugin` entry
   returns in code: `name`, `version` (`MAJOR.MINOR.PATCH`, no pre-release
   metadata in v1), `minApiVersion..maxApiVersion` (inclusive range against
   `ApiVersion.current = 1`; a bump is a breaking change via a new ADR), and
   `capabilities` — refs of `kind` in `tool | profile | hook` plus a registry
   `name` (tools share the immutable `ToolRegistry` namespace; hooks and
   profiles have their own).
2. The **install manifest** lives inside the jar as
   `META-INF/raider-plugin.json` (`schemaVersion` supported: `1`):

```json
{
  "schemaVersion": 1,
  "name": "hello-tool",
  "version": "1.0.0",
  "artifact": "hello-tool-1.0.0.jar",
  "sha256": "f65bd144c2a51000d6b2227c024402063e458ef76afa75fc11631a86fe93b5f1",
  "minApiVersion": 1,
  "maxApiVersion": 1,
  "capabilities": [
    { "kind": "tool", "name": "hello.greet" }
  ]
}
```

The manifest's `name`/`version`/api range/capabilities must EQUAL the SPI
descriptor (`PluginLoader` enforces this at load, `PluginLoader.scala:293-306`).
Validation is pure
preflight — non-empty names, supported schema, 64-char lowercase hex
`sha256`, `maxApiVersion >= minApiVersion >= 1`, unique capability names —
and failures are typed `PluginError` (`Malformed`, `InvalidVersion`,
`UnknownCapability`, `DuplicateName`, `IncompatibleApi`; `plugin.scala:131-176`),
mapped to
`RA-INP`/`RA-CAP`, never exceptions.

Sources: `plugin.scala:94-100` (`PluginDescriptor` fields),
`plugin.scala:111-120` (`PluginManifest` fields),
`plugin.scala:32-33` (`ApiVersion.current = 1`),
`plugin.scala:234-251` (`Preflight.compatibility`),
`plugin.scala:253-278` (`Preflight.manifest`),
`plugin.scala:281-286` (`Preflight.install`),
`plugin.scala:294-300` (`Preflight.coinstall`).

## Jar layout and service discovery

```
hello-tool-1.0.0.jar
├── hello/HelloToolPlugin.class
├── hello/HelloGreetTool.class
├── META-INF/raider-plugin.json                          <- the manifest
├── META-INF/services/raider.core.plugin.RaiderPlugin    <- hello.HelloToolPlugin
├── META-INF/services/raider.core.tool$Tool              <- hello.HelloGreetTool
└── META-INF/services/raider.core.plugin.RaiderHook      <- (hook plugins only)
```

Discovery rules (enforced, not advisory):

- `META-INF/services/raider.core.plugin.RaiderPlugin` — **exactly one**
  provider; zero or several is a typed failure.
- Service-file names are JVM **binary** names. `Tool` is nested inside
  `object tool`, so the file is literally `raider.core.tool$Tool` — derive it
  via `classOf[Tool].getName`, never by hand.
- The provided `Tool` provider set must EQUAL the declared `tool`
  capabilities: a declared-but-missing tool and an undeclared extra tool are
  both typed failures before any dispatch.
- Hook plugins additionally ship
  `META-INF/services/raider.core.plugin.RaiderHook`. Honesty note: the v1
  loader validates hook capabilities but does NOT instantiate hook instances
  yet; a host walks the loaded plugin's classloader with `ServiceLoader` and
  dispatches instances through `HookRunner.runHook` under the declared name.

Sources: `PluginLoader.scala:39-44` (D4 discovery rules),
`PluginLoader.scala:270-291` (exactly-one provider check),
`PluginLoader.scala:315-352` (provided set must EQUAL declared tool
capabilities), `PluginLoader.scala:205-211` (parent-delegate only),
`PluginLoader.scala:234-239` (`LinkageError`/`ServiceConfigurationError` →
typed `Malformed`), `HookRunner.scala:42,66-79` (`runHook`).

## sha256 payload pinning (integrity, NOT trust)

The manifest's `sha256` pins the jar **payload** with the canonical digest of
`PluginLoader.payloadSha256` (D2):

- every non-directory entry EXCEPT `META-INF/raider-plugin.json` itself (a
  whole-file pin would be a self-reference fixed point),
- sorted by entry name,
- each entry digested as `name ++ 0x00 ++ size ++ 0x00 ++ content`
  (UTF-8 name, decimal ASCII size, raw bytes),
- rendered as 64 lowercase hex characters.

This is why a rebuild changes the pin: the digest is a property of the exact
bytes. Build your jar in two passes — write the payload jar, compute the
digest, then rewrite the final jar with the manifest pinning it. The fixture
build in `contracts/plugins/FixturePluginsSpec.scala` does exactly this and
prints the computed pins. After ANY rebuild, recompute `sha256` in your
manifest.

The pin protects against a broken or silently substituted file between
review and install; the loader and the installer both recompute and compare
it. It does NOT make untrusted code trusted — see the honesty boundary below.

Sources: `PluginLoader.scala:115-157` (canonical `payloadSha256`),
`PluginLoader.scala:181-192` (`compareSha` mismatch message),
`FixturePluginsSpec.scala:274-315` (two-pass fixture build; the spec path is
`contracts/plugins/FixturePluginsSpec.scala`).

## Load pipeline

`PluginLoader.load(jar, base: ToolRegistry, apiVersion)` decides everything
BEFORE any class is defined:

1. read + preflight `META-INF/raider-plugin.json`;
2. recompute the payload digest and compare with the pin (mismatch = typed
   failure, nothing loaded);
3. compatibility: `apiVersion` must lie inside the manifest's inclusive api
   range (`IncompatibleApi` otherwise);
4. duplicate-name preflight of declared tool capabilities vs the base
   registry (tools are one shared namespace — a clash is
   `DuplicateName`, at preflight, never at registry build);
5. only then open the parent-delegate `URLClassLoader`, resolve the single
   `RaiderPlugin` provider (descriptor must equal the manifest), instantiate
   the declared tools and shape-validate them via the immutable
   `ToolRegistry.build`.

On success you get a `LoadedPlugin(manifest, plugin, tools, classLoader)`
(`PluginLoader.scala:370-379`).
The host composes the **overlay**: a NEW immutable registry built over the
base plus the loaded tools — an existing registry is never mutated. `close()`
releases the classloader handle best-effort; JVM hot-unload is explicitly
not guaranteed.

Sources: `PluginLoader.scala:167-179` (the five load steps in order),
`PluginLoader.scala:77-113` (`readManifest`),
`PluginLoader.scala:194-200` (duplicate-vs-base preflight),
`PluginLoader.scala:212-251` (classloader open + typed failure closes it),
`FixturePluginsSpec.scala:435-441` (overlay composition in the executable
contract).

## Building the jar

Use Scala 3 (the host pins the toolchain, currently 3.9.0). Minimal recipe:

- compile your sources against `raider-core` + `zio`/`zio-json`;
- package ONLY your classes, the services files and the manifest;
- two-pass the sha256 pin as above;
- sanity check with `PluginLoader.readManifest` and
  `PluginLoader.payloadSha256`.

The executable reference build is
`contracts/plugins/FixturePluginsSpec.scala`: it assembles all three fixture
jars at test time and loads them through the production loader. The checked-in
`examples/plugins/*/manifest.json` files show real pins produced by that
build; treat them as examples of the format, not as pins for your own rebuild.

## Installing and managing (CLI)

The `raider` CLI manages a local catalog directory (`~/.raider/plugins`,
override with the `RAIDER_PLUGINS_DIR` env var; one jar per plugin, named
`<name>-<version>.jar`):

```
raider plugin list
raider plugin install <jar>
raider plugin enable <name>
raider plugin disable <name>
raider plugin remove <name>
raider plugin update <name> <jar>
raider plugin rollback <name>
```

(Exact usage string: `PluginCli.scala:71-73`; the dispatch table
`PluginCli.scala:91-112` is the authoritative arity/source of truth.)

`install` verifies the file, reads and prefights the manifest, recomputes and
compares the payload sha256, refuses a re-install of the same plugin name,
runs the co-install preflight of the whole resulting descriptor set (a tool
name clash with ANY installed plugin fails here), then byte-copies the jar
and re-verifies the copied bytes; it never overwrites an existing target.
Output is one plain-JSON line on stdout; `list` reports
`name/version/active/file` and reports an unreadable jar with an `error`
field instead of skipping it. Exit codes for the wired `plugin` command:
`0` success, `21` typed failure.

`update <name> <jar>` (RAI-101) runs the SAME preflight as install — manifest
validity, pinned payload sha256, name match with the installed plugin,
co-install preflight of the new descriptor against the REST of the catalog —
before any file is touched. It then stages a hidden re-verified copy inside
the catalog (never matching `*.jar`, so no partial plugin is ever observable)
and swaps via two `ATOMIC_MOVE` renames: the displaced jar is preserved
byte-for-byte as the `<name>.prev.jar` backup sidecar, the staged bytes land
as `<name>-<version>.jar`. A failure between the renames triggers a
best-effort restore and says so honestly — this is not a filesystem
transaction. The disable state carries over: an update never silently
re-activates a disabled plugin. Same-version updates are allowed (a repair
path); the pinned bytes still decide.

`rollback <name>` swaps back to the preserved `.prev.jar` bytes — after
re-verifying that sidecar's OWN manifest against its pinned payload first.
Rolling back twice alternates the two versions (a true swap: the displaced
current jar becomes the new prev). `remove` drops the plugin together with
both sidecars (disabled marker + prev backup). Sidecars never appear in
`list` and never enter co-install preflight.

Sources: `PluginCli.scala:78-85` (`defaultPluginsDir`,
`RAIDER_PLUGINS_DIR`), `PluginCli.scala:117-124` (exit code mapping of the
standalone runner), `PluginCli.scala:133-155` (`list` + `error` field),
`PluginCli.scala:157-198` (`install`), `PluginCli.scala:204-224`
(`copyVerified` byte re-verification), `PluginCli.scala:230-255` (`remove`
drops prev sidecar), `PluginCli.scala:257-284` (`enable`/`disable` markers),
`PluginCli.scala:301-346` (`update`), `PluginCli.scala:356-405`
(`rollback`), `PluginCli.scala:415-451` (swap protocol + best-effort
restore), `PluginCli.scala:458-483` (hidden staged copy),
`Main.scala:85-99` (`pluginCommand` wiring; failure →
`ExitCodes.ConfigInvalid`), `result.scala:20-24` (`ConfigInvalid = 21`).

Honesty: these commands manage FILES only — they never load classes.
Activation happens when a session runs `PluginLoader.load`; `disable` writes
a `<jar>.disabled` sidecar marker that gates activation, not the JVM.

## Lifecycle honesty (read before shipping)

- The classloader is a **compatibility boundary, not a security sandbox**.
  Plugin code runs with full process authority: `System.exit`, JVM-state
  mutation and native crashes are not interceptable. Only install code you
  trust; sha256 pinning is integrity, not authorization.
- Updates apply to **new sessions** after active work drains; there is no hot
  unload — old instances finish their own lifecycle. Plugin code is never
  activated by opening a workspace; only an explicit owner install/enable
  activates it.
- A hook invocation is bounded by the host (`HookRunner`, 2000 ms default):
  expiry yields a typed `TimedOut` plus one cooperative interrupt —
  containment is not promised.

Sources: `HookRunner.scala:141-143` (`DefaultTimeoutMs = 2000L`),
`HookRunner.scala:116` (typed `TimedOut`), `HookRunner.scala:120-129`
(single cooperative interrupt, late result dropped).
- Plugin config never extends core policy or budgets; hooks cannot allow what
  core policy forbids.

## Fixtures and examples

| Example | Demonstrates |
| --- | --- |
| [hello-tool](../examples/plugins/hello-tool/README.md) | smallest plugin: one stateless tool, manifest, services |
| [scoped-search](../examples/plugins/scoped-search/README.md) | read-only tool with bounded search, typed input failures |
| [audit-hook](../examples/plugins/audit-hook/README.md) | `RaiderHook` run-start audit hook, `HookRunner` dispatch |

The executable acceptance (SDK-01..03) is
`contracts/plugins/FixturePluginsSpec.scala`: all three fixtures load against
the empty registry, tools answer through the overlay, broken variants
(duplicate tool name vs base and co-install, tampered payload) fail typed,
and the fixture build is deterministic without network.

Compile coverage (honesty note, post-review W13): the `examples/plugins`
SOURCES are compiled by the pinned-dotc build script
(`scripts/build/compile.sh`, section «examples/plugins fixture plugins»,
product classes on the classpath) — they are NOT part of any sbt module and
not compiled by `sbt compile`; the executable SDK acceptance remains the
twin fixtures of `FixturePluginsSpec` on the raiderCore test classpath.

Sources (the spec lives at `contracts/plugins/FixturePluginsSpec.scala`):
`FixturePluginsSpec.scala:469-607` (SDK-01: load, overlay, answers,
co-install), `FixturePluginsSpec.scala:608-658` (SDK-02: duplicate name and
tampered payload → typed), `FixturePluginsSpec.scala:660-685` (SDK-03:
deterministic network-free build). It runs on the raiderCore test classpath
(`build.sbt:30-31` wires `contracts/plugins` into `raiderCore/Test`).

## Not part of v1

Marketplace/distribution, jar signing, profiles loading, hook instance
loading by the loader itself, non-JVM SDKs. Changing the SPI surface is a
breaking change and requires a new ADR.
