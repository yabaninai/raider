# Execution card: direct-anthropic (Anthropic-compatible wire)

> NOT APPROVED FOR EXECUTION — owner must fill endpoints/budgets and ratify.

- Prepared by RAI-285 as a **text only** (no execution happened in RAI-285).
  Target profile `direct-anthropic` (per-provider `live_verified` statuses,
  docs/working-product.md §1). Unblocks RAI-171 (LIVE-PREP-01/02).
- Scenario authority: [docs/tasks/live-wire-request.md](../tasks/live-wire-request.md).
  Wire contract: [docs/provider-protocols.md](../provider-protocols.md).
  Gate catalog: [docs/quality-gates.md](../quality-gates.md). Card source:
  [docs/tasks/m1.md](../tasks/m1.md) (RAI-285).
- This text contains no endpoint values, no credential values, and no budget
  numbers. Every owner-supplied value is either an `<<OWNER-FILLS-IN: ...>>`
  marker or a private env value that never enters a file.

## 1. Mode declaration

**LIVE mode.** Real, paid, mutable provider calls against exactly one
owner-selected Anthropic-compatible endpoint. `--provider mock` legs inside
this card are gate fixtures only (pipeline sanity, zero spend); a response
carrying the `[scripted-fixture]` marker voids the leg as live evidence and is
recorded as a finding. This card never declares `live_verified` for the
profile; that board status changes only after a ratified, executed run records
the evidence of §9.

## 2. Endpoint identity (credential-free)

| Env name | Filled by | Contract |
|---|---|---|
| `RAIDER_PROVIDER` | fixed literal `anthropic` | selects the Anthropic-compatible wire; the headless CLI accepts only `mock`, `openai`, `anthropic` |
| `RAIDER_BASE_URL` | `<<OWNER-FILLS-IN: base URL>>` | bare origin and path; no userinfo, no query-string credential forms |
| `RAIDER_MODEL` | `<<OWNER-FILLS-IN: model id>>` | one explicit model id known to the endpoint; no registry/discovery call is made (see §7, G6) |
| `RAIDER_API_KEY` | owner, private env | the value is never written to any file, argv, log, artifact, report, or the board; this is the headless CLI's credential channel for every provider |
| `RAIDER_API_KEY_CMD` | owner, optional | helper executable whose first stdout line is the key; bounded to 10 seconds |

The `--api-key` flag does not exist: the CLI refuses it as a typed
configuration error (credentials are resolved from env at runtime, never
carried in args). The `api_key_env` names shown in the provider-protocols §1
configuration examples (e.g. an `ANTHROPIC_API_KEY`-style name) are target
profile shapes; the public headless surface is `RAIDER_API_KEY` /
`RAIDER_API_KEY_CMD`. The adapter emits the Anthropic-compatible auth scheme
and protocol-version headers per the wire contract.

## 3. Model and build pinning

- **Model:** the explicit `RAIDER_MODEL` id from §2. No `/models` discovery is
  required or attempted; the wire is exercised with the explicit id only.
- **Build:** pin exactly one headless artifact via
  `RAIDER_LIVE_JAR=<<OWNER-FILLS-IN: absolute jar path>>`:
  - the released headless jar listed in `docs/releases/release.json` with its
    SHA256SUMS provenance, or
  - a jar built by `make raider-jar` from one pinned source commit (record the
    commit id).
- Record in the execution record at run time: source commit id, jar SHA256,
  `java -version` output (JDK 21 or newer required), and the exit code of the
  top-level `help` invocation (must be 0). The headless CLI has no
  `--version` flag; build identity comes from the pinned jar and the release
  record ([docs/releases/jvm-distribution.md](../releases/jvm-distribution.md)).

## 4. Budget caps (owner placeholders)

| Cap | Value |
|---|---|
| Per-profile USD cap (this card) | `<<OWNER-FILLS-IN: USD cap>>` |
| Total USD cap (all three provider cards together) | `<<OWNER-FILLS-IN: USD cap>>` |
| Request-count cap (this card) | `<<OWNER-FILLS-IN: max requests>>` |
| Per-attempt output bound | `RAIDER_LIVE_MAX_TOKENS=<<OWNER-FILLS-IN: tokens>>` |
| Whole-run deadline | `RAIDER_LIVE_RUN_DEADLINE=<<OWNER-FILLS-IN: seconds>>` |

