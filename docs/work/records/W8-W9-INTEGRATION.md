# W8-W9-INTEGRATION: W8/W9/W9b Wave Integration + Self-Execution (Work Record)

Status: done. Date: 2026-10-10 (UTC day/night). Coordinator: continuation of
the night session. Baseline: root after W7-INTEGRATION (full integration of
P-LIN wave 1). All integrations included byte-for-byte lane↔root comparisons
and full root gates; each batch ran `make quality-changed QUALITY_EXECUTE=1` =
7/7 passed (manifests: 20261010-124556, 131528, 132854, 134026, 135202, 140255,
150019, 152002 + final after W9-F3).

## Resulting behavior (by wave)

**W8** (6 lanes, spawn 12:53):
- LIN-01C LineageReducer (runtime/lineage/reduce): 13 frozen payload cases,
  9 typed errors, phase-free, deterministic; L11.
- LIN-02D LineageDataStore (dsl/lineage/store): atomic tmp+move,
  Missing/Corrupt/Io, single-writer; L13.
- RAI-072 docs: events.md/tabs.md/cancel.md + runtime-contracts §5; L15.
- RAI-080 docs: onboarding.md; L16.
- LIN-02B-fix (review F1): canonical codec output = frozen schema (explicit
  null; dataRef intentionally omitted); L17.
- LIN-01A-fix (review F2): GraphHash escaping is injective (|, C0; outcome
  escaped) + GraphHashSpec; L18.
- LIN-02C renderer (3rd attempt, code-first): LineageProjection + 6 tests;
  L19. L12 (2nd attempt) and L8 (1st) ended without code due to the balance
  issue, see below.
- RAI-079: AnthropicMessagesStandSpec — full tool round against local JDK
  HttpServer (loopback, wire assertions, typed failures, 15/15); L20.
- W8-review (L14): 6/7 ACCEPT; F1→L17, F2→L18 (both recorded and resolved).

**W9** (3 lanes, spawn 14:05):
- LIN-03A LiveGraphBuilder (runtime/lineage/live): stepwise twin of the batch
  reducer; fold==batch invariant; atomicity at node boundary; L21.
- View adapter SessionGraphView→LineageView + golden E2E (cli/lineage); L22.
- W9-review (L23): 6/7 ACCEPT; W9-F2 (DisableSyntax.null in LIN-02C files) →
  L24 fix (Option.fold/orNull, rule NOT disabled); W9-F1/F4 → L26.

**W9b** (3 lanes, spawn 15:05):
- LIN-05B GraphComparator + ReplayVerifier (runtime/lineage/replay):
  first-divergence diff, verify(events,hash), typed error layers; L25 (worker
  stopped before gates — code was compiled, integrated and checked by coordinator).
- W9-F1 GraphHash sentinel + W9-F4 renderer \n escape: L26 (stopped before
  gates, integrated and checked).
- LIN-04B slice LineageTab provider (ui, pure, no IO, through :aux seam); L27.

**Self-executed** (provider balance exhausted — see Blocker):
- LIN5-03 slice: ReplayVerifier.verifyPartial + PartialReplay — consistent
  partial graph from flushed events.jsonl checkpoint; P1–P4 fixture-first;
  12/12 spec; mid-file seq-gap/malformed → typed Read (not a fake partial).
- W9-F3 docs nit: full paths to ReplCommands.scala in onboarding.md.

## Blocker (external)

**zai/glm-5.3-flash: “Insufficient balance or no resource package”** —
confirmed by a probe call (15:20). This also explains all “sudden deaths” of
agents without RECORD (L2/L3/L4/L8 wave 1, L12, L25/L26). New lane workers
cannot be spawned until the owner adds funds. Ready queue —
`raider-night/W7-registry.md` §Ready queue.

## Incidents and remediations

- `.env.yabanin` (local untracked root file, mode 600, .gitignore .env.*)
  leaked via rsync into 8 W8 lanes + base-manifest-w8.json (path+sha256, not
  contents). Remediation: removed from lanes; L17/L18 restored for gate
  continuity; manifest v2 clean; later lanes use `--exclude '.env*'` + their
  own pre-work manifests. Root git fingerprint was not affected.
- cp incident in contracts (see W7-INTEGRATION) — fixed; comparison IDENTICAL.

## Honest status

All integrated cards passed full root gates; HOWEVER, independent review was
conducted only for waves W7 (L14) and W8 (L23). W9b implementations (replay
core, tab provider, LIN5-03, F1/F4 fixes) still have NO independent review —
first in the ready queue. LIN-02 parent (scalac projection compilation —
LIN2-01) and LIN-03 full (cli wiring) remain open; LIN-04 parent (live TUI
integration) remains open.

---

# Addendum: W10–W11 (Evening, 2026-10-10)

The owner added funds; the worker provider was switched DIRECTLY to
`zai-coding-plan/glm-5.3-flash` (the yabanin gateway is not used).

**W10** (4 lanes): LIN-03B (HeadlessRunner → summary lineage hash; deviation:
  batch reduce on settle instead of LiveGraphBuilder fold — equivalent under
  fold==batch invariant, reviewed in W12), RAI-097 StatusText (runtime/events +
  cli/chat), LIN2-01 compile-gate (L30: gate spec CAUGHT a real renderer defect
  — “()” indentation broke dotc), W10 review (L28: LineageTab/replay-core
  ACCEPT; GraphHash F-L28-1/2 Major, verifyPartial F-L28-3, ordering F-L28-4).
**W11** (3 fix lanes): GraphHash escaping for header+refs (BREAKING hash,
  documented), reducePartial + verifyPartial torn-tail ⇒ PartialReplay (LIN5-03
  contract now works on a REAL crash prefix), renderer “()” fix — tiny+deep
  compile with dotc 3.9.0.
