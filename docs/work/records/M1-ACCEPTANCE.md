# RAI-099: work record — M1 acceptance (milestone evidence)

Status: done (evidence compiled; owner sign-off requested). Date: 2026-10-09 UTC.
Baseline: integrated W5 tree (latest wave, full suite 545/0). Mode:
`Git unavailable: isolated snapshot` (lane-37). The lane has no `artifacts/` —
all figures below were compiled from work records; each evidence-table row
identifies its source record. Fresh source fingerprints for the evidence were
captured in this lane (M1A-01):

| Source | sha256 |
| --- | --- |
| `docs/work/records/W1-EVENTS-TABS.md` | `f0c7580da15def5a88e919e77e1053280196b2d68366cfc67fa50d48ba07e29f` |
| `docs/work/records/W2-EMITTERS-CANCEL-SHELL.md` | `04fb0f224a3948e0ded8eb104b7264cdca8a73fd123e9013939ff2c0137e8bc8` |
| `docs/work/records/W3-PARALLEL-10.md` | `ced510dd9605588c26ef6a4ecffcb65d430f66ebb65eaefae41f8fca55431573` |
| `docs/work/records/W4-PARALLEL.md` | `16ce8b185a56bf385a91121d9982384ee723b4e79e63694a5d8d0f238158c0e5` |
| `docs/work/records/W5-PARALLEL.md` | `3ecf367607c1869c71347b71ec50d758951c12b5ebcf3e2f52191de546dac053` |
| `docs/work/board.md` (before RAI-099 edit) | `c79d8e01304a2f13c002be18c56e78dee2863fc1761fd245c71be20236ada23a` |
| `docs/roadmap-12k.md` | `b8755b8f2cdc7b43e11d037c7f3255129a7dd367f35ad1c12c76ad0eb20ea72a` |
| `scripts/quality-policy/registry.json` | `d3085718e6e322afbd030ce280f16936d06d6c6066b5cd2314c3cb7cf0cf98ba` |

## 1. What M1 includes

P2 exit criterion / M1 definition (roadmap-12k §P2, “Exit M1”): the user
starts a task, sees 5 independently working agents, switches away and returns
to an unchanged draft; closing a tab ≠ canceling work (a running tab offers
hide or cancel); closing the root handles its children; UI focus does not
change owner/budget/policy; plain chat/REPL/run remain available.

By component:

- **Tabs**: SessionRegistry on the real EventHub (P2.A), TabShell on JLine with
  `:tabs/:new/:switch/:next/:prev/:rename/:close` (P2.B), PTY integration
  (P2.C) — RAI-057/061/062 (records W1, W2, W3).
- **Statuses**: the job layer is the event source; StatusLedger → JobStatusChanged +
  exactly one Terminal per job; StatusLine in chat; EventExport for headless —
  RAI-058/059 (W1, W2) and RAI-060 (W3-PLAN).
- **Cancel**: `cancelOutOfBand`/`cancelAllOutOfBand` outside model admission,
  child deadlines with bounded grace, observer-interrupt ≠ owner-cancel (P3.B,
  RAI-063, W2); Delegated-race fix + root-pool invariant (RAI-073, W3).
- **Input integrity**: draft is byte-exact (raw line preserved), focus is only
  UI state (test invariant), one-writer printing, bounded history/scrollback
  with truthful eviction counters (RAI-057/061, W1/W2).

## 2. Evidence table

Statuses “passed (recorded)” mean the check was run and recorded in the stated
record on the integrated tree for the corresponding wave; this lane did not
rerun sbt/PTY (docs-only snapshot lane; see obligations in §3).

| # | Claim | Evidence source | Status |
| --- | --- | --- | --- |
| E1 | M1-DEMO PTY: 5 tabs, 1105 events, 0 losses, byte-exact drafts, `all_pass=true` (`artifacts/tabs-pty/m1-demo-summary.json`); PTY-01..03 PASS | `W3-PARALLEL-10.md` (RAI-062); reconfirmed in `W5-PARALLEL.md` (“W3 artifacts, tree did not regress”, full suite 545/0) | passed (recorded) |
| E2 | StatusLine (`raider.cli.chat.StatusLine`) / EventExport (`raider.cli.run.EventExport`): one-line status from the real hub + consumer-side helpers by `seq` | RAI-060 accepted in W3-PLAN session — Session History entry in `board.md` (“accepted (integrated, gates green)”); no separate record file; documented in `docs/api.md`; sources are present in the snapshot: `modules/cli/src/main/scala/raider/cli/chat/StatusLine.scala`, `modules/cli/src/main/scala/raider/cli/run/EventExport.scala` | accepted (board entry; no standalone record) |
| E3 | cancelOutOfBand/deadlines: parked child with maxLLM=1 cancels in ≤5s ×20 without a second slot (CANCEL-01..03); Uncertain is truthful; observer≠owner; 119/119 in lane; cancelInvoke/tool schemas unchanged | `W2-EMITTERS-CANCEL-SHELL.md` (RAI-063) — note: actual source is W2, not W3; W3 added Delegated-race fix + root-pool invariant (RAI-073, DR-01..03) | passed (recorded) |
| E4 | Mailbox + steering: bounded Mailbox (msgId dedup, ack in one STM commit, turn-boundary, typed Full/TerminalJob) — W3 (RAI-065); wiring per-child cap 32, `sendToChild`, owner steering at turn boundary, final-drain ack ≠ loss (SENDW-01..03) — W4 (RAI-074) | `W3-PARALLEL-10.md` + `W4-PARALLEL.md` | passed (recorded); model tool `send_agent` deliberately absent (W5 limitation) |
| E5 | UX03/04 baseline: event→visible p50 53.45 / p95 55.45 ms; REPL warm/cold measured (`artifacts/ux-baseline-root.json`) | `W5-PARALLEL.md` (RAI-089, coordinator measured on root) | measured (baseline, NOT a threshold; protocol `docs/reviews/ux-baseline-protocol.md`) |
| E6 | Test stabilization: raiderRuntime ×10 consecutively → 0 flakes (1710 test runs); negative control: 1–10/100 failures on old call-index code; parallel sbt ×3 stable | `W5-PARALLEL.md` (RAI-092) | passed |
| E7 | Integrated wave runs: full sbt 406/0 (W3) → 476/0 (W4) → 545/0 (W5); EXECUTE + 8/9/9 profiles passed; fast (10 gates) PASSED; validator 76→85→95 cards | `W3-PARALLEL-10.md`, `W4-PARALLEL.md`, `W5-PARALLEL.md` (quality artifacts: `…20261009-172428/172520`, `…20261009-191006`, `…20261009-203453/203554`) | passed (recorded, integrated trees) |

