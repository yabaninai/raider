# Execution card: gateway-baseline (Yabanin gateway, W17 baseline scope)

> NOT APPROVED FOR EXECUTION — owner must fill endpoints/budgets and ratify.

- Prepared by RAI-285 as a **text only** (no execution happened in RAI-285).
  Target profile `gateway` (per-provider `live_verified` statuses,
  docs/working-product.md §1, W17 row: gateway baseline scope; the extended
  gateway deferral stays with the P11 review matrix).
- Wire contract: [docs/provider-protocols.md](../provider-protocols.md);
  runtime/CI boundary: [docs/ci-runtime.md](../ci-runtime.md) §10/§13;
  gate catalog: [docs/quality-gates.md](../quality-gates.md); card source:
  [docs/tasks/m1.md](../tasks/m1.md) (RAI-285).
- This text contains no endpoint values, no credential values, and no budget
  numbers. Every owner-supplied value is either an `<<OWNER-FILLS-IN: ...>>`
  marker or a private env value that never enters a file.

## 1. Mode declaration

**Offline-first, live leg separately gated.** The baseline passes its
deterministic local contract fixture first (no network, no spend), then —
only after owner ratification of the live leg — one minimal live smoke
through the deployed gateway. This card never declares `live_verified`; the
base release does not require a deployed Yabanin (§7, G7). `--provider mock`
legs are gate fixtures only; a response carrying the `[scripted-fixture]`
marker voids a leg as live evidence.

## 2. Endpoint identity (credential-free)

Gateway-side names (wrapper-provided, child-only; names recorded here,
values never recorded anywhere):

| Env name | Provided by | Contract |
|---|---|---|
| `YABANIN_GATEWAY_URL` | wrapper | gateway base URL; no userinfo, no query-string credential forms |
| `YABANIN_API_KEY` | wrapper | the gateway-issued child credential; value never written to any file, argv, log, artifact, report, or the board |
| `YABANIN_LABELS` | wrapper | lineage/label propagation for the gateway's own accounting |

Raider-side connection (verified public headless surface):

| Env name | Filled by | Contract |
|---|---|---|
| `RAIDER_PROVIDER` | fixed literal `openai` | the gateway is consumed through the generic OpenAI-compatible wire (the M0 profile shape in provider-protocols §1); the enriched `kind = "yabanin"` TOML profile is beta (RAI-037/038) and is **not** claimed by this card |
| `RAIDER_BASE_URL` | the wrapper (from `YABANIN_GATEWAY_URL`) or the owner shell | gateway origin and path |
| `RAIDER_MODEL` | `<<OWNER-FILLS-IN: model id>>` | one explicit model id known to the gateway |
| `RAIDER_API_KEY` / `RAIDER_API_KEY_CMD` | wrapper indirection (child credential) or owner, private env | the credential reaches Raider only through the product's env channels; values never appear in any file or argv |

The `--api-key` flag does not exist: the CLI refuses it as a typed
configuration error. No session key is ever minted by Raider on an inherited
session; there is exactly one session lifecycle owner — the wrapper.

## 3. Model and build pinning

- **Model:** the explicit `RAIDER_MODEL` id from §2; the gateway routes it.
- **Build:** pin exactly one headless artifact via
  `RAIDER_LIVE_JAR=<<OWNER-FILLS-IN: absolute jar path>>` (released jar with
  SHA256SUMS provenance, or `make raider-jar` from one pinned commit).
  Record source commit id, jar SHA256, `java -version` (JDK 21+), and the
  top-level `help` exit code (0) in the execution record. The headless CLI
  has no `--version` flag
  ([docs/releases/jvm-distribution.md](../releases/jvm-distribution.md)).
- **Wrapper:** the `yb` wrapper binary is an external, owner-provided tool
  from the Yabanin side; its identity/version is recorded in the execution
  record. Raider builds no wrapper, mint logic, or second scheduler.

## 4. Budget caps (owner placeholders)

| Cap | Value |
|---|---|
| Per-profile USD cap (this card, live leg only) | `<<OWNER-FILLS-IN: USD cap>>` |
| Total USD cap (all three provider cards together) | `<<OWNER-FILLS-IN: USD cap>>` |
| Request-count cap (live leg) | `<<OWNER-FILLS-IN: max requests>>` |
| Per-attempt output bound | `RAIDER_LIVE_MAX_TOKENS=<<OWNER-FILLS-IN: tokens>>` |
| Whole-run deadline | `RAIDER_LIVE_RUN_DEADLINE=<<OWNER-FILLS-IN: seconds>>` |

Enforcement semantics match the direct cards (provider-protocols §2):
pre-dispatch atomic refusal on cap overrun (typed `ProviderBudget`, exit
class 23 `BudgetExhausted`); a hard USD cap requires bounded price/output
(BUD-02 refuses unknown-price launch under a hard cap); uncertain outcomes
settle their reservation and stop dispatch; unknown stays unknown, never
zero. The offline legs spend nothing by construction.

## 5. Isolation and data handling

- Disposable fixture workspace and sanitized prompts only
  ([docs/tasks/live-wire-request.md](../tasks/live-wire-request.md) §3).
