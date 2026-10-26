# ADR-038: Chat named-check to portable trustedCheck export mapping

Status: **Proposed** (authored and implemented by card RAI-215 on
`lane/rai-215`; coordinator review and integration pending). Date: 2026-10-26.
Builds on [ADR-033](ADR-033-chat-workflow-export.md) (chat export semantics,
CWF-R3) and [ADR-036](ADR-036-portable-workflow-format.md) (portable workflow
v1 trust rules). Changes only the chat portable export path
(`PortableChatExport`, `ExportWorkflowCommand --format portable`) and the chat
export manifest schema; it does **not** change the portable workflow v1
document schema, the lowerer, the launcher-pinned trusted registry
(`PortableTrustedRegistry`), the Scala export, or `TurnLedger` selection
semantics.

## 1. Problem

A validated chat selection always names at least one trusted check
(ADR-033 §4.1). Portable workflow v1 has no user-named check construct: checks
enter a portable document only as `trustedCheck` steps that NAME a
launcher-pinned `(checkId, registrySnapshot)` pair (ADR-036). RAI-203 therefore
refused every chat selection with CWF-R3 ("not representable"), which made
`:export-workflow --format portable` dead code for the chat command it was
built for, even when the user's registered check is definitionally identical
to a pinned one.

The missing piece is the MAPPING rule: when does a chat named check
`(name, argv, advisory)` correspond to a pinned trusted check such that the
export may name it — and what must happen otherwise?

## 2. Decision

### 2.1 Exact-match mapping (the rule)

A chat named check maps onto a portable `trustedCheck` step if and only if ALL
of the following hold against the exporting launcher's pinned check table:

1. `advisory == false`. An advisory chat check (failure does not block) has no
   portable counterpart: every `trustedCheck` result feeds the trusted-verdict
   projection, so mapping it would silently STRENGTHEN it into a blocking
   check. The refusal retains the Scala export, which renders advisory
   semantics natively.
2. `name == checkId` of a pinned entry.
3. `checkSnapshot(name, argv) == registrySnapshot` of that pinned entry, where
   the canonical derivation is:

   ```
   checkSnapshot(checkId, argv) =
     "sha256:" + lowercaseHex(SHA-256(UTF8((checkId +: argv).mkString("\u0000"))))
   ```

   This is exactly the derivation the launcher registry already uses
   (`PortableTrustedRegistry.checkSnapshot`); it now lives canonically next to
   the wire rendering (`PortableChatExport.checkSnapshot`), and a pinned test
   asserts both produce the identical digest for the shipped marker check, so
   the two definitions cannot drift. The digest binds the check id AND the
   full argv vector: a stale or foreign definition under a pinned name never
   matches, and no argv ever enters the exported document or manifest.

The mapping is **all-or-nothing**: every named check in the selection must
map. The first unmappable check refuses `CWF-R3` naming the offending check,
the specific reason (no pinned check with this id / the id is pinned but the
definition digest differs / advisory checks are not representable), and the
retained Scala option. Nothing is fabricated: the exporter never invents a
`registrySnapshot`, never inlines argv, never widens or renames the pinned
authority, and dispatches nothing.

The mapping is a **pure function**
`(List[NamedCheck], pinned table) -> Either[RaiderError, List[PortablePinnedCheck]]`:
no registry mutation, no I/O, no dispatch; identical inputs produce identical
outputs. Duplicate checks that map to the same `(checkId, registrySnapshot)`
pair fold into one gate step, preserving first-occurrence order.

### 2.2 Document shape

The exported document keeps the RAI-203 shape — `act` (agent, pinned
profile/toolset) → gate step(s) → `run` (sequence) — generalized from one gate
step to one gate step PER mapped check, in selection order:

* gate step ids are `gate`, `gate-2`, `gate-3`, … (first gate keeps the
  historical id `gate`; single-check documents are shape-identical to the
  RAI-203 render);
* every gate step has `input: #/steps/act/output` (checks are pure programs
  over the agent output; the input is DATA, never executed);
* `run` sequences `act` and all gates; the verdict remains the run output
  under `trustedVerifier` authority.

