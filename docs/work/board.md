# Raider Execution Board

Current improvement program (2026-10-05): [12,000-hour roadmap](../roadmap-12k.md)
and [parallel OpenCode prompt](../prompts/opencode-12k-parallel.txt).
Tabs/statuses/plugins first; all Yabanin work is P11 after accepted standalone M4.
P0 wave executed (gap matrix, ADR-024 frozen, runner fix, event seam, renderer
spike): [P0-WAVE0 record](records/P0-WAVE0.md). This is not runtime acceptance.

Last passing run (2026-10-09, W6×10): full sbt 569/0; fast PASSED; 9 профилей passed; ADR-025 FROZEN (R1-R8); M1-ACCEPTANCE compiled; delegation-флейки 0 (×10); plugin update/rollback; JobJournal; state tools; CI wrappers (artifacts/quality/20261009-223155|223336|223502). Предыдущие: W5×10: full sbt 545/0; fast PASSED; 9 профилей passed; UX03/04 ЗАМЕРЕНЫ (p50 53.5/p95 55.5ms); delegation-флейки стабилизированы (×10 + negative control); plugin hooks + state tools + journal + TabShell :plugins/:state + plugin wiring. (artifacts/quality/20261009-203453|203554, artifacts/ux-baseline-root.json). Предыдущие: W4×10: full sbt 476/0; EXECUTE + 9 профилей (вкл. security/transport/tools) passed; fast (10 gates) PASSED впервые на дереве с изменениями (artifacts/quality/20261009-172910|185843|185942|191006). M1-DEMO PTY all_pass. Предыдущие: W3×10: full sbt 406/0; EXECUTE + 8 профилей (вкл. новый security) passed; M1-DEMO PTY all_pass 5 вкладок/1105 событий/0 потерь (artifacts/quality/20261009-172428|172520, artifacts/tabs-pty). Предыдущие: 2026-10-09 W2: EXECUTE 7/7 + profiles static/unit/contracts/runtime/ci on merged W2 tree; runtime 137/137, ui 16/16 (artifacts/quality/20261009-100041-*). Previous: 2026-10-08 W1: quality-changed EXECUTE 7/7 + profiles static/unit/contracts/runtime/ci (cli-smoke) on integrated W1 tree (artifacts/quality/20261008-001211-*, 20261008-001358-*); mock run + RA-CFG preflight demo. Previous: 2026-10-06 P0-WAVE0: quality-changed EXECUTE 7/7 on changed
tree (first honest non-clean-tree green after RAI-054), profiles
static/unit/contracts/runtime 5/5, module runtime 99/99 tests, PTY probe 4/4
(artifacts/quality/20261006-120717-*, 20261007-005445-*, artifacts/renderer-spike).
Earlier: quality fast 6/6 full-tree (artifacts/quality/20261004-181210), full sbt
test 188 green, coder agent with REAL fs_read/fs_search/proc_run + live openai
provider — record: [SELFDEV](records/SELFDEV.md).

## Night campaign 2026-10-10 (P-LIN waves W7–W13, parallel lanes)

**Решение владельца (2026-10-12):** дальнейшие доработки — только **видимые
spawn-субагенты** в интерактивной сессии владельца (`/raider-wave`; агенты
`raider-worker`/`raider-reviewer` в `.opencode/agents/`; механика —
parallel-development.md §1b). Фоновый bash-цикл ночного запуска
(opencode-1k-continuation.txt) — **deprecated**; headless-ночной режим — только
отдельным явным решением владельца. Одновременно открыты параллельные lanes
TUI/P1/P3-остатков (roadmap ревизия 2026-10-12); W13-review закрыть в первом
spawn-режиме волной.

Владелец разрешил ночную параллельную разработку; провайдер воркеров
zai/glm-5.3-flash (позже zai-coding-plan, напрямую, без yabanin). Полная
механика/статусы: `raider-night` registry (temp) + records W7-INTEGRATION,
W8-W9-INTEGRATION (addendum W10–W11). Интегрировано и прогнано полными root
гейтами (EXECUTE 7/7, manifests 20261010-060836…224104):

- LIN-01A/B/C + GraphHash-фиксы (W9-F1, F-L28-1/2; BREAKING канонизация
  задокументирована), LIN-02A/B/C/D + renderer dotc-фикс, view-adapter
  (cli/lineage), LIN-02 LIN2-01 compile-gate (golden→pinned dotc ЗЕЛЁНЫЙ),
  LIN-03A live builder + LIN-03B HeadlessRunner summary lineage-хэш,
  LIN-04A `:aux` + LIN-04B LineageTab provider, LIN-05A corpus + LIN-05B
  comparator/verifier + LIN5-03 partial replay (reducePartial),
  LIN-05D RecordedSource (W13, review approve 2026-10-12), LIN-08A docs/lineage.md.