**Integration**: L32+L33+L34+LineageCompileGateSpec together; first sbt-test
  run RED (negative LIN2-01-C: dropping the last line became harmless — the
  closing “()” is optional after the fix) → coordinator patched the negative
  case to use deterministic corruptObjectHeader; final EXECUTE 7/7
  (artifacts/quality/20261010-221444-30407-…-0001 and 221444).

**LIN2-01 ACHIEVED**: golden graph→lineage.scala projections compile with
pinned scalac (dotc 3.9.0), byte-identical on repeat. Remaining open: LIN2-03
(concurrent reads — partially covered), LIN-05 full interpreter, LIN-04 full
TUI, multi-job export (owner).

---

# Addendum 2: W12–W14 (Evening/Night 2026-10-10, direct zai-coding-plan provider)

**W12**: RAI-097 remainder (TabBar→StatusText, L36), RAI-096 (plugins SDK docs +
3 fixtures, L37); review R1/R2 resolved with coordinator fixes (null literals
in LineageCompileGateSpec + stale doc; the first fix had a type error, caught
by sbt-test gate and corrected). examples/plugins added to
scripts/build/compile.sh (SDK-03 gap closed, exit 0).

**W13**: review (L36 ACCEPT; L37 ACCEPT after docs fixes: F1 — reviewer false
positive, confirmed by grep; F2 — new state documented); LIN-05D RecordedSource
(L39) — DataRef access to recorded model responses. Full 10-profile regression
of the integrated tree: passed (20261010-225639).

**W14**: LIN-05E ReplayInterpreter (L40) — replay-recorded slice: LLM from the
recording + integrity (ToolCall recomputation is a recorded obligation).
Final review L41: ACCEPT on all criteria (a)-(f).

## Incidents (recorded truthfully)

1. **Files lost in root, window ~14:44–15:05**: SessionGraphView+Spec (L22)
   and LiveGraphBuilder+Spec (L21) disappeared from root after successful
   integration (both pairs passed manifest 150019 gates). Cause is unknown:
   coordinator commands were copy-only; no other files were lost (commit audit
   against lane-22 and review F-LOSS control). Both pairs were restored
   byte-for-byte from lane-21/lane-22: SessionGraphViewSpec 8/8,
   LiveGraphBuilderSpec 12/12; final gates EXECUTE 7/7 + runtime/repl/ci/
   tools/transport/security profiles passed; compile.sh green (116 Scala files
   in product modules).
2. **.env.yabanin in lane trees** (F-HYG-1): fully removed from all lanes (0
   files); the user's untracked file in root was left untouched.

## Final status for the night

Root EXECUTE 7/7 (manifest 20261010-234134+) and 6-profile regression passed
(234226). Open (morning/owner): LIN-05 tool recomputation (slice after
ReplayInterpreter), LIN-06/07, TabShell→REPL wiring, multi-job export
(schema decision), phase/recovery schema card (wave 3), M1-evidence RAI-099,
live-wire verification (owner endpoint/budget). No changes were committed
(only the owner commits).

---

# Addendum 3: W15–W17 (2026-10-11, coding plan)

- **W15**: LIN-05F ToolReplay/FsReadRecompute (recompute fs_read against the
  workspace, typed Unsupported/ArgsUnreadable/ReadFailed; lane-side
  OpenAIChatStreamSpec flake did not reproduce in root — 2/2 green in isolation),
  LIN-08B LineageCatalog (scan runsRoot → verifyPartial statuses → unified
  chat==headless table; persistence remains an obligation).
- **W16**: review — LIN-05F CHANGES-REQUIRED (F-COV-1 test gap) → L45
  (ToolReplaySpec 6→11 behavioral tests: escapes/binary/2000-boundary/
  canonical drift/unsupported; 1st attempt ended during research, 2nd was a
  code-first success); F-HYG closed (.raider cleaned, excludes expanded).
- **W17**: LIN-06A-prep ReplayModelBackend — recorded responses as ModelBackend
  (ordinal strategy, typed Overrun/Exhausted, honest capabilities). The
  LIN-05/06 replay chain is assembled end-to-end.

All integrations — EXECUTE 7/7 (latest manifest 20261011-174348+).
Open: LIN-06B/C (AgentLoop harness + diff report), LIN-07, TabShell wiring,
multi-job export (owner), schema cards (owner), live wire (owner). No commits —
only the owner commits.

---

# Addendum 4: W18–W19 (Evening, 2026-10-11)

- **LIN-07 slice**: SubtreeReplay (subtree→subgraph→verify against RecordedSource;
  subtree hash; golden deep-tree).
- **LIN-06B-1**: GraphComparator.compareNormalized (canonical job-id map) +
  ReplayHarness — **LIN5-01 executed end-to-end**: replay run through the real
  loop (ReplayModelBackend + fs-tool against an isolated fixture workspace)
  reproduces the recorded graph (normalized Match); replacing the response →
  Mismatch with the first divergence.
- **W18 review fixes**: FCOV-06 test + null cleanup of 13 literals
  (SessionGraphView/Spec, ToolReplaySpec) — raiderCli scalafix exit 0.
- Integration incident: modified ReplayModelBackend was not copied (L48),
  causing ReplayHarnessSpec to fail in root — found by full per-file comparison
  and then copied. Process fix: integration ALWAYS means a full per-file diff
  across all files in lane scope (not only “new” files).
Final: EXECUTE 7/7 (manifest 20261011-231726). Open: LIN-06C diff report,
LIN-07 full, owner items (multi-job, schema cards, live wire, RAI-099).
