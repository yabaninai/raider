# sbt-test gate budget load calibration at wave-scale lane counts (RAI-224)

Date: 2026-10-27. Baseline snapshot: `git archive 1351538` (`lane/rai-224` tip; archive SHA-256
`5ca77021bddb023dd1b9fc64a347712d8fd33b09b5c7943b8fcf3a125905ad58`, 12,431,360 bytes). The product
inputs of every measured copy are exactly the integrated wave-C tree (`1351538`); this card adds no
product change. Budget under calibration: `sbt-test` `timeout_s = 1800`
(`scripts/quality-policy/registry.json`, raised 900 → 1800 by RAI-209; NOT changed by this card).

## 1. Question

Board wave-A follow-up 5 (2026-10-26): "load ≥200 can still exceed the new 1800 s `sbt-test` budget
under 8-lane contention — owner calibration question for the next raise or a dedicated gate host."
This note measures the wall duration of the exact gate command under controlled concurrent-lane
loads (1, 4, 8, 12 builds) on the production host and states a raise-or-dedicated-host
recommendation as evidence for that owner decision.

## 2. Hardware and toolchain context

| Item | Value |
| --- | --- |
| Host | Apple M5 Max (model `Mac17,6`), 18 CPU cores, 137,438,953,472 bytes (128 GiB) unified memory |
| OS / FS | macOS 26.5.1 (25F80), APFS |
| JVM | OpenJDK 27 (build 27, `/opt/homebrew/opt/openjdk`; pinned by the `sbt` wrapper's `JAVA_HOME` default) |
| Build tool | `sbt` launcher 2.0.10 (Homebrew) running the build-declared sbt 1.11.4 (`project/build.properties`) |
| Scala / ZIO | pinned 3.9.0 / 2.1.26 (per registry toolchain) |
| Measurement window | 2026-10-27 01:30–03:39 UTC; neighboring lanes were ACTIVE on this host throughout and are recorded per-tier as ambient load (§5) |

The 1800 s budget question concerns THIS host under REAL multi-lane conditions, so ambient
neighbor-lane load is treated as a recorded covariate, not eliminated; a pristine window did not
occur at any point (1-min load ranged ~15–229 across the session).

## 3. Method (exact commands)

All measurement ran OUTSIDE the repo tree in the pre-approved session temp area
`/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-224-loadcal` (25 isolated copies
`c01`–`c25`, snapshot/instrument mode, no `.git` — per `docs/parallel-development.md` §2 discipline;
integrity anchored by the archive hash above).

1. Copies: `git -C <wt/rai-224> archive 1351538 | tar -x -C cNN`, then `cp -R
   <root>/artifacts/tools/m2 cNN/artifacts/tools/m2` (77 MB pinned toolchain store per copy — the
   runbook §1a worktree preparation step; `ReplayProductCliSpec` and `LineageCompileGateSpec` inside
   `sbt test` locate the build root through this marker and use its pinned scalac, so an empty
   per-copy store would be an environment failure, not a contention datum).
2. Caches (shared-infrastructure disclosure): all copies share the per-user Coursier cache
   (`~/Library/Caches/Coursier`, populated before the run) and sbt state (`~/.sbt`, 527 MB; boot +
   compiler bridge). Per `docs/parallel-development.md` §1a the shared Coursier cache is
   concurrency-safe; it was read-mostly during the runs. There was NO per-copy dependency download:
   cold-time inflation from empty caches does not affect these numbers, and the same sharing applies
   to production lanes.
3. Per-build memory cap (constant across all tiers): `SBT_OPTS="-Xmx2G
   -XX:+ExitOnOutOfMemoryError"` and `JAVA_TOOL_OPTIONS="-Xmx2G -XX:+ExitOnOutOfMemoryError"` —
   every JVM of a build (sbt core JVM, forked test JVMs via `Test / fork := true`, repl-engine dotty
   forks) is capped at a 2 GiB heap; an OOM would crash fast and visibly. Validation: zero real OOM
   events in all 25 builds (`grep "OutOfMemoryError:"` = 0 hits); peak TOTAL java RSS observed
   25,156 MB (tier 12) of 128 GiB — memory was never the binding constraint. The cap was validated
   by the tier-0 single build before any concurrent tier ran.
4. Warm-then-time protocol: for each tier, the tier's copies first run `sbt --batch Test/compile`
   (warm phase, AT tier concurrency, recorded) so that the timed phase measures the recurring
   gate cost — sbt boot + incremental no-op compile + full test execution — matching the production
   gate sequence where `sbt-compile` runs before `sbt-test` in the same worktree `target/`. The
   timed phase is the EXACT registry gate argv: `sbt --batch test` (subprocess, cwd = copy root),
   one timed run per copy, copies never reused (sbt test-result caching would corrupt repeats).
   Concurrent builds were launched together (≤4.5 s start spread at N = 12). An instrument hardcap
   of 3600 s (2× budget) per timed build was armed but never hit.
5. Ambient sampler: every 30 s per tier: 1/5/15-min load (`uptime`), java process count, total java
   RSS (`ps`). Per-tier stats in §5.

Instrument scripts (verbatim, on the measurement host, outside the repo):
`run-build.sh` (per-copy build with cap + watchdog), `launch-tier.sh` (sampler + warm at tier
concurrency + simultaneous timed launches), `collect-tier.sh` (status/final table) — retained at
`.../rai-224-loadcal/{run-build.sh,launch-tier.sh,collect-tier.sh}`.

## 4. Calibration table — wall duration of `sbt --batch test` per build vs the 1800 s budget

"within" = wall duration ≤ 1800 s AND exit 0 (a real gate pass); "over-budget" = the real gate would
have killed the build at 1800 s (`timed_out`, classified failed — here the instrument let it finish
to record the true overrun); "failed" = exit ≠ 0 (test failure; suite named); "zinc-poisoned" =
persistent false compile error after a ≥8-way concurrent cold compile (§6), recorded as that
build's result, never smoothed.

| Tier | Builds | Full-build wall min / median / max (s) | Within budget | Over budget (>1800 s) | Test-failed within budget | Zinc-poisoned fast-fail | Ambient load (1-min) min / median / max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| base (1) | 1 | 1064 / 1064 / 1064 | 0/1 | 0 | 1 (c01, `OutboundSpec`) | 0 | 22 / 51 / 175 |
| 4 | 4 | 1901 / 1921 / 1931 | 0/4 | **4/4** (c02, c05 over-budget AND test-failed: `ChatWorkflowVerifierE03Spec`, `OutboundSpec`) | 0 | 0 | 41 / 158 / 213 |
| 8 | 8 | 1131 / 1146 / 1161 (n=7) | 7/7 | 0 | 3 (c06+c07 `OutboundSpec`, c12 `MultiJobExportSpec`) | 1 (c08, 61 s) | 35 / 62 / 218 |
| 12 | 12 | 1253 / 1482.5 / 1489 (n=10) | 10/10 | 0 | 3 (c15 `ProcessApiAcceptanceSpec`, c22 `OutboundSpec`, c25 `ProcessApiAcceptanceSpec`+`OutboundSpec`) | 2 (c17 55 s, c19 60 s) | 30 / 81 / 229 |

Per-build detail (copy → seconds / exit / classification):

- base: c01 1064/1/failed(`OutboundSpec`)
- tier 4: c02 1931/1/over-budget+failed; c03 1918/0/over-budget; c04 1901/0/over-budget; c05 1924/1/over-budget+failed
- tier 8: c06 1156/1; c07 1141/1; c08 61/1/zinc-poisoned; c09 1155/0; c10 1161/0; c11 1146/0; c12 1131/1; c13 1140/0
- tier 12: c14 1454/0; c15 1253/1; c16 1473/0; c17 55/1/zinc-poisoned; c18 1478/0; c19 60/1/zinc-poisoned; c20 1482/0; c21 1488/0; c22 1489/1; c23 1488/0; c24 1483/0; c25 1483/1

Warm-phase context (`sbt --batch Test/compile`, same concurrency, not the headline metric):
tier 4: 283–286 s (ran inside the neighbor storm); tier 8: 140–142 s; tier 12: 169–171 s. The test
phase dominates the budget; 12-way cold compile costs only ~+20% over 8-way in calm ambient.

Reference anchor: on this host, integrated wave manifests recorded `sbt-test` at 548.0–598.7 s
(wave B, five runs), 566.6–614.3 s (wave A), 594.2/619.5 s (wave C); RAI-209 records standalone
480–570 s. The tier-8 tier sits at ~1.9× that anchor with 8 concurrent builds; tier-12 at ~2.5×.

## 5. Contention curve — what actually binds

The curve is NOT a function of lane count alone. Ambient neighbor-lane load dominates:

- base → 4 → 8 → 12 medians: 1064 → 1921 → 1146 → 1482 s, against ambient medians 51 → 158 → 62 → 81.
- Tier 4 ran entirely inside a neighbor storm (ambient median 158, max 213) and produced the ONLY
  over-budget results of the session (1901–1931 s; all four builds). This reproduces the wave-A
  follow-up at small N: **one to four builds under a ≥150-load storm exceed the 1800 s budget.**
- With moderate ambient (medians 62–81), 8 concurrent builds cost only ~+80 s over the storm-ambient
  baseline (1146 vs 1064), and 12 concurrent builds median 1482 s = 82% of budget, max 1489 s = 83%.
- Host totals: peak java process count 69 (tier 12); peak total java RSS 25.2 GB of 128 GiB; the
  2 GiB/JVM cap never bound (zero OOM). The host is CPU-schedule-bound: 1-min load reached 229 on
  18 cores, i.e. ~12.7× oversubscription, during the tier-12 window's ambient spikes.

Decomposition: duration ≈ f(total host load), where total = ambient lanes + measured tier. The
budget failure mode is the ambient-storm regime (≥ ~150 total load), not the measured tier count:
a SINGLE build in that regime already ran 1064 s with a timing-test failure (base), and four builds
ran 1900–1930 s (over budget).

## 6. Observed failure classes (recorded, not smoothed)

1. Timing-sensitive test failures: 9 of 22 full builds failed at least one suite — exclusively the
   documented load-sensitive concurrency families: `raider.cli.processapi.OutboundSpec`
   (saturated-queue coalescing assertions, e.g. expected gap frame `(65,3)` got `(66,2)`),
   `ProcessApiAcceptanceSpec`, `MultiJobExportSpec` (the documented MJE family), and
   `ChatWorkflowVerifierE03Spec`. All other suites (604/422/219-per-group summaries) passed in the
   same runs. Per AGENTS.md these are recorded as that build's result.
2. Zinc-state poisoning under ≥8-way concurrent COLD compiles: 3 builds (c08 @8-way, c17/c19
   @12-way) failed main compile with the identical false error `AgentLoop.scala:772: value id is not
   a member of raider.core.ToolCall` (`raiderRuntime / Compile / compileIncremental`), reproducing on
   immediate retry within the same copy. Proven properties: sources byte-identical across copies
   (`AgentLoop.scala` SHA-256 `22b6fc79…` in c01/c08); c08's on-disk core outputs byte-identical to
   the healthy c01 (`tool$ToolCall.class` `e775f9b7…`, `tool.tasty` `5707e8e4…`, `id()` present via
   javap); after `sbt --batch clean` all three copies compile green (exit 0; diagnostic probe
   `logs/diag-c08`, `logs/diag-c17c19`). Localization: zinc incremental analysis/backup state under
   heavy concurrent I/O; self-healing on a clean rebuild; 0 occurrences at ≤4-way. The timed
   `sbt --batch test` runs of these copies fast-failed (55–61 s) and are recorded as failed builds.
3. No OOM, no instrument hardcap hit, no missing-tool or environment failures in any timed run.

## 7. Recommendation (evidence for the owner decision — NO policy changed by this card)

1. The binding variable is TOTAL host load, not the number of gate lanes. At ambient ≲100 the
   current 1800 s budget holds up to at least 12 concurrent `sbt-test` builds (max observed 1489 s,
   83% of budget) — so the runbook's 8–10 heavy-lane cap is validated with headroom for the calm-to-
   moderate regime, and a budget raise is NOT justified by lane count itself.
2. At ambient storm levels (1-min load ≳150) even 1–4 builds breach the budget (1901–1931 s; a
   single build also failed a timing suite there). No realistic budget raise absorbs this regime —
   it is unbounded neighbor work, which is exactly why the wave-A timeout at load ≥200 occurred.
3. Primary recommendation: a DEDICATED GATE HOST (or admission control that defers `sbt-test`
   starts while host 1-min load > ~100). If neither is available, keep heavy lanes ≤8 and treat the
   1800 s budget as valid only under the ≤100-load admission condition; a further budget raise would
   mask the storm regime without fixing it and is not supported by this evidence.
4. Independent secondary risk worth an owner follow-up: the ≥8-way cold-compile zinc poisoning
   (false `Not Found` compile error, self-healing after clean). It corrupts lane warm-ups under
   storms and its failure mode (compile error from byte-identical sources) is expensive to diagnose
   per occurrence. Candidate follow-up card: reproduce in isolation (no ambient noise), then either
   pin an sbt/zinc workaround or add a gate-side "false compile error under contention" diagnosis
   note. Out of scope here (no build/policy change allowed by this card).
5. Measurement limitations that bound these claims: single host, single sbt/JDK pin, n=1 baseline,
   ambient not controllable, tier-4 has no calm-window datapoint (the storm was not schedulable
   around), and each build ran once (no variance estimate within a tier). The claims above are
   strictly about this host/toolchain under recorded ambient conditions.

## 8. Evidence inventory (measurement dir `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-224-loadcal`)

The temp dir is session-scoped; hashes below pin the results as produced. `.result` files are the
per-build JSON records quoted in §4; `host-samples.log` is the 30 s ambient series; `test.log` is
the full per-build sbt output. (SHA-256, first 16 hex shown; files retained while the session
directory exists.)

| Artifact | SHA-256 (16) |
| --- | --- |
| logs/base/c01/timed.result | `f32cb32cb3a14e7b` |
| logs/base/host-samples.log | `01778b40509a2be4` |
| logs/4/c02..c05/timed.result | `1913f71d878beb2e`, `588a72b264d22888`, `ead63685c029be03`, `a7d01709a1931b96` |
| logs/4/host-samples.log | `34fe3826b1ce8fc0` |
| logs/8/c06..c13/timed.result | `87282d152efc6972`, `58cd8d26a0f50796`, `eb8898fb133905df`, `66ea35af06e12a71`, `84e0f02eef246197`, `485b02236417d11c`, `6c6aa6e939c984c0`, `7029e717e8702cc3` |
| logs/8/host-samples.log | `1ac714cb2440bf52` |
| logs/12/c14..c25/timed.result | `34276fd15f84d144`, `c4038ed5b29f9290`, `d398c1bacaff455a`, `8f0e403367a21008`, `17c0f660c1bc1363`, `34ed06ddfd309486`, `25290f604c1c99be`, `e51e32e455164e9e`, `aa630c385c288107`, `0b7e68e410d3e95f`, `da999d550b983fd6`, `bcbdfe7274c76f3d` |
| logs/12/host-samples.log | `184ffe25c879df06` |
| warm results (c01–c25) | `2f30297d76a26f9d` (c01), `0ae2274df04d04b4`/`808429d8476e936d`/`2de364ce00d61eb0`/`2747a4be43795e37` (c02–c05), `cb5c6e0e7a41427a`…`61c0b5e53603dfc2` (c06–c13), `08ce0c32d38e1bac`…`6452bfa8dc16d353` (c14–c25) |
| zinc-poison diagnostics | `logs/diag-c08/c08/warm.result` `d80071598994c278`; `logs/diag-c17c19/c17/warm.result` `daa4c23cbcb27cae`, `logs/diag-c17c19/c19/warm.result` `f08e8fd3c87af316` (all exit 0) |
| test.log (base/4) | `ce7c3e7b1d8294bb` (c01), `3e4a5c9ca0d56c72`, `06d1f22e5e46a9c2`, `dcf7dda0975da5d7`, `f2925f53270a2cdd` |
| test.log (8) | `4a100f5e4c189437`, `6704d6436707c226`, `46664fa036ac125b`, `f07dd777fb1aec26`, `abb575db75dc98fd`, `a9bb078006e0cf83`, `9331a51d4868330c`, `361b50a65cb0d661` |
| test.log (12) | `772d35b639c8e55e`, `e69b3ab6fcf6b73d`, `50ee1c59fe795b11`, `6d6df0cbf79c1534`, `ac3d885cb62e2fa5`, `a11be9d742d284a8`, `5a5358bb362a5e73`, `e577581e67bf3bc3`, `6c659c5253d8c0ea`, `eeeae6ec42ab63d9`, `13bbf743048b24ab`, `af8e717cd21a10df` |
| archive snapshot | `git archive 1351538` → `5ca77021bddb023d…` (§ header) |

Exact tier launch commands (as executed): `nohup launch-tier.sh base`, `… 4`, `… 8`, `… 12` where
`launch-tier.sh <tag>` starts the 30 s ambient sampler, runs `run-build.sh <copy> <tag> warm` for
every copy in `tier-<tag>.txt` at tier concurrency, waits, then launches `run-build.sh <copy> <tag>
timed` for all copies simultaneously; `run-build.sh` executes `sbt --batch Test/compile` (warm) or
`sbt --batch test` (timed, the exact registry argv) with `SBT_OPTS`/`JAVA_TOOL_OPTIONS` per §3.3.
