# RAI-155.c: work record — deterministic chat-workflow GENERATOR

Status: done (card-scope deliverable complete; independent review returned
changes-required — 1 MAJOR + 2 minor; all three fixes applied and re-gated,
see "Review and next task").
Date: 2026-10-14 UTC. Baseline: `b9f9c8f` (clean worktree `wt/rai-155c`,
branch `lane/rai-155c`, matching main HEAD at start). Worker: visible lane
spawn. No shared files, no wire changes, no ADR-031 renderer changes, no
Task/Program API changes; `modules/cli/src/main/scala/raider/cli/chat/**` was
NOT touched (coordination note honored — `.b` owns the chat ledger; the
`.b`/`.c` wiring belongs to `.d`/integration).

## Resulting behavior

Deterministic workflow GENERATOR per ADR-033 §4/§6, delivered as a pure
library in `raider.dsl.workflow` (dsl module, core-only dependency):

1. **Pure STS input contract** (`StsSelection.scala`): bounded immutable
   selection snapshot — one explicit Intent turn, ordered Refinement turns,
   the named task params (`targetPath`, `summary`), named trusted checks
   (name + validated argv), one bounded agent stage (`maxRounds`), and
   non-secret origin references (providerKind, model, optional baseUrl).
   Plain data, no cli dependency; the chat-side adapter that produces it is
   RAI-155.b's surface (its fixture inputs land separately; this module only
   validates and renders).
2. **Typed refusals** (ADR-033 §8, structured `CWF-Rn` detail prefix on the
   existing `RaiderError.InputValidation` family): R1 unsupported shape
   (missing/unknown/duplicate params, non-Intent/non-Refinement kinds, empty
   or control-bearing check argv, `maxRounds < 1`, unsupported providerKind,
   non-URL baseUrl, reserved-word export name — every refusal names the
   supported shape); R3 non-terminal turns (lists the offending turnIds and
   their statuses); R4 secret-bearing selections (conservative credential-
   shape scan over every captured string; the refusal names the field class
   and NEVER echoes the value). R2 (legacy capture-less sessions) is
   structurally impossible against this input trait and belongs to `.b`'s
   adapter surface.
3. **Deterministic renderer** (`ChatWorkflowRenderer.render`): pure function,
   identical selection + options → byte-identical source/manifest/input
   artifacts (no clock, no randomness, no model call — proven twice in two
   suites). Model-assisted synthesis: NOT present (off by default per card;
   the seam is the `LauncherTemplate` parameter, documented). Escape
   discipline follows the RAI-151 precedents (own copies in this module):
   `\uXXXX` for C0/DEL in string literals (dotc 3.9.0 rejects `\xHH` there),
   lexically inert `\xHH` only inside `//` comments, quotes/backslashes
   escaped first so `\u0022`/`\u005c` are never emitted; JSON artifacts use
   proper JSON escaping.
4. **Generated artifact set** (ADR-033 §6.2 layout): `<name>.scala` +
   `<name>-manifest.json` + `<name>.input.json`. The source is normal
   editable Scala: nested versioned `Input`/`TaskParams` (schemaVersion gate)
   with validated JSON decoding INSIDE the String boundary; named editable
   constants (`promptTemplate` with `{workspace}`/`{targetPath}`/`{task}`
   placeholders, per-check argv lists, `maxRounds`, `checkTimeoutSeconds`,
   provider/baseUrl/model references); explicit `Launch` services case class;
   ONE bounded sequential agent stage over the existing `AgentLoop.runText`
   (no parallelism/delegation/phases, `CostEstimate.Unknown` per K5); output
   validation (non-empty, `AgentLoop.InternalMarker`-free); named checks run
   via `ProcessBuilder` WITHOUT a shell in the workspace with a bounded wait
   (nonzero exit = typed failed run, R9); check argv comes only from the
   editable constants — the input JSON can never inject a command (text-is-
   data, §8).
5. **Option-L launcher with parameterized construction seam** (ADR-033
   §6.3/§7): explicit `@main` binding CURRENT env/config at run time; the
   launch-construction section is rendered from `LauncherTemplate`
   (single built-in `ExplicitAssembly`: `raider.cli.run.Credentials.resolve`
   env/helper credential references with typed auth failure and NO hidden
   fallback, `CodingToolset` rooted at the input workspace, validated
   `BudgetLimits(maxAttempts = maxRounds)`, one `Admission`/ledger per run).
   The PKT-1-capability vs CLI-internal route decision remains RAI-155.d's —
   the renderer does not hard-freeze it (a later slice adds its
   `LauncherTemplate` case without changing the render contract). The
   manifest records `launcherTemplate: "explicit-assembly"`. Zero dispatch
   until the launcher runs the workflow (construction validates only).
