# Raider beyond Scala: portable workflows and adoption

Owner-directed planning revision, 2026-10-23. Based on the
[LLM4S/LangChain4j research](research/llm4s-langchain4j-2026-10-23.md).
This extends [roadmap §6e](roadmap-12k.md#6e-cross-ecosystem-workflows) and the
existing chat-to-Scala-to-CI path. It does not declare new APIs, commands,
packages or customer demand implemented. RAI-196..206 are registered delivery
cards; their contract decisions precede implementation.

## 1. Product direction and first markets

Proposed promise: **turn useful agent work into an editable, checked workflow
that runs from your existing tools**. Users choose a ready recipe, a portable
definition or native Scala authoring. All execute through the same Scala/ZIO
runtime, policy, budget, cancellation and evidence machinery.

| Audience | First recurring task | Entry surface | Evidence that warrants expansion |
| --- | --- | --- | --- |
| Polyglot development/platform teams | Repair failing tests or apply a bounded migration with independent checks | Installed CLI plus portable recipe; generic local CI first | Repeat runs in two non-Scala repositories, with independently verified changes |
| JVM application teams | Typed extraction/classification plus a deterministic application check | Same local process boundary; Java-friendly embedding considered later | A real caller needs in-process integration and accepts the lifecycle contract |
| Internal automation/data teams | Extract structured fields from supplied documents or route supplied tickets into a report | JSON input/output and portable workflow, no connector required | Repeat use saves manual work with an acceptable correction rate |

These are ordered hypotheses, not three simultaneous product launches. Finish
the coding/CI path first; use the read-only extraction recipe to test transfer
beyond coding without building retrieval infrastructure, CRM connectors or a
hosted service. External outreach/publication remains a separate action.

## 2. Entry surfaces and DSL decision

Keep three concepts separate: the language of the repository being changed,
the language of the caller, and the language that implements Raider. None
requires the other two to match. Product implementation/tests/stands stay
Scala 3 + ZIO. This revision does not authorize Python/TypeScript runtime code.

| Option | Benefit | Cost/risk | Decision for the first slice |
| --- | --- | --- | --- |
| Ordinary Scala DSL | Full expressiveness, compile-time types, existing implementation | JVM authoring/toolchain knowledge | Retain; do not reduce arbitrary Scala to a serializable graph |
| JSON workflow document with schema-aware editing | Any language can generate it; no new grammar; deterministic validation | Verbose for manual authoring | Recommended interchange and first portable execution surface; ADR required |
| YAML authoring over the same schema | Familiar to CI users | Implicit types, tags, aliases, duplicate keys and source-map complexity | Evaluate with users; no parser dependency until a separate card is justified |
| TypeScript/Python/Kotlin builder | Familiar composition and IDE tooling | Package/version/support matrix; conflict with current shipped-code policy | Defer packages; evaluate later as thin producers of the same format |
| New textual language with expressions | Potentially concise and domain-specific | Parser, type checker, LSP, debugger, migration and security costs | Do not build yet; require measured advantage over JSON/Scala prototypes |

RAI-196 compares authoring tasks using read-only mockups: change a schema,
insert a check, add a branch, bound a map and diagnose an invalid reference.
Record completion time, corrections and comprehension. No participants means
usability is pending; an internal walkthrough cannot substitute for it.
JSON is an engineering default, not a claim it is already the most convenient
human DSL. RAI-197 may refine the shape while preserving the single-runtime rule.

## 3. Portable semantics before syntax

Proposed portable subset, frozen only by RAI-197:

- Versioned input/output schemas and stable step IDs; schema/data references
  use a bounded path notation such as JSON Pointer, not executable expressions.
- Agent step with a named profile/tool set; structured output is validated
  locally even when a provider supports native schemas.
- Sequence, typed conditional, bounded parallel map and join. V1 branches use
  a small declared predicate set; arbitrary host callbacks are not portable.
- Trusted deterministic check references bound by the workflow's provenance;
  task input and model output cannot supply new commands or widen policy.
- Bounded repair and explicit human checkpoint. Repair only repeats a declared
  safe operation; ambiguous mutation ends uncertain and requires reconciliation.
- Root deadline/attempt/child/token limits; monetary limits only when estimates
  and enforcement support them. Unknown cost remains unknown, never zero.
- Explicit output artifact bindings and a machine-readable final verdict.

Illustrative authoring sketch, **not current syntax or a runnable example**:

```text
input: issue { summary: string, workspace: workspaceRef }
repair: agent(coder, input.summary, output: PatchSummary)
verify: trustedCheck(projectTests, dependsOn: repair)
report: output(repair.summary, verify.evidence)
limits: attempts <= 8, parallelism <= 2, deadline <= 5m
```

The user should be able to describe this without learning ZIO. Implementers
must not ship a parser for this sketch. The ADR supplies exact schema fixtures
and refusal cases first. JSON Schema editor completion is the initial editing
aid; diagnostics identify document path, step ID, offending reference and a
suggested correction. Validation and explanation make zero model/tool calls.

### Shared execution boundary

```text
chat selection -> portable definition (supported subset only)
portable document -> validate/resolve -> existing Program/Task execution
native Scala -----------------------> existing Program/Task execution
CLI / local process client --------> same launch/policy/evidence boundary
```

Audit existing definition descriptors before adding an intermediate type;
introduce only the representation that portable validation actually needs.
No independent scheduling, retry loop, budget ledger or persistence engine.
Native Scala stays more expressive; arbitrary closures fail portable export
with a precise unsupported reason. Preserve the existing Scala export path.
Neither source export nor validation executes generated work.

### Security and compatibility constraints

Workflow definitions are reviewed executable configuration. Task inputs and
model/tool output are untrusted data. Profiles/checks resolve against trusted
configuration and may only narrow inherited permissions. No arbitrary class
deserialization, shell interpolation, remote imports or automatic dependency
downloads. Reject unknown major versions, duplicate keys/IDs, cycles, missing
references, impossible joins, oversized/deep documents and unbounded work.
Schema evolution requires fixtures/migration and explicit unsupported behavior.
No sandbox claim is made for Scala or configured processes.

## 4. Calling Raider from other languages

Prefer a managed child process over a daemon for the first integration.
RAI-200 freezes a bounded JSON-lines protocol: handshake/version/capabilities,
submit, observe, cancel and terminal result with artifacts. Each invocation
owns one root; request IDs identify messages and do not imply idempotent
submission. Diagnostic text goes to stderr; stdout is protocol-only.
The current headless runner never reads stdin: use a separate opt-in adapter,
not a behavior change to the existing non-TTY command.

The contract must define message size, framing/Unicode, backpressure, EOF,
broken pipe, cancellation acknowledgement versus completed cleanup, exit
codes and exactly one observable terminal result when the channel survives.
After process death without a terminal record, outcome is uncertain. Do not
resubmit automatically. Reuse existing artifact/recovery inspection semantics.

CLI JSON input/output already offers an early foreign-language entry point;
the protocol is for streaming and lifecycle control. TypeScript, Python, Go,
.NET and Java callers can use ordinary subprocess APIs without a Raider SDK.
Foreign-language callers/fixtures are external consumer projects, not shipped
product modules. Any checked-in non-Scala example/test code needs a separately
recorded language-policy exception; the implementation cards use Scala process
stands. Independent consumer evidence records source/toolchain hashes and can
remain pending without pretending a Scala stand proves foreign-language UX.

RAI-206 is a demand-gated Java facade design spike, not an authorization to add
Spring/Quarkus dependencies or publish SDKs. A future facade implemented in
Scala should expose Java values, typed results and explicit cancellation/close,
without leaking ZIO, Scala collections or compiler dependencies.

## 5. Capability and result quality

RAI-202 first audits existing `ModelCapabilities`, codecs and validation.
Any public request/schema change receives a separate ADR in that card before
implementation child cards are assigned. Compare native-schema output,
prompt/parse/validate fallback, streaming tools, cancellation, usage and model
identity. Contract tests run against Scala HTTP stands on both direct wires.
Capabilities are keyed by endpoint/model/config evidence; common URL shape
alone is not support. Fallback is explicit, budgeted, and never crosses an
authentication or policy failure. Human/model judge feedback remains advisory
unless trusted workflow policy explicitly says otherwise.

Do not add a second observability platform. Existing event/lineage identities
should link steps, attempts, checks and artifacts. Report prompt content only
under the existing trace policy; counters and redacted identifiers suffice for
default diagnostics. Portable/Scala parity includes errors and accounting,
not just matching success strings.

## 6. Delivery, dependencies and estimates

All figures are provisional engineering-hour ranges including implementation,
review and relevant tests, not model runtime or a promise of delivery dates.
They are additional scope: the old 13,100-hour envelope and later TUI-X program
are not silently expanded. Existing functionality is credited by audit.
Before each implementation packet the coordinator records remaining work,
allocation/tradeoff and the concrete files; split oversized cards before coding.

| Card | Verifiable outcome | Dependencies | Range |
| --- | --- | --- | ---: |
| RAI-196 | Reviewed architecture/market boundary ADR and reuse audit | none | 24–48 |
| RAI-197 | Versioned portable workflow/schema/semantics ADR and cases | 196 | 40–80 |
| RAI-198 | Strict validator and lowering to existing runtime | 197 | 80–160 |
| RAI-199 | Portable CLI execution with native/portable parity | 198, 187 | 60–120 |
| RAI-200 | Local process protocol ADR and transcript corpus | 196 | 24–48 |
| RAI-201 | Bounded process adapter using one runtime | 200, 187 | 60–120 |
| RAI-202 | Provider/output capability audit and reviewed extension contract | 196 | 24–48 |
| RAI-203 | Supported chat selection to portable recipe and two useful recipes | 198, 160 | 60–120 |
| RAI-204 | Coherent installed quickstart and first-use evidence | 193 | 32–64 |
| RAI-205 | Independent comparative/consumer acceptance packet | 199, 201, 203, 204, 202 | 40–80 |
| RAI-206 | Demand-gated Java embedding design spike | 205 | 16–32 |

RAI-196..205 total **444–888 h**; optional RAI-206 adds **16–32 h**.
Capability implementation children discovered by RAI-202, external SDKs,
new syntax tooling and durable execution are outside these ranges and need
explicit estimates/cards. RAI-205 must report a required missing capability
as a blocker and register its implementation, not lower the acceptance bar.

Execution order:

1. Preserve in-flight First CI (186/195 then 187), live-wire 184 and TUI-X
   188..192. Reconcile current board before assignments. Do not reopen accepted
   RAI-155/160/193 or start the completed lineage wave from old prompt text.
2. Start light RAI-196 and onboarding RAI-204 alongside that work, with exclusive
   shared-file ownership. Contracts 197/200/202 may run in parallel after 196.
3. Implement 198 then 199/203; 201 can run separately after its prerequisites.
   Reviews remain independent; integrated candidate gates remain mandatory.
4. RAI-205 accepts one immutable candidate and records demand separately.
   RAI-206 starts only if a real JVM embedding need is recorded. No participant
   is not a positive result and does not block unrelated engineering.

No implementation card depends on acceptance of a parent waiting for that
same card. Existing RAI-195/186 contract sequencing is reconciled by its current
owner; this revision does not silently change those cards or accepted evidence.

## 7. Acceptance and competitive experiments

| Track | Required evidence |
| --- | --- |
| Portable contract | Zero-call validation; schema errors with locations; version/refusal corpus; bounded fan-out; no silent unsupported node conversion |
| Semantic parity | Same scripted model/tool responses and inputs through Scala and portable paths; equivalent effects, checks, errors, cancellation, limits and artifact identities (run IDs may differ) |
| Process boundary | Real child process, non-TTY/closed stdin where applicable, fragmented/oversize frames, slow reader, EOF, SIGTERM, cancellation race and no orphan descendants |
| Three consumer scenarios | Two non-Scala repository workflows and one read-only extraction/classification workflow; independent checks, no prewritten repair in production; missing external evidence stays pending |
| Framework comparison | Pinned LLM4S and LangChain4j artifacts; equivalent tools/prompts/models/budgets; count adapter/setup code and setup time separately; at least three attempts per selected live task when a budget is explicitly authorized |
| Offline comparison | Invalid structured output, unsupported capability, failed check, root cancellation and ambiguous mutation; no model-quality claim from scripted stands |
| First use | Five new users across at least two language backgrounds; target four complete setup and one useful recipe within ten minutes without author assistance; report raw outcomes |
| Repeat value | At least two external users repeat a workflow in seven days; count manual corrections, cost/unknown cost, latency and setup failures |

These are acceptance targets, not new green gates or observed results. Do not
edit protected verifiers/registry under product cards. Register any missing
gate under a separate reviewed policy card. Competitor harnesses are external
pinned consumers and require explicit fixture/license/language decisions.
Paid comparisons require endpoint/model/account/cap authorization separate from
offline development. This plan spends nothing and contacts no participants.

Stop expansion into SDKs/custom syntax if participants cannot independently
run the core recipe, if fewer than two return, or if maintenance effort exceeds
the recorded range without benefit. Fix installation, diagnostics and recipe
value first. Human and live results remain pending when prerequisites are
absent; deterministic engineering acceptance may still proceed separately.

## 8. OpenCode handoff

Use the existing [canonical prompt](prompts/opencode-12k-parallel.txt) with this
revision. Start from the current board and ready cards, not from historical
phase prose. The first new ready card is RAI-196; RAI-204 is independently ready
after accepted RAI-193. Each packet names allowed files, contracts, normal/error/
cancel cases, evidence and review owner. Do not build all proposed surfaces
before validating one end-to-end portable scenario.
