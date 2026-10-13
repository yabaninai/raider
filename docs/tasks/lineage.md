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

## RAI-140: ADR-031: lineage projection runtime contract — typed dry-run description (LIN-02 completion contract)

Status: ready. Milestone: LIN. Dependencies: LIN-02.

**Scenario and contract.** LIN-02 audit finding: the renderer emits model/tool calls as COMMENTS and ()-bodies — compiling proves syntax, not the promised Program/Task composition. This contract card designs the missing public contract (ADR-031, independent review required): (1) a small projection runtime in dsl.lineage.proj.runtime — typed step constructors the GENERATED code references (model/tool step, inputs, dataRef) whose Task evaluates to a DRY structural report (Task.succeed/fail ONLY — no provider call, no tool dispatch at construction OR execution); (2) NodeView gains inputs: Vector[String] (graph dataflow edges) so dependencies are expressible; (3) explicit semantics table distinguishing: rendering vs typed description vs recorded replay (ReplayHarness, graph-based, existing) vs live rerun (IsolatedRerun, graph-based, existing) — the generated source is NOT a rerun entrypoint and must say so; (4) failure paths: a recorded-failed node projects to Task.fail(typed) so sequencing short-circuits honestly. Frozen API consumption only: Task (succeed/fail/map/flatMap), Program (fromFunction/andThen). No second execution semantics.

**Context.** Owner directive 2026-10-13 (P1): compiling comment-only output does not implement LIN-02's promised projection. Requires a separate ADR + independent review because it adds a public contract.

**Allowed paths.** `docs/adr/ADR-031-lineage-projection-runtime.md` `modules/dsl/src/main/scala/raider/dsl/lineage/proj/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: PRC-01: the runtime compiles; a hand-written sample projection builds a Program whose execution (Task.zio) returns the deterministic step report vector — order == declaration, zero provider/tool dispatch (assert via scripted probe backend absence).
- Error/adversarial: PRC-02: a failed node short-circuits with the typed error; inputs flow as threaded values; delegation/phase nest as sub-Programs.
- Cancellation/concurrency: PRC-03: concurrent construction/render of two projections is isolated (no shared mutable state); repeat execution is deterministic (identical reports).

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Provider/tool invocation from projected code, changing Task/Program frozen API, making the generated source a replay or rerun entrypoint, renderer rewrite (RAI-141), schema wire changes (RAI-143).

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-141: Renderer emits real Program/Task composition (LIN-02 implementation)

Status: ready. Milestone: LIN. Dependencies: RAI-140.

**Scenario and contract.** Implementation against ADR-031 (RAI-140): the renderer emits, per node, REAL composition referencing the projection runtime — model/tool steps with dataRef keys and threaded inputs (sequencing = declaration order = graph child order; dependencies = NodeView.inputs threaded as values), delegations/phases as nested defs building sub-Programs, failure nodes as Task.fail. Payloads stay in lineage-data.json (dataRef keys only). Deterministic byte-identical output preserved (LIN2-C-01 discipline); unknown kind still typed-rejected (LIN2-02). Golden projections regenerated; the pinned-dotc compile gate (LIN2-01) now compiles EXECUTABLE projections; comment-only bodies disappear (a projection with zero executable statements is a renderer bug). Comparator and recorded interpreter (graph-based) must be verified unaffected.

**Context.** Owner directive 2026-10-13 (P1); ADR-031 (RAI-140) defines the runtime; existing compile-gate evidence (compiles + byte-identical) is preserved and extended, not discarded.

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/proj/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` `contracts/lineage/golden/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: REN-01: golden projections compile (pinned dotc 3.9.0) AND are executable Programs — repeated render byte-identical; compile gate extended to assert no comment-only bodies (at least one real statement per def body).
- Error/adversarial: REN-02: unknown node kind → typed rejection (existing LIN2-02 stays green); malformed graph → typed render error.
- Cancellation/concurrency: REN-03: concurrent renders isolated (existing LIN2-C-03 stays green); comparator suite green without changes; recorded-interpreter suite green (layers independent).

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Wire event changes, GraphHash/normalization changes, comparator changes, replay/rerun behavior, provider calls.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-142: LIN-02 acceptance: executed projection semantics (sequencing, inputs, delegation, phases, failures)

Status: ready. Milestone: LIN. Dependencies: RAI-141.

**Scenario and contract.** Acceptance card: EXECUTE the generated projections (via Task.zio on the built Program) and pin MEANINGFUL semantics — model/tool sequencing equals graph order; input dependencies observable in the report (threaded values match NodeView.inputs); delegation nests (sub-program execution inside parent report tree); supported phases appear as named sub-programs; recorded-failure nodes short-circuit with the typed error; execution is deterministic (identical report vector on repeat); ZERO provider/tool dispatch during execution (scripted-backend probe asserts no calls — the projection is dry by construction). Fixture: a representative graph covering all node kinds + a failed node + a phase + a delegation, rendered, compiled (pinned dotc), executed.

**Context.** Owner directive 2026-10-13 (P1): acceptance must demonstrate structure and semantics, not syntactically valid Scala with comments.

**Allowed paths.** `modules/cli/src/test/scala/raider/cli/lineage/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: PRJ-01: executed projection returns the exact expected step-report tree (order, kinds, dataRef keys, threaded inputs, nesting) for the representative graph.
- Error/adversarial: PRJ-02: failed node → typed short-circuit error; the report prefix up to the failure is observable.
- Cancellation/concurrency: PRJ-03: repeat execution identical ×3; scripted probe backend records ZERO calls during compile AND execute; pinned-dotc compile gate green on the fixture.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Provider calls, replay/rerun semantics, wire changes, comparator changes.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-143: Schema card: versioned phase/recovery events + cross-run provenance contract (wave-2 wire extension)

