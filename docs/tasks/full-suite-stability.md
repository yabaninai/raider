# RAI-178: Full-suite stability — WIR-01 internal timeout + suite-duration investigation

Status: accepted. Milestone: M2. Dependencies: none.

**Scenario and contract.** After the ZIO.sleep sweep replaced blocking polls at six sites, the focused `MultiJobExportSpec` matrix passed 10/10, but the full `raiderCli/test` suite exceeded the 2700-second tool budget. WIR-01 (`WorkflowLaunchSpec`) hit its internal five-minute deadline, and multiple export/verifier suites also reported internal timeouts. Diagnose WIR-01 under full-suite load with fiber trace/timeline capture and measure per-suite wall times using the sbt test timing report. Determine whether the cause is real-process wall time in WIR/SIGTERM-family fixtures, suite-duration growth from verifier/SDLC/forge suites under unchanged gate budgets, or residual blocking waits in process-based fixtures. Select the minimal justified correction: fixture/process behavior, parallelism/ordering, and/or evidence-based `sbt-test` budget calibration. The `e846d84` repl-smoke/secrets-scan calibration is precedent for measured budget calibration with identical assertions; it is not permission to change an acceptance bar.

**Context.** RAI-157 lane records and the 2026-10-18 board report the green focused 10/10 matrix after the six-site sleep sweep, but a full `raiderCli/test` timeout: WIR-01's internal five-minute deadline plus internal timeouts in export/verifier suites, with total duration beyond the 2700-second tool budget. Suspects remain hypotheses until measured. `e846d84` calibrated gate budgets from measured execution time under contention without changing assertions.

**Allowed paths.** `modules/cli/src/test/**` `scripts/quality-policy/registry.json` `docs/tasks/**` `docs/work/board.md` `docs/work/records/**` `artifacts/**` (diagnostic evidence only). Confirm concrete fixture and registry paths before assignment; no other build, verifier, or gate-policy changes are authorized.

**Acceptance.**

- Normal: WIR01-STAB-01: capture an actionable WIR-01 fiber trace/timeline and per-suite sbt wall-time report from the full `raiderCli/test` suite; correlate internal deadlines and test process activity. Identify blocking-wait evidence or explicitly rule it out for the implicated fixtures.
- Error/adversarial: WIR01-STAB-02: distinguish fixture/process wall-time and suite-load effects from a product/test defect; make only the minimal reviewed correction supported by evidence. If calibration is selected, record measured runtime, contention context, and rationale; retain identical assertions and semantics. No expectation weakening, skips, or acceptance-threshold changes.
- Concurrency/cancellation: WIR01-STAB-03: verify the selected fix against process cleanup, cancellation/SIGTERM behavior, and fixture isolation/parallelism as applicable; run the full `raiderCli/test` suite five consecutive times green. Any incomplete or timed-out run is not green evidence.

**Required gates.** Run the exact component/full-suite checks needed to reproduce and close WIR-01, including `raiderCli/test` ×5 consecutive green. Run `make quality-changed` and `make quality-changed QUALITY_EXECUTE=1`, plus applicable static/unit/runtime/CI profiles on the integrated candidate. Preserve logs, timing data, traces, hashes, and non-pass outcomes in the work record; a timeout is not a pass.

**Non-goals.** Weakening expectations; skipping tests; changing acceptance thresholds; unrelated test/gate budget changes; speculative process refactors; claiming a full product/gate pass from focused suites; paid calls or neighboring-repository changes.

**Compatibility/rollback.** No public contract changes. Any `sbt-test` budget calibration must preserve the exact assertions and gate meaning and be limited to the measured profile; revert the minimal fixture or calibration change if it does not address the demonstrated cause.

**Deliverables.** Diagnosis record with WIR-01 fiber trace/timeline, per-suite wall times, cause adjudication and exact evidence; minimal reviewed fixture and/or measured budget calibration if justified; five consecutive green `raiderCli/test` runs and required integrated gates; work record with commands, exit codes, evidence paths/hashes, limitations, and next card.
