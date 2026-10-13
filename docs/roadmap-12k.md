# Raider: 12,000 hours to a useful daily tool

Owner's revision dated 2026-10-05. Canonical order for further development;
replaces the sequence and budget in [roadmap v2](roadmap-10k.md).
Result: one local Raider with subagent tabs, clear intermediate statuses, and
installable plugins. **The Yabanin gateway is exclusively P11, the final phase
after acceptance of the standalone product.**

This is an estimate of remaining engineering effort, not hours already completed,
LLM runtime, or a promise of one uninterrupted 12,000-hour session. Implementation
proceeds in small, verifiable cards. The [working acceptance](working-product.md),
[parallel runbook](parallel-development.md), [contracts](runtime-contracts.md),
and [gates](quality-gates.md) remain in force, with the sequencing refinements below.
Launch: [full OpenCode prompt](prompts/opencode-12k-parallel.txt).

> **Revision 2026-10-10 (owner, ADR-027):** phase **P-LIN — Session lineage
> (the call tree as a valid Scala program)** has been added **ahead of the entire
> plan**, +1,100 h (13,100 h total). The minimal tab host for the Lineage tab is
> pulled forward from P2 on a limited basis (one tab only, no drafts). The other
> phases are unchanged in content; cumulative totals have shifted. Decisions are
> recorded in [ADR-027](adr/ADR-027-session-lineage.md).

> **Execution revision 2026-10-12 (owner):** improvements are carried out as
> **visible spawned subagents** in the owner's interactive session (`/raider-wave`,
> `.opencode/agents/raider-worker|raider-reviewer`; mechanics in
> [parallel-development.md §1b](parallel-development.md)). Background bash loops
> `opencode run`/nohup-night-loop for executing cards are prohibited by default;
> headless overnight execution requires a separate explicit owner decision.
> Parallel lanes are open alongside the remaining P-LIN work: **P2 TUI remnants**
> (per-tab drafts, scrollback bounds, unread badges, palette/help — P2.B/C remnants
> on top of accepted RAI-057/061/062), **P1 remnants** (RAI-059/060), and **P3**
> (RAI-063, send/steering) — dependencies in §5 have been closed by evidence
> (P0-WAVE0: ADR-024 + renderer spike; W1: events). This does not change phase
> budgets: cards are registered and accepted under the shared gates, and hours are
> charged to their respective phases.

## 1. What actually exists

### Revision 2026-10-12: embeddable SDK within the current phases

At the owner's request, we are expanding the usefulness of the existing runtime:
Raider remains a standalone coding agent with a real REPL and CI; an additional
user path is embedding agent workflows in a Scala 3 + ZIO application. The working
positioning is managed agent workflows with explicit limits, child-task
cancellation, and observable results, executed by one runtime in an application,
REPL, and CI. This is a testable product hypothesis, not a claim that the SDK is
ready. The owner confirmed: Raider + SDK, first scenario is CI, and the current
wave must not stop. The product is fully open source; the existing MIT/license
policy remains. A paid edition, closed SDK capabilities, and monetization are out
of scope. Product feedback measures independent use, repeat runs, community
issues/contributions, and maintenance cost for maintainers.

Sources were checked at `4b46dc7`: `core` contains lazy `Task` and `Program`,
`dsl` contains typed composition, `runtime` contains the loop/jobs/scopes/admission/
budgets; convenient launch operations are in `repl/facade`. The plugin-author SDK
(RAI-096) already has its own scope; it does not prove that the runtime is easy to
embed in a third-party application. `build.sbt` separates these modules, but the
presence of modules is not a check of an external consumer or a published artifact.

The current LIN-13/14/15 and RAI-113/114 wave, P-LIN obligations, TUI, and W01–W18
remain in force. No new top-level phase is being introduced. P0.A/B refine the SDK
boundaries; P4.C accepts a minimal external consumer; P7 checks practical value;
P10 accepts the deliverable. Contracts change only through a separate ADR card
with independent review and consumer updates. Detailed guides and a full update
of user documentation remain P9/P10 work; a short command for running the
consumer example is needed with the example itself for reproducible acceptance.

The primary SDK user is a Scala workflow author for AI-native SDLC; the first
application scenario is TokenRouter development locally and in generic CI
(refined below). A host Scala/ZIO application remains an additional SDK surface.
Recruitment of pilot users starts in P0.A and continues in P7.C/P9.C. If there is
no response, demand stays `pending`; this does not block independent engineering
work and must not be turned into a positive user assessment.

The 13,100 h budget remains a planning envelope for now, not a new estimate of
remaining work: existing P0/P4/P7/P10 packages are reused, and accepted slices
are not charged again. RAI-115 must estimate added effort and available remainder;
before assigning implementation, the coordinator records used/reserved/remaining
and a concrete reallocation within the packages. If the remainder is insufficient,
revise scope/estimate explicitly under §7, without cancelling earlier acceptance
or promising that the “SDK is free.”

### Owner refinement after SDK research, 2026-10-12

The SDK's main differentiator is the ease of describing AI-native SDLC in ordinary
Scala. The user's first success is to write a workflow, run it locally, and run
the same workflow on a **local CI runner**. A CI system has not yet been selected;
GitHub Actions is not the first adapter. A forge for PR/merge has also not been
selected: first the general contract and a local Scala HTTP stand, then a selected
adapter in a separate card.

The first project is `../tokenrouter` (Yabanin), **development of its code**, not
connection to its gateway. The owner confirmed this distinction: P11 remains last;
read-only consumer discovery and SDLC requirements are permitted now, while
TokenRouter changes receive separate cards of its own. The current TokenRouter
checkout is dirty; its baseline and hashes of the inputs read are recorded in the
[research](research/sdk-roadmap-research-2026-10-12.md).

