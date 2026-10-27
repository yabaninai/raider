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
