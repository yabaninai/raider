# Provider execution cards (owner-gated texts)

> NOT APPROVED FOR EXECUTION — owner must fill endpoints/budgets and ratify.

Prepared by RAI-285 as **texts only**: no endpoint was called, nothing was
spent, no live evidence exists yet. These cards unblock RAI-171
(LIVE-PREP-01/02), whose live-wire verification stays owner-gated
(docs/work/board.md, Open Obligations). Scenario authority:
[docs/tasks/live-wire-request.md](../tasks/live-wire-request.md). Wire
contract: [docs/provider-protocols.md](../provider-protocols.md). Gate
catalog: [docs/quality-gates.md](../quality-gates.md). Card source:
[docs/tasks/m1.md](../tasks/m1.md). Per-provider `live_verified` statuses:
[docs/working-product.md](../working-product.md) §1.

## Cards

| Card | File | Wire | Mode |
|---|---|---|---|
| `direct-chat` | [direct-chat.md](direct-chat.md) | generic OpenAI-compatible endpoint (`--provider openai`) | LIVE |
| `direct-anthropic` | [direct-anthropic.md](direct-anthropic.md) | Anthropic-compatible endpoint (`--provider anthropic`) | LIVE |
| `gateway-baseline` | [gateway-baseline.md](gateway-baseline.md) | Yabanin gateway through the generic OpenAI-compatible wire, wrapper-owned lifecycle | offline-first, live leg separately gated |

Core/CLI work without Yabanin is a first-class requirement: the two direct
cards need no Yabanin module, service, or env var; the gateway card is the
optional, separately-gated leg (§5 G7).

## Shared acceptance frame

Every card maps **all seven** provider-protocols §5 gates, enumerated exactly
as the protocol names them:

1. Clean environment without `YABANIN_*`, no Yabanin module/service: CI and
   REPL acceptance on both local mock HTTP protocols.
2. One string/typed schema/tool round/fanout/timeout scenario produces
   equivalent normalized semantics on both wires.
3. An explicit Anthropic endpoint receives no OpenAI/Yabanin headers; direct
   OpenAI sends no session mint requests.
4. Unknown price remains unknown; observed usage is distinguished from
   estimates.
5. Mixed-provider children are accounted for in one root; each attempt
   records the actual provider/role.
6. An unavailable doctor registry does not block an explicit model;
   unsupported required tools are blocked before spend.
7. The optional Yabanin adapter passes a separate contract/stand profile; the
   base release does not require deployed Yabanin.

Gate semantics: green requires recorded evidence in an executed, ratified
run; an unobserved gate stays PENDING with its reason in the execution
record and is never passed by substitution. G2 equivalence is a joint
finding across the two direct cards.

## Execution record

Every executed card writes one execution record with: credential-free
endpoint identity, model and pinned build identity, ratified caps, preflight
assertions, one spend-log row per attempt (`requestNumber, provider,
scenario, reserved, charged, costStatus, outcome`), stop events, and the
per-gate findings. The five-scenario live matrix and its honesty rules are
defined in [docs/tasks/live-wire-request.md](../tasks/live-wire-request.md).

## Ratification workflow

1. Owner fills every `<<OWNER-FILLS-IN: ...>>` marker in the chosen card
   (endpoint, model, caps, executor identity, approval reference).
2. Owner supplies credentials privately through the card's env channels —
   never through files, argv, or this repository.
3. Coordinator sequences the three cards (no sibling execution).
4. Each execution records its evidence per the card's §9; the board's
   `live_verified` statuses change only from that recorded evidence.
