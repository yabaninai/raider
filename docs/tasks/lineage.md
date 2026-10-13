<!-- Cards for P-LIN (roadmap-12k rev 2026-10-10, ADR-027). Same rules as m0.md. -->

# P-LIN: Session lineage — the call tree as a valid Scala program

Priority: ahead of the rest of the plan (owner decision, 2026-10-10, ADR-027).
Contract and decisions: [ADR-027](../adr/ADR-027-session-lineage.md). Shared DoD
is in the [index](README.md); gates follow [quality-gates](../quality-gates.md).

## LIN-01: SessionGraph model and events → graph reducer

Status: ready. Milestone: LIN. Dependencies: RAI-004.

**Scenario and contract.** Event-sourced session graph with ModelCall/ToolCall(kind, RecoveryClass)/Delegation/Phase nodes; the events.jsonl → graph reducer is deterministic; node kinds can be extended without changing the contract.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/runtime/src/test/scala/raider/runtime/lineage/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN1-01: a real campaign events.jsonl file produces a graph; repeating the reduction produces an identical hash.
- Error/adversarial: LIN1-02: malformed events produce a typed error, not a silent skip.
- Cancellation/concurrency: LIN1-03: parallel children produce correct subtrees.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-02: Project the graph into a valid Scala program

Status: todo. Milestone: LIN. Dependencies: LIN-01, RAI-017.

