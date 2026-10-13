<!-- Work record. Original in Russian; key results in English below. -->

# RAI-012-SLICE: work record (OpenAI SSE streaming state machine)

Status: done (slice: framing+semantic parser+streaming backend; full
RAI-012 acceptance remains a milestone obligation). Date: 2026-10-04.
Baseline: previous last-passing gate artifacts/quality/20261003-221636.

## Resulting behavior

The RAI-012 slice provides SSE streaming as a product ModelBackend over
REAL local HTTP (Scala stand, loopback:0; no network, payment, or port 8081):

- `raider.provider.chat.stream.SseFramer` — incremental SSE framer over BYTES (0x0A does not occur within multi-byte
  UTF-8, so framing before UTF-8 decoding is safe); state (partial line +
  accumulated data lines) lives in FIELDS — events split at ANY chunk boundary
  (including between a data \n and the empty-line terminator) are not lost
  (SSE-01); multi-line data are joined with "\n"; comments `:` and
  event:/id:/retry: are ignored; maxFrameBytes limit yields typed
  StreamProtocol (no unbounded buffer); framer reuse for Anthropic is provided
  for (semantics are separate);
- `raider.provider.chat.stream.ChatChunkParser` — semantic chunk state machine: delta.content → TextDelta immediately;
  tool_calls accumulate BY INDEX (interleaved), ToolCallReady is emitted ONLY
  on finish_reason, in index order (partial arguments are never executable,
  §8); first finish_reason wins; [DONE] confirms end; EOF-before-finish and
  DONE without finish_reason yield typed StreamProtocol; usage-only chunks →
  UsageObserved (priceKnown=false — unpriced, not free);
- `raider.provider.chat.stream.OpenAIChatStreamingBackend` — POST with
  `"stream":true, stream_options.include_usage`; status mapping as in the
  non-streaming slice; body closes on ANY exit (acquire-release in the CALLER's scope — the
  ZStream[Scope,…] contract); idle deadline races the read with a timer (the
  losing blocked read is interrupted and its IOException is consumed) → typed
  DeadlineExceeded; interrupt closes the body (SSE-03); no retries by design;
  capabilities are truthful:
  streaming=Supported, cancellationAck=Unknown.

Four real integration defects/traps were found and closed with fixtures
(each reproduced by a test, then fixed):

1. **Lost frames at chunk boundaries**: dataLines/hasData were LOCAL in drain() — a frame split between a data \n
   and an empty line disappeared. Fix: line-parsing state is held in framer
   fields; SSE-01 partition property (splits of 1,2,3,5,7,13,64,1024 bytes →
   identical events) catches this class of bug.
2. **`ArrayBuffer[Byte].indexOf('\n', pos)`** — Char does not match Byte; indexOf always returns −1 (the framer “saw” no
   lines). Fix:
   `'\n'.toByte`.
3. **Eager finalizer evaluation**: `ZStream.fromZIO(ZIO.fromEither(
   parser.finish()))` evaluated finish() when the stream was CONSTRUCTED — before the first chunk
   → permanently “truncated”. Fix: construct the finalizer inside
   ZStream.unwrap (truly lazy).
4. **Idle-deadline race with a blocking read**: `.timeout` over
   attemptBlockingIO returned raw IOException instead of typed deadline. Fix: `race` the read with
   a timer (interrupt the losing side and discard its error). Also replace
   `readNBytes(8192)` with `read(buf)` (`readNBytes` blocks until exactly
   8192 bytes/EOF — the wrong API for incremental SSE).

The spec also covers stand scenarios stream-text/tool (round 2 for tool
role)/truncated/garbage/parked/silent; each frame is fragmented into 2-byte
chunks with flush (real chunked HTTP).

## Changed paths

```
e8ae72e2…  modules/provider-chat/src/main/scala/raider/provider/chat/stream/SseFramer.scala
679b0808…  modules/provider-chat/src/main/scala/raider/provider/chat/stream/ChatChunkParser.scala
a0bca07e…  modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala
adfe616d…  modules/provider-chat/src/test/scala/raider/provider/chat/stream/OpenAIChatStreamSpec.scala
```

Only new files under `modules/provider-chat/**/stream/**` (RAI-012 allowed
paths); the verified non-streaming slice and contracts were not touched.

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| SSE-01: every chunk partition → identical output/usage/tool IDs | pass (partition property ×8 sizes) | OpenAIChatStreamSpec |
| SSE-02: EOF-before-finish / DONE without finish / invalid JSON / oversize → typed RA-STREAM | pass | OpenAIChatStreamSpec |
| SSE-02: ToolCallReady ONLY on finish, index order, partial arguments not executable | pass | OpenAIChatStreamSpec |
| Integration: fragmented SSE over HTTP → text+usage+Finished | pass | OpenAIChatStreamSpec |
| SSE-03: cancel mid-stream → body closed (stand-side ref), Uncertain accounting retained | pass | OpenAIChatStreamSpec |
| AgentLoop completes HTTP streaming tool round (2 rounds) | pass | OpenAIChatStreamSpec |
| Stability ×3 | 17/17, exit 0 ×3 | /tmp/s28.log, /tmp/st-run-{1,2}.log |
| Full sbt test | 151 tests, 0 failed, exit 0 | /tmp/full-test-R012.log |
| Quality gates 6/6 ×2 consecutively | exit 0 ×2 | artifacts/quality/20261004-04{3918,4001}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderProviderChat/test"` ×3 | 0,0,0 | /tmp/s28.log, /tmp/st-run-{1,2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (151) | /tmp/full-test-R012.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261004-04{3918,4001}/ |

## Limitations / obligations

- Full RAI-012 card (sanitized corpus matrix, interleaved multi-choice edge
  cases, protocolVerdict escalations) remains a milestone obligation; this
  slice covers the SSE-01..03 acceptance skeleton.
- Anthropic reuse of the framer is checked in RAI-013.
- SSE idle timeout is inter-chunk only: initial connect uses the same
  callTimeoutMs (JDK client); a separate per-read budget can be added if needed.
- HTTP chunked + keepalive comments are simplified in the stand versus a real
  provider; the sanitized corpus matrix remains an obligation.

## Review and next task

No independent review was conducted (coordinator session). Next ready cards: RAI-013 (Anthropic Messages over the framer), child
delegation (§11) over JobManager+AgentLoop, RAI-022 (headless CLI).
