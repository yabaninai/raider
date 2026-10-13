# Raider Execution Board

## 2026-10-13 — English documentation and verified current examples

DOCS-EN translation and gallery are integrated through visible Luna lanes; all task metadata and existing RAI-127/128/129 entries are preserved. The permanent documentation language is English. Current examples cover real Scala regression repair, evidence-based payment configuration audit, parallel REPL review with a checked test plan, and deterministic typed release readiness.

Candidate checks: clean compile; ten offline cases; live billing/audit/REPL scenarios through requested `glm-5.3-flash` in the Yabanin coding-plan registry. Initial model/verifier failures are retained and described in [DOCS-EN record](records/DOCS-EN.md). No runtime API, gate registry, dependency, neighboring repository, push, or deploy changes.

Final acceptance requires a passing, fresh `artifacts/docs-english-final/receipt.json` on the integrated `main` tree. Candidate evidence does not substitute for that receipt. Next available implementation card: RAI-127; existing RAI-128/129 remain separate.


## Current user documentation / examples 2026-10-13

Owner requested actual-main user docs, verified useful examples and merge to main.
Visible Luna writer + independent Luna reviewer; coordinator owns integration.
[User guide](../user-guide.md) and [REPL/bundle gallery](../user-guide-repl.md)
cover eight runnable examples. Single-command runner:
`python3 scripts/quality/user_examples.py`.
Pre-record integrated gallery: eight cases passed, real coding check 1→edit→0,
lineage Scala compiled; current limitations and scripted markers are explicit.
[Documentation record](records/USER-DOCS-CURRENT.md).

Separate reviewed corrections discovered by checks:
[README classifier](records/VERIFIER-README-CLASSIFICATION.md) adds only exact
README.md docs mapping; [SSE idle read](records/TRANSPORT-SSE-IDLE.md), worker
`96f02ed`, fixes cancelable owned body read. Existing deadline test reproduced
twice before fix; final targeted 12/12 and full lane sbt-test passed without
changed tests/bounds. Earlier failures retained in records.

Final main acceptance after merges/records is the external receipt
`artifacts/user-docs-final/receipt.json`; no lane-only freshness claim.
These component results do not announce M3/M4, live model quality, public SDK
or completed forge SDLC. Next SDK contract remains RAI-116.

## SDK research / owner decisions 2026-10-12

Priority clarified: **convenient Scala DSL for AI-native SDLC**, one workflow
locally and in a generic **local CI runner**. The first consumer is code
development `../tokenrouter` (Yabanin); gateway integration remains P11. Target
discovery was read-only; the originally dirty tree was not changed. Sources,
baseline, and hashes:
[research](../research/sdk-roadmap-research-2026-10-12.md).

Confirmed: full SDLC, automatic commit/PR/merge, Scala-configured human
checkpoints; release = version/changelog/**local tag**/local artifacts, without
pushing the tag, publishing a package/image, or deploying. CI engine/forge not
selected; first, a generic contract and local Scala stand; GitHub Actions is not
the first adapter. Preview is allowed before M4: current pinned Scala/ZIO,
experimental API with migration, no durable resume; trusted local data. Full
milestone acceptance remains in force.

**RAI-115 accepted (source audit)**: lane `lane/rai-115-sdk-audit`, final `c156f40`,
independent reviewer approved; [audit](../research/sdk-embedding-audit.md).
Lane docs validator / changed / execute / docs profile — exit 0;
manifest `wt/rai-115-sdk-audit/artifacts/quality/20261012-193634-72162-0001/manifest.json`.
This is not acceptance of the SDK implementation. Findings: String bundle
boundary, launch/lifecycle/admission parity, ignored UsageObserved/zero
settlement on the current AgentLoop success path. The accounting claim requires
a separate reproducer/fix gate.

**RAI-116 ready** for a separate workflow/launch ADR; **RAI-117 todo**, depends
on it, describes SDLC effects/checkpoints/forge/release and revisits prior
non-goals. Implementation cards will be assigned after the contracts and
remaining-work estimate; the current LIN-13/14/15 + RAI-113/114 wave continues.
RAI-115 budget, 120–240 h, is a gross estimate before deduplication; SDLC kit,
remote adapter, and remaining work are unknown. It is not a newly approved
budget or hours actually spent.

Planning scope: [ROADMAP-SDK-RESEARCH](../tasks/roadmap-sdk-research.md).
Result/review/evidence: [research record](records/ROADMAP-SDK-RESEARCH.md),
[RAI-115 record](records/RAI-115.md).
Workflow preview WF-01/02 is independent of host embedding/plugin UI/M2/M4;
SDLC-01–05 and SDK-01–05 remain explicit milestone obligations.

## Previous planning revision 2026-10-12: embedding SDK

Historical snapshot before the additional research above; current status:
RAI-115 is accepted, and the next ready SDK card is RAI-116.

At the owner's request, the SDK is embedded in existing P0/P4.C/P7/P10;
SDK-01–05 criteria and P0/P7/P9 pilots are described in the
[roadmap](../roadmap-12k.md). Scope of current planning work:
[ROADMAP-SDK](../tasks/roadmap-sdk-integration.md). Result/review/evidence:
[work record](records/ROADMAP-SDK.md). Raider remains a standalone coding
product; Scala/ZIO embedding is an additional user path, with no new top-level
phase. Detailed user documentation is deferred. The 13,100 h budget is the
current envelope; estimate the remaining work and record the SDK reallocation
before implementation. No new features have been accepted.

**Ready at this revision: RAI-115** — P0.A audit of the public SDK boundary
and packet for a separate ADR. Allowed output:
`docs/research/sdk-embedding-audit.md`; gates: docs. It can run in parallel with
LIN-13/14/15 and RAI-113/114 without shared code/build edits. New implementation
cards are registered after the audit/ADR; existing cards are not reopened or
given someone else's scope. The coordinator owns planning files; a visible,
read-only spawned reviewer independently checks this revision.

Owner confirmed: Raider + SDK, first scenario is CI, without stopping the
current wave; the project is fully open source (existing MIT/license policy).
Pilot users, timelines, and demand have not yet been specified; clarifying them
does not block the independent audit. A paid edition/closed SDK features are
out of scope.