**Scenario and contract.** Deterministically project the graph into lineage.scala: nodes become Program/Task compositions (RAI-017 is frozen), delegations become nested blocks, phases become defs; data is written to lineage-data.json.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/**` `contracts/lineage/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN2-01: the projection compiles with pinned scalac; repeated output is byte-identical.
- Error/adversarial: LIN2-02: an unknown node kind is rejected rather than emitted as invalid code.
- Cancellation/concurrency: LIN2-03: concurrent reads observe a snapshot.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-03: Live incremental graph and projection support

Status: todo. Milestone: LIN. Dependencies: LIN-01, LIN-02.

**Scenario and contract.** Incrementally extend the graph and projection during a session; periodically compile and validate the incremental output; on crash, the artifact remains consistent through the last completed node.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/cli/src/main/scala/raider/cli/run/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN3-01: the artifact matches the graph after every round, for at least 200 rounds.
- Error/adversarial: LIN3-02: a compile failure does not crash the session; the invalid status is visible.
- Cancellation/concurrency: LIN3-03: a crash between nodes leaves the artifact consistent through the last completed node.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-04: Lineage tab in the TUI

Status: todo. Milestone: LIN. Dependencies: LIN-03.

**Scenario and contract.** Minimal tab host (one tab, without P2 drafts): session tree, lineage.scala source, and validity status; accessible during a live session without blocking input.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/ui/**` `modules/cli/src/main/scala/raider/cli/chat/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN4-01: the tab remains live without blocking input.
- Error/adversarial: LIN4-02: an invalid projection has an honest status.
- Cancellation/concurrency: LIN4-03: switching tabs does not desynchronize the view.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-05: Replay-recorded interpreter and graph comparator

Status: todo. Milestone: LIN. Dependencies: LIN-02, LIN-03.

**Scenario and contract.** Execute the lineage program in replay-recorded mode (default): use LLM responses from the recording and recompute deterministic tool calls. Success means an identical call graph, checked by a graph-hash comparator.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `scripts/quality/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN5-01: replay produces an identical normalized graph hash.
- Error/adversarial: LIN5-02: a mismatch fails and identifies the first divergent node.
- Cancellation/concurrency: LIN5-03: interruption leaves a consistent partial graph and checkpoint.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-06: Live rerun, mutation isolation, and diff report

Status: todo. Milestone: LIN. Dependencies: LIN-05.

**Scenario and contract.** Opt-in live rerun makes real calls. Mutating operations run only in an isolated WS copy (RecoveryClass policy); enforce a budget; report the diff between the live and recorded graphs.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/tools/src/main/scala/raider/tools/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN6-01: the live rerun completes in isolation and reports differences by phase.
- Error/adversarial: LIN6-02: reject Mutating operations outside isolation before execution; stop when the budget is exhausted.
- Cancellation/concurrency: LIN6-03: on cancellation, classify the copy; a retry creates no duplicates.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-07: Partial replay of phases/subtrees

Status: todo. Milestone: LIN. Dependencies: LIN-05.

**Scenario and contract.** Replay a named phase/subtree with an explicit input contract; isolate non-overlapping phases; report contract errors instead of substituting empty values.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN7-01: replay a phase using only its subtree and inputs.
- Error/adversarial: LIN7-02: external dependencies produce a contract error.
- Cancellation/concurrency: LIN7-03: concurrent phase replays are isolated.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-08: Scopes (chat/headless/campaigns), pinning, catalog

Status: todo. Milestone: LIN. Dependencies: LIN-03, LIN-05.

**Scenario and contract.** Lineage for chat, headless, and overnight campaigns (tree catalog); compare the pinned environment: warning + attempt + discrepancy report.

**Context.** ADR-027; roadmap-12k P-LIN; runtime-contracts §14

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/**` `modules/runtime/src/main/scala/raider/runtime/lineage/**` `docs/lineage.md` Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN8-01: Catalog with graph-hash and statuses; chat==headless format.
- Error/adversarial: LIN8-02: Pinning differs — warning and marking.
- Cancellation/concurrency: LIN8-03: Catalog during parallel runs — atomic append.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** See the card in docs/tasks/lineage.md.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-09: Write lineage artifacts beside the production run

Status: ready. Milestone: LIN. Dependencies: LIN-03.

**Scenario and contract.** On settle, HeadlessRunner writes lineage.scala (projection) and lineage-data.json beside the run events.jsonl/summary; atomic write in the run directory; projection/write failure does not change the run exit code but is reported in summary; chat==headless format.

**Context.** docs/lineage.md §9.2 “Write artifacts to the run directory” and §10; LIN-03 obligation; ADR-027.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/test/scala/raider/cli/**` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN9-01: Production run leaves valid lineage.scala + lineage-data.json (frozen schema, compile-gate passes on artifacts).
- Error/adversarial: LIN9-02: Projection failure — typed diagnostic in summary; run exits with its normal exit code.
- Cancellation/concurrency: LIN9-03: Parallel runs — artifacts in separate run directories, without overlap (atomic per-run).

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Campaign catalog (LIN-08 scan/persistence), live-wire, changing the frozen lineage schema.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-10: CLI command surface for replay

Status: ready. Milestone: LIN. Dependencies: LIN-05.

**Scenario and contract.** Standalone CLI entry for replay: flags/command run ReplayHarness on lineage-data.json from a run/catalog, print Match/Mismatch with first divergent node and graph-hash, and distinguish outcomes by exit code; recorded-only, no model calls.

**Context.** docs/lineage.md §9.2 “CLI command surface for replay”; ReplayHarness/ReplayModelBackend ready (§9.1, LIN5-01 end-to-end); ADR-027.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/**` `docs/lineage.md` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN10-01: Replay from LIN-09 artifacts — Match + graph-hash; Mismatch prints the first divergent node.
- Error/adversarial: LIN10-02: Missing/malformed lineage-data.json — typed error and non-zero exit.
- Cancellation/concurrency: LIN10-03: Parallel replays — isolated workspaces (verifyUntouched), no shared writes.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Live model behind replay (LIN-06), changing the ReplayHarness contract, second runner/scheduler.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-11: LineageRenderer: compilable graph projections without named-def roots

Status: ready. Milestone: LIN. Dependencies: LIN-02.

**Scenario and contract.** LineageRenderer emits a syntactically valid outer projection scope for graphs without named-def roots (a comment-only object body is closed with a legal statement, e.g. `()`); every valid graph projection compiles with pinned dotc 3.9.0; other projection formats remain unchanged.

**Context.** LIN-09 contract-change request (reproducer: object X with indented comment-only body → dotc “indented definitions expected, eof found”); LIN2-01 compile-gate; docs/lineage.md §9.1.

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/proj/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN11-01: Projection of a plain-run graph (model/tool-only, without named-def) compiles with pinned dotc 3.9.0 (compile-gate).
- Error/adversarial: LIN11-02: Negative compile-gate catches projection body corruption (the negative remains negative).
- Cancellation/concurrency: LIN11-03: Repeated projection is byte-identical; LIN2-01 golden tiny-graph/deep-tree invariants remain unchanged.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Changing frozen lineage-data schema, GraphHash/comparator normalization, or existing golden projection format.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## RAI-109: Add replay to the raider product command (CLI wiring for LIN-10)

Status: ready. Milestone: LIN. Dependencies: LIN-09, LIN-10.

**Scenario and contract.** Shared CLI subcommand: `raider replay <run-dir> --workspace <dir> [--data <file>]` matches LIN-10 flags and exit codes (0/10/20/21/22/23/24/26) by delegating to ReplayCli.exec without duplicating logic; docs/lineage.md §10 is updated to use the product entry point; standalone ReplayCliMain is retained as a transitional entry point.

**Context.** LIN-10 accepted (standalone entry; reviewer risk 1: wiring was a separate card); docs/lineage.md §10; ci-runtime §8 exit mapping.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/**` `docs/lineage.md` Tests belong within the same modules/contract areas. Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN13-01: the product entry point returns Match + graph hash/exit 0 and Mismatch/exit 10 with the first divergent node (process mocks through `raider replay`).
- Error/adversarial: LIN13-02: malformed/missing lineage-data.json returns a typed error + exit 21; usage/unknown flag returns a typed error and invalid exit.
- Cancellation/concurrency: LIN13-03: parallel `raider replay` invocations use isolated workspaces (verifyUntouched), with no shared writes.

**Required gates.** After foundation: `make quality-changed`; `make quality-changed QUALITY_EXECUTE=1`; applicable profiles per metadata; verify evidence for each required profile. Full/release aggregates are allowed only if they actually include the complete list. State external prerequisites explicitly; unavailable is not passed.

**Non-goals.** Live/hybrid replay flags (LIN-06), a second runner, changing the frozen exit mapping, or the headless JLine rule.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Rolling back the feature does not replay side effects; retain persistent incomplete/uncertain artifacts for reconciliation.

**Deliverables.** A minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record using the template, and current evidence/log hashes. Real paid calls or publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-12: Tree catalog persistence (LIN-08 remainder)

Status: ready. Milestone: LIN. Dependencies: LIN-08, LIN-09, LIN-10.

**Scenario and contract.** Add persistence to LineageCatalog: scan records (run-dir, graph-hash, verifyPartial verdict, pinned-env summary) are atomically saved (tmp+move/single-writer LineageDataStore pattern) in campaign catalog; concurrent appends are atomic without loss; reload roundtrip is consistent; chat==headless format; malformed persisted file yields typed error, not silent handling.

**Context.** docs/lineage.md §9.2 (LineageCatalog scan without persistence) and §5; LIN-08 accepted in code; LIN-09/10 provided artifacts and CLI surface.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/cli/src/test/scala/raider/cli/**` `modules/runtime/src/test/scala/raider/runtime/**` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LINC-01: persist+reload roundtrip — identical records (graph-hash, verdicts); replay CLI can read catalog.
- Error/adversarial: LINC-02: Malformed persisted catalog — typed error identifies record; scan does not fail silently.
- Cancellation/concurrency: LINC-03: Parallel appends from multiple runs — atomic, zero losses, deterministic read order.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Changing frozen lineage-data schema, live-wire, catalog UI, or a distributed catalog.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-13: Sync docs/lineage.md §9.2 — truthful implementation status

Status: ready. Milestone: LIN. Dependencies: LIN-09, RAI-108, RAI-109.

**Scenario and contract.** Bring §9.2 in line with integrated state at lane base commit: TabShell wiring complete (RAI-108 --tabs), run artifact writing complete (LIN-09: lineage.scala/lineage-data.json beside events.jsonl/summary), verify product raider replay entry point (RAI-109); every item links to evidence (record or file:line); open items (live-wire, multi-job export, phase/recovery schema-card, LIN2-03 coverage, LIN-12 persistence) remain honestly open with card links; claim nothing beyond integrated state.

**Context.** docs/lineage.md §9.2 snapshot caught W21: TabShell-wiring and “write artifacts to run directory” rows became outdated after RAI-108/LIN-09 (both accepted; records in docs/work/records/).

**Allowed paths.** `docs/lineage.md` Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: DOC-13-01: Every §9.2 row has an evidence link (record path or file:line) verifiable at base commit.
- Error/adversarial: DOC-13-02: No in-flight/owner-gated item is marked complete; fact/text mismatch is a finding, not an omission.
- Cancellation/concurrency: DOC-13-03: Docs profile passes; anchors/links valid; diff only in docs/lineage.md.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Product code, changing ADR-027/schema contracts, rewriting §9.1 beyond facts, board/task files.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-14: LIN2-03 remainder: concurrent projection reads — snapshot coverage

Status: ready. Milestone: LIN. Dependencies: LIN-02.

**Scenario and contract.** Test coverage for LIN2-03 (“concurrent read — snapshot”): property spec verifies concurrent LineageDataStore/projection reads during atomic tmp+move writes see either the old or new COMPLETE document, never partial; reads do not mask a race as Missing/Corrupt; deterministic seed, ≥100 iterations, 3 repetitions without flakes. Do not silently fix a discovered defect here: typed reproducer + contract-change request (fix is a separate card).

**Context.** docs/lineage.md §9.2 “Other, honestly”: LIN2-03 is partially covered; store is atomic (tmp+move), formal concurrency coverage is absent.

**Allowed paths.** `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN2-03-01: Property ≥100 concurrent read/write iterations — 0 partial reads (each read is a valid schemaVersion 1 document).
- Error/adversarial: LIN2-03-02: 3 repetitions pass (flake=0); reproduced defect has typed reproducer in record, assertions not weakened.
- Cancellation/concurrency: LIN2-03-03: Repeated read projection is byte-identical for unchanged document; new files only in allowed paths.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Changing LineageDataStore/codecs/frozen schema, changing LIN-12 scope, utilities outside the spec.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## LIN-15: LIN-06 scripted acceptance — LIN6-01/02/03 (budget/isolation/cancellation)

Status: ready. Milestone: LIN. Dependencies: LIN-06.

**Scenario and contract.** Deterministic offline LIN-06 acceptance using existing mechanics (IsolatedRerun/WorkspaceIsolation/RunDiffReport/ReplayHarness/Admission), with backend EXPLICITLY labeled scripted: LIN6-01 isolated rerun completes with per-phase live-vs-recorded diff; LIN6-02 budget exhaustion stops rerun with typed error (RerunError.Replay over Admission), mutating node outside isolation rejected BEFORE execution; LIN6-03 interrupt during rerun leaves copy classified/consistent and retry does not duplicate side effects. Minimal typed fixes to product files in allowed paths are permitted if acceptance reveals a defect; each fix needs a reproducer in record.

**Context.** W22 review: L52 (LIN-06C)/L53 (LIN-07-full)/L54 accepted; F-W22R1-1 closed in 5ff5318; IsolatedRerunSpec covers happy-path/tamper, but LIN6-02 (budget) and LIN6-03 (cancel/retry) acceptance cases are not fixture-tested.

**Allowed paths.** `modules/cli/src/test/scala/raider/cli/lineage/**` `modules/cli/src/main/scala/raider/cli/lineage/**` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: LIN6-01(sc): LIN6-01(sc): isolated rerun completes; diff report is per-phase and deterministic (identical on repeat).
- Error/adversarial: LIN6-02(sc): LIN6-02(sc): exhausted budget stops with typed error before exceeding budget; mutating outside isolation rejected before execution (product path, not only test helper).
- Cancellation/concurrency: LIN6-03(sc): LIN6-03(sc): interrupt during rerun leaves no orphan writes to original; retry yields consistent verdict without duplicates.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Live model behind replay (owner live-wire), LIN-12 catalog, new wire events, weakening verifyUntouched/snapshot boundaries.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.

## RAI-120: IsolatedRerun canonical-path guard (requireIsolatedTarget alias bypass)

Status: ready. Milestone: LIN. Dependencies: LIN-15.

**Scenario and contract.** LIN-15 review finding (minor, blocker for future live-rerun CLI): requireIsolatedTarget compares target==original syntactically (Path equality); original alias (ws.resolve("..").resolve(ws.getFileName), symlink) passes byte check as “faithful copy,” so mutating replay would run IN-PLACE. Fix: canonical comparison (Files.isSameFile/toRealPath with MissingFile handling) BEFORE execution, typed rejection using existing MutatingUnisolated; reproducer tests for alias and symlink alias; do not weaken verifyUntouched discipline or LIN6 fixtures.

**Context.** LIN-15 review finding 1 (rationale: before connecting the guard to user targets in the CLI layer); reproducer: guard currently returns Right for target = original.resolve("..").resolve(name).

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests are within the same modules/contract areas. Confirm concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: ISO-01: Reviewer alias reproducer — typed rejection BEFORE execution (original bytes unchanged, parent clean).
- Error/adversarial: ISO-02: Symlink alias of original — typed rejection; missing target — honest typed error (not silent pass).
- Cancellation/concurrency: ISO-03: Legitimate isolated copy passes guard — existing LiveRerunScriptedAcceptanceSpec/IsolatedRerunSpec pass ×3 without assertion changes.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification for each required profile. Full/release aggregates are allowed if they actually include the full list. External prerequisites must be explicit; unavailable is not passed.

**Non-goals.** Live model (LIN-06 live-wire — owner), changing WorkspaceIsolation/isolate semantics, new typed cases outside the guard, LIN-12 catalog.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; handle versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are retained for reconciliation.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their combined contract gate.
