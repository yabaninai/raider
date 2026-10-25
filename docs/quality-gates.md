<!-- Translated from Russian original. Key terms preserved as-is. -->

# Quality Gates and Evidence

## 1. Status

In this package, gate names, commands, and thresholds are requirements for a future runner. Raider currently has no `Makefile`, sbt build, or implemented gates. RAI-003/004 will create them. Do not run a nonexistent command or record it as passed.

Required philosophy: where a scripted response can reproduce a defect, do so without a real LLM. The model evaluates decisions, while machine checks verify the effect. An executor's summary is not evidence.

Revision dated 2026-10-03: profile applicability is determined by stage and named cases in the protected registry. The clarification below resolves cycles such as “the facade requires a packaged REPL that does not yet exist” and “the process tool requires a future CI runner.” All final requirements remain in force; a deferred check is not considered passed.

## 1a. Activation Stages and Gate Policy Review

| Stage | What component acceptance allows | What cannot be declared ready |
| --- | --- | --- |
| bootstrap | Actual compiler/spike/build probes with exact argv/source/toolchain hash while the runner is being built | full/product suite |
| component | Active named cases for the frozen interface and changed behavior; testkit/local mock is acceptable when checking its own logic | End-to-end capabilities that do not yet exist |
| milestone | All required cases for the target milestone on one integrated build | Missing/not_due/skipped/unavailable required gate |
| working-product | W01–W17, full deterministic suite, real process/PTY/install/task corpus | Working claim based on one SDK/mock demo |
| release | Working plus advertised platform/package/security/integration capabilities | An advertised backend/platform that has not been verified |

RAI-004 creates a reviewed registry: `gate_id`, `profiles`, `activation_stage`, `prerequisites`, `component_scope`, `required_by`, argv/tool prerequisites, expected artifact/check IDs. The stage manifest records selected cases and remaining obligations. `not_due` is a pending obligation with a target stage/owner, not a test pass. An active check with a missing tool or a broken test runner always fails/is unavailable; the stage does not hide the error.

At component stage, `repl` for RAI-018 means compile/facade ownership fixtures; real compiler/PTY cases activate in RAI-019/020 and are required by RAI-026. `ci` for process tools means their headless/env/signal contract fixtures; the complete bundle/context/reports/platform suite is required by RAI-026. Similarly, early eval means scripted loop/verifier cases, while the full task corpus is required by working-product. Docs initially checks links/API compile fixtures; runnable examples activate with the corresponding API. The coordinator and a fresh reviewer record the exact map before assigning a lane, not the worker after a test fails.

A build/dependency/registry change before M0 requires self/static/contracts/security and **all already-active affected cases**; the full product suite that does not yet exist remains pending. After reaching M0, a build change requires full. An unknown path requires a conservative active suite and reviewed classification, not an automatic skip. `make quality-full` is always a strict, overall milestone check: until the product is ready, it cannot be redefined as “only what we can run.”

