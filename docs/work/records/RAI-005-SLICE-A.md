<!-- Work record. Original in Russian; key results in English below. -->

# RAI-005.a: Provider config — record

Дата: 2026-10-03. Writer: coordinator (root). Lane-исполнение отменено
(model-contention escalation), карточка исполнена по тому же packet-спеку.
Deps: RAI-002 slice1 (versioned envelope), RAI-004 (gates) — готовы.

## Deliverable

Production: `modules/core/src/main/scala/raider/core/config/Config.scala`

- `WireKind` = OpenAICompatible | AnthropicCompatible (zio-json codec);
- `RoleBinding(profile, modelOverride, requiredOption)` — роли моделей;
  `RoleOption.known` whitelist ("json_mode","tools","streaming");
- `Ceilings` (maxSteps/maxOutputTokens/maxConcurrentModelCalls/hardCapMicroUsd)
  + `narrowed(by)` — overrides могут ТОЛЬКО сужать (widening → typed отказ,
  CFG-03);
- `CredentialRef.parse` — только имена env-переменных `[A-Z_][A-Z0-9_]*`;
  значения секретов в конфиге невозможны по построению;
- `Endpoint.parse` — non-local host обязан https (localhost/127.0.0.1/::1
  разрешают http); API prefix сохраняется как сконфигурирован;
- `ProviderProfile` (name/wire/endpoint/apiPrefix/credential/model/priceKnown);
- `RaiderConfig.build(ConfigWire)` — полная валидация ДО любого dispatch:
  schemaVersion (через supportedSchemaVersions), ≥1 роль, duplicate profile
  names, role→missing profile, unknown required option, negative/zero caps;
  `profileForRole` применяет modelOverride;
- `credentials.resolve(config, env: Map[String,String])` — bounded resolution
  против ЯВНОЙ env-map (core не читает ambient env); diagnostics redacted
  (`value=<redacted>`), missing key → typed RA-AUTH (CFG-02).

Tests: `modules/core/src/test/scala/raider/core/config/ConfigSpec.scala` — 8
тестов: mixed-roles резолюция без service calls + prefix preserved + override;
missing role / duplicate names / missing env key (RA-AUTH); insecure remote URL
(конструируется как codec — мимо smart-конструктора, build() ловит) / negative
caps → RA-CFG; unknown required option ('telepathy'); секрет не попадает в
диагностику; reload не трогает running snapshot; narrowing ok / widening
refused; decode: версия проверяется до payload, malformed → отказ.

## Проверки (exact)

- `sbt --batch "raiderCore/test"` → exit 0, 13 passed / 0 failed (8 config +
  5 ToolContract RAI-002.b).
- Стабильность runtime: 3 подряд `sbt --batch "raiderRuntime/test"` → 33/33.
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates,
  manifest artifacts/quality/20261003-141753/manifest.json.

## Находки в ходе карточки

1. Insecure-URL fixture, построенная через `Endpoint.parse(...).toOption.get`,
   падала раньше тестируемой ветки — негативный кейс строится литерально
   (как реальный codec-путь), validation в build() подтверждена.
2. `Either.isSuccess` не существует → isRight (тест-фикс).
3. `profileForRole` изначально не применял modelOverride — применён в резолюции.

## Limitations

- Формат wire-JSON конфига — v1 (ConfigWire): поле `priceKnown` маркирует
  known/unknown цену для будущего bridge в BudgetLimits (RAI-009 CostEstimate);
  сам bridge — RAI-010.
- Endpoint/credential smart-конструкторы не закрыты приватно (codec строит
  напрямую) — валидация обязательна в build(); это задокументировано тестом.
