# TRANSPORT-SSE-IDLE: work record

Status: done (narrow runtime correction, independent review).
Date: 2026-10-12 UTC / 2026-10-13 Europe/Moscow.
Baseline: `0f62c91` (assignment), product inherits `7702fd0`.
Visible Luna worker worktree: `wt/sse-idle-fix`, branch `fix/sse-idle-deadline`.
Final worker commit: `96f02ed83bc5117d2ef36dc005e25f8087c4a5c0`.
Card: [transport-sse-idle](../../tasks/transport-sse-idle.md).

## Resulting behavior

Idle timer can now release its losing blocking SSE read: `attemptBlockingCancelable`
closes the response body belonging to that read on interruption. Both cancellation
close and response scope finalizer use `attemptBlockingIO(...close()).ignore` so
IOException from close does not become a defect replacing the declared deadline.
The normal read/error mapping remains unchanged. No public API, timeout, provider
wire, retry or pricing changes. This is the existing RA-DEADLINE contract corrected.

## Changed paths

Worker changed only
`modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala`.
Coordinator writes this record, card status and board. Tests, fixture bounds and
gate registry were read-only in this runtime card. The README classifier correction
is a separate verifier card.

## Acceptance evidence / commands

| Criterion / command | Actual result | Evidence |
| --- | --- | --- |
| Baseline full sbt-test reproduction | exit 1, idle timeout result None after existing outer 10s | coordinator `artifacts/quality/20261013-012310-88826-0001/sbt-test.log` |
| Baseline isolated `sbt --batch 'raiderProviderChat/testOnly *OpenAIChatStreamSpec'` | exit 1, same failure on unchanged runtime | coordinator `artifacts/user-examples/sse-isolated.log` |
| SSEFIX-01/02 final isolated suite | exit 0, 12 passed / 0 failed / 0 ignored | worker final report; source commit `96f02ed` |
| `git diff --check` | exit 0 | worker report and coordinator reviewed diff |
| `make quality-changed` | exit 0, contracts/static/unit | worker final report |
| `make quality-changed QUALITY_EXECUTE=1` | exit 0, compile/contracts/formatter and full sbt-test all passed after final safe-close edit | `wt/sse-idle-fix/artifacts/quality/20261013-013839-2081-0001/manifest.json` |
| Independent final source review | approve | `/root/user_docs_review_luna`, final diff in owner session |

Worker uses JDK21, sbt1.11.4, Scala3.9.0/ZIO2.1.26 and configured /tmp/cc-master
cache. Actual ZIO API was inspected in resolved jar. The targeted suite includes
normal text/tool rounds, fragmented frames/UTF8, protocol errors, idle deadline
and cancellation/accounting Uncertain. Its 12 tests are not a claim of 17 tests.

SSEFIX-03 final integrated candidate is checked after main merge and records;
exact commands/exits/logs/hashes are in `artifacts/user-docs-final/receipt.json`.
Lane evidence does not substitute for that receipt. Missing/failed final receipt
leaves integration pending. No source edits after final fingerprint checks.

## Limitations / review and next task

Closing the client body does not prove upstream billing cancellation; cancellation
acknowledgement remains Unknown. Close IOException is suppressed in cleanup to
preserve the outcome; no exactly-once or durable resume promise. No paid calls.
Independent reviewer initially blocked the unsafe `succeed(close()).ignore` form;
worker fixed both cleanup paths and repeated affected checks on final source.

Next SDK packet is RAI-116; observed-usage/zero-settlement gap remains separate
and was not changed by this transport correction. Main readiness beyond these
component cases requires existing milestone obligations.
