# RAI-001 REPL/headless spike

Feasibility evidence for RAI-001 (not production code). One shared ZIO runtime
(`RaiderSpike`) is loaded into the real Scala 3.9.0 REPL from the compiled
classpath; the headless runner and probes work on the classpath without the
compiler/JLine.

## Run (full suite)

```sh
sh experiments/repl-spike/run_spike.sh          # fetch + compile + both harnesses
sh experiments/repl-spike/run_spike.sh --skip-fetch
```

Exit 0 ⟺ all 35 checks pass (10 headless + 25 REPL; F0 remediation added a
grandchild reproducer, artifact-failure exit 27, and before-headers cancel).
Evidence is written to `artifacts/rai-001/` (git-ignored; override with
`RAIDER_SPIKE_EVIDENCE`): transcripts, summaries, `*.raw` step logs,
`toolchain.json` (coordinates + sha256), and a source manifest inside
`environment.raw`. Fault-injection self-test:
`run_spike.sh --self-test-fault-injection`.

## Contents

| File | Purpose |
| --- | --- |
| `fetch_toolchain.py` | POM-walking resolver from repo1.maven.org, pinned roots only |
| `prelude/SpikePrelude.scala` | Shared runtime: foreground `ask`/`blocking`, background `start` → `Job(await/cancel/info)` |
| `headless/HeadlessSpike.scala` | non-TTY runner: root fiber + owned child process, SIGTERM/INT → bounded cancel + atomic partial artifact + 143/130 |
| `probe/HttpJsonProbe.scala` | JDK HttpClient + ZIO adapter (full read and fiber interrupt), zio-json derive/roundtrip/negative cases |
| `harness/repl_pty_harness.py` | PTY driver for the real REPL: SPIKE-01/02/03 (see repl-summary.json) |
| `harness/headless_harness.py` | HEADLESS-01..04 + PROBE-* (see headless-summary.json) |

Context and decisions: `docs/adr/ADR-015-rai-001-feasibility-toolchain.md`,
`docs/research/feasibility/rai-001-findings.md`, work record
`docs/work/records/RAI-001.md`.

## Important details for running the REPL on plain `java`

- `dotty.tools.repl.Main -usejavacp` is required (otherwise
  `MissingCoreLibraryException`).
- PTY: `TIOCSWINSZ` is required (for example 40×120), as are
  `TERM=xterm-256color` and a response to Primary DA `ESC[c` → `ESC[?62;22c`,
  otherwise JLine 4 will not render the prompt.
- Multiline input is terminated by a blank line.
