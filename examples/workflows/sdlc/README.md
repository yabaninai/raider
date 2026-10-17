# SDLC stages and evidence example

RAI-164 adds ordinary `Program`/`Task` composition for stage handlers and typed,
versioned evidence values. The example illustrates candidate binding and the
versioned JSON projection; it does not dispatch a provider, check process, forge
operation, or release action.

`SdlcEvidence.artifact("check-result", check)` produces the JSON value for an
SDLC evidence artifact. A run-artifact writer can retain this payload alongside
the existing run record or as an events extension. Existing run/event schema v1
is unchanged. This lane supplies the typed projection only; wiring a writer into
headless run persistence remains a separate integration task and is not claimed
as shipped here.

`SDLC-STG-01` through `SDLC-STG-03` are executable in
`modules/core/src/test/scala/raider/core/sdlc/SdlcStagesSpec.scala`.
