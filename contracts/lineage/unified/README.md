# Unified lineage acceptance corpus (RAI-146, ADR-031 + ADR-032)

Frozen multi-root corpus of unified multi-job lineage streams plus the pinned
expectations the independent verifier
(`raider.cli.lineage.UnifiedCorpusVerifierSpec`) checks. This corpus is the
PROTECTED acceptance surface of the unified lineage feature: any change to
these files — streams, expectations, manifest — goes through the RAI-146
review lineage (a separate reviewed card), never a drive-by edit. The manifest
pins the sha256 of every file here; the verifier re-hashes each file before
use, so an accidental edit fails loudly instead of silently shifting the
acceptance target.

## Version

`corpus version 1` (2026-10-13). Card: RAI-146. Contracts: ADR-032 (v2 wire:
phase payloads, multi-job correlation, run provenance) and ADR-031 (dry-run
projection runtime). Baseline: the RAI-145 unified projection
(`modules/dsl/src/main/scala/raider/dsl/lineage/proj/UnifiedProjection.scala`).

## Layout

```
contracts/lineage/unified/
  README.md                                  this spec (hashed in the manifest)
  manifest.json                              corpus version + sha256 of every file
  cases/
    case-1-billing.jsonl                     golden: billing-shape repair+reviews+boundary
    case-1-billing.expected.json             pins for case-1
    case-2-delegation-tree.jsonl             golden: delegation tree (nested delegation)
    case-2-delegation-tree.expected.json     pins for case-2
    case-3-unlinked.jsonl                    golden: campaign + linked child, no stray envelopes
    case-3-unlinked.expected.json            pins for case-3 (unified side)
    case-3-stray.jsonl                       golden: the UNLINKED native root's own stream
    case-3-stray.expected.json               pins for case-3 (stray side)
    case-4-pure-fanout-roots.jsonl           golden: root scope that is ONLY a fan-out group
    case-4-pure-fanout-roots.expected.json   pins for case-4
  negatives/
    neg-1-corrupted-correlation.jsonl        case-1 with one child's invocationId stripped
    neg-1-corrupted-correlation.expected.json
    neg-2-invented-edge.jsonl                case-3 + a foreign segment with a stolen correlation key
    neg-2-invented-edge.expected.json
    neg-3a-torn-tail.jsonl                   case-1 with the last line truncated mid-JSON (byte tear)
    neg-3a-torn-tail.expected.json
    neg-3b-detached-segment.jsonl            case-1 crash prefix (39 lines; j-boundary-2 tail lost)
    neg-3b-detached-segment.expected.json
```

## Stream construction rules

All streams are JSONL, one `LineageEnvelope` per line, authored through the
REAL codecs (zio-json derived `LineageEnvelope` codec, v2 payloads per
ADR-032 §1). Pinned conventions:

- `rootId` is `root-unified-corpus`, `sessionId` is `sess-unified-corpus`
  for every stream; the campaign root job is the `jobId` of the FIRST line.
- Per-job `seq` runs 1,2,3,… dense in file order (the exporter's canonical
  rule; enforced by the versioned reader).
- `atMs` is a deterministic per-stream clock (5,10,15,… in file order).
- Child-job envelopes are `schemaVersion: 2` with `invocationId` (emitted
  last, omitted when absent) — the RAI-144 join convention; campaign frozen
  payloads are `schemaVersion: 1`; phase/provenance payloads are v2-only.
- Node ids are `<jobId>:n<seq>` (reducer convention); lineage-data keys are
  `<jobId>/round-<n>` and `<jobId>/call-<callId>`.

## Case inventory (VR-01 positives)

1. **case-1-billing** — the BillingCi shape: five wire-native phases
   (baseline-check / repair / final-check / reviews / boundary), eight
   correlation-linked child segments, one mutating `bash` tool call in the
   repair child, two recorded fan-out groups (reviews pair, parallelism 2;
   boundary trio, parallelism 3), a cross-run provenance record
   (`parentRunId` + 16 artifact pointers), 48 envelopes. Executed projection:
   the exact 23-report tree (5 phase defs @ nesting 1, 8 delegation defs and
   9 child-segment nodes @ nesting 2; fan-outs composed with the frozen
   `batch` verb in input order).
2. **case-2-delegation-tree** — orchestrator model round, then a recorded
   fan-out of two workers parented to the MODEL-ROUND node (non-def parent),
   and a nested delegation (worker → subworker → leaf). Covers projection
   nesting deeper than phase → delegation → child (RAI-145 record, risk 1)
   and the fan-out scope-matching fix (review finding f2: a validated group
   under a non-def parent is composed as recorded `batch`, so
   `GeneratedProjection.fanOut` never claims a group the source does not
   compose). 15 envelopes, 7-report tree.
