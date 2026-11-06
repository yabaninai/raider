<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider parallel development

The owner explicitly authorized parallel development on 2026-10-03. This
runbook applies to agents developing Raider. Raider product subagents are
checked separately against runtime scopes/workspaces. Goal: [working product](working-product.md).

## 1. Roles and concurrency limit

Owner decision 2026-10-16 (supersedes the earlier "up to three implementers"
default and the 2026-10-12 "8–10 active workers" ceiling for agent count):
capacity is the primary coordinator session plus up to 20 spawned Luna
agents. The primary session remains the single coordinator/integrator and
sole owner of the integration checkout; spawned agents may lead heavy
worktree lanes under its assignment packets. Agent count is no longer the
binding limit — build capacity is: at most 8–10 concurrent heavy
(sbt-running) lanes on 18 cores / 128 GB, with light lanes (docs, contracts,
review, research, fixture preparation without full builds) filling the
remaining slots. A reviewer is always available; do not occupy all writer
slots at the cost of review. Reduce heavy concurrency when RAM/CPU degrade,
and split a large card before coding.

The coordinator solely owns the integration checkout, shared `build.sbt`/`project/**`,
public core/DSL signatures, gate registry/thresholds, dependency graph, board,
and final acceptance. A separately assigned owner may change these files while
the coordinator prevents competing edits. A worker gets a feature module/specific
files and tests. A reviewer checks a different diff in a fresh context; authors
do not review their own implementation.

## 1a. Worktree mode (owner decision 2026-10-12: aggressive parallelism, at least 8 lanes)

The owner explicitly authorized 8+ parallel lanes through **git worktrees**
(instead of snapshot copies). Worktree mode is primary; snapshot mode in §2 is
the fallback when Git is unavailable.

- Preparation: coordinator commits a clean tree to `main`; for each lane:
  `git worktree add <WT-ROOT>/raider-wt-<N> -b lane/wXX-r<N> main`. Each
  worktree has its own `.git` (a pointer file), its own `target/`, and a
  separate branch. Copy `artifacts/tools/m2` into the worktree (untracked;
  the bootstrap-SSL environment is pre-existing).
- Worker works ONLY in its own worktree/branch. Committing to its OWN branch is
  allowed and required (deliverable = branch + RECORD.md); pushing is
  prohibited; `main` and shared files (`build.sbt`/`project/**`, board,
  `docs/tasks/**`, `docs/adr/**`, `scripts/**`, `.gitignore`) belong to the
  coordinator only.
- In worktrees, gates use git mode (`make quality-changed` without
  QUALITY_BASE_MANIFEST classifies changes vs branch HEAD). No sbtlock is
  needed (isolated `target/`); the shared coursier cache is concurrency-safe.
- Integration: coordinator merges lane branches into `main` sequentially
  (`git merge --no-ff`), resolves conflicts, and runs full gates on main after
  each merge batch. Green lane ≠ final acceptance: main gates are required.
- REVIEW lane occupies one worktree slot (read-only, verdict file).
- Limit (owner decision 2026-10-16): the primary coordinator session plus up
  to 20 spawned Luna agents may be active, but concurrent heavy lanes that
  run sbt/full gates stay capped at 8–10 (empirically stable on 18 cores /
  128 GB; gate timeouts are load-calibrated). Additional capacity goes to
  light lanes, immediate per-lane reviewers, and queued heavy lanes that
  start as build slots free; it does not lift the build cap or invent ready
  cards.

## 1b. Visible spawn agents (owner decision 2026-10-12 — primary execution mode)

Work runs as **visible spawned subagents in the owner's interactive session**,
not as background processes. The owner opens OpenCode, enters `/raider-wave`
(or `/raider-wave <IDs>`), and sees each lane as an agent in the TUI in real time.

- Coordinator is the primary session with the owner. Workers are `raider-worker`
  subagents and the reviewer is `raider-reviewer` (`.opencode/agents/*.md`);
  dispatch is `.opencode/command/raider-wave.md`. Spawn using the task tool.
  Worker/reviewer routing uses the configured Luna agent pool (owner decision
  2026-10-16, replacing the earlier worker provider routing). Record routing
  in the board when it deviates from the default.
