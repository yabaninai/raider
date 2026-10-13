# ADR-033: Explicit chat capture and executable workflow export (RAI-155.a contract)

Status: **Proposed** (reviewable contract of card RAI-155.a; acceptance IDs
CWF-A01..A03 are checked against this document before implementation cards
RAI-155.b–.f start). Date: 2026-10-14. Builds on the accepted ADR-029
(authoring/launch boundary, RAI-116) and ADR-031 (projection dry-run, RAI-140);
inherits ADR-032's versioning policy without changing the lineage wire.
Extends [runtime-contracts](../runtime-contracts.md) (new §17) and
[ci-runtime](../ci-runtime.md) (new §12). Does **not** change the frozen
`Task`/`Program` API, the frozen BundleWorkflow v1 wire, the ADR-024 event
seam, ADR-031 projection semantics, or gate policy. All code references were
verified at lane baseline `7ce089d` (branch `lane/rai-155a`); ADR-029
file:line citations are inherited from its audit at `5bec721` (a direct
ancestor of this baseline).

Every command name, schema, and signature in this ADR is a **proposal — not
implemented** unless explicitly marked otherwise. Source audit at `7ce089d`:
there is no `:export-workflow` REPL/chat command (chat commands are `:help
:quit :clear :context :tools :trace :save :sessions :load :compact :fix :pipe
:delegate`, `ChatLoop.scala:763-784`) and no `raider compile`/`raider
workflow` CLI target (`CliArgs.Target = Bundle | Agent | Replay | Resume`,
`CliArgs.scala:64-87`). Nothing here authorizes advertising them as shipped.

## 1. Context

Owner request (2026-10-14, `docs/prompts/opencode-chat-to-workflow.txt`):
work with an agent in chat, explicitly export an editable Scala workflow, edit
it, then run the same definition locally and in generic non-TTY CI. RAI-155.a
must freeze — before any implementation — the packaging decision left open by
ADR-029 §6 (Option L vs Option B), the first supported scope, chat capture
semantics, export semantics, refusal cases, input/output layout, and a
remaining-work estimate with proposed hour allocation.

What exists today (source audit at `7ce089d`):

- **Chat** (`modules/cli/src/main/scala/raider/cli/chat/`): `ChatLoop` runs
  each user message as ONE job of a per-session `JobManager`; full history is
  sent per message, truncated to the last 40 messages
  (`ChatLoop.scala:39,445`). Compaction (manual `:compact` and automatic above
  30 messages) summarizes the oldest slice into ONE system message and
  **drops the original messages** from history (`ChatLoop.scala:44-46,500`).
- **Persistence** (`Session.scala`): `SavedSession` schemaVersion 2 = `{id,
  messages: List[RequestMessage], model, timestamp, workspace?, label?}` —
  a flat conversation transcript. There is **no turn identity, no job id, no
  tool-outcome record, no terminal status, no pending/interrupted boundary**
  in the saved document. `RequestMessage` is `{role, content}`
  (`backend.scala:78`).
- **CLI** (`CliArgs.scala:64-87`): targets `Bundle | Agent | Replay | Resume`.
  Bundle loading reduces to `Map[String, Program[String,String]]` over the
  frozen no-arg SPI (`bundle.scala:92-100`).
- **Bundle v1 cannot host agent work**: no service injection through the
  no-arg SPI; the runner admits a bundle program as one opaque outer Model
  slot and settles `MicroUsd(0)` (`HeadlessRunner.scala:624-647`, per
  ADR-029 §3.2). No agent-performing bundle exists.
- **Launch boundary** (ADR-029, accepted): authors run `Task`/`Program`
  compositions through one explicitly-configured launch capability — one
  root/admission/ledger per run, lazy construction, no hidden
  `Runtime.default`, no second scheduler. The capability itself is **not yet
  implemented** (ADR-029 §7 PKT-1 is unscheduled) — a known prerequisite,
  recorded in §12.
- **Projection** (ADR-031): generated lineage projections are zero-dispatch
  dry-run descriptions — a different artifact layer from this ADR's export
  (§9.4); the two must never be conflated.

## 2. Decision summary