- Credentials are env-only through `RAIDER_API_KEY` / `RAIDER_API_KEY_CMD`
  and the wrapper-provided gateway envs; no dotenv artifacts; values never
  in argv, logs, artifacts, execution record, board, or tool output.
- Endpoint identity is recorded without credentials; redirects to different
  origins are not followed with credentials; insecure remote HTTP is not
  used (loopback HTTP is allowed for the local contract fixture).
- No hidden direct fallback: a gateway auth/policy failure is a typed
  failure; Raider never retries against, or switches to, any direct provider
  endpoint, other account, or other protocol.
- Label values in `YABANIN_LABELS` are lineage metadata only; they never
  carry secret material.

## 6. Stop conditions

1. USD cap or request-count cap would be exceeded (live leg): refused before
   the call; terminal.
2. Uncertain outcome: reservation settles as committed and future dispatch
   stops; the ambiguous call is never automatically repeated.
3. Gateway auth/policy failure: typed failure (`provider-auth-refusal`
   zero-cost spend row); zero direct-endpoint contact; the leg stops.
4. Signals: SIGINT/SIGTERM produce cleanup with exit 130/143 and truthful
   partial/unknown usage.
5. `[scripted-fixture]` marker observed outside the mock fixture legs: the
   leg is voided as live evidence and recorded as a finding.
6. Any refusal, deadline exit (24), config refusal (21), or uncertain exit
   (26) is recorded verbatim; none may be re-labeled as a pass.

## 7. Acceptance — all seven provider-protocols §5 gates

Gates are enumerated exactly as the protocol names them. Green requires the
recorded evidence below; an unobserved gate stays PENDING with the reason in
the execution record — never passed by substitution.

| # | Gate (provider-protocols §5) | Observable in this card | Evidence fields |
|---|---|---|---|
| G1 | Clean environment without `YABANIN_*`, no Yabanin module/service: CI and REPL acceptance on both local mock HTTP protocols. | Inverted for this card: the baseline **requires** the gateway side, so the clean-environment half is owned by the two direct cards. This card's contribution: the offline legs pass on local mock HTTP stands only, no live gateway needed. | Offline fixture outputs; cross-reference to the direct cards' G1 rows |
| G2 | One string/typed schema/tool round/fanout/timeout scenario produces equivalent normalized semantics on both wires. | The offline fixture (gateway surrogate stand) and, when ratified, the live smoke produce the same normalized event vocabulary as the direct wires. Equivalence is joint with the direct cards. | `events.jsonl` envelopes; joint comparison note in the execution records |
| G3 | An explicit Anthropic endpoint receives no OpenAI/Yabanin headers; direct OpenAI sends no session mint requests. | Raider sends **no** session-mint requests: exactly one mint exists and it belongs to the wrapper lifecycle owner. Negative leg: an invalid child credential yields a typed failure with zero contact to any direct provider origin (request count to non-gateway origins = 0). | Spend log rows; execution record lifecycle-owner statement (`wrapper`); negative-leg network assertion |
| G4 | Unknown price remains unknown; observed usage is distinguished from estimates. | The usage smoke surfaces the wire's usage envelope (`UsageObserved(priceKnown)`). Gateway-side extended accounting is out of scope (W17; RAI-038/039) — anything not observable through the wire is recorded as unknown/pending, never estimated. | `events.jsonl` usage events; `run.json` accounting fields |
| G5 | Mixed-provider children are accounted for in one root; each attempt records the actual provider/role. | In the baseline all attempts route through one gateway connection; each spend-log row records the actual provider profile and role. Mixed-provider-through-gateway children belong to the extended scope (RAI-038/039) and are recorded PENDING-here, never claimed. | Spend log per-attempt rows; shared root ledger |
| G6 | An unavailable doctor registry does not block an explicit model; unsupported required tools are blocked before spend. | The explicit-`--model` smoke runs with zero registry/discovery calls; the unknown-flag negative leg is a typed pre-dispatch refusal (exit 21, zero network). The doctor surface itself is exercised by the adapter's separate contract/stand profile, not by this baseline. | Preflight negative legs; spend log refusal rows |
| G7 | The optional Yabanin adapter passes a separate contract/stand profile; the base release does not require deployed Yabanin. | This card **is** that separate leg: the deterministic offline fixture passes with no deployed gateway; the live leg is separately owner-gated and optional. The direct cards evidence base-release independence from the other side. | Offline fixture outputs; ratification state of the live leg in the execution record |

## 8. Command argv templates

Offline legs (no network beyond loopback; no spend):

```sh
java -version
java -jar "$RAIDER_LIVE_JAR" help
# expect: command list, exit 0
java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider bogus --input x \
  --out "$RAIDER_LIVE_OUT/preflight"
# expect: typed refusal, exit 21, zero network

# Deterministic local contract fixture: the pinned build's documented
# local-stand entrypoint plays the gateway surrogate (loopback HTTP). The
# executor records the exact stand invocation; no new infrastructure and no
# second scheduler is built by this card.
#   asserted: gateway-style OpenAI-compatible responses parse and normalize;
#   lifecycle owner is the wrapper (Raider mints nothing);
#   a 401 stand leg yields a typed failure with zero non-gateway contact.
```

