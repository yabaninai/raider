<!-- Translated from Russian original. Key terms preserved as-is. -->

# ADR-015: RAI-001 Feasibility — Pinned Toolchain and Adapter Findings

Status: partially confirmed candidates; revision required after the independent review on 2026-10-03. Original date: 2026-10-02.
Evidence: `artifacts/rai-001/` (run `sh experiments/repl-spike/run_spike.sh`, exit 0, 32/32 checks).

The independent [assessment](../reviews/2026-10-03-readiness.md) confirms compile and basic REPL feasibility, but identifies a false-green suite and uncovered HTTP/process/exit guarantees. The decisions below are retained as initial proposals: moving HTTP cancellation, ThreadDeath, and signal/report logic into production requires negative fixtures and a revised ADR.

## Context

RAI-001 requires actual compiler/PTY/headless proof, not claims. The spike was tested on
host darwin/arm64 (macOS 26.5.1), JDK 20.0.1 (Oracle). All versions below were actually
used in the run; inventory with sha256: `artifacts/rai-001/toolchain.json`.

## Verified Versions and Facts

| Component | Version | Observation |
| --- | --- | --- |
| Scala compiler | `org.scala-lang:scala3-compiler_3:3.9.0` | the only 3.9.x version on Central; matches the plan candidate |
| Scala REPL | `org.scala-lang:scala3-repl_3:3.9.0` | a separate artifact since 3.8+; NOT included in the compiler jar |
| Scala library | `org.scala-lang:scala-library:3.9.0` | unified library; `scala3-library_3:3.9.0` — manifest-only relocation stub (344 B) |
| JLine | 4.0.14 (terminal/reader/jni) | dependency of scala3-repl_3 |
| ZIO | `dev.zio:zio_3:2.1.26` | unsafe.run/fork, fiber interrupt, onExit — work on 3.9.0/JDK 20 |
| zio-json | `dev.zio:zio-json_3:0.10.0` | DeriveJsonCodec.gen, decode/roundtrip/negative PASS |
| JDK HttpClient | built into JDK 20.0.1 | streaming + fiber-interrupt closes the body; the server sees the disconnect |

## Decisions

1. **RAI-003 build** includes `scala3-repl_3` only in the dev/compiler distribution;
   headless distribution = zio/scala-library without compiler/JLine
   (verified: 0 contaminating jars in the headless classpath, HEADLESS-04).
2. **Launching the compiler/REPL with `java -cp`** requires explicit `-classpath`/`-usejavacp`,
   otherwise `MissingCoreLibraryException` occurs. RAI-003 sbt and the RAI-019 launcher pass
   the classpath explicitly; the official `scala` launcher does the same implicitly.
3. **REPL interruption**: dotty REPL stops evaluation through `StopRepl`
   (`java.lang.ThreadDeath` in the evaluating thread). ZIO `unsafe.run` survives this:
   the fiber is interrupted, observer-cancel `fiber.await` does NOT cancel the observed job
   (SPIKE-03-await-observer: the prompt returned, `w.info` → state=running). This is the key
   feasibility basis for the facade semantics in product-repl.md §10.
4. **HTTP client candidate** (runtime-contracts §1): JDK HttpClient + a narrow ZIO
   async adapter — accepted for RAI-011; wire parser a separate class.
5. **JSON codec candidate**: zio-json 0.10.0 — accepted for versioned schemas RAI-002
   (schema pairs are finalized there with compile fixtures).
6. **PTY harness contract** (for the RAI-004/019 REPL profile): PTY must set
   `TIOCSWINSZ` (40×120), `TERM=xterm-256color`, and respond to Primary DA
   (`ESC[c` → `ESC[?62;22c`), otherwise JLine 4 does not render the prompt. Multiline input
   REPL ends with a blank line. Responses to capability queries are part of the harness,
   not the product.
7. **Signals**: `sun.misc.Signal` (TERM/INT) → `System.exit(128+n)` + shutdown hook
   with a bounded interrupt (4s grace) — verified: 143/130, atomic write of partial run.json,
   owned child killed, no orphan by PID. RAI-022 runner retains this mechanism
   (a platform-native replacement, if needed, gets a separate card).

## Gaps and Limitations

- **JDK 21 LTS is absent on the host** (only 20.0.1 and 11.0.20). The spike ran on
  JDK 20.0.1; RAI-003 bootstrap must install/pin JDK 21 (Temurin) and
  rerun the compile smoke. This limits the evidence; it is not an automatic pass for 21.
- The REPL prints a `ThreadDeath` stack trace after foreground Ctrl+C — a cosmetic issue for
  RAI-019 (the adapter should render a clear cancellation message).
- Background fiber output interleaves with the edit buffer (confirmed by the transcript) —
  single-writer TerminalRenderer is required in RAI-019, as the contract anticipated.
- JLine draws a gray `~` status-line artifact over narrow terminals — recorded
  as an observation.
- The Windows/Linux suite was not part of the spike (darwin/arm64 only).
