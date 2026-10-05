# Raider 10K — Agent State Registry & Yabanin Discovery Master Prompt

You are working on Raider, a self-hosting agent harness (Scala 3 + ZIO 2).
This prompt launches the **state workstream** of the long-range roadmap
(`docs/roadmap-10k.md` v2, §3.4–§3.5, Phase 3 + the Phase-7 discovery): an
OPTIONAL persistent state registry for agents, with a reserved direct
Yabanin integration slot. Work the tasks in order; skip a task only if
genuinely blocked and record why.

## Hard constraints (read first)

- The project stays **MIT-only**: runtime deps must never be copyleft
  (GPL/LGPL/AGPL forbidden; MPL/EPL/CDDL fail until allowlisted). The
  `license-audit` gate enforces this. No new runtime dependencies at all in
  this workstream (zio-json already exists).
- Product code: Scala 3 + ZIO only (`modules/**`). Python only under
  `scripts/quality`.
- ALL code compiles with `-Werror` (zero warnings); scalafmt/scalafix must
  pass (nulls banned).
- **Optionality is law**: when the state registry is not configured, NOTHING
  is constructed, no tools register, no events fire — behavior byte-identical
  to today. A fixture must assert the default CodingToolset tool list is
  unchanged when the option is absent.
- **Yabanin is read-only territory**: never modify the neighbor repository.
  Discovery = reading `docs/yabanin-integration.md` (the contract source of
  truth) and, if `YABANIN_REPO` points to a checkout, READ-ONLY file
  inspection. Cite files for every claim; the integration doc explicitly
  warns the OpenAPI is stale and `yb delegate` runs a FakeExecutor — do not
  fabricate contracts. Yabanin-side changes are separate YB-cards under
  Yabanin's own rules.
- **No hidden fallback**: a Yabanin backend that fails auth/policy fails
  typed; there is never a silent local/direct bypass (AGENTS rule).
- Never call live/paid endpoints from product tests; mock/replay only.
- If `MASTER-10K-PHASE0.md` runs in another session: build.sbt module
  registrations are a single-owner edit — sequence the two additions.

## Verification trio — after EVERY task

1. `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` — exit 0
2. `COURSIER_CACHE=/tmp/cc-master sbt --batch test` — 0 failures
3. `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1`
   — **10/10 gates passed** (fast profile includes `license-audit`)

Write a work record in `docs/work/records/` and update `docs/work/board.md`
after every task. Commit after every completed task:
`git add -A && git commit -m "feat(state-N): <description>"`.

## Tasks

### Task S.1 — Yabanin state-discovery report (~0.5 day)

Read `docs/yabanin-integration.md` end to end. If `YABANIN_REPO` is set,
READ-ONLY inspect the cited surfaces (session-keys API, decision metadata,
delegation controller persistence, any KV/session-store contract). Write
`docs/research/yabanin-state-discovery.md`:
- what Yabanin verifiably offers today (file citations),
- candidate contracts for a remote state backend (attribution-only sync /
  usage-correlated accounting / native KV if any),
- explicit "not proven" list,
- open questions for the owner.
Acceptance: every claim cites a file; zero invented APIs; report committed.

### Task S.2 — ADR-022: Agent State Registry (~0.5 day)

Write `docs/adr/ADR-022-agent-state-registry.md` (status: Accepted after
self-review) per roadmap §3.4: SPI (`get/put/delete/keys`), scopes
(`Agent(sessionId)` / `Root(rootId)` blackboard / `Global`), bounds (≤64 KiB
value default, per-scope key-count caps, sanitized keys), atomicity
(temp+rename, no transactions — document honestly), optionality
(default-off, fixtures in both modes), opt-in tool exposure
(`state_get`/`state_put`, `state_put` = `RecoveryClass.Mutating`, default
scope = calling agent's own), events (`StateRead/StateWritten/StateDeleted`
— stubbed via the existing tracer pattern until the Phase-1 bus lands), and
the reserved Yabanin backend slot (contract from S.1, implementation
Phase 7).
Acceptance: ADR committed; matches roadmap §3.4; no overclaims.

### Task S.3 — ADR-023 draft: Yabanin integration contract (~0.5 day)

Write `docs/adr/ADR-023-yabanin-integration.md` with status **Proposed**
(owner accepts after reviewing S.1): transport labels, session-key modes
(disabled/optional/required, no hidden fallback), state backend slot,
accounting sync (pending/partial/complete), agent-eval export. Mark every
TBD item explicitly; final acceptance happens in Phase 7 after the contract
is proven against a local stand.
Acceptance: ADR committed as Proposed with TBDs visible.

### Task S.4 — `raider.state` module v1 (~2 days)

New module `raider-state` (package `raider.state`) in build.sbt, depending
on `raiderCore` (+ zio/zio-json). Implement per ADR-022:
- `AgentStateStore` SPI, `Scope`, bounded/sanitized keys, typed errors
  (reuse `RaiderError.InputValidation`/`ToolFailed`/`JournalFailure`).
- `MemoryBackend` (tests) and `FileBackend`: JSON documents under
  `<base>/{agent|root|global}/<sanitized-key>.json`, atomic temp+rename
  (SessionStore patterns), corrupt file → typed error listing (skip, never
  crash), `keys()` listing.
- FIXTURES FIRST (ZIO Test, temp dirs, eager reads): roundtrip get/put/delete;
  scope isolation (agent A cannot see agent B; root shared by two session ids);
  value-size bound → typed denial; key sanitization (traversal rejected);
  atomic overwrite survives interrupted tmp (no half files); corrupt file →
  typed error + listing skips it; count caps.
Acceptance: all fixtures green; module rides `sbt-test` in the fast profile.

### Task S.5 — Opt-in wiring: tools + facade (~1 day)

- `CodingToolset.make(workspaceRoot, state: Option[AgentStateStore] = None)`:
  when `Some`, register `state_get {scope?, key}` / `state_put {scope?, key,
  value}` with JSON schemas (mirror existing tool registration style);
  when `None`, the registry is IDENTICAL to today — fixture asserts the exact
  tool-name list is unchanged.
- Chat: `:state [prefix]` listing behind the same option (absent option →
  "state registry not configured" message).
- REPL facade: `session.state` handle — `state("k") = <json>`,
  `state.get("k")` — only when the session was built with a store.
- `StateRead/StateWritten` tracer events on every operation (existing
  TraceEvent-style echo until the Phase-1 bus exists).
Acceptance: default-off fixtures; with-state fixtures (roundtrip through the
TOOL JSON contract incl. policy-denial feedback for out-of-bounds values);
blackboard demo test: two sessions sharing a `Root` scope.

### Task S.6 — Close-out (~0.5 day)

Update `docs/roadmap-10k.md` (Phase 3 progress markers), `docs/api.md`
(state registry section), `docs/contributing.md` (how to write a state
backend), the board, and the work record summarizing evidence (gate
manifests, fixture counts, discovery report path).

## Final verification checklist (run once at the end)

```sh
COURSIER_CACHE=/tmp/cc-master sbt --batch compile
COURSIER_CACHE=/tmp/cc-master sbt --batch test
COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1   # 10/10
python3 scripts/quality/license_audit.py --self-test                   # MIT-only
sh scripts/quality/repl_smoke.sh
sh scripts/quality/cli_smoke.sh
```

All must pass. Fix before committing; a timeout or skip is NOT a pass.

## Honesty rules (unchanged)

No invented checks, commits, URLs, or quality percentages. No fabricated
Yabanin APIs — cite files or mark unproven. If blocked, record the exact
blocker in the work record and move to the next task.