The goal is a complete cycle: task/plan → implementation → checks/repair → review
→ commit/PR → merge → version/changelog/**local tag**/release artifacts. Commit/merge
are automatic under the configured policy; the author of the Scala workflow
configures human checkpoints. Pushing a release tag, publishing packages/containers,
and deployment are out of the first scenario. This extends the old non-goals of
the CI/runtime contracts: the change must be recorded in a separate ADR (RAI-117);
implementation prohibitions remain until then.

Experimental workflow preview is permitted before M4 under its own limited
acceptance (§6b). The current pinned Scala 3 + ZIO toolchain is used; preview API
changes are permitted with migration. Support for all Scala 3/JDK versions, a
stable API, and public publishing are not promised. Crash resume is not required:
interrupted/unknown and an explicit new invocation. Detailed preview records are
used only with trusted local data; broader export/retention policy is deferred and
is not claimed as implemented.

Sequencing is based on the [market/SDLC research](research/sdk-roadmap-research-2026-10-12.md)
and the RAI-115 source audit. Alternatives already have generic ZIO facades/DSLs,
so the ease-of-use promise is tested with our own SDLC workflow, not by counting
combinators.

### Historical baseline 2026-10-05

Source inspection at commit `7ee6c0c`, 2026-10-05; this is inventory, not new
runtime acceptance. The board has newer session records than some of the status
lines near the top. Check the sources at each run instead of repeating old
estimates.

| Area | Found in source | Next improvement |
| --- | --- | --- |
| Native runtime | `modules/runtime/.../loop/AgentLoop.scala`, jobs/scopes/admission/budget | Check open cancel/ownership cases and connect them to observable states |
| Providers | `modules/provider-chat`, `modules/provider-anthropic` | Preserve both direct wires and equivalent acceptance |
| Coding workflow | `modules/tools`, CLI headless/chat, patch/process/search | Check the full read → edit → failing test → fix → passing test cycle |
| Scala REPL | `modules/repl-engine`, `modules/repl`; real compiler and facade | Preserve bindings/multiline input; add completion with the TUI |
| Subagents | JobManager, DelegationToolset, `start/await/cancel` | `send` still refuses; `watch` only has initial/final snapshots; live tabs are needed |
| Statuses | TraceEvent, JobSnapshot, StreamingDisplay | Unified subscription, current state, reasons for waiting, bounded tool progress |
| History | `modules/cli/.../chat/Session.scala`: SessionStore and compaction | Migrations, workspace/tabs recovery, truthful status after crash |
| Plugins | ToolRegistry, BundleLoader as separate foundations | No implemented user-facing plugin lifecycle/loader |
| UI | JLine and [ADR-016](adr/ADR-016-tui-renderer.md), [design](tui-design.md) | `modules/ui` does not yet exist; current output is linear |

At the start of work, there is a user-owned untracked file
`docs/research/yabanin-state-discovery.md`: preserve it; do not treat it as an
accepted contract or authorization to begin integration before P11.

## 2. The scenario the product is being built for

The user launches `raider`, selects a direct provider on first entry, and types
“fix the tests in this project.” The main agent creates a scout/coder/reviewer;
each appears in a tab with a name, current action, and unread events. The user
switches among them, reads their progress, adds a constraint, opens the diff and
check results, and cancels a selected agent or the whole group. An ordinary task
does not require writing Scala; a full Scala REPL is available for complex
workflows and uses the same runtime.

```text
[Main] [Scout: search] [Coder: tests 12s ●] [Reviewer: waiting for slot] [+]
Project scalaagent · 3 running · budget: known amount / unknown
Coder → proc_run: sbt test · output updated 0.4s ago
Changes: 3 files · checks: running · result: not ready yet
> Add a note to the selected agent…
Next tab · New agent · Commands · Plugins · Help
```

This is a mockup and future UX, not an existing command with proven acceptance.

### Measurable usability requirements

| ID | Requirement | Acceptance method |
| --- | --- | --- |
| UX01 | Set up a direct provider, without Yabanin or writing a workflow | 4 of 5 new users complete the offline training task in ≤10 minutes; live credentials are entered separately |
| UX02 | Switch tabs in ≤2 keystrokes, p95 ≤100 ms | 5 agents, 1,000 switches; draft, history, Unicode, and scroll position are preserved |
| UX03 | Each agent has a clear current status | Event-to-visible p95 ≤250 ms on a pinned machine; “waiting” includes a reason |
| UX04 | Fast local UI startup | Ready prompt p95 ≤2 s warm, ≤5 s cold after installation; network TTFT is measured separately |
| UX05 | Plugin available without rebuilding Raider | Install a trusted fixture plugin and run its tool in ≤3 actions after selecting the source; an explicit restart is allowed |
| UX06 | Clear cancellation | One gesture cancels a selected agent; root cancellation is separate; owned subtree/processes terminate within ≤5 s in controlled fixture ×20 |
| UX07 | No commands users have to memorize | Palette, hint bar, action search, fallback keybindings, text statuses independent of color |
| UX08 | Intermediate result and cause of issue are visible | Diff/checks, retry delay, permission request, token usage; unknown cost is shown as unknown, not 0 |

Thresholds are requirements for future checks. P0 pins the hardware/JDK/terminal/
load/sample count and measurement method; thresholds may not be relaxed after a
failure. Human usability without participants remains pending. Progress shows
observable steps, tool output, and short summaries; it does not invent completion
percentages or model thoughts.

## 3. Budget and phase outcomes

Hours include implementation, integration/review, checks, documentation, and
contingency. Each row already includes its share of overhead; coordinator or test
hours are not charged again. Total: **13,100 h** (after the 2026-10-10 revision:
+1,100 h for P-LIN), including 1,200 h contingency (10%).

