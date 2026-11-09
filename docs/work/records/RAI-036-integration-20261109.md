# RAI-036: work record (wave R-3 serial integration)

Status: done (integrated candidate GREEN on main). Date: 2026-11-09T21:15:00Z. Baseline commit/source: main at `a5bcf53e` before merge; integrated candidate after this step: `41ca1021` (merge `dc28592b` + integration scalafmt fix).

## Card

RAI-036 — conversation sessions (runtime conversation package, REPL session bridge, `contracts/sessions` wire contract). Integrated the independent lane branch `lane/rai-036` (7 commits `73e259ad`…`8b91516a` on top of `a5bcf53e`; independent review verdict: ACCEPT, no blockers) into `main` with a `--no-ff` merge, then verified the INTEGRATED candidate from the root checkout.

## Changed paths

Merged from the lane (all new files, 2510 insertions; no `build.sbt` change needed — modules already registered):

- `modules/runtime/src/main/scala/raider/runtime/conversation/{Conversation,ConversationArchive,ConversationRoot}.scala`
- `modules/runtime/src/test/scala/raider/runtime/conversation/{ConversationSpec,ConversationConcurrencySpec,ConversationArchiveSpec,InterruptReproSpec}.scala`
- `modules/repl/src/main/scala/raider/repl/session/ConversationRef.scala`
- `modules/repl/src/test/scala/raider/repl/session/SessionArchiveSpec.scala`
- `contracts/sessions/{README.md,session-archive.schema.json,expected/archive-golden.json,expected/archive-rejected.json}`

Integration-only addition (this checkout, on top of the merge): `41ca1021` reformats the 9 lane Scala files with scalafmt (`scalafmtAll` output only — comment reflow and indentation, no token-level code changes). Lane commits were not amended or rebased.

## Resulting behavior

- **SESSION-01 (normal path)**: `Conversation` implements a durable, serialized multi-turn session — exactly one provider turn at a time (turn dispatch serialized under `dispatchLock`), prompt/reply transcript persisted by `ConversationArchive.encodeDocument` as ONE compact JSON document per session at `~/.raider/conversations/<sessionId>.json` (manual AST codec, exact pinned field order `schemaVersion, sessionId, rootId, model, label?, archivedAtMs, turns`, `label` omitted when absent, never null). Encoding is byte-pinned against `contracts/sessions/expected/archive-golden.json`. REPL surface: `ConversationRef`/`ConversationTurn` verbs (`ask`, `start`, `cancel`, `close`, `messages`, `archive`) bridge the runtime to the REPL session package.
- **SESSION-02 (error/adversarial)**: `decodeDocument` uses a strict envelope with explicit-integer `schemaVersion` — missing field is a typed refusal, a newer-version document is a typed refusal naming the upgrade (never silently read), and unknown extra fields are ignored (documented divergence from the stricter reviewer-facing JSON Schema, `additionalProperties: false`). Unknown/invalid session ids are refused typed at the store boundary (`sanitize` — strict `[A-Za-z0-9._-]`, no leading dot, 1..64 chars). An oversized transcript is a typed budget refusal BEFORE any turn runs; reopening an archive seeds a fresh root and executes nothing until the next explicit ask — no implicit agent restart, no silent compaction. `expected/archive-rejected.json` pins each codec refusal reason prefix.
- **SESSION-03 (cancellation/concurrency)**: `ConversationRoot` anchors root ownership (`rootId` recorded per archive; an archived root is never reused — reopen always gets a fresh root). Concurrent start/close/cancel preserve order through the single `dispatchLock`; close and `cancelTurn` scan `inFlight` under the SAME lock, so a registered turn fiber is always visible once the permit is released — no dropped queued receipts.
- **ZIO 2.1.26 uninterruptible-fork root cause and fix**: a fiber forked from inside `ZIO.uninterruptible` inherits the uninterruptible mask and cannot be interrupted while parked — `Fiber.interrupt` from another fiber never completes. The turn dispatcher therefore forks the turn body (`turnBody(turn).forkDaemon`) OUTSIDE any uninterruptible region; atomicity comes from `dispatchLock` alone (the uninterruptible section is narrowed to registration under the permit). `InterruptReproSpec` (suite `InterruptSemanticsPin`, 2 tests) pins both halves of the reasoning: a plain-fork interrupt of a promise-parked child completes, and a fork created inside `ZIO.uninterruptible` parks forever (interrupt cannot be delivered) — if that ever flips, the ZIO fork-inheritance semantics changed and the dispatcher guard must be re-evaluated.

## Test inventory (integrated candidate, this checkout)

