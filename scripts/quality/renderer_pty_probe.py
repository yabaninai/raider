#!/usr/bin/env python3
"""RAI-056 / P0.C — inline renderer PTY probe (ADR-016 feasibility spike).

Drives the REAL JLine 4.0.14 line editor (raider.repl.spike.InlineRendererSpike,
Test scope) under a pseudo-terminal — no mocks. Modeled on
scripts/quality/repl_pty_smoke.py: terminal size + TERM are probe-owned,
Primary-DA capability queries are answered so JLine renders prompts, ISIG is off
so a raw \\x03 reaches the app, and HOME is isolated so the JVM never touches
the user home.

Tests:
  RENDER-01  xterm-256color: `:burst 200` streams 200 background printAbove
                writes while the user types a mixed Cyrillic/emoji/CJK draft in
                two parts; the final ACCEPTED line must equal the full draft and
                every `ev N payload ...` line must be atomic (untorn).
  RENDER-02  parse the baseline report: events==200, dropped==0, record
                p50/p95/max (BASELINE — no threshold claims).
  RENDER-03  TERM=dumb plain fallback: must still work (no crash, ACCEPTED
                intact); the probe records the observable difference.
  RENDER-04  THREE back-to-back overlapping `:burst 67` (card: "три
                параллельных фоновых источника"): 201 events total, lines still
                atomic, draft intact; the per-burst reports <base>.b<N>.json are
                aggregated by this probe and total dropped must be 0.

Report files: the spike NEVER writes a shared report file — each burst writes
its own counters to <argv(0)>.b<N>.json (last-writer-wins on one path would
silently drop the other bursts' data). This probe materializes the canonical
files from those per-burst reports:
  renderer-baseline.json     = verbatim copy of renderer-baseline.json.b1.json
                               (RENDER-01 single burst; the canonical baseline)
  renderer-baseline-04.json  = probe-side aggregate of the three RENDER-04
                               per-burst reports: events/dropped are sums;
                               p50/p95/max are linear-interpolation percentiles
                               over the concatenated raw per-burst durations_ns
                               arrays (true global percentiles, rule in-file).

Limitation (stated honestly): line atomicity is checked at the level of the
final cleaned transcript stream — a frame torn mid-write and later erased/
redrawn by JLine is not individually detectable; what is proven is buffer/
draft integrity and the final visible state, not per-frame on-the-wire
integrity.

Exit 0 only if all four pass. Machine-readable summary -> evidence dir.
"""
from __future__ import annotations

import fcntl
import json
import math
import os
import pathlib
import pty
import re
import select
import shutil
import struct
import subprocess
import sys
import termios
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
# Evidence lives INSIDE the repo: artifacts/ is the gitignored evidence area.
# ROOT is anchored to this file's location (parents[2] of
# scripts/quality/renderer_pty_probe.py), so the probe works from any cwd
# (repo root, scripts/quality/, ...). RAI056_EVIDENCE_DIR still overrides.
EVIDENCE = pathlib.Path(
    os.environ.get("RAI056_EVIDENCE_DIR", str(ROOT / "artifacts" / "renderer-spike"))
)
CP_CACHE = EVIDENCE / "spike-classpath.txt"
COURSIER_CACHE = "/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/coursier-lane-b"

PROMPT = "spike> "
PAYLOAD = "x" * 32
PARTIAL = "привет😀Х "   # Cyrillic + emoji + CJK, no Enter
REST = "мир"               # rest of the draft (Cyrillic)
DRAFT = PARTIAL + REST      # full typed draft

# ANSI/VT10x strip. The CSI alternative is `ESC [ <params> <interm> <final>`:
# it must accept ZERO params so paramless sequences (\x1b[K erase-line, \x1b[H
# home) are removed too — JLine's printAbove emits \x1b[K on every redraw.
ANSI = re.compile(
    r"\x1b\[[0-9;?]*[ -/]*[@-~]"           # CSI (covers \x1b[K, \x1b[2J, \x1b[?25l)
    r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\\\)"  # OSC (window title, ...)
    r"|\x1b[()][0-9A-B]"                    # charset designation
    r"|\x1b[=><]"                           # keypad / alt-screen modes
    r"|\x1b7|\x1b8"                         # DECSC / DECRC
)