- RAI-072 (events/tabs/cancel docs), RAI-079 (anthropic stand tool-round),
  RAI-080 (onboarding), RAI-096 (plugins SDK + 3 fixtures; examples теперь
  в compile.sh), RAI-097 (StatusText единый: cli-chat + tab bar).
- Ревью-циклы: W8-review (6/7→фиксы закрыты), W9-review (6/7→закрыто),
  W10-review (3 Major→W11 закрыты), W12-review (5/6→R1/R2 закрыты
  coordinator'ом, self-fixes на ревью W13), W13-review: approve (2026-10-12,
  первый spawn-режим: независимый raider-reviewer по wt/w13-review;
  RecordedSourceSpec 11/11 воспроизведены, evidence-binding сверен).

Open (после ночи): LIN-05 full интерпретатор (replay-recorded LLM из записи;
RecordedSource — преп), LIN-06/07 (live re-run, частичный replay фаз),
TabShell→REPL wiring (RAI-062-смежное), multi-job export (owner-решение),
M1-evidence card RAI-099 (coordinator), phase/recovery schema-card (wave-3,
owner), live-wire verification (владелец: endpoint/бюджет).

## Spawn campaign 2026-10-12 (visible agents, §1b; coordinator)

Волна 1 (spawn-режим, 4 lanes): **W13-review LIN-05D approve** (независимый
reviewer, RecordedSourceSpec 11/11 воспроизведены); **LIN-09** lineage-артефакты
ряда рана (RunLineageArtifacts + 6 тестов; reviewer approve; contract-change →
LIN-11); **LIN-10** standalone replay CLI (ReplayCli; reviewer approve;
integrated-gate поймал forbidden System.exit → integration-fix 60bccc5:
sanctioned ZIOAppDefault entry `ReplayCliMain`, process-смок exit 21
воспроизведён); **RAI-102** TabShell в продуктовом REPL (contract-change
исполнен: build.sbt edge raiderRepl→raiderUi; TabShellHost + настоящие PTY
P2W-01..03; reviewer approve); **LIN-11** LineageRenderer `()` closer для
def-free графов (reviewer approve). Интеграция: main 60bccc5, merges
978194a/3f050e0/af4ff7f/56f244e. Integrated gates на кандидате 150700f:
quality-full passed 10/10 (manifest 20261012-050132-69438-0001) + профили
static/unit/contracts/ci/docs/runtime/repl/security passed 9/9 (manifest
20261012-050347-70594-0001). Открытое (owner): live-wire, multi-job export,
phase/recovery schema-card; follow-up: wiring TabShellHost в Main.ttyLoop,
wiring ReplayCli в продукт-команду `raider`. Волна 2: RAI-103..106
(P2-остатки: drafts/scrollback/unread/palette-help на продуктовой поверхности).

Волна 2 (spawn-режим, 4 lanes; интеграция main f893d48): **RAI-103** drafts
(P2D-01..03, reviewer approve; продуктовых правок нет — фича-специфичные
PTY-спеки), **RAI-104** scrollback bounds (P2S-01..03, approve; продуктовый
TabShellScrollback render), **RAI-105** unread badges (P2U-01..03; blocker
lane → coordinator determinism-fix 71d0aa3: quiescence drain перед :quit в
P2W-01 драйвере, badge-маркер недетерминирован), **RAI-106** palette/help
(P2P-01..03, approve; деривационный каталог из frozen CommandRouter,
TabShellHost хук paletteTerminal). Records: docs/work/records/RAI-103..106.md.
Integrated gates на f893d48: quality-full 10/10 (manifest
20261012-071006-49099-0001) + профили static/unit/contracts/ci/docs/runtime/
repl/security 9/9 (manifest 20261012-071243-51219-0001). **RAI-107 Plan mode
(ADR-028) — обязательная карточка владельца (2026-10-12): accepted** — lane
rai-107 с handoff partial (мёртвый rate-limit spawn верифицирован вторым
воркером и сохранён); ADR-028 Accepted (owner mandate); PLAN-01..03 зелёные
(scripted, 11 тестов); review: код approve, 1 MAJOR evidence-finding
(record после гейтов сломал собственный fingerprint) закрыт перегоном на
main; lane-манифесты помечены stale в record (docs/work/records/RAI-107.md).
Determinism-фиксы на интеграции (детерминизм, не ослабление ассертов):
71d0aa3 quiescence drain P2W-01; 91a6575 :tabs-render retry P2U-03.
Финальные integrated gates (main 91a6575): quality-full 10/10 (manifest
20261012-105946-2326-0001) + профили ci/contracts/docs/repl/runtime/security/
static/unit 9/9 (manifest 20261012-110432-24403-0001); validate.py passed
(121 cards). Follow-ups: RAI-068 chat-команды в палитре; approval-канал/UI
plan-режима; TabShellHost в Main.ttyLoop; ReplayCli в продукт-команду
`raider`; P2S-рендер consumer-шаг; eval-профиль plan-режима (milestone).

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
| RAI-059 Jobs/budget/delegation emitters (P1.B-2) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); EMJ-01..03 18 emission-тестов; Terminal authority for UI |
| REPL-LIVE-BRIDGE | done | live prelude built in interpreter world (MainBridge classloader split deleted); live Qwen round-trip; 21/21 repl, PTY 13/13, EXECUTE 7/7; [record](records/REPL-LIVE-BRIDGE.md) |
| RAI-063 OOB cancel/deadlines (P3.B) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); CANCEL-01..03 ×20 ≤5s; 119/119 lane |
| RAI-061 Tab shell (P2.B) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); SHELL-01..03; 16/16 ui |
| RAI-060 Status line (P1.C) | accepted | [W3 record](records/W3-PARALLEL-10.md); integrated, gates green |
| RAI-062 PTY integration (P2.C) | accepted | [RAI-062 record](records/RAI-062.md); PTY-01..03 + M1-DEMO all_pass |
| RAI-003/023/027 etc. | todo | prerequisites not met |
| M1 | accepted-pending-owner-note | [M1-ACCEPTANCE.md](records/M1-ACCEPTANCE.md) (evidence compiled) |
| Remaining M1/M2/P2 | todo | per working-product.md acceptance |

