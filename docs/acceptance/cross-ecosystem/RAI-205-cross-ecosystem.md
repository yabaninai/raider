# RAI-205: Independent cross-ecosystem comparison and consumer acceptance

Status: offline-engineering evidence recorded; live quality, external-consumer and repeat-use statuses pending. Date: 2026-10-27. Lane: `lane/rai-205`, worktree `wt/rai-205`, baseline commit `1351538`.

## Scope and method

One integrated candidate — `modules/cli/target/scala-3.9.0/raider-cli.jar`, sha256
`c9bd87776660f84c6569506b3b9beb9927abd45f0080afcfe9bc4ec272c7acad` — is driven
through the `raider process-api` contract by two pinned foreign-framework
consumers, each compiled and executed outside the product classpath:

- Ecosystem A: `org.llm4s:llm4s-core_3:0.5.1` (Scala 3 consumer, `xe.llm4s.XeLlm4sConsumer`),
  pinned closure in `contracts/acceptance/cross-ecosystem/consumer-deps.json`
  (llm4s-media_3, cats-core_3 2.13.0-lihaoyi, upickle 4.4.3 stack, geny 1.1.1).
- Ecosystem B: `dev.langchain4j:langchain4j:1.22.0` (Java consumer,
  `xe.langchain4j.XeLangChain4jConsumer`; langchain4j-core 1.22.0,
  jackson-databind 2.22.1, slf4j-api 2.0.17).

The consumers are plain JVM programs: they hold only the adapter jar plus their
own framework closure and speak the process-api wire contract (hello/negotiate,
task submit, observation frames, cancel, close). No product class, no ZIO type
and no compiler type appears in either consumer's classpath.

Fixture OpenAI-compatible stand: `contracts/acceptance/cross-ecosystem/stand/Stand.scala`
(`com.sun.net.httpserver`, fixture key `sk-stand-205` only — never a real
credential). Stand A serves the scripted tool round (touch marker) and the final
text; stand B serves the same rounds behind a 3000 ms delay that widens the
cancellation window. All consumer tasks run against the mock provider unless a
stand URL is configured; no live or paid call is made by this evidence.

## Consumer matrix (XE-ACCEPT-01)

Two non-Scala repository tasks and one read-only extraction task per ecosystem,
plus the offline protocol cases:

| Case | Ecosystems | Stand | Adapter exit | Named checks (all pass) |
| --- | --- | --- | --- | --- |
| handshake-terminal | llm4s, langchain4j | mock | 0 | 8 / 9 checks incl. `langchain4j-message-roundtrip` for ecosystem B |
| refusals (4 anchors) | llm4s, langchain4j | mock | 0 / 20 / 20 / 0 | refusal anchors matched (input_rejected pre-dispatch, malformed_frame, oversize_frame, duplicate_submit, hello_already_negotiated, unknown_field); policy denial demonstrated via the RA-PBUDGET budget route; auth-failure (401) and unavailable-provider paths are NOT exercised offline yet |
| observe-dedup | llm4s, langchain4j | mock | 0 | 6 checks |
| tool-task (repository write via `touch`) | llm4s, langchain4j | A | 0 | 5 checks: terminal completed/success, artifacts set, marker file exists |
| extraction-task (read-only extraction) | llm4s, langchain4j | A | 0 | 4 checks |
| cancel-mid-run | llm4s, langchain4j | B | 26 | 5 checks: cancel accepted before terminal, cancel ack accepted, terminal cancelled/incomplete, cleanup complete |
| eof-uncertain | llm4s, langchain4j | B | 26 | 4 checks: dispatch accepted, EOF after dispatch settles, terminal cancelled/uncertain, cleanup complete |

langchain4j checks are byte-equivalent to the llm4s set plus the
`langchain4j-message-roundtrip` handshake check. All checks in all six manifests
pass. The manifests pin the framework coordinates and the adapter jar sha256;
the checker recomputes the digest from the jar bytes, so the evidence is bound
to the candidate fingerprint, not to a path.

## Independent checker

`contracts/acceptance/cross-ecosystem/checker/IndependentChecker.scala`
(`xe.checker.IndependentChecker`) is deliberately independent of both consumer
frameworks: its runtime classpath is scala-library plus the upickle JSON stack
only. It re-derives everything from the evidence tree and the golden file
`contracts/acceptance/cross-ecosystem/expected/golden-checks.json`:

1. Manifests: each of the 20 golden scenario entries (10 per ecosystem, anchor
   keys) matches exactly one manifest scenario entry with the exact ordered
   check-name list and adapter exit code; every manifest entry is consumed;
   ecosystem identity and pinned coordinates must agree.
2. Artifacts: invocation directories are classified as full (`artifacts.json`
   present) or fatal-only (exit 20, `events.jsonl` only, possibly empty);
   per-scenario group counts must match the goldens; all artifact digests and
   sizes are recomputed bidirectionally; `run.json` status/exitCode must agree
   with the group (0 → Succeeded, 26 → Cancelled).
3. Workspace: `tool-task/marker.txt` must exist (the scripted tool round really
   executed on disk).
4. Stand logs: every non-empty line is JSONL with `auth` of `present|absent`
   only; the fixture key appears in no log; at least one `auth: present` line
   must exist across the corpus as positive redaction evidence.

Result for the recorded run: `ok: true`, 160 findings, 0 failed
(`artifacts/xe-out/checker-report.json`).

## Cancellation/budget/uncertain-mutation corpus (XE-ACCEPT-03)

- cancel-mid-run and eof-uncertain: see matrix above; both ecosystems, exit 26,
  cleanup-complete and terminal-cancelled checks pass on the integrated
  candidate.
