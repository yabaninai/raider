# Raider user handbook

This handbook walks through four useful tasks from the current checkout: fix a
Scala billing defect, write an audit finding, compose typed Scala requests, and
inspect a run's lineage. Commands and code below are drawn from
`examples/user-guide/` and the current CLI implementation.

## 1. Prepare the examples

From the repository root, use JDK 21, sbt 1.11.4, Python 3, and a populated
Coursier cache (or network access to resolve the pinned dependencies). Build
the example classpath and release bundle, then run the offline verifier:

```sh
sh scripts/build/user-examples.sh
python3 scripts/quality/user_examples.py
```

The verifier runs the real Scala compiler, compiler-backed REPL, bundle loader,
CLI, workspace tools, and subprocess tool. It writes a report, command output,
and hashes under `artifacts/user-examples/verification-*/`. To run one scenario,
use `--only coding`, `--only audit`, `--only repl-review`, or `--only bundle`.

To use the REPL directly, load the generated classpath and feed it one of the
checked-in Scala scripts:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/01compiler.repl
```

That script compiles a value, case class, typed function, and multiline
definition. Its assertion precedes the `EXAMPLE-01 PASS` marker. More REPL
examples, including sessions, background jobs, and typed parallel asks, are in
the [REPL guide](user-guide-repl.md).

## 2. Repair the billing boundary

The fixture defines a flat 1,000-cent discount for purchases of at least
10,000 cents. Its implementation has a strict comparison:

```scala
package billing

object Discount:
  def discountCents(s: Int): Int = if s > 10000 then 1000 else 0
```

The test checks the value below, at, and above the threshold:

```scala
val results = List(
  check(Discount.discountCents(9999), 0, "below-threshold"),
  check(Discount.discountCents(10000), 1000, "exact-threshold"),
  check(Discount.discountCents(10001), 1000, "above-threshold")
)
```

Run the offline coding scenario:

```sh
python3 scripts/quality/user_examples.py --only coding
```

It copies the fixture to a disposable workspace and connects the real `coder`
agent and native workspace tools to a local, fixed OpenAI-compatible script.
The input asks: “Fix the billing discount boundary at exactly 100 dollars.
Compile and run the boundary tests before and after the edit.” The scripted
sequence reads `Discount.scala`, searches for `s > 10000`, compiles, runs the
tests, edits the source with its recorded SHA-256 as a precondition, then
compiles and tests again.

The observable test results are:

```text
before edit: FAIL exact-threshold       (test process exit 1)
after edit:  DiscountSpec: PASS 3 boundaries (test process exit 0)
```

The resulting function uses `s >= 10000`. The verifier checks the tool order,
compile/test exits, before-and-after hashes, final source, and that the checked-in
fixture is unchanged. `summary.md` carries `[scripted-billing]` to identify the
fixed response source.

## 3. Audit configuration and save evidence

The audit fixture contains this setting in `config/payments.conf`:

```hocon
allow_plaintext_fallback = true
retry_limit = 3
```

Run its offline audit scenario:

```sh
python3 scripts/quality/user_examples.py --only audit
```

The agent receives the task to inspect the payment configuration, search for
plaintext fallback, and create `AUDIT.json` without editing the configuration.
The checked-in local script drives `fs_tree`, `fs_read`, `fs_search`, and
`fs_edit` in a disposable copy. The report follows this shape:

```json
{
  "schemaVersion": 1,
  "status": "action_required",
  "findings": [{
    "id": "PAY-001",
    "severity": "high",
    "evidence": {
      "path": "config/payments.conf",
      "line": 2,
      "matched": "allow_plaintext_fallback = true"
    },
    "impact": "Payment requests may use plaintext transport when the primary route is unavailable.",
    "action": "Set allow_plaintext_fallback = false and verify the production transport policy before deployment."
  }]
}
```

The verifier checks the finding fields against the file, confirms that the
configuration bytes did not change, and confirms that `AUDIT.json` was created.
The run directory contains `summary.md` and `transcript.json`; the report itself
is in the disposable workspace. The summary includes `[scripted-audit]` for the
offline scenario.

## 4. Compose Scala requests with typed results

The billing review example builds two lazy asks, labels each answer with a
tuple, then executes both together:

```scala
val billingCode = "if subtotalCents > 10000 then 1000 else 0"
val billingPolicy =
  "A flat $10 (1000 cents) discount applies to purchases of at least $100 (10000 cents)."

val scoutAsk = scout(
  s"$billingPolicy Review this Scala expression: $billingCode. " +
    "Identify the exact boundary defect and give one concrete input/output counterexample. Do not use tools."
).map(answer => ("boundary-review", answer))

