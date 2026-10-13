<!-- Work record. Original in Russian; key results in English below. -->

# RAI-005.a: Provider config — record

Date: 2026-10-03. Writer: coordinator (root). Lane execution cancelled (model-contention escalation); card executed from the same packet specification. Dependencies: RAI-002 slice1 (versioned envelope), RAI-004 (gates) — ready.

## Deliverable

Production: `modules/core/src/main/scala/raider/core/config/Config.scala`

- `WireKind` = OpenAICompatible | AnthropicCompatible (zio-json codec);
- `RoleBinding(profile, modelOverride, requiredOption)` — model roles;
  `RoleOption.known` whitelist (`"json_mode"`, `"tools"`, `"streaming"`);
- `Ceilings` (maxSteps/maxOutputTokens/maxConcurrentModelCalls/hardCapMicroUsd)
  + `narrowed(by)` — overrides may ONLY narrow (widening → typed denial, CFG-03);
- `CredentialRef.parse` — env variable names only `[A-Z_][A-Z0-9_]*`;
  secret values cannot be represented in the config by construction;
- `Endpoint.parse` — non-local hosts must use https (localhost/127.0.0.1/::1
  allow http); API prefix is preserved as configured;
- `ProviderProfile` (name/wire/endpoint/apiPrefix/credential/model/priceKnown);
- `RaiderConfig.build(ConfigWire)` — full validation BEFORE any dispatch:
  schemaVersion (via supportedSchemaVersions), at least 1 role, duplicate
  profile names, role→missing profile, unknown required option, negative/zero
  caps; `profileForRole` applies modelOverride;
- `credentials.resolve(config, env: Map[String,String])` — bounded resolution
  against an EXPLICIT env map (core does not read ambient env); diagnostics are
  redacted (`value=<redacted>`), missing key → typed RA-AUTH (CFG-02).

Tests: `modules/core/src/test/scala/raider/core/config/ConfigSpec.scala` — 8
tests: mixed-role resolution without service calls + prefix preserved +
override; missing role / duplicate names / missing env key (RA-AUTH); insecure
remote URL (constructed as codec — bypassing smart constructor, caught by
build()) / negative caps → RA-CFG; unknown required option (`telepathy`); secret
not present in diagnostics; reload does not affect running snapshot; narrowing
allowed / widening refused; decode checks version before payload, malformed →
denial.

## Checks (exact)

- `sbt --batch "raiderCore/test"` → exit 0, 13 passed / 0 failed (8 config +
  5 ToolContract RAI-002.b).
- Runtime stability: 3 consecutive `sbt --batch "raiderRuntime/test"` → 33/33.
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates,
  manifest artifacts/quality/20261003-141753/manifest.json.

## Findings during the card

1. Insecure-URL fixture built with `Endpoint.parse(...).toOption.get` failed
   before reaching the tested branch — the negative case is constructed as a
   literal (like the real codec path), confirming validation in build().
2. `Either.isSuccess` does not exist → use `isRight` (test fix).
3. `profileForRole` initially did not apply modelOverride — applied during resolution.

## Limitations

- Wire JSON config format is v1 (`ConfigWire`); `priceKnown` marks known/unknown
  prices for a future bridge into BudgetLimits (`RAI-009 CostEstimate`); the
  bridge itself is RAI-010.
- Endpoint/credential smart constructors are not private (codec constructs
  directly) — validation is mandatory in build(); documented by a test.