## 3. Honest obligations (NOT closed by M1)

1. **Human usability sessions (UX01)** — not conducted; subjective usability
   assessment belongs to the owner. UX03/04 (E5) is a machine-clock baseline
   from one machine, with no thresholds; UX goals remain future acceptance.
2. **live_verified (providers)** — no. W1 live probes (T1..T6/R1..R3) were
   refusal-before-spend only; RAI-079 (W4) was an HTTP stand tool-round, not live.
   Board obligation “Live-wire verification (real providers)” remains open:
   an owner-configured endpoint/budget is needed.
3. **Milestone run of formal profiles on the integrated candidate** — not run
   and not claimed by this card. `registry.json` contains only component-stage
   profiles; `stage_policy.milestone` = “obligations tracked by board; not runnable
   until stage target”. Formal M1 acceptance (milestone gate set on one candidate)
   is a known limitation and the next coordinator/owner step; lane evidence does
   not replace verification of the integrated candidate.
4. **Delegated-race edge**: a deadline-watchdog microsecond-window race before
   publication is a theoretical race (LOW, W3 review; the “publish-attempt
   precedes settle” guarantee is documented). Minor W2 notes remain open:
   setChildDeadline latest-wins has no separate test; watchdog maps are not
   cleaned up (growth in a very long-lived toolset).
5. **This record's lane limitation**: snapshot without Git and without `artifacts/`;
   evidence was compiled from records (fingerprints above). Fresh runs in the
   lane: planning-package validator only (see Commands). Source changes after
   the wave checks would require rerunning affected gates — E1–E7 records are
   bound to fingerprints of the corresponding wave trees, not this lane.

## 4. Verdict

**M1 functionally complete — owner sign-off requested.** Tabs, statuses,
cancel (OOB + deadlines), input integrity, and M1-DEMO (5 tabs, 0 losses) are
closed on the integrated W5 tree with green component runs. Owner sign-off is
required for §3 obligations (usability, live_verified, milestone profiles on
the candidate). No M2/M3 claims: plugin loader/hooks, StateTools, JobJournal,
and their wiring are outside this verdict.

## Changed paths

- `docs/work/records/M1-ACCEPTANCE.md` (new, this document).
- `docs/work/board.md` — one M1 row in Module Status.

Public APIs/schemas/contracts were not changed.

## Commands (this lane)

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `python3 scripts/quality/quality.py inventory --out <evidence>/before-manifest.json` | 0 | 396 files, before edits; lane-37-evidence |
| `python3 scripts/plan/validate.py --out artifacts/plan-report-m1a.json` | 1 | failed; 30 errors — ALL pre-existing (RAI-093..101 markdown/JSON contract mismatches + RAI-093 title/milestone in `docs/tasks/`, outside this card's allowed paths); A/B run without lane edits produces a byte-identical error set; 0 errors from RAI-099/M1-ACCEPTANCE |
| `python3 scripts/quality/quality.py inventory --out <evidence>/after-manifest.json` + diff against before | 0 | sanity manifest: only 2 allowed files |

## Limitations / blockers

- **Validator (docs-profile gate plan-validate) is red in the lane snapshot**: 30
  pre-existing RAI-093..101 errors (`docs/tasks/*.md` ↔ `backlog.json`
  contract mismatches; snapshot contains 104 cards versus 95 on W5 — cards
  093..101 were registered without Markdown synchronization). `docs/tasks/*`
  files are outside RAI-099 allowed paths — fix belongs to a separate card on
  the integrated tree; criterion “validate → 0” was honestly NOT met in the lane
  (see Commands).
- All obligations in §3; milestone-stage profiles are absent from registry.
- E2 has no standalone work record — it relies on the board entry and source files.
- Artifacts `artifacts/tabs-pty/m1-demo-summary.json` and
  `artifacts/ux-baseline-root.json` exist in the root tree, not in the lane;
  the lane cannot recheck them byte-for-byte — trust is based on the record
  fingerprints above.

## Review and next task

No independent review of this document was conducted (author's compilation);
the verdict awaits owner sign-off. Blocker before acceptance: synchronize
RAI-093..101 card contracts (validator → 0 on the integrated tree).
Next in the DAG: run formal milestone profiles on the integrated candidate
(coordinator), then P3.A remainder (mailbox wiring into the model tool), P4.C
SDK/docs/fixtures (M2 close), UX01 human sessions (owner).
