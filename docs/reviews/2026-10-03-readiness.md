# Independent Raider readiness assessment, 2026-10-03

Verdict: **changes-required** for full acceptance of RAI-001 and SPIKE-OAI-01; product readiness **not implemented**. Basis: reading current files, three independent review tracks, and repeating available local checks. This is a development audit; neither spike's source was changed during the assessment.

## What exists

Detailed design, 53 Raider cards/3 companion cards, contracts, and executor workflow were prepared. Feasibility prototypes exist: three RAI-001 Scala sources with a real compiler/REPL, ZIO jobs, headless process/signal demo, HTTP/JSON probes; additionally a Scala OpenAI-compatible client with mock gateway and six claimed scenarios. There is no `build.sbt`, `Makefile`, `modules/`, product native loop, complete provider adapters, Tool runtime, Program/Task DSL, or installable Raider CLI. `RaiderSpike.start(...)` is not production `worker.start(...)`.

At review time Git had no commits and sources were untracked. Therefore a new prompt must preserve the untracked baseline; a worktree from a nonexistent HEAD is unsuitable. OpenCode in the environment: `1.18.34`, confirmed by `--version` and `run --help` without inference calls.

## What was reproduced

| Check | Observation | Practical conclusion |
| --- | --- | --- |
| Fresh Scala compile | exit 0, three sources in a separate classes directory | Compiler/ZIO/codec candidate really works on the checked host |
| PTY with xterm and isolated JVM home | 25/25, exit 0 | Core REPL feasibility confirmed |
| PTY with inherited TERM=dumb | 22/25, exit 1 | Harness environment is not yet reproducible enough |
| Additional foreground cancellation | After Ctrl+C and prompt, no new stream chunks for 2s | Sample streaming fiber is interrupted, not only the observer |
| Headless normal/SIGTERM/SIGINT | 0/143/130; expected JSON state; no direct child per OS probe | Limited signal/direct-child scenario confirmed |
| JSON probe | 3/3, exit 0 | Basic codec derivation checked |
| Fault-injection launcher | fake java/python3 return 17, entire suite returns 0 | Main runner can report false success |
| HTTP replay | loopback bind forbidden by sandbox | Not reproduced; original evidence is not a new pass |

Original `artifacts/rai-001` were preserved. Independent logs/transcripts/reproducer were copied to `artifacts/readiness-review-2026-10-03/`. Source/evidence hashes and observed results are recorded in [review manifest](2026-10-03-evidence.json). The complete original headless harness hit forbidden `ps`; a separate check probed only its own child PID, without bypassing the sandbox.

## Priority findings

| Severity | Location on reviewed baseline | Scenario / defect | Required correction |
| --- | --- | --- | --- |
| P0 for gate trust | `run_spike.sh:10,29,44,47,70,73` | `set -e` plus producer `| tee` loses producer exit; reproduced all-fail→0 | Propagate actual exit; fault-inject every step; fresh output without stale classes |
| P1 | `repl_pty_harness.py:61–64` | `setdefault` preserves TERM=dumb; JVM history is directed to user's home | Test-owned TERM/capabilities/home/history; separate dumb/noTTY capability tests |
| P1 | `HttpJsonProbe.scala:35–59,85–87` | `sendAsync` lacks cancellation hook; blocking read on compute pool; timeout result discarded, then unbounded await | Before-headers/stalled-body cancellation fixtures, interruptible I/O, bounded acquire/finalization, incremental UTF-8 |
| P1 | `HeadlessSpike.scala:15–18,43–54` | Claimed group cleanup implemented for only one sleep PID | Narrow claim or real process-tree/group implementation with grandchildren/resistant-child tests |
| P1 | `HeadlessSpike.scala:101–113,117–133` | Failure can reach exit 0; artifact-written flag set before successful write | Production error/exit state machine, reporter fault injection; do not copy sample as finished runner |
| P2 | `repl_pty_harness.py:315–324`, original record | Summary saved before final check; original source hashes absent | Full check IDs and immutable run/source/toolchain manifests |
| P2 | `fetch_toolchain.py:112–114,134–137,175` | Resolver skips dependencies/errors and returns 0; post-download hashes are inventory only | Standard pinned resolver/build; dependency lock/verification policy |
| P1 execution plan | Original RAI-018→REPL gate, RAI-016→CI gate | Gate requires components from later dependent cards | Component/stage registry; all end-to-end obligations remain mandatory at milestone |
| P1 execution plan | Original dependency/build→full, CI only RAI-045 | Full run is not yet possible; minimal development CI comes too late | Foundation gates and early CI slice; full final acceptance on one shared build |
| P1 delivery | Old OpenCode prompt/model workflow | Single writer and no handoff/integration-lane protocol | Explicit isolated parallel lanes, shared ownership, coordinator/reviewer, and patch preconditions |

