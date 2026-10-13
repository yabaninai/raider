# Raider user guide

Use Raider from its Scala REPL, its tab shell, or its headless CLI. This guide
starts with a model-backed coding task, then shows a scripted demo, an audit,
and a typed check workflow. It describes this source checkout; Raider is still
under development.

## Choose how to work

| Need | Start with | What it does |
| --- | --- | --- |
| Ask Raider to change a project | [Headless coding task](#fix-a-scala-bug-with-a-model) | Runs the `coder` agent against the workspace you name |
| Explore agent requests in Scala | [Scala REPL](user-guide-repl.md) | Evaluates Scala definitions and Raider requests in one session |
| Keep several tabs and drafts | [Tab shell](user-guide-tui.md) | Switches between tabs; text typed in a tab is saved as a draft, not evaluated as Scala |
| Run one repeatable workflow in CI | [Local CI guide](user-guide-ci.md) | Runs checks, reviews, and a typed decision |

## Prepare the checkout

From the repository root, install JDK 21 and sbt 1.11.4. Build the example
classpath and bundle:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

The build uses the pinned Scala 3.9.0 and ZIO 2.1.26 dependencies. The classpath
lets you start the CLI and REPL directly from this checkout. For provider setup
and troubleshooting, see [onboarding](onboarding.md).

## Fix a Scala bug with a model

This example copies a small Scala billing project into a temporary directory.
The policy is “discount 1,000 cents when the subtotal is at least 10,000
cents”; the fixture has a strict `>` comparison, so the exact boundary fails.

### 1. Configure a provider and make a disposable workspace

Set the OpenAI-compatible endpoint and model for your account. Make the
credential available to the process through your private environment or
`RAIDER_API_KEY_CMD` helper; do not put the key in a command argument, URL, or
project file. For a key already provisioned in the environment, the CLI reads
`RAIDER_API_KEY`.

```sh
: "${RAIDER_BASE_URL:?Set your OpenAI-compatible endpoint URL}"
: "${RAIDER_MODEL:?Set the model name for that endpoint}"
WORKSPACE=$(mktemp -d)
cp -R examples/user-guide/fixture/. "$WORKSPACE/"
RUN_OUT="$PWD/artifacts/my-billing-run"
mkdir -p "$RUN_OUT"
```

`WORKSPACE` is the copy the agent may change. Keep your original project
outside it while learning the workflow. Check your provider, endpoint, model,
and spend limit before making a live request.

### 2. Give the agent the task

Create a checker in that copy. It compiles the fixture's source and test, then
runs the three boundary assertions. The classpath comes from the setup step.

```sh
cat > "$WORKSPACE/check.sh" <<EOF
#!/bin/sh
set -eu
mkdir -p "$WORKSPACE/build/classes"
java -cp "$RAIDER_CP" dotty.tools.dotc.Main \
  -classpath "$RAIDER_CP" -Werror -d "$WORKSPACE/build/classes" \
  "$WORKSPACE/src/main/scala/billing/Discount.scala" \
  "$WORKSPACE/src/test/scala/billing/DiscountSpec.scala"
java -cp "$WORKSPACE/build/classes:$RAIDER_CP" billing.DiscountSpec
EOF
chmod +x "$WORKSPACE/check.sh"
```

The check before the edit should compile and then exit 1 with
`FAIL exact-threshold`. The same check should exit 0 after the fix and print
`DiscountSpec: PASS 3 boundaries`.

Run the built-in coding agent with an explicit provider, endpoint, model, and
workspace:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider openai \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --workspace "$WORKSPACE" \
  --input "Read the source and tests. Run ./check.sh before editing and observe the expected exact-threshold failure. Fix only Discount.scala so the discount applies at 10000 cents. Run ./check.sh again and report its result." \
  --out "$RUN_OUT"
```

This sends a live model request and lets the built-in `coder` use workspace
read/search/edit and process tools. The model's explanation is not proof that
the repair works; the compiler and test output are.

### 3. Check the result yourself

Run the checker again outside the agent, inspect the changed source, and read
the CLI's run summary. The CLI prints the generated work directory in its
result line; `--out` is the parent directory for that run.

```sh
"$WORKSPACE/check.sh"
cat "$WORKSPACE/src/main/scala/billing/Discount.scala"
find "$RUN_OUT" -maxdepth 2 -type f -print
```

Look for `run.json`, `summary.md`, `transcript.json`, and `events.jsonl` under
the printed `work-<id>` directory. Confirm the final process exit and the
`DiscountSpec: PASS 3 boundaries` output yourself. The workspace and commands
run with your process permissions; use a disposable copy for this example.

## See the same bug case without a live model

Run the fixed-response demo with the local scripted stand:

```sh
python3 scripts/quality/user_examples.py --only coding
```

It drives the real Scala tools through the billing repair sequence, but its
predetermined responses demonstrate wiring and checks, not model reasoning.

## Create an audit report

The audit fixture has `allow_plaintext_fallback = true` in
`config/payments.conf`. Run the offline report example:

```sh
python3 scripts/quality/user_examples.py --only audit
```

The expected `AUDIT.json` identifies the file and line, quotes the setting,
and gives an impact and remediation. The example works in a disposable copy;
the source configuration stays unchanged. Its local scripted response does
not establish that a model independently found the issue.

<a id="2-typed-release-readiness-workflow"></a>

## Check release readiness from typed input

The compiled `task-summary` bundle validates three supplied statuses. It
reports a pending check; it does not compile code, run a migration, or decide
whether the statuses are true.

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/readiness --mock
```

Expected `summary.md` result:

```json
{"decision":"BLOCKED","passed":["migration","tests"],"pending":["rollback"],"action":"Complete: rollback."}
```

Set `rollback=pass` to get `READY`. `BLOCKED` is a valid workflow result and
the command exits successfully; missing, duplicate, unknown, or invalid
checks are rejected. The implementation is
[`TaskSummary.scala`](../examples/user-guide/bundle/TaskSummary.scala). See
[the Scala tutorial](user-guide-repl.md) for `Program`, `Task`, `andThen`, and
parallel typed requests.

## Run a local CI workflow

The [Billing CI example](user-guide-ci.md) repairs the billing fixture, checks
it independently, runs parallel reviews, and writes a typed decision plus an
index of its run artifacts. It uses live provider calls when launched in live
mode. Read its provider and budget requirements before running it.

The checked-in [successful capture](examples/ci-pipeline/successful-run.json)
contains six independent native run roots. The application index links those
roots; it does not make them one native graph. A separately generated unified
lineage program comes from an offline acceptance corpus. The [complete six
historical projections](examples/ci-pipeline/native-projections.md) and
[unified program](examples/ci-pipeline/unified-lineage/lineage.scala) show the
two structures side by side. A lineage projection describes recorded calls;
it is not a command to repeat them.

## Read a run's trace

For the coding task above, begin with `summary.md` and `run.json` in the
generated work directory. Then use `lineage.scala` to find call order and
follow each `data:` reference into `lineage-data.json`. `transcript.json` and
`events.jsonl` contain the model/tool exchange and process results. These
files explain what ran; compare them with the actual changed source and check
output before you decide the task is done. For a guided example, see the
[lineage walkthrough](user-guide-lineage.md).

Recorded lineage can also be compared with a replayed graph by the `replay`
command. The comparison is not playback of a recorded mutating repair: a
projection does not execute its calls. In particular, do not treat a graph
match as proof that a source edit was repeated or rolled back.

## Start in the REPL or tab shell

The REPL is useful when you want to define Scala values and compose requests.
Start it with the classpath from setup:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

The mock provider returns fixed `[scripted-fixture]` responses. The REPL
evaluates ordinary Scala; its tutorial covers live provider configuration,
typed asks, and jobs.

To open the separate tab shell, use `--tabs`:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main --tabs
```

Tab shell text is saved as a draft. It does not evaluate Scala or turn draft
text into an agent request. See the [tab shell guide](user-guide-tui.md) for
its commands and current behavior, and the [Scala REPL guide](user-guide-repl.md)
when you want compiled Scala interaction.

## Common setup problems

- **The classpath file is missing or Java is rejected:** rebuild with JDK 21 and inspect `artifacts/user-examples/build.log`.
- **A live call reports missing credentials:** privately provide `RAIDER_API_KEY` or configure `RAIDER_API_KEY_CMD`; never pass a key as an argument.
- **The run refuses to start:** include `--out`, exactly one input flag, and either `--agent` or both `--bundle` and `--workflow`.
- **The agent says it passed but tests do not:** check the actual compiler/test exit and output. If lineage is absent, inspect `run.json`, `summary.md`, and `events.jsonl`.
