# Read a coding run's lineage

Lineage helps answer three questions: what ran, what failed, and what files
changed. Use `jq` for the JSON commands below. A run directory contains a summary, event log, transcript, and—when
the event stream can be reduced—two lineage files. The lineage files organize
the run; the event log and transcript provide the detail behind each step.

## Find a run

Start with the output directory printed by your `raider run` command or set by
its `--out` option. Each completed invocation gets its own `work-<id>`
directory. Replace the placeholder below with the actual path printed by your
run; a new run has a different directory name.

```sh
RUN_DIR='<path-to-the-printed-work-directory>'
cat "$RUN_DIR/summary.md"
cat "$RUN_DIR/run.json"
```

| File | Use it to |
| --- | --- |
| `summary.md` | Get the human-readable run status and exit code. |
| `run.json` | Inspect the structured result, checks, and accounting. |
| `events.jsonl` | Trace ordered events and detailed process/tool results. |
| `transcript.json` | Read the run's conversation and tool trace. |
| `lineage.scala` | See the Scala projection of the recorded graph. |
| `lineage-data.json` | Look up node details using their `dataRef` keys. |

For an example you can read without running Raider, open the
[saved CI result](examples/ci-pipeline/successful-run.json).

## What ran?

Open `lineage-data.json`. Each item in `nodes` has a sequence number (`seq`),
a kind, and usually a `dataRef`. `parentId` shows containment, such as a tool
call under a model call. `inputs` identifies earlier nodes whose results fed
the step. The `data` object maps each `dataRef` to the recorded model or tool
details.

For example, inspect a fresh run without assuming a fixed node ID:

```sh
jq '.nodes[] | {seq, id, kind, parentId, inputs, dataRef}' \
  "$RUN_DIR/lineage-data.json"
jq '.data | to_entries[] | {ref: .key, detail: .value}' \
  "$RUN_DIR/lineage-data.json"
```

For a multi-stage CI capture, `successful-run.json` contains an application
index and six separate native runs. Inspect the stage list and the native
summaries with:

```sh
jq '.pipelineIndex.stages' docs/examples/ci-pipeline/successful-run.json
jq '.nativeRuns[] | {stage, runId, nativeStatus, nativeExitCode, nodes}' \
  docs/examples/ci-pipeline/successful-run.json
```

Those six roots are linked by the application index; they are not one native
graph. The capture's `nativeWorkDir` values identify original artifact
locations, while the checked-in JSON contains sanitized excerpts. The
[pipeline guide](user-guide-ci.md) explains the stages and their outcomes.

## What failed?

Follow the relevant `dataRef` to find the operation, then inspect its process
result in `events.jsonl` or `transcript.json`. A tool call with outcome
`Succeeded` means the tool returned a result. It does not mean the command
that the tool launched passed. In the billing example, the pre-edit test
process exits 1 because the strict discount boundary is still present. The
later edit and passing checks are separate steps. Read `summary.md` and
`run.json` for the overall status rather than inferring it from one node.

## What files changed?

Inspect the workspace used for the run. For the [CI example](user-guide-ci.md),
compare its copied source with the original fixture. Replace the output path
with the one you chose when launching it:

```sh
PIPELINE_DIR='artifacts/user-ci/run-<your-timestamp>'
diff -u examples/user-guide/fixture/src/main/scala/billing/Discount.scala \
  "$PIPELINE_DIR/workspace/src/main/scala/billing/Discount.scala"
```

A successful repair changes `>` to `>=`. `diff` exits 1 when it finds a change;
that is expected here. For a task in a Git project, run `git diff` from that
project's working copy. Check the actual diff and test output before accepting
what the agent changed.

## The Scala projection

The current renderer produces a typed Scala 3 `Program` using `LineageStep`
and `Task` composition. A small excerpt from the current unified artifact:

```scala
val phase_baseline_check: Program[Unit, Vector[StepReport]] =
  Program.fromFunction("phase_baseline_check") { _ =>
    LineageStep.phase("camp-billing:n1", "baseline-check", None, Vector.empty, nesting = 1)
      .flatMap { n_camp_billing_n1 =>
        delegation_ci_baseline(())
          .map { n_camp_billing_n2 =>
            n_camp_billing_n1 ++ n_camp_billing_n2
          }
      }
  }
```

The phase and child steps return structural reports: node kind and ID,
`dataRef`, inputs, and nesting. Payloads stay in `lineage-data.json`. The
projection is a **dry-run description**: executing its
program reports recorded call-graph structure; it performs no model call, tool
call, or I/O. It is not replay or live rerun.

Read the [complete unified lineage Scala artifact](examples/ci-pipeline/unified-lineage/lineage.scala)
with its [how this example was generated](examples/ci-pipeline/unified-lineage/README.md).
For the separate six-root CI capture, see the [historical captured native
projections](examples/ci-pipeline/native-projections.md); those saved
comments-only projections predate the current renderer.

## Replay limits

Replay has limited support for recorded responses and read-only operations.
It cannot replay the saved billing repair shown here. Running a generated
Scala projection only describes the graph; it does not rerun the agent or
repeat its changes. See the [replay reference](lineage.md) for supported inputs.
