# NIGHTLY-PHASE-1: work record

Status: done. Date: 2026-10-05T02:05Z. Baseline commit/source: d8da7ff (docs: add nightly improvement master prompt and parallel runner).

## Resulting behavior

Quality infrastructure additions (master prompt Phase 1, tasks 1.1–1.6):

- sbt-scalafmt 2.5.2 wired; `ThisBuild / scalafmtOnCompile := false`; whole tree formatted (83 files, whitespace-only); `scalafmt-check` gate added to `static` + `fast` profiles.
- sbt-scalafix 0.12.1 wired with `.scalafix.conf` (DisableSyntax with `noNulls`, LeakingImplicitClassVal); `scalafix-check` gate added to `fast`. ProcedureSyntax is Scala-2-only and cannot be enabled (master-prompt list adjusted, documented in `.scalafix.conf`).
- All literal `null` usages eliminated from product + test code (15 sites) so the DisableSyntax null ban is green: ProcessTool guard, AgentLoopDelegationSpec backends (now `TurnBackend(Vector.empty)`), MainBridge ThreadLocal unwrap, repl Main console/EOF paths (Option-based readLine), ChatLoop limits fallback + tracer match + stdin EOF, BundleLoader jar-entry lookup.
- `scripts/quality/forbidden_apis.py`: scanner for Thread.stop, Await.result/ready without timeout, Runtime.exec, System.exit outside `raider/cli/Main.scala`, hardcoded secret shapes, Double money arithmetic. `--self-test` embedded positive/negative fixtures (gate runs with `--self-test`).
- Gate registry expanded: `scalafmt-check`, `scalafix-check`, `forbidden-apis`, `repl-smoke`, `cli-smoke` gates; new component profiles `tools` (sbt-test), `transport` (sbt-test), `repl` (sbt-test + repl-smoke), `ci` (sbt-test + cli-smoke); `fast` now 9 gates. `quality.py load_policy` validates gate references for all component profiles (check extension, not relaxation).
- Two scalameta parser-gap workarounds: leading `=` bindings in for-comprehensions hoisted to `val` in ToolContractSpec + CompositionSpec (semantics unchanged, comments document why).

## Changed paths

- `project/plugins.sbt` (new: scalafmt + scalafix plugins)
- `build.sbt` (scalafmtOnCompile := false)
- `.scalafix.conf` (new)
- `.scalafmt.conf` (pre-existing, unchanged)
- `scripts/quality/forbidden_apis.py` (new)
- `scripts/quality/quality.py` (component-profile validation list)
- `scripts/quality-policy/registry.json` (5 new gates, 4 new profiles, fast/static expanded)
- `modules/tools/src/main/scala/raider/tools/process/ProcessTool.scala` (null-free guard)
- `modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala` (null-free backends)
- `modules/repl/src/main/scala/raider/repl/MainBridge.scala`, `modules/repl/src/main/scala/raider/repl/Main.scala` (null-free interop)
- `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala`, `modules/cli/src/main/scala/raider/cli/run/BundleLoader.scala` (null-free fallbacks/lookups)
- `modules/core/src/test/scala/raider/core/ToolContractSpec.scala`, `modules/dsl/src/test/scala/raider/dsl/composition/CompositionSpec.scala` (parser-gap hoists)
- 83 scala files reformatted by scalafmt (whitespace)

No public API or schema changes; no new product dependencies (plugins are build-tooling only).

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| sbt compile exit 0 (-Werror) | 0 | build log (sbt --batch compile) |
| sbt test 0 failures | 10 suites, all "0 tests failed" | /tmp/test-out.txt |
| scalafmtCheckAll | exit 0 | gate log artifacts/quality/20261005-020242/scalafmt-check.log |
| scalafixAll --check | exit 0 | gate log artifacts/quality/20261005-020242/scalafix-check.log |
| forbidden-apis self-test + scan | PASS, 82 files, 0 violations | gate log artifacts/quality/20261005-020242/forbidden-apis.log |
| full-tree fast profile | 9/9 gates passed | artifacts/quality/20261005-020242/manifest.json |
| profiles tools,transport,repl,ci | passed (sbt-test, cli-smoke, repl-smoke) | artifacts/quality/20261005-020348/manifest.json |
| repl_smoke.sh standalone | PASS (exit 0, all assertions) | artifacts/repl-fast/nontty-transcript.txt |
| cli_smoke.sh standalone | PASS (exit 0, run.json Succeeded) | gate log |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | build log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | /tmp/test-out.txt |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch scalafmtAll` / `scalafmtCheckAll` | 0 | gate log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "scalafixAll --check"` | 0 | /tmp/scalafix-out.txt |
| `python3 scripts/quality/forbidden_apis.py --self-test` | 0 | gate log |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-020242/manifest.json |
| `COURSIER_CACHE=/tmp/cc-master make quality-profile PROFILES="tools,transport,repl,ci"` | 0 | artifacts/quality/20261005-020348/manifest.json |
| `sh scripts/quality/repl_smoke.sh` | 0 | /tmp/repl-smoke-out.txt |
| `sh scripts/quality/cli_smoke.sh` | 0 | stdout |

## Limitations / blockers

- scalafix `ProcedureSyntax` is Scala-2-only per scalafix itself; rule dropped from the master-prompt list (Scala 3 has no procedure syntax to ban).
- DisableSyntax configured with `noNulls` only: `noVars`/`noThrows`/`noAsInstanceOf` are intentionally off — DottyReplEngine session vars, the documented unreachable-invariant throw (Composition) and transport casts are existing designs; refactoring them is out of scope for lint enablement.
- scalafix (scalameta parser) cannot parse a Scala 3 for-comprehension that opens with an `=` binding; two tests were hoisted to `val` (documented in-code).
- One `sbt --batch test` invocation hit a 15-minute wall-clock timeout caused by orphaned forked test JVMs from a killed run; after killing the strays the suite passed in ~1.5 min. No test was skipped or relaxed.
- cli-smoke depends on `modules/cli/target/demo-bundle.jar` produced by `raiderCli/test`; in the `ci` profile `sbt-test` is ordered first for this reason (setup failure 21 otherwise, per script contract — not a silent pass).

## Review and next task

Self-executed nightly run; no reviewer assigned. Next: Phase 2 (core improvements: CLI streaming, session persistence, compaction, fs_patch, fs_tree, proc_run parsing).
