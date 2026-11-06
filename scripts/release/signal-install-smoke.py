#!/usr/bin/env python3
"""Exercise install-smoke termination while its Coursier launch is blocked."""

import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import time


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[2]
    release = (
        pathlib.Path(sys.argv[1]).resolve()
        if len(sys.argv) > 1
        else root / "artifacts/release/0.1.1"
    )
    manifest = json.loads((release / "release.json").read_text(encoding="utf-8"))
    version = manifest.get("version")
    if not isinstance(version, str) or not version:
        raise RuntimeError(f"release metadata has no usable version: {release}")
    print(f"[signal-install-smoke] release: {release} (version {version})")
    with tempfile.TemporaryDirectory(prefix="raider-sigterm-") as temporary:
        base = pathlib.Path(temporary)
        bin_dir = base / "bin"
        bin_dir.mkdir()
        marker = base / "coursier-started"
        blocker = bin_dir / "cs"
        blocker.write_text(
            "#!/bin/sh\n"
            f"printf started > {marker}\n"
            "while :; do sleep 1; done\n",
            encoding="utf-8",
        )
        blocker.chmod(0o755)
        install_tmp = base / "install-tmp"
        install_tmp.mkdir()
        environment = os.environ.copy()
        environment["PATH"] = f"{bin_dir}:{environment['PATH']}"
        environment["TMPDIR"] = str(install_tmp)
        process = subprocess.Popen(
            [
                "sh",
                str(root / "scripts/release/install-smoke.sh"),
                str(release / "raider-cli.jar"),
                version,
                str(release / "packaging/raider"),
                str(release),
            ],
            cwd=root,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
        deadline = time.monotonic() + 20
        while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.05)
        if not marker.exists():
            os.killpg(process.pid, signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=10)
            raise RuntimeError(
                f"Coursier launch did not start; exit={process.returncode}; "
                f"stdout={stdout!r}; stderr={stderr!r}"
            )
        os.killpg(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        if process.returncode != 143:
            raise RuntimeError(
                f"expected truthful SIGTERM exit 143, got {process.returncode}; "
                f"stdout={stdout!r}; stderr={stderr!r}"
            )
        leftovers = list(install_tmp.iterdir())
        if leftovers:
            raise RuntimeError(f"partial installation remained after SIGTERM: {leftovers}")
        print("[signal-install-smoke] PASS: SIGTERM exit 143; staging cleaned; no promotion")
        print(f"[signal-install-smoke] child output: {stdout.strip() or '(none)'}")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
