# OpenCode backend contracts (`contracts/backends/opencode/`)

This directory holds the pinned wire contract for the OpenCode HTTP backend
adapter (card RAI-035, module `modules/backend-opencode`):

- `expected/pinned-events.json` — pinned raw `GET /global/event` frames
  (GlobalEvent shape) with the expected adapter mapping for each frame. The
  shapes mirror the OpenCode server docs and the JS SDK `types.gen.ts` at tag
  v1.19.4 (corpus snapshot `d7d85817160120fd59fb27ee6ff354f45d18b994`).
- `event-frame.schema.json` — JSON Schema (draft 2020-12) for the frame
  envelope the adapter accepts on the shared global SSE feed.

## Scope

The adapter consumes a deliberately narrow slice of the OpenCode surface:
`/global/event` streaming, session create/read/status, message list,
`prompt_async` submission and `abort`. The event pin covers exactly the event
types the run-settle state machine needs:

- `server.connected` (properties carry the server address),
- `session.created` (properties are the session object itself; note the
  contrast with `message.updated`, whose properties wrap a `info` object),
- `session.status` (`busy`/`idle`/`retry`),
- `session.idle`,
- `message.updated` (user and assistant info),
- `message.part.updated` (`text` deltas, `tool` states
  `pending`/`running`/`completed`/`error`, other part types pass through),
- `session.error` (abort evidence: `MessageAbortedError`),
- `permission.updated`,
- any unknown `type` — decoded as a pass-through `Unknown` event, never an
  error.

## Honesty rules enforced by the spec

- An undecodable frame (valid SSE, invalid JSON shape) is a typed
  `StreamProtocol` error and forces a reconnect; the reader never replays a
  mutation after a reconnect (no `prompt_async` resubmission).
- Unknown future event types surface as `Unknown`, not as failures: version
  skew must be observable, not fatal.
- Run outcomes settle only from terminal server evidence; if the settle
  deadline expires without terminal events, the adapter reconciles against
  the message list and reports the outcome with `reconciled = true`. Missing
  completion evidence after reconciliation is `Uncertain`, never a fabricated
  success.
- Abort outcomes distinguish an acknowledged server abort (`Aborted` with
  server abort evidence) from a silent abort (deadline, no completion
  evidence -> `Uncertain`).
- Subscriptions register synchronously before a mutating call is sent, so run
  evidence emitted while the server processes the submission cannot be missed
  (the shared feed has no replay).
- Session-scoped views filter on the session id and, when configured, the
  workspace directory: concurrent sessions from two roots do not cross-read.

## Spec entry points (standalone, no sbt)

The specs are plain `main` programs in `modules/backend-opencode/src/test/`
(the module is not yet registered in `build.sbt`; registration is a
coordinator action). They compile and run with the pinned dotc 3.9.0 and the
jar closure under `artifacts/tools/m2`:

- `raider.backend.opencode.TestMain` — the full suite: wire decoding against
  `expected/pinned-events.json`, HTTP transport behavior against a local
  in-process contract stand (`OpenCodeContractStand`, real sockets), settle /
  abort / concurrency semantics (OCBACK-01..03).
- `modules/backend-opencode/run-tests.sh` — pinned compiler + runner.

A re-pin of `expected/pinned-events.json` MUST be accompanied by: a new SDK
tag/corpus snapshot reference in the file comment, a review of the semantic
diff, and refreshed gate evidence.
