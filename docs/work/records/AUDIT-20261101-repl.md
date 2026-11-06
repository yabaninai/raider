# AUDIT-20261101 REPL and interactive-workflow lane

Status: partial (read-only code and documentation audit; no checks were executed). Date: 2026-11-01. Inspected commit: `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`; product baseline: `04f74780891e0e7032bf98b3d1dccac279f32354`.

## Finding summary

The product contains a real Scala 3.9.0 compiler-backed REPL, a separate JLine chat/tab shell, live OpenAI-compatible and Anthropic-compatible backend construction, tool and plan approval paths, and a chat-to-editable-workflow export path. These are real implementations, not just the historical spikes. The checked-in acceptance records describe extensive process/PTY and local-stand evidence, but this audit did not rerun them and they are not evidence of a real-provider coding session or the inspected candidate's current green gates.

Readiness remains incomplete. The installed headless JAR deliberately excludes the Scala compiler and JLine; chat and Scala evaluation use different input loops; one published DSL golden scenario calls a nonexistent `Chat.close()`; and the portable chat export works only when the selected named checks exactly match the exporter's pinned trusted-check registry. W10/W11/W15 and user readiness should not be reported complete from this lane.

## Prioritized findings

### P1 — No installed REPL/TUI path

The source checkout can launch `raider.repl.Main`, but the user guide explicitly says the installed headless JAR has no Scala compiler or JLine and cannot start the REPL/TUI (`docs/user-guide.md:3-8`). The onboarding guide currently requires building an example classpath and launching the main class directly (`docs/onboarding.md:101-136`). This leaves the user-facing installable REPL portion of W01/W10 unmet, even though the compiler-backed source-checkout REPL exists. Package/release evidence is owned by broader readiness, not this lane; this is a product-surface blocker rather than a request to bundle the compiler into the headless runner.

### P1 — The chat shell and Scala REPL are distinct modes

`Main.main` selects exactly one of `--tabs`, `--chat`, or the legacy Scala loop (`modules/repl/src/main/scala/raider/repl/Main.scala:415-432`). The banner explicitly says Scala evaluation runs without `--chat` and without `--tabs` (`Main.scala:443-462`). The interactive chat adapter treats ordinary input as a model prompt (`Main.scala:643-735`); tabs treat entered text as drafts. User-facing guidance confirms that tab text is not evaluated as Scala (`docs/user-guide.md:10-17`, `docs/onboarding.md:109-145`). A user can use the distinct Scala REPL, chat TUI, and headless workflow export, but cannot currently move between Scala evaluation and chat inside one active shell. The plans should describe these as separate surfaces and keep the integrated experience pending unless the owner accepts that separation for W10/W15.

### P1 — A required DSL golden scenario does not compile against the current API

The W11 golden example in `docs/working-product.md:91-94` calls `chat.close()`, and `docs/product-repl.md:282-290` also presents a closeable chat session. The actual `Chat` class exposes `ask` and `messages` only (`modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala:604-633`); repository search found no `Chat.close` method or extension. The practical REPL guide accurately says there is no `close()` method (`docs/user-guide-repl.md:107-118`). W11 requires all golden scenarios to compile and run (`docs/working-product.md:34-35`), so this scenario is currently a concrete contract/code mismatch. Next action: either add the session lifecycle behavior under a separate implementation card with ownership tests, or amend the golden scenario and record the remaining lifecycle gap explicitly. Do not claim W11 complete until the scenario is resolved and compiled.

### P2 — Portable export is implemented, but chat selections have a narrow representable check set

The real CLI command consumes an explicitly validated selection snapshot, externalized `targetPath` and `summary`, then dispatches to the Scala or portable exporter (`modules/cli/src/main/scala/raider/cli/chat/ExportWorkflowCommand.scala:21-52,143-245,266-280`). ADR-038 maps a named check only if ID, frozen definition digest, and non-advisory semantics exactly match a launcher-pinned trusted check; mismatches refuse with `CWF-R3` and preserve Scala export as the alternative (`docs/adr/ADR-038-chat-check-to-trusted-check-export-mapping.md:31-47,140-151`). The CLI currently supplies a single pinned `workspace-marker-present` check (`ExportWorkflowCommand.scala:266-280`; see also `docs/work/records/RAI-215.md:7-13`). Thus portable export is not a general conversion for arbitrary named checks. Expand pinned mappings only through reviewed contract/card work, or preserve the explicit refusal and user guidance.

### P2 — Current provider and coding-quality evidence is offline/scripted

Live OpenAI-compatible and Anthropic Messages constructors exist in the REPL prelude (`modules/repl/src/main/scala/raider/repl/Main.scala:267-324`); the ordinary chat input starts the same runtime facade with coding tools and the system prompt (`Main.scala:729-736`; `modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala:115-121`). The chat layout has token-bound `:approve`/`:deny` tool approval and exact hunk review/apply through `PatchTool` (`ChatLayoutSink.scala:208-266`; `DiffReviewCommands.scala:89-136,172-207`). Plan approval is a separate typed exactly-once `PlanSession` edge (`PlanPanel.scala:95-118`). These are real wiring. The cited PTY evidence is deterministic: e.g. `ToolApprovalChatPtySpec` launches the real product process under PTY against a loopback scripted OpenAI-compatible stand (`modules/repl/src/test/scala/raider/repl/ToolApprovalChatPtySpec.scala:14-18,42-67,96-120`), and records explicitly say mock/scripted streams do not establish live-provider behavior (`docs/work/records/RAI-188.md:7-13,34-40`). No live model completion, real paid call, or OpenCode comparison was run here. Keep live profile statuses pending until separately authorized scoped runs exist.

