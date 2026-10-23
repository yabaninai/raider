# Provider and structured-output capability audit (RAI-202)

Status: source audit plus offline Scala HTTP-stand evidence at baseline
`71d3df0697fcef02bbab6837df56542a2331ea8e`. The audit does not infer support
from a provider label or URL shape. It does not make live/paid requests. Evidence
below separates source facts, behavior exercised by local stands, and unknown
upstream/model-specific behavior.

## 1. Findings

`ModelCapabilities` in `modules/core/.../backend.scala` currently reports only
`streaming`, `tools`, and `cancellationAck`, each as Supported/Unsupported/
Unknown. It is an instance-level declaration, not a registry keyed to an
endpoint/model/config evidence record. `ModelRequest` contains model, messages,
max output tokens, and tool definitions. It has no response-format/schema mode,
fallback policy, validation budget, or provenance field. The same backend
instance can be asked for different model strings, so its static capabilities do
not establish per-model support.

| Capability / behavior | OpenAI-compatible Chat Completions | Anthropic Messages | Evidence and status |
| --- | --- | --- | --- |
| Native constrained final JSON/schema output | **Unsupported by the typed request contract and absent from the normal encoder path**: the encoder sends model/messages/max_tokens/tools only; no typed `response_format`/JSON-schema mode. `RAIDER_OPENAI_EXTRA_BODY` is an untyped escape hatch that can append arbitrary JSON, but provides no capability evidence or guarantee. | **Unsupported by current request shape**: encoder sends model/max_tokens/messages/tools/stream; no output-format schema mode | `ModelRequest` and both `encodeRequest` implementations. No native-schema negotiation in the typed contract or normal encoder path. This says nothing about upstream support in general. |
| Tool parameter schema on wire | **Supported as tool schema serialization**: each tool parameters schema placed under `function.parameters`; general schema validity/endpoint acceptance is not proven by encoding | **Supported with pre-send validation**: each definition maps to native `input_schema`; empty/invalid/non-object JSON fails locally | OpenAI `Wire.encodeRequest`; Anthropic `encodeTools`; `AnthropicToolDefinitionsSpec` and `CrossWireToolParitySpec`. Tool schemas are not constrained final-output schemas. |
| Local codec/schema validation of arbitrary final output | **No general typed final-output validator in ModelRequest/AgentLoop**. Final text only checked nonempty and reserved-marker-free | Same | `AgentLoop.validateFinal`; `Codec`/typed workflow codecs exist elsewhere but are not connected to a general provider output schema request here. |
| Parse/validate/repair fallback | Plan mode parses `PlanProposal` locally and can make bounded, billable repair attempts; ordinary `runText` does not. This is application-level prompt/parse/validate, not a provider capability fallback | Same shared AgentLoop, wire-independent | `AgentLoop.runPlan`, `PlanProposal`, `PlanMode`; repair re-enters normal admission. No generic schema fallback mode/contract yet. |
| Non-streaming text/tools | **Supported** | **Supported** | `OpenAIChatStandSpec`; `AnthropicMessagesStandSpec`; captured request/response and full tool round. |
| Streaming text | **Unsupported on `OpenAIChatBackend`**; separate `OpenAIChatStreamingBackend` is implemented | **Supported only when constructed with `streaming=true`**; non-streaming instance reports Unsupported | `OpenAIChatStreamingBackend`, `AnthropicMessagesBackend.capabilities`; corresponding local specs below. Capability is constructor/config-instance-specific. |
| Streaming tool calls | OpenAI streaming implementation exists; deltas are assembled into ready tool calls. End-to-end stand evidence required for exact guarantee; no general schema-output claim | Anthropic parser emits tool-use deltas and completed calls; stand tests exercise fragmented arguments/tool round | `OpenAIChatStreamSpec`; `AnthropicToolDefinitionsSpec` and streaming stand cases. “Streaming tools” means tool-call events, not native final JSON mode. |
| Cancellation | Both advertise `cancellationAck=Unknown`. OpenAI cancellation of `CompletableFuture` is not remote acknowledgement. Anthropic body is scoped/closed and interruption stops local consumption, not evidence the upstream stopped work/metering | Same | Backend source comments and `ModelCapabilities`; no upstream cancellation acknowledgement wire contract. |
| Usage and price/cost | Response usage is optional; if present token counts are emitted with currency=None and `priceKnown=false`. Missing usage is not synthesized | Same for non-stream; SSE parser reconciles cumulative usage and emits observed values; missing fields remain absent | Both stand specs; Anthropic parser cumulative-overwrite behavior. Usage is not a known price and AgentLoop event telemetry still records usage unknown at its event seam. |
| Model identity | Request carries caller-provided model string; response `id` becomes `AttemptId`. Returned model field is not used as resolved model identity | Same | Both response decoders; event identity is provider attempt/message ID, not verified model identity. No endpoint/model/config provenance snapshot is emitted. |
| Authentication/policy errors | HTTP 401/403 -> ProviderAuth; no retry/fallback in backend | HTTP 401/403 -> ProviderAuth; no retry/fallback in backend | Local stands exercise auth failures; status mapping source. An output fallback must not run after these failures. |
| Cross-wire/tool parity | Request/response normalization and tool round exist | Native `tool_use`/`tool_result` semantics preserved | `CrossWireToolParitySpec`; test is local-fixture parity, not model-quality equivalence. |

