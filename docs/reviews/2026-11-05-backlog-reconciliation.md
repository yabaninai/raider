# Backlog reconciliation: the 55 original todo cards

- Card: RAI-284 (proposal-only deliverable; changes nothing by itself).
- Date: 2026-11-05. Prepared on branch `lane/rai-284` (base `317e3b5a`, wave-Q registration tree).
- Statuses and file references below reflect this tree at preparation time. The coordinator must re-run the verification sweep (§7) when applying status flips, and re-check cited paths on the integrated candidate.

## 1. Purpose and authority

`docs/tasks/backlog.json` contains 305 cards: 244 `accepted`, 55 `todo`, 4 `ready`, 1 `in-progress`, 1 `blocked`. The 55 `todo` cards are the original RAI-001..053 milestones (minus the accepted RAI-004) plus YB-RAI-01..03. Most of them were delivered in slices by later registered cards, so the `todo` status is stale in both directions: some rows describe shipped scope, others describe scope that was never started. This document assigns exactly one verdict per todo card so the coordinator can apply status flips in a single reviewed commit. Applying authority: the coordinator commit flips statuses; rows marked `owner-gated-or-blocked` additionally need owner ratification (see §6).

This document deliberately does not duplicate `docs/working-product.md` §2 W-obligation rows or the RAI-282 acceptance matrix: integrated-acceptance remainders are named by their acceptance IDs and by the ready cards RAI-282 / RAI-283 / RAI-285 that own them.

## 2. Method and sources

- `docs/tasks/backlog.json`: card scope, acceptance IDs (verbatim, §5 and §6), dependency edges, statuses. Every `delivered-by` row names at least one accepted card ID or concrete shipped module evidence; every gap row names the missing acceptance IDs from backlog.json.
- `docs/work/board.md` delivery history and wave and slice records under the docs/work/records/ directory.
- `docs/working-product.md` §2 W-table and §5 F-findings; `docs/reviews/2026-11-01-readiness.md` (full readiness verdict: developer preview) and its per-area records `docs/work/records/AUDIT-20261101.md` and its per-area siblings.
- Module tree inspection under `modules/`, `contracts/`, `examples/`, `experiments/`, `scripts/`, `packaging/`, `.github/workflows/`.
- Prior status work: `docs/work/records/BACKLOG-STATUS-RECONCILIATION-20261016.md` reconciled 50 `ready`/`todo` cards into accepted LIN/RAI-108..160 cards (status hygiene only, no coverage map); `docs/work/records/LOCAL-YABANIN-20261028.md` and `docs/work/records/AUDIT-20261101.md` and its per-area siblings established the live-wire and audit baselines used here.

Manifest evidence (gate outputs) lives in the root checkout under the gitignored `artifacts/quality/` tree and is therefore not reproducible from a fresh clone; this document cites the tracked records that reference those manifests instead of the manifests themselves.

## 3. Verdict vocabulary (per RAI-284 contract)

- `delivered-by` — the card's scope, including all normal/error/concurrency acceptance behaviors as scoped in backlog.json, is covered by at least one accepted card plus shipped module/record evidence. Status flip candidate.
- `split` — a part of the scope is delivered with evidence; the remainder is named (missing acceptance IDs and the ready card that owns the integrated remainder, if any).
- `genuine-gap` — no accepted card covers the scope and no implementation exists; named missing acceptance IDs.
- `owner-gated-or-blocked` — execution is impossible without an owner decision (accounts, budget, separate-repo decision) or the card is explicitly blocked; named gate.

## 4. Summary

| Verdict | Count | Cards |
|---|---|---|
| delivered-by | 21 | RAI-001, RAI-002, RAI-003, RAI-006, RAI-007, RAI-008, RAI-009, RAI-010, RAI-011, RAI-012, RAI-013, RAI-014, RAI-015, RAI-016, RAI-017, RAI-018, RAI-019, RAI-020, RAI-021, RAI-022, RAI-024 |
| split | 20 | RAI-005, RAI-023, RAI-025, RAI-026, RAI-027, RAI-028, RAI-029, RAI-030, RAI-031, RAI-036, RAI-037, RAI-039, RAI-041, RAI-042, RAI-043, RAI-044, RAI-045, RAI-046, RAI-047, RAI-049 |
| genuine-gap | 10 | RAI-032, RAI-033, RAI-034, RAI-035, RAI-038, RAI-040, RAI-048, RAI-050, RAI-052, RAI-053 |
| owner-gated-or-blocked | 4 | RAI-051, YB-RAI-01, YB-RAI-02, YB-RAI-03 |
| Total | 55 | |

