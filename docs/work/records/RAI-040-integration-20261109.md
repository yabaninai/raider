# RAI-040: work record (wave R-3 serial integration)

Status: done (integrated candidate GREEN on main). Date: 2026-11-09T21:45:00Z. Baseline commit/source: main at `48b15f0b` before merge; integrated candidate after this step: `23afa73b` (merge `d216b388` + registration `d226c378` + integration scalafmt fix `23afa73b`).

## Card

RAI-040 — eval-run export bundle (`modules/eval-export`, contract `contracts/eval-export`: 6 schemas + pinned goldens). Integrated the independent lane branch `lane/rai-040` (commits `94cbb699` + `83d9eee3` on base `a5bcf53e`; independent review verdict: ACCEPT, no blockers) into `main` with a `--no-ff` merge, registered the module in `build.sbt` (coordinator-owned file; the sanctioned integration edit), and verified the INTEGRATED candidate from the root checkout.

## Changed paths

Merged from the lane (all new files, 45 files, 3617 insertions; paths disjoint from everything on main since `a5bcf53e`):

- `modules/eval-export/src/main/scala/raider/eval/export/{model,wire,bundle,digests,exporter,lineage,reconcile}.scala` (package `raider.evalexport`)
- `modules/eval-export/src/test/scala/raider/eval/export/{ExportSpec,ReconcileSpec,HonestySpec,TestMain,GoldenRepin,Fixtures}.scala`
- `modules/eval-export/run-tests.sh` (standalone pinned-dotc 3.9.0 runner; `--update-goldens` repin mode)
- `contracts/eval-export/{README.md,schemas/*.schema.json,expected/{normal-run,cancelled-run,late-usage,unknown-price}/*}`

Integration-only additions (this checkout, on top of the merge):

1. `d226c378` — build.sbt registration (exact diff, 22 insertions / 2 deletions):
   - new declaration after `raiderBackendOpencode`:

     ```scala
     lazy val raiderEvalExport = (project in file("modules/eval-export"))
       .settings(
         commonSettings,
         testSettings,
         name := "raider-eval-export"
       )
       .settings(
         libraryDependencies ++= Seq(
           "dev.zio" %% "zio" % zioVersion,
           "dev.zio" %% "zio-json" % zioJsonVersion
         )
       )
     ```

     (preceded by a comment block noting the standalone nature, the lane runner entry, and coordinator ownership). Dependency edges match the imports exactly: the module imports only `zio.*`, `zio.json.*` (main) and `zio.test.*` (test) — no `raider.core`/`raider.runtime`/`raider.testkit` usage, no `zio-streams` usage, hence no `dependsOn` edges and no streams dependency. `testSettings` supplies `zio-test`/`zio-test-sbt` (Test scope), the `zio.test.ZIOFramework` registration, and serial in-module test execution — the standard module pattern (`raiderBackendOpencode` shape, minus streams).
   - root project: `raiderEvalExport` appended to BOTH `.aggregate(...)` and `.dependsOn(...)` (keeping the two root lists structurally parallel), so the full-tree `sbt --batch test` traversal includes the module — full-tree module suite count goes 11 → 12.
2. `23afa73b` — integration scalafmt fix: reformats the 12 lane Scala files (6 main + 6 test) with `sbt -batch scalafmtAll` output only — comment reflow, indentation, and line-breaking; no token-level code changes. Lane commits were not amended or rebased; lane worktrees untouched.

## Resulting behavior

