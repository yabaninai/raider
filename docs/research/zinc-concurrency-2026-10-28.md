# Zinc cold-compile poisoning: concurrency isolation (RAI-241)

Date: 2026-10-29. Lane: wave F, RAI-241. Baseline: `lane/rai-241` @ `a810133` (product inputs identical to `45f8f4c`; delta between the two commits is `scripts/research/**` only — disclosed per run). Experiment harness: `scripts/research/zinc-poisoning/run-zinc-tier.sh`. Disposable base: `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-241-zinc/` (session-scoped; durable values — signatures, hashes, durations, readings — are recorded below).

Prior evidence isolated by RAI-224 (`docs/work/records/RAI-224.md`): at-least-8-way concurrent cold compiles produced 3 fast-fails with `[E008] Not Found Error: …/AgentLoop.scala:772:7 — value id is not a member of raider.core.ToolCall`, while sources and outputs stayed byte-identical to healthy copies; every copy self-healed after `clean`; tiers ≤4 stayed green. This card reproduces the failure under controlled concurrency, bounds its trigger space, and localizes the persisting state.

## 1. Method

- Each tier launches `n` disposable copies (`git archive` snapshots, no `.git`, per `docs/parallel-development.md` snapshot mode) with an optional stagger, cold-compiles them concurrently (`sbt --batch Test/compile`, `-Xmx2G -XX:+ExitOnOutOfMemoryError`), then runs a recompile detector phase.
- For n≥8 a calm-window gate holds launch until the 1-minute host load drops below 8 (waits up to 30 min, then launches with disclosure). All launches below that gate legitimately passed (`launch-load.txt` per tier).
- A 30 s host sampler records load, JVM count/RSS, listening sockets (`host-samples.log`); sibling-lane interference is therefore disclosed per tier, not assumed.
- A failed copy triggers bounded diagnostics: preserve the poisoned target state as a tar (with sha256), then retry → clean → heal, each recorded with exit codes and durations.
- Cleanup kills only JVMs whose cwd is inside the experiment base and removes only the experiment tree (`zinc-cleanup.sh`).
- Hard caps: 1800 s per phase watchdog; every JVM capped at `-Xmx2G` with `ExitOnOutOfMemoryError`.

Harness fixes landed under this card before the matrix ran: m2 copy fix (`9b3cf62`), calm-gate locale fix (`8f36bde`), sampler JVM-count fix (`45f8f4c`), phase-PID wait fix (`a810133`), and poisoned-state preservation extended to `modules/core/target` after forensics identified core's zinc state as the carrier (absent during T1/T4/T8a; applied as an uncommitted working-tree change effective from T8s onward — so T8s/I1/I2 ran at `a810133` plus this uncommitted scripts-only delta — and committed only in `0e01f60`).

## 2. Experiment matrix

Load readings are 1-minute averages: `launch {a b c}` = three consecutive readings at gate pass; `ambient n/min/med/max` = during-tier sampler distribution.

| Tier | Copies × concurrency | Stagger | sbt state isolation | Poison / copies | Cold duration | launch load | ambient n/min/med/max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T1 | 1 | — | default (shared) | **1 / 1** | 40 s (fail) | n/a (sibling lanes building) | 27 / 19.24 / 33.97 / 75.86 |
| T1b | 1 | — | default (shared) | 0 / 1 | 141 s | n/a (sibling lanes active) | 6 / 36.65 / 54.70 / 112.67 |
| T4 | 4 concurrent | 0 | default (shared) | 0 / 4 | 224 s ×4 | {29.98 36.81 31.35} | 8 / 28.78 / 50.58 / 72.15 |
| T8a | 8 concurrent | 0 | default (shared) | **1 / 8** (c04) | c04 80 s (fail); others 135–140 s | {6.61 17.45 26.64} | 8 / 6.24 / 55.21 / 72.35 |
| T8b | 8 concurrent | 0 | default (shared) | 0 / 8 | 140 s ×8 | {6.89 9.67 13.48} | 5 / 6.89 / 58.90 / 108.72 |
| T8s | 8, stagger 30 s | 30 s | default (shared) | **1 / 8** (c08; heal re-poisoned) | c08 20 s (fail); others 6–240 s window | {7.04 29.26 25.74} | 18 / 5.34 / 8.07 / 10.52 |
| I1 | 2 concurrent | 0 | per-copy `sbt.global.base`, `boot`, `ivy2`; shared coursier dir | **1 / 2** (i1a) | i1a 30 s (fail); i1b 46 s | not sampled (manual run; host otherwise idle-ish per adjacent uptime checks) | — |
| I2 | 2 concurrent | 0 | per-copy global/boot/ivy **and per-copy coursier cache** (pre-seeded 149 MB) | 0 / 2 | green both | not sampled (manual run) | — |

