# Start using Raider

Use this page to set up a checkout, start Raider, and choose a first way to work: the tabs interface, Scala REPL, or command line. Commands assume you are in the repository root.

## 1. Set up Java and build

Raider's examples require JDK 21. Install a JDK 21 distribution, then replace `/path/to/jdk-21` below with its actual installation path. This sets Java for the current shell and puts its tools first on `PATH`:

```sh
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

The version output should identify Java 21. The project pins sbt 1.11.4 and Scala 3.9.0. Build the runtime classpath and example bundle once:

```sh
sh scripts/build/user-examples.sh
```

The script prints a success line naming `artifacts/user-examples/classpath.txt` and `task-summary.jar`. It writes the full build log to `artifacts/user-examples/build.log`. Keep this shell open and load the classpath:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

If you open a fresh terminal later, return to the repository root, set `JAVA_HOME` and `PATH` again, and run the `RAIDER_CP=...` command there. The build itself need not be repeated unless the checkout changes.

## 2. Choose how to start

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

### Command line

The CLI runs an agent task without an interactive prompt. Its `--out` path is a parent directory; Raider creates a `work-<id>` folder inside it. A mock task uses fixed responses, so use it to learn the command shape rather than to expect a real code change:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider mock \
  --input "Read README.md and summarize the project" \
  --workspace . --out artifacts/first-run
```

The command prints a result line with the work directory. Look there for `run.json` and `summary.md`; when produced, `transcript.json` contains the run trace. The built-in `coder` has file and process tools. Use a disposable workspace for tasks that may edit files.

## 3. Configure a live model

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

## 4. Give the coder a real task

After configuring a live provider, try a bounded task in a disposable copy of a project. For example, ask it to inspect a small bug, make a focused edit, and run the relevant test. Set `RAIDER_WORKSPACE` to the absolute path of that copy:

```sh
export RAIDER_WORKSPACE="/path/to/disposable/project"
```

Then run:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider "$RAIDER_PROVIDER" \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "Find and fix the boundary bug in the discount calculation, then run its focused test." \
  --workspace "$RAIDER_WORKSPACE" --out artifacts/bug-fix
```

`RAIDER_WORKSPACE` is the path of the disposable project copy; define it before running the command. Review the diff and test output in that workspace, and inspect `run.json`, `summary.md`, and `transcript.json` in the printed work directory. A successful call shows what this configured model did on this task; it does not guarantee the same result on another project.

For more runnable scenarios, see the [examples guide](user-guide.md). To run the local CI example, see the [CI guide](user-guide-ci.md); for run history and lineage, see the [lineage guide](user-guide-lineage.md).

## Common setup problems

- **Java is not version 21:** check that `JAVA_HOME` points to the JDK installation and that `$JAVA_HOME/bin` comes first on `PATH`.
- **Build cannot resolve dependencies:** check network access, or set `COURSIER_CACHE` to a populated Coursier cache and rerun the build.
- **Classpath file is missing:** run `sh scripts/build/user-examples.sh` from the repository root and inspect `artifacts/user-examples/build.log`.
- **Live CLI reports missing credentials:** check that the private environment or helper is available in this shell. Never paste a token into the command.
- **A mock marker appears:** `RAIDER_PROVIDER=mock` was selected, or a live credential could not be resolved. Check provider and credential setup before interpreting the response.
