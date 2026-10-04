#!/usr/bin/env python3
"""RAI-001 PTY harness: drives the real pinned Scala 3 REPL in a pseudo-terminal.

Covers SPIKE-01 (clean run, bindings, shared runtime, background jobs),
SPIKE-02 (compile error keeps old bindings, missing toolchain explicit),
SPIKE-03 (Ctrl+C idle/foreground/await, background output vs edit buffer).

Writes raw + cleaned transcripts and a JSON check summary under artifacts/rai-001/.
Exit code 0 only if every check passed; unexpected behaviors are recorded as
findings (feasibility data), and behavioral regressions fail checks.
"""
from __future__ import annotations

import fcntl
import json
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

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(os.environ.get("RAIDER_SPIKE_EVIDENCE",
                                  ROOT / "artifacts" / "rai-001"))
CP = ":".join([
    (ROOT / "artifacts/tools/cp-repl.txt").read_text().strip(),
    (ROOT / "artifacts/tools/cp-headless.txt").read_text().strip(),
    str(ROOT / "artifacts/spike/classes"),
])

ANSI = re.compile(r"\x1b\[[0-9;?$>*][0-9;?$]*[a-zA-Z]|\x1b\][^\x07]*(\x07|\x1b\\\\)|\x1b[()][A-B0-9]|\x1b[=><]|\x1b7|\x1b8")
PROMPT = "scala> "

checks: list[dict] = []
findings: list[str] = []


def check(cid: str, ok: bool, detail: str = "") -> bool:
    checks.append({"id": cid, "status": "pass" if ok else "fail", "detail": detail[:400]})
    print(f"[check] {cid}: {'PASS' if ok else 'FAIL'} {detail[:160]}")
    return ok


QUERIES: list[tuple[bytes, bytes]] = [
    (b"\x1b[c", b"\x1b[?62;22c"),          # Primary DA: report VT220-class terminal
    (b"\x1b[>c", b"\x1b[?0c"),             # Secondary DA
    (b"\x1b[?2027$p", b"\x1b[?2027;0$y"),  # synchronized update: not supported
    (b"\x1b[?u", b"\x1b[?0u"),             # kitty keyboard: not supported
    (b"\x1b[>q", b"\x1bP>|spike-pty\x1b\\"),  # XTVERSION
]


