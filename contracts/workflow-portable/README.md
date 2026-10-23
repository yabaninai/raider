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
* `refusals/manifest.json` has 13 preflight refusal cases. Each includes expected
  stable refusal code, document path, step ID when applicable, offending
  reference, suggested correction, and the zero-dispatch invariant. Raw text is
  used for duplicate JSON keys because a parsed JSON object cannot represent
  them faithfully.

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
`authority-expansion`.

All paths and IDs are stable fixture identities. Schema, semantic validator,
and diagnostic wording may be refined only through ADR-036 review and updates to
this corpus before implementation freeze.
