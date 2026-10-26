# Local process adapter lane contract (RAI-201)

This directory is the lane contract for the `raider process-api` stdio adapter.
It maps the normative ADR-037 (`docs/adr/ADR-037-local-process-protocol.md`)
rows and the frozen fixture corpus (`docs/fixtures/process-api/`) onto the
adapter implementation in `modules/cli/src/main/scala/raider/cli/processapi/`.
The ADR and fixtures remain normative; this document records the binding
implementation decisions a reviewer needs to check the adapter against them.

## Scope

One process invocation owns ONE runtime root. The adapter is opt-in: it is a
separate CLI route (`raider process-api ...`) invoked before the ordinary
run/chat parse, exactly like `plugin`/`workflow`. Ordinary CLI behavior is
unchanged.

## Launch options and their preflight

| Flag | Values | Default | Notes |
|---|---|---|---|
| `--provider` | `mock`, `openai`, `anthropic` | `mock` | same factory as the headless runner (`ProviderBackend.make`); live providers require the env credential path (`Credentials.resolve`) |
| `--model` | name | `mock-model` | used as-is for `--agent coder` |
| `--agent` | `plain`, `coder` | `plain` | `plain` = empty tool registry, model `builtin:plain`; `coder` = `CodingToolset` registry rooted at `--workspace`, model from `--model` |
| `--workspace` | dir | CWD | tool root for `coder` |
| `--out` | dir | `./raider-out` | invocation work dirs live under this |
| `--base-url` | URL | `""` | passed to the backend factory |

No `--deadline`, no `--api-key` (never reads secrets into argv; the card does
not create a new credential path). Unknown flags, missing values, bad enum
values and an unparseable `--base-url` are refused with a bounded stderr
diagnostic and exit code 21 (`ConfigInvalid`) before any frame is read.

## Frame discipline (ADR-037 §2–§3, §6)

- Stdin: LF-delimited JSON objects. Only `0x0A` splits frames; U+2028/U+2029
  are ordinary data (fixture `fragmented-frames-unicode`). Partial delivery is
  handled by an incremental assembler; a frame is decoded and validated only
  once it is fully received (exactly one `submit` is dispatched per completely
  assembled frame).
- Strict UTF-8 decoding with malformed-sequence errors: `invalid_utf8`
  (protocol failure, fixture `fragmented-frames-unicode`).
- Payload bound: 1,048,576 bytes. An over-long frame is rejected
  (`oversize_frame`) as soon as the bound is exceeded — before JSON decoding
  and before any dispatch — and the rest of the line is discarded to the next
  LF without logging payload bytes (fixture `oversize-frame`).
- Every frame carries `protocolVersion {major, minor}`; only `1.0` is
  negotiated. A different version is refused before any dispatch
  (`unsupported_version`, protocol-failure exit family).
- Unknown fields are rejected per frame with `unknown_field` naming the field
  PATH (never the value); the session continues. Missing required fields are
  `invalid_frame`. Unknown `type` is `unknown_type` (session continues).
- `submit.input` is decoded as an opaque JSON object at the framing level and
  validated only at dispatch: the object must have exactly `schemaVersion` (=1)
  and `task` (non-empty string, bounded); violations are `input_rejected` and
  never start a root (fixture `refusal-and-secret-fields`: a forbidden field
  inside `input` is refused pre-dispatch, its value is never echoed, and the
  first-root-owner rule stays intact).
- Errors that cannot carry the client's `requestId` (framing failures) are
  emitted with no `requestId` field.

## Handshake and the root-owner rule (ADR-037 §4)

- `hello` must be the first frame (`handshake_required` otherwise, session
  continues). A second `hello` is `hello_already_negotiated`.
- `helloAck` answers with `negotiated 1.0`, `capabilities ["observe","cancel"]`
  and a bounded `adapterVersion`.
- `requiredCapabilities` must be a subset of the supported set; anything else
  is a refusal before launch (`unsupported_capability`, protocol-failure exit
  family, no root).
- `submit` while a root is owned: bounded `duplicate_submit` refusal; the
  first root continues (fixture `duplicate-submit`).
- Exactly one root per invocation; exactly one `terminal` frame per run,
  written last, after all ordinary queued frames.

## Streaming, backpressure, observation re-delivery (ADR-037 §5)

- The run is streamed automatically once accepted; `observe` is a bounded
  re-delivery query over the adapter's own retained observation envelopes
  (drop-oldest, bounded), keyed by hub per-job `seq`. Re-delivery is
  at-least-once; the client dedups by `sequence`. `observe` with nothing
  retained past the requested sequence answers `observe_failed` (session
  continues).
