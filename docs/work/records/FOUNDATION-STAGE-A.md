<!-- Work record. Original in Russian; key results in English below. -->

# FOUNDATION-STAGE-A + F0 finalization: work record

Status: done (re-review pending). Date: 2026-10-03 (UTC). Baseline: the same
untracked tree; the review manifest matches the pre-F0-fix state (checked at
the start of the session).

## Resulting behavior

1. F0 remediation is complete: all 13 independent review findings (N1–N13)
   were resolved — exit propagation without tee-swap + fault-injection
   self-tests in both launchers, TERM/HOME isolation, truthful exit map
   (failed→20, artifact→27), grandchild reproducer for the process-group gap
   (RAI-016 obligation), typed StreamProtocol failures (exit 21) for malformed
   SSE/premature EOF, measurable before-headers cancellation, source manifests,
   toolchain inventory in the evidence directory, and updated README/usage.
2. The owner established the language policy: product code is Scala 3 + ZIO
   only; Python is minimized and excluded from the deliverable; a local
   OpenAI-compatible stand is permitted for tests. Recorded in docs/PLAN.md and
   AGENTS.md.
3. Foundation stage A: first product Scala code — modules/core v0 (6 files:
   ids, RaiderError family, Task (lazy ZIO wrapper), Program with infix andThen +
   mapOutput, Agent/Workflow, Job/Child, ModelBackend + ModelEvent/Usage/
   Capabilities). Build with scripts/build/compile.sh on pinned dotc 3.9.0:
   the positive fixture compiles; the negative fixture (andThen type mismatch)
   is correctly rejected; 0 warnings. scripts/bootstrap.sh is jar bootstrap
   glue.

## Changed paths

- experiments/repl-spike/{run_spike.sh, fetch_toolchain.py, harness/repl_pty_harness.py,
  harness/headless_harness.py, headless/HeadlessSpike.scala, probe/HttpJsonProbe.scala, README.md}
- experiments/openai-chat-spike/{run_spike.sh, OpenAiCompatSpike.scala,
  harness/mock_gateway.py, harness/oai_spike_harness.py, README.md}
- modules/core/src/main/scala/raider/core/{ids,errors,task,program,job,backend}.scala (new)
- contracts/compile/{positive/ProgramCompose.scala, negative/AndThenMismatch.scala} (new)
- scripts/{bootstrap.sh, build/compile.sh} (new)
- docs/PLAN.md, AGENTS.md (language policy), docs/prompts/opencode-autonomous-scala.txt (new),
  docs/work/board.md, this record

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| F0 false-green fixed | self-tests: java/python3/both → suite correctly FAILS; self-test exit 0 | session stdout + artifacts/f0-final-*.log |
| F0 final runs | rai-001: 10/10 headless + 25/25 REPL; oai: 9/9; both exit 0 | artifacts/f0-final/{rai-001,oai}/ |
| Product compilation | exit 0, 0 warnings, negative rejected | artifacts/build.log |
| Plan packet validated | validate.py exit 0 (56 cards) | artifacts/plan-review/report.json |

## Commands

| argv | Exit |
| --- | --- |
| sh experiments/repl-spike/run_spike.sh --self-test-fault-injection | 0 |
| sh experiments/openai-chat-spike/run_spike.sh --self-test-fault-injection | 0 |
| RAIDER_SPIKE_EVIDENCE=artifacts/f0-final/rai-001 sh experiments/repl-spike/run_spike.sh | 0 |
| RAIDER_OAI_EVIDENCE=artifacts/f0-final/oai sh experiments/openai-chat-spike/run_spike.sh | 0 |
| sh scripts/bootstrap.sh (actual run after re-review; inventory in artifacts/toolchain/) | 0 | artifacts/f0-final-rereview/bootstrap.log |
| sh scripts/build/compile.sh | 0 | artifacts/f0-final-rereview/build.log |
| both fault-injection self-tests | 0 | artifacts/f0-final-rereview/selftest-{rai001,oai}.log |
| full suite runs (after self-test) | 0 | artifacts/f0-final-rereview/suite-{rai001,oai}.log + {rai-001,oai}/ |

## Limitations / blockers

- Re-review (round 2): all 13 findings were confirmed fixed; its narrow notes
  (bootstrap had not actually run, self-tests were not archived,
  MetadataObserved/incomplete error families, sealing Task, weak negative gate,
  OAI-05 grep) were closed in this same session with reruns — see the updated
  Commands table and artifacts/f0-final-rereview/.
- sbt is not installed (GitHub is slow) — RAI-003 stage B: install sbt with
  brew or use artifacts from Central; until then the canonical build is the
  dotc script.
- Host has JDK 20.0.1; JDK 21 is the target policy (brew openjdk@21), pending.
- modules/core v0 is a contract skeleton; the complete RAI-002 freeze
  (schemas/codecs/fixtures) is not done.

## Review and next task

Review: F0 fixes — re-review pending; core v0 — review together with RAI-002.
Next: docs/prompts/opencode-autonomous-scala.txt (Foundation→F4 waves through W01–W18).
