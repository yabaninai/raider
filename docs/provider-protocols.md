<!-- Translated from Russian original. Key terms preserved as-is. -->

# Provider-neutral inference

Raider works fully without Yabanin. The MVP has two first-class wire kinds: `openai-compatible` and `anthropic-compatible`; a Yabanin endpoint can already be used through a compatible wire profile. The enriched `yabanin` kind is being implemented by RAI-037/038 as an optional integration with labels/session/accounting; the absence of Yabanin does not block the REPL, CI, tools, subagents, checks, or local budgets.

## 1. Choosing a connection

```toml
[providers.direct]
kind = "openai-compatible"
base_url = "https://your-endpoint.example/v1"
api_key_env = "RAIDER_DIRECT_KEY"

[providers.claude]
kind = "anthropic-compatible"
base_url = "https://api.anthropic.com/v1"
api_key_env = "ANTHROPIC_API_KEY"
api_version = "2023-06-01"

[providers.yabanin]
kind = "yabanin"
wire_protocol = "openai-chat"
base_url = "http://localhost:8082/v1"
api_key_env = "YABANIN_API_KEY"
session_key_mode = "disabled"

[models.fast]
provider = "direct"
model = "owner-configured-model"

[models.review]
provider = "claude"
model = "owner-configured-model"

[profiles.direct.models.fast]
provider = "direct"
model = "owner-configured-model"

[profiles.direct.models.review]
provider = "direct"
model = "owner-configured-review-model"

[profiles.claude.models.fast]
provider = "claude"
model = "owner-configured-model"

[profiles.claude.models.review]
provider = "claude"
model = "owner-configured-review-model"
```

The M0 generic profile for Yabanin uses `kind = "openai-compatible"` and the gateway URL/key; the enriched TOML block with `kind = "yabanin"` above is a beta contract, and session mint/labels flags must not be claimed to work before RAI-037/038.

Provider name is a local ID; model is an explicit endpoint model ID/alias. A preset role (`scout`, `reviewer`) selects a model role, which is resolved through config. One workflow may use different providers; the root ledger is shared. The profile is selected explicitly (`--profile direct`/`claude`/`yabanin`) or by a trusted default in config. There is no automatic failover from Yabanin to a direct provider, account change, or protocol change after 401/403. An opt-in fallback policy would be separate configuration with accounting provenance, not the MVP default.

`profile` and `provider` are different entities. A profile defines role bindings/limits/policy defaults; a provider is a connection. Resolver: explicit Agent provider/model → selected `profiles.<name>.models.<role>` → global `models.<role>`; missing role is an error, with no guessing of the model ID. `.model("review")` selects a role, so it uses the selected profile binding; an explicitly set provider/model pair bypasses it while remaining within mandatory policy. The profile examples above override fast/review; other preset roles in use must also have bindings. The yabanin profile is configured similarly through a gateway provider. Config inheritance does not expand mandatory capabilities or budgets. The doctor/launch report shows the resolved role/provider/model before spend.

The TOML examples define the target schema; specific versions/model IDs are confirmed by implementation fixtures and deployment. Official Anthropic auth/version headers are the default protocol profile; compatible endpoints may explicitly choose an allowlisted auth strategy (`x-api-key`/Bearer/none-local). Arbitrary secret headers use env references, with no literals in tracked config. Insecure remote HTTP is disabled by default; loopback HTTP is allowed for local gateways.

## 2. Shared contract and standalone operation

Core `ModelBackend` accepts a normalized conversation/tools/output constraints and returns normalized events/usage/error. The wire adapter translates messages and tool rounds; the runtime loop/cancellation/budgets do not know the specific endpoint. The Yabanin decorator is not part of the default `ModelBackend` constructor. Pricing/usage source is a separate SPI.

`ModelBackend` is one model attempt. `AgentBackend` is an entire agent execution (native loop or external pi/OpenCode engine). The wire adapter is not an agent engine; an external backend is not run through the native loop again. Exact interfaces: [runtime contracts](runtime-contracts.md).

