<!-- Work record. Translated from the Russian original. -->

# RAI-011-SLICE: work record (OpenAI-compatible transport feasibility slice)

Status: done (TRANSPORT SLICE — not a complete provider: non-streaming only).
Date: 2026-10-03. Baseline: previous last-passing gate
artifacts/quality/20261003-220233 (RAI-009-B).

## Resulting behavior

Phase F (stretch): OpenAI-compatible Chat Completions as a product
`ModelBackend` over REAL local HTTP — no network, no cost, and NEVER
127.0.0.1:8081 (llama.cpp):

- `modules/provider-chat` (new module; build.sbt change owned by coordinator):
  `raider.provider.chat.OpenAIChatBackend` — JDK HttpClient,
  non-streaming `/chat/completions`; normalization to ModelEvents (Started →
  TextDelta/tool_calls → UsageObserved(priceKnown=false) → Finished); status
  mapping 401/403→RA-AUTH, 429→RA-RATE, 5xx→RA-UNAVAIL, other non-200 or
  unparseable body→RA-STREAM; apiKey is write-only (`toString` redacted, never
  appears in error details); interrupt cancels in-flight request
  (`fromCompletionStage→CF.cancel`); NO retries (AGENTS discipline); honest
  capabilities: streaming=Unsupported (SSE = RAI-012),
  cancellationAck=Unknown (CF cancel ≠ server ack);
- SCALA STAND (AGENTS/PLAN language policy: provider stands are Scala JDK
  HttpServer inside the module): `OpenAIChatStandSpec` starts a stand at
  127.0.0.1:0 (ephemeral port), always checks auth, and uses request-driven
  scenarios (by model name: ok/tool-model/boom/garbage + tool-role request gets
  final text). The Python mock
  `experiments/openai-chat-spike/harness/mock_gateway.py` remains a spike
  artifact; it is not needed for this slice (the Scala stand covers the same
  behavior more deterministically)—no integration with it was performed, and
  this is not a “skip”;
- Capstone: AgentLoop (Phase A) completes a REAL HTTP tool round: round1 =
  tool_calls from the stand, native tool, round2 = final text; provider usage
  is accounted as unpriced (not free), and the reservation reconciles.

Incidental determinism fix (repl-engine, coordinator-owned change): with the
new eighth module, the gates caught a load flake in a compiler test
("runtime failure..." received truncated driver output). Fixed by strengthening
reproducibility, WITHOUT weakening checks:
`Global / concurrentRestrictions += Tags.limit(Tags.Test, 2)` (build.sbt) and
`@@ TestAspect.sequential` on ReplEngineSpec (six in-process compilers no
longer compete for memory). Test assertions were not changed; gates passed
6/6 twice consecutively after the fix.

## Changed paths

```
c4a3a82f…  build.sbt (module + Tags.limit(Tags.Test, 2))
906085fc…  modules/provider-chat/src/main/scala/raider/provider/chat/OpenAIChatBackend.scala
22e1c9ea…  modules/provider-chat/src/test/scala/raider/provider/chat/OpenAIChatStandSpec.scala
7ee852de…  modules/repl-engine/src/test/scala/raider/repl/engine/ReplEngineSpec.scala (sequential aspect)
```

No existing product/contract was changed (only build.sbt and tests).

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Text roundtrip over HTTP (stand) | 4 events, usage unpriced, auth ok | OpenAIChatStandSpec |
| 401 → RA-AUTH (key does not leak into details) | pass | OpenAIChatStandSpec |
| 500 → RA-UNAVAIL; malformed body → RA-STREAM | pass | OpenAIChatStandSpec |
| Capstone: AgentLoop + real HTTP tool round | pass, unpricedAttempts=2 | OpenAIChatStandSpec |
| Transport test stability ×3 | 4/4, exit 0 ×3 | /tmp/f4.log, /tmp/f-run-{1,2}.log |
| Full sbt test | 138 tests, 0 failed, exit 0 | /tmp/full-test-F.log |
| Quality gates 6/6 ×2 CONSECUTIVELY (after determinism fix) | exit 0 ×2 | artifacts/quality/20261003-221154, 20261003-221229 |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderProviderChat/test"` ×3 | 0,0,0 | /tmp/f{4,-run-1,-run-2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (138) | /tmp/full-test-F.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261003-221{154,229}/ |
| `make quality-changed QUALITY_EXECUTE=1` (before fix, honestly reported) | exit 2, sbt-test fail | artifacts/quality/20261003-220946/ (not suppressed) |

## Limitations / obligations

- Streaming/SSE — RAI-012 (obligation); Anthropic Messages — RAI-013.
- Wire tool rounds: non-streaming tool_calls only; parallel tool_calls, usage
  in streaming chunks, retries/backoff, and endpoint probing are outside this
  slice.
- Integration with the Python spike mock was not performed (the Scala stand
  is sufficient; if needed, make a separate card with explicit stand config).
- Long-running cancellation cycle (stalled body, before-headers cancel) —
  CHAT-03, outside this slice (partially covered historically by the spike).

## Review and next task

No independent review was performed (coordinator session). Session closeout:
full run of all acceptances + board/obligations.