Enforcement semantics (provider-protocols §2; honest by construction):

- Dispatch is refused **before** any call when the projected cost would exceed
  the remaining USD cap or the request-count cap (atomic per-request hard
  stop; typed `ProviderBudget` refusal, exit class 23 `BudgetExhausted`).
- A hard USD cap requires bounded price/output (conservative reservation).
  With unknown pricing, launch under a hard USD cap is refused (BUD-02); then
  the token/attempt/deadline caps above are the honest enforcement and **no
  fabricated monetary guarantee is recorded**.
- An uncertain outcome settles its reservation as committed and stops future
  dispatch. Unknown stays unknown — it is never rendered as zero.
- Historical precedent for ceiling shape: the RAI-184 authorized ceiling
  recorded in the runtime spend guard and in
  [docs/tasks/live-wire-request.md](../tasks/live-wire-request.md). This
  card's actual caps are owner-set at ratification.

## 5. Isolation and data handling

- Runs execute against a **disposable fixture workspace**
  (`RAIDER_LIVE_WORKSPACE`, a fresh temp-directory copy) with sanitized
  prompts only (live-wire-request §3). No real project files, no secrets, no
  customer data.
- Credentials are env-only: `RAIDER_API_KEY` / `RAIDER_API_KEY_CMD`. Values
  never appear in argv, logs, artifacts, the execution record, the board, or
  tool output. Helper output is consumed, never echoed.
- Endpoint identity is recorded without credentials; error responses that
  redirect to a different origin are not followed with credentials attached;
  insecure remote HTTP is not used.
- The adapter does not log the model's thinking or signature blocks by
  default (provider-protocols §4); transcripts carry sanitized prompt and
  response content only.
- No hidden fallback: a provider auth/policy failure is a typed failure. The
  card never switches provider, account, endpoint, or protocol to continue.
- No dotenv-style artifacts are produced by or for this card.

## 6. Stop conditions

1. USD cap would be exceeded: dispatch refused before the call; terminal.
2. Request-count cap reached: refused at the atomic check; terminal.
3. Uncertain outcome (timeout, ambiguous failure, missing usage where the
   scenario requires usage): reservation settles as committed and future
   dispatch stops; the ambiguous call is never automatically repeated.
4. Provider auth/policy failure: typed failure recorded as a zero-cost
   `provider-auth-refusal` spend entry; no fallback of any kind; the leg
   stops (a retry is an explicitly recorded operator action within the caps).
5. Signals: SIGINT/SIGTERM produce cleanup with exit 130/143 and truthful
   partial/unknown usage (scenario S4).
6. `[scripted-fixture]` marker observed in any response: the leg is voided as
   live evidence and recorded as a finding.
7. Any refusal, deadline exit (24), config refusal (21), or uncertain exit
   (26) is recorded verbatim; none may be re-labeled as a pass.

## 7. Acceptance — all seven provider-protocols §5 gates

Gates are enumerated exactly as the protocol names them. Green requires the
recorded live evidence below; an unobserved gate stays PENDING with the
reason in the execution record — never passed by substitution.

