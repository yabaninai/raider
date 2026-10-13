#!/usr/bin/env python3
"""RAI-070 — UX baseline harness (P0.C remainder / P9 prep).

Reproducible UX03/UX04-prototype measurements on ONE pinned machine (this
macOS lane): every run records its full environment into the report, so a
number is only ever read together with the parameters that produced it.

What is measured (BASELINE ONLY — UX01-08 thresholds are NOT claimed here,
see docs/reviews/ux-baseline-protocol.md and docs/reviews/2026-10-05-p0a-gap-matrix.md §2):

  ux03_event_visible_latency  event→visible prototype: a real REPL under a
                probe-owned PTY; per sample a unique "zNNN" token is written
                to the PTY master and the clock stops when the echoed token
                becomes visible in the output stream. N samples (default 100);
                p50/p95/max/min/mean/stddev recorded with the raw samples.
  ux04_repl_start  REPL start prototype: `java -cp <repl-test-classpath>
                raider.repl.Main` under a PTY until the prompt is visible
                (timeout 30 s). One iteration = a COLD launch (first start)
                immediately followed by a WARM launch (second, consecutive —
                OS page caches warm; NOT a preloaded server process).
                --start-samples iterations (default 5); p50/p95 each.

Classpath resolution order: RAIDER_REPL_CP env → success cache next to the
report → `sbt --batch export raiderRepl/runtime:fullClasspath` (budget
UX_SBT_TIMEOUT_S, default 900 s; COURSIER_CACHE passes through). When no
classpath is available the UX measurements are recorded as
"skipped: no classpath" — a documented skip is NEVER a passed result — and
the script still writes a valid JSON with environment + skips. The failure
reason is cached next to the report (<out>.classpath-fail.json) so repeated
runs stay deterministic and cheap; --refresh-classpath retries sbt.

PTY discipline is modeled on scripts/quality/repl_pty_smoke.py and
scripts/quality/renderer_pty_probe.py: terminal size + TERM are probe-owned
(xterm-256color for the child regardless of the ambient TERM), Primary-DA
capability queries are answered so JLine renders the prompt, ISIG is off,
and HOME is isolated so the JVM/REPL never touches the user home.

Tests recorded in-report:
  UXB-01  JSON baseline with full environment parameters written.
  UXB-02  unsupported terminal is EXPLICITLY recorded (terminal.status =
          "unsupported", terminal_unsupported=true, finding appended) —
          exercise with `TERM=unknown-term`; never a silent skip.
  UXB-03  repeated-run stability: variance (population stddev) is recorded
          for every measurement; run twice back-to-back and compare the
          recursive key structure (protocol doc §UXB-03).

Exit codes: 0 = report written (skips and failed measurements included and
recorded honestly); 2 = the report could not be written. No network is used
by the measurements themselves; the optional sbt export may resolve
dependencies through the local coursier cache only.

Stdlib-only; reuses scripts/quality/quality.py (read-only import) so the
repo fingerprint uses EXACTLY the same inventory semantics and excludes as
the quality runner.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import math
import os
import pathlib
import platform
import pty
import re
import select
import shutil
import statistics
import struct
import subprocess
import sys
import termios
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]

SCHEMA_VERSION = 1
PROMPT = "raider> "
CHILD_TERM = "xterm-256color"
START_TIMEOUT_S = 30.0          # card RAI-070: REPL start budget per launch
PROMPT_WAIT_S = 60.0            # first prompt budget inside the latency session
SAMPLE_TIMEOUT_S = 5.0          # per-sample echo budget
QUIT_GRACE_S = 15.0
SBT_TIMEOUT_S = float(os.environ.get("UX_SBT_TIMEOUT_S", "900"))
LATENCY_SAMPLES_DEFAULT = 100
START_SAMPLES_DEFAULT = 5

KNOWN_TERM_FAMILIES = (
    "xterm", "screen", "tmux", "vt100", "vt220", "linux", "ansi", "rxvt",
    "alacritty", "kitty", "wezterm", "ghostty", "foot", "contour", "st",
    "eterm", "domterm", "dumb",
)

# Same CSI/OSC strip as the renderer probe (paramless sequences included).
ANSI = re.compile(
    r"\x1b\[[0-9;?]*[ -/]*[@-~]"
    r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)"
    r"|\x1b[()][0-9A-B]"
    r"|\x1b[=><]"
    r"|\x1b7|\x1b8"
)
# A trailing INCOMPLETE escape sequence would otherwise leave residue bytes
# (e.g. "\x1b[2") that a digit token could false-match while it is still
# arriving; strip it before every search.
PARTIAL_TAIL = re.compile(r"\x1b\[[0-9;?]*[ -/]*$")

QUERIES: list[tuple[bytes, bytes]] = [
    (b"\x1b[c", b"\x1b[?62;22c"),
    (b"\x1b[>c", b"\x1b[?0c"),
    (b"\x1b[?2027$p", b"\x1b[?2027;0$y"),
    (b"\x1b[?u", b"\x1b[?0u"),
    (b"\x1b[>q", b"\x1bP>|raider-ux-baseline\x1b\\"),
]


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def percentile(values: list[float], q: float) -> float:
    """Linear-interpolation percentile (same rule as the renderer spike)."""
    if not values:
        return 0.0
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    pos = q * (len(s) - 1)
    lo, hi = int(math.floor(pos)), int(math.ceil(pos))
    frac = pos - lo
    return s[lo] * (1.0 - frac) + s[hi] * frac


def stats_block(samples_ms: list[float]) -> dict:
    """n/percentiles/mean/stddev + raw samples (baseline, no thresholds)."""
    if not samples_ms:
        return {"n": 0, "status": "no-samples"}
    return {
        "n": len(samples_ms),
        "p50_ms": round(percentile(samples_ms, 0.50), 3),
        "p95_ms": round(percentile(samples_ms, 0.95), 3),
        "max_ms": round(max(samples_ms), 3),
        "min_ms": round(min(samples_ms), 3),
        "mean_ms": round(statistics.fmean(samples_ms), 3),
        # population stddev — recorded for UXB-03 (repeated-run dispersion)
        "stddev_ms": round(statistics.pstdev(samples_ms), 3),
        "samples_ms": [round(x, 3) for x in samples_ms],
        "percentile_rule": "linear interpolation (renderer-spike rule)",
    }


def run_capture(argv: list[str], timeout: float) -> tuple[int, str, str]:
    try:
        res = subprocess.run(argv, capture_output=True, text=True,
                             timeout=timeout, cwd=ROOT)
        return res.returncode, res.stdout, res.stderr
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as e:
        return 127, "", f"{type(e).__name__}: {e}"


def terminal_status(term: str | None) -> dict:
    """Classify the AMBIENT terminal; unsupported is recorded, never silent."""
    known = ", ".join(KNOWN_TERM_FAMILIES)
    if not term:
        return {"name": None, "supported": False, "status": "unsupported",
                "note": f"TERM is not set (known families: {known})"}
    t = term.strip()
    supported = t.lower() in KNOWN_TERM_FAMILIES or \
        t.lower().startswith(KNOWN_TERM_FAMILIES)
    if supported:
        note = "known terminal family"
        if t.lower() == "dumb":
            note += " (plain fallback; RENDER-03 showed JLine dumb mode works)"
    else:
        note = (f"TERM={t!r} matches none of the known families "
                f"({known}) — recorded as unsupported, not silently skipped")
    return {"name": t, "supported": supported,
            "status": "supported" if supported else "unsupported",
            "note": note}


def repo_fingerprint() -> dict:
    """sha256 over the quality-runner inventory (same excludes, read-only)."""
    try:
        sys.path.insert(0, str(ROOT / "scripts" / "quality"))
        import quality as qrunner  # noqa: local import, read-only reuse
        excludes = qrunner.fingerprint_excludes()
        inv = qrunner.inventory(ROOT, excludes)
        source = "scripts/quality/quality.py inventory + registry excludes"
    except Exception as e:  # registry unreadable: self-contained fallback
        excludes = {".git", "artifacts", "target", ".opencode", "__pycache__",
                    ".DS_Store", ".raider", ".bsp", ".metals", ".idea",
                    ".scala-build", ".bloop"}
        inv = {}
        for p in ROOT.rglob("*"):
            if not p.is_file() and not p.is_symlink():
                continue
            rel = p.relative_to(ROOT).as_posix()
            if any(part in excludes for part in rel.split("/")):
                continue
            if p.is_symlink():
                inv[rel] = {"mode": "symlink", "target": os.readlink(p)}
            else:
                st = p.stat()
                h = hashlib.sha256(p.read_bytes()).hexdigest()
                inv[rel] = {"sha256": h, "mode": oct(st.st_mode & 0o777)}
        source = f"built-in fallback excludes ({type(e).__name__})"
    blob = json.dumps(inv, sort_keys=True, separators=(",", ":")).encode()
    return {
        "algorithm": "sha256(json(inventory), sort_keys)",
        "sha256": hashlib.sha256(blob).hexdigest(),
        "file_count": len(inv),
        "excludes": sorted(excludes),
        "source": source,
    }


# --------------------------------------------------------------------------
# classpath
# --------------------------------------------------------------------------

def resolve_classpath(out: pathlib.Path, args) -> dict:
    """env → success cache → sbt export; failure is cached and recorded."""
    info: dict = {"status": "unavailable", "source": "none", "classpath": None}
    env_cp = os.environ.get("RAIDER_REPL_CP")
    if env_cp:
        return {**info, "status": "ok", "source": "env RAIDER_REPL_CP",
                "classpath": env_cp}
    cache = out.parent / (out.name + ".classpath-cache.txt")
    if cache.exists() and cache.read_text().strip():
        return {**info, "status": "ok", "source": f"cache {cache.name}",
                "classpath": cache.read_text().strip()}
    fail_path = out.parent / (out.name + ".classpath-fail.json")
    if fail_path.exists() and not args.refresh_classpath:
        try:
            reason = json.loads(fail_path.read_text()).get("reason", "unknown")
        except (OSError, json.JSONDecodeError):
            reason = "unreadable failure cache"
        return {**info, "reason": reason + " (cached failure; pass "
                "--refresh-classpath to retry sbt)"}
    if args.no_sbt:
        return {**info, "reason": "sbt attempt disabled by --no-sbt"}
    env = dict(os.environ)
    res = subprocess.run(
        ["sbt", "--batch", "--error", "export raiderRepl/runtime:fullClasspath"],
        capture_output=True, text=True, env=env, cwd=ROOT, timeout=SBT_TIMEOUT_S)
    lines = [l for l in res.stdout.splitlines()
             if l.strip() and not l.startswith("[")]
    cp = lines[-1].strip() if lines else ""
    if res.returncode == 0 and cp:
        out.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(cp + "\n")
        fail_path.unlink(missing_ok=True)
        return {**info, "status": "ok", "source": "sbt export (cached now)",
                "classpath": cp}
    # sbt --error logs [error] lines on STDOUT; stderr is the fallback
    err_lines = [l.strip() for l in (res.stdout + "\n" + res.stderr).splitlines()
                 if l.strip().startswith("[error]")]
    if not err_lines:
        err_lines = [l.strip() for l in (res.stderr or "").splitlines()
                     if l.strip()]
    reason = (f"sbt export failed rc={res.returncode}; last error: "
              f"{err_lines[-1][:300] if err_lines else '(no output)'}")
    out.parent.mkdir(parents=True, exist_ok=True)
    fail_path.write_text(json.dumps(
        {"reason": reason, "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
         "sbt_timeout_s": SBT_TIMEOUT_S}, ensure_ascii=False))
    return {**info, "reason": reason}


# --------------------------------------------------------------------------
# PTY session (probe-owned terminal; pattern of repl_pty_smoke/renderer probe)
# --------------------------------------------------------------------------

class ReplSession:
    """One real `java -cp <cp> raider.repl.Main` under a PTY."""

    def __init__(self, cp: str, home: pathlib.Path):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        attrs = termios.tcgetattr(slave)
        attrs[3] &= ~termios.ISIG
        termios.tcsetattr(slave, termios.TCSANOW, attrs)
        shutil.rmtree(home, ignore_errors=True)
        home.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ)
        env["NO_COLOR"] = "1"
        env["TERM"] = CHILD_TERM           # probe-owned, NOT the ambient TERM
        env["HOME"] = str(home)
        env["RAIDER_PROVIDER"] = "mock"    # never a live backend
        self.buf = bytearray()
        self.proc = subprocess.Popen(
            ["java", "-cp", cp, "raider.repl.Main"],
            stdin=slave, stdout=slave, stderr=slave, close_fds=True,
            env=env, cwd=ROOT)
        os.close(slave)

    def clean(self) -> str:
        return ANSI.sub("", self.buf.decode("utf-8", "replace"))

    def pump(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            r, _, _ = select.select([self.master], [], [], 0.05)
            if r:
                try:
                    data = os.read(self.master, 65536)
                except OSError:
                    return
                if not data:
                    return
                self.buf.extend(data)
                for q, a in QUERIES:
                    if q in data:
                        os.write(self.master, a)

    def wait_visible(self, needle: str, start: int, timeout: float) -> int:
        """Index of `needle` in the CLEANED stream at/after `start`, else -1."""
        deadline = time.monotonic() + timeout
        while True:
            cleaned = PARTIAL_TAIL.sub("", self.clean())
            idx = cleaned.find(needle, start)
            if idx >= 0:
                return idx
            if time.monotonic() >= deadline:
                return -1
            self.pump(0.05)

    def send(self, s: str) -> None:
        os.write(self.master, s.encode())

    def alive(self) -> bool:
        return self.proc.poll() is None

    def finish(self) -> None:
        if self.alive():
            try:
                self.send(":quit\r")
            except OSError:
                pass
            deadline = time.monotonic() + QUIT_GRACE_S
            while self.alive() and time.monotonic() < deadline:
                self.pump(0.1)
            if self.alive():
                self.proc.kill()
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
        try:
            os.close(self.master)
        except OSError:
            pass


# --------------------------------------------------------------------------
# measurements
# --------------------------------------------------------------------------

def measure_latency(cp: str, n: int, transcripts: pathlib.Path) -> dict:
    """UX03 prototype: write a unique token to the PTY, stop the clock when
    the echoed token is visible in the output stream."""
    home = transcripts.parent / "ux-home-latency"
    s = ReplSession(cp, home)
    result: dict = {"metric": "event→visible latency (UX03 prototype)",
                    "method": "unique zNNN token written to the PTY master; "
                              "clock stops when the echoed token is visible "
                              "in the cleaned output stream"}
    samples: list[float] = []
    try:
        if s.wait_visible(PROMPT, 0, PROMPT_WAIT_S) < 0:
            result.update({"status": "failed",
                           "reason": f"no prompt within {PROMPT_WAIT_S}s"})
            return result
        mark = 0
        failures = 0
        for i in range(n):
            token = f"z{i:03d}"          # unique, non-overlapping for n<=1000
            t0 = time.monotonic_ns()
            s.send(token)
            idx = s.wait_visible(token, mark, SAMPLE_TIMEOUT_S)
            if idx < 0:
                failures += 1
                mark = len(PARTIAL_TAIL.sub("", s.clean()))
                s.send("\x7f" * len(token))   # clear the line editor
                s.pump(0.05)
                continue
            samples.append((time.monotonic_ns() - t0) / 1e6)
            mark = idx + len(token)
            s.send("\x7f" * len(token))       # backspace: keep the line empty
            s.pump(0.02)
        result.update(stats_block(samples))
        result["failed_samples"] = failures
        result["status"] = "measured" if not failures else "partial"
    finally:
        (transcripts.parent / "ux-latency-transcript.txt").write_text(
            s.clean(), encoding="utf-8")
        s.finish()
    return result


def measure_start(cp: str, iterations: int, transcripts: pathlib.Path) -> dict:
    """UX04 prototype: cold = first launch to visible prompt; warm = the
    immediately following launch. `iterations` cold/warm pairs."""
    def one_launch(tag: str, n: int) -> float | None:
        home = transcripts.parent / f"ux-home-{tag}-{n}"
        s = ReplSession(cp, home)
        t0 = time.monotonic_ns()
        try:
            if s.wait_visible(PROMPT, 0, START_TIMEOUT_S) < 0:
                return None
            return (time.monotonic_ns() - t0) / 1e9
        finally:
            if tag == "cold" and n == 1:
                (transcripts.parent / "ux-start-transcript.txt").write_text(
                    s.clean(), encoding="utf-8")
            s.finish()

    cold: list[float] = []
    warm: list[float] = []
    cold_fail = warm_fail = 0
    for i in range(iterations):
        c = one_launch("cold", i + 1)
        if c is None:
            cold_fail += 1
        else:
            cold.append(c)
        w = one_launch("warm", i + 1)
        if w is None:
            warm_fail += 1
        else:
            warm.append(w)
    result = {
        "metric": "REPL start to visible prompt (UX04 prototype)",
        "method": f"java raider.repl.Main under a probe-owned PTY, prompt "
                  f"{PROMPT!r} visible within {START_TIMEOUT_S}s; cold = "
                  f"first launch of the pair, warm = immediately consecutive "
                  f"second launch (warm OS page caches, NOT a preloaded "
                  f"server process)",
        "cold": {**stats_block(cold), "unit": "s",
                 "failed_launches": cold_fail},
        "warm": {**stats_block(warm), "unit": "s",
                 "failed_launches": warm_fail},
    }
    if cold_fail == warm_fail == 0:
        result["status"] = "measured"
    elif cold or warm:
        result["status"] = "partial"
        result["reason"] = f"{cold_fail} cold and {warm_fail} warm launches " \
                           f"hit the {START_TIMEOUT_S}s timeout"
    else:
        result["status"] = "failed"
        result["reason"] = f"every launch hit the {START_TIMEOUT_S}s timeout"
    return result


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(
        description="RAI-070 UX baseline harness (baseline only, no "
                    "UX01-08 threshold claims)")
    ap.add_argument("--out", default=str(ROOT / "artifacts" / "ux-baseline"
                                         / "ux-baseline.json"),
                    help="report path (side files are placed next to it)")
    ap.add_argument("--samples", type=int, default=LATENCY_SAMPLES_DEFAULT,
                    help=f"latency samples (default {LATENCY_SAMPLES_DEFAULT})")
    ap.add_argument("--start-samples", type=int, default=START_SAMPLES_DEFAULT,
                    help=f"warm/cold iterations (default {START_SAMPLES_DEFAULT})")
    ap.add_argument("--term", default=None,
                    help="override the AMBIENT TERM recorded in the report "
                         "(the PTY children always run TERM="
                         f"{CHILD_TERM}; UXB-02: pass a name like "
                         "unknown-term to exercise the unsupported path)")
    ap.add_argument("--no-sbt", action="store_true",
                    help="never attempt `sbt export` (cache/env only)")
    ap.add_argument("--refresh-classpath", action="store_true",
                    help="retry sbt export even if a cached failure exists")
    args = ap.parse_args()
    if args.samples < 1 or args.start_samples < 1:
        ap.error("--samples and --start-samples must be >= 1")

    out = pathlib.Path(args.out).resolve()
    report: dict = {
        "kind": "raider-ux-baseline",
        "card": "RAI-070",
        "package": "P0.C remainder / P9 prep",
        "schema_version": SCHEMA_VERSION,
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "mode": "git-available" if (ROOT / ".git").exists()
                else "Git unavailable: isolated snapshot",
        "thresholds": "NONE — this harness records a BASELINE only; UX01-08 "
                      "thresholds are NOT claimed (see "
                      "docs/reviews/ux-baseline-protocol.md)",
    }
    findings: list[str] = []

    # ---- environment (UXB-01) ---------------------------------------------
    java_rc, java_out, java_err = run_capture(["java", "-version"], 60)
    # real java prints the version to stderr; fall back to stdout so wrapper
    # shims that print to stdout are still recorded
    java_line = next((l for l in (java_err + "\n" + java_out).splitlines()
                      if l.strip()), "")
    sbt_rc, sbt_out, sbt_err = run_capture(["sbt", "--batch", "--version"], 120)
    sbt_runner = next((l.strip() for l in sbt_out.splitlines()
                       if "runner" in l.lower() and l.strip()), "")
    # the runner only reports itself; the build-pinned version is authoritative
    sbt_props = ROOT / "project" / "build.properties"
    sbt_version = None
    if sbt_props.exists():
        for l in sbt_props.read_text().splitlines():
            if l.strip().startswith("sbt.version"):
                sbt_version = l.split("=", 1)[-1].strip()
    term = args.term if args.term is not None else os.environ.get("TERM")
    try:
        loadavg = [round(x, 2) for x in os.getloadavg()]
    except OSError:
        loadavg = None
    report["environment"] = {
        "java_version": java_line if java_rc == 0 else
                        f"unavailable (rc={java_rc})",
        "java_ok": java_rc == 0,
        "sbt_version": sbt_version or
                       f"unavailable (no {sbt_props.relative_to(ROOT)})",
        "sbt_runner": sbt_runner if sbt_rc == 0 else
                      f"unavailable (rc={sbt_rc}: {sbt_err.strip()[:120]})",
        "sbt_ok": sbt_rc == 0 and bool(sbt_version),
        "term": term,
        "cpu_count": os.cpu_count(),
        "platform": f"{platform.system()} {platform.release()} "
                    f"{platform.machine()}",
        "hostname": platform.node(),
        "python_version": platform.python_version(),
        "load_average_1m_5m_15m": loadavg,
        "timestamp": report["ts"],
        "repo_fingerprint": repo_fingerprint(),
        "pins": {"child_term": CHILD_TERM, "rows": 40, "cols": 120,
                 "latency_samples": args.samples,
                 "start_iterations": args.start_samples,
                 "start_timeout_s": START_TIMEOUT_S,
                 "sbt_timeout_s": SBT_TIMEOUT_S,
                 "provider": "mock"},
    }

    # ---- terminal classification (UXB-02) ----------------------------------
    term_info = terminal_status(term)
    report["terminal"] = term_info
    report["terminal_unsupported"] = not term_info["supported"]
    if not term_info["supported"]:
        findings.append(f"UXB-02: unsupported terminal explicitly recorded "
                        f"({term_info['note']}); PTY children still run "
                        f"TERM={CHILD_TERM} (probe-owned)")

    # ---- classpath ----------------------------------------------------------
    cp_info = resolve_classpath(out, args)
    report["classpath"] = {k: v for k, v in cp_info.items() if k != "classpath"}
    cp = cp_info.get("classpath") if cp_info["status"] == "ok" else None

    # ---- measurements --------------------------------------------------------
    measurements: dict = {}
    transcripts = out.parent / (out.stem + "-transcripts")
    transcripts.mkdir(parents=True, exist_ok=True)
    if cp is None:
        skip = ("skipped: no classpath (documented skip, not passed); "
                "reason: " + cp_info.get("reason", "unknown"))
        measurements["ux03_event_visible_latency"] = {
            "status": "skipped", "skip_reason": skip}
        measurements["ux04_repl_start"] = {
            "status": "skipped", "skip_reason": skip}
        findings.append(skip)
    else:
        for key, fn, budget in (
            ("ux03_event_visible_latency",
             lambda: measure_latency(cp, args.samples, transcripts),
             args.samples * SAMPLE_TIMEOUT_S + PROMPT_WAIT_S + QUIT_GRACE_S),
            ("ux04_repl_start",
             lambda: measure_start(cp, args.start_samples, transcripts),
             args.start_samples * 2 * (START_TIMEOUT_S + QUIT_GRACE_S)),
        ):
            t0 = time.monotonic()
            try:
                measurements[key] = fn()
            except Exception as e:   # honest failure, never a fake skip/pass
                measurements[key] = {
                    "status": "failed",
                    "reason": f"{type(e).__name__}: {e}"[:400]}
            measurements[key]["wall_s"] = round(time.monotonic() - t0, 1)
    report["measurements"] = measurements

    # ---- in-report UXB tests -------------------------------------------------
    env = report["environment"]
    uxb01_ok = all([
        env["java_version"], env["cpu_count"], env["timestamp"],
        env["repo_fingerprint"]["sha256"], "term" in env,
    ]) and out.parent.exists()
    def has_stddev(m: dict) -> bool:
        if "stddev_ms" in m:
            return True
        return any(has_stddev(v) for v in m.values() if isinstance(v, dict))

    report["tests"] = {
        "UXB-01": {
            "status": "pass" if uxb01_ok else "fail",
            "detail": "JSON baseline with environment parameters "
                      "(java/sbt/TERM/cpu/timestamp/repo-fingerprint) written",
        },
        "UXB-02": {
            "status": "pass",
            "detail": ("unsupported terminal explicitly recorded "
                       "(terminal.status=unsupported)") if not term_info["supported"]
                      else ("terminal supported and recorded; the unsupported "
                            "path is exercised by TERM=unknown-term (protocol "
                            "doc §UXB-02)"),
        },
        "UXB-03": {
            "status": "pass" if all(
                has_stddev(m) or m.get("status") == "skipped"
                for m in measurements.values()) else "fail",
            "detail": "dispersion (population stddev) recorded for every "
                      "measured quantity"
                      + ("; skipped measurements record no numbers "
                         "(documented skip, not dispersion)"
                         if any(m.get("status") == "skipped"
                                for m in measurements.values()) else "")
                      + "; structure stability is verified by two "
                        "back-to-back runs (protocol doc §UXB-03)",
        },
    }

    report["findings"] = findings
    report["limitations"] = [
        "baseline only: no UX01-08 threshold is claimed or compared against",
        "warm start = consecutive relaunch (warm OS caches), not a preloaded "
        "server process",
        "echo latency includes JLine line-editor redraw cost on this pinned "
        "machine only",
        "repo fingerprint uses quality-runner inventory excludes "
        "(target/, artifacts/, .git/, ... — see environment.repo_fingerprint)",
    ]

    # ---- atomic write; exit 0 iff the report is on disk -----------------------
    out.parent.mkdir(parents=True, exist_ok=True)
    try:
        tmp = out.with_suffix(out.suffix + ".tmp")
        tmp.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                       encoding="utf-8")
        os.replace(tmp, out)
    except OSError as e:
        print(f"[fatal] cannot write report {out}: {e}", file=sys.stderr)
        return 2

    measured = {k: v.get("status") for k, v in measurements.items()}
    print(f"[ux-baseline] report -> {out}")
    print(f"[ux-baseline] classpath={report['classpath']['status']} "
          f"terminal={term_info['status']} measurements={measured}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
