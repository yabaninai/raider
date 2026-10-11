# scoped-search — fixture-плагин Raider с ограниченным поиском (RAI-096)

READ-ONLY tool `scoped.search`: литеральный поиск по ИМЕНАМ файлов строго
внутри одного заданного каталога. Демонстрирует: параметры tool через
`parametersJsonSchema`, честную ограниченность (bounded scan ≤ 500 записей,
`truncated` вместо тихой обрезки) и typed-ошибки ввода
(`RaiderError.InputValidation` — родной error-канал `Tool`, не исключения
через шов).

## Файлы

- `src/main/scala/scopedsearch/ScopedSearchPlugin.scala` —
  `ScopedSearchPlugin` (`RaiderPlugin`) + `ScopedSearchTool` (`Tool`).
- `manifest.json` — форма `META-INF/raider-plugin.json`.

## Аргументы

```json
{"root": "<каталог>", "query": "<подстрока имени>"}
```

Оба поля обязательны; отсутствующий/недиректорийный `root` и пустой `query` —
typed отказ. Результат: `{"root":..., "query":..., "matches":[...],
"truncated":bool}` — имена файлов, отсортированы.

## Jar-layout / сборка / установка

Идентично [hello-tool](../hello-tool/README.md): services —
`raider.core.plugin.RaiderPlugin` → `scopedsearch.ScopedSearchPlugin` и
`raider.core.tool$Tool` → `scopedsearch.ScopedSearchTool`; двухпроходная
сборка с пином `PluginLoader.payloadSha256`; после пересборки пересчитать
`sha256`.

## Исполняемый контракт

`contracts/plugins/FixturePluginsSpec.scala`: загрузка, поиск по fixture-дереву
(`alpha.txt`, `beta.md`, `sub/gamma.txt`), typed отказ на отсутствующем
каталоге, co-install overlay вместе с hello-tool.

Ссылки: [docs/plugins.md](../../../docs/plugins.md),
[ADR-025](../../../docs/adr/ADR-025-plugin-spi.md).