| # | Gate (provider-protocols §5) | Live observable in this card | Evidence fields |
|---|---|---|---|
| G1 | Clean environment without `YABANIN_*`, no Yabanin module/service: CI and REPL acceptance on both local mock HTTP protocols. | Executor asserts no `YABANIN_*` variables in the run environment and the run's only network target is the owner endpoint. The offline half (both local mock HTTP protocols) stays owned by the repo's existing transport/contract profiles. | Preflight assertion output in the execution record; spend log network targets |
| G2 | One string/typed schema/tool round/fanout/timeout scenario produces equivalent normalized semantics on both wires. | S1, S2, S3 produce the normalized event vocabulary (text deltas, typed-schema payloads, tool call/result correlation, usage envelopes). Equivalence is **joint**: green only when the sibling `direct-chat` card records matching normalized semantics. Until then: PENDING. | `events.jsonl` envelopes on both wires; joint comparison note in both execution records |
| G3 | An explicit Anthropic endpoint receives no OpenAI/Yabanin headers; direct OpenAI sends no session mint requests. | This card's half: the adapter emits only its own auth scheme and protocol-version headers — no OpenAI/Yabanin headers, and no session-mint traffic. Live confirmation uses owner-side endpoint capture when the endpoint provides it; otherwise the contract profiles' local-stand header capture complements this card and the leg records the adapter's request paths/counts — a missing owner-side capture is recorded as a limitation, never scored as a pass. | Spend log per-request rows; endpoint-side header/path capture when available; local-stand header capture from the contract profiles |
| G4 | Unknown price remains unknown; observed usage is distinguished from estimates. | S5 legs: usage-bearing responses surface observed usage; the unpriced-model leg surfaces `unpricedAttempts`/`unknown` — cost is never displayed as zero. | `events.jsonl` `UsageObserved(priceKnown)`; `run.json` accounting fields |
| G5 | Mixed-provider children are accounted for in one root; each attempt records the actual provider/role. | One root run whose delegate resolves the sibling provider/role, expressed through whatever delegation/profile surface the pinned build documents as public. Each attempt row carries actual provider and role. If the public surface cannot express it, the leg records PENDING-with-reason — never hidden. | Spend log per-attempt rows (provider, role, model); one shared root ledger in `run.json` accounting |
| G6 | An unavailable doctor registry does not block an explicit model; unsupported required tools are blocked before spend. | The explicit-`--model` legs run with zero registry/discovery HTTP calls; the unsupported-required-tool leg produces a typed pre-dispatch refusal (exit 21/22 class) with a zero-cost spend entry and no HTTP dispatch, expressed through the pinned build's public declaration surface (ratified at execution; an absent surface records PENDING-with-reason, never substitution). The doctor surface belongs to the gateway adapter contract (W17); on this wire its absence must not block anything — recorded as observed. | Preflight negative legs in the execution record; spend log zero-cost refusal rows |
| G7 | The optional Yabanin adapter passes a separate contract/stand profile; the base release does not require deployed Yabanin. | The entire matrix completes with no `YABANIN_*` env and no Yabanin contact; the gateway profile is exercised by its own separate card. | Preflight assertion; completed matrix in the execution record |

## 8. Command argv templates

Executor shell setup (values private; the file never carries them):

```sh
export RAIDER_PROVIDER=anthropic
export RAIDER_BASE_URL=<<OWNER-FILLS-IN: base URL>>
export RAIDER_MODEL=<<OWNER-FILLS-IN: model id>>
# RAIDER_API_KEY / RAIDER_API_KEY_CMD are set privately in the executor
# shell; their values are never written to any file.
export RAIDER_LIVE_JAR=<<OWNER-FILLS-IN: pinned headless jar path>>
export RAIDER_LIVE_WORKSPACE=<<OWNER-FILLS-IN: disposable fixture workspace>>
export RAIDER_LIVE_OUT=<<OWNER-FILLS-IN: artifacts output directory>>
export RAIDER_LIVE_INPUT=<<OWNER-FILLS-IN: sanitized prompt file>>
export RAIDER_LIVE_MAX_TOKENS=<<OWNER-FILLS-IN: per-attempt output bound>>
export RAIDER_LIVE_RUN_DEADLINE=<<OWNER-FILLS-IN: whole-run seconds>>
export RAIDER_LIVE_CANCEL_AFTER=<<OWNER-FILLS-IN: seconds before S4 cancellation>>
```

Preflight (all must hold before any live leg):

```sh
java -version
java -jar "$RAIDER_LIVE_JAR" help
# expect: command list, exit 0
env | grep -c '^YABANIN_'
# expect: 0 (G1 assertion; captured in the execution record)
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider bogus --input x \
  --out "$RAIDER_LIVE_OUT/preflight"
# expect: typed refusal, exit 21, zero network
```

Mock pipeline sanity (gate fixture, zero spend — never live evidence):

```sh
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider mock \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/mock-sanity"
# expect: exit 0; run.json/result.json/events.jsonl present
```

Scenario legs (each within the §4 caps; stop conditions of §6 apply):

