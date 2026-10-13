# RAI-155.a: work record — ADR-033 chat capture and workflow export contract

Status: done (contract ADR authored; independent review required before
acceptance and before implementation cards RAI-155.b–.f start). Date:
2026-10-14 UTC. Baseline: `7ce089d` (clean worktree `wt/rai-155a`, branch
`lane/rai-155a`, matching main HEAD at start). Worker: visible lane spawn.
No code, build, or gate-policy files were changed.

## Resulting behavior

Authored [ADR-033](../../adr/ADR-033-chat-workflow-export.md) — the explicit
chat capture and executable workflow export contract that gates the RAI-155
chain (.b–.f implement against it). Source audit first, per card: no
`:export-workflow` chat command and no `raider compile`/`raider workflow` CLI
target exist (`CliArgs.Target = Bundle | Agent | Replay | Resume`,
`CliArgs.scala:64-87`); chat persistence is a flat conversation transcript
(`SavedSession` schemaVersion 2, `Session.scala:36`) whose compaction drops
original messages (`ChatLoop.scala:500`); bundle v1 is the frozen no-arg SPI
that cannot host agent work (ADR-029 §3.2).

Decision summary:

1. **Packaging DECIDED: Option L (launcher artifact).** The exported workflow
   is ordinary editable Scala with an explicit `main` constructing the
   ADR-029 §3.1 launch capability; the same artifact runs locally and in
   non-TTY generic CI (ADR-029 §3.1 rule 5). Option B (bundle v1) is
   rejected: the no-arg SPI has no service injection and the runner admits
   one opaque outer slot settling `MicroUsd(0)` — an agent workflow behind
   v1 would be structurally unobservable and zero-cost (the H3 trap); the
   honest Option B route is bundle v2 (separate ADR + migration), out of
   scope. Bundle ABI stays unchanged (CWF-A01 "where feasible" satisfied by
   zero wire change). Roadmap §6d wording ("Prefer the existing
   BundleWorkflow v1 boundary") is flagged for coordinator alignment; the
   validated-JSON-inside-the-String-boundary discipline stands, the
   executable artifact is the launcher main.
2. **First scope frozen**: the Supported Task Specification — explicit
   sequential coding intent + ordered explicit refinements + named
   task/workspace parameters + named trusted check commands + one bounded
   agent stage + checks-must-pass policy; deterministic renderer; any
   model-assisted synthesis explicit and configured (off in the first
   slice); everything else typed refusal.
3. **Capture semantics**: an exportable turn ledger persisted alongside the
   message history (additive session schema v3), surviving save/load/
   compaction; legacy conversation-only sessions readable but export-refused
   typed (cannot invent absent execution records); pending/interrupted turns
   preserved with request + `Unknown` status.
4. **Export semantics**: deterministic render of a bounded immutable
   selection → editable source + non-secret manifest + versioned input
   example; export/build/compile dispatch no agent tools; exported text is
   data, never a shell instruction; authority never inherited from chat;
   credentials env/helper references only; secret-bearing selections
   refused; payloads externalized (editable without regeneration).
5. **Execution binding**: launcher binds current input/provider/workspace/
   limits/policy at execution (no chat authority); build identity digests
   invalidate stale claims; artifacts/exit codes per ci-runtime §7–8;
   SIGTERM ownership; non-TTY approval → NeedsApproval exit 25.
6. **Refusal taxonomy** (CWF-A02): nine typed cases R1–R9 mapped to existing
   sealed `RaiderError` families (dedicated family would need a separate
   core card), each with the actionable-message obligation.
7. **Honesty table** K1–K10: no arbitrary-session conversion, no durable
   resume, no forge/deploy, ADR-031 projection zero-dispatch and distinct
   (export = a fifth artifact layer), unknown pricing stays unknown
   (ADR-029 H3 cited), bundle ABI unchanged, trusted-Scala-not-sandbox,
   no hidden fallback, no implicit dispatch, proposals never advertised.
8. **Estimate proposal** (§12, coordinator allocates): .b/.c/.d/.e 16–24 h
   each, .f 8–16 h → 72–120 h total; highest risk = unscheduled ADR-029
   PKT-1 launch-capability prerequisite for .d (16–24 h if that route is
   chosen); tradeoff named (.f polish / .e corpus breadth are elastic;
   acceptance matrix, refusals, no-dispatch probes are not).

Consumer doc updates: `docs/runtime-contracts.md` gains §17 (contract
summary + honesty pointer); `docs/ci-runtime.md` gains §12 (launcher-artifact
refinement of §3, explicitly designed-not-implemented).

## Changed paths

- `docs/adr/ADR-033-chat-workflow-export.md` (added)
- `docs/runtime-contracts.md` (§17 added)
- `docs/ci-runtime.md` (§12 added)
- `docs/work/records/RAI-155a.md` (this record; draft kept in lane worktree
  per lane-worker contract; coordinator transfers to root after merge)

All within the assignment packet's allowed paths (docs/adr/**,
docs/runtime-contracts.md, docs/ci-runtime.md + the lane record). Public
APIs, schemas, and gate policy unchanged; no code changed.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| CWF-A01 (lifecycle/schemas/program shape/responsibilities; one runtime; unchanged ABI) | ADR-033 §3 (L decision, ABI unchanged), §4.1 (STS), §6.2–6.4 (layout/manifest/input schemas), §6.3 (program shape), §11.1 (responsibilities), §11.4 (traceability) + reference lifecycle | ADR-033 (sha256 `755bbc9a…`) |
| CWF-A02 (typed refusals; text-is-data; ABI versioning) | §8 taxonomy R1–R9 + text-is-data rule + ABI discipline clause | ADR-033 §8 |
| CWF-A03 (snapshot; reopen; pending turns; cancellation; CI policy; non-TTY refusal; projection untouched) | §5.3–5.4, §7, §9 | ADR-033 §9 |
| Estimate/hour-allocation obligation | §12 proposal with unknowns/tradeoffs (coordinator records allocation) | ADR-033 §12 |
| Option L vs B decided against ADR-029 §3.2 | §3.1–3.2 comparison table + justification | ADR-033 §3 |
| Independent review | NOT YET PERFORMED — required before acceptance and before .b–.f freeze anything | this record |

