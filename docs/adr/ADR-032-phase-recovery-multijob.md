# ADR-032: Versioned phase/recovery events and cross-run provenance (lineage wire schemaVersion 2)

Status: **Accepted** (owner-authorized schema card RAI-143, 2026-10-13; P2
unified CI pipeline objective). Date: 2026-10-13. Supersedes nothing; extends
[ADR-027](ADR-027-session-lineage.md) §6.2 gaps 1–2 (phase markers, recovery
class on wire) and resolves the wave-2 schema-card obligation. Does not change
the frozen ADR-024 runtime→UI event seam.

## 1. Decision

The lineage event wire (events.jsonl consumed by the lineage reader/reducer)
gains a **versioned extension under `schemaVersion: 2`**. The frozen v1 wire
(13 `EventPayload` cases, ADR-024) is **pinned**: v1 documents decode
unchanged, bit-for-bit, through the same frozen codecs. The ADR-024 seam
(`raider.runtime.events.**`) is NOT modified — the v2 envelope is a
lineage-layer type (`raider.runtime.lineage.wire.LineageEnvelope`) whose v1
form is wire-identical to the frozen envelope.

Three additive groups:

1. **Phase payloads (wire-native phases).** `phase_started` (enter, with
   recovery metadata) and `phase_finished` (exit). A `phase_started` creates a
   `SessionNode.Phase` at enter; `phase_finished` closes the innermost open
   phase with that name (LIFO per job). Phase recovery metadata is policy
   input for future live re-run isolation (ADR-027 §2), carried on the wire
   and into the lineage-data resource; it is deliberately NOT a
   `SessionNode.Phase` field (§4 hash policy, §6 model boundary).
2. **Multi-job correlation.** Optional envelope field `invocationId`
   (non-empty string): the delegation invocation a job's envelopes belong to.
   Optional on v1 envelopes (and on v2 envelopes of the root job); **required
   on v2 envelopes of every non-root job** (reducer-checked, typed
   `ReduceError.MissingCorrelation`). The parent control edge still comes only
   from the parent's frozen `Delegated` payload; `invocationId` is the
   invocation-level join key RAI-144 uses to merge child segments into one
   provenance tree. RAI-143 defines the fields and validation; the join
   implementation is RAI-144.
3. **Cross-run provenance record.** `run_provenance` payload: at most ONE per
   stream (second occurrence → typed `ReduceError.DuplicateProvenance`),
   carrying `parentRunId` (optional; absent = root run, never guessed) and
   `artifacts` — a list of `{kind, path}` native artifact pointers (e.g. the
   per-job work dir and events.jsonl of upstream runs).

### Wire shapes (exact JSON)

v1 envelope — unchanged, frozen codec bytes:

```json
{"schemaVersion":1,"seq":1,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":0,"payload":{"type":"model_call_started","round":1,"model":"m","messageCount":null}}
```

v2 envelope with correlation + phase enter/exit:

```json
{"schemaVersion":2,"seq":2,"jobId":"job-child-1","rootId":"root-1","sessionId":"sess-1","atMs":15,"payload":{"type":"phase_started","name":"repair","recovery":"Mutating"},"invocationId":"call-9"}
```

```json
{"schemaVersion":2,"seq":3,"jobId":"job-child-1","rootId":"root-1","sessionId":"sess-1","atMs":42,"payload":{"type":"phase_finished","name":"repair"},"invocationId":"call-9"}
```

v2 provenance record (root job):

```json
{"schemaVersion":2,"seq":7,"jobId":"job-1","rootId":"root-1","sessionId":"sess-1","atMs":90,"payload":{"type":"run_provenance","parentRunId":"run-2026-10-12-a","artifacts":[{"kind":"work_dir","path":"/tmp/run-a"},{"kind":"events","path":"/tmp/run-a/events.jsonl"}]}}
```

Encoding rules: `invocationId` is emitted last and omitted when absent; v2
payloads emit `type` first; `note` and `parentRunId` are omitted when absent
(frozen wire convention: `None` options are omitted). `recovery` uses the
closed names `ReadOnly | Idempotent | Mutating` (same names as the
lineage-data `toolCallData.recovery` field). Unknown `type` values and
malformed v2 fields are decode failures (typed), never guesses.

## 2. Versioning policy

- `schemaVersion` per envelope: `1` (frozen, pinned) or `2` (this ADR).
- Any other value → typed refusal at both layers
  (`LineageWireError.SchemaVersionUnsupported` in the reader,
  `ReduceError.SchemaVersionUnsupported` in the reducer). Unknown future
  versions are never decoded best-effort.
- v1 documents decode unchanged: the v1 payload union is exactly the frozen 13
  cases; a v1 envelope carrying `invocationId` is a version-mixing anomaly and
  is refused typed (`LineageWireError.InvocationIdOnV1`) — unknown/additive
  fields are rejected, never silently ignored.