- **EXPORT-01 (export bundle)**: `Exporter.exportRun` turns a validated `model.EvalRunInput` into an `ExportBundle` of exactly six documents — `manifest.json` plus `episodes/calls/tools/verdicts/reconciliation` `.jsonl` — canonical compact JSON, one trailing newline per non-empty file, empty sections as zero-byte files. Content digests (`digests.scala` SHA-256 over UTF-8) pin the actual section bytes in the manifest; the validating `wire` codecs round-trip every document; `Lineage` reconstructs jobs/attempts/episodes from the exported documents alone; exports are byte-identical across runs (determinism) and byte-exact against the pinned goldens `contracts/eval-export/expected/{normal-run,unknown-price}/`.
- **EXPORT-02 (honesty)**: verdicts are exported ONLY from actual `model.VerifierResult` records — pending/absent verifier outcomes carry explicit typed reasons (never fabricated pass/fail); raw tool args are digested and never embedded (redacted-safe args only when explicitly pinned); env references travel as NAME-only; unknown usage prices are explicit nulls with `unknown=true` (never zeros); episode–attempt completeness is enforced 1:1 (every attempt carries exactly one episode record, one root job required).
- **EXPORT-03 (partial runs + reconciliation)**: cancelled/partial runs export honestly (cancelled episode with declared verifier produces a pending verdict with reason; interrupted episode without a declared verifier is absent) — golden `contracts/eval-export/expected/cancelled-run/`. `Reconciliation.append` mutates ONLY `reconciliation.jsonl` (all other documents stay byte-identical), builds a versioned per-call correction chain, and the file chain is tamper-evident (`verifyChain` rejects a corrupted history); late-usage appends are pinned by the `expected/late-usage/` golden.
- **Registration semantics**: the three per-area specs (`ExportSpec`, `ReconcileSpec`, `HonestySpec`) and the composition entry `TestMain` are all `ZIOSpecDefault` objects, so they are sbt-discoverable via the standard zio-test wiring — no 0-test registration, no fake coverage. `GoldenRepin` (`ZIOAppDefault`) is not a spec and is not discovered. The standalone runner `modules/eval-export/run-tests.sh` (pinned dotc 3.9.0 + pinned m2 jars, sets `RAIDER_REPO_ROOT`) remains the lane's test entry and golden repin path; under sbt the in-process tests resolve the same goldens via the repo-root CWD fallback (all gates launch from the repo root).

## Test inventory (integrated candidate, this checkout)