3. **case-3-unlinked** (+ **case-3-stray**) — the unlinked-roots case: a
   campaign with one linked child and a provenance record, plus the stray
   native root's OWN stream (captured, never introduced, carrying a STOLEN
   `inv-checker` correlation key). The verifier proves the two reduce to
   separate graphs, project to disjoint trees, and the unified segment
   report carries the stray as typed `Unlinked` — the named VR-02
   unlinked-segment control (RAI-144 review deferral, finding f4).
4. **case-4-pure-fanout-roots** — a campaign whose roots are exactly one
   fan-out group (no other root nodes): the root scope's single emission
   element is a `batch(...)` expression, which must flatten to the
   `Task[Vector[StepReport]]` body type (review finding f1 reproducer; the
   emitted form is `batch(...)(p => p(())).map(_.flatten.toVector)`). Before
   the f1 fix this case compiled as a type-erroring bare batch.

## Negative controls (VR-02)

- **neg-1 corrupted correlation** — the repair child's `invocationId` is
  stripped: strict and partial reduction refuse typed
  `ReduceError.MissingCorrelation(j-repair, 1)`. No source is generated.
- **neg-2 invented edge** — the stray segment (stolen correlation key, no
  `Delegated` introduction) is appended to case-3's stream: strict and
  partial refuse typed `ReduceError.UnknownParent(j-stray, 1)`. A
  correlation key alone never invents an edge; introduction is required.
- **neg-3a torn tail** — case-1 with the final `Terminal` line truncated
  mid-JSON (byte-level crash artifact): the versioned reader returns the 47
  validated envelopes plus `TruncatedTail(48, 40)`; strict reduction refuses
  typed `ReduceError.MissingTerminal(camp-billing)`; the LIN5-03 partial
  entry keeps every completed node — the partial graph is IDENTICAL to the
  case-1 graph (same hash pin) and the partial projection compiles and
  executes the full 23-report tree deterministically.
- **neg-3b detached segment** — case-1 truncated to its first 39 lines (the
  crash hit mid-`j-boundary-2`; its round never finished, the boundary phase
  never closed, the provenance record and campaign `Terminal` are gone):
  strict refuses typed `MissingTerminal(camp-billing)`; the partial keeps
  only the completed 16-node graph (the open constructs and everything
  hanging under them are cut — no dangling edges), the segment report marks
  `j-boundary-2` typed `Incomplete`, no `runProvenance` val and no boundary
  def appear in the generated source, and the 16-report tree executes
  deterministically.

## Expectation derivation (how the pins were produced, and how to review them)

The expected documents are independent contract-semantics derivations from
the frozen streams — NOT snapshots of generator internals:

- `graphHash` — `GraphHash` of the strict (`reduceWire`) reduction; the
  canonical form is the pinned ADR-027 scheme (v2 metadata is excluded by
  contract, ADR-032 §3, so provenance never enters these pins).
- `reportTree` — the pre-order walk of the projected view per ADR-031 §2.3:
  report order == declaration order == graph child order; nesting is the
  static depth (roots 0; a phase/delegation def emits its own node one level
  deeper, its children are visited at that depth); `dataRef` and `inputs`
  are the view's 1:1 fields (reducer dataflow: each node inputs the job's
  previously appended node; a child's first node inputs its base Delegation
  node; phases carry no inputs). Batch fan-outs keep INPUT order (COMP-01),
  so the executed order equals this pre-order.
- `provenance` — the verbatim `run_provenance` record of the stream (ADR-032
  §1); resolution is checked against the linked segment inventory (per-child
  `work_dir` + `events` pointer pairs).
- `segments` / `fanOut` — the recorded data carried into the projection
  input, validated by the generator against the graph.
- `sourcePins` / `sourceAbsent` / `generatedSourceSha256` — deterministic
  generator outputs: the exact `batch(...)` lines and data vals must appear
  (or not appear), and the whole generated artifact must hash to the pin.
  These are the byte-level pins that make the surface protected: any
  emission change is a corpus-review event, not a silent drift.

Reviewing a pin = recompute the reduction/view by hand (or with a scratch
pipeline) from the frozen stream and compare. The verifier performs exactly
this comparison on every run.

## Verifier obligations

- **VR-01** every positive case: corpus integrity (manifest sha256), strict
  reduction green, graph hash pin, provenance resolution, exact executed
  report tree (×3), pinned-dotc compile green, source pins/absent pins,
  generated-source sha256.
- **VR-02** every negative: the pinned typed refusal (strict and, where
  pinned, partial), the pinned reader facts (envelope count, truncated
  tail), and for the torn cases the deterministic typed-partial projection.
- **VR-03** verifier green ×3; concurrent verification is isolated (every
  compile runs in a unique temp directory; parallel verification of
  different cases produces identical results).

## Change policy

This corpus is frozen. Adding a case, changing a stream, or refreshing any
pin requires a reviewed card in the RAI-146 lineage (the verifier is the
protected acceptance surface of unified lineage). The manifest's sha256 map
must be regenerated in the same change; the verifier fails on any hash
mismatch, so stale edits cannot pass.