| # | Area | Decision (short) | Where |
| --- | --- | --- | --- |
| DA | Packaging | **Option L — launcher artifact.** The exported workflow is ordinary editable Scala with an explicit `main` that constructs the ADR-029 launch capability and runs the definition. Bundle v1 stays frozen and untouched. | §3 |
| DB | First scope | The Supported Task Specification (STS): explicit sequential coding intent + ordered explicit refinements + named task/workspace parameters + named trusted check commands + one bounded agent stage. Deterministic renderer; model-assisted synthesis is a separate explicit configured operation. | §4 |
| DC | Chat capture | An exportable **turn ledger** stored alongside (not inside) the model-facing message history; survives save/load/compaction; legacy conversation-only sessions stay readable but export-refused (typed); pending/interrupted turns are preserved, never dropped. | §5 |
| DD | Export semantics | Deterministic render of a bounded immutable selection → editable source + manifest + input example. Export/build/compile dispatch nothing. Exported text is data, never a shell instruction. Authority is never inherited from chat; credentials are env/helper references and never enter source/logs/manifests. | §6 |
| DE | Execution binding | The launcher binds CURRENT input/provider/workspace/limits/policy at execution time; non-TTY approval is a typed NeedsApproval refusal (exit 25). Build identity (source+input digests) invalidates stale result claims. | §7 |
| DF | Refusals | Typed refusal taxonomy for unsupported selections, missing context, secret-bearing exports, path escape/symlink/existing target, ambiguous effects, invalid edited source. | §8 |
| DG | Concurrency/snapshot | Consistent export snapshot at a turn boundary; interrupted exports never look complete; restart does not redispatch; ADR-031 projection stays zero-dispatch and unaffected. | §9 |
| DH | Honesty | No arbitrary-session conversion, no durable resume, no forge/deploy/publish, unknown pricing stays unknown (ADR-029 H3 `MicroUsd(0)` trap), bundle ABI unchanged. | §10 |

## 3. Packaging decision: Option L (launcher artifact) — DECIDED

### 3.1 The two options ADR-029 left open

| | Option L (launcher artifact) | Option B (bundle v1 as-is) |
| --- | --- | --- |
| Artifact shape | Ordinary Scala source: workflow definition + explicit `main` constructing the launch capability (ADR-029 §3.1) | Class implementing the frozen no-arg `BundleWorkflow` SPI, loaded by `BundleLoader` |
| Service injection | Explicit constructor wiring in the `main`: backend/tools/policy/workspace are arguments | **Impossible**: the SPI has a public no-arg constructor and only `name`/`program` members (`bundle.scala:92-100`); a v1 program cannot receive a backend, tools, admission, or workspace |
| Admission/ledger | Loop-owned admission per dispatch; one root/admission/ledger per run (ADR-029 D3) | One opaque outer Model slot around the whole program; internal rounds invisible; settles `MicroUsd(0)` (`HeadlessRunner.scala:624-647`) |
| Wire impact | None — v1 stays frozen, `BundleLoader`/`schemaVersion {1}` untouched | None for the wire — but making agent work *honest* behind v1 requires bundle **v2** (new `schemaVersion`, separate ADR, loader/runner migration, ADR-029 D2) |
| Local vs CI | The same `main` in different process environments (ADR-029 §3.1 rule 5) | Same v1 path — but only deterministic non-agent programs work today |
| Accounting honesty | Cost reporting says unpriced/unknown (H3 gap is a reporting limitation) | Cost reporting would be **structurally** zero for the whole workflow — the exact `MicroUsd(0)` trap ADR-029 H3 forbids presenting as truth |

### 3.2 Decision and justification

**Option L is selected.** Justification against ADR-029 §3.2 and the
RAI-155.d goal (local + non-TTY generic CI):

1. **Option B is not viable for the required behavior.** CWF-01 requires the
   exported workflow to "launch real agent work and trusted checks". Behind
   v1, the workflow program cannot obtain a backend or tools (no-arg SPI), and
   even if it reached one through hidden global state (forbidden by ADR-029
   H2), the entire workflow would be one unobservable admission unit with zero
   settled cost. ADR-029 §3.2 states this "must not be presented as typed,
   budgeted, per-round admission".
2. **The honest Option B route is out of scope.** A service-carrying wire is a
   bundle v2 proposal: new `schemaVersion`, separate reviewed ADR,
   loader/runner migration, updated consumers (ADR-029 D2/§5.3). CWF-A01
   requires "unchanged bundle ABI where feasible" — Option L needs **zero**
   wire change; a v2 detour would add a migration risk the first slice does
   not need.
3. **Option L satisfies the local+CI goal with one definition.** The compiled
   launcher `main` runs locally (dev) and in non-TTY generic CI (same binary,
   explicit input, artifacts and exit codes per `ci-runtime` §7–8); "local vs
   CI differs by process environment, not by a second implementation"
   (ADR-029 §3.1 rule 5). One root/admission/ledger per invocation; policy
   preflight before spend.
