# RAI-155.d: work record — compile edited chat workflows and launch them locally and in generic CI

Status: done (card-scope deliverable complete; all three acceptance cases
implemented and evidenced — see the limitations section for the honest
renderer-defect finding that the compile command now detects and refuses).
Review round 1 (2026-10-14, changes-required verdict, 4 minor findings):
ALL FOUR FINDINGS RESOLVED — see the "Review round 1 fixes" section for the
per-finding evidence, the seam justification, the honest deviation note for
the unreachable :192-201 branch, and limitations #8 for the unwritable-output
gap.
Date: 2026-10-14 UTC. Baseline: `29ad1b4` (clean worktree `wt/rai-155d`,
branch `lane/rai-155d`, matching main HEAD at start). Worker: visible lane
spawn. Allowed paths honored: `modules/cli/src/main/scala/raider/cli/workflow/**`
(new), `modules/cli/src/main/scala/raider/cli/run/**` (NOT touched),
`modules/cli/src/main/scala/raider/cli/Main.scala` (subcommand routing only),
`modules/cli/src/test/scala/raider/cli/**` (three new spec suites). No wire
changes, no renderer changes, no shared files, no live providers (all provider
work runs against local loopback stands).

## Route decision (recorded per ADR-029 §3.1/§7 and ADR-033 §7)

**CLI-internal assembly.** PKT-1 `WorkflowLaunch.make` does not exist in the
runtime module (it is the runtime owner's packet, outside this lane's allowed
paths), and a CLI-local imitation of it would be a second admission shape.
Instead the .d launcher drives the compiled artifact's OWN public
ExplicitAssembly surface — the generated `buildBackend` factory plus the same
public runtime parts the generated `buildLaunch` assembles (CodingToolset
rooted at the input workspace, validated `BudgetLimits`, ONE `Admission` per
run) — under the ADR-029 D1/D3/D4 rules:

1. **Explicit services, no hidden fallback (D1 rule 2):** credentials resolve
   at EXECUTION time from the passed environment via
   `raider.cli.run.Credentials.resolve` (env/helper references only, never
   argv; the parameter is the RAI-064 `HeadlessRunner.run(args, env)` test
   seam — production passes `sys.env`, the identical source the generated
   launcher uses). NO authority comes from the chat, the manifest, or a
   previous run: the manifest is never read by the launcher (provenance
   only), and every invocation performs a fresh credential/policy resolution
   (CWF-A03 policy recheck per invocation).
2. **Zero dispatch before the boundary (D1 rule 1):** construction validates
   and allocates but dispatches nothing; the ONE dispatch is the workflow
   program itself (the .c sentinel probes prove the decode/construct path is
   zero-dispatch; this lane's probes prove the input decode boundary fires
   typed before any provider contact).
3. **One root/admission/ledger per invocation (D3):** the generated agent
   stage creates its own loop root over the Launch assembled per run; no
   process-global registries, no second scheduler — the launcher is one ZIO
   flow on the caller's scope.
4. **Console adapter boundary (D4):** the only blocking interpreter join is
   `WorkflowRun.command`, mirroring the proven Main.scala discipline.
5. **The LauncherTemplate seam needed NO extension** — `ExplicitAssembly` is
   sufficient and its authority binding is correct as shipped by .c. The
   renderer was not touched.

## Resulting behavior (proposal now implemented)

