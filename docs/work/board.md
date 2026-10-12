# Raider Execution Board

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

Приоритет уточнён: **удобный Scala DSL для AI-native SDLC**, один workflow локально
и в generic **локальном CI runner**. Первый consumer — разработка кода
`../tokenrouter` (Yabanin); gateway integration остаётся P11. Target discovery
read-only, исходно dirty дерево не менялось. Источники, baseline и hashes:
[исследование](../research/sdk-roadmap-research-2026-10-12.md).

Подтверждены full SDLC, automatic commit/PR/merge, Scala-configured human
checkpoints; release = version/changelog/**local tag**/local artifacts без push
тега, package/image publication или deploy. CI engine/forge не выбраны, сначала
generic contract и local Scala stand; GitHub Actions не является первым adapter.
Preview допускается до M4: текущий pinned Scala/ZIO, experimental API с migration,
no durable resume; trusted local data. Полная milestone acceptance сохраняется.

**RAI-115 accepted (source audit)**: lane `lane/rai-115-sdk-audit`, final `c156f40`,
независимый reviewer approve; [audit](../research/sdk-embedding-audit.md).
Lane docs validator / changed / execute / docs profile — exit 0;
manifest `wt/rai-115-sdk-audit/artifacts/quality/20261012-193634-72162-0001/manifest.json`.
Это не acceptance SDK implementation. Findings: String bundle boundary,
launch/lifecycle/admission parity, ignored UsageObserved/zero settlement на текущем
AgentLoop success path. Accounting claim требует отдельного reproducer/fix gate.

**RAI-116 ready** для отдельного workflow/launch ADR; **RAI-117 todo**, зависит
от него, описывает SDLC effects/checkpoints/forge/release и пересмотр прежних
non-goals. Implementation cards назначаются после contracts и оценки остатка;
текущая волна LIN-13/14/15 + RAI-113/114 продолжается. Budget RAI-115 120–240 h —
gross estimate до дедупликации, SDLC kit/remote adapter/остаток unknown; не новый
утверждённый бюджет и не фактически потраченные часы.

Planning scope: [ROADMAP-SDK-RESEARCH](../tasks/roadmap-sdk-research.md).
Result/review/evidence: [research record](records/ROADMAP-SDK-RESEARCH.md),
[RAI-115 record](records/RAI-115.md).
Workflow preview WF-01/02 независим от host embedding/plugin UI/M2/M4;
SDLC-01–05 и SDK-01–05 остаются явными milestone obligations.

## Previous planning revision 2026-10-12: embedding SDK

Исторический snapshot до дополнительного исследования выше; текущий статус
RAI-115 — accepted, следующая готовая SDK-карточка — RAI-116.

По запросу владельца SDK встроен в существующие P0/P4.C/P7/P10; критерии
SDK-01–05 и пилоты P0/P7/P9 описаны в [roadmap](../roadmap-12k.md).
Scope текущей плановой работы: [ROADMAP-SDK](../tasks/roadmap-sdk-integration.md).
Результат/review/evidence: [work record](records/ROADMAP-SDK.md).
Raider остаётся standalone coding product; Scala/ZIO embedding — дополнительный
пользовательский путь, без новой верхнеуровневой фазы. Подробная пользовательская
документация отложена. Бюджет 13 100 h — текущий envelope, оценку остатка и
перераспределение SDK нужно записать до implementation; новые функции не приняты.

**На момент этой ревизии ready: RAI-115** — P0.A audit публичной SDK-границы и packet для отдельного ADR.
Allowed output: `docs/research/sdk-embedding-audit.md`; gates: docs. Может идти
параллельно LIN-13/14/15 и RAI-113/114 без shared code/build edits. Новые
implementation cards регистрируются после audit/ADR; существующие карточки
не переоткрываются и не получают чужой scope. Coordinator владеет planning
файлами; текущую ревизию независимо проверяет видимый read-only spawn-reviewer.

Владелец подтвердил: Raider + SDK, первый сценарий — CI, без остановки текущей
волны; проект полностью open source (существующая MIT/license policy).
Пилотные пользователи, сроки и спрос пока не заданы; их уточнение не блокирует
независимый audit. Платная редакция/закрытые SDK-функции в scope не входят.

