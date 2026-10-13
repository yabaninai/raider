# Raider onboarding

This guide takes you from a checkout to a first Scala REPL session, then to an offline coding run and a compiled workflow bundle. It describes the current source checkout; it does not imply a published Scala SDK or a release-ready product. For more runnable scenarios, see the [examples guide](user-guide.md) and [REPL and bundle gallery](user-guide-repl.md). For run evidence and lineage files, see the [session lineage guide](lineage.md).

## Requirements

- JDK 21
- sbt 1.11.4 (the pinned version is in `project/build.properties`)
- Python 3 and the JDK `jar` tool for the user-example build and verifier
- Dependency resolution access, or a populated Coursier cache

The examples use Scala 3.9.0 and ZIO 2.1.26. Run commands from the repository root. The example build script checks that Java is JDK 21 and uses `/tmp/cc-master` as its default Coursier cache; set `COURSIER_CACHE` to use another location.

## Build the classpath and start the REPL

Prepare the runtime classpath and compiled example bundle:

```sh
sh scripts/build/user-examples.sh
```

The script writes `artifacts/user-examples/classpath.txt` and `artifacts/user-examples/task-summary.jar`. The first build may resolve dependencies. Start Raider’s compiler-backed Scala 3 REPL directly with Java:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

Use the direct classpath launch for interactive or piped REPL input. Starting it through sbt can redirect stdin. The mock provider makes no model request; responses carry `[scripted-fixture]` and are fixed examples, not answers based on your codebase.

At the `raider>` prompt, try a real Scala definition and a DSL request:

```scala
case class Check(name: String, passed: Boolean)
val checks = List(Check("compile", true), Check("tests", false))
checks.filterNot(_.passed).map(_.name)
scout.ask("Explain what a failed check means in one sentence")
```

The first three lines are evaluated by the Scala compiler. The `scout` request runs through the mock fixture in this setup. Type `:help` for REPL commands; `:quit` exits. The [REPL gallery](user-guide-repl.md) includes the verified compiler example and a parallel billing review.

### Billing review example

The repository’s sixth REPL example asks `scout` to identify a boundary defect and `worker` to propose expected test values for the same Scala expression. It compiles and runs as Scala source in the REPL:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/06billing-review.repl
```

The script checks that both labelled responses are non-empty and prints `EXAMPLE-06 PASS`. Its fixed mock replies demonstrate DSL composition, not model reasoning. The example can be used with a live OpenAI-compatible model only when an endpoint, model, and spend budget have been deliberately selected; details are in the [REPL gallery](user-guide-repl.md#6-parallel-billing-review-with-typed-labels).

## Run offline coding and audit examples

The coding and audit examples run the native headless CLI against local scripted OpenAI-compatible stands. They make no live provider request. The stand checks the expected tool sequence and prior results, while the actual `coder` agent reads and changes a disposable fixture workspace.

```sh
python3 scripts/quality/user_examples.py --only coding
python3 scripts/quality/user_examples.py --only audit
```

The coding case starts with a Scala billing discount defect at exactly 10,000 cents. It checks that the initial test fails, the agent reads and searches the source, applies a hash-guarded edit from `>` to `>=`, and then compiles and runs the boundary tests successfully. The audit case reads and searches a payment configuration, then writes `AUDIT.json` with the finding and remediation. Both cases preserve the checked-in fixtures.

To run the CLI yourself, first build its assembly jar with `make raider-jar`. A headless invocation uses the `run` subcommand, a built-in agent, an input, and an output directory:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent coder --provider mock \
  --input "Read README.md and summarize this project" \
  --workspace . --out artifacts/first-run
```

The mock `coder` uses fixed model responses; it is not a general offline coding model. The built-in agent names are `scout`, `worker`, and `coder`. In headless mode, `coder` has workspace file and process tools; `scout` and `worker` do not. `--out` is required. `--input` and `--input-json` are alternatives. Choose either `--agent <name>` or both `--bundle <jar> --workflow <name>`.

## Configure a provider

