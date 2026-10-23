# Proposed provider output-capability extension contract

Status: proposed, not approved or implemented. This document records the
contract shape for a separate ADR review before implementation children. It
does not change the public Scala API, wire schema, or provider defaults.

## Request intent

A future request should express output intent explicitly and independently from
tool definitions:

- `LocalValidateOnly(schema, codecIdentity)`: request ordinary provider output,
  then parse and validate locally; do not imply provider-side enforcement.
- `NativeSchemaRequired(schema, codecIdentity)`: require a positively evidenced
  native mode for the exact endpoint/model/config key. If unsupported or unknown,
  refuse before dispatch; never silently downgrade.
- `NativeSchemaPreferred(schema, codecIdentity, fallbackBudget)`: use native mode
  only when positively evidenced; otherwise fallback is permitted only when the
  request explicitly enables it and supplies a finite attempt/token/cost budget.

The final public names/types are reserved for ADR review. The schema and codec
identity must be stable/redacted provenance references, not prompt contents or
credentials. Local validation is always required, including after a native mode
reports success.

## Fallback and repair semantics

Fallback is opt-in, bounded by the root admission/budget owner, and observable as
a distinct execution mode. Each extra inference is a separately admitted and
accounted attempt. Unknown usage or price stays unknown, never zero. A failure
classified as authentication, authorization, policy denial, configuration
refusal, or any pre-dispatch trust failure is terminal: it must not trigger a
direct-wire fallback or retry. Transport ambiguity is not automatically
retryable. Repair may repeat only model generation/validation; it must never
repeat a tool call or other mutation. Cancellation applies to every attempt and
the final status distinguishes local cancellation from any provider
acknowledgement.

## Capability identity and evidence

Capabilities are tri-state and keyed at minimum by adapter/wire version,
credential-free normalized endpoint identity, exact model ID, relevant request
configuration, and evidence provenance. Runtime configuration without current
positive evidence yields Unknown. No support is inferred from a provider brand,
endpoint suffix, common URL shape, model family, or tool-schema support.

Evidence reports offline stand contracts separately from live observations.
Live claims identify timestamp, exact model/config, test case, implementation
hash, response mode, local validation, usage completeness, and any refusal or
truncation. Credentials and raw secret-bearing prompts are excluded.

## Comparison and verdicts

The result matrix compares **native-schema**, **explicit parse/validate
fallback**, and **streaming-tools** as separate modes. It records request
semantics, endpoint evidence, valid/invalid cases, refusals/truncation,
tool-call correctness, cancellation behavior, attempts, token usage, known or
unknown cost, and local validator results. Streaming tools do not count as
structured final output. Human/model judge feedback is advisory; deterministic
validators and trusted workflow checks own pass/fail unless a reviewed trusted
policy explicitly gives a judge a distinct role.

## Migration and gates

Existing calls retain their current behavior by default. New intent must be
additive or explicitly versioned, and existing public compile examples and both
wire consumers must be updated in the ADR's implementation plan. Contract tests
use the Scala HTTP stands for both direct wires. Required cases cover exact
evidence-key lookup, Unknown/Unsupported refusal, invalid output, budgeted
fallback, 401/403 and policy no-fallback, incomplete usage, cancellation, and
no mutation replay. Any API/schema change is blocked until a separate ADR is
reviewed and accepted.
