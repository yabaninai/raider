# Lineage data contract (LIN-02A, ADR-027)

Frozen contract of `lineage-data.json` — the data resource of a Raider session
lineage run ([ADR-027](../../docs/adr/ADR-027-session-lineage.md), cards
[P-LIN](../../docs/tasks/lineage.md)). The generated lineage program carries
**no inline data**: every node payload (full prompts, tool args/results,
delegation targets, phase names) lives in the `data` map and is referenced by
`dataRef`. The Scala codec for this format is implemented against this exact
revision (schemaVersion 1); any shape change is a new schemaVersion, never a
silent change (same discipline as `modules/core/schemas.scala`).

## Layout

```
contracts/lineage/
  lineage-data.schema.json        JSON Schema (draft-07), frozen revision
  golden/tiny-graph/              minimal valid example (see below)
  golden/deep-tree/               delegation/nesting example (see below)
```

Each golden directory contains two files:

- `lineage-data.json` — the frozen contract document;
- `session-graph.json` — informative companion fixture of the SessionGraph
  shape (nodes with `id`/`kind` plus `control`/`dataflow` edges, ADR-027 §3).
  It is NOT part of the frozen contract; it exists so the examples stay
  mutually consistent with the LIN-01 graph model and the golden checker can
  verify graph ↔ data agreement.

## Document fields (schemaVersion 1)

| Field | Type | Meaning |
| --- | --- | --- |
| `schemaVersion` | const `1` | Frozen wire revision; the only allowed value here. |
| `generator` | object | Producing toolchain: `name` (const `raider`), `version` (semver), `scalaVersion` (const `3.9.0`), `jdkVersion` (major, string). |
| `runId` | string | Identifier of the recorded run (chat session, headless run or campaign run). |
| `nodes` | array | Call-graph nodes in causality order. |
| `data` | object | Node payloads keyed by `dataRef`; values must match exactly one node-data shape. |

Node fields: `id`, `kind` (`model_call` \| `tool_call` \| `delegation` \|
`phase`), `parentId` (control edge; `null` for roots), `seq` (run-scoped
monotonically increasing counter; canonical generators emit 1, 2, 3, …),
`atMs` (offset from run start, ms), `inputs` (dataflow edges: nodeIds whose
outputs this node consumes), `dataRef` (key into `data`).

Data payload per node `kind`:

| kind | payload |
| --- | --- |
| `model_call` | `{model, round, prompt, response, usage}` where `usage` is `null` or `{reservedMicroUsd: integer\|null, observedMicroUsd: integer\|null, unknown: boolean}` |
| `tool_call` | `{tool, callId, recovery, outcome, argsSummary, resultTail}` where `recovery` ∈ `ReadOnly\|Idempotent\|Mutating` and `outcome` ∈ `Succeeded\|Failed\|Cancelled\|Timeout\|null` (`null` = unfinished node); `resultTail` is `string\|null` |
| `delegation` | `{childJobId, agent}` |
| `phase` | `{name, note}` where `note` is `string\|null` |

## Invariants

Schema-enforced (draft-07, `additionalProperties: false` everywhere — unknown
fields are rejected, never silently ignored):

1. `schemaVersion` is exactly `1`; `generator.name` is exactly `raider`;
   `generator.scalaVersion` is exactly the pinned `3.9.0`.
2. Every `data` value matches exactly one of the four payload shapes (they are
   mutually exclusive by construction).
3. `dataRef` is a required non-null string on `model_call` and `tool_call`
   nodes (conditional `if/then` in the schema). Canonical generators emit it
   for every node kind.

Validator/reducer-checked (draft-07 cannot express cross-references; the
golden checker and the LIN-01/LIN-02 code enforce them):

4. **Unique node ids** — `id` is unique across the document.
5. **Parents-before-children** — `parentId` is either `null` (root) or a node
   that appears EARLIER in the `nodes` array; a node is never its own parent.
6. **Referential integrity** — every `dataRef` resolves to an existing `data`
   entry whose payload shape matches the referencing node's `kind`; every
   `inputs` entry is an existing node id and not the node itself.
7. **No inline data in code** — the projection (LIN-02) reads all payloads
   from `data`; `lineage.scala` contains structure only (ADR-027 §2: prompts
   are stored whole in the data resource, never in code).

## Rejected example (negative case, LIN2-A-02)

Duplicate node ids are rejected — the reducer/codec fails with a typed error,
never silently merges or renames. The following `nodes` fragment is invalid:

```json
[
  {"id": "n1", "kind": "phase", "parentId": null, "seq": 1, "atMs": 0, "inputs": []},
  {"id": "n1", "kind": "model_call", "parentId": "n1", "seq": 2, "atMs": 10, "inputs": []}
]
```

It is invalid: two nodes share `id: "n1"`, which breaks invariant 4
(unique ids) and makes `parentId: "n1"` ambiguous (self-parent on top). The
same rejection applies to unknown `kind` values, unknown fields, a
`model_call`/`tool_call` node without `dataRef`, and a `dataRef` pointing to a
payload of a mismatched shape. Broken inputs are a typed error, not a
best-effort skip (LIN1-02 discipline).

## Golden examples

- `golden/tiny-graph/` — minimal valid run: one user phase (root), one model
  round, two tool calls with different recovery classes (`ReadOnly` succeeded,
  `Mutating` failed with `resultTail: null`); `usage.reservedMicroUsd: null`
  covered.
- `golden/deep-tree/` — delegation with a nested subtree (child model round
  and tool call under the `delegation` node), an unfinished tool call
  (`outcome: null`), a `usage: null` model round, and an inputs-dataflow edge
  crossing the delegation boundary (`n3.inputs = ["n1"]`, rendered as a
  `dataflow` edge in `session-graph.json`).

Both examples are structurally validated against `lineage-data.schema.json`
and cross-checked against their `session-graph.json` (same node set in the
same order; `control` edges == non-null `parentId` relations; `dataflow` edges
== `inputs` relations; phase/recovery/childJobId agreement with payloads).
Validation is done by a structural checker script run from a temp directory
(the `python3-jsonschema` library is not available in this environment); the
checker is deliberately NOT stored in the repo.

## Determinism (LIN2-A-03)

Golden files are canonical: 2-space indent, keys in contract order, UTF-8
without `\r`, exactly one trailing newline. `json.dumps(obj, ensure_ascii=False,
indent=2) + "\n"` reproduces them byte-identically; the checker verifies this
re-serialization equality. Regeneration must not reformat them.

## Freezing

Changes to this contract require a new `schemaVersion` and a separate ADR
(additional fields, new node kinds via payload shapes, enum extensions are all
schema decisions). Downstream consumers: the LIN-01 reducer, the LIN-02
projection/codec and the LIN-05 replay comparator.
