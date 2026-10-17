# From a chat task to an edited Scala workflow

This walkthrough is a local, scripted demonstration of the RAI-155 path. It uses
the real Raider `ChatLoop`, task-selection commands, exporter, pinned Scala
compiler, compiled local launcher, and non-TTY `workflow run` command. The chat
and run-time model responses come from a loopback OpenAI-compatible scripted
stand in the E01 verifier; this is not a live-model quality result and makes no
paid calls.

## What is being demonstrated

One captured task is exported as ordinary Scala. The export's manifest records
the authoring source turns; it is provenance, not an executable definition and
not a run history. Editing the generated Scala changes the future run: the
prompt asks to publish `marker2.txt`, and its trusted check tests for
`marker2.txt`. The task input remains external JSON. Each launch creates fresh
agent work in a fresh workspace; it does not replay the chat's historical
answer, tools, or mutations.

The complete files are available beside this guide:

- [complete generated source, before editing](cwf_e01.generated.scala)
- [complete source after the demonstrated manual edit](cwf_e01.edited.scala)
- [the exact two-line behavioral diff](cwf_e01.edit.diff)
- [export manifest from the actual run](../../../artifacts/rai-155f/e01/cwf-e01-a/cwf_e01-manifest.json)
- [exported input example](../../../artifacts/rai-155f/e01/cwf-e01-a/cwf_e01.input.json)

The example IDs and digests below are real values from this lane's captured
execution. The reusable inputs shown later use placeholders for workspace paths;
the evidence files under `artifacts/rai-155f/e01/` are the exact artifacts from
the run.

## Chat transcript and explicit selection

Build the CLI from a clean checkout and start chat against an OpenAI-compatible
endpoint you explicitly control. Credentials are supplied through the
environment/helper mechanism; never put a key in a command, transcript, source,
or report. For example, use your endpoint URL in place of the local stand URL:

```sh
make raider-jar
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat \
  --provider openai --base-url "$OPENAI_BASE_URL" --model "$OPENAI_MODEL"
```

The E01 verifier drives these actual user turns through `ChatLoop.processMessage`
with a scripted local OpenAI-compatible stand (the stand port is ephemeral):

```text
thanks, keep this note as capture context only
VERIFIER-INTENT-9d21: publish the module marker now; PUBLISH-TARGET: marker.txt
create only the publish target file and nothing else
verify the publish target exists before reporting done
```

In the real chat interface, mark the intent and refinements explicitly, register
the independent check, and select the last three task turns (not the ordinary
context-only first turn):

```text
:task mark 2 intent
:task mark 3 refinement
:task mark 4 refinement
:task check marker /bin/test -f marker.txt
:task select 3
:task status
```

The verifier exercised the matching real `TaskCommands.mark`, check registration
and selection path: four captured completed turns, with turns 2–4 selected and
turn 1 excluded. It exported twice and confirmed the three output files were
byte-identical. The source provenance is session `loop_1`, turns
`t-2-b08b8e05`, `t-3-7b043f40`, `t-4-20d7b2ef`; those IDs are also in the actual
manifest. `:task select` without a marked intent or incomplete/unsupported turn
is refused; rerun the missing task in chat, mark its turns, then select again.

Export with valid flags. `--summary` consumes the rest of the line and is
therefore last:

```text
:export-workflow --out artifacts/chat-workflow --name cwf_e01 --target-path marker.txt --summary first-input-summary-token
```

`--target-path` and `--summary` are mandatory because they are explicit
per-run parameters, not values inferred from the chat. Expected success prints
the source, manifest, input example, and SHA-256 identities. The actual captured
manifest records an export source digest of
`sha256-0a749727e10ac4213ab3c119026f59e569745c5e0e82e1d5cecbe39cb3d2a68e`.
Missing selection, missing parameters, secrets, symlink targets, a missing
parent, a staging remnant, or overwriting an existing target are refusals; fix
the named condition and use a new empty output directory. E02's refusal and
hostile-input checks passed (see [actual E02 log](../../../artifacts/rai-155f/e02.log)).

## Edit and compile

The generated file is normal editable Scala. This demonstration changes the
requested side effect and the independent check together:

```diff
- PUBLISH-TARGET: marker.txt
+ PUBLISH-TARGET: marker2.txt
...
- val checkMarker: List[String] = List("/bin/test", "-f", "marker.txt")
+ val checkMarker: List[String] = List("/bin/test", "-f", "marker2.txt")
```

The edit is meaningful: the edited workflow now requests a different target and
only passes its named trusted check when that target exists. The captured user
task's unedited requested target remains `marker.txt`. The full before/after
files and [exact diff](cwf_e01.edit.diff) are provided above. Compile the
edited file; use a new output directory for each compile:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar compile \
  docs/examples/chat-workflow/cwf_e01.edited.scala --out artifacts/cwf-e01-build
