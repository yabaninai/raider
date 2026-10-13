# TRANSPORT-SSE-IDLE: idle deadline terminates blocking reads

Status: done (reviewed runtime correction; final main receipt required).
Date: 2026-10-13. Baseline product: `7702fd0` / `073d617`.
Separate runtime correction discovered by USER-DOCS-CURRENT verification.

## Reproduction / result

On JDK21 the existing OpenAIChatStreamSpec case "idle timeout between chunks →
typed DeadlineExceeded" returns None after the outer 10s timeout instead of the existing
RA-DEADLINE with callTimeoutMs=500. Reproduced in the full suite and standalone suite
on the unchanged runtime. Evidence in the coordinator worktree: artifacts/quality/
20261013-012310-88826-0001/manifest.json (sbt-test fail),
artifacts/user-examples/sse-isolated.log (exit 1).

Current readChunk uses attemptBlockingIO and race waits for interruption of the losing branch;
scoped body.close runs after leaving the scope, so it does not release the read in time.
Fix read release on interruption using the existing ZIO blocking
cancellation primitive, closing the owned stream body. API/error codes,
The 500ms policy and 10s verifier bound do not change. Do not weaken the fixture.

## Owner / allowed paths

Visible Luna worker in independent worktree owns ONLY
`modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala`.
Coordinator owns this task, work record `docs/work/records/TRANSPORT-SSE-IDLE.md`
and the board. Existing tests/fixtures/registry are read-only. No public signature change.

## Acceptance

- SSEFIX-01: existing timeout regression returns typed RA-DEADLINE within its
  existing bound; entire OpenAIChatStreamSpec passes including normal tool round,
  fragmented JSON/UTF8, EOF/protocol failures and cancellation/accounting Uncertain.
- SSEFIX-02: caller interruption releases owned read/body and scope; timeout
  loser IO errors do not replace the declared deadline. No new retries/fallback.
- SSEFIX-03: worker changed/execute and integrated fast/docs/security pass;
  source/policy fingerprints fresh, independent review, no changed verifier or bounds.

## Non-goals

Provider API/signature/schema changes, additional dependencies, new transport,
pricing/accounting features, weakening timeouts/thresholds or replacing tests,
paid calls, edits outside the single runtime source. Work record after execution.
