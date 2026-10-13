# Raider user handbook

Use this guide to choose a working example, run it from this checkout, and
inspect what its result proves. Raider is a Scala 3 and ZIO runtime for coding workflows.
It is under active development; the guides use a source checkout. Examples below use the current
source checkout and pinned local fixtures.

## Choose a case

| If you want to… | Start here | What the result demonstrates |
| --- | --- | --- |
| Repair a real source defect offline | [Billing repair](#repair-a-billing-boundary-offline) | Native tools change a disposable Scala project; independent compile and tests pass |
| Record an audit finding offline | [Payment audit](#audit-payment-configuration-offline) | The report cites a source line and the input config remains unchanged |
| Compose parallel typed requests | [Parallel asks](#compose-parallel-asks-in-scala) | Scala types and labels survive two concurrent requests |
| Gate a release from supplied check results | [Typed release bundle](#check-release-readiness-with-a-typed-bundle) | A Scala workflow returns `READY` or `BLOCKED`; it does not run the checks |
| See a successful local CI execution | [Captured live CI](#inspect-the-recorded-live-ci-success) | A recorded OpenAI-compatible gateway run repaired, checked, reviewed, and returned `READY` |
| Follow model and tool calls | [Lineage](#follow-lineage-and-read-the-evidence) | Projection references resolve to data and run records |

## Prepare once

Install JDK 21, sbt 1.11.4, and Python 3, then work from the repository root. The example build
resolves the pinned Scala and ZIO dependencies (or uses the available Coursier
cache), compiles the examples, and prepares the CLI classpath and release
bundle:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

The build creates `artifacts/user-examples/task-summary.jar`. The verifier
below runs the actual compiler, CLI, workspace tools, and local scripted
provider stands (small servers that return scripted model responses). It writes a fresh report and command logs under
`artifacts/user-examples/verification-*/`. Example selectors are `coding`,
`audit`, `repl-review`, `bundle`, `artifacts`, and the individual REPL cases.

## Repair a billing boundary offline

The fixture's policy gives a 1,000-cent discount for purchases of at least
10,000 cents. Its bug is a strict comparison:

```scala
def discountCents(s: Int): Int = if s > 10000 then 1000 else 0
```

Run the offline case:

```sh
python3 scripts/quality/user_examples.py --only coding
```

This case copies the Scala fixture to a temporary workspace and connects the
real `coder` agent, workspace tools, compiler, and test process to a local
scripted OpenAI-compatible stand. The stand supplies a predetermined sequence
of requests; it does not reason about the repository. The sequence reads and
searches the source, compiles and tests before editing, applies a SHA-guarded
edit from `>` to `>=`, then compiles and tests again.

Expected observable result, checked by the verifier:

```text
before edit: FAIL exact-threshold       (test exit 1)
after edit:  DiscountSpec: PASS 3 boundaries (test exit 0)
```

The final function uses `s >= 10000`. The verifier checks request/tool order,
process exits, hashes, the final source, and that the checked-in fixture was
not changed. The generated summary marks the run `[scripted-billing]`. To
inspect the report and logs for that invocation, list the newest report path
printed by the verifier or run:

```sh
find artifacts/user-examples/verification-* -path '*/coding/work-*' -type d -print
```

The report's `codingWorkDir` identifies the native run directory. The
disposable Scala project is in `workspace/` beside that report. This is an offline scripted case, not a live model evaluation.

## Audit payment configuration offline

The fixture contains `allow_plaintext_fallback = true` at line 2. Run:

```sh
python3 scripts/quality/user_examples.py --only audit
```

The local stand directs the agent through tree, read, search, and edit tools in
a disposable copy. The expected `AUDIT.json` finding includes the path
`config/payments.conf`, line `2`, matched setting, impact, and remediation. The
verifier checks the report fields against the source, confirms the fixture
configuration is byte-identical, and confirms the report was created. This
case tests the audit workflow and report contract; the scripted model response
does not independently discover the risk. Open `audit-workspace/AUDIT.json`
beside that invocation's `report.json`. Its expected finding includes:

```json
{
  "id": "PAY-001",
  "severity": "high",
  "evidence": {
    "path": "config/payments.conf",
    "line": 2,
    "matched": "allow_plaintext_fallback = true"
  },
  "impact": "Payment requests may use plaintext transport when the primary route is unavailable.",
  "action": "Set allow_plaintext_fallback = false and verify the production transport policy before deployment."
}
```

This is one finding inside the report's `findings` array. The complete report
also has `schemaVersion: 1` and `status: "action_required"`. The example writes
the report; applying its recommendation is a separate change.

## Compose parallel asks in Scala

Calling `scout(prompt)` or `worker(prompt)` constructs a lazy typed ask. `map`
labels its eventual response, and `all(...).run()` executes both requests and
returns a tuple:

```scala
val code = "if subtotalCents > 10000 then 1000 else 0"
val policy = "A flat 1000-cent discount applies at 10000 cents and above."

val reviewAsk = scout(
  s"$policy Review this expression: $code. Give one boundary counterexample. Do not use tools."
).map(answer => ("boundary-review", answer))

val testsAsk = worker(
  s"$policy Return only JSON pairs [subtotalCents, expectedDiscountCents] " +
    "for 9999, 10000, and 10001. Do not use tools."
).map(answer => ("test-plan", answer))

val (review, plan) = all(reviewAsk, testsAsk).run()
```

The expected policy values are `[[9999,0],[10000,1000],[10001,1000]]`. Run the
checked-in fixture with the deterministic backend:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/06billing-review.repl
```

Expected marker: `EXAMPLE-06 PASS`. In mock mode, fixed fixture replies only
demonstrate Scala compilation, ask composition, and result labels. The
verifier's opt-in live review is a separate scope: it checks the response
against the stated policy, forbids tool requests, and rejects fallback to a
scripted fixture. See [the REPL gallery](user-guide-repl.md) for type queries,
ordered batches, jobs, and sessions.

<a id="2-typed-release-readiness-workflow"></a>

## Check release readiness with a typed bundle

`TaskSummary.scala` accepts three named check results (`tests`, `migration`,
and `rollback`), validates them, and produces JSON. Its `Program` stages are
typed and composed with `andThen`; the bundle makes no provider request and
does not run the checks itself.

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check \
  --mock
```

Expected workflow payload (shown after `SUCCEEDED — output:` in `summary.md`
and inside the output record in `run.json`):

```json
{"decision":"BLOCKED","passed":["migration","tests"],"pending":["rollback"],"action":"Complete: rollback."}
```

Changing `rollback=pending` to `rollback=pass` produces a successful `READY`
decision with no pending checks. That only means the three supplied statuses
passed validation. Missing, duplicate, unknown, or malformed checks fail input
validation (CLI exit 21). The full executable bundle is
[`TaskSummary.scala`](../examples/user-guide/bundle/TaskSummary.scala).

## Inspect the recorded live CI success

The source tree includes a sanitized capture of an actual local CI application
run. It records a successful OpenAI-compatible coding-plan gateway execution:
process exit 0, pipeline `SUCCEEDED`, decision `READY`, 70.87 seconds, an
independent compile, three passing boundary assertions, two accepted general
reviews, and three accepted boundary reviews. The capture records six
independent native roots (repair plus five reviews); the application index
links them but does not create graph edges or a shared budget.

Open the [captured result](examples/ci-pipeline/successful-run.json) and
[capture notes](examples/ci-pipeline/README.md). They preserve the original
execution identity and six native graph records. This is historical recorded
evidence, not a run performed by this guide. For a fresh local CI invocation,
the separate [CI walkthrough](user-guide-ci.md) documents the application,
build, required provider configuration, and output paths. Its execution can
make paid provider calls and requires explicit endpoint, model, account, and
budget selection.

## Follow lineage and read the evidence

For an offline coding run, the verifier report points to its disposable
workspace and native run directory. Find a run directory from the generated
report, then inspect its files:

```sh
# Replace <id> with the directory printed by the coding verifier.
REPORT='artifacts/user-examples/verification-<id>/report.json'
RUN_DIR=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["codingWorkDir"])' "$REPORT")
find "$RUN_DIR" -maxdepth 1 -type f -print
cat "$RUN_DIR/run.json"
cat "$RUN_DIR/summary.md"
cat "$RUN_DIR/lineage.scala"
```

`run.json` records run status and exit information; `summary.md` is the human
readable result; `events.jsonl` is the event stream; `transcript.json` records
the model/tool exchange. When reduction succeeds, `lineage.scala` projects
the call graph and `lineage-data.json` holds the referenced node data. In the
offline repair, follow a `data:` reference in the projection into the matching
`lineage-data.json` entry, then compare its tool identity with the transcript
and process output. `summary.md` and the native run say what happened; the
application-level verifier report says which conditions it accepted.

The recorded successful CI capture has six historic native root records. The
sanitized JSON contains each root's full `scalaProjection` plus its node and
data records. Use
[the CI capture walkthrough](user-guide-ci.md#captured-ci-run-and-lineage)
for context and the [six-root capture](examples/ci-pipeline/successful-run.json)
to inspect each complete projection, or read the [six full Scala blocks](examples/ci-pipeline/native-projections.md).
These are separate roots, not the unified lineage program. The complete
generated unified DSL artifact has a different evidence scope: it is derived
from the frozen acceptance corpus, not from the captured live CI run. See the
[artifact provenance and wiring limits](examples/ci-pipeline/unified-lineage/README.md),
the [complete generated program](examples/ci-pipeline/unified-lineage/lineage.scala),
and the [unified lineage section](user-guide-ci.md#unified-lineage-for-this-pipeline-shape).

The CLI replay command compares graph
structure from recorded lineage with a replayed graph and reports a match or
the first divergence. It is not a general playback of recorded mutating tool
effects. The current `ReplayHarness` executable fixture uses a recorded model
response and a read-only `fs_read` operation; it does not establish that a
recorded file repair can be automatically replayed. A mutating rerun must be
treated as real execution in an isolated workspace, with the current guard
and its typed refusal behavior. Do not use a replay result as evidence that an
external mutation happened exactly once or was rolled back.

For detailed graph/data links, replay modes, and current limits, read the
[lineage walkthrough](user-guide-lineage.md) and [lineage contract guide](lineage.md).

## Common troubleshooting

- **The classpath file is missing or empty:** rerun `sh scripts/build/user-examples.sh` and inspect `artifacts/user-examples/build.log`.
- **The build rejects Java:** use JDK 21 and check `JAVA_HOME`.
- **The REPL prints `[scripted-fixture]`:** it is using the deterministic fixture provider. That is expected in the mock example; it is not a live answer.
- **A live verifier refuses to start:** `--live` requires `RAIDER_LIVE_APPROVED=1`, `RAIDER_PROVIDER=openai`, `RAIDER_BASE_URL`, and `RAIDER_MODEL`, plus credentials from the approved environment/helper. Select an account and spend budget before opt-in.
- **`--bundle` or `--workflow` is rejected:** pass both together, or use `--agent`; do not combine the two invocation forms.
- **The bundle says `BLOCKED`:** inspect `pending` and complete the listed upstream check before supplying `pass`. The bundle validates supplied values; it does not run tests or migrations.
- **Lineage files are absent:** some failed or unsettled runs may not yield a reducible graph. Check `run.json`, `summary.md`, and `events.jsonl`; lineage writing is best-effort.
- **The captured six-root run is mistaken for unified lineage:** the stage index links separate graphs. Unified lineage is currently demonstrated by the corpus-derived artifact; the runtime phase and `coder` delegation wiring follow-ups remain (RAI-153/154).

## Scope and safety

Offline coding and audit examples use local scripted responses and real Scala
tools in disposable copies; they verify wiring, tool order, reports, and
process outcomes, not model reasoning. The successful CI capture is recorded
live evidence with the scope stated above. The unified lineage program is
corpus-derived evidence with its own provenance. These are different kinds of
evidence and must not be merged into one claim.

Scala code, bundles, and process tools run with the current process user's
permissions. Tool policy does not sandbox arbitrary Scala. Use trusted
workspaces and bundles, keep credentials outside source/arguments/reports, and
do not make paid calls without a selected account, endpoint, model, and budget.
