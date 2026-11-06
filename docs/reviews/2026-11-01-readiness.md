# Raider readiness audit — 2026-11-01

## Verdict and scope

Raider is a substantial **developer preview**, with a native Scala/ZIO runtime,
real tools, compiler-backed REPL, headless execution, two provider transports,
and portable workflows. It is no longer just specifications or feasibility
spikes. However, complete working-product/release readiness is **not established**,
and the audit found concrete gaps in both product wiring and evidence validation.
There is no defensible single completion percentage.

- `engineering_ready`: **not established** against W01–W17; component passes
  exist, but there is no complete integrated acceptance matrix/runner and
  several required behaviors are missing or miswired.
- `live_verified`: **not established for a coding task** on any required
  profile. RAI-184 records a gateway wire smoke; direct-chat/direct-anthropic
  coding scenarios remain pending.
- `opencode_comparison`: **not demonstrated** by the required matched tasks,
  budgets, repetitions and independent verdicts.

Method: eight isolated read-only audit lanes, coordinator source cross-checks,
server-ref verification, negative evidence-verifier execution, and offline
smokes of an immutable copied candidate JAR. No product code, gate policy,
protected expectation, existing card status, credential, or neighboring
repository was changed. No model calls, push, publication, hosted CI run,
full rebuild, or full regression suite was performed by this audit.

Primary source review baseline: `04f74780891e0e7032bf98b3d1dccac279f32354`.
The existing OpenCode session remained active and merged RAI-254 during the
audit: later `main` = `e41cb28def730686ba1b4a09d7b0fb84c7d075a2`.
That delta changes the corpus driver, its golden, and RAI-254's record; it does
not change the runtime sources underlying the findings below. Recheck current
HEAD before implementation. Lane records describe the earlier snapshot, not
the later RAI-254 integration.

## Why the remote appears stuck on October 28

This is a local/remote publication gap, not an absence of recent work.

| Observation | Audit start | Later observation during the audit |
| --- | --- | --- |
| Local branch | `main` at `04f7478` (November 1) | `main` at `e41cb28` (November 1) |
| Server-side branch | `main` at `ed1ef4f72b1771500a1367f856c49065fbbdb567` (October 28) | Same server ref, verified again |
| Ahead / behind | 73 / 0 commits | 76 / 0 commits |
| First-parent commits beyond server | 26 | 27 |
| Remote `master` | No advertised ref from `git ls-remote --heads origin main master` | Same |

The board's October 30 continuation explicitly says **no pushes**. The local
changes have not been published to that remote; changing this restriction is a
separate owner decision. Nothing was pushed by the audit. All date/count claims
are observations of actual refs, not an assumption that a web UI is stale.

## What changed over the last days

At the initial baseline, the 73 unpublished commits touch 70 paths. Only five
are product main-source files; the rest are tests/acceptance, build/scripts,
documentation and evidence. The large diff includes a backlog JSON rewrite and
vendored research evidence, so changed-line totals overstate user-facing growth.
See the [history audit](../work/records/AUDIT-20261101-history.md) for exact counts.

| Period | Concrete result | What it does not establish |
| --- | --- | --- |
| October 28–29, wave F / RAI-232..245 | Fixed process-API receipt/waiter and observation coordination; guarded export segment registration and finalization timing; hardened fixture process cleanup; added pinned offline dependency provisioning, reproducible JAR tooling, cancellation consumer probes, and Zinc compiler-state research | A new end-user product surface or complete working-product acceptance |
| October 30–November 1, RAI-246..252 | Deterministic race probes; protected the runtime phase-emission/arming window; documented at-least-once observation and deduplication; localized Zinc state poisoning; wired `make repro-jar`; stabilized generated ID aliases across clean builds | Exactly-once delivery, arbitrary durable resume, or cross-machine byte reproducibility |
| November 1, RAI-253 | Rebuilt the integrated candidate and changed both golden JAR pins to `e629ab0b…`; record reports two identical clean rounds and six loaded 20-scenario runs with 160 checks and no failed findings | A complete live coding task or all W01–W17 |
| During this audit, RAI-254 | OpenCode merged cancellation scenarios into the full corpus: expected count grows from 20 to 22, findings from 160 to 176 | A new integrated pass unless the post-merge run and full required profiles are actually complete |

