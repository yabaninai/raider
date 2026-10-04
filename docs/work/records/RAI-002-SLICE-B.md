<!-- Work record. Original in Russian; key results in English below. -->

# RAI-002.b: Tool contract freeze — record

Дата: 2026-10-03. Writer: coordinator (root). Slice 2 карточки RAI-002
(пер во休 slice 1 — codecs/schemas — accepted ранее). Остаток карточки
(bundle/result schemas, DSL-негативы) остаётся todo.

## Deliverable

Production: `modules/core/src/main/scala/raider/core/tool.scala`

- `RecoveryClass` = ReadOnly | Idempotent | Mutating (§10 recovery classes);
- `ToolCapabilities(concurrentSafe)` — заявляется явно, default false;
- `ToolCall(name, call: ToolCallId, argumentsJson)` — 1:1 с
  `ModelEvent.ToolCallReady` (loop-level контракт);
- `ToolResult(call: ToolCallId, outputJson)` — результат мэтчится по call-id (§8);
- `Tool` — name/version/description/recovery/timeoutMs/capabilities +
  `invoke(argumentsJson): ZIO[Scope, RaiderError, String]` (JSON-граница на
  уровне core; typed-обёртки — RAI-028/031);
- `ToolRegistry.of/build` — validated: duplicate names, пустое имя, version < 1,
  timeout <= 0 → typed InputValidation; immutable; lookup чистый.

Build: `build.sbt` — raiderCore получил `testSettings` (zio-test; ранее test-депы
были только в других модулях).

Tests: `modules/core/src/test/scala/raider/core/ToolContractSpec.scala` — 5
тестов: валидный registry + точный lookup; duplicate → RA-INP с деталью;
empty name / version=0 / timeout=0 → typed; ленивость (регистрация не
диспетчерствует — API-03); recovery/concurrentSafe заявляются, не выводятся.

## Проверки (exact)

- `sbt --batch "raiderCore/test"` → exit 0, 13/13 (с config-тестами).
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates
  (artifacts/quality/20261003-141753/manifest.json).

## Limitations

- Bundle/result schemas + launch-facade capability — остаток RAI-002 (todo).
- Tool-политика (кто может вызывать) — не здесь (RAI-016/023 trust boundary).