```

The successful E01 compilation used Scala 3.9.0 and produced
`artifacts/rai-155f/e01/build-edited/build-identity.json`. Its edited-source
digest is `2090e877480196a533c257b9b8d126d3e279cbea547e53cde9aba80b88dc089a`,
distinct from the pre-edit export digest. Invalid Scala is a compile failure;
fix the reported source error and compile to a new empty output directory.

## Run two inputs through both launchers

Prepare two separate fresh copies of a fixture repository and one input JSON per
copy. An input has this shape; set `workspace` to that copy's absolute path:

```json
{
  "schemaVersion": 1,
  "workspace": "/absolute/path/to/fresh-copy-1",
  "task": { "targetPath": "marker.txt", "summary": "first-input-summary-token" }
}
```

For input two use another fresh workspace and set `summary` to
`second-input-summary-token`. Keep the same edited compiled classes for both
launchers and both inputs. The local route executes the generated main with the
compiled classes and Raider runtime classpath; the non-TTY route uses the
product CLI command below. A caller supplies a real local-compatible provider
endpoint and its credential through the normal helper/environment setup. The
verifier's equivalent local launcher is a fresh Java subprocess, while its CI
launcher is `raider.cli.Main workflow run` as a fresh non-TTY subprocess against
the same live loopback stand.

```sh
# Generic non-TTY launcher, input 1 (repeat with input 2 and a distinct --out)
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar workflow run \
  --classes artifacts/cwf-e01-build --input /absolute/path/input-1.json \
  --out artifacts/cwf-e01-ci-1 \
  --source docs/examples/chat-workflow/cwf_e01.edited.scala
```

The local-main route used in the verifier is equivalent to:

```sh
java -cp "artifacts/cwf-e01-build/classes:modules/cli/target/scala-3.9.0/raider-cli.jar" \
  runCwf_e01 /absolute/path/input-1.json
```

Use a separate copy, input file, and `--out` for each of these four runs:

| Run | Input | Launcher | Observed result |
| --- | --- | --- | --- |
| 1 | `first-input-summary-token` | compiled local main | exit 0; edited target published; trusted check passed |
| 2 | `first-input-summary-token` | `workflow run` (non-TTY) | exit 0; `Succeeded`, `trusted-checks-passed` |
| 3 | `second-input-summary-token` | compiled local main | exit 0; edited target published; trusted check passed |
| 4 | `second-input-summary-token` | `workflow run` (non-TTY) | exit 0; `Succeeded`, `trusted-checks-passed` |

The independent check was also executed on the fresh first workspace before
agent work: both the old `marker.txt` check and the new `marker2.txt` check
failed (nonzero, as expected). After the edited workflow ran, the
`marker2.txt` check passed and the old `marker.txt` check failed. This is the
pre-repair failure and post-repair success; the model's text alone is not the
verdict. Different task inputs produced different normalized results, while
same-input local and CI results matched.

## Fifth run: isolate the edit

For the fifth run, compile/run the unedited generated source on input one in a
fifth new fixture copy. It exited 0 and published `marker.txt`, whereas the
edited run published `marker2.txt`; both trusted checks passed for their
respective sources. The result's actual CI identity and report are under
[`ci-out-control`](../../../artifacts/rai-155f/e01/ci-out-control/).

For edited CI runs, actual artifacts include
[`run.json`](../../../artifacts/rai-155f/e01/ci-out-1/work-a9c65549/run.json),
[`summary.md`](../../../artifacts/rai-155f/e01/ci-out-1/work-a9c65549/summary.md),
and `events.jsonl`. The first edited CI run ID is `wf_955e397c`; its run
artifact records source digest
`2090e877480196a533c257b9b8d126d3e279cbea547e53cde9aba80b88dc089a`, exit 0,
and `trusted-checks-passed`. The fifth run ID is `wf_9ac3e838` and records the
unedited digest. All five runs used the same scripted stand for *new* model
requests; chat answers and authoring turns were not replayed.

## Lineage, failures, and limits

- **Authoring provenance:** the export manifest points to the captured chat
  session/turns and the pre-edit source. It does not assert a future execution.
- **Execution lineage:** compile identity hashes the edited source and classes;
  each CI `run.json` binds that build identity to its input. Later run history is
  separate from chat-turn provenance. Changing source requires a fresh compile;
  a stale source/build identity is rejected.
- Invalid edits produce a typed compile error. Invalid JSON, a symlink workspace,
  missing credentials, a corrupted build, unsupported input, and a failing
  trusted check fail closed before success. E02 exercises these paths; inspect
  its actual log before diagnosing a failure.
- Provider/network errors are not retries of mutating work. Fix the endpoint,
  credential helper, input or source as appropriate, then explicitly launch a
  new run with a fresh output directory. An interrupted or unknown run is not a
  success; inspect its run/interruption artifact and explicitly start another
  run if desired. E03's bounded SIGTERM and no-automatic-retry cases passed; see
  [actual E03 log](../../../artifacts/rai-155f/e03.log).
- This demonstration uses a scripted local stand, not a live model: it proves
  product path and observable behavior, not model quality. Exported Scala is
  trusted user code with the invoking process's permissions; Raider does not
  sandbox it. No durable resume or exactly-once guarantee is provided for
  arbitrary Scala effects.

## Evidence from this demonstration

The independent E01 verifier's actual log is
[here](../../../artifacts/rai-155f/e01.log); it reports one test passed and no
failures. The preserved source, manifest, build identity, all five compiled/run
outputs, `run.json`, `summary.md`, and `events.jsonl` are under
[`artifacts/rai-155f/e01/`](../../../artifacts/rai-155f/e01/). E02 and E03
verifier logs are stored alongside it. The test covers the exact sequence:
four-turn chat capture, explicit selection, deterministic export, behavioral
edit, pinned compile, 2 inputs × 2 launchers on four fresh workspaces, and the
unedited fifth control run.
