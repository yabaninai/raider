# Historical demo: “Raider for Scala developers” — types before tokens, fibers instead of threads

**Archive, captured 2026-10-09.** The live-model commands, transcript, timing,
and model-specific observations below record that run; they are not a current
launch recipe or a claim that those calls were repeated against today's
source. For current setup and runnable examples, use the [current launch
guide](../user-guide.md) and [verified REPL/bundle gallery](../user-guide-repl.md).

Target audience: a Scala developer who has not yet seen why they need an agent.
The main event is **not “the agent answered in text,” but that the agent API is a
typed Scala library inside a live REPL with in-process dotc 3.9**: the compiler
catches errors BEFORE a model call, fan-out uses ordinary collections over ZIO
fibers, and all agent code runs in the same JVM. Everything below was run
through `raider.repl.Main` — the interpreter responses are real.

## Act 0 — Scala's power in 90 seconds (REPL)

Launch (live Qwen):

```sh
RAIDER_PROVIDER=openai RAIDER_API_KEY=dummy RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf sbt "raiderRepl/run"
```

**1. The compiler stands between you and the token bill.** A typo in an
argument means the model is NOT called at all:

```scala
scout(123)
```

```text
-- [E007] Type Mismatch Error: -------------------------------------------------
1 |scout(123)
  |      ^^^
  |      Found:    (123 : Int)
  |      Required: String
1 error found
```

In Python agents, this kind of error becomes an HTTP 500 at 3 a.m. Here it
doesn't exist: it failed to compile.

**2. Fan-out is just a List over ZIO fibers.** Three subagents start
simultaneously, await is map, and the answers are `List[String]`, ordinary
values:

```scala
val t0 = System.nanoTime()
val jobs = List(
  scout("1: name one advantage of ZIO fibers").start(),
  scout("2: name one advantage of immutability").start(),
  scout("3: name one advantage of sealed types").start()
)
val answers = jobs.map(_.await())
val wall = (System.nanoTime() - t0) / 1000000000
```

The following live-model call was captured verbatim in the REPL echo, including
its original Russian prompt, so that the displayed expression remains an exact
record of what was run. The prompt asks for an ironic analysis of Oleg from
Positive Technologies praising Rust with the words “speed,” “safety,” and
“native,” and for an isekai anime plot about Oleg in a world of hype:

```scala
("speed", "safety", "native feel").map(p => scout(s"Oleg from Positive Technologies praises Rust as '$p' without understanding the pros and cons. Explain ironically which parts are slogans and which are true, and how Scala responds. Turn it into an isekai anime story about Oleg in a world of hype-chasing.").start()).map(_.!!).zipWithIndex.map((a, i) => s"[${i + 1}] ${a.take(60)}").mkString("\n")
```

```text
val jobs: List[raider.runtime.jobs.JobHandle[String]] = List(...)
val answers: List[String] = List(...)
val wall: Double = 0.251670125
List("speed", "safety", "native feel").map(p => scout(s"Oleg from Positive Technologies praises Rust as '$p' without understanding the pros and cons. Explain ironically which parts are slogans and which are true, and how Scala responds. Turn it into an isekai anime story about Oleg in a world of hype-chasing.").start()).map(_.!!).zipWithIndex.map((a, i) => s"[${i + 1}] ${a.take(60)}").mkString("\n")

```

No `Promise.all`, no stringly typed API: `.start()` returns
`JobHandle[String]`, and from there it is ordinary Scala code — `grouped`,
`take`, `mkString`, whatever you like. With a live model, try 5–8 tasks —
wall-clock time will show real parallelism (admission limits only LLM slots,
not you).

**3. In-process compiler.** The REPL compiles input with the same dotc 3.9 used
to build Raider — including enum/given/opaque/match type (verified examples:
`docs/demos/repl-oleg-parallel-demo.md` §4). A line for the skeptic: “an agent
written in Scala and compiled by the compiler you are using right now as a
chat.”

## Act 1 — self-hosting: the agent works on its own code

The coding agent reads its own `AgentLoop.scala` and explains admission. The
following live-run command retains its original Russian `--input` verbatim;
the prompt asks it to read `AgentLoop.scala` and explain in two sentences how
admission limits concurrent LLM calls:

```sh
RAIDER_API_KEY=dummy java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent coder --provider openai --base-url http://127.0.0.1:8081/v1 --model unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf --input "Read modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala and explain in two sentences how admission limits parallel LLM calls" --out artifacts/scala-pro-demo
```

