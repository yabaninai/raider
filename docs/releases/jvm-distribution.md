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

Run the fresh-prefix simulation with:

```sh
sh scripts/release/install-smoke.sh modules/cli/target/scala-3.9.0/raider-cli.jar 1.2.3
```

The Homebrew and Scoop files are local formula/manifest templates. Their
`VERSION`, release URL, and SHA-256 placeholders must be replaced using actual
release outputs before a maintainer submits them to a package index. This card
does not publish packages or make a hosted availability claim.

## Local release integration status

The current `LocalReleaseHandler` delegates to `ReleaseStage`, which validates
accepted check/review/integration evidence and a checkpoint, then produces
`CHANGELOG.md`, `release.json`, and `SHA256SUMS` under
`artifacts/release/<version>/` and creates a local `v<version>` tag. Its build
callback does not currently stage arbitrary distribution files, and its
checksum set is fixed to those two release metadata files. Expanding it to
include the fat JAR must be done in the owning CLI/SDLC card, which is outside
RAI-193's allowed paths. Consequently, the distribution has not yet been
released through that machinery and no distribution-inclusive release output
is claimed here.