Status: ready. Milestone: LIN. Dependencies: LIN-05.

**Scenario and contract.** Owner-authorized schema card (2026-10-13, P2): extend the FROZEN 13-payload event wire with VERSIONED additions — (1) phase/recovery payloads (phase enter/exit with recovery metadata) so Phase nodes are wire-native instead of model-derived; (2) multi-job correlation fields (invocationId/jobId on envelopes or a header record) so delegate children can join one provenance tree; (3) cross-run provenance record (parent run id, native artifact pointers). Versioning: schemaVersion 2 documents additive fields; v1 documents decode unchanged (back-compat pinned); the reducer accepts both. GraphHash canonicalization: new fields enter the canonical form in a VERSIONED way (hash version bump, documented BREAKING-for-comparators note) or are excluded by contract — decide and pin. PROTECTED change: independent review required; hidden fixtures untouched.

**Context.** Owner authorization 2026-10-13: the previously owner-gated phase/recovery schema work is authorized for the unified CI pipeline objective. Long-standing ADR-027 §6.2 obligation (phase/recovery schema-card, wave 2).

**Allowed paths.** `docs/adr/ADR-032-phase-recovery-multijob.md` `contracts/lineage/lineage-data.schema.json` `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/runtime/src/test/scala/raider/runtime/**` `modules/dsl/src/main/scala/raider/dsl/lineage/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: SCH-01: v2 wire fixtures decode; v1 fixtures decode UNCHANGED (back-compat pinned bit-for-bit); mixed streams typed.
- Error/adversarial: SCH-02: reducer maps new payloads to graph nodes (Phase native, provenance record) with the documented hash-version policy; golden graphs updated under the pinned policy.
- Cancellation/concurrency: SCH-03: malformed v2 payloads → typed reducer errors (no silent drops); unknown future version → typed, not guessed.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Second scheduler/runtime, live-wire, changing v1 historical fixtures silently, multi-job export implementation (RAI-144), renderer (RAI-141).

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-144: Multi-job lineage export: delegate children join one events stream with provenance

Status: ready. Milestone: LIN. Dependencies: RAI-143.

**Scenario and contract.** Runtime/export card on the RAI-143 contract: delegate CHILD jobs (DelegationToolset forks) emit their envelopes into the RUN's events.jsonl with the correlation fields (invocationId/jobId), so the reducer builds ONE tree with real parent-child edges — no invented edges between separate native roots. Preserve provenance: each child segment carries a pointer to its original per-job native artifacts (work dir, events.jsonl). Ordering: envelopes interleave by emission time with monotonic per-job seq; the reducer's canonical order remains deterministic (documented interleave policy). The application-owned index (BillingCi pattern) is NOT an edge source: separate native roots stay separate unless the correlation contract (RAI-143) links them.

**Context.** Owner directive 2026-10-13 (P2): BillingCi today has six independent native roots linked by an application index — no native phase graph; multi-job export was owner-gated and is now authorized.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/test/scala/raider/cli/**` `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/runtime/src/test/scala/raider/runtime/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: MJE-01: a delegation run (scripted) exports one events.jsonl containing parent+child envelopes with correlation; the reducer builds one tree; child provenance pointers resolve to the real per-job artifacts.
- Error/adversarial: MJE-02: per-job seq monotonic per job; interleave deterministic on replay; graph hash stable.
- Cancellation/concurrency: MJE-03: a child failure is a typed node in the unified tree (no silent swallow); torn child tail → partial-graph semantics (LIN5-03 discipline).

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Second scheduler/runtime, changing per-run artifact layouts silently, cross-ROOT stitching without the RAI-143 contract, renderer.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-145: Unified CI pipeline graph → DSL projection (BillingCi shape)

Status: ready. Milestone: LIN. Dependencies: RAI-142, RAI-144.

**Scenario and contract.** Projection card: the unified multi-job graph (repair → checks → reviews → boundary batch with REAL edges) projects through the ADR-031 runtime into an executable dry-run composition: phase defs (baseline/repair/final-check/reviews/boundary), delegation blocks where the contract links jobs, batch fan-out as the DSL batch/all composition the loop ACTUALLY used. The illustrative pipeline/parallel/batch syntax is NOT an approved API: the projection uses the existing frozen DSL verbs (andThen/all/batch + Task sequencing) or a reviewed contract addition — choose and pin in the card record. No invented parallelism: edges only from provenance/correlation.

**Context.** Owner directive 2026-10-13 (P2, decomposition chosen after dependency inspection): depends on the schema (143) and export (144) cards plus the P1 projection runtime (140-142).

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: UNI-01: a representative multi-job fixture (scripted, covering repair+reviews+boundary shape) projects and executes to the expected report tree with real nesting and batch fan-out; compile gate green (pinned dotc).
- Error/adversarial: UNI-02: no edge exists between roots the contract does not link — assert the report tree separates unlinked roots; provenance pointers preserved in reports.
- Cancellation/concurrency: UNI-03: malformed/unlinked segments → typed report entries, not silent merges; repeat execution deterministic ×3.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Live model calls, presentation-only graph assertions, changing the BillingCi example (RAI-147), wire changes.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-146: Independent acceptance/verifier coverage for unified lineage (multi-root corpus + verifier)

Status: ready. Milestone: LIN. Dependencies: RAI-145.

**Scenario and contract.** Independent acceptance card: a frozen multi-root corpus (golden unified graphs: billing-shape repair+reviews+boundary, delegation tree, unlinked-roots case) + a verifier that checks projected reports against the corpus (exact trees, hash pins, provenance resolution) and NEGATIVE controls (corrupted correlation → typed; invented edge → detected). Corpus is versioned; verifier is the protected acceptance surface for the unified lineage feature — future changes go through this card's review.

**Context.** Owner directive 2026-10-13 (P2: independent acceptance/verifier coverage as its own card).

**Allowed paths.** `contracts/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` `modules/runtime/src/test/scala/raider/runtime/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: VR-01: positive corpus cases pass the verifier (exact report trees + graph-hash pins + provenance resolution).
- Error/adversarial: VR-02: negative controls fail typed (corrupted correlation, invented edge, torn segment) — negatives stay negatives.
- Cancellation/concurrency: VR-03: verifier green ×3; concurrent verification isolated (unique temp dirs).

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Product code changes, renderer changes, live providers, modifying RAI-081 protected coding corpus.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-147: User guide + complete BillingCi example with actual generated lineage (unified)

