# Raider Execution Board

Last passing run (2026-10-04, SELFDEV): quality fast 6/6 full-tree
(artifacts/quality/20261004-181210), full sbt test 188 green,
coder agent with REAL fs_read/fs_search/proc_run + live openai provider;
selfdev prompts: docs/selfdev-prompts.md, launcher scripts/selfdev/raider.sh
— record: [SELFDEV](records/SELFDEV.md).

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
| RAI-003/023/027 etc. | todo | prerequisites not met |
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
