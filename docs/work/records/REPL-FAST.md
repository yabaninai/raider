<!-- Work record. Original in Russian; key results in English below. -->

# REPL-FAST (W10-subset): work record

Status: done (fixture/mock backend; honest obligations listed). Date: 2026-10-03.
Baseline: root checkout without commits (all files staged, main has no history);
previous last-passing gate: artifacts/quality/20261003-151233 (board).

## Resulting behavior

`sbt --batch "raiderRepl/runMain raider.repl.Main"` starts an interactive
REPL, in which the real compiler Scala 3.9.0 (pinned `scala3-repl_3` +
JLine 4.0.14) executes input in-process:

- val/def/case class, imports and multiline input (continuation on
  unclosed parentheses/brackets/`=`; an empty line always submits the buffer);
- compile error PRESERVES bindings, prompt returns (W10-subset);
- agent facade: `scout.ask("...")`, `val j = worker.start("...")`,
  `j.await()` (idempotent), `j.cancel()`, `j.watch()` — on top of
  ScriptedModelBackend, NO network; placeholder `j.send` — typed refusal RA-CAP;
- commands `:help :jobs :cancel <id> :reset :quit`;
- non-TTY mode: piped stdin is read directly (dumb-free pipe reader);
- PTY-mode: JLine line editor, Ctrl+C clears pending buffer.

Key integration architecture findings (reproduced, not theory):

1. **Static object split**: in-process REPL classloader
   (`AbstractFileClassLoader`) loads classpath classes SELF-FIRST →
   `FacadeOps$` exists in TWO instances (console world and interpreter world). Therefore all REPL state (session, job-handle registry)
   lives in interpreter world; console communicates only through eval
   `ReplCommands.print*` (captured stdout = repr, user resN numbering is not shifted). Probe: APP `FacadeOps$@6913c1fb` ≠ REPL `@5502f74c`.
2. **`:reset` = driver recreation**: state-only reset resets counter
   names `rs$line$N`, but old classes remain in the classloader → collisions
   (after reset `scout.ask` returned `String = ()`). A new `ReplDriver`
   provides a clean namespace.
3. **sbt launcher consumes stdin**: homebrew sbt script with `--batch` does
   `exec </dev/null` (line 703) — piped input does not reach the JVM at all.
   Non-TTY acceptance uses direct `java -cp` (classpath via
   `sbt --batch --error "export raiderRepl/runtime:fullClasspath"`).
4. **Ctrl+C in JLine 4 is not bound** (abort by default on Ctrl+G; with ISIG
   `\x03` becomes SIGINT and bypasses keymap): product explicitly binds
   `\u0003 → Reference("abort")`; PTY test disables ISIG on the slave to test the product binding itself.
5. Tests using the real compiler require `Test / fork := true`: inside
   sbt-JVM `java.class.path` = only sbt-launch → `-usejavacp` cannot see the classpath.

## Changed paths

New modules (root aggregate):

- `modules/repl-engine/` — `ReplEngine` (frozen trait: eval/completions/
  reset, EvalResult enum) + `DottyReplEngine` (ReplDriver wiring, classification
  Value/CompileError/RuntimeFailure, reset=rebuild) + `ReplEngineSpec` 6/6.
- `modules/repl/` — facade (`ReplSession`, `FacadeOps`: extension
  ask/start/await/send/cancel/watch with `using ReplSession`; `ReplCommands`:
  console text-entry points) + `Main` (pipe/tty loops, multiline, prelude,
  banner) + `Commands` (`:help/:jobs/:cancel/:reset/:quit`; `:type` — honest
  refusal) + `FacadeOpsSpec` 6/6 (sequential — shared global registry).

Changed:

- `build.sbt`: raiderReplEngine (scala3-repl 3.9.0, jline-terminal/reader/jni
  4.0.14, `Test / fork`), raiderRepl (deps core+runtime+dsl+testkit+replEngine,
  `run / fork`, `Test / fork`); both in root aggregate/dependsOn; zio-streams in
  raiderRuntime (zio-streams was already transitive through zio-json in core).
- `modules/runtime/.../loop/Runner.scala`: THE ONLY execution point
  `runText(backend, model, messages, ceilings, admission)` — admission slot +
  reserve (Unknown=unpriced, never free) → stream → collect TextDelta until
  Finished; Failed → typed error; no Finished → StreamProtocol. No tools/retries.
- `scripts/build/compile.sh`: + scala3-repl/jline jars (and compiler jars for
  TASTy references) in compile-classpath gate compile-fixtures.
- `scripts/quality/repl_smoke.sh`, `scripts/quality/repl_pty_smoke.py`: new
  acceptance harnesses (see evidence).

sha256 (final sources):

