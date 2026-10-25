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

These are the documented `raider run` command forms; the CLI does not currently
support general `--help` or `run --help` options. See the
[installed onboarding guide](../onboarding.md) for the complete first-use path.

`packaging/raider` is a local installed-layout launcher. It supports
`raider --version` using the adjacent `version` file, then uses Coursier's
`--extra-jars` launch mode when `cs` is available and otherwise invokes
`java -jar` directly. It checks `JAVA_HOME` and requires Java 21 or newer.
The headless CLI itself does not currently implement `--version`.

Create a real local JVM release from a clean, committed worktree with:

```sh
sh scripts/release/local-jvm-release.sh 0.1.0
```

The driver builds with `make raider-jar`, then invokes the actual
`LocalReleaseHandler` and `ReleaseStage` against that assembly. It stages the
launcher and formula sources alongside the JAR, writes release provenance and
changelog, and verifies every entry in `SHA256SUMS`. The Homebrew and Scoop
formulae are checked for rendered version and the staged JAR's real SHA-256.
Finally, the clean-prefix smoke runs against the released JAR and the staged
`artifacts/release/<version>/packaging/raider` launcher.
The version directory and `v<version>` tag are local worktree state only.
For Git worktrees whose `.git` entry is a pointer file, the script temporarily
exposes the resolved per-worktree Git directory while the existing release
handler runs, then restores the pointer on process exit. Git commits, tags, and
release inputs remain in the assigned worktree.

`install-smoke.sh` accepts the release directory as its fourth argument. Before
creating install staging, `verify-local-release.sh` validates the versioned
JSON metadata with `jq` and checks every file listed in `SHA256SUMS`. Missing or
malformed metadata and checksum failures return 21 and do not create a prefix.
The Coursier launcher uses the shipped fat JAR as an extra classpath entry with
`raider.cli.Main`; Java fallback remains available when Coursier is absent or
`RAIDER_USE_COURSIER=0` is explicitly set.

The local negative and interruption harnesses are:

```sh
sh scripts/release/negative-install-smoke.sh artifacts/release/<version>
python3 scripts/release/signal-install-smoke.py
```

The signal harness sends SIGTERM to the install-smoke process group while its
Coursier command is blocked. It requires exit 143 and an empty temporary install
directory. The harness is test/bootstrap glue; it is not shipped in the CLI.

The LocalRelease `ReleaseStage.Request.distributionArtifacts` field accepts
source paths and destination-relative names. The stage copies these into its
private staging directory, substitutes `VERSION` and
`REPLACE_WITH_RELEASE_SHA256` in `.rb`, `.json`, `.cmd`, `.sh`, and `.txt`
files, and includes every resulting path in `SHA256SUMS`. Binary files such as
the JAR are copied byte-for-byte. The release directory is promoted only after
the complete set and checksums have been written; interruption leaves no
installed partial version. The staged set can include:

```scala
distributionArtifacts = List(
  DistributionArtifact(jar, "raider-cli.jar"),
  DistributionArtifact(launcher, "packaging/raider"),
  DistributionArtifact(homebrew, "packaging/homebrew/raider.rb"),
  DistributionArtifact(scoop, "packaging/scoop/raider.json")
)
```

Build the assembly before invoking the SDLC release stage and pass its output
path as `jar`. Then install-smoke the staged JAR, not the build-tree JAR:

```sh
make raider-jar
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
request's build callback runs before staging, allowing it to build the fat JAR;
the source path is then included in `distributionArtifacts`.

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
