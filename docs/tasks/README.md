<!-- Translated from the Russian original. Key terms preserved as-is. -->

# Raider implementation cards

Current clarification, 2026-10-03: [working target](../working-product.md), [parallel runbook](../parallel-development.md), [stage-aware gates](../quality-gates.md). Original IDs are preserved; milestone acceptance and a useful coding-agent result are distinct.

Current IDs, statuses, and dependencies are in [backlog.json](backlog.json) and the [execution board](../work/board.md). The original 53 Raider cards have been extended by later revisions; todo/ready/accepted are determined from the current cards and their evidence.

| Scope | Document |
| --- | --- |
| M0 001–026 | [Foundation/runtime/protocols/REPL/CI](m0.md) |
| M1 027–040 | [Extensibility and optional integrations](m1.md) |
| M2 041–047 / P2 048–053 | [Release/P2](m2-p2.md) |
| YB-RAI-01–03 | [Changes in Yabanin](yabanin.md) |
| Machine metadata/dependencies | [backlog.json](backlog.json) |

## Assigning work

1. Choose a card with accepted prerequisites. Start with RAI-001 → RAI-003 → RAI-002 → RAI-004; then config/testkit/runtime.
2. Read AGENTS.md, the [main plan](../PLAN.md), applicable contracts, and the exact card.
3. Narrow allowed directories to concrete files/symbols. A coding packet should cover one observable behavior, usually 1–3 production files.
4. Split a large milestone card into `.a/.b/.c` before execution: accepted signatures, their own tests/paths/dependencies/records. Preserve all parent acceptance; do not hide new architecture inside a child task.
5. Start with a behavioral fixture; then implement. The [review workflow](../model-workflow.md) and [quality gates](../quality-gates.md) are mandatory.
6. Create a [work record](../templates/work-record.md) after actual work and before the final fingerprint gate. Link actual evidence in ignored artifacts; do not mark todo as accepted based on a model summary.

## Shared Definition of Done

Accepted dependencies; normal/error/cancel fixtures; required profiles executed, current, and passing; fresh independent review; current docs/API fixtures; no new unresolved blocker; accurate limitations. All profiles always include runner self where applicable. Missing tool/stand/scanner/hosted-runner evidence is unavailable/pending, never passed.

RAI-026 is joint acceptance for M0. RAI-047 is the M2 release candidate, with no implied publishing. Optional Yabanin/bridges do not block a standalone release, but any advertised capability needs its own complete evidence. P2 is not automatically included in v1.

## Dependency index

Markdown contains the full contract; JSON is canonical for ID/title/dependency/profile metadata, and the docs gate checks their consistency. Keep current progress in a separate board/work records; do not lose original acceptance criteria. Add child cards to backlog metadata when assigned, recording parent linkage.