- `raiderRuntime/test`: **481 passed, 0 failed, 0 ignored** (pre-merge main baseline plus the lane's **38 new** runtime tests: ConversationSpec 20, ConversationConcurrencySpec 8, ConversationArchiveSpec 8, InterruptReproSpec 2).
- `raiderRepl/test`: **244 passed, 0 failed, 0 ignored** (plus the lane's **5 new** SessionArchiveSpec tests).
- Full-tree `sbt --batch test` (inside the quality fast profile): **1888 passed, 0 failed** across 11 module suites — confirms no cross-module regression from the integration.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Clean `--no-ff` merge of `lane/rai-036` into main (disjoint paths) | met | merge commit `dc28592b`, 'ort' strategy, no conflicts; 13 files, 2510 insertions |
| Integrated battery `raiderRuntime/test` + `raiderRepl/test` | met | run 1 (merge `dc28592b`) and run 2 (candidate `41ca1021`): 481 + 244 passed, 0 failed, both `[success]` |
| `make quality-changed` | met | EXIT=0; classification: clean tree → minimum `fast` profile |
| `make quality-changed QUALITY_EXECUTE=1` | met on candidate | first run on `dc28592b`: EXIT=2 — `scalafmt-check: failed exit=1` (9 other gates passed); after integration scalafmt fix `41ca1021`: EXIT=0, status=passed, all 10 gates green (manifest `artifacts/quality/20261109-210038-24012-0001/manifest.json`) |
| Contract goldens pinned | met | archive codec/store contract tests compare `encodeDocument` output byte-for-byte to `contracts/sessions/expected/archive-golden.json`; refusal-set tests pinned to `expected/archive-rejected.json` (8/9+9/8 counts above include them) |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git merge --no-ff lane/rai-036 -m "RAI-036: integrate conversation sessions lane (wave R-3)"` | 0 | merge commit `dc28592b` |
| `sbt -batch "raiderRuntime/test" "raiderRepl/test"` (run 1, `dc28592b`) | 0 | `/tmp/int036-battery.log` (481 + 244 passed; raiderRepl 4m51s) |
| `make quality-changed` (run 1) | 0 | `/tmp/int036-qc.log` |
| `make quality-changed QUALITY_EXECUTE=1` (run 1, `dc28592b`) | 2 | `/tmp/int036-qce.log`; manifest `artifacts/quality/20261109-204035-14293-0001/` — scalafmt-check failed exit=1, 9 gates passed |
| `sbt -batch scalafmtAll` + commit `41ca1021` | 0 | 9 files reformatted, formatting-only diff |
| `sbt -batch "raiderRuntime/test" "raiderRepl/test"` (run 2, `41ca1021`) | 0 | `/tmp/int036-battery.log` (481 + 244 passed; raiderRepl 5m30s) |
| `make quality-changed` (run 2) | 0 | `/tmp/int036-qc.log` |
| `make quality-changed QUALITY_EXECUTE=1` (run 2, `41ca1021`) | 0 | `/tmp/int036-qce.log`; manifest `artifacts/quality/20261109-210038-24012-0001/` — all 10 gates passed (`self-registry-check`, `sbt-compile`, `scalafmt-check`, `scalafix-check`, `forbidden-apis`, `license-audit`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `sbt-test`) |

Lane-side execution evidence (lane worktree, snapshot `8b91516a`): `/tmp/rai036-step2.log`, `/tmp/rai036-step2b.log`, `/tmp/rai036-step2c.log`, `/tmp/rai036-step3a.log`, `/tmp/rai036-step3b.log`. Integrated-candidate battery log: `/tmp/int036-battery.log` (final content = run 2).

## Limitations / review follow-ups

1. **ConversationStore.sanitize Unicode-vs-ASCII-schema gap** (review follow-up, NOT fixed here): the store id filter is ASCII `[A-Za-z0-9._-]` while the reviewer-facing JSON Schema pattern is likewise ASCII — non-ASCII (e.g. CJK/Unicode) session identifiers are refused at the store boundary; the intended treatment of Unicode ids across schema and store is unresolved and needs a separate card decision.
2. **Steering during a conversation is out of scope**: the steering-during-conversation test belongs to RAI-063 (send/steering); this lane only guarantees serialized turns and cancel/close ordering.
3. **Interrupt-after-runner-return window**: there is a small documented window where a turn's runner has returned but the fiber is not yet deregistered; interrupt in that window is a no-op rather than an error — documented behavior, no test pins it yet.
4. **`RaiderError.getMessage == null` wart in `modules/core`** (pre-existing, outside this card's paths): flagged for a separate card; intentionally NOT fixed in this integration.
5. Integration deviation, recorded honestly: the lane merged non-scalafmt-clean sources, so the first `QUALITY_EXECUTE=1` run on the merge commit failed `scalafmt-check` (EXIT=2). Fixed by integration commit `41ca1021` (formatter output only, lane commits untouched) and the full battery + fast profile were RERUN on the new fingerprint per the evidence-fingerprint rule — the green evidence above applies to `41ca1021`, not to `dc28592b`.
6. Fast-profile gate classification on a clean tree resolves to the minimum `fast` profile; semantic profiles beyond it (e.g. `runtime`, `repl`) were covered by the explicit two-module battery plus the full-tree `sbt --batch test` inside the profile.

## Review and next task

Lane review verdict: ACCEPT, no blockers (independent review of `lane/rai-036`). This integration adds no review blockers; follow-ups 1 and 4 above are candidate cards. Next available: RAI-037/RAI-040 lane integrations (wave R-3), then the steering card RAI-063.