For one-shot `run`, choose the provider with `--provider openai` or `--provider anthropic`, and set `--base-url` and `--model` for the selected endpoint. For REPL startup, set `RAIDER_PROVIDER`, `RAIDER_BASE_URL`, and `RAIDER_MODEL`. The supported provider values are `mock`, `openai`, and `anthropic`. Both transports encode tool definitions. The examples in this guide were exercised through the OpenAI-compatible path; structured Anthropic tool-result content mapping remains a separate W05 obligation.

Supply credentials by environment variable or helper reference. Never put a credential value in an argument, URL, repository file, or report. For example, provision the credential in your secret manager, then export the environment variable in your shell session:

```sh
export RAIDER_API_KEY="$(your-secret-manager-command)"
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent coder --provider openai \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "Read README.md and summarize this project" \
  --workspace . --out artifacts/live-run
```

Alternatively, configure `RAIDER_API_KEY_CMD` with a helper command that prints the credential as its first stdout line. The helper has a 10-second limit. Do not use a real paid endpoint without an explicitly selected account, mode, model, and spend budget. The CLI rejects `--api-key`; live CLI chat/run also fails preflight when credentials are missing. The REPL reports missing live credentials and continues with its scripted path, so check the startup notice and `[scripted-fixture]` marker before treating output as live.

For live OpenAI-compatible coding demonstrations, see the opt-in procedures in the [examples guide](user-guide.md). The billing review prompt in example 06 asks for text and test cases without tools. A successful mock run does not establish model quality.

## Compile and run a workflow bundle

The examples build script compiles `examples/user-guide/bundle/TaskSummary.scala` and packages it as `artifacts/user-examples/task-summary.jar`. Run the compiled workflow through the same headless CLI:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check --mock
```

This deterministic Scala workflow validates three required checks and reports `BLOCKED` while `rollback` is pending. It makes no model request. `--mock` supplies the CLI backend configuration; it does not change the workflow into a model call. Missing or invalid checks are rejected.

## Find run artifacts

The `--out` path is a parent directory. Each invocation creates a separate `work-<id>` directory beneath it, for example:

```text
artifacts/first-run/work-<id>/
├── run.json
├── summary.md
├── events.jsonl
├── transcript.json
├── lineage.scala
└── lineage-data.json
```

`run.json` records the status, exit code, accounting, and output; `summary.md` gives a human-readable result; `events.jsonl` records the run’s event envelopes; `transcript.json` records the model-loop trace. Settled runs whose event stream can be reduced also get `lineage.scala` and `lineage-data.json` in the same directory. Lineage writing is best-effort, and an unavailable lineage result is reported in the summary. The [lineage guide](lineage.md) explains the artifacts and their limits. The examples verifier stores its own reports, captured command output, and input hashes under `artifacts/user-examples/verification-*/`.

## Common setup errors

- **`Examples require JDK 21`** — point `JAVA_HOME` at JDK 21; the example build script checks the Java executable it will use.
- **Missing or empty `artifacts/user-examples/classpath.txt`** — rerun `sh scripts/build/user-examples.sh` and inspect `artifacts/user-examples/build.log`.
- **`RA-CFG` for missing live credentials** — set `RAIDER_API_KEY` or `RAIDER_API_KEY_CMD` before a live CLI call. The CLI checks before dispatching a request.
- **`--api-key is removed`** — pass credentials through the environment or helper; the CLI deliberately refuses secret values in argv.
- **`unknown --provider`** — use `mock`, `openai`, or `anthropic`.
- **`--out` required / missing input** — pass `--out <directory>` and exactly one of `--input` or `--input-json`.
- **Bundle/agent argument conflict** — use `--agent <name>` alone, or provide both `--bundle <jar>` and `--workflow <name>`.
- **Provider-specific tool behavior differs** — Anthropic tool definitions are implemented; structured tool-result content mapping remains incomplete. The documented CI example uses the OpenAI-compatible path.
- **The REPL shows `[scripted-fixture]` or a mock notice** — the session is using the deterministic fixture backend. Check `RAIDER_PROVIDER`, endpoint/model settings, and credential resolution before interpreting a response as live output.

For typed exit codes, CLI artifacts, and trust boundaries, see [ci-runtime.md](ci-runtime.md). Scala scripts, bundles, plugins, and repository commands run with the process user’s permissions; workspace checks do not sandbox trusted Scala code. Use disposable workspaces for experiments.
