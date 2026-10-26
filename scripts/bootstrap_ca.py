#!/usr/bin/env python3
"""Resolve a TLS CA bundle for bootstrap downloads (RAI-218).

A stock macOS python.org distribution does not trust the system keychain and
its bundled OpenSSL looks for a CA file that does not exist, so the pinned
coordinate downloads in fetch_toolchain.py fail with CERTIFICATE_VERIFY_FAILED
unless a CA bundle is provided. Observed on the RAI-203/RAI-207 lanes
(2026-10-25) as a manual SSL_CERT_FILE workaround; this resolver automates it.

Protocol (consumed by scripts/bootstrap.sh):
  stdout  exactly one line: an absolute bundle path to export as SSL_CERT_FILE,
          or the token "none" when the interpreter defaults already resolve.
  stderr  human-readable progress and error lines.
  exit    0 = resolved (including "none"), 1 = typed failure, never a traceback.

Resolution order: certifi when importable; else the macOS system bundle
/etc/ssl/cert.pem (override the candidate path with RAIDER_BOOTSTRAP_SYSTEM_CA
for tests and sandboxes); else the interpreter default verify paths when
already usable; else a typed failure with a remediation hint. A caller-set
SSL_CERT_FILE is honored by bootstrap.sh and never probed here.

This file is bootstrap glue (language policy), not product code.
"""
from __future__ import annotations

import os
import ssl
import sys

DEFAULT_SYSTEM_BUNDLE = "/etc/ssl/cert.pem"


def log(message: str) -> None:
    print(f"bootstrap: {message}", file=sys.stderr)


def certifi_bundle() -> str | None:
    """Return the certifi bundle path when certifi is importable and usable."""
    try:
        import certifi
    except ImportError:
        return None
    where = certifi.where()
    if isinstance(where, str) and os.path.isfile(where):
        return where
    return None


def system_bundle() -> str | None:
    """Return the readable PEM bundle at the system candidate path."""
    candidate = os.environ.get("RAIDER_BOOTSTRAP_SYSTEM_CA", DEFAULT_SYSTEM_BUNDLE)
    if os.path.isfile(candidate) and os.access(candidate, os.R_OK):
        return candidate
    return None


def default_paths_usable() -> bool:
    """True when the interpreter's OpenSSL default CA locations exist."""
    paths = ssl.get_default_verify_paths()
    if paths.cafile and os.path.isfile(paths.cafile):
        return True
    return bool(paths.capath) and os.path.isdir(paths.capath)


def fail() -> int:
    """Typed failure: named condition, remediation hints, exit 1, no traceback."""
    candidate = os.environ.get("RAIDER_BOOTSTRAP_SYSTEM_CA", DEFAULT_SYSTEM_BUNDLE)
    paths = ssl.get_default_verify_paths()
    print(
        "bootstrap: error: no TLS CA bundle resolved for pinned-coordinate"
        " downloads (RAI-218)",
        file=sys.stderr,
    )
    print(f"  certifi: not importable by {sys.executable}", file=sys.stderr)
    print(
        f"  system bundle {candidate}: missing or unreadable",
        file=sys.stderr,
    )
    print(
        f"  interpreter default CA file: {paths.cafile or '<none>'} (missing)",
        file=sys.stderr,
    )
    print(
        "Remediation: run the 'Install Certificates.command' shipped next to"
        " your python.org installation"
        f" (Python {sys.version.split()[0]}) to install certifi, or install"
        f" certifi into this interpreter ('{os.path.basename(sys.executable)}"
        " -m pip install certifi'), or point SSL_CERT_FILE at a PEM CA bundle"
        " and rerun 'sh scripts/bootstrap.sh'.",
        file=sys.stderr,
    )
    return 1


def main() -> int:
    bundle = certifi_bundle()
    if bundle is not None:
        log(f"CA bundle: certifi ({bundle})")
        print(bundle)
        return 0
    bundle = system_bundle()
    if bundle is not None:
        log(f"CA bundle: system ({bundle})")
        print(bundle)
        return 0
    if default_paths_usable():
        log("CA bundle: interpreter defaults already usable; no SSL_CERT_FILE override")
        print("none")
        return 0
    return fail()


if __name__ == "__main__":
    raise SystemExit(main())