These changes improve reliability and reproducibility. The recent activity is
predominantly stabilization and acceptance infrastructure, not equivalent to
closing all the promised installation/coding/live-model experience. At the
inspected snapshots RAI-246..254 still say `ready` in backlog despite their
merges. The coordinator must reconcile reviews, integrated evidence and status;
it must not start duplicate implementations from the stale queue.

## What exists and what remains

“Present” below means inspected implementation, supported where stated by
historical records. It does not mean freshly rerun full acceptance.

| Capability | Present today | Remaining requirement or limit |
| --- | --- | --- |
| Native coding runtime, W02/W04/W09 | Real `AgentLoop`, file/process tools, ownership, cancellation, admission, child jobs and event exports | Delegated child model selection is miswired; complete candidate-bound coding/cancellation acceptance remains |
| Filesystem tools, W03 | Read/search/tree/edit and single-file patch; lower-level multi-file patch implementation | Multi-file/create/delete patch behavior is not exposed through the coding registry's single-file `fs_patch`; structured delete contract is missing there |
| Policy/context, W06/W07 | Workspace containment, argv process execution, approvals and several pinned trust paths | Workspace containment alone does not protect sensitive/verifier paths; automatic project-instruction precedence/provenance is not established on the headless coder path |
| Sessions/DSL/extensions, W08/W11/W13 | Persistent chat, typed composition, plugin catalog/loader and executable tool SPI | Required `chat.close()` golden disagrees with API; custom tool plus executable profile integration and complete golden/session matrix need proof. Arbitrary closure recovery is not promised |
| Providers, W05/W17 | OpenAI-compatible and Anthropic-compatible implementations, including local HTTP/streaming tests; optional gateway wire use | Live direct providers and gateway coding scenarios with actual edits/checks remain unverified; gateway lifecycle/accounting claims require their own evidence |
| Limits/accounting, W14 | Attempts/children/concurrency, exact local money accounting; portable token/deadline envelope; bundle deadline | Native `--agent coder` root/children lack equivalent aggregate token/time controls; a standalone live spend guard is not wired into normal provider dispatch |
| REPL/TUI, W01/W10/W15 | Actual Dotty compiler REPL, JLine chat/tabs, approvals, plans and hunk review in source checkout | Installed distribution is headless; no installed REPL path. Chat/tabs/Scala evaluation are distinct modes. Unified UX is a scope decision, not automatically a defect |
| CI/workflows, W12 | Shared runtime for headless/bundles, local generic/GitHub/GitLab environment fixtures, reports/exits/signals, Scala export and portable/process APIs | Local process fixtures do not establish hosted template execution, full trust/classpath/platform acceptance, or published package consumption |
| Cross-ecosystem use | Actual llm4s/LangChain4j consumer fixtures and checked local scenarios | Mostly deterministic stands; portable registry supports specific pinned capabilities, not arbitrary recipes or demonstrated external demand |
| Distribution/release, W01/W18 | Local JVM releases and clean-prefix smoke records; reproducibility improved on one host | `--help` fails; installed REPL, advertised platform/package-manager checks, resolved dependency security/SBOM and complete release acceptance remain |

Detailed evidence: [runtime](../work/records/AUDIT-20261101-runtime.md),
[providers](../work/records/AUDIT-20261101-providers.md),
[REPL](../work/records/AUDIT-20261101-repl.md),
[CI](../work/records/AUDIT-20261101-ci.md),
[security](../work/records/AUDIT-20261101-security.md).

## Findings that change the plan

### P1: evidence verification can accept no executed gates

The coordinator constructed an explicitly artificial negative manifest with the
real current source inventory and policy hash, `profiles: ["fast"]`,
`gates: []`, and `toolchain: {}`. Executing the unchanged production verifier
returned **0** and printed that it satisfied `fast` with policy/toolchain/log
hashes checked. Expected behavior is rejection. This is a reproduced verifier
defect, not successful product evidence or proof that historical tests did not run.

`cmd_verify` in `scripts/quality/quality.py` checks only the gates listed in the
submitted manifest. It does not require the registry's full gate set for the
claimed profile, or compare the current toolchain. Separately, explicit profile
execution does not always include `self`, despite the documented requirement.
Fix under an independent protected-runner/policy card with negative fixtures for
missing/duplicate gates, inconsistent outcomes and stale toolchains.

### P1: source fingerprints include unrelated worktrees