Live smoke (only after ratification; caps of §4 apply):

```sh
# The wrapper owns the session; Raider is the child process.
yb ci exec --session=on -- \
  java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider openai \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/gw-smoke" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS" \
  --run-deadline "$RAIDER_LIVE_RUN_DEADLINE"

# Auth negative leg (zero direct fallback proof): run with an invalid child
# credential against the gateway; expect a typed failure and zero requests
# to any direct provider origin.
yb ci exec --session=on -- \
  java -jar "$RAIDER_LIVE_JAR" run --agent scout --provider openai \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "$RAIDER_LIVE_INPUT" --workspace "$RAIDER_LIVE_WORKSPACE" \
  --out "$RAIDER_LIVE_OUT/gw-auth-negative" \
  --max-tokens "$RAIDER_LIVE_MAX_TOKENS"
```

Executor shell setup (values private; the file never carries them):

```sh
export RAIDER_LIVE_JAR=<<OWNER-FILLS-IN: pinned headless jar path>>
export RAIDER_LIVE_WORKSPACE=<<OWNER-FILLS-IN: disposable fixture workspace>>
export RAIDER_LIVE_OUT=<<OWNER-FILLS-IN: artifacts output directory>>
export RAIDER_LIVE_INPUT=<<OWNER-FILLS-IN: sanitized prompt file>>
export RAIDER_LIVE_MAX_TOKENS=<<OWNER-FILLS-IN: per-attempt output bound>>
export RAIDER_LIVE_RUN_DEADLINE=<<OWNER-FILLS-IN: whole-run seconds>>
# RAIDER_BASE_URL / RAIDER_MODEL / RAIDER_API_KEY (or RAIDER_API_KEY_CMD)
# are set by the wrapper or privately by the owner; values never written
# to any file. YABANIN_GATEWAY_URL / YABANIN_API_KEY / YABANIN_LABELS are
# wrapper-provided child envs.
```

## 9. Expected observable evidence

Per run, under `--out`:

- Artifact set: `run.json`, `result.json`, `events.jsonl`, `checks.junit.xml`,
  `summary.md`, `transcript.json`.
- `run.json` core: `schemaVersion`, `rootId`, `status`, typed `exitCode`
  (0/10/20/21/22/23/24/25/26/27/130/143 map as in the direct cards),
  `taskVerdict`, `checks[]`, `artifacts[]` (`artifactId`, `sha256`,
  `mediaType`, `sizeBytes`, `path`), `output`, `error`, `startedAt`,
  `finishedAt`.
- `run.json` accounting block: `reservedUsd`, `observedUsd`, `uncertainUsd`,
  `unpricedAttempts`, `lateSettlements`, `estimateViolations`, `unknown` —
  exact micro-USD rendering (six fractional digits; no floating point).
- `events.jsonl`: schema-versioned envelopes; `UsageObserved(attempt, usage,
  priceKnown)`.
- Execution record: endpoint identity without credentials, model id, pinned
  build and wrapper identity, ratified caps, the **session lifecycle owner
  per root stated as `wrapper`** (when the pinned build's `run.json` carries
  a lifecycle-owner field it must show that value; absence is recorded as a
  finding, never patched by prose), the zero-non-gateway-contact assertion
  from the auth negative leg, spend-log rows with the shape
  `requestNumber, provider, scenario, reserved, charged, costStatus
  (Observed/Estimated/Unknown), outcome (response / provider-auth-refusal /
  uncertain-failure)`, and per-gate G1–G7 findings.

## 10. Ratification checklist (owner fills before execution)

| Field | Value |
|---|---|
| Gateway URL | `<<OWNER-FILLS-IN>>` |
| Child credential channel (wrapper env or helper) | `<<OWNER-FILLS-IN>>` |
| Labels policy (lineage metadata only) | `<<OWNER-FILLS-IN>>` |
| Model id | `<<OWNER-FILLS-IN>>` |
| Wrapper binary identity/version | `<<OWNER-FILLS-IN>>` |
| Per-profile USD cap (live leg) | `<<OWNER-FILLS-IN>>` |
| Total USD cap (three cards) | `<<OWNER-FILLS-IN>>` |
| Request-count cap | `<<OWNER-FILLS-IN>>` |
| Per-attempt output bound / run deadline | `<<OWNER-FILLS-IN>>` |
| Live-leg approval (separate from offline fixture) | `<<OWNER-FILLS-IN>>` |
| Executor identity and date | `<<OWNER-FILLS-IN>>` |
| Approval reference | `<<OWNER-FILLS-IN>>` |

## 11. Non-goals (W17 boundary)

- No enriched `kind = "yabanin"` TOML claims (beta, RAI-037/038); no extended
  gateway accounting or live-stand work (RAI-038/039); no importers, bridges,
  or a second scheduler/runtime inside adapters; no wrapper implementation
  (the wrapper is owned by the Yabanin side); no edits to gate policy,
  verifiers, or the board by this card; no claims of exactly-once execution
  or durable resume.