| Phase | Verifiable outcome | Hours | Cumulative |
| --- | --- | ---: | ---: |
| P-LIN | Session lineage: call tree = valid Scala program, replay | 1,100 | 1,100 |
| P0 | Actual baseline, remaining defects, agreed seams and acceptance | 400 | 1,500 |
| P1 | Live, truthful statuses and bounded tool progress | 900 | 2,400 |
| P2 | Tabs, drafts, scrollback, first simple TUI | 1,400 | 3,800 |
| P3 | Convenient subagent coordination, steering/cancel/review | 1,000 | 4,800 |
| P4 | Plugin lifecycle, embedding SDK, and install without rebuilding | 1,400 | 6,200 |
| P5 | Optional local agent state and shared blackboard | 900 | 7,100 |
| P6 | History, recovery, compaction, and budget control | 1,100 | 8,200 |
| P7 | Complete standalone coding workflow, REPL, and CI | 1,000 | 9,200 |
| P8 | Multiple projects, load, and long-running work | 900 | 10,100 |
| P9 | Fullscreen if needed, accessibility, onboarding, and UX polish | 900 | 11,000 |
| P10 | Acceptance, packaging, and stable standalone release candidate | 1,000 | 12,000 |
| P11 | Yabanin: discovery → optional adapter → final acceptance | 1,100 | 13,100 |

Indicative breakdown of all hours: implementation 7,200; checks/review/integration
3,000; docs/onboarding 600; contingency 1,200. These are two views of the same
budget, not additional hours. Contingency is spent within a phase on identified
risks; any remainder does not become new features. After each phase, recalculate
the estimate-to-complete and remaining hours; do not invent work to “use up” the
12,000.

First useful releases: M1 after P2 (tabs + statuses), M2 after P4 (plugins +
minimal embedding SDK), M3 after P7 (full standalone workflow), M4 after P10
(standalone candidate), M5 after P11 (optional gateway suite). This is cumulative
effort, not a calendar: independent phase packages may overlap. With 4 people at
30 productive h/week, the arithmetic minimum is 100 weeks; dependencies, review,
and availability suggest 26–36 months. This is a planning assumption, not a
promise that LLMs will accelerate delivery.

## 4. Work packages and exit criteria

The `P*.A/B/C` packages below are budget work packages, not huge coding assignments.
Before execution, the coordinator splits a package into 8–24 h cards, each with one
observable result, exact allowed files, normal/error/cancel cases, and dependency
IDs. Budgeted child cards add up to the parent package; existing work is not
charged again. New cards get free IDs and metadata in `docs/tasks/backlog.json`,
with the corresponding markdown/index updated according to the active validator.

### P-LIN — Session lineage: the call tree as a valid Scala program, 1,100 h

Ahead of the entire plan by owner decision of 2026-10-10 (ADR-027; all decisions
are there). Cards: [docs/tasks/lineage.md](tasks/lineage.md); metadata is in
backlog.json (milestone `LIN`). The current card campaign continues in the
background — it closes RAI-020/022 dependencies.

- P-LIN.01 **140 h**: event-sourced SessionGraph model: ModelCall / ToolCall(kind,
  RecoveryClass) / Delegation / Phase nodes; events → graph reducer; fuzz tests
  on real events.jsonl. Extensible for future tool calls.
- P-LIN.02 **160 h**: graph → valid Scala projection (Program/Task compositions,
  RAI-017 as compilation target); data is lineage-data.json, not code.
- P-LIN.03 **120 h**: live incremental support: graph and projection grow during
  the session; periodic incremental compile-validation.
- P-LIN.04 **180 h**: Lineage tab in TUI (minimal tab host, pulled forward from
  P2 in a limited form: one tab, no drafts): tree + source + validity status;
  available during the session.
- P-LIN.05 **120 h**: replay-recorded interpreter (default) + comparator
  “replayed graph == recorded graph” (success = same call graph).
- P-LIN.06 **140 h**: live re-run (opt-in): isolated workspace copy for Mutating
  (RecoveryClass policy), budget, “live vs recorded” diff report.
- P-LIN.07 **80 h**: partial replay of named phases/subtrees.
- P-LIN.08 **60 h**: surfaces: chat + headless + overnight campaigns (tree
  catalog); verify pinned environment, warnings, divergence reports.
- Contingency **80 h**.

Exit: for each session, lineage.scala compiles and replays with an identical graph;
the TUI tab displays the live tree; an isolated live re-run produces a diff report;
a CI gate validates projection compilation. Hybrid mode (LLM from the recording
+ recomputation of deterministic tool calls) is the primary mode.

### P0 — trustworthy starting point, 400 h

- P0.A **120 h**: reconcile source/board/gates; W01–W18 matrix “implemented /
  proven / remaining”; check current send/watch/cancel issues. Add SDK inventory:
  public/importable API vs REPL internals, dependencies/classpath, three launch
  surfaces, and external-consumer gaps (RAI-115). Hold up to five conversations
  about a recurring task, current solution, and cost of manual oversight; there
  are no participants/dates yet, and lack of interviews is recorded explicitly.
- P0.B **160 h**: agree on runtime→UI event seam, snapshot+subscribe, ownership/UI
  focus, progress contract; ADR with independent review, preserve ADR-016. For the
  embedding API, a separate ADR card after RAI-115: lifecycle in host ZIO scope,
  configuration/tools/policy/budget ownership, typed failures/cancellation,
  versioning, and compatibility with existing REPL/CLI consumers. First prove
  what the current API lacks; no additional module/layer is prescribed in advance.
  RAI-116 first fixes the workflow-authoring/local/CI boundary, including only the
  necessary host launch. This is a separate ADR from the already accepted UI event
  seam; P1/P2 do not wait for SDK, and SDK does not wait for plugin UI. RAI-117
  records SDLC effects and checkpoint/forge/release contract; decisions are not
  represented as implementation.
- P0.C **120 h**: JLine inline renderer spike in PTY, establish baseline,
  reproducible UX measurements, and first child cards; describe separate verifier
  cards.

Exit: one reproducible baseline, actual exits/logs, frozen interfaces, lane
isolation, first 3 independent packets. Defects in the protected registry are
handled by a separate card with independent review; the roadmap does not change
gate policy.