- Mixed v1/v2 streams are legal input to the versioned reader/reducer; each
  envelope keeps its own version as typed data. The frozen single-version
  reader (`LineageEventReader`) and its behavior are unchanged; its rejection
  of `schemaVersion: 2` remains correct for v1-only consumers.
- Phase pairing on the stream: `phase_finished` without an open phase of that
  name in the same job → typed `ReduceError.PhaseMismatch`. A phase still open
  at the job's `Terminal` is legal: its node stands (it started; the stream
  says nothing about exit) — same honesty rule as the unknown-outcome tool
  flush.

## 3. GraphHash canonicalization policy — DECIDED: excluded by contract

**New v2 metadata does NOT enter the GraphHash canonical form. `GraphHash` is
unchanged byte-for-byte; no hash-version bump; v1 hashes remain valid and
comparable.**

Justification:

1. GraphHash identifies **call-graph shape** — the ADR-027 §2 replay-success
   contract is "same call graph (tree shape: nodes/edges/types)". Phase NODES
   (id/parent/seq/name/note) were already canonical v1 line shapes; they now
   occur from the wire and hash exactly as before. Phase recovery class,
   `invocationId` and the provenance record are policy/environment metadata,
   not call-graph shape.
2. Native artifact pointers are **environment-relative**. Pinning them into
   the canonical form would make replay/evidence hashes machine-dependent:
   the same logical run recorded in two work dirs would hash differently,
   breaking replay comparison for a reason that is not a graph difference.
3. Zero-migration: comparators (LIN-05) and every frozen v1 golden hash pin
   stay valid. The alternative policy (hash-version bump with the new fields
   in the canonical form) would break hash comparability for every existing
   consumer and require regenerating frozen golden pins — that cost buys no
   integrity the contract needs.
4. Injectivity is preserved because the hash's domain is unchanged: the
   `SessionGraph`/`SessionNode` model gains NO fields (§6). Two graphs with
   the same hash are node-for-node identical; v2 metadata lives outside the
   model and gets its own acceptance pins (RAI-146 corpus verifies provenance
   resolution explicitly).

Consequences for comparators: a recorded and a replayed run with identical
graphs but different provenance pointers compare EQUAL by graph hash —
intentional; provenance equality is asserted by the RAI-146 verifier against
the corpus, not by GraphHash.

## 4. lineage-data schema v2 (additive)

`contracts/lineage/lineage-data.schema.json` moves `schemaVersion` from
`const 1` to `enum [1, 2]` with these additive, optional fields:

- top-level `provenance`: `{parentRunId: string|null (optional), artifacts:
  [{kind, path}] (required)}`, `additionalProperties: false`; forbidden on
  schemaVersion-1 documents by a draft-07 `if/then`;
- `phaseData.recovery`: optional enum `ReadOnly|Idempotent|Mutating`;
- `delegationData.invocationId`: optional non-empty string.

Version coupling of the data-entry fields (`phaseData.recovery`,
`delegationData.invocationId`) cannot be expressed in draft-07 (data entries
are independent subschemas and cannot reference the document version), so —
like all cross-references in this contract — it is **validator/reducer-checked**:
the DSL codec refuses a schemaVersion-1 document that carries any v2 field
(typed `LineageDataError.V2FieldOnV1`), never silently ignoring it.
`contracts/lineage/golden/**` are NOT touched by this ADR; they remain valid
v1 documents. The README contract prose and any new golden corpus for the
unified shape belong to RAI-146.

## 5. Model boundary

`SessionGraph`, `SessionNode`, `GraphHash` and the frozen event seam are not
modified. The v2 types live in `raider.runtime.lineage.wire`
(`LineageEnvelope`, `LineagePayload`, `RunProvenance`, `ArtifactPointer`);
the reducer returns `LineageReduction(graph, provenance)` from the versioned
entry points (`reduceWire` / `reducePartialWire`). The existing
`reduce`/`reducePartial` (frozen `EventEnvelope` input) keep their signatures
and behavior; they are the v1 entry points. Additive `ReduceError` cases:
`PhaseMismatch`, `DuplicateProvenance`, `MissingCorrelation`.

## 6. Consequences

- RAI-144 (multi-job export) writes v2 envelopes with `invocationId` on child
  segments plus per-child provenance pointers, and consumes
  `reduceWire`/`reducePartialWire` to build the unified tree.
- RAI-145 projects phase nodes as first-class pipeline segments; phase
  recovery metadata reaches the projection through the lineage-data v2
  payload, not through the graph model.
- RAI-146 owns the independent acceptance corpus: v2 golden wire fixtures,
  unified graphs, provenance-resolution pins, negative controls (corrupted
  correlation, invented edge). It also owns README/golden regeneration under
  this ADR's policy.
- Downstream consumers comparing hashes across this change need NO migration;
  consumers that must distinguish v2 metadata use the typed reduction result
  and the lineage-data document, not GraphHash.