Reading: poison struck at n=1 (T1 — while ≥3 sibling worktree lanes were cold-building the same tree), at 8-way (T8a), at 8-way staggered under a near-idle host (T8s, during-tier median load 8.07), and at 2-way with fully isolated sbt global state (I1). It did not strike in 3 of 6 controlled tiers (T4, T8b, I2 green; T8a, T8s, I1 poisoned). Host load magnitude and tier membership are not the discriminator; overlapping cold compiles of the tree (from any lanes on the host) are the common factor of every reproduction.

## 3. Verbatim failure signatures

All reproductions fail fast (7–80 s) at the same site. Every instance below is from the run's own log, verbatim:

T1/c01 cold (`logs/T1/c01/cold.log`):

```
[error] -- [E008] Not Found Error: /private/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-241-zinc/copies/T1/c01/modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala:772:7
[error] 772 |    tc.id
[error]     |    ^^^^^
[error]     |    value id is not a member of raider.core.ToolCall
[error] one error found
[error] (raiderRuntime / Compile / compileIncremental) Compilation failed
```

T8a/c04 cold, T8s/c08 cold, T8s/c08 heal, I1/i1a cold, and the c08-replay all show the identical block (same file, same line/column `772:7`, same `tc.id` caret, `value id is not a member of raider.core.ToolCall`), differing only in the copy path. sbt totals observed: 40 s, 80 s, 20 s, 13 s, 30 s, 7 s respectively.

Diagnostics across reproductions (exit/duration recorded in `*.result` files):

| Copy | retry (no clean) | clean → heal |
| --- | --- | --- |
| T1/c01 | fail, 26 s | `raiderCore/clean` alone heals (clean 5 s; `Test/compile` green, 57 s) |
| T8a/c04 | fail, 10 s | heals (clean 5 s; heal green, 56 s) |
| T8s/c08 | fail, 10 s | heal **failed, 13 s** — the clean rebuild was poisoned again while sibling lanes were still building |

The T8s/c08 heal re-poison is itself a reproduction: a lone clean rebuild of one copy was poisoned during a window when other host lanes were cold-building. Persistence across plain retry (three copies) and healing by regenerating core's target (two copies) locate the failure in copy-local state, not in any shared host cache.

## 4. State forensics (poisoned vs healthy)

All hashes are sha256. Source on disk matches the snapshot exactly (`git show 45f8f4c:modules/core/src/main/scala/raider/core/tool.scala` → `dccc3d934c369906377cf4507271dca78a10948d0f303b863ea5691896a32737`; the file defines `def id: ToolCallId = call` at line 34).

- Core outputs are byte-identical between poisoned and healthy copies across three independent reproductions:
  - `tool.tasty` = `266d1c4a93bee2fa8e4332b9d3f92f272c132da811864a6385c1aeea6015348e` (T1/c01, T1b/c01, T8s/c08 tar)
  - `tool$ToolCall.class` = `ff7e9ed45962d8b7e4d625e5f9cfc2c8a13a238da81d6687b6c1c7ed84a788a2` (same three), and `javap` shows `public java.lang.String id();` in the poisoned copy.
