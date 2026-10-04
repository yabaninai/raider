#!/usr/bin/env python3
"""SPIKE-OAI-01 harness: OpenAI-compatible Chat wire against the local mock gateway.

Scenarios (subprocess + one PTY):
  OAI-01 non-streaming ask; OAI-02 streaming text == non-streaming text (equivalence,
  fragmented SSE frames); OAI-03 tool-call stream: args assembled, never executed;
  OAI-04 401 classified as ProviderAuth; OAI-05 mid-stream cancellation closes the
  body (gateway observes disconnect); OAI-06 the same client streams from the real
  Scala REPL via replAsk.
"""
from __future__ import annotations

import fcntl
import json
import os
import pathlib
import pty
import re
import shutil
import select
import struct
import subprocess
import sys
import termios
import threading
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import mock_gateway  # noqa: E402

ROOT = HERE.parents[2]
OUT = pathlib.Path(os.environ.get("RAIDER_OAI_EVIDENCE",
                                  ROOT / "artifacts" / "oai-spike"))
OUT.mkdir(parents=True, exist_ok=True)
KEY = mock_gateway.API_KEY
CP = ":".join([
    (ROOT / "artifacts/tools/cp-repl.txt").read_text().strip(),
    (ROOT / "artifacts/tools/cp-headless.txt").read_text().strip(),
    str(ROOT / "artifacts/spike/classes-oai"),  # fresh, isolated from RAI-001 classes
])
ANSI = re.compile(r"\x1b\[[0-9;?$]*[a-zA-Z]|\x1b[()][A-B0-9]|\x1b[=><]")
PROMPT = "scala> "

checks: list[dict] = []


def check(cid: str, ok: bool, detail: str = "") -> bool:
    checks.append({"id": cid, "status": "pass" if ok else "fail", "detail": detail[:400]})
    print(f"[check] {cid}: {'PASS' if ok else 'FAIL'} {detail[:200]}")
    return ok


def java_cli(mode: str, url: str, *rest: str) -> subprocess.CompletedProcess:
    argv = ["java", "-cp", CP, "raider.spike.oai.OpenAiCompatSpike", mode, url, KEY, *rest]
    return subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                          text=True, timeout=120, cwd=ROOT)


class Repl:
    def __init__(self):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        env = dict(os.environ)
        env["NO_COLOR"] = "1"
        env["TERM"] = "xterm-256color"
        pty_home = OUT / "pty-home"
        shutil.rmtree(pty_home, ignore_errors=True)
        pty_home.mkdir(parents=True, exist_ok=True)
        env["HOME"] = str(pty_home)  # isolated from user home (review N6)
        self.proc = subprocess.Popen(
            ["java", "-cp", CP, "dotty.tools.repl.Main", "-usejavacp", "-color", "never"],
            stdin=slave, stdout=slave, stderr=slave, close_fds=True, env=env, cwd=ROOT)
        os.close(slave)
        self.raw = open(OUT / "repl-transcript-raw.txt", "wb")
        self.buffer = ""

    def pump(self, timeout: float):
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
                for q, a in [(b"\x1b[c", b"\x1b[?62;22c")]:
                    if q in data:
                        os.write(self.master, a)

    def clean(self) -> str:
        return ANSI.sub("", self.buffer)

    def wait_prompt(self, timeout: float = 90.0) -> str:
        start = len(self.buffer)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.pump(0.3)
            cleaned = ANSI.sub("", self.buffer[start:])
            idx = cleaned.rfind(PROMPT)
            if idx >= 0:
                return cleaned[: idx + len(PROMPT)]
        raise TimeoutError(f"no prompt; tail={self.clean()[-300:]!r}")

    def line(self, code: str, timeout: float = 90.0) -> str:
        os.write(self.master, (code + "\r").encode())
        return self.wait_prompt(timeout)

    def finish(self, timeout: float = 20.0) -> int:
        os.write(self.master, b":quit\r")
        deadline = time.monotonic() + timeout
        while self.proc.poll() is None and time.monotonic() < deadline:
            self.pump(0.2)
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait()
            rc = -9
        else:
            rc = self.proc.returncode
        self.pump(0.5)
        self.raw.close()
        return rc