1. **`raider compile <source>.scala --out <dir>`** (`WorkflowCompile`):
   compiles the edited generated workflow with the PINNED toolchain (dotc
   3.9.0; the exact COMPILER_CP of `scripts/build/compile.sh` and the
   ChatWorkflowCompileGateSpec subprocess discipline; `RAIDER_M2_REPO` or
   ancestor discovery of `artifacts/tools/m2`) into a launcher artifact:
   `<out>/classes/` + `<out>/build-identity.json` + `<out>/dotc.log`.
   - Build identity (`WorkflowBuildIdentity`): deterministic, no timestamps —
     `sourceDigest` (sha256 of the edited source bytes) and `classDigest`
     (sha256 over the sorted class files), the derived
     `mainClass`/`moduleClass` names, and the toolchain. This is what makes
     "a manual edit invalidates earlier result claims" (CWF-D02) checkable.
   - Publish is staged (`<out>.staging`, same parent, atomic rename); a
     partially written build is never published; a leftover staging directory
     (own derived data) is cleaned before reuse.
   - Typed failures (R-taxonomy): invalid/torn source → `RA-COMPILE` (exit
     21) with the bounded diagnostics tail; dotc timeout → `RA-DEADLINE`
     (exit 24), never claimed as a compile verdict; toolchain jar missing →
     `RA-CFG` (21); staging/publish IO → `RA-JOURNAL` (27); existing
     non-empty `--out` → `RA-INP` (21, refused BEFORE compiling, no staging
     remnant). A pre-created EMPTY `--out` is accepted.
   - Launchability check: after dotc, the compile refuses (typed, actionable)
     when the `<stem>.class` main file exists but does not define `<stem>` —
     the case-collision hazard below — using an exact-case directory scan
     (a naive existence check false-positives on case-insensitive
     filesystems) and a `Class.forName(..., initialize = false)` name check
     (no static work, no dispatch).
   - The dotc child is destroyed on JVM shutdown (bounded owned-work cleanup).
2. **`raider workflow run --classes <dir> --input <file> --out <dir>
   [--source <file>]`** (`WorkflowRun`): the NON-TTY headless launcher — the
   same compiled artifact a user runs locally as a plain main, driven through
   the ci-runtime discipline. Preflight order (§12): args → build identity
   (classDigest recomputed on EVERY run: corruption/partial-write detection;
   optional `--source` staleness: sha256 mismatch → `RA-INP` "stale build") →
   input readability → output-dir probe (`RA-JOURNAL` 27 — but see
   limitations #8: in the shipped code the events-writer open shadows this
   typed probe for a genuinely unwritable `--out`) → module load →
   credential preflight (RAI-064; `RA-CFG` 21 before any construction) →
   validated decode inside the String boundary (the compiled
   `decodeOrRefuse`; hostile/invalid input → `RA-INP` 21 with the generated
   `CWF-input` prefix, zero dispatch) → CLI-internal assembly → THE single
   dispatch → artifacts.
   - Artifacts (ci-runtime §7): `run.json` (the frozen `RunResult` schema,
     validated pre-write, with the RAI-086-style ADDITIVE splice of
     `buildIdentity` and `inputDigest` — no core schema change), `summary.md`
     (outcome, digests, the honest unpriced/unknown cost line, and the
     workflow report rendered as bounded UNTRUSTED DATA in a tilde fence),
     and `events.jsonl` (lifecycle envelopes: Queued→Running, diagnostics,
     Terminal — streamed via `EventsJsonlWriter`). Success writes artifacts
     IN-FLOW (a write failure fails the run — never green without artifacts);
     typed failures and interruption write best-effort truthful artifacts
     from the finalizer (status Failed/Cancelled/Uncertain/Interrupted with
     the typed error).
   - Exit codes (ci-runtime §8): via `HeadlessRunner.exitCodeFor` — success
     0; decode/identity/credential preflight failures 21; failed trusted
     check 10 (R9 — a failed check stays failed); provider failures 20;
     `ToolDenied` 22; `CapabilityUnsupported` 25 (NeedsApproval, non-TTY);
     interruption 143. SIGTERM: the Main.scala bounded-stop discipline — the
     shutdown hook interrupts the runner fiber and waits at most 5 s for the
     truthful-artifact finalizers, then RETURNS (never System.exit in the
     hook); if the bounded stop expires (adversarial provider hang + bounded
     loop retry), the hook writes a truthful `interrupted-*.json` marker —
     the outcome stays incomplete/uncertain, never green.
   - stdin is NEVER read (closed stdin safe by construction, like Main).
   - Launcher/input boundary (the .c review requirement): the input JSON is
     DATA; hostile EXTRA fields (a "checks" array, an "argv" string) are
     ignored by the versioned decoder and never interpreted as commands;
     trusted checks execute ONLY the compiled `trustedChecks` constants —
     proven at the launcher boundary (hostile-input probe below).