4. **Accounting honesty degrades gracefully.** Under Option L the known H3
   settlement gap is a *reporting* limitation (usage shown as unpriced/unknown,
   never zero). Under Option B it would be a *structural* lie (the only
   settlement the wire/runner can produce is zero).

**Roadmap §6d alignment note (coordinator action).** Roadmap §6d says
"Prefer the existing BundleWorkflow v1 String-to-String boundary with typed
input decoding inside the workflow". With Option L decided, the first half of
that sentence needs alignment: the **executable artifact is a launcher main,
not a v1 bundle entry**. What stands: the exported workflow definition remains
a `Program[String,String]`-shaped composition with **validated JSON decoding
inside the String input boundary** (§6.3), pinned toolchain, existing
scheduler/runner, and the v1 wire itself remains frozen and untouched. The
coordinator should record this wording alignment when registering the .b–.f
allocation; roadmap §6d's own requirement that "RAI-155.a freezes the proposed
commands and output layout before implementation" is satisfied by this ADR.

**What would reopen Option B:** an accepted bundle v2 ADR (service-carrying or
typed wire) with a completed migration. Nothing in this contract pre-drafts
that; v1 stays frozen.

### 3.3 Consequence for the .d acceptance wording

CWF-D01 says "non-TTY CLI bundle launch". Under Option L the non-TTY launcher
runs the **launcher artifact** (the compiled `main`) through the existing
headless discipline (preflight, artifacts, exit codes, SIGTERM ownership);
it does **not** go through `BundleLoader`/v1 SPI. The .d card's own sentence
"CLI syntax must reflect implementation; currently no --ci or compile command
may be assumed" is honored: §7 and §11.3 propose command names that become
real only when .d implements them.

## 4. First scope: the Supported Task Specification (STS)

The first slice supports exactly ONE workflow shape. Anything outside it is a
typed refusal (§8), never an approximation.

### 4.1 STS components (normative)

| Component | Content | Origin |
| --- | --- | --- |
| `TaskIntent` | One explicit intent turn: the coding task statement (e.g. "make the failing module compile and keep its checks green"). Marked by the user in chat (proposal: `:task mark`), never inferred from message timing or content. | chat turn (kind `Intent`) |
| `Refinements` | An ordered list of explicit refinement turns that constrain the task (e.g. "do not touch the public API"). Applied in selection order. | chat turns (kind `Refinement`) |
| `TaskParams` | Named task/workspace parameters that vary per run: concrete target path(s), the task input instance. Externalized to the input file (§6.2); never hard-coded from one run's captured values. | selection + input schema |
| `NamedTrustedChecks` | User-named deterministic check commands with validated `argv` (e.g. `compile = sbt --batch compile`, the same shape the existing `:fix` loop uses, `CompileFixLoop.scala:65`). Rendered as trusted check definitions; model text can never add or alter a check. | explicit user registration |
| `AgentStage` | ONE sequential bounded agent stage: the model+tool loop executing the intent under the refinements, with `maxRounds` bounded by the effective policy at execution time. No parallelism, no delegation, no phases in the first slice. | selection |
| `SuccessPolicy` | The named checks must pass on the NEW workspace; "the agent said done" is never a verdict (ci-runtime §7). A failed check is a failed run. | fixed by this contract |

### 4.2 Not representable in the first slice (refusal, §8)

Parallel composition (`all`/`batch`), model-initiated delegation, phase
graphs, SDLC effects (commit/PR/merge/release — RAI-117), durable
continuation, branching inferred from conversation, concurrency inferred from
timestamps, and conversion of arbitrary conversations that do not contain an
explicit intent/refinement/check structure. RAI-153/154 gate any later claim
of unified phase/delegation lineage for this path; the first sequential
export does not wait for them (roadmap §6d).

### 4.3 Determinism and synthesis

- The renderer (§6) is a **pure function** of the selection snapshot and the
  STS: identical selection bytes → identical output bytes (no clock, no
  randomness, no model call). This is checkable in .c/.e acceptance
  (CWF-C01 "identical supported specification renders stable output").
- **Model-assisted synthesis is optional, explicit, and configured.** If a
  future slice adds LLM help for drafting an STS from a conversation, it is a
  separate command with its own provider configuration and budget, its
  proposal is validated before use, and the default remains deterministic
  rendering. The first slice ships deterministic-only; no synthesis path is
  required for export.

## 5. Chat capture contract (implemented by RAI-155.b)

### 5.1 Turn records (what is persisted per turn)

Each captured turn is a record **separate from the model-facing message
list**:

