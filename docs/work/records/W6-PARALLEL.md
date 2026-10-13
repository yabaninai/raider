# W6-PARALLEL: work record

Status: done (after fix round from independent review). Date: 2026-10-09 UTC.
Baseline: commit `03828a9` (owner committed W4/W5 tree). Mode: W6×10 — 9 cards
in 9 lanes + ADR-025 freeze review + UX (coordinator).

## Resulting behavior (9 cards accepted)

1. **RAI-083** — `plugin` subcommand in Main (routing before CliArgs, JSON, exit
   result.scala). cli 124/124 (included).
2. **RAI-084** — HookRunner/RaiderHook (bounded 2s default, cooperative
   interrupt, sealed HookOutcome; frozen PluginError not extended). core 61.
3. **RAI-085** — StateTools (state_get/put/delete/keys, opt-in, scope
   Agent/Root/Global, RA-LBUDGET/RA-CAP feedback).
4. **RAI-086** — `--context` → CiContext in run.json (untrusted → redacted);
   `accounting` from real BudgetLedger (honest unknown; field absent on
   preflight-fail).
5. **RAI-087** — JobJournal: append-only JSONL+rotate, atomic checkpoint,
   corrupt/partial tail → truncate + Uncertain, no auto-replay.
6. **RAI-088** — TabShell `:plugins`/`:state` (pure providers, one writer).
7. **RAI-092** — Delegation flakes stabilized (content-based routing instead of
   call-index; awaitSettled instead of race): raiderRuntime ×10 → 0 flakes
   (1710 runs), negative control 1-10/100 on old code.
8. **RAI-090/098** — REPL live env-key + **live anthropic** (build edge
   raiderRepl→provider-anthropic, SSE, escape tests, masking; OpenAI path
   byte-identical). repl 55/55.
9. **RAI-091** — forbidden-apis location allowlist (additions-only, NOTE skips,
   lookalike negative; gate-policy review ACCEPTED in W6 batch review).

Integration: two hand-merges (AgentLoop: journal-scaladoc × plugins-param;
HeadlessRunner: context/accounting × ci-wrappers) were confirmed as a “clean
union” by review after the fix round. Five integration omissions from review
were fixed (LoopEvents journal wiring, CodingToolset state opt-in, ShellViews
adapter, 3 missing specs, board M1 row).

## Acceptance evidence

| Check | Result |
| --- | --- |
| Full `sbt test` | **569 passed / 0 failed** (runtime 205, cli 124, tools 63, core 70, repl 55, ui 23, …) |
| fast (10 gates) | PASSED |
| 9 profiles (incl. security/transport/tools/repl) | passed (`…20261009-223502`) |
| ADR-025 freeze review | CHANGES-REQUIRED → R1-R8 amendments applied → **Status: Frozen** (review verdict: “code needs nothing”) |
| build.sbt edges | ratified: tools→runtime (no cycle), repl→provider-anthropic; raiderCore test-sources += contracts/plugins |
| registry | +`^examples/` → docs (additions-only, RAI-096 follow-up) |
| Validator | 104 cards passed |

## Limitations

- HookRunner timeout is cooperative (process isolation is out of scope).
- JobJournal: no fsync, single-writer; loop wiring: Terminal publication at
  job-level (RAI-059) — journal sees the loop emission channel (slice honestly
  documented).
- Hook instance wiring is an open contract (ADR-025 §7 freeze note, owner card).
- tools→runtime edge was “dead” before RAI-094 (now used).
- Snapshot zinc-staleness (known lane artifact) is fixed by clean.

## Review

W6 batch independent review: 12 ACCEPTED + 5 integration drops (all fixed
verbatim from lanes) → final suites green; AgentLoop/HeadlessRunner merges
confirmed as a clean union.
