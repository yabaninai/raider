# Run a Scala coding workflow in local CI

Use this example to see Raider repair a small Scala bug, verify the change, and
produce a machine-readable decision. The fixture's discount should apply at **10,000
cents or more**. Its original code uses a strict `>` comparison, so the
exact-threshold case fails.

The application is [`BillingCi.scala`](../examples/user-guide/ci/BillingCi.scala).
It copies the fixture into a fresh output directory, runs a baseline check, asks an
agent to repair the bug, runs an independent compiler and test check, gathers two
source reviews and three boundary reviews, and writes `READY` or `BLOCKED`.

The checks use 9,999, 10,000, and 10,001 cents to cover both sides of the threshold
and the exact boundary. The app checks compiler/test exit codes and output, then validates the review
answers before it writes `READY`.

## Set up and run

You need JDK 21, `sbt`, and a configured OpenAI-compatible provider. Install
`jq` for the result-inspection commands below; see [setup](onboarding.md) if you
have not built Raider before. The example
requires model `glm-5.3-flash`. Configure `RAIDER_BASE_URL` and either
`RAIDER_API_KEY` or `RAIDER_API_KEY_CMD` using your private environment or
credential helper. Do not put credentials in source files or command arguments.

Build the example and its runtime classpath:

```sh
sh scripts/build/user-ci.sh
```

Set the required non-secret values and opt in to provider calls:

```sh
export RAIDER_PROVIDER=openai
export RAIDER_MODEL=glm-5.3-flash
export RAIDER_LIVE_APPROVED=1
```

Now choose a fresh output directory and launch the workflow:

```sh
RUN_DIR="artifacts/user-ci/run-$(date +%Y%m%d-%H%M%S)"
java -cp "$(cat artifacts/user-ci/classpath.txt)" demo.BillingCi \
  "$(cat artifacts/user-ci/runtime-classpath.txt)" \
  "$RUN_DIR" \
  examples/user-guide/fixture
```

The arguments are the runtime classpath used by the Scala checker, the output
directory, and the fixture directory. The copied project is at `$RUN_DIR/workspace`.

The build creates `artifacts/user-ci/classpath.txt` for the example and
`artifacts/user-ci/runtime-classpath.txt` for the compiler and test processes. Keep those
files in place between build and launch. The output directory is separate, so a
later run gets a new index, logs, workspace, and native run directories.

This command makes live model calls and may incur cost. It is not a dry run. The
workflow has a five-minute overall deadline; each of its six agent runs has a
120-second deadline. Each run has its own attempt limit, so these limits do not make
a shared request or cost cap.

The coding agent runs with the process permissions and tool registry configured for
Raider. The review prompts ask for no tools, and the app checks for tool calls
afterward; those checks are not a sandbox. Run this only with a workspace and
provider configuration you trust.

<a id="captured-ci-run-and-lineage"></a>

## Understand the result

A successful run exits `0` and writes `READY` to `decision.json`, with pipeline
status `SUCCEEDED` in `pipeline-index.json`. `READY` means the baseline compiled and
failed only at the threshold, the repair changed the source, the independent final
compile and test passed, both source reviews passed validation, and all three
boundary reviews passed.

A blocked or failed run normally exits `1`. If the overall deadline expires, the
index is marked `UNKNOWN` and the process exits `24`; some stage may be incomplete.
An agent's answer alone does not establish success: the app checks actual process
exit codes and validates the structured review results.

The recorded successful example contains this decision:

```json
{"verdict":"READY","repairCheckExit":0,"sourceChanged":true,"reviewPasses":2,"boundaryPasses":3,"requiredBoundaryCases":[9999,10000,10001],"detail":"Independent compile and all three expected-discount reviews passed."}
```

This is a historical capture, not the result of a new run on your machine. It was
recorded through the Yabanin coding-plan gateway with requested model
`glm-5.3-flash`. See the [captured six-root
run](examples/ci-pipeline/successful-run.json) and its [complete native Scala
projections](examples/ci-pipeline/native-projections.md).

In your own run, compare `decision.json` with the stage statuses in
`pipeline-index.json`. A `READY` decision should agree with a successful final-check
stage and successful reviews. If they do not agree, use the stage's native
`run.json` and summary to understand whether it failed before producing complete
artifacts.

## Find a stage's files

The output directory contains:

- `decision.json`: the typed application decision.
- `pipeline-index.json`: stage status and links to each native run.
- `baseline-check.log` and `final-independent-check.log`: compiler/test process
  output.
