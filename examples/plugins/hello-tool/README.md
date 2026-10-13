# hello-tool — Minimal Raider Fixture Plugin (RAI-096)

The smallest useful plugin: one STATELESS tool, `hello.greet`
(`{"name": "..."}` → `{"message": "hello[, name]"}`). It demonstrates the complete
SPI minimum: entry class, tool class, manifest, and services files.

## Files

- `src/main/scala/hello/HelloToolPlugin.scala` — `HelloToolPlugin`
  (`RaiderPlugin`, descriptor only) + `HelloGreetTool` (`raider.core.Tool`).
- `manifest.json` — the `META-INF/raider-plugin.json` form used for installation.

## Jar Layout (what PluginLoader checks)

```
hello-tool-1.0.0.jar
├── hello/HelloToolPlugin.class
├── hello/HelloGreetTool.class
├── META-INF/raider-plugin.json          ← manifest.json; sha256 pins the payload
├── META-INF/services/raider.core.plugin.RaiderPlugin   → hello.HelloToolPlugin
└── META-INF/services/raider.core.tool$Tool             → hello.HelloGreetTool
```

Service file names are JVM binary names; `raider.core.tool$Tool` is written using
`classOf[Tool].getName` (`Tool` is nested in `object tool`), never by hand.
The number of tool providers must exactly match the number declared in `capabilities`;
fewer or more results in a typed rejection.

## Build

Scala 3 (3.9.0); dependencies are host-provided: the host supplies raider-core, zio, and
zio-json through parent-delegation (ADR-025 §2.4); child-first is forbidden, and only
the plugin's own classes go into the jar. The canonical executable build for these
fixtures is `contracts/plugins/FixturePluginsSpec.scala` (two passes: payload
jar → `PluginLoader.payloadSha256` → final jar with a manifest pinning
the digest). Build yours the same way; after EVERY rebuild, recalculate
`sha256` in the manifest — the pin is a property of the exact bytes, integrity, not trust
(ADR-025 §2.2).

## Install and Verify

```
raider plugin install <path>/hello-tool-1.0.0.jar
raider plugin list
```

`install` checks the manifest, recalculates the payload sha256 and verifies the pin, then
copies the bytes into the plugin directory (`~/.raider/plugins`, override:
`RAIDER_PLUGINS_DIR`). Activation happens in a NEW session via `PluginLoader.load`:
install only places files and does not load classes.

## Executable Contract

`contracts/plugins/FixturePluginsSpec.scala` (RAI-096, SDK-01..03): `PluginLoader.load`,
tool in the overlay registry, invocation, malformed variants → typed `PluginError`.

References: [docs/plugins.md](../../../docs/plugins.md),
[ADR-025](../../../docs/adr/ADR-025-plugin-spi.md).
