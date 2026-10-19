# RAI-157-matrix: work record (final determinism matrix)

Status: done (matrix-green; closure-ready per the adjudication's closure condition). Date: 2026-10-19 UTC. Baseline commit: `06beb1a5f867b2a765e44234851a12e08192cb7b` (`06beb1a`, `docs: record RAI-178 integration acceptance`), branch `lane/rai-157-m`, worktree `wt/rai-157-m`, created from `main` at `06beb1a` with a clean tree. Machine: 18-core macOS shared with other active lanes; load average 7.6–8.5 during all matrices and gates (documented contention context, same ballpark as RAI-178's green matrices at load 6.8–10.7).

## Resulting behavior

No product, test, build, or gate-policy source was changed in this round. This record discharges RAI-157's last acceptance obligation: the determinism matrix on the healed integrated tree (post RAI-178 `withLiveClock` fix, merged on `main`). The strict MJE-03c expectation (`Left(ReduceError.MissingTerminal(childId))`, refused typed entry with completed nodes retained under the partial entry) is unchanged and passed inside every matrix run below.

Disposition: the ×10 focused matrix and the ×3 full-module matrix are green on the integrated candidate, so the MJE-03c intermittent known-red now has a passing matrix on the integrated candidate per the adjudication's closure condition (historical reds remain honestly recorded in `docs/work/records/RAI-157.md`; none was observed in this round).

## Changed paths

- `docs/work/records/RAI-157-matrix.md` (this record) — only change.

Environment bootstrap, not source changes: the pinned toolchain `artifacts/tools/m2` (gitignored, 77 MB) was copied read-only from the root checkout's untracked provision into this fresh worktree, as the earlier RAI-157 lane did from the Coursier cache; `compile-fixtures` needs it and it is not committed.

## Preflight: phantom compile (packet rule applied once)

First `sbt --batch Test/compile` in the fresh worktree failed in 13 s with the exact phantom signature documented in RAI-157 forensics: `[E008] Not Found Error: modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala:772:7 — value id is not a member of raider.core.ToolCall` (log `/tmp/rai157m/00-warm-compile.log`, sha256 `c8b3e36b114994132dbda98cf6ff05aa558c33ba390f94a866f45656d0c5e5e7`). The source at HEAD defines `def id: ToolCallId = call` on `ToolCall` (`modules/core/src/main/scala/raider/core/tool.scala:34`) and this exact tree passed RAI-178's whole-build gate, so this is the known phantom, not a real red. Per the packet rule, `target`/`project/target`/`project/project`/`modules/*/target`/`.bsp` were purged and the compile retried exactly once: exit 0 in 29 s, 24 compile batches, 0 errors (log `/tmp/rai157m/00-warm-compile-2.log`, sha256 `99494f0e515b12ce5b570dfe9131e88b9e0a8324d424be8136527b12f9b63804`). The single retry is spent; any later compile failure would have been recorded as a real red (none occurred).

## Matrix A — `raiderCli/testOnly raider.cli.run.MultiJobExportSpec` ×10 consecutive green

Command per run: `sbt --batch 'raiderCli/testOnly raider.cli.run.MultiJobExportSpec'`, strictly sequential (one sbt at a time in this worktree). Every run: exit 0, `8 tests passed. 0 tests failed. 0 tests ignored.`, including `MJE-03c: a detached child (no Terminal) is refused typed by the strict entry and keeps its completed nodes under the partial entry` with a `+` marker in every log. Logs `/tmp/rai157m/A-spec-{1..10}.log`.

| Run | Exit | Duration | Log SHA-256 |
| ---: | ---: | ---: | --- |
| 1 | 0 | 6 s | `ef1952dd814bf7ad0111ef47488f2b35947cfa1dbf51a12d528502057052a82f` |
| 2 | 0 | 6 s | `0850489973adadd7b94cf70dd64875470433e83e4d8193ed59a6d84e5bfe8682` |
| 3 | 0 | 6 s | `e420b0672c3b1d6536ddcba15a6071e9af066bf4fc06eb9aa2474cd6961a231d` |
| 4 | 0 | 6 s | `849d77b5737e6de0b2d50495e768ebbc839ea588601cb91cbcfd9607738b38c1` |
| 5 | 0 | 7 s | `5d539ff66de2cd29bf98dc4fe3cebcd0e56b04fd55eef48d600a65f0d0d8c023` |
| 6 | 0 | 6 s | `e977827ee659e1d552a1fbf53015f489f87da7e2db9597ed0f0123df114ac7d8` |
| 7 | 0 | 7 s | `a6d87440efb7b218b94f06756bf1ff3168c58e7b44b700ae75b0336ada8d569d` |
| 8 | 0 | 6 s | `26dbde9dcf0cd0eabd44a80c7856fac79eb49650a64edfcee14b218a640baa7e` |
| 9 | 0 | 7 s | `f2d5dcf9038ffc191154a4fdfc02be34351047b9f06bc1a2bbc48e4eb71fb473` |
| 10 | 0 | 7 s | `abd2014d99c5180d5ffa4628f096709a54e16eab577d0701d65e6f1794094796` |

Verdict: 10/10 consecutive green. No run was red, timed out, or skipped; durations include sbt boot.

## Matrix B — `raiderCli/test` ×3 consecutive green (module end-to-end)

Command per run: `sbt --batch raiderCli/test`, strictly sequential. Every run: exit 0, `450 tests passed. 0 tests failed. 0 tests ignored.` Durations (140–145 s) match RAI-178's healed baseline (136–145 s). Logs `/tmp/rai157m/B-module-{1,2,3}.log`.

