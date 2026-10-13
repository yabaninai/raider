# LIN-10: work record

Status: done (lane lane/lin-10, worktree wt/lin-10). Date: 2026-10-12.
Baseline commit/source: 831dabf (clean status on entry, verified at lane start).
Evidence run: quality run_id `20261012-035305-84550-0001` (manifest sha256
`9d0292586c0624f4c69f8c7c166f69d2a8206017e778455dfbc7a6ec9cff48ee`,
fingerprint fresh — `quality-verify ... OK`).

## Resulting behavior

Standalone CLI entry for the replay chain (LIN-10, ADR-027 §4): command
`ReplayCli [replay] <run-dir> --workspace <dir> [--data <file>]` in
`raider.cli.lineage`:

- reads `lineage-data.json` from the run directory (or `--data`) through frozen
  `LineageDataStore.read` (strict single-value decoding);
- isolates the workspace: a one-time copy via `WorkspaceIsolation.isolate`
  into a unique staging directory per call (UUID — parallel calls do not share
  a write target), byte snapshot of the original before replay, then
  `verifyUntouched` — an isolation violation overrides any verdict with a
  typed error;
- replays the recording through frozen `ReplayHarness` (recorded-only:
  `ReplayModelBackend` plays responses from the recording; no model/paid calls);
- compares graphs using `GraphComparator.compareNormalized`:
  - `verdict: match` + normalized graph hash + node count (stdout);
  - `verdict: mismatch` + deterministic `RunDiffReport` (first diverging node
    in normalized IDs, both hashes, node counts);
- typed errors (closed set `ReplayCli.Failure` over frozen types `StoreError` /
  `IsolationError` / `RaiderError` / `ReduceError`) — `typed-error: …` on
  stderr; a schema-valid but unreducible document (`recordedGraph` refused) is
  typed `Record`, not a defect;
- exit codes follow only frozen ci-runtime §8 (`raider.core.result.ExitCodes`):
  0 = Match, 10 = Mismatch (TaskRejected), 21 = corrupt/missing data.json,
  argv misuse, rejected workspace shape, unreducible document; 20 = runtime/IO/
  reduce/replay-loop; 22/23/24 = typed `RaiderError` mapping; 26 = Tampered
  (source changed during replay — verdict is untrusted).