3. **Main routing:** `compile` and `workflow run` dispatch BEFORE the
   run/chat parsing (the plugin-command discipline), so workflow arguments
   are never interpreted as run/chat flags; chat/run/replay/resume/plugin
   behavior is unchanged (full cli suite green).

## Acceptance evidence

| Criterion / case | Actual result | Evidence |
| --- | --- | --- |
| CWF-D01 (fresh compile+package of the EDITED generated class; local plain-main launch and non-TTY CLI launch of the SAME artifact on separate fresh workspaces; equivalent normalized outputs; real tool effects; independent check results; provider/workspace/input explicit) | `WorkflowLaunchSpec` "CWF-D01" green: the fixture renders the STS export, EDITS the source (baseUrl constant → the local stand URL; @main renamed — see limitations), compiles via the product `raider compile`; launch A = the artifact main as a plain `java -cp ... wf155d_launch_main inputA.json` child (exit 0); launch B = `WorkflowRun.run` (exit 0, run.json `Succeeded`, `taskVerdict: trusted-checks-passed`, buildIdentity+inputDigest spliced); BOTH fresh workspaces contain `marker.txt` created by the agent stage's REAL proc_run tool call (round 1 tool_calls → tool execution → round 2 gated on the tool result); both reports carry the identical checks line and the stand's final answer; 4 model rounds total on the shared scripted stand | `raiderCli/testOnly raider.cli.workflow.WorkflowLaunchSpec` (log in lane terminal capture); stand = local JDK HttpServer on the loopback (AnthropicStandSpec discipline), scripted SSE, key ASSERTED on the wire (review round 1, finding 3: the stand counts requests carrying exactly `Authorization: Bearer stand-key-155d` and CWF-D01 asserts the per-launch 4/4 delta — env → credential → backend header, both launchers) |
| CWF-D02 (invalid scala/manifest-identity/input; stale build; unmet policy/credential; failed checks; unwritable output — typed nonzero; READY requires actual checks on the new workspace) | `WorkflowCompileSpec` (7 tests) + `WorkflowRunSpec` (6 tests) green: torn source → `RA-COMPILE` 21 with diagnostics; non-empty `--out` refused 21 BEFORE compiling with no staging remnant; empty `--out` accepted; missing classes dir 21; tampered classes 21 ("stale or corrupted build"); stale `--source` 21 ("stale build ... recompile"); missing credentials 21 `RA-CFG` with truthful failed run.json; invalid input JSON 21 `RA-INP` `CWF-input` (zero dispatch); provider failure → 20 `RA-UNAVAIL` with `Failed` run.json carrying buildIdentity+inputDigest; failed trusted checks are the compiled ones and fail the run (launch spec's marker check on a workspace WITHOUT the marker fails typed `RA-OUT`/10 in the .c gate probes; here the check passed only after the real tool effect); the case-collision detector's refusal branches are now covered deterministically (review round 1, finding 2 — see the review section); UNWRITABLE OUTPUT is NOT exercised — the shipped behavior is a raw defect crash (exit 1, untyped), NOT the typed `RA-JOURNAL` 27 this criterion names: see limitations #8 | `raiderCli/testOnly raider.cli.workflow.WorkflowCompileSpec WorkflowRunSpec` |
| No-argv-injection at the launcher/input boundary (.c review requirement) | `WorkflowRunSpec` hostile-input probe green: the input JSON carries extra `"checks": ["touch", "pwned.txt"]` and `"argv": "rm -rf /"` fields; the run decodes (DATA, ignored), fails ONLY at the provider (20), and NO injected file exists in the workspace; the executed checks remain the compiled `trustedChecks` | same suite |
| CWF-D03 (stdin closed; SIGTERM during active model/process work → bounded owned-work cleanup and truthful incomplete artifacts; no automatic mutation retry; separate launches share no state) | `WorkflowLaunchSpec` D03 tests green: (a) SIGTERM child test — a REAL `java -cp ... raider.cli.Main workflow run ...` child, SIGTERM'd while the model call was in flight against a hanging stand (delta≥1 observed at ~0.5 s): bounded stop (waitFor true), POSIX 143, the work dir carries events.jsonl AND truthful Interrupted evidence (the run finalizer's `Interrupted` run.json, or — when the bounded stop expired before finalization — the hook's `interrupted-*.json` marker), never `Succeeded`; provider-round requests bounded (1–3: the loop's EXISTING bounded retry of a retryable transport failure; no MUTATION had run and none was re-dispatched); (b) stdin-closed by construction (Main discipline; nothing reads stdin); (c) two CONCURRENT CLI launches: distinct rootIds and work dirs, both green on their own workspace, marker effects isolated | same suite |
| Existing v1 bundles still load and run (D02 compatibility) | Full `raiderCli` test suite green: 409 passed / 0 failed (includes HeadlessRunner bundle tests, BundleLoader, provenance, replay/resume suites; +3 = the review-round-1 collision-detector tests) | `sbt raiderCli/test` (full suite log, lane terminal capture) |
| .c generator contract unchanged | `ChatWorkflowCompileGateSpec` green unchanged (4 tests; byte-identical golden render, pinned dotc compiles, edit-flip probe) | same run |

