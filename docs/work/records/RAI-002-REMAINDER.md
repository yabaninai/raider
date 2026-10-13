<!-- Work record. Original in Russian; key results are translated below. -->

# RAI-002-REMAINDER: work record (bundle/result schemas + launch capability)

Status: done (schema/codec slice; CLI/bundle-loading surfaces remain cards
RAI-022/024). Date: 2026-10-03. Baseline: previous last-passing gate
artifacts/quality/20261003-214446 (REPL-LOOP).

## Resulting behavior

The RAI-002 remainder slice adds versioned JSON schemas to core (data only; no
dispatch):

- `raider.core.bundle.BundleManifest` (+`BundleEntry`): compiled bundle
  manifest — schemaVersion/bundleId/entries(name, mainClass, version)/
  declaredTrustLevel; `validate` checks unknown version, empty IDs, missing
  entries, duplicates, and version <1. `entry(name)` returns typed
  InputValidation for unknown entries, with the known names listed (never an
  arbitrary fallback).
- `raider.core.result.RunResult`: `result.json` schema (`ci-runtime` §7) —
  ExecutionStatus (terminal set in §4), exitCode from the STABLE `ExitCodes`
  map (0/10/20/21/22/23/24/25/26/27/130/143, `ci-runtime` §8), taskVerdict
  (the verifier's verdict; there are deliberately no fields where a model
  response could present itself as a verdict), checks
  (Passed/Failed/Errored/Skipped), artifacts
  (artifactId/sha256/mediaType/sizeBytes/path), output (codec + version +
  JSON), error (flat RaiderError wire code), timestamps. Validation checks
  unknown exitCode, 64-hex sha256, sizeBytes>=0, non-empty paths/timestamps,
  and well-formed output JSON.
- `raider.core.launch`: `LaunchCapability` (explicit capability for launch
  signatures, installed by prelude/loader — core has no global launch hook;
  `ReplSession` in repl satisfies the contract) + `DefinitionContext`
  (explicit immutable snapshot of registered agents/workflows; duplicate or
  empty names = RA-INP; lookup of an unknown name returns a structured error
  listing known names, §11).

Canonical round-trip codecs (zio-json); versions are discriminated by
schemaVersion validation BEFORE payload, following the existing
VersionedEnvelope style.

## Changed paths

```
ca144fbd…  modules/core/src/main/scala/raider/core/bundle.scala
5926a0d8…  modules/core/src/main/scala/raider/core/result.scala
eaecc448…  modules/core/src/main/scala/raider/core/launch.scala
e48a58eb…  modules/core/src/test/scala/raider/core/RemainderSchemasSpec.scala
1a539ec5…  modules/core/src/test/scala/raider/core/ContractsSpec.scala (+9 checks, 26→35)
```

Public schemas are NEW types; existing codecs/events/envelope were not changed
(the previous 26 ContractsSpec checks remain and pass).

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| RemainderSchemasSpec (bundle 3 + result 4 + launch 3) | 10/10 pass | raiderCore/test |
| ContractsSpec (canonical wire, extended) | ALL PASS, 35 checks, exit 0 | gate core-contracts-spec |
| Full sbt test | 121 tests, 0 failed, exit 0 | /tmp/full-test-C.log |
| Quality gates | 6/6 passed | artifacts/quality/20261003-215025/manifest.json |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCore/test"` | 0 | /tmp/core-C4.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCore/Test/runMain raider.core.ContractsSpec"` | 0 (ALL PASS) | /tmp/contracts-C.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (121) | /tmp/full-test-C.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261003-215025/ |

## Limitations / obligations

- CLI bundle loading, jar packaging, and checks.junit.xml/events.jsonl/
  summary.md reporters are cards RAI-022/024/025/026; this slice contains core
  schemas only.
- The `input` schema (RAI-002 “JSON schemas input/context/...”) — input context
  remains in RunContext + the OutputPayload slice; complete input policy is
  RAI-030.
- LaunchCapability is a contract; concrete `using` applications in new
  REPL/CLI launch signatures are being added in RAI-018/022.

## Review and next task

No independent review was conducted (coordinator session). Next phase:
RAI-006.b (testkit virtual time / controllable promises, MOCK-03).