# Terminal capability answers (JLine sends these at startup).
QUERIES: list[tuple[bytes, bytes]] = [
    (b"\x1b[c", b"\x1b[?62;22c"),
    (b"\x1b[>c", b"\x1b[?0c"),
    (b"\x1b[?2027$p", b"\x1b[?2027;0$y"),
    (b"\x1b[?u", b"\x1b[?0u"),
    (b"\x1b[>q", b"\x1bP>|raider-renderer\x1b\\"),
]

EV_RE = re.compile(r"ev \d+ payload " + re.escape(PAYLOAD))

checks: list[dict] = []
findings: list[str] = []


def check(cid: str, ok: bool, detail: str = "") -> bool:
    checks.append({"id": cid, "status": "pass" if ok else "fail",
                   "detail": detail[:400]})
    print(f"[{cid}] {'PASS' if ok else 'FAIL'}  {detail[:200]}")
    return ok


def classpath() -> str:
    env_cp = os.environ.get("RAIDER_SPIKE_CP")
    if env_cp:
        return env_cp
    if CP_CACHE.exists():
        cp = CP_CACHE.read_text().strip()
        if cp:
            return cp
    # Fallback: resolve via sbt (dedicated coursier cache), then cache.
    env = dict(os.environ)
    env.setdefault("COURSIER_CACHE", COURSIER_CACHE)
    res = subprocess.run(
        ["sbt", "--batch", "export raiderRepl/Test/fullClasspath"],
        capture_output=True, text=True, env=env, cwd=ROOT, timeout=600)
    lines = [l for l in res.stdout.splitlines() if l.strip() and not l.startswith("[")]
    cp = lines[-1].strip() if lines else ""
    if res.returncode != 0 or not cp:
        print(f"[fatal] classpath export failed rc={res.returncode} {res.stderr[-400:]}")
        sys.exit(21)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    CP_CACHE.write_text(cp)
    return cp