- Isolation in §1a is not weakened: the worker receives an ABSOLUTE worktree
  path + branch in the assignment packet and directs every tool call there.
  Default worktree is `wt/<card-id>` inside the repository (gitignored), so the
  spawned agent remains within project dir and its permissions; external
  `<WT-ROOT>` is allowed only if the owner explicitly configures external_directory allow.
- A child session is not itself a worktree — isolation comes from the worktree
  + branch + absolute paths, not the fact of spawning. All §1a rules (branch
  per lane, no worker push/merge, sequential coordinator merge, main gates after
  a batch) remain in force.
- Prohibited by default: executing cards in background bash loops using
  `opencode run`/`nohup` (night loop in docs/prompts/opencode-1k-continuation.txt).
  Headless night mode requires a separate explicit owner decision, recorded in
  the board with a reason; it is not used by default.
- Lane report = subagent's final message (changed paths, commands/exits,
  evidence, risks) in the agent deliverable format; coordinator integrates and
  updates records/board. Do not require the owner to read logs to learn lane
  status: it is visible in the TUI and coordinator report.
- Fallback, in order: (1) task tool unavailable in the installed version, or a
  provider/pool failure kills spawns mid-work (wave-3 and RAI-121 incidents)
  → workers execute sequentially in the primary session, review in parallel
  when the pool recovers, preserving uncommitted lane work; (2) separate
  explicit owner decision for the night loop. Record the fallback reason in the
  board. Do not invent API fields (`cwd` on task, etc.).

## 1c. Lane build hygiene: the zinc cold-compile poison marker (RAI-241)

Measured source: `docs/research/zinc-concurrency-2026-10-28.md` (RAI-241
experiment; §7 recommended mitigation adopted here as lane hygiene; §6
falsifiable hypothesis H1). This section changes no gates and no product code;
it tells a lane what a specific cold-compile failure means and what to do.

**Recognition.** During a cold compile, the signature
`value id is not a member of raider.core.ToolCall` at `AgentLoop.scala:772:7`
(a fast-failing `[E008] Not Found Error` in
`raiderRuntime / Compile / compileIncremental`; all reproductions fail fast,
7–80 s) is a known copy-local zinc infra-poison marker — not a product error
and not a broken worktree. Sources and emitted artifacts in the affected copy
stay byte-identical to healthy copies: the wrong signature lives in what the
downstream compile consumes from that copy's persisted raiderCore zinc state.
The poison is copy-local by measurement — it is not in any shared host cache.

**Recovery (verified).** In the affected copy only:

```sh
sbt --batch raiderCore/clean   # then recompile, e.g. sbt --batch Test/compile
```

Verified heal in 57 s; full `clean` also works. No global caches need clearing.
The lane is healthy after the heal reruns its phase; do not re-clone or
re-create the worktree for this.

**What NOT to do, and why (per the measured evidence).**

- Do not retry blind. The poison is deterministic once induced: retry without
  cleaning always re-fails (observed 3/3), because a failed runtime compile
  commits no zinc state, so the retry re-reads the same poisoned analysis. A
  blind retry never goes green; it only burns a full compile each time.
