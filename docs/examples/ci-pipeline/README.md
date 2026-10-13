# Captured local CI execution

This directory contains sanitized excerpts from actual local CI runs. Each
native graph retains its original node IDs, parent links, input references, and
data references. Tool arguments, private classpaths, full transcripts, and model
prompts are omitted. These excerpts are for documentation and are not replay inputs.

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
The retained first attempt is not evidence that the corrected application has
completed a new run.

The first application index also lost two failed stages' native references and
used an inconsistent name for reviewer B. The original index is preserved in
the excerpt. `nativeRuns` contains the six validated graphs recovered directly
from their artifact directories. The current source fixes reference retention
and stage naming; the first index must not be treated as a correct six-run
linkage table.
