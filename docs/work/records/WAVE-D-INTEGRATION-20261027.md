# WAVE-D-INTEGRATION: work record

Status: blocked (integration halted at the first executed gate — verbatim stop per
the integration card's decision rule). Date: 2026-10-27. Baseline commit/source:
`main` @ `69a9a4b` (verified HEAD == origin/main, clean tree; worktree tips
wt/rai-222=06928dd, wt/rai-223=e6131c9, wt/rai-224=da8ddc0, wt/rai-205=3c9ab50,
wt/rai-225=bd4a2f2 — all verified before merging).

## Resulting behavior

Five lane branches merged serially `--no-ff` into `main` with zero textual
conflicts (forecast confirmed): `7e2f7f1` (lane/rai-222), `c83e1db`
(lane/rai-223), `ce84701` (lane/rai-224), `ac9612f` (lane/rai-205), `a6ea8a2`
(lane/rai-225, merged last). One disclosed integration-fix commit `101c1e4`
followed: RAI-205 corpus re-pin (`adapterJarSha256` in
`contracts/acceptance/cross-ecosystem/expected/golden-checks.json` updated
`c9bd87776660f84c6569506b3b9beb9927abd45f0080afcfe9bc4ec272c7acad` ->
`997f29a18e38848c082deaeb825a7f4da40ca76d47444663c656693a16d47fdd` for the jar
rebuilt from the integrated tree) and the F-224-1 editorial fix (tier-4 summary
row in `docs/research/gate-budget-load-calibration-2026-10-27.md`: c02/c05 were
over-budget AND test-failed; the cell moved them out of "Test-failed within
budget" (now 0) into the over-budget cell with the suite names).

The cross-ecosystem corpus re-pin obligation is GREEN on the integrated
candidate (see evidence). The card acceptance flow is HALTED because the first
executed `sbt-test` gate run produced a real assertion red in a suite outside
the two authorized load-flaky suites, plus a gate-level timeout. Per the card's
decision rule ("a second failure or any other failing suite -> verbatim stop,
no push"), no rerun was attempted, no status flips were made, nothing was
pushed, and no worktrees were removed.

## Changed paths

- Merge commits only (listed above) — content authored by the lanes.
- `contracts/acceptance/cross-ecosystem/expected/golden-checks.json`
  (modified; `adapterJarSha256` only)
- `docs/research/gate-budget-load-calibration-2026-10-27.md` (modified; one
  table row, F-224-1)
- This record.

Public API/schema changes: none.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Adapter jar rebuilt on integrated tree | exit 0; jar sha256 `997f29a18e38848c082deaeb825a7f4da40ca76d47444663c656693a16d47fdd` (old lane jar `c9bd8777…acad`) | `make raider-jar`, 2026-10-27 11:57:41 local |
| Pinned consumer closure present in root checkout | 29 of 31 pinned artifacts missing from gitignored `artifacts/tools/m2`; copied from lane `wt/rai-205` m2; ALL 31 pinned sha256 verified OK against `consumer-deps.json` | provisioning run log (session transcript) |
| RAI-205 corpus re-pin: full matrix | PASS — 20/20 consumer scenario runs (llm4s 6+2+2, langchain4j 6+2+2), checker `findings=160 failed=0`, `ok=true`, report binds the NEW jar hash (18 occurrences, 0 old-hash) | `bash contracts/acceptance/cross-ecosystem/scenarios/run-all.sh` exit 0; `artifacts/xe-out/checker-report.json` |
| RAI-205 corpus re-pin: budget refusal | PASS — inner budget run exit 23, typed refusal `RA-PBUDGET`, artifacts events.jsonl/run.json/summary.md | `bash contracts/acceptance/cross-ecosystem/scenarios/run-budget.sh` exit 0 |
| RAI-225 vs corpus golden conflict check | No contradiction — RAI-225 keys the `duplicate_submit` refusal on OWNERSHIP (frozen XE-WIRE-02 semantics); corpus scenario `refusals-duplicate-submit` expects exactly that refusal (`duplicate-submit-refused`, exit 0) and ran PASS on the integrated jar | `docs/work/records/RAI-225.md` §1; run-all output |
| `make quality-changed` (plan) | exit 0 — clean tree selects minimum `fast` | session transcript |
| `make quality-changed QUALITY_EXECUTE=1` | FAILED — 9/10 gates passed; `sbt-test` exit 1, `timed_out=true`, duration 1800.08 s (gate ceiling); real red: `MultiJobExportSpec` MJE-03c `childId != "<none>"` at `MultiJobExportSpec.scala:860` (module summary 603 passed / 1 failed); neither authorized load-flaky suite (AgentLoopPhaseEmissionSpec, ResumeCommandSpec) appears in the log — the kill at the ceiling left later suites unexecuted | manifest `artifacts/quality/20261027-120432-11235-0001/manifest.json` sha256 `5c3a4dd2903e05a4eb8ac5a36f7e148ae2e9b210324f1fbbb95af746e0527584`; `sbt-test.log` sha256 `515dd39dce215019fc90a4d979f9b920d793b3e5515b28f082efed7070f910b2` |

Verbatim failure block (ANSI stripped):

```
- multi-job lineage export (RAI-144) / MJE-03c: a detached child (no Terminal) is refused typed by the strict entry and keeps its completed nodes under the partial entry
  ✗ Result was false
  childId != "<none>"
  childId = "<none>"
  at .../modules/cli/src/test/scala/raider/cli/run/MultiJobExportSpec.scala:860
```

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git merge --no-ff` ×5 (222, 223, 224, 205, 225) | 0 each, no conflicts | merge commits `7e2f7f1`, `c83e1db`, `ce84701`, `ac9612f`, `a6ea8a2` |
| `make raider-jar` | 0 | jar `modules/cli/target/scala-3.9.0/raider-cli.jar` |
| `bash contracts/acceptance/cross-ecosystem/scenarios/run-all.sh` (attempt 1) | 2 | aborted in `compile_all` — pinned consumer closure absent from root `artifacts/tools/m2` (gitignored per-checkout state) |
| `bash contracts/acceptance/cross-ecosystem/scenarios/run-all.sh` (attempt 2, after pinned provisioning) | 0 | `artifacts/xe-out/` (six manifests, `checker-report.json`) |
| `bash contracts/acceptance/cross-ecosystem/scenarios/run-budget.sh` | 0 | inner exit 23 / `RA-PBUDGET` |
| `make quality-changed` | 0 | plan: `fast` (clean tree) |
| `make quality-changed QUALITY_EXECUTE=1` | make error 1 | `artifacts/quality/20261027-120432-11235-0001/manifest.json` |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` | NOT RUN | halted before the flips/validator stage |

## Limitations / blockers

- BLOCKER (card rule): `sbt-test` red outside the authorized flake set +
  gate timeout. MJE-03c is a historical load-sensitive watch (RAI-157/185/186
  lineage, closed 2026-10-19 with a matrix, recurred under load since), and
  `MultiJobExportSpec` is named among the load-sensitive families in the
  RAI-224 calibration doc — but the integration card enumerates exactly two
  rerun-eligible suites (AgentLoopPhaseEmissionSpec:421,
  ResumeCommandSpec:397), so the prescribed outcome for this observation is
  verbatim stop and owner/coordinator adjudication. No rerun was attempted.
- Ambient host context during the gate run was not pristine: four day-old
  orphaned JVMs from wave-B temp worktrees were on the host during the run
  (incl. one sbt `test` in `/var/folders/.../opencode/waveB-wt/rai-214`, pid
  85261, started 2026-10-26 02:44) and three lane-started `java -cp` processes
  in `wt/rai-205` (08:09–11:52). All were left untouched (not this session's
  processes). Load 1-min at 12:38 local: 5.71 / 9.65 / 16.34. Owner decision
  needed on reaping the stale wave-B processes before any rerun.
- This session's own leaked processes were reaped: xe stands 4903/4925/6359
  survived `stop_stands` SIGTERM (the `|| true` in `lib.sh` masks the failure —
  hygiene note for the corpus owners) and orphan sbt pid 24924 from the
  timed-out gate; all four are gone.
- Not done (gated on adjudication): RAI-224 tier summary anomaly scan beyond
  F-224-1 (none found in §5 narrative), docs/contracts profile rerun on the
  post-edit fingerprint, `fast docs contracts ci runtime eval security`
  profile run, card flips (m1.md/backlog.json/README), board wave-D entry,
  plan validator, push, worktree/branch cleanup. Lane branches and worktrees
  are intact as the durable evidence.

## Review and next task

No reviewer verdict — this is a coordinator halt record, not an acceptance.
Next decision belongs to the owner/coordinator: (a) rerun
`make quality-changed QUALITY_EXECUTE=1` on the calm, reaped host with the
MJE-03c history in view (its red is a documented load-sensitive family, but
extending the rerun allowance is an owner call, not a coordinator call), or
(b) register the MJE-03c load-recurrence as a determinism child card first.
The corpus re-pin and F-224-1 fixes on `101c1e4` stand independently of this
decision and are already committed on local `main` (unpushed).

---

## Adjudicated resume, 2026-10-27 12:47–13:07 local

### Coordinator adjudication (verbatim substance, received 2026-10-27)

The stopped gate failure is the standing MJE-03c one-rerun family: exact
wave-A signature (`childId != "<none>"`, `MultiJobExportSpec.scala:860`),
`sbt-test` `timed_out=true` at the 1800 s ceiling, RAI-224 lineage names
`MultiJobExportSpec` load-sensitive, and stale foreign JVMs demonstrably
contended CPU during the run. The one-rerun allowance (session policy since
wave A) applies — this supersedes the narrower two-suite wording in the
original brief. Plan: reap stale JVMs, confirm a calm window, disclose, then
rerun `make quality-changed QUALITY_EXECUTE=1` (rerun #1). 10/10 -> continue
the full halted pipeline; a repeat MJE-03c failure or any other failing suite
-> verbatim stop, no push, no flips.

### Reap results (2026-10-27 ~12:47–12:49 local)

Every pid was re-checked via `ps`/`lsof` (cmdline, etime, cwd) before
signalling; all predated the 12:38 cutoff given by the coordinator; none was a
process started after it. All 26 targets exited on SIGTERM; no SIGKILL was
needed; post-reap sweep found no java/sbt process left under `wt/` or
`/var/folders/.../opencode/waveB-wt/`.

Day-old wave-B orphans (4):

| pid | started | cwd | identity |
| --- | --- | --- | --- |
| 85261 | 10-26 02:44:55 | `T/opencode/waveB-wt/rai-214` | openjdk java, sbt launch (`-Xmx1024m`), etime 1d10h |
| 35151 | 10-26 02:24:04 | `T/opencode/waveB-wt/rai-212/modules/repl-engine` | openjdk 27 test-agent (`-classpath` ForkMain), etime 1d10h |
| 61375 | 10-26 03:11:13 | `wt/rai-218/modules/repl` | openjdk 27 sbt (`@sbt-args…`), etime 1d09h |
| 22989 | 10-26 02:55:15 | `T/opencode/waveB-wt/rai-214/modules/repl` | openjdk 27 sbt (`@sbt-args…`), etime 1d09h |

wt/rai-205 stuck `java -cp` corpus consumers (22 — the six named by the
coordinator plus 16 more found by the mandated sweep, all the identical leak
signature: `/usr/bin/java -cp …/wt/rai-205/artifacts/cross-ecosystem/classes…`
xe consumer/stand invocations; one inspected verbatim is the
`budget-extract` scenario consumer with `--usage-out 50 --content-file
…/expected/budget-extract-content.json --marker …/xe-ws/budget/marker.txt`):

| started | pids |
| --- | --- |
| 06:09:43–06:26:54 | 42877, 43418, 47964, 49034, 49543, 50511, 51127, 51170, 57139, 57927, 57940, 69217, 69231 |
| 06:36:22–06:36:23 | 85074, 85087 |
| 08:09:54–08:12:23 | 93898, 93912, 94692 |
| 11:49:39–11:52:45 | 96190, 96203, 97659, 97672 |

(Also reaped earlier in this session, before this record section: this
session's own leaked xe stands 4903/4925/6359 and the sbt orphan 24924 from
the timed-out gate run — see Limitations above.)

### Load readings and calm-window status

- 12:47 local: 1/5/15-min load `5.63 / 5.40 / 10.04` on 18 logical cores
  (6 performance); top process a foreign `go test -race` (pid 9965, cwd
  `tokenrouter-imp-fleet-w24-01`, ~136 % CPU), started 12:50:29.
- 12:50–12:54: go-test transient peaked 1-min load at `16.77`, finished
  12:54; residue `go-build` at ~33 %.
- 12:55 onward: the host's `Virtualization.framework` VM (pid 41365, foreign,
  not a scalaagent lane, NOT in the adjudicated reap scope) began cycling:
  3–4 minute bursts at ~1100–1330 % CPU (≈11 of 18 cores) alternating with
  1–2 minute near-idle gaps (~40 % CPU). Observed minute-by-minute
  12:56–13:07: 1-min load oscillated `5.89 → 23.30` with 5/15-min at
  `11.16–15.60 / 10.74–13.56`; VM bursts recurred at 13:00, 13:04, 13:07.

**Calm-window precondition (step 2) is NOT met**: the foreign VM workload is
outside this session's authority to terminate, its bursts are longer than the
quiet gaps, and a ~30-minute `sbt-test` run cannot fit between them. Rerun #1
under the MJE-03c allowance is prepared but NOT started; starting it under
this contention would reproduce the exact conditions the adjudication
identified and would make any result uninterpretable. Next step: hold until
the VM workload ends (or the coordinator directs otherwise), verify
1-min load low and stable with no foreign JVMs/lanes, then execute rerun #1.

### Owner override and rerun #1 launch (2026-10-27 13:22–13:26 local)

**Owner decision (via coordinator, 13:22 local): run rerun #1 NOW despite
foreign load.** Coordinator executes; this session records the override and
the tradeoff: evidence obtained under the disclosed foreign contention is
weaker than a calm-window run for the load-sensitive MJE-03c family, but the
owner explicitly accepted that cost. Stop conditions unchanged: MJE-03c
repeat or any other failing suite -> verbatim stop, no push, no flips.

Readings at the override moment, 13:22 local: 1/5/15-min load
`8.62 / 14.70 / 14.81`; VM pid 41365 (started 10-24 23:45, etime 2d13h) at
29.9 % in a gap, known to cycle 2–4 min bursts at ~1100–1330 % CPU; foreign
`go test -json -race -shuffle=on -count=1` (pid 51854, tokenrouter,
`handlers.test`) at 99.7 % CPU.

Readings immediately before launch, 13:25:39 local: 1/5/15-min load
`7.85 / 11.02 / 13.18`; VM 41365 at 136.8 % (gap); go test 51854 exited;
remaining foreign consumer: PyCharm (pid 76541) at ~424 % CPU. No scalaagent
lane activity; no foreign JVMs under `wt/` or opencode worktrees (sweep clean
after the 26-pid reap).

Rerun #1 command: `make quality-changed QUALITY_EXECUTE=1` — launched
13:26 local against tree `dd28d76`. Result section to follow below.

### Rerun #1 result: 10/10 PASSED (2026-10-27 13:26–13:36 local)

`make quality-changed QUALITY_EXECUTE=1` exited 0 — all ten fast gates passed;
`sbt-test` exit 0, `timed_out=false`, duration 559.81 s (vs the failed run's
1800.08 s ceiling kill). Manifest
`artifacts/quality/20261027-132619-64417-0001/manifest.json`, sha256
`b4bda7ff37ec9a4c31dcc95906891dff7483472d3806eed5412365d76d191db4`, status
passed, window 10:26:19Z–10:36:39Z; `sbt-test.log` sha256
`808a46a6f9c6b62fe1f1e5d0eabf7ad9ea7fec8063526125368a589a4c189043`; cli
module summary 604 passed / 0 failed — the MJE-03c assertion
(`MultiJobExportSpec.scala:860`, red in the 12:04 run) does not appear in the
rerun log at all.

### Profile union result: 13/13 PASSED (2026-10-27 13:37–13:51 local)

`make quality-profile PROFILES="fast docs contracts ci runtime eval security"`
exited 0 — gates: sbt-test (641.52 s, exit 0, `timed_out=false`), cli-smoke,
core-contracts-spec, compile-fixtures, plan-validate, eval-corpus,
self-registry-check, sbt-compile, scalafmt-check, scalafix-check,
forbidden-apis, license-audit, security-secrets-scan. Manifest
`artifacts/quality/20261027-133748-69535-0001/manifest.json`, sha256
`5fec539c8a05091bc321ac1b1cb625ae1e6e90a08742385414023cfa6d56748d`, status
passed, window 10:37:48Z–10:51:10Z; sbt-test log sha256 `4d486e778303f387…`
(truncated prefix as printed by the runner; full value in the manifest).

### Completion of the halted pipeline

With rerun #1 green, the pipeline resumed exactly as briefed: flips
(RAI-205/222/223/224/225 → accepted; RAI-206 → ready) in `backlog.json`
(245 cards, `raider_count` 236), `m1.md`, and the `docs/tasks/README.md`
index; board.md wave-D entry (both gate manifests and the failed run
disclosed); `python3 scripts/plan/validate.py --out artifacts/plan-review/
report.json` exit 0, 245 cards; push; worktree/branch cleanup. See the board
entry for the full evidence set and follow-ups.
