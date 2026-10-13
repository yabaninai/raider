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

Install JDK 21, sbt 1.11.4, and Python 3. From the repository root, build the example
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

Follow [model setup](onboarding.md#connect-a-model) first. This case uses an
OpenAI-compatible endpoint. The helper creates the disposable workspace, gives
the coder a compile/test command, and checks the resulting source and tests.

```sh
: "${RAIDER_BASE_URL:?Set your OpenAI-compatible endpoint URL}"
: "${RAIDER_MODEL:?Set the model name for that endpoint}"
RAIDER_PROVIDER=openai RAIDER_LIVE_APPROVED=1 \
  python3 scripts/quality/user_examples.py --only coding --live
```

This makes live model calls using your configured credentials. The expected
change is `s > 10000` to `s >= 10000`, with these test results:

```text
Before the edit: FAIL exact-threshold
After the edit:  DiscountSpec: PASS 3 boundaries
```

The command prints the run-artifact directory. The copied project is under
`artifacts/user-examples/verification-<id>/workspace/`, and its result report is
`artifacts/user-examples/verification-<id>/report.json`. Check the final source
and test results there. The original fixture stays unchanged.

## Work on your own project

Use `coder` to give a model access to a project you choose. Work on a branch or
disposable copy, and replace the path and task below with your own. Include the
command that checks the change so the agent knows how to finish.

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
WORKSPACE='/absolute/path/to/your/project-copy'
java -cp "$RAIDER_CP" raider.cli.Main run \
  --agent coder --provider "$RAIDER_PROVIDER" \
  --base-url "$RAIDER_BASE_URL" --model "$RAIDER_MODEL" \
  --workspace "$WORKSPACE" \
  --input "Fix the discount boundary bug. Keep public signatures unchanged. Run sbt test and report the changed files and test result." \
  --out artifacts/project-task
```

Replace `sbt test` if your project uses a different check command. The CLI uses
the provider flags above; set them through [model setup](onboarding.md#connect-a-model).
Unlike the fixture helper, this command works directly in `WORKSPACE`. It can
change files and run processes with your permissions.

Raider prints a `work-<id>` path beneath `artifacts/project-task`. Read its
`summary.md` for the result and `transcript.json` for tool output. In your
project copy, review `git diff` and run the relevant checks yourself before
accepting the change. See [reading a run](user-guide-lineage.md).

## See the same bug case without a live model

Run the fixed-response demo with the local scripted stand:

```sh
python3 scripts/quality/user_examples.py --only coding
```

It drives the real Scala tools through the billing repair sequence, but its
responses follow a fixed script; the file edits and Scala checks still run.

## Create an audit report

The audit fixture has `allow_plaintext_fallback = true` in
`config/payments.conf`. Run the offline report example:

```sh
python3 scripts/quality/user_examples.py --only audit
```

The expected `AUDIT.json` identifies the file and line, quotes the setting,
and gives an impact and remediation. The example works in a disposable copy;
the source configuration stays unchanged. The report content comes from the scripted demo.

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

Expected workflow output, included in `summary.md`:

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
index of its run artifacts. It makes live provider calls. Read its provider and budget requirements before running it.

The walkthrough includes a successful run and links to the complete Scala
projections, so you can compare a result with the workflow that produced it.

## Read a run's trace

For the coding task above, begin with `summary.md` and `run.json` in the
generated work directory. Then use `lineage.scala` to find call order and
follow each `data:` reference into `lineage-data.json`. `transcript.json` and
`events.jsonl` contain the model/tool exchange and process results. These
files explain what ran; compare them with the actual changed source and check
output before you decide the task is done. For a guided example, see the
[lineage walkthrough](user-guide-lineage.md).

## Common setup problems

- **The classpath file is missing or Java is rejected:** rebuild with JDK 21 and inspect `artifacts/user-examples/build.log`.
- **A live call reports missing credentials:** privately provide `RAIDER_API_KEY` or configure `RAIDER_API_KEY_CMD`; never pass a key as an argument.
- **The run refuses to start:** include `--out`, exactly one input flag, and either `--agent` or both `--bundle` and `--workflow`.
- **The agent says it passed but tests do not:** check the actual compiler/test exit and output. If lineage is absent, inspect `run.json`, `summary.md`, and `events.jsonl`.