Status: ready. Milestone: LIN. Dependencies: RAI-146.

**Scenario and contract.** Documentation card: update user-guide-ci.md and the BillingCi example for the UNIFIED lineage — the guide shows the ACTUAL generated lineage artifacts (lineage.scala + lineage-data.json from a real recorded/scripted run) and the actual unified projection for the pipeline shape; every DSL syntax claim matches the shipped API (andThen/all/batch; NO pipeline/parallel/batch unless a reviewed contract added it — cite the exact card if so); honest limitations (live-wire gated, provenance of unlinked roots separate); lineage.md §cross-run section updated to the RAI-143 contract. Examples must compile (compile-fixtures gate) and quoted lineage outputs must be real (copied from executed runs, path-cited).

**Context.** Owner directive 2026-10-13 (P2: documentation + complete CI example); the billing example currently documents six independent roots honestly; the unified shape arrives with RAI-143..146.

**Allowed paths.** `docs/user-guide-ci.md` `docs/examples/ci-pipeline/**` `examples/user-guide/ci/**` `docs/lineage.md` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: DOC-14-01: every command/syntax claim verified against the shipped API; example compiles (compile-fixtures).
- Error/adversarial: DOC-14-02: quoted lineage outputs are real artifacts of executed runs (record cites the producing run/manifest); no illustrative-invented lineage.
- Cancellation/concurrency: DOC-14-03: docs profile green; links/anchors valid; English policy.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Product code, protected fixtures, live-wire claims, unrelated owner-gated items.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-148: RecoveryClass taxonomy fix: fs_patch/proc_run are Mutating; single taxonomy source

Status: ready. Milestone: LIN. Dependencies: LIN-06.

**Scenario and contract.** Owner-flagged defect (2026-10-13): the static taxonomy (LineageReducer private sets, duplicated in IsolatedRerun) misses `fs_patch` (a real mutating patch tool — captured repairs labeled ReadOnly despite modifying source) and `proc_run` (a real command-execution tool — labeled ReadOnly). Fix: (1) add fs_patch to WriteTools and proc_run to ExecTools in the CANONICAL source; (2) deduplicate: IsolatedRerun consumes the canonical taxonomy (one public source, e.g. RecoveryTaxonomy in runtime.lineage) instead of a private copy — the duplication was flagged by two reviews as a silent-drift hazard; (3) recorded fixtures containing fs_patch/proc_run tool calls re-verify (isolated-rerun guard now refuses mutating-without-isolation for these); (4) ADR-027 §6.2 doc updated. This is classification correctness, not a relabel.

**Context.** Owner directive 2026-10-13: 'the captured repair currently labels fs_patch as ReadOnly despite modifying source... do not merely relabel the documentation or assume proc_run is harmless.' Reviews W22/LIN-15 flagged the duplicated taxonomy as a drift hazard.