Standalone operation records attempts, provider/model, tokens, latency, costs when a price catalog is configured, and uncertain/unpriced status when a price is missing. Server accounting/session keys are an optional capability, not a required generic API interface. A hard USD cap requires bounded price/output and conservative reservation; without them, an explicit token/attempt/time cap is possible, but no fabricated monetary guarantee. Model registry discovery is optional: a compatible API without `/models` works with an explicitly configured model.

`doctor` does not generate tokens by default; it reports endpoint/config/capabilities as `supported|unsupported|unknown`. Capabilities are defined by a verified profile and versioned tests, or, when needed, an explicit capped inference probe. Do not infer tools/stream/JSON support from a single HTTP 200. Required capability is checked before root dispatch.

Base URL contract: the URL contains the API prefix, and the adapter adds a relative path (`chat/completions` or `messages`). Custom prefixes are preserved; a duplicate `/v1` is not introduced. Error redirects to another origin do not forward auth. TLS/proxy/timeouts/bounded bodies are shared; shared SSE framing does not mix provider-specific state machines.

## 3. OpenAI-compatible MVP

The first wire is Chat Completions, streaming/nonstreaming, user/assistant/tool rounds, tool call IDs, and usage. Responses is a future independent wire adapter, not a commitment of every compatible server. Nonstandard fields/usage capabilities are feature-profiled; unknown optional fields are safely ignored, while unknown required semantics are not disguised as support.

Transport fixtures: fragmented UTF-8/SSE/CRLF, null content/tool-only output, interleaved tool indices, complete argument assembly, usage-only chunks, finish reason, `[DONE]`, malformed/oversized data, unexpected EOF, structured error, retry-after, and cancellation cleanup. A finish without final usage can be execution-complete/accounting-partial. A provider attempt retry is safe only with known pre-dispatch/explicit retry policy; paid partial attempts are accounted for, and tools are not silently re-executed.

## 4. Anthropic-compatible MVP

Messages wire `/messages`: top-level system instructions, content blocks, tool_use/tool_result matching IDs, required max_tokens, and explicit version/auth headers. Do not convert tool history to plain text: correlation, error flags, and block order must be preserved. Server-managed tools/thinking/prompt-cache/multimodal are capability-specific extensions; a required unsupported feature is rejected, not silently dropped.

SSE state machine: message_start → content_block_start/delta/stop by index → message_delta → message_stop; ping/error are handled separately. Input JSON fragments are assembled into a complete tool block; callbacks with partial args do not execute tools. Usage updates are normalized as cumulative, without repeatedly adding totals. An unknown optional event is recorded; unknown content semantics are not turned into a successful response string. Thinking/signature blocks are not logged by default; when the feature is used, the allowed protocol round-trip form is preserved without requiring disclosure of reasoning. Basis: [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), [tool definitions](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools).

Fixtures include multiple tool blocks, a tool error round, stop_reason tool_use/end_turn/max_tokens/refusal, incomplete blocks, an error after HTTP 200, cumulative usage, fragmented Unicode, oversized input, idle/deadline, and cancel. Max-tokens truncation is not considered complete task output without a declared policy. JSON output schema validation remains available at runtime even if the endpoint does not support native structured-output mode.

## 5. Independence gates

1. Clean environment without `YABANIN_*`, no Yabanin module/service: CI and REPL acceptance on both local mock HTTP protocols.
2. One string/typed schema/tool round/fanout/timeout scenario produces equivalent normalized semantics on both wires.
3. An explicit Anthropic endpoint receives no OpenAI/Yabanin headers; direct OpenAI sends no session mint requests.
4. Unknown price remains unknown; observed usage is distinguished from estimates.
5. Mixed-provider children are accounted for in one root; each attempt records the actual provider/role.
6. An unavailable doctor registry does not block an explicit model; unsupported required tools are blocked before spend.
7. The optional Yabanin adapter passes a separate contract/stand profile; the base release does not require deployed Yabanin.