| Field | Content |
| --- | --- |
| `turnId` | stable unique id within the session |
| `kind` | `Intent` \| `Refinement` \| `Ordinary` (explicit marking; `Ordinary` turns are capture context, not exportable structure) |
| `request` | the user's request text (bounded) |
| `jobId` | the per-message job id (RAI-060 wiring exists: `ChatLoop.runJob`) |
| `status` | `Completed` \| `Failed` \| `Interrupted` \| `Unknown` — the job's terminal outcome; `Unknown` covers torn/pending state |
| `toolSummary` | bounded per-tool outcome summary (tool name, ok/refused/failed, recovery class where known) — **no payloads** (same carve-out discipline as ADR-031 §2.5) |
| `configRefs` | workspace path and provider/model **references** as configured for that turn (non-secret; no credential values, §6.5) |
| `atMs`/`finishedAtMs` | wall-clock start/end (best effort; never used to infer structure) |

The capture ledger is append-ordered; ordering is the turn sequence, not
timestamps.

### 5.2 Survival across save/load and compaction

- The ledger persists **inside the session document** (proposal: additive
  `schemaVersion 3` on `SavedSession`; v1/v2 documents load unchanged — the
  existing typed version gate, `Session.scala:54-87`, is the pattern).
- Compaction rewrites the **message list** (`ChatLoop.scala:500`); it must
  never drop or rewrite ledger entries. The ledger — not the message list —
  is the export-provenance source. (Today compaction destroys exactly the
  provenance an exporter would need; this clause is the fix.)
- Save/reopen round-trips the ledger with stable ids and ordering (CWF-B01).

### 5.3 Legacy sessions and missing context — typed refusal

- v1/v2 (conversation-only) sessions remain **readable** (existing behavior
  unchanged) but contain no ledger; export from them is a typed refusal
  (`ExportRefused.NoCaptureContext` → `RaiderError.InputValidation` family,
  §8): the product **cannot invent absent execution records**. The message
  names the missing structure and the remedy (re-run the task in the current
  chat with explicit task marking).
- Reopening a session never restores authority and never silently rebinds the
  active backend/workspace: the saved `configRefs` are compared with the
  active configuration and any mismatch is displayed explicitly before work
  continues (CWF-B02).

### 5.4 Incomplete and interrupted state

- A turn interrupted by crash/EOF/SIGTERM keeps its pending user request and
  status `Interrupted`/`Unknown` (persisted by the next save or a
  crash-tolerant append); it never disappears silently (CWF-B02).
- A selection containing any non-`Completed` turn is refused for export with
  the offending turn ids (§8) — the user re-runs or narrows the selection.
- Restart after an interrupted mutation does not redispatch it (existing
  trust-boundary rule; CWF-B03).

## 6. Export semantics (implemented by RAI-155.c)

### 6.1 What export is

Export = a **deterministic render** (§4.3) of a bounded, immutable selection
snapshot into three artifacts (§6.2). Export performs **no model/tool
dispatch, no provider call, no network I/O** — construction of the exported
program value is lazy by the existing API-03 discipline, and compilation is a
pure scalac/sbt step (CWF-A02/CWF-C03). Negative no-provider probes are part
of .c/.d/.e acceptance.

### 6.2 Output layout (proposal — exact names frozen at .c implementation)

```text
<target-dir>/
  <name>.scala            # editable launcher + workflow definition (§6.3)
  <name>-manifest.json    # non-secret origin/build-identity metadata (§6.4)
  <name>.input.json       # versioned input example (§6.6)
```

Write discipline: atomic (staging directory + rename); an interrupted export
leaves a distinguishably incomplete staging directory and is never presented
as a complete workflow (CWF-C03). An existing non-empty target is a typed
refusal — export never overwrites user edits. Path escapes, symlink targets,
and unwritable locations are typed refusals (§8).

### 6.3 Generated source shape (illustrative — signatures are NOT frozen)

