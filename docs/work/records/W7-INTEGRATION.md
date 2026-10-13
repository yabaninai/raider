# W7-INTEGRATION: P-LIN wave 1 integration (work record)

Status: done. Date: 2026-10-10 (UTC morning). Coordinator: overnight OpenCode
session (continuation of W7-PARALLEL). Baseline: root dirty tree after W6
(board-accepted), snapshot of 4,075 files (`raider-night/baseline-manifest.sha256.txt`).

## Resulting behavior

Integrated 8 results from wave 1 (10 lanes: 7 done, 1 blocked→rescope,
1 stopped without changes → redispatch, 1 review):

1. **LIN-01A** (L1): `raider.runtime.lineage.model` — SessionGraph (sealed
   SessionNode + 4 kinds, validated/childrenOf/roots, duplicate/parent/dataflow
   checks), GraphHash (SHA-256 canonical form, siblings normalized); 11 tests.
2. **LIN-01B** (L2): `raider.runtime.lineage.io.LineageEventReader` — reads
   events.jsonl, ParsedEvents/TruncatedTail/LineageError.
3. **LIN-02A** (L3): `contracts/lineage/**` — lineage-data.schema.json
   (schemaVersion=1), golden tiny-graph/deep-tree, README.
4. **LIN-04A** (L4): UI `:aux` — AuxTab/aux provider, CommandRouter/ShellCommand/
   ShellExecutor/ShellFeedback/TabBarRenderer/TabShell; registry unaffected;
   AuxTabSpec.
5. **LIN-05A** (L6, rescoped to runtime test scope): LineageCorpus —
   deterministic campaign generator (campaign(seed), corruptedTail,
   withSeqGap, wrongSchemaVersion) + spec. First attempt in testkit was
   honestly blocked (testkit↛runtime, build.sbt frozen); coordinator rescoped
   to runtime test scope, and code was delivered on a second run.
6. **LIN-02B** (L7): `raider.dsl.lineage.data.LineageData` — typed model +
   zio-json codecs for LineageDataDocument.
7. **LIN-03A-prep** (L10): HeadlessRunner streams events.jsonl during the run
   (EventsJsonlWriter, append+flush per line), start transition is the first
   line, Terminal is last; EventsJsonlWriterSpec + HeadlessRunnerCrashSpec.
8. **LIN-08A** (L5): `docs/lineage.md` — user-facing session-lineage spec.

L9 (review): baseline ACCEPT, contracts CHANGES-REQUIRED → ADR-027 §6
addendum (phase/recovery — wave 2 schema card; multi-job export — wave 2 CLI;
recording store — wave 2 slot 0). L8 (LIN-02C renderer): agent stopped before
work, 0 changes — redispatch in W8 (L12).

Method: byte-for-byte comparison of each lane artifact with its source
(cmp/diff -r), secret scan (clean), allowed-path check against diffs,
sequential copying, full root gates after integration.

## Commands / exits

| Command | Exit | Evidence |
| --- | --- | --- |
| `make quality-changed` (after L1+L5) | 0 | artifacts/quality/20261010-060836-57784-0001 |
| `make quality-changed QUALITY_EXECUTE=1` (after L1+L5) | 0 | 7/7 passed, manifest 20261010-060836-57784-0001 |
| `make quality-changed` (full integration of 8 lanes) | 0 | artifacts/quality/20261010-124556-37841-0001 |
| `make quality-changed QUALITY_EXECUTE=1` (full integration) | 0 | 7/7 passed (core-contracts-spec, compile-fixtures, plan-validate, self-registry-check, sbt-compile, scalafmt-check, sbt-test), manifest 20261010-124556-37841-0001 |
| `sbt --batch scalafmtAll` | 0 | reformatted 5 new files |

Integration incident: an incorrect `cp -R lane-03/contracts/lineage/ contracts/`
placed files one level too high; fixed by moving them to `contracts/lineage/`;
`diff -r` against lane-03 was IDENTICAL; tracked contracts/ files were not
affected (`git status`: only ?? additions).

## Limitations / blockers

- Lanes L2/L3/L4 finished without RECORD.md (processes stopped after writing
  code); their evidence is lane test reports and full root `sbt-test` 7/7.
  Independent review of the integrated tree is assigned (W8 L14).
- Root-level `contracts/README.md`/`contracts/golden/` did not exist (checked
  with `git ls-tree HEAD`) — no tracked content was lost.
- LIN-02C (renderer) is not implemented — redispatch L12; view-adapter and
  LIN-03B wait for it/reducer (wave W8+).

## Next

W8 launched: L11 (LIN-01C reducer), L12 (LIN-02C), L13 (LIN-02D store),
L14 (wave 1 review), L15 (RAI-072 docs), L16 (RAI-080 onboarding) —
`raider-night/W7-registry.md` §Wave W8.
