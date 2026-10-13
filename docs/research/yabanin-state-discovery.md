# Yabanin state-discovery report (Task S.1)

Status: complete as a **documentation-grounded** discovery. Date: 2026-10-05.
Feeds: ADR-022 `docs/adr/ADR-022-agent-state-registry.md` (reserved Yabanin
backend slot) and ADR-023 `docs/adr/ADR-023-yabanin-integration.md` (Proposed
contract draft, Phase 7 final). Note (2026-10-05, P0.A): both ADR files do not
exist yet — the numbers are reserved by the historical roadmap; links rendered
as plain paths so this research does not fabricate targets. Per
docs/roadmap-12k.md these contracts are P11-scope work.

## 0. Method and evidence base (honest limits first)

- `YABANIN_REPO` was **unset** in this session, so no neighbor checkout was
  inspected live. Per the state launch prompt, discovery without a checkout
  reads `docs/yabanin-integration.md` — the contract source of truth — and
  cites it for every claim. Nothing below claims live verification.
- The integration doc itself is grounded in a **read-only inspection of the
  sibling `tokenrouter` checkout, HEAD `0dbb287ac46be0dbf95d77f87a066baa4c42add3`,
  2026-10-02**, with SHA-256 hashes and clean working-tree status for 24 files
  recorded in [`docs/research/yabanin-snapshot.json`](yabanin-snapshot.json).
  Citations below use the form *Yabanin `<path>`* and point at that snapshot
  for integrity evidence.
- Two explicit traps from the integration doc bind this whole report:
  `docs/api/openapi.yaml` describes a **stale, narrower** surface than the
  current router, so **no client may be generated from the OpenAPI without
  contract checks** (`docs/yabanin-integration.md` §1, "Important discrepancy");
  and `yb delegate` in the studied code runs a **FakeExecutor by design** and
  is not a live Scala-agent backend (§1, last paragraph).
- Read-only discipline: Yabanin is not modified by this workstream; any
  Yabanin-side change is a separate YB-card under Yabanin's own rules
  (`docs/yabanin-integration.md` §12; `AGENTS.md`).

## 1. What Yabanin verifiably offers today (file citations)

Every row states the surface, the Yabanin source file that documents it, and
the section of [`docs/yabanin-integration.md`](../yabanin-integration.md)
where Raider's verified observation is recorded.

