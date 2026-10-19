# Live-wire budget request — RAI-171 preparation

**Status: request only.** This document authorizes no live call, paid execution, credential provisioning, or provider configuration. Execution may begin only after the owner records the selected cap and providers in the board and separately authorizes the execution card.

## Providers and endpoints

The requested provider kinds are the two first-class wires in `docs/provider-protocols.md` §5:

- OpenAI-compatible: owner-selected endpoint and model ID.
- Anthropic-compatible: owner-selected endpoint and model ID.

Record endpoint identity and model IDs without credentials after owner selection. Endpoint URLs and models are currently unselected; do not infer production endpoints from examples in the protocol documentation.

## Requested scenario set

Exercise each selected provider independently with these scenarios:

1. Direct non-streaming chat, including observed usage.
2. Streaming response, including fragmented content and final usage when supplied.
3. Complete tool round: tool call, local fixture execution, and correlated tool result.
4. Cancellation during a response stream, with request cleanup and truthful partial/unknown usage.
5. Usage and cost reporting, distinguishing observed usage, estimated cost, and unknown prices.

Use sanitized prompts and disposable fixture workspaces. No real project secrets or data.

## Illustrative budget math (not a price quote)

For planning only, estimate 10 attempts per scenario per provider: 5 scenarios × 10 attempts × 2 providers = **100 attempts**. Assume 5,000 input tokens and 1,000 output tokens per attempt. Using example rates of $3 per million input tokens and $15 per million output tokens:

- Estimated per attempt: (5,000 × $3 + 1,000 × $15) / 1,000,000 = **$0.03**.
- Estimated scenario set: 100 × $0.03 = **$3.00**.
- Requested cap options: **$5** (tight run, little headroom) or **$20** (larger operational headroom for differing owner-selected model rates/token use).

These rates and token counts are placeholders for arithmetic, not verified prices or predictions. Before execution, replace estimates with the owner-selected endpoint/model pricing and conservative token bounds. The recorded cap is a hard ceiling enforced by the runner; if available pricing/bounds cannot support a hard monetary cap, do not execute under this request.

## Hard stop and credentials

The runner must enforce the exact USD cap recorded in the board before dispatch. Stop immediately when the cap is exhausted or a charge/usage outcome is uncertain; do not retry beyond the cap, automatically repeat an ambiguous attempt, or switch provider/account to continue. Report partial/unknown usage truthfully.

Credentials are supplied only through environment-variable references or an approved helper. Never put credential values in this document, board, argv, reports, artifacts, or logs.

## Owner completion

The owner fills this line, then records the same values in the board before any execution request:

`CAP (USD, owner): ________   PROVIDERS (owner): ________`
