# RAI-037: work record (wave R-3 serial integration)

Status: done (integrated candidate GREEN on main). Date: 2026-11-09. Baseline commit/source: main at `8d776e83` before merge (after RAI-036 `41ca1021` and RAI-040 `23afa73b`); integrated candidate after this step: `03caed07` (merge `93586c52` + registration `bb12e841` + integration scalafmt `2b5ae3ef` + integration scalafix `1dbc733c`/`03caed07`).

## Card

RAI-037 — optional Yabanin profile (labels / connection wrapper environment / capability doctor, `modules/yabanin`, contract `contracts/yabanin`). Integrated the independent lane branch `lane/rai-037` (commits `9f99e4ce` + `3da85433` on base `a5bcf53e`; independent review verdict: ACCEPT, no blockers — the wide-ok golden repair was judged justified) into `main` with a `--no-ff` merge, registered the module in `build.sbt` (coordinator-owned file; the sanctioned integration edit), and verified the INTEGRATED candidate from the root checkout.

## Changed paths

Merged from the lane (all new files, 26 files, 3216 insertions; paths disjoint from everything on main since `a5bcf53e`):

- `modules/yabanin/src/main/scala/raider/yabanin/labels/{Labels,PathEscape,LabelSetDocument}.scala`, `.../connection/{GatewayUrl,ChildEnv,WrapperEnv,WrapperEnvDocument}.scala`, `.../doctor/{Capability,DoctorReport,DoctorReportDocument,YabaninDoctor}.scala` (package `raider.yabanin`)
- `modules/yabanin/src/test/scala/raider/yabanin/{TestMain,labels/{LabelsSpec,PathEscapeSpec,Goldens}.scala,connection/WrapperEnvSpec.scala,doctor/{DoctorSpec,YabaninContractStand}.scala}`
- `modules/yabanin/run-tests.sh` (standalone pinned-dotc 3.9.0 runner)
- `contracts/yabanin/{README.md,labels.schema.json,wrapper-env.schema.json,doctor-report.schema.json,expected/{labels-golden.json,pathescape-golden.json,doctor-golden.json}}`

Integration-only additions (this checkout, on top of the merge):

1. `bb12e841` — build.sbt registration (exact diff, 22 insertions / 2 deletions):
   - new declaration after `raiderEvalExport`, mirroring the `d226c378` pattern:

     ```scala
     lazy val raiderYabanin = (project in file("modules/yabanin"))
       .settings(
         commonSettings,
         testSettings,
         name := "raider-yabanin"
       )
       .settings(
         libraryDependencies ++= Seq(
           "dev.zio" %% "zio" % zioVersion,
           "dev.zio" %% "zio-json" % zioJsonVersion
         )
       )
     ```

     (preceded by a comment block noting the optional-profile lane, the standalone nature, the lane runner entry, and coordinator ownership). Dependency edges match the imports exactly: main sources import only `zio.{Chunk}`, `zio.json.*` + `zio.json.ast.Json` and JDK (`java.net.URI`, `java.net.http.*`); test sources add `zio.test.*`, `zio.Scope` and JDK `com.sun.net.httpserver.*` for the loopback contract stand — no `raider.core`/`raider.runtime`/`raider.testkit` usage, hence no `dependsOn` edges. `testSettings` supplies `zio-test`/`zio-test-sbt` (Test scope), the zio-test framework registration, and serial in-module execution — the standard standalone module pattern (`raiderEvalExport` shape).
   - root project: `raiderYabanin` appended to BOTH `.aggregate(...)` and `.dependsOn(...)` (keeping the two root lists structurally parallel), so the full-tree `sbt --batch test` traversal includes the module — full-tree module suite count goes 12 → 13.
