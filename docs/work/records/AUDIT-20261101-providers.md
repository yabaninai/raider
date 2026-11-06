# AUDIT-20261101 providers work record

Status: complete (read-only audit). Date: 2026-11-01. Baseline: product input `04f74780891e0e7032bf98b3d1dccac279f32354`; audit lane HEAD at inspection `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`, branch `audit/20261101-providers`. Worktree started clean. No network/model calls, builds, tests, credential reads, or product edits were performed.

## Resulting assessment

The source implements two first-class provider backends: OpenAI Chat Completions and Anthropic Messages. `ProviderBackend` maps `openai` and `anthropic` explicitly, credential preflight occurs before construction, and there is no direct-provider failover branch after provider errors. The OpenAI non-streaming backend reports streaming unsupported; streaming is provided by a separate adapter. Anthropic streaming depends on the backend's configured streaming flag. Both report cancellation acknowledgement as unknown. These are source findings, not evidence that an arbitrary endpoint/model supports the features (`modules/cli/src/main/scala/raider/cli/run/ProviderBackend.scala`; `modules/provider-chat/src/main/scala/raider/provider/chat/OpenAIChatBackend.scala`; `modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicMessagesBackend.scala`; `docs/provider-protocols.md`).

`RAI-184` records an authenticated OpenAI-compatible live wire run against `demo.yabanin.ai/gw/v1`, which is explicitly a Yabanin gateway. Its tracked `matrix-resume.json` records non-streaming chat, SSE, a complete arithmetic tool round, mid-stream disconnect, observed token usage, and estimated costs; model IDs are `glm-5.3-flash` and `glm-5.3`. This supports a historical gateway wire smoke, with the limitations below. It is not evidence for a direct OpenAI-compatible endpoint. The matrix has no code-workspace changes, project checks, or real coding-task result. A two-leg arithmetic tool round does not satisfy the working-product requirement that a live profile complete a coding scenario with actual changes and checks (`docs/working-product.md` §1; `docs/work/records/RAI-184.md`; `artifacts/rai-184/matrix-resume.json`).

### Per-profile verdict as of this audit

| Profile | Verdict | Evidence and limit |
| --- | --- | --- |
| `direct-chat` | **pending** | No non-gateway direct OpenAI-compatible endpoint/model run is recorded. The RAI-184 “direct chat” scenario ran through the Yabanin gateway. No real coding scenario or checks. |
| `direct-anthropic` | **pending** | RAI-184 expressly says the Anthropic leg was not executed because no owner-configured endpoint was available. Local Scala stands are offline contract evidence only. |
| `gateway` | **actual: scoped wire smoke only; `live_verified` pending** | The 2026-10-25 artifact records the gateway host, model IDs, outcomes, usage, latency, and unknown/estimated cost. No full coding task, source diff, or project checks; no gateway billing confirmation. This profile is not working-product verified. |

The 2026-10-25 live evidence is self-reported by the RAI-184 work record and preserved in tracked JSON artifacts (verified with `git ls-files`). The artifacts include endpoint identity and scenario details, but not raw request/response transcripts or independently verifiable provider receipts. The separate harness ran from an OS temporary directory and is not included in the repository, so its exact implementation, source hash, and per-dispatch link to the Scala guard cannot be reproduced from tracked evidence. This audit treats the event as recorded historical evidence, not a fresh observation.

## Acceptance evidence

| Criterion | Audit result | Evidence |
| --- | --- | --- |
| Direct OpenAI-compatible support | Implemented in source; endpoint/model live acceptance pending | `ProviderBackend.scala`; `OpenAIChatBackend.scala`; RAI-184 artifacts identify gateway host |
| Direct Anthropic support | Implemented in source; live endpoint/scenario unverified | `ProviderBackend.scala`; `AnthropicMessagesBackend.scala`; RAI-184.md § Acceptance evidence |
| Gateway support | Compatible OpenAI wire used successfully in the recorded gateway matrix; enriched Yabanin adapter/session/accounting is not established by this evidence | `matrix-resume.json`; `docs/provider-protocols.md` §§1, 5 |
| Real coding task through live model | Not evidenced | RAI-184 only records simple `add_numbers(2,3)` tool fixture; no workspace edits/checks |
| Hard budget enforcement in product calls | **Not established**: `LiveWireSpendGuard` has no production call sites under `modules/**`; it is defined and unit-tested. Live CLI construction wraps both live backends in `RateLimitRetries` (up to three 429 retries), and no guard is applied in that call path. The RAI-184 temp harness's independent enforcement cannot be confirmed from tracked source. | `LiveWireSpendGuard.scala`; `ProviderBackend.scala:13-18, 59-68`; search command below |
| Cancellation / charge handling | Local request cancellation is implemented, but remote stop/metering acknowledgement remains unknown. RAI-184 recorded unknown usage after abort and retained a reservation. | Both backend Scaladoc; `matrix-resume.json` `cancelMidStream`; `spend-ledger.json` entry 17 |