The first seven paths are relative to `experiments/repl-spike/` and its `harness/probe/headless` subdirectories. Source-level HTTP/headless risks are not claimed as reproduced network/failure bugs; corresponding negative fixtures are still needed. Direct child cleanup does not prove group cleanup. Xterm feasibility does not prove a finished terminal renderer or JDK21/Linux compatibility.

## RAI-001 status clarification

Useful feasibility is partially demonstrated, but original scope is not fully accepted. Review requires fixing false-green/evidence/environment issues and tracking remaining obligations. Background output interleaving was already acknowledged in the original record, although original acceptance requires an uncorrupted edit buffer. Only an explicit reviewed scope split is acceptable: the spike proves feasibility; RAI-019 owns production renderer acceptance. JDK21/Linux and stalled HTTP remain separately pending, not passed.

ADR-015 contains candidate decisions with stated proof scope. Claims “HTTP cancellation ready”, “ThreadDeath is cosmetic”, and “process group cleanup proven” cannot be carried into production without additional contract checks.

## Addendum: SPIKE-OAI-01

A new [OpenAI-compatible spike](../../experiments/openai-chat-spike/README.md) was found when rechecking the tree. The saved author artifact `artifacts/oai-spike/summary.json` reports **6/6**: nonstream, fragmented stream, tool-argument assembly, auth error, cancel/disconnect, and REPL. This is useful separate transport feasibility evidence; it does not close RAI-011/012. Independent HTTP replay was not run: loopback bind was previously forbidden in this sandbox. The author's 6/6 is not represented as repeated by the reviewer.

Independently performed: fresh compile of one Scala source into a new directory (**exit 0**) and launcher fault injection: fake `java/python3` with **exit 17 → suite exit 0**, including `OAI SPIKE SUITE COMPLETE`. Logs, exact compile argv, hashes of five source files, and captured author checks are in `artifacts/readiness-review-2026-10-03/openai-addendum/`, indexed in the [review manifest](2026-10-03-evidence.json).

| Severity | Location in `experiments/openai-chat-spike/` | Finding / required acceptance |
| --- | --- | --- |
| P0 for gate trust | `run_spike.sh:24–29` | Producer failure hidden by `tee`; harness stderr discarded, classes shared with old spike. Fix exit propagation, fresh outputs, fault injection for every step |
| P1 | `OpenAiCompatSpike.scala:110–132,189–197,243–257` | Acquire without cancel hook, blocking `readLine`, and await after timeout do not prove bounded cancellation time. Need before-headers, no-newline/stalled-body, and cleanup-deadline fixtures |
| P1 | `OpenAiCompatSpike.scala:159–197,237–241` | Malformed JSON ignored; EOF without confirmed terminal event can yield success. Need terminal-state machine and negative stream fixtures |
| P2 / scope boundary | `OpenAiCompatSpike.scala:33–37,99–108` | Request has no tools/tool-result round trip; URL is always extended with `/v1/chat/completions`. Assembling unsolicited delta does not prove a full tool round or configurable base-prefix contract |

The last three items are source-level findings, not claims of reproduced network failures. Dummy-key argv is suitable only for this fixture; production credentials must come via env/helper without history/log exposure. Blocking cancellation “at line boundaries” must not be carried into runtime as a deadline guarantee. This spike's priority does not remove the target product's requirement for a direct Anthropic-compatible adapter.

## Plan and prompt changes

1. New finish target is [working acceptance W01–W18](../working-product.md), including sessions/context/instructions/SPI/package; the M0 runtime demo is insufficient.
2. [Parallel runbook](../parallel-development.md): coordinator + up to three workers, worktrees or immutable snapshot copies, one owner for shared contracts and integration.
3. [Stage-aware gates](../quality-gates.md): a full product cannot be demanded from the first component diff; milestone requirements remain.
4. [OpenCode finish prompt](../prompts/opencode-finish.txt) first checks current code/evidence, fixes discovered defects, then runs parallel waves toward an actually runnable product.
5. Mocks prove protocol/runtime behavior. Live working scenario and OpenCode comparison retain separate statuses and require configured endpoint/budget.

This adjusts the execution program. It does not mean that listed runtime defects or missing product features were implemented/fixed by this review.