## Previous execution status

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
**Волна 3 (решения владельца 2026-10-12):** состав = wiring-тройка
(RAI-108/109/110) + RAI-111 (палитра) + LIN-12 (catalog persistence) +
RAI-112 (M1-evidence refresh); approval UX = TUI-экран плана; 4 lanes;
cleanup worktrees отложен. Зарегистрировано RAI-108..112+LIN-12 (validate
passed 127 cards, b75c2ec). ИНЦИДЕНТ: 4 spawn-спавна батча 1 упали по
rate-limit провайдера ПОСЛЕ начала работы — во всех 4 worktrees остался
незакоммиченный partial. Fallback §1b(1): воркеры исполняются последовательно
в primary-сессии coordinator'а (причина зафиксирована здесь), review —
spawn-агентами при восстановлении провайдера; самоприёмка запрещена, мерджи
только после независимого review.
Финальные integrated gates (main 91a6575): quality-full 10/10 (manifest
20261012-105946-2326-0001) + профили ci/contracts/docs/repl/runtime/security/
static/unit 9/9 (manifest 20261012-110432-24403-0001); validate.py passed
(121 cards). Follow-ups: RAI-068 chat-команды в палитре; approval-канал/UI
plan-режима; TabShellHost в Main.ttyLoop; ReplayCli в продукт-команду
`raider`; P2S-рендер consumer-шаг; eval-профиль plan-режима (milestone).

