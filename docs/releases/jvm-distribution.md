# JVM distribution

For consumer installation and the first mock CLI run, use the
[installed onboarding guide](../onboarding.md). This document is the
maintainer-facing description of local release construction and smoke checks.
The generated release directory and version tag are local worktree state; the
formulas contain future URL shapes and do not indicate a hosted release.

## Local build and launcher

The current headless distribution is the `raiderCli/assembly` output:

```sh
make raider-jar
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent scout --provider mock \
  --input "Read README.md and summarize the project purpose and how to run its checks" \
  --workspace . --out artifacts/raider-first-use
```

The assembly main class is `raider.cli.Main`. It packages the current `raiderCli`
dependency graph and intentionally excludes the Scala REPL compiler and JLine.
For a coordinate-free Coursier invocation, install Coursier separately and add
the fat JAR as an extra launch classpath entry:

```sh
cs launch --main raider.cli.Main --extra-jars /path/to/raider-cli.jar -- run \
  --agent scout --provider mock \
  --input "Read README.md and summarize the project purpose and how to run its checks" \
  --workspace . --out artifacts/raider-first-use
```

The CLI supports `raider --help` / `raider help`: it exits 0 with an accurate
command list (RAI-261). Unknown flags and unknown subcommands exit 21 with a
diagnostic that points to `--help`; per-command forms such as `run --help` are
not supported. See the
[installed onboarding guide](../onboarding.md) for the complete first-use path.

`packaging/raider` is a local installed-layout launcher. It supports
`raider --version` using the adjacent `version` file, then uses Coursier's
`--extra-jars` launch mode when `cs` is available and otherwise invokes
`java -jar` directly. It checks `JAVA_HOME` and requires Java 21 or newer.
The headless CLI itself does not currently implement `--version`; the
interactive Scala REPL ships as the separate `raider-repl` distribution
described below (RAI-262).

Create a real local JVM release from a clean, committed worktree with:

```sh
sh scripts/release/local-jvm-release.sh <version>
```

The script refuses stale `.release-*` staging directories, builds the headless
assembly with `make raider-jar`, builds the REPL distribution under the release
version with `sbt --batch "set version := \"<version>\"" raiderReplDist` (the
release version is pinned only for that build; the source tree stays at its
own version), and then invokes the `LocalReleaseDriver`, which drives the
actual `LocalReleaseHandler` and `ReleaseStage` against that assembly. The
staged set covers both distributions (RAI-279):

- the headless package: `raider-cli.jar`, `packaging/raider`,
  `packaging/homebrew/raider.rb`, `packaging/scoop/raider.json`;
- the REPL distribution: `repl/raider-repl-<version>/` with the launcher
  `raider-repl`, `raider-repl.jar`, `README.txt`, its own `SHA256SUMS`, and
  the `version` metadata file, plus the release archive
  `repl/raider-repl-<version>.zip`.

The staged `CHANGELOG.md`, `release.json` provenance, and the top-level
`SHA256SUMS` cover every file in the release tree: the top-level manifest does
not list itself, and the nested REPL manifest lists its payload with paths
relative to the `raider-repl-<version>` directory. The driver's
materialization hook (it runs after staging and before the atomic move) names
both distributions in `release.json`:

```json
"distributions": {
  "headless": "raider-cli.jar",
  "repl": "repl/raider-repl-<version>",
  "replArchive": "repl/raider-repl-<version>.zip"
}
```

The same hook folds the unreleased changelog section into the dated section
that the stage renders from the driver notes; the wrapper script applies the
same fold to the repository `CHANGELOG.md` after the run and fails if a
duplicate section for the version remains. Driver release notes must mirror
the unreleased `CHANGELOG.md` section for the version verbatim.

Verification then checks both checksum manifests (at the release root and
inside `repl/raider-repl-<version>/`), the staged REPL `version` file, the
rendered formulas, and the staged JAR's real SHA-256 before the clean-prefix
smoke runs against the released JAR and the staged
`artifacts/release/<version>/packaging/raider` launcher.
The version directory and `v<version>` tag are local worktree state only.
For Git worktrees whose `.git` entry is a pointer file, the script temporarily
exposes the resolved per-worktree Git directory while the existing release
handler runs, then restores the pointer on process exit. Git commits, tags, and
release inputs remain in the assigned worktree.

`install-smoke.sh` accepts the release directory as its fourth argument. Before
creating install staging, `verify-local-release.sh` validates the versioned
JSON metadata with `jq` — including the `distributions` naming object — and
checks every entry of `SHA256SUMS`. RAI-279 additionally cross-checks
manifest coverage: every regular file in the release directory except the
manifest itself must be listed; a listed-but-missing or present-but-unlisted
file is an integrity failure. Missing or malformed metadata, checksum
failures, and coverage mismatches return 21 and do not create a prefix.
Releases cut before RAI-279 (0.1.x) carry neither the `distributions` object
nor full-manifest coverage; verify those with the pre-RAI-279 scripts from an
older checkout.
The Coursier launcher uses the shipped fat JAR as an extra classpath entry with
`raider.cli.Main`; the Java fallback remains available when Coursier is absent
or `RAIDER_USE_COURSIER=0` is explicitly set. The REPL distribution installs
separately: unpack or copy `repl/raider-repl-<version>/` and run its
`raider-repl` launcher (RAI-262); it carries no headless CLI package.

