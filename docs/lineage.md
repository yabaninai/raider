<!-- LIN-08A: user documentation + operational spec for session lineage.
     Contract: ADR-027 (docs/adr/ADR-027-session-lineage.md) and the
     P-LIN cards (docs/tasks/lineage.md). Introduces no new product decisions.
     Commands/paths checked against modules/cli (CliArgs.scala, HeadlessRunner.scala,
     EventExport.scala, ReplayCli.scala); events are from raider.runtime.events
     (ADR-024 §5). -->

# Session lineage: the call tree as a valid Scala program

User documentation and operational spec for the session lineage layer (ADR-027,
phase P-LIN). Every session—a chat, a headless run, or a nightly campaign—gets a
**SessionGraph**: a call tree materialized as a **valid Scala program**
(`lineage.scala`) plus a data resource (`lineage-data.json`). Running this program
**reproduces the session's chain** of calls.

For implementation status, see [§9](#9-implementation-status-reported-accurately).
This document describes the decisions recorded in ADR-027, rather than a finished product.

## 1. Three artifacts and the source of truth

| Artifact | What it is | Role |
| --- | --- | --- |
| `events.jsonl` | Session event log (headless runner export, schemaVersion 1) | **The sole source of truth** |
| SessionGraph | Event-sourced session graph: the `events.jsonl → graph` reducer | Model (in memory, rebuilt from events) |
| `lineage.scala` + `lineage-data.json` | Graph projection into a valid Scala program + a data resource | **Deterministic projection** (similar to Spark's `explain()`) |

Key properties:

- **Events are the source of truth; the program is a projection.** The graph
  comparator detects manual edits to `lineage.scala` (projecting the events again
  will no longer produce a match).
- **No data is embedded in the code.** All node data—LLM round prompts and tool
  call arguments—is stored in `lineage-data.json`. `lineage.scala` contains only
  Program/Task composition (RAI-017, frozen: it is the projection target, not the model).
- **The program is valid by construction and checked by compilation** periodically
  during the session and in CI (the LIN gate). An invalid projection has an explicit
  status (visible in the TUI until fixed), rather than being silently skipped.

## 2. Layers (ADR-027 §3)

| Layer | Responsibility | Card |
| --- | --- | --- |
| 1. SessionGraph (model) | The `events.jsonl → graph` reducer. Nodes: `ModelCall \| ToolCall(kind, RecoveryClass) \| Delegation(subtree) \| Phase(name)`; edges represent dataflow (`inputs: Vector[NodeId]`) + control flow. New tool call types are registered as ToolCall-node subtypes without changing the contract | LIN-01 |
| 2. Projection | `graph → valid Scala`: nodes are Program/Task compositions, delegations are nested blocks, phases are `def`s; data goes into `lineage-data.json` | LIN-02 |
| 3. Live maintenance | Incrementally builds the graph and projection during the session; periodic compilation and validation; the artifact is always accessible from the TUI's Lineage tab | LIN-03 |
| 4. Reproduction | replay-recorded / hybrid / live re-run; the graph comparator determines acceptance | LIN-05–07 |

Compaction of long sessions: nodes are folded into named phases (`Phase(name)`),
which also serve as compaction boundaries and units of partial replay.

## 3. Artifact locations

The run directory is set by the `--out` flag. Actual headless runner behavior
(`raider run ... --out <dir>`, checked against `modules/cli/run/HeadlessRunner.scala`):

```text
<out>/work-<uuid>/          # run directory; parallel runs do not share a directory
├── run.json                # run result (status, accounting, ci)
├── events.jsonl            # session events—the source of truth (schemaVersion 1)
├── summary.md              # human-readable summary
└── transcript.json         # run trace
```

Lineage artifacts sit **alongside the run artifacts**, in the same run directory:

```text
<out>/work-<uuid>/lineage.scala        # graph projection (valid Scala)
<out>/work-<uuid>/lineage-data.json    # data resource (prompts, node data)
```

ADR-027 expresses this as `artifacts/<run>/lineage.scala` +
`artifacts/<run>/lineage-data.json`. A typical invocation:

```sh
raider run --agent coder --input "..." --out artifacts/<run>   # + --mock/--provider, etc.; see §6
```

- `lineage.scala` and `lineage-data.json` **are included in the run's evidence
  fingerprint**, alongside `events.jsonl` (ADR-027 §2, the “Storage” row).
- `events.jsonl` is the canonical export of per-job `EventEnvelope`s (actual
  `raider.runtime.events` events, sorted by per-job seq; duplicates are discarded
  and the coalescing marker is retained accurately).

## 4. Reproduction modes

| Mode | Status | What happens | Success criterion |
| --- | --- | --- | --- |
| **replay-recorded** | **Default** mode: free, deterministic | The program runs on recorded data; it makes no calls | Identical call graph (normalized graph-hash) |
| **hybrid** | Primary mode (similar to Spark lineage) | LLM nodes come from the recording; **deterministic tool calls are recomputed** | Identical tree structure (nodes/edges/types) |
| **live re-run** | Opt-in | Real calls; a budget is mandatory | **A live graph** + a report of differences |

Success semantics follow the owner's decision (ADR-027 §2): successful reproduction
means **the same call graph** (tree structure: nodes/edges/types). LLM text may differ
in live mode; structure is compared, not text.

- **Live re-run (opt-in) requires isolation and a budget:** mutating nodes execute
  **in an isolated workspace copy** (RecoveryClass determines policy); execution
  is refused before it starts if a mutating node is not isolated; execution stops
  when the budget is exhausted (LIN6-02).
- **Successful live re-run produces a live graph:** divergence from the recording
  is captured in a “live vs recorded” diff report; this is a result, not a failure.
- **Replay verification uses the opposite rule:** graph divergence means the check
  **fails**, reporting the first divergent node (LIN5-02). Live re-run is not used
  as “successful replay verification.”
- **The graph comparator determines acceptance** for replay modes (LIN-05). It also
  detects manual edits to the projection.

### 4.1 Partial replay

Replay of a **named phase** or **subtree** is required (LIN-07):

- replay is limited to the phase subtree and its explicit input contract;
- external dependencies cause a contract error, rather than an empty substitution;
- disjoint phases replay in isolation from each other;
- concurrent phase replays are isolated.

## 5. Scope and the tree catalog

Lineage is built for all session scopes (ADR-027 §2, “Scope”):

1. **Chat sessions**: the call tree of an interactive session.
2. **Headless runs**: one run = one tree (currently, `raider run --out ...` writes
   the run's `events.jsonl`; lineage artifacts arrive with P-LIN).
3. **Nightly campaigns**: a **tree catalog** containing the campaign's runs, with
   a graph-hash and reproducibility status for each (LIN8-01).

Catalog requirements:

- catalog entries have the **same** format for chat and headless (chat==headless);
- every entry carries a **graph-hash** and **reproducibility status** (successfully
  replayed / divergent / not checked);
- parallel runs add entries through **atomic append** (LIN8-03).

### 5.1 Multi-job graph (reported accurately)

A run's `events.jsonl` contains events from **one job**. Events from delegated
children are assembled into a combined graph **by the reducer, using rootId/jobId
from the journal layer**. Since RAI-144 the unified multi-job export is
shipped for delegate children of one run — see
[§5.2](#52-unified-multi-job-lineage-wire-v2-adr-032). Until a run actually
delegates, the catalog operates on per-job/per-run trees assembled by the
reducer.

### 5.2 Unified multi-job lineage (wire v2, ADR-032)

The lineage wire gains **schemaVersion 2** (ADR-032, card RAI-143; the frozen
ADR-024 runtime→UI seam is NOT modified):

- **Phase payloads.** `phase_started` (enter, with recovery metadata) and
  `phase_finished` (exit) create/close `SessionNode.Phase` in the graph
  (LIFO per job; a phase still open at `Terminal` stands).
- **Multi-job correlation.** Optional envelope field `invocationId` — the
  delegation invocation a job's envelopes belong to. Required on v2 envelopes
  of every non-root job (typed `ReduceError.MissingCorrelation`); a v1
  envelope carrying it is refused typed (`InvocationIdOnV1`). A correlation
  key alone never invents an edge: the parent control edge comes only from
  the parent's frozen `Delegated` payload.
- **Cross-run provenance.** `run_provenance` — at most one per stream —
  carries `parentRunId` (absent = root run, never guessed) and `{kind, path}`
  artifact pointers; returned as `LineageReduction(graph, provenance)` from
  the versioned entries.
- **Versioning policy.** v1 documents decode bit-for-bit unchanged; the
  frozen single-version reader/reducer keep refusing v2; mixed v1/v2 streams
  are legal input to the versioned entries (`LineageWireReader`,
  `reduceWire`/`reducePartialWire`); any other version is a typed refusal.
- **GraphHash is unchanged (ADR-032 §3).** v2 metadata (phase recovery,
  `invocationId`, provenance) is excluded from the canonical form by
  contract: v1 hashes remain valid and comparable; provenance equality is
  asserted by the RAI-146 verifier, not by GraphHash.

What ships on top (cards RAI-144/145/146, all integrated at `619fe25`):

- **Multi-job export** — `raider.cli.run.MultiJobExport`
  (`modules/cli/src/main/scala/raider/cli/run/MultiJobExport.scala`): a run's
  delegate children join the run's single `events.jsonl`; one canonical
  `Delegated` fork record in the parent's stream; per-job `seq` dense in file
  order (export-canonical); the stream's file order is the reduction order and
  `GraphHash` is invariant under the cross-job interleave; exactly one
  `run_provenance` record before the campaign `Terminal`; honesty rules for
  post-terminal forks (native-only segment) and detached children (typed
  partial under LIN5-03). Delegation-free runs stay byte-identical to the v1
  layout. The run artifact writer emits `lineage-data.json` schemaVersion 2
  (top-level `provenance`) exactly when the record is present
  (`RunLineageArtifacts`).
- **Unified projection** — `raider.dsl.lineage.proj.UnifiedProjection`
  (`modules/dsl/src/main/scala/raider/dsl/lineage/proj/UnifiedProjection.scala`):
  the unified tree projects to the ADR-031 executable dry-run description —
  phase defs per wire-native phase, delegation defs per correlation-linked
  child, recorded fan-out groups composed with the frozen
  `raider.dsl.composition.batch` verb (validated against the graph; parallelism
  is never inferred), typed segment entries (`Linked`/`Incomplete`/`Unlinked`),
  and the provenance/segments carried as data vals. A real rendered artifact
  for the billing pipeline shape is checked in at
  `docs/examples/ci-pipeline/unified-lineage/lineage.scala` with its
  provenance README.
- **Acceptance corpus + verifier** — `contracts/lineage/unified/`
  (frozen, sha256-manifested; card RAI-146) +
  `raider.cli.lineage.UnifiedCorpusVerifierSpec`
  (`modules/cli/src/test/scala/raider/cli/lineage/UnifiedCorpusVerifierSpec.scala`):
  four golden unified streams (including the BillingCi shape) and four typed
  negative controls (corrupted correlation, invented edge, byte-torn tail,
  detached prefix); the verifier re-hashes the manifest, checks exact executed
  report trees (×3), graph-hash pins, provenance resolution, pinned-dotc
  compile, and generated-source byte pins; every compile runs in a unique
  temp directory (VR-03).

Limitations today (reported accurately):

- the headless product path does not yet wire delegation into the `coder`
  agent (the export is fully wired in the runner and exercised by fixtures;
  a wiring card is the follow-up);
- the product cannot emit v2 phase envelopes yet (the frozen hub seam has no
  phase surface) — corpus phases are frozen wire payloads authored through the
  real codecs, and the RAI-145 machinery fixture inserts them as a documented
  stand-in; real phase emission is a wiring card candidate;
- `raider replay` reads the frozen v1 format (LIN-10/RAI-109); a mixed v1/v2
  export replayed through it is refused typed
  (`SchemaVersionUnsupported`) — unified-graph replay is not a shipped
  command surface;
- `RunLineageArtifacts` refuses phase-bearing graphs typed, so a unified
  `lineage.scala`/`lineage-data.json` pair is not yet written beside a run's
  `events.jsonl`; unified artifacts are produced through the
  projection/verifier path;
- fork records are root-anchored in the export; per-level anchoring is a
  precondition for nested delegation chains (the corpus's
  `case-2-delegation-tree` pins the projection semantics for deeper nesting).

## 6. Environment pinning

Reproducibility is tied to the environment. The pinned environment is recorded
in `toolchain.json` (LIN-08): toolchain (Scala/JDK), Raider version, and model.

- When reproduction starts, **pinned versions are checked BEFORE execution**.
- A mismatch causes a **warning before execution** followed by an attempt to
  reproduce (“warn and attempt,” ADR-027 §2).
- If reproduction runs in a different environment, the diff report explicitly
  marks it as **“reproduced in a different environment”** (LIN8-02). Pinning
  mismatches must not be hidden.
- The compiler checks the projection **with pinning** (LIN2-01): projecting the
  same graph again produces byte-identical output.

## 7. TUI: the Lineage tab

A single Lineage tab (LIN-04) in a minimal tab host (a limited part of P2 brought
forward by the owner's decision, ADR-027 §3.3):

- displays the **session tree**, `lineage.scala` source, and **compilation and
  validation status**;
- there is one tab; P2 drafts and full-screen modes are outside this wave;
- invalid status **remains visible until fixed**, rather than being hidden;
- accessible during a live session **without blocking input**; tab switching does
  not desynchronize state (LIN4-01/03);
- the artifact is maintained **incrementally during the live session** (ADR-027
  §2, “Generation”) and is consistent with the last completed node after a crash
  (LIN3-03);
- a compilation failure does not crash the session; its status remains visible
  (LIN3-02).

## 8. Explicit limitations

| Limitation | Meaning |
| --- | --- |
| **ClassLoader is NOT a sandbox** | A lineage program is trusted user code with process privileges (like the REPL). Tool policy is not a sandbox for arbitrary Scala. Do not run lineage of unknown origin expecting isolation |
| **Restore does not replay mutations** | Restoring a session/crash archive restores the graph and state, but **does not automatically repeat** the effects of mutating nodes (runtime-invariant 18) |
| **Live re-run does not merge into the working tree** | Live run changes remain in the isolated workspace copy. There is no automatic merge/push (runtime-contracts §15) |
| **No exactly-once guarantee** | No claim is made that effects of arbitrary Scala lambdas can be repeated after an ambiguous result; mutating calls are not retried automatically |
| **`argsSummary ≤512 redacted` is not the full data** | Wire events carry a limited summary; full node data exists only in `lineage-data.json`; dataflow is represented by separate `inputs: Vector[NodeId]` edges in the graph model |
| **Phase nodes have no wire event** | Events have no phase case (13 frozen payload cases, ADR-024); the `PhaseMarked(name)` wire extension requires a separate wave-2 schema card with independent review. For now, the graph model builds Phase nodes |
| **RecoveryClass is not on the wire** | Tool calls do not carry a recovery class; until the schema card, the lineage layer uses a static tool-name → RecoveryClass taxonomy (ReadOnly by default, Mutating for write/exec classes), extendable through configuration without changing frozen events |
| **A recording store is a prerequisite** | Durable storage for `lineage-data.json` is wave-2 slot-0 and mandatory before LIN-05 replay; otherwise, rework is required (ADR-027 §6.5) |
| **No commitments to these features** | No exactly-once, no web/cloud, no distributed replay, no auto-merge—outside scope (runtime-contracts §15, ADR-027) |

## 9. Implementation status (reported accurately)

As of the 2026-10-12 snapshot, P-LIN waves W7–W21 are integrated and committed
(“P-LIN session lineage waves W7-W21”). Evidence is in
`docs/work/records/W7-INTEGRATION.md` and
`docs/work/records/W8-W9-INTEGRATION.md` (with W10–W21 addenda). Each item below
can be checked against the indicated file. The unified multi-job lineage
additions of 2026-10-13 (wire v2, export, projection, corpus — RAI-143…146)
are recorded in [§5.2](#52-unified-multi-job-lineage-wire-v2-adr-032) and
[§9.1](#91-implemented-by-adr-027-3-layer-with-file-references).

### 9.1 Implemented (by ADR-027 §3 layer, with file references)

**Layer 1—SessionGraph, model and reducer (LIN-01):**

- `SessionGraph`: sealed `SessionNode`—`ModelCall | ToolCall(RecoveryClass) |
  Delegation | Phase`, validated/childrenOf/roots, dataflow edges
  `inputs: Vector[NodeId]`—
  `modules/runtime/src/main/scala/raider/runtime/lineage/model/SessionGraph.scala`
- `GraphHash`: SHA-256 of the canonical form (injective escaping)—
  `modules/runtime/src/main/scala/raider/runtime/lineage/model/GraphHash.scala`
- `events.jsonl` reader (`ParsedEvents`/`TruncatedTail`/`LineageError`)—
  `modules/runtime/src/main/scala/raider/runtime/lineage/io/LineageEventReader.scala`
- `LineageReducer`: 13 frozen payload cases → graph, 9 typed errors,
  `reducePartial` (crash prefix)—
  `modules/runtime/src/main/scala/raider/runtime/lineage/reduce/LineageReducer.scala`
- Deterministic campaign test corpus (seed/corruptedTail/seqGap)—
  `modules/runtime/src/test/scala/raider/runtime/lineage/corpus/LineageCorpus.scala`

**Layer 2—projection and data (LIN-02):**

- Data contract: `contracts/lineage/lineage-data.schema.json`
  (schemaVersion 1), golden fixtures `contracts/lineage/golden/{tiny-graph,deep-tree}/`,
  `contracts/lineage/README.md` (LIN-02A)
- Typed model + zio-json codecs; the canonical form is the frozen schema—
  `modules/dsl/src/main/scala/raider/dsl/lineage/data/LineageData.scala`
- Atomic tmp+move store, single writer (`Missing`/`Corrupt`/`Io`)—
  `modules/dsl/src/main/scala/raider/dsl/lineage/store/LineageDataStore.scala`
- Graph → `lineage.scala` renderer—
  `modules/dsl/src/main/scala/raider/dsl/lineage/proj/LineageProjection.scala`
- **LIN2-01 achieved**: golden projections compile with pinned dotc 3.9.0;
  repeat projection is byte-identical—
  `modules/cli/src/test/scala/raider/cli/lineage/LineageCompileGateSpec.scala`
- CLI adapter from graph to projection view—
  `modules/cli/src/main/scala/raider/cli/lineage/SessionGraphView.scala`

**Layer 3—live maintenance (LIN-03/04):**

- Incremental `LiveGraphBuilder` (stepwise equivalent of the batch reducer,
  fold==batch invariant)—
  `modules/runtime/src/main/scala/raider/runtime/lineage/live/LiveGraphBuilder.scala`
- Headless runner: streams `events.jsonl` during the run (`EventsJsonlWriter`)
  and adds a lineage line with graph-hash to `summary.md` when the run settles—
  `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`
- TUI: the `:aux` seam (`AuxTab`/`TabShell`)—`modules/ui/src/main/scala/raider/ui/`;
  the Lineage tab as a pure provider without IO (LIN-04B)—
  `modules/ui/src/main/scala/raider/ui/LineageTab.scala`

**Layer 4—reproduction (LIN-05/06/07) and catalog (LIN-08):**

- `GraphComparator` (normalized, first divergence) + `ReplayVerifier.verify` /
  `verifyPartial`—
  `modules/runtime/src/main/scala/raider/runtime/lineage/replay/{GraphComparator,ReplayVerifier}.scala`
- `RecordedSource`: DataRef access to recorded model responses—
  `modules/dsl/src/main/scala/raider/dsl/lineage/replay/RecordedSource.scala`
- `ReplayInterpreter` (replay-recorded: LLM nodes from the recording, integrity verdict)—
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayInterpreter.scala`
- `ToolReplay`: deterministic recomputation of `fs_read` against the workspace,
  typed `Unsupported`/`ArgsUnreadable`/`ReadFailed`—
  `modules/cli/src/main/scala/raider/cli/lineage/ToolReplay.scala`
- `ReplayModelBackend`: recorded responses as a `ModelBackend` (ordinal strategy,
  typed `Overrun`/`Exhausted`)—
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayModelBackend.scala`
- `ReplayHarness`: **LIN5-01 completed end to end**: replay run through the actual
  loop (budget through `Admission`, isolated fixture workspace), normalized
  `Match`/`Mismatch` with the first divergence—
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala`
- Partial replay (LIN-07): by subtree—
  `modules/cli/src/main/scala/raider/cli/lineage/SubtreeReplay.scala`,
  by named phase—
  `modules/cli/src/main/scala/raider/cli/lineage/PhaseReplay.scala`
- LIN-06 mechanics: isolated workspace copy + `verifyUntouched`—
  `modules/cli/src/main/scala/raider/cli/lineage/WorkspaceIsolation.scala`;
  “replayed vs recorded” diff report—
  `modules/cli/src/main/scala/raider/cli/lineage/RunDiffReport.scala`
- Tree catalog (scan runsRoot → verifyPartial statuses, chat==headless)—
  `modules/cli/src/main/scala/raider/cli/lineage/LineageCatalog.scala`
- Standalone replay entry point (LIN-10): CLI command over `ReplayHarness`—
  Match/Mismatch verdict + graph-hash + first divergent node, typed errors
  (`StoreError`/`IsolationError`/`RaiderError`/`ReduceError`), exit codes per
  ci-runtime §8, workspace isolation (`verifyUntouched`)—
  `modules/cli/src/main/scala/raider/cli/lineage/ReplayCli.scala` (§10)

Every layer is covered by behavioral specs alongside the code
(`modules/*/src/test/scala/**/lineage/**`).

**Unified multi-job lineage (wire v2) — RAI-143/144/145/146** (see
[§5.2](#52-unified-multi-job-lineage-wire-v2-adr-032)):

- Versioned wire + reducer entries (`LineageWireReader`, `reduceWire` /
  `reducePartialWire`, v2 payload cases, provenance record, typed refusals) —
  `modules/runtime/src/main/scala/raider/runtime/lineage/io/LineageWireReader.scala`,
  `modules/runtime/src/main/scala/raider/runtime/lineage/wire/LineageWire.scala`,
  `modules/runtime/src/main/scala/raider/runtime/lineage/reduce/LineageReducer.scala`;
  lineage-data schema v2 — `contracts/lineage/lineage-data.schema.json`
- Multi-job export (join, canonical fork record, provenance, interleave
  policy) —
  `modules/cli/src/main/scala/raider/cli/run/MultiJobExport.scala` (+ runner
  integration in `HeadlessRunner.scala`, artifacts in `RunLineageArtifacts.scala`)
- Unified projection (phase/delegation defs, validated recorded fan-outs as
  `batch`, typed segments) —
  `modules/dsl/src/main/scala/raider/dsl/lineage/proj/UnifiedProjection.scala`
- Frozen acceptance corpus + independent verifier —
  `contracts/lineage/unified/**`,
  `modules/cli/src/test/scala/raider/cli/lineage/UnifiedCorpusVerifierSpec.scala`;
  a real rendered unified artifact —
  `docs/examples/ci-pipeline/unified-lineage/lineage.scala`

### 9.2 Still missing

| Missing item | Fact (evidence checked against baseline `4b46dc7`) |
| --- | --- |
| LIN-06 live re-run with a live model and budget | The mechanics are ready (`WorkspaceIsolation` isolation—`modules/cli/src/main/scala/raider/cli/lineage/WorkspaceIsolation.scala`, `Admission.make` budget in `ReplayHarness`—`modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala:298`, `RunDiffReport.scala` diff report), but there is no live provider behind the replay runner: `ReplayModelBackend` plays recordings only (`modules/cli/src/main/scala/raider/cli/lineage/ReplayModelBackend.scala:75`). The open card is LIN-06 (`docs/tasks/lineage.md`, “LIN-06: Live re-run”) |
| Live-wire verification | An owner obligation (owner's endpoint/budget); it has not been performed. Verifiable fact: the code has no live provider behind the replay runner—the only backend plays recordings (`modules/cli/src/main/scala/raider/cli/lineage/ReplayModelBackend.scala:75`) |
| ~~Multi-job export~~—shipped (RAI-144, ADR-032) | Delegate children of one run now join the run's single `events.jsonl` as schemaVersion 2 envelopes with `invocationId`, one canonical `Delegated` fork record, and one `run_provenance` record (`MultiJobExport`—`modules/cli/src/main/scala/raider/cli/run/MultiJobExport.scala`; runner integration + `RunLineageArtifacts` v2 gating; `docs/work/records/RAI-144.md`). Open follow-ups: the headless product path does not yet wire delegation into the `coder` agent (agent-registry policy surface), and phase payloads have no live emitter (next row); see [§5.2](#52-unified-multi-job-lineage-wire-v2-adr-032) |
| ~~Phase/recovery schema card~~—shipped (RAI-143, ADR-032) | The wire extension is shipped: `phase_started`/`phase_finished` (recovery metadata), `invocationId` correlation, `run_provenance`, versioned reader/reducer entries, GraphHash exclusion policy (ADR-032 §3); lineage-data schema v2 (`contracts/lineage/lineage-data.schema.json`); `docs/work/records/RAI-143.md`. Still open: the product runtime emits no v2 phase envelopes yet (the frozen ADR-024 hub seam is unchanged; `LoopEvents` has no phase surface) — real phase emission is a wiring card candidate; RecoveryClass on the wire for tools remains taxonomy-based (RAI-148 fixes the taxonomy source) |
| ~~TabShell wiring in the product REPL~~—closed (RAI-108) | The product entry point's `--tabs` flag starts TabShell: explicit flag and `runTabs` dispatcher in `main` (`modules/repl/src/main/scala/raider/repl/Main.scala:230-242`), `TabShellHost.wiring`/`TabShellHost.run` (`Main.scala:280-288`, `modules/repl/src/main/scala/raider/repl/TabShellHost.scala:31`); the legacy loop without the flag is unchanged; PTY acceptance P2E-01..03—`docs/work/records/RAI-108.md` |
| ~~Writing artifacts to the run directory~~—closed (LIN-09) | A settled run writes `lineage.scala` + `lineage-data.json` to its run directory alongside `events.jsonl`/`summary.md` (`modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala:665-677`; writer `RunLineageArtifacts`—`modules/cli/src/main/scala/raider/cli/run/RunLineageArtifacts.scala:52-55`); a write failure produces a typed `unavailable` line in summary.md without changing the exit code. `docs/work/records/LIN-09.md` |
| Other items, reported accurately | Catalog persistence is closed (LIN-12): `CatalogStore` uses atomic tmp+move, an append queue under a per-path lock, upsert by run-dir, and typed Corrupt with the entry name (`modules/cli/src/main/scala/raider/cli/lineage/CatalogStore.scala`; `docs/work/records/LIN-12.md`). LIN2-03 is covered by a property spec: 4 reader fibers vs a writer, 0 partial reads, ×3 flake=0 (`modules/dsl/src/test/scala/raider/dsl/lineage/store/LineageSnapshotRaceSpec.scala`; `docs/work/records/LIN-14.md`). LIN-06 scripted acceptance is closed (LIN-15: LIN6-01/02/03(sc), typed fixes to IsolatedRerun/ReplayHarness; `docs/work/records/LIN-15.md`; open follow-up: canonical-path guard before the live-rerun CLI). The replay command is integrated into the product: `raider replay` → `ReplayCommand` → `ReplayCli.exec` (`modules/cli/src/main/scala/raider/cli/run/ReplayCommand.scala:37`; `docs/work/records/RAI-109.md`, §10); the standalone `ReplayCliMain` entry point remains a transitional direct invocation of the same chain |

P-LIN cards are in `docs/tasks/lineage.md`; the contract is ADR-027. The LIN gate's
compilation component is implemented (`LineageCompileGateSpec`); the full P-LIN
phase milestone obligations (live-wire, multi-job export, phase/recovery schema
card) remain open. TabShell wiring is closed by RAI-108, and run artifact writing
is closed by LIN-09 (see the table above). This section records the actual state,
rather than declaring the phase complete.

## 10. Actual commands and paths (checked against modules/cli)

| Command/path | Fact (source in code) |
| --- | --- |
| `raider run (--bundle <jar> --workflow <name> \| --agent scout\|worker\|coder) (--input <text> \| --input-json <file>) --out <dir> [--mock] [--provider mock\|openai\|anthropic] [--base-url URL] [--model NAME] [--workspace DIR] [--context FILE]` | `CliArgs.parse` (`modules/cli/run/CliArgs.scala`): `--out` is required; `--agent` is mutually exclusive with `--bundle/--workflow`; `--api-key` produces a typed error (secrets must not be passed in argv) |
| `<out>/work-<uuid>/`—run directory; `run.json`, `events.jsonl`, `summary.md`, `transcript.json` | `HeadlessRunner.writeArtifacts` / concatenation of `--out` + `work-<uuid>` (`modules/cli/run/HeadlessRunner.scala`) |
| `events.jsonl`—per-job `EventEnvelope`, schemaVersion 1, duplicates discarded | `EventExport.jsonl` (`modules/cli/run/EventExport.scala`) |
| `<out>/work-<uuid>/lineage.scala`, `<out>/work-<uuid>/lineage-data.json`—alongside `events.jsonl`, included in the evidence fingerprint | LIN-09: a settled run writes both artifacts to its run directory (`RunLineageArtifacts.write`, `modules/cli/run/HeadlessRunner.scala:665-677`, atomically per-run dir); failure produces a typed `unavailable` line in summary.md without changing the exit code (`docs/work/records/LIN-09.md`); decision ADR-027 §2 |
| `raider replay <run-dir> --workspace <dir> [--data <file>]`—the product's replay-recorded subcommand (LIN-10 + RAI-109): full flag and exit-code parity with the standalone entry point, delegation to `ReplayCli.exec` (a single source for the argv contract) | `CliArgs.Target.Replay` dispatcher → `ReplayCommand.outcome` → `ReplayCli.exec` (`modules/cli/run/ReplayCommand.scala`, `modules/cli/lineage/ReplayCli.scala`): stdout is `verdict: match` + normalized graph-hash, or `verdict: mismatch` + `RunDiffReport` (first divergent node, both hashes); stderr is `typed-error: …`; exit codes follow ci-runtime §8 (`raider.core.result.ExitCodes`): 0 = Match, 10 = Mismatch, 21 = corrupt/missing data.json (typed `StoreError.Missing`/`Corrupt`), 20/22/23/24/26 = other typed outcomes (`RaiderError`/`ReduceError`/isolation); parallel replays use unique staging directories and do not write to the original (LIN10-03); run.json is NOT created for replay (result = None) |
| `java -cp <cli-classpath> raider.cli.lineage.ReplayCliMain …`—transitional standalone LIN-10 entry point (the same chain; retained for direct invocation without the product CLI) | `ReplayCliMain` (sanctioned ZIOAppDefault runner; `modules/cli/lineage/ReplayCli.scala`) |

Examples:

```sh
# Headless run; the run writes lineage.scala + lineage-data.json to <out>/work-<uuid>/ (LIN-09):
raider run --agent coder --input "Fix the regression and run the checks" \
  --out artifacts/<run> --mock

# replay-recorded—the product subcommand (LIN-10 + RAI-109; recorded-only, no model calls):
raider replay artifacts/<run> --workspace <run-workspace>
#   verdict: match   + graph-hash, exit 0
#   verdict: mismatch + first divergent node, exit 10
#   typed-error (corrupt/missing lineage-data.json), exit 21

# Transitional standalone LIN-10 entry point (the same chain, without the product CLI):
java -cp <cli-classpath> raider.cli.lineage.ReplayCliMain artifacts/<run> \
  --workspace <run-workspace>
```

Live/hybrid replay flags are not part of the product's `raider` command: replay is
recorded-only (LIN-10/RAI-109); live modes belong to LIN-06 and separate cards
(a public contract change requires a separate ADR if needed).

## 11. Related documents

- [ADR-027: Session lineage](adr/ADR-027-session-lineage.md)—contract and owner decisions.
- [P-LIN cards](tasks/lineage.md)—LIN-01…LIN-08, acceptance and allowed paths.
- [Runtime contracts §14/§15](runtime-contracts.md)—errors; deferred complexities.
- [Quality gates](quality-gates.md)—the LIN gate, stage-aware activation.
- [TUI design](tui-design.md)—modes A/B, tab host.
