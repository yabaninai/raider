# ROADMAP-V2-STATE-YABANIN: work record

Status: done. Date: 2026-10-05T16:40Z. Baseline commit/source: 0b7c2dc (docs: TUI design + Phase-0 prompt).

## Resulting behavior

Owner directive: add an **optional agent state registry** with a reserved
**direct Yabanin integration** slot; adjust the plan and add hours; provide a
launch prompt.

1. **Roadmap v2** (`docs/roadmap-10k.md`, rewritten): four capabilities
   (tabs / statuses / plugins / **agent state**); new §3.4 Agent State
   Registry (optional, default-off: SPI, scopes Agent/Root-blackboard/Global,
   bounded KV, atomic writes, opt-in tools, state events) and §3.5 Yabanin
   direct integration (discovery-first; transport labels + doctor +
   session-key modes; **state backend slot**; accounting sync; eval export;
   absolute no-hidden-fallback rule). Phases renumbered 0–11: new **Phase 3
   (state registry, 800h)** and **Phase 7 (Yabanin, 900h)**; total
   **10,000 → 11,700 h** (+1,700). Governance gains the **optionality rule**
   (optional features default OFF; fixtures assert both modes). Risks gain
   state-store-scope-creep, state-injection and Yabanin-contract-drift rows.
2. **Yabanin grounded, not guessed**: §3.5 and the new prompts are derived
   from `docs/yabanin-integration.md` (verified neighbor-repo research,
   2026-10-02) — including its warnings (stale OpenAPI, FakeExecutor in
   `yb delegate`, no direct DB access, separate YB-cards, no hidden
   fallback). The exact state-backend contract is explicitly deferred to a
   read-only discovery spike (Task S.1) — nothing invented here.
3. **Cross-reference renumbering** applied everywhere: ADR-016 and
   tui-design.md (web console → Phase 9), licensing.md (plugins → Phase 4),
   MASTER-10K-PHASE0.md (ADR-002→017, ADR-004→019, plus a build.sbt
   single-owner parallel note). ADR backlog is now one monotonic series
   (016 accepted; 017–023 planned).
4. **New launch prompt** `docs/prompts/MASTER-10K-STATE.md`: six tasks
   (S.1 Yabanin state-discovery report with file citations; S.2 ADR-022;
   S.3 ADR-023 Proposed with visible TBDs; S.4 `raider.state` module v1 —
   Memory/File backends, fixtures first; S.5 opt-in wiring with a
   default-off byte-identical fixture; S.6 close-out), the verification trio
   (10/10 gates incl. license-audit), MIT-only and read-only-Yabanin
   constraints, honesty rules.

## Changed paths

- `docs/roadmap-10k.md` (v2 rewrite: §1 mock shows state_put, §2 +state row,
  §3.4/§3.5 new, §4 phases 0–11 = 11,700h, §5 optionality rule, §6 risks,
  §7 non-goals, §8 renumbered refs, §9 ADR 017–023, §10 two launch prompts)
- `docs/prompts/MASTER-10K-STATE.md` (new)
- `docs/prompts/MASTER-10K-PHASE0.md` (ADR renumbering + parallel note)
- `docs/adr/ADR-016-tui-renderer.md`, `docs/tui-design.md`,
  `docs/licensing.md` (phase-reference fixes)

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Gates after the change (docs/ → docs profile; full-tree fast) | 10/10 passed | artifacts/quality/20261005-163220/manifest.json |
| Hours table sums to the stated total | 300+900+1400+800+1300+1300+1200+900+1100+900+1000+600 = 11,700 | roadmap §4 |
| Every Yabanin claim traceable | §3.5 + prompts cite docs/yabanin-integration.md sections; no invented APIs | docs review |
| Cross-refs consistent after renumbering | grep: no stale "Phase 7 web"/"Phase 3 plugin" refs outside historical records | grep output |
| Default-off guarantees encoded | optionality rule (§5) + S.5 byte-identical fixture requirement | roadmap + prompt |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 (10/10) | artifacts/quality/20261005-163220/manifest.json |

## Limitations / blockers

- ADR-023 is intentionally NOT written yet (its content depends on the S.1
  discovery); the prompt schedules it as a Proposed draft with TBDs.
- Phase 3's event wiring assumes the Phase-1 bus; until then the prompt
  mandates tracer-style stubs (explicitly bounded scope).
- No Yabanin checkout was inspected in this increment (YABANIN_REPO unset);
  §3.5 relies on the committed integration doc, and S.1 re-verifies against
  a live checkout when available.

## Review and next task

Owner: review roadmap v2 hour allocation and the two launch prompts. Next:
launch `MASTER-10K-STATE.md` (and/or `MASTER-10K-PHASE0.md`) with an
executor; both carry the build.sbt single-owner sequencing note.
