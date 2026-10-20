# LIN-08: work record

Status: partial. Date: 2026-10-20 (UTC). Remainder baseline: `f201be4d9727b7594739366f990f17be8251618d` (`lane/lin-08`, `wt/lin-08`).

## Resulting behavior

- Catalog records now carry an explicit `Chat`, `Headless`, or `Campaign` scope. `CatalogStore.recordsOf` and `refresh` accept the scope, and `CatalogStore.query` returns records from one scope in stable catalog order. Schema-version-1 records written before the added metadata remain readable and default to campaign scope.
- A catalog record may refer to a committed snapshot by manifest path and generation. Campaign refresh resolves and validates present RAI-173 commit pointers; malformed or mismatched commit metadata is a typed `InvalidSnapshot` and is not bypassed with fixed-name data/source reads.
- Pinning resolves and verifies the current commit pointer, atomically preserves its manifest under a generation-specific immutable name, and stores that stable manifest/generation reference. Catalog upserts retain the pinned reference. Eviction preserves pinned entries and returns a typed, non-mutating `PinnedCapacity` refusal if the requested limit is below the number of pins. Unpinning does not remove snapshot artifacts.
- The frozen `lineage-data` document and GraphHash were not changed. Catalog persistence remains schema version 1 with backward-compatible defaults for the new record metadata.
- Replay now reads an optional `<run-dir>/toolchain.json` before the isolated attempt. Matching pins leave the verdict unchanged. Divergent pins emit a warning before replay, attempt the replay, and annotate the resulting report with the differing fields. Missing pins preserve legacy behavior; malformed pins are typed input failures.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/CatalogStore.scala`
- `modules/cli/src/main/scala/raider/cli/lineage/LineageSnapshot.scala`
- `modules/cli/src/test/scala/raider/cli/lineage/CatalogStoreSpec.scala`
- `docs/lineage.md`
- `docs/work/records/LIN-08.md`
- `modules/cli/src/main/scala/raider/cli/lineage/ReplayCli.scala`
- `modules/cli/src/test/scala/raider/cli/lineage/ReplayCliSpec.scala`

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN8-SCOPE-01 | PASS: scope metadata and queries isolate chat, headless, and campaign entries | `CatalogStoreSpec`: “LIN8-SCOPE-01 scopes categorize chat, headless, and campaigns” |
| LIN8-SCOPE-02 | PASS: prior schema-version-1 catalog entries load with campaign as the compatibility default | `CatalogStoreSpec`: “LIN8-SCOPE-02 older schema-version-1 records load as campaign scope” |
| LIN8-PIN-01 | PASS: pin references an immutable verified commit manifest and survives a catalog upsert, pointer replacement, and eviction pressure | `CatalogStoreSpec`: “LIN8-PIN-01 pin retains immutable manifest reference across updates and eviction” |
| LIN8-PIN-02 | PASS: over-capacity eviction refuses typed and leaves file bytes and entries unchanged | `CatalogStoreSpec`: “LIN8-PIN-02 eviction refuses when pinned entries exceed capacity” |
| LIN8-POINTER-01 | PASS: catalog snapshot references are commit manifests/generations and resolve through the shared snapshot reader | `CatalogStoreSpec`: “LIN8-POINTER-01 catalog resolves and preserves only verified commit manifests” |
| LIN8-POINTER-02 | PASS: malformed commit metadata refuses refresh typed; no fixed-path fallback or catalog publication | `CatalogStoreSpec`: “LIN8-POINTER-02 invalid commit metadata refuses refresh without fallback” |
| LIN8-01 / LINC-01 | PASS in existing fixtures: graph hash and partial-verification status persist; chat/headless record shape remains shared | `CatalogStoreSpec` existing roundtrip and chat==headless fixtures; accepted LIN-12 evidence in `docs/work/records/LIN-12.md` |
| LIN8-03 / LINC-03 | PASS in existing fixtures: atomic concurrent appends, deterministic order, zero losses | `CatalogStoreSpec`: 8 writers × 5 records, same-key writers, and concurrent whole-document reads; LIN-12 accepted evidence |
| Snapshot protocol regression | PASS: existing reader/writer concurrent commit and malformed-pointer fixtures remain green | `LineageSnapshotProtocolSpec`, 4/4 |
| LIN8-02 match fixture | PASS: pinned environment matches; replay succeeds and report has no environment annotation | `ReplayCliSpec`: “LIN8-02: matching pinned environment replays without a report” |
| LIN8-02 mismatch fixture | PASS: mismatch warning is emitted before replay; replay still succeeds and report is annotated | `ReplayCliSpec`: “LIN8-02: divergent pinned environment warns, attempts replay, and annotates its report” |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short`, `git branch --show-current`, `git rev-parse HEAD` | 0; clean baseline, `lane/lin-08`, `90f6f882ebc6579b47de04301b0b96860a989bdd` | — |
| `sbt --batch raiderCli/scalafmtAll "raiderCli/testOnly raider.cli.lineage.CatalogStoreSpec raider.cli.lineage.LineageSnapshotProtocolSpec"` | 0; format passed; 24/24 passed, including the legacy schema default and refresh-through-pointer cases | Console |
| `make quality-changed` | 0; profiles `contracts`, `docs`, `static`, `unit` | Console |
| `make quality-changed QUALITY_EXECUTE=1` | 0; all 6 gates passed, no timeouts | `artifacts/quality/20261020-083528-91053-0001/manifest.json`; SHA-256 `cdcf38668308a312903715e5ae016e5d6cb553667304441123364a385b129ba0` |
| `make quality-profile PROFILES="static unit contracts runtime"` — first attempt | 1; `sbt-test` failed on existing `MultiJobExportSpec` post-Terminal fork assertion (`nativeLines = 1`, expected ≥2); not a pass | `artifacts/quality/20261020-080203-1139-0001/manifest.json`, `sbt-test.log` |
| `sbt --batch "raiderCli/testOnly raider.cli.run.MultiJobExportSpec"` | 0; 8/8 passed on isolated rerun | Console |
| `make quality-profile PROFILES="static unit contracts runtime"` — final rerun | 0; all 5 gates passed, no timeouts | `artifacts/quality/20261020-084231-3544-0001/manifest.json`; SHA-256 `80d2c4740f72d709dd2302ff59a4ae9b92da20cb03ea4d7be855d74dbc96a113`; `sbt-test.log` SHA-256 `b27b7d707078c792bd30fb9c60a537d0c162da6e7b165f52ca844bba2e3a81d7` |
| `python3 scripts/quality/quality.py verify … --require <profile>` | 0 for `static`, `unit`, `contracts`, `docs` on the changed manifest and `static`, `unit`, `contracts`, `runtime` on the profile manifest; all evidence fresh | Manifests above; verifier output confirmed policy/toolchain/log hashes and fresh fingerprint |
| `git diff --check` | 0 | Console |

