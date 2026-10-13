# W5-PARALLEL: Work Record

Status: done. Date: 2026-10-09 UTC. Baseline: integrated W4 tree.
Mode: W5×10 (owner: cloud subagents only; local disabled) — 9 cards in 9
isolated lanes + UX measurements by coordinator on root + reviewer.

## Resulting behavior (9 cards accepted)

1. **RAI-083 (P4.B)** — `plugin` subcommand in Main (routed before CliArgs
   parsing, JSON output, result.scala exit codes; RAIDER_PLUGINS_DIR). cli 105.
2. **RAI-084 (P4.A finish)** — `HookRunner` + `RaiderHook` trait: bounded
   timeout (cooperative interrupt, NEVER a stop API), typed outcomes (own
   HookOutcome family — frozen PluginError was not extended), read-only input
   seam (no policy/budget), no-sandbox documented; PLUG-03 constant-pool scan
   remains green. core 61/61.
3. **RAI-085 (P5.B)** — `StateTools`: state_get/put/delete/keys as Tools
   (JSON args), scope defaults to Agent(jobId), Root/Global only explicitly;
   RA-LBUDGET/RA-CAP structured feedback; without a store, tools are absent.
4. **RAI-086 (RAI-023 wiring)** — `--context <file>` → CiContext (untrusted →
   redacted in run.json); run.json `accounting` from a REAL BudgetLedger
   snapshot (unknown ⇔ unpriced; on preflight failure the field is absent, not
   fabricated); summary cost line.
5. **RAI-087 (P6.A)** — `JobJournal`: append-only JSONL with rotation, atomic
   checkpoint, recovery: corrupt/partial tail → truncate + honest Uncertain;
   NO auto-replay of mutations; no-fsync contract documented.
6. **RAI-088** — TabShell `:plugins`/`:state` screens through pure providers
   (no IO in ui); printAbove single-writer; 100-event burst test.
7. **RAI-089 (coordinator, root)** — UX03/04 baseline MEASURED: event→visible
   **p50 53.45ms / p95 55.45ms**; REPL warm/cold measured;
   `artifacts/ux-baseline-root.json` + protocol.
8. **RAI-090** — REPL live env key (RAIDER_API_KEY/RAIDER_API_KEY_CMD, masking,
   10s bound; missing key → notice + mock fallback); live Anthropic uses an
   honest fallback (REPL does not depend on provider-anthropic). repl 48/48.
9. **RAI-091 (gate-policy)** — forbidden-apis location allowlist
   (additions-only: system-exit @ raider.cli.Main; skips are explicit NOTE;
   lookalike-path negative case in self-test). Required review was completed
   as part of W5 batch review (verdict ACCEPTED).
10. **RAI-092 (test-only)** — delegation flakes stabilized:
    content-based routing instead of call-index + awaitSettled instead of a
    race; negative control proved the cause (1-10/100 on old code);
    **raiderRuntime/test ×10 consecutively → 0 flakes** (1710 test runs);
    parallel sbt ×3 stable.

## Acceptance evidence

| Check | Result |
| --- | --- |
| Full `sbt test` (final) | **545 passed / 0 failed** |
| fast (10 gates) | PASSED — scalafix+forbidden-apis exercised, 0 violations |
| 9 profiles | passed: static/unit/contracts/runtime/ci/repl/tools/transport/security (`…20261009-203554`) |
| M1-DEMO | PTY all_pass (5 tabs/1105 events/0 lost; W3 artifacts, tree did not regress: full suite green) |
| UX03/04 | measured on root: p50 53.45 / p95 55.45 ms (baseline, not threshold) |
| STAB | ×10 runs + negative control + parallel sbt ×3 |
| Validator | 95 cards passed |

Integration incidents: scalafmt ran after my HookRunner docs edits (oops —
reformatted); neutralizing fixture literals broke 3 LiveCredentialsSpec
assertions (fixed with neutral values); JobJournal `parent != null` →
`Option(parent).foreach` (scalafix). All were resolved before the final green run.

## Key commands

`sbt --batch test` → 545/0; `make quality-changed QUALITY_EXECUTE=1` → 0;
`make quality-profile PROFILES="fast"` → 0; 9 profiles → 0;
`python3 scripts/quality/ux_baseline.py --out artifacts/ux-baseline-root.json` → 0;
`python3 scripts/plan/validate.py` → passed/95.

## Limitations / blockers

- HookRunner timeout is cooperative (a hook that ignores interrupt holds a
  daemon thread until JVM exit) — documented; fundamentally solved only by
  process isolation (outside budget).
- JobJournal: no fsync; single-writer; wiring into runtime loop is a separate
  card (nothing calls it yet).
- Mailbox wiring: sendToChild is a Scala API; the `send_agent` model tool is
  still intentionally absent.
- RAI-084 extends the skeleton (RaiderHook trait) — ADR-025 freeze review must
  confirm the addition (queued).
- REPL live Anthropic: blocked on a build.sbt edge (raiderRepl →
  provider-anthropic; note to coordinator).

## Review and next task

Independent W5 batch review (verdict ACCEPTED; findings applied: the
neutralize script broke assertions — fixed; HookRunner docs reworded).
Next: P4.C SDK/docs/fixtures (M2 close), P5.B wiring (StateTools →
CodingToolset/TabShell), P6.A wiring (journal → loop), UX01 human sessions
(owner), M2 acceptance run.
