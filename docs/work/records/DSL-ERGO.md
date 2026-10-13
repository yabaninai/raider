<!-- Work record. Original in Russian; key results in English below. -->

# DSL-ERGO (RAI-018 slice): work record — less verbose DSL

Status: done (slice: facade spellings for golden scenarios; full RAI-018 is a separate card). Date: 2026-10-04. Baseline: previous last-passing gate `artifacts/quality/20261004-051133` (RAI-013-SLICE).

## Resulting behavior

Less verbose DSL in the REPL (everything uses ONE interpreter — AgentLoop/composition, no second semantics; construction dispatches nothing — API-03):

- `scout("research parser")` is a LAZY typed `AgentAsk` descriptor: `.run()` / `.ask()` (foreground), `.start()` (background, `:jobs`), `.map(f)` (typed post-transformation); before run/ask/start there are zero backend calls (fixture check).
- `all(scout, reviewer).ask("p")` → `(String, String)`; `all(askA.map(len), askB).run()` → typed tuple, through `raider.dsl.composition.all` (fail-fast COMP-02).
- `batch(List("parser","config","cli"), parallelism = 2)(name => scout(s"research $name")).run()` → results in input order; `.collect()` → `BatchOutcome` (domain failures become values, external interruption is re-raised, §5).
- `openSession(worker)` → `Chat` with history: each `ask` adds user+assistant turns — MULTI-TURN continuity through the same AgentLoop (fixture: turn-2 request carries user1, assistant1, user2).
- `job.await` WITHOUT parentheses is an overload of `await()` via `@targetName` (erasure clash resolved by annotation); both spellings produce one outcome, and await is idempotent.
- Facade registry (`:jobs`/`:cancel`) extended to `JobHandle[?]` (heterogeneous typed jobs); cancel uses `cancelAny`.

The international contract did not change: AgentRef/ask/start/await()/send(RA-CAP)/watch are unchanged; `Runner.runText` untouched.

## Changed paths

```
107b8f70… modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala
4dd84d3a… modules/repl/src/test/scala/raider/repl/facade/DslErgonomicsSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Laziness: constructing scout/all/batch makes 0 backend requests (API-03) | pass | DslErgonomicsSpec |
| `scout("p").run()` / `.ask()` / `.map(_.length)` — 1 dispatch, typed | pass | DslErgonomicsSpec |
| `.start()` + await (both spellings) are idempotent, Succeeded | pass | DslErgonomicsSpec |
| `all(agents).ask` / `all(asks).run` — tuples, fail-fast through composition | pass | DslErgonomicsSpec |
| `batch.run()` — results in INPUT order (request-write order for parallelism>1 is NOT part of the contract; the gate caught my overly strict assertion, corrected to set semantics) | pass | DslErgonomicsSpec + gate 054915 (honest failure) |
| `batch.collect()` — domain failures as outcomes | pass | DslErgonomicsSpec |
| `openSession`: turn 2 carries user1+assistant1+user2 (continuity) | pass | DslErgonomicsSpec |
| Both REPL smokes on changed facade | non-TTY exit 0; PTY 13/13 | `/tmp/smoke-dsl.log`, `/tmp/pty-dsl.log` |
| Stability ×3 (repl 16/16) | exit 0 ×3 | `/tmp/d9.log`, `/tmp/dsl-run-{1,2}.log` |
| Full sbt test | 171 tests, 0 failed, exit 0 ×2 | `/tmp/full-test-DSL{,2}.log` |
| Quality gates 6/6 ×2 CONSECUTIVELY (after assertion fix) | exit 0 ×2 | `artifacts/quality/20261004-05{5104,5145}/` |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRepl/test"` ×3 | 0,0,0 | `/tmp/d9.log`, `/tmp/dsl-run-{1,2}.log` |
| `sh scripts/quality/repl_smoke.sh` | 0 | `/tmp/smoke-dsl.log` |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 (13/13) | `/tmp/pty-dsl.log` |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×2 | 0 (171) | `/tmp/full-test-DSL{,2}.log` |
| `make quality-changed QUALITY_EXECUTE=1` (before assertion fix, honestly) | exit 2 | `artifacts/quality/20261004-054915/` |
| `make quality-changed QUALITY_EXECUTE=1` ×2 (after) | 0, 0 | `artifacts/quality/20261004-05{5104,5145}/` |

## Limitations / obligations

- `.named(...)` on compositions, `job.send` (RA-CAP), streaming watch — previous RAI-018 obligations.
- Chat does not survive `:reset` (state is in interpreter world, like the entire facade; known W10 model).
- `all` arity-2 (like composition.all); arity-3+ and heterogeneous tuple map — in full card RAI-018/017.
- `AgentAsk` is invariant in A (`JobHandle` is invariant — covariance +A broke `start()`); this does not affect ergonomics.

## Review and next task

No independent review was conducted (coordinator session). Next: child delegation (§11), RAI-022 (headless CLI), structured message contract.
