# BillingCi example (six independent native roots)

`BillingCi.scala` is the application behind the
[CI pipeline guide](../../../docs/user-guide-ci.md): repair a deliberately
wrong billing rule, verify with an independent pinned compile+test process,
collect two inline-source reviews and three boundary reviews (bounded
`batch`, parallelism 2), then write a typed decision and an application-owned
index of the run artifacts.

## Lineage: what this example produces

- Each of the six headless invocations writes its own SINGLE-ROOT lineage
  artifacts (`lineage.scala`, `lineage-data.json`) beside its `events.jsonl`.
  Each invocation is an independent native root with its own budget; the
  `pipeline-index.json` the application writes links their artifact
  directories and creates no native edges between roots.
- The application composes stages with the shipped DSL verbs only:
  `Program.andThen`, `all`, and `batch.collect` plus `Task` sequencing. There
  is no `pipeline` or `parallel` API.

## The unified shape (not produced by this example)

The unified lineage shape for this same pipeline — repair → checks → reviews
→ boundary batch as ONE phase graph with real delegation edges — is
demonstrated on real generated artifacts by the RAI-146 acceptance corpus
(`contracts/lineage/unified/`, case `case-1-billing`) and the checked-in
rendered program
[`docs/examples/ci-pipeline/unified-lineage/lineage.scala`](../../../docs/examples/ci-pipeline/unified-lineage/lineage.scala)
(SHA-256 pinned to the corpus). The shipped export/join
(`raider.cli.run.MultiJobExport`, ADR-032) assembles that shape when delegate
children run inside ONE headless run; this example launches separate
invocations instead and stays honest about the difference. The guide section
[Unified lineage for this pipeline shape](../../../docs/user-guide-ci.md#unified-lineage-for-this-pipeline-shape)
documents the semantics (ADR-031 dry-run projection; not a rerun or replay
entrypoint) and the current limitations (live-wire gated; no live phase
emission yet; per-level anchoring before nested delegation ships).

## Build and run

See [the CI guide](../../../docs/user-guide-ci.md) for the exact commands
(`scripts/build/user-ci.sh`, the live opt-in switch, and the three program
arguments). The captured offline evidence lives in
[`docs/examples/ci-pipeline/`](../../../docs/examples/ci-pipeline/README.md).