2. `2b5ae3ef` — integration scalafmt: reformats the 18 lane Scala files (11 main + 7 test) with `sbt -batch scalafmtAll` output only — formatting-only diff (1318 insertions / 650 deletions). No token-level code change. Lane commits were not amended or rebased; lane worktrees untouched.
3. `1dbc733c` + `03caed07` — integration scalafix null-elimination (behavior-preserving syntax changes on Java-interop null checks; 6 `DisableSyntax.null` violations, see "Real red before green"). Both commits carry the same message `RAI-037: integration scalafix null-elimination (wave R-3)`; `1dbc733c` holds the null-elimination edits (3 files, 8/8) and `03caed07` the one-line correction (1 file, 1/1) — honest sequence, disclosed below.

## Resulting behavior

- **YBCON-01 (labels + child-only env, contract §1–§2)**: label parsing admits only canonical `key=value` pairs within the pinned limits (implementation defaults asserted equal to the `expected/labels-golden.json` pins); rendered headers are the canonical SORTED form; canonicalization drops (duplicate-wins before cap, per §1.3 order) are visible in the parsed `drops` set, never silently discarded; wrapper labels merge first and preserve provenance (parsed pairs attributed wrapper). `ChildEnv.build` hands the child ONLY the allowlisted wrapper entries and the constructed env — non-allowlisted parent variables never reach the child; blank allowlisted values become visible omissions; empty merged labels omit the extra-headers entry entirely. Documents mirror the schemas' field order (`child_env` omitted when not built; `gateway_url` null explicit, not absent).
- **YBCON-02 (typed doctor classification, contract §3)**: `YabaninDoctor` issues exactly two plain GETs (`/models` first, `/yabanin/session-keys` second) and classifies WITHOUT fallback — 401 auth-denied and 403 forbidden are distinct typed outcomes with `decision: null`; 501 (and 404/405) unsupported endpoints are distinguished per-probe (501 on models with session-keys ok, and vice versa); `docs_version` mismatch is `stale_models_docs` with the ids recorded; 400 carrying `label_overflow` is distinguished from any other 400 (plain 400 → `transport_failure`); call-level transport failures classify `transport_failure` with a null status. The first non-empty `X-Yabanin-Decision` header is recorded verbatim (never from the second probe if models carried it).
- **YBCON-03 (matrix distinctness + protected attribution)**: matrix roots × attempts produce pairwise-distinct canonical headers; merging preserves the protected wrapper CI attribution against client override attempts; path-escaping pins round-trip byte-for-byte against `expected/pathescape-golden.json` including the wide-ok multibyte case (wide characters preserved, strict hex decoder rejects truncated/non-hex/invalid-UTF-8 escapes with positions).
- **Registration semantics**: the four specs (`WrapperEnvSpec`, `DoctorSpec`, `PathEscapeSpec`, `LabelsSpec`) and the composition entry `TestMain` are all `ZIOSpecDefault` objects, so all five are sbt-discoverable via the standard zio-test wiring — no 0-test registration, no fake coverage. `Goldens` and `YabaninContractStand` are helper objects, not specs, and are not discovered. The standalone runner `modules/yabanin/run-tests.sh` (pinned dotc 3.9.0 + pinned m2 jars, sets `RAIDER_REPO_ROOT`, honest exit codes, exit 21 on a missing pinned jar) remains the lane's test entry; under sbt the in-process tests resolve the same goldens via the repo-root CWD fallback.

## Real red before green (verbatim disclosure)

