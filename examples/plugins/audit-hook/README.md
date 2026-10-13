# audit-hook — Raider fixture plugin with a hook (RAI-096)

A plugin with NO tools: one `audit.runStart` hook using the narrow v1
`RaiderHook` SPI (RAI-084). `apply(inputJson)` is one synchronous call from JSON
string to JSON string; the hook marks the run start and embeds the input
verbatim. The host (`HookRunner`, 2000 ms by default) enforces the time boundary;
the trait itself makes no completion guarantee.

## Files

- `src/main/scala/audithook/AuditHookPlugin.scala` — `AuditHookPlugin`
  (`RaiderPlugin`, capability kind `hook`) and `AuditRunStartHook`
  (`RaiderHook`, name = `audit.runStart`).
- `manifest.json` — the `META-INF/raider-plugin.json` form.

## v1 truthfulness (important)

The loader (`PluginLoader`) validates hook capability during preflight, but does
not yet create hook INSTANCES — the loaded audit-hook plugin has an empty
`tools` collection. The provider is discovered through the standard
`ServiceLoader` using the loaded classloader
(`META-INF/services/raider.core.plugin.RaiderHook` →
`audithook.AuditRunStartHook`) and passed to `HookRunner.runHook` strictly under
its declared name. The hook runs with process privileges: the classloader is a
compatibility boundary, NOT a sandbox (ADR-025 §2.4).

## Jar layout / build / installation

Same as [hello-tool](../hello-tool/README.md), except capabilities contains
`{"kind": "hook", "name": "audit.runStart"}`, and the jar contains hook
services instead of tool services. Recalculate the pinned `sha256` after every
rebuild.

## Executable contract

`contracts/plugins/FixturePluginsSpec.scala`: loading with hook capability,
ServiceLoader discovery of the provider, and `HookRunner.runHook` under the
declared name (typed `Completed`).

References: [docs/plugins.md](../../../docs/plugins.md),
[ADR-025](../../../docs/adr/ADR-025-plugin-spi.md).
