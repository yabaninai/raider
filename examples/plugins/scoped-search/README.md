# scoped-search — Raider fixture plugin with bounded search (RAI-096)

Read-only tool `scoped.search`: literal search by file names strictly inside a
single specified directory. It demonstrates tool parameters through
`parametersJsonSchema`, honest bounds (bounded scan ≤ 500 entries, `truncated`
instead of silent truncation), and typed input errors
(`RaiderError.InputValidation` — the `Tool`'s native error channel, not
exceptions across the boundary).

## Files

- `src/main/scala/scopedsearch/ScopedSearchPlugin.scala` —
  `ScopedSearchPlugin` (`RaiderPlugin`) + `ScopedSearchTool` (`Tool`).
- `manifest.json` — shape of `META-INF/raider-plugin.json`.

## Arguments

```json
{"root": "<directory>", "query": "<filename substring>"}
```

Both fields are required; missing/non-directory `root` and empty `query` are
typed rejections. Result: `{"root":..., "query":..., "matches":[...],
"truncated":bool}` — sorted file names.

## Jar layout / build / installation

Identical to [hello-tool](../hello-tool/README.md): services are
`raider.core.plugin.RaiderPlugin` → `scopedsearch.ScopedSearchPlugin` and
`raider.core.tool$Tool` → `scopedsearch.ScopedSearchTool`; two-pass build with
`PluginLoader.payloadSha256` pinned; recalculate `sha256` after rebuilding.

## Executable contract

`contracts/plugins/FixturePluginsSpec.scala`: loading, search in a fixture
tree (`alpha.txt`, `beta.md`, `sub/gamma.txt`), typed rejection for a missing
directory, co-install overlay together with hello-tool.

References: [docs/plugins.md](../../../docs/plugins.md),
[ADR-025](../../../docs/adr/ADR-025-plugin-spi.md).
