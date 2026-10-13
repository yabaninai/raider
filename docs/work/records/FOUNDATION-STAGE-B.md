<!-- Work record. Original in Russian; key results in English below. -->

# FOUNDATION-STAGE-B + RAI-002 slice 1: work record

Status: done (review pending). Date: 2026-10-03 (UTC). Baseline: continues FOUNDATION-STAGE-A; tree is untracked (there are still no commits under the rule).

## Resulting behavior

1. Independent F0 re-review (round 2, read-only explore agent): all 13/13 round-1 findings were confirmed fixed. Narrow findings were addressed in this session: `bootstrap.sh` was actually run (inventory in `artifacts/toolchain/`); fault-injection self-tests archived; `MetadataObserved` added; error families completed to all 18 in §14; `Usage.plus` no longer silently mixes currencies; `Task` sealed; negative gate requires a type mismatch specifically; OAI-05 grep is tied to the scenario.
2. RAI-003 stage B: sbt installed (brew; runner 2.0.10), `project/build.properties` pins sbt 1.11.4; canonical multi-project `build.sbt` (raider-core/runtime/dsl/testkit), Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0, `-Werror`; `.scalafmt.conf`; Makefile (bootstrap/compile/build-script/spike-*). `sbt --batch compile` exited 0. The dotc path `scripts/build/compile.sh` still works (exit 0, 9 files).
3. RAI-002 slice 1: canonical JSON codecs (IDs → strings; Usage; ModelEvent with `"type"` discriminator; flat-wire RaiderError rejecting unknown codes; VersionedEnvelope validating `schemaVersion` BEFORE payload; RunContext validating required fields) + `ContractsSpec` (main-based, exit 0/1): 26 checks — roundtrip for all events/errors/envelopes, negative cases (unknown error code, malformed JSON, unknown schemaVersion, empty system, attempt<1). ALL PASS (26 checks in archived gate log), exit 0.

## Changed paths

- `modules/core/src/main/scala/raider/core/{backend,codecs,schemas}.scala` (updated/new)
- `modules/core/src/test/scala/raider/core/ContractsSpec.scala` (new)
- `modules/{runtime,dsl,testkit}/src/main/scala/raider/{runtime,dsl,testkit}/` (skeletons)
- `build.sbt`, `project/build.properties`, `.scalafmt.conf`, `Makefile` (new)
- `scripts/build/compile.sh` (+zio-json/magnolia in CP)
- `scripts/bootstrap.sh` (actually run), `docs/work/{board.md, records/*}`, `docs/prompts/opencode-autonomous-scala.txt` (status correction)

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| F0 re-review | 13/13 fixed; narrow findings closed | `artifacts/f0-final-rereview/{bootstrap,build,selftest-*,suite-*}.log` |
| sbt compile | exit 0 | stdout from session (`sbt --batch compile`) |
| ContractsSpec | ALL PASS (27), exit 0 | sbt Test/runMain output |
| Dual build | dotc path exit 0 (9 files) | `artifacts/build.log` |
| Plan package | `validate.py` exit 0 | `artifacts/plan-review/report.json` |

## Limitations / blockers

- JDK history is mixed: PATH Java 21.0.12.1, sbt uses its own JVM (gate logs), spikes ran on 20.0.1. A unified JDK policy (21 target + JAVA_HOME in gates) is RAI-004 iteration 2.
- scalafmt/scalafix plugins are not connected (static gate = compile with `-Werror`); formatting is an RAI-004+ follow-up after versions are chosen.
- RAI-002 remainder: DSL composition fixtures (`all`/`fork` with RAI-017), full bundle/context schemas, and REPL contract portions are the next slices.
- Runtime/dsl/testkit modules are empty skeletons; populate them through F1 cards.

## Review and next task

Review: pending (fresh explore reviewer for this diff). Next: RAI-004 quality runner MVP (registry/classifier/evidence + Makefile quality-* targets) → then F1.
