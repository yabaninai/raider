# ADR-027: Session lineage — a call tree as a valid Scala program

Status: **Accepted** (owner Q&A 2026-10-10, 16/16 answers). Date: 2026-10-10.
Owner: project owner (interactive survey). Priority: **ahead of the entire plan**
(roadmap-12k revision: P-LIN phase before P0). Does not change gate policy or
runtime-contracts §14; extends them with a new layer.

## 1. Decision

For each session (chat, headless, night campaign), Raider builds a
**SessionGraph** — a call tree materialized as a **valid Scala program**
(lineage program) plus a data resource. Running the program
**reproduces the chain**.

## 2. Owner decisions on record (2026-10-10)

| # | Decision |
| --- | --- |
| Replay success | **Same call graph** (tree shape: nodes/edges/types); LLM text may differ in live mode |
| Default mode | **Replay-recorded** (free, deterministic) |
| Primary mode | **Hybrid**: LLM nodes from the recording, deterministic tool calls are recomputed (similar to Spark lineage) |
| Nodes | Tool calls + LLM rounds + delegation subtrees + **user-defined phases** (segmentation for compaction and partial replay) |
| Prompts | Included in full — node inputs are stored in the data resource, not in code |
| Scope | Chat sessions + headless runs + night campaigns (tree catalog) |
| Mutating nodes during live re-run | Only in an **isolated workspace copy** (RecoveryClass = policy) |
| Live branch during live re-run | Success ("live" graph, with a discrepancy report); failure for replay verification |
| Generation | **Incremental live generation**, artifact available through a **separate TUI tab** |
| Layer | **New SessionGraph layer** (not only a projection of Program DSL); node types are extensible for future tools |
| Storage | Beside the run: artifacts/<run>/lineage.scala + lineage-data.json; included in the evidence-fingerprint |
| Environment changes | Warn + attempt; compare pinned versions (toolchain.json); produce a discrepancy diff report |
| Partial replay | Required: replay a named phase/subtree |
| Plan placement | **P-LIN phase before P0** in roadmap-12k (literally ahead of everything) |
| Current card campaign | Continues in the background (closes LIN dependencies: RAI-020/022) |

## 3. Architecture (layers)

1. **SessionGraph** (model): event-sourced graph, reducer `events.jsonl →
   graph`. Nodes: `ModelCall | ToolCall(kind, RecoveryClass) | Delegation(subtree)
   | Phase(name)`; edges represent dataflow + control flow. Extensibility: new
   tool-call types are registered as ToolCall-node subtypes without changing the contract.
2. **Projection**: `graph → valid Scala` (RAI-017 `Program`/`Task` compositions
   are the compilation target, not the model). No inline data: all records are in lineage-data.json.
3. **Live support**: incrementally extend the graph and projection during the
   session; periodically compile/validate incrementally; the artifact is always
   available in the TUI Lineage tab (a minimal tab host is pulled forward from P2,
   with limited scope).
4. **Replay**: replay-recorded (default), hybrid (primary), live
   re-run (opt-in, isolated WS copy, budget, diff report of the “live vs
   recorded” graph). A graph comparator is part of acceptance.

## 4. Consequences

- Program DSL remains frozen (RAI-017): it is the projection target, not the source.
- Events are the single source of truth; the lineage program is a deterministic
  projection (like Spark's `explain()`). Manual edits to the projection are detected
  by the comparator.
- Program validity is checked through compilation periodically and in CI (LIN gate).
- Long sessions: compact nodes into phases; keep the data resource separate from code.
- roadmap-12k budget: +1,100 h (P-LIN phase), cumulative totals recalculated.

## 5. Addendum (2026-10-10, night): runtime and execution scale

The owner raised P-LIN urgency: execution moves to **OpenCode agents**
(model `glm-5.3-flash` through the public yabanin.ai stand, child key `yag_`),
with **up to 10 concurrent lane workers** (overriding the “up to three” limit in
parallel-development.md §1 by owner decision; isolation is preserved:
snapshot copies without .git, manifests, separate targets). Night work uses
queue cycles with retries for unfinished cards. sbt gates are serialized by a
global lock (to protect against 10 concurrent JVMs). Attribution remains the
child key + labels where the transport supports them.

## 6. Addendum (2026-10-10, night): contract gaps from independent L9 review

Independent baseline review (W6) confirmed baseline-trust and found gaps
between §3 and the current event layer (ADR-024, 13 frozen payload cases):

1. **Phase markers**: events have no phase case — Phase nodes are currently built
   only by the graph model; the `PhaseMarked(name)` wire extension is a separate
   wave-2 schema card with independent review (frozen seam: adding a case is a
   schema decision, events.scala).
2. **RecoveryClass on wire**: tool calls do not carry a recovery class; before the
   schema card, the source is a static tool-name → RecoveryClass taxonomy in the lineage
   layer (ReadOnly by default, Mutating for write/exec classes), extensible through
   config without changing frozen events. **Update (2026-10-13, RAI-148)**: the
   canonical taxonomy source is `raider.runtime.lineage.model.RecoveryTaxonomy`
   (single source; LineageReducer, LiveGraphBuilder, and IsolatedRerun consume it
   — no private copies). Membership correction: `fs_patch` (write class) and
   `proc_run` (exec class) are Mutating — they were missing from the original
   sets, so captured repairs using `fs_patch` were misclassified ReadOnly.
   GraphHash policy: the recovery class renders into the canonical form, so
   historical records containing `fs_patch`/`proc_run` calls re-verify to
   different hashes and must be recomputed per the RAI-148 record; no in-tree
   fixture was affected.
3. **Dataflow inputs/outputs**: `argsSummary ≤512 redacted` is not the full data;
   full data lives in lineage-data.json (the data resource), while the graph model
   carries `inputs: Vector[NodeId]` as separate dataflow edges.
4. **Multi-job export**: a run's events.jsonl contains one job; delegated child
   events are collected into a shared graph by the journal layer's reducer keyed
   by rootId/jobId; unified multi-job export belongs to `cli/run/**`, wave 2, after
   the read API is frozen.
5. **Recording store**: durable storage for lineage-data.json is required before
   LIN-05 replay; otherwise rework is needed. Wave-2 slot 0.

These are obligations, not new decisions; each wire change goes through its own
schema card + review.
