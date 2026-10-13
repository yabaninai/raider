# LIN-09: work record

Status: done (with one recorded frozen-layer gap — see Limitations). Date:
2026-10-12. Baseline commit: 831dabf (branch lane/lin-09, clean worktree).

## Resulting behavior

A completed headless run now writes two lineage artifacts to its OWN run directory
(`work-<uuid>/`, alongside events.jsonl/summary.md):

- `lineage-data.json` — frozen schemaVersion-1 document (ADR-027 §3), assembled
  ONLY from the run's reduced graph and its own exported envelopes: graph nodes
  verbatim (id/parent/inputs/seq/atMs/dataRef), with the data resource linking wire
  summaries (ToolStarted.argsSummary, ModelCallFinished usage, outcome,
  recovery class). Written using the accepted atomic store `LineageDataStore`
  (tmp+`ATOMIC_MOVE`, single writer per path).
- `lineage.scala` — deterministic projection through the frozen pipeline
  `SessionGraphView` → `LineageRenderer`; written using the same atomic pattern
  (tmp+`ATOMIC_MOVE`). Rendered output is emitted VERBATIM — the writer does not
  edit renderer output.

Properties:

- Parallel run isolation is structural (LIN9-03): each run has its own
  `work-<uuid>`, with no overlapping paths; tmp files are unique in the run
  directory and do not remain after writing.
- Projection/write failure is typed `ArtifactError` (View/Render/Store/Io),
  rendered as one line `- lineage artifacts: unavailable (<typed>)` in summary.md,
  and NEVER changes the run exit code (LIN9-02). The writer is total
  (NonFatal → Io); no new crash paths in writeArtifacts.
- Artifacts are written ONLY for reducible campaigns (`Some(Right(graph))`):
  preflight refusals and irreducible campaigns write nothing and add no line
  (summary remains byte-compatible with the pre-LIN-03B form).
- On success: `- lineage artifacts: lineage.scala, lineage-data.json (schemaVersion 1)`.
- Content is honest: frozen headless-wire (ADR-024 §5) does not export prompt/
  response text or result tails — `ModelCallData.prompt/response` are written as
  empty strings, `resultTail` is null (the only schema-legal “not recorded” form);
  wire summaries (argsSummary/usage/outcome/recovery) are recorded as-is. Usage
  comes from the real ledger (unknown is not coerced to 0).
- Delegation nodes have no dataRef (wave-1 model, SessionGraphView contract) — no
  data entry; Phase graphs are rejected as typed (in wave-1 Phase has no atMs,
  headless reducer does not create Phase — defensive branch).

Nothing in frozen layers (contracts schema, LineageProjection, LineageDataStore,
events, run.json/events.jsonl/exit codes for 18 families) was changed. CliArgs is
untouched (LIN-10).

## Changed paths

- `modules/cli/src/main/scala/raider/cli/run/RunLineageArtifacts.scala` (new)
- `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala` (writeArtifacts
  call site; envelopes passed through executeAsJob deliverable; LIN-09 scaladoc)
- `modules/cli/src/test/scala/raider/cli/run/LineageArtifactsSpec.scala` (new)

Public API/schema changes: none. New dependencies: none.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN9-01 (valid artifacts, frozen schema) | pass | `LineageArtifactsSpec`: document decodes with frozen store (`LineageDataStore.read`) and validator (`LineageDataFile.read`: schemaVersion 1 + ref integrity); canonical generator block; canonical modelCallData keys; explicit-null parentId; projection == exact output of `SessionGraphView`→`LineageRenderer` over its OWN events.jsonl (checkpoint consistency); pinned dotc 3.9.0 compiles the PRODUCED file (delegation graph, exit 0) |
| LIN9-02 (projection failure → typed diagnostic, normal exit) | pass | Bundle run (nodeless campaign) → `- lineage artifacts: unavailable (Render(Empty))` in summary.md, exit 0, no files created; confirmed by a REAL CLI smoke run (`work-4fac0caf/summary.md`, packaged process) |
| LIN9-03 (parallel runs isolated) | pass | 6 parallel mock runs to one `--out`: each has its own workDir/artifacts, document == its checkpoint render, 0 tmp leftovers, all exit 0 |
| Regression: preflight refusal | pass | no lineage lines or artifacts, exit 21 (byte compatibility) |

