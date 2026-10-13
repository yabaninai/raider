# Raider onboarding

This guide starts with a deterministic, typed readiness check that needs no model account. It then points to the compiler-backed Scala REPL and offline coding examples. These instructions describe the current source checkout; they do not claim a published SDK or production readiness.

## Requirements

- JDK 21
- sbt 1.11.4 (`project/build.properties` pins the launcher)
- Python 3 and the JDK `jar` tool (used by the example build and verifier)
- Dependency download access, or dependencies already in the Coursier cache

The build pins Scala 3.9.0 and ZIO 2.1.26 in `build.sbt`. Run commands below from the repository root. The example build uses `/tmp/cc-master` as its default `COURSIER_CACHE`; set that variable first if you need a different cache.

## Build the examples

```sh
sh scripts/build/user-examples.sh
```

Expected success message:

```text
Examples built: artifacts/user-examples/classpath.txt and task-summary.jar
```

The classpath is in `artifacts/user-examples/classpath.txt`; the compiled readiness workflow is `artifacts/user-examples/task-summary.jar`. The build log is `artifacts/user-examples/build.log`.

## First useful command: typed readiness check

`TaskSummary` is a deterministic Scala workflow. It parses three named checks, validates their names and statuses, and emits JSON. A pending check blocks readiness. This command does not contact a model or network:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/readiness --mock
```

Expected behavior: the CLI reports a `BLOCKED` result, with `tests` and `migration` passed and `rollback` pending. The exact `work-<id>` directory name is generated per invocation. Find its `run.json` and `summary.md` beneath `artifacts/user-examples/readiness/work-<id>/`. `run.json` contains the structured result; the summary is for people. A complete check set such as `tests=pass,migration=pass,rollback=pass` produces `READY`. Unknown, duplicate, missing, or invalid checks are rejected. The typed parser/evaluator composition is in [TaskSummary.scala](../examples/user-guide/bundle/TaskSummary.scala).

The expected workflow payload is:

```json
{"decision":"BLOCKED","passed":["migration","tests"],"pending":["rollback"],"action":"Complete: rollback."}
```

`BLOCKED` is a valid workflow result and exits 0; it is the report that tells you a supplied check is pending.

This demonstrates an executable typed workflow and observable artifacts. It does not establish provider quality, readiness of a real release, or the status of external checks.

## Optional: start the Scala REPL

The REPL uses the runtime classpath prepared above. Set the provider explicitly to `mock` so this first session stays local and scripted:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

At the `raider>` prompt, enter Scala definitions and then an example agent request:

```scala
case class Check(name: String, passed: Boolean)
val checks = List(Check("compile", true), Check("tests", false))
checks.filterNot(_.passed).map(_.name)
scout.ask("Explain what a failed check means in one sentence")
```

The first three lines are evaluated by the Scala compiler. The agent reply is a fixed mock response marked `[scripted-fixture]`; it is not analysis of your checkout. Use `:help` for commands and `:quit` to exit. More executable sessions are in the [REPL guide](user-guide-repl.md).

## Next case: offline coding and audit

To run the repository's scripted examples against disposable fixture workspaces, use:

```sh
python3 scripts/quality/user_examples.py --only coding
python3 scripts/quality/user_examples.py --only audit
```

These commands build examples unless `--skip-build` is supplied. The coding case expects the scripted stand to observe the billing boundary failure, apply a hash-guarded source edit, and pass the boundary checks afterward. The audit case expects a report file with a finding and remediation. They make no paid provider calls and preserve checked-in fixtures. Each invocation writes a `report.json`, captured stdout/stderr, and input hashes under a generated `artifacts/user-examples/verification-*/` directory. That report ties this run to its recorded input hashes; it is evidence for the scripted example only, not a general product readiness claim.

See the [examples guide](user-guide.md) for the full case set, [CI guide](user-guide-ci.md) for the local CI example, and [lineage walkthrough](user-guide-lineage.md) for run trace files. The [lineage reference](lineage.md) covers schemas and contracts.

## Optional provider configuration

Direct OpenAI-compatible and Anthropic-compatible transports are available in the source. Provider setup requires an endpoint, model, and credential. Live usage is opt-in and may incur charges; select the account, model, mode, and spend limit deliberately before sending a request. The examples in this onboarding guide use mock or scripted backends. Existing live example evidence uses the OpenAI-compatible path; do not infer live verification of every provider from it.

For a CLI invocation, choose `--provider openai` or `--provider anthropic` and supply `--base-url` and `--model`. For REPL startup, use `RAIDER_PROVIDER`, `RAIDER_BASE_URL`, and `RAIDER_MODEL`. Supply the secret through a private environment or helper reference; never put its value in argv, a URL, a repository file, or a report. Set `RAIDER_PROVIDER`, `RAIDER_BASE_URL`, `RAIDER_MODEL`, and `RAIDER_API_KEY` in your private environment (or use the credential helper below). After building the examples, the checkout classpath can launch the CLI directly:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider "$RAIDER_PROVIDER" \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --input "Read README.md and summarize this project" \
  --workspace . --out artifacts/live-run
```

Alternatively, set `RAIDER_API_KEY_CMD` to a helper that prints the credential as its first stdout line; the helper has a 10-second limit. The CLI does not accept `--api-key`. Live CLI calls fail preflight when credential resolution is missing. A mock success or `[scripted-fixture]` reply is not live evidence. The offline verifier's `--live` option is a separate explicit switch: set `RAIDER_LIVE_APPROVED=1` only after selecting provider, endpoint, model, and budget. Never use it to bypass provider setup.

## Troubleshooting

- **`Examples require JDK 21`**: set `JAVA_HOME` to a JDK 21 installation; the build checks the selected Java executable.
- **The classpath file is missing or empty**: rerun `sh scripts/build/user-examples.sh`; inspect `artifacts/user-examples/build.log` for dependency or compile errors.
- **Dependency resolution fails**: check network access or point `COURSIER_CACHE` at a populated cache, then rerun the build.
- **`RA-CFG` or a missing credential message**: check that the chosen private environment/helper is available to this process. Do not paste the credential into a command.
- **`--api-key is removed`**: configure `RAIDER_API_KEY` or `RAIDER_API_KEY_CMD`; credentials in argv are rejected.
- **`unknown --provider`**: supported values are `mock`, `openai`, and `anthropic`.
- **Missing `--out` or input**: provide `--out <directory>` and exactly one of `--input` or `--input-json`.
- **Bundle/agent conflict**: choose `--agent <name>` or both `--bundle <jar>` and `--workflow <name>`.
- **Mock notice or `[scripted-fixture]` in the REPL**: the reply is scripted. Check `RAIDER_PROVIDER`, endpoint/model settings, and credential resolution before interpreting any response as live.

Scala scripts, bundles, plugins, and repository commands run with the current process user's permissions; workspace checks are not a sandbox for trusted Scala code. Use a disposable workspace for experiments.
