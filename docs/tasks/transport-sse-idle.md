# TRANSPORT-SSE-IDLE: idle deadline завершает блокирующее чтение

Status: done (reviewed runtime correction; final main receipt required).
Date: 2026-10-13. Baseline product: `7702fd0` / `073d617`.
Separate runtime correction discovered by USER-DOCS-CURRENT verification.

## Reproduction / result

На JDK21 existing OpenAIChatStreamSpec case "idle timeout between chunks →
typed DeadlineExceeded" возвращает None после outer 10s timeout, вместо existing
RA-DEADLINE при callTimeoutMs=500. Воспроизведено в full suite и standalone suite
на неизменённом runtime. Evidence coordinator worktree: artifacts/quality/
20261013-012310-88826-0001/manifest.json (sbt-test fail),
artifacts/user-examples/sse-isolated.log (exit 1).

Текущий readChunk использует attemptBlockingIO и race ждёт interruption loser;
scoped body.close выполняется после выхода, что не освобождает чтение вовремя.
Исправить освобождение read при interruption через existing ZIO blocking
cancellation primitive с закрытием принадлежащего stream body. API/error codes,
500ms policy и 10s verifier bound не меняются. Никакого ослабления fixture.

## Owner / allowed paths

Visible Luna worker in independent worktree owns ONLY
`modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala`.
Coordinator owns this task, work record `docs/work/records/TRANSPORT-SSE-IDLE.md`
и board. Existing tests/fixtures/registry are read-only. No public signature change.

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