```scala
// Generated by Raider chat export (PROPOSAL, RAI-155 chain) — EDITABLE SOURCE.
// Provenance: session <id>, turns [<ids>]; identity in <name>-manifest.json.
// This file is trusted Scala: it runs with this process's permissions, and
// tool policy is not a sandbox (runtime-contracts §10). The text below is
// DATA captured from a chat session — it is not an instruction to invoke a
// shell; the only executed commands are the named checks defined here and
// the agent stage's policy-governed tool calls at RUN time.

object <Name>:
  // ---- input boundary: validated JSON decoding INSIDE the String boundary
  final case class Input(workspace: String, task: String, /* ... */)
  object Input:
    given zio.json.JsonCodec[Input] = zio.json.DeriveJsonCodec.gen

  // ---- explicit, editable parameters (the "meaningful edit" surface)
  val promptTemplate: String = "..."        // intent + refinements, EDITABLE
  val checkCompile: List[String] = List("sbt", "--batch", "compile") // EDITABLE
  val maxRounds: Int = 6                     // bounded agent work

  // ---- the definition: existing lazy Task/Program composition verbs
  val workflow: Program[String, String] = rawInput =>
    for
      input <- decodeOr(refuse)(rawInput)          // validated, typed failure
      _     <- agentStage(promptTemplate, input)   // bounded, current policy
      _     <- trustedChecks(List(checkCompile))   // deterministic commands
    yield /* validated report */

@main def <name>(args: String*): Unit =
  // Launcher (Option L): constructs the ADR-029 §3.1 launch capability
  // explicitly — backend from CURRENT env/config (typed auth failure, no
  // hidden fallback), tools for the explicit workspace, current limits.
  // Credentials come from env/helper references; none appear in this file,
  // the manifest, or logs. Authority is NOT inherited from the chat session.
  ...
```

Program shape summary (checkable by .e): one `Program[String,String]`-shaped
definition with validated JSON decoding inside the boundary; named editable
constants for prompt/checks/limits; one bounded agent stage; deterministic
checks; an explicit `main` launcher. The definition performs no dispatch until
the launcher runs it.

### 6.4 Manifest (non-secret origin metadata, illustrative)

```json
{
  "schemaVersion": 1,
  "kind": "chat-workflow-export",
  "generator": "raider <version>",
  "source": {
    "sessionId": "<id>", "turnIds": ["..."],
    "specDigest": "sha256-256-hex"
  },
  "buildIdentity": { "sourceDigest": "sha256-256-hex" },
  "origin": { "model": "<name>", "providerKind": "openai-compatible|anthropic-compatible" },
  "checks": [ { "name": "compile", "argv": ["sbt", "--batch", "compile"] } ]
}
```

Rules: credential values never appear (the manifest is scannable and the
export refuses secret-bearing selections, §6.5/§8); `buildIdentity.sourceDigest`
is what .d records into run artifacts so "a manual edit invalidates earlier
result claims" (CWF-D02). The manifest describes provenance; it is **not**
program data and is never executed.

### 6.5 Externalization and redaction

- Prompts/refinements/checks/limits render as **named editable constants** in
  the source — the user edits them without regenerating (CWF-C01: an edited
  prompt/check survives compilation and changes behavior).
- Bulky captured payloads (model responses, tool results) are **not embedded**;
  the manifest references source turn ids only. Recorded chat is provenance,
  never program data.
- Secret-bearing selections are refused before anything is written: values
  matching credential shapes (env-resolved key material) abort export with a
  typed error that does not echo the secret. Credentials remain env/helper
  references in the generated source (CWF-A02).

### 6.6 Input schema (illustrative; versioned)

```json
{
  "schemaVersion": 1,
  "workspace": "/abs/path/to/fresh-copy",
  "task": { "targetPath": "src/main/scala/Foo.scala", "summary": "..." }
}
```

- Consumed at RUN time by the launcher; validated decoding inside the program
  (§6.3). The workspace must exist, be a directory, and pass the same
  path-escape/symlink checks as export targets (§8).
- The same input drives local and CI runs; two different inputs must produce
  observably different runs (CWF-01 matrix semantics belong to .d/.e).

## 7. Execution binding (implemented by RAI-155.d)

- **Compile/package (proposal names, reserved only at .d implementation after
  a fresh source audit):** `raider compile <source>.scala --out <artifact>`
  compiles the edited source with the pinned toolchain (Scala 3.9.0 / ZIO
  2.1.26 / JDK 21, ADR-029 D5) into a runnable launcher artifact. Compilation
  dispatches nothing agent-shaped. Invalid edited source → typed compile
  failure, nonzero exit (21), never an auto-run.
- **Run (proposal names):** `raider workflow run --artifact ... --input
  <file>` locally; the same launcher artifact non-TTY in generic CI. Whether
  .d routes execution through ADR-029 PKT-1's launch capability or a
  CLI-internal assembly over the same runtime parts, it MUST preserve the
  ADR-029 D1/D3/D4 rules — one root/`Admission`/`BudgetLedger`/`JobManager`
  per run, explicit backend/tools/policy/workspace, no hidden
  `Runtime.default` in the capability path, single admission discipline, no
  second scheduler. The choice is recorded in .d's own card before code.