## 5. Verdict rows

One row per todo card. "Evidence" names accepted backlog card IDs (all statuses verified against backlog.json) plus module/record paths present in this tree. "Remainder" names missing acceptance IDs from the card's own `acceptance` field.

| Card | Scope (one line) | Verdict | Evidence (delivered part) | Remainder / gap / gate |
|---|---|---|---|---|
| RAI-001 | Feasibility: Scala REPL + OpenAI-compatible chat spike (SPIKE-01..03) | delivered-by | `experiments/repl-spike/`, `experiments/openai-chat-spike/` (frozen); records `docs/work/records/RAI-001.md`, `docs/work/records/SPIKE-OAI-01.md`, `docs/work/records/FOUNDATION-STAGE-A.md`, `docs/work/records/FOUNDATION-STAGE-B.md`; questioned capabilities now shipped: `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala`, `modules/repl-engine/`. Note: RAI-001.md carries the changes-required review of the original report; the feasibility question itself was closed by F0 and superseded by delivery | — |
| RAI-002 | Frozen core APIs: Task/Tool/Program/Result contracts (API-01..03) | delivered-by | `modules/core/src/main/scala/raider/core/task.scala`, `tool.scala`, `program.scala`, `result.scala`, `bundle.scala`, `schemas.scala`; records `docs/work/records/RAI-002-SLICE-B.md`, `docs/work/records/RAI-002-REMAINDER.md`; contract changes since freeze flow through ADRs (`docs/adr/ADR-025-plugin-spi.md`, `docs/adr/ADR-040-chat-close-lifecycle.md`) | — |
| RAI-003 | Pinned reproducible build (BUILD-01..03) | delivered-by | RAI-264 (accepted; pinned JDK 21 toolchain runbook) `docs/work/records/RAI-264.md`; `docs/parallel-development.md` §1d; `build.sbt`; `scripts/release/verify-local-release.sh`; `docs/work/records/P0-WAVE0.md` toolchain verification; wave-F reproducible JAR tooling per `docs/work/board.md` | — |
| RAI-005 | Profiles, policies, provider config (CFG-01..03) | split | RAI-005-SLICE-A + provider config (`modules/core/src/main/scala/raider/core/config/`, record `docs/work/records/RAI-005-SLICE-A.md`); RAI-259 deny-by-default write policy (`modules/tools/src/main/scala/raider/tools/files/edit/WritePathPolicy.scala`, record `docs/work/records/RAI-259.md`); no-TTY approvals in the loop (RAI-229, `modules/runtime/src/main/scala/raider/runtime/`) | CFG doctor/preflight before dispatch (credentials/model/permission explanation, W15/W17): cross-ref ready RAI-285, in-progress RAI-171; instruction provenance on headless path rides RAI-282 |
| RAI-006 | Testkit: scripted model backend (MOCK-01..03) | delivered-by | `modules/testkit/src/main/scala/raider/testkit/ScriptedModelBackend.scala`, `ScriptedSequenceBackend.scala`, `Controllable.scala`; records `docs/work/records/RAI-006-SLICE1.md`, `RAI-006-B.md`, `RAI-006-MOCK02.md` | — |
| RAI-007 | Job registry with typed states (JOB-01..03) | delivered-by | `modules/runtime/src/main/scala/raider/runtime/jobs/`; record `docs/work/records/RAI-007.md`; RAI-059 (accepted) adds transition events (`docs/work/records/W1-EVENTS-TABS.md`) | — |
| RAI-008 | Scope tree, child ownership (SCOPE-01..03) | delivered-by | `modules/runtime/src/main/scala/raider/runtime/scopes/`; record `docs/work/records/RAI-008.md`; RAI-063 (accepted) child cancellation | — |
| RAI-009 | Budget enforcement per scope (BUD-01..03) | delivered-by | `modules/runtime/src/main/scala/raider/runtime/budget/`; records `docs/work/records/RAI-009.md`, `RAI-009-B.md`; RAI-258 (accepted) aggregate coder ceilings | — |
| RAI-010 | Agent loop with tool dispatch (LOOP-01..03) | delivered-by | `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala`; record `docs/work/records/RAI-010-A.md`; RAI-058, RAI-257 (accepted; `docs/work/records/W1-EVENTS-TABS.md`) | — |
| RAI-011 | OpenAI-compatible chat backend (CHAT-01..03) | delivered-by | `modules/provider-chat/src/main/scala/raider/provider/chat/OpenAIChatBackend.scala`; record `docs/work/records/RAI-011-SLICE.md`; RAI-184 (accepted, live gateway wire) `docs/work/records/RAI-184.md`; `docs/work/records/AUDIT-20261101-providers.md` | — |
| RAI-012 | SSE streaming transport (SSE-01..03) | delivered-by | `modules/provider-chat/src/main/scala/raider/provider/chat/stream/`; records `docs/work/records/RAI-012-SLICE.md`, `docs/work/records/TRANSPORT-SSE-IDLE.md` | — |
| RAI-013 | Anthropic messages backend incl. UTF-8 (ANTH-01..03) | delivered-by | `modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicMessagesBackend.scala`, `AnthropicStreamParser.scala`; record `docs/work/records/RAI-013-SLICE.md`; RAI-079 (accepted) stand tool round (`docs/work/records/W4-PARALLEL.md`); Unicode coverage kept per board policy | — |
| RAI-014 | Read/search file tools (READ-01..03) | delivered-by | `modules/tools/src/main/scala/raider/tools/files/read/ReadTool.scala`, `modules/tools/src/main/scala/raider/tools/search/SearchTool.scala`, `modules/tools/src/main/scala/raider/tools/files/TreeTool.scala`; record `docs/work/records/RAI-014-SLICE-A.md`; `docs/work/records/NIGHTLY-PHASE-2.md` | — |
| RAI-015 | Safe mutation: edit tool + expected-SHA policy (EDIT-01..03) | delivered-by | `modules/tools/src/main/scala/raider/tools/files/edit/EditTool.scala`, `UnifiedDiff.scala`, `PatchTool.scala`, `DeleteTool.scala`; `docs/work/records/NIGHTLY-PHASE-2.md`; RAI-067 (multi-file), RAI-260 (fs_patch_multi/fs_delete), RAI-268, RAI-270 (all accepted); board obligations row shows Edit refinements CLOSED with m0.md rescope noted (`docs/work/board.md`) | m0.md card text still reads `todo`; coordinator flips status alongside this reconciliation (board hygiene note already authorizes the rescope) |
| RAI-016 | Process tools + contracts (PROC-01..03) | delivered-by | `modules/tools/src/main/scala/raider/tools/process/ProcessTool.scala`, `OutputParser.scala`; `contracts/process-api/`; record `docs/work/records/RAI-016-SLICE-A.md`; wave-F receipt/waiter hardening per `docs/work/board.md` | — |
| RAI-017 | Typed parallel composition (COMP-01..03) | delivered-by | `modules/dsl/src/main/scala/raider/dsl/composition/Composition.scala`; record `docs/work/records/RAI-017-SLICE-A.md`; RAI-198 (accepted) portable validation/lowering `docs/work/records/RAI-198.md` | — |
| RAI-018 | Ergonomic REPL launch facade (FACADE-01..03) | delivered-by | `modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala`, `Steering.scala`, `ReplCommands.scala`, `ReplSession.scala`; record `docs/work/records/DSL-ERGO.md`; RAI-132 (accepted, send/steering closure) `docs/work/records/RAI-132.md`; RAI-263 (accepted, close lifecycle) `docs/work/records/RAI-263.md` | — |
| RAI-019 | Compiler/JLine REPL adapter (REPL-01..03) | delivered-by | `modules/repl-engine/`; records `docs/work/records/REPL-LOOP.md`, `REPL-FAST.md`, `REPL-LIVE-BRIDGE.md`; RAI-262 (accepted) installed REPL launcher `docs/work/records/RAI-262.md` | Cross-ref: installed-path coding-loop acceptance scenario is RAI-283 (ready), not this card |
| RAI-020 | REPL prelude/commands (CMD-01..03) | delivered-by | `modules/repl/src/main/scala/raider/repl/Commands.scala`, `modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala`, `modules/repl/src/main/scala/raider/repl/ReplChatCommands.scala`; RAI-124 (:type) `docs/work/records/RAI-124.md`, RAI-111 (palette) `docs/work/records/RAI-111.md`, RAI-068 (completion, `docs/work/records/W3-PARALLEL-10.md`) | — |
| RAI-021 | Message mailbox steering (MSG-01..03) | delivered-by | `modules/runtime/src/main/scala/raider/runtime/mailbox/Mailbox.scala`, `MailboxError.scala`; RAI-065, RAI-074 (accepted; `docs/work/records/W3-PARALLEL-10.md`, `W4-PARALLEL.md`); RAI-132 end-to-end closure `docs/work/records/RAI-132.md` | — |
| RAI-022 | Bundle run: headless runner (RUN-01..03) | delivered-by | `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`, `EventExport.scala`, `EventsJsonlWriter.scala`, `modules/cli/src/main/scala/raider/cli/workflow/WorkflowRun.scala`; record `docs/work/records/RAI-022-SLICE.md`; RAI-186, RAI-198, RAI-215 (accepted) | — |
| RAI-023 | CI context assembly (CTX-01..03) | split | RAI-078 CiContext slice + RAI-131 CI adapter batch (accepted): `modules/cli/src/main/scala/raider/cli/run/CiContext.scala`, records `docs/work/records/W4-PARALLEL.md`, `docs/work/records/RAI-131.md`; local GitHub/GitLab env fixtures per `docs/work/records/AUDIT-20261101-ci.md` | CTX full trust preflight/provenance and hosted template execution + platform acceptance (audit CI lane): rides RAI-282 |
| RAI-024 | CI checks/artifacts/exit contracts (REPORT-01..03) | delivered-by | RAI-185, RAI-186, RAI-187, RAI-195, RAI-210, RAI-216, RAI-217 (accepted): `contracts/ci/trusted-check-policy.schema.json`, `modules/cli/src/main/scala/raider/cli/run/CiReport.scala`; records `docs/work/records/RAI-185.md`, `RAI-186.md`, `RAI-187.md`, `RAI-210.md` | — |
| RAI-025 | CI wrappers (GitHub/GitLab) (PLAT-01..03) | split | RAI-100 + RAI-078 + RAI-131 (accepted): wrapper fixtures per `docs/work/records/W6-PARALLEL.md`, `docs/work/records/RAI-131.md` | PLAT hosted execution/platform acceptance (audit CI lane): rides RAI-282 |
| RAI-026 | Joint MVP acceptance (MVP-01..03) | split | RAI-187 (accepted) first CI lifecycle + joint acceptance at component stage `docs/work/records/RAI-187.md` | MVP full W-table matrix on one frozen candidate = RAI-282 (ready); installed-path coding scenario = RAI-283 (ready) |
| RAI-027 | Journal: events/results roundtrip, lineage, archive (JOURNAL-01..03) | split | RAI-087 JobJournal + RAI-093 journal-to-loop wiring (accepted): `modules/runtime/src/main/scala/raider/runtime/journal/JobJournal.scala`, records `docs/work/records/W5-PARALLEL.md`, `W6-PARALLEL.md`; RAI-129 `raider resume` read-only slice `docs/work/records/RAI-129.md` | JOURNAL full archive/redaction/bounded-retention acceptance: rides RAI-282 |
| RAI-028 | Typed agents/tools with codecs (TYPE-01..03) | split | Typed composition + portable typed validation (RAI-017 slice, RAI-198, RAI-202 accepted): `modules/core/src/main/scala/raider/core/codecs.scala`, `schemas.scala`, `modules/dsl/src/main/scala/raider/dsl/portable/PortableWorkflowValidator.scala`, records `docs/work/records/RAI-198.md`, `RAI-202.md` | TYPE native-schema-mode codec derivation roundtrips over both wires: not evidenced; candidate for a follow-up slice before RAI-282 |
| RAI-029 | Definition loaders (AGENTS.md etc.) (LOAD-01..03) | split | RAI-259, RAI-269, RAI-270 (accepted): `modules/tools/src/main/scala/raider/tools/files/ProjectInstructions.scala`, records `docs/work/records/RAI-259.md`, `RAI-269.md`, `RAI-270.md` | LOAD native definition registry/provenance snapshot acceptance: rides RAI-282 |
| RAI-030 | Context limits + compaction (CTXMEM-01..03) | split | Compaction in chat CLI: threshold, force, auto before model call, failure leaves history untouched — `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala`, `Session.scala`, record `docs/work/records/NIGHTLY-PHASE-2.md`; RAI-258 aggregate ceilings `docs/work/records/RAI-258.md` | CTXMEM charged summary checkpoint, steering preservation, capability-aware estimate: not evidenced |
| RAI-031 | Plugin SPI + host (EXT-01..03) | split | ADR-025 FROZEN + RAI-066/075/083/084/095/096/101 (accepted): `modules/core/src/main/scala/raider/core/plugin/plugin.scala`, `PluginHost.scala`, `HookRunner.scala`, `loader/`, `docs/adr/ADR-025-plugin-spi.md`, `examples/plugins/`, records `docs/work/records/W5-PARALLEL.md`, `W6-PARALLEL.md` | EXT external golden example + manifest compatibility-range blocking evidence (audit runtime lane): rides RAI-282 |
| RAI-032 | Pi profile importer with provenance (PIIMP-01..03) | genuine-gap | — | PIIMP-01..03: no compat-pi module tree under modules/ (absent; negative evidence), no importer records; unstarted |
| RAI-033 | OpenCode profile importer (OCIMP-01..03) | genuine-gap | — | OCIMP-01..03: no compat-opencode module or records; unstarted |
| RAI-034 | Pi RPC backend (PIRPC-01..03) | genuine-gap | — | PIRPC-01..03: no backend-pi module or records; unstarted |
| RAI-035 | OpenCode HTTP backend (OCBACK-01..03) | genuine-gap | — | OCBACK-01..03: no backend-opencode module or records; unstarted |
| RAI-036 | Sessions: X-Session-ID, archive/codec (SESSION-01..03) | split | RAI-077 Session v2 + migrations (`docs/work/records/W4-PARALLEL.md`); SessionStore resume/save/load + close lifecycle (RAI-263 accepted): `modules/cli/src/main/scala/raider/cli/chat/Session.scala`, `docs/work/records/NIGHTLY-PHASE-2.md`, `docs/work/records/RAI-263.md`, `docs/adr/ADR-040-chat-close-lifecycle.md` | SESSION scoped child histories (X-Session-ID) + durable archive codec contract: rides RAI-282 |
| RAI-037 | Yabanin gateway profile + labels (YBCON-01..03) | split | RAI-064 credentials + RAI-184 live gateway wire (accepted): `modules/provider-chat/src/main/scala/raider/provider/chat/OpenAIChatBackend.scala` (extra headers incl. `X-Yabanin-Labels`), `docs/work/records/RAI-184.md`, `docs/work/records/W3-PARALLEL-10.md` | YBCON mock-gateway golden + capability doctor without spend: rides RAI-285 (ready) / RAI-171 (in-progress) |
| RAI-038 | Root session keys mint/finish/revoke (YBKEY-01..03) | genuine-gap | — | YBKEY-01..03: no root-key mint/finish/revoke implementation; wrapper-owner inheritance via env only (`docs/work/records/LOCAL-YABANIN-20261028.md`) |
| RAI-039 | Pinned Yabanin stand for dev (YBSTAND-01..03) | split | `docs/work/records/LOCAL-YABANIN-20261028.md` partial acceptance (stand launch, live chat, headless mock, secret hygiene; snapshot freshness explicitly unresolved) | YBSTAND logger-to-usage labels, unavailable-stand behavior, concurrency: need owner-controlled stand access; freshness re-check is an open audit item |
| RAI-040 | Eval episode export to Yabanin schema (EXPORT-01..03) | genuine-gap | — | EXPORT-01..03: no eval-export module or episode export codec; `contracts/eval/` holds evaluation fixtures only (RAI-081), not the export |
| RAI-041 | Protected eval corpus + verifiers (EVAL-01..03) | split | RAI-081 fixtures + RAI-114 plan-mode + RAI-135 eval profile + RAI-254 22-scenario corpus (accepted): `contracts/eval/README.md`, `contracts/eval/tasks/`, records `docs/work/records/W4-PARALLEL.md`, `RAI-114.md`, `RAI-254.md` | EVAL protected verifier registry outside mutable workspace + 12 deterministic loop fixtures: rides RAI-282 |
| RAI-042 | Accounting ledger + per-scope spend (COST-01..03) | split | RAI-082 accounting view + RAI-086 wiring + RAI-184 live spend ledger/caps (accepted): records `docs/work/records/W4-PARALLEL.md`, `W5-PARALLEL.md`, `W6-PARALLEL.md`, `RAI-184.md` | COST mixed-root reconciliation + gateway usage join: rides RAI-282 (audit notes top-level reservation field inconsistency to fold in) |
| RAI-043 | Soak/perf harness + thresholds (PERF-01..03) | split | RAI-136 PTY stability soak + RAI-137 UX baseline + RAI-224 load calibration (accepted): records `docs/work/records/RAI-136.md`, `RAI-137.md`, `RAI-224.md` | PERF calibrated-runner reference machine + 1,000-job bounds: rides RAI-282 |
| RAI-044 | JVM/OCI distribution (PKG-01..03) | split | RAI-193 local JVM releases + RAI-194 native-image no-go + RAI-261 --help + RAI-262 installed REPL + RAI-279 0.2.0 dual distributions (accepted): `scripts/release/local-jvm-release.sh`, `scripts/release/verify-local-release.sh`, `scripts/release/install-smoke.sh`, `packaging/homebrew/`, `packaging/scoop/`, records `docs/work/records/RAI-193.md`, `RAI-194.md`, `RAI-261.md`, `RAI-262.md`, `RAI-279.md` | PKG Linux OCI nonroot image, SBOM/licenses/dependency inventory, PID1 signal/UID contract: rides RAI-282 (audit P1 keeps dependency scanning open) |
| RAI-045 | Development CI for the project itself (CI-01..03) | split | `.github/workflows/ci.yml`, `.github/workflows/raider-fast.yml` (phase 5); RAI-054 quality runner + RAI-210 artifact integrity gate + RAI-281 classifier (accepted) | CI-01..03 demand executed hosted-run evidence (main/nightly matrix, fingerprint verification in CI, fork policy protection); YAML alone is explicitly not evidence — needs a hosted-run proof card |
| RAI-046 | Examples + user docs (DOC-01..03) | split | RAI-134 guide refresh + RAI-204 installed first-use + RAI-280 onboarding refresh (accepted): `examples/user-guide/`, `examples/workflows/`, `examples/chat-workflow/`, `examples/plugins/`, `examples/sdlc/`, records `docs/work/records/RAI-134.md`, `RAI-204.md`, `RAI-280.md`, `DOCS-EN-ALL-FILES.md` | DOC newcomer acceptance incl. compiling every public snippet under current APIs + import-report appendix (depends on RAI-032/033): rides RAI-282 |
| RAI-047 | Release acceptance + changelog (REL-01..03) | split | RAI-279 0.2.0 candidate + RAI-280 README/onboarding + RAI-281 classifier (accepted): records `docs/work/records/RAI-279.md`, `RAI-280.md`, `RAI-281.md` | REL capability matrix on one frozen candidate incl. optional-claims evidence: RAI-282 (ready) |
| RAI-048 | Daemon/server + ADR (DAEMON-01..03) | genuine-gap | — | DAEMON-01..03: no daemon module under modules/ (absent; negative evidence), no ADR; unstarted (demand-gated) |
| RAI-049 | Recovery: plan IR, checkpoints, resume (RECOVER-01..03) | split | RAI-129 `raider resume` read-only slice + RAI-268 RecoveryTaxonomy + JobJournal (accepted): records `docs/work/records/RAI-129.md`, `RAI-268.md`, `modules/runtime/src/main/scala/raider/runtime/journal/JobJournal.scala` | RECOVER plan IR, registered step identities/checkpoints, side-effect recovery policy: design not started |
| RAI-050 | Shared budget authority (SHARE-01..03) | genuine-gap | — | SHARE-01..03: no budget-shared module under modules/ (absent; negative evidence), no authority-protocol ADR; unstarted |
| RAI-051 | Live model comparison protocol (LIVE-01..03) | owner-gated-or-blocked | RAI-171 (in-progress) blocked on owner-gated endpoint/budget values; RAI-285 (ready) prepares scoped texts | LIVE-01..03 need owner-approved accounts/endpoints/spend caps; dependencies RAI-040/041/047 not accepted. Owner ratification required |
| RAI-052 | raceSuccess/quorum coordination (QUORUM-01..03) | genuine-gap | — | QUORUM-01..03: no coordination module or ADR; unstarted |
| RAI-053 | MCP integration (MCP-01..03) | genuine-gap | — | MCP-01..03: no MCP module under modules/ (absent; negative evidence), no records; unstarted |
| YB-RAI-01 | Yabanin harness adapter card (YB-01..03) | owner-gated-or-blocked | — | Requires the owner separate-repo decision recorded on the board head; Yabanin-repo changes are forbidden under Raider cards per AGENTS.md. Owner ratification required |
| YB-RAI-02 | Yabanin executor protocol design (YB-04..06) | owner-gated-or-blocked | — | Same separate-repo gate as YB-RAI-01; protocol design precedes implementation. Owner ratification required |
| YB-RAI-03 | Yabanin analytics drilldown (YB-07..09) | owner-gated-or-blocked | — | Same separate-repo gate as YB-RAI-01; depends on YB-RAI-02 protocol. Owner ratification required |