- **RAI-001** [Feasibility of headless mode and a real Scala REPL](m0.md); dependencies: none; profiles: bootstrap.
- **RAI-002** [Frozen public APIs and versioned schemas](m0.md); dependencies: RAI-001, RAI-003; profiles: static, contracts, docs.
- **RAI-003** [Pinned build and distribution separation](m0.md); dependencies: RAI-001; profiles: bootstrap, static, contracts.
- **RAI-004** [Quality runner, classifier, and evidence](m0.md); dependencies: RAI-002, RAI-003; profiles: self, static, contracts, security.
- **RAI-005** [Profiles, policies, and independent provider config](m0.md); dependencies: RAI-002, RAI-004; profiles: static, unit, contracts, security.
- **RAI-006** [Deterministic testkit without LLM](m0.md); dependencies: RAI-002, RAI-004; profiles: static, unit, contracts.
- **RAI-007** [Job registry and atomic lifecycle](m0.md); dependencies: RAI-002, RAI-006; profiles: static, unit, contracts, runtime.
- **RAI-008** [Structured scopes and subtree cancellation](m0.md); dependencies: RAI-007; profiles: static, unit, contracts, runtime.
- **RAI-009** [Root budget and admission without deadlocks](m0.md); dependencies: RAI-005, RAI-008; profiles: static, unit, contracts, runtime, performance.
- **RAI-010** [Native agent loop](m0.md); dependencies: RAI-005, RAI-006, RAI-009; profiles: static, unit, contracts, runtime, eval.
- **RAI-011** [OpenAI-compatible HTTP Chat](m0.md); dependencies: RAI-005, RAI-006, RAI-010; profiles: static, unit, contracts, transport, security.
- **RAI-012** [OpenAI SSE streaming state machine](m0.md); dependencies: RAI-011; profiles: static, unit, contracts, transport, runtime.
- **RAI-013** [Anthropic-compatible Messages and streaming](m0.md); dependencies: RAI-010, RAI-012; profiles: static, unit, contracts, transport, runtime, security.
- **RAI-014** [Read/search with workspace containment](m0.md); dependencies: RAI-005, RAI-008; profiles: static, unit, tools, security.
- **RAI-015** [Safe mutation and patch artifacts](m0.md); dependencies: RAI-014; profiles: static, unit, tools, runtime, security.
- **RAI-016** [Owned process tools and isolated execution](m0.md); dependencies: RAI-005, RAI-008, RAI-014; profiles: static, unit, tools, runtime, security, ci.
- **RAI-017** [Unified typed composition](m0.md); dependencies: RAI-002, RAI-008, RAI-009; profiles: static, unit, contracts, runtime, docs.
- **RAI-018** [Ergonomic REPL launch facade](m0.md); dependencies: RAI-010, RAI-017; profiles: static, unit, contracts, runtime, repl, docs.
- **RAI-019** [Pinned compiler/JLine REPL adapter](m0.md); dependencies: RAI-001, RAI-018; profiles: static, unit, contracts, repl, security, performance.
- **RAI-020** [Prelude, registry commands, and definition loading](m0.md); dependencies: RAI-005, RAI-019; profiles: static, unit, contracts, repl, docs, security.
- **RAI-021** [Steering mailbox and checked model delegation](m0.md); dependencies: RAI-010, RAI-018, RAI-020; profiles: static, unit, contracts, runtime, eval.
- **RAI-022** [Compiled bundles and headless Runner](m0.md); dependencies: RAI-002, RAI-005, RAI-010, RAI-017; profiles: static, unit, contracts, ci, runtime, docs, security.
- **RAI-023** [CI context and trust preflight](m0.md); dependencies: RAI-005, RAI-022; profiles: static, unit, contracts, ci, security.
- **RAI-024** [Trusted checks, artifacts, and exit contracts](m0.md); dependencies: RAI-002, RAI-016, RAI-022, RAI-023; profiles: static, unit, contracts, ci, tools, security, eval.
- **RAI-025** [GitHub/GitLab/generic wrappers](m0.md); dependencies: RAI-023, RAI-024; profiles: static, unit, contracts, ci, docs, security.
- **RAI-026** [Joint MVP acceptance](m0.md); dependencies: RAI-012, RAI-013, RAI-014, RAI-015, RAI-016, RAI-017, RAI-018, RAI-019, RAI-020, RAI-021, RAI-022, RAI-023, RAI-024, RAI-025; profiles: full, ci, repl, transport, eval.
- **RAI-027** [Journal and archiving without replay](m1.md); dependencies: RAI-026; profiles: static, unit, contracts, runtime, ci, security.
- **RAI-028** [Typed agents, tools, and bounded output repair](m1.md); dependencies: RAI-002, RAI-010, RAI-027; profiles: static, unit, contracts, runtime, eval, docs.
- **RAI-029** [Definition loaders and context policy](m1.md); dependencies: RAI-020, RAI-027; profiles: static, unit, contracts, docs, security.
- **RAI-030** [Context limits and compaction](m1.md); dependencies: RAI-027, RAI-028, RAI-029; profiles: static, unit, contracts, runtime, eval, security.
- **RAI-031** [Versioned JVM plugin SPI](m1.md); dependencies: RAI-028, RAI-029; profiles: static, unit, contracts, runtime, security, compatibility.
- **RAI-032** [Pi profile importer with provenance](m1.md); dependencies: RAI-029, RAI-031; profiles: static, unit, contracts, docs, compatibility, security, eval.
- **RAI-033** [OpenCode profile importer](m1.md); dependencies: RAI-029, RAI-031; profiles: static, unit, contracts, docs, compatibility, security, eval.
- **RAI-034** [Pi RPC external backend](m1.md); dependencies: RAI-027, RAI-031, RAI-032; profiles: static, unit, contracts, runtime, tools, transport, compatibility, security.
- **RAI-035** [OpenCode HTTP external backend](m1.md); dependencies: RAI-027, RAI-031, RAI-033; profiles: static, unit, contracts, runtime, transport, compatibility, security.
- **RAI-036** [Conversation sessions with serialized turns](m1.md); dependencies: RAI-027, RAI-030; profiles: static, unit, contracts, runtime, repl, security.
- **RAI-037** [Optional Yabanin profile, labels, and wrapper env](m1.md); dependencies: RAI-011, RAI-012, RAI-023, RAI-027; profiles: static, unit, contracts, transport, ci, security.
- **RAI-038** [Yabanin root session keys and finish](m1.md); dependencies: RAI-037, RAI-009; profiles: static, unit, contracts, transport, runtime, ci, security.
- **RAI-039** [Pinned Yabanin stand acceptance](m1.md); dependencies: RAI-037, RAI-038; profiles: integration, ci, transport, security.
- **RAI-040** [Agent-eval contract export](m1.md); dependencies: RAI-027, RAI-028, RAI-024; profiles: static, unit, contracts, eval, security.
- **RAI-041** [Protected task eval and adversarial corpus](m2-p2.md); dependencies: RAI-024, RAI-028, RAI-040; profiles: eval, ci, contracts, security.
- **RAI-042** [Accounting reconciliation without fake money](m2-p2.md); dependencies: RAI-038, RAI-039, RAI-040; profiles: static, unit, contracts, runtime, transport, integration, ci.
- **RAI-043** [Soak and calibrated performance](m2-p2.md); dependencies: RAI-026, RAI-027, RAI-030; profiles: performance, runtime, ci, repl.
- **RAI-044** [JVM/OCI distribution and supply-chain inventory](m2-p2.md); dependencies: RAI-022, RAI-026, RAI-031; profiles: release, ci, security, docs.
- **RAI-045** [Raider's own development CI](m2-p2.md); dependencies: RAI-004, RAI-026, RAI-044; profiles: self, full, security, ci.
- **RAI-046** [Executable examples and documentation](m2-p2.md); dependencies: RAI-026, RAI-028, RAI-032, RAI-033, RAI-044; profiles: docs, contracts, ci, repl, compatibility.
- **RAI-047** [Release candidate acceptance](m2-p2.md); dependencies: RAI-041, RAI-043, RAI-044, RAI-045, RAI-046; profiles: release, full, ci, security.
- **RAI-048** [Daemon attach and explicit job ownership](m2-p2.md); dependencies: RAI-047; profiles: static, unit, contracts, runtime, security, ci.
- **RAI-049** [Recovery of registered serializable plans only](m2-p2.md); dependencies: RAI-027, RAI-047; profiles: static, unit, contracts, runtime, eval, security.
- **RAI-050** [Shared budget authority across CI jobs](m2-p2.md); dependencies: RAI-009, RAI-042, RAI-047; profiles: static, unit, contracts, runtime, integration, security.
- **RAI-051** [Capped live model comparison](m2-p2.md); dependencies: RAI-040, RAI-041, RAI-047; profiles: eval, integration, security.
- **RAI-052** [raceSuccess/quorum coordination](m2-p2.md); dependencies: RAI-017, RAI-043, RAI-047; profiles: static, unit, contracts, runtime, eval, performance.
- **RAI-053** [MCP tool adapter](m2-p2.md); dependencies: RAI-031, RAI-047; profiles: static, unit, contracts, transport, tools, security, compatibility.
- **YB-RAI-01** [Connect Raider to the yb harness](yabanin.md); dependencies: RAI-037, RAI-038; profiles: yabanin-quality-changed, yabanin-quality-full, integration.
- **YB-RAI-02** [Real Raider executor instead of FakeExecutor](yabanin.md); dependencies: RAI-022, RAI-024, RAI-039, YB-RAI-01; profiles: yabanin-quality-changed, yabanin-quality-full, integration.
- **YB-RAI-03** [CI/root analytics drilldown](yabanin.md); dependencies: RAI-040, RAI-042, YB-RAI-01; profiles: yabanin-quality-changed, yabanin-quality-full, integration.
- **RAI-054** [Quality runner: manifest/execute classification defect](m0.md); dependencies: none; profiles: self, static, docs.
- **RAI-055** [Event envelopes and snapshot/subscribe hub (P1.A slice)](m1.md); dependencies: none; profiles: static, unit, contracts, runtime.
- **RAI-056** [Inline renderer PTY spike and UX baseline (P0.C)](m0.md); dependencies: none; profiles: self, static, repl, docs.
- **RAI-057** [SessionRegistry: identity/focus and per-tab state (P2.A slice)](m1.md); dependencies: RAI-055; profiles: static, unit, contracts.
- **RAI-058** [AgentLoop: emit events to EventHub (P1.B-1)](m1.md); dependencies: RAI-055; profiles: static, unit, contracts, runtime.
- **RAI-059** [JobManager/budget/delegation emission and onTransition hook (P1.B-2)](m1.md); dependencies: RAI-055; profiles: static, unit, contracts, runtime.
- **RAI-060** [Status line/timeline and headless event export (P1.C)](m1.md); dependencies: RAI-058, RAI-059; profiles: static, unit, contracts, ci, runtime.
- **RAI-061** [Inline tab shell on JLine (P2.B)](m2-p2.md); dependencies: RAI-057; profiles: static, unit, contracts.
- **RAI-062** [PTY integration for tabs/runtime and headless regression (P2.C)](m2-p2.md); dependencies: RAI-060, RAI-061; profiles: self, static, repl, ci, docs.
- **RAI-063** [OOB subtree cancel, deadlines, and stuck-child fix (P3.B)](m1.md); dependencies: RAI-055; profiles: static, unit, contracts, runtime.
- **RAI-064** [Credentials through env/helper only (setup without argv secrets)](m0.md); dependencies: none; profiles: static, unit, contracts, security.
- **RAI-065** [Bounded mailbox for send (P3.A slice)](m1.md); dependencies: none; profiles: static, unit, contracts, runtime.
- **RAI-066** [Plugin SPI ADR-025 and narrow skeleton (P4.A)](m2-p2.md); dependencies: none; profiles: static, unit, contracts, docs.
- **RAI-067** [Multi-file patch and partial-failure contract (RAI-015 remainder)](m2-p2.md); dependencies: none; profiles: static, unit, contracts, tools.
- **RAI-068** [REPL completion (RAI-020 slice)](m1.md); dependencies: none; profiles: static, unit, contracts, repl.
- **RAI-069** [Security profile in gate registry (gate-policy card)](m0.md); dependencies: none; profiles: self, static, security.
- **RAI-070** [UX baseline harness (P0.C remainder / P9 prep)](m0.md); dependencies: none; profiles: self, static, docs.
- **RAI-071** [--provider anthropic in CLI (W05 parity)](m1.md); dependencies: RAI-060; profiles: static, unit, contracts, transport.
- **RAI-072** [Docs refresh: events/tabs/cancel API](m2-p2.md); dependencies: none; profiles: docs.
- **RAI-073** [Delegated-race fix: emit before race with Terminal](m1.md); dependencies: RAI-059; profiles: static, unit, contracts, runtime.
- **RAI-074** [Mailbox wiring in DelegationToolset](m1.md); dependencies: RAI-065, RAI-073; profiles: static, unit, contracts, runtime.
- **RAI-075** [Plugin loader and CLI commands](m2-p2.md); dependencies: RAI-066; profiles: static, unit, contracts.
- **RAI-076** [StateStore SPI (P5.A slice)](m1.md); dependencies: RAI-060; profiles: static, unit, contracts, runtime.
- **RAI-077** [Session schema v2 and migrations](m1.md); dependencies: RAI-060; profiles: static, unit, contracts.
- **RAI-078** [CI context parsing (RAI-023/025 slice)](m2-p2.md); dependencies: RAI-060; profiles: static, unit, contracts.
- **RAI-079** [Anthropic HTTP stand tool-round test](m1.md); dependencies: RAI-071; profiles: static, unit, contracts, transport.
- **RAI-080** [Onboarding guide (UX01 prep)](m0.md); dependencies: RAI-071; profiles: docs.
- **RAI-081** [Eval task fixtures (authoring, protected)](m2-p2.md); dependencies: RAI-060; profiles: docs.
- **RAI-082** [Accounting view (P6.B slice)](m2-p2.md); dependencies: RAI-060; profiles: static, unit, contracts.
- **RAI-083** [PluginCli wiring in Main (P4.B completion)](m2-p2.md); dependencies: RAI-075; profiles: static, unit, contracts.
- **RAI-084** [Bounded hook execution (P4.A finish)](m2-p2.md); dependencies: RAI-066; profiles: static, unit, contracts.
- **RAI-085** [state_get/put opt-in tools (P5.B)](m1.md); dependencies: RAI-076; profiles: static, unit, contracts, tools.
- **RAI-086** [HeadlessRunner wiring: CiContext + AccountingView](m2-p2.md); dependencies: RAI-078, RAI-082; profiles: static, unit, contracts, ci.
- **RAI-087** [Journal/checkpoint slice (P6.A)](m2-p2.md); dependencies: RAI-055; profiles: static, unit, contracts, runtime.
- **RAI-088** [TabShell :plugins/:state screens](m1.md); dependencies: RAI-075, RAI-076; profiles: static, unit, contracts.
- **RAI-089** [UX03/04 baseline at root](m0.md); dependencies: RAI-070; profiles: self, static, docs.
- **RAI-090** [REPL live env-key](m1.md); dependencies: RAI-064; profiles: static, unit, repl.
- **RAI-091** [forbidden-apis location allowlist (gate-policy)](m0.md); dependencies: RAI-069; profiles: self, static.
- **RAI-092** [Stabilization: delegation test flakes under load](m1.md); dependencies: RAI-073; profiles: static, unit, runtime.
- **RAI-093** [Journal→AgentLoop wiring (P6.A finish)](m1.md); dependencies: RAI-087, RAI-058; profiles: static, unit, contracts, runtime.
- **RAI-094** [StateTools → CodingToolset + TabShell provider](m1.md); dependencies: RAI-085, RAI-088; profiles: static, unit, contracts, tools.
- **RAI-095** [Plugin hook invocation in loop (lifecycle)](m2-p2.md); dependencies: RAI-084; profiles: static, unit, contracts, runtime.
- **RAI-096** [P4.C SDK: docs + 3 fixture plugins](m2-p2.md); dependencies: RAI-075; profiles: docs, static.
- **RAI-097** [Unified status render for CLI and tabs](m1.md); dependencies: RAI-060, RAI-061; profiles: static, unit, contracts.
- **RAI-098** [REPL live Anthropic](m1.md); dependencies: RAI-090; profiles: static, unit, repl.
- **RAI-099** [M1 acceptance card (milestone evidence)](m1.md); dependencies: RAI-062, RAI-060; profiles: docs.
- **RAI-100** [GitHub/GitLab wrappers wiring](m2-p2.md); dependencies: RAI-078; profiles: static, unit, contracts, ci.
- **RAI-101** [Plugin update/rollback lifecycle](m2-p2.md); dependencies: RAI-075; profiles: static, unit, contracts.
- **LIN-01** [SessionGraph model and events → graph reducer](lineage.md); dependencies: RAI-004; profiles: static, unit, contracts.
- **LIN-02** [Project the graph into a valid Scala program](lineage.md); dependencies: LIN-01, RAI-017; profiles: static, unit, contracts, docs.
- **LIN-03** [Live incremental graph and projection support](lineage.md); dependencies: LIN-01, LIN-02; profiles: static, unit, runtime.
- **LIN-04** [Lineage tab in the TUI](lineage.md); dependencies: LIN-03; profiles: static, unit, repl, docs.
- **LIN-05** [Replay-recorded interpreter and graph comparator](lineage.md); dependencies: LIN-02, LIN-03; profiles: static, unit, contracts, eval.
- **LIN-06** [Live rerun, mutation isolation, and diff report](lineage.md); dependencies: LIN-05; profiles: static, unit, runtime, security.
- **LIN-07** [Partial replay of phases/subtrees](lineage.md); dependencies: LIN-05; profiles: static, unit, contracts.
- **LIN-08** [Scopes (chat/headless/campaigns), pinning, catalog](lineage.md); dependencies: LIN-03, LIN-05; profiles: static, unit, docs.
- **RAI-102** [TabShell in the product REPL (chat wiring base)](m2-p2.md); dependencies: RAI-061, RAI-062; profiles: static, unit, contracts, repl.
- **RAI-103** [Per-tab drafts in the product REPL (P2 remainder)](m2-p2.md); dependencies: RAI-102; profiles: static, unit, repl.
- **RAI-104** [Scrollback bounds in the product REPL (P2 remainder)](m2-p2.md); dependencies: RAI-102; profiles: static, unit, repl.
- **RAI-105** [Unread badges in the product REPL (P2 remainder)](m2-p2.md); dependencies: RAI-102; profiles: static, unit, repl.
- **RAI-106** [Palette/help in the product REPL (P2 remainder)](m2-p2.md); dependencies: RAI-102; profiles: static, unit, repl, docs.
- **LIN-09** [Write lineage artifacts beside the production run](lineage.md); dependencies: LIN-03; profiles: static, unit, contracts, ci.
- **LIN-10** [CLI command surface for replay](lineage.md); dependencies: LIN-05; profiles: static, unit, contracts, docs.
- **LIN-11** [LineageRenderer: compilable graph projections without named-def roots](lineage.md); dependencies: LIN-02; profiles: static, unit, contracts.
- **RAI-107** [Plan mode: read-only plan phase and approval transition (ADR-028)](m2-p2.md); dependencies: RAI-010; profiles: static, unit, contracts, runtime, eval.
- **RAI-108** [Product entry point: TabShellHost in Main.ttyLoop (--tabs)](m2-p2.md); dependencies: RAI-102, RAI-062; profiles: static, unit, contracts, repl.
- **RAI-109** [Add replay to the raider product command (CLI wiring for LIN-10)](lineage.md); dependencies: LIN-09, LIN-10; profiles: static, unit, contracts, ci, docs.
- **RAI-110** [Plan mode: TUI plan screen and approval (ADR-028 §3, owner: TUI screen)](m2-p2.md); dependencies: RAI-107; profiles: static, unit, contracts, repl, runtime.
- **RAI-111** [Palette: REPL cycle chat commands (RAI-068 follow-up)](m2-p2.md); dependencies: RAI-106, RAI-068; profiles: static, unit, repl.
- **LIN-12** [Tree catalog persistence (LIN-08 remainder)](lineage.md); dependencies: LIN-08, LIN-09, LIN-10; profiles: static, unit, contracts, ci.
- **RAI-112** [M1 evidence refresh on the integrated candidate (RAI-099 refresh)](m2-p2.md); dependencies: RAI-099; profiles: docs.
- **LIN-13** [Sync docs/lineage.md §9.2 — truthful implementation status](lineage.md); dependencies: LIN-09, RAI-108, RAI-109; profiles: docs.
- **LIN-14** [LIN2-03 remainder: concurrent projection reads — snapshot coverage](lineage.md); dependencies: LIN-02; profiles: static, unit, contracts.
- **LIN-15** [LIN-06 scripted acceptance — LIN6-01/02/03 (budget/isolation/cancellation)](lineage.md); dependencies: LIN-06; profiles: static, unit, contracts, runtime.
- **RAI-113** [Scrollback render consumer in tabs mode (P2S consumer step)](m2-p2.md); dependencies: RAI-104, RAI-108; profiles: static, unit, repl.
- **RAI-114** [Plan-mode eval fixtures: scripted scenario corpus (eval profile prerequisite)](m1.md); dependencies: RAI-107; profiles: static, unit, contracts.
- **RAI-115** [Embedding SDK audit: public boundary and packet for ADR (P0.A / P4.C)](m1.md); dependencies: none; profiles: docs.
- **RAI-116** [ADR: Scala workflow SDK for local/generic CI and host launch boundary](m1.md); dependencies: RAI-115; profiles: docs.
- **RAI-117** [ADR: SDLC stages, configurable checkpoints, and generic integration/release contract](m1.md); dependencies: RAI-116; profiles: docs.
- **RAI-119** [PTY determinism under load: PlanPanelSpec / TabShellUnreadPtySpec / MainScrollbackPtySpec](m2-p2.md); dependencies: RAI-110, RAI-105, RAI-113; profiles: static, unit, repl, runtime.
- **RAI-120** [IsolatedRerun canonical-path guard (requireIsolatedTarget alias bypass)](lineage.md); dependencies: LIN-15; profiles: static, unit, contracts, runtime.
- **RAI-121** [:scrollback precedence — align with frozen :switch](m2-p2.md); dependencies: RAI-113; profiles: static, unit, repl.
- **RAI-123** [PlanPanel outcome scoping: per-job outcomeRef (candidate based on RAI-119 reviewer risk)](m2-p2.md); dependencies: RAI-119, RAI-110; profiles: static, unit, repl, runtime.
- **RAI-122** [tabs_pty_probe: sync with ADR-024 honest-unknowns status text](m1.md); dependencies: RAI-112, RAI-062; profiles: static, unit, docs.
- **RAI-124** [REPL :type — expression type without evaluation (RAI-019/020 slice)](m2-p2.md); dependencies: RAI-068; profiles: static, unit, repl.
- **RAI-126** [PTY probes COURSIER_CACHE: env.setdefault instead of hardcoded override](m1.md); dependencies: RAI-122; profiles: static, docs.

See [backlog.json](backlog.json) for cards added in later revisions, including LIN and P2 work.

## Existing obligations added on 2026-10-13

These cards were already present in the local backlog when documentation work began; their metadata is preserved and their Markdown mirrors are now synchronized.

| Card | Milestone | Dependencies | Profiles |
| --- | --- | --- | --- |
| **RAI-127** [Anthropic tool definitions on wire (structured message contract)](m1.md)) | M1 | RAI-013 | static, unit, contracts, transport |
| **RAI-128** [Cross-root fairness: named test with ≥2 roots under contention (Admission)](m1.md)) | M1 | RAI-009 | static, unit, runtime |
| **RAI-129** [§13 recovery remainder: JobJournal recovery/resume in product commands (scoping slice)](m1.md)) | M1 | RAI-027 | static, unit, contracts, ci |
| **RAI-130** [:type PTY oracles — remove echo saturation and use an honest error prefix](m2-p2.md) | P2 | RAI-124 | static, unit, repl |
- **RAI-131** [CI adapter batch: GitHub/GitLab contexts + trust preflight + exit contracts (combined RAI-023 remainder + RAI-024)](m0.md); dependencies: RAI-100, RAI-078; profiles: static, unit, contracts, ci.
- **RAI-132** [Steering surface: job.send wiring in REPL/DSL + :send command + typed receipts (P3 send/steering remainder)](m2-p2.md); dependencies: RAI-074; profiles: static, unit, repl, runtime.
- **RAI-133** [W05 completion: structured tool_result content blocks + cross-wire parity spec (OpenAI vs Anthropic)](m1.md); dependencies: RAI-127; profiles: static, unit, contracts, transport.
- **RAI-134** [User guide refresh: new product surfaces in onboarding/api (English documentation policy)](m1.md); dependencies: RAI-129, RAI-124, RAI-113, RAI-110; profiles: docs.
- **RAI-135** [Registry: additive component-stage eval and perf profiles (gate policy change, coordinator-executed)](m1.md); dependencies: RAI-114; profiles: static, docs.
- **RAI-136** [Load soak: PTY stability under storm + RAI-123 trigger probe (conditional product fix)](m2-p2.md); dependencies: RAI-119, RAI-123; profiles: static, unit, repl, runtime.
- **RAI-137** [UX baseline harness gate semantics: measurable exit codes, then perf profile registration](m1.md); dependencies: RAI-135; profiles: static, docs.
- **RAI-140** [ADR-031: lineage projection runtime contract — typed dry-run description (LIN-02 completion contract)](lineage.md); dependencies: LIN-02; profiles: static, unit, contracts, docs.
- **RAI-141** [Renderer emits real Program/Task composition (LIN-02 implementation)](lineage.md); dependencies: RAI-140; profiles: static, unit, contracts.
- **RAI-142** [LIN-02 acceptance: executed projection semantics (sequencing, inputs, delegation, phases, failures)](lineage.md); dependencies: RAI-141; profiles: static, unit, contracts, runtime.
- **RAI-143** [Schema card: versioned phase/recovery events + cross-run provenance contract (wave-2 wire extension)](lineage.md); dependencies: LIN-05; profiles: static, unit, contracts.
- **RAI-144** [Multi-job lineage export: delegate children join one events stream with provenance](lineage.md); dependencies: RAI-143; profiles: static, unit, contracts, ci.
- **RAI-145** [Unified CI pipeline graph → DSL projection (BillingCi shape)](lineage.md); dependencies: RAI-142, RAI-144; profiles: static, unit, contracts, runtime.
- **RAI-146** [Independent acceptance/verifier coverage for unified lineage (multi-root corpus + verifier)](lineage.md); dependencies: RAI-145; profiles: static, unit, contracts.
- **RAI-147** [User guide + complete BillingCi example with actual generated lineage (unified)](lineage.md); dependencies: RAI-146; profiles: docs, static.
- **RAI-148** [RecoveryClass taxonomy fix: fs_patch/proc_run are Mutating; single taxonomy source](lineage.md); dependencies: LIN-06; profiles: static, unit, contracts, runtime.