## Commands

| argv / command | Exit | Evidence |
| --- | --- | --- |
| `git status --short` + `git log --oneline -1` (pre-work) | 0, clean @ `29ad1b4` | baseline = main HEAD |
| `sbt raiderCli/compile` (incremental during development) | 0 | lane terminal capture |
| `sbt raiderCli/testOnly raider.cli.workflow.WorkflowCompileSpec` | 0 — 7 passed / 0 failed (4 round-0 + 3 review-round-1) | lane terminal capture |
| `sbt raiderCli/testOnly raider.cli.workflow.WorkflowRunSpec` | 0 — 6 passed / 0 failed | lane terminal capture |
| `sbt raiderCli/testOnly raider.cli.workflow.WorkflowLaunchSpec` | 0 — 3 passed / 0 failed | lane terminal capture |
| `sbt raiderCli/testOnly raider.cli.workflow.*` (all four suites incl. the unchanged .c gate) | 0 — 20 passed / 0 failed | lane terminal capture |
| `sbt raiderCli/test` (FULL cli suite, regression) | 0 — 409 passed / 0 failed / 0 ignored | lane terminal capture |
| review round 1: `sbt raiderCli/testOnly raider.cli.workflow.WorkflowCompileSpec raider.cli.workflow.WorkflowLaunchSpec` ×3 (determinism of the new tests) | 0 — 10 passed / 0 failed, all three runs identical | lane terminal capture |
| review round 1: revert-check — `verifyMainNotCollided` stubbed to `ZIO.unit`, `sbt raiderCli/testOnly ... -- -t "review fix"` | nonzero — BOTH refusal tests FAIL (`Right(())`, no typed refusal); stub removed, file hash verified byte-identical | lane terminal capture |
| review round 1: scratch probe (since removed) — read-only parent `--out`, in-process + subprocess | in-process: effect DIES with `java.nio.file.AccessDeniedException` (defect, no typed Outcome); subprocess `raider.cli.Main workflow run`: EXIT 1 with a raw stack trace from `EventsJsonlWriter.open` — NOT typed `RA-JOURNAL` 27 | limitations #8 |
| `make quality-changed` (review-round final fingerprint) | 0 — classification: profiles `contracts, static, unit` | lane terminal capture |
| `make quality-changed QUALITY_EXECUTE=1` (review-round final fingerprint) | 0 — status=passed; core-contracts-spec / compile-fixtures / sbt-compile / scalafmt-check / sbt-test all exit=0, skipped=0 | `artifacts/quality/20261014-104523-90411-0001/manifest.json` |
| `make quality-profile PROFILES="static unit contracts ci"` (review-round final fingerprint) | 0 — status=passed; sbt-test / cli-smoke / core-contracts-spec / compile-fixtures / sbt-compile / scalafmt-check all exit=0, skipped=0 | `artifacts/quality/20261014-105051-93670-0001/manifest.json` |