6. **Export write discipline** (`ChatWorkflowExport.exportTo`, §6.2/§9):
   staging directory + rename publish; an existing target (file/dir/symlink)
   is a typed R5 refusal — export never overwrites user edits; a leftover
   staging directory from an interrupted export is a typed R5 refusal naming
   the remnant; concurrent exports to the same target collide on the
   target-keyed staging name (loser refuses), different targets are
   independent; parents are never created implicitly.

Golden example committed under `examples/chat-workflow/fix_widget_task/`
(generated from the committed fixture selection; pinned byte-for-byte by the
dsl spec). Manifest `buildIdentity.sourceDigest`
(`sha256-f9cf0b8d…`) equals the actual sha256 of the committed source file —
build identity is self-consistent and is what `.d` records into run
artifacts.

## Changed paths

- `modules/dsl/src/main/scala/raider/dsl/workflow/StsSelection.scala` (added)
- `modules/dsl/src/main/scala/raider/dsl/workflow/ChatWorkflowRenderer.scala`
  (added)
- `modules/dsl/src/main/scala/raider/dsl/workflow/ChatWorkflowExport.scala`
  (added)
- `modules/dsl/src/test/scala/raider/dsl/workflow/ChatWorkflowRendererSpec.scala`
  (added; 10 tests)
- `modules/cli/src/test/scala/raider/cli/workflow/ChatWorkflowCompileGateSpec.scala`
  (added; 4 tests; allowed by the canonical card's `modules/cli/src/test/scala/raider/cli/**`
  path — the pinned-dotc gate needs the full module test classpath, which the
  core-only dsl module cannot see)
- `examples/chat-workflow/fix_widget_task/fix_widget_task.scala` (added,
  generated golden)
- `examples/chat-workflow/fix_widget_task/fix_widget_task-manifest.json`
  (added, generated golden)
- `examples/chat-workflow/fix_widget_task/fix_widget_task.input.json` (added,
  generated golden)
- `docs/work/records/RAI-155c.md` (this record, draft in lane worktree)

