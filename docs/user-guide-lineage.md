# Read a coding run's lineage

Lineage helps answer three questions: what ran, what failed, and what files
changed. A run directory contains a summary, event log, transcript, and—when
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

The saved billing example used below lives in an ignored local artifact
directory, so it may not exist in a fresh checkout. For a checked-in example,
use the [sanitized CI capture](examples/ci-pipeline/README.md).

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

The offline billing walkthrough records eight model rounds and seven tool
calls: source read, boundary search, compile and test before the edit, edit,
then compile and test after it. The recorded order comes from `seq`; tool
parent links and input links describe different relationships.

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

Look for recorded mutating operations, then inspect the actual source diff in
the project working copy used for the run. For example, find `fs_patch` tool
entries in a captured pipeline and review the corresponding source file:

```sh
jq '.nativeRuns[].data | to_entries[] | select(.value.tool == "fs_patch")' \
  docs/examples/ci-pipeline/successful-run.json
SOURCE_FILE='path-from-the-recorded-patch'
git diff -- "$SOURCE_FILE"
```

The diff command must run from the relevant project working copy. Tool
arguments show what change was requested or recorded; the workspace diff
shows what actually changed. In the CI capture, the `fs_patch` recovery label
is `ReadOnly` even though the patch mutated source. That label is not a
permission or replay guarantee. See the [capture notes](examples/ci-pipeline/README.md).

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
projection is a **dry-run description**: constructing or executing its
program reports recorded call-graph structure; it performs no model call, tool
call, or I/O. It is not replay or live rerun.

Read the [complete unified lineage Scala artifact](examples/ci-pipeline/unified-lineage/lineage.scala)
with its [provenance and evidence notes](examples/ci-pipeline/unified-lineage/README.md).
For the separate six-root CI capture, see the [historical captured native
projections](examples/ci-pipeline/native-projections.md); those saved
comments-only projections predate the current renderer.

## Replay limits

Replay consumes recorded responses and supports only operations handled by the
replay harness. The saved billing export lacks model responses, and the checked
replay fixture demonstrates a read-only `fs_read` sequence. This guide does
not claim that mutating repair replay works or provide a repair recipe. Replay
uses an isolated workspace copy; live rerun is a separate, guarded path. See
[Session lineage](lineage.md) for current status and limitations.
