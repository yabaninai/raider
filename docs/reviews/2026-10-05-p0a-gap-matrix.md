# P0.A: actual source/board/evidence matrix (W01–W18, UX01–08)

Date: 2026-10-05. Baseline: commit `7ee6c0c` + uncommitted planning docs
(see [ROADMAP-12K record](../work/records/ROADMAP-12K.md)). Method: read-only
inspection of actual sources in three independent inventory passes (runtime,
CLI/REPL, quality/build), cross-check against board and registry. This is an
inventory, not acceptance: no row below creates a new green status. Canonical
order: [roadmap-12k](../roadmap-12k.md).

## 1. W01–W18 matrix

Legend: **implemented** — code exists and is covered by tests/evidence record;
**partial** — a slice is implemented, some required cases are missing;
**absent** — no code; **deferred-P11** — intentionally deferred in roadmap-12k.

| ID | Area | Status | Proven (evidence) | Remaining before required acceptance |
| --- | --- | --- | --- | --- |
| W01 | Installation | partial | pinned toolchain (sbt 1.11.4, Scala 3.9.0, ZIO 2.1.26); fat-jar `raider-cli.jar` smoke PASS (NIGHTLY-P5); GitHub Actions workflow exists | clean install/upgrade/uninstall rehearsal; `raider --help`; formal W01 acceptance on candidate |
| W02 | Native coding loop | implemented* | AgentLoop + tool round; SELFDEV: coder agent with real fs_read/fs_search/proc_run and live OpenAI provider (2026-10-04) | full acceptance on one candidate with hidden assertions; “*” means slice evidence, not full W02 |
| W03 | File tools | partial | read/search/tree/edit/patch (unified diff, sha+atomic), RAI-014-SLICE 13/13; bounded tree with skip-list | multi-file patch remainder (RAI-015); verify continuation for truncation; partial-failure contract cases |
| W04 | Real processes | partial | argv/cwd/env allowlist, exit/deadline, RAI-016-SLICE 6/6; `CleanupGuarantee.DirectChildOnly` | grandchild cleanup; child deadlines; OOB cancel (§11 policy card) |
| W05 | Both direct wires | implemented* | OpenAI: non-stream+SSE+tool_calls, live-verified (RAI-011/012); Anthropic: Messages, native tool_use/tool_result (RAI-013) | unified acceptance of both wires on one candidate; cancelled/error/usage matrix |
| W06 | Policy | partial | ToolDenied→exit 22, workspace containment, env allowlist, sandbox-capability reporting | cases for secrets/protected-file inaccessibility; child policy intersection; noTTY NeedsApproval |
| W07 | Project context | partial | config slices (RAI-005-SLICE-A 13/13, CFG-01..03) | instructions/workspace/profile precedence (RAI-029), provenance, silent policy expansion cases |
| W08 | Sessions | partial | SessionStore: atomic save/load/list, corrupt-skip; automatic+manual compaction (threshold 30/keep 20) | versioned schema (fields currently lack version), journal/reopen, recovery after crash, migrations |
| W09 | Subagents | partial | JobManager (bounded retention, cancel-wins race), delegate/await/cancel/list, maxLLM=1 no-deadlock, one root ledger | `send` absent (typed refusal); watch is initial+final only; stuck child blocks cancellation at maxLLM=1; deadlines/OOB cancel |
| W10 | Real REPL | implemented* | real dotty ReplDriver, multiline, bindings persist, PTY smoke 13/13 (REPL-FAST) | completion returns Nil; `:type` refuses; streaming output does not protect edit buffer (see §3) |
| W11 | DSL | partial | AgentAsk lazy, all/batch/openSession, andThen composition; ContractsSpec 35 PASS | full golden-scenario corpus in one acceptance; bare-Task UX cases |
| W12 | CI portability | partial | headless nonTTY, SIGTERM bounded (5s), run/events/summary/transcript artifacts, exit mapping for 18 families | GitHub/GitLab context fixtures (RAI-025), JUnit schema, trust preflight (RAI-023) |
| W13 | Extension | partial | BundleLoader: jar + META-INF/raider-bundle.json + BundleWorkflow SPI (workflow packaging) | user tool/profile SPI (RAI-031) absent; plugin lifecycle absent |
| W14 | Root limits/accounting | implemented* | BudgetLedger: reservations/settlements/late/uncertain, UsageReport known/unknown, admission caps; stress 7/7 ×3 | identical accounting in UI and CI on one candidate |
| W15 | Independent entry | partial | `--agent scout/worker/coder`, chat/run without workflow; env-based setup in REPL | guided setup/doctor; UX01 rehearsal; `--api-key` in argv (see §4) |
| W16 | Task fixtures | partial | CompileFixLoop (:fix ≤3), mock fixture marker in summary.md | corpus review/bugfix/new-file/refactor/research + hidden tests (RAI-046), verifier outside workspace |
| W17 | Gateway | deferred-P11 | historical materials preserved; ADR-022/023 reserved, no files | all: P11 after M4; early discovery/adapter/session keys prohibited |
| W18 | Final integration | absent | — | all W01–W17 on one immutable build; only after P11 |

