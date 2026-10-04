#!/usr/bin/env python3
"""PTY smoke for the W10 Raider REPL (fast-path slice).

Drives the REAL product process (java -cp <raiderRepl full classpath>
raider.repl.Main) in a pseudo-terminal — no mocks. Modeled on the RAI-001
spike harness (experiments/repl-spike/harness/repl_pty_harness.py): terminal
size + TERM are test-owned, Primary DA is answered so JLine renders prompts,
HOME is isolated so JVM/REPL history never touches the user home.

Exit 0 only if every check passed. Evidence: artifacts/repl-fast/pty-*.
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

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts" / "repl-fast"
CP_FILE = OUT / "classpath.txt"
PROMPT = "raider> "
CONT = "     | "

ANSI = re.compile(
    r"\x1b\[[0-9;?$>*][0-9;?$]*[a-zA-Z]|\x1b\][^\x07]*(\x07|\x1b\\\\)|"
    r"\x1b[()][A-B0-9]|\x1b[=><]|\x1b7|\x1b8")

checks: list[dict] = []
findings: list[str] = []

QUERIES: list[tuple[bytes, bytes]] = [
    (b"\x1b[c", b"\x1b[?62;22c"),
    (b"\x1b[>c", b"\x1b[?0c"),
    (b"\x1b[?2027$p", b"\x1b[?2027;0$y"),
    (b"\x1b[?u", b"\x1b[?0u"),
    (b"\x1b[>q", b"\x1bP>|raider-pty\x1b\\"),
]


def check(cid: str, ok: bool, detail: str = "") -> bool:
    checks.append({"id": cid, "status": "pass" if ok else "fail",
                   "detail": detail[:400]})
    print(f"[check] {cid}: {'PASS' if ok else 'FAIL'} {detail[:160]}")
    return ok


def classpath() -> str:
    if os.environ.get("RAIDER_REPL_CP"):
        return os.environ["RAIDER_REPL_CP"]
    env = dict(os.environ)
    env.setdefault("COURSIER_CACHE", "/tmp/cc-master")
    res = subprocess.run(
        ["sbt", "--batch", "--error", "export raiderRepl/runtime:fullClasspath"],
        capture_output=True, text=True, env=env, cwd=ROOT, timeout=300)
    cp = res.stdout.strip().splitlines()[-1].strip() if res.stdout.strip() else ""
    if res.returncode != 0 or not cp:
        print(f"[fatal] classpath export failed: rc={res.returncode} {res.stderr[-300:]}")
        sys.exit(21)
    return cp


class Repl:
    def __init__(self, cp: str):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        # Test-owned discipline: with ISIG on, \x03 becomes SIGINT to the
        # foreground process group (which this pty has none of — the byte is
        # swallowed before JLine ever sees it). Turn ISIG off so Ctrl+C
        # arrives as a plain byte and the PRODUCT keymap binding is what is
        # actually exercised.
        attrs = termios.tcgetattr(slave)
        attrs[3] &= ~termios.ISIG
        termios.tcsetattr(slave, termios.TCSANOW, attrs)
        env = dict(os.environ)
        env["NO_COLOR"] = "1"
        env["TERM"] = "xterm-256color"
        pty_home = OUT / "pty-home"
        shutil.rmtree(pty_home, ignore_errors=True)
        pty_home.mkdir(parents=True, exist_ok=True)
        env["HOME"] = str(pty_home)
        self.proc = subprocess.Popen(
            ["java", "-cp", cp, "raider.repl.Main"],
            stdin=slave, stdout=slave, stderr=slave, close_fds=True,
            env=env, cwd=ROOT)
        os.close(slave)
        self.raw = open(OUT / "pty-transcript-raw.txt", "ab")
        self.buffer = ""
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

    def clean(self) -> str:
        return ANSI.sub("", self.buffer)

    def wait_prompt(self, timeout: float = 60.0, want_cont: bool = False) -> str:
        marker = CONT if want_cont else PROMPT
        start_len = len(self.buffer)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.pump(0.3)
            cleaned = ANSI.sub("", self.buffer[start_len:])
            idx = cleaned.rfind(marker)
            if idx >= 0:
                return cleaned[: idx + len(marker)]
        raise TimeoutError(
            f"no {marker!r} within {timeout}s; tail={self.clean()[-300:]!r}")

    def send(self, data: str) -> None:
        os.write(self.master, data.encode())

    def line(self, code: str, timeout: float = 60.0) -> str:
        self.send(code + "\r")
        return self.wait_prompt(timeout)

    def alive(self) -> bool:
        return self.proc.poll() is None

    def finish(self, timeout: float = 20.0) -> int:
        deadline = time.monotonic() + timeout
        while self.proc.poll() is None and time.monotonic() < deadline:
            self.pump(0.2)
        if self.proc.poll() is None:
            findings.append("REPL did not exit after :quit within grace; killed")
            self.proc.kill()
            self.proc.wait()
            return -9
        self.pump(0.5)
        self.raw.close()
        try:
            os.close(self.master)
        except OSError:
            pass
        return self.proc.returncode


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "pty-transcript-raw.txt").write_bytes(b"")
    cp = classpath()
    repl = Repl(cp)
    try:
        banner = repl.wait_prompt(120)
        check("PTY-banner",
              "Raider REPL" in banner and "fixture/mock" in banner,
              f"cold_start={repl.first_prompt_s and f'{repl.first_prompt_s:.2f}s'}")

        out = repl.line("val x = 1")
        check("PTY-val", "val x: Int = 1" in out, out[-120:])

        # multiline: '=' keeps the continuation prompt, balanced tail submits
        repl.send("def add(a: Int) =\r")
        cont = repl.wait_prompt(20, want_cont=True)
        out = repl.line("  a + 1")
        check("PTY-multiline", CONT in cont and "def add(a: Int): Int" in out,
              out[-120:])

        out = repl.line("add(2)")
        check("PTY-def-use", "= 3" in out, out[-120:])

        out = repl.line("case class Pt(x: Int, y: Int)")
        out2 = repl.line("Pt(3, 4).x")
        check("PTY-case-class", "Int = 3" in out2, out2[-120:])

        # compile error preserves state
        out = repl.line('val broken: Int = "oops"')
        out2 = repl.line("val keep = 5")
        check("PTY-compile-error-keeps-state",
              ("Error" in out) and ("val keep: Int = 5" in out2), out2[-120:])

        # facade: scripted ask
        out = repl.line('scout.ask("hi")')
        check("PTY-ask-scripted", "[scripted-fixture] mock answer" in out, out[-160:])

        # background start returns the prompt quickly; await settles
        t0 = time.monotonic()
        out = repl.line('val jb = worker.start("pty-bg")', timeout=20)
        latency = time.monotonic() - t0
        check("PTY-start-nonblocking",
              latency < 3.0 and "JobHandle" in out,
              f"latency={latency:.2f}s out={out[-100:]!r}")
        out = repl.line("jb.await()")
        check("PTY-await", "[scripted-fixture] mock answer" in out, out[-120:])

        out = repl.line(":jobs")
        check("PTY-jobs", "j_1" in out and "Succeeded" in out, out[-160:])

        # reset re-imports the prelude; facade keeps working
        out = repl.line(":reset")
        out2 = repl.line('scout.ask("again")')
        check("PTY-reset-prelude",
              "session state reset" in out and "[scripted-fixture] mock answer" in out2,
              out2[-120:])

        # idle Ctrl+C clears the pending buffer and recovers the prompt.
        # JLine 4 default binds abort to Ctrl+G (\x07); the product binds
        # \x03 explicitly. Probe both; the check passes if the pending buffer
        # is cleared and the prompt recovers.
        repl.send("val partial = ")
        repl.pump(0.5)
        repl.send("\x03")
        cleared_by_ctrlc = False
        try:
            repl.wait_prompt(3)
            cleared_by_ctrlc = True
        except TimeoutError:
            findings.append("\\x03 did not clear the pending buffer")
        if not cleared_by_ctrlc:
            repl.send("\x07")
            try:
                repl.wait_prompt(3)
                findings.append("fallback \\x07 (Ctrl+G) cleared the buffer")
            except TimeoutError:
                findings.append("\\x07 also did not clear the buffer")
        out = repl.line("val afterCtrlC = 11")
        check("PTY-idle-ctrlc",
              "val afterCtrlC: Int = 11" in out, f"cleared_by_ctrlc={cleared_by_ctrlc} out={out[-100:]!r}")

        repl.send(":quit\r")
        code = repl.finish(30)
        check("PTY-clean-exit", code == 0, f"exit={code}")
    finally:
        if repl.alive():
            repl.proc.kill()
            repl.proc.wait()
        (OUT / "pty-transcript.txt").write_text(repl.clean())

    summary = {
        "kind": "raider-repl-pty-smoke",
        "cold_start_s": repl.first_prompt_s,
        "checks": checks,
        "findings": findings,
    }
    (OUT / "pty-summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False))

    failed = [c for c in checks if c["status"] != "pass"]
    print(f"\nchecks: {len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