Additional check outside the test JVM: real `sbt raiderCli/run run --agent
scout --input task-42 --mock` → work-4c1ed495: lineage-data.json
(schemaVersion 1, generator raider/0.1.0-SNAPSHOT/3.9.0/jdk 27, canonical
keys, usage {reserved 0, observed 0, unknown: true} — honest), lineage.scala
`object lineage_cli_637a41be` + `// model("builtin:scout", round = 1) // data:
j_1/round-1`.

Source sha256 (evidence-fingerprint):

- HeadlessRunner.scala `526bb1421a40534ba05fbbd9bcf805b3bcbb228cdb84f7e28c3414790356e5e0`
- RunLineageArtifacts.scala `c514d5535ce7e63adb27a6620138fe812b8647c0004d0f663e0f45db1bc18630`
- LineageArtifactsSpec.scala `ca1332411094c2c5062c7cf3b0001501c4a86b29e2643ad45a021875bf69087c`

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` (git-mode classification) | 0 | profiles = contracts, static, unit (3 changed files) |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (passed) | `artifacts/quality/20261012-035653-90012-0001/manifest.json` sha256 `b89d8ee5…013ab5`; all 5 gates passed |
| `make quality-profile PROFILES="static,unit,contracts,ci"` | 0 (passed) | `artifacts/quality/20261012-035813-91206-0001/manifest.json` sha256 `2dc1cdb9…f51cd`; sbt-test/cli-smoke/core-contracts-spec/compile-fixtures/sbt-compile/scalafmt-check — all passed |
| `make quality-verify MANIFEST=artifacts/quality/20261012-035813-91206-0001/manifest.json PROFILES=static,unit,contracts,ci` | 0 | `[verify] OK … (policy/toolchain/logs hashes checked, fingerprint fresh)` |
| `sbt raiderCli/testOnly raider.cli.run.LineageArtifactsSpec` | 0 | 6 tests passed, 0 failed |
| `sh scripts/quality/cli_smoke.sh` | 0 | real run: typed `unavailable (Render(Empty))`, exit 0 |

Note: at worktree start, untracked `artifacts/tools/m2` (pinned dotc) was missing
— copied from root checkout per docs/parallel-development.md §1a (runbook lane
preparation, write only in this worktree). Without it compile-fixtures/compile-gate
tests would honestly fail unavailable. Also cleared inherited stale
`modules/{core,runtime}/target` (phantom `ToolCall.id` compile error — stale
classfiles, not sources).

## Limitations / blockers

1. **Frozen-renderer gap (contract-change request, outside card scope).**
   `LineageRenderer` (dsl, frozen) for graphs without named-def roots (only
   ModelCall/ToolCall) renders the object body as COMMENTS ONLY — dotc 3.9.0
   rejects such a body (“indented definitions expected, eof found”), reproduced
   directly. An ordinary headless run (scout/coder) yields exactly this graph:
   the Phase reducer does not create Phase (frozen wire), and the Delegation
   toolset is not connected in headless. Therefore `lineage.scala` for an ORDINARY
   production run exists and is valid as a projection (exact output of frozen
   pipeline, which compile-gate compiles against goldens), but dotc will NOT
   compile it until the renderer provides a non-comment body (for example, `()`
   — checked: commented body + `()` compiles, exit 0). The fix belongs to the dsl
   owner; proposed semantics: renderer emits `()` for the outer scope when there
   are no named-def statements (a one-line frozen-layer change, separate review).
   Card acceptance LIN9-01 “compile-gate green on artifacts” is proven on a
   produced delegation-form artifact (real dotc, exit 0); for plain runs it is
   blocked by this gap.
2. lineage-data.json for plain runs carries empty prompt/response and null
   resultTail — this honestly means “wire does not export text,” not a recording
   defect. Full payloads require a wire extension (schema card), not this card.
3. Generator jdkVersion comes from the run JVM (`java.specification.version`) —
   schema-valid (`^[0-9]+$`), but varies between environments (21/27 observed) —
   expected behavior for a pinned-environment field.
4. `modules/cli/target/demo-bundle.jar` for cli_smoke is created by
   HeadlessRunnerSpec (existing); my spec builds its own jar in a temporary
   directory with no side write to target.

## Review and next task

Lane verdict: ready for coordinator review; merge by coordinator only. The
contract-change request in limitation 1 is for owner decision (dsl owner).
Next ready cards on the board: LIN-10 (CLI replay surface, consumes this card's
artifacts), P2/P1/P3 remnants under the 2026-10-12 roadmap revision.
