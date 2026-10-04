#!/usr/bin/env python3
"""Forbidden API / hygiene scanner for Raider Scala sources (nightly Phase 1.3).

Scans modules/**/*.scala for banned or dangerous constructs:
  * Thread.stop                       — deprecated, unsafe async exceptions
  * Await.result/Await.ready without a timeout argument — unbounded blocking
  * Runtime.exec / Runtime.getRuntime().exec            — use ProcessBuilder
  * System.exit outside modules/cli/src/main/scala/raider/cli/Main.scala
  * Hardcoded secret shapes (sk-/rk- keys, long api_key/secret/password/token
    literals, AWS AKIA..., GitHub gh?_...) — credentials come from env/helpers
    only (AGENTS.md trust boundary); short placeholder keys in test fixtures
    (<= 15 chars) are allowed.
  * Double money arithmetic — USD amounts must use the Long-based MicroUsd

Exit codes: 0 = clean, 1 = violations found, 2 = internal/usage error.
Pass --self-test to run the embedded positive/negative fixtures first
(the gate must prove it actually detects, not merely that the tree is clean).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCAN_DIR = ROOT / "modules"
ALLOWED_SYSTEM_EXIT = {
    "modules/cli/src/main/scala/raider/cli/Main.scala",
}

# Each rule: (rule id, compiled regex, human explanation). Regexes are applied
# to comment-stripped source line by line (best-effort comment stripping).
RULES: list[tuple[str, re.Pattern, str]] = [
    (
        "thread-stop",
        re.compile(r"\bThread\s*\.\s*stop\b"),
        "Thread.stop is deprecated and unsafe; use interruption/cancellation",
    ),
    (
        "await-no-timeout",
        re.compile(r"\bAwait\s*\.\s*(result|ready)\s*\((?:[^(),]|\([^()]*\))*\)"),
        "Await.result/ready without a timeout blocks unboundedly",
    ),
    (
        "runtime-exec",
        re.compile(r"\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\b|\bRuntime\s*\.\s*exec\b"),
        "Runtime.exec is error-prone; use scala.sys.process / ProcessBuilder",
    ),
    (
        "system-exit",
        re.compile(r"\bSystem\s*\.\s*exit\s*\("),
        "System.exit is only allowed in raider.cli.Main (process entrypoint)",
    ),
    (
        "secret-literal",
        re.compile(
            r'"(?:sk|rk)-[A-Za-z0-9_-]{8,}"'          # openai-style key literal
            r"|(?i:api[_-]?key|secret|password|passwd|token)\s*[:=]\s*\"[^\"]{16,}\""
            r"|\bAKIA[0-9A-Z]{16}\b"                   # aws access key id
            r"|\bgh[pousr]_[A-Za-z0-9]{30,}\b"         # github token
        ),
        "hardcoded credential shape; credentials must come from env/helper",
    ),
    (
        "double-money",
        re.compile(
            r"(?i:\b(?:price|cost|usd|fee|amount)[a-z0-9_]*\s*:\s*Double\b)"
            r"|(?i:\b(?:price|cost|usd|fee|amount)[a-z0-9_]*\s*=\s*[0-9]+\.[0-9]+)"
            r"|(?i:\b(?:price|cost|usd|fee|amount)[a-z0-9_]*\s*\*)"
        ),
        "money must use Long-based MicroUsd, never Double arithmetic",
    ),
]

COMMENT = re.compile(r"//.*$|/\*.*?\*/", re.DOTALL)


def strip_comments(text: str) -> str:
    # Best-effort: block comments first (possibly multi-line), then line comments.
    text = re.sub(r"/\*.*?\*/", lambda m: " " * len(m.group(0)), text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", text)


def scan_file(path: pathlib.Path) -> list[tuple[str, int, str]]:
    rel = path.relative_to(ROOT).as_posix()
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError) as exc:
        print(f"[forbidden-apis] WARN unreadable {rel}: {exc}", file=sys.stderr)
        return []
    text = strip_comments(text)
    hits: list[tuple[str, int, str]] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        for rule_id, rx, why in RULES:
            if rule_id == "system-exit" and rel in ALLOWED_SYSTEM_EXIT:
                continue
            m = rx.search(line)
            if m:
                hits.append((rule_id, lineno, m.group(0)[:80]))
    return hits


def self_test() -> bool:
    must_flag = {
        "thread-stop": 'val t = Thread.stop()',
        "await-no-timeout": 'Await.result(fut)',
        "runtime-exec": 'Runtime.getRuntime().exec("ls")',
        "system-exit": 'System.exit(1)',
        "secret-literal": 'val k = "sk-proj1234567890abcdef"',
        "double-money": 'val priceTotal: Double = price * qty',
    }
    must_pass = [
        'System.exit(0) allowed in raider.cli.Main',  # checked by location, not text
        'Await.result(fut, 5.seconds)',
        'val k = "test-key-123"',                     # short test placeholder
        'MicroUsd.ofDollars(3) + MicroUsd.ofDollars(4)',
        'budget.costEstimate // documented, not arithmetic',
        'val j = worker.start("bg"); j.await()',
        '// comment mentioning Thread.stop must not trip the scanner',
    ]
    ok = True
    for rule_id, snippet in must_flag.items():
        rx = dict((r[0], r[1]) for r in RULES)[rule_id]
        if not rx.search(strip_comments(snippet)):
            print(f"[forbidden-apis] SELF-TEST FAIL: {rule_id} did not detect: {snippet}")
            ok = False
    negative_rx = [r[1] for r in RULES if r[0] not in ("system-exit",)]
    for snippet in must_pass:
        for rx in negative_rx:
            if rx.search(strip_comments(snippet)):
                print(f"[forbidden-apis] SELF-TEST FAIL: false positive on: {snippet}")
                ok = False
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(prog="forbidden_apis")
    ap.add_argument("--self-test", action="store_true",
                    help="verify detection fixtures, then scan")
    args = ap.parse_args()

    if args.self_test and not self_test():
        return 1

    violations: list[str] = []
    files = sorted(SCAN_DIR.rglob("*.scala"))
    if not files:
        print("[forbidden-apis] FAIL: no scala sources found under modules/")
        return 2
    for path in files:
        for rule_id, lineno, match in scan_file(path):
            rel = path.relative_to(ROOT).as_posix()
            violations.append(f"{rel}:{lineno}: [{rule_id}] {match}")
    if violations:
        print(f"[forbidden-apis] FAIL: {len(violations)} violation(s)")
        for v in violations:
            print("  " + v)
        return 1
    print(f"[forbidden-apis] PASS: {len(files)} files scanned, 0 violations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
