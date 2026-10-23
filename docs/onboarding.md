# Start using Raider

This guide starts with the installed headless CLI and a deterministic mock run.
It requires Java 21 or newer, but does not require sbt, Python, a source checkout,
or provider credentials. The local release is not a hosted download: obtain its
directory from the release maintainer. No public download URL is currently
available. For source-built Scala REPL and tab-shell instructions, see
[source checkout setup](#source-checkout-setup).

## 1. Install the local JVM distribution

The release directory contains `raider-cli.jar`, `packaging/raider`, and
`SHA256SUMS`. Verify the supplied release before copying files, then create a
private install directory. Replace the example paths with paths on your system:

```sh
RELEASE_DIR="/path/to/release/0.1.1"
INSTALL_DIR="$HOME/.local/raider/0.1.1"
(cd "$RELEASE_DIR" && shasum -a 256 -c SHA256SUMS)
mkdir -p "$INSTALL_DIR"
cp "$RELEASE_DIR/raider-cli.jar" "$INSTALL_DIR/raider-cli.jar"
cp "$RELEASE_DIR/packaging/raider" "$INSTALL_DIR/raider"
printf '%s\n' '0.1.1' > "$INSTALL_DIR/version"
chmod +x "$INSTALL_DIR/raider"
export PATH="$INSTALL_DIR:$PATH"
java -version
raider --version
raider --help
```

`java -version` must report Java 21 or newer. `raider --version` should print
`raider 0.1.1`, and `raider --help` should show the headless CLI commands. The
launcher uses Coursier when `cs` is on `PATH`; otherwise it runs the adjacent JAR
with Java. This headless JAR does not include the Scala compiler or JLine, so it
does not provide `raider repl` or the tab shell. `packaging/homebrew/raider.rb`
and `packaging/scoop/raider.json` are local formula templates, not evidence of
Homebrew/Scoop installation or hosted artifacts.

## 2. Run a useful deterministic first task

From a project directory you trust, run the built-in scout in mock mode. This
reads the project and writes a report without contacting a model or editing
files:

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

## 3. Choose a source-built interactive interface

The REPL and tab shell are source-checkout interfaces, separate from the
headless installed package. They require JDK 21, sbt, and Python 3 for the
example build and use the checkout's classpath. In the tab shell, typed task
text is a draft; it is not submitted to an agent. Continue with the
[source-checkout setup](#source-checkout-setup) below.

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
