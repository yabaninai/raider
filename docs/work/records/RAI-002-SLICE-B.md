<!-- Work record. Original in Russian; key results in English below. -->

# RAI-002.b: Tool contract freeze — record

Date: 2026-10-03. Writer: coordinator (root). Slice 2 of card RAI-002
(previously accepted slice 1 — codecs/schemas). The remaining card scope
(bundle/result schemas, DSL negatives) remains todo.

## Deliverable

Production: `modules/core/src/main/scala/raider/core/tool.scala`

- `RecoveryClass` = ReadOnly | Idempotent | Mutating (§10 recovery classes);
- `ToolCapabilities(concurrentSafe)` — explicitly declared, default false;
- `ToolCall(name, call: ToolCallId, argumentsJson)` — 1:1 with
  `ModelEvent.ToolCallReady` (loop-level contract);
- `ToolResult(call: ToolCallId, outputJson)` — result is matched by call ID (§8);
- `Tool` — name/version/description/recovery/timeoutMs/capabilities +
  `invoke(argumentsJson): ZIO[Scope, RaiderError, String]` (JSON boundary at
  core level; typed wrappers are RAI-028/031);
- `ToolRegistry.of/build` — validated: duplicate names, empty name, version < 1,
  timeout <= 0 → typed InputValidation; immutable; pure lookup.

Build: `build.sbt` — raiderCore received `testSettings` (zio-test; previously
test dependencies were only in other modules).

Tests: `modules/core/src/test/scala/raider/core/ToolContractSpec.scala` — 5
tests: valid registry + exact lookup; duplicate → RA-INP with details; empty
name / version=0 / timeout=0 → typed error; laziness (registration does not
dispatch — API-03); recovery/concurrentSafe are declared, not inferred.

## Checks (exact)

- `sbt --batch "raiderCore/test"` → exit 0, 13/13 (including config tests).
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates
  (artifacts/quality/20261003-141753/manifest.json).

## Limitations

- Bundle/result schemas + launch-facade capability remain in RAI-002 (todo).
- Tool policy (who may invoke) is out of scope here (RAI-016/023 trust boundary).