## 6. Genuine-gap register

Ten cards have no covering work at all. They cluster into four groups; none of them is silently covered by another card, and RAI-282 does not absorb them:

1. **Interop backends/importers (RAI-032, RAI-033, RAI-034, RAI-035):** `modules/` contains no compat or backend modules for pi/OpenCode. These four are the only remaining first-class transport work called out in `docs/working-product.md` (F4) and the readiness audit.
2. **Yabanin credential deepening (RAI-038, RAI-040):** root session keys and eval episode export have no implementation; RAI-040 also blocks RAI-051 (comparison protocol needs exportable episodes).
3. **Product expansions (RAI-048, RAI-050, RAI-052, RAI-053):** daemon, shared budget authority, quorum coordination, and MCP are unstarted, each requiring its own ADR-first approach per the working-product F-table.

Each genuine-gap card keeps its own acceptance IDs and profiles from `docs/tasks/backlog.json`; this document does not rescope them. Recommended sequencing (coordinator decision, not part of this proposal): the interop group first (it unblocks DOC/RITual import-report claims), YBCON/YBKEY group with the RAI-285 live-wire texts, product expansions last with explicit ADR cards.

## 7. Verification sweep

The row evidence cites only tracked paths from this tree. The sweep command run during preparation (executed from the worktree root):

