# Portable workflow examples (RAI-203)

Two runnable portable workflow v1 documents with input examples. Each document
passes `PortableWorkflowValidator` and lowers through
`PortableWorkflowLowerer` against a stand registry; the test
`modules/dsl/src/test/scala/raider/dsl/portable/PortableRecipePairSpec.scala`
executes both end to end with deterministic outcome checks.

## Recipes

- `checked-repair/` — one pinned agent extracts a task candidate, a declared
  bounded repair (`repair`, `maxAttempts: 3`, `safeOperationRef:
  normalize-task-v1`) normalizes it, and a trusted check
  (`normalized-task-shape`) verifies the normalized shape.
  - `input-success.json` — a well-formed task id; the run finishes green.
  - `input-repair-exhaustion.json` — an empty task id; the safe operation
    fails deterministically, the repair exhausts its declared attempt bound of
    3, and the run ends typed `LocalBudgetExceeded` before the check runs.
- `extraction-classification/` — a read-only extractor agent runs as the
  template of a bounded fan-out (`map`, `maxItems: 4`, `parallelism: 2`) over
  input documents, and a trusted check (`classification-labels-valid`)
  verifies every extracted label.
  - `input-two-documents.json` — two classifiable documents; the run finishes
    green.
  - `input-unclassifiable.json` — one document carries no classifiable
    signal; the extraction stays honest (empty label) and the trusted check
    reports `status: failed` — invalid extraction does not pass.

## Trust model

The documents NAME authorities; they never carry them:

- agent steps name a `profile` + `toolset` pair (for example `coder` +
  `workspace`, `extractor` + `read-only`). The launcher or stand that runs the
  document pins the actual program behind that name. A document cannot widen
  or rename pinned authority — the lowering refuses a profile that is not in
  the pinned registry (`untrusted-authority-reference`).
- trusted checks name a `checkId` plus a `registrySnapshot` digest
  (`sha256:...`). The digest is the launcher-side provenance of the pinned
  check definition; a stale or foreign snapshot fails resolution. The snapshot
  convention is the NUL-joined check material (check id + pinned definition
  material), SHA-256 encoded:
  - `normalized-task-shape` =
    `"normalized-task-shape\u0000trusted-stand:normalized-output-shape-v1"`
    -> `sha256:bb788ad23f74c3c1e5d610f0e4ff8c317ec4b8fc8fc738d3180a59671cc09456`
  - `classification-labels-valid` =
    `"classification-labels-valid\u0000trusted-stand:classification-labels-v1"`
    -> `sha256:69baafd85b2d35be2ffcfc66150ff9a0f8f5f0f3ff73686729e3c64999ad2dff`
- the repair's `safeOperationRef` names a safe operation; the launcher/stand
  registry owns the program behind the name. The document declares the retry
  bound (`maxAttempts`); an uncertain outcome is never repeated automatically
  and exhausting the bound is a typed refusal.

Running these recipes therefore requires a registry that pins exactly these
entries. The test spec in `raider.dsl.portable` is such a stand (mock mode:
every decision is a deterministic Scala function, no model call, no paid
call). A production launcher may pin the same names over a live provider —
that is a launcher-owner decision recorded by the launcher, not something
these examples can do by themselves. The shipped reference launcher
(`raider.cli.portable.PortableTrustedRegistry`) pins only `coder` +
`workspace` and `workspace-marker-present`, with empty `safeOperations`, so
running these recipes against the reference launcher as-is refuses typed
(`unsupported-construct` for the repair; `untrusted-authority-reference` for
the `extractor` profile).

## Input conventions

- The workflow root binds to the first entry of `outputs.bindings`
  (`run`); only that subtree executes, within ONE root budget
  (`limits.deadlineMs`, `attempts`, `children`) and ONE cancellation scope.
- A `map` step resolves its `items` pointer at run time against the run
  input. `#/inputs/documents` therefore requires the input JSON to nest the
  documents under an `"inputs"` key: `{"inputs": {"documents": [...]}}`.
- Template steps (`classify-item`) read the current item through `#/item`.
  Their declared `input` must start with `#/item` or `#/index` — escaping the
  template scope is refused by the validator.
- Bounded fan-out stays inside the root budget: more items than `maxItems`
  (4) or more children than `limits.children` (8) fail typed
  (`LocalBudgetExceeded`) BEFORE any item is dispatched.

## Modes

- Mock mode (what the tests execute): Scala stand programs provide every
  authority; outcomes are deterministic and reproducible in CI.
- Live mode (not exercised by these examples): a launcher pins the same
  profile/toolset/check/safe-operation names over a live model backend. The
  documents do not change; only the pinned registry does. The verdict always
  comes from the trusted check, never from the agent text.