## Previous execution status

Current improvement program (2026-10-05): [12,000-hour roadmap](../roadmap-12k.md)
and [parallel OpenCode prompt](../prompts/opencode-12k-parallel.txt).
Tabs/statuses/plugins first; all Yabanin work is P11 after accepted standalone M4.
P0 wave executed (gap matrix, ADR-024 frozen, runner fix, event seam, renderer
spike): [P0-WAVE0 record](records/P0-WAVE0.md). This is not runtime acceptance.

Last passing run (2026-10-09, W6×10): full sbt 569/0; fast PASSED; 9 profiles passed; ADR-025 FROZEN (R1-R8); M1-ACCEPTANCE compiled; delegation flakes 0 (×10); plugin update/rollback; JobJournal; state tools; CI wrappers (artifacts/quality/20261009-223155|223336|223502). Previous: W5×10: full sbt 545/0; fast PASSED; 9 profiles passed; UX03/04 MEASURED (p50 53.5/p95 55.5ms); delegation flakes stabilized (×10 + negative control); plugin hooks + state tools + journal + TabShell :plugins/:state + plugin wiring. (artifacts/quality/20261009-203453|203554, artifacts/ux-baseline-root.json). Previous: W4×10: full sbt 476/0; EXECUTE + 9 profiles (including security/transport/tools) passed; fast (10 gates) PASSED for the first time on a tree with changes (artifacts/quality/20261009-172910|185843|185942|191006). M1-DEMO PTY all_pass. Previous: W3×10: full sbt 406/0; EXECUTE + 8 profiles (including new security) passed; M1-DEMO PTY all_pass, 5 tabs/1105 events/0 lost (artifacts/quality/20261009-172428|172520, artifacts/tabs-pty). Previous: 2026-10-09 W2: EXECUTE 7/7 + profiles static/unit/contracts/runtime/ci on merged W2 tree; runtime 137/137, ui 16/16 (artifacts/quality/20261009-100041-*). Previous: 2026-10-08 W1: quality-changed EXECUTE 7/7 + profiles static/unit/contracts/runtime/ci (cli-smoke) on integrated W1 tree (artifacts/quality/20261008-001211-*, 20261008-001358-*); mock run + RA-CFG preflight demo. Previous: 2026-10-06 P0-WAVE0: quality-changed EXECUTE 7/7 on changed
tree (first honest non-clean-tree green after RAI-054), profiles
static/unit/contracts/runtime 5/5, module runtime 99/99 tests, PTY probe 4/4
(artifacts/quality/20261006-120717-*, 20261007-005445-*, artifacts/renderer-spike).
Earlier: quality fast 6/6 full-tree (artifacts/quality/20261004-181210), full sbt
test 188 green, coder agent with REAL fs_read/fs_search/proc_run + live openai
provider — record: [SELFDEV](records/SELFDEV.md).

## Night campaign 2026-10-10 (P-LIN waves W7–W13, parallel lanes)

**Owner decision (2026-10-12):** further work will use only **visible spawned
subagents** in the owner's interactive session (`/raider-wave`; agents
`raider-worker`/`raider-reviewer` in `.opencode/agents/`; mechanics in
parallel-development.md §1b). The background bash loop for nightly runs
(opencode-1k-continuation.txt) is **deprecated**; headless night mode requires a
separate explicit owner decision. Parallel lanes for remaining TUI/P1/P3 work
are open at the same time (roadmap revision 2026-10-12); close W13-review in
the first spawn-mode wave.

Owner authorized parallel night development; worker provider
zai/glm-5.3-flash (later zai-coding-plan, direct, without yabanin). Full
mechanics/statuses: `raider-night` registry (temp) + records W7-INTEGRATION,
W8-W9-INTEGRATION (addendum W10–W11). Integrated and run through full root
gates (EXECUTE 7/7, manifests 20261010-060836…224104):

- LIN-01A/B/C + GraphHash fixes (W9-F1, F-L28-1/2; BREAKING canonicalization
  documented), LIN-02A/B/C/D + renderer dotc fix, view-adapter
  (cli/lineage), LIN-02 LIN2-01 compile-gate (golden→pinned dotc GREEN),
  LIN-03A live builder + LIN-03B HeadlessRunner summary lineage hash,
  LIN-04A `:aux` + LIN-04B LineageTab provider, LIN-05A corpus + LIN-05B
  comparator/verifier + LIN5-03 partial replay (reducePartial),
  LIN-05D RecordedSource (W13, review approve 2026-10-12), LIN-08A docs/lineage.md.
- RAI-072 (events/tabs/cancel docs), RAI-079 (anthropic stand tool-round),
  RAI-080 (onboarding), RAI-096 (plugins SDK + 3 fixtures; examples are now
  in compile.sh), RAI-097 (unified StatusText: cli-chat + tab bar).
- Review cycles: W8-review (6/7→fixes closed), W9-review (6/7→closed),
  W10-review (3 Major→closed in W11), W12-review (5/6→R1/R2 closed by the
  coordinator, self-fixes reviewed in W13), W13-review: approve (2026-10-12,
  first spawn mode: independent raider-reviewer on wt/w13-review;
  RecordedSourceSpec 11/11 reproduced, evidence binding checked).

Open (after the night run): LIN-05 full interpreter (replay-recorded LLM from a
record; RecordedSource is a prerequisite), LIN-06/07 (live re-run, partial phase
replay), TabShell→REPL wiring (adjacent to RAI-062), multi-job export (owner
decision), M1-evidence card RAI-099 (coordinator), phase/recovery schema card
(wave 3, owner), live-wire verification (owner: endpoint/budget).

## Spawn campaign 2026-10-12 (visible agents, §1b; coordinator)

