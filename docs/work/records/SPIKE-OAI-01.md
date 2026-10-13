<!-- Work record. Translated from the Russian original. -->

# SPIKE-OAI-01: work record (owner-directed)

Status: **changes-required** per independent review on 2026-10-03 at the end of
this record. Original author status: done (review pending). Date: 2026-10-02
(UTC). Baseline: the same initial untracked tree as RAI-001; RAI-001 was not
changed (its suite was not rerun—the RAI-001 spike has no product files, and
the classes compile alongside it in ignored artifacts/spike/classes).

Purpose: the owner directed priority to an OpenAI-compatible gateway (“for now,
just OpenAI-compatible”). This is a feasibility spike for RAI-011/012, not
completion of those cards: product modules `modules/provider-chat/**` and
transport profiles have not been created.

## Resulting behavior

- Real Scala client for OpenAI-compatible Chat: JDK HttpClient + ZIO
  acquire-release adapter + zio-json derived wire schemas.
- Non-streaming roundtrip; SSE streaming with per-frame interruption
  (interruption at line boundaries); tool-call deltas assembled by index,
  partial arguments are not executed; 401 classified as ProviderAuth (exit
  10); fiber interruption mid-stream closes body—the mock gateway records the
  disconnect; the same client is used from the real REPL (`replAsk`).
- Local mock gateway (Python stdlib, 127.0.0.1): Bearer auth, OpenAI-style error
  body, chunked SSE with each frame intentionally split into two HTTP chunks
  (reassembly is checked), scenarios selected through `X-Spike-Scenario`.

## Changed paths

- `experiments/openai-chat-spike/OpenAiCompatSpike.scala` (new)
- `experiments/openai-chat-spike/harness/mock_gateway.py`,
  `.../oai_spike_harness.py` (new)
- `experiments/openai-chat-spike/run_spike.sh`, `README.md` (new)
- `docs/work/board.md`, this record (updated)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| OAI-01 non-stream | PASS exit=0, final + usage total=26 | artifacts/oai-spike/oai-01-ask.log |
| OAI-02 stream≡non-stream (fragmented frames) | PASS exit=0, sawDone=true | artifacts/oai-spike/oai-02-stream.log |
| OAI-03 tool args assembled, not executed | PASS args={"path":"README.md"} | artifacts/oai-spike/oai-03-tool.log |
| OAI-04 auth 401 classified | PASS exit=10 | artifacts/oai-spike/oai-04-auth.log |
| OAI-05 cancel midstream | PASS cleanly=true, gateway_saw_disconnect=True | artifacts/oai-spike/oai-05-cancel.log, gateway-disconnect.log |
| OAI-06 REPL streaming | PASS `answer: String = "…"` in transcript | artifacts/oai-spike/repl-transcript.txt |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `java -cp <repl+headless> dotty.tools.dotc.Main -classpath <cp> -d artifacts/spike/classes experiments/openai-chat-spike/OpenAiCompatSpike.scala` | 0 | artifacts/oai-spike/compile.log |
| `sh experiments/openai-chat-spike/run_spike.sh` | 0 | artifacts/oai-spike/run-spike.log (6/6) |

Versions are the same as RAI-001 (Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0,
JDK 20.0.1).

## Limitations / blockers

- Mock gateway; no TLS/redirects/429/5xx/multiple choices, no real endpoints or
  keys—these are all covered by the RAI-011/012 transport/security contracts.
- One HTTP chunk contains parts of one SSE frame; broader fragmentation corpus
  (UTF-8 split by bytes, CRLF, keepalive) belongs in RAI-012 fixtures.
- Interruptibility was checked at SSE line boundaries; a blocking readLine
  inside a line is not interrupted (consistent with the “safe boundary” plan).
- No independent review was performed.

## Review and next task

Review: pending. The product path did not change: RAI-003 → RAI-002 → RAI-004 →
… → RAI-011/012; spike findings (send/use path, per-line interruptibility,
zio-json wire codecs) carry into those cards as verified approaches.

## Independent review 2026-10-03

Current verdict: **changes-required**. The original author report above is
preserved as history. [Independent review](../../reviews/2026-10-03-readiness.md)
confirmed a fresh compile and reproduced a false-green launcher (producer17→
suite0). The HTTP 6/6 result was not independently repeated; cancellation/
terminal-event/tool-round guarantees require separate fixtures. These
approaches have not yet been accepted as production adapters.