Required audit cases: current Ctrl+C bindings and foreground/observer/edit
semantics; glyph width for Cyrillic/CJK/emoji/combining characters (one `Char`
per cell is not enough); provider/model inheritance for delegated children;
setup without implicit localhost probing and without credentials in argv (env/helper
references only).

### P1 — statuses, 900 h

- P1.A **320 h**: envelopes with root/session/job/event IDs and per-job sequence;
  reducer/snapshot plus subscribe without a loss window; schema compatibility.
- P1.B **330 h**: admission/model/tool/retry/compaction/budget events; streamed,
  bounded process tail; redact and escape terminal control characters.
- P1.C **250 h**: status line/timeline and headless event export; burst/slow-consumer/
  ordering/cancel tests, reconnect, and terminal-state consistency.

Exit: UI distinguishes queued, model, tool, waiting for approval/child/slot, retry,
cancel requested, and terminal status; UX03/08. The ledger is the source of state;
accounting is not calculated from lost UI stream events. Diagnostics may be
coalesced/dropped with a loss counter; terminal/approval/accounting need a separate
reliable path. A slow viewer does not block cancellation. No unbounded queue just
to “avoid losing” events.

### P2 — tabs, 1,400 h

- P2.A **450 h**: SessionRegistry over existing jobs, identity/focus,
  per-tab draft/history/scrollback, bounded buffers, and unread badges.
- P2.B **500 h**: inline JLine shell, palette/help, new/switch/rename/close,
  separate parent/child navigation; input router keyed by immutable session ID.
- P2.C **450 h**: connect events/runtime and run real PTY testing:
  Unicode/paste/multiline/resize/focus during streaming; headless regression.

Exit M1: user launches a task, sees 5 independently working agents, switches
between them, and returns to the unchanged draft. Closing a tab ≠ cancelling work:
a running tab offers hide or cancel; closing root handles children. UI focus does
not change owner/budget/policy. Plain chat/REPL/run remain available.

### P3 — subagent control, 1,000 h

- P3.A **350 h**: bounded mailbox for `send`, message ID/ack, and apply at turn
  boundary; editing the task does not replace a tool call already in progress.
- P3.B **350 h**: out-of-band subtree cancel, deadlines, maxLLM=1 regression,
  fair admission, live watch; distinguish observer interrupt from owner cancellation.
- P3.C **300 h**: delegate templates, lineage, result handoff, and review diff;
  separate write workspaces for product agents, explicit integration owner.

Exit: scout→coder→reviewer workflow; clarification reaches the intended child
exactly by message ID, not a neighboring tab; mailbox overflow is a typed refusal;
parent/child cancel UX06 and budget tests. Do not introduce a second scheduler. If
work is already terminal, `send` is explicitly rejected or a new turn is offered;
no promises of mutation replay.

### P4 — convenient plugins and embeddable SDK, 1,400 h

- P4.A **450 h**: narrow versioned Scala SPI (tools/profiles/hooks), manifest,
  compatibility/duplicate-name preflight, controlled classloader/resource lifecycle.
- P4.B **500 h**: UI plugin manager and CLI install/list/check/enable/disable/update/
  remove/rollback, pinned versions/checksums, user-vs-workspace config precedence.
- P4.C **450 h**: SDK/template, docs, three local fixture plugins, API-version
  compatibility, crash/cleanup/failed-install/rollback acceptance. The first SDK
  slice is Scala workflow authoring and the local→compiled bundle→generic headless
  CI path (WF-01/02, §6b), before a full host consumer. Reuse Program/Task/andThen/
  all/batch; new SDLC helpers need a domain contract, not a parser/macros or separate
  graph runtime. A separate slice is the minimal SDK for a host Scala/ZIO app under
  reviewed RAI-116 ADR (separate SDK contract inside P0.B, independent of the UI
  seam): existing core/runtime/dsl/providers, explicit tools/policy/limits, without
  mandatory REPL/compiler/JLine/CLI or production testkit. The consumer builds
  outside the root aggregate from artifacts in an isolated local repository,
  without source links or classpath from the checkout. Publishing to a public
  registry is not needed for this check. A short example + run command ship with
  the code; detailed guides remain P10.C.

Exit M2: select a local JAR in the UI → see source/version/capabilities → confirm
trust → use the tool; Raider is not rebuilt, UX05. Additionally SDK-01/02 (§6a):
one external Scala/ZIO consumer runs a typed workflow and correctly closes owned
resources. Accepted plugin slices remain accepted; an unfinished SDK slice is
tracked separately and is not presented as completed M2. For remote sources,
bound downloads/checksums, no shell install script. A workspace does not activate
code automatically; plugin config cannot expand policy/root budget. Hooks cannot
allow what core policy forbids. Updating an active plugin applies to new sessions
after drain/restart, without promising safe hot-unload of JVM code. Plugin
version/configuration is pinned to the run.

A classloader is a compatibility boundary, **not a security sandbox**: a Scala
plugin has process privileges; `System.exit`/native crashes cannot be guaranteed
to be caught. Catchable defects become typed failures; a hang requires bounded
cancellation and a truthful message. Process isolation for untrusted code is a
separate project outside this budget. A public marketplace, billing, and SDKs in
other languages are not needed for a convenient v1.

### P5 — local state, 900 h

- P5.A **300 h**: default-off state SPI, Memory/File backends, Agent/Root scopes,
  explicit Global, quotas, revision/CAS for concurrent writes, atomic replace.
- P5.B **300 h**: opt-in state_get/put tools, provenance/inspect/reset/export,
  shared blackboard for parent/children; key and value limits.
- P5.C **300 h**: lost-update/corruption/scope-isolation tests, migration, and SDK
  state backend seam; two agents coordinate locally.

Exit: disabled creates no store/tools/background work; enabled survives restart
under the declared durability model. Persisted state is untrusted data, not
instructions/credentials. Sizes/number of keys are bounded. No remote/Yabanin.
Atomic rename is not claimed to protect against power loss without a separate
fsync contract.

