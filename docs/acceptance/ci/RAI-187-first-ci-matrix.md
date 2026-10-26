# RAI-187 First CI joint acceptance: FCI-LIFE-01/02 and FCI-MATRIX-01

This record describes the joint First CI acceptance surface on the RAI-187 lane
candidate. It extends the RAI-185 process-boundary suite
(`raider.cli.run.FirstCiProcessAcceptanceSpec`) with two lifecycle clauses and
binds the whole First CI matrix to one machine-readable map
(`contracts/acceptance/ci/first-ci-matrix.json`). `raider.cli.workflow.WorkflowLaunchSpec`
(RAI-186) remains the owner of the `workflow run` launcher signal clauses; the
matrix references it where a clause spans both paths.

## New product behavior (RAI-187)

- `raider.cli.Main run` gains `--deadline <seconds>`: a parse-time validated,
  positive-integer bound on the BUNDLE program's work (ci-runtime §6: the
  internal deadline stays shorter than the CI timeout, leaving time for cleanup
  and artifact upload).
- Validation is typed and pre-dispatch: a missing, non-numeric, zero, or
  negative value is a `Configuration` refusal (exit 21) before any dispatch or
  spend; combining `--deadline` with `--agent` is a typed refusal so the flag is
  never silently ignored.
- When the deadline fires, the program fiber is interrupted inside the admitted
  budget slot (a real failed attempt in the ledger), the typed
  `DeadlineExceeded` error (code `RA-DEADLINE`) carries exit 24 (§8), and the
  normal artifact path renders the truthful failure: `run.json` (status failed,
  exit 24, `RA-DEADLINE`, real `accounting` splice), `result.json` (typed error
  verdict document, `task-failed`), `summary.md` (`FAILED — RA-DEADLINE: ...`),
  `checks.junit.xml`, and a complete `artifacts.json`. Nothing is reported as
  succeeded.
- Preflight and artifact/report writes stay OUTSIDE the deadline window, so a
  deadline hit still produces the complete honest artifact set.

## Coverage map

| Clause | Surface and checks | Executable evidence |
| --- | --- | --- |
| FCI-LIFE-01 | Real `--deadline 1` run against a 120 s sleeping fixture workflow (`raider.cli.run.LongSleepWorkflow`) exits 24 with the full honest artifact set; invalid deadline values (`0`, `-3`, `abc`, `1.5`) and `--deadline` + `--agent` are typed parse-time refusals (exit 21) with no artifact tree. | `FirstCiProcessAcceptanceSpec`: "FCI-LIFE-01: a real deadline run exits 24 with truthful deadline artifacts and no fabricated success"; "FCI-LIFE-01: invalid --deadline values are typed parse-time refusals before any dispatch" |
| FCI-LIFE-02 | The fixture workflow (`raider.cli.run.SpawnSleepWorkflow`) spawns a real grandchild (`exec sleep 300`) and records its PID; the suite parent delivers SIGTERM to the real CLI child (`Process.destroy()`), asserts exit 143 within 30 s, asserts the grandchild is destroyed within 10 s (no orphan), and asserts honest partial artifacts: streamed `events.jsonl` present and non-empty, `run.json`/`result.json` ABSENT, no success line on stdout/stderr. | `FirstCiProcessAcceptanceSpec`: "FCI-LIFE-02: parent-delivered SIGTERM stops the real child with bounded cleanup, exit 143, honest partial artifacts, and no orphan" |
| FCI-MATRIX-01 | Joint matrix of invocation/non-TTY, environment parity, pre-dispatch refusals, artifact/JUnit schemas, exit precedence, hostile-value neutralization, signal exit, and the new deadline clause, all executing as one sequential suite of real process children. | `FirstCiProcessAcceptanceSpec` (8 tests); machine-readable map `contracts/acceptance/ci/first-ci-matrix.json`; launcher-path signal clauses in `raider.cli.workflow.WorkflowLaunchSpec` (WRI-02/WRI-03, FCI-REPORT-01/03/04/05) |

## Explicit boundaries

- The deadline bounds the BUNDLE program only in this slice; built-in `--agent`
  runs keep their existing loop budgets and refuse the flag.
- FCI-LIFE-02 verifies bounded cleanup of the fixture's OWNED grandchild (the
  runner's scoped release destroys it during the SIGTERM stop). It does not
  claim that arbitrary user-spawned daemons are reaped.
- Signal delivery is POSIX-parent-delivered (`Process.destroy()` → SIGTERM) on
  macOS; the exit-143 mapping follows the existing OS signal acceptance
  (RAI-131/RUN-03, RAI-186). No in-process interrupt test substitutes for it.
- No second scheduler/runtime, no hosted CI calls, no paid provider calls
  (`--mock` only), no durable resume or exactly-once claims.
- The gate policy registry (`scripts/quality-policy/registry.json`) was NOT
  modified; the `sbt-test` 900-second ceiling is RAI-209's separate card.

## Observed process facts (RAI-187 deadline run artifact set)

From the persisted deadline fixture output (one representative run):

- `run.json`: `"status":"Failed"`, `"exitCode":24`,
  `"error":{"code":"RA-DEADLINE","detail":"bundle program exceeded --deadline 1s","retryable":false}`,
  `accounting` splice present.
- `result.json`:
  `{"code":"RA-DEADLINE",...,"taskVerdict":"task-failed","checkEvidence":["bundle-preflight","run-outcome","run-result-schema"],"checkPolicyDigest":"sha256-e16cf355821fe11d46124f693059bd1e53acb2b8cfc06b7f6a97de0337bbea38"}`.
- `artifacts.json` lists exactly the six completed files
  (`checks.junit.xml`, `events.jsonl`, `result.json`, `run.json`, `summary.md`,
  `toolchain.json`), each verified by size and SHA-256; no `.tmp` residue.
- `summary.md` status line: `FAILED — RA-DEADLINE: bundle program exceeded
  --deadline 1s`.

## Repeat commands

Focused suite (single command, one execution):

```sh
sbt --batch 'raiderCli/testOnly raider.cli.run.FirstCiProcessAcceptanceSpec'
```

Result: 8 tests passed, 0 failed, 0 ignored (after formatting). Full changed
module suite for regression scope:

```sh
sbt --batch raiderCli/test
```

Result: 538 tests passed, 0 failed, 0 ignored (5 m 4 s wall).

Gate commands are recorded in the RAI-187 work record with exact manifests,
SHA-256 hashes, and the honest sbt-test timeout outcome under documented host
load.