- own argv parser (optional `replay` literal, positional `<run-dir>`, required
  `--workspace`, optional `--data`); shared `raider.cli.run.CliArgs` and
  `run/**` (lane LIN-09) unchanged — integrating into product command `raider`
  is a separate coordinator card.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/ReplayCli.scala` (new — entry,
  parser, pipeline, rendering, exit codes);
- `modules/cli/src/test/scala/raider/cli/lineage/ReplayCliSpec.scala` (new —
  7 behavioral tests LIN10-01/02/03 + parse/exitCode);
- `docs/lineage.md` — §9.1 (+LIN-10 row), §9.2 (honest status of standalone
  entry), §10 (actual command, output format, exit codes, example).

Lineage public API/schemas were not changed: `ReplayHarness`, `ReplayModelBackend`,
`RecordedSource`, `GraphComparator`/`ReplayVerifier`, `WorkspaceIsolation`,
`lineage-data.schema.json` are consumed as-is (frozen).

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN10-01 (Match + graph hash) | passed: replay document from run directory → Match, hash matched `compareNormalized(recorded, recorded)`, exit 0 | `ReplayCliSpec` “LIN10-01: … Match” in `sbt-test.log` (manifest 20261012-035305-84550-0001, approx. line 679); process smoke 1: `verdict: match`, `graph-hash: be568cfb…de59`, exit 0 |
| LIN10-01 (Mismatch + first diverging node) | passed: round-1 response mutation → Mismatch, first divergence = `job-1:n7` (missing-node, normalized ID), hashes differ, exit 10 | `ReplayCliSpec` “LIN10-01: … Mismatch”; process smoke 2: `first-divergence: missing-node`, `path: root -> job-1:n7`, exit 10 |
| LIN10-02 (corrupt/missing data.json) | passed: `StoreError.Missing` / `StoreError.Corrupt` → typed-error, exit 21 (nonzero) | `ReplayCliSpec` “LIN10-02: missing/corrupt …”; process smokes 3/5 (exit 21, `typed-error: lineage-data …`) |
| LIN10-03 (parallel replays, verifyUntouched) | passed: two parallel replays of one run against one original — both Match, both passed internal verifyUntouched, test verification of original against pre-snapshot was Right | `ReplayCliSpec` “LIN10-03: parallel replays …”; process smoke 6: two concurrent `java` processes, both exit 0, same hash, `ws/notes.txt` byte-identical |
| Gates (static/unit/contracts/docs) | passed, verify OK (fingerprint fresh) | `artifacts/quality/20261012-035305-84550-0001/manifest.json` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` | 0 (classification: contracts/docs/static/unit) | stdout JSON, same tree |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (passed) | `artifacts/quality/20261012-035305-84550-0001/manifest.json` |
| `make quality-verify MANIFEST=artifacts/quality/20261012-035305-84550-0001/manifest.json PROFILES=static,unit,contracts,docs` | 0 — “satisfies … (policy/toolchain/logs hashes checked, fingerprint fresh)” | verify output |
| `sbt 'raiderCli/testOnly raider.cli.lineage.ReplayCliSpec'` | 0 — 7 tests passed, 0 failed | before gate run; final tests in gate `sbt-test.log` |
| `java -cp <cp> raider.cli.lineage.ReplayCli <run-1> --workspace <ws>` (smoke Match) | 0 | stdout: verdict/graph-hash/nodes |
| `… ReplayCli <run-2> --workspace <ws>` (smoke Mismatch) | 10 | stdout: RunDiffReport (job-1:n7) |
| `… ReplayCli <run-missing> --workspace <ws>` | 21 | stderr: typed StoreError.Missing |
| `… ReplayCli <run-corrupt> --workspace <ws>` | 21 | stderr: typed StoreError.Corrupt |
| `… ReplayCli` (no arguments) | 21 | stderr: usage |
| `… ReplayCli <run-1> --workspace <ws>` × 2 in parallel | 0, 0 | both Match, original untouched |

Evidence note: intermediate gate run 20261012-034923-76295-0001 failed
(scalafmt-check: files not formatted; compile-fixtures: pre-existing
`artifacts/tools/m2` was missing from worktree — copied per
parallel-development §1a, untracked). After formatting, final run
20261012-035305-84550-0001 passed; sources were not changed afterward.

## Limitations / blockers

- ReplayHarness is a frozen fixture-campaign contract: user message, maxRounds,
  and emission-seq layout are fixed (see ReplayHarness scaladoc). An arbitrary
  run honestly matches only if recorded with the same loop layout; another
  shape diverges LOUDLY on the first field (comparator works as designed).
  Input generalization is not in this card (non-goal: changing ReplayHarness contract).
- Product command `raider` was not extended: standalone entry
  (`java -cp … raider.cli.lineage.ReplayCli`); adding flags to shared CLI is a
  separate coordinator card (`run/**` — lane LIN-09).
- Smoke `-cp` used `sbt 'export raiderCli/Compile/fullClasspath'`; no packaged
  jar/assembly was built for replay command (not required by card).
- Live model behind replay (LIN-06), campaign catalog (LIN-08 persistence) are
  outside scope.

## Review and next task

- Reviewer: assigned by coordinator (lane report = subagent final message;
  independent review follows §1a/§1b process).
- Next ready steps for P-LIN cards: integrate replay command into common CLI
  after LIN-09 (coordinate with `run/**`), LIN-08 campaign catalog (scan+persist
  over ReplayCli verdicts), partial-replay surface (LIN-07) over the same
  command mechanism.
