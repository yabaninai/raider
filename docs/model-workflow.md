<!-- Translated from Russian original. Key terms preserved as-is. -->

# Working with simpler models

## 1. What makes quality reproducible

The basic unit of work is a small card with fixed inputs, allowed paths, a
contract, and executable acceptance. A stronger model is useful for resolving
architectural uncertainty; simpler models can implement narrow contracts. A
model name is not evidence of quality.

All required knowledge is stored in git. Do not require the next executor to
remember a chat, a previous session, or to have a particular MCP.

## 2. Roles

| Role | Scope | Output |
| --- | --- | --- |
| Owner | Priorities, product/license decisions, risk/budget | Selected cards and acceptance |
| Planner | Break down uncertainty, record interfaces | ADR + small cards |
| Implementer | One card | Diff, tests, work record |
| Reviewer | Check contract/diff/evidence | Findings and verdict |
| Integrator | Combine accepted cards, check downstream | Final full/acceptance evidence |

One model may perform different roles in separate clean contexts. Independent
review means a fresh context and independent reading of diff/tests, not
necessarily a different vendor. An author cannot approve their own changes to
protected verifiers/quality policy.

## 3. Card size

Practical guideline: 1–3 production files, one interface or observable
behavior, usually 100–400 lines of diff plus necessary tests. This is not a
hard rule: task splitting depends on dependencies and understanding, not an
artificial LOC limit.

If a card requires new public APIs, state-machine redesign, and changes to
five modules at once, start with a design/contract card, then implement. This
program does not allow a single “build the whole REPL” card.

## 4. Input packet

For each card:

1. `AGENTS.md`.
2. Card text and only relevant specification sections.
3. Exact allowed files/symbols and the accepted API signature.
4. Existing tests/fixtures and evidence baseline.
5. Dependencies and their accepted work records.
6. Required test commands/gate profiles.
7. Non-goals and stop/escalation criteria.

Do not send the entire 50-page program with each coding task. The full index
helps locate sections; the work packet contains specifics. Do not omit a
required invariant to shorten context.

## 5. Work cycle

```text
read card → inspect code/status → identify contract tests
→ implement smallest change → targeted check → repair
→ required gates → fresh review → accepted → next dependent card
```

For a bug fix: reproduce first, then fix. For a feature: create the acceptance
fixture from the contract, then implement. Do not create tests that merely
confirm the current accidental implementation.

After two substantive failed attempts for the same reason, stop repeating the
same approach: prepare a precise blocker/reproducer and design packet. A
simple missing import/format issue can be fixed locally and does not require
escalation. Never temporarily disable a failing test.

For a public API change, present the minimal contract/ADR diff and update the
designated fixtures and dependent cards. A runtime implementer does not change
names merely because a different DSL feels more familiar.

## 6. Ready-to-use implementer prompt

```text
You are working in the raider repository. Execute only card <ID>.
Read AGENTS.md, the card, named specifications, and the current code path.
Check git status (or the manifest baseline for a snapshot without Git) and dependencies; preserve others' changes.
Implement the contract with the smallest diff in allowed paths. First prepare
acceptance/reproduction tests. Do not change protected gates/fixtures/thresholds.
Run targeted tests and all required profiles; failed/skipped/stale is not passed.
Prepare the work record before final gates. Report exact commands, exit codes,
evidence path, limitations, and the next available card.
If the contract is ambiguous, prepare a concrete design decision and continue
independent authorized work; do not silently invent an incompatible API.
```

## 7. Ready-to-use reviewer prompt

```text
Review card <ID> against the actual diff and current source. Use the author's
summary only for navigation. Read the contract and required gates.
Check normal/error/cancellation/race cases, ownership, budgets, secrets,
backward compatibility, and unchanged verifiers. Check source/log/policy
hashes in the manifest. Reproduce a concrete scenario if needed.
Return accepted / changes-required / blocked with facts, file:line, severity,
and the smallest correction. Do not add adjacent features to this card.
```

A reviewer does not trust a fabricated evidence path: the file must exist and
be verified by the runner. Agreement with the author is not required. Review
of selected risk boundaries includes adversarial checks, not just a restatement
of the diff.

## 8. Board management

Card states: todo → ready → in-progress → review → accepted. `blocked` records
a dependency/cause and does not become accepted merely with time.

`docs/tasks/README.md` is the static backlog; current execution can be tracked
in an issue tracker or separate board file. Create work records only for
completed cards. A work record retains task ID, source baseline, actual
changed paths, acceptance IDs, commands/exit codes, evidence, and limitations.

The owner authorized parallel development on 2026-10-03. The
[runbook](parallel-development.md) specifies coordinator + up to three workers,
isolated worktrees/snapshot copies, frozen signatures/allowed paths, automatic
fresh review, and sequential integration. One writer **per checkout**;
independent directories may build in parallel. Implementation dependencies
differ from final integration dependencies: a component can be implemented
against an accepted interface/testkit, while the parent accepts the actual
combined work. Record and review refinements before coding; “almost ready” is
not an accepted prerequisite.

The finish target is a [working coding agent](working-product.md), including
sessions/context/package/fixtures. Milestone M0 is not the product finish.
Stage-aware gates do not require future runtime from the first compile-only
contract; all final obligations remain mandatory.

## 9. Scope and quality control

- Real paid calls are not required for everyday development.
- Mock mode is visible to users and in artifacts; fixture output must not be
  shown as a live-model answer.
- API examples are compiled/run; a stale example fails the docs gate.
- A dependency addition must explain the existing gap and compatibility/license result.
- No speculative framework/macro, automatic fixing of all lint findings in
  another's module, or broad refactoring of neighboring code.
- A flaky test is not accepted as an “async quirk”: fix virtual time/coordination
  or document the platform cause.
- `run` succeeded and task verifier accepted are separate facts.

## 10. z.ai for development and product use

An implementer may use an available z.ai coding tool and any assigned model.
Raider must not depend on the model name or its subscription.

The product does not require Yabanin. OpenAI-compatible/Anthropic-compatible
profiles are first-class; Yabanin is an optional gateway/accounting layer. A
direct Z.ai profile uses an explicit base URL/account configuration and its
own capability check. General API and Coding Plan endpoints differ; do not
automatically substitute one for the other. Support for a custom harness/account
must be confirmed by current docs and the owner's account, not by a similar-looking URL.

Do not hardcode commercial limits/model IDs forever in AGENTS.md. Work
records/eval retain the actual IDs used and the gateway's actual model. Route
simple worker/review tasks by role; use a stronger model for a specific,
demonstrated difficulty.

## 11. Escalation packet

If a simpler model cannot solve the task:

```text
Card ID / contract clause:
Smallest reproducer:
Current relevant symbols/files:
Expected / actual:
Attempts already made and why they failed:
Exact command / exit / logs:
Two bounded implementation options:
Required decision / missing external dependency:
```

This lets the owner/stronger model make a concrete decision. Do not ask to
“redo everything well” without a reproducer.

## 12. Owner acceptance without reading all the code

For each milestone, provide one demo-acceptance command, one gate-verification
command, a short results table, and known limitations. The owner sees actual
terminal transcripts, root/job trees, cleanup, costs/uncertainty, and actual
diff. A long narrative report does not replace these artifacts.