- Do not "fix" it by nuking global caches (`~/.sbt` global plugins, boot/ivy,
  coursier). Induction reproduced with per-copy sbt global/boot/ivy state (I1),
  so shared global state is not the carrier; clearing global caches is slow,
  disrupts every other lane on the host, and does not touch the copy-local
  carrier (the affected copy's raiderCore target).

**Overlapping cold compiles across worktree lanes.** Staggering and state
isolation did not prevent induction, and host load magnitude is not the
discriminator: the common factor of every reproduction was overlapping cold
compiles of the raider tree on one host, from any lanes. Assume any cold
compile overlapping other lanes' cold compiles carries ~12% per-copy induction
risk (4 poisons in 33 controlled copy-compiles; single-day, single-host
sample). Guidance:

- The §1 heavy-lane cap does not make induction impossible. With 8–10 heavy
  lanes cold-building, treat one copy hitting the marker as expected chance,
  not as a lane defect or a regression: recognize, heal in place, rerun the
  phase. Do not open a product-defect card for it and do not report the lane
  as product-broken.
- The ~12% figure is exposure-risk, not a per-retry loop budget: after the
  marker appears, clean and heal; never loop retries while other lanes are
  still cold-building.
- If you choose to diagnose before healing, follow the RAI-241 harness order
  (preserve the poisoned state tar → exit-coded retry → clean → heal); once
  you `clean`, the evidence state is gone. Otherwise, just clean and move on.
- A heal can itself be re-poisoned while sibling lanes are still cold-building
  (observed once, T8s/c08). If the heal re-fails with the same signature, wait
  out the overlap window and re-apply the heal.

## 1d. Lane build hygiene: JDK/toolchain pin for lanes and gates (RAI-264)

Measured source: RAI-254 lane evidence (work record `docs/work/records/RAI-254.md`;
gate manifests `20261101-044235-92625-0001` failed and `20261101-044855-95050-0001`
passed under `wt/rai-254/artifacts/quality/`). This section changes no gates and no
product code; it tells a lane which JDK to run under and how this cold-compile
failure is distinct from the §1c zinc poison.

**The pin (required).** Every lane shell and every gate run executes sbt under
**JDK 21**:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

Set both before the first `sbt`/`make quality-*` call in the worktree. Homebrew
`openjdk` on this host is currently **JDK 27**, and an unset `JAVA_HOME` lets the
sbt launcher resolve it even when PATH `java` is 21: the failed RAI-254 gate run
recorded `toolchain.java` = Oracle `21.0.12.1` in its manifest while the same run's
sbt banner read `welcome to sbt 1.11.4 (Homebrew Java 27)`. Verify the sbt banner,
not `java -version` alone.

**Recognition.** A **cold** `raiderCli / Test` compile under JDK 27 fails
deterministically: dotc 3.9.0 cannot resolve the `java.lang.invoke.VarHandleDoubles`
inner-class entries while parsing JDK 27's `VarHandle.class` from the JRT (the
LazyVals phase pulls `java.lang.invoke.VarHandle`). Verbatim from the failed
manifest's `sbt-test.log`:

```
[error] ## Exception when compiling 94 sources to /Users/reireirei/PycharmProjects/PythonProject/startup/scalaagent/wt/rai-254/modules/cli/target/scala-3.9.0/test-classes
[error] java.lang.AssertionError: assertion failed: failure to resolve inner class:
[error] externalName = java.lang.invoke.VarHandleDoubles$FieldStaticReadOnly,
[error] outerName = java.lang.invoke.VarHandleDoubles,
[error] innerName = FieldStaticReadOnly
[error] owner.fullName = java.lang.invoke.VarHandleDoubles
[error] while parsing /modules/java.base/java/lang/invoke/VarHandle.class
```

Frames run `dotty.tools.dotc.core.classfile.ClassfileParser$innerClasses$.classSymbol`
→ `dotty.tools.dotc.transform.LazyVals`. The same fingerprint passes green when the
run is repeated under the JDK 21 pin (manifest `20261101-044855-95050-0001`:
5 checks passed, 1722 tests / 0 failed / 0 ignored, banner `Oracle Corporation
Java 21.0.12.1`). This is a known-infra marker, not a product error and not a
broken worktree.

**Distinct from the §1c zinc poison.** Do not conflate the two cold-compile
markers:

| | §1c zinc E008 poison | JDK 27 dotc crash (this section) |
| --- | --- | --- |
| Signature | `[E008] Not Found Error`, `value id is not a member of raider.core.ToolCall` at `AgentLoop.scala:772:7` | `AssertionError: failure to resolve inner class: VarHandleDoubles$FieldStaticReadOnly` |
| Carrier | copy-local persisted zinc analysis | the JDK the sbt JVM runs under |
| Does `sbt --batch raiderCore/clean` heal? | **Yes** (regenerate the analysis; recompile green, 57–65 s) | **No** — a clean recompile under JDK 27 re-crashes deterministically |
| Remedy | clean the affected copy and recompile | pin `JAVA_HOME` to JDK 21 and rerun; no cleaning |