## Open Obligations

| Obligation | Card/condition |
| --- | --- |
| Live-wire verification (real providers) | owner-configured endpoint/budget; without it pending |
| Edit tool refinements (patch format, multi-file) | RAI-015 remainder |
| Tool definitions on wire (input_schema per tool) | structured message contract |
| Child deadlines / OOB-cancel | §11 policy card |
| send_agent (steering) | RA-CAP currently; RAI-018 |
| Chat memory in CLI (multi-turn persistence) | RAI-018 remainder |
| REPL: :type, completion | RAI-019/020 |
| Journal/artifacts/recovery (§13) | RAI-027 |
| Packaged distribution, CI adapters | RAI-022 remainder-2, RAI-023..026 |
| Virtual backoff fixture | with retry card (no retries by design) |
| Fair admission across DIFFERENT roots | RAI-010/017 integration |
| Performance profile in registry | RAI-004 stage-aware |
| Owner sign-off F-14 + runtime profile | strict registry upgrade |
| Event emission from AgentLoop/JobManager into hub | P1.B/P2.C cards over RAI-055 (cancellation/ownership/budget tests) |
| Tabs UI on ADR-024 events | RAI-057 → P2.B/C |
| --api-key credentials-in-argv (CliArgs:67) | new setup card (env/helper refs only) |
| Probe COURSIER_CACHE fallback → env.setdefault | one-line follow-up with next scripts/quality change |
| Plugin SPI ADR/design | P4.A (после P0 baseline — baseline готов этой wave) |

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
| 2026-10-09 (W6-PARALLEL) | 9 карточек: 083 plugin wiring, 084 HookRunner, 085 StateTools, 086 CI/accounting wiring, 087 JobJournal, 088 :plugins/:state, 090/098 REPL env-key+anthropic live, 091 forbidden-apis allowlist (APPROVE), 092 delegation stabilization ×10, 093 journal wiring, 094 state tools→tools/ui, 095 hooks→loop, 096 P4.C SDK, 097 единый status-render, 099 M1-ACCEPTANCE compiled, 100 CI wrappers, 101 update/rollback. ADR-025 FROZEN. Full sbt 569/0. Record: W6-PARALLEL.md |
| 2026-10-09 (W5-PARALLEL) | 9 карточек: 083 plugin wiring, 084 HookRunner, 085 StateTools, 086 CI/accounting wiring, 087 JobJournal, 088 :plugins/:state экраны, 089 UX baseline ЗАМЕРЕН, 090 REPL env-key, 091 forbidden-apis allowlist (policy APPROVE), 092 delegation test stabilization. Full sbt 545/0. Record: W5-PARALLEL.md |
| 2026-10-09 (W4-PARALLEL) | 9 карточек: RAI-074 mailbox wiring (send_to_child, 171/171 runtime), 075 PluginLoader+PluginCli (P4.B ядро), 076 StateStore SPI (P5.A), 077 Session v2+миграции, 078 CiContext (RAI-023 slice), 079 anthropic stand tool-round, 080 onboarding guide, 081 eval fixtures ×6 (protected), 082 accounting view. Full sbt 476/0; fast PASSED (10 gates). Record: W4-PARALLEL.md |
| 2026-10-09 (W3-PARALLEL-10) | 10 карточек за волну (owner decision 3→10 threads): RAI-062 PTY-вкладки (M1-DEMO all_pass), 065 mailbox, 066 ADR-025+SPI skeleton, 067 multi-file patch, 068 REPL completions, 069 security profile (gate-policy APPROVE), 070 UX baseline harness, 071 anthropic CLI, 072 docs, 073 Delegated-race. Full sbt 406/0. Record: W3-PARALLEL-10.md |
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
