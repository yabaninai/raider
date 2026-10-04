#!/usr/bin/env python3
"""RAI-001 headless harness.

Runs the compiled spike on the headless-only classpath (no compiler/JLine),
with stdin closed and no TTY. Scenarios:
  - HEADLESS-01 normal completion: exit 0, truthful run.json, owned child finalized;
  - HEADLESS-02 SIGTERM: exit 143, partial truthful artifact, no orphan process;
  - HEADLESS-03 SIGINT: exit 130, same guarantees;
  - HEADLESS-04 classpath purity: no compiler/JLine jars on the run classpath;
  - PROBE-JSON zio-json candidate decode/roundtrip/negative;
  - PROBE-HTTP JDK HttpClient streaming read, full and mid-stream cancelled
    (local slow HTTP server; cancellation closes the body).
Also records cold-start wall time and peak RSS for the headless process.
"""
from __future__ import annotations

import http.server
import json
import os
import pathlib
import signal
import shutil
import socket
import subprocess
import sys
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(os.environ.get("RAIDER_SPIKE_EVIDENCE",
                                  ROOT / "artifacts" / "rai-001"))
CP_HEADLESS = ":".join([
    (ROOT / "artifacts/tools/cp-headless.txt").read_text().strip(),
    str(ROOT / "artifacts/spike/classes"),
])

checks: list[dict] = []
findings: list[str] = []


def check(cid: str, ok: bool, detail: str = "") -> bool:
    checks.append({"id": cid, "status": "pass" if ok else "fail", "detail": detail[:400]})
    print(f"[check] {cid}: {'PASS' if ok else 'FAIL'} {detail[:200]}")
    return ok


def run_headless(name: str, total_ms: str, tick_ms: str, sig: signal.Signals | None,
                 delay_s: float, extra_env: dict[str, str] | None = None) -> tuple[int, dict, str, float, int]:
    out_dir = OUT / f"headless-{name}"
    shutil.rmtree(out_dir, ignore_errors=True)  # fresh: no stale run.json
    out_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env["RAIDER_SPIKE_OUT"] = str(out_dir)
    if extra_env:
        env.update(extra_env)
    argv = ["java", "-cp", CP_HEADLESS, "raider.spike.HeadlessSpike", total_ms, tick_ms]
    t0 = time.monotonic()
    proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, cwd=ROOT)
    peak_rss = 0

    def sample_rss():
        nonlocal peak_rss
        while proc.poll() is None:
            try:
                q = subprocess.run(["ps", "-o", "rss=", "-p", str(proc.pid)],
                                   capture_output=True, text=True)
                if q.stdout.strip():
                    peak_rss = max(peak_rss, int(q.stdout.split()[0]))
            except Exception:
                pass
            time.sleep(0.15)

    sampler = threading.Thread(target=sample_rss, daemon=True)
    sampler.start()
    if sig is not None:
        time.sleep(delay_s)
        if proc.poll() is None:
            proc.send_signal(sig)
    stdout, stderr = proc.communicate(timeout=90)
    elapsed = time.monotonic() - t0
    (out_dir / "stdout.log").write_bytes(stdout)
    (out_dir / "stderr.log").write_bytes(stderr)
    run_json = {}
    rj = out_dir / "run.json"
    if rj.exists():
        run_json = json.loads(rj.read_text())
    return proc.returncode, run_json, stdout.decode(errors="replace"), elapsed, peak_rss


class SlowHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/hang"):
            # deliberate no-response endpoint: client must withdraw the request
            time.sleep(5)
            try:
                self.send_response(200)
                self.send_header("Content-Length", "4")
                self.end_headers()
                self.wfile.write(b"late")
            except (BrokenPipeError, ConnectionResetError):
                with open(OUT / "http-server.log", "a") as fh:
                    fh.write("hang client disconnected\n")
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            for i in range(1, 21):
                body = f"part-{i:02d}{'x' * 60}\n".encode()
                self.wfile.write(f"{len(body):x}\r\n".encode() + body + b"\r\n")
                self.wfile.flush()
                time.sleep(0.2)
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            with open(OUT / "http-server.log", "a") as fh:
                fh.write(f"client disconnected after part {i}\n")
            return

    def log_message(self, *args):
        pass


