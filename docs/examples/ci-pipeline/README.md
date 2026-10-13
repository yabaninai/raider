# Captured local CI execution

This directory contains sanitized excerpts from actual local CI runs. Each
native graph retains its original node IDs, parent links, input references, and
data references. Tool arguments, private classpaths, full transcripts, and model
prompts are omitted. These excerpts are for documentation and are not replay inputs.

## Successful run

[Successful capture](successful-run.json): the corrected application completed
with process exit 0, pipeline status `SUCCEEDED`, and decision `READY` in
70.87 seconds. The independent compiler and three boundary tests passed;
both general reviews and all three boundary reviews were accepted. The capture
records the actual source revision and SHA-256, the application index, and the
six native graphs linked by that index.

| Stage | Native exit | Model nodes | Tool nodes |
| --- | ---: | ---: | ---: |
| `repair` | 0 | 9 | 10 |
| `independent-review-a` | 0 | 1 | 0 |
| `independent-review-b` | 0 | 1 | 0 |
| `boundary-9999` | 0 | 1 | 0 |
| `boundary-10000` | 0 | 1 | 0 |
| `boundary-10001` | 0 | 1 | 0 |

These are 24 nodes across six independent native roots. They are linked by the
application index, with no native edges between roots. The exporter records
`ReadOnly` for the repair's `fs_patch` recovery annotation; the patch did mutate
the source. That annotation is not permission, sandboxing, or replay evidence.
The full original artifacts remain in ignored `artifacts/user-ci/run-fixed` in
the integration checkout; the excerpt intentionally excludes full payloads.

## First attempt

The first pipeline repaired the Scala bug and passed the independent compiler
and three boundary assertions. Its strict JSON decoder rejected several fenced
model replies, so the decision was `BLOCKED`. The initial application also hung
after settling its index; the coordinator stopped
that finished invocation. The original failed receipt remains unchanged.

The current application corrects exit ownership, gives parallel boundary tasks
separate context files, preserves native evidence during verdict updates, and
accepts one complete JSON code fence before applying the same field/value checks.

[Captured first attempt](first-blocked-run.json):

| Stage | Native exit | Model nodes | Tool nodes |
| --- | ---: | ---: | ---: |
| `boundary-10000` | 0 | 1 | 0 |
| `boundary-10001` | 0 | 1 | 0 |
| `boundary-9999` | 0 | 1 | 0 |
| `independent-review-a` | 0 | 1 | 0 |
| `independent-review-b` | 0 | 1 | 0 |
| `repair` | 0 | 8 | 10 |

All six native invocations completed successfully; the application verdict was
still `BLOCKED`. Native completion and CI approval describe different outcomes.

The capture records the source revision and fingerprint that actually ran.
The retained first attempt describes only the earlier failed revision. The
separate successful capture above records execution of the corrected application.

The first application index also lost two failed stages' native references and
used an inconsistent name for reviewer B. The original index is preserved in
the excerpt. `nativeRuns` contains the six validated graphs recovered directly
from their artifact directories. The current source fixes reference retention
and stage naming; the first index must not be treated as a correct six-run
linkage table.