Note: the two passing manifests above were produced on the tree whose only
post-run change is THIS record file (operational documentation; the gate
fingerprint covers it, and the coordinator's integrated-candidate gate run
re-establishes the fingerprint on merge — the .c record pattern). Round-0
gate history, including the first-attempt `scalafmt-check` failure and its
manifests (`20261014-094538-46383-0001`, `20261014-095628-53081-0001`),
remains in the previous revision of this record (commit `24f067f`).

User-facing command surface (exact argv, as implemented):

- `raider compile <source>.scala --out <dir>`
- `raider workflow run --classes <dir> --input <file> --out <dir> [--source <edited.scala>]`
- Local (launcher-artifact) mode: `java -cp <product-classpath>:<dir>/classes <main-class> <input.json>`

## File hashes (this lane snapshot, sha256)

Recomputed against the committed lane tip after review round 1 (the
round-0 values for WorkflowCompile.scala and WorkflowRun.scala were
pre-scalafmtAll and stale; every value below was verified at the final
source state):

- `modules/cli/src/main/scala/raider/cli/workflow/WorkflowCompile.scala`
  `2d6d9d233711a00f1831c8679fc320c85bfaeaca8e2d32aa7cd41b4166e52005`
- `modules/cli/src/main/scala/raider/cli/workflow/WorkflowRun.scala`
  `7337a33ffb222dfdaaeb377049c028cb99fccb1559cdb58d66e34250f2a9c125`
- `modules/cli/src/main/scala/raider/cli/workflow/WorkflowBuildIdentity.scala`
  `5a0b9276ff47c98c7d48e27018dfa85fa38d72a495a69c1e8fae70251ff6a52a`
- `modules/cli/src/main/scala/raider/cli/Main.scala`
  `7f865c63e9cbfc7f7cc95bac955e2654b56d06955031740e452b49f0ff64b71c`
- `modules/cli/src/test/scala/raider/cli/workflow/WorkflowCompileSpec.scala`
  `bdd0365c4dddc1a908a945b95e9ee878fafc7380f22128aaec8fd77a87b113f4`
- `modules/cli/src/test/scala/raider/cli/workflow/WorkflowRunSpec.scala`
  `e0186a0aa345fcb771b6cc0738c2c4162c54eaba423e51ddf229be8e91d2fd2f`
- `modules/cli/src/test/scala/raider/cli/workflow/WorkflowLaunchSpec.scala`
  `f4d1687dab22c65edb9fde7f4c6c906312dd35472b803b6bed3a69fb5325574f`

## Review round 1 fixes (2026-10-14, changes-required verdict — 4 minor findings)

1. **Stale file hashes** — fixed: every hash in the File hashes section was
   recomputed and verified at the final source state (the round-0 values for
   WorkflowCompile.scala and WorkflowRun.scala were pre-`scalafmtAll`).
