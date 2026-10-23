# RAI-200 / RAI-201 local process protocol transcript corpus

This is a proposed, language-neutral acceptance corpus, not executable adapter
code or evidence of a running implementation. Each JSON fixture is a transcript
scenario: `frames` gives complete wire records (as JSON objects); `transport`
describes byte delivery/closure; `requiredBehavior` is the assertion for the
future Scala child-process stand. Implementations should serialize `frames` as
UTF-8 JSON Lines and must not rely on write/read chunk boundaries. The corpus
implements the contract proposed by [ADR-037](../../adr/ADR-037-local-process-protocol.md).

| Fixture | Scenario | Required behavior |
| --- | --- | --- |
| `normal-handshake-run-terminal.json` | Handshake, capability negotiation, single submit, acceptance, observations, terminal with artifacts | Negotiated schema/capabilities are explicit; event sequence is monotonic; artifacts are references with digest/size/provenance; terminal status and process exit agree. |
| `cancel-mid-run.json` | Cancel during an active root, acknowledgement followed by terminal | Ack `accepted` is not completion; owned cleanup is reported separately; exactly one terminal result settles the race. |
| `oversize-frame.json` | Submit frame above 1,048,576 payload bytes before LF | Reject before JSON decoding/dispatch; no root/provider/tool effect; emit a bounded error if writable, then nonzero protocol exit. |
| `fragmented-frames-unicode.json` | UTF-8 frame split across arbitrary byte chunks, with multibyte and wide characters and U+2028/U+2029 data | Incremental framing/decoding reconstructs the exact strings; no replacement, delimiter confusion, or premature dispatch. |
| `slow-reader.json` | Reader pauses while progress frames accumulate | Queue remains within both limits; progress may be coalesced with explicit sequence gap/count; critical responses and terminal reserve are preserved or the root is cancelled within bounded cleanup. |
| `eof.json` | EOF at clean boundary before submit, and separately EOF after dispatch / mid-frame | No root before submit; after dispatch stop admission and clean up; partial frame is never dispatched; no false success or retry. |
| `broken-pipe.json` | Client closes protocol output while child writes observation/terminal | Detect write failure, stop admission, cancel owned work, finish bounded cleanup; do not report success or automatically resubmit. |
| `death-without-terminal.json` | Child dies after accepted/progress and before terminal frame | Consumer records uncertain/incomplete, inspects existing artifacts/recovery data read-only, and does not auto-resubmit; request ID is not treated as completion proof. |
| `duplicate-submit.json` | A second submit arrives in the same invocation | Refuse it without allocating another root or implying idempotency. |
| `refusal-and-secret-fields.json` | Unknown major version and secret-bearing field | Refuse before dispatch and never echo/log secret values. |

Oversize bytes are specified by a deterministic construction recipe instead of
checking in a megabyte of repetitive text. The fragmented fixture lists byte
chunks as hex so chunk boundaries, including within UTF-8 sequences, are
unambiguous. EOF has two subcases in one fixture. `terminal` appears at most
once; death-without-terminal deliberately contains no terminal record.
