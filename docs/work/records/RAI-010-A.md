<!-- Work record. -->

# RAI-010-A: work record (native agent loop slice)

Status: done (scripted/fixture slice; honest obligations listed). Date: 2026-10-03.
Baseline: root checkout with no commits; starting gate 6/6 full-tree
artifacts/quality/20261003-212034 (fingerprint before this card's changes).

## Resulting behavior

`raider.runtime.loop.AgentLoop` is the single entry point for AGENT execution
(runtime-contracts §8), not a second runtime:

- freeze point = constructor: backend, ToolRegistry, model, Admission, root,
  attemptEstimate; each `runText(messages, ceilings, maxRounds)` binds the
  conversation to its ceilings; configuration validation (empty messages,
  maxRounds<1, maxAttempts<1, maxRounds>maxAttempts) returns typed Configuration
  BEFORE any call;
- a round = one admission attempt (Model slot + BudgetLedger reserve in §6
  order) around one `backend.stream`; ONLY `ToolCallReady` is executed
  (`ToolCallDelta` is never executed, §8);
- unknown tool / invalid arguments JSON → structured feedback to the model
  (`{"error":"unknown_tool"|"invalid_arguments"}`, role "tool"), loop
  continues, bounded by maxRounds — no silent ignore;
- a definitive tool error (or non-JSON tool output) fails the entire run with
  the tool code (RA-TOOLFAIL etc.); mutational tool calls are NOT retried after
  an ambiguous/failed result;
- tools are dispatched through the Tool slot (`withSlotFree`), strictly
  sequentially in emission order (§6 default serialization), with per-tool
  timeoutMs → typed DeadlineExceeded; slot is released;
- tool results are RequestMessage("tool",
  `{"tool_call_id":…,"output":…}`) with zio-json escaping;
- exhausting maxRounds/admission.maxAttempts → typed LocalBudgetExceeded;
- final text is validated: non-empty + marker-free
  (`AgentLoop.InternalMarker` is a reserved sentinel, never injected by the
  loop; its presence in the response = OutputValidation);
- cancellation accounting is entirely in RAI-009 Admission brackets: cancel
  before permit — nothing; after permit and before dispatch — release+refund;
  interrupt DURING model/tool dispatch — Uncertain, charge kept (with a Priced
  estimate visible in uncertainTotal; Unknown is honestly tracked as unpriced);
- with an empty registry, the loop degenerates EXACTLY to Runner.runText
  (fixture equality of text) — Runner.runText is UNCHANGED, REPL foundation intact.

Testkit (RAI-006 territory, minimal documented extension):
`ScriptedSequenceBackend` — the k-th `stream()` plays the k-th script; all
requests are recorded, `fixtureMode=true`, an extra call = typed StreamProtocol.

Related hardening (RAI-009.b territory, brought forward due to flakiness under
the new parallel load): two sleep-ordered BudgetAdmissionSpec tests
("cancel while WAITING…", "cancel during dispatch…") were changed to
deterministic `repeatUntil` barriers on availableSlots/reservedActive (the
pattern used by a neighboring deterministic test).

## Changed paths

New:

- `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala`
- `modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopSpec.scala`
- `modules/testkit/src/main/scala/raider/testkit/ScriptedSequenceBackend.scala`
- `modules/testkit/src/test/scala/raider/testkit/ScriptedSequenceBackendSpec.scala`

Changed:

- `build.sbt`: raiderRuntime `.dependsOn(raiderTestkit % Test)` — loop fixtures
  run scripted backends; Test scope, testkit is not included in the production
  classpath (coordinator change, no new external dependencies).
- `modules/runtime/src/test/scala/raider/runtime/budget/BudgetAdmissionSpec.scala`:
  2 tests made deterministic (see above); assertion semantics unchanged.

sha256 (final sources):

```
96d681fe…  build.sbt
c2138bdf…  modules/testkit/src/main/scala/raider/testkit/ScriptedSequenceBackend.scala
29e127c6…  modules/testkit/src/test/scala/raider/testkit/ScriptedSequenceBackendSpec.scala
94be0092…  modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala
1b456512…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopSpec.scala
49e1a6b1…  modules/runtime/src/test/scala/raider/runtime/budget/BudgetAdmissionSpec.scala
```

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LOOP-01 text→tool→result→final (scripted, tool_call_id matched, distinct attempts) | pass | AgentLoopSpec LOOP-01 suite |
| LOOP-01 two calls in one round — strictly sequential, both matched | pass | AgentLoopSpec |
| LOOP-01 degeneration: empty registry == Runner.runText | pass (text equality, 1 request) | AgentLoopSpec |
| LOOP-02 maxRounds>maxAttempts → RA-CFG BEFORE any call (requests empty) | pass | AgentLoopSpec |
| LOOP-02 maxRounds exhausted with unclosed tool calls → RA-LBUDGET | pass | AgentLoopSpec |
| LOOP-02 admission maxAttempts exhausted at root → RA-LBUDGET, backend untouched | pass | AgentLoopSpec |
| LOOP-02 unknown tool / invalid args → feedback, tool not invoked | pass | AgentLoopSpec |
| LOOP-02 provider Failed → RA-RATE; no Finished → RA-STREAM; empty/marked final → RA-OUT | pass | AgentLoopSpec |
| LOOP-03 tool failure → Failed with tool code (RA-TOOLFAIL), no retry | pass | AgentLoopSpec |
| LOOP-03 non-JSON tool output → RA-TOOLFAIL | pass | AgentLoopSpec |
| LOOP-03 tool timeout → RA-DEADLINE, slot released | pass | AgentLoopSpec |
| LOOP-03 cancelRoot before dispatch → RA-CANCEL, zero charge | pass | AgentLoopSpec |
| LOOP-03 interrupt DURING model dispatch (Priced 250µ$) → uncertainTotal=250µ$, charge kept | pass | AgentLoopSpec |
| LOOP-03 interrupt DURING tool dispatch → model portion of rounds settled, no slot leaks | pass | AgentLoopSpec |
| Loop spec stability ×3 | 18/18, 18/18, 18/18 (exit 0 ×3) | /tmp/loop-run-{1,2,3}.log (runs), console |
| Full sbt test ×3 | 110 tests (core 10+13+5, dsl 19, testkit 6+6? see breakdown below), exit 0 ×3 | /tmp/full-test-A2*.log |
| Testkit spec | 5/5 (3 ScriptedModelBackend + 2 ScriptedSequenceBackend) | raiderTestkit/test |

Full run breakdown (7 suite blocks): 10 (core Config) + 13 (core
contracts) + 5 (core Tool) + 19 (runtime jobs/scopes) + 6 (runtime budget) +
6 (testkit) + 51 (runtime, including 18 loop) — 110 total, exit 0.

## Commands

| argv / command | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderTestkit/test"` | 0 | /tmp/tk-test.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/test"` | 0 (after fixes) | /tmp/rt-test7.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopSpec"` ×3 | 0,0,0 | /tmp/loop-run-{1..3}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×3 | 0,0,0 | /tmp/full-test-A2{,-run2,-run3}.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6 passed) | artifacts/quality/20261003-214145/manifest.json |

## Limitations / obligations

- Child delegation (LOOP-01 in the full card) is NOT in this slice: delegate/
  await_agent tools over JobManager (§11) are a separate card; this contains
  only text/tool rounds, as allowed by the phase scope.
- Parallel fan-out of `concurrentSafe` tool calls is not implemented (always
  sequential); documented honestly in code.
- Steering at a safe boundary (§8 step 6) is an obligation (send = RA-CAP in
  REPL; the loop API has no steering input).
- Live pricing: attemptEstimate defaults to Unknown (unpriced is tracked, not
  free); prices will come with provider adapters (RAI-011/012).
- Journal/event recording of rounds (§8 steps 5/8) is an obligation (RAI-027).
- The loop reuses admission attempt semantics as “all root dispatches”
  (model+tool) — RAI-009 counters were unchanged; maxRounds separately limits
  model rounds.

## Review and next task

No independent review was performed (coordinator session). Next on the
critical path: Phase B — REPL scout/worker on AgentLoop (behavior without tools
is identical), then the RAI-002 remainder.
