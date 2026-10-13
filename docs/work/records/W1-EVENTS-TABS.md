# W1-EVENTS-TABS: Work Record

Status: done. Date: 2026-10-07/08 UTC. Baseline: integrated tree
[P0-WAVE0](P0-WAVE0.md). Execution: coordinator + 3 parallel writers in
isolated snapshot lanes (lane-a3/b3/c3, base manifests of 249 files),
independent review of both phases (initial + fix rounds). Transport
infrastructure had 4 failures during the session (500 “Context size exceeded” /
abort); all were recovered with “assess+continue” restart rounds without lost
work.

## Resulting behavior

1. **RAI-057 (P2.A, accepted)** — `raider.ui.SessionRegistry`: tab data on the
   real EventHub (no mocks): create/focus/focusNext/focusPrev (switching takes
   one call), draft ops, tabs() view (label/jobId/status/unread/lastSeq), close
   semantics (running → RunningChoice(Hide|CancelRequested), root → list of
   affected child tabs by rootId); bounded history 200 / scrollback 500 with
   honest eviction counters; dedup + seq sort before folding; focus is UI state
   only (test invariant). Found and fixed a partial implementation bug
   (runFold vs runFoldZIO — events were not applied).
2. **RAI-058 (P1.B-1, accepted)** — emission from AgentLoop/RateLimitRetries to
   EventHub: Waiting(Slot) before waiting for a slot (RELIABLE), Model/Tool
   events with the actual round (hardcoded -1 removed, including trace),
   ToolFinished on failure/timeout/interrupt paths (Cancelled via
   onInterrupt-finalizer — the partial catchAllCause variant was incorrect:
   ZIO interruption is not caught), RetryScheduled(attempt, delayMs, cause),
   honest unknown usage (never an invented cost). Option[...]=None has zero
   overhead; all 40+ call sites remain compatible. 109/109 runtime tests
   (99+10 EMIT); spec stable ×3.
3. **RAI-064 (M0, accepted)** — credentials only through env/helper: `--api-key`
   removed from argv (typed RA-CFG with guidance; value is never echoed);
   `RAIDER_API_KEY` or `RAIDER_API_KEY_CMD` (first stdout line; stderr drained
   by daemon thread; bounded 10s → typed timeout, process destroyed); preflight
   BEFORE any spend; first4+*** mask; mock works without a key. Redaction
   fixture (sentinel absent from all artifacts). Live probes T1..T6/R1..R3:
   refusal-before-spend confirmed (events.jsonl contains only run.started/finished).

Public contracts: unchanged (new parameters are trailing with defaults).
TraceEvent: types/codecs unchanged; round values changed from -1 to real values
(authorized by the card; transcript consumers checked in review).

## Changed paths

- `modules/ui/src/main/scala/raider/ui/SessionRegistry.scala` (new), `modules/ui/src/test/scala/raider/ui/SessionRegistrySpec.scala` (new), `modules/ui/src/main/scala/raider/ui/package.scala` (placeholder removed)
- `modules/runtime/src/main/scala/raider/runtime/loop/{LoopEvents.scala (new), AgentLoop.scala}`, `modules/runtime/src/main/scala/raider/runtime/display/RateLimitRetries.scala`, `modules/runtime/src/test/scala/raider/runtime/{loop/AgentLoopEmissionSpec,display/RateLimitRetriesEmissionSpec}.scala` (new)
- `modules/cli/src/main/scala/raider/cli/run/{Credentials.scala (new), CliArgs.scala, HeadlessRunner.scala}`, `Main.scala`, `chat/ChatLoop.scala`, tests: `run/{CredentialsSpec.scala (new), HeadlessRunnerSpec.scala}`
- `docs/tasks/{backlog.json,m0.md,m1.md,m2-p2.md,README.md}`, `docs/work/board.md`, this record

## Acceptance evidence

| ID | Result | Evidence |
| --- | --- | --- |
| TAB-01..03 | 5/5 ui tests (×2 stability), focus invariant, exact eviction conservation | lane-a3-evidence/WORK-RAI-057-lane-a3.md |
| EMIT-01..03 | 109/109 runtime; EMIT ×3 without flakiness; maxLLM=1 in every fixture | lane-b3-evidence/{final-test.log,emit-run1..3.log} |
| SETUP-01..03 | 44/44 cli; sentinel-redaction; refusal before spend (T3: events only started/finished, 30ms) | lane-c3-evidence/{logs,argv-verify,reviewfix-verify} |
| Review fixes | D1-1 (unread on create), D3-1 (helper stderr drain + bounded timeout), D3-4 relabel | lane-a3/c3-evidence + review verdict |
| Integrated gates | EXECUTE passed 7/7; profiles static/unit/contracts/runtime/ci passed (incl. cli-smoke) | artifacts/quality/20261008-001211-39194-0001, artifacts/quality/20261008-001358-40602-0001 |
| Root demo | mock run exit 0; live without key → exit 21 RA-CFG before spend; --api-key → typed error without echoing the secret | stdout/artifacts from this session |

## Commands

| argv | Exit | Manifest/log |
| --- | --- | --- |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261008-001211-39194-0001/manifest.json |
| `make quality-profile PROFILES="static unit contracts runtime ci"` | 0 | artifacts/quality/20261008-001358-40602-0001/manifest.json |
| `java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent scout --provider mock --input demo --out artifacts/demo-w1` | 0 (Succeeded) | artifacts/demo-w1/work-*/run.json |
| same with `--provider openai --base-url http://127.0.0.1:9/v1` without a key | Failed/21 (RA-CFG preflight) | /tmp/t-rai64/work-*/run.json |
| same with `--api-key sk-secret123` | RA-CFG: --api-key removed… (value not echoed) | stdout |
| `python3 scripts/plan/validate.py` | 0 | artifacts/plan-review/ |

## Limitations / blockers

- Terminal/JobStatusChanged/budget/delegation events — **RAI-059** (ready, not
  started); do not run the UI on live runs without it (Terminal is authoritative
  for the job layer; review caution).
- The `security` profile is absent from the registry (component_profiles) —
  RAI-064 remains open at milestone level; changing gate policy requires a
  separate card and independent review (not done here).
- Reorder gap-stall and forwarder supervision (hub) are documented limitations
  for a future card.
- ToolProgress/LineTail and DiagnosticsCoalesced are not emitted (no producing
  surface in MVP); the seams are recorded.
- No unit test for the 10s helper timeout (behavior demonstrated by probe R2);
  whether to add one is up to the gate card.
- Transport infrastructure: 4 session failures; reflected in the acceptance
  reports.

## Review and next task

Independent review: all three ACCEPTED (D1-1/D3-1/D3-4 resolved in lanes;
D2/D3-2/D3-3 are informational/obligations). Next in DAG: **RAI-059** (ready)
→ **RAI-060** (status line, after 058+059 — 058 accepted) and **RAI-061** (tab
shell, after 057 — accepted; coordinator will add JLine dependency for raiderUi).
After 059+060, RAI-062 opens → **M1 demo**.