- `workspace/`: the copied fixture, including the repaired source when the run
  succeeds.
- `stages/<stage>/native/work-<id>/`: a stage's native run files, such as
  `run.json`, `events.jsonl`, `summary.md`, and, when available, `lineage.scala` and
  `lineage-data.json`.

Run IDs are assigned at execution time. Read the repair stage's `nativeWorkDir` from
the index rather than guessing it:

```sh
jq '{status, decision, stages}' "$RUN_DIR/pipeline-index.json"
STAGE_DIR=$(jq -r '.stages[] | select(.name == "repair") | .nativeWorkDir' \
  "$RUN_DIR/pipeline-index.json")
cat "$RUN_DIR/$STAGE_DIR/summary.md"
cat "$RUN_DIR/$STAGE_DIR/lineage.scala"
```

To list just stage names, statuses, exits, and artifact directories:

```sh
jq -r '.stages[] | [.name, .status, (.nativeExitCode // "-"), (.nativeWorkDir // "-")] | @tsv' \
  "$RUN_DIR/pipeline-index.json"
```

## Scala composition in the example

The app builds typed `Program` values and composes them with Raider's current DSL.
This excerpt uses the example's actual variable names; it omits setup around these
statements:

```scala
repairProgram = agentProgram("billing-repair")
verifyProgram = Program.fromFunction[AgentOutput, VerifiedRepair]("verify-repair") { attempt =>
  Task(ZIO.attemptBlocking {
    val evidence = validateRepair(setup, attempt)
    VerifiedRepair(evidence.sourceChanged, readUtf8(setup.source), attempt)
  }.mapError(error => RaiderError.OutputValidation(
    Option(error.getMessage).getOrElse("repair verification failed")
  )))
}
repairAndVerify = repairProgram.andThen(verifyProgram)
repaired <- Task.zio(repairAndVerify(repairInput))

reviews <- Task.zio(all(
  reviewProgram(reviewsInput._1),
  reviewProgram(reviewsInput._2)
))

boundaryOutcomes <- Task.zio(batch.collect(boundaryInputs, parallelism = 2) { input =>
  boundaryProgram(input)
})
```

The first composition checks the actual repair attempt before passing its verified
result onward. `all` runs the two reviews together. `batch.collect` processes the
three boundary inputs with at most two active at once and returns their results
in input order. The application
validates the results and creates the decision only after those checks.

The full application also defines the input/output data for each stage, writes
prompts and stage records, and records failures. The excerpt shows
the main composition only; use the linked Scala source for the complete runnable
program.

## Troubleshoot

- **Build says JDK 21 is required:** point `JAVA_HOME` at a JDK 21 installation and
  rerun `sh scripts/build/user-ci.sh`.
- **Missing environment names or invalid model:** configure the private endpoint and
  credential helper, set the required values above, and confirm the model is exactly
  `glm-5.3-flash`. The program rejects other model names.
- **Provider or endpoint error:** check that the configured base URL is an HTTP(S)
  URL without embedded credentials, query, or fragment, and that the provider
  accepts the configured key. Keep secret values out of logs and reports.
- **Exit `1` or `BLOCKED`:** inspect `decision.json`, stage statuses in
  `pipeline-index.json`, then the relevant stage's `summary.md` and transcript.
  Compare `baseline-check.log` and `final-independent-check.log`; the baseline is
  expected to report only `FAIL exact-threshold`, while the final check should
  report `DiscountSpec: PASS 3 boundaries`.
- **Exit `24`:** the overall deadline elapsed. The index may say `UNKNOWN`; inspect
  completed stage artifacts before deciding what to do next.

If a native run has a `summary.md` but no lineage files, use its summary and event
stream first; the lineage files are available only when the run settled and its
events could be reduced.

<a id="unified-lineage-for-this-pipeline-shape"></a>

## Native lineage and the unified view

This live application launches six independent agent runs: one repair, two reviews,
and three boundary reviews. Each writes its own native root and graph;
`pipeline-index.json` links those roots. A separate [unified Scala
projection](examples/ci-pipeline/unified-lineage/lineage.scala) describes the same
kind of pipeline as one delegation graph. It was generated from an offline
example; it is not the output of this live application. It is a dry-run description: it dispatches no
models or tools and is not a replay command. The full current unified projection is
available in that linked file; full historical projections from the six live roots
are linked above.