### P6 — recovery and control, 1,100 h

- P6.A **400 h**: versioned session/journal/checkpoint, tabs/history/plugin pins,
  crash recovery and migrations; restore history separately from resuming execution.
- P6.B **350 h**: bounded context/compaction, root/tab budgets and unknown usage,
  reservations/refunds, snapshot reconciliation with state revisions.
- P6.C **350 h**: SIGTERM/kill -9/corrupt tail/disk-full cases; owned child cleanup,
  stale leases/statuses, and observable partial failure.

Exit: after a crash, a tab shows interrupted/unknown, not “running” without a live
process. Reading history does not restart mutations; a new attempt requires
postcondition checks/user decision. No exactly-once/durable resume of arbitrary
Scala lambda. UI and CI show the same root accounting.

### P7 — complete standalone workflow, 1,000 h

- P7.A **350 h**: remaining tools/policy/project context; real read/edit/process
  and negative cases, protected files, deletion/partial patch errors.
- P7.B **350 h**: REPL compiler/completion and headless adapters over one runtime;
  non-TTY/SIGTERM/exits/artifacts/JUnit/trust-boundary acceptance. One `Program`
  definition file is used by the host ZIO app, REPL, and compiled CI bundle;
  adapters handle launch/input/output, while semantics are shared.
- P7.C **300 h**: bugfix/refactor/new-file/review corpus, both direct wires via
  Scala HTTP stand, installed-package golden scenarios, UX01 rehearsal. SDK-03/04:
  external consumer with a non-coding typed workflow (e.g. extracting information
  and summarizing it), and CI scenario read → edit → failing check → fix → passing
  check with diff/exit/artifacts. Scripted success proves the mechanism; model
  quality and user value need separate evidence. Offer three external users their
  own tasks; measure time to first result, our assistance, diff usefulness, and
  repeat use, not just successful startup. The main application workflow is
  TokenRouter SDLC (§6c): local/generic CI, configurable checkpoints, verified
  stage outputs, then automatic commit/PR/merge and local release artifacts. This
  is a new contract area under RAI-117; estimate its added scope and verifier work
  separately before implementation. Generic bugfix corpus W02/W16 remains; CI
  adapters do not create a second scheduler.

Exit M3: map W01–W16 + WF-01/02 + SDLC-01–04 to one candidate with actual case
evidence and no gateway dependency. SDLC-03/04 here verify the generic contract
on a local stand/disposable Git; real forge PR/merge remains pending. SDLC-05 is
added to M4. Reuse accepted slices and close gaps only. Live verification and
OpenCode comparison have separate budget/status and are not inferred from offline
tests. Do not hide unfinished W obligations behind a count of green unit tests.

### P8 — scale for daily work, 900 h

- P8.A **300 h**: multi-workspace tabs/worktrees, resource limits/fairness,
  conflict warnings, and sequential integration.
- P8.B **300 h**: benchmark event bursts, bounded history/memory, reconnect;
  10k events/s stress on a pinned machine, not represented as ordinary load.
- P8.C **300 h**: 8-hour offline soak, slow terminal/process/plugin, collect leak/
  latency metrics, fix existing components.

Exit: caps are actually enforced; no orphan children, observer leaks, or lost
terminal statuses. Thresholds/baseline are set before the run. Remote executor
and distributed scheduler are not part of this phase.

### P9 — ease of use, 900 h

- P9.A **300 h**: fullscreen renderer over the same controller if M1 feedback
  confirms value; otherwise improve inline against the same UX cases.
- P9.B **300 h**: accessible status labels, keyboard remap/fallback, plain mode,
  Russian/English help, search history/actions, plugin onboarding.
- P9.C **300 h**: usability sessions, terminal matrix, and prioritized UX fixes;
  first use, provider error, failed plugin, recovery. Include SDK/CI pilot results
  from P7.C: if at least two of three participants reuse it on a new task, consider
  expanding the SDK; otherwise first investigate causes and narrow/fix the scenario.
  This is a product decision criterion, not market proof or a new engineering gate;
  with no participants, status is `pending`, and expansion is not decided automatically.

Exit: UX01–08 confirmed by applicable PTY/human evidence; renderer choice does not
delay M1. Test key combinations on macOS/Linux, tmux, and declared terminal
emulators; unsupported terminals use a clear plain fallback.

### P10 — standalone release candidate, 1,000 h

- P10.A **350 h**: clean install/upgrade/uninstall, reproducible packaging,
  SBOM/notices and existing license policy, portable examples. For SDK: artifact
  coordinates/version, dependency inventory, supported Scala/JDK/ZIO based on
  pinned toolchain, and clean consumer build (SDK-05); no cross-build to additional
  versions is implied. Public publishing is a separate release action.
- P10.B **350 h**: independent acceptance of all standalone claims on an immutable
  candidate, security/compatibility regressions, and discovered defects. Include
  SDK-01–05 on the same product inputs; do not substitute old green lane evidence
  for fresh evidence.
- P10.C **300 h**: docs/troubleshooting/migration, onboarding evidence, release
  rehearsal, and optional capped direct live/comparison if authorized.

Exit M4: `standalone_engineering_ready` only when W01–W16 + UX/plugin/state
advertised cases + SDK-01–05 + WF-01/02 + SDLC-01–05 are complete; archive
source/toolchain/policy/artifact hashes. SDLC engineering acceptance runs on
disposable repos/local stand; real forge claims get separate evidence for the
selected adapter, without which they remain pending. This is not the old general
`engineering_ready` for W01–W17. W17 and full W18 remain pending until P11.
Creating a release candidate does not imply publishing it.

### P11 — Yabanin, final phase, 1,100 h

