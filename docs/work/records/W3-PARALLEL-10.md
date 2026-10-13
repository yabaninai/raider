# W3-PARALLEL-10: work record

Status: done. Date: 2026-10-09 UTC. Baseline: integrated W2 tree + RAI-060.
Mode: OWNER DECISION 2026-10-09 — parallelism increased from 3→10 workers
(exceeding runbook §1 by owner decision; recorded in the board). 10 isolated
lanes (base 275/277 files), cloud agents as writers (local-qwen disabled by the
owner), independent reviewers. Both dispatch batches and restarts after
500/abort completed without loss of work.

## Resulting behavior (10 cards accepted)

1. **RAI-062 (P2.C)** — Tab PTY integration: `TabShellPtyApp` (test-scope) +
   `tabs_pty_probe.py`; PTY-01/02/03 PASS on root; **M1-DEMO: 5 tabs, 1105
   events, 0 losses, byte-exact drafts** (`artifacts/tabs-pty/m1-demo-summary.json`,
   all_pass=true).
2. **RAI-065 (P3.A)** — `raider.runtime.mailbox.Mailbox`: bounded, msgId dedup,
   ack in one STM commit, turn boundary (Draining/Closed), typed Full/TerminalJob;
   spec flake (interruptible bankCycle window) fixed with `ZIO.uninterruptible`
   (reviewer fix).
3. **RAI-066 (P4.A)** — **ADR-025** (Proposed, freeze after review) + clean
   `raider.core.plugin` skeleton (descriptor/manifest/preflight; structurally
   verified no-IO); core 36/36.
4. **RAI-067** — `PatchTool.applyAll`: multi-file unified diff, all-or-nothing
   with honest partial report; best-effort restore + ToolOutcomeUncertain;
   tools 60/60.
5. **RAI-068** — Real REPL completions through dotty 3.9.0
   (`completions(cursor, expr, state)`, protected → bridge subclass);
   JLine Completer + cache keyed by state identity; repl 25, repl-engine 9,
   smoke 0.
6. **RAI-069 (gate-policy, review APPROVE)** — Security profile (component):
   secrets-scan gate (fail-closed, masked findings, self-test, SEC-02
   verified-negative); registry diff additions-only; thresholds unchanged.
7. **RAI-070** — UX baseline harness: environment+fingerprint JSON, UX03/04
   honestly skipped in lane (classpath) — measurement code demonstrated by
   smoke run; protocol in docs/reviews.
8. **RAI-071** — `--provider anthropic` (W05 parity): ProviderBackend factory,
   preflight RA-CFG, cli 65/65; transport profile passed; HTTP-stand round
   honestly deferred (no stand in cli).
9. **RAI-072** — Docs refresh: api/architecture/contributing aligned with actual
   API (checked against source; P11 not represented as done; validator 0).
10. **RAI-073** — Delegated-race fix: park-gate (child parks on Promise before
    Delegated publication; addFinalizer release; DR-01..03; discriminator
    lastSeq==3). Root-pool invariant locked by a test.

## Changed paths

runtime: `mailbox/**` (new), `delegate/DelegationToolset.scala` (race fix +
null-free), `delegate/DelegationRaceSpec.scala`; tools: `edit/{PatchTool,
UnifiedDiff}.scala`, `MultiPatchSpec.scala`; repl: `repl-engine/DottyReplEngine.scala`,
`repl/{CompletionSupport.scala,Main.scala}`, specs; ui: `TabShellPtyApp.scala`;
core: `plugin/plugin.scala` + spec; cli: `run/{CliArgs,ProviderBackend,
HeadlessRunner}.scala`, `chat/ChatLoop.scala`, 3 new specs; gate-policy:
`registry.json`, `quality.py`, `security_checks.py`; UX: `ux_baseline.py`,
`docs/reviews/ux-baseline-protocol.md`; docs: `{api,architecture,contributing}.md`;
ADR-025; `build.sbt` (raiderUi JLine + raiderCli provider-anthropic);
docs/tasks/* (RAI-065..073), board, this record.

## Acceptance evidence

| Check | Result |
| --- | --- |
| Full `sbt test` | **406 passed / 0 failed** (runtime 150, cli 65, tools 60, core 36, repl 25, cli-extra 11, ui 16, repl-engine 9, etc.) |
| forbidden_apis / scalafix / scalafmt | 0 violations (fixes: mailbox uninterruptible, Option-null in DelegationToolset, System.exit→return in PTY-app/spike, fixture key rename, LazyList drain) |
| EXECUTE + profiles | passed: `…20261009-172428` (5/5) and `…20261009-172520` (**8 profiles** static/unit/contracts/runtime/ci/repl/tools/security) |
| M1-DEMO (root PTY) | PTY-01..03 PASS, all_pass=true, 5 tabs / 1105 events / 0 dropped |
| Validator | 76 cards passed |
| Review | 8 cards ACCEPTED; RAI-069 registry APPROVE (additions-only, fail-closed); all fixes applied |

## Commands (key)

`sbt --batch test` → 406/0; `make quality-changed QUALITY_EXECUTE=1` → 0;
`make quality-profile PROFILES="static unit contracts runtime ci repl tools security"` → 0;
`python3 scripts/quality/tabs_pty_probe.py` → 0 (M1-DEMO all_pass);
`python3 scripts/quality/security_checks.py --self-test` → 0;
`python3 scripts/plan/validate.py` → passed/76.

## Limitations / blockers

- Delegated-race:Deadline-watchdog in the microsecond window before publication
  is a theoretical race (LOW, review; exact “publish-attempt precedes settle”
  guarantee documented).
- RAI-071: tool-round through HTTP-stand inside cli is deferred (no stand in the
  module); wire is covered by the provider-anthropic suite.
- RAI-070: UX03/UX04 numbers will appear in a run with a working classpath (it
  now builds in root; protocol is ready).
- RAI-069: licensing is information-only; CVE/OWASP scanner out of scope.
- PatchTool rollback is best-effort (crash mid-commit → Uncertain, not silent).
- Mailbox wiring in DelegationToolset/REPL is a separate card (P3.A remainder).
- Docs: fast is now 10 gates; mailbox section added to api.md.

## Review and next task

W3 review: ACCEPTED ×8 + registry APPROVE; fix list closed (flake, null,
forbidden-apis, docs count). **M1 milestone: functionally closed** (tabs +
statuses + cancel + demo all_pass) — formal M1 acceptance (milestone gate set on
integrated candidate + honest obligations W08-W11/UX) is the coordinator's next
step. Next in DAG: P2 formal M1-acceptance card, P3.A remainder (mailbox wiring),
P4.B plugin manager, P5 state, P6 recovery, P7 corpus/live. Yabanin live is after
M4 (P11); key/budget with owner.