Wave 1 (spawn mode, 4 lanes): **W13-review LIN-05D approve** (independent
reviewer, RecordedSourceSpec 11/11 reproduced); **LIN-09** lineage run
artifacts (RunLineageArtifacts + 6 tests; reviewer approve; contract change →
LIN-11); **LIN-10** standalone replay CLI (ReplayCli; reviewer approve;
integrated gate caught forbidden System.exit → integration fix 60bccc5:
sanctioned ZIOAppDefault entry `ReplayCliMain`, process smoke exit 21
reproduced); **RAI-102** TabShell in the product REPL (contract change
implemented: build.sbt edge raiderRepl→raiderUi; TabShellHost + real PTY
P2W-01..03; reviewer approve); **LIN-11** LineageRenderer `()` closer for
def-free graphs (reviewer approve). Integration: main 60bccc5, merges
978194a/3f050e0/af4ff7f/56f244e. Integrated gates on candidate 150700f:
quality-full passed 10/10 (manifest 20261012-050132-69438-0001) + profiles
static/unit/contracts/ci/docs/runtime/repl/security passed 9/9
(manifest 20261012-050347-70594-0001). Open (owner): live-wire, multi-job export,
phase/recovery schema card; follow-up: wire TabShellHost into Main.ttyLoop,
wire ReplayCli into the product `raider` command. Wave 2: RAI-103..106
(remaining P2 work: drafts/scrollback/unread/palette-help on the product surface).

Wave 2 (spawn mode, 4 lanes; integration main f893d48): **RAI-103** drafts
(P2D-01..03, reviewer approve; no product changes — feature-specific PTY
specs), **RAI-104** scrollback bounds (P2S-01..03, approve; product
TabShellScrollback render), **RAI-105** unread badges (P2U-01..03; blocker
lane → coordinator determinism fix 71d0aa3: quiescence drain before :quit in
the P2W-01 driver, since the badge marker was nondeterministic), **RAI-106**
palette/help (P2P-01..03, approve; derived catalog from frozen CommandRouter,
TabShellHost paletteTerminal hook). Records: docs/work/records/RAI-103..106.md.
Integrated gates on f893d48: quality-full 10/10 (manifest
20261012-071006-49099-0001) + profiles static/unit/contracts/ci/docs/runtime/
repl/security 9/9 (manifest 20261012-071243-51219-0001). **RAI-107 Plan mode
(ADR-028) — mandatory owner card (2026-10-12): accepted** — lane rai-107 with
partial handoff (a dead rate-limit spawn was verified by a second worker and
preserved); ADR-028 Accepted (owner mandate); PLAN-01..03 green (scripted, 11
tests); review: code approved, 1 MAJOR evidence finding (record after gates
broke its own fingerprint) closed by rerunning on main; lane manifests marked
stale in the record (docs/work/records/RAI-107.md). Determinism fixes during
integration (determinism, not weaker assertions):
71d0aa3 quiescence drain P2W-01; 91a6575 :tabs-render retry P2U-03.
**Wave 3 (owner decisions 2026-10-12):** composition = wiring trio
(RAI-108/109/110) + RAI-111 (palette) + LIN-12 (catalog persistence) +
RAI-112 (M1-evidence refresh); approval UX = TUI plan screen; 4 lanes;
worktree cleanup deferred. RAI-108..112+LIN-12 registered (validate passed 127
cards, b75c2ec). INCIDENT: all 4 spawns in batch 1 failed due to provider
rate limits AFTER work had started — uncommitted partial work remained in all
4 worktrees. Fallback §1b(1): workers run sequentially in the coordinator's
primary session (reason recorded here); review is by spawned agents when the
provider recovers; self-approval is prohibited, merges only after independent
review.
Final integrated gates (main 91a6575): quality-full 10/10 (manifest
20261012-105946-2326-0001) + ci/contracts/docs/repl/runtime/security/static/unit
profiles 9/9 (manifest 20261012-110432-24403-0001); validate.py passed
(121 cards). Follow-ups: RAI-068 chat commands in palette; approval channel/UI
for plan mode; TabShellHost in Main.ttyLoop; ReplayCli in product command
`raider`; P2S render consumer step; plan-mode eval profile (milestone).

**Wave 4 (spawn mode §1b, 7 lanes + integration; base 4b46dc7 → main
10ea71c):** LIN-13/14/15 + RAI-113/114 registered (4b46dc7, validate 132
cards). Lanes and verdicts: **W22-R8-review** (W20–W21 tail: F-W22R1-1 the
only finding, closed in 5ff5318) → **W22-R8** sweep (R1 MAJOR:
`Gen.int(1,50)` was random sampling instead of a sweep; fixes 1cc02b3:
`Gen.fromIterable(1 to 50)` + 7 separate assertions; re-review approve,
determinism: 50 seed files identical ×3); **LIN-12** CatalogStore persistence
(append queue/per-path lock/upsert run-dir; LINC-01..03; approve; minor points:
no cross-process support, key path normalization); **LIN-13** lineage §9.2 sync
(17/17 evidence links; approve; related §10 edits marked); **LIN-14** LIN2-03
race spec (4 readers vs writer, 0 partial reads ×3; approve; exposed:
PlanPanelSpec flake 1/4, baseline scalafix ParseError at PlanTabsPtyApp:173
from e1a8c9f); **LIN-15** LIN-06 scripted acceptance LIN6-01/02/03 (typed
fixes in IsolatedRerun/ReplayHarness with reproducers; approve; minor: alias
hole in guard — follow-up RAI-117); **RAI-113** :scrollback consumer
(registry+PTY P2SR-01..03; approve; minor: precedence vs :switch — follow-up
RAI-118); **RAI-114** plan-mode eval corpus, 6 scenarios (PEVAL-01..03 ×3;
approve). PlanTabsPtyApp conflict resolved in favor of RAI-114's superset fix.
Integration: merges c98fb77…29d770b + records (W22-R8.md assembled; LIN-12/
RAI-113 transferred from worktrees).
Integrated gates on candidate 10ea71c: quality-full (full sbt test + repl/cli
smokes) — the first sbt-test attempt honestly failed on the known
TabShellUnreadPtySpec PTY flake under load from 8 lane JVMs (isolated run 3/3
in 40s, flake is not a regression; honest failed manifest
20261012-200222-93693-0001), then passed on retry; quality-changed EXECUTE 10/10
(manifest 20261012-200841-98234-0001); static/unit/contracts/ci/docs/runtime/
repl/security profiles 9/9 (manifest 20261012-201326-4234-0001).
Follow-ups registered: RAI-116 (PTY determinism under load), RAI-117
(canonical-path guard), RAI-118 (:scrollback/:switch precedence).
The SDK research track also landed independently on main (3a60f93…318fcf4,
RAI-115) — retained; no conflicts with wave 4.

