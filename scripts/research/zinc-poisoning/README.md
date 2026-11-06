# RAI-241 zinc cold-compile poisoning experiment scripts

Evidence-card instrument for `docs/research/zinc-concurrency-2026-10-28.md`.
Investigates the ≥8-way concurrent cold-compile zinc poisoning first observed by
RAI-224 (3 fast-fails with the false error `AgentLoop.scala:772: value id is not
a member of raider.core.ToolCall`; 0 occurrences at ≤4-way).

## Scripts

| Script | Purpose |
| --- | --- |
| `run-zinc-tier.sh <tag> <n> [stagger_s]` | Prepares `n` fresh disposable copies of the pinned commit and runs the two-phase protocol (cold compile, then incremental no-op recompile) at `n`-way concurrency, with a 30 s host sampler, bounded watchdogs, and auto-diagnostics (retry / clean / heal) for any failed copy. |
| `zinc-cleanup.sh [base]` | Disposes of the experiment: kills only JVMs whose cwd is inside the experiment base, then removes the disposable tree. Never touches foreign processes. |

## Safety properties

- Disposable copies live under
  `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-241-zinc/`
  (override with `RAI241_BASE`); copies are snapshot extracts
  (`git archive <commit>`, no `.git`) per `docs/parallel-development.md` §2.
- Hard burner cap: 8 concurrent sbt builds (the planned maximum tier); the
  script refuses larger values.
- Every sbt JVM is capped at `-Xmx2G -XX:+ExitOnOutOfMemoryError` (RAI-224
  protocol) so a memory blowup is an immediate visible crash.
- Per-phase watchdog `HARDCAP_S=1800` signals only its own process group.
- Heavy tiers (n ≥ 8) wait up to 30 min for a calm window (1-min load < 8)
  before launching; a missed window is recorded in `calm-window-miss.txt`.
- No product, build, or gate-policy change: this directory plus the research
  doc and work record are the entire card diff.

## Environment expectations

- The sbt wrapper pins `JAVA_HOME=/opt/homebrew/opt/openjdk` (OpenJDK 27);
  build declares sbt 1.11.4 (`project/build.properties`).
- Shared per-user state (disclosed): `~/.sbt` (boot + compiler bridge) and
  `~/Library/Caches/Coursier`. Each tier writes
  `logs/<tag>/shared-writes-during-tier.txt` — the files modified under those
  trees while the tier ran (expected: none once populated).
- `artifacts/tools/m2` is copied into every copy (the runbook's worktree
  preparation step; 114 MB each).
