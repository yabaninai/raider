# Raider: contributor instructions

## Status and requirements

As of 2026-10-03, the project had specifications and feasibility spikes RAI-001/SPIKE-OAI-01, but no product runtime. Check the actual code and board on every run; this historical status is not permanent. The intended use is CI pipelines and a useful standalone coding workflow with a real Scala REPL. Required reading: `docs/working-product.md`, `docs/parallel-development.md`, `docs/PLAN.md`, the assigned task card, and applicable contracts. Working acceptance and stage-aware gates refine the older milestone sequence. Do not present planned APIs/gates or a spike as a finished product.

## Task cards

- One task, one card, one verifiable result. Before changing code, check `git status --short` and the existing implementation. In an isolated copy without `.git`, record that mode and verify the baseline inventory/current hashes according to the parallel runbook. Do not return to the root checkout just to run Git.
- Respect the card's allowed paths, dependencies, and non-goals. Public contract changes require a separate ADR and updates to consumers/examples.
- Continue authorized work without intermediate confirmation requests. Do not ask again for permission already granted.
- Language policy (2026-10-03): product code uses Scala 3 + ZIO only (`modules/**`, `contracts/**`, `examples/**`, tests, and HTTP stands). Python is limited to `scripts/plan`, `scripts/quality`, bootstrap glue, and frozen `experiments/**`; it is not shipped. Provider tests use a Scala stand or an explicitly configured local OpenAI-compatible stand.
- Documentation language policy (owner decision, 2026-10-13): all repository documentation must be in English, including guides, plans, task prose, ADRs, prompts, contributor instructions, and work records. Keep identifiers, file paths, API names, and immutable evidence values intact. New documentation and documentation updates must follow this rule.
- Do not add adjacent features, dependencies, daemons, DSL macros, or abstraction layers on your own initiative.
- The owner explicitly authorized parallel development. On 2026-10-12, aggressive parallelism was authorized: at least 8 lanes through Git worktrees (one branch per lane, commits to that branch, merge integration by the coordinator), according to `docs/parallel-development.md` §1a. Snapshot mode is a fallback. The root checkout, shared contracts, build, gate registry, and board each have a single designated owner. A child session is not an isolated checkout; concurrent mutable builds in one checkout are forbidden.
- Execution mode (owner decision, 2026-10-12, `docs/parallel-development.md` §1b): cards run through **visible spawned subagents** in the owner's interactive session (`/raider-wave`, agents `raider-worker`/`raider-reviewer` in `.opencode/agents/`). Background Bash loops running `opencode run` (nohup/night-loop) are forbidden by default. A headless overnight mode requires a separate explicit owner decision recorded in the board.
- Parallel lanes are open (2026-10-12 roadmap revision): alongside the remaining P-LIN work, lanes may cover remaining P2 TUI work (drafts/scrollback/unread badges/palette/help; coordinator registers cards), remaining P1 work (RAI-059/060), and P3 (RAI-063, send/steering). Roadmap §5 dependencies are closed by evidence (P0-WAVE0, W1).
- Do not modify the neighboring Yabanin repository under a Raider card. Yabanin changes require separate cards and its own mandatory rules.

## Checks and evidence

- Gate catalog and meanings: `docs/quality-gates.md`, including the mandatory activation stage. Before the runner exists, run the exact bootstrap checks. Component stage checks the specific cases being implemented; the rest remain milestone obligations. Full/milestone stages have no green skips. Do not require a packaged REPL/CI from a card that only creates its prerequisites.
- Once the runner exists, run `make quality-changed`, then `make quality-changed QUALITY_EXECUTE=1` before completing a task. Also run the card's semantically required profiles.
- Runtime/concurrency changes require cancellation/ownership/budget tests. REPL changes require real compiler/PTY checks, not only a mock interpreter.
- Core/CLI must work without Yabanin: OpenAI-compatible and Anthropic-compatible transports are first-class MVP requirements. There is no hidden direct fallback from a gateway after auth/policy failure.
- Headless runner/CI adapter changes require non-TTY, SIGTERM, exit-code, artifact/schema, and trust-boundary acceptance. GitHub/GitLab adapters must not implement a second scheduler/runtime.
- A skip, timeout, scanner error, missing tool, or external blocker is not a pass. Do not lower thresholds or disable checks to obtain a green status.
- Evidence applies to the checked source fingerprint and gate configuration. Source changes after a check require rerunning affected gates.
- Create a work record using `docs/templates/work-record.md` after actual execution. Do not create empty reports in advance.
- Evidence is tied to an immutable product-input snapshot. Operational board/records may be excluded only through an exact reviewed allowlist with separate audit hashes; code/specifications/prompts/build/fixtures/gate policy remain in the fingerprint. Product-input changes require new affected checks; lane evidence does not replace checking the integrated candidate.

## Trust boundaries

- REPL and Scala plugins execute trusted user code with the process's permissions. Tool policy is not a sandbox for arbitrary Scala.
- Never write secrets to Git, history, snapshots, examples, argv, reports, or tool output. Credentials are supplied through env/helper references; values must not be logged.
- Do not make real paid calls without an explicit mode and budget. Ordinary development/CI gates use mock/replay.
- Do not automatically repeat mutating tool calls after an ambiguous result. Do not claim exactly-once execution or durable resume for arbitrary Scala lambdas.
- Do not change protected verifiers, hidden acceptance fixtures, or gate policy in a product implementation card. Such changes require a separate card and independent review.

## Handoff

Report changed paths, behavior, exact commands/exit codes, evidence path, risks/limitations, and the next available card. Do not invent executed checks, commit/PR URLs, paid results, or quality percentages.