### P2 — REPL success is supported by historical records, not a new candidate run

`DottyReplEngine` wraps the actual `dotty.tools.repl.ReplDriver` and retains returned compiler state across submissions, including retaining previous state on compile failure (`modules/repl-engine/src/main/scala/raider/repl/engine/DottyReplEngine.scala:10-29,49-60`). It asks the compiler for completions (`:62-94`). Prior records report real product-process PTY coverage for compiler-backed behavior and streaming/draft rendering (e.g. `docs/work/records/RAI-188.md:34-53`, `docs/work/records/RAI-130.md:67-69`). Those records are historical and bound to their recorded source/gate snapshots. No current REPL or broader W10 gate was run for this audit; W10's full acceptance also includes behavior beyond the inspected excerpts, including compiler/PTY cases in `docs/quality-gates.md:181-205`.

## Surface assessment

- **Real Scala REPL:** Present in source checkout. `DottyReplEngine` uses the Scala compiler, and `Main` has JLine and pipe input loops. The default no-flag mode evaluates Scala; `--chat` and `--tabs` are distinct product shells.
- **Useful chat/TUI workflow:** Real TabShell/JLine wiring, a one-writer render bridge, draft/scrollback/palette features, model switching, cancellation, tool call cards, tool approvals, and plan/task view wiring are present. User drafts are not Scala expressions. The no-flag Scala prompt supports agent DSL calls but is not the chat layout.
- **Approvals and diff review:** Mutating tool approval in chat is token-bound; plan approval is a distinct session edge; diff review parses model-produced diff fences and applies accepted hunks through the production patch policy. `DiffReviewCommands` rejects missing provenance, no accepted hunks, multi-file review, and repeated apply; this is a constrained review surface, not general Git integration.
- **Tabs/plans:** UI tabs/drafts and plan task panel are wired to the existing TabShell/runtime. The `--chat` path attaches lineage/task content; the legacy `--tabs` path is a separate shell. Historical PTY tests are deterministic fixtures and do not establish live provider quality.
- **Chat → editable Scala → portable workflow:** Real capture, explicit `:task` marking/selection, Scala export, edit, compile, and execution are described by the verifier contract and RAI-160/RAI-155 evidence. `contracts/chat-workflow/README.md:4-14,22-44,96-100` states the capture/export/compile/run surfaces and scripted stand; `docs/work/records/RAI-160.md:43-67` gives the actual command chain. Portable export has a separate exact-match pinned-check mapping with mock-run evidence in `docs/work/records/RAI-215.md:27-36`. That chain is useful and concrete, but local scripted stands are not live-model proof, and portable check mapping remains narrow.
- **W11/W15 readiness:** `working-product.md:34-35,39` is the requirement; `product-repl.md` is a target contract, not an assertion of completion. `docs/user-guide.md:10-17,33-88` now accurately distinguishes headless coding, Scala REPL, tab shell, and CI workflows. The source checkout has starter agent bindings, while the installed headless JAR does not offer the REPL/TUI. W15 can only be claimed for the specific implemented headless `coder` path after its own acceptance, not by equating source-checkout demo code with full product readiness.

## Commands and audit method

No builds, tests, paid calls, network calls, or product mutations were performed. Read-only repository inspection:

| Command | Exit | Result |
| --- | ---: | --- |
| `git status --short --branch` | 0 | Clean at start; branch `audit/20261101-repl`. |
| `git rev-parse HEAD` | 0 | `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`. |
| `git cat-file -e 04f74780891e0e7032bf98b3d1dccac279f32354^{commit}` | 0 | Assigned product baseline exists in this worktree. |
| `git merge-base HEAD 04f74780891e0e7032bf98b3d1dccac279f32354` | 0 | Baseline is an ancestor. |
| `git diff --stat 04f74780891e0e7032bf98b3d1dccac279f32354..HEAD` | 0 | Only audit-card registration differs from product baseline; inspected product sources match baseline. |
| `rg --files modules/repl modules/cli/src/main/scala/raider/cli/chat modules/cli/src/test/scala/raider/cli/chat docs` | 0 | Located implementation, tests, guides, ADRs, and records. |

Additional source/doc inspection used `nl -ba` and `rg -n` on the paths linked above. Historical gate commands and outcomes are cited only as prior records; none were rerun. The assigned `AUDIT-20261101` coordinator card prohibits builds and product edits, so no quality runner or plan validator was invoked.

## Limitations and next actions

- The audit is not a current-source verification run and offers no new gate pass, no user-run proof, and no live-provider result.
- Resolve the unsupported `chat.close()` golden scenario, and inventory which W10/W11/W15 acceptance IDs have current integrated evidence.
- Keep installed-package REPL support and combined chat/Scala mode explicitly pending unless the owner changes the required acceptance.
- Preserve ADR-038's exact-match portable export limitation in the user flow. A follow-up should widen the pinned registry only with the required contract review and acceptance update.
- Next available work is coordinator-owned integration of the eight audit lanes; this lane introduces no implementation recommendation beyond those findings.

## Review and next task

Independent review: pending (coordinator audit review). No product checks were run. This lane record applies to inspected source baseline `04f74780891e0e7032bf98b3d1dccac279f32354`; integrated readiness remains for the coordinator to determine from all lanes and current evidence.