`inventory` recursively scans filesystem contents, while the registry excludes
build/cache directory segments but not `wt`. The November 1 eval manifest lists
**23,515 `wt/` paths** as product inputs. Changes in other lanes therefore affect
root evidence. Conversely `.opencode` is excluded although checked-in command
prompts are product inputs under the documented policy.

This audit initially created its nine worktrees under the prescribed `wt/`;
the first verification attempts consequently added roughly 11,000 differences.
Those counts are audit-induced contamination, not 11,000 product defects. All
nine were then relocated with `git worktree move` under excluded
`artifacts/audit-worktrees/`. A repeated root verification still rejected the
older manifests (the active session and existing worktrees had changed).
Do not disable freshness checks or use `--allow-stale` to certify the current
candidate. Fix the inventory semantics under separate review, then regenerate
evidence for an isolated, frozen product snapshot. Existing historical evidence
remains historical; relocation did not alter product files.

### P1: native delegated children use a test model identifier

`DelegationToolset.scala` constructs `AgentLoop` with
`s"scripted:${args.agent}"`, while the headless parent uses `args.model`.
`CoderDelegationRunSpec` explicitly recognizes that scripted prefix in its local
stand. The source path therefore does not propagate the configured live model
to children. This is a confirmed wiring mismatch; no paid endpoint was invoked
to measure its response. Reproduce it with a local Scala provider stand that
requires the intended model for both parent and child, then fix model/profile
propagation with cancellation/budget ownership coverage.

### P1: requirements are stronger than current safety/limit wiring

The native coder constructs a full workspace `CodingToolset` without a
protected-path filter visible in that path; root containment does not make
in-workspace verifier/credential files inaccessible. That is a W06 acceptance
gap, not a claim that hidden verifier files were actually exposed in a run.
A process tool and trusted arbitrary Scala are not an OS sandbox.

Likewise native-coder aggregate token/time ceilings remain open. Do not erase
existing support: portable workflows already enforce a token/deadline envelope,
and bundle runs support `--deadline`. The separate `LiveWireSpendGuard` has only
definition/test references, not normal provider-dispatch call sites. The stopped
RAI-184 ledger also has inconsistent top-level reservation fields. Resolve these
before using a normal live run as evidence of a hard spending cap.

### P1: installation and DSL acceptance are not complete

An immutable copy of candidate JAR `e629ab0bd14a54f333c777884e184a3e7c3a58b5748ef02501acbb424e23f152`
returns **21** for `--help`, with an unknown-subcommand diagnostic. The same JAR
successfully runs a mock scout and validates a portable document. The mock output
is a fixed answer, not evidence that the model inspected or repaired a project.

The real Scala REPL exists but is not in the installed headless distribution;
provide a separate installed REPL path while preserving headless separation.
`working-product.md` requires `chat.close()`, but the current facade `Chat` has
only `ask`/`messages` and the practical guide explicitly says close is absent.
Resolve the lifecycle/contract mismatch with real compiler checks; do not silently
weaken the required golden to obtain a pass.

### P1 for final acceptance: named gates are narrower than their labels

The registry executes component-stage profiles. There is no complete W01–W17
manifest/stage implementation. `make quality-full` runs sbt tests/format, a
forbidden-API self-test, changed-scope gates, and two smoke scripts; it is not
the specified working-product suite. Named runtime/tools/transport profiles
largely point to the same sbt test run, so profile count is not independent
coverage. Real tests inside that run still have value.

The registered security profile is a narrow changed-line secret scan, and
`license-audit` is invoked with `--self-test`, not against the resolved graph.
Dependency vulnerability scanning, SBOM/notices and release/platform obligations
need actual reviewed checks. The audit does not allege a known CVE, secret leak,
or license violation. See the [acceptance audit](../work/records/AUDIT-20261101-acceptance.md).

### P2: planning state can dispatch already completed work

PLAN and the handoff still told workers to start RAI-196/204, while the backlog
already accepted them. `quality-gates.md` still opens by saying no Makefile,
sbt build or gates exist. Historical todo parents do not accurately inventory
current source. At the inspected snapshot the backlog contains 274 cards:
208 accepted, 55 todo, 9 ready, 1 in-progress and 1 blocked. These counts are
administrative data, not a readiness percentage.