**Волна 4 (spawn-режим §1b, 7 lanes + интеграция; base 4b46dc7 → main
10ea71c):** зарегистрированы LIN-13/14/15 + RAI-113/114 (4b46dc7, validate
132 cards). Lanes и вердикты: **W22-R8-review** (хвост W20–W21: F-W22R1-1
единственный finding, закрыт 5ff5318) → **W22-R8** sweep (R1 MAJOR:
`Gen.int(1,50)` — случайная выборка вместо sweep; fixes 1cc02b3:
`Gen.fromIterable(1 to 50)` + 7 раздельных ассертов; re-review approve,
детерминизм 50 seed-файлов ×3 идентичны); **LIN-12** CatalogStore persistence
(append-очередь/per-path lock/upsert run-dir; LINC-01..03; approve; minors:
no cross-process, path-нормализация ключа); **LIN-13** lineage §9.2 sync
(17/17 evidence-ссылок; approve; §10-сопутствующие правки помечены);
**LIN-14** LIN2-03 race-spec (4 readers vs writer, 0 частичных ×3; approve;
вскрыл: PlanPanelSpec flake 1/4, baseline scalafix ParseError
PlanTabsPtyApp:173 из e1a8c9f); **LIN-15** LIN-06 scripted acceptance
LIN6-01/02/03 (typed-фиксы IsolatedRerun/ReplayHarness с reproducer'ами;
approve; minor: alias-дыра guard'а — follow-up RAI-117); **RAI-113**
:scrollback consumer (реестр+PTY P2SR-01..03; approve; minor: precedence
vs :switch — follow-up RAI-118); **RAI-114** plan-mode eval corpus 6
сценариев (PEVAL-01..03 ×3; approve). Конфликт PlanTabsPtyApp разрешён в
пользу superset-фикса RAI-114. Интеграция: merges c98fb77…29d770b + records
(W22-R8.md собран; LIN-12/RAI-113 перенесены из worktrees).
Integrated gates на кандидате 10ea71c: quality-full (полный sbt test +
repl/cli смоки) — первая попытка sbt-test честно упала на известном
PTY-флейке TabShellUnreadPtySpec под нагрузкой 8 lane-JVM (изолированный
прогон 3/3 за 40s, флейк не регрессия; честный failed-манифест
20261012-200222-93693-0001), повтор зелёный; quality-changed EXECUTE 10/10
(manifest 20261012-200841-98234-0001); профили static/unit/contracts/ci/
docs/runtime/repl/security 9/9 (manifest 20261012-201326-4234-0001).
Follow-ups зарегистрированы: RAI-116 (PTY-детерминизм под нагрузкой),
RAI-117 (canonical-path guard), RAI-118 (:scrollback/:switch precedence).
Одновременно на main независимо лег SDK-research трек (3a60f93…318fcf4,
RAI-115) — сохранил, конфликтов с волной 4 нет.

**Волна 4b (spawn-режим §1b, base 9a43224 → main 6790c6f):** RAI-116..118
перенумерованы при регистрации в RAI-119..121 (коллизия с SDK-треком,
взявшим RAI-116/117). Lanes и вердикты: **RAI-119** PTY-детерминизм (3
тест-файла; драйвер-infra att1 reproducer: evidence-dir до старта child;
PlanPanel drain+refresh-retry; TabShellUnreadPty waitQuiet-drain + fresh-render
retry run9; PTYD-01/02 10/10 под load 156–164, PTYD-03 raiderRepl ×3 +
full sbt test; review approve; честно: PlanPanel wave-4 класс не воспроизведён
за 18 попыток — фикс превентивный, рецидив → условная RAI-123);
**RAI-120** IsolatedRerun canonical-path guard (isSameFile до байт-проверки,
fail-closed на missing/IO; ISO-01..03; review approve; minors: типизация
guard-отказов огрублена — учесть в LIN-15 wiring-карточке);
**RAI-121** :scrollback digit-first precedence (сверен построчно с frozen
:switch, ассерты не правились — UUID never all-digit; SCB-01..03; review
approve; в lane верифицирован partial мёртвого rate-limit spawn — второй
случай инцидента, паттерн воспроизводим); **RAI-112** M1-evidence refresh —
M1R-01 BLOCKED: frozen tabs_pty_probe ждёт legacy `[Running]`, продукт
рендерит ADR-024 honest-статусы → **RAI-122** (защищённый верификатор:
ROW_RE = грамматика StatusText, ok_live → «[running | cost unknown]», :aux в
HINT_BAR, pump-guard перед :quit; review approve по обоим scope-пунктам,
негативный контроль PROBE-02 держит); M1R-01 повторён на кандидате ×3
all_pass → **RAI-112 done** (addendum в record).
Интеграция: merges 81dad02/8829796/8809e16/05bbe60/7fd2776; evidence RAI-119
(artifacts/rai-119) и RAI-122 (artifacts/rai-122-evidence) перенесены в root.
Integrated gates на кандидате 7fd2776: EXECUTE 10/10 (manifest
20261012-233255-59159-0001, полный sbt test без флейков), профили
static/unit/contracts/ci/docs/runtime/repl/security 9/9 (manifest
20261012-233800-62893-0001); validate 140 cards. Открытое: RAI-123
(условная, по рецидиву PlanPanel-гонки), LIN-06 live-wire/multi-job/
schema-card (owner), P2S-рендер — закрыт RAI-113; eval-профиль plan-режима —
corpus готов (RAI-114), регистрация профиля в working-product stage.

**Волна 4c (spawn-режим §1b, base 7702fd0 → main acca49f) + аудит
обязательств:** зарегистрированы RAI-124/126 (7702fd0, validate 142).
Lanes: **RAI-124** REPL `:type` (нативный dotty TypeOf через frozen
engine.eval — БЕЗ исполнения: side-effect-маркер отсутствует, resN не
сдвигается; helpText/catalog freshness; TYPE-01..03, PTY ×3 flake=0; review
approve; minors: PTY-оракулы echo-saturation → RAI-130, UX-префикс →
RAI-130); **RAI-126** PTY-probes COURSIER_CACHE setdefault (3 точки —
вкл. sbt_run :159 за пределами якоря карточки, отклонение честно
документировано и одобрено review; CC-01..03 c численной проверкой
использования кэша вызывающего 0→180 файлов; защищённые верификаторы, review
approve). Независимый аудит Open Obligations (read-only reviewer, main
7702fd0): 7 из 18 строк CLOSED/STALE (эвиденция file:line), ключевой gap —
Anthropic-провод не передаёт tool-definitions (W05); таблица заменена;
зарегистрированы RAI-127/128/129 (из аудита) + RAI-130 (из review), validate
146 cards. Интеграция: merges 6f4e9e3/acca49f. Integrated gates на acca49f:
EXECUTE 10/10 (manifest 20261013-023006-78942-0001), профили 9/9 (manifest
20261013-023534-82374-0001). Гигиена: RAI-015 (m0.md) перескоупить
отдельной карточкой. Волна 4d: RAI-127/128/129/130.

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
| LIN-12 Catalog persistence | accepted | [record](records/LIN-12.md); LINC-01..03; review approve (minors: cross-process/path-norm) |
| LIN-13 lineage §9.2 sync | accepted | [record](records/LIN-13.md); DOC-13-01..03; review approve |
| LIN-14 LIN2-03 race coverage | accepted | [record](records/LIN-14.md); 0 частичных чтений ×3; review approve |
| LIN-15 LIN-06 scripted acceptance | accepted | [record](records/LIN-15.md); LIN6-01/02/03(sc); review approve; alias-guard → RAI-117 |
| RAI-113 :scrollback consumer | accepted | [record](records/RAI-113.md); P2SR-01..03 PTY; review approve; precedence → RAI-118 |
| RAI-114 plan-mode eval corpus | accepted | [record](records/RAI-114.md); PEVAL-01..03 ×3; review approve |
| W22-R8 replay sweep | accepted | [record](records/W22-R8.md); 50-seed deterministic ×3; review R1-R3 fixes, re-review approve |
| RAI-112 M1 evidence refresh | accepted | [record + addendum](records/RAI-112.md); M1R-01..03 met на 7fd2776 (probe ×3 all_pass после RAI-122) |
| RAI-119 PTY-детерминизм | accepted | [record](records/RAI-119.md); PTYD-01/02 10/10 под нагрузкой; review approve; рецидив → RAI-123 |
| RAI-120 IsolatedRerun guard | accepted | [record](records/RAI-120.md); ISO-01..03 fail-closed; review approve |
| RAI-121 :scrollback precedence | accepted | [record](records/RAI-121.md); SCB-01..03, digit-first = :switch; review approve |
| RAI-122 tabs_pty_probe sync | accepted | [record](records/RAI-122.md); PROBE-01..03; защищённый верификатор, review approve |
| RAI-124 REPL :type | accepted | [record](records/RAI-124.md); TYPE-01..03, без исполнения; review approve; follow-ups → RAI-130 |
| RAI-126 probes COURSIER_CACHE | accepted | [record](records/RAI-126.md); CC-01..03; защищённые верификаторы, review approve |
| RAI-003/023/027 etc. | todo | prerequisites not met |
| M1 | accepted-pending-owner-note | [M1-ACCEPTANCE.md](records/M1-ACCEPTANCE.md) (evidence compiled) |
| Remaining M1/M2/P2 | todo | per working-product.md acceptance |

## Open Obligations

Verified 2026-10-13 (independent audit по main acca49f; file:line-факты в
записи аудита волны 4c). 7 строк закрыты/stale, 3 owner-gated, открыты:

| Obligation | Card/condition | Status |
| --- | --- | --- |
| Live-wire verification (real providers) | OWNER (endpoint/budget) | OWNER-GATED (lineage.md §9.2; ad-hoc live-раунды были, формальной scoped-верификации нет) |
| Tool definitions on wire — Anthropic | RAI-127 (ready) | OPEN — AnthropicMessagesBackend не передаёт tools/input_schema (OpenAI-провод передаёт); W05 gap |
| Journal/artifacts/recovery (§13) | RAI-129 (ready, узкий срез) + owner schema-card | OPEN — journal+replay есть; recovery/resume-команд в продукте нет; retention/redaction и wire — отдельно |
| CI full adapters | RAI-023(full)/024/026 | OPEN — RAI-078/100 accepted; остались trust-preflight/provenance, trusted checks/exit contracts, MVP-приёмка |
| Fair admission across DIFFERENT roots | RAI-128 (ready) | OPEN — fair FIFO by construction, named ≥2-root теста нет |
| Performance/eval profile in registry | RAI-004 remainder; working-product stage | OPEN — registry 12 профилей; eval-corpus готов (RAI-114) |
| send_agent (steering) — REPL facade | RAI-018 remainder / RAI-021 | OPEN (узко) — runtime steering готов (RAI-065/074), FacadeOps.send не подключён |
| Owner sign-off F-14 | OWNER | OWNER-GATED (RAI-007.md:59-61); runtime-профиль в registry уже есть |
| Multi-job export / LIN-06 live re-run / phase-recovery schema-card | OWNER + LIN-06 | OWNER-GATED |
| :type PTY-оракулы дезатурация | RAI-130 (ready) | OPEN — echo-saturation findings RAI-124 |
| ~~Edit tool refinements~~ | CLOSED | fs_patch+UnifiedDiff+PatchTool; RAI-067 multi-file accepted; RAI-015 в m0.md перескоупить (hygiene) |
| ~~Child deadlines / OOB-cancel~~ | CLOSED | RAI-063 accepted |
| ~~Chat memory in CLI~~ | CLOSED | SessionStore, --resume, :compact |
| ~~REPL: :type, completion~~ | CLOSED | completion RAI-068; :type RAI-124 (merged acca49f) |
| ~~Virtual backoff fixture~~ | STALE | RateLimitRetries + 2 спеки существуют |
| ~~Event emission into hub~~ | CLOSED | RAI-058/059 accepted |
| ~~Tabs UI on ADR-024~~ | CLOSED | RAI-057/061/062/102/108 + RAI-103..106/113 |
| ~~--api-key in argv~~ | STALE | RAI-064: удалён, typed error |
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