1. **scalafmt-check (expected per the integration procedure)**: first `make quality-changed QUALITY_EXECUTE=1` on `bb12e841` exited 2 — `scalafmt-check: failed exit=1` (18 lane files), the other 9 gates passed. Fixed by `2b5ae3ef` (`sbt -batch scalafmtAll`, formatting-only).
2. **scalafix-check `DisableSyntax.null` (6 violations)**: second EXECUTE run on `2b5ae3ef` exited 2 — `scalafix-check: failed exit=1`, all other 9 gates passed (manifest `artifacts/quality/20261109-214919-53684-0001/scalafix-check.log`): `GatewayUrl.scala:39/41/43` (`getRawQuery`/`getRawFragment`/`getUserInfo ne null`), `YabaninContractStand.scala:125/147` (`getFirst("Authorization") ne null`), `YabaninDoctor.scala:141` (`firstValue(...).orElse(null)`). Thresholds were NOT lowered; fixed by behavior-preserving syntax edits — `Option(x).isDefined` for the three URI guards and the two stand header checks, and `firstValue(DecisionHeader).<conv>.filter(_.nonEmpty)` for the decision header. Committed as `1dbc733c` with `.toOption` as the conversion.
3. **`.toOption` does not exist on scala-library 3.9.0**: the rerun battery on `1dbc733c` went red — `sbt` compile failed `[E008] value toOption is not a member of java.util.Optional[String]` (the build pairs scala3-library_3 3.9.0 with the standalone scala-library 3.9.0, whose restructured `scala.jdk.OptionConverters.RichOptional` exposes `toScala`/`asScala`, not the Scala-2.13-era `toOption`), and `run-tests.sh` failed with the same E008 under the pinned dotc. The intermediate `/tmp` logs of these two red battery runs were subsequently overwritten by the green reruns at the same paths; the failures are disclosed here verbatim from session evidence. Fixed by `03caed07` (`.toScala`). A first correction attempt with `.asScala` was rejected by the sbt build's `-Werror` (`asScala` deprecated since 2.13.0: use `toScala`) — also disclosed, not committed; `run-tests.sh` (no `-Werror`) had passed 47/0 on that attempt. After `03caed07`: battery green both ways (below).
4. **secrets gate coverage**: `scripts/quality/security_checks.py` supports `--self-test` only (no ref/diff-range mode), so no diff-scoped secrets run exists; caveat recorded. Compensating evidence on fingerprint `03caed07`: the full-tree `forbidden-apis` gate inside the passing profile covers secret shapes (exit 0), `security_checks.py --self-test` exit 0, a manual merge-diff sweep for credential-shaped strings (`yag_*` long tokens, `api_key =`, `Bearer <token>`) found zero hits, and the local `.env.yabanin` stand-credentials file is untracked/gitignored (mode 0600, predates this lane, not part of the merge — its values are not reproduced here).

## Test inventory (integrated candidate, this checkout)