## Commands

| Command | Exit | Evidence |
| --- | --- | --- |
| `git status --short` (pre-work) | 0, empty | clean baseline `7ce089d` = main HEAD |
| `make quality-changed` | 0 | classified: ADR added + 2 docs modified, profiles=[docs] |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261014-015109-60522-0001/manifest.json`, status=passed, plan-validate exit=0 |
| `make quality-profile PROFILES="docs"` | 0 | `artifacts/quality/20261014-015113-60607-0001/manifest.json`, status=passed |

File hashes (this lane snapshot): ADR-033
`755bbc9aea457698083ef0b55e338df164e2f5b43bcaa772a8783981c87967ca`;
runtime-contracts.md
`b7f1e2b9afc7e1e453535563f61e02cb85fe20d61807af9a4a69476fc12fabaf`;
ci-runtime.md
`a7eb7f6cfee59fae285e45588a31d2c2f3a351a02ff5b8404d6b9b3dfcc3d005`.

## Limitations / blockers

- Contract only: no runtime/CLI/chat code was implemented; all command
  names, schemas, and signatures in ADR-033 are proposals — explicitly not
  implemented and not accepted as shipped.
- The ADR-029 launch capability (PKT-1) is unscheduled; .d's execution route
  (capability vs CLI-internal assembly with identical rules) is recorded in
  .d's own card before code. Not resolvable in this contract.
- Roadmap §6d wording ("Prefer the existing BundleWorkflow v1 boundary")
  needs the coordinator's alignment note per ADR-033 §3.2 (docs/tasks/** and
  docs/work/board.md are outside this lane's allowed paths).
- No runtime, consumer, provider, or paid checks were run (not in card
  scope); the docs profile executes plan-validate only.
- Pricing policy for unpriced models stays unknown (H3); all cost claims
  through the future exported path remain unpriced/unknown until the
  separate settlement card lands.

## Review and next task

Next: independent reviewer approval of ADR-033 (card acceptance condition,
checked against CWF-A01..03), then coordinator records the §12 allocation,
applies the §3.2 roadmap alignment note, and unblocks RAI-155.b (capture
ledger, session schema v3, selection/export commands) against the frozen
contract. RAI-155.c can start in parallel with .b once the §4/§6 STS and
export clauses are accepted.