| # | Surface | Yabanin source(s) | Verified behavior relevant to state | Raider evidence |
| --- | --- | --- | --- | --- |
| 1 | OpenAI-compatible gateway (routing/cache/failover) | `README.md`, `docs/architecture.md`, `pkg/cli/gateway.go` | Inference endpoint only; no storage/KV endpoint is described on this surface | integration doc §1 row 1 |
| 2 | Chat Completions / Responses / Models routes | `pkg/cli/gateway.go`, `pkg/cli/models_endpoint.go`, `pkg/cli/responses_endpoint.go` | Current router is broader than the stale OpenAPI | §1 rows 2 + the "discrepancy" warning |
| 3 | **Session keys** mint/revoke/finish/usage | `pkg/cli/sessionkeys_api.go` | Routes `POST /v1/yabanin/session-keys`, `DELETE …/{id}`, `POST …/{id}/finish`, `GET …/{id}/usage`, `GET /v1/yabanin/usage`; mint requires a parent `yb_` key with `can_mint_sessions`; TTL/rate limits exist; numeric limits must be checked against a versioned contract, never hardcoded | §7 |
| 4 | **Attribution labels** | `docs/labels.md`, `pkg/labels/header.go`, `pkg/cli/labels_mw.go` | Headers `X-Session-Id`, `X-Yabanin-Task`, `X-Yabanin-Labels`; labels attribute spend, they never grant permission; limits observed: ≤20 labels per source, 32 effective default, value ≤128 chars, `yabanin.` prefix reserved; values percent-encoded per Go `url.PathEscape`, comma-separated pairs, keys sorted | §6 |
| 5 | **Decision metadata** | `pkg/cli/decision.go`, `pkg/cli/chat.go` | `X-Yabanin-Decision` records the gateway **routing** decision (requested/actual model evidence); it is explicitly NOT a statement about task correctness | §8 |
| 6 | Usage / accounting endpoints | `pkg/cli/sessionkeys_api.go` (usage routes) | Usage endpoint exposes `complete`, `unpriced_requests`, totals/by_model, optional groups; reconciliation is bounded; final record may be `accounting_pending/partial/complete` independent of `execution_status`; incomplete never promoted to complete by a timer | §7, §8 |
| 7 | Harness adapter registry | `docs/harness.md`, `pkg/harness/adapters/registry.go` | A sanctioned slot for a Raider adapter (`YB-RAI-01`) with its own config schema version, idempotent connect/disconnect, drift tests | §1 row 8, §10 |
| 8 | Persistent delegation controller | `pkg/delegation/controller/*`, `pkg/trcli/delegate.go` | A persistent **control-plane** controller for delegation exists; the integration doc's instruction is to match formats and NOT create a second controlling controller for one job; `yb delegate` runs a FakeExecutor by design | §1 rows 9 + last paragraph |
| 9 | Agent-eval contracts | `pkg/agenteval/contracts/*` (+ `schema/manifest.schema.json`, `episode.schema.json`, `call.schema.json`, `tool.schema.json`, `verdict.schema.json`) | Versioned JSON Schemas (2020-12) for `manifest/episodes/calls/tools/verdicts` — a measurement export surface | §1 row 10, §11 |
| 10 | CI child environment/wrapper | `docs/ci.md`, `pkg/trcli/ci.go` | `yb ci exec --session=on` wrapper-first integration; child-only URL/credential/labels; bounded poll; no second control plane | §1 row 3, §13 |
| 11 | Denial semantics | integration doc §8 (observed from `pkg/cli/chat.go`, `sessionkeys_api.go` behavior classes) | 429 rate-limit vs 429 budget-exceeded are distinguished by structured error; 403 model/whitelist denial is terminal; 503 datastore failure must NOT be bypassed via a direct provider | §8 |

**Key observation for the state workstream:** across all eleven cited
surfaces, **no KV / session-store / state-persistence API appears**. The
word "session" in Yabanin's vocabulary means *session keys* (a scoped
credential + accounting boundary), not a stored conversation or state
document. Row 8's delegation controller persists control-plane state, but the
doc directs us to map formats, not to use it as a KV store.

## 2. Candidate contracts for a remote state backend

The roadmap (§3.4/§3.5) reserves a `yabanin` backend slot in
`raider.state`. Three candidates survive discovery; they are ordered by how
much proven surface they reuse.

### 2.1 Candidate A — attribution-only sync (no remote storage)

State stays **local** (FileBackend is the persistence plane). What crosses to
Yabanin is **attribution**: state-scope identity rides the proven label
namespace (row 4) so that gateway-side analytics can correlate spend with
`raider.root/job/attempt` and a bounded `raider.state-scope=<id>` style label.

- Proven inputs: labels header + limits + PathEscape encoding (§6).
- Honest scope: this is **usage attribution for state-heavy runs**, not state
  storage. Nothing remote can be read back; `get` is always local.
- Risks: label cardinality — the doc already warns that high-cardinality
  root/job/attempt labels must NOT be auto-added to hourly rollup dimensions
  (§6); a per-scope label would need the same treatment (request logs/export
  first).
- Verdict: feasible with zero unproven API surface; weakest capability.

### 2.2 Candidate B — usage-correlated accounting for state workflows

Session keys (row 3) already give "one bounded session key per root workflow,
shared by descendants, TTL/cap/labels root-pinned" (§7). A state backend could
keep **accounting correlation records** per scope: `{scope, sessionKeyId,
usageSnapshot, accountingState ∈ pending/partial/complete}` — using the
proven usage routes (`GET /v1/yabanin/session-keys/{id}/usage`,
`GET /v1/yabanin/usage`) and the honest pending/partial/complete states (§8).