`RAIDER_API_KEY=dummy` — llama.cpp does not check the key; the CLI requires
credential-preflight by design (a secret is never placed in argv).

The agent's actual answer (live run 2026-10-09):

```text
Admission implements a permit pool as a bounded ZIO Queue[Unit] (modelSlots),
whose size sets the maximum number of concurrent LLM calls… [translated from the recorded live response]
```

The agent's trace is typed and machine-readable (show the skeptic):

```sh
cat artifacts/scala-pro-demo/work-*/run.json | python3 -m json.tool | head -20
```

`run.json` (schema validation before writing, atomic temp+rename write),
`events.jsonl` (job-state timeline), `summary.md` (human-readable),
`transcript.json` (model-loop trace). Exit codes are typed:
0/10/20/21/22/23/24/25/26/27/130/143 — CI distinguishes “check refusal” from
“runtime failure” without parsing strings.

## Act 2 — the agent runs sbt on itself (mutating loop)

The following command keeps its original Russian prompt as a record of the
demonstrated invocation. It asks the agent to run `proc_run: sbt --batch
core/test`, then report in one paragraph how many tests passed/failed and what
the output says about failures.

```sh
RAIDER_API_KEY=dummy java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent coder --provider openai --base-url http://127.0.0.1:8081/v1 --model unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf --input "Run proc_run: sbt --batch core/test, then summarize in one paragraph how many tests passed, how many failed, and what the output shows for the failures" --out artifacts/sbt-selftest
```

`proc_run` parses sbt output — the agent sees a structured compilation and test
result, not a wall of text.

## Act 3 — REPL: parallel reconnaissance by three subagents

```sh
RAIDER_PROVIDER=openai RAIDER_API_KEY=dummy RAIDER_BASE_URL=http://127.0.0.1:8081/v1 RAIDER_MODEL=unsloth/Qwen3.8-27B-GGUF/BF16/Qwen3.8-27B-BF16-00001-of-00002.gguf sbt "raiderRepl/run"
```

Inside (one line at a time):

```scala
val a = scout.start("Read modules/core/src/main/scala/raider/core and list every enum/sealed hierarchy with its purpose, one line each")
val b = coder.start("Read modules/tools/src/main/scala/raider/tools and explain how fs_edit guarantees patch atomicity")
val c = worker.start("Read modules/provider-chat and describe in text the path of an SSE chunk from socket to ChatChunkParser")

a.await(); b.await(); c.await()
:jobs
```

The REPL itself is a real in-process Scala 3.9 compiler: what you type is
compiled by the same dotc as Raider itself. More advanced features are shown
in `docs/demos/repl-oleg-parallel-demo.md` §4 (opaque/given/union/match type —
all were run through this REPL).

## Why a Scala developer might care (the honest version)

| What the Scala developer sees | What is underneath |
| --- | --- |
| `scout(123)` does not compile | typed DSL, errors before spend (E007) |
| `List(...).map(_.start())` — fan-out | ZIO fibers, JobHandle[String], admission |
| REPL = in-process dotc 3.9 | repl-engine, the same compiler that builds Raider |
| Agent works on its own code | self-hosting: fs_read/proc_run on Raider modules |
| Typed exit codes, JSONL events | `result.scala`, artifact schema validation |
| Direct provider wire in the recorded run | OpenAI-compatible local Qwen; this does not establish Anthropic tool-call parity (RAI-127 remains open) |

## Honesty notes

- Act 0 was run through the REPL (mock prelude) — timings and types are real; numbers will be live on a live model.
- Act 1 was checked on live Qwen: `Succeeded/exit 0`, 11 rounds, artifacts `artifacts/scala-pro-demo/work-9c36a7b6/`.
- Jar built 2026-10-08; if the CLI changed, run `make raider-jar` first.
- Answer quality depends on the local model; types/parallelism/budget/artifacts are runtime properties and do not depend on the model.
- Act 2 launches a real `sbt test` — the first run includes sbt cold start.
- Do not write `for (x <- agentString)` — for over String iterates Char (the compiler will show this honestly).

worker("Put this in one verdict for Oleg: " + List("speed", "safety", "native").map(p => scout(s"Truth or slogan in Oleg's claim 'Rust = $p'? One sentence").start()).map(_.!!).mkString(" | ")).start().!!