def wait_for_file(path: pathlib.Path, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            try:
                if path.read_text().strip():
                    return True
            except OSError:
                pass
        time.sleep(0.2)
    return path.exists()


def wait_for_all(paths: list[pathlib.Path], timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ready = True
        for p in paths:
            try:
                if not (p.exists() and p.read_text().strip()):
                    ready = False
                    break
            except OSError:
                ready = False
                break
        if ready:
            return True
        time.sleep(0.2)
    return False


def percentile(sorted_ns: list[int], q: float) -> float:
    """Linear-interpolation percentile, SAME rule as the spike (ns -> ms)."""
    if not sorted_ns:
        return 0.0
    if len(sorted_ns) == 1:
        return float(sorted_ns[0]) * 1e-6
    pos = q * (len(sorted_ns) - 1)
    lo, hi = int(math.floor(pos)), int(math.ceil(pos))
    frac = pos - lo
    return (sorted_ns[lo] * (1.0 - frac) + sorted_ns[hi] * frac) * 1e-6


class Session:
    """One java spike process under a PTY."""

    def __init__(self, cp: str, report: pathlib.Path, term: str = "xterm-256color",
                 rows: int = 40, cols: int = 120):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        # ISIG off: \x03 reaches the app as a raw byte (matches the product smoke).
        attrs = termios.tcgetattr(slave)
        attrs[3] &= ~termios.ISIG
        termios.tcsetattr(slave, termios.TCSANOW, attrs)
        home = EVIDENCE / f"pty-home-{term}"
        shutil.rmtree(home, ignore_errors=True)
        home.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ)
        env["NO_COLOR"] = "1"
        env["TERM"] = term
        env["HOME"] = str(home)
        self.report = report
        if report.exists():
            report.unlink()
        # Also clear stale per-burst reports from previous runs (round-2
        # schema: <base>.b<N>.json) so a leftover can never be mistaken for
        # this run's counters.
        for pat in (report.name + ".b*.json", report.name + ".b*.json.tmp"):
            for stale in sorted(report.parent.glob(pat)):
                stale.unlink(missing_ok=True)
        self.proc = subprocess.Popen(
            ["java", "-cp", cp, "raider.repl.spike.InlineRendererSpike", str(report)],
            stdin=slave, stdout=slave, stderr=slave, close_fds=True,
            env=env, cwd=ROOT)
        os.close(slave)
        self.raw = bytearray()

    def pump(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            r, _, _ = select.select([self.master], [], [], 0.1)
            if r:
                try:
                    data = os.read(self.master, 65536)
                except OSError:
                    return
                if not data:
                    return
                self.raw.extend(data)
                for q, a in QUERIES:
                    if q in data:
                        os.write(self.master, a)

    def clean(self) -> str:
        return ANSI.sub("", self.raw.decode("utf-8", "replace"))

    def wait_for(self, substr: str, timeout: float = 30.0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if substr in self.clean():
                return True
            self.pump(0.2)
        return substr in self.clean()

    def send(self, s: str) -> None:
        os.write(self.master, s.encode())

    def n_ansi(self) -> int:
        return len(ANSI.findall(self.raw.decode("utf-8", "replace")))

    def alive(self) -> bool:
        return self.proc.poll() is None

    def finish(self, timeout: float = 20.0) -> int:
        deadline = time.monotonic() + timeout
        while self.alive() and time.monotonic() < deadline:
            self.pump(0.2)
        if self.alive():
            self.proc.kill()
            self.proc.wait()
            return -9
        self.pump(0.5)
        try:
            os.close(self.master)
        except OSError:
            pass
        return self.proc.returncode


def read_report(path: pathlib.Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None


def main() -> int:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    git_avail = (ROOT / ".git").exists()
    cp = classpath()

    # ---- RENDER-01 (+02 share the session) --------------------------------
    report1 = EVIDENCE / "renderer-baseline.json"
    rep1_b1 = EVIDENCE / "renderer-baseline.json.b1.json"   # per-burst report
    s = Session(cp, report1, term="xterm-256color")
    xterm_ansi = 0
    try:
        ok_prompt = s.wait_for(PROMPT, 60)
        s.send(":width\r")
        ok_width = s.wait_for("WIDTH_JSON", 10)
        # Trigger the burst FIRST (its own line) so it streams in the background,
        # then type the draft in two parts while events stream — the interleaving
        # (background printAbove overlapping the in-progress edit) is what is tested.
        s.send(":burst 200\r")
        s.pump(0.6)
        s.send(PARTIAL)      # partial draft, no Enter
        s.pump(0.6)
        s.send(REST)         # rest of the draft
        s.pump(0.6)
        s.send("\r")         # submit
        ok_acc = s.wait_for("ACCEPTED " + DRAFT, 20)
        s.pump(0.6)          # settle so all 200 ev lines are flushed
        cleaned = s.clean()
        n_ev = len(EV_RE.findall(cleaned))
        all_present = all(f"ev {i} payload {PAYLOAD}" in cleaned for i in range(200))
        n_acc = cleaned.count("ACCEPTED " + DRAFT)
        xterm_ansi = s.n_ansi()
        ok_report = wait_for_file(rep1_b1, 20)
        # Canonical baseline = verbatim copy of the single-burst report
        # (the spike itself only ever writes <base>.b<N>.json).
        if rep1_b1.exists():
            report1.write_text(rep1_b1.read_text())
        elif report1.exists():
            report1.unlink()
        s.send(":quit\r")
        code = s.finish(20)
    finally:
        if s.alive():
            s.proc.kill()
            s.proc.wait()
        (EVIDENCE / "transcript-render01.txt").write_text(s.clean())

    r01 = (ok_prompt and ok_width and ok_acc and (n_acc == 1)
           and all_present and (n_ev == 200) and ok_report and (code == 0))
    check("RENDER-01", r01,
          f"width={ok_width} accepted_once={n_acc == 1} ev_atomic={n_ev}/200 "
          f"all_present={all_present} report={ok_report} exit={code}")
    if n_ev != 200:
        findings.append(f"RENDER-01: only {n_ev}/200 background event lines were "
                        f"atomic in the stream (JLine printAbove limit?)")

    # ---- RENDER-02 --------------------------------------------------------
    rep1 = read_report(report1)
    if rep1 is None:
        r02 = check("RENDER-02", False, "baseline report missing/unparseable")
        baseline = {}
    else:
        baseline = {
            "events": rep1.get("events"),
            "p50_ms": rep1.get("p50_ms"),
            "p95_ms": rep1.get("p95_ms"),
            "max_ms": rep1.get("max_ms"),
            "dropped": rep1.get("dropped"),
            "wcwidth_table": rep1.get("wcwidth_table"),
        }
        r02 = (rep1.get("events") == 200 and rep1.get("dropped") == 0)
        check("RENDER-02", r02,
              f"events={rep1.get('events')} dropped={rep1.get('dropped')} "
              f"p50={rep1.get('p50_ms')} p95={rep1.get('p95_ms')} max={rep1.get('max_ms')}")
        if r02:
            print("BASELINE " + json.dumps(baseline, ensure_ascii=False))

    # ---- RENDER-03 (TERM=dumb plain fallback) ----------------------------
    report3 = EVIDENCE / "renderer-baseline-dumb.json"
    s = Session(cp, report3, term="dumb")
    try:
        s.pump(2.0)          # let the JVM + dumb terminal come up
        s.send(DRAFT + "\r")
        ok_acc3 = s.wait_for("ACCEPTED " + DRAFT, 20)
        s.pump(0.5)
        c3 = s.clean()
        n_acc3 = c3.count("ACCEPTED " + DRAFT)
        dumb_ansi = s.n_ansi()
        prompt_visible = PROMPT in c3
        s.send(":quit\r")
        code3 = s.finish(20)
    finally:
        if s.alive():
            s.proc.kill()
            s.proc.wait()
        (EVIDENCE / "transcript-render03.txt").write_text(s.clean())

    r03 = (ok_acc3 and (n_acc3 == 1) and (code3 == 0))
    check("RENDER-03", r03,
          f"term=dumb accepted_once={n_acc3 == 1} exit={code3} "
          f"ansi_dumb={dumb_ansi} ansi_xterm={xterm_ansi} prompt_visible={prompt_visible}")
    findings.append(
        f"RENDER-03 observable: TERM=dumb emits {dumb_ansi} VT sequences vs "
        f"{xterm_ansi} under xterm-256color; plain fallback keeps ACCEPTED intact")

    # ---- RENDER-04 (three overlapping bursts: 3 x 67 = 201 events) --------
    report4 = EVIDENCE / "renderer-baseline-04.json"
    reps4 = [EVIDENCE / f"renderer-baseline-04.json.b{i}.json" for i in (1, 2, 3)]
    agg4: dict | None = None
    s = Session(cp, report4, term="xterm-256color")
    try:
        ok_prompt4 = s.wait_for(PROMPT, 60)
        # Three back-to-back bursts: the bytes queue in the PTY and the main
        # loop spawns all three threads within milliseconds -> true overlap
        # (card: "три параллельных фоновых источника").
        s.send(":burst 67\r")
        s.pump(0.05)
        s.send(":burst 67\r")
        s.pump(0.05)
        s.send(":burst 67\r")
        s.pump(0.2)
        s.send(PARTIAL)
        s.pump(0.2)
        s.send(REST)
        s.pump(0.2)
        s.send("\r")
        ok_acc4 = s.wait_for("ACCEPTED " + DRAFT, 20)
        s.pump(1.5)              # let all three bursts finish + write reports
        c4 = s.clean()
        n_ev4 = len(EV_RE.findall(c4))
        n_acc4 = c4.count("ACCEPTED " + DRAFT)
        ok_reports4 = wait_for_all(reps4, 30)
        per4 = [read_report(p) for p in reps4]
        if ok_reports4 and all(p is not None for p in per4):
            all_ns = sorted(d for p in per4 for d in p.get("durations_ns", []))
            agg4 = {
                "events": sum(p["events"] for p in per4),
                "p50_ms": percentile(all_ns, 0.50),
                "p95_ms": percentile(all_ns, 0.95),
                "max_ms": (all_ns[-1] * 1e-6) if all_ns else 0.0,
                "dropped": sum(p["dropped"] for p in per4),
                "aggregation": (
                    "probe-side aggregate of the three per-burst reports "
                    "<base>.b<N>.json (the spike never writes a shared file, "
                    "so no burst's counters can be lost to a last-writer-wins "
                    "overwrite): events and dropped are sums; p50/p95/max are "
                    f"linear-interpolation percentiles over the concatenated "
                    f"raw per-burst durations_ns arrays (N={len(all_ns)} "
                    "samples) — true global percentiles, not percentiles of "
                    "per-burst percentiles"),
                "bursts": len(per4),
                "burst_layout": [67, 67, 67],
                "per_burst": [
                    {
                        "file": p.name,
                        "burst": d.get("burst"),
                        "events": d.get("events"),
                        "dropped": d.get("dropped"),
                        "p50_ms": d.get("p50_ms"),
                        "p95_ms": d.get("p95_ms"),
                        "max_ms": d.get("max_ms"),
                    }
                    for p, d in zip(reps4, per4)
                ],
                "wcwidth_table": per4[0].get("wcwidth_table"),
            }
            report4.write_text(json.dumps(agg4, ensure_ascii=False))
        s.send(":quit\r")
        code4 = s.finish(20)
    finally:
        if s.alive():
            s.proc.kill()
            s.proc.wait()
        (EVIDENCE / "transcript-render04.txt").write_text(s.clean())

    total_events4 = agg4["events"] if agg4 else None
    total_dropped4 = agg4["dropped"] if agg4 else None
    r04 = (ok_prompt4 and ok_acc4 and (n_acc4 == 1) and (n_ev4 == 201)
           and (total_events4 == 201) and (total_dropped4 == 0)
           and ok_reports4 and (code4 == 0))
    check("RENDER-04", r04,
          f"3x67 overlapping accepted_once={n_acc4 == 1} "
          f"ev_atomic={n_ev4}/201 agg_events={total_events4}/201 "
          f"agg_dropped={total_dropped4} reports3of3={ok_reports4} exit={code4}")
    if agg4 is not None:
        print("BASELINE-04 " + json.dumps(
            {k: agg4[k] for k in ("events", "p50_ms", "p95_ms", "max_ms",
                                  "dropped")}, ensure_ascii=False))
    if n_ev4 != 201:
        findings.append(f"RENDER-04: only {n_ev4}/201 event lines atomic under "
                        f"three overlapping bursts")

    # ---- summary ----------------------------------------------------------
    findings.append(
        "Limitation: line atomicity is checked at the level of the final "
        "cleaned transcript stream — a frame torn mid-write and later "
        "erased/redrawn by JLine is not individually detectable; buffer/"
        "draft integrity and the final visible state are what is proven")
    all_pass = all([r01, r02, r03, r04])
    # "round" is caller-supplied run metadata: taken from RAI056_ROUND and
    # included only when set (a hardcoded default would leave stale metadata).
    round_env = os.environ.get("RAI056_ROUND")
    summary = {
        "kind": "raider-renderer-pty-probe",
        "card": "RAI-056",
        "package": "P0.C",
        **({"round": round_env} if round_env else {}),
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "lane": str(ROOT),
        "mode": "git-available" if git_avail else "Git unavailable: isolated snapshot",
        "jline": "4.0.14",
        "tests": checks,
        "baseline": baseline,
        "baseline04": agg4,
        "ansi_counts": {"xterm_256color": xterm_ansi, "dumb": dumb_ansi},
        "findings": findings,
        "all_pass": all_pass,
    }
    (EVIDENCE / "renderer-pty-summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False))
    print(f"\nsummary -> {EVIDENCE / 'renderer-pty-summary.json'}")
    print(f"tests: {sum(1 for c in checks if c['status'] == 'pass')}/{len(checks)} passed")
    return 0 if all_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
