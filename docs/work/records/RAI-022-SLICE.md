<!-- Work record. Original in Russian; key results in English below. -->

# RAI-022-SLICE: work record — headless runner + bundle loading

Status: done (slice: RUN-01/02/03 skeleton + remainder [--agent + SIGTERM
bounded stop]; packaged distribution/CI adapters are milestone obligations).
Date: 2026-10-04 (updated the same day). Baseline: previous last-passing gate
artifacts/quality/20261004-064809 (DELEGATION).

## Resulting behavior (remainder, same day)

**`--agent` mode**: `run --agent scout|worker --input … --out … --mock` —
built-in agents (whitelist `CliArgs.BuiltInAgents`) on `MockBackend`
(`[mock]` marker IN RESPONSE TEXT — fixture discipline; tools=Unsupported,
honestly); `--agent` XOR `--bundle/--workflow` (RA-CFG). **Runner does NOT
double-admit**: bundle programs are opaque Tasks, runner provides an outer slot;
AgentLoop performs ITS OWN admission inside — nested take at maxLLM=1 = deadlock
(caught by fixture, separated by target branches).

**SIGTERM bounded stop**: shutdown-hook → interrupt fibers → wait for finalizers
≤ 5 s → RETURN (never System.exit from hook — exit from a hook deadlocks
Shutdown.runHooks, caught by probe). POSIX termination after hooks yields 143;
normal completion marks `done` — hook is an immediate no-op.

**Three zio/Scala 3.9 traps in this remainder** (all caught by probes):
1. `.fork` (non-daemon) on top-level Unsafe: supervisor interrupts child when
   parent root fiber finishes → runner died immediately → `.forkDaemon`.
2. `System.exit` does not flush piped stdout → println output was lost → explicit
   flush.
3. Scala 3.9 E046-cyclic on member-select chains (`x.call.y`) in AgentLoop file
   — destructured fields one by one; added missing import ToolCallId (previously
   the type was inferred, not named).

Headless runner WITHOUT compiler/JLine/network (headless distribution rule):

- `raider.core.BundleWorkflow` — frozen v1 SPI (allowed path
  `modules/core/**/bundle/**`): public class, no-arg constructor,
  `name` + `program: Program[String,String]`; programs are lazy — instantiation
  does not dispatch anything (API-03);
- `raider.cli.run.BundleLoader` — manifest from `META-INF/raider-bundle.json`
  inside the jar (validate → BundleManifest), instantiate entries through an
  isolated URLClassLoader; class name must match entry; NO Desktop network
  resolution — local jar only;
- `raider.cli.run.CliArgs` — pure parser for `run --bundle --workflow
  (--input|--input-json) --out [--mock]`; unknown/duplicate/missing → typed
  RA-CFG; stdin is NEVER read (safe with closed stdin);
- `raider.cli.run.HeadlessRunner` — preflight BEFORE spend (manifest → load →
  entry → input; RUN-02), then ONE admitted dispatch (`admission.withSlot`,
  root = cli_<uuid>); artifacts in `<out>/work-<uuid>/` (RUN-03: separate
  directories for concurrent runs): `run.json` (RunResult, VALIDATED before
  writing, atomic temp+rename; schema check is real, its result is inside the
  document), `events.jsonl` (versioned envelopes, bounded), `summary.md` with
  REQUIRED fixture marker when `--mock` (mock is never presented as live);
- `--mock` is required in the slice: live providers are not connected — without
  the flag, typed refusal (exit 21);
- `ExitCodes` mapping (ci-runtime §8): ALL 18 error families → codes
  10/20/21/22/23/24/25/26/27; success 0; no catch-all zero;
- `scripts/quality/cli_smoke.sh` — REAL Main process with closed stdin
  (`printf '' |`), exit 0 + run.json Succeeded + fixture marker.

Fixture jar is built IN THE TEST from compiled classes (`modules/cli-fixtures`,
Scala classes DemoWorkflow/SecondWorkflow) — no runtime compiler needed.