2. **`verifyMainNotCollided` shipped untested** — resolved with THREE
   deterministic named tests in WorkflowCompileSpec:
   - *review fix 2a*: a collision-shaped staging dir — the exact-case
     `<stem>.class` entry present but its BYTES defining a different class
     (a real classfile copied under the derived main-class name) → typed
     `RA-COMPILE` ("cannot verify the launchable main"). This is the branch
     the REAL macOS collision takes: the JVM's `defineClass` name contract
     turns the collision into `NoClassDefFoundError` (wrong name), which
     verifyMainNotCollided's attempt-mapping converts to the typed refusal.
   - *review fix 2b*: an exact-case `<stem>.class` entry that yields NO
     loadable class (broken symlink: listed by the scan, unresolvable by the
     URLClassLoader) → the `ClassNotFoundException` branch (round-0
     WorkflowCompile.scala:202-211) → typed `RA-COMPILE` ("exists but does
     not define").
   - *review fix 2c*: the POSITIVE branch through the PUBLIC `compile` entry:
     the fixture renames the generated OBJECT token away from the case pair
     (`Wf155d_pos` → `Wf155dPos`; the replace also rewrites the
     `.runLauncher` reference), so `wf155d_pos.class` is present AND defines
     the derived main name — the `Class.forName` name check passes and the
     build publishes (the exact-case entry is asserted present, so the test
     proves the real name check ran, not the missing-entry early-out).
   - Visibility seam (allowed by the review): `verifyMainNotCollided` widened
     `private` → `private[workflow]` (no behavior change). Justification: the
     refusal-branch staging directories cannot be produced deterministically
     through the public entry — `compile` derives staging itself and dotc's
     write order decides the real collision (the round-0 finding 1
     nondeterminism). The crafted-dir seam tests the check directly.
   - HONEST DEVIATION from the review instruction: the reviewer's
     "Class.forName name mismatch" branch (round-0
     WorkflowCompile.scala:192-201, `cls.getName != mainClass` after a
     SUCCESSFUL load) is UNREACHABLE and therefore not covered by any test:
     `ClassLoader.defineClass` throws `NoClassDefFoundError` when the
     requested name differs from the class file's binary name, so a
     successfully loaded class ALWAYS has `getName == mainClass`. The branch
     is dead defensive code; a future behavior card may simplify it (this
     review-fix card is forbidden from product behavior changes). The
     refusal guarantee itself IS covered — via the 2a path the real
     collision actually takes, plus 2b.
   - Revert-check (the packet's request, feasible for this finding): with
     `verifyMainNotCollided` stubbed to `ZIO.unit`, BOTH refusal tests fail
     (they observe `Right(())` — no typed refusal). Stub removed afterwards;
     the file hash was verified byte-identical to the pre-probe state.
   - Determinism: all three new tests green in three consecutive
     full-suite-pair runs (10/10 each, exit 0 ×3); no dotc write-order or
     timing dependence (2a/2b use crafted dirs, no dotc; 2c's edit makes the
     collision structurally impossible).
3. **Record overstated the stand's key assertion** — resolved by ADDING the
   assertion rather than weakening the wording: the stand now counts requests
   carrying exactly `Authorization: Bearer stand-key-155d`
   (WorkflowLaunchSpec stand handler) and CWF-D01 asserts the per-launch
   4/4 delta, covering BOTH launchers (the local plain-main child and the
   in-process `WorkflowRun.run`). The CWF-D01 evidence row now names the
   mechanism.
4. **CWF-D02 "unwritable output — typed nonzero" untested and silently
   omitted** — resolved with the reviewer's explicit alternative: an honest
   Limitations entry (#8), because the PREFERRED test would fail against the
   shipped behavior (product behavior changes are forbidden in this
   review-fix card). Evidence gathered via a temporary probe (removed after
   the run): read-only parent `--out` → in-process `WorkflowRun.run` DIES
   with `java.nio.file.AccessDeniedException`; through
   `raider.cli.Main workflow run` the process exits 1 with a raw stack trace
   from `EventsJsonlWriter.open` — nonzero, but NOT the typed
   `RA-JOURNAL` 27 the criterion names (see limitations #8 for the code-path
   citation and the follow-up fix shape).

## Limitations / findings / risks

1. **FINDING (renderer defect, outside this lane's paths — contract-change
   request for the coordinator):** the .c-generated naming pair
   (`object <Name>` + `@main def <name>`) produces two classes differing
   only in case; dotc warns ("Generated class X differs only in case from x.
   Such classes will overwrite one another on case-insensitive filesystems").
   On this macOS checkout the write order is nondeterministic: when the
   `@main` helper loses, `wf155d_launch.class` contains the OBJECT and the
   artifact has NO launchable main (the .c gate only compiled and
   reflectively loaded the artifact, so this never surfaced). Consequences
   handled here: (a) `raider compile` DETECTS the lost-main branch and
   refuses with a typed, actionable `RA-COMPILE` (exact-case scan +
   initialize-free name check); (b) the .d fixtures exercise the card's edit
   surface (rename the `@main`, e.g. `wf155d_launch_main`) — the module
   class the run path derives is untouched. REQUIRED FIX (renderer card):
   change the generated `@main` name (or drop the object mirror emission);
   until then the "local plain-main" leg of the UNEDITED generated shape is
   unreliable on case-insensitive filesystems and reliable on Linux/CI.
2. Model-round events: the generated `agentStage` builds `AgentLoop` without
   the `LoopEvents` seam, so `events.jsonl` on this path carries the
   launcher's LIFECYCLE events (queued/running/diagnostics/terminal), not
   per-model-round events ("artifacts where the launch capability provides
   them"). Wiring the loop's event seam through the generated source is a
   renderer-side change (same future card).
3. `checks.junit.xml` is not emitted: the generated `runAllChecks` is
   all-or-nothing typed (the failing check is named in the error detail, and
   `run.json` carries the `build-identity-verified` / `workflow-launch` /
   `run-result-schema` check results). Per-check junit needs a renderer-side
   result surface.
4. Under the adversarial hang+retry timing, the bounded stop can expire
   before the finalizers land; the hook then writes the truthful
   `interrupted-*.json` marker (tested). A full run.json is therefore not
   guaranteed under SIGTERM+hang — by ci-runtime §6 design (best-effort
   save; absence of a complete result always means incomplete).
5. Credentials resolve inside the generated `buildLaunch` (env/helper); the
   CLI preflight resolves them ONCE more for fail-fast ordering, so a
   configured helper command executes twice per launch (read-only key
   lookup semantics; documented, no secrets in logs/argv either way).
6. Cost stays unpriced/unknown through this path (`CostEstimate.Unknown`,
   K5/H3): the summary carries the honest line, never a zero presented as
   observed cost.
7. No live/paid provider calls anywhere: the stand is a local loopback
   JDK HttpServer; the dispatch-failure probe targets a closed loopback
   port. Pricing-relevant usage on this path is unpriced.
8. **GAP (review round 1, finding 4): an unwritable `--out` is NOT a typed
   `RA-JOURNAL` 27 failure — it is an untyped defect crash (probed, see the
   review section).** Code path: `WorkflowRun.body` opens the
   events writer FIRST (`WorkflowRun.scala:214`,
   `EventsJsonlWriter.open(workDir/events.jsonl)`), and `open`
   (`EventsJsonlWriter.scala:116-128`) maps its `Files.createDirectories`
   IO failure through `.orDie` — an `AccessDeniedException` becomes a
   DEFECT. `flow`'s typed output-dir probe
   (`WorkflowRun.scala:278-287` → `RaiderError.JournalFailure` →
   `RA-JOURNAL`, exit 27 via `HeadlessRunner.exitCodeFor`) is therefore
   unreachable for a genuinely unwritable `--out`. In-process,
   `WorkflowRun.run`'s `fold` handles only typed errors, so the effect
   dies; through `WorkflowRun.command`, `fiber.join`'s `getOrThrow()`
   rethrows the RAW defect (not `FiberFailure`), so the
   `FiberFailure` handler (`WorkflowRun.scala:848-854`) does not apply and
   the process crashes: exit 1 (outside the §8 table) with a stack trace,
   no typed `raider RA-JOURNAL: …` line, and no truthful artifacts.
   Observed by a temporary probe (since removed): read-only parent dir,
   in-process + subprocess, both reproduced deterministically. A test
   asserting "typed RA-JOURNAL 27" would fail against this shipped
   behavior and behavior changes are out of scope for a review-fix card —
   hence this entry instead. FOLLOW-UP (behavior card): open the events
   writer AFTER the typed `createDirectories` probe (or make
   `EventsJsonlWriter.open` typed), then add the named read-only-parent
   test asserting `RA-JOURNAL`/27 with truthful failed artifacts.

## Next available card

RAI-155.e (independent end-to-end verifier) — its fixtures should reuse the
two-launcher matrix shape proven here; the renderer naming fix (finding 1)
and the optional event-seam/junit renderer extensions (findings 2–3) belong
in a separate renderer contract card before .e freezes its unedited-flow
fixtures.