- **Binding at execution, never from chat:** provider+credentials (env/helper
  references; typed auth failure, no hidden fallback), workspace (explicit,
  fresh copy in acceptance), limits/policy (current configuration), approvals
  (none inherited; policy-ask in non-TTY mode → `NeedsApproval`, exit 25 —
  `result.scala:28`, `ci-runtime` §6/§8). A new CI invocation performs the
  full policy check again (CWF-A03).
- **Artifacts/exit codes:** per `ci-runtime` §7–8 (`run.json`, `result.json`,
  `events.jsonl`, `checks.junit.xml`, exit table unchanged). `run.json`
  records source+input digests (build identity) alongside the usual
  context/accounting fields. Cost fields report unpriced/unknown until the
  H3 settlement correction lands (ADR-029 §4/PKT-8) — **never `MicroUsd(0)`
  presented as observed cost** (§10).
- **Cancellation:** SIGTERM/SIGINT own and terminate launched work within the
  existing bounded-grace mechanics; outcome is a truthful nonzero/incomplete
  artifact (exit 143/130/26 as applicable). No automatic mutation retry; no
  durable resume (CWF-D03, ADR-029 H1).

## 8. Refusal taxonomy (CWF-A02)

All refusals are **typed** and ride the existing sealed `RaiderError`
families (runtime-contracts §14). A dedicated `ChatWorkflow` error family
would be a core schema change and requires its own card (same discipline as
ADR-031 §2.4); until then the `InputValidation` family carries a structured
detail prefix (illustrative code `CWF-…`) so consumers can distinguish these
refusals without string-matching prose. Secrets never appear in details.

| # | Case | Typed mapping | Actionable message names |
| --- | --- | --- | --- |
| R1 | Unsupported selection (parallel/delegation/phase/non-STS structure) | `InputValidation` | what is unsupported, why, and the supported shape |
| R2 | Missing capture context (legacy conversation-only session) | `InputValidation` | the session lacks turn records; export cannot invent them; remedy |
| R3 | Non-terminal turns in selection (pending/`Interrupted`/`Unknown`/`Failed`) | `InputValidation` | the offending `turnId`s |
| R4 | Secret-bearing selection/export | `InputValidation` | the turn/field class — never the value |
| R5 | Target path escape / symlink / existing non-empty target / unwritable | `InputValidation` / `JournalFailure` | the path rule violated |
| R6 | Ambiguous effects (turn with unknown mutation outcome) | `InputValidation` | re-run explicitly; nothing auto-retries |
| R7 | Invalid edited source / invalid input JSON / bad manifest | compile/decode failure, exit 21 | compiler/decoder diagnostics |
| R8 | Unmet policy/approval at run time | `ToolDenied` / `CapabilityUnsupported` → exit 22/25 | current policy requirement |
| R9 | Failed trusted check | `TaskRejected` outcome, exit 10 | the failing check — a failed check stays failed |

**Export text is data, never an instruction to invoke a shell.** Captured
chat text is rendered only as escaped Scala string data (or manifest/input
JSON with proper encoding); no captured text can become a check command, a
launcher argv, or a shell line. The only executable commands in the artifact
are the user-registered named checks (validated argv) and the agent stage's
policy-governed tool calls at run time. .c/.e carry negative probes for this
(hostile Scala literals, injection attempts — CWF-C02/CWF-E02).

**ABI discipline:** this contract requires **no** change to the frozen
bundle v1 wire, `Task`/`Program` API, or event seam. The only new public
surface is the launcher/CLI/chat UX built by .b–.d, plus the additive session
schema v3 (versioned migration, existing typed version gate). Any future
public ABI change discovered during implementation is separately versioned
and reviewed BEFORE implementation (CWF-A02).

## 9. Concurrency, snapshots, cancellation (CWF-A03)

1. **Consistent export snapshot.** Export takes a bounded immutable selection
   at a turn boundary: a concurrent append either is entirely outside the
   selection or produces a new export; a torn turn is never included
   (non-`Completed` turns are refused by R3 anyway). Concurrent exports to
   the same target refuse (R5); to different targets they are independent.
2. **Interrupted export.** Staging + rename: a partial export is never
   complete-looking; a restart re-exports explicitly.
3. **Saved/reopened sessions.** Round-trip preserves ledger, ids, ordering,
   and terminal statuses; mismatch of saved config refs vs active
   backend/workspace is shown explicitly (§5.3); authority is never restored.
4. **Projection separation (ADR-031 untouched).** The four lineage layers
   (rendering, dry-run projection, replay, live rerun) keep their semantics;
   the projection remains **zero-dispatch** at construction AND execution.
   The exported workflow artifact is a **fifth, distinct** thing — an
   authoring product that executes REAL agent work when its launcher runs.
   It is not a rerun/replay entry, carries the provenance header, and never
   substitutes for a projection in either direction.