```sh
# S1 non-streaming chat with observed usage
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/s1" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS" \
  --run-deadline "$RAIDER_LIVE_RUN_DEADLINE"

# S2 streaming (fragmented deltas, final usage envelope)
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/s2" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS" \
  --run-deadline "$RAIDER_LIVE_RUN_DEADLINE"

# S3 one complete tool round (tool call, local fixture tool, correlated result)
java -jar "$RAIDER_LIVE_JAR" run --agent coder --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/s3" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS" \
  --max-dispatches <<OWNER-FILLS-IN: small dispatch bound>>

# S4 cancellation mid-stream (truthful partial/unknown usage)
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/s4" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS" &
pid=$!
sleep "$RAIDER_LIVE_CANCEL_AFTER"
kill -TERM "$pid"
wait "$pid"; echo "exit=$?"
# expect: exit 143 (SIGTERM class), status records cancellation truthfully

# S5a usage/cost reporting on a priced model
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/s5a" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS"

# S5b unpriced-model negative leg (unknown must stay unknown, never zero)
RAIDER_MODEL_S5B=<<OWNER-FILLS-IN: endpoint model id absent from any price catalog>> \
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL_S5B" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/s5b" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS"
```

G6 negative legs (zero spend by construction):

```sh
# Leg A (verified public surface): an unknown capability flag is a typed
# pre-dispatch refusal — exit 21, no HTTP dispatch, no spend.
java -jar "$RAIDER_LIVE_JAR" run --agent coder --provider anthropic \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/g6-parse-refusal" \
  --require-tool unsupported-example

# Leg B (capability refusal): the unsupported-required-tool case is expressed
# through the pinned build's public declaration surface for required
# capabilities, ratified at execution time. If that surface does not exist in
# the pinned build, the leg records PENDING-with-reason and the pre-spend
# capability-refusal evidence stays owned by the runtime contract profiles —
# never passed by substitution.
```

## 9. Expected observable evidence

Per run, under `--out`:

- Artifact set: `run.json`, `result.json`, `events.jsonl`, `checks.junit.xml`,
  `summary.md`, `transcript.json`.
- `run.json` core: `schemaVersion`, `rootId`, `status`
  (Succeeded/Failed/Cancelled/Interrupted/Uncertain), `exitCode` (typed map:
  0 Success, 10 TaskRejected, 20 RuntimeFailure, 21 ConfigInvalid, 22
  PolicyDenied, 23 BudgetExhausted, 24 DeadlineExceeded, 25 NeedsApproval,
  26 Uncertain, 27 ArtifactWriteFailure, 130 SigInt, 143 SigTerm),
  `taskVerdict`, `checks[]`, `artifacts[]` (each with `artifactId`, `sha256`,
  `mediaType`, `sizeBytes`, `path`), `output`, `error`, `startedAt`,
  `finishedAt`.
- `run.json` accounting block: `reservedUsd`, `observedUsd`, `uncertainUsd`,
  `unpricedAttempts`, `lateSettlements`, `estimateViolations`, `unknown`.
  Money is exact micro-USD rendering (six fractional digits; no floating
  point). Unknown stays unknown — never zero.
- `events.jsonl`: schema-versioned envelopes; per-attempt
  `UsageObserved(attempt, usage, priceKnown)` events.
- Execution record (one per card execution): endpoint identity without
  credentials, model id, pinned build identity, ratified caps, the preflight
  assertion outputs, one spend-log row per attempt with the shape
  `requestNumber, provider, scenario, reserved, charged, costStatus
  (Observed/Estimated/Unknown), outcome (response / provider-auth-refusal /
  uncertain-failure)`, every stop event with its trigger and timestamp, and
  the per-gate G1–G7 findings.

## 10. Ratification checklist (owner fills before execution)

| Field | Value |
|---|---|
| Endpoint base URL | `<<OWNER-FILLS-IN>>` |
| Model id | `<<OWNER-FILLS-IN>>` |
| Credential channel (env or helper) | `<<OWNER-FILLS-IN>>` |
| Per-profile USD cap | `<<OWNER-FILLS-IN>>` |
| Total USD cap (three cards) | `<<OWNER-FILLS-IN>>` |
| Request-count cap | `<<OWNER-FILLS-IN>>` |
| Per-attempt output bound / run deadline | `<<OWNER-FILLS-IN>>` |
| Executor identity and date | `<<OWNER-FILLS-IN>>` |
| Approval reference | `<<OWNER-FILLS-IN>>` |

## 11. Non-goals

- No protocol or adapter implementation work; no price verification (unknown
  prices are recorded, never estimated into existence); no account or
  organization setup; no sibling-card execution (the coordinator sequences
  the three cards); no edits to gate policy, verifiers, or the board by this
  card; no claims of exactly-once execution or durable resume.
