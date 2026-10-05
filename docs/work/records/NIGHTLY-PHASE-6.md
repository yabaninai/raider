# NIGHTLY-PHASE-6: work record

Status: done. Date: 2026-10-05T15:00Z. Baseline commit/source: 081e26e (feat(phase-5): packaging & CI).

## Resulting behavior

Documentation (master prompt Phase 6, tasks 6.1–6.3), all in English:

- **6.1 API documentation**: new `docs/api.md` — public API reference for core (ModelBackend/ModelEvent, Tool/ToolRegistry, RaiderError codes, TraceEvent), runtime (AgentLoop, Admission/BudgetLimits/MicroUsd, JobManager, DelegationToolset, display decorators), tools (all six CodingToolset tools with argument schemas and the `parsed` proc_run field), providers (transport + status mapping), CLI entrypoints, and the REPL facade — with Scala usage examples. ScalaDoc coverage verified across `modules/core|runtime|tools` public sources (all main files carry documented headers; this nightly's new APIs — SessionStore, CompileFixLoop, OutputParser, TreeTool, PatchTool, UnifiedDiff, RateLimitRetries, StreamingDisplay, ReplSession extensions — were written with ScalaDoc from the start).
- **6.2 Architecture guide**: new `docs/architecture.md` — module structure diagram, one-round data flow (model → events → AgentLoop → tools → tool-message → next round) with the key invariants (one execution point, traces are data, honesty at the edges), the two-layer budget/admission system (BudgetLimits + Admission brackets, delegation root-ledger, BUD-03), frontends (headless CLI / streaming chat / REPL with the classloader-split ThreadLocal bridge), provider transport, and a topic→file index.
- **6.3 Contributor guide**: new `docs/contributing.md` — everyday commands, how to add a tool (implementation rules: containment/preconditions/atomicity/bounds, CodingToolset registration, prompt registration, fixtures-first), how to add a provider (transport rules, SSE framer reuse, no hidden retries, stand-based fixtures), how to run the quality gates (profiles, evidence freshness, no-green-skips), and how to write tests (scripted backends, determinism rules incl. the content-routing lesson from Phase 4, eager reads for lazy assertions, no live models in tests).

## Changed paths

- `docs/api.md` (new)
- `docs/architecture.md` (new)
- `docs/contributing.md` (new)

No code, build, or gate-policy changes in this phase.

## Acceptance evidence (final nightly verification checklist)

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 1 | `sbt --batch compile` (-Werror) | exit 0 | build log |
| 2 | `sbt --batch test` | 10 suites, 0 failures | /tmp/test-p6.txt |
| 3 | `sbt --batch scalafmtCheckAll` | passed (gate) | artifacts/quality/20261005-145423/scalafmt-check.log |
| 4 | `sbt --batch "scalafixAll --check"` | passed (gate) | artifacts/quality/20261005-145423/scalafix-check.log |
| 5 | `python3 scripts/quality/forbidden_apis.py --self-test` | PASS, 0 violations | gate log |
| 6 | `make quality-changed QUALITY_EXECUTE=1` | 9/9 gates | artifacts/quality/20261005-145423/manifest.json |
| 7 | `sh scripts/quality/repl_smoke.sh` | PASS | /tmp/final-repl.txt |
| 8 | `sh scripts/quality/cli_smoke.sh` | PASS (run.json Succeeded) | /tmp/final-cli.txt |
| 9 | `python3 scripts/quality/repl_pty_smoke.py` | 13/13 passed | /tmp/final-pty.txt |
| 10 | live self-hosting: `scripts/selfdev/raider.sh run --agent coder --provider openai --input "Read build.sbt and list the modules"` | Succeeded, exitCode 0 (real llama.cpp on 127.0.0.1:8081; coder read build.sbt and returned the module table) | artifacts/selfdev/work-d565efba/ |
| — | all component profiles `tools,transport,repl,ci,static,unit,contracts,docs,runtime` executed | passed | quality run (profile execute) |
| — | fat JAR `java -jar raider-cli.jar chat --provider mock` | PASS (Phase 5 record) | NIGHTLY-PHASE-5.md |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` / `test` | 0 / 0 | /tmp/test-p6.txt |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-145423/manifest.json |
| `make quality-profile PROFILES="tools,transport,repl,ci,static,unit,contracts,docs,runtime"` | 0 | quality run manifest |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 (13/13) | /tmp/final-pty.txt |
| `sh scripts/selfdev/raider.sh run --agent coder --provider openai --input "Read build.sbt and list the modules" --out artifacts/selfdev` | 0, Succeeded | artifacts/selfdev/work-d565efba/ |

## Limitations / blockers

- The live check (#10) ran against the LOCAL llama.cpp stand (no paid API), explicitly sanctioned by the master-prompt checklist; it is an operational smoke, not a repeatable product test — CI keeps using mock/replay.
- CI workflow (.github/workflows/ci.yml) remains unexecuted until the first GitHub push (see Phase 5 record).

## Review and next task

Self-executed nightly run complete (Phases 1–6). Suggested next cards: extract the shared REPL/CLI system prompt into one constant, wire `CompileFixLoop` into the headless coder path behind an explicit flag, and give the delegation `maxLLM=1` stuck-child cancel channel its own card (tracked obligation).