| Run | Exit | Duration | Result | Log SHA-256 |
| ---: | ---: | ---: | --- | --- |
| 1 | 0 | 145 s | 450/0/0 | `dccf191e7ee44b01716d54ff53866139efe1311bdfd27648bed3c55ed2e9791b` |
| 2 | 0 | 141 s | 450/0/0 | `95b03641e747bf1b549f1498591c115e39f1d6300c113f1d1d614b86592b87aa` |
| 3 | 0 | 140 s | 450/0/0 | `f0c9fd7d74c578a9151990c5d81f7eff42ed73a6723118ef89d7424752b25ec7` |

Verdict: 3/3 consecutive green, MJE-03c green inside each full-module run.

## Finalized pre-gate state and gates

Pre-gate tree: clean at `06beb1a` (no source change before or after the gates; `git status --short` empty). Source hashes at fingerprint: `MultiJobExport.scala` `06d8075e8fa15528aec92752fd63019de40dfc16d33e91a0819beb53833b5d09` (identical to the RAI-157 closure-round hash), `MultiJobExportSpec.scala` `8ad0e9d2b21bbc563bd0de74f757634f56bbbc72122b9a00e6a9306ba55688ed` (RAI-178's `withLiveClock` version). Gates ran in the mandated order on this unchanged tree; only this record was written afterward.

| Command | Exit | Duration | Result / manifest |
| --- | ---: | ---: | --- |
| `make quality-changed` | 0 | — | Clean tree; `[quality] no changes vs HEAD (clean tree executes minimum fast)`; profiles `["fast"]`. Log `/tmp/rai157m/G1-quality-changed.log`. |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | 386 s | status=passed profiles=`['fast']`; all 10 gates passed exit=0 timeout=False (self-registry-check, sbt-compile, scalafmt-check, scalafix-check, forbidden-apis, license-audit, core-contracts-spec, compile-fixtures, plan-validate, sbt-test). Manifest `artifacts/quality/20261019-190423-17581-0001/manifest.json`, SHA-256 `9d28970816a73755d97362c1c893ac039ffd1efbfcb569f77d7ddda0731338f5`. Log `/tmp/rai157m/G2-quality-changed-execute.log`. |
| `make quality-profile PROFILES="static unit contracts ci"` | 0 | 406 s | status=passed profiles=`['ci','contracts','static','unit']`; all 6 gates passed exit=0 timeout=False (sbt-test, cli-smoke, core-contracts-spec, compile-fixtures, sbt-compile, scalafmt-check). Manifest `artifacts/quality/20261019-191057-19198-0001/manifest.json`, SHA-256 `5e0acf5a76f6ccc2c42fc452abb3ffcf93c7d683d32cead6cb687e760ccb1b4d`. Log `/tmp/rai157m/G3-quality-profile.log`. |
| `make quality-verify MANIFEST=…190423-17581-0001… PROFILES=fast` | 0 | — | `[verify] OK: manifest … satisfies ['fast'] (policy/toolchain/logs hashes checked, fingerprint fresh)` |
| `make quality-verify MANIFEST=…191057-19198-0001… PROFILES=static,unit,contracts,ci` | 0 | — | `[verify] OK: manifest … satisfies ['static','unit','contracts','ci'] (policy/toolchain/logs hashes checked, fingerprint fresh)` |

Both executing gate runs' whole-build `sbt --batch test` (the `sbt-test` gate) report 0 failed tests in every module suite, with raiderCli at 450/0/0 in both. No gate skipped, timed out, or hit a scanner error; the `compile-fixtures` pinned dotc toolchain was fully provisioned before the gates (no exit-21 condition).

## Limitations / blockers

- This is lane evidence at the `06beb1a` snapshot in a dedicated worktree; per repo policy it does not by itself re-bind integrated-candidate acceptance — the coordinator merges this record (records-only branch) and owns the board closure statement for MJE-03c.
- The ×10/×3 matrices ran at load 7.6–8.5 with other lanes active. The historical MJE-03c/MJE-02/MJE-NESTED intermittent reds were load-sensitive; no red appeared here, but a matrix proves absence-of-failure across the executed runs, not a proof of determinism under arbitrary oversubscription (the RAI-178 root-cause fix — frozen TestClock — is the causal closure; this matrix is its confirmation on the full spec).
- No expectation, deadline, settle window, budget, threshold, or gate-policy value was changed; the phantom-compile purge/retry allowance was used exactly once as authorized.
- Matrix logs live under `/tmp/rai157m/` (not committed, per house pattern); gate manifests and logs are under `artifacts/quality/` in this worktree (gitignored evidence; the manifests record their own hashes).

## Review and next task

No independent review needed for a records-only, no-code-change round; suggested reviewer check is exactly the matrices' exit codes above and that `git diff main..lane/rai-157-m` contains only this record. Lane verdict: **matrix-green (closure-ready)** — RAI-157's final acceptance obligation is discharged on the integrated candidate; MJE-03c can be closed as "passing matrix on the integrated candidate" per the adjudication's closure condition, with the historical intermittent reds retained in the RAI-157 record. Next ready steps per board: RAI-179 remains the open dispatch-seam card; the deferred main-source TestClock-hostile-poll card is a separate registration.

Product-input hash and operational-record hash differ under the reviewed policy. Gate manifests bind the clean `06beb1a` source snapshot at execution time; adding this record does not change that source snapshot.