5. **Cancellation ownership.** The launcher owns its launched work; SIGTERM
   cleanup is bounded and honest (§7); chat-side export itself is a fast pure
   step, and its interruption leaves only the staging remnant (2).
6. **Restart.** No restart path redispatches a started mutation — the
   session's pending/unknown turn state survives (§5.4) and requires an
   explicit user action.

## 10. Compatibility and honesty obligations

| # | Obligation | Where |
| --- | --- | --- |
| K1 | No arbitrary-session conversion: only an explicit STS selection exports; everything else is refused (R1/R2) | §4.2, §8 |
| K2 | No durable resume of arbitrary Scala; interruption = `Interrupted`/`Uncertain` + explicit rerun (ADR-029 H1) | §7 |
| K3 | No forge/deploy/publish/PR/merge/release — RAI-117 owns SDLC effects; checkpoints/external-effect coordination stays with RAI-117 without expanding this slice | §4.2 |
| K4 | ADR-031 dry-run separation preserved; projection zero-dispatch and unaffected | §9.4 |
| K5 | Unknown pricing stays unknown: both chat runs and launcher runs report unpriced/unknown cost until the settlement correction (ADR-029 H3: `AgentLoop.collect` ignores `UsageObserved`; loop and bundle paths settle `MicroUsd(0)` — `AgentLoop.scala:488,508-525`, `HeadlessRunner.scala:638-646`). **No observed-USD or hard-cap claim through the exported path until a separate runtime card corrects and verifies settlement.** | §7 |
| K6 | Bundle v1 ABI unchanged: no schemaVersion change, no SPI change, existing bundles and examples unaffected | §3, §8 |
| K7 | Generated/exported workflows are trusted Scala, not sandboxed; tool policy is not a security boundary (runtime-contracts §10, ci-runtime §5) | §6.3 header, §8 |
| K8 | No hidden provider fallback in the launcher (ADR-029 H7); typed auth/policy failure | §7 |
| K9 | Export/build never dispatch agent work implicitly; negative probes mandatory in .c/.d/.e | §6.1 |
| K10 | Command names are proposals until implemented; docs never advertise unshipped commands | header, §11 |

## 11. Implementation surfaces for RAI-155.b–.f (PROPOSALS — explicitly not-yet-accepted)

### 11.1 Surfaces and responsibilities

| Card | Proposed surface (NOT implemented) | Allowed paths (per card) | Responsibility under this contract |
| --- | --- | --- | --- |
| RAI-155.b | Session capture ledger (`SavedSession` schemaVersion 3, additive); chat commands `:task mark`, `:task select`, `:export-workflow <name> <target>` (all proposals) | `modules/cli/.../chat/**`, `Main.scala` | §5 semantics; selection; save/load/compaction survival; typed legacy refusal |
| RAI-155.c | Deterministic renderer module `raider.dsl.workflow.export` (proposal); emits §6.2 layout | `modules/dsl/.../workflow/**`, `modules/cli/.../chat/**`, `examples/chat-workflow/**` | §4 STS validation, §6 render/escape/redaction, determinism probes |
| RAI-155.d | CLI verbs `raider compile`, `raider workflow run` (proposals; exact syntax fixed at implementation) | `modules/cli/.../workflow/**`, `.../run/**`, `Main.scala`, `scripts/build/chat-workflow.sh` | §7 binding, build identity, artifacts/exit codes, SIGTERM cases |
| RAI-155.e | Independent verifier; fixtures under `contracts/chat-workflow/**`, `scripts/quality/chat_workflow.py` | (verifier card) | CWF-01..03 matrix, negative corpus, no-provider probes |
| RAI-155.f | English walkthrough with the complete exported + edited source | user docs | K10: documents only shipped commands |

### 11.2 Supported-task-spec skeleton (illustrative, non-frozen)

```text
TaskSpec {
  intent:      TurnRef            // exactly one, kind Intent, status Completed
  refinements: List[TurnRef]      // ordered, kind Refinement, all Completed
  checks:      List[NamedCheck]   // non-empty; name + validated argv
  agentStage:  { maxRoundsBound } // bounded by policy at run time
}
Selection = List[TurnRef]  // bounded, contiguous-by-construction, immutable snapshot
```

### 11.3 Input/output layout

- Input (run time): `<name>.input.json` (§6.6) — the same file for local and
  CI launches.
- Output (run time): `ci-runtime` §7 artifact set written under the explicit
  `--out` directory; exit codes per §8.
- Export (authoring time): §6.2 layout.

### 11.4 Traceability — parent acceptance ↔ contract clauses