**Allowed paths.** `modules/runtime/src/main/scala/raider/runtime/lineage/**` `modules/runtime/src/test/scala/raider/runtime/**` `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: TAX-01: fs_patch and proc_run classify Mutating (unit pins); the canonical source is the ONLY taxonomy (IsolatedRerun imports it — no second set literal).
- Error/adversarial: TAX-02: a captured fs_patch repair fixture re-runs the isolation guard: mutating-without-isolation now refused typed (reproducer from the BillingCi captured run shape).
- Cancellation/concurrency: TAX-03: unknown tools stay ReadOnly-by-default (ADR discipline unchanged); existing lineage suites green; GraphHash of OLD fixtures unchanged (classification is metadata on ToolCall — verify the hash policy: if the recovery class enters the hash, document the versioned policy).

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Config-driven taxonomy extension (reserved for the schema card), renaming frozen enum cases, wire changes, relabeling documentation only.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-150: LIN-02.b: populate SessionGraphView inputs/outcome for product export

Status: ready. Milestone: LIN. Dependencies: RAI-141.

**Scenario and contract.** RAI-141 flagged: the product CLI view adapter (SessionGraphView) renders empty `inputs` and no failed markers until this one-file card — the compile-gate test adapter already pins the populated contract. Populate NodeView.inputs from SessionGraph dataflow edges and NodeView.outcome from the recorded ToolCall outcome class; keep the view pure (no IO); deterministic ordering; SessionGraphViewSpec updated to pin populated fields for a representative graph (model/tool sequencing, a failed tool, cross-node inputs).

**Context.** RAI-141 record limitation 1: product CLI export currently renders empty inputs/no failed markers; the gate adapter pins the populated contract.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/SessionGraphView.scala` `modules/cli/src/test/scala/raider/cli/lineage/SessionGraphViewSpec.scala` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: SGB-01: the adapter populates inputs/outcome for a representative graph; the rendered projection threads real dependency values (compile gate green on the populated view).
- Error/adversarial: SGB-02: empty/absent edges degrade honestly (empty inputs vector, outcome None) — no invented values.
- Cancellation/concurrency: SGB-03: adapter stays pure/deterministic; existing view+gate suites green ×3.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Renderer changes (RAI-141 accepted), wire changes, product CLI command surface, graph model changes.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-151: Projection literal escaping: \uXXXX for C0 controls inside generated string literals + hostile-literal compile negative

Status: ready. Milestone: LIN. Dependencies: RAI-141.

**Scenario and contract.** RAI-141 review minor finding: the escape discipline renders C0 controls (other than \n/\r/\t) and DEL as \xHH inside generated STRING literals — pinned dotc 3.9.0 rejects \xHH escapes in string literals (reproduced: model name with U+0001 → compile failure). Pre-RAI-141 the payload landed in a // comment (lexically inert) so it compiled; the W9F4-01 fixture pins this non-compiling shape but never compile-checks it. Fix: emit \uXXXX for control characters inside string literals (\xHH stays only where lexically inert, e.g. comments); add a hostile-literal compile NEGATIVE-to-POSITIVE: a graph with a U+0001 model name must render AND compile (pinned dotc) AND execute; update the W9F4-01 pin to the compiling shape.

**Context.** RAI-141 review finding 1 (minor, non-blocking): fails closed today, but the spec enshrines the non-compiling shape as expected; goldens are clean so nothing blocks integration.

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/lineage/proj/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: ESC-01: a hostile-payload graph (C0 control in model/tool name) renders and COMPILES under pinned dotc; execution deterministic.
- Error/adversarial: ESC-02: comment-only discipline unchanged where lexically inert; LIN2-C-01/02/03 stay green.
- Cancellation/concurrency: ESC-03: compile gate runs the hostile fixture as part of the suite; ×3 green.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** GraphHash/normalization changes, comparator changes, wire changes, data-resource format changes.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-153: Live phase emission: wire v2 phase payloads from the loop (replaces corpus stand-ins)

Status: ready. Milestone: LIN. Dependencies: RAI-143, RAI-144.

**Scenario and contract.** The ADR-032 v2 wire carries phase payloads, but no live emitter exists — RAI-145's fixture inserts campaign stage phases as stand-ins (disclosed). Card: add a phase-emission surface to the loop (LoopEvents phase hooks or the ADR-024 seam per the ADR-032 contract), emit PhaseStarted/phase payloads with recovery metadata at real phase boundaries (plan phases per RAI-107 semantics; pipeline stages where the runtime knows them), wire through HeadlessRunner's export (RAI-144), and update the RAI-146 corpus/verifier ONLY where stand-in positions are replaced by live emission (corpus review lineage). Generator needs zero changes (RAI-145 pinned emission-agnostic).

**Context.** RAI-145/147 limitations: phase stand-ins disclosed; the wiring card candidate was registered promptly per the RAI-147 review risk 2 so 'shipped' becomes 'producible'.