Public API/schema changes: none outside the new `raider.dsl.workflow`
package (new module-internal surface; frozen APIs untouched).

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| CWF-C01 (representative STS → deterministic editable Scala compiling under pinned dotc 3.9.0; second task/workspace input; identical spec renders stable output) | `ChatWorkflowCompileGateSpec` green: representative export compiles exit 0; repeat render byte-identical and re-compiles exit 0; loaded artifact runs an input-dependent check differently per workspace input; `ChatWorkflowRendererSpec` determinism tests green | `modules/cli/target/chat-workflow-compile/*.dotc.log`, gate spec `raider.cli.workflow.ChatWorkflowCompileGateSpec` |
| packet CWF-C02 (edits to externalized parameters change runtime behavior WITHOUT regeneration; manifest documents the parameter surfaces) | Flip driven by the COMPILED constants (review-fix): generated `runAllChecks` over `trustedChecks` on a marker-present workspace — original artifact green, edited SOURCE constant `List("/usr/bin/true")` → `List("/usr/bin/false")` recompiles and fails its own "pass" check (typed `OutputValidation`, "exit code 1"); NEGATIVE CONTROL proven: with the edit temporarily reverted the probe FAILS (`editApplied` and `editedRed` false) — no hand-passed argv in this probe; same artifact, different input JSON flips the marker check (read at execution) | `ChatWorkflowCompileGateSpec` probe test (edit-without-regeneration + second-input assertions) |
| packet CWF-C03 / canonical CWF-C02 (hostile strings compile; secret-bearing selection refused; zero implicit dispatch probe) | Hostile export (U+0001/U+0002/U+0003/U+0005, DEL, quotes, newlines, `rm -rf` injection text) compiles exit 0 with `\uXXXX` literals and `\xHH` only in `//` comments; R4 refusal never echoes the value; sentinel-backend probe: invalid input fails typed at the decode boundary with the backend NEVER touched, valid input reaches the agent stage (backend touched, typed `ProviderUnavailable`) | `ChatWorkflowCompileGateSpec` hostile + probe tests; `ChatWorkflowRendererSpec` R4 test |
| canonical CWF-C03 (concurrent exports don't overwrite; interruption leaves identifiable incomplete output; export/compile cannot redispatch) | Target-keyed staging + rename: existing target refused, leftover staging refused with remnant message, missing parent refused; renderer/export are pure (no dispatch surface) and the sentinel probe proves no dispatch from construction | `ChatWorkflowRendererSpec` exportTo tests; `ChatWorkflowCompileGateSpec` probe test |
| Required gates | `make quality-changed` exit 0; `make quality-changed QUALITY_EXECUTE=1` status=passed (all checks exit 0); `make quality-profile PROFILES="static unit contracts"` status=passed | artifacts/quality manifests listed under Commands |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` + `git log --oneline -1` (pre-work) | 0, clean @ `b9f9c8f` | baseline = main HEAD |
| `make quality-changed` | 0 | classification: profiles `contracts, docs, static, unit` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (status=passed; core-contracts-spec/compile-fixtures/plan-validate/sbt-compile/scalafmt-check/sbt-test all exit=0) | `artifacts/quality/20261014-024253-24207-0001/manifest.json` |
| `make quality-profile PROFILES="static unit contracts"` | 0 (status=passed) | `artifacts/quality/20261014-024729-29342-0001/manifest.json` |
| `sbt raiderDsl/Test/testOnly raider.dsl.workflow.ChatWorkflowRendererSpec` (post-format rerun) | 0 — 10 passed / 0 failed | lane terminal capture |
| `sbt raiderCli/Test/testOnly raider.cli.workflow.ChatWorkflowCompileGateSpec` (post-format rerun) | 0 — 4 passed / 0 failed; dotc exit 0 ×5 (representative, repeat, hostile, probe, edited) and torn-header negative exit != 0 | `modules/cli/target/chat-workflow-compile/*.dotc.log`, lane terminal capture |
| `sbt --batch "raiderCli/testOnly raider.cli.workflow.ChatWorkflowCompileGateSpec"` (review fix: probe 3 driven by compiled `trustedChecks` via generated `runAllChecks`) | 0 — 4 passed / 0 failed; dotc exit 0 ×5 + torn-header negative exit != 0 | lane terminal capture; `modules/cli/target/chat-workflow-compile/*.dotc.log` |
| `sbt --batch "raiderCli/testOnly raider.cli.workflow.ChatWorkflowCompileGateSpec"` (NEGATIVE CONTROL: source edit temporarily reverted in the probe — replacement made a no-op) | 1 — 3 passed / 1 failed: probe test RED, `editApplied` and `editedRed` both false (run-level flip assertion catches the reverted edit); `originalAllGreen` stayed true | lane terminal capture (temporary patch reverted immediately after; not committed) |
| `make quality-changed` (review-fix tree) | 0 — profiles `contracts, static, unit` | lane terminal capture |
| `make quality-changed QUALITY_EXECUTE=1` (review-fix tree, final fingerprint) | 0 (status=passed; core-contracts-spec/compile-fixtures/sbt-compile/scalafmt-check/sbt-test all exit=0) | `artifacts/quality/20261014-032817-80324-0001/manifest.json` |
| `sbt --batch "raiderCli/testOnly raider.cli.workflow.ChatWorkflowCompileGateSpec"` (final fingerprint) | 0 — 4 passed / 0 failed | lane terminal capture |

Note: an earlier QUALITY_EXECUTE run failed on `scalafmt-check` (exit 1);
`sbt scalafmtAll` reformatted the new sources and the full gate chain was
rerun to green. The review-fix reruns (rows above) apply to the current
source fingerprint (no product-input change after the last passing run).

File hashes (this lane snapshot, sha256):

- `modules/dsl/src/main/scala/raider/dsl/workflow/StsSelection.scala`
  `907e0135ccc99fcdbcaa19f41786a2daf1a1d08dc933273f9fca0068a895a507`
- `modules/dsl/src/main/scala/raider/dsl/workflow/ChatWorkflowRenderer.scala`
  `ead34b4e59cb63c4f572011b8e7c7ac044c736995816b203a87af9429a9a4069`
- `modules/dsl/src/main/scala/raider/dsl/workflow/ChatWorkflowExport.scala`
  `76e7474fc5ad231c679b0854eadd383cbf329a92c2a25ecdd00a972c5f119499`
- `modules/dsl/src/test/scala/raider/dsl/workflow/ChatWorkflowRendererSpec.scala`
  `0862f61b6b9223eb52168bd5293b1abc63018036e17860669180c27fa5486dda`
- `modules/cli/src/test/scala/raider/cli/workflow/ChatWorkflowCompileGateSpec.scala`
  `bf9e62bd87ebe7f2eb2dfa3f78c10af2d399c683c1d99bedfc71f70ad50128b1`
- `examples/chat-workflow/fix_widget_task/fix_widget_task.scala`
  `f9cf0b8db8674f8d1dfe0ca39aafd3d0d3aab203c38faa470cd8e4656a259a13`
- `examples/chat-workflow/fix_widget_task/fix_widget_task-manifest.json`
  `d177e233deee8ebd242c24681c3872e4167638d17d100f272729465bc89106ab`
- `examples/chat-workflow/fix_widget_task/fix_widget_task.input.json`
  `82fb04aefab2e026469fcceaa99848f971443097f72e166af85bf704ba65558a`

## Limitations / blockers

- No CLI/export command is shipped: the chat command seam and `.b` adapter
  wiring belong to RAI-155.b/.d; this card delivers the pure generator +
  tests per the coordination note (chat/** untouched).
- `LauncherTemplate` has exactly one built-in (`ExplicitAssembly`). The
  PKT-1 `WorkflowLaunch` capability does not exist yet; when .d freezes its
  route it adds its template case. The generated launcher is honest about
  this in its header comments.
- The generated launcher prints a textual report and exits 0/1 with
  `raider CODE: detail` on stderr; the ci-runtime §7–8 artifact set, exit
  table and SIGTERM ownership are RAI-155.d's execution-binding acceptance
  and are intentionally not pre-built here.
- R4 secret scan is conservative by design (long-hex may refuse benign
  digests in prose; credential-assignment shapes may false-positive on
  unlucky wording). A false refusal costs a re-wording; a false pass would
  leak a credential. Documented in code.
- Probe checks in the compile gate use local deterministic commands
  (`/bin/test`, `/usr/bin/true`); the representative fixture's `sbt` checks
  are proven to COMPILE only — executing real sbt/agent work is CWF-01/.d/.e
  territory.
- Known renderer limitation (documented, NOT fixed — review finding 3):
  `turnLine` builds the `specDigest` canonical turn line with an unescaped
  `|` separator, so turn text containing `|` can make the line ambiguous to
  a reader. Provenance identity only: the canonical text is hashed and never
  rendered or executed, so behavior is unaffected. Escaping changes the
  digest format (frozen contract surface) and is deferred to a separate
  contract-change card; the limitation is marked in the renderer source.
- No live/paid provider calls anywhere; the sentinel backend and local
  stands replace the transport (no-dispatch probes mandatory per K9).
- Pricing stays unpriced/unknown through this path (K5/H3): generated code
  carries `CostEstimate.Unknown`; no observed-USD claims.

## Review and next task

Independent review verdict: **changes-required** (1 MAJOR + 2 minor); all
three fixes applied in this lane and re-gated on the final fingerprint:

1. MAJOR (probe 3, `ChatWorkflowCompileGateSpec`): the "edit flips outcome"
   proof passed argv BY HAND, so the red outcome proved nothing about the
   edit. Fixed per review spec: on a marker-present workspace the probe now
   reflectively invokes the GENERATED `runAllChecks` on BOTH artifacts —
   original green via the compiled `trustedChecks`, edited red (typed
   `OutputValidation` naming the flipped "pass" check, "exit code 1"). No
   hand-passed argv remains in probe 3. Negative control stated in the test
   comment and PROVEN: with the edit temporarily reverted the probe FAILS
   (`editApplied` and `editedRed` false) — see the Commands table. Docstring
   updated accordingly.
2. Minor (this record): dotc compile-evidence count corrected ×3 → ×5.
3. Minor (documented, not implemented): `turnLine` `|` separator ambiguity —
   one-line comment added to `ChatWorkflowRenderer` and a Known-limitation
   bullet added above; execution unaffected, escaping deferred to a separate
   contract-change card.

Next: coordinator review of this lane (canonical CWF-C01..03 against the
evidence above), then RAI-155.d — compile/package + execution binding for
the edited source (route decision recorded in .d's card before code), with
`.b`'s selection adapter feeding `StsSelection` from the turn ledger. The
golden example is the reference artifact for `.d`/`.e`/`.f`.