### Remainder execution at `f201be4` (2026-10-20)

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `sbt --batch raiderCli/scalafmtAll "raiderCli/testOnly raider.cli.lineage.ReplayCliSpec"` | 0; 9/9 passed | Console; includes both LIN8-02 replay fixtures |
| `make quality-changed` | 0; selected `contracts`, `docs`, `static`, `unit` | Console |
| `make quality-changed QUALITY_EXECUTE=1` | 0; all six reported gates passed | `artifacts/quality/20261020-092643-72668-0001/manifest.json`; SHA-256 `523e5f931a1b07da8028d7b1dd7ab4cecc1f3bbbc1d7a496199f84a7f8ee23a6` |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0; all five reported gates passed | `artifacts/quality/20261020-093450-83618-0001/manifest.json`; SHA-256 `60a25963d6845168ddcd2d75c198aef8106bc99087585a412c68dc98d33ef37d` |

Toolchain: Java 21.0.12.1, sbt 1.11.4, Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0. The final profile manifest reports test summary 5 passed, 0 failed, 0 skipped. Both final manifests were verified with the requested profile requirements before this operational record was written; the record path is on the quality runner's exact operational-record allowlist.

## Limitations / blockers

- This completes the scope categorization, scope-aware catalog access, snapshot-reference, pin, and eviction behaviors exercised above. It does not yet wire a chat-session producer and headless-run producer to automatically assign `Chat` and `Headless`; those producers/call sites must supply the scope through the catalog API.
- The requested producer-origin wiring remains incomplete: `ChatLoop` and the headless artifact/export entrypoints do not automatically publish catalog records in `Chat`, `Headless`, or `Campaign` scope. In particular, no chat lineage artifact producer exists at the inspected entrypoint. LIN8-01 is therefore not fully accepted by this remainder, despite the pre-existing scope metadata and catalog tests.
- LIN8-02 reads and compares a supplied run-local pin, but the existing headless/chat producers do not yet write `toolchain.json`; the fixture validates the replay consumer using a deterministic pinned file.
- Existing LIN-12 persistence uses an in-process per-path append lock; cross-process same-file append serialization remains outside its accepted contract. Generation files/manifests are retained; this change does not add generation-file garbage collection.
- The first full profile attempt encountered one unrelated nondeterministic MultiJobExportSpec failure. The targeted fixture passed on rerun and the complete required profile passed on rerun; the original failure remains recorded above.
- Lane evidence applies to this lane fingerprint. Integration into the coordinator candidate requires fresh gates there.

## Review and next task

No independent review was performed in this worker lane. The coordinator should schedule an entrypoint-scope wiring follow-up (or provide the missing chat lineage producer contract) before accepting LIN-08, then integrate and rerun gates on the candidate. LIN8-02 replay behavior is implemented and locally verified. No push or merge was performed.

Product-input checks used the protected quality runner. This record is operational evidence and does not rebind or change the recorded product fingerprint.
