<div align="center">
<img src="docs/logo.svg" alt="Raider" width="400"/>

# Raider

Scala workflows for coding agents and CI

[![License: MIT](https://img.shields.io/badge/License-MIT-e94560.svg)](LICENSE)
[![Scala 3](https://img.shields.io/badge/Scala-3.9.0-0f3460?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![ZIO 2](https://img.shields.io/badge/ZIO-2.1.26-e94560)](https://zio.dev/)
</div>

Raider lets you run coding agents from Scala and from a command-line workflow.
Use it to inspect a repository, change code, run checks, and coordinate several
agent requests. The project is in development and currently runs from a source
checkout.

## Build and start

Install **JDK 21**, **sbt 1.11.4**, and **Python 3**, then run these commands
from the repository root:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

This opens the Scala REPL with fixed demo responses and makes no model calls.
Try ordinary Scala, then an agent request:

```scala
val task = scout("Explain what this project does")
val answer = task.run()
println(answer)
```

Type `:quit` to exit. To get answers from a model, follow
[provider setup](docs/onboarding.md#connect-a-model).

For the **tabbed TUI**, add `--tabs`:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main --tabs
```

Use `:help`, `:new work`, and `:tabs` inside it. The TUI currently manages tabs
and drafts; typing a task there does not launch an agent. Use the Scala REPL
or CLI to run agent work. [TUI commands and examples](docs/user-guide-tui.md).

## Do something useful

| Task | Guide |
| --- | --- |
| Set up Java, build Raider, and connect a model | [Getting started](docs/onboarding.md) |
| Ask an agent to fix code or review a repository | [Practical handbook](docs/user-guide.md) |
| Use tabs, drafts, and scrollback | [TUI](docs/user-guide-tui.md) |
| Write typed workflows, parallel reviews, and batches | [Scala REPL](docs/user-guide-repl.md) |
| Run repair → tests → reviews as a local CI job | [CI walkthrough](docs/user-guide-ci.md) |
| See which model and tool calls produced a result | [Reading a run](docs/user-guide-lineage.md) |

For example, the Scala REPL can run two reviews together:

```scala
val code = "if subtotalCents > 10000 then 1000 else 0"
val policy = "The 1000-cent discount applies at 10000 cents and above."
val boundary = scout(s"$policy Review this code: $code. Give a failing boundary case. No tools.")
val tests = worker(s"$policy List expected discounts for 9999, 10000 and 10001 cents. No tools.")
val (finding, testPlan) = all(boundary, tests).run()
```

With a model configured, these are two model requests. In `mock` mode they
return fixed demo text. The [Scala guide](docs/user-guide-repl.md) explains
when requests run and how to use their results.

The [CI example](docs/user-guide-ci.md) goes further: it repairs a Scala bug,
runs the compiler and boundary tests, gathers five reviews, and writes a
`READY` or `BLOCKED` decision. The guide shows how to launch it and inspect
the resulting files, including the [complete Scala projections](docs/examples/ci-pipeline/native-projections.md).

## Reference

[API](docs/api.md) · [Architecture](docs/architecture.md) ·
[Contributing](docs/contributing.md) · [Roadmap](docs/roadmap-12k.md)

Scala code and repository commands run with your process permissions. Use
workspaces you trust and keep credentials outside Git.

MIT — see [LICENSE](LICENSE).
