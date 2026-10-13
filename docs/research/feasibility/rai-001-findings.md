<!-- Translated from Russian original. Key terms preserved as-is. -->

# RAI-001 feasibility findings

Historical findings from the first run. Current verdict and independent reruns: [review 2026-10-03](../../reviews/2026-10-03-readiness.md). The full card is changes-required; 32/32 alone is insufficient because the entrypoint produced a false green.


Date: 2026-10-02. Run: `sh experiments/repl-spike/run_spike.sh` → exit 0,
32/32 checks (7 headless + 25 REPL). All artifacts: `artifacts/rai-001/`.

## Measurements (host darwin/arm64, macOS 26.5.1, JDK 20.0.1)

| Metric | Value | Source |
| --- | --- | --- |
| REPL cold start to first prompt | 1.15–1.62 s (3 runs) | repl-summary.json |
| REPL peak RSS (with compiler+JLine) | ~547–667 MiB | repl-summary.json |
| Headless cold start (normal scenario, 2.5 s work) | ~3.0 s wall | headless-harness.log |
| Headless peak RSS (ZIO runtime, without compiler) | ~103 MiB | headless-harness.log |
| Background start → prompt return | 0.34–0.46 s | SPIKE-01-start-nonblocking |

## Confirmed behavior (key transcripts)

1. **Foreground Ctrl+C** (`blocking(15000)`): REPL prints
   `Attempting to interrupt running REPL command`, throws `ThreadDeath` via
   `dotty.tools.repl.StopRepl` in the evaluating thread; the ZIO fiber is interrupted;
   the prompt returns in ~2 s; bindings are preserved (`postFg: Int = 1` PASS).
   — repl-transcript.txt.
2. **Await Ctrl+C**: `w.await` is interrupted by the same mechanism, but the job fiber stays alive
   (`w.info` → `state=running` PASS); explicit `w.cancel()` → `state=interrupted` PASS.
   Observer/owner cancellation separation is confirmed in the real REPL.
3. **Compile error preserves bindings**: `val bad: Int = "oops"` → 1 error,
   then `good * 6` → `42` PASS; the shared runtime object stays alive (`j.info` after the error).
4. **Multiline input**: ends with a blank line (`val multi = 1 + 2` → 3).
5. **Unicode**: `val uni = "project ✓"` round-trip in REPL PASS.
6. **Headless SIGTERM**: exit 143, `status=interrupted`, `signal=TERM`,
   `stepsCompleted=4` (truthful partial), child killed (`childAliveAfterCleanup=false`),
   no orphan process by PID. SIGINT: exit 130. Normal path: exit 0, `succeeded`.
7. **Classpath split**: headless classpath (11 jars) does not contain
   scala3-compiler/scala3-repl/JLine (HEADLESS-04 PASS).
8. **HTTP**: JDK HttpClient streaming; fiber interrupt after 900 ms closes the body —
   Python slow server recorded `client disconnected` (probe-http-cancelled.log,
   http-server.log).
9. **zio-json**: decode/roundtrip/negative — 3/3 PASS (probe-json.log).
10. **Missing toolchain**: REPL without the library jar fails with `NoClassDefFoundError`
    with exit 1 — explicit unavailability, not silence (repl-missing-toolchain.log).

## Limitations / open questions for follow-up cards

- JDK 21 LTS was not installed on the host: RAI-003 installs and pins Temurin 21, and repeats the compile smoke there (see ADR-015).
- Background job output interleaves with the edit buffer → single-writer renderer (RAI-019).
- `sun.misc.Signal` is a jdk.unsupported API: sufficient for the MVP runner, but the platform contract is fixed in RAI-022.
- REPL evaluation is not isolated from ThreadDeath for arbitrary user code — already documented in product-repl.md §10 (there is no promise to preserve bindings after a hard stop).
- PTY harness requirements (winsize/TERM/DA responses) are carried into the RAI-004 REPL profile.