- `sbt -batch "raiderEvalExport/test"`: **70 passed, 0 failed, 0 ignored**, `[success]`. This is the honest duplication of the lane design under sbt discovery: `TestMain` composes the three suites (35 tests) AND each of the three specs is discovered standalone (summing to the same 35) — 4 discovered suites, 70 executions of 35 distinct assertions. The standalone runner runs only `TestMain` once.
- `sh modules/eval-export/run-tests.sh` on the integrated tree: **35 passed, 0 failed, 0 ignored**, exit 0 — matches the coordinator's two lane-side runs (35/0, exit 0).
- Full-tree `sbt --batch test` (inside the quality fast profile, after registration): **1958 passed, 0 failed, 0 ignored across 12 module suites** = previous 1888 (11 suites) + 70 new eval-export executions. No cross-module regression.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Clean `--no-ff` merge of `lane/rai-040` into main (disjoint paths) | met | merge commit `d216b388`, 'ort' strategy, no conflicts; 45 files, 3617 insertions |
| Module registration in build.sbt (convention-following, minimal) | met | commit `d226c378`; only what imports require: zio + zio-json, `testSettings`, root aggregate/dependsOn |
| `sbt -batch "raiderEvalExport/test"` | met | run 1 (`d226c378`) 70/0 exit 0; run 2 (`23afa73b`) 70/0 exit 0, both `[success]` |
| Standalone runner on integrated tree | met | run 1 (`d226c378`) 35/0 exit 0; run 2 (`23afa73b`) 35/0 exit 0 |
| `make quality-changed` | met | EXIT=0 both runs; classification: clean tree → minimum `fast` profile |
| `make quality-changed QUALITY_EXECUTE=1` | met on candidate | first run on `d226c378`: EXIT=2 — `scalafmt-check: failed exit=1` (12 lane files), 9 other gates passed incl. `sbt-test` (1958/0); after integration scalafmt fix `23afa73b`: EXIT=0, status=passed, all 10 gates green (manifest `artifacts/quality/20261109-213259-43352-0001/manifest.json`) |
| Contract goldens pinned | met | golden byte-compare tests pass inside both the standalone battery (35/0) and the sbt-discovered suites (70/0); repin path remains `run-tests.sh --update-goldens` (not exercised — goldens unchanged) |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git merge --no-ff lane/rai-040 -m "RAI-040: integrate eval export lane (wave R-3)"` | 0 | merge commit `d216b388` |
| build.sbt registration edit + commit `d226c378` | 0 | diff in "Changed paths" above |
| `sbt -batch "raiderEvalExport/test"` (run 1, `d226c378`) | 0 | `/tmp/int040-module-test.log` (70 passed / 0 failed) |
| `sh modules/eval-export/run-tests.sh` (run 1) | 21 → then 0 | first attempt exited 21: root checkout m2 store missing `dev/zio/zio-test_3/2.1.26/zio-test_3-2.1.26.jar` (lane-local provisioning gap); installed that exact jar from the lane worktree store (read-only copy, lane untouched), sha256 `30531c9164e9af99f7f1a09b812acccb0f635dc78825482f382c1a5955abce05`, byte-identical to the coursier-cache jar sbt used for the same module's 70/0 run; rerun on `d226c378`: `/tmp/int040-standalone.log` (35 passed / 0 failed, exit 0) |
| `make quality-changed` (run 1) | 0 | `/tmp/int040-qc.log` |
| `make quality-changed QUALITY_EXECUTE=1` (run 1, `d226c378`) | 2 | `/tmp/int040-qce.log`; manifest `artifacts/quality/20261109-212047-34062-0001/` — scalafmt-check failed exit=1 (12 files), 9 gates passed; `sbt-test` passed 1958/0/12 suites |
| `sbt -batch scalafmtAll` + commit `23afa73b` | 0 | 12 files reformatted, formatting-only diff |
| `sbt -batch "raiderEvalExport/test"` (run 2, `23afa73b`) | 0 | `/tmp/int040-module-test2.log` (70 passed / 0 failed) |
| `sh modules/eval-export/run-tests.sh` (run 2, `23afa73b`) | 0 | `/tmp/int040-standalone2.log` (35 passed / 0 failed) |
| `make quality-changed` (run 2) | 0 | `/tmp/int040-qc2.log` |
| `make quality-changed QUALITY_EXECUTE=1` (run 2, `23afa73b`) | 0 | `/tmp/int040-qce2.log`; manifest `artifacts/quality/20261109-213259-43352-0001/` — all 10 gates passed (`self-registry-check`, `sbt-compile`, `scalafmt-check`, `scalafix-check`, `forbidden-apis`, `license-audit`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `sbt-test`); `sbt-test` log confirms 1958 passed / 0 failed across 12 module suites |

Integrated-candidate logs: `/tmp/int040-module-test2.log`, `/tmp/int040-standalone2.log`, `/tmp/int040-qc2.log`, `/tmp/int040-qce2.log`; gate logs under `artifacts/quality/20261109-213259-43352-0001/`. The green evidence applies to fingerprint `23afa73b` (merge + registration + formatting), per the evidence-fingerprint rule.

## Limitations / review follow-ups

1. **`Exporter.lower` Locale-default case folding** (review follow-up, NOT fixed here): `exporter.scala` `lower(e: scala.reflect.Enum) = e.toString.toLowerCase` uses the JVM default locale (Turkish-i hazard: enum tag names could fold differently under a `tr` default locale). Severity low (enum term names are ASCII and stable under typical locales), but a separate card should pin `toLowerCase(Locale.ROOT)` (or explicit `Enum`-from-`ordinal` mapping).
2. **`verdict.verifierId` cross-check rule unresolved** (review follow-up, NOT fixed here): verdict wire records carry their own `verifierId`, and the exporter does not enforce that it matches the episode's declared `verifierId` (or decide what happens when it differs). The contract README does not pin the rule; needs a separate contract decision, not an integration-time fix.
3. **Multi-verdict-per-episode stance**: the exporter groups verdicts by episode (`groupBy(_.episodeId)`) and exports every `VerifierResult` record; there is no uniqueness constraint per episode. The stance (allow-and-preserve vs restrict-to-one) is unpinned in the contract; left as-is, follow-up card owns the decision.
4. **sbt-discovered suite duplication (70 vs 35)**: because `TestMain` composes the three specs while all four objects are `ZIOSpecDefault`, sbt executes the 35 distinct assertions twice. Honest duplication, no false coverage claims; a future lane-owned cleanup could exclude `TestMain` from sbt discovery or drop the per-spec standalone discovery — deliberately NOT changed in integration.
5. **m2 store gap in this checkout (environment, not product)**: the root checkout's pinned m2 store lacked `zio-test_3-2.1.26` (no manifest in the repo pins runner-jar provisioning; the default `bootstrap.sh m2` manifest covers the consumer closure only). Provisioned from the lane worktree's store with sha256 cross-check against the coursier cache (identical). Fresh checkouts hitting `run-tests.sh` will need the same one-jar provisioning until a manifest covers runner jars.
6. Fast-profile gate classification on a clean tree resolves to the minimum `fast` profile; the full-tree `sbt --batch test` inside the profile covers the new module's suites, and the standalone pinned-toolchain runner was executed separately on the integrated tree.

## Review and next task

Lane review verdict: ACCEPT, no blockers (independent review of `lane/rai-040`). This integration adds no review blockers; follow-ups 1–3 above are candidate cards. Wave R-3 remains: step 3 of 3 (RAI-037 lane integration), then the steering card RAI-063.
