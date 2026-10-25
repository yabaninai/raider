# Portable workflow v1 contract fixtures

Status: proposed fixture corpus for ADR-036; these documents freeze the intended
contract shape for independent review, not runtime behavior. Schema:
[`schema/workflow-v1.schema.json`](schema/workflow-v1.schema.json).

## Corpus

* `valid/` has one schema-valid standalone document per construct/binding:
  agent, trusted check, sequence, typed conditional, bounded map, join, repair,
  checkpoint, output binding/final verdict, and root limits (10 fixtures).
  Fixtures intentionally remain standalone minimal documents so every named
  concept has a direct golden example.
* `refusals/manifest.json` has 17 preflight refusal cases. Each includes expected
  stable refusal code, document path, step ID when applicable, offending
  reference, suggested correction, and the zero-dispatch invariant. Raw text is
  used for duplicate JSON keys because a parsed JSON object cannot represent
  them faithfully.
* `refusals/fixtures/oversized.json` and
  `refusals/fixtures/oversized-utf8.json` are real schema-valid workflow
  documents of exactly 262145 UTF-8 bytes. Rebuild them deterministically from
  the contract directory with `python3 tools/build_oversized_fixture.py`; the script starts
  from `valid/agent.json`, puts the padding in the declared input string
  schema's enum (ASCII in the first artifact and wide Unicode in the second),
  and sizes the serialized JSON to the exact target byte count.
  The generated artifact is committed so validation can measure and schema-
  validate the exact bytes. JSON Schema does not express the whole-document byte
  ceiling. RAI-198 local preflight owns that check: UTF-8 document byte length
  `> 262144` refuses with `document-too-large` at document root JSON Pointer
  `""` (the empty string), before runtime dispatch. This fixture declares and
  verifies the expectation and is not a runtime refusal execution in RAI-197.
* The bounded-map example binds its template agent to `#/item`; `#/index` is
  also available in the template scope. The item reference has the source array's
  item schema. Refusals cover pointers escaping that scope, schema depth over 16,
  and enums larger than 64 values.
* Frozen v1 resource bounds are 262144 UTF-8 document bytes, schema depth 16
  (each input/output root counts as depth 1), and 64 enum values per schema node.

The acceptance runner introduced by RAI-198 must apply schema and semantic
validation to these fixtures without a provider, tool registry side effect, or
runtime launch. “Valid” means the document conforms to the proposed JSON Schema;
external trusted profile/check resolution is represented by names and provenance
fixtures, and is not evidence that a production registry exists.

## Fixture IDs

Valid: `agent`, `trusted-check`, `sequence`, `conditional`, `bounded-map`,
`join`, `repair`, `checkpoint`, `output-verdict`, `root-limits`.

Refusal: `unknown-major`, `duplicate-step-id`, `duplicate-object-key`, `cycle`,
`missing-reference`, `ill-typed-reference`, `impossible-join`, `oversized`,
`too-deep`, `unbounded-work`, `arbitrary-expression`, `callback`,
`authority-expansion`, `map-item-pointer-escape`, `schema-too-deep`,
`enum-oversized`.

All paths and IDs are stable fixture identities. Schema, semantic validator,
and diagnostic wording may be refined only through ADR-036 review and updates to
this corpus before implementation freeze.