- Budget exhaustion: `contracts/acceptance/cross-ecosystem/scenarios/run-budget.sh`
  runs the fixture workflow `contracts/acceptance/cross-ecosystem/expected/budget-exhaust.workflow.json`
  against the pinned trusted registry (profile `coder`, toolset `workspace`,
  check `workspace-marker-present`, `registrySnapshot` sha256:0c877972cf59788094e1d7653a1f01f6cd1b6c9108d117d5e069bc6b6bdaba0b)
  with `limits.tokens: 1` and a sequential fan-out over two documents. The
  second model dispatch is refused with the typed budget refusal
  `raider RA-PBUDGET: declared token ceiling of 1 exceeded (observed 150);
  further model dispatch is refused`, process exit 23
  (`ExitCodes.BudgetExhausted`), `run.json` status `Failed` / exitCode 23.
  Evidence: `artifacts/xe-budget/budget-result.json` and the work directory
  `artifacts/xe-budget/run/work-4c720d57/` (events.jsonl, run.json, summary.md).

## Statuses (recorded independently)

| Status | Value | Basis |
| --- | --- | --- |
| Offline engineering | PASS | All named-case checks above on the integrated candidate fingerprint; scripted/mock and fixture-stand only. |
| Live quality | PENDING | No live or paid call is made; live repetitions need an explicit owner-authorized mode and budget cap. |
| External consumer | PENDING | No external consumer demand evidence exists; foreign-language consumers additionally require external fixture/policy provenance. |
| Repeat-use | PENDING | Corpus not yet repeated on a second integrated candidate; re-run `run-all.sh` on the next candidate fingerprint. |

Scripted cases never establish model quality or competitor superiority; the
fixture stand answers are deterministic strings, not model outputs.

## Setup metrics (adapter code and consumer setup counted separately)

Per the framework-comparison requirement (`docs/cross-ecosystem-plan.md` §7,
"count adapter/setup code and setup time separately"), the offline corpus
records code volumes; setup time is a separate, still-pending measurement:

| Component | Source | Lines (`wc -l`, committed source) |
| --- | --- | --- |
| Adapter-side fixture stand | `contracts/acceptance/cross-ecosystem/stand/Stand.scala` | 148 |
| Adapter-side independent checker | `contracts/acceptance/cross-ecosystem/checker/IndependentChecker.scala` | 261 |
| Consumer-side setup, Scala | `contracts/acceptance/cross-ecosystem/llm4s-consumer/XeLlm4sConsumer.scala` | 656 |
| Consumer-side setup, Java | `contracts/acceptance/cross-ecosystem/langchain4j-consumer/XeLangChain4jConsumer.java` | 760 |

Adapter-side code (stand + checker) and consumer-side setup code are counted
separately; the consumer-side figures measure the integration effort a
foreign-framework consumer needs, not adapter cost. Setup time: PENDING,
recorded with the External-consumer status above — no external consumer has
performed a timed setup, and no self-timed value is substituted for it.

## RAI-202 gap fact and child-card proposal

The pinned registry exposes only `coder`/`workspace`/`workspace-marker-present`.
Running the shipped example workflow
`docs/examples/portable-workflows/extraction-classification/extraction-classification.workflow.json`
(uses profile `extractor`, toolset `read-only`, check `classification-labels-valid`)
against the pinned registry is refused with
`raider RA-INP: untrusted-authority-reference at /steps/0/profile (step 'classify-item')`.
This blocks only example-workflow acceptance, not the consumer matrix above.

Proposed child card (coordinator registers): extend the pinned trusted registry
example authority set (or rescope the example workflow to pinned names) so the
extraction-classification example resolves under the untrusted-authority
policy; acceptance = the example workflow runs end-to-end under the portable
budget route without RA-INP refusals, plus registry/check snapshot evidence.
No protected verifier or gate-policy change is required.

## Reproduction

```bash
bash contracts/acceptance/cross-ecosystem/scenarios/run-budget.sh   # exit 0; inner budget run exit 23 expected
bash contracts/acceptance/cross-ecosystem/scenarios/run-all.sh      # exit 0; 20 consumer scenario runs + checker
```

`run-all.sh` compiles the stand, both consumers and the checker from pinned
local Maven artifacts under `artifacts/tools/m2/`, starts stands on ephemeral
ports, runs six consumer invocations, stops the stands and runs the checker;
it exits non-zero if any consumer scenario or checker section fails. Evidence
tree: `artifacts/xe-out/` (six manifests, per-scenario invocation directories,
stand logs, `checker-report.json`).

## Limitations

- In the recorded runs the consumer cancels before the adapter's first provider
  round-trip (cancel/eof invocation event streams contain only
  `JobStatusChanged Queued→Running`), so the corpus verifies cancel-accepted /
  ack / terminal-cancelled / cleanup on the integrated candidate, while
  mid-provider-response cancellation semantics remain covered by the process-api
  wire fixtures (`docs/fixtures/process-api/cancel-mid-run.json`, `eof.json`)
  from their own cards. Stand B's empty log is expected under this timing and
  the checker accepts it (positive redaction evidence is required across the
  corpus, not per file).
- The plain wire used by the process-api compiled route is non-streaming
  (`OpenAIChatBackend`, `streaming = Unsupported`); SSE frames are exercised by
  the streaming backend slice, not by this corpus.
- LangChain4j 1.22.0 requires `slf4j-api` on the classpath or initialization
  fails; the pinned closure in `consumer-deps.json` includes it.
- Live statuses above are pending and are not implied by any check here.