The local negative and interruption harnesses are:

```sh
sh scripts/release/negative-install-smoke.sh artifacts/release/<version>
python3 scripts/release/signal-install-smoke.py [artifacts/release/<version>]
```

The negative harness derives the version from `release.json`, corrupts
`release.json` and `SHA256SUMS`, and then corrupts one REPL payload artifact
at a time (missing jar, truncated jar, tampered launcher, missing release
zip). Every case must be refused with exit 21 and must leave the install
staging directory empty. The signal harness sends SIGTERM to the
install-smoke process group while its Coursier command is blocked; it takes
the release directory as an optional argument and reads the version from
`release.json`. It requires exit 143 and an empty temporary install
directory. The harnesses are test/bootstrap glue; they are not shipped in the
CLI.

The LocalRelease `ReleaseStage.Request.distributionArtifacts` field accepts
source paths and destination-relative names. The stage copies these into its
private staging directory, substitutes `VERSION` and
`REPLACE_WITH_RELEASE_SHA256` in files whose bytes contain either placeholder,
and includes every resulting path in `SHA256SUMS` (RAI-279: the driver's
materialization hook rewrites the manifest after staging so it covers the
complete tree). Binary files such as the JARs are copied byte-for-byte unless
a freak placeholder byte match would corrupt them — which is why the driver
refuses to run when any REPL distribution artifact contains a placeholder.
The release directory is promoted only after the complete set and checksums
have been written; interruption leaves no installed partial version. The
RAI-279 staged set is:

```scala
distributionArtifacts = List(
  DistributionArtifact(jar, "raider-cli.jar"),
  DistributionArtifact(launcher, "packaging/raider"),
  DistributionArtifact(homebrew, "packaging/homebrew/raider.rb"),
  DistributionArtifact(scoop, "packaging/scoop/raider.json"),
  DistributionArtifact(replLauncher, s"repl/raider-repl-$version/raider-repl"),
  DistributionArtifact(replJar, s"repl/raider-repl-$version/raider-repl.jar"),
  DistributionArtifact(replReadme, s"repl/raider-repl-$version/README.txt"),
  DistributionArtifact(replChecksums, s"repl/raider-repl-$version/SHA256SUMS"),
  DistributionArtifact(replVersion, s"repl/raider-repl-$version/version"),
  DistributionArtifact(replZip, s"repl/raider-repl-$version.zip")
)
```

Build the assembly and the REPL distribution before invoking the SDLC release
stage and pass their output paths as the artifact sources. Then install-smoke
the staged JAR, not the build-tree JAR:

```sh
make raider-jar
sbt --batch "set version := \"<version>\"" raiderReplDist
sh scripts/release/install-smoke.sh artifacts/release/<version>/raider-cli.jar <version>
```

The Homebrew and Scoop templates contain the release URL shape for a future
maintainer publication; local release does not upload files or assert that the
URL is hosted. No package publication or deployment is performed.

## Local release integration status

`LocalReleaseHandler` delegates to `ReleaseStage`, which validates accepted
check/review/integration evidence and a checkpoint, then produces changelog,
provenance, requested distribution artifacts, and complete checksums under
`artifacts/release/<version>/`, and creates a local `v<version>` tag. The
request's build callback runs before staging, allowing it to build the fat
JAR; the source path is then included in `distributionArtifacts`. RAI-279
adds the request's materialization hook
(`ReleaseStage.Request.afterStagedArtifacts`): the `LocalReleaseDriver` hook
adds the `distributions` naming object to `release.json`, folds the
unreleased changelog section into the dated section, and rewrites the
top-level `SHA256SUMS` so it covers every staged file — all before the stage
atomically moves the staging directory into its release location and creates
the local tag.

### Standalone driver trust basis

`scripts/release/LocalReleaseDriver.scala` composes candidate identity and typed
check/review/integration records from the clean local Git revision and the
observed assembly, writes the normal checkpoint request, and constructs a
matching decision through `CheckpointDispatch`. The invoking local owner is
the authority for this self-release: the check record reflects the executed
local build, and the review/acceptance decision is explicitly attributed to
`local-owner`. There is no remote reviewer, forge, network approval, or claim
that these locally composed records are independent evidence. The checkpoint
is revalidated by the existing dispatch contract before release. Do not use
this driver to represent externally reviewed or published releases.