**Hard dependency: M4 accepted.** Before that, do not start Yabanin gateway client
modules, discovery of its gateway/session/accounting integration, ADR-023
implementation, labels/session keys, remote state, or accounting sync. Read-only
TokenRouter discovery as an SDLC project is allowed now; separately authorized
target cards are executed under its rules and do not implement Raider gateway
integration. Existing historical materials are preserved; direct custom base URL
and generic plugin/state seams are not integration.

- P11.A **250 h**: refresh read-only discovery of actual deployment/contracts,
  integration ADR, offline schemas/stand, and capabilities; do not trust old OpenAPI.
- P11.B **500 h**: optional transport profile, attribution, key lifecycle with
  one owner, bounded accounting reconciliation, and eval export against proven schemas.
- P11.C **350 h**: offline deny/timeout/drift fixtures, full integrated candidate,
  capped live only with endpoint/mode/budget, docs, and adapter disablement.

Exit M5: W17 and W18 on the final candidate; both direct configurations remain
ready without Yabanin. Auth/policy denial never triggers hidden direct fallback.
Usage has pending/partial/complete states; timeout does not become complete. If
there is no native remote state API, do not invent a KV endpoint: File remains the
primary store, and attribution/export are described accordingly. A real remote
store gets a contract/card only if support and budget remainder are demonstrated.
An externally unavailable deployment leaves live gateway pending; offline
acceptance is separate. Yabanin changes are separate YB cards and separately
authorized work; their hours are not hidden in the 12,000 Raider hours; no direct
access to its DB/Redis.

## 5. Parallel execution graph

Milestone order does not prohibit preparation of independent components. A
dependency means an accepted contract/component with evidence, not merely a phase
that has started.

| Package | Implementation may start after | What can proceed in parallel |
| --- | --- | --- |
| P0.A/B/C | Inspection; B/C use facts from A, review before freeze | Inventory, pure renderer spike, acceptance design |
| P1.A | P0.B | P2.A/B on frozen interfaces and scripted source |
| P1.B | P1.A | UI components; P1.C is needed after integration |
| P1.C | P1.A + P1.B; UI part after P0.C | Independent ordering/backpressure fixtures |
| P2.A/B | P0.B + P0.C | P1 event implementation; scripted source before P2.C |
| P2.C | P1.C + P2.A/B | P4.A with a separate plugin SPI owner |
| P3.A/B | P0.B + P1.A | P2 frontend; P3.C waits for P2.C and A/B |
| P4.A/B/C | P0 baseline; B after A; SDK/tests C after A, final C acceptance after B | P1/P2/P3 in separate files; M2 waits for M1 and plugin UI integration |
| P4.C embedding slice | RAI-115 audit → accepted RAI-116 SDK ADR inside P0.B → frozen SDK boundary; UI event ADR is not a dependency | Consumer acceptance design parallel with current wave; shared build/API have one owner; M2 waits for plugin and embedding acceptance |
| Workflow preview (P4.C/P7.B) | RAI-115 → RAI-116 ADR → WF-01/02 | Current P-LIN/P1/P2/P3; preview does not wait for M1/plugin UI/M2, host SDK-01/02, or M4 |
| SDLC kit (P7/P10) | RAI-117 contract + separate estimate; implementation after RAI-116 boundary | Pure stage fixtures/local stand parallel with runtime implementation; external mutations only after effect-contract acceptance |
| P5 | P1.C + P4.A; P5.C plugin seam after P4.C | P3/P4 in separate modules; core store does not depend on remote backend |
| P6 | P2.C + P3 + P5 | P7 tools/REPL/CI gaps, then shared recovery acceptance |
| P7 | P0 gap matrix for independent fixes | Final M3 after M2 + P3 + P5 + P6 |
| P8 | P3 + P6 | P7 corpus and P9 UX research |
| P9 | M2 for UX work, P8 for final load matrix | P10 packaging preparation |
| P10 | M3 + P8 + P9 for final acceptance | Docs and packaging in advance, full candidate sequentially |
| P11 | M4 only | A first; B against frozen ADR; C after B; no early integration lane |

Typical waves:

| Wave | Lane A | Lane B | Lane C / review |
| --- | --- | --- | --- |
| W0 | Actual inventory / defects | Renderer spike without runtime edits | Acceptance/ADR review |
| W1 | Events/reducer | Tab UI on scripted events | Plugin SPI design/review |
| W2 | Runtime emission/progress | Session registry + UI binding | Plugin loader |
| W3 | Steering/cancellation | Plugin manager/SDK | Integration review/PTY |
| W4 | Local state / recovery | Tools/REPL/CI gaps | Corpus/independent review |
| W5 | Multi-workspace/soak | UX/onboarding | Packaging/compatibility review |
| W6 | Candidate defects | Release acceptance | Docs/usability verification |
| W7, after M4 | Yabanin adapter | Offline fixtures against frozen contract | Independent review; live if authorized |

Maximum: coordinator + 8–10 active spawned workers (owner decision of 2026-10-12,
superseding the “up to three” limit; reviewer occupies a slot; mechanics in
parallel-development.md §1a/§1b). Do not keep all writer slots occupied at the
cost of having no review. The root checkout, shared contracts/build/gate
registry/board belong to the coordinator or one explicitly assigned owner.
Development of protected verifiers is a separate card with fresh review; an
implementation worker cannot rewrite its test's expected result.

Each writer gets a separate worktree or copy of an immutable snapshot with
absolute cwd, allowed files, and base hash; no shared `target/`, mutable hardlinks,
or accidental return to root. If a tool cannot set child cwd, use a verified
isolation mechanism; do not invent OpenCode parameters. Without isolation, writers
work sequentially while read-only review can remain parallel.

Integration: base/path/hash check → fresh diff review → sequential application
→ compile/contracts + affected gates → runnable demo for the wave. A patch that
conflicts with the new root is rebased and checked again. A green lane does not
replace a green integrated candidate. Do not add a CI/runtime scheduler for
development orchestration.

## 6. Acceptance order and compatibility with the old backlog

