# Installed CLI first use

This is the deterministic acceptance path for the headless JVM distribution.
It uses a local release directory and the mock backend; it does not need a
provider key or make a network model call. Do not replace the release directory
with a build-tree JAR when recording acceptance.

## Prerequisites

- A verified local release directory containing `raider-cli.jar`,
  `packaging/raider`, `release.json`, and `SHA256SUMS`.
- Java 21 or newer. Coursier is optional; the launcher falls back to `java -jar`.
- `shasum` (or `sha256sum`) for release verification, plus `jq` to read the
  version from release metadata.

The release directory is local maintainer output. There is no hosted download
URL. Homebrew and Scoop templates do not establish package availability or
Windows runtime support. The installed JAR is headless and excludes the Scala
compiler and JLine.

## Install and first useful run

Set `RELEASE_DIR` to the verified release directory and use a new install path
for each installation attempt. These commands install the staged artifact into
a separate prefix and add that directory to this shell's `PATH`:

```sh
RELEASE_DIR="/absolute/path/to/artifacts/release/0.1.1"
(cd "$RELEASE_DIR" && shasum -a 256 -c SHA256SUMS)
VERSION=$(jq -r .version "$RELEASE_DIR/release.json")
INSTALL_DIR="$HOME/.local/raider/$VERSION"
mkdir -p "$INSTALL_DIR"
cp "$RELEASE_DIR/raider-cli.jar" "$INSTALL_DIR/raider-cli.jar"
cp "$RELEASE_DIR/packaging/raider" "$INSTALL_DIR/raider"
printf '%s\n' "$VERSION" > "$INSTALL_DIR/version"
chmod +x "$INSTALL_DIR/raider"
export PATH="$INSTALL_DIR:$PATH"
java -version
raider --version
mkdir -p artifacts
raider run --agent scout --provider mock \
  --input "Read README.md and summarize the project purpose and how to run its checks" \
  --workspace . --out artifacts/raider-first-use
```

The `raider run` command prints a result/work directory. Read `summary.md` and
`run.json` there; `transcript.json` and `events.jsonl` are also written where
applicable. The `--out` directory is a parent and each invocation gets its own
`work-<id>` child. Use a distinct output parent for retries or another first
run; do not clear an earlier run to make room. Mock response text is a fixed
fixture and is not evidence of model quality or actual repository analysis.
This scout request exercises the installed CLI and creates inspectable result
artifacts without making workspace changes. The current CLI does not implement
a general `--help` option: `raider --help` returns an unknown-subcommand error
with exit 21; use the documented `raider run` invocation.

## Verified sequence

The executed commands, exact release version and JAR SHA-256, exit codes,
artifact directory, and limitations are recorded in
[`../../work/records/RAI-204.md`](../../work/records/RAI-204.md). A run counts
only when it used the staged local-release JAR through the installed launcher.
