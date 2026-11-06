# Start using Raider

This guide starts with the installed JVM distribution: the headless `raider`
CLI, a deterministic mock run, and the installed `raider-repl` Scala REPL.
It requires Java 21 or newer, but does not require sbt, Python, a source checkout,
or provider credentials. The local release is not a hosted download: obtain its
directory from the release maintainer. No public download URL is currently
available; this guide describes the 0.2.0 release layout. For building the same
interfaces from a source checkout, see
[source checkout setup](#source-checkout-setup).

## 1. Install the local JVM distribution

The release directory contains the headless CLI package (`raider-cli.jar`,
`packaging/raider`, `packaging/homebrew/raider.rb`, `packaging/scoop/raider.json`,
`release.json`, `SHA256SUMS`) and the installed REPL distribution as a nested
`raider-repl-<version>/` directory with its own `SHA256SUMS`. Verify the
supplied release before copying files, then create a private install directory.
This example uses `jq` to read the version from the verified metadata. Replace
the example path with the path on your system:

```sh
RELEASE_DIR="/path/to/release/0.2.0"
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
```

`java -version` must report Java 21 or newer; the launcher refuses older Javas.
`raider --version` should print `raider $VERSION`. `raider --help` (or
`raider help`) exits 0 and prints the command list to stdout: a usage line, the
eleven commands `run`, `chat`, `process-api`, `compile`, `workflow run`,
`workflow check`, `workflow explain`, `plugin`, `replay`, `resume`, and
`verify-artifacts` with their real flags, a note that the interactive Scala
REPL ships as a separate distribution (`raider.repl.Main`), and the exit-code
legend (0 success, 10 task rejected, 20 runtime failure, 21 configuration,
22 policy denied, 23 budget exhausted, 24 deadline exceeded, 25 needs approval,
26 uncertain, 27 artifact write failure, 130/143 signals). Unknown flags and
unknown subcommands exit 21 with a diagnostic that points at `raider --help`.
Start with the documented `raider run` form below. The launcher uses Coursier
when `cs` is on `PATH` (set `RAIDER_USE_COURSIER=0` to force the Java
fallback); otherwise it runs the adjacent JAR with Java. The headless CLI
package excludes the Scala compiler and terminal stack; the interactive REPL
and tab shell come from the release's installed REPL distribution
([Section 3](#installed-repl)) or from a source checkout
([below](#source-checkout-setup)). `packaging/homebrew/raider.rb` and
`packaging/scoop/raider.json` are local formula templates, not evidence of
Homebrew/Scoop installation or hosted artifacts.

## 2. Run a useful deterministic first task

From a project directory you trust, run the built-in scout in mock mode. The
scripted provider returns a fixed response and writes run artifacts without
contacting a model or editing files. It demonstrates the first CLI workflow and
artifact inspection; it does not prove that the mock response analyzed your
repository:

```sh
mkdir -p artifacts
raider run --agent scout --provider mock \
  --input "Read README.md and summarize the project purpose and how to run its checks" \
  --workspace . --out artifacts/raider-first-use
```

The CLI prints the created `work-<id>` directory under
`artifacts/raider-first-use/`. Inspect `summary.md`, `run.json`, and, when
present, `transcript.json` and `events.jsonl` there. `--out` is a parent
directory: each run receives its own work directory. Use a new `--out` parent
for a separate first-use attempt so earlier artifacts are preserved. Mock
responses are scripted fixtures; they demonstrate command wiring and artifact
creation, not model quality or a live answer. The executable offline
first-use example and its recorded command results are in
[`examples/first-use/`](examples/first-use/).

<a id="installed-repl"></a>

## 3. First steps with the installed REPL distribution

The release also ships the interactive Scala REPL as a separate installed
distribution, independent of a source checkout: the staged
`raider-repl-<version>/` directory (or its `.zip` form) contains the
`raider-repl` launcher script and the self-contained `raider-repl.jar`
(main class `raider.repl.Main`, with the in-process Scala 3.9.0 compiler and
JLine inside), plus `README.txt`, a `version` file, and `SHA256SUMS`. It
requires Java 21 or newer — no sbt, no Python, no source checkout:

```sh
cd raider-repl-<version>  # Replace <version> with the staged directory name.
shasum -a 256 -c SHA256SUMS
chmod +x raider-repl
./raider-repl --version
```

`./raider-repl --version` prints `raider-repl <version>` and exits 0 without
starting the JVM. The launcher refuses with exit 21 and a one-line diagnostic
when the distribution jar is missing or unreadable, when the jar lacks the
in-process Scala compiler, or when no usable Java 21 is found
(`raider-repl: Java 21 or newer is required`); a refusal never hangs.

Start the offline scripted session:

```sh
RAIDER_PROVIDER=mock ./raider-repl
```

`RAIDER_PROVIDER=mock` selects the offline scripted fixture: no provider
request is made, responses are fixed script text, and the banner marks the
scripted backend. The responses demonstrate the interface, not model quality.
At the
`raider>` prompt, evaluate ordinary Scala and use the REPL commands: `:help`
lists them (`:help`, `:jobs`, `:cancel <id>`, `:reset`, `:quit`), and `:quit`
exits. Without arguments this is the line REPL; `--tabs` starts the product
tab shell and `--chat` starts the chat layout over the scripted/live backend.
In the tab shell, typed task text is a draft; it is not submitted to an agent.
SIGINT/SIGTERM terminate the REPL with exit codes 130/143. Live providers
follow the same environment contract as the raider CLI; see
[configure a live model](#connect-a-model). To build and run the same
interfaces from a source checkout instead, continue with the
[source-checkout setup](#source-checkout-setup).

<a id="source-checkout-setup"></a>

## Source-checkout setup

The following sections describe building from a checkout, not installing the
JVM distribution. Commands assume you are in the repository root.

### Set up Java and build

Raider's examples require JDK 21. Install a JDK 21 distribution, then replace `/path/to/jdk-21` below with its actual installation path. This sets Java for the current shell and puts its tools first on `PATH`:

```sh
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

The version output should identify Java 21. Install sbt and Python 3 as well; the project pins sbt 1.11.4 and Scala 3.9.0. Build the runtime classpath and example bundle once:

```sh
sh scripts/build/user-examples.sh
```

The script prints a success line naming `artifacts/user-examples/classpath.txt` and `task-summary.jar`. It writes the full build log to `artifacts/user-examples/build.log`. Keep this shell open and load the classpath:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

If you open a fresh terminal later, return to the repository root, set `JAVA_HOME` and `PATH` again, and run the `RAIDER_CP=...` command there. The build itself need not be repeated unless the checkout changes.

### Choose how to start

### Tabs interface

Start the terminal interface with:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main --tabs
```

This starts with the scripted mock backend and makes no provider request. In the tabs interface, input is saved as a draft; it does not evaluate Scala. Try `:help`, then `:new notes`, `:tabs`, and `:quit`. See the [tabs guide](user-guide-tui.md) for available commands and current limits.

### Scala REPL

For Scala evaluation, start the REPL without `--tabs`:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

At the `raider>` prompt, try Scala and a scripted agent request:

```scala
case class Check(name: String, passed: Boolean)
val checks = List(Check("compile", true), Check("tests", false))
checks.filterNot(_.passed).map(_.name)
scout.ask("Explain what a failed check means in one sentence")
```

The first three lines run through the Scala compiler. The `scout` reply is fixed mock text, not an answer based on your project. Enter `:help` for REPL commands and `:quit` to exit. More examples are in the [REPL guide](user-guide-repl.md).

The compiler-backed REPL also supports `:type <expression>`, which compiles
the expression against the current bindings and prints its type without
evaluating it. For example, `:type scout("Review the parser")` reports
`AgentAsk[String]`; it does not make a provider request. Tab completion uses
the Scala compiler's live bindings. In `--tabs` mode, type `:scrollback <tab>`
to show retained event lines and the eviction count, and press Tab on a command
prefix to open the command palette. Tab-shell drafts remain local text, not
submitted prompts. The [TUI guide](user-guide-tui.md) documents the command
surface and its current limits.

Tabs mode also exposes a read-only plan phase: a proposed plan is displayed in
the Plan tab before execution. Review it, then enter `:approve` to start the
execution once or `:reject` to cancel the live plan phase. The plan phase is
read-only by tool recovery classification; approval is the explicit transition
to execution. The deterministic plan-scenario corpus is an offline evaluation
fixture, not a claim of live model quality. See [ADR-028](adr/ADR-028-plan-mode.md)
and the [plan-mode evaluation notes](quality-gates.md#3-profiles).

### Command line

The CLI runs an agent task without an interactive prompt. Its `--out` path is a parent directory; Raider creates a `work-<id>` folder inside it. A mock task uses fixed responses, so use it to learn the command shape rather than to expect a real code change:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider mock \
  --input "Read README.md and summarize the project" \
  --workspace . --out artifacts/first-run
```

The command prints a result line with the work directory. Look there for `run.json` and `summary.md`; when produced, `transcript.json` contains the run trace. The built-in `coder` has file and process tools. Use a disposable workspace for tasks that may edit files.

Each completed headless run writes `events.jsonl`, the event journal used by
`raider resume`. Given a printed work directory, run:

```sh
RUN_DIR='artifacts/first-run/work-<id>'  # Replace <id> with the printed directory name.
java -cp "$RAIDER_CP" raider.cli.Main resume "$RUN_DIR"
```

Replace `<id>` with the actual directory suffix; this is a read-only recovery
report, not a continuation. It folds the journal into observable state and
reports artifact hashes without invoking the model, tools, or workspace writes.
Exit 0 means the journal is consistent (the run may be terminal or interrupted);
26 means a valid prefix was recovered but the final journal line is uncertain;
21 means usage or an unreadable/missing journal; 20 means the recovered prefix
could not be folded. Preserve the original run directory, including any torn
tail. Resume reads `events.jsonl` specifically; it does not look for or resume
from a file named `segments.jsonl`.

<a id="connect-a-model"></a>

### Configure a live model

Live calls send your prompt and task context to the endpoint you configure, and may incur charges. The source supports OpenAI-compatible and Anthropic-compatible providers. Configure the provider, endpoint, model, and credential in your private shell environment. For example, set non-secret values for your chosen service:

```sh
export RAIDER_PROVIDER=openai
export RAIDER_BASE_URL="https://your-provider.example/v1"
export RAIDER_MODEL="your-model-name"
```

Do not put a token in this file, a command argument, or the endpoint URL. Provide credentials using your private environment's `RAIDER_API_KEY`, or point `RAIDER_API_KEY_CMD` at a credential helper that prints the key on its first stdout line:

```sh
export RAIDER_API_KEY_CMD="/path/to/credential-helper"
```

The helper has a 10-second limit. The CLI rejects `--api-key`; if credentials cannot be resolved, a live CLI call fails before dispatch. To use Anthropic, set `RAIDER_PROVIDER=anthropic` and configure its compatible base URL and model. For CLI runs, pass the same endpoint and model explicitly:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider "$RAIDER_PROVIDER" \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "Read README.md and summarize the project" \
  --workspace . --out artifacts/model-run
```

The REPL reads `RAIDER_PROVIDER`, `RAIDER_BASE_URL`, `RAIDER_MODEL`, and the credential from its environment. A `[scripted-fixture]` marker means the response is mock content; do not treat it as model output.

The Anthropic-compatible transport sends tool definitions with `name`,
`description`, and `input_schema`; tool results are returned as structured
`tool_result.content` text blocks containing the rendered JSON result. Offline
local-stand and cross-wire tests cover this mapping. Normal onboarding examples
remain mock or configured provider examples; they do not make a live Anthropic
request unless explicitly configured.

For a CI run, `--require-provenance <manifest.json>` makes provenance
verification a preflight requirement before dispatch. A successful run writes
`checks.junit.xml` alongside the run artifacts. A preflight refusal is a
failure, not a direct-provider fallback; consult [CI usage](user-guide-ci.md)
for trusted bundle and artifact details.

### Try a coding task

Continue with [fixing a Scala bug](user-guide.md#fix-a-scala-bug-with-a-model).
The example supplies the buggy project and its tests, runs the agent in a
copy, and checks the result. You can then use the [CLI on your own project](user-guide.md#work-on-your-own-project)
or build a [local CI workflow](user-guide-ci.md).

## Common setup problems

- **Java is not version 21:** check that `JAVA_HOME` points to the JDK installation and that `$JAVA_HOME/bin` comes first on `PATH`.
- **Build cannot resolve dependencies:** check network access, or set `COURSIER_CACHE` to a populated Coursier cache and rerun the build.
- **Classpath file is missing:** run `sh scripts/build/user-examples.sh` from the repository root and inspect `artifacts/user-examples/build.log`.
- **Live CLI reports missing credentials:** check that the private environment or helper is available in this shell. Never paste a token into the command.
- **A mock marker appears:** `RAIDER_PROVIDER=mock` was selected, or a live credential could not be resolved. Check provider and credential setup before interpreting the response.
