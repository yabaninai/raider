# pi RPC backend contract (RAI-034)

This directory holds the wire contract of the pi RPC external backend
(`raider.backend.pi.PiRpcBackend` in `modules/backend-pi`).

## Capability disclosure

- `pi-rpc-capabilities.schema.json` — structural schema for the capability
  report: exact key set, enum domains, and the honest-unknown requirements
  (nested accounting `unknown`, tool ownership disclaimer, session support
  `unsupported`).
- `golden/pi-rpc-capabilities.json` — the byte-exact golden fixture. The
  canonical rendering produced by `PiRpcCapabilities.defaults.canonicalJson`
  must equal this file byte-for-byte (sorted keys, 2-space indent, no
  trailing newline). `PiRpcContractSpec` enforces both directions.

The disclosure states the trust-boundary facts explicitly:

- `tools` is `supported-externally-owned` and `toolOwnership` records that
  tools execute inside pi and Raider tool policy is **NOT** enforced inside
  pi (pi owns execution; see runtime-contracts §13).
- `nestedAccounting` is `unknown` and is never claimed otherwise.
- `priceModel` records `priceKnown=false`: pi reports cost, but Raider does
  not price pi usage.

## Wire protocol (upstream pin)

Protocol: `pi-rpc/1`, upstream pi commit pin
`55a5c6914570e2ae31ca36297654483bbbf46883`. Frames are LF-terminated
single-line JSON objects on the child's stdout; requests are LF-terminated
JSON objects written to the child's stdin. CR is stripped before parsing;
U+2028/U+2029 are data, never line terminators (byte-level splitting).
Blank lines, unparseable frames, truncated final frames, oversized frames,
`response` frames with ids Raider never issued, and stream end without
`agent_settled` are all stream violations (`RA-STREAM` or, when the
outcome is unobservable, `RA-UNAVAIL`/`RA-CANCEL` with an explicit
"uncertain" explanation).

Completion is signaled only by `agent_settled(aborted=true|false)`.
`abort` requests are id-matched; an acknowledged cancel must end with
`agent_settled(aborted=true)` within the configured settle wait, otherwise
the child is destroyed/stopped and the outcome is reported as uncertain.
Closing stdin is the orderly shutdown signal (process-per-run; no sessions,
no steering, no structured output in the surface Raider consumes).

## Process model

Exactly one inference attempt per `run` call: spawn child, write prompt,
stream normalized `raider.core.ModelEvent`s until exactly one terminal
event, then end the stream. The scope finalizer performs an orderly stop:
close stdin, graceful destroy window, then destroy/destroyForcibly.