def start_slow_server() -> tuple[str, http.server.ThreadingHTTPServer]:
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SlowHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{srv.server_address[1]}/slow", srv


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "http-server.log").write_text("")

    # HEADLESS-04: classpath purity
    jars = CP_HEADLESS.split(":")
    dirty = [j for j in jars if any(t in j for t in ("scala3-compiler", "scala3-repl", "jline"))]
    check("HEADLESS-04-classpath-purity", not dirty,
          f"jars={len(jars)} dirty={dirty}")

    # HEADLESS-01: normal
    code, rj, so, elapsed, rss = run_headless("normal", "2500", "250", None, 0)
    ok = (code == 0 and rj.get("status") == "succeeded"
          and rj.get("childAliveAfterCleanup") is False and rj.get("stepsCompleted", 0) >= 5)
    check("HEADLESS-01-normal", ok,
          f"exit={code} status={rj.get('status')} steps={rj.get('stepsCompleted')} "
          f"cold_start={elapsed:.2f}s peak_rss={rss // 1024}MiB")

    # HEADLESS-02: SIGTERM
    code, rj, so, elapsed, rss = run_headless("sigterm", "15000", "300", signal.SIGTERM, 1.5)
    orphan = False
    pid = rj.get("childPid")
    if pid:
        q = subprocess.run(["ps", "-p", str(pid), "-o", "pid="], capture_output=True, text=True)
        orphan = q.stdout.strip() != ""
    ok = (code == 143 and rj.get("status") in ("interrupted", "interrupted-cleanup-timeout")
          and rj.get("signal") == "TERM" and 1 <= rj.get("stepsCompleted", 0) < 50
          and not orphan and rj.get("childAliveAfterCleanup") is False)
    check("HEADLESS-02-sigterm", ok,
          f"exit={code} status={rj.get('status')} signal={rj.get('signal')} "
          f"steps={rj.get('stepsCompleted')} orphan={orphan} elapsed={elapsed:.2f}s")

    # HEADLESS-03: SIGINT
    code, rj, so, elapsed, rss = run_headless("sigint", "15000", "300", signal.SIGINT, 1.5)
    pid = rj.get("childPid")
    orphan = False
    if pid:
        q = subprocess.run(["ps", "-p", str(pid), "-o", "pid="], capture_output=True, text=True)
        orphan = q.stdout.strip() != ""
    ok = (code == 130 and rj.get("signal") == "INT" and not orphan
          and rj.get("childAliveAfterCleanup") is False)
    check("HEADLESS-03-sigint", ok,
          f"exit={code} signal={rj.get('signal')} steps={rj.get('stepsCompleted')} orphan={orphan}")

    # PROBE-JSON
    p = subprocess.run(["java", "-cp", CP_HEADLESS, "raider.spike.HttpJsonProbe", "json"],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120, cwd=ROOT)
    (OUT / "probe-json.log").write_text(p.stdout + p.stderr)
    check("PROBE-JSON", p.returncode == 0 and p.stdout.count(": PASS") == 3,
          f"exit={p.returncode} {p.stdout.strip().splitlines()}")

    # PROBE-HTTP (local slow server; full read, then cancelled read)
    url, srv = start_slow_server()
    try:
        p = subprocess.run(["java", "-cp", CP_HEADLESS, "raider.spike.HttpJsonProbe",
                            "http", url, "10000"],
                           stdin=subprocess.DEVNULL, capture_output=True, text=True,
                           timeout=120, cwd=ROOT)
        (OUT / "probe-http-full.log").write_text(p.stdout + p.stderr)
        full_ok = p.returncode == 0 and "http-stream-read: PASS" in p.stdout
        check("PROBE-HTTP-full", full_ok, f"exit={p.returncode}")

        p = subprocess.run(["java", "-cp", CP_HEADLESS, "raider.spike.HttpJsonProbe",
                            "http-interrupt", url, "900"],
                           stdin=subprocess.DEVNULL, capture_output=True, text=True,
                           timeout=120, cwd=ROOT)
        (OUT / "probe-http-cancelled.log").write_text(p.stdout + p.stderr)
        disconnect_seen = False
        for _ in range(20):
            if "client disconnected" in (OUT / "http-server.log").read_text():
                disconnect_seen = True
                break
            time.sleep(0.25)
        cancelled_ok = (p.returncode == 0 and "http-interrupt-cleanup: PASS" in p.stdout
                        and disconnect_seen)
        check("PROBE-HTTP-cancel-midstream", cancelled_ok,
              f"exit={p.returncode} server_saw_disconnect={disconnect_seen}")
        p = subprocess.run(["java", "-cp", CP_HEADLESS, "raider.spike.HttpJsonProbe",
                            "http-cancel-headers", f"http://127.0.0.1:{srv.server_address[1]}/hang",
                            "700"],
                           stdin=subprocess.DEVNULL, capture_output=True, text=True,
                           timeout=120, cwd=ROOT)
        (OUT / "probe-http-cancel-headers.log").write_text(p.stdout + p.stderr)
        hang_disconnect = False
        for _ in range(24):
            if "hang client disconnected" in (OUT / "http-server.log").read_text():
                hang_disconnect = True
                break
            time.sleep(0.25)
        check("PROBE-HTTP-cancel-before-headers",
              p.returncode == 0 and "http-cancel-before-headers: PASS" in p.stdout,
              f"exit={p.returncode} server_saw_withdrawal={hang_disconnect}")
    finally:
        srv.shutdown()
        srv.server_close()

    # HEADLESS-05: grandchild reproducer (process-group gap documented, not claimed)
    code, rj, so, elapsed, rss = run_headless(
        "grandchild", "15000", "300", signal.SIGTERM, 1.5,
        extra_env={"RAIDER_SPIKE_CHILD_MODE": "grandchild"})
    gpid = rj.get("grandchildPid")
    grandchild_alive = False
    if gpid and int(gpid) > 0:
        q = subprocess.run(["ps", "-p", str(gpid), "-o", "pid="],
                           capture_output=True, text=True)
        grandchild_alive = q.stdout.strip() != ""
        if grandchild_alive:
            subprocess.run(["kill", "-9", str(gpid)])
    findings_note = (f"grandchildAliveAfterDirectChildCleanup={grandchild_alive}; "
                     "process-group kill is an RAI-016 obligation, spike claims direct-child only")
    check("HEADLESS-05-grandchild-gap-reproducer",
          code == 143 and rj.get("childAliveAfterCleanup") is False
          and gpid and int(gpid) > 0 and grandchild_alive,
          f"exit={code} grandchildPid={gpid} grandchildAlive={grandchild_alive} "
          "gap must be observed to count as reproduced")
    findings.append(findings_note)

    # HEADLESS-06: artifact write failure must be nonzero (27)
    blocked = OUT / "blocked-as-file"
    shutil.rmtree(blocked, ignore_errors=True)
    blocked.parent.mkdir(parents=True, exist_ok=True)
    blocked.write_text("occupied")
    code, rj, so, elapsed, rss = run_headless(
        "artifact-fail", "2000", "300", None, 0,
        extra_env={"RAIDER_SPIKE_OUT": str(blocked / "run.json")})
    check("HEADLESS-06-artifact-failure-nonzero", code == 27,
          f"exit={code} (setup/artifact failure path)")

    failed = [c for c in checks if c["status"] != "pass"]
    (OUT / "headless-summary.json").write_text(json.dumps(
        {"checks": checks,
         "findings": findings,
         "headless_cold_start_s": None,
         "note": "cold start/rss per scenario detail strings"}, indent=2))
    print(f"\nchecks: {len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