## Limitations and risks

- **Budget ledger field inconsistency:** the detailed RAI-184 record and `matrix-resume.json` state estimated usage of `$0.006902` plus two retained `$0.10` reservations (`committedReservedUsd` `$0.206902`; remaining about `$4.7931`). However `spend-ledger.json` top-level `reservedExposureUsd` is `0.0`, while `remainingCapUsd` is `$4.793098`. These fields do not reconcile. Preserve the uncertainty but correct/reconcile ledger semantics before reusing it as a hard-cap receipt.
- The documented hard live cap is not wired into the production provider dispatch path at this baseline. Unit tests of the standalone spend guard and a temporary live harness do not by themselves prove that normal CLI/chat calls are capped. The production wrapper's 429 retry behavior is also separate from the live matrix's stated no-retry behavior; any capped production path must account for each dispatched retry.
- Gateway usage and estimated USD values are recorded; gateway pricing and actual billed cost remain unknown. The short-token chat returned only reasoning tokens and empty visible content. Mid-stream close did not establish that upstream generation or billing stopped.
- No direct Anthropic live matrix, direct OpenAI-compatible live matrix, repeated live runs, or Raider-vs-OpenCode comparison was found in the reviewed provider evidence. Local stands and RAI-202 are explicitly offline/source/contract evidence, not endpoint capability claims.
- There is no hidden provider fallback in the inspected CLI factory. The bounded 429 retry decorator is explicit and applies to both live wires; auth errors remain typed and do not trigger a provider/account switch.

## Commands

All commands below were read-only and exited 0. No builds, tests, endpoint calls, or credentials were accessed.

| Command | Exit | Result |
| --- | ---: | --- |
| `git status --short --branch && git rev-parse HEAD && git branch --show-current && pwd` | 0 | Clean assigned worktree at `935cc2ff…`, branch `audit/20261101-providers`; verified absolute lane cwd. |
| `cat AGENTS.md`, required docs, provider contract, task card, and work-record template; `sed -n` for RAI-184 card/record | 0 | Read contributor constraints, working-product acceptance, protocol requirements, live-wire request, and RAI-184 claims. |
| `rg --files docs/work/records` and `rg -n ... docs/work/records docs/acceptance docs/tasks` | 0 | Located RAI-184, later RAI-202 provider audit, M1 acceptance and relevant implementation records. |
| `git ls-files artifacts/rai-184 docs/work/records/RAI-184.md` | 0 | All three RAI-184 JSON artifacts and its work record are tracked. |
| `python3` JSON pretty-print of `matrix-resume.json`, `matrix.json`, `spend-ledger.json` | 0 | Parsed artifacts; confirmed recorded scenario, endpoint, usage, cost, and ledger fields. |
| `sha256sum artifacts/rai-184/*.json docs/work/records/RAI-184.md <three provider/budget source files>` | 0 | Captured current artifact/record/source hashes during inspection; no mutation. |
| `git log -1 --format=... -- <artifact/source paths>` | 0 | Confirmed RAI-184 artifact commit and source history provenance. |
| `nl -ba` on RAI-184 record/artifacts and provider source; `rg -n` over provider factory/backends/guard | 0 | Cross-checked claims and exact implementation locations. |
| `rg -n 'LiveWireSpendGuard' modules --glob '*.scala'` | 0 | Only definition and test references; no production caller in `modules/**`. |
| `git diff 04f74780891e0e7032bf98b3d1dccac279f32354..HEAD --stat` | 0 | Product baseline delta in this lane is only the audit task card; lane is aligned for source inspection. |

## Review and next task

Independent review is pending. Next provider follow-up: add an explicitly scoped task to connect per-dispatch budget reservations/settlement to production live provider calls (including bounded retries and cancellation uncertainty), reconcile RAI-184 ledger fields, then execute separately authorized direct OpenAI-compatible, Anthropic-compatible, and gateway coding-task scenarios with actual edits/checks. Do not treat this lane audit or the historical gateway wire matrix as integrated candidate acceptance.

The audit changes only this operational record. No product-input or quality-gate evidence was generated.