This audit corrects PLAN and the handoff. It leaves numbered card acceptance
and protected gate-policy documentation to the existing coordinator's reviewed
reconciliation. See [roadmap audit](../work/records/AUDIT-20261101-roadmap.md).

## Corrected next work for OpenCode

The labels below identify proposed narrow follow-ups, not newly accepted cards.
The active coordinator assigns numbered cards and disjoint owners after checking
current work. Do not duplicate existing wave-G workers or restart old parents.

| Order / scope | Dependencies and allowed change family | Verifiable completion |
| --- | --- | --- |
| 1. Wave-G handoff | Current `main`, RAI-246..254 reviews and post-merge evidence; coordinator-owned board/backlog/index | Actual candidate/profile/corpus results recorded together, truthful statuses and next queue; no duplicate implementation |
| 2a. Evidence verifier | Separate protected-runner card and independent review; `scripts/quality` with negative fixtures | Reject zero/omitted/duplicate gates, inconsistent exits/statuses, missing/wrong toolchain and stale inputs; full gate-set coverage |
| 2b. Product inventory | Separate protected-policy card; tracked/untracked input inventory and test fixtures | Changes in unrelated `wt` copies cannot affect product hash; real prompt/spec/build/fixture changes do; operational allowlist/audit hashes remain exact |
| 3a. Child model propagation | Frozen model/profile selection contract; runtime delegation and CLI/REPL consumers | Local Scala stand checks real configured parent/child model IDs and tools; cancellation, child policy intersection and shared budget tests pass |
| 3b. Coder limits/policy/context | Explicit root-limit and protected-path/instruction contracts; separate small cards | Native root/children obey token/time/attempt limits; retries/unknown charges remain accounted; protected paths and instruction precedence exercised end to end |
| 3c. Structured mutation tools | Existing `PatchTool.applyAll` contract and explicit partial failure semantics | Agent-visible create/delete/multi-file patch, stale-hash and partial-failure cases tested through the real coding registry |
| 4. Installed workflow and DSL | Packaging plus REPL/facade child cards; retain headless/compiler boundary | Clean install, useful help, separate REPL launch, compiler/PTY golden scenarios including session lifecycle, actual coding loop/check artifacts |
| 5. Candidate acceptance | Explicit W01–W17 matrix, existing RAI-026/041/043/044/045/047 obligations scoped to what remains | One frozen candidate with required cases, logs and independent verdict; resolved dependency/license/SBOM and supported platform evidence; no green skips |
| 6. Live usefulness and comparison | Engineering blockers closed; endpoint identity, explicit permitted budget, reconciled spend guard | Real edits and failing→passing checks per direct-chat/direct-anthropic/gateway profile; comparison separately follows the matched W16 protocol |

Keep RAI-206's Java facade demand gate closed until its trigger is evidenced.
Defer new adjacent abstractions/features while closing the working acceptance.
Hosted publishing, remote pushes and paid comparison retain their existing
permission boundaries; this plan does not authorize any of them.

## Checks executed by the coordinator

Raw local evidence is in the audit worktree's `artifacts/audit-20261101/`;
[coordinator work record](../work/records/AUDIT-20261101.md) records final commands,
exits, hashes, review and documentation-validation results. Raw artifacts are not
assumed to survive a Git clone. The following are observations, not a full suite:

| Check | Actual result | Meaning |
| --- | --- | --- |
| Server refs / Git graph | Commands exit 0; remote `ed1ef4f`, local advanced `04f7478` → `e41cb28`, ahead 73 → 76 | Recent work is local; active session progress observed |
| Root historical fast/contracts/eval manifests | Verification exit 1; source fingerprints changed | Not usable as fresh current acceptance; no claim that past test executions failed |
| Artificial empty-gate `fast` manifest | Verifier exit 0, where nonzero was required | Reproduced false acceptance; explicitly NOT product evidence |
| Candidate JAR `--help` | Exit 21 | Confirmed user-entrypoint gap |
| Candidate JAR mock scout | Exit 0, artifacts generated | Headless mock smoke only |
| Candidate JAR portable document check | Exit 0, `valid: true` | Schema validation only, not execution authority or live workflow proof |

The copied JAR was verified against RAI-253's canonical pin. The existing root
build output at the moment of inspection instead had the older `997f29a1…` hash;
it was not treated as the current candidate and was not modified. Never infer
which source a cached build output represents from its filename alone.
