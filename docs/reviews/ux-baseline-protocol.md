# UX baseline protocol (RAI-070) — pinned machine, baseline without thresholds

Card RAI-070 (M0, P0.C remainder / P9 prep). Harness: `scripts/quality/ux_baseline.py`
(stdlib-only Python). Report: machine-readable JSON (`--out`, default
`artifacts/ux-baseline/ux-baseline.json`); side files are placed alongside the
report.

**Main protocol limitation: this harness does NOT claim UX01–08 thresholds**
(roadmap-12k §UX, gap matrix `2026-10-05-p0a-gap-matrix.md` §2). It records
baseline numbers — machine-clock measurements on one pinned machine — so future
cards (P1/P2/P9) have a point of reference. No number in this report is a
pass/fail result against target thresholds (UX03 p95 ≤ 250 ms, UX04 p95 ≤ 2 s
warm, etc.), and it is not human-usability evidence. Card non-goals: human
usability sessions, threshold claims, CI requirement.

## 1. Pinning the machine

“Pinned machine = this macOS”: parameters are fixed in EACH report
(`environment`), not in this document — a number is meaningful only with its
own run. `environment` includes: `java -version` (first line), sbt (version
from `project/build.properties` + runner line), environment `TERM`, CPU count,
platform/hostname, Python, load average (1m/5m/15m), timestamp, and **repo
fingerprint** — sha256 over the inventory of the whole repository with the
SAME exclusions as the quality runner (`target/`, `artifacts/`, `.git/`,
`.opencode/`, …; see `environment.repo_fingerprint.excludes`). Source changes
after a run invalidate evidence linkage — a new run is required.

Run pins (samples, timeouts, child TERM, provider=mock) are in
`environment.pins`; changing them makes runs not directly comparable.

## 2. What is measured

### 2.1 `measurements.ux03_event_visible_latency` — event→visible (UX03 prototype)

Real REPL (`java -cp <classpath> raider.repl.Main`) under a harness-owned PTY:
winsize 40×120, ISIG disabled, Primary-DA requests answered by JLine,
`NO_COLOR=1`, child `TERM=xterm-256color` (independent of ambient TERM),
isolated HOME, provider=mock. Technique matches
`scripts/quality/repl_pty_smoke.py` / `renderer_pty_probe.py`.

Sample: a unique token `z000`…`zNNN` is written to the PTY master (unique so
echo cannot be confused with a prior occurrence); the clock (`monotonic_ns`)
stops when the token echo appears in the ANSI-stripped output stream. The line
is then erased with backspaces (the REPL does not eval it — session stays
clean). Default N=100 (`--samples`); budget per sample 5 s. Recorded: n,
p50/p95/max/min/mean, **stddev (population)** and raw `samples_ms` (rounded to
3 decimal places); percentiles use linear interpolation (renderer-spike rule).
A sample timeout is not passed: status `partial` with `failed_samples` > 0;
complete failure is `failed`.

### 2.2 `measurements.ux04_repl_start` — REPL startup (UX04 prototype)

One iteration is a pair of launches: **cold** — first `java -cp
<repl-test-classpath> raider.repl.Main` until prompt `raider> ` appears in the
visible stream (PTY, 30 s timeout, as in the card); **warm** — the immediately
following second launch. Default 5 iterations (`--start-samples`) → 5 cold + 5
warm; p50/p95, stddev, and raw samples (in seconds) are recorded separately.

Honest metric limitations: warm here means warm OS page caches (class
files/libraries are cached) for each newly launched JVM process; it is NOT a
preloaded server or JIT warm-up of one process. The REPL “banner” is the first
visible prompt; the process prints no separate banner.

## 3. Classpath and honest skip

Classpath resolution order (`classpath.source` in the report):

1. `RAIDER_REPL_CP` (env, as in `repl_pty_smoke.py`);
2. successful cache `<out>.classpath-cache.txt` (beside the report);
3. `sbt --batch --error export raiderRepl/runtime:fullClasspath`
   (budget `UX_SBT_TIMEOUT_S`, default 900 s; `COURSIER_CACHE` is passed
   through as-is — the measurements themselves do not use the network).

If the classpath is unavailable, UX measurements receive status
`"skipped: no classpath (documented skip, not passed)"` with a specific reason —
**documented skip is not passed**; a valid JSON report with environment + skips
is still written (exit 0). The sbt failure reason is cached in
`<out>.classpath-fail.json` (determinism on repeat runs); retry with
`--refresh-classpath`; disable sbt entirely with `--no-sbt`.

## 4. How to repeat

```bash
# single run (report + environment + baseline)
python3 scripts/quality/ux_baseline.py \
  --out /path/to/evidence/ux-baseline.json          # exit 0 if JSON was written

# JSON validity
python3 -m json.tool /path/to/evidence/ux-baseline.json >/dev/null

# repeat run (idempotent: same side-file names are overwritten)
python3 scripts/quality/ux_baseline.py --out /path/to/evidence/ux-baseline.json
```

### UXB-01 — JSON baseline with environment parameters

One run: exit 0, `environment` populated (java/sbt/TERM/cpu_count/
timestamp/repo_fingerprint), `tests.UXB-01.status = "pass"`.

### UXB-02 — unsupported terminal: explicit record, not silent skip

```bash
TERM=unknown-term python3 scripts/quality/ux_baseline.py \
  --out /path/to/evidence/ux-baseline-uxb02.json    # exit 0
```

The report must contain: `terminal.status = "unsupported"`,
`terminal.supported = false`, `terminal_unsupported = true`, and a finding that
explains this — lack of support is explicitly recorded, not silently skipped.
PTY children continue to use probe-owned `TERM=xterm-256color` (ambient TERM
affects only the environment record). Classification: exact match or prefix
of known families (xterm*, screen*, tmux*, vt100*, dumb, …); `TERM=dumb` is
supported-limited (RENDER-03: plain fallback works).

### UXB-03 — repeat run is stable (variance recorded)

Two consecutive runs per §4. (1) Variance: each measurement records
`stddev_ms` (population) + raw samples. (2) Structural identity: recursive key
sets match (values and timestamps naturally differ):

```bash
python3 - <<'EOF'
import json, sys
def keys(o, p=""):
    ks = set()
    if isinstance(o, dict):
        for k, v in o.items():
            ks.add(f"{p}.{k}"); ks |= keys(v, f"{p}.{k}")
    elif isinstance(o, list):
        for v in o: ks |= keys(v, f"{p}[]")
    return ks
a = keys(json.load(open(sys.argv[1])))
b = keys(json.load(open(sys.argv[2])))
print("STRUCTURE_IDENTICAL" if a == b else
      f"DIFF only_a={sorted(a-b)} only_b={sorted(b-a)}")
EOF
```

## 5. Exit codes and idempotency

- exit 0 — report written (skips and honest `failed`/`partial` statuses are
  allowed and recorded in JSON); a skipped measurement is never marked passed;
- exit 2 — report could not be written;
- side files: `<out>.classpath-cache.txt`, `<out>.classpath-fail.json`,
  `<out>-transcripts/` (PTY session transcripts), `<out>.tmp` → atomic
  `os.replace`. A repeated run deterministically overwrites them.