| New scope | Existing obligations to reuse |
| --- | --- |
| P0/P1 | RAI-002/004/006/009/010; schema/runtime/budget evidence |
| P2/P3 | RAI-007/008/017–021; W08–W11; send/watch remain obligations |
| P4/P5 | RAI-028/029/031 and new scoped state cards; W13 |
| P0/P4.C embedding | RAI-115 audit; RAI-002/007–010/017/028; RAI-096 remains plugin SDK, new implementation cards after ADR |
| P6 | RAI-027/030/036; W08/W14 |
| P7/P10 | RAI-014–016/022–026/041–047; W01–W16 |
| P11 | RAI-037–039; W17/W18; YB companion work separately |

This maps scope; it does not automatically close old cards. The coordinator
checks exact acceptance and does not extend an “accepted slice” to the whole
parent. The old F4 gateway workstream and MASTER-10K-STATE discovery yield to this
order. W17 is deferred, not removed; full `engineering_ready` is allowed only
after W01–W17 and joint W18. Track `live_verified.direct-chat`,
`direct-anthropic`, `gateway`, and `opencode_comparison` separately.

After each card: first `make quality-changed`, then
`make quality-changed QUALITY_EXECUTE=1`, with a real baseline manifest for
snapshot lanes. Also run actually applicable profiles; a name in the spec does
not mean a runner exists. The current registry is in
`scripts/quality-policy/registry.json`; new UI/plugin/performance/security/release
cases need separate reviewed verifier cards. Do not invent `make acceptance-working`
as an existing gate. A documentation validator does not prove runtime, and
current fast is not the full W acceptance.

Risk-based checks: runtime — cancellation/ownership/budget; TUI/REPL — real
compiler/PTY; CI — nonTTY/signals/exits/artifacts/trust; plugins — compatibility,
failure/cleanup/install integrity; recovery — crash/no mutation replay; providers
— real local Scala HTTP stand. Mock/replay by default. Paid product calls only
with an explicit mode and cap. Gate failure/missing tool/timeout is not passed.

Create a work record after execution. Evidence is tied to source/policy/toolchain
snapshot and actual commands/exits/log hashes. Do not consider the current runner
fully spec-compliant solely because its self-check is green; P0 checks this gap
too. Input changes after tests require affected reruns; operational exclusions
only through an exact reviewed allowlist. Neither the board nor the number of
spawned workers proves readiness.

## 6a. Embeddable SDK acceptance and value validation

These are new case obligations within existing phases, not names of ready-made
gates. After RAI-115, the coordinator registers an ADR and 8–24 h child cards with
exact paths, implementation/integration dependencies, and independent review.
Protected verifiers and registry changes are separate cards; existing checks are
not weakened.

| Case | Phase | Observable result |
| --- | --- | --- |
| SDK-01 | P4.C | A clean external Scala/ZIO consumer obtains versioned JARs, compiles a typed Program, and runs the native loop; runtime classpath contains no repl/compiler/JLine/CLI/testkit; scripted testkit is allowed only in test scope |
| SDK-02 | P4.C | Host scope owns root/children/resources; normal/failure/cancel and maxLLM=1 are checked; two host instances share no hidden global state; construction is lazy; budget/policy denial is typed; provider/tool config is explicit |
| SDK-03 | P7.B/C | One workflow definition works from host app, real REPL, and compiled CLI bundle: same normalized result/error, policy/budget, and cancellation; both direct wires pass local Scala HTTP stands; non-coding typed workflow without required coding prompt/tools. Bundle v1 is String→String: typed internals do not prove a typed wire; codec/schema change only through ADR |
| SDK-04 | P7.C | On an external fixture repo, CI performs read/edit/failing-check/fix/passing-check and emits diff, checks, and exit/artifacts; non-TTY/SIGTERM/trust checked; scripted and live evidence separated, live only with explicit mode/cap |
| SDK-05 | P10.A/B | Artifacts and dependency inventory are obtained from immutable candidate; clean consumer install/compile/run, supported versions, and compatibility policy checked by independent reviewer; public publishing is not implied |

Types check composition, not the truth of an LLM response: structured outputs need
runtime validation and bounded repair under RAI-028. The API must not force the
host into a blocking REPL facade or a second scheduler. New distributed budget
authority, durable workflow engine, RAG/vector-store catalog, and SDKs in other
languages are out of scope.

P-LIN remains a mechanism to record, explain, and replay supported operations.
Scala-projection compilation, graph equality, and workflow execution are separate
claims with separate evidence. Converting an arbitrary session into an editable
program and durable resume of arbitrary Scala callbacks are not SDK properties;
unfinished P-LIN acceptance remains in place and is not replaced by this refinement.

RAI-115 is a source audit (P0.A); after independent review, the next card **RAI-116**
sets the workflow authoring/launch contract, then **RAI-117** sets SDLC
effects/acceptance packet. Check ready/accepted against the board. Implementation
starts with the local/CI workflow path; the host consumer is additional, separate
acceptance. SDK/CI pilots may start after the required slice is ready, before full
M3; their reports do not close W/UX acceptance. Participants, demand, and dates
are not yet confirmed. For OSS delivery, P10.C records public API support,
deprecation rules, issue channel, and a reproducible consumer reproducer; new
dependencies and release artifacts follow the existing license policy. Repository
stars do not replace use data and are not an engineering acceptance condition.

## 6b. Early workflow preview and dependencies

`workflow_preview_eligible` is a separate component result, not M2/M3/M4 and not
a stable SDK. Requirements: reviewed RAI-116 boundary, isolated installation of
required artifacts, WF-01/02 on one immutable candidate, declared versions/
limitations, one example command. Shared source is not copied into consumers;
one workflow is not replaced by two similar implementations. Public publishing
is separate.

| Case | Required observable result |
| --- | --- |
| WF-01 | Author's Scala source compiles with pinned compiler, runs locally through dev/REPL path and as a compiled bundle in non-TTY generic local CI; input/result/error/artifacts are comparable; String boundary v1 is explicit; lazy definition does not call model/tools |
| WF-02 | One root/ledger and per-call admission across all adapters; maxLLM=1 with child does not deadlock; external cancellation closes owned resources; invalid input/policy/provider failure/cap produce typed outcome; two runs do not share accidental global state |