## 2. Evidence scope and limits

The Scala JDK `HttpServer` stands are deterministic local fixtures. Relevant
cases include `OpenAIChatStandSpec`, `OpenAIChatStreamSpec`,
`AnthropicMessagesStandSpec`, `AnthropicMessagesSpec`,
`AnthropicToolDefinitionsSpec`, and `CrossWireToolParitySpec`. They verify the
configured requests, headers, codec/event mapping, auth/error handling, tool
rounds, and observed fixture usage. They do not contact a paid endpoint, prove
that arbitrary OpenAI-compatible or Anthropic-compatible servers accept all
schema dialects, or establish support for any untested model/configuration.

The concrete evidence key required for a future capability claim is a tuple of
wire/adapter implementation and version, normalized endpoint identity (scheme,
host, effective API path; credentials excluded), exact model identifier,
relevant non-secret request/config options, and test/observation provenance
(fixture or live, date, case/result, implementation hash). A family name or
common `/v1` URL is insufficient. Live evidence must be explicitly separate from
offline contract evidence; missing/expired evidence yields Unknown.

## 3. Proposed comparison matrix

For each output schema and evidence key, record separate rows for:

1. **Native-schema**: schema/mode actually transmitted and accepted by the
   endpoint/model, provider response/refusal/truncation behavior, local codec
   validation outcome, and usage observed.
2. **Explicit fallback**: prompt instruction plus local parse/codec validation;
   number of extra model attempts, token/cost reservation and actual usage,
   repair exhaustion/error, and validation outcome. Never label prompt-only
   instructions as native enforcement.
3. **Streaming-tools**: tool-definition serialization, delta assembly,
   completion/correlation, validation before dispatch, and cancellation at
   header/body/fragment boundaries. Tool schema support remains distinct from
   final structured-output support.

Report supported/unsupported/unknown independently per mode and evidence key.
Also record validity against the same local codec; schema acceptance alone is
not output correctness. An advisory judge may provide qualitative feedback, but
cannot turn invalid output into a pass or replace deterministic workflow
verifiers. It is advisory unless trusted workflow policy explicitly assigns a
separate verdict role.

## 4. Confirmed gaps and proposed implementation children

No product implementation is authorized by RAI-202. The implementation packet
must follow a separately reviewed public contract decision. Candidate scoped
children, subject to coordinator registration and estimate:

| Candidate child | Gap / bounded result | Required evidence |
| --- | --- | --- |
| Provider request schema-mode and capability provenance | Add explicit output-schema request semantics and evidence-keyed per endpoint/model/config status; preserve existing default behavior | Both-wire encoding, no-send preflight, supported/unsupported/unknown and provenance tests |
| Shared structured-output validation/fallback | Typed codec validation, explicit NativeOnly / FallbackAllowed / LocalValidateOnly intent, attempt/token/cost ceilings, bounded repairs | Invalid JSON/schema, repair budget exhaustion, auth/policy no-fallback, cancellation and no mutation replay |
| Streaming capability contract | Distinguish text stream, streaming tool calls, cancellation request and remote acknowledgement | Both Scala HTTP stands: fragmented JSON/Unicode/tool args, body stalls, interruption and honest ack status |
| Usage/model evidence reconciliation | Bind observed model, attempt identity, partial/missing usage and priced/unknown status without fabricated values | Fixture reconciliation, incomplete usage, no-zero-cost, config/model identity checks |

The estimates are deliberately not set here: coordinator must register child
cards with allowed paths and remaining allocations before implementation.