class Repl:
    def __init__(self, argv: list[str] | None = None, env_extra: dict[str, str] | None = None):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        env = dict(os.environ)
        env["NO_COLOR"] = "1"
        env["TERM"] = "xterm-256color"  # test-owned, never inherited (review 2026-10-03)
        pty_home = OUT / "pty-home"
        shutil.rmtree(pty_home, ignore_errors=True)
        pty_home.mkdir(parents=True, exist_ok=True)
        env["HOME"] = str(pty_home)  # isolate JVM/REPL history from user home
        if env_extra:
            env.update(env_extra)
        base = argv or [
            "java", "-cp", CP, "dotty.tools.repl.Main", "-usejavacp", "-color", "never",
        ]
        self.proc = subprocess.Popen(
            base, stdin=slave, stdout=slave, stderr=slave, close_fds=True, env=env, cwd=ROOT
        )
        os.close(slave)
        self.raw = open(OUT / "repl-transcript-raw.txt", "ab")
        self.buffer = ""
        self.max_rss_kb = 0
        self.started = time.monotonic()
        self.first_prompt_s: float | None = None

    def pump(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            r, _, _ = select.select([self.master], [], [], 0.2)
            if r:
                try:
                    data = os.read(self.master, 65536)
                except OSError:
                    return
                if not data:
                    return
                self.raw.write(data)
                self.raw.flush()
                self.buffer += data.decode("utf-8", "replace")
                for query, reply in QUERIES:
                    if query in data:
                        os.write(self.master, reply)
            try:
                out = subprocess.run(
                    ["ps", "-o", "rss=,pid=", "-p", str(self.proc.pid)],
                    capture_output=True, text=True,
                )
                if out.stdout.strip():
                    rss = int(out.stdout.split()[0])
                    self.max_rss_kb = max(self.max_rss_kb, rss)
            except Exception:
                pass

    def clean(self) -> str:
        return ANSI.sub("", self.buffer)

    def wait_prompt(self, timeout: float = 60.0) -> str:
        """Wait until a fresh `scala> ` appears; returns cleaned transcript delta."""
        start_len = len(self.buffer)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.pump(0.3)
            cleaned = ANSI.sub("", self.buffer[start_len:])
            idx = cleaned.rfind(PROMPT)
            if idx >= 0 and self.first_prompt_s is not None:
                return cleaned[: idx + len(PROMPT)]
            if idx >= 0 and self.first_prompt_s is None:
                self.first_prompt_s = time.monotonic() - self.started
                return cleaned[: idx + len(PROMPT)]
        raise TimeoutError(f"no prompt within {timeout}s; tail={self.clean()[-300:]!r}")

    def send(self, data: str) -> None:
        os.write(self.master, data.encode())

    def line(self, code: str, timeout: float = 60.0) -> str:
        self.send(code + "\r")
        return self.wait_prompt(timeout)

    def ctrl_c(self) -> None:
        self.send("\x03")

    def alive(self) -> bool:
        return self.proc.poll() is None

    def finish(self, timeout: float = 20.0) -> int:
        deadline = time.monotonic() + timeout
        while self.proc.poll() is None and time.monotonic() < deadline:
            self.pump(0.2)
        if self.proc.poll() is None:
            findings.append("REPL did not exit after :quit within grace; killing (shutdown ownership gap)")
            self.proc.kill()
            self.proc.wait()
            return -9
        # drain
        self.pump(0.5)
        self.raw.close()
        try:
            os.close(self.master)
        except OSError:
            pass
        return self.proc.returncode


def missing_toolcheck() -> None:
    """SPIKE-02: a REPL launched without core library on classpath fails explicitly."""
    cp_lines = (ROOT / "artifacts/tools/cp-repl.txt").read_text().strip().split(":")
    broken = [p for p in cp_lines if "scala-library" not in p]
    env = dict(os.environ)
    env["NO_COLOR"] = "1"
    proc = subprocess.run(
        ["java", "-cp", ":".join(broken), "dotty.tools.repl.Main", "-usejavacp"],
        input="", capture_output=True, text=True, timeout=120, env=env, cwd=ROOT,
    )
    ok = proc.returncode != 0 and ("MissingCoreLibrary" in proc.stderr or "Exception" in proc.stderr)
    check("SPIKE-02-missing-toolchain", ok,
          f"exit={proc.returncode} stderr={proc.stderr.splitlines()[0] if proc.stderr else ''!r}")
    (OUT / "repl-missing-toolchain.log").write_text(proc.stdout + "\n---STDERR---\n" + proc.stderr)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "repl-transcript-raw.txt").write_bytes(b"")
    repl = Repl()
    try:
        banner = repl.wait_prompt(120)
        check("SPIKE-01-banner", "Welcome to Scala" in banner, f"cold_start={repl.first_prompt_s:.2f}s")

        out = repl.line("val a = 40 + 2")
        check("SPIKE-01-val", "a: Int = 42" in out, out.strip().splitlines()[-2:] and "")

        out = repl.line("def add(x: Int, y: Int) = x + y")
        check("SPIKE-01-def", "add" in out and "Int" in out, "")

        out = repl.line("case class Pt(x: Int, y: Int)")
        check("SPIKE-01-case-class", "Pt" in out and ("defined" in out or "case class" in out), "")

        out = repl.line("Pt(3, 4).x")
        check("SPIKE-01-case-use", "Int = 3" in out, "")

        out = repl.line("val uni = \"проект ✓\"")
        check("SPIKE-01-unicode", "uni: String" in out, "")

        repl.send("val multi =\r")
        repl.pump(0.4)
        repl.send("  1 +\r")
        repl.pump(0.4)
        repl.send("  2\r")
        repl.pump(0.4)
        out = repl.line("")  # blank line terminates multiline input
        check("SPIKE-01-multiline", "multi: Int = 3" in out, out[-200:])

        out = repl.line("import raider.spike.RaiderSpike")
        check("SPIKE-01-import-prelude", "import" in out, "")

        # background start must not block next input while job still runs
        t0 = time.monotonic()
        out = repl.line("val j = RaiderSpike.start(\"spike1\", chunks = 8, delayMs = 250)")
        prompt_latency = time.monotonic() - t0
        check("SPIKE-01-start-nonblocking",
              prompt_latency < 1.5 and ("RaiderSpike$Job" in out or "Job(j_" in out),
              f"prompt_latency={prompt_latency:.2f}s out={out[-120:]!r}")

        out = repl.line("1 + 1")
        check("SPIKE-01-input-while-job", "Int = 2" in out, "")

        out = repl.line("j.await")
        check("SPIKE-01-await", "chunk-8" in out, out[-200:])

        out = repl.line("RaiderSpike.counter")
        c1 = out
        out = repl.line("j.await")
        check("SPIKE-01-await-idempotent", "chunk-8" in out, "")
        out = repl.line("RaiderSpike.counter")
        check("SPIKE-01-await-no-rerun", c1.strip().splitlines()[-2] == out.strip().splitlines()[-2], "")

        # ---- SPIKE-02: compile error keeps bindings
        out = repl.line("val good = 7")
        check("SPIKE-02-good-binding", "good: Int = 7" in out, "")
        out = repl.line("val bad: Int = \"oops\"")
        check("SPIKE-02-compile-error", ("error" in out.lower()) or ("mismatch" in out.lower()), out[-200:])
        out = repl.line("good * 6")
        check("SPIKE-02-binding-preserved", "Int = 42" in out, "")
        out = repl.line("j.info")
        check("SPIKE-02-shared-state-after-error", "state=succeeded" in out, out[-120:])

        # ---- SPIKE-03: idle Ctrl+C clears the line
        repl.send("val partialNotSent = ")
        repl.pump(0.5)
        repl.ctrl_c()
        try:
            out = repl.wait_prompt(5)
            ok = "partialNotSent" not in out
        except TimeoutError:
            repl.send("\r")
            out = repl.wait_prompt(5)
            ok = True
            findings.append("idle Ctrl+C needed a newline nudge to redraw prompt")
        out2 = repl.line("val afterInterrupt = 11")
        check("SPIKE-03-idle-ctrlc", ok and "afterInterrupt: Int = 11" in out2, "")

        # ---- SPIKE-03: foreground Ctrl+C during blocking evaluation
        t0 = time.monotonic()
        repl.send("RaiderSpike.blocking(15000)\r")
        repl.pump(1.5)
        repl.ctrl_c()
        try:
            out = repl.wait_prompt(6)
            recovered = True
            fg_cancelled_quickly = time.monotonic() - t0 < 8
            findings.append(
                f"foreground Ctrl+C: prompt recovered={recovered} in {time.monotonic()-t0:.1f}s; "
                f"transcript tail={ANSI.sub('', out)[-160:]!r}")
        except TimeoutError as exc:
            recovered = False
            fg_cancelled_quickly = False
            findings.append(f"foreground Ctrl+C did NOT recover prompt within 6s: {exc}")
        out = repl.line("val postFg = 1")
        check("SPIKE-03-foreground-ctrlc", recovered and "postFg: Int = 1" in out,
              findings[-1])

        # ---- SPIKE-03: Ctrl+C during await leaves job running
        out = repl.line("val w = RaiderSpike.start(\"await-job\", chunks = 30, delayMs = 400)")
        check("SPIKE-03-await-job-start", ("RaiderSpike$Job" in out or "Job(j_" in out), out[-120:])
        repl.send("w.await\r")
        repl.pump(2.0)
        repl.ctrl_c()
        try:
            out = repl.wait_prompt(6)
            await_observer_stopped = True
            findings.append(f"await Ctrl+C transcript tail={ANSI.sub('', out)[-160:]!r}")
        except TimeoutError:
            await_observer_stopped = False
            findings.append("await Ctrl+C did not return the prompt (observer not interruptible)")
        out = repl.line("w.info")
        job_still_running = "state=running" in out
        check("SPIKE-03-await-observer", await_observer_stopped and job_still_running,
              f"observer_stopped={await_observer_stopped} job_still_running={job_still_running}")
        out = repl.line("w.cancel()")
        repl.pump(0.6)
        out = repl.line("w.info")
        check("SPIKE-03-explicit-cancel", "state=interrupted" in out, out[-120:])

        # ---- SPIKE-03: background output vs editing buffer
        out = repl.line("val noisy = RaiderSpike.start(\"noisy\", chunks = 12, delayMs = 250)")
        for ch in "val typing = 9":
            repl.send(ch)
            repl.pump(0.12)
        out = repl.line("9")
        check("SPIKE-03-edit-during-stream", "typing: Int = 99" in out,
              "interleaving recorded in raw transcript")
        findings.append("background output interleaves with the edit buffer: "
                        "single-writer terminal renderer required (drives RAI-019 design)")

        # ---- exit
        repl.send(":quit\r")
        code = repl.finish(30)
        check("SPIKE-01-clean-exit", code == 0, f"exit={code}")
    finally:
        if repl.alive():
            repl.proc.kill()
            repl.proc.wait()
        (OUT / "repl-transcript.txt").write_text(repl.clean())

    # toolchain check is part of the suite and must precede the summary (review P2)
    missing_toolcheck()

    summary = {
        "cold_start_s": repl.first_prompt_s,
        "peak_rss_kb": repl.max_rss_kb,
        "checks": checks,
        "findings": findings,
    }
    (OUT / "repl-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))

    failed = [c for c in checks if c["status"] != "pass"]
    print(f"\nchecks: {len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