The first demonstration uses a deterministic backend/local stands. Then the same
scenario can use an explicitly configured direct provider with mode/cap. Offline
proof does not prove model quality. The clean host consumer SDK-01/02 remains for
P4/M2, full SDK-03 parity for P7; these do not block feedback on a ready workflow
preview.

The first SDLC example should allow replacing a stage, adding a tool/check,
choosing a checkpoint, and limiting the repair/review loop in ordinary Scala. Do
not introduce abstractions just to make the example look elegant. Public meaning
of stages/inputs/outputs, retry, and approval is fixed by RAI-116/117 before helper
implementation.

## 6c. TokenRouter AI-native SDLC: end goal and delivery slices

Target inventory: `../tokenrouter`, HEAD `91e6ce0`, dirty working inputs hashed
separately in research. This plan has not performed any worktree mutation or
acceptance of TokenRouter. Its commands, policy, and fingerprints are reread for
every assignment; any target edits are separate cards in that repository.

| Slice / case | Phase | Result and negative acceptance |
| --- | --- | --- |
| SDLC-01: task→plan→implement→check→review | P7.A/C | Stage inputs/outputs and review verdict are structured; checks run via trusted runner on pinned candidate; repair/rework bounded; refusal/error/attempts exhausted do not succeed; changed checks cannot authorize their own result |
| SDLC-02: configurable human checkpoint | P7.B | Workflow chooses auto/approval/deny within owner policy. In headless mode NeedsApproval ends invocation with artifact/exit; a new run rechecks policy/task/repo/base/candidate and decision expiry. Stale/rejected decision does not expand rights; no claim of durable fiber resume |
| SDLC-03: commit/PR contract + local stand | P7.B/C | Commit is tied to exact tree/checks/review and created on isolated branch. PR lifecycle checked by Scala stand without selected forge; timeout/duplicate/conflict yield truthful outcome and read-only reconciliation before retry. Real PR pending selection and acceptance of adapter |
| SDLC-04: automatic integration | P7.B/C | One integration owner, expected source/target revisions, required repo checks/review observed; stale refs/conflict/ambiguous merge are not success. After candidate changes, rerun affected checks; stand/disposable Git prove protocol, not real branch protection |
| SDLC-05: local release preparation | P7.C/P10.B | Version/changelog from accepted revision; local-only tag; build into a separate candidate-specific directory; checksum/build metadata linked to revision. Existing tag/version, failed build/cancel have explicit outcome; no tag push, package/image publish, or deploy |

The full cycle is the confirmed end goal. Slices are accepted in sequence:
workflow preview → SDLC-01/02 → SDLC-03/04 on local stand → SDLC-05; the real
forge adapter is selected later. A local stand does not replace real PR/merge, and
an incomplete preview is not called a ready full SDLC.

TokenRouter already has `make release-artifacts`; it produces binaries/checksums/
SBOM or build manifests, but not changelog/tag. Pushing `v*` in its current release
workflow publishes images and deploys staging; the owner chose a **local tag with
no push**. CI engine and forge are not yet selected; its existing GitHub origin
does not mean GitHub Actions or the first forge adapter has been selected. Generic
local invocation, versioned artifacts, and local Scala stand suffice for initial
fixtures.

Changes to old non-goals in `ci-runtime.md` §6 and `runtime-contracts.md` §11,
approval schema, and SCM/release capabilities require RAI-117 ADR and independent
review. Before RAI-117 is accepted, the coordinator registers exact mapping of
SDLC case → phase/M3/M4 → implementation card ID and separate verifier card ID
(or an already accepted verifier with evidence), owners, allowed paths,
dependencies, estimate, and hour source. The RAI-117 worker prepares the packet
within its allowed paths; the coordinator owns the card registry. Implementation
does not start before contract review, scope estimate, and verifier packet
registration. Gate policy/protected fixtures change only in a dedicated verifier
card with independent review. Human checkpoints narrow trusted capabilities;
their Scala declaration does not grant extra rights. Trust policy is not a
sandbox for Scala or repository build commands.

The SDLC kit/SCM/release scope has been added to the early SDK assumption and is
not fully estimated yet. Before implementation, record a separate estimate and
hour source in P7/P10; reuse approved slices, do not remove old obligations.
13,100 h is a historical envelope, not confirmation that the new scope fits it.

## 7. Scope control

Not included in this budget: web/cloud console, hosted marketplace, remote
distributed execution, a new DSL parser/macros, second runtime, other product
languages, or reworking Yabanin. These parts of the previous roadmap yield to the
usability of the local product. All product modules/tests/HTTP stands use Scala 3
+ ZIO; follow the existing license policy; add no dependencies without a specific
card and review.

Main risks and actions: event loss → snapshot/sequence tests; JLine input conflict
→ early PTY spike; plugin ABI/dependencies → narrow SPI/compatibility matrix;
state corruption → versioned fixtures/CAS; parallel merge churn → freeze and
ownership; stale board → actual inventory; external Yabanin blocker → standalone
product is still accepted first.

Once per wave, the coordinator publishes: accepted results, active lane/card/path,
blocker and cause, used/reserved/remaining estimate, and next runnable increment.
A package budget variance >20% triggers re-planning the remainder and an explicit
tradeoff description, not a hidden relaxation of acceptance. While independent
ready work exists, an external blocker is no reason to stop all development.

Sources for executor integration: official [OpenCode agents](https://opencode.ai/docs/agents/)
and [commands](https://opencode.ai/docs/commands/), checked 2026-10-05. Primary
agent switching and child-session navigation differ; neither guarantees filesystem
isolation. Check actual tools/version in the next session; Raider's interface is
designed separately from OpenCode's interface.