**Wave 4b (spawn mode §1b, base 9a43224 → main 6790c6f):** RAI-116..118
renumbered at registration to RAI-119..121 (collision with the SDK track, which
took RAI-116/117). Lanes and verdicts: **RAI-119** PTY determinism (3 test
files; driver infra att1 reproducer: evidence-dir before child starts;
PlanPanel drain+refresh-retry; TabShellUnreadPty waitQuiet-drain + fresh-render
PlanPanel drain+refresh-retry; TabShellUnreadPty waitQuiet-drain + fresh-render
retry run9; PTYD-01/02 10/10 under load 156–164, PTYD-03 raiderRepl ×3 +
full sbt test; review approve; candid note: the wave-4 PlanPanel class was not
reproduced in 18 attempts — the fix is preventive, recurrence → conditional
RAI-123); **RAI-120** IsolatedRerun canonical-path guard (isSameFile before
byte-check, fail-closed on missing/IO; ISO-01..03; review approve; minor: guard
failure typing is too coarse — account for this in the LIN-15 wiring card);
**RAI-121** :scrollback digit-first precedence (checked line by line against
frozen :switch, assertions unchanged — UUID is never all-digit; SCB-01..03;
review approve; dead rate-limit spawn partial verified in lane — second
incident, pattern is reproducible); **RAI-112** M1-evidence refresh — M1R-01
BLOCKED: frozen tabs_pty_probe expects legacy `[Running]`, while the product
renders honest ADR-024 statuses → **RAI-122** (protected verifier: ROW_RE =
StatusText grammar, ok_live → “[running | cost unknown]”, :aux in HINT_BAR,
pump guard before :quit; review approved both scope points, negative control
PROBE-02 holds); M1R-01 repeated on candidate ×3 all_pass → **RAI-112 done**
(addendum in record).
Integration: merges 81dad02/8829796/8809e16/05bbe60/7fd2776; RAI-119 evidence
(artifacts/rai-119) and RAI-122 (artifacts/rai-122-evidence) transferred to
root. Integrated gates on candidate 7fd2776: EXECUTE 10/10 (manifest
20261012-233255-59159-0001, full sbt test without flakes), profiles
static/unit/contracts/ci/docs/runtime/repl/security 9/9 (manifest
20261012-233800-62893-0001); validate 140 cards. Open: RAI-123 (conditional,
on recurrence of the PlanPanel race), LIN-06 live-wire/multi-job/schema card
(owner), P2S rendering — closed by RAI-113; plan-mode eval profile — corpus
ready (RAI-114), profile registration in working-product stage.

**Wave 4c (visible spawn mode §1b, base 7702fd0 → main acca49f) and obligations audit:** RAI-124/126 were registered (7702fd0, validate 142). RAI-124 implements REPL `:type` through native dotty TypeOf and frozen engine.eval WITHOUT execution: no side-effect marker, no resN advance; helpText/catalog freshness; TYPE-01..03, PTY ×3 flake=0; review approve. Minor findings: saturated PTY echo oracles and misleading UX error prefix → RAI-130. RAI-126 applies COURSIER_CACHE setdefault to three PTY-probe sites, including sbt_run :159 outside the original card anchor; the deviation is explicitly documented and approved by review. CC-01..03 numerically verify the caller cache grows from 0 to 180 files; protected verifiers, review approve. Independent read-only Open Obligations audit against main 7702fd0 found 7 of 18 rows CLOSED/STALE (file:line evidence); the key W05 gap is missing Anthropic tool definitions. The table was replaced; RAI-127/128/129 were registered from the audit, RAI-130 from review, validate 146 cards. Merges: 6f4e9e3/acca49f. Integrated gates on acca49f: EXECUTE 10/10 (20261013-023006-78942-0001), profiles 9/9 (20261013-023534-82374-0001). Hygiene: rescope RAI-015 (m0.md) through a separate card. Wave 4d: RAI-127/128/129/130.

**Wave 4d (visible spawn mode §1b, base 0e119da → main 1fd8636; scalafmt-fix
committer follow-up):** Lanes and verdicts: **RAI-127** Anthropic tool
definitions on the wire (encodeTools: input_schema verbatim from
parametersJsonSchema; definition-gated stand round — tool_use only when
definitions are on the wire; empty tools remain byte-for-byte unchanged;
RED→green; review approved; minor findings: schema validated as a JSON object
without deep semantics, tool_result.content remains a string — pre-existing;
do not consider all of W05 closed); **RAI-128** named cross-root fairness test
(FAIR-01..03: bounded FIFO, peak==1 tripwire, per-root exhaustion — typed refusal
only for the exhausted root, negative root-priority control detected; review
approved; minor findings: 5ms settling can only produce a false red,
forkDaemon tails are harmless); **RAI-129** `raider resume <run-dir>` — read-only
recovery from events.jsonl (reducePartial → named fields + SHA-256 references;
typed exits 0/26/21/20; torn tail marked without tracing that tail; run directory
remains byte-identical; review approved; minor findings: exit-20 branch untested
— assigned to 129.b; checkpoint.json is an unused name; documentation must
explain events.jsonl versus segments when consumed); **RAI-130** :type PTY
oracles no longer accept saturated echo (send→echo→answer after the last anchor;
negative control TYPED-01: without send, 12 oracles fail;
compileErrorText(msg, typeQuery) gives accurate diagnostics without
“prelude broken?”; review approved; minor finding: identical repeated query
03c lacks per-step oracle isolation, covered at suite level). Integration:
merges 89888f6/84b3ade/8c83ec1/1fd8636; scalafmt-check correctly failed on the
candidate (6 lane files) → whitespace-only fix → EXECUTE 10/10 (manifest
20261013-042259-77530-0001), profiles
static/unit/contracts/ci/docs/runtime/repl/security 9/9 (manifest
20261013-042817-84409-0001). Open: 129.b (continuation) / 129.c (retention)
after an owner schema card; W05-tool_result content (structured block mapping)
requires a candidate card.