def main() -> int:
    (OUT / "gateway-disconnect.log").write_text("")
    os.environ["SPIKE_DISCONNECT_LOG"] = str(OUT / "gateway-disconnect.log")
    url, srv = mock_gateway.start()
    print(f"[harness] mock gateway at {url}")

    try:
        p = java_cli("ask", url, "fast", "проверь проект")
        (OUT / "oai-01-ask.log").write_text(p.stdout + p.stderr)
        check("OAI-01-nonstream",
              p.returncode == 0 and "[final] Fragments of the answer with русский ✓" in p.stdout
              and "total=26" in p.stdout,
              f"exit={p.returncode}")

        p = java_cli("ask-stream", url, "fast", "проверь проект")
        (OUT / "oai-02-stream.log").write_text(p.stdout + p.stderr)
        check("OAI-02-stream-equivalence",
              p.returncode == 0 and "[final] Fragments of the answer with русский ✓" in p.stdout
              and "sawDone=true" in p.stdout and "total=26" in p.stdout,
              f"exit={p.returncode} fragmented frames reassembled")

        p = java_cli("tool-stream", url, "fast", "найди README")
        (OUT / "oai-03-tool.log").write_text(p.stdout + p.stderr)
        tool_line = next((l for l in p.stdout.splitlines() if l.startswith("[tool-call]")), "")
        args_ok = False
        if tool_line:
            m = re.search(r"args=(\{.*\})", tool_line)
            if m:
                try:
                    args = json.loads(m.group(1))
                    args_ok = args == {"path": "README.md"}
                except json.JSONDecodeError:
                    args_ok = False
        check("OAI-03-tool-args-assembled",
              p.returncode == 0 and "id=call_1" in tool_line and "name=fs.read" in tool_line
              and args_ok and "[tool-execute]" not in p.stdout,
              f"exit={p.returncode} tool_line={tool_line!r} args_valid={args_ok}")

        bad = subprocess.run(
            ["java", "-cp", CP, "raider.spike.oai.OpenAiCompatSpike", "ask", url,
             "wrong-key", "fast", "-"],
            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120, cwd=ROOT)
        (OUT / "oai-04-auth.log").write_text(bad.stdout + bad.stderr)
        check("OAI-04-auth-classified",
              bad.returncode == 10 and "[classified] ProviderAuth http=401" in bad.stdout,
              f"exit={bad.returncode}")

        p = java_cli("cancel-stream", url, "fast", "900")
        (OUT / "oai-05-cancel.log").write_text(p.stdout + p.stderr)
        disconnect_seen = False
        for _ in range(20):
            if "client disconnected (stream-slow)" in (OUT / "gateway-disconnect.log").read_text():
                disconnect_seen = True
                break
            time.sleep(0.25)
        check("OAI-05-cancel-midstream",
              p.returncode == 0 and "cleanly=true" in p.stdout
              and "noPartialToolExecution=true" in p.stdout and disconnect_seen,
              f"exit={p.returncode} gateway_saw_disconnect={disconnect_seen}")

        p = java_cli("malformed-stream", url, "fast", "проверь")
        (OUT / "oai-07-malformed.log").write_text(p.stdout + p.stderr)
        check("OAI-07-malformed-sse",
              p.returncode == 21 and "[classified] StreamProtocol" in p.stdout,
              f"exit={p.returncode}")

        p = java_cli("eof-stream", url, "fast", "проверь")
        (OUT / "oai-08-eof.log").write_text(p.stdout + p.stderr)
        check("OAI-08-premature-eof",
              p.returncode == 21 and "[classified] StreamProtocol" in p.stdout
              and "before terminal event" in p.stdout,
              f"exit={p.returncode}")

        hang = subprocess.run(
            ["java", "-cp", CP, "raider.spike.oai.OpenAiCompatSpike", "cancel-headers",
             url, KEY, "fast", "700"],
            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120, cwd=ROOT)
        (OUT / "oai-09-cancel-headers.log").write_text(hang.stdout + hang.stderr)
        hang_disconnect = False
        for _ in range(28):
            if "client disconnected (hang-headers)" in (OUT / "gateway-disconnect.log").read_text():
                hang_disconnect = True
                break
            time.sleep(0.25)
        check("OAI-09-cancel-before-headers",
              hang.returncode == 0 and "[cancelled-before-headers] cleanly=true" in hang.stdout,
              f"exit={hang.returncode} server_saw_withdrawal={hang_disconnect}")

        # OAI-06: same client from the real REPL
        repl = Repl()
        try:
            repl.wait_prompt(120)
            out = repl.line("import raider.spike.oai.OpenAiCompatSpike")
            out = repl.line(
                f'val answer = OpenAiCompatSpike.replAsk("{url}", "{KEY}", "fast", "проверь проект")',
                timeout=120)
            ok = ('answer: String = "Fragments of the answer with русский ✓"' in out)
            rc = repl.finish(30)
            (OUT / "repl-transcript.txt").write_text(repl.clean())
            check("OAI-06-repl-stream", ok and rc == 0,
                  f"repl_exit={rc} streamed_into_repl={ok}")
        finally:
            if repl.proc.poll() is None:
                repl.proc.kill()
                repl.proc.wait()
    finally:
        srv.shutdown()
        srv.server_close()

    failed = [c for c in checks if c["status"] != "pass"]
    (OUT / "summary.json").write_text(json.dumps(
        {"gateway_url": url, "checks": checks}, indent=2, ensure_ascii=False))
    print(f"\nchecks: {len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
