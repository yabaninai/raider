# SPIKE-OAI-01: OpenAI-compatible Chat wire spike (owner-directed)

Feasibility prototype for future RAI-011/012: a real Scala client
(JDK HttpClient + ZIO adapter + zio-json codecs) against a local mock gateway
implementing `POST /v1/chat/completions`. Not a product module.

## Run

```sh
sh experiments/repl-spike/run_spike.sh        # once: toolchain + classpaths
sh experiments/openai-chat-spike/run_spike.sh
```

Exit 0 ⟺ 9/9 checks (F0 remediation added malformed-SSE, premature-EOF,
cancel-before-headers). Evidence: `artifacts/oai-spike/` (override via
`RAIDER_OAI_EVIDENCE`; summary.json, scenario logs, source manifest,
REPL transcript). Fault injection: `run_spike.sh --self-test-fault-injection`.

## What was demonstrated

| ID | Scenario |
| --- | --- |
| OAI-01 | non-streaming: JSON roundtrip, usage |
| OAI-02 | SSE streaming == non-streaming (each SSE frame is fragmented across two HTTP chunks) |
| OAI-03 | tool calls: id/name/args are assembled by index from deltas; partial args are not executed |
| OAI-04 | 401 → `[classified] ProviderAuth`, exit 10 |
| OAI-05 | fiber interrupt during a stream closes the body; the gateway sees the disconnect |
| OAI-06 | the same client streams from the real REPL (`replAsk`) |
| OAI-07 | malformed SSE payload → `[classified] StreamProtocol`, exit 21 |
| OAI-08 | premature EOF/truncated body without a terminal event → exit 21 |
| OAI-09 | cancel before headers cancels the request (`cf.cancel`); the body is not opened |

## Boundaries

The mock gateway uses Python stdlib; there are no TLS/redirect/429/5xx scenarios
and no real endpoints. Product implementation is RAI-011 (HTTP Chat) and RAI-012
(SSE state machine), with their own transport/security profiles. Record:
`docs/work/records/SPIKE-OAI-01.md`.
