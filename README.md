<div align="center">

<img src="docs/logo.svg" alt="Raider" width="400"/>

# Raider

**Scala workflows for coding agents and CI** — Scala 3 + ZIO 2

[![License: MIT](https://img.shields.io/badge/License-MIT-e94560.svg)](LICENSE)
[![Scala 3](https://img.shields.io/badge/Scala-3.9.0-0f3460?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![ZIO 2](https://img.shields.io/badge/ZIO-2.1.26-e94560)](https://zio.dev/)

</div>

Raider is an in-development Scala coding agent and workflow runtime. The current source includes a native model/tool loop, a compiler-backed Scala REPL, compiled workflows, OpenAI-compatible and Anthropic Messages transports, workspace tools, and headless run artifacts. The live tool examples currently document OpenAI-compatible providers; Anthropic tool definition parity is tracked by RAI-127. The examples below exercise current code; they do not claim a released SDK or autonomous pull request, merge, or release workflow.

## Run the verified examples

Requirements: JDK 21, sbt 1.11.4, Python 3, and the JDK `jar` tool. Run from the checkout root:

```sh
sh scripts/build/user-examples.sh
python3 scripts/quality/user_examples.py
```

The examples use the pinned Scala 3.9.0 and ZIO 2.1.26 dependencies. The first build may resolve dependencies. The verifier runs the actual Scala compiler, REPL, CLI and workspace tools. Reports, command exits, transcripts and input hashes go under ignored `artifacts/user-examples/verification-*/`.

### Coding tasks and a release workflow

| Scenario | Run | What to inspect |
| --- | --- | --- |
| Repair the $100 billing discount boundary | `python3 scripts/quality/user_examples.py --only coding` | `DiscountSpec` fails at exactly 10,000 cents, then passes below/at/above after a hash-guarded edit. |
| Audit a payment configuration | `python3 scripts/quality/user_examples.py --only audit` | `AUDIT.json` reports `PAY-001`, the exact config line, impact and a remediation. |
| Check release readiness | Run the bundle command below | Structured output says `BLOCKED` with `rollback` pending; the CLI run itself succeeds. |

### Review code and propose tests in Scala

The review example describes two lazy asks, labels their answers, then runs them together:

```scala
val billingCode = "if subtotalCents > 10000 then 1000 else 0"
val billingPolicy = "A flat $10 (1000 cents) discount applies to purchases of at least $100 (10000 cents)."
val scoutAsk = scout(s"$billingPolicy Review this Scala expression: $billingCode. Identify the exact boundary defect and give one concrete input/output counterexample. Do not use tools. Reply concisely.").map(answer => ("boundary-review", answer))
val testAsk = worker(s"$billingPolicy For this Scala expression: $billingCode, Specify the expected discounts required by the policy, not the outputs of the buggy code. Return ONLY a JSON array of [subtotalCents, expectedDiscountCents] pairs for 9999, 10000 and 10001 cents. No markdown or tools.").map(answer => ("test-plan", answer))
val (review, plan) = all(scoutAsk, testAsk).run()
```

Run that exact example:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/06billing-review.repl
```

The review example runs two lazy prompts in parallel against inline Scala billing code. `map` adds typed result labels and `all(...).run()` combines the responses. The mock replies are fixed placeholders; use an explicitly selected OpenAI-compatible model and spend budget for the live verifier in the [guide](docs/user-guide.md#3-parallel-dsl-review-with-useful-prompts).

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check --mock
```

The offline coding and audit examples use the native runtime/compiler with deterministic scripted tool stands. The release workflow is ordinary typed Scala code. It verifies tool sequencing and real Scala checks without a model request. These runs do not establish general model quality. Reports and transcripts are under ignored `artifacts/user-examples/verification-*/`.

## Current CLI surface

The headless CLI supports `run`, `chat`, `plugin`, and `replay`. `raider.cli.Main` is the source launch entry point; `raider.repl.Main` starts the Scala REPL. The REPL's scripted/mock responses are fixed fixtures, not answers from a model. The tab shell (`--tabs`) is a separate UI from Scala evaluation.

`coder` has native file and process tools. Scala scripts, bundles, plugins and repository commands run with the process user's permissions; workspace checks are not a sandbox. Use disposable workspaces for experiments.

## Development quality gates

```sh
make quality-changed
make quality-changed QUALITY_EXECUTE=1
```

The protected registry defines profile commands. Evidence is written to `artifacts/quality/<run-id>/manifest.json`. Counts and results belong to the checked candidate and are not hardcoded here.

[Roadmap](docs/roadmap-12k.md) · [Execution board](docs/work/board.md) · [Runtime contracts](docs/runtime-contracts.md)

MIT — see [LICENSE](LICENSE).