- Proven inputs: session-key routes, usage fields, reconciliation semantics.
- Honest scope: again **not KV**; the store persists *correlation*, and the
  durable agent state remains local.
- Risks: numeric limits/TTLs are deployment-dependent ("numeric limits
  must be checked against the versioned contract"); usage poll must be bounded, never
  timer-promoted to complete.
- Verdict: feasible on proven routes; useful for Phase-7 accounting sync
  regardless of which candidate wins.

### 2.3 Candidate C — native KV (state stored in Yabanin)

**Not proven.** No cited surface exposes a KV/session-store API (see the key
observation in §1). If a deployment ships one later, the contract would have
to be fixed by a read-only discovery spike against that deployment's
versioned contract fixtures — never generated from the stale OpenAPI (§1
warning).

- Consequence encoded in ADR-022/ADR-023: until a native KV contract is
  proven, **`FileBackend` is the only persistent backend**; the `yabanin`
  slot ships as attribution/sync-only (A+B) in Phase 7 and the storage gap is
  documented, per roadmap §4 Phase 7 ("if no KV contract exists, ship
  attribution/sync-only and document the gap").

## 3. Explicit "not proven" list

1. Any KV / session-store / state-persistence HTTP API in Yabanin — absent
   from all cited surfaces (§1); nothing may be invented.
2. Live behavior of any endpoint from this machine — no checkout was
   inspected (`YABANIN_REPO` unset) and no deployment exists in scope; every
   claim above is a documentation-grounded observation.
3. Whether `X-Yabanin-Decision` carries any metadata usable for state
   attribution beyond routing evidence — the doc restricts its meaning to the
   gateway routing decision (§8).
4. Whether the delegation controller's persistence (row 8) has any readable
   format a Raider state backend could map to — the doc only sanctions
   matching formats to avoid a second controller (§1).
5. Label overflow behavior cross-language (Space/`+`/`/`/`,`/`=`/Unicode
   under Go `url.PathEscape`) — requires golden tests on a real stand (§6).
6. Session-key numeric limits, TTL, rate limits, and DB-availability
   behavior — deployment/versioned-contract checks only (§7).
7. Session-cap strictness — source shows post-hoc budget logic; the presence
   of a `max_cost_usd` field does not prove zero overshoot (§8).
8. Cache policy for tool-bearing/state-bearing requests — must be fixture-
   proven before live acceptance (§9).
9. The OpenAPI file as a contract source — explicitly stale (§1); never a
   basis for client generation.

## 4. Open questions for the owner

1. **Native KV intent**: is a native KV/session-store API planned for any
   Yabanin deployment, or is attribution+accounting sync (candidates A+B) the
   intended end state for the Phase-7 "state backend v1"?
2. **Reference stand**: which Yabanin deployment/version is the pinned stand
   for Phase-7 contract proof (roadmap §4 Phase 7 requires state-backend
   fixtures against a local stand)?
3. **Label cardinality policy**: should a state-scope identifier be emitted
   as a request label at all, or only into request logs/export (the doc
   warns against high-cardinality rollup dimensions)?
4. **Eval export**: should `StateRead/StateWritten/StateDeleted` events be
   exported into the agent-eval `tools.jsonl`/`calls.jsonl` streams as
   digest-only entries (§11 keeps arguments as digests, never raw secrets)?
5. **YB-RAI-01 timing**: the harness adapter card (row 7) is on Yabanin's
   side; should its scheduling be coordinated with Phase 7 start or run
   independently?

## 5. Decision carried forward

ADR-022 fixes the local SPI (`Memory`/`File` backends now) and reserves the
`yabanin` backend slot with the contract TBD-pending Phase-7 proof. ADR-023
(draft, Proposed) records candidates A/B/C with the TBDs of §3/§4 visible.
No client code is written against any unproven surface, and no paid or live
endpoint is contacted by any product test.