| Parent/card clause | Contract section |
| --- | --- |
| CWF-01 (export/edit/compile/local/CI matrix) | §4, §6.3, §7 |
| CWF-02 (typed failures; no implicit dispatch; explicit synthesis; deterministic no-provider generation) | §4.3, §6.1, §8 |
| CWF-03 (turn-boundary snapshot; interruption preservation; SIGTERM; same-runtime launch) | §9, §7 |
| CWF-A01 (lifecycle/schemas/program shape/responsibilities; one runtime; unchanged ABI) | §3, §4.1, §6.2–6.4, §11.1, §11.4 |
| CWF-A02 (typed refusals; text-is-data; ABI versioning) | §8 |
| CWF-A03 (snapshot; reopen; pending turns; cancellation; CI policy; non-TTY refusal; projection untouched) | §5.3–5.4, §7, §9 |

**Reference lifecycle for fixtures (the shape .b–.f implement and .e freezes):**
a multi-turn repair session — (1) user marks intent "make module X compile and
keep checks green"; (2–3) two refinement turns ("don't touch the public API",
"prefer the smallest diff"); (4) agent stage: model+tool rounds edit files;
named check `compile` fails; agent repairs; check passes; (5) user selects
turns 1–4, exports `<name>`; (6) user edits `checkCompile`/`promptTemplate`
in the source; (7) pinned compile succeeds; (8) the edited launcher runs on a
fresh workspace copy locally and non-TTY in CI on two inputs — real model/tool
work, checks failing-before/passing-after on the new workspace, pairwise
equal results for equal inputs; (9) a fifth fresh unedited run isolates the
edit's effect. No recorded answer or historical patch is replayed.

## 12. Remaining-work estimate (PROPOSAL — the coordinator records allocation)

Per the card obligation; ADR-029 packet sizing convention (8–24 h), all
values are estimates before implementation, not approved budget:

| Card | Estimate | Driver |
| --- | --- | --- |
| RAI-155.b | 16–24 h | schema v3 + ledger + commands + compaction/save-load integration + B01–B03 |
| RAI-155.c | 16–24 h | STS validation + deterministic renderer + escaping/redaction + C01–C03 |
| RAI-155.d | 16–24 h | compile/package + execution binding + artifacts/exit/SIGTERM + D01–D03 |
| RAI-155.e | 16–24 h | independent matrix + negative corpus + stands (E01–E03) |
| RAI-155.f | 8–16 h | walkthrough with real artifacts |
| Total proposed | 72–120 h | plus parent integration (existing umbrella) |

Recorded unknowns/risks:

- **Launch-capability prerequisite (highest risk).** ADR-029 PKT-1
  (WorkflowLaunch core, 16–24 h) is unscheduled. .d needs it, or a
  CLI-internal assembly with identical D1/D3/D4 rules (§7); the decision is
  made in .d's card. Either route adds work not counted in the 72–120 h.
- **Pricing policy** for unpriced models (H3) — stays unknown; K5 holds
  regardless.
- **Session schema v3 migration** details (crash-tolerant append vs
  next-save persistence) are .b implementation decisions within §5.4 bounds.
- **Tradeoff if allocation shrinks:** .f polish and .e corpus breadth are the
  elastic parts; the CWF acceptance matrix, typed refusals, and no-dispatch
  probes are not.

## 13. Non-goals

Implementing any runtime behavior (this card is contract-only); weakening
protected verifiers or gate policy; forcing all chat records through unified
lineage; redefining bundle v1 or SDLC effects; a second scheduler/runtime; a
general chat-to-program synthesis platform; durable workflow engines;
phase/delegation export (RAI-153/154 territory); SDLC effects (RAI-117).

## 14. Consequences

- **Positive:** the RAI-155 chain has one frozen packaging decision (L), one
  supported scope, typed refusal boundaries, and a checkable honesty table;
  .b–.f can implement against this document, and .e can verify against it
  without re-litigating scope.
- **Costs/risks:** Option L means the non-TTY launcher is a new CLI surface
  (not a reuse of `BundleLoader`) — its preflight/artifact/exit discipline
  must be proven equivalent in .d/.e; the launch-capability prerequisite is
  open (§12); the roadmap §6d sentence needs the coordinator's alignment note
  (§3.2); the H3 accounting gap means all cost claims through this path stay
  unpriced/unknown until the separate settlement card lands.
- **Neutrality:** no change to the frozen `Task`/`Program` API, bundle v1
  wire, ADR-024 seam, ADR-031 projection, gate policy, or existing chat
  behavior beyond the additive capture ledger and new explicit commands.