## Night campaign 2026-10-13 (owner decision)

Owner decision (interactive session, 2026-10-13): overnight development
continuation is authorized in the visible spawn-agent mode (parallel-development
§1b) — explicitly NOT the deprecated headless night-loop; combined cards are
allowed so that more functionality ships per lane. Reason: use idle night
capacity to accelerate progress toward the working product. All new records,
cards, and board text follow the English documentation policy (2026-10-13).

Registered night cards (validate passed, 152 cards):

- **RAI-131** — CI adapter batch (combined RAI-023 remainder + RAI-024):
  GitHub/GitLab context fixtures, trust preflight (provenance before spend),
  per-context exit/artifact contracts (W12 core).
- **RAI-132** — Steering surface (P3 send/steering remainder): FacadeOps/job
  send wiring, REPL :send command, typed receipts, golden DSL steering.
- **RAI-133** — W05 completion: structured tool_result content blocks on the
  Anthropic wire + cross-wire parity spec (OpenAI vs Anthropic stands).
- **RAI-134** — User guide refresh for all new surfaces (English).
- **RAI-135** — Registry: additive eval/perf component-stage profiles
  (coordinator-executed gate-policy change; independent review required).
- **RAI-136** — Load soak of the PTY family + RAI-123 trigger probe
  (conditional product fix only with a captured reproducer).

Wave N1 execution fallback (recorded 2026-10-13): mid-wave the environment
permission config restricted task-tool spawns to read-only explore agents only
(worker/reviewer spawns denied). Per parallel-development §1b fallback (1):
night cards execute SEQUENTIALLY in the primary coordinator session
(coordinator-as-worker, wave-3 precedent); each completed card gets an
independent read-only verification pass (explore agent) before merge — no
self-acceptance. Execution order by value density: RAI-135 (coordinator-owned
registry change) -> RAI-133 (W05 completion) -> RAI-131 (CI adapter batch) ->
RAI-132 (steering) -> RAI-134 (guide refresh) -> RAI-136 (soak). Owner-gated
items remain untouched (live-wire, multi-job export, phase/recovery
schema-card, F-14 sign-off).

## Module Status

| Card / scope | Status | Evidence / next step |
| --- | --- | --- |
| RAI-001 Feasibility | changes-required | [original record](records/RAI-001.md), independent review, hash manifest |
| SPIKE-OAI-01 Transport feasibility | changes-required | [original record](records/SPIKE-OAI-01.md) |
| RAI-003 Pinned build | todo | preparation allowed in parallel with remediation |
| RAI-004 Quality runner | todo | stage-aware registry + non-git snapshot classifier |
| RAI-007 Jobs registry | accepted | [record](records/RAI-007.md); 10 runtime tests; F-1 regression |
| RAI-008 Scopes | accepted | [record](records/RAI-008.md); SCOPE-01..03 matrix |
| RAI-009 Budget/admission | accepted | [record](records/RAI-009.md); 33/33 tests ×3 |
| RAI-010 AgentLoop | accepted | [record](records/RAI-010-A.md); LOOP-01..03; 18/18 ×3; 188 total |
| RAI-002 API/schemas | accepted (slice) | [record](records/RAI-002-REMAINDER.md); bundle/result/launch schemas; ContractsSpec 35 ALL PASS |
| RAI-005 Config | accepted (slice) | [record](records/RAI-005-SLICE-A.md); core 13/13; CFG-01..03 |
| RAI-006 Testkit | accepted (slice) | [record](records/RAI-006-B.md); controllable primitives, deterministic cancel |
| RAI-009 Budget remainder | accepted (slice) | [record](records/RAI-009-B.md); stress + late-settlement 7/7 ×3 |
| RAI-011 OpenAI transport | accepted (slice) | [record](records/RAI-011-SLICE.md); non-streaming + SSE + tool calls; live-verified |
| RAI-012 SSE streaming | accepted (slice) | [record](records/RAI-012-SLICE.md); SseFramer + ChatChunkParser; 17/17 ×3 |
| RAI-013 Anthropic | accepted (slice) | [record](records/RAI-013-SLICE.md); Messages wire, native tool_use/tool_result; 11/11 ×3 |
| RAI-014 Read/search | accepted (slice) | [record](records/RAI-014-SLICE-A.md); 13/13 |
| RAI-016 Process | accepted (slice) | [record](records/RAI-016-SLICE-A.md); 6/6 |
| RAI-017 Composition | accepted (slice) | [record](records/RAI-017-SLICE-A.md); 10/10 |
| RAI-022 Headless runner | accepted (slice) | [record](records/RAI-022-SLICE.md); jar bundles, chat mode, SIGTERM; 7/7 ×3 |
| REPL-FAST | done | [record](records/REPL-FAST.md); real compiler, 13/13 PTY |
| REPL-LOOP | done | [record](records/REPL-LOOP.md); facade on AgentLoop |
| DSL-ERGO | done | [record](records/DSL-ERGO.md); lazy descriptors, all/batch, openSession |
| DELEGATION | done | [record](records/DELEGATION.md); delegate/await/cancel/list; 7/7 ×3 |
| SELFDEV | done | [record](records/SELFDEV.md); coder agent, live backend, fs tools; 10/10 |
| RAI-054 Quality runner fix | accepted | [record](records/P0-WAVE0.md); classify file-keyed + git mode; EXECUTE green on dirty tree |
| RAI-055 Events (P1.A slice) | accepted | [record](records/P0-WAVE0.md); ADR-024 frozen; raider.runtime.events; 99/99; EVT-01..04 |
| RAI-056 Renderer PTY spike (P0.C) | accepted | [record](records/P0-WAVE0.md); RENDER-01..04 4/4; baseline in artifacts/renderer-spike |
| RAI-057 SessionRegistry (P2.A) | accepted | [W1 record](records/W1-EVENTS-TABS.md); real-hub tabs, TAB-01..03, 5/5 |
| RAI-058 Loop emitters (P1.B-1) | accepted | [W1 record](records/W1-EVENTS-TABS.md); real rounds, outcomes, 109/109 runtime |
| RAI-064 Credentials env-only | accepted | [W1 record](records/W1-EVENTS-TABS.md); --api-key removed, helper bounded+masked; security-profile obligation open |
| RAI-059 Jobs/budget/delegation emitters (P1.B-2) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); EMJ-01..03, 18 emission tests; Terminal authority for UI |
| REPL-LIVE-BRIDGE | done | live prelude built in interpreter world (MainBridge classloader split deleted); live Qwen round-trip; 21/21 repl, PTY 13/13, EXECUTE 7/7; [record](records/REPL-LIVE-BRIDGE.md) |
| RAI-063 OOB cancel/deadlines (P3.B) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); CANCEL-01..03 ×20 ≤5s; 119/119 lane |
| RAI-061 Tab shell (P2.B) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); SHELL-01..03; 16/16 ui |
| RAI-060 Status line (P1.C) | accepted | [W3 record](records/W3-PARALLEL-10.md); integrated, gates green |
| RAI-062 PTY integration (P2.C) | accepted | [RAI-062 record](records/RAI-062.md); PTY-01..03 + M1-DEMO all_pass |
| LIN-12 Catalog persistence | accepted | [record](records/LIN-12.md); LINC-01..03; review approve (minors: cross-process/path-norm) |
| LIN-13 lineage §9.2 sync | accepted | [record](records/LIN-13.md); DOC-13-01..03; review approve |
| LIN-14 LIN2-03 race coverage | accepted | [record](records/LIN-14.md); 0 partial reads ×3; review approve |
| LIN-15 LIN-06 scripted acceptance | accepted | [record](records/LIN-15.md); LIN6-01/02/03(sc); review approve; alias-guard → RAI-117 |
| RAI-113 :scrollback consumer | accepted | [record](records/RAI-113.md); P2SR-01..03 PTY; review approve; precedence → RAI-118 |
| RAI-114 plan-mode eval corpus | accepted | [record](records/RAI-114.md); PEVAL-01..03 ×3; review approve |
| W22-R8 replay sweep | accepted | [record](records/W22-R8.md); 50-seed deterministic ×3; review R1-R3 fixes, re-review approve |
| RAI-112 M1 evidence refresh | accepted | [record + addendum](records/RAI-112.md); M1R-01..03 met on 7fd2776 (probe ×3 all_pass after RAI-122) |
| RAI-119 PTY determinism | accepted | [record](records/RAI-119.md); PTYD-01/02 10/10 under load; review approve; recurrence → RAI-123 |
| RAI-120 IsolatedRerun guard | accepted | [record](records/RAI-120.md); ISO-01..03 fail-closed; review approve |
| RAI-121 :scrollback precedence | accepted | [record](records/RAI-121.md); SCB-01..03, digit-first = :switch; review approve |
| RAI-122 tabs_pty_probe sync | accepted | [record](records/RAI-122.md); PROBE-01..03; protected verifier, review approve |
| RAI-124 REPL :type | accepted | [record](records/RAI-124.md); TYPE-01..03, without execution; review approve; follow-ups → RAI-130 |
| RAI-126 probes COURSIER_CACHE | accepted | [record](records/RAI-126.md); CC-01..03; protected verifiers, review approve |
| RAI-003/023/027 etc. | todo | prerequisites not met |
| M1 | accepted-pending-owner-note | [M1-ACCEPTANCE.md](records/M1-ACCEPTANCE.md) (evidence compiled) |
| Remaining M1/M2/P2 | todo | per working-product.md acceptance |