## 2. UX01–08 matrix (current state → target)

| ID | Requirement | Actual now | Gap |
| --- | --- | --- | --- |
| UX01 | Direct-provider setup ≤10 min | env `RAIDER_PROVIDER/BASE_URL/MODEL` in REPL; CLI flags `--provider/--base-url/--model/--api-key`; mock by default | no guided setup/doctor; no measurements |
| UX02 | Tabs ≤2 keystrokes, p95 ≤100 ms | `modules/ui` ABSENT; no tabs | all P2; metric inapplicable before M1 |
| UX03 | Agent status, event→visible p95 ≤250 ms | pull-only `JobSnapshot` (Queued/Running/Waiting/WaitingApproval/Cancelling/Terminal); no subscriptions; ChatLoop prints trace to stderr without distinguishing reasons | P1.A/B/C: envelopes, snapshot+subscribe, waiting reasons |
| UX04 | UI startup p95 ≤2s warm | REPL startup p95 unmeasured; PTY smoke exists, no metrics | baseline measurements in P0.C |
| UX05 | Plugin ≤3 actions | plugin manager/SPI absent | all P4 |
| UX06 | Cancel with one gesture ≤5s ×20 | cancel_agent/cancelAny/Admission.cancelRoot/scope-grace exist; REPL foreground eval synchronous without cancel; proc cleanup DirectChildOnly | controlled fixture ×20; OOB subtree cancellation (P3.B) |
| UX07 | Palette/hints/fallback | REPL `:commands`, chat help text; no palette/hint bar | P2.B |
| UX08 | Intermediate result and reason | streaming deltas → stdout, tool events → stderr; usage pull-only; unknown cost not displayed (no UI) | P1.B/C: bounded tool progress, retry delay, honest unknown |

## 3. Verified current send / watch / cancel problems

1. **`send` is intentionally absent**: `DelegationToolset.scala:33` (“send_agent
   is deliberately absent”); REPL facade `FacadeOps.scala:246-252` throws
   `RaiderError.CapabilityUnsupported`. This is the P3.A obligation (bounded
   mailbox), not a regression.
2. **`watch` is limited**: `FacadeOps.scala:233-244` — snapshot + terminal
   snapshot; per-event streaming is marked “beta obligation, cut from W10”.
   Runtime has no live subscription: the only mechanism is callback
   `trace: TraceEvent => UIO[Unit]` (`AgentLoop.scala:60`), JobManager pull methods (`snapshot/adminView`), and Promise-based await.
3. **Cancellation**: `JobManager.cancel/cancelAny` paths (STM transition to
   Cancelling + interrupt), `Admission.cancelRoot`, `RaiderScope.close(grace)`
   with `uncertainChildren`. Documented defect: a parked child at maxLLM=1
   holds the only model slot and blocks the parent's cancel_agent call
   (`AgentLoopDelegationSpec.scala:389-393`; test runs with llm=2). This is the
   P3.B obligation (OOB cancel) and must remain visible until closed.
4. **Events never emitted at all**: admission wait, budget reserve/settle,
   retry (`RateLimitRetries` emits nothing), delegation (ChildDelegated/
   ChildAwaited are defined in core but not emitted),
   `ToolCallFinished(success=false)`, and tool timeout. `ModelCall/ToolCall*`
   hardcode `round = -1`
   (`AgentLoop.scala:168,215,241,266`).

