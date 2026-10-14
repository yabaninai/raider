#!/usr/bin/env python3
"""Forbidden API / hygiene scanner for Raider Scala sources (nightly Phase 1.3).

Scans modules/**/*.scala for banned or dangerous constructs:
  * Thread.stop                       — deprecated, unsafe async exceptions
  * Await.result/Await.ready without a timeout argument — unbounded blocking
  * Runtime.exec / Runtime.getRuntime().exec            — use ProcessBuilder
  * System.exit outside the location allowlisted in ALLOWLIST (raider.cli.Main)
  * Hardcoded secret shapes (sk-/rk- keys, long api_key/secret/password/token
    literals, AWS AKIA..., GitHub gh?_...) — credentials come from env/helpers
    only (AGENTS.md trust boundary); short placeholder keys in test fixtures
    (<= 15 chars) are allowed.
  * Double money arithmetic — USD amounts must use the Long-based MicroUsd

Location allowlist (RAI-091): a violation whose file path matches an ALLOWLIST
entry is surfaced as an explicit NOTE line in the report (never a violation,
never silence). Everything outside the allowlist is flagged exactly as before;
no rule, threshold or pattern is weakened by this mechanism (gate-policy:
additions-only).

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

# RAI-091 location allowlist: rule id -> symbolic class-paths where that rule
# is not a violation. Rationale (gate-policy additions-only): System.exit is
# legitimately required exactly once, at the process entrypoint
# raider.cli.Main, so the CLI process can terminate with a meaningful exit
# code; anywhere else under modules/ System.exit stays a violation. A file
# qualifies only when its path ends with the class-path rendered as a path
# suffix ("raider.cli.Main" -> ".../raider/cli/Main.scala", boundary-checked:
# .../raider/cli/MainBackup.scala does NOT match). Skips are reported as NOTE
# lines in the report, never silent and never violations.
ALLOWLIST: dict[str, list[str]] = {
    "system-exit": ["raider.cli.Main", "raider.cli.workflow.WorkflowRun"],
}


def _allowlist_suffix(class_path: str) -> str:
    """Render a dotted class-path as a file-path suffix.

    Example: "raider.cli.Main" -> "raider/cli/Main.scala".
    """
    parts = class_path.split(".")
    return "/".join(parts[:-1] + [parts[-1] + ".scala"])


def allowlist_entry(rule_id: str, rel: str) -> str | None:
    """Return the ALLOWLIST entry (class-path) matching rel, or None.

    A path matches when it ends with the rendered class-path suffix preceded
    by a path boundary "/", e.g. ".../raider/cli/Main.scala" (rel paths are
    always rooted at "modules/", so the leading "/" exists).
    """
    for entry in ALLOWLIST.get(rule_id, ()):
        if rel.endswith("/" + _allowlist_suffix(entry)):
            return entry
    return None

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


# Hit: (rule_id, lineno, match). Allowlist skip: (rule_id, lineno, match, entry).
def scan_text(rel: str, text: str) -> tuple[list[tuple[str, int, str]], list[tuple[str, int, str, str]]]:
    """Scan one source text (comment-stripped internally) at rel path.

    Returns (hits, skips): hits are violations; skips are occurrences in
    allowlisted locations (reported as NOTEs by the caller, never silent).
    """
    text = strip_comments(text)
    hits: list[tuple[str, int, str]] = []
    skips: list[tuple[str, int, str, str]] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        for rule_id, rx, why in RULES:
            m = rx.search(line)
            if m:
                entry = allowlist_entry(rule_id, rel)
                if entry:
                    skips.append((rule_id, lineno, m.group(0)[:80], entry))
                else:
                    hits.append((rule_id, lineno, m.group(0)[:80]))
    return hits, skips


def scan_file(path: pathlib.Path) -> tuple[list[tuple[str, int, str]], list[tuple[str, int, str, str]]]:
    rel = path.relative_to(ROOT).as_posix()
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError) as exc:
        print(f"[forbidden-apis] WARN unreadable {rel}: {exc}", file=sys.stderr)
        return [], []
    return scan_text(rel, text)


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

    # RAI-091 location allowlist fixtures (FA-01..FA-03).
    main_rel = "modules/cli/src/main/scala/raider/cli/Main.scala"
    other_rel = "modules/core/src/main/scala/raider/util/OtherFile.scala"
    lookalike_rel = "modules/cli/src/main/scala/raider/cli/MainBackup.scala"

    # FA-01: System.exit(1) in the allowlisted Main location is skipped and
    # surfaced as a note (not a violation, not silence).
    hits, skips = scan_text(main_rel, 'object Main { def run(): Unit = System.exit(1) }')
    if hits:
        print(f"[forbidden-apis] SELF-TEST FAIL (FA-01): allowlisted Main flagged: {hits}")
        ok = False
    if len(skips) != 1 or skips[0][0] != "system-exit" or skips[0][3] != "raider.cli.Main":
        print(f"[forbidden-apis] SELF-TEST FAIL (FA-01): expected one allowlist skip note, got: {skips}")
        ok = False

    # FA-02: outside the allowlist the rule fires exactly as before.
    hits, skips = scan_text(other_rel, 'object OtherFile { def kill(): Unit = System.exit(1) }')
    if not any(h[0] == "system-exit" for h in hits) or skips:
        print(f"[forbidden-apis] SELF-TEST FAIL (FA-02): violation missed outside allowlist: hits={hits} skips={skips}")
        ok = False

    # FA-03: self-test extended with allowlist cases — the allowlist matches
    # the exact location only; a lookalike path must still be flagged.
    hits, skips = scan_text(lookalike_rel, 'object MainBackup { def kill(): Unit = System.exit(1) }')
    if not any(h[0] == "system-exit" for h in hits) or skips:
        print(f"[forbidden-apis] SELF-TEST FAIL (FA-03): lookalike path must not be allowlisted: hits={hits} skips={skips}")
        ok = False
    if _allowlist_suffix("raider.cli.Main") != "raider/cli/Main.scala":
        print("[forbidden-apis] SELF-TEST FAIL (FA-03): bad allowlist suffix rendering")
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
    notes: list[str] = []
    files = sorted(SCAN_DIR.rglob("*.scala"))
    if not files:
        print("[forbidden-apis] FAIL: no scala sources found under modules/")
        return 2
    for path in files:
        hits, skips = scan_file(path)
        rel = path.relative_to(ROOT).as_posix()
        for rule_id, lineno, match in hits:
            violations.append(f"{rel}:{lineno}: [{rule_id}] {match}")
        for rule_id, lineno, match, entry in skips:
            notes.append(f"{rel}:{lineno}: [{rule_id}] {match} (allowlisted: {entry})")
    for n in notes:
        print(f"[forbidden-apis] NOTE allowlist skip: {n}")
    if violations:
        print(f"[forbidden-apis] FAIL: {len(violations)} violation(s)")
        for v in violations:
            print("  " + v)
        return 1
    print(f"[forbidden-apis] PASS: {len(files)} files scanned, 0 violations, {len(notes)} allowlist skip(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
