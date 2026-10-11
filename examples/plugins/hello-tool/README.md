# hello-tool — минимальный fixture-плагин Raider (RAI-096)

Самый маленький полезный плагин: один STATELESS tool `hello.greet`
(`{"name": "..."}` → `{"message": "hello[, name]"}`). Демонстрирует полный
SPI-минимум: entry-класс, tool-класс, манифест, services-файлы.

## Файлы

- `src/main/scala/hello/HelloToolPlugin.scala` — `HelloToolPlugin`
  (`RaiderPlugin`, только дескриптор) + `HelloGreetTool` (`raider.core.Tool`).
- `manifest.json` — форма `META-INF/raider-plugin.json` для установки.

## Jar-layout (что проверяет PluginLoader)

```
hello-tool-1.0.0.jar
├── hello/HelloToolPlugin.class
├── hello/HelloGreetTool.class
├── META-INF/raider-plugin.json          ← manifest.json, sha256 пиннит payload
├── META-INF/services/raider.core.plugin.RaiderPlugin   → hello.HelloToolPlugin
└── META-INF/services/raider.core.tool$Tool             → hello.HelloGreetTool
```

Имена services-файлов — JVM binary names; `raider.core.tool$Tool` пишется через
`classOf[Tool].getName` (Tool вложен в `object tool`), никогда вручную.
Провайдеров tool ровно столько, сколько объявлено в `capabilities` — меньше и
больше — typed отказ.

## Сборка

Скала 3 (3.9.0), зависимости — хостовые: raider-core, zio, zio-json хост
предоставляет через parent-delegation (ADR-025 §2.4), child-first запрещён, в
jar кладутся только собственные классы. Каноническая исполняемая сборка этих
фикстур — `contracts/plugins/FixturePluginsSpec.scala` (двухпроходная: payload
jar → `PluginLoader.payloadSha256` → финальный jar с манифестом, пиннящим
дайджест). Свою сборку делайте так же; после ЛЮБОЙ пересборки пересчитайте
`sha256` в манифесте — пин это свойство точных байт, integrity, не trust
(ADR-025 §2.2).

## Установка и проверка

```
raider plugin install <path>/hello-tool-1.0.0.jar
raider plugin list
```

install проверяет манифест, пересчитывает payload sha256 и сверяет пин, затем
копирует байты в каталог плагинов (`~/.raider/plugins`, override:
`RAIDER_PLUGINS_DIR`). Активация — в НОВОЙ сессии при `PluginLoader.load`:
install только кладёт файлы и не загружает классы.

## Исполняемый контракт

`contracts/plugins/FixturePluginsSpec.scala` (RAI-096, SDK-01..03): загрузка
`PluginLoader.load`, tool в overlay-реестре, вызов, битые варианты → typed
`PluginError`.

Ссылки: [docs/plugins.md](../../../docs/plugins.md),
[ADR-025](../../../docs/adr/ADR-025-plugin-spi.md).