## 4. Required P0 audit cases

| Case | Fact |
| --- | --- |
| Ctrl+C bindings | REPL: JLine `Reference("abort")` on `main` keymap (`repl/Main.scala:150-156`); UserInterruptException clears pending input. Foreground eval is synchronous — no cancellation (spike-only). CLI chat uses raw BufferedReader, no input-signal handling; SIGTERM hook exists (bounded 5s). |
| Glyph width | No width handling anywhere; future renderer must handle Cyrillic/CJK/emoji/combining (one `Char` ≠ one cell). Measure in P0.C spike. |
| Child provider/model inheritance | Child loop receives same `ModelBackend` (`DelegationToolset.make`); passing model string into child `runText` from parent config needs clarification in P1 card (not explicitly confirmed by inventory). |
| Setup without localhost probe, credentials not in argv | DEFECT FOUND: `run/CliArgs.scala:67` accepts `--api-key` in argv (default `"no-key"`) — secret may enter shell history/process listing. Card needed: env/helper references only, deprecate argv flag. No implicit localhost probe found. |
| Terminal writers | No unified writer: 4 independent paths (REPL facade stdout/stderr, ChatLoop stdout/stderr+println, HeadlessRunner stderr, ReplCommands println); no synchronization. |

## 5. Gate registry and runner findings

- Registry (`scripts/quality-policy/registry.json`): 12 gates, 11 profiles,
  **all stage=component**; no executable milestone/full/release/security/eval/
  performance/ui/plugin profiles (only backlog metadata exists).
- **Confirmed runner defect** (`scripts/quality/quality.py`): `classify`
  (:91-106) returns a dict keyed by profile names (values are empty lists);
  `cmd_execute` (:186-190) searches it for a **file path** → always misses →
  `__unmapped__` → “changed files map to no profile” → exit 1 **before** running
  a gate. With any changes (git or manifest), `quality.py execute` always
  fails; green NIGHTLY runs were on a clean tree (`changes=[]` → fast).
- **Second runner defect**: `fingerprint(None)` fills `changes` only for a base
  manifest (:84-99) — git-diff is not implemented, so `make quality-changed`
  without a manifest always prints “no changes vs base” even on a dirty tree
  (verified 2026-10-05 in the current checkout with changed tracked files).
  Spec quality-gates §2 requires staged/unstaged/untracked/deleted/renamed.
  Both defects are tracked by RAI-054 (separate gate-infrastructure card,
  independent review required). Until the fix is integrated, the required
  `make quality-changed QUALITY_EXECUTE=1` after cards cannot honestly be run.
- Board vs code: status lines agree (accepted slices exist and have tests;
  tests run — full 188 green according to records). The stale “product runtime
  does not exist” text in AGENTS.md itself says that statement is not permanent;
  actual runtime/providers/tools/REPL/CLI/session persistence exist.

## 6. Findings for P0.B/C and early cards

1. P0.B (event seam) builds on existing `TraceEvent` (11 types, JSON codec),
   `JobStatus` state machine with `canTransition`, pull snapshots, callback
   emitter in AgentLoop. Frozen seam must add: envelopes (root/session/job/event
   IDs, per-job sequence), snapshot+subscribe without loss window, waiting
   reasons (admission/slot/approval), bounded tool progress, honest unknown cost.
   ADR: **ADR-024** (017–023 reserved by historical roadmap).
2. P0.C (renderer spike) builds on JLine 4.0.14 (repl-engine), existing PTY
   helper pattern (`scripts/quality/repl_pty_smoke.py`: pty+select+ISIG off,
   isolated HOME, fixed TERM). Measure redisplay during background output,
   draft preservation, Unicode width, redraw latency p95.
3. First implementation cards (registered RAI-054..057): runner fix, event
   envelopes/hub, renderer spike, session registry skeleton. Order by roadmap
   DAG §5: P0.B review → P1.A; P2.A/B can proceed in parallel against frozen
   interfaces + scripted source.
4. None of the above means “finished product”: this is planning inventory;
   each acceptance is a separate card with actual gates.
