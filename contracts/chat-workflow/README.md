# Chat-workflow verifier corpus (RAI-155.e, CWF-E01..E03)

Frozen expectation corpus of the INDEPENDENT end-to-end verifier for the
chat-to-edited-Scala-to-CI chain (card RAI-155.e). The verifier drives real
product entry points only:

- capture: `ChatLoop.processMessage` (the real per-turn capture path),
- explicit selection: `TaskCommands.mark` / `TaskCommands.registerCheck` /
  `TaskCommands.select` (the real `:task` implementations),
- export: `ChatLoop.exportWorkflow` (the real `:export-workflow` command path),
- compile: `WorkflowCompile.command` (the real `raider compile`, pinned dotc),
- local launch: the compiled launcher `main` run as a plain subprocess
  (`java -cp <classes>:<cp> <launcherMain> <input>` — Option L),
- CI launch: `raider.cli.Main workflow run …` as a fresh non-TTY subprocess.

The verifier never reuses implementation internals as oracles: expectations in
`expectations/*.json` are derived from ADR-033 §4–§9, the ci-runtime exit
table, and the card acceptance texts. Generator output is never pinned as a
snapshot; determinism is asserted by byte-identity of repeated exports and by
pairwise equality of normalized run outcomes.

## Scenario (the owner's demonstration requirement)

A multi-turn repair-style chat session (one ordinary turn, one intent turn,
two refinement turns — the ordinary turn is deliberately NOT selected, proving
selection is explicit), export of the editable launcher, a MEANINGFUL manual
edit of the exported source (prompt text AND check parameter), pinned
compilation, then the SAME edited artifact run by both launchers on two input
cases over four fresh workspace copies, pairwise-compared normalized results,
plus a FIFTH fresh run of the UNEDITED workflow on input 1 to isolate the
observable effect of the manual edit.

- Publish target of the unedited workflow: `marker.txt` (the intent turn text
  carries the `PUBLISH-TARGET: marker.txt` token; the trusted check is
  `/bin/test -f marker.txt`).
- The manual edit flips BOTH the prompt template token
  (`PUBLISH-TARGET: marker.txt` → `PUBLISH-TARGET: marker2.txt`) and the check
  argv (`/bin/test -f marker.txt` → `/bin/test -f marker2.txt`).
- The scripted local OpenAI-compatible stand keys its tool instruction on the
  rendered prompt token (that is what a scripted provider stand is: the prompt
  text is the only thing a real model would see). The agent stage itself is
  REAL: the AgentLoop parses the SSE frames, invokes the real process tool
  (`/usr/bin/touch <target>` in the workspace) and produces a real final
  answer; the trusted check is an independent process on the NEW workspace.

## Normalization spec (deterministic)

`normalize(run)` keeps exactly:

| field | local launcher | ci launcher |
| --- | --- | --- |
| `launcher` | `local-main` | `raider-workflow-run` |
| `exitCode` | process exit value | process exit value (== run.json exitCode) |
| `report` | full trimmed stdout (the Program report) | run.json `output.json` decoded from its JSON string form |
| `publishTarget` | marker basename present in the fresh workspace | same |
| `effects` | sorted list of publish-marker basenames existing in the fresh workspace after the run | same |
| `taskInstance` | input `task.summary` token | same |
| `sourceDigest` | sha256 of the compiled source bytes | run.json `buildIdentity.sourceDigest` (stripped `sha256-` prefix) |

`normalize` strips as volatile: root/session ids, `work-*` directory names,
`startedAt`/`finishedAt`/`atMs` timestamps, event `seq` numbers and envelope
timestamps, absolute workspace paths, loopback ports, compiler logs, and all
stdout noise outside the report.

Pairwise rule (card CWF-E01): for the same input case,
`normalize(local) == normalize(ci)` in every kept field EXCEPT `launcher`
(which identifies the launcher surface, not the outcome); different input
cases must differ observably; the edited run and the unedited control run on
input 1 must differ in `report`, `effects` and `sourceDigest` while both being
successful.

## External prerequisites (declared, not hidden)

1. Pinned toolchain jars under `artifacts/tools/m2` (Scala 3.9.0 dotc), as
   resolved by the product `raider compile` (WorkflowCompile). Missing jars
   fail the verifier with an explicit toolchain error — never a skip.
2. A loopback scripted OpenAI-compatible stand (JDK HttpServer on an ephemeral
   port, SSE frames). No live provider, no paid calls, no network beyond
   127.0.0.1. A stand startup failure fails the affected cases.

## Files

- `expectations/CWF-E01.json` — normal matrix expectations (CWF-E01).
- `expectations/CWF-E02.json` — negative corpus expectations (CWF-E02).
- `expectations/CWF-E03.json` — cancellation/concurrency expectations (CWF-E03).

Every missing/timeout/skipped verifier case is a non-pass. No fixture in this
corpus hand-authors a workflow: every compiled/run source in the verifier is
produced by `:export-workflow` from a captured session (the only deliberately
corrupted source is the invalid-edit negative case, derived from a real
export).
