# JVM distribution

## Local build and launcher

The current headless distribution is the `raiderCli/assembly` output:

```sh
make raider-jar
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar --help
```

The assembly main class is `raider.cli.Main`. It packages the current `raiderCli`
dependency graph and intentionally excludes the Scala REPL compiler and JLine.
For a coordinate-free Coursier invocation, install Coursier separately and run:

```sh
cs launch --jar /path/to/raider-cli.jar -- run --help
```

`packaging/raider` is a local installed-layout launcher. It supports
`raider --version` using the adjacent `version` file, then uses Coursier's
`--jar` mode when `cs` is available and otherwise invokes `java -jar` directly.
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
Finally, the clean-prefix smoke runs against `artifacts/release/<version>/raider-cli.jar`.
The version directory and `v<version>` tag are local worktree state only.

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