- Outbound bounds (fixtures `slow-reader`, `oversize-frame`): at most 64
  ordinary queued frames and 4 MiB of encoded payload, at most one frame at
  1 MiB. Receipts (accepted, error, cancelAck), cancellation state and the
  reserved terminal frame are never dropped; terminal capacity is reserved
  separately from the ordinary queue.
- When the observation path is saturated, new progress observations coalesce:
  the next delivered observation carries `skipped {fromSequence, count}`
  reporting exactly the coalesced range. If the gap is still open at settle
  time, one final observation reporting it is flushed before the terminal
  frame.
- A peer that stops draining does not grow memory: writes are bounded by a
  stall watchdog (default 30 s); on a stall the peer is declared dead, the
  root is cancelled with bounded cleanup, artifacts are still written, and the
  session exits non-zero (26) without claiming success (fixtures `slow-reader`,
  `broken-pipe`).

## Cancellation, EOF, death, signals (ADR-037 §7; XE-PROC-02/03)

- `cancel` with no root → `cancelAck.status = "not_found"`.
- `cancel` of the owned root → `cancelAck.status = "accepted"` meaning
  REQUESTED, not completed; the adapter then performs bounded cleanup and the
  single terminal frame settles the race (fixture `cancel-mid-run`).
- `cancel` after the terminal frame is decided → `cancelAck.status =
  "already_terminal"`.
- Terminal vocabulary over the adapter's outcomes:
  - natural completion → `executionStatus "completed"`, exit 0 or 10
    (checks failed), `taskVerdict success|task-failed`, accounting complete.
  - typed run failure → `executionStatus "failed"`, exit per
    `HeadlessRunner.exitCodeFor` (22/25 render `safetyVerdict "denied"`).
  - client cancel / EOF after dispatch / peer death / protocol failure with a
    live root → `executionStatus "cancelled"`, exit 26, cleanup `bounded`.
  - SIGTERM → `executionStatus "interrupted"`, exit 143. OS-signal deaths the
    adapter cannot handle surface as the process's own signal status (130/143)
    — the fixture corpus' `exitCode 130` is the signal value, not an adapter
    decision; a REQUESTED cancel is 26.
- EOF before any submit (clean boundary): no root, no terminal frame, exit 0.
- EOF after dispatch: stop accepting, cancel the owned root, bounded cleanup,
  exit 26 — never a fabricated success; never an automatic re-dispatch or
  resume of an uncertain run (fixture `eof`).
- Partial frame at EOF (bytes without LF): protocol error, never dispatched.
- If the root process dies without a terminal frame the CALLER observes an
  uncertain outcome; whatever local artifacts were written stay on disk
  (fixture `death-without-terminal`, `broken-pipe`).
- SIGTERM handling is bounded: the hook requests a settle, waits at most 5 s
  for it, and never calls `System.exit` from inside the hook (the same
  discipline as the headless runner's stop).

## Artifacts and accounting (ADR-037 §4, ci-runtime §7)

Work dir `<out>/work-<uuid>/` per accepted submit: `run.json` (validated via
`RunResult.validate`, the validation recorded as a real check), `result.json`
(validated output or structured failure payload), `events.jsonl` (canonical
envelope checkpoint via `EventsJsonlWriter`), `summary.md`, and
`artifacts.json` (sha256 manifest of the four, excluding itself). The
terminal frame's `artifacts` refs are derived from the actual manifest bytes
and always point at existing files (fixture `normal-handshake-run-terminal`).

Version-1 limitations (documented, not hidden): no delegation wiring, no
lineage/multi-job export, no transcript.json, no `--deadline`. A failed
artifact write is reported honestly (`cleanupStatus "bounded"`, bounded
`primaryError`) and never masked as a completed run.

## Exit codes (summary)

| Situation | Exit |
|---|---|
| clean session, no run (EOF before submit; refusals then clean EOF) | 0 |
| natural completion, checks passed | 0 |
| natural completion, checks failed | 10 |
| bad launch argv | 21 |
| protocol failure with no root (oversize, malformed, invalid UTF-8, unsupported version/capability) | 20 |
| typed run failure | `HeadlessRunner.exitCodeFor` |
| requested cancel / EOF after dispatch / peer death / stall | 26 |
| SIGTERM (graceful path) | 143 |
| unexpected internal defect | 70 |
