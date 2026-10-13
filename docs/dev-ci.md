# Developer CI (RAI-045.a) — raider-fast workflow

Status: the minimal early slice RAI-045.a is implemented. Parent card
[RAI-045](tasks/m2-p2.md) remains open; this document describes only slice `.a`
and does not replace its acceptance criteria (DEV-CI-01..03).

## Included (`.github/workflows/raider-fast.yml` workflow)

One `fast` job on `ubuntu-latest`, triggered by `push` and `pull_request`, with
a **30-minute** job timeout (`timeout-minutes: 30`).

Steps (all are actual Makefile targets):

1. `actions/checkout@v4`, `actions/setup-java@v4` — **JDK 21, distribution
   `temurin`** (the pin matches the existing `.github/workflows/ci.yml`), and
   `coursier/cache-action@v6`.
2. `make bootstrap` — downloads pinned toolchain jars (Scala 3.9.0, ZIO 2.1.26,
   zio-json 0.10.0, JLine 4.0.14, coursier interface) from Maven Central into
   `artifacts/toolchain` (`scripts/bootstrap.sh`; idempotent). Network access
   is a deliberate, permitted exception here: only pinned artifacts with fixed
   coordinates are downloaded, with no snapshots.
3. `make compile` — `sbt --batch compile`.
4. `make quality-changed` — quality runner classifies changed files (git mode),
   without executing checks.
5. `make quality-changed QUALITY_EXECUTE=1` — executes active gates; a failed
   or skipped gate or scanner error fails the step (`continue-on-error` is not
   used).

Only **offline gates** run. Paid/live provider calls are excluded by design: no
step runs `raider-chat`, `raider-run`, spikes, or anything requiring API keys.

## Excluded (scope of parent RAI-045)

- Full nightly suite (`make quality-full`), soak, release lanes.
- GitLab CI (`.gitlab-ci.yml`) and `integrations/development-ci/**`.
- Uploading artifacts/logs on failure, fingerprint verification between jobs,
  full nightly Linux+macOS matrix, compiler/package smoke.
- PR risk gates / merge-base logic.
- Fork secret isolation and protected policy for forks.
- Acceptance DEV-CI-01 (clean build manifests/logs), DEV-CI-02 (fail-closed on
  scanners/prerequisites/stale cache), DEV-CI-03 (stale/concurrency/fork
  policy): these belong to the parent, not slice `.a`.

The presence of a YAML file does **not** claim that CI was executed (parent
non-goal: “claiming executed CI from YAML only”): the first actual run must be
recorded separately with evidence.

## Limitations

- **PTY gates are not included.** `repl_pty_smoke.py`, `renderer_pty_probe.py`,
  `tabs_pty_probe.py`, and CLI/REPL smoke require an interactive terminal; they
  do not run on hosted `ubuntu-latest` and need a self-hosted runner (separate
  work in the parent scope).
- `sbt` is expected to be preinstalled on the GitHub-hosted Ubuntu runner, the
  same assumption as in the existing `ci.yml` (the workflow does not install
  sbt).
- `.github/workflows/*.yml` is not matched in the quality runner's
  `path_profiles` registry (`scripts/quality-policy/registry.json`) and is
  classified as `__unmapped__` until a separate card extends the registry;
  this does not affect execution of the workflow's own gates.
- `coursier/cache-action@v6` speeds up sbt's default Coursier cache;
  `COURSIER_CACHE=/tmp/cc-master` from the Makefile is not persistent on the
  ephemeral CI disk and is not cached by that action.