val testAsk = worker(
  s"$billingPolicy For this Scala expression: $billingCode, " +
    "specify the expected discounts for 9999, 10000 and 10001 cents. " +
    "Return only a JSON array of [subtotalCents, expectedDiscountCents] pairs. No tools."
).map(answer => ("test-plan", answer))

val (review, plan) = all(scoutAsk, testAsk).run()
```

The checked-in version asserts the labels and non-empty replies. In an
OpenAI-compatible live run, its verifier also checks that the returned JSON
contains `[[9999,0],[10000,1000],[10001,1000]]` after sorting. Run the compiled
REPL example with the deterministic provider using:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/06billing-review.repl
```

It prints `EXAMPLE-06 PASS`. The fixed mock replies demonstrate compilation,
composition, and tuple labels; they do not perform the requested analysis.
See [Scala REPL and typed workflow examples](user-guide-repl.md) for `map`,
`batch`, `openSession`, and bundle workflows.

## 5. Check release readiness from typed inputs

The example bundle implements a `BundleWorkflow` named `task-summary`. Its
program parses comma-separated `name=status` entries, then validates the
required `tests`, `migration`, and `rollback` checks. Statuses are `pass` and
`pending`; missing, duplicate, unknown, or malformed checks fail validation.

Run it with one pending check:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check \
  --mock
```

The workflow makes no model call. The generated `summary.md` contains a
structured decision with these fields:

```json
{"decision":"BLOCKED","passed":["migration","tests"],"pending":["rollback"],"action":"Complete: rollback."}
```

When all required checks pass, the decision is `READY`, `pending` is empty, and
the action is `No follow-up required.` Missing required checks or an invalid
status return CLI exit 21. Treat `READY` as the result of these three supplied
checks; the workflow does not run the checks itself.

## 6. Inspect run artifacts and lineage

Headless runs write a per-invocation directory below the path supplied to
`--out`. A settled run includes `run.json`, `summary.md`, `events.jsonl`,
`transcript.json`, `lineage.scala`, and `lineage-data.json`. The lineage Scala
file is a deterministic projection of the session graph; its data references
resolve through the JSON resource. Together these files let you inspect the
graph, event sequence, run summary, and recorded node data.

The coding scenario above also creates a settled headless run. Its verification
report records the `codingWorkDir`. Find the recorded run directory with:

```sh
python3 scripts/quality/user_examples.py --only coding
find artifacts/user-examples/verification-* -path '*/coding/work-*' -type d -print
```

Set `RUN_DIR` to the `work-<uuid>` directory printed above, then inspect its
files:

```sh
find "$RUN_DIR" -maxdepth 1 -type f -print
cat "$RUN_DIR/run.json"
cat "$RUN_DIR/summary.md"
```

Replay the recorded run against the original workspace:

```sh
raider replay "$RUN_DIR" --workspace examples/user-guide/fixture
```

A matching replay prints `verdict: match` with a normalized graph hash and exits
0. A graph mismatch prints the first divergent node and both hashes, then exits
10. A missing or corrupt `lineage-data.json` is a typed error and exits 21.
Recorded replay makes no model calls. See the [lineage guide](user-guide-lineage.md)
for reading the projection and replay reports.

## 7. Compose a CI pipeline in Scala

[`BillingCi.scala`](../examples/user-guide/ci/BillingCi.scala) describes a local CI workflow that repairs the billing example, runs independent Scala checks, asks two reviewers in parallel, and runs three bounded boundary reviews before producing a typed CI decision. An application-owned index links each stage to its run artifacts.

The six agent invocations (one repair, two parallel reviews, and three boundary reviews) each have their own native run root and lineage artifacts. The pipeline index connects those per-stage records; it is not a unified native graph, shared accounting root, or durable recovery mechanism. The example does not automatically create or merge a pull request. SDK support for that workflow is future work.

See the [CI pipeline guide](user-guide-ci.md) for the stage inputs, outputs, and artifact references. This section describes the example's design and makes no claim that it has been built or run.

## Limits of these examples

The offline coding and audit examples use deterministic local scripts that
select tool calls from observed results. They verify the CLI, Scala checks,
workspace operations, artifact contents, and report contracts. They do not
measure model reasoning. The mock REPL returns fixed responses. Live provider
examples require a deliberately selected endpoint, model, and spend budget;
this handbook does not report new live verification. Repository commands and
Scala programs run with the process user's permissions, so use disposable
workspaces for tasks that can change files.