Because the mapping guarantees the document names exactly pinned
`(checkId, registrySnapshot)` pairs, `raider workflow run` over the same
launcher lowers the exported gates natively — `PortableProgramRegistry.checks`
is keyed by precisely this pair — with zero new runtime code and zero
authority widening.

### 2.3 Export target and pinned table delivery

`PortableExportTarget` carries the launcher's pinned trusted references as
data: `profile`, `toolset`, and `checks: List[PortablePinnedCheck]` (new
`PortablePinnedCheck(checkId, registrySnapshot)`). The dsl layer stays
launcher-agnostic: `ExportWorkflowCommand` passes THIS launcher's pinned table
(today: `workspace-marker-present` with its frozen snapshot digest). A
selection without named checks (reachable only from non-chat launchers and the
dsl API) renders the pinned entries as gate steps — the unchanged RAI-203
recipe path.

### 2.4 Manifest: one additive, optional field

The chat export manifest records what the document names. With N mapped gates
a singular `trusted` object would under-record, so the closed manifest schema
gains exactly ONE additive, OPTIONAL property:

```json
"trustedChecks": {
  "type": "array", "minItems": 1, "maxItems": 64,
  "items": {
    "type": "object", "additionalProperties": false,
    "required": ["checkId", "registrySnapshot"],
    "properties": {
      "checkId": { "$ref": "#/$defs/identifier" },
      "registrySnapshot": { "type": "string", "pattern": "^sha256:[a-f0-9]{64}$" }
    }
  }
}
```

Rules:

* `trusted` keeps its exact singular shape and records the FIRST gate
  (backward-compatible readers still see a valid primary reference);
* `trustedChecks` (the complete ordered list, first entry equal to `trusted`)
  is present if and only if the export mapped named chat checks; the check-free
  path keeps today's manifest byte-for-byte (field absent);
* `schemaVersion` stays `1` — the addition is optional and closed;
* `contracts/workflow-portable/schema/chat-portable-export-manifest-v1.schema.json`
  and the runtime mirror (`PortableChatExport.validateManifest`) change
  together in this card; the portable workflow v1 document schema and its
  fixture corpus are untouched.

The shipped recipe examples (`docs/examples/portable-workflows/`) are
standalone workflow documents, not chat exports; they contain no export
manifest and need no change.

### 2.5 Refusal matrix

| Selection check vs pinned table | Result |
| --- | --- |
| Exact match (`name == checkId`, digest equal, non-advisory) | Gate step naming the pinned pair; export succeeds |
| Unknown id (no pinned check with this name) | `CWF-R3`, names the check, retains the Scala option |
| Pinned name, different argv (partial match) | `CWF-R3`, names the digest mismatch, retains the Scala option |
| Different name, pinned argv (renamed check) | `CWF-R3` (digest binds the id too), retains the Scala option |
| Advisory check (even if definition-identical) | `CWF-R3`, names the advisory-semantics reason |
| Launcher pins no checks / empty pinned table | `CWF-R3` for every named check |

Every refusal is typed, explains the step, and never writes artifacts.

## 3. Consequences

* `:export-workflow --format portable` becomes live for the supported chat
  shape: a user who registers the exact pinned definition (today
  `:task check workspace-marker-present /bin/test -f marker.txt`) gets a
  portable export that validates, edits, and runs green through native
  lowering on the same launcher (pinned end-to-end test against the mock
  provider; the verdict is decided by the real pinned check, never by agent
  text).
* Unmapped checks keep the honest RAI-203 refusal — now with a per-check
  reason instead of a blanket "not representable".
* Consumers updated in this card: `ExportWorkflowCommand` (passes the pinned
  table), both test suites. No other consumers exist
  (`PortableExportTarget`/`documentJson`/`manifestJson` have no other
  callers at baseline `b90a191`).
* Rollback is a revert of the scoped change; the check-free export path and
  the launcher registry are unchanged.

## 4. Non-goals

Inlining argv or policy into exports; widening the pinned authority; new wire
beyond §2.4; changes to `TurnLedger` selection semantics, the Scala export,
the lowerer, the document validator bounds, or gate policy. A launcher that
pins more checks in the future needs no new ADR: the mapping rule, document
shape, and manifest field already cover N pinned entries.