The zinc study's "clean heals, retry never heals" logic
(`docs/research/zinc-concurrency-2026-10-28.md` §12: plain retry 6/6, incremental
recompilation does not heal, clean+recompile heal ~65 s) does not transfer here:
there is no poisoned state to regenerate — sources and zinc state are fine, the
compiler/JDK pair is not.

**What NOT to do, and why (per the measured evidence).**

- Do not re-run `raiderCore/clean` (or `clean`) and retry the compile under
  JDK 27. The crash is deterministic on any cold recompile under that JDK;
  cleaning burns a full compile and returns the same AssertionError. The remedy
  is the JDK pin, not cleaning.
- Do not diagnose it as the §1c zinc poison, run §1c's heal for it, or open a
  zinc/product-defect card: no zinc state is corrupted and the E008 signature is
  absent. Match the marker first, then apply the matching remedy.
- Do not read a green warm-zinc JDK 27 run as evidence that JDK 27 is safe:
  RAI-254's review-fixup run passed under Homebrew Java 27 only because zinc was
  warm (no cli test sources were recompiled; manifest `20261101-053427-13638-0001`).
  Only a cold compile under the pin (or a pinned rerun after this crash) is
  evidence.
- Do not "fix" it from your lane by upgrading dotc/Scala or hard-pinning
  `build.sbt`: shared build changes are coordinator-owned and need a separate
  card; this section is the hygiene rule, not a build change.

**Lanes racing cold compiles under mixed JDKs on one host.** Zinc state is
copy-local (§1a: each worktree has its own `target/`), so a JDK 27 lane crashes
only its own copy and does not corrupt sibling lanes. Guidance:

- Pin per shell, per lane, before the first sbt call. A lane that starts unpinned
  on a host with Homebrew JDK 27 will crash its own cold compile even while
  sibling lanes run pinned and green; the reverse does not hold (a pinned lane is
  unaffected by an unpinned sibling's crash).
- A lane observing this crash in a sibling must not run §1c's `raiderCore/clean`
  on the sibling's behalf (or on its own copy): §1c's heal belongs to the E008
  marker only. The crashing lane pins the JDK and reruns its own phase.
- The pin does not remove the §1c overlap risk: concurrent cold compiles under
  JDK 21 still carry the ~12% per-copy E008 induction risk. Both hygiene rules
  coexist: pin the JDK; if the E008 marker appears, clean and heal in place per
  §1c.
- Under the §1 heavy-lane cap, treat one lane's dotc crash during a wave as the
  expected unpinned-start case, not a lane defect or regression: check the sbt
  banner's Java version first, pin, rerun the phase.

## 1e. Test execution stance: suites run serially inside each module (RAI-266)

Decision (card RAI-266, routing RAI-247 review finding P3-1): `build.sbt` sets
`Test / parallelExecution := false` in the shared `testSettings`. zio-test suites
inside one module execute strictly one-after-another; module-level overlap stays
capped at 2 by the `Global / concurrentRestrictions` `Tags.Test` limit. No gate,
check, or threshold is relaxed.

Why free suite concurrency is not allowed: suites share module-local fixed paths
under `target/`. The known pair is `raider.cli.lineage.LineageCompileGateSpec`
and `raider.cli.lineage.UnifiedProjectionExecutionSpec`, which both reuse
`modules/cli/target/lineage-compile/` (`esc-hostile.scala`,
`esc-hostile.dotc.log`, `out-esc-hostile/`), and a suite's `freshDir` deletes
directory contents on entry — concurrent execution is a latent interference
source, not an allowed feature. Per-module serialization removes that collision
class structurally.

Update (card RAI-272): the pinned-dotc suites no longer share fixed working
dirs. `raider.cli.lineage.LineageCompileGateSpec`,
`raider.cli.lineage.UnifiedProjectionExecutionSpec`,
`raider.cli.lineage.LineageProjectionExecutionSpec`,
`raider.cli.lineage.LineagePlainRunCompileGateSpec` and
`raider.cli.workflow.ChatWorkflowCompileGateSpec` each write into their own
`modules/cli/target/suite-work/<Suite>/` through the test-scope helper
`raider.cli.testkit.SuiteWorkdir` (wiped and recreated per test JVM); the
shared `lineage-compile/` and single-suite `chat-workflow-compile/` dirs are
no longer written. The remaining fixed paths are single-writer and safe
without migration: the per-suite runtime dirs
(`modules/runtime/target/lineage-replay-w22-r8`, `lineage-replay-spec`,
`lineage-corpus-spec`, `plan-corpus-spec`), the deliberate
`modules/cli/target/demo-bundle.jar` export for `scripts/quality/cli_smoke.sh`
(single writer `HeadlessRunnerSpec`), and every `Files.createTempDirectory`
user (a fresh unique directory per call). The stance itself stayed `false`
until RAI-274 re-evaluated it from this clean baseline (see below).

Measured evidence (work record `docs/work/records/RAI-266.md`):

- Probe, default stance: two minimal zio suites exchanged real-time file
  markers under verified-fresh state; the sibling marker appeared at
  **+111 ms** (A written at epoch-ms 1793594612407, B at 1793594612518) —
  suites genuinely run concurrently without the setting.
- Probe, `Test / parallelExecution := false`: strict A-then-B. A waited for a
  marker that could only appear after A's own task returned and failed at
  +20 s; B was written at **+25.2 s** (A at epoch-ms 1793594637580, B at
  1793594662748), after A's task had completed — sbt executed the framework
  tasks sequentially.
- Full `sbt --batch test`: **700 s** (0:11:40) before the change vs **680 s**
  (0:11:20) after, both green, exit 0 — suite serialization is effectively
  free because compiler-heavy suites saturate CPU and the `Tags.Test` cap
  already limits module-level overlap.

Re-evaluation (card RAI-274, work record `docs/work/records/RAI-274.md`):
**the serial stance stays.** RAI-272 removed the hazard class that motivated
the pin, but the probe shows the stance is still load-bearing and the flip
buys nothing measured:

- The stance surface is two mechanisms, not one. `Test / parallelExecution`
  governs suite concurrency only in **in-process** modules (core, runtime,
  dsl, testkit, ui, tools, providers). Three modules fork their tests
  (`Test / fork := true`: raiderCli per RAI-109, raiderRepl,
  raiderReplEngine); there sbt puts all suites into one `SubProcess` group
  and runs them sequentially inside the fork (default
  `Test / testForkedParallel := false`), so flipping `parallelExecution` is
  inert for them.
- RAI-266-style marker probe (temporary suites, verified-clean marker state
  before every run; `Test / parallelExecution := true` applied per module via
  a session `set`): raiderCore flip → suites genuinely overlap, sibling
  markers −1 ms apart (A at epoch-ms 1793838203445, B at 1793838203444, both
  passed); raiderCore pin → strict A-then-B, A failed its 20 s wait (A at
  1793838357875, B at 1793838378034, +20.02 s). raiderCli flip → still
  strictly sequential (A at 1793838107358, B at 1793838127488, +20.01 s, A
  failed its wait as designed); raiderCli pin → same serial signature
  (1793838328373 → 1793838348510, +20.14 s).
- Measured full `sbt --batch test` on the same tree (1824 tests, both green,
  exit 0): serial **595 s** (sbt Total 592 s) vs flipped **637 s** (sbt Total
  632 s) — flipping adds contention, not throughput, consistent with the
  RAI-266 measurement above.
- Under the flip the migrated suite set showed no cross-suite interference
  (the killed-first flipped attempt ran 938 tests green before an external
  SIGTERM; the completed flipped run passed 1824/1824 and the suite-work
  audit afterwards found only per-suite directories under
  `modules/cli/target/suite-work/`, with `lineage-compile/` and
  `chat-workflow-compile/` not recreated). Interference-freedom is not the
  reason to keep serial — the reason is that free suite concurrency buys no
  runtime and removes a structural protection for future suites.

Lane guidance: do not "fix" slow suites by re-enabling `parallelExecution`;
make suites independent of module-local fixed paths instead (unique per-suite
subdirectories under `target/` — landed for the pinned-dotc suites by
RAI-272; new suites must not reintroduce cross-suite fixed paths: put working
files in a per-suite `suite-work/<Suite>/` directory or a unique temp
directory). Re-enabling `parallelExecution` would not even parallelize the
forked modules (cli/repl/repl-engine); that would additionally require
`Test / testForkedParallel := true`, which no card has proposed or measured.
When timing test
runs, keep the §1d JDK pin in the shell, and expect sibling-lane sbt activity
to add wall-clock noise (both measurements above were taken with two sibling
lanes compiling on the same host).

## 2. Prove isolation first

Before starting writers, record Git state, installed OpenCode version,
available task/subagent tools, cwd, and baseline fingerprint. At the time of
the audit, the repository was unborn and all sources untracked; a normal
worktree from HEAD was not possible.

If a suitable immutable commit exists and Git is writable: create a separate
worktree/branch per lane. Do not hide dirty user files with stash/reset or
include them in an arbitrary initial commit. If the needed baseline includes
uncommitted work, use the snapshot path.

Fallback without commits or `.git` write access: create an immutable source
snapshot and a separate lane copy in an allowed scratch directory. The manifest
includes all selected source/config/docs/scripts, including untracked files,
with relative paths/modes/hashes/symlink targets. Exclude `.git`, `.env*`/credentials,
runtime state, outputs/caches, and raw evidence; explicitly allowlist config
examples. Do not hardlink mutable source. A symlink outside the snapshot must
not become a way to read secrets; listed immutable toolchain artifacts may be
used separately read-only.

A snapshot copy without `.git` is a fully supported lane mode. Worker records
`Git unavailable: isolated snapshot`, checks the root-provided base manifest
and cwd; missing Git does not force a return to the shared checkout. RAI-004
must support diffing before/after inventories (path/hash/mode/symlink/add/delete;
rename may be delete+add), for example through the planned `QUALITY_BASE_MANIFEST`.
Until this mode is implemented, use explicit scoped bootstrap commands with
the same manifest. Manifest mode checks base/current hashes, active path
classification, and at least active fast when nothing has changed; it does not
skip checks. Implement and test the flag before using it in reports.

Worktree/copy path is absolute and passed to every tool call. Before work,
worker proves its cwd and baseline; coordinator confirms output/build directories
differ. An OpenCode child session alone does not create a worktree. The primary
lane binding mode is a visible spawned subagent (§1b): isolation comes from the
worktree + branch + absolute paths in the packet. A separate OpenCode process
with `--dir <lane>` is a last-resort fallback (only if task tool is unavailable,
with reason recorded in the board); do not invent a task API `cwd` parameter
if the installed version lacks one.

OpenCode 1.18.34 was confirmed in the audited environment by `--version`;
`run --help` supports `--dir`, `--agent`, `--file`. Recheck the version before
new runs. Command/agent schemas may differ across versions. Docs: [CLI](https://opencode.ai/docs/cli/),
[agents](https://opencode.ai/docs/agents/).

If isolation is temporarily unavailable, writers work sequentially while
research/review remain parallel; record the reason in the board. Do not call
multiple agents writing the root checkout at the same time parallel lane work.

## 3. Assignment packet

Coordinator gives each worker:

```text
Assignment / parent card / lane:
Absolute workspace / baseline snapshot hash / source inventory:
Implementation prerequisites / later integration prerequisites:
Frozen public symbols + exact signatures + schema versions:
Allowed files and tests; shared files forbidden:
Normal/error/cancel acceptance IDs:
Required active gate cases / milestone obligations still pending:
Expected deliverable (patch + before/after hashes + evidence):
Heartbeat / bounded task size / escalation condition:
```

Do not pass the entire multi-thousand-line plan in each coding task. Required
invariants relevant to the change go in the packet. A worker must not present
an absent signature as agreed: on discrepancy, return a reproducer and minimal
contract-change request.

## 4. Practical waves

| Wave | Lane A | Lane B | Lane C | Integration condition |
| --- | --- | --- | --- | --- |
| F0 | Spike fixes/reproduction | Read-only contracts/gate review | Acceptance fixtures/design | Truthful exits/evidence; frozen bootstrap decisions |
| Foundation | Build/toolchain owner | Public contracts owner after build skeleton | Gate runner/testkit/minimal CI after interface freeze | Shared compile/contract tests, stage registry |
| F1 | Jobs/scopes/budgets/native loop | OpenAI+Anthropic protocol slices | Filesystem/process tools | One native tool loop through HTTP fixture |
| F2 | DSL/runtime integration | REPL/terminal adapter | Headless CI/reports | One Program works in REPL and CI |
| F3 | Sessions/context/journal | Typed tools/SPI/examples | Packaging/task fixtures | Daily coding workflow from installed package |
| F4 | Fixes for found defects | Independent reviewer/verification | Gateway baseline / optional enriched integration | W01–W17 on one candidate |

The table lists lanes; it does not authorize starting dependent work before
signatures are available. Two provider wires can be split across workers when
slots are available; shared HTTP/SSE framing has one owner. Root or an
exclusively assigned owner changes contracts; then all affected lanes get a
new frozen baseline.

## 5. Worker responsibilities

Check assignment/cwd/source hashes, write a behavioral fixture, implement
narrow behavior, run local gate cases, and prepare the deliverable. Tools/tests
run inside the lane; allocate ports dynamically and use unique temp paths.
`target/`, compiler state, generated files, and reports are not shared. Share
dependency-download caches only with supported concurrency/lock semantics; if
uncertain, use a separate cache or bootstrap first.

Worker does not edit the central board/other lanes; sends status and record to
coordinator. Result includes base hash, add/modify/delete/rename inventory,
patch/binary payload where needed, resulting source hash, test argv/exits, log
hashes, and unresolved findings. Do not copy the whole lane directory over root.

## 6. Integration and conflicts

Coordinator checks paths/secret exclusions/base hashes, reads the diff, gets
independent review, and applies changes sequentially. If a target path changed
since baseline, do a meaningful merge/rebase and rerun affected tests; never
force-overwrite. Preserve user changes.

After each integration batch: shared compile/contracts + affected active gates;
after each wave, a runnable demo from the integrated tree. Lane evidence proves
its baseline; a final milestone requires a new shared run, not a union of old
manifests. For a shared contract change, dependent lanes pause incompatible
edits and refresh snapshots.

If checks are absent, coordinator does not accept “passed by inspection”.
Create a scoped fixture/runner task; until then the obligation is pending. If a
failure cause repeats twice, a fresh reviewer decides on a bounded redesign/reproducer;
the executor does not disable the gate.

## 7. Statuses without a permanent review blocker

Card states: todo → ready → in-progress → review → accepted; with defects,
changes-required; with an external impediment, blocked with a reason. `ready`
means implementation dependencies are actually satisfied; “ready, waiting on
dependency” is invalid. A parent has separate integration acceptance — one
component does not make the parent accepted.

Coordinator schedules a reviewer automatically as soon as the diff is ready.
There is no need to wait for owner confirmation on every local card: the
owner's request already authorizes implementation/review/integration under
this runbook. Owner input is needed for new products/publication/live spend or
changes to mandatory requirements not already authorized by context. Pending
review is not resolved by the author accepting their own work.

Operational board/records describe progress. Product evidence is tied to an
immutable input snapshot; operational files are excluded only by an exact
protected policy allowlist, with their revisions/hashes retained as audit
metadata. Acceptance fixtures, specs, prompts, source/build/gate registry are
not excluded from the fingerprint. Documentation changes are checked separately,
but a new status line alone does not require rebuilding a binary.

## 8. Restart and completion

Before compaction/end of turn, coordinator saves active assignments, lane
paths/baselines, worker session/process IDs, ready queue, last checks, review
verdicts, and next actions. The new turn first checks live processes/current
hashes; it does not duplicate workers or replay product mutations from artifacts.

If workers continue, coordinator monitors and integrates their results during
the available turn; it does not declare completion merely because it spawned
them. On session-limit exhaustion, leave an actionable handoff; with an external
blocker, complete all independent local tasks. Done means [working acceptance](working-product.md),
not “56 cards were mentioned in the report.”