**Allowed paths.** `docs/adr/ADR-024-events.md` `modules/runtime/src/main/scala/raider/runtime/events/**` `modules/runtime/src/main/scala/raider/runtime/loop/**` `modules/runtime/src/test/scala/raider/runtime/**` `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/test/scala/raider/cli/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: PHE-01: a scripted run with known phase boundaries emits v2 phase payloads; the unified projection executes the same report tree WITHOUT generator changes (RAI-145 fixture flipped from stand-ins to live emission, green).
- Error/adversarial: PHE-02: v1 consumers unchanged (frozen seam discipline; v1-only readers see no new cases).
- Cancellation/concurrency: PHE-03: corpus/verifier updates route through the RAI-146 review lineage; x3 deterministic.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Second scheduler, inventing phase semantics the runtime does not know, changing frozen v1 payloads, renderer changes.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-154: Headless coder-agent delegation wiring + per-level fork anchoring + unified lineage-data writer

Status: ready. Milestone: LIN. Dependencies: RAI-144.

**Scenario and contract.** Three carried gaps from RAI-144/145/146/147 reviews, one card: (1) headless coder-agent delegation wiring — the export path is proven via DelegationToolset fixtures but `raider run --agent coder` cannot delegate yet (agent-policy surface); wire delegation tools per the agent policy with tests; (2) per-level fork anchoring — MultiJobExport anchors fork records in the campaign (root) stream; nested delegation chains need per-level anchoring before grandchild edges are correct (typed reproducer first); (3) unified lineage-data writer — RunLineageArtifacts refuses phase-bearing graphs typed; extend to write the unified lineage-data (v2) beside the run when phases/correlation exist, keeping v1 output for delegation-free runs byte-identical.

**Context.** RAI-144 limitation (headless delegation not wired), RAI-144 review risk 1 (root-anchored fork records), RAI-147 limitation (RunLineageArtifacts refuses phase-bearing graphs). Registered promptly per the RAI-147 review risk 2.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/test/scala/raider/cli/**` `modules/cli/src/main/scala/raider/cli/lineage/**` `modules/cli/src/test/scala/raider/cli/lineage/**` `modules/dsl/src/main/scala/raider/dsl/lineage/**` `modules/dsl/src/test/scala/raider/dsl/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: WIR-01: `raider run --agent coder` with a delegation-capable scripted policy exports the unified tree end-to-end (RAI-144 MJE-01 semantics through the product entry).
- Error/adversarial: WIR-02: nested delegation chain — per-level anchoring reproducer (root-to-grandchild wrong-edge reproducer typed/red first), then correct per-level edges pinned.
- Cancellation/concurrency: WIR-03: delegation-free runs — lineage-data.json byte-identical to v1 output (regression pin); phase-bearing runs write v2 with provenance; refusals stay typed.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Second scheduler/runtime, live providers, plan-mode semantics changes, renderer changes.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-155: Chat to editable Scala workflow to local and generic CI

Status: todo. Milestone: LIN. Dependencies: RAI-155.a, RAI-155.b, RAI-155.c, RAI-155.d, RAI-155.e, RAI-155.f.

**Scenario and contract.** Priority integration umbrella. Deliver one supported product path: a user completes a multi-turn coding conversation in raider chat, selects the relevant turns and task intent, explicitly exports a proposed Scala workflow, edits that normal source file, compiles/packages it, and runs the edited definition on a fresh workspace through the existing local/headless runtime. The output must launch real agent work and trusted checks; returning LineageStep/StepReport history, a fixed answer, a historical patch, or a TaskSummary-only demonstration does not satisfy this card. All six child packets are required for acceptance; the parent owns integration only. Preserve current P-LIN/TUI lanes and accepted contracts.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `docs/work/records/RAI-155.md`

**Acceptance.**

- Normal: CWF-01: from a real multi-turn chat entry exercised through a local Scala provider stand, export source; edit a meaningful prompt/check parameter; compile the edited file with pinned Scala. Execute a two-input by two-launcher matrix: each input runs locally and in non-TTY generic CI on its own fresh fixture copy (four edited-workflow runs), with pairwise equivalent normalized results for the same input. All runs perform actual model/tool rounds and independent failing-before/passing-after checks. Different inputs alter the expected behavior. An additional fresh unedited-workflow control on the first input demonstrates the manual edit changes observable behavior; no manually authored replacement workflow or canned success is accepted.
- Error/adversarial: CWF-02: unsupported or incomplete selected turns, invalid edits/inputs, stale approval/context, failed checks, and export/artifact failures produce actionable failure, never READY. Construction/export/compile of generated output launches no agent/tool work. Any synthesis model request is separately explicit and configured, with deterministic no-provider generation available for the supported recorded task specification.
- Cancellation/concurrency: CWF-03: append/export races use a consistent turn boundary; interruption preserves partial/unknown status without redispatching mutations. Non-TTY SIGTERM owns and terminates launched work within a bound and produces a truthful nonzero/incomplete outcome. Final docs and all acceptance run on the same integrated candidate.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** Arbitrary-session conversion, automatic replay of observed tool arguments, durable continuation of arbitrary Scala effects, changing ADR-031 projection semantics, autonomous publishing/deploy/PR/merge, new scheduler/runtime, new forge adapter, general-purpose code-generation platform.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-155.a: Contract for explicit chat capture and executable workflow export

Status: todo. Milestone: LIN. Dependencies: RAI-116, RAI-140.

**Scenario and contract.** Freeze a separate reviewed ADR/contract after the existing RAI-116 authoring/launch boundary. Define selection of session/turns, completed/incomplete boundaries, supported task specification, proposed export command and output layout, human-editable Scala plus versioned metadata, input schema, redaction/environment references, export versus compile versus execution, and compatibility with BundleWorkflow v1 Program[String,String]. Reserve command names only after source audit; no current :export-workflow or raider compile support is assumed. Decide binding of workflow input/provider/workspace/limits to the existing runtime. First slice supports sequential coding intent, explicit prompt refinements, named trusted check commands, and reusable task/workspace inputs. Define refusal for unrepresentable history and uncertainty; do not infer concurrency, skip missing context, or silently compile/run generated code. Coordinate with RAI-117 for future checkpoints/external effects without expanding this slice to them. Prepare a remaining-work estimate and proposed hour allocation/tradeoff in the ADR; the coordinator records the allocation in the roadmap before implementation.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `docs/adr/**` `docs/runtime-contracts.md` `docs/ci-runtime.md` `docs/api.md` `contracts/chat-workflow/**`

**Acceptance.**

- Normal: CWF-A01: reviewed fixtures describe a multi-turn repair/export/edit/compile/local/CI lifecycle, input and artifact schemas, generated program shape, and exact implementation/verification responsibilities. Parent acceptance is traceable to every contract clause; launch uses the one existing runtime and unchanged bundle ABI where feasible.
- Error/adversarial: CWF-A02: contract specifies typed refusal for unsupported selections, missing context, secret-bearing exports, path escape/symlink/existing target, ambiguous effects and invalid source; export text is data, never an instruction to invoke a shell. Any required public ABI change is separately versioned and reviewed before implementation.
- Cancellation/concurrency: CWF-A03: consistent export snapshot, saved/reopened sessions, pending/failed turns, cancellation ownership, policy checks on a new CI invocation, and non-TTY approval refusal are specified. Existing projection remains zero-dispatch and unaffected.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** Implementing runtime behavior, protected verifier weakening, forcing all chat records through unified lineage, silently redefining bundle v1 or SDLC effects.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-155.b: Persist exportable chat turns and explicit task selection

Status: todo. Milestone: LIN. Dependencies: RAI-155.a.

**Scenario and contract.** Implement the reviewed chat capture and selection contract through the actual chat entry. Persist turn/job identity, user intent/refinements, relevant workspace/provider configuration references, tool outcomes and pending/completed/interrupted boundaries required to explain the proposed workflow. Keep sensitive values out of export/logs and use environment/helper names. Preserve usable source turns across history compaction and save/reopen; legacy conversation-only saves remain readable but cannot invent absent execution records. Reopening does not restore authority or silently change active backend/workspace; validate or show explicit mismatch. Export takes a bounded immutable selection and performs no model/tool execution.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/chat/**` `modules/cli/src/main/scala/raider/cli/Main.scala` `modules/cli/src/test/scala/raider/cli/chat/**` `modules/cli/src/test/scala/raider/cli/**`

**Acceptance.**

- Normal: CWF-B01: at least three chat turns with one code edit and check round survive save/reopen and compaction with stable selection/ordering. A selected task specification reaches the exporter without omitted constraints; legacy sessions have documented limitations and current launch configuration is explicit.
- Error/adversarial: CWF-B02: missing/corrupt history, unsupported selection, configuration mismatch, or secret-bearing content gives a typed diagnostic. Interrupted turns keep the pending user request and partial/unknown effect status instead of disappearing from the saved state.
- Cancellation/concurrency: CWF-B03: concurrent append/export sees a complete selection boundary; interrupted write never exposes a successful partial export; restart does not redispatch a started mutation. Streaming/status rendering and existing chat behavior remain compatible.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** Durable fiber resume, automatic retry of ambiguous mutations, TUI prompt submission, changes to provider wire protocols or ADR-031 projection.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-155.c: Generate editable executable Scala from the selected chat task

Status: todo. Milestone: LIN. Dependencies: RAI-155.a, RAI-155.b.

**Scenario and contract.** Generate normal editable Scala using the accepted runtime binding, Program/Task and existing composition verbs. Target an executable BundleWorkflow v1 definition with validated JSON decoded inside the String input boundary, explicit task/workspace/checks/limits, output validation, and bounded agent work. The source must request new agent work on new input, not embed recorded final answers or blindly repeat tool calls. Export source, manifest/input example and non-secret origin metadata; preserve source/turn references separately from subsequent execution lineage. Known workflow structure renders deterministically from the supported task specification. If optional LLM synthesis is needed, make it an explicit configured operation and validate its proposal; do not execute arbitrary chat text. Reject unsupported constructs rather than emitting misleading runnable-looking stubs.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `modules/dsl/src/main/scala/raider/dsl/workflow/**` `modules/dsl/src/test/scala/raider/dsl/workflow/**` `modules/cli/src/main/scala/raider/cli/chat/**` `modules/cli/src/main/scala/raider/cli/workflow/**` `modules/cli/src/test/scala/raider/cli/**` `examples/chat-workflow/**`

**Acceptance.**

- Normal: CWF-C01: selected chat intent produces Scala source/manifest/input data that compiles with the pinned compiler, includes actual runtime agent stages and deterministic checks, and accepts a second task/workspace input. Editing a prompt/check parameter survives compilation and changes the later result. Identical supported specification renders stable output.
- Error/adversarial: CWF-C02: hostile Scala literals, path/symlink escape, existing export target, missing context, unsupported control flow, credential/private-path leakage, and invalid edited Scala fail visibly with useful diagnostics. No provider/tool dispatch occurs from generating or compiling supported output; skeletons with TODOs or fixed outcomes do not pass.
- Cancellation/concurrency: CWF-C03: concurrent exports do not overwrite one another; interruption leaves incomplete output identifiable and never published as a complete workflow. Export/compile cannot redispatch any tool from the source chat.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** General synthesis of arbitrary sessions, inventing parallelism from timestamps, exporting secrets, replacing projection or replay, new DSL macros/runtime, making unrestricted Scala safe to execute.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-155.d: Compile edited chat workflows and launch them locally and in generic CI

Status: todo. Milestone: LIN. Dependencies: RAI-155.c.

**Scenario and contract.** Provide the reviewed explicit build/package path for user-edited source and execute the same resulting workflow locally and through existing headless bundle launch. Reuse one runner/loop, policy/budget and cancellation ownership. Record the actual source/input/build identity so a manual edit invalidates earlier result claims. Generated code is trusted Scala; initial construction/loading of generated supported output must not start agent/tool work. Execution resolves current credentials and policy rather than inheriting chat authority. No published SDK or selected forge is required. CLI syntax must reflect implementation; currently no --ci or compile command may be assumed. Restrict the first end-to-end path to sequential chat tasks; unified phases/delegation additionally require RAI-153/154.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/workflow/**` `modules/cli/src/main/scala/raider/cli/run/**` `modules/cli/src/main/scala/raider/cli/Main.scala` `modules/cli/src/test/scala/raider/cli/**` `modules/dsl/src/main/scala/raider/dsl/workflow/**` `modules/dsl/src/test/scala/raider/dsl/workflow/**` `examples/chat-workflow/**` `scripts/build/chat-workflow.sh`

**Acceptance.**

- Normal: CWF-D01: fresh checkout compiles and packages the edited generated class without an example preloaded on the runner classpath; local launch and non-TTY CLI bundle launch execute identical workflow logic on separate fresh workspaces with equivalent normalized outputs for the same input (the two-input matrix is specified in CWF-01), real tool effects, and independent check results. Provider, workspace and input are explicit.
- Error/adversarial: CWF-D02: invalid Scala/manifest/input, stale build/source identity, unmet policy/approval, failed trusted checks, missing credentials and unwritable output fail usefully with nonzero status. READY requires actual checks on the new workspace. Existing v1 bundles still load and run with their established behavior.
- Cancellation/concurrency: CWF-D03: stdin closed and SIGTERM during active model/process work yield bounded owned-work cleanup and truthful incomplete/error artifacts; no automatic mutation retry. Separate launches share no accidental state or hidden admission authority.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** A second scheduler, forging cross-run graph edges, durable resume, automatic commit/PR/merge/release/deploy, compulsory hosted CI integration, arbitrary untrusted Scala sandboxing.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-155.e: Independent end-to-end verifier for chat to edited Scala to CI

Status: todo. Milestone: LIN. Dependencies: RAI-155.b, RAI-155.c, RAI-155.d.

**Scenario and contract.** Independent reviewer-owned acceptance packet for CWF-01..03 and all child cases. Freeze Scala fixtures and a local OpenAI-compatible Scala stand that drives actual multi-turn chat, export, manual source edit, pinned compile/package, local execution and non-TTY generic CI. Include a second fresh task/repo input and assert new actual model/tool calls, changed source, independent checker results and matching artifact/source identities. A fixed successful report, lineage-only program, prewritten replacement workflow, or re-execution of the original chat is a negative case. Review protected fixtures separately from implementation; no product implementation edits are allowed in this verifier card.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `contracts/chat-workflow/**` `modules/cli/src/test/scala/raider/cli/**` `modules/dsl/src/test/scala/raider/dsl/workflow/**` `scripts/quality/chat_workflow.py`

**Acceptance.**

- Normal: CWF-E01: full product-entry chain passes the CWF-01 two-input by two-launcher matrix on four fresh copies, with pairwise equivalent local/CI outcomes per input. A fifth fresh unedited-workflow control for the first input isolates the observable effect of the manual edit. No-provider capture/export/build probes prove no implicit agent work. Existing runtime acceptance is preserved and integrated candidate artifacts identify the checked source.
- Error/adversarial: CWF-E02: negative corpus detects bogus success, unsupported/lost chat context, invalid source/inputs, prompt injection into emitted code, secret leakage, changed verifier/test, path escape, stale build and failed checks. Every missing/timeout/skipped case is non-pass; no fixture hand-authoring substitutes for export.
- Cancellation/concurrency: CWF-E03: interrupted stream/tool/export/build, consistent append/export snapshot, fresh-process launch and SIGTERM cases have bounded observable outcomes; replay/resume of ambiguous mutations is forbidden. Repeat deterministic cases sufficiently to expose concurrency failures; preserve any red trail.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** Changing product implementation or relaxing protected assertions, claiming live model quality from a scripted stand, hosted CI/forge support, changing global gate policy without a separate reviewed card.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-155.f: User walkthrough: chat, export Scala, edit, and run in CI

Status: todo. Milestone: LIN. Dependencies: RAI-155.e.

**Scenario and contract.** Document the accepted user path in English with a real small conversation, exact shipped export command, complete exported Scala source, a meaningful user edit/diff, exact compile/package/local/CI commands and actual result/artifact locations. Make the edited generated workflow the main example; distinguish authoring source from dry-run lineage and later run history in plain language. Keep acceptance/internal planning detail out of the user tutorial. Preserve old captures and link the new source/result to the candidate that produced them.

**Context.** Owner request 2026-10-14: work with an agent in chat, explicitly export an editable Scala workflow, edit it, then run the same definition locally and in generic CI. Roadmap section 6d; this is workflow authoring, not ADR-031 dry-run projection or replay.

**Allowed paths.** `README.md` `docs/onboarding.md` `docs/user-guide.md` `docs/user-guide-repl.md` `docs/user-guide-ci.md` `docs/user-guide-lineage.md` `docs/api.md` `docs/examples/chat-workflow/**` `examples/chat-workflow/**`

**Acceptance.**

- Normal: CWF-F01: a new user can follow chat -> export -> edit -> compile -> local -> generic CI from a clean checkout using the same example source; all commands exist, all links resolve, and the complete generated and edited Scala are provided with actual output.
- Error/adversarial: CWF-F02: document supported chat selection and incomplete/unsupported export errors, invalid edits, input/provider failures and failed checks with actionable next steps; no aspirational command is presented as shipped.
- Cancellation/concurrency: CWF-F03: explain interrupted/unknown state and explicit new launch, bounded concurrent work if supported, and policy/credential setup without secrets; docs match the integrated verified behavior.

**Required gates.** For implementation, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` and the applicable profiles in metadata on the integrated candidate. The current task only registers this plan; it does not execute runtime gates. Missing/skipped checks never pass.

**Non-goals.** Implementing adjacent features, replacing user instructions with acceptance narration, inventing successful live runs, silently updating historical captures.

**Compatibility/rollback.** Preserve frozen bundle/projection contracts; public changes require the reviewed contract card first. Retain old source/captures; reverting code does not undo external effects.

**Deliverables.** Commit only the assigned paths, provide actual case results and source identity, independent review, and a work record. Register any further decomposition before implementation; the parent is accepted only after all children and the complete user flow pass.

## RAI-156: ReplayHarness.eventuallyTerminal: drain to hub watermark before Terminal-truncation (product-side relay-race fix)

Status: ready. Milestone: LIN. Dependencies: RAI-152.

**Scenario and contract.** RAI-152 mechanism analysis (review-approved): the replay child's event capture terminates on Terminal PRESENCE in the sink, but the hub relays reliable vs diagnostics envelopes via two independent forwarder fibers with unspecified relative order (hub.scala:235-245, :50-53). Under CPU starvation the diagnostics-class tail (seq < Terminal) is still in flight -> sink misses seqs -> typed SeqGap -> exit 20. Discriminator: a diagnostics drop consumes no sequence, so gap + droppedDiagnostics==0 is the relay race by construction (typed reproducer exists: dropped=0 envelopes=9 Left(SeqGap(j_1,8,10)), missing seqs exactly the diagnostics tail). Fix the PRODUCT: drain to the hub watermark (committed-vs-consumed) before deciding truncation — diagnostics quiescence — not Terminal presence. Test-side retry wrappers (RAI-152) stay as defense; the product raider replay becomes load-robust itself.

**Context.** RAI-152 review (approve) required coordinator registration: the product race family is open in `raider replay` under load until this lands; RAI-152's test-side masks are instrumented at three call sites only.

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala` `modules/cli/src/test/scala/raider/cli/lineage/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: RT-01: the typed reproducer (dropped=0 + diagnostics-tail gap) is green WITHOUT the test retry wrapper under a documented load storm (the product no longer truncates mid-stream).
- Error/adversarial: RT-02: truncation semantics unchanged for genuinely torn streams (real torn tail still typed TruncatedTail); existing replay suites green x3.
- Cancellation/concurrency: RT-03: ReplayProductCliSpec/ReplayChainE2E retry wrappers may remain but their retry counters stay zero in the storm run (product fixed, not masked); full sbt test x1 green.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Hub forwarding-order semantics changes (the unspecified-order contract stays; we drain, not reorder), event wire changes, renderer/comparator changes, masking via test retries only.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.

## RAI-157: MultiJobExport settle window: captured-but-unconnected introductions must not vanish silently

Status: ready. Milestone: LIN. Dependencies: RAI-144, RAI-156.

**Scenario and contract.** RAI-146/156 adjudication finding (small, real): a mid-campaign introduction whose capture fiber has not delivered by drain(2000ms)+close is dropped with NO wire trace — zero observability of an incomplete child in a live run. The RAI-152 pattern applied to the exporter: the runner's settle must wait for captured-but-unconnected introductions (bounded: introduction queue/watermark), so every correlated child is either JOINED (complete), PARTIAL (LIN5-03 discipline), or NATIVE-ONLY-with-fork-record — never absent. Product determinism, not test retry.

**Context.** RAI-146/156 adjudication (2026-10-14): 'in a live run, a mid-campaign introduction whose capture fiber has not delivered by drain+close is dropped with no wire trace — register a settle-window relay-race card (the RAI-156 pattern).'

**Allowed paths.** `modules/cli/src/main/scala/raider/cli/run/MultiJobExport.scala` `modules/cli/src/test/scala/raider/cli/run/**` Tests live inside the same modules/contract areas. Narrow concrete files before assignment; extending the path boundary requires a separate scope card.

**Acceptance.**

- Normal: SW-01: typed reproducer first — an introduction delivered after the current drain window under a documented load storm is absent from the wire (red); after the settle-window fix the child is observable in one of the three classes (joined/partial/native-only) with its fork record (green).
- Error/adversarial: SW-02: MJE-02/03b/03c get the same pollUntil determinism pins (the adjudicator's optional hardening); delegation-free exports byte-identical.
- Cancellation/concurrency: SW-03: bounded wait (no unbounded hang); drain budget documented; x3 deterministic; full sbt test x1 green.

**Required gates.** After foundation: make quality-changed; make quality-changed QUALITY_EXECUTE=1; applicable profiles per metadata; evidence verification with each required profile. full/release aggregates allowed only if they actually include the whole list. External prerequisites explicit; unavailable is not passed.

**Non-goals.** Hub forwarding-order semantics changes, unbounded waits, wire format changes, masking via test retries.

**Compatibility/rollback.** Frozen schemas/APIs and downstream fixtures are not changed silently; versioned migration/ADR separately. Rolling back a feature does not replay side effects; persistent incomplete/uncertain artifacts are kept for reconciliation.

**Deliverables.** Minimal diff in allowed paths, the listed executable fixtures with actual IDs, an updated relevant contract/example, an actual work record per template with current evidence/log hashes. Real paid calls/publishing are not implied. If the task is too large, split into independent child cards before coding; the parent accepts their joint contract gate.