- `sbt -batch "raiderYabanin/test"` on `03caed07`: **94 passed, 0 failed, 0 ignored**, `[success]`, exit 0. Honest duplication of the lane design under sbt discovery: `TestMain` composes the four suites (47 tests) AND each of the four specs is discovered standalone (summing to the same 47) — 5 discovered suites, 94 executions of 47 distinct assertions. Same duplication shape as eval-export's 70/35. The standalone runner runs only `TestMain` once.
- `sh modules/yabanin/run-tests.sh` on the integrated tree, `03caed07`: **47 passed, 0 failed, 0 ignored**, exit 0 — matches the lane-side evidence (47/0 ×3). No m2 provisioning was needed this time (the pinned store already carried the full runner closure; the RAI-040 integration step had provisioned `zio-test_3-2.1.26` into this checkout's store).
- Full-tree `sbt --batch test` (inside the quality fast profile's `sbt-test` gate, after registration): **2052 passed, 0 failed, 0 ignored across 13 module suites** = previous 1958 (12 suites, RAI-040 record) + 94 new yabanin executions. No cross-module regression.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Clean `--no-ff` merge of `lane/rai-037` into main (disjoint paths) | met | merge commit `93586c52`, 'ort' strategy, no conflicts; 26 files, 3216 insertions |
| Module registration in build.sbt (convention-following, minimal, d226c378 pattern) | met | commit `bb12e841`; only what imports require: zio + zio-json, `testSettings`, root aggregate/dependsOn kept parallel |
| `sbt -batch "raiderYabanin/test"` | met | run 1 (pre-scalafmt tree) 94/0 exit 0; run 2 (`2b5ae3ef`) 94/0; final (`03caed07`) 94/0 — all `[success]` |
| Standalone runner on integrated tree | met | run 1 47/0 exit 0; run 2 (`2b5ae3ef`) 47/0; final (`03caed07`) 47/0 exit 0 |
| `make quality-changed` | met | EXIT=0 on the final tree (classification: clean tree → minimum `fast` profile) |
| `make quality-changed QUALITY_EXECUTE=1` | met on candidate | run 1 (`bb12e841`): EXIT=2 scalafmt-check failed; run 2 (`2b5ae3ef`): EXIT=2 scalafix-check failed (6 `DisableSyntax.null`); final run (`03caed07`): EXIT=0, status=passed, all 10 gates green (manifest `artifacts/quality/20261109-220446-63768-0001/manifest.json`); no threshold lowered, no gate skipped |
| Full-tree battery via profile | met | `sbt-test` gate exit 0: 2052/0/13 suites |
| Contract goldens pinned | met | golden byte-compare tests pass inside both the standalone battery (47/0) and the sbt-discovered suites (94/0); goldens unchanged by integration (wide-ok repair was lane-side, review-ACCEPTed) |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git merge --no-ff lane/rai-037 -m "RAI-037: integrate optional Yabanin profile lane (wave R-3)"` | 0 | merge commit `93586c52` |
| build.sbt registration edit + commit `bb12e841` | 0 | diff in "Changed paths" above |
| `sbt -batch "raiderYabanin/test"` (run 1, pre-scalafmt) | 0 | `/tmp/opencode/rai037-sbt-test.log` (94 passed / 0 failed) |
| `sh modules/yabanin/run-tests.sh` (run 1) | 0 | `/tmp/opencode/rai037-run-tests.log` (47 passed / 0 failed) |
| `make quality-changed QUALITY_EXECUTE=1` (run 1, `bb12e841`) | 2 | `/tmp/opencode/rai037-quality.log`; manifest `artifacts/quality/20261109-214750-52792-0001/` — scalafmt-check failed exit=1 (18 files), 9 gates passed |
| `sbt -batch scalafmtAll` + commit `2b5ae3ef` | 0 | `/tmp/opencode/rai037-scalafmt.log`; 18 files reformatted, formatting-only |
| `sbt -batch "raiderYabanin/test"` (run 2, `2b5ae3ef`) | 0 | `/tmp/opencode/rai037-sbt-test-rerun.log` (94/0) |
| `sh modules/yabanin/run-tests.sh` (run 2, `2b5ae3ef`) | 0 | `/tmp/opencode/rai037-run-tests-rerun.log` (47/0) |
| `make quality-changed QUALITY_EXECUTE=1` (run 2, `2b5ae3ef`) | 2 | `/tmp/opencode/rai037-quality-rerun.log`; manifest `artifacts/quality/20261109-214919-53684-0001/` — scalafix-check failed exit=1 (`DisableSyntax.null` ×6), 9 gates passed; violations in `scalafix-check.log` |
| null-elimination edits + `sbt -batch scalafmtAll` + `sbt -batch "scalafixAll --check"` | 0 | `/tmp/opencode/rai037-scalafmt2.log`, `/tmp/opencode/rai037-scalafix2.log`; commit `1dbc733c` |
| `sbt -batch "raiderYabanin/test"` (on `1dbc733c`) | 1 | red: `[E008] toOption not a member of java.util.Optional[String]` (sbt, -Werror build); same E008 red for `run-tests.sh` — logs overwritten by the later green reruns at the same paths, disclosed above |
| `sbt -batch "raiderYabanin/test"` (`.asScala` attempt) | 1 | red: `-Werror` deprecation (`asScala` since 2.13.0, use `toScala`); `run-tests.sh` passed 47/0 on this attempt (no -Werror at the pinned runner) |
| `.toScala` correction + commit `03caed07` | 0 | `sbt -batch "scalafixAll --check"` exit 0 (`/tmp/opencode/rai037-scalafix3.log`) before commit |
| `sbt -batch "raiderYabanin/test"` (final, `03caed07`) | 0 | `/tmp/opencode/rai037-sbt-test-final.log` (94/0, `[success]`) |
| `sh modules/yabanin/run-tests.sh` (final, `03caed07`) | 0 | `/tmp/opencode/rai037-run-tests-final.log` (47/0) |
| `make quality-changed` (final, classify) | 0 | `/tmp/opencode/rai037-qc-classify.log` |
| `make quality-changed QUALITY_EXECUTE=1` (final, `03caed07`) | 0 | `/tmp/opencode/rai037-quality-final.log`; manifest `artifacts/quality/20261109-220446-63768-0001/` — all 10 gates passed (`self-registry-check`, `sbt-compile`, `scalafmt-check`, `scalafix-check`, `forbidden-apis`, `license-audit`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `sbt-test`); `sbt-test` log confirms 2052 passed / 0 failed across 13 module suites |
| `python3 scripts/quality/security_checks.py --help` | 0 | `/tmp/opencode/rai037-secrets-selftest.log` header — `--self-test` only, no ref/range mode (caveat recorded) |
| `python3 scripts/quality/security_checks.py --self-test` | 0 | `/tmp/opencode/rai037-secrets-selftest.log` |
| merge-diff secret-shape sweep (`git diff 8d776e83..03caed07` vs credential-shaped patterns) | 0 hits | session evidence; `.env.yabanin` verified untracked/gitignored, outside the merge |

Integrated-candidate logs: `/tmp/opencode/rai037-sbt-test-final.log`, `/tmp/opencode/rai037-run-tests-final.log`, `/tmp/opencode/rai037-quality-final.log`, `/tmp/opencode/rai037-qc-classify.log`; gate logs under `artifacts/quality/20261109-220446-63768-0001/`. The green evidence applies to fingerprint `03caed07` (merge + registration + formatting + null-elimination + correction), per the evidence-fingerprint rule.

## Limitations / review follow-ups (carried, NOT fixed here)

1. **Go-toolchain re-pin of hand-authored goldens still open** (contract §4 / `expected/pathescape-golden.json` re-pin note): the escape expectations were re-derived by the lane's Scala implementation, not a supported Go toolchain; the wide-ok golden repair was implementation-derived and independently review-verified as justified, but the authoritative Go-toolchain re-pin remains a separate contract card.
2. **JSON-Schema draft 2020-12 validator not in the closure**: documents are pinned structurally (field set/order, omits, null-explicitness) against the schemas' property order; no schema-validation library exists in the module's dependency closure, so deep `pattern`/`format` schema semantics are not machine-checked.
3. **wrapper-env `value_present` schema wording nit**: cosmetic contract-text item flagged in review; untouched at integration (contracts are lane-owned inputs).
4. **`ProtectedWrapperKey` drop detail embeds the client label value** in the drop reason — audit-hygiene candidate (a value, not a credential, but worth a follow-up look); untouched at integration.
5. **`run-tests.sh` + `TestMain` acknowledged as card-intended harness files**: the standalone runner duplicates the sbt-discoverable suites by design (lane evidence channel); the 94-vs-47 duplication under sbt is honest and unchanged.
6. Fast-profile gate classification on a clean tree resolves to the minimum `fast` profile; the full-tree `sbt --batch test` inside the profile covers the new module's suites, and the standalone pinned-toolchain runner was executed separately on the integrated tree.
7. Two integration commits share the message `RAI-037: integration scalafix null-elimination (wave R-3)` (`1dbc733c` edits, `03caed07` correction); the sequence is disclosed here and left unamended.

## Review and next task

Lane review verdict: ACCEPT, no blockers (independent review of `lane/rai-037`, including the wide-ok golden repair adjudication). This integration adds no review blockers; follow-ups 1–4 above are candidate cards (1 and 2 are contract-card class). Wave R-3 is now fully integrated: RAI-036 (`41ca1021`), RAI-040 (`23afa73b`), RAI-037 (`03caed07`). Per the R-3 dependency ruling, RAI-038 (root session keys & finish) and RAI-039 (pinned local Yabanin stand acceptance) are unblocked by this merge; dispatch remains an owner/coordinator decision.
