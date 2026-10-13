<!-- Work record. Original in Russian; key results in English below. -->

# DELEGATION (§11): work record — model-callable child delegation

Status: done (slice: delegate/await_agent/cancel_agent/list_agents over
JobManager+AgentLoop; full §11 — send_agent/context policy — remains an
obligation). Date: 2026-10-04. Baseline: previous last-passing gate
artifacts/quality/20261004-055145 (DSL-ERGO).

## Resulting behavior

`raider.runtime.loop.DelegationToolset` — model-callable delegation tools over
the SAME JobManager / root ledger / admission as workflow fork (runtime-contracts
§11, LOOP-01/W09):

- `delegate {"agent","input"}` — validates name against explicit whitelist
  and count against `limits.maxChildren`; creates a child job with its OWN
  root (child budget = parent budget — one ledger, verified: 6 dispatches on
  one root); returns `{"child_id","state"}` — NEVER transcript; errors
  (unknown_agent/max_children/invalid_arguments) give structured feedback JSON
  to the loop, not silent ignore;
- `await_agent {"child_id"}` — suspends parent, does NOT hold a model slot
  (children operate under the same maxConcurrentLlm=1 — BUD-03 no-deadlock for
  cooperative children, proved by maxLLM=1 fixture); holds ONE tool slot, so
  construction requires `maxConcurrentTools >= 2` (typed configuration,
  BUD-03 discipline);
- `cancel_agent` / `list_agents` — child statuses through JobManager admin;
- children cannot delegate again: child registry = base registry (§11 — a
  separate policy, not accidental); `send_agent` is deliberately absent
  (steering = RA-CAP obligation);
- incidental compiler fix: nested member select in raw interpolation
  (`${call.call.value.toJson}`) triggered E046 cyclic in Scala 3.9 — use an
  intermediate value (`AgentLoop.toolMessage`).

**Actual design finding** (reproduced by fixture and honestly documented): at
maxLLM=1 a STUCK child holds the only model slot, while its parent needs ITS
OWN slot even to invoke cancel_agent — a cycle. Mitigations: (a) llm≥2 for
scenarios at risk of hanging (cancel fixture uses llm=2), (b) child deadlines,
(c) out-of-band cancel channel — an OBLIGATION in board (§11 policy).

## Changed paths

```
11b30699…  modules/runtime/src/main/scala/raider/runtime/loop/DelegationToolset.scala
0e9bc310…  modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala (toolMessage: intermediate val for E046)
429d635b…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| delegate → await_agent → final: one root ledger (tries=6: 3 parent model + 1 child model + 2 tools), maxLLM=1 without deadlock | pass (15s timeout guard did not fire) | AgentLoopDelegationSpec |
| unknown agent → unknown_agent feedback + known names, nothing spawned | pass | AgentLoopDelegationSpec |
| max_children exceeded → max_children feedback, second spawn refused | pass | AgentLoopDelegationSpec |
| await_agent with unknown id → unknown_child feedback | pass | AgentLoopDelegationSpec |
| cancel_agent parks then cancels: child Cancelled, model slot released | pass (llm=2, see finding) | AgentLoopDelegationSpec |
| Construction: maxConcurrentTools<2 / shadow names → RA-CFG | pass | AgentLoopDelegationSpec |
| No recursive delegation: child registry = base registry | pass (structurally) | AgentLoopDelegationSpec |
| Stability ×3 | 7/7, exit 0 ×3 | /tmp/g24.log, /tmp/dlg-run-{1,2}.log |
| Full sbt test | 178 tests, 0 failed, exit 0 | /tmp/full-test-DLG.log |
| Quality gates 6/6 ×2 CONSECUTIVELY | exit 0 ×2 | artifacts/quality/20261004-06{4737,4809}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopDelegationSpec"` ×3 | 0,0,0 | /tmp/g24.log, /tmp/dlg-run-{1,2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (178) | /tmp/full-test-DLG.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261004-06{4737,4809}/ |

Diagnostic trail (honestly): first runs looked like hangs — thread dump showed
ALL ZScheduler workers parked. Cause was a combination of (a) an incorrect
assertion `tries==5` (correct value is 6 — child consumes root budget) and (b)
parallel test load plus the real design gap where cancel at maxLLM=1 can block
(see above). The `-zt` filter in sbt testOnly did not filter in this setup (all
tests ran); noted for future use.

## Limitations / obligations

- send_agent is absent (RA-CAP steering obligation); context policy
  (TaskOnly/Summary) needs a structured message contract.
- A stuck child at maxLLM=1 blocks the parent's cancelAgent — design finding;
  mitigations (child deadlines / OOB cancel) are §11 obligations.
- Tool definitions (delegate argument schemas) on the wire are a structured
  message contract obligation.
- Depth tracking currently means recursion is prohibited by construction;
  numeric depth will come with the policy card.

## Review and next task

Independent review was not conducted (coordinator session). Next: RAI-022
(headless CLI/bundle), structured message contract, RAI-014/015 tool integration
into the loop.
