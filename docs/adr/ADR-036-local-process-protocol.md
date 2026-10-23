# ADR-036: Bounded local process protocol

Status: **Proposed** (RAI-200; protocol contract for independent review, not an
implemented adapter). Date: 2026-10-23. This ADR follows
[ADR-035](ADR-035-cross-ecosystem-workflow-boundary.md),
[cross-ecosystem plan §4](../cross-ecosystem-plan.md#4-calling-raider-from-other-languages),
and the existing [CI runtime contract](../ci-runtime.md). It is intentionally
language-neutral: callers use ordinary process and UTF-8 JSON facilities. The
implementation remains Scala 3 + ZIO and executes through the existing runtime.

No command name, schema/API implementation, SDK, daemon, remote endpoint, new
scheduler, or durable-resume capability is declared implemented by this
proposal. The fixture corpus in
[`../fixtures/process-api/`](../fixtures/process-api/) is the proposed
acceptance corpus for RAI-201.

## 1. Decision and boundary

Provide an explicit opt-in managed child-process adapter for callers that need
streamed lifecycle control. One child process invocation owns exactly one
existing Raider root and all its owned work. It does not create a second agent
loop, scheduler, admission system, policy engine, budget ledger, or artifact
store. Process ownership, trust, preflight, cleanup, root limits, artifact
semantics, and recovery inspection remain those of the existing CI runtime.

The adapter is a separate entry surface. Existing headless commands continue
not to read stdin. Do not repurpose their stdin, infer protocol mode from a
pipe, or alter existing CLI defaults. A future implementation must expose a
separately selected opt-in adapter and prove the ordinary headless behavior is
unchanged.

The adapter's stdin carries client requests; stdout carries only protocol
frames. Human diagnostics and operational logs go to stderr, redacted under the
existing logging policy. No protocol response is written to stderr, and no
diagnostic text is written to stdout.

## 2. Framing, encoding, and resource bounds

1. The wire is UTF-8 JSON Lines: one JSON object per frame, terminated by one
   LF byte (`0x0A`). A frame is at most **1,048,576 bytes before LF**. The LF is
   not counted. A larger frame is rejected before JSON decoding or dispatch;
   the adapter emits a bounded protocol error if stdout remains writable and
   then terminates with the protocol-failure exit. No partial oversized
   request is dispatched.
2. JSON must be valid UTF-8 and one complete JSON object. Invalid UTF-8, invalid
   JSON, a non-object top-level value, and an unknown required major version
   fail closed. JSON string escapes are decoded normally. Unicode scalar
   values, including multibyte/wide characters and U+2028/U+2029, are data;
   they are never treated as line delimiters. Literal LF remains the frame
   delimiter, so a JSON string containing a line break uses JSON escaping.
3. Receivers accept arbitrary read fragmentation and may receive several
   frames in one read. They buffer at most one bounded frame plus framing
   state; partial UTF-8/code points are completed by incremental decoding, not
   replaced or dropped. EOF before any bytes of the next frame is ordinary
   input closure. EOF with a partial frame is a protocol error and the partial
   frame is never dispatched. The producer flushes each complete response
   frame; a reader must not depend on process-write boundaries.
4. Each direction has a bounded queue of at most **64 frames and 4 MiB of
   encoded payload**, with the per-frame limit still applying. The producer
   applies backpressure rather than growing memory without bound. Progress
   observations may be coalesced by event/job key when a full queue blocks
   production; their sequence gap/count must be represented in a later
   observation. Request replies, cancellation state, and the single terminal
   result are never silently dropped. Space for one terminal result is
   reserved outside the ordinary observation queue. If required protocol data
   cannot be delivered because the peer does not drain stdout, stop accepting
   new requests, cancel the owned root, and close after bounded cleanup; the
   caller observes a missing terminal record as uncertain.

These bounds apply to bytes, not character count. They include escaped JSON
representation. Implementations must not log rejected frame contents.

## 3. Version and message table

Every frame has `protocolVersion` (major/minor), `type`, and, when associated
with an operation, `requestId`. Version 1 starts at `1.0`. A peer rejects an
unknown major version before dispatch; an unknown optional capability or
optional field may be ignored only when the versioned schema explicitly marks
it optional. Request IDs are opaque caller-chosen identifiers, unique within
one invocation. They correlate a request and its responses/events only; they
are **not** idempotency keys, durable handles, or permission to resubmit.

| Direction | `type` | Required content / meaning |
| --- | --- | --- |
| client → child | `hello` | Supported protocol version range and requested feature set. Must be first. |
| child → client | `helloAck` | Negotiated version, adapter/runtime version, and explicit capabilities, including whether streaming observation and cancellation are supported. Unsupported required capability refuses launch. |
| client → child | `submit` | New `requestId`, versioned input reference/value, and only non-secret invocation options allowed by trusted configuration. One submit creates one root; a second submit in this invocation is refused. |
| child → client | `accepted` | Correlates the request and confirms dispatch/root identity. It is acceptance, not completion or success. Preflight refusal returns a structured error instead. |
| client → child | `observe` | `requestId` and cursor/sequence from which to observe. Observation never starts or repeats work. |
| child → client | `observation` | Correlated, monotonic per-root sequence; event kind and bounded payload/reference. Gaps caused by coalesced progress are explicit. |
| client → child | `cancel` | `requestId`; requests cancellation of the owned root/subtree. Repeated cancel requests do not create new work. |
| child → client | `cancelAck` | Reports `accepted`, `already_terminal`, or `not_found`. `accepted` means cancellation was requested; it does not assert cleanup or terminal completion. |
| child → client | `terminal` | Exactly one terminal record for a dispatched root while the protocol output channel survives. Contains execution/task/safety/protocol/accounting statuses, primary/secondary errors, cleanup status, stable exit code, and artifact references. |
| either direction | `error` | Structured bounded error with stable code, correlation where known, and safe message; never includes secret values or raw environment. |

All messages use the negotiated schema's exact field allowlist. Unknown fields
that could change authority, execution, or result interpretation are rejected.
Diagnostics may carry non-sensitive identifiers only; prompts, credentials,
environment values, and unredacted tool arguments are not protocol metadata by
default.

## 4. Invocation, trust, and artifacts

Preflight and dispatch follow `ci-runtime.md` §3 and §5: arguments/configuration,
trusted bundle and input validation, trusted policy/capability resolution,
output location validation, provider/profile readiness, then root creation and
dispatch. Invalid input, unsupported required capability, or policy denial is
refused before provider/tool work. A caller cannot provide or widen executable
commands, verifier identity, permissions, trust level, budget ceiling, or
secret values through a message.

Secret-bearing fields are forbidden. Provider credentials and other secrets
come only from existing trusted environment/helper references bound by the
launch configuration; values are never echoed, returned in error text, placed
in artifacts, or logged. The protocol transports only opaque references when a
trusted invocation contract explicitly requires a reference, and never
resolves a caller-supplied reference into additional authority.

The terminal result uses the existing CI artifact contract (§7): each artifact
reference is relative to the invocation's configured output root and includes
path, SHA-256, media type, byte size, and provenance. It does not inline
unbounded file contents. Manifest completeness, redaction, atomic-write and
failure behavior remain those of the existing artifact writer. A missing or
unreadable required artifact cannot be represented as success.

## 5. Cancellation, terminal state, and process death

`cancelAck(accepted)` is a request acknowledgement only. Cleanup completion is
reported separately in the terminal result (`cleanupStatus`: complete, bounded,
or uncertain) and process exit. The adapter stops admission, cancels the owned
subtree/process groups, and applies the existing bounded grace period. It does
not report `Cancelled` merely because a signal or cancel request was sent. A
race between cancel and success/failure is resolved by the existing root's
single terminal transition; the protocol emits exactly one `terminal` frame if
the channel survives long enough to write it.

If stdout breaks or the peer closes stdin, the child stops accepting work and
performs bounded cancellation/cleanup. A broken pipe is not success. It must
not trigger a second submission, reconnect-and-replay, or mutation retry. If
the child dies, is killed, loses its runner, or cannot write the terminal
record, the caller must treat the outcome as **uncertain/incomplete**. It may
inspect existing artifacts and recovery evidence read-only and explicitly
reconcile; it must not automatically resubmit. A request ID is not an
artifact-recovery token and does not prove the child dispatched or completed
the root.

Terminal frame ordering: all already-produced required final evidence and
artifact references precede the one terminal frame; no ordinary observations
follow it. The terminal frame is flushed before normal process exit where the
channel remains available. On a failure to serialize/write the terminal frame,
exit nonzero and preserve the uncertainty rather than fabricating a response.

## 6. Exit codes

For a completed invocation, the process exit is the existing `ci-runtime.md`
§8 code, with the existing primary/secondary cause precedence. The terminal
record's `exitCode` MUST equal the process status when available.

| Code | Meaning |
| --- | --- |
| 0 | Declared success policy satisfied, cleanup and required artifacts complete |
| 10 | Task/verifier/check rejection |
| 20 | Runtime/provider/protocol failure |
| 21 | Configuration/input/bundle/preflight invalid |
| 22 | Policy/capability denial |
| 23 | Budget exhausted or required hard cap unavailable |
| 24 | Internal deadline exceeded |
| 25 | NeedsApproval in noninteractive mode |
| 26 | Side-effect/cancellation outcome uncertain |
| 27 | Required artifact/evidence write failure |
| 130 / 143 | SIGINT / SIGTERM handling |

Artifact failure 27 takes precedence, followed by uncertainty 26, then the
root's stop reason, except that signal handling preserves 130/143 as specified
by the existing contract. A process that fails before protocol handshake uses
the corresponding existing preflight/runtime code where determinable; inability
to write a structured error never changes a nonzero result to zero. The process
launcher/wrapper preserves the child code.

## 7. Failure semantics summary

| Condition | Required behavior |
| --- | --- |
| Duplicate `submit` in an invocation | Refuse; never create a second root. Do not imply duplicate request IDs are safely retryable. |
| Duplicate/unknown correlation ID | Return a bounded protocol error or ignore only a documented repeated observation/cancel; never dispatch. |
| Oversize, malformed, invalid UTF-8, unsupported major | Refuse before dispatch; bounded error if writable; protocol failure exit. |
| EOF between complete frames before submit | Close cleanly without a root. |
| EOF mid-frame | Protocol error; partial request is not dispatched. |
| EOF after dispatch / broken stdout pipe | Stop admission, bounded cancellation/cleanup, nonzero exit or uncertainty as appropriate; no automatic retry. |
| Slow output consumer | Bounded backpressure and progress coalescing; preserve critical receipts/terminal reserve; cancel if delivery cannot recover. |
| Cancel acknowledgement | Reports request acceptance only; terminal/cleanup status remains authoritative. |
| Death without terminal | Caller records uncertain/incomplete, inspects existing evidence read-only, and does not resubmit automatically. |
| Surviving output channel | Exactly one terminal result for a dispatched root, including failure/cancellation outcomes. |

## 8. Compatibility, non-goals, and review

Existing Scala workflows, headless command defaults, stdin behavior, artifact
schemas and stable exit meanings remain unchanged. Any implemented wire/schema
deviation requires a reviewed ADR and migration/refusal fixtures before
consumers are updated.

Out of scope: daemon or network protocol, remote/distributed scheduler, foreign
SDKs, arbitrary code sandbox, durable resume, exactly-once submission or
mutation, automatic retry/reconnect, changed exit-code meanings, and alteration
of ordinary headless stdin behavior. RAI-201 owns the opt-in Scala adapter and
must turn the linked transcript corpus into executable Scala-process
acceptance. The Proposed decisions here do not themselves pass that acceptance.