## Open Obligations

Historical audit snapshot, verified 2026-10-13 against main `acca49f` (file:line facts in the wave 4c audit record). The OPEN/ready states below describe that source revision. Later Wave 4d and night-campaign entries record later changes and registered work; use those entries and the [canonical backlog](../tasks/backlog.json) for current status. At the audit revision, seven rows were closed/stale and three were owner-gated; the remaining obligations were:

| Obligation | Card/condition | Status |
| --- | --- | --- |
| Live-wire verification (real providers) | OWNER (endpoint/budget) | OWNER-GATED (lineage.md §9.2; ad-hoc live rounds existed, but formal scoped verification is absent) |
| Tool definitions on wire — Anthropic | RAI-127 (ready) | OPEN — AnthropicMessagesBackend does not send tools/input_schema (OpenAI does); W05 gap |
| Journal/artifacts/recovery (§13) | RAI-129 (ready, narrow slice) + owner schema card | OPEN — journal+replay exist; product recovery/resume commands do not; retention/redaction and wire work are separate |
| CI full adapters | RAI-023(full)/024/026 | OPEN — RAI-078/100 accepted; trust preflight/provenance, trusted checks/exit contracts, and MVP acceptance remain |
| Fair admission across DIFFERENT roots | RAI-128 (ready) | OPEN — fair FIFO by construction; no named test with ≥2 roots |
| Performance/eval profile in registry | RAI-004 remainder; working-product stage | OPEN — registry has 12 profiles; eval corpus ready (RAI-114) |
| send_agent (steering) — REPL facade | RAI-018 remainder / RAI-021 | OPEN (narrow) — runtime steering implemented (RAI-065/074), FacadeOps.send unwired |
| Owner sign-off F-14 | OWNER | OWNER-GATED (RAI-007.md:59-61); runtime profile already exists in registry |
| Multi-job export / LIN-06 live rerun / phase-recovery schema card | OWNER + LIN-06 | OWNER-GATED |
| :type PTY oracle desaturation | RAI-130 (ready) | OPEN — RAI-124 echo-saturation findings |
| ~~Edit tool refinements~~ | CLOSED | fs_patch+UnifiedDiff+PatchTool; RAI-067 multi-file accepted; rescope RAI-015 in m0.md (hygiene) |
| ~~Child deadlines / OOB-cancel~~ | CLOSED | RAI-063 accepted |
| ~~Chat memory in CLI~~ | CLOSED | SessionStore, --resume, :compact |
| ~~REPL: :type, completion~~ | CLOSED | completion RAI-068; :type RAI-124 (merged acca49f) |
| ~~Virtual backoff fixture~~ | STALE | RateLimitRetries and two specs exist |
| ~~Event emission into hub~~ | CLOSED | RAI-058/059 accepted |
| ~~Tabs UI on ADR-024~~ | CLOSED | RAI-057/061/062/102/108 + RAI-103..106/113 |
| ~~--api-key in argv~~ | STALE | RAI-064: removed, typed error |
| ~~Probe COURSIER_CACHE setdefault~~ | CLOSED | RAI-126 (merged acca49f) |
| ~~Plugin SPI ADR/design~~ | CLOSED | ADR-025 FROZEN; RAI-066/075/083/096/101 |

