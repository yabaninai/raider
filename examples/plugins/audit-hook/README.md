# audit-hook — fixture-плагин Raider с хуком (RAI-096)

Плагин БЕЗ tools: один hook `audit.runStart` на узком v1 `RaiderHook` SPI
(RAI-084). `apply(inputJson)` — один синхронный вызов JSON-строка →
JSON-строка; хук помечает старт прогона и вкладывает вход verbatim. Временную
границу держит хост (`HookRunner`, 2000 ms по умолчанию) — trait сам ничего не
гарантирует о завершении.

## Файлы

- `src/main/scala/audithook/AuditHookPlugin.scala` — `AuditHookPlugin`
  (`RaiderPlugin`, capability kind `hook`) + `AuditRunStartHook`
  (`RaiderHook`, name = `audit.runStart`).
- `manifest.json` — форма `META-INF/raider-plugin.json`.

## Честность v1 (важно)

Загрузчик (PluginLoader) валидирует hook-capability на preflight, но
ИНСТАНЦИИ хуков пока не создаёт — у загруженного audit-hook плагина `tools`
пуст. Провайдер находится стандартным ServiceLoader по загруженному
classloader'у (`META-INF/services/raider.core.plugin.RaiderHook` →
`audithook.AuditRunStartHook`) и передаётся в `HookRunner.runHook` строго под
объявленным именем. Хук исполняется с полномочиями процесса: classloader —
граница совместимости, НЕ sandbox (ADR-025 §2.4).

## Jar-layout / сборка / установка

Идентично [hello-tool](../hello-tool/README.md), но в capabilities —
`{"kind": "hook", "name": "audit.runStart"}`, и вместо tool-services в jar
лежит hook-services. Пин `sha256` пересчитывать после каждой пересборки.

## Исполняемый контракт

`contracts/plugins/FixturePluginsSpec.scala`: загрузка с hook-capability,
ServiceLoader-дискавери провайдера, `HookRunner.runHook` под объявленным
именем (typed `Completed`).

Ссылки: [docs/plugins.md](../../../docs/plugins.md),
[ADR-025](../../../docs/adr/ADR-025-plugin-spi.md).