- The compiler's runtime-side error therefore reads a signature that is not what is on disk: the corruption is in what the downstream compile consumes from zinc's persisted state, not in emitted artifacts.
- raiderCore's zinc state zip differs every time it was captured: healthy `0fcfe684970e5f8abb31c262fbfa12492955b5166d39c506a7a0eae42c2858a0`, T1/c01 `2f96d2c4acbc6dac93352a8582de7c10af8160dc7fdcc45fdcfd0818f0a58ef1`, T8s/c08 `28ef5eae1ed2d074c871891f8658b42724a169389d996a1650950159097ae87f` (state = `modules/core/target/scala-3.9.0/zinc/inc_compile_3.zip`). Binary-level member comparison of the API store was not performed (out of card scope).
- Failed runtime compiles commit no zinc state (no `zinc/` dir under `modules/runtime/target`), which is why a plain retry re-fails fast.
- Deterministic carrier replay: a fresh `git archive` tree of `a810133` plus extraction of T8s/c08's preserved poisoned state (`poisoned-zinc-state.tar.gz`, `b2fee3fd4f32b487a8e2169cb2c2b835cced7d5c1f1bf4d9e94b6cda6163f634`, 1.9 MB) fails `sbt --batch Test/compile` in 7 s with the identical E008 block — no concurrency involved in the propagation step. (`logs/T8s/c08/replay.log`, `replay.result`: exit 1.)
- T8a/c04's preserved tar predates the core-inclusive preservation fix and covers `modules/runtime/target` only (`39a05cb974a45385ca18b28499e87643896dfa500be7f990f92049eac9809065`); its core state was consumed by the heal before preservation was fixed.

## 5. What is ruled out (bounded evidence)

