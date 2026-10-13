# LIN-12: work record

Status: done. Date: 2026-10-12 (UTC). Baseline commit/source: 4b46dc7 (lane/lin-12, git worktree wt/lin-12); head after work: f42fdae.

## Resulting behavior

`LineageCatalog` gains persistence (LIN-12, LIN-08 remainder): a new `CatalogStore` (modules/cli/.../lineage/CatalogStore.scala) atomically saves campaign catalog scan records (run-dir, rootId/sessionId, nodeCount, graph-hash, verifyPartial verdict complete/tail/nodeCount, mtime, pinned-env summary scala/jdk/raider/model) to `<campaignsRoot>/lineage-catalog.json`, following the LineageDataStore discipline: canonical bytes → unique tmp file in the destination directory → ATOMIC_MOVE; per-target in-process lock; no-fsync is honestly documented (visibility, not durability).

- **Append model** (intentional difference from `LineageDataStore.write`): append is a read-modify-write upsert keyed by run-dir; concurrent appends to one target are **queued** under the per-path lock (rejecting one would lose the losing writer's records, contradicting LINC-03 “0 losses”); read order is deterministic (mtime DESC, path ASC — directory order). Cross-process writers are out of scope, as with LineageDataStore.
- **Typed errors** (Missing/Corrupt/Io taxonomy as in LineageDataStore): a malformed persisted file yields `CatalogStoreError.Corrupt(path, record, detail)`, where `record` NAMES the damaged record (`#<index> (path: …)`) or the whole document (`CatalogStore.DocumentLevel`); strict single-value decode (wrapping in a one-element array cannot hide a malformed suffix); NO auto-repair, file is not rewritten.
- **`CatalogStore.refresh(runsRoot)`** — scan+persist round trip: malformed persisted catalog content fails TYPED before publishing a fresh scan (LINC-02 “scan does not fail silently”); Missing is normal on first run; tree damage is `RefreshError.Scan(CatalogError)`; file errors are `RefreshError.Store(CatalogStoreError)`. Each refresh stamps records with the pinned-env summary from `EnvPinning.currentEnv()`. Repeating refresh on an unchanged tree is byte-identical.
- **chat==headless**: one `CatalogRecord` shape without a mode discriminator; absent sessionId honestly omits the member (it is not invented).
- `LineageCatalog` remains a pure scan (only its scaladoc pointer to CatalogStore changed; behavior unchanged). The catalog is read by replay-CLI on top: persistence touches only the catalog file; run-dir and its artifacts are unchanged (captured by a behavioral fixture).

Public API contract: new types `CatalogRecord`/`TailInfo`/`PinnedSummary`/`CatalogStoreError`/`RefreshError`, methods `CatalogStore.{catalogFile,recordsOf,load,append,refresh}`. Frozen schemas (lineage-data, events) were not affected.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/CatalogStore.scala` — NEW: catalog persistence (549 lines).
- `modules/cli/src/main/scala/raider/cli/lineage/LineageCatalog.scala` — scaladoc only (1 line: “Persistence is the NEXT card” → pointer to CatalogStore).
- `modules/cli/src/test/scala/raider/cli/lineage/CatalogStoreSpec.scala` — NEW behavioral spec (803 lines, 14 tests).

Unchanged: modules/runtime/**, modules/dsl/** (LineageDataStore untouched), build.sbt/project/**, scripts/**, docs/** (except this untracked record), contracts/**.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LINC-01 (normal): persist+reload round trip is identical | PASS: reload == refresh; `records.map(_.toEntry) == scan` (full structural identity of CatalogEntry: graph-hash, verdicts, tail, mtime); repeated refresh is byte-identical; pinned-env summary survives round trip | CatalogStoreSpec: “refresh persists scan records…”, “repeat refresh of an unchanged tree is byte-identical” |
| LINC-01: catalog can be read by replay-CLI on top | PASS: run-dir with events.jsonl + lineage-data.json — refresh does not change a byte in run-dir; `ReplayCli.run` → Match(hash, 3 nodes, exit 0) on top of persisted catalog; record.complete=true, record.hash == campaign GraphHash (catalog surface), replay-hash == normalized self-hash (replay surface) | CatalogStoreSpec: “persisted catalog coexists with the replay CLI over the same run directory” |
| LINC-01: chat==headless format | PASS: one record shape; key sets for records with/without sessionId differ ONLY by the optional `sessionId` member (no mode discriminator); absence is honestly omitted | CatalogStoreSpec: “chat==headless: ONE record shape…” |
| LINC-02 (error/adversarial): damaged persisted catalog is a typed error identifying the record | PASS: one bad record among valid records → `Corrupt(path, record≈"…work-good…", detail)`; garbage/truncation/foreign schemaVersion/missing schemaVersion → document-level `Corrupt`; NO auto-repair (bytes of damaged file remain untouched) | CatalogStoreSpec: “one damaged record…”, “garbage, truncated and wrong-schemaVersion…” |
| LINC-02: scan does not fail silently | PASS: refresh over damaged persisted catalog → `Left(RefreshError.Store(Corrupt(path==file,…)))`; file bytes are NOT overwritten by fresh scan (tree is valid); tree damage → typed `Scan(RunUnreadable/NotFound/NotADirectory)`, nothing is persisted; append over damaged catalog → typed failure, file untouched; absent → `Missing`, directory-at-path → `Io` | CatalogStoreSpec: “refresh over a damaged persisted catalog…”, “refresh: scan damage refuses typed…”, “append over a damaged catalog…”, “load: absent catalog is typed Missing; a directory is typed Io” |
| LINC-03 (cancellation/concurrency): parallel appends are atomic, lose 0 records, and have deterministic ordering | PASS ×3 repeats: 8 writers × 5 records → exactly 40 records, `load == Right(expected sorted vector)` computed BEFORE the race; same-key from 4 writers → exactly 1 WHOLE record (no mixing); readers during appends see only whole documents (Corrupt/Io fails the test), final state is complete and ordered; empty append is a no-op without creating a file | CatalogStoreSpec: “8 writers x 5 records…”, “same run-dir key…”, “concurrent reads during appends…”, “append of an empty batch…” |
| Lineage CLI regressions | PASS: raider.cli.lineage.* — 127/127 | sbt run log (console) |
| Full suite | PASS: 1045 passed / 0 failed / 0 skipped (full `sbt --batch test` within the gate, 349 s) | artifacts/quality/20261012-184128-14887-0001/sbt-test.log |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` / `git log --oneline -1` (baseline check) | 0 (clean; 4b46dc7, branch lane/lin-12) | — |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.CatalogStoreSpec"` | 0, 14/14 passed | /tmp/opencode/lin12-run9.log (console) |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.*"` | 0, 127/127 passed | /tmp/opencode/lin12-lineage-all.log |
| `make quality-changed` (git-mode classify) | 0; changed=3 paths → profiles contracts/static/unit | console |
| `make quality-changed QUALITY_EXECUTE=1` (first run) | **2 — FAILED: scalafmt-check exit=1** (formatting); other 4 gates passed; thresholds were not weakened, formatting fixed with `sbt raiderCli/scalafmtAll`, gate rerun | artifacts/quality/20261012-182840-1661-0001/manifest.json |
| `make quality-changed QUALITY_EXECUTE=1` (rerun, after formatting) | 0; status=passed, 5/5 gates (core-contracts-spec, compile-fixtures, sbt-compile, scalafmt-check, sbt-test), timed_out=False throughout; full sbt-test 214 s | artifacts/quality/20261012-183606-6727-0001/manifest.json |
| `make quality-profile PROFILES="static unit contracts ci"` | 0; status=passed, 6/6 gates (including cli-smoke), full sbt-test 349 s, 1045/0 | artifacts/quality/20261012-184128-14887-0001/manifest.json |
| `git commit` ×2 in lane/lin-12 | 0 | 6070b8e (store+scaladoc), f42fdae (spec) |

Toolchain: JDK 21.0.12.1, sbt 1.11.4, Scala 3.9.0 pinned, ZIO 2.1.26 / zio-json 0.10.0 pinned. Bootstrap environment (artifacts/tools/m2) was preinstalled in the worktree.

## Limitations / blockers

- Final gates (183606, 184128) ran after the last source edit (scalafmt) and BEFORE commits; content did not change between profile and commits — evidence applies to tree f42fdae. Lane evidence does not replace main gates on the integrated candidate.
- The first execute run failed scalafmt-check (honestly recorded in the command table); after formatting, all gates were rerun from scratch, thresholds were not weakened.
- Cross-process concurrent appends to one file are out of scope (same boundary as LineageDataStore/JobJournal: tmp+move keeps each observed file whole; last complete write wins). The in-process LINC-03 guarantee is queuing under per-path lock.
- No-fsync: atomicity of visibility ordering, not durability on power loss (documented in scaladoc, same discipline as LineageDataStore).
- `CatalogStore` is not connected to the product CLI (catalog argv surface is a separate card/lane `raider.cli.run`); acceptance in this card is the store surface and behavioral fixtures, as in LIN-08B.
- docs/lineage.md §9.2 still describes the catalog as “scan without persistence” — editing it was forbidden in this card (forbidden paths); synchronizing §9.2 is card LIN-13 (under its contract, the line must receive an evidence link to this record/file).
- The events.jsonl test fixture locally duplicates `LineageCorpus.writeJsonl` semantics (raiderCli has no test→test dependency on raiderRuntime; build.sbt is frozen for the lane) — same pattern as in LineageCatalogSpec.

## Review and next task

Card LIN-12 is closed in the lane: all three acceptance cases (LINC-01/02/03) are covered by executable fixtures with actual IDs, card gates (static/unit/contracts/ci) are green, frozen schemas and other lanes were untouched. No push/merge was performed (prohibited) — the coordinator integrates into main and then reruns affected gates on the candidate. Next ready step: merge lane/lin-12 + main gates; then, per board, LIN-13 (§9.2 sync, now with evidence for the catalog line), LIN-14 (LIN2-03 snapshot coverage), LIN-15.