## Key Findings (fixed by fixtures)

| Finding | Fix |
| --- | --- |
| zio-streams: `empty *> s` drops `s` (zip semantics) | use `++` + `.drain` |
| Scala 3.9: E046 cyclic on `x.call.y` chains | intermediate vals, no chains |
| Non-daemon fork interrupted by supervisor on parent completion | use `.forkDaemon` |
| System.exit from shutdown hook deadlocks runHooks | hook = interrupt + bounded wait + return |
| System.exit doesn't flush piped stdout | explicit flush |
| Nested admission on maxLLM=1 deadlocks runner+loop | runner not double-admission |
| java glob `**/*.scala` doesn't match root files | use `*.scala` |
| SSE frames lost at chunk boundaries | state in fields, not locals |
| `ArrayBuffer[Byte].indexOf('\n')` — Char never matches Byte | `'\n'.toByte` |
| `readNBytes(8192)` blocks until exactly 8192/EOF | use `read(buf)` for streaming |
| Reasoning models burn tokens on thinking | MaxOutputTokens 4096 |
| Stale --mock check blocks live provider | removed (provider flag governs) |

## Session History

| Date | Event |
| --- | --- |
| 2026-10-09 (W6-PARALLEL) | 9 cards: 083 plugin wiring, 084 HookRunner, 085 StateTools, 086 CI/accounting wiring, 087 JobJournal, 088 :plugins/:state, 090/098 REPL env-key+anthropic live, 091 forbidden-apis allowlist (APPROVE), 092 delegation stabilization ×10, 093 journal wiring, 094 state tools→tools/ui, 095 hooks→loop, 096 P4.C SDK, 097 unified status render, 099 M1-ACCEPTANCE compiled, 100 CI wrappers, 101 update/rollback. ADR-025 FROZEN. Full sbt 569/0. Record: W6-PARALLEL.md |
| 2026-10-09 (W5-PARALLEL) | 9 cards: 083 plugin wiring, 084 HookRunner, 085 StateTools, 086 CI/accounting wiring, 087 JobJournal, 088 :plugins/:state screens, 089 UX baseline MEASURED, 090 REPL env-key, 091 forbidden-apis allowlist (policy APPROVE), 092 delegation test stabilization. Full sbt 545/0. Record: W5-PARALLEL.md |
| 2026-10-09 (W4-PARALLEL) | 9 cards: RAI-074 mailbox wiring (send_to_child, 171/171 runtime), 075 PluginLoader+PluginCli (P4.B core), 076 StateStore SPI (P5.A), 077 Session v2+migrations, 078 CiContext (RAI-023 slice), 079 anthropic stand tool-round, 080 onboarding guide, 081 eval fixtures ×6 (protected), 082 accounting view. Full sbt 476/0; fast PASSED (10 gates). Record: W4-PARALLEL.md |
| 2026-10-09 (W3-PARALLEL-10) | 10 cards in the wave (owner decision 3→10 threads): RAI-062 PTY tabs (M1-DEMO all_pass), 065 mailbox, 066 ADR-025+SPI skeleton, 067 multi-file patch, 068 REPL completions, 069 security profile (gate-policy APPROVE), 070 UX baseline harness, 071 anthropic CLI, 072 docs, 073 Delegated-race. Full sbt 406/0. Record: W3-PARALLEL-10.md |
| 2026-10-09 (W3-PLAN) | RAI-060 accepted (integrated, gates green). OWNER DECISION: parallelism cap raised 3→10 threads (runbook §1 superseded for this wave; recorded here). Registered W3 wave: RAI-062/065..072 (8 lanes + reviewer + coordinator = 10). Validator 75 cards. |
| 2026-10-09 (W2-EMITTERS-CANCEL-SHELL) | RAI-059 emitters + RAI-063 OOB cancel/deadlines + RAI-061 tab shell accepted; DelegationToolset hand-merge verified pure-addition; runtime 137/137, ui 16/16; integrated gates green. Record: W2-EMITTERS-CANCEL-SHELL.md |
| 2026-10-08 (REPL-LIVE-BRIDGE) | Live REPL fixed: phase-3 prelude read console-world MainBridge statics; scala3-repl classloader split made interpreter world see null backend (NPE on every live ask). Prelude now wires streaming backend + coding tools inline; honest banner; MainBridge deleted. raiderRepl 21/21, PTY 13/13, quality-changed EXECUTE 7/7, live Qwen round-trip via local llama.cpp. Record: REPL-LIVE-BRIDGE.md |
| 2026-10-08 (W1-EVENTS-TABS) | RAI-057 SessionRegistry + RAI-058 loop emitters + RAI-064 credentials env-only accepted (3 lanes, independent review, fix rounds). Runtime 109/109, cli 44/44, ui 5/5. Integrated EXECUTE 7/7 + runtime/ci profiles. Record: W1-EVENTS-TABS.md |
| 2026-10-06 (P0-WAVE0) | 12k roadmap first wave: P0.A gap matrix (W01–W18/UX01–08, 2 runner defects + --api-key finding); ADR-024 event seam frozen (2 review rounds); RAI-054 runner fix (first honest dirty-tree EXECUTE green); RAI-055 raider.runtime.events (EVT-01..04, 99/99, zio queue-suspend defect found+fixed); RAI-056 renderer PTY spike (draft survives 200 writes + 3×67 overlap, width table baseline). Isolated snapshot lanes, independent review each. Record: P0-WAVE0.md |
| 2026-10-05 (ROADMAP-V2) | Plan v2 11,700h: optional agent state registry (Phase 3, 800h — scopes/blackboard/opt-in tools/default-off) + Yabanin direct integration slot (Phase 7, 900h, discovery-first per docs/yabanin-integration.md). New launch prompt MASTER-10K-STATE.md; ADR series 017–023; cross-refs renumbered. Gates 10/10. Record: ROADMAP-V2-STATE-YABANIN.md |
| 2026-10-05 (ROADMAP-MIT-START) | MIT-only enforced: license_audit.py gate (fast=10 gates), baseline 25 artifacts 0 problems, ADR-016 accepted (custom ANSI renderer on JLine, Lanterna rejected as LGPL), roadmap+licensing.md amended. Gates 10/10. Record: ROADMAP-MIT-START.md |
| 2026-10-05 (NIGHTLY-FOLLOWUP) | CoderPrompts single-sourced; make quality-full executed end-to-end (exit 0). FIXED: Makefile COURSIER_CACHE inline-default bug (coursier relative cache dumped 223MB https/ into repo — removed, gitignored), SECOND flaky delegation test (max_children, call-index race) content-routed, 6/6 clean. full 0 fail, gates 9/9. Record: NIGHTLY-FOLLOWUP.md |
| 2026-10-05 (NIGHTLY-P6) | Docs: api.md (public API + examples), architecture.md (modules, round data flow, budget/admission), contributing.md (tool/provider/gates/tests). FINAL CHECKLIST 10/10 incl. PTY smoke 13/13 and LIVE self-hosting round (local llama.cpp, Succeeded). Record: NIGHTLY-PHASE-6.md |
| 2026-10-05 (NIGHTLY-P5) | Packaging: GitHub Actions CI (test+quality jobs), raider-cli.jar via sbt-assembly (java -jar smoke PASS), Makefile raider-jar/raider-chat/raider-run/quality-full. full 0 fail, gates 9/9. Record: NIGHTLY-PHASE-5.md |
| 2026-10-05 (NIGHTLY-P4) | Recovery: CompileFixLoop (:fix, errors→model→recompile ≤3), RateLimitRetries (429 backoff 1/2/4s ×3, all 3 construction sites), coder prompts upgraded. FIXED flaky delegation cancel test (turn routing by content, bounded settle) — was hanging suites under load. full 0 fail ×2, gates 9/9. Record: NIGHTLY-PHASE-4.md |
| 2026-10-05 (NIGHTLY-P3) | REPL live: streaming backend via MainBridge, RAIDER_MODEL→session.model, system prompt on every facade call, live echo (deltas stdout, tools stderr). ReplSession +2 defaulted members. repl 21, full 0 fail, gates 9/9, repl_smoke PASS. Record: NIGHTLY-PHASE-3.md |
| 2026-10-05 (NIGHTLY-P2) | Core: CLI streaming (SSE backend + StreamingDisplay), SessionStore (--resume/:save/:sessions/:load), compaction (:compact + auto), fs_patch (unified diff, sha+atomic), fs_tree (bounded, skip-list), proc_run parsed field (sbt compile/test). cli 27, tools 46, full 0 fail, gates 9/9. Record: NIGHTLY-PHASE-2.md |
| 2026-10-05 (NIGHTLY-P1) | Quality infra: scalafmt+scalafix gates, forbidden_apis.py (self-tested), repl/cli smoke gates, profiles tools/transport/repl/ci, fast=9 gates. Null-free rule enabled (15 sites refactored). compile+test 0 fail, fast 9/9, profiles passed. Record: NIGHTLY-PHASE-1.md |
| 2026-10-04 (SELFDEV) | coder agent: CodingToolset (fs_read/fs_search/proc_run with containment and policy-denial=feedback) + --provider openai/--base-url/--model in CLI; selfdev launcher + prompts. cli 10/10, tools 19/19, full 188, gates 6/6. Record: SELFDEV.md |
| 2026-10-04 (RAI-022 remainder) | --agent mode + SIGTERM bounded stop. Caught by probes: non-daemon fork interrupted by supervisor; exit from hook deadlocks; stdout not flushed to pipe; nested admission deadlocks on maxLLM=1. 7/7 ×3; full 185; gates 6/6. RAI-022-SLICE.md updated |
| 2026-10-04 (RAI-022) | Headless runner: cli module (no compiler/JLine), jar-bundle via BundleWorkflow SPI, preflight before spend, artifacts, exit mapping 18 families. 5/5 ×3 + cli_smoke PASS. Record: RAI-022-SLICE.md |
| 2026-10-04 (DELEGATION) | §11: DelegationToolset over JobManager+AgentLoop; one root-ledger (6 dispatches), maxLLM=1 no-deadlock, no recursion. 7/7 ×3; full 178; gates 6/6 ×2. Finding: stuck child at maxLLM=1 blocks parent cancel. Record: DELEGATION.md |
| 2026-10-04 (DSL-ERGO) | Less verbose DSL in REPL facade: AgentAsk (lazy, API-03), all/batch/openSession over composition, @targetName-await, heterogeneous job registry. 9/9 ×3; full 171; smokes green. Record: DSL-ERGO.md |
| 2026-10-04 (RAI-013) | Anthropic Messages slice: provider-anthropic module; non-streaming+SSE, native tool_use/tool_result, cumulative usage. 11/11 ×3; full 162. Record: RAI-013-SLICE.md |
| 2026-10-04 (RAI-012) | SSE streaming slice: SseFramer (byte-level, UTF-8-safe) + ChatChunkParser + streaming backend. Partition property, typed negatives, cancel-mid-stream. 17/17 ×3; full 151. Record: RAI-012-SLICE.md |
| 2026-10-04 (RAI-011) | OpenAI transport slice: provider-chat module, JDK HttpClient, non-streaming + tool_calls. Capstone: AgentLoop over HTTP. 4/4 ×3. Record: RAI-011-SLICE.md |
| 2026-10-03 (40h session final) | Full test 188/0, gates 6/6, all smokes green. Records: RAI-010-A through RAI-011-SLICE |