- Host load magnitude: poison at median load 8.07 (T8s), green at median 50.58–58.90 with max 108–112 (T4, T8b, T1b).
- 8-way tier membership: poison at n=1 (T1) and n=2 (I1).
- Launch stagger: 30 s stagger did not prevent T8s/c08.
- Shared `~/.sbt` global plugins / boot / ivy state: I1 poisoned with these per-copy (`-Dsbt.global.base/-Dsbt.boot.directory/-Dsbt.ivy.home`). The RAI-224-era global-state suspicion (T1b's shared probe showed `~/.sbt/1.0/plugins/target/**` writes on every launch) is thereby bounded: those writes are not the required carrier.
- Shared coursier cache as sole carrier: cannot be concluded — I2 (per-copy cache) stayed green, which is a non-reproduction, not a discrimination (poison is probabilistic; single pair).
- Wrong-artifact / wrong-source explanations: byte-identical outputs and source-hash equality (above).
- Not attempted (out of scope): binary instrumentation of zinc's API pickle write; dotty compiler-bridge tracing; ≥3-pair statistics for the coursier-cache-only isolation.

## 6. Falsifiable root-cause hypothesis

**H1.** When two or more cold `raiderCore` compiles of the raider tree overlap in time on one host — regardless of which worktree or tier they belong to — a race (site not yet localized) can make one copy's `raiderCore / Compile / compileIncremental` commit a zinc API analysis whose signature for `raider.core.tool$ToolCall` omits member `id`, while the emitted `tool.tasty` / `tool$ToolCall.class` are byte-correct. The wrong analysis persists in that copy's core zinc state; every later `raiderRuntime` compile in the copy then deterministically fails with E008 at `AgentLoop.scala:772:7` until raiderCore's target (its zinc state) is regenerated.

- Supported by: T8a/c04 and T8s/c08 cold (induction under controlled concurrency), T1 (induction with sibling-lane overlap only), retry-persistence in three copies, `raiderCore/clean`-only healing in T1/c01, and the deterministic replay from the preserved T8s/c08 state.
- Falsified if: a poisoned copy's downstream failure survives `raiderCore/clean` (relocates the carrier); a fresh tree plus the preserved state compiles green (refutes the replay); or a fully isolated, repeated paired run shows poison without any shared resource at all.
- Open (next card): the induction site inside `compileIncremental` — which zinc/dotty sub-step writes the API state disjointly from the emitted TASTy — requires instrumented zinc (API pickle dump) or compiler-bridge logging. Observed induction rate in this study: 4 poisons in 33 controlled copy-compiles (~12% per concurrent-cold exposure; 29 harness-tier copies plus 4 manual I1/I2 copies; single-day, single-host sample).

Erratum (review reconciliation, 2026-10-31): the §6 count "33 controlled copy-compiles" reconciles against the §2 matrix as 34 total copies (30 harness-tier: T1 1 + T1b 1 + T4 4 + T8a 8 + T8b 8 + T8s 8; plus 4 manual I1/I2) minus T1, whose reproduction was natural (sibling-lane overlap, uncontrolled) and is excluded from both numerator and denominator (RAI-241 record: "Reproduced 4× controlled + 1 natural (T1 at n=1 under sibling-lane overlap)"); 4/33 ≈ 12.1%. Residual ambiguity: the §2 per-tier reading ("3 of 6 controlled tiers") also excludes T1b, giving denominator 32 (4/32 = 12.5%); retained evidence does not settle T1b's membership. The "~29" figure originated as an undecomposed RAI-241-phase approximation and was not recounted against the §2 matrix at the RAI-249 fixup. All candidate denominators (32–34) give 11.8–12.5%, so the "~12%" used in §6 and §7 stands.

## 7. Recommended mitigation for the parallel runbook

1. Treat the signature `value id is not a member of raider.core.ToolCall` at `AgentLoop.scala:772:7` during a cold compile as a known infra-poison marker, not a product error: fast-fail makes it cheap to identify.
2. Recovery is one command in the affected copy: `sbt --batch raiderCore/clean` then recompile (verified heal in 57 s). Full `clean` also works. No global caches need clearing.
3. Because staggering and state isolation do not prevent induction, lane hygiene should assume any cold compile overlapping other lanes' cold compiles carries ~12% per-copy risk; the retry→clean→heal diagnostics chain implemented in `run-zinc-tier.sh` is the pattern to copy into CI wrappers (preserve state tar → retry → clean → heal, all exit-coded).
4. CI adapters must not retry blind: the poison is copy-local and deterministic once induced; retry without cleaning always re-fails (observed 3/3).

## 8. Evidence index

All paths under `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-241-zinc/` (session-scoped, disposed after this card; values above are the durable record):

- Tier results: `logs/{T1,T1b,T4,T8a,T8b,T8s}/{summary.txt,host-samples.log,launch-load.txt,shared-writes-during-tier.txt}` and per-copy `{cold,recompile,retry,clean,heal}.{log,result,signature}`.
- Snapshot hashes: T1 archive `9f43e4ec3b542b643ddb22f9304be9462991e01ebb1582d3c531617b0f72c012`; T1b/T8*/I* archives of `a810133` `2c8b98d1105279125f33ca6397b5896aae1d10b8174ce431e3cc819fca579bed` (deterministic `git archive`).
- Preserved poisoned states: `logs/T8a/c04/poisoned-zinc-state.tar.gz` `39a05cb974a45385ca18b28499e87643896dfa500be7f990f92049eac9809065`; `logs/T8s/c08/poisoned-zinc-state.tar.gz` `b2fee3fd4f32b487a8e2169cb2c2b835cced7d5c1f1bf4d9e94b6cda6163f634`.
- Replay: `logs/T8s/c08/replay.{log,result}`.
- Isolation runs: `logs/I1/{i1a,i1b}.cold.{log,exit}`, `logs/I2/{i2a,i2b}.cold.{log,exit}`.

Disclosure: the dead pre-interrupt T1 attempt (killed at the old m2-copy bug) left no evidence and was removed from the disposable base; T1 ran at `45f8f4c`, all later runs at `a810133` (scripts-only delta); one orphaned sbt JVM from the interrupted session (pid 32034) was reaped under the harness's own-JVM-only rule. Ambient load for I1/I2 was not sampled by the harness (manual runs); adjacent uptime readings showed a 5–14 band.

---

# Instrumented continuation (RAI-249, 2026-10-30): the persisted API is intact — the poison is analysis-state-level

Lane: `lane/rai-249`, worktree `wt/rai-249`, baseline commit `4382105ec5ce215d1de3f346c9cc32ee1c1b1627` (product inputs identical to main at experiment time; delta is `scripts/research/**` and this document — disclosed per run). Disposable base: `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-249-zinc/` (session-scoped; durable values recorded below). Archive of the baseline: sha256 `33a5415ecdf567f07da4068613eb4e0c8d7af02da1af14c542d283a41f5051d3` (deterministic `git archive`). The worktree lacks `artifacts/tools/m2`; all copies pull the m2 snapshot from the main checkout (`artifacts/tools/m2`), selected by `RAI249_M2_SRC` or automatic fallback.

This card answers the open question RAI-241 left ("which zinc/dotty sub-step writes the API state disjointly from the emitted TASTy") by instrumenting the write side and bisecting the persisted state. Headline: **the write side is exonerated** — the persisted zinc API member set and every emitted artifact are byte-for-byte and semantically identical between poisoned and healthy copies; the poison lives elsewhere in the analysis state and expresses itself only when a downstream module's compile consumes it.

## 9. Method delta

Two new scripts under `scripts/research/zinc-poisoning/`:

- `api-probe-overlay/ApiProbe.scala` + `RaiderApiProbePlugin.scala` — a read-only sbt plugin overlaid into each disposable copy's `project/` (overlay sha recorded in `<copy>/.api-probe-overlay.sha256`). Task `apiProbeDump` walks each module's `target/scala-3.9.0/zinc/inc_compile_*.zip` (the `Compile`-configuration analysis store), reads the current `Analysis` through zinc's own deserializer, and prints, for every class whose fully qualified name contains `ToolCall`: the persisted member names and `nameHashes()` (name / `xsbti.UseScope` scope / hash), `structure()` presence, and the `AnalyzedClass` API hash. It also hashes the emitted `tool.tasty` / `tool$ToolCall.class` and runs `javap` to check `public java.lang.String id();`. Nothing is written to the analysis or products.
- `run-zinc-instrumented-tier.sh` — the RAI-241 safety envelope (calm-window gate with disclosure, 30 s host sampler, per-phase watchdog 1800 s, `-Xmx2G -XX:+ExitOnOutOfMemoryError`, bounded diagnostics with preserve-before-touch) extended with probe phases (cold → probe → recompile → probe →, on failure, preserve → retry → clean → heal → probe).

Correction to the probe during S0: zinc persists **minimized** APIs — `ClassLike.structure()` is always empty, including in healthy copies. The surviving member-name signal is `AnalyzedClass.nameHashes(): xsbti.api.NameHash[]` (`xsbti.api.NameHash` = `name()/scope()/hash()`, verified via `javap -cp compiler-interface-1.10.8.jar xsbti.api.NameHash`); verdicts therefore key on `id-namehash-present` (whether the name hash list for `raider.core.tool$.ToolCall` contains member `id`) and the exact class name, not on substring matches.

## 10. Write-time comparison: poisoned and healthy copies are identical

S0 (n=1, quiet host) established the healthy reference; IN1 (n=8, stagger 0) induced poison under load. IN1's calm-window gate expired after its wait budget; the run launched **with disclosure** (`launch-load.txt`: three consecutive readings, launch at 1-minute load ≈ 178; 20 sibling `yes` burners active; ambient during-tier 1-minute load distribution n=25, min 72, median 126, max 187).

| Copy | cold | recompile | core probe (cold) | `tool.tasty` | javap `id()` |
| --- | --- | --- | --- | --- | --- |
| c01 | 0 / 297 s | 0 / 30 s | `tool$ToolCall id-structure-present=false id-namehash-present=true apiHash=-889453991` | `266d1c4a…` | PRESENT |
| c02 | **1 / 216 s** | **1 / 45 s** | same as c01 | `266d1c4a…` | PRESENT |
| c03 | 0 / 302 s | 0 / 35 s | same as c01 | `266d1c4a…` | PRESENT |
| c04 | 0 / 302 s | 0 / 35 s | same as c01 | `266d1c4a…` | PRESENT |
| c05 | **1 / 206 s** | **1 / 45 s** | same as c01 | `266d1c4a…` | PRESENT |
| c06 | 0 / 302 s | 0 / 30 s | same as c01 | `266d1c4a…` | PRESENT |
| c07 | **1 / 211 s** | **1 / 45 s** | same as c01 | `266d1c4a…` | PRESENT |
| c08 | 0 / 297 s | 0 / 35 s | same as c01 | `266d1c4a…` | PRESENT |

- Poison rate 3/8 under disclosed heavy load, identical E008 signature at `AgentLoop.scala:772:7` (`value id is not a member of raider.core.ToolCall`).
- All eight copies' persisted analyses agree to the last detail: the name-hash list for `raider.core.tool$.ToolCall` contains `id/Default` (visible verbatim in the probe dump), `structure()` empty (minimization, both states), API hash `-889453991` everywhere.
- Emitted artifacts: `tool.tasty` sha256 `266d1c4a93bee2fa8e4332b9d3f92f272c132da811864a6385c1aeea6015348e` (same value as RAI-241's healthy reference) and `tool$ToolCall.class` sha256 `ff7e9ed45962d8b7e4d625e5f9cfc2c8a13a238da81d6687b6c1c7ed84a788a2` in **all eight** copies.
- Failure anatomy at cold (poisoned copies): `modules/runtime/target` has **no** `zinc/` state and **no** classes — the runtime compile died before committing anything; core's state zip `inc_compile_3.zip` was written normally. The API the runtime compiler failed to see is present in core's persisted analysis at the moment of failure.
- Diagnostics chain (identical for c02/c05/c07, e.g. `logs/IN1/c02/{retry,clean,heal}.result`): retry (no clean) fails 15 s; clean 5 s; heal 65 s exit 0. Preserve-before-touch tars exist per poisoned copy; canonical: `logs/IN1/c07/poisoned-zinc-state.tar.gz` sha256 `fd3221a419e556e6e66ac35208a0de508f4268842792fb1c9dd1696c90a9ea47` (contains `modules/{core,runtime}/target`, including the **original** poisoned core zip, sha256 `4830548d14ebcac46ed9ad8ba273d76dea8ba2e6ef16d3dde6aa64336f668df1`).

Conclusion: the disjoint-write question RAI-241 posed has a negative answer — there is no disjoint write of the API member set. Whatever poisons the copy does not touch the API store's content for `tool$ToolCall`, the API hash, the emitted TASTy, or the emitted class.

## 11. State replay bisect (RP1–RP14)

All replays: fresh `git archive` tree of `4382105` (no `.git`, m2 from the main checkout), then exactly one state component transplanted from IN1/c07's preserved tar (or IN1/c01 for the healthy donor), then `sbt --batch raiderCore/Compile/compile raiderRuntime/Compile/compile` in a single fresh sbt JVM unless noted. No overlay, no probe inside these runs. Inventory: 12 replay dirs (RP1–RP4, RP6–RP10, RP12–RP14), 13 sbt executions (RP6 ran twice — its second run skipped core entirely); RP5 and RP11 are skipped/aborted numbering with no evidence retained (the disposable base is session-scoped).

| Run | Transplanted component(s) | Result | Duration |
| --- | --- | --- | --- |
| RP1 | full c07 tar (`runtime/target` + `core/target`) | E008, exit 1 | 42 s |
| RP2 | only `modules/runtime/target` | success | 93 s |
| RP3 | only `modules/core/target` (zip `4830548d…`) | E008, exit 1 | 71 s |
| RP4 | RP3's core tree, but healthy zip from IN1/c01 (`ad7cfa4e…`) swapped in | success | 158 s |
| RP6 | RP3 clone; core-only compile first, then full build (same JVM) | core recompiles 9 sources (81 s, rewrites zip `c48bb259…` → `30cebfed…`), full build E008 at 33 s; **run 2 skipped core entirely** (no core compile line) | — |
| RP7 | RP3's core tree with core zip **deleted** | success (core fully recompiled) | 333 s |
| RP8 | RP3 clone; runtime-only compile in fresh JVM | E008, exit 1 — log shows one core `compiling 9` line (core re-ran as a dependency in the same JVM first) | 83 s |
| RP9 | RP3 clone; hash all core class/TASTy products before/after a core compile | exit 0; **every product byte-identical** — the forced re-emission is deterministic | — |
| RP10 | RP3 clone; core compile with `logLevel := Debug` | exit 0; invalidation reason captured (below) | — |
| RP12 | RP3 clone (post-RP3 state, rewritten zip `c48bb259…`); fresh JVM | core **again** recompiles the same 9 sources (39 s), then E008 at 9 s — the rewritten zip still poisons | — |
| RP13 | **Healthy** c01 clone, 3 anon class files of `trace$RunTrace` deleted; full build | core partial-recompiles 9 sources, exit 0 (runtime was up-to-date, 0 s) | 45 s |
| RP14 | RP13 state, runtime `classes/` emptied (zinc state kept), fresh JVM | core recompiles 9 (38 s), runtime recompiles all 46 (20 s), **exit 0** | — |

### 11.1 What the poisoned analysis does on every fresh-JVM read (RP10)

Zinc's debug log (`copies/RP10/rp10-core-debug.log`, lines 21–37):

```
[debug] Invalidating '<coursier-cache>/…/magnolia_3/1.3.23/magnolia_3-1.3.23.jar' because could not find class raider.core.trace$RunTrace$_$_$_$_$_$$anon on the classpath.
[debug] All initially invalidated sources:{…schemas.scala, result.scala, plugin/plugin.scala, codecs.scala, config/Config.scala, sdlc/SdlcStages.scala, trace.scala, sdlc/SdlcEvidence.scala, bundle.scala}
```

- The invalidated set is the same nine magnolia-dependent core sources on every fresh read (RP3, RP6, RP8, RP10, RP12 all log `compiling 9 Scala sources` for core). `tool.scala` is not among them.
- The name `raider.core.trace$RunTrace$_$_$_$_$_$$anon` is zinc's positional-name template for Scala 3 anonymous classes; `strings` shows exactly one occurrence in the **poisoned** zip and exactly one in the **healthy** zip (plus 24 concrete `RunTrace$$anon$N` names in both) — the template itself is normal, not the poison marker.
- No file named like the template exists in any copy's classes dir (poisoned, healthy, healed listings are identical); the healthy build validates the same name fine.

### 11.2 Interpretation

- The **sole switch** is the core analysis zip: transplant it → E008 (RP1, RP3); swap in a healthy zip → success (RP4); delete it → success (RP7).
- The **rewrite does not heal**: RP3's own partial recompile produced zip `c48bb259…`, which still forces the same nine-source invalidation and still E008s downstream in a fresh JVM (RP12); RP6's rewrite `30cebfed…` likewise stayed poisoned. Repeated partial re-emissions are byte-identical (RP9) — the poison is not in any emitted artifact and is not removed by incremental rewrites.
- The **same-JVM partial-recompile sequence is innocent**: from a healthy state, the same visible shape (nine-source core partial recompile → full runtime recompile) succeeds (RP13, RP14). The cascade is therefore a symptom of dependency-graph shape (magnolia dependents), not the failure mechanism, and JVM-level compiler reuse is not the discriminator.
- Within one JVM, zinc memoizes the classpath-entry validation after the first partial recompile — RP6's second run skipped core entirely yet still failed downstream. The poison enters the downstream compile through the analysis read itself.
- Core's own compiles keep succeeding against the poisoned zip (RP6 run 1, RP9, RP10, RP12 core step); only downstream-module compiles fail. The poisoned state is consumed exclusively through the downstream compile's zinc layer (`AnalysisMap` / per-classpath-entry lookup feeding the bridge), not through core's incremental loop.

## 12. Findings, revised hypothesis, depth bound

**Exonerated (bounded evidence):** the persisted zinc API for `raider.core.tool$.ToolCall` (member name hashes, API hash — probe, all copies); emitted `tool.tasty` / `tool$ToolCall.class` (byte-identical to the RAI-241 healthy reference in every copy, poisoned included); sources (archive hash); the forced-invalidation cascade itself and same-JVM compiler reuse (RP13/RP14 reproduce the cascade benignly); the JVM-shared dotty driver as a standalone trigger (RP4, RP13, RP14 green in fresh JVMs; RP6 run 2 isolates the difference to the zip).

**Revised hypothesis (H2, falsifiable):** under concurrent cold compiles, a race in `compileIncremental` can commit a core zinc analysis that is internally inconsistent in a non-API region (classpath-entry / class-index or relations candidates) while its API section and all emitted artifacts stay correct. The inconsistency (i) forces re-invalidating the same nine magnolia-dependent core sources on every fresh-JVM read of the analysis, (ii) survives incremental rewrites because the corrupted region is not recomputed by recompiles whose inputs are unchanged, and (iii) makes every downstream-module compile that consumes the analysis fail with E008 — the downstream compiler resolving `raider.core.ToolCall` without member `id` even though the TASTy it could read is byte-perfect. Falsified if: a deserialized poisoned analysis shows an intact non-API region; or a downstream compile succeeds after transplanting any single non-zip core state component; or rewriting the zip via a forced full-source-content change heals it.

**Depth bound (honest non-localization):** the exact sbinary field inside `inc_compile.bin` is not localized. Doing so requires deserializing and diffing the analysis pickle (zinc's `analysis` sbinary format, no public reader for the entry/class-index regions) or instrumenting zinc/`scala3-sbt-bridge` internals — both out of this card's budget (bytecode surgery on the prebuilt bridge was explicitly descoped at planning). Candidates ranked by evidence: classpath-entry defines-class index > relations > stamps region; APIs/name-hashes excluded by the probe.

**Mitigation updates (supersedes RAI-241 §7 where different):**
1. Healing requires regenerating the analysis: `sbt --batch raiderCore/clean` + recompile (65 s observed), or deleting `modules/core/target/scala-3.9.0/zinc/` (RP7). Plain retry never heals (6/6 across studies: 3/3 RAI-241 — T1/c01, T8a/c04, T8s/c08; 3/3 RAI-249 IN1 — c02/c05/c07).
2. New: **incremental recompilation does not heal either** — the poisoned analysis persists through partial rewrites (RP6/RP12), so "it recompiled core fine this time" must not be read as recovery.
3. Detection signature unchanged (E008 at `AgentLoop.scala:772:7`, `value id is not a member of raider.core.ToolCall`, fast-fail 15–216 s). The probe (`apiProbeDump`) is a cheap differential for future studies but cannot detect the poison (APIs are identical); the bisect replay (tar preserve → RP3/RP4-style swap) is the discriminating test.

## 13. Evidence index (RAI-249 additions)

All paths under `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-249-zinc/` (session-scoped; durable values above):

- Harness: `scripts/research/zinc-poisoning/api-probe-overlay/{ApiProbe.scala,RaiderApiProbePlugin.scala,}` overlay applied per copy with sha in `.api-probe-overlay.sha256`; `apply-api-probe-overlay.sh`; `run-zinc-instrumented-tier.sh`.
- Tiers: `logs/S0/` (healthy reference, quiet host, cold 81 s / recompile 10 s); `logs/IN1/{summary.txt,host-samples.log,launch-load.txt}` and `logs/IN1/{c01…c08}/` per-phase `{log,result,signature}`, `probe-{cold,recompile,retry,heal}.{verdict,emitted-check.txt,log}`.
- Preserved poisoned state: `logs/IN1/c07/poisoned-zinc-state.tar.gz` sha256 `fd3221a419e556e6e66ac35208a0de508f4268842792fb1c9dd1696c90a9ea47` (c02/c05 tars alongside; original core zip inside: `4830548d14ebcac46ed9ad8ba273d76dea8ba2e6ef16d3dde6aa64336f668df1`).
- Replays: `copies/RP{1,2,3,4,6,7,8,9,10,12,13,14}/` (12 dirs; RP5/RP11 unused numbering — no evidence retained) with per-run logs (`rp<NN>*.log`); healthy donor zip `copies/IN1/c01/modules/core/target/scala-3.9.0/zinc/inc_compile_3.zip` sha256 `ad7cfa4efba02e2402e0dbaa9a4bf7c652a639aa47b026ec99f3faee78b9138c`; rewritten-poisoned zips: RP3 `c48bb259e3c8f392d454c80e8d2634dceeced6624482223ac4003443776c1ef8`, RP6 `30cebfed74329af7586a7526b0e3db2e5975220371042a38c1b5a4c706e4c94e`, RP10 `286d64b54213038b55d00cf0356e975ec0f90d4235ac559c7132f7afc81948b1`.
- Bisect scratch: `bisect/{poisoned,healthy}/` (unzipped `inc_compile.bin` / `api_companions.bin` for `strings` checks).
- javap references: `xsbti.api.NameHash` / `xsbti.UseScope` from `org.scala-sbt:compiler-interface:1.10.8` in the Coursier cache.

Disclosure: IN1 launched at 1-minute load ≈178 after the calm gate expired with disclosure (20 sibling `yes` burners; healthy cold durations inflated to ~300 s vs S0's 81 s — duration comparisons across the two runs are not meaningful; correctness results are). All bisect runs were sequential single-JVM operations under ambient load 72–187. RP8's original framing ("runtime-only compile, no core work in JVM") was corrected during review: the log shows core's nine-source recompile ran first as an sbt dependency — RP8 is retained as a robustness replication, not as a discriminator (RP13/RP14 supersede it).