```
fd495f13…  build.sbt
29c88ca2…  scripts/build/compile.sh
14382974…  scripts/quality/repl_smoke.sh
25a85825…  scripts/quality/repl_pty_smoke.py
72b3c6a0…  modules/repl-engine/src/main/scala/raider/repl/engine/DottyReplEngine.scala
799f4b7f…  modules/repl-engine/src/main/scala/raider/repl/engine/ReplEngine.scala
58db03fd…  modules/repl-engine/src/test/scala/raider/repl/engine/ReplEngineSpec.scala
88909526…  modules/repl/src/main/scala/raider/repl/Commands.scala
e8919b92…  modules/repl/src/main/scala/raider/repl/Main.scala
fdd2b577…  modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala
6dd07032…  modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala
99592566…  modules/repl/src/main/scala/raider/repl/facade/ReplSession.scala
d17289e1…  modules/repl/src/test/scala/raider/repl/facade/FacadeOpsSpec.scala
88338a6a…  modules/runtime/src/main/scala/raider/runtime/loop/Runner.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Phase 0 (contracts compile) | sbt compile green before agents started | sbt output, 15:37 |
| Agent-A: engine val/def/case class persist between evals; compile error preserves bindings; reset clears state | 6/6 (after fixes: DiagnosticHeader `[E###]`, fork, reset=rebuild) | `raiderReplEngine/test` ×N |
| Agent-B: facade ask/start/await/registration/cancel-terminal/send-refusal | 6/6 ×3 stable runs (after `@@ TestAspect.sequential`) | `raiderRepl/test` |
| Full sbt test | all modules green (core 5+13+26, dsl 10, testkit 3, runtime 33, tools 19, engine 6, facade 6) | `sbt --batch test` |
| Quality gates (fast, full-tree mode) | 6/6 passed | artifacts/quality/20261003-204841/ |
| Non-TTY smoke: `printf 'val x = 1\nx\n:quit\n'` + ask/start/await/:jobs | exit 0; val x: Int = 1; res0: Int = 1; scripted answer; j_1 Succeeded; bye | scripts/quality/repl_smoke.sh → artifacts/repl-fast/nontty-transcript.txt |
| PTY smoke (real java process, not mock) | 13/13 checks, findings=[] | scripts/quality/repl_pty_smoke.py → artifacts/repl-fast/pty-summary.json, pty-transcript.txt |

Demo (non-TTY transcript, shortened):

```
raider> val x = 1
val x: Int = 1
raider> x
val res0: Int = 1
raider> scout.ask("ping")
val res1: String = "[scripted-fixture] mock answer (ScriptedModelBackend; no live network in this slice)"
raider> val j = worker.start("bg")
val j: raider.runtime.jobs.JobHandle[String] = ...
raider> j.await()
val res2: String = "[scripted-fixture] mock answer ..."
raider> :jobs
j_1  Succeeded  worker: bg
raider> :quit
bye
```

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | console |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderReplEngine/test"` | 0 (after fixes) | console |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRepl/test"` ×3 | 0,0,0 | console |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | console |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261003-204841/manifest.json |
| `sh scripts/quality/repl_smoke.sh` | 0 | artifacts/repl-fast/nontty-*.txt |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 | artifacts/repl-fast/pty-summary.json |

## Limitations / blockers

- **Live-wire not a goal of this prompt**: only ScriptedModelBackend (fixture
  marked in banner and transcripts). Providers, streaming coalescing,
  journal — obligations, not “done”.
- `j.send` (job steering) — typed RA-CAP refusal, obligation in board.
- `:type`, completion (`completions()` = Nil), journal — obligations.
- Runner: without tools/retries (RAI-010 loop — next layer); ceilings
  parameter is validated, but accounting is handled by admission (its limits).
- Heuristic multiline — char-count by bracket count (not a lexer); a line with an unmatched bracket inside a literal is submitted by an empty line.
- `:reset` loses access to previously issued job handles in `:jobs` (fibers
  continue to completion, but the new-world registry is empty — the command reports this accurately).
- One sbt process at a time (build-lock); parallel mutable builds
  are prohibited — agent runs were serialized by the lock.
- Infrastructure note: 1 of 2 Task agents (A) died mid-stream (local
  llama.cpp: "stream interrupted"); coordinator completed its draft.
- The gate `core-contracts-spec`/`compile-fixtures`/`plan-validate` was not
  retroactively rebound to this record: evidence 20261003-204841
  captured from the final tree.

## Review and next task

Independent review of this record was not conducted (coordinator session).
Next ready layers: RAI-010.a native agent loop (freeze → Admission →
stream → tool rounds) on top of Runner.runText; live-wire transport — after
RAI-011/012; W-card heading — per docs/working-product.md.
