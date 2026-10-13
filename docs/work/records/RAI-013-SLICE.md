<!-- Work record. -->

# RAI-013-SLICE: work record (Anthropic-compatible Messages + streaming)

Status: done (slice: native Messages wire non-streaming + SSE streaming;
the full ANTH-01..03 corpus remains a milestone obligation). Date: 2026-10-04.
Baseline: previous last-passing gate artifacts/quality/20261004-044001
(RAI-012-SLICE).

## Resulting behavior

The RAI-013 slice is the second first-class wire (`anthropic-compatible`) over
real local HTTP (Scala stand; no network/cost/8081):

- `raider.provider.anthropic.AnthropicMessagesBackend` — `/v1/messages`,
  headers `x-api-key` + pinned `anthropic-version: 2023-06-01` (Config
  apiKey write-only, toString redacted), required `max_tokens` from
  ModelRequest; TWO transports in one class (non-streaming ofString +
  SSE ofInputStream), identical status mapping (401/403→AUTH,
  429→RATE, 5xx→UNAVAIL, other non-200→StreamProtocol with a hint about
  version/parameters); error bodies are NOT included in details alongside the key;
- NATIVE semantics without fake OpenAI conversion (provider-protocols §4):
  responses → content blocks (text / tool_use with input-JSON); tool results
  from the cycle (role "tool", envelope `{"tool_call_id","output"}`) are
  translated into a NATIVE `tool_result` block in a user message — correlation
  preserved, NOT plain text (full block history = structured message contract
  obligation); tool output rides as a string payload inside
  tool_result.content;
- `AnthropicStreamParser` — Anthropic SSE state machine:
  message_start(usage.input) → content_block_start/delta/stop BY INDEX →
  message_delta(cumulative output_tokens — REPLACEMENT, never summed) →
  message_stop; input_json_delta accumulates until content_block_stop —
  ToolCallReady only on block_stop (partial arguments are not executable);
  error events after 200 → typed ProviderUnavailable with anthropic type;
  ping is ignored; unknown CONTENT (block/delta types) → typed fail with the
  type name; unknown OPTIONAL events are ignored; EXACTLY ONE
  UsageObserved at finish (ANTH-03: no double counting);
- framer reuse WORKED as intended: SseFramer from
  provider-chat (dependency provider-anthropic → provider-chat, authorized by
  provider-protocols §4 “reusable framing”), partition-property on
  anthropic-corpus passes;
- max_tokens truncation is honestly passed through as stop_reason (incomplete
  output — caller policy).

Found and fixed with fixtures (reproduced by a test, then resolved):

1. **ErrWire field**: the anthropic error object carries `type`, not
   `error_type` — the error type was lost as “unknown”; fixtures caught it and
   the issue was fixed.
2. **Tool-result envelope**: `output` is arbitrary JSON (an object), not a
   string → InputValidation on every tool round. Fix: output: Json.
3. **Stand tool-round detection**: tool_result is a user message with a block;
   the "tool" role does not exist on the wire → the stand looked in the wrong
   place → endless tool rounds until maxAttempts (RA-LBUDGET). Fix: detect
   tool_result in the content.

## Changed paths

```
c8642421…  build.sbt (raiderProviderAnthropic module, root aggregate)
8ce3a8aa…  modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicMessagesBackend.scala
6e092e0f…  modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicStreamParser.scala
d21acf3b…  modules/provider-anthropic/src/test/scala/raider/provider/anthropic/AnthropicMessagesSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| ANTH-01 non-streaming: text + usage unpriced + end_turn | pass | AnthropicMessagesSpec |
| ANTH-01 tool round: NATIVE tool_use ×2 → loop executes → round2 → final | pass | AnthropicMessagesSpec |
| ANTH-01 streaming: fragmented UTF-8 (Unicode-escaped exact fixture: "\u043f\u0440\u0438\u0432\u0435\u0442 ✓", multibyte split across HTTP chunks) | pass | AnthropicMessagesSpec |
| ANTH-01 streaming tool round: input_json_delta in parts, ToolCallReady on block_stop | pass | AnthropicMessagesSpec |
| ANTH-03 cumulative usage WITHOUT double counting (5→12 → exactly one UsageObserved(15,12)) | pass | AnthropicMessagesSpec |
| ANTH-02 error-after-200 → RA-UNAVAIL with anthropic type; truncated → RA-STREAM | pass | AnthropicMessagesSpec |
| ANTH-02 invalid key → RA-AUTH; invalid version → typed 400 | pass | AnthropicMessagesSpec |
| ANTH-02 unknown block/delta type → typed, type name in details | pass | AnthropicMessagesSpec |
| ANTH-03 cancel between block_stop and message_stop → body closed, Uncertain held | pass | AnthropicMessagesSpec |
| Framer reused: partition-property on anthropic corpus (k=1,3,8,64,4096) | pass | AnthropicMessagesSpec |
| Stability ×3 | 11/11, exit 0 ×3 | /tmp/an9.log, /tmp/an-run-{1,2}.log |
| Full sbt test | 162 tests, 0 failed, exit 0 | /tmp/full-test-R013.log |
| Quality gates 6/6 ×2 IN A ROW | exit 0 ×2 | artifacts/quality/20261004-05{1050,1133}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderProviderAnthropic/test"` ×3 | 0,0,0 | /tmp/an9.log, /tmp/an-run-{1,2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (162) | /tmp/full-test-R013.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261004-05{1050,1133}/ |

## Limitations / obligations

- Tool DEFINITIONS are not sent on the wire (ModelRequest is flat; translating
  ToolRegistry → tools/input_schema is a structured message contract, separate
  card) — tool_use in RESPONSES works natively.
- Full block history (text/tool_use order in PREVIOUS turns) is a structured
  message contract obligation; currently the loop turn uses text messages.
- system parameter, thinking/signature blocks, server-managed tools,
  multimodal are outside this slice (unsupported → typed fail, not silent drop).
- Full ANTH corpus (sanitized matrix, tool error round, refusal) is a card
  milestone obligation.

## Review and next task

No independent review was performed (coordinator session). Ready next:
child delegation (§11) on JobManager+AgentLoop; RAI-022 (headless CLI/bundle);
RAI-014/015 tools in the loop scenario.