Gate-timeout calibration (owner decision 2026-10-26, board 2026-10-25 follow-up #4, RAI-209): the `sbt-test` gate timeout in the protected registry was raised from 900 s to 1800 s to absorb host contention from parallel lanes (measured standalone full-suite times on this host are roughly 480–570 s; the 900 s cap produced a host-side kill while `sbt-test` was still running under concurrent lane load). This is contention calibration, not a bar change: no assertion, profile membership, or activation stage changed; a timeout still classifies as failed — never a pass or skip — and the authorized single rerun for a red `sbt-test` is unchanged. The runner self-check (`_self_test_gate_timeouts`) pins every gate timeout and fails the `self` profile on any drift.

Target additional runner commands: `make quality-profile QUALITY_STAGE=component PROFILES="..."` and `make acceptance-working`. Stage/registry evidence is required; these commands are not implemented yet. After foundation, minimal development CI runs active compile/unit/contracts/self/risk cases on every integration; it does not wait for the later RAI-045 packaging matrix. Full gates run again on the integrated candidate after the waves.

## 2. Commands After Runner Implementation

```sh
make quality-fast
make quality-changed
make quality-changed QUALITY_EXECUTE=1
make quality-full
make quality-profile PROFILES="ci contracts runtime"
make quality-integration
make quality-release
python3 scripts/quality/quality.py evidence artifacts/quality/<run-id>/manifest.json --require full
```

`quality-profile PROFILES="..."` runs the union of named profiles from the protected registry; an unknown profile is an error, and self is mandatory. `quality-changed` without EXECUTE only displays the classification. `full` includes local deterministic integration/PTY, but not live paid providers or external Yabanin Docker deployment. `quality-integration` is an explicit external stand gate with prerequisite checks; a missing required stand is failed/unavailable, not a green skip.

PR/CI uses the merge-base ref `QUALITY_BASE`; a clean checkout does not mean “no checks.” A local command without a base accounts for staged/unstaged/untracked/deleted/renamed changes; a clean checkout runs at least fast, and CI always passes a base or full. On the initial commit/no HEAD, the classifier supports an empty baseline tree.

Snapshot lanes may not contain `.git`. RAI-004 implements a second baseline source: an explicit immutable inventory manifest (`QUALITY_BASE_MANIFEST` is the target interface). The diff accounts for add/modify/delete, mode, and symlink target; rename is allowed as delete+add. The baseline manifest hash and before/current source are verified; a missing or mismatched baseline is an error. A clean copy runs active fast; an unknown path runs the conservative active suite. This is the same classifier/evidence contract, not a second reduced runner. Before manifest mode is implemented, bootstrap runs exact scoped checks and saves inventories. The `git status` check is replaced by an explicit `Git unavailable: isolated snapshot` and manifest verification; checks must not be silently run in the root checkout.

## 3. Profiles

| Profile | Contents | Required for |
| --- | --- | --- |
| self | Tests for the quality runner, command registry, fingerprint, and evidence | Any code gate |
| static | scalafmt check, compiler warnings, selected Scalafix rules, forbidden API rules | All Scala/config changes |
| unit | Unit/property tests for core/runtime/parser/config | All behavior changes |
| contracts | Public compile examples, negative type tests, JSON/event/backend schema conformance | Public API/schema/protocol |
| runtime | Cancellation, ownership, races, root budget, queue/mailbox/recovery invariants | Runtime/concurrency |
| transport | Real local HTTP server, fragmented SSE, tool calls, retries/cleanup | Network/providers |
| ci | Non-TTY, signals/exits, bundle isolation, context/trust, artifact/JUnit schemas, adapters | Headless runner/CI/checks |
| repl | Real pinned compiler, PTY signals/paste/completion, terminal rendering | CLI/REPL/compiler |
| tools | Workspace/process/permission tests and mutation preconditions | Tools/policies |
| docs | Links, code examples through compiler/mock runner, command freshness | Docs/examples/public API |
| security | Dependency vulnerabilities, secrets, policy boundary checks, license inventory | Dependencies/auth/tools/process/network |
| compatibility | Native/PI/OpenCode backend contract suites, pinned corpus | Importers/bridges |
| eval | Deterministic protected acceptance verifiers | Agent loop/profiles/model defaults |
| performance | Runtime overhead, background spawn latency, soak/memory/queue bound | Resource/concurrency/performance |
| integration | Yabanin stand, session keys/labels/accounting, optional live bridges | Release integration readiness |
| release | Packaged JVM distribution smoke, clean install, SBOM/notices/platform evidence | Release |

`fast = self + static + scoped unit + minimal contracts`; `full = self/static/unit/contracts/runtime/transport/ci/repl/tools/docs/security/eval + compatibility for enabled adapters + performance smoke`. `release` adds integration for declared supported capabilities. Real LLMs are separate, never implicit in full.

Component-stage registration status (2026-10-16, RAI-135/RAI-137): `eval` is registered
in the component-stage registry and runs the deterministic plan-mode corpus
(`raider.runtime.loop.corpus.PlanScenarioCorpusSpec`, RAI-114) as the
`eval-corpus` gate. The full protected task-corpus eval (RAI-081 fixtures,
hidden verifiers) remains a working-product stage obligation and is NOT
claimed by the component profile. The additive `perf` component profile runs
the UX baseline harness and writes `artifacts/ux-baseline/quality-profile.json`;
it has no thresholds and makes no machine-independent performance claim. The
gate exits 0 only when both latency and start measurements completed. An
unavailable classpath or other documented skip returns 3, writes the report,
and explains the reason on stderr. `--allow-skip` is informational-only for
manual use and is deliberately absent from the gate command. Measurement
failures return nonzero; report-write failures return 2. The separate
`performance` milestone profile remains a broader obligation and is not
implied by `perf`.

Packaging files under `packaging/` map additively to the existing `static` and `contracts` profiles in the quality registry.

## 4. Tools and Versions

RAI-003 pins sbt, Scala, JDK major/build policy, ZIO, and test library versions. Exact formatter/linter/scanner versions and tool downloads are separate from gate execution. `make bootstrap` installs the agreed toolchain; a gate does not hide package installation in the middle of a check.

Static: `sbt scalafmtCheckAll`, `sbt 'scalafixAll --check'` if the pinned syntax is supported, compilation with deprecation/feature/unused checks and fatal warnings on owned code. `scalafixOnCompile` is disabled: a check must not silently rewrite sources.

Forbidden rules: `Thread.stop`, unbounded `Await.result`, global unsafe runtime launch outside the sanctioned facade, broad catch suppressing interruption, unbounded queues in runtime, Double money arithmetic, raw `Runtime.exec`/shell concatenation outside the process adapter, secret logging. Rules are tied to actual risks; permitted low-level exceptions are narrow and documented.

Security dependency scanning: pinned OWASP Dependency-Check or another verified working JVM scanner over the resolved classpath/build plugins; RAI-004 will choose the precise analyzer/tool. A database/scanner error is not passed. SBOM/attributions include runtime and compiler/JLine transitive artifacts. CVE false positives receive a bounded owner-approved exception with reason/expiry; the model does not create its own exemption.

Agree on the license policy proposal before distribution: permitted SPDX expressions + notices inventory. Do not automatically inherit the product license from pi/OpenCode. Copied upstream artifacts retain license/origin/commit/hash.

## 5. Evidence Manifest

Minimum fields:

```json
{
  "schema_version": 1,
  "run_id": "qg-example",
  "status": "passed",
  "source": {"commit": "optional-on-initial-repo", "sha256": "..."},
  "policy_sha256": "...",
  "toolchain": {"scala": "pinned", "jdk": "pinned", "sbt": "pinned"},
  "profiles": ["static", "unit"],
  "gates": [
    {"id": "compile", "argv": ["sbt", "compile"], "exit_code": 0,
     "status": "passed", "log": "compile.log", "log_sha256": "..."}
  ],
  "test_summary": {"passed": 0, "failed": 0, "skipped": 0},
  "started_at": "...",
  "finished_at": "..."
}
```

This JSON is schematic, not real evidence. The runner fills in numbers from machine-readable results.

Fingerprint: tracked + nonignored untracked product source/config/specs/prompts, deletion/path/mode/symlink target; exclude `.git`, build outputs, artifacts, and runtime `.raider` state under protected policy. Exact operational allowlist: `docs/work/board.md`, `docs/work/records/**`, `docs/work/assignments/**`; their hashes/revisions are preserved as audit metadata and checked separately by docs lint. Do not put executable fixtures/config there or expand the exclusion to all docs. Exact resolved dependency inventory/build/toolchain hash is stored separately. Source snapshot lane and integrated candidate are distinct; a foreign manifest is not rebound to a new hash.

Different before/after hashes → stale. A change to the command registry, threshold policy, fixture corpus, or log → evidence invalid. `evidence --require PROFILE` checks status, completeness, required gates, current hashes, and logs; fast cannot be presented as full.

The runner executes argv from the protected registry without a shell. A fixture/task card cannot expand the registry or insert a command such as `curl ... | sh`. Timeout/interrupt still saves a partial report, with an overall nonzero exit; SIGKILL leaves the run as running, which is not accepted as success. Evidence write failure cannot produce a green result.

## 6. Change Matrix

| Change | Minimum profiles |
| --- | --- |
| Core/DSL/public API | static/unit/contracts/docs |
| Runtime/jobs/budgets/mailbox | static/unit/contracts/runtime/eval |
| Provider/HTTP/SSE | static/unit/contracts/transport/security |
| Headless CLI/bundle/CI/checks/reporters | static/unit/contracts/ci/runtime/docs/security |
| REPL/CLI/JLine/compiler integration | static/unit/contracts/repl/docs/security |
| Tools/workspaces/process | static/unit/tools/runtime/security |
| Yabanin adapter | static/unit/contracts/transport/security + integration acceptance |
| Dependency/build/runner/policy | Before M0: all active affected cases + security/self/contracts; after M0: full. Independent gate-policy review is mandatory |
| Agent prompt/model-role defaults | docs/eval/compatibility; paid comparison only when explicit |
| Docs with DSL examples | docs/contracts; selected repl as well after REPL implementation |
| Unknown path/file type | Conservative active suite + reviewed classification; full at milestone. No skip |

Classification is a lower bound. The semantic risk of a card may add profiles. There is no rule like “change only the README to bypass eval”: prompts/configuration are classified separately.

## 7. Required Runtime Invariants

Each scenario has a short test ID and deterministic fixture:

1. Task construction/map/printing does not call a provider/tool.
2. Two `run(task)` calls produce two roots; two `await(job)` calls do not repeat effects.
3. Concurrent waiters receive one consistent outcome.
4. Parent cancellation interrupts the subtree and clears reservations/permits.
5. Cancellation during spawn registration leaves no orphan.
6. Success/cancel race produces one terminal transition.
7. maxConcurrentLlm=1 + waiting parent does not deadlock.
8. Total root attempts/children/depth stay within caps during fanout.
9. Reservation/settlement are correct on failure, unknown usage, and overflow.
10. Deadline cancels queue wait and retry sleep; cleanup is bounded.
11. Output repair counts as an attempt and is not infinite.
12. Partial streaming tool args are never executed.
13. Message consume/cancel/finish race does not lose the receipt.
14. Scope exit cancels unjoined children; `all/batch` waits for results.
15. Slow observers do not block critical state/accounting; queues are bounded.
16. An external backend without cancellation acknowledgement is not falsely marked Cancelled.
17. Reload does not change the prompt/tools of a running job.
18. Crash archive does not replay a mutation automatically.
19. Parent/child capabilities only narrow.
20. Journal error prevents a false durable-success claim.

Property/state-machine tests generate start/fork/send/cancel/join/fail/close sequences. A virtual clock controls backoff/deadline; real sleeps are prohibited in unit tests except explicit platform/PTY tests. Deterministic ordering uses promises/barriers, not “wait 100ms.”

## 8. Real REPL Acceptance Tests

PTY tests launch packaged `raider repl --mock` as a process with a terminal, send input/signals, and check transcript/state:

- preset agent `.ask` → usable result without creating an agent;
- `.start` for two jobs → the next `val` executes before the jobs finish;
- `(scout andThen reviewer).ask` → typed stages in the correct order;
- `all` for Programs/Tasks, `batch` → tuple/order/concurrency preserved;
- compile error → previously created binding remains;
- runtime error → prompt recovers, runtime stays alive;
- multiline/paste/Unicode/quoted path;
- completion of main symbols;
- foreground Ctrl+C cancels children;
- Ctrl+C while awaiting an existing job leaves the job running;
- watch Ctrl+C unsubscribes the observer;
- idle Ctrl+C clears the line;
- EOF/:exit/reset clean shutdown;
- background output does not corrupt the edit buffer;
- stdout JSON contains no ANSI/logs/secrets;
- load file/register/run and the same file through CLI;
- pretty-printing Job/Task does not trigger the effect or reveal a credential;
- bare Task prints the `.run()/.start()` hint;
- dot/infix syntax use one launch implementation.

Mock `Interpreter.evaluate` is useful for unit tests, but does not replace these tests. The PTY helper is selected by RAI-001; a small Python stdlib helper on Unix is allowed, with platform-specific Windows coverage separate. macOS+Linux are required for v1; declare Windows supported only after a real suite.

## 9. Coverage, Mutation, and Test Quality

Initial release thresholds: overall statements >=80%, core/runtime/provider parser >=90%, critical branch cases covered through the explicit invariant matrix. Coverage does not replace invariant checks and does not require pointless DTO/getter tests.

Coverage for the compiler module is separate: the adapter may have a smaller instrumentable fraction, but PTY/compile acceptance is required. Exceptions for generated/external code are exact and reviewed. Do not change the threshold along with an implementation fix.

Selected mutation probes for budget/cancellation/protocol/policy: remove permit decrement; replace unknown cost with zero; execute partial args; allow a child to broaden permission; preserve state as success after failure. The suite must catch every such mutation. Do not introduce a heavyweight general mutation framework before its value is proven.

Tests that only assert implementation structure or mock the sole effect being checked are not sufficient acceptance. A regression test first reproduces the bug, then checks the resulting behavior.

## 10. Performance and UX Budgets

Initial target acceptance budgets on the declared reference machine, after warm-up:

- native background submission p95 <=100ms, excluding Scala compilation/network queue execution;
- idle prompt remains editable with 20 streaming mock jobs;
- mock native cancellation acknowledgement p95 <=500ms, with process/network grace measured separately;
- 1,000 short mock jobs in bounded batches leave no registry/fibers/processes behind; memory stabilizes at baseline after cleanup;
- journal flush/slow renderer do not create unbounded backlog;
- REPL cold start is measured and reported, initial target <=5s packaged warm-cache, dependency downloads excluded and reported separately.

RAI-001/043 measure feasibility and load. Any budget change requires a recorded decision with measurements; an executor must not weaken the threshold to pass. Do not use flaky performance checks as unit assertions on arbitrary shared CI: use a benchmark lane or relative regression against a calibrated baseline, preserving machine/JDK/load metadata.

## 11. Agent Quality Eval

Initial set: at least 12 deterministic mock cases for loop/protocol/tools and 6 small real task fixtures (repo search, structured extraction, safe edit, test-driven bugfix, review with planted bug, parallel synthesis).

Protected verifier is outside the mutable agent workspace. For coding: hidden regression tests and diff constraints; for research: source presence/consistency checks and a manual rubric for conclusions that cannot be formalized. Safety/protocol hard gates are separate from usefulness score.

Live model comparison: same tasks/tools/budgets/seeds where supported, multiple repetitions, cost/latency/uncertainty recorded. At least 3 repetitions for a pilot; claims of stable superiority require a sufficient sample/CI, not one good response. Do not include eval holdout in agent context.

## 12. Product CI and Development CI

The product `ci` profile runs all 12 acceptance clauses in [CI runtime](ci-runtime.md), including noTTY/SIGTERM/fork trust and artifact-failure precedence. Direct transport acceptance does not run Yabanin: environment without YABANIN_* and both mock HTTP wires. Mixed-provider children share the root budget; cross-protocol history/tool IDs are preserved. Package smoke checks that compiler/JLine are absent from the headless classpath, network dependency resolution, and container PID1 signal forwarding.

Raider's own development CI:

PR: pinned toolchain, format/compile/unit/contracts + risk profiles, no secrets on fork PR. Main/nightly: full on Linux, REPL/package smoke on macOS, dependency scan. Release: full + declared optional integrations (Yabanin readiness requires a pinned stand) + SBOM/notices + clean install + signed/checksummed distribution under release policy.

CI cannot be declared working because YAML exists. Verify jobs on a clean checkout and save logs/artifacts. Cache speeds up builds; it does not preserve a pass without a fingerprint.

Reviewer: inspect the actual diff, contract, negative cases, logs/manifest, and tests on the required version. A task is done only when card acceptance + gates have passed. A blocker lists the exact missing dependency; skipped profiles remain pending.