Found by fixtures: race during parallel copying of keep-jar (three concurrent
Files.copy calls to one path — FileAlreadyExistsException) — serialized with a
lock.

## Changed paths

```
3ebe372a…  build.sbt (raiderCli + raiderCliFixtures + aggregate)
77252d74…  modules/core/src/main/scala/raider/core/bundle.scala (BundleWorkflow SPI)
a906dbb7…  modules/cli/src/main/scala/raider/cli/Main.scala
4867b40c…  modules/cli/src/main/scala/raider/cli/run/CliArgs.scala
44776f2d…  modules/cli/src/main/scala/raider/cli/run/BundleLoader.scala
7e6c4f25…  modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala
978e9f4c…  modules/cli/src/test/scala/raider/cli/run/HeadlessRunnerSpec.scala
2fed586e…  modules/cli-fixtures/src/main/scala/raider/fixtures/DemoWorkflow.scala
a3a2112a…  scripts/quality/cli_smoke.sh
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| RUN-01: jar bundle → mock Program headlessly, artifacts, closed stdin | pass (unit + Main-process smoke) | HeadlessRunnerSpec RUN-01 + cli_smoke.sh |
| RUN-02: unknown workflow / not-a-jar / missing --mock / duplicate flags → typed preflight, exit 21/20, no output/spend | pass | HeadlessRunnerSpec RUN-02 |
| RUN-03 (light): concurrent runs → separate work directories | pass (3 in parallel, unique dirs) | HeadlessRunnerSpec RUN-03 |
| Exit mapping complete for all 18 families | structurally (total function) | HeadlessRunner.exitCodeFor |
| Stability ×3 | 5/5 → 7/7 (after remainder), exit 0 ×3 | /tmp/c12.log, /tmp/c38.log, /tmp/cli-run-{1,2}.log, /tmp/cli3-run-{1,2}.log |
| Full sbt test | 185 tests, 0 failed, exit 0 | /tmp/full-test-R022{,b}.log |
| Quality gates 6/6 ×2 IN A ROW | exit 0 ×2 | artifacts/quality/20261004-14{5246,5334}/ (slice), 20261004-163216 (remainder, 6 passed) |
| SIGTERM probe: TERM during launch window → process exits boundedly (no orphan) | pass | manual probe (kill -TERM, process exited) |
| cli_smoke | PASS exit 0 (after flush fix) | /tmp/cli-smoke8.log |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCli/test"` ×5 | 0,0,0,0,0 | /tmp/c12.log, /tmp/cli-run-{1,2}.log, /tmp/c38.log, /tmp/cli3-run-{1,2}.log |
| `sh scripts/quality/cli_smoke.sh` | 0 | /tmp/cli-smoke8.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×2 | 0 (183/185) | /tmp/full-test-R022{,b}.log |
| `make quality-changed QUALITY_EXECUTE=1` ×4 | 0 ×4 (one honest FAIL 054915 — DSL-ERGO assertion, fixed) | artifacts/quality/20261004-14{5246,5334}/, 20261004-163216 |

## Limitations / obligations

- Packaged distribution (zip/isolated classpath) and SIGTERM phase
  (shutdown-hook interrupt, bounded stop, exit 143) are CI/milestone obligations;
  the slice has no SIGTERM handler (root cleanup = caller's scope).
- Strict loader isolation (child-first/sealed parent) is a security-profile
  obligation; currently parent-delegation.
- `--agent` mode (built-in agents in CLI) is the next part of RAI-022;
  today only bundle mode.
- Manifest signatures/trust chain are outside the slice (trust by
  declaredTrustLevel is honestly documented; “trust from bare hash” is a card
  non-goal).

## Review and next task

Independent review was not performed (coordinator session). Next: `--agent` mode
+ structured message contract; RAI-023 (CI context/trust preflight) on top of the
  completed artifacts.