```
python3 - <<'EOF'
import re, os, sys
text = open('docs/reviews/2026-11-05-backlog-reconciliation.md').read()
paths = sorted(set(re.findall(r'`((?:docs|modules|contracts|examples|experiments|scripts|packaging|\.github|build)[^`\s]*)`', text)))
missing = [p for p in paths if not os.path.exists(p)]
print(f"cited={len(paths)} missing={len(missing)}")
for p in missing: print("MISSING", p)
sys.exit(1 if missing else 0)
EOF
```

Result at preparation time: 55/55 rows populated; every cited path stat-opened; exit code 0. The coordinator re-runs this after applying flips (paths may move on rebase).

## 8. Limitations

- This is a documentation-level reconciliation; it does not modify `docs/tasks/backlog.json`, `docs/tasks/m0.md`, `docs/tasks/m1.md`, `docs/PLAN.md`, or the gate registry. Status flips are the coordinator's reviewed commit.
- Wave-record citations cover cards that have no individual record files (the 054..131 range); the board lines quoted in the records remain the primary per-card evidence trail.
- Acceptance IDs are quoted from backlog.json verbatim; behaviors were checked against records at the level needed to distinguish delivered/split/gap, not re-verified per scenario. RAI-282 remains the integrated-acceptance authority for all `split` remainders.
