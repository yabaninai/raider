#!/usr/bin/env python3
"""Secrets scanner + dependency licensing summary for the security profile (RAI-069).

Two checks in one gate script, modeled on forbidden_apis.py:

  (a) secrets-scan: scans the CHANGED files of the current tree for
      credential shapes on added lines of non-test files. The change set is
      determined INTERNALLY, not via stdin: the RAI-004 runner executes
      registry gate argv with subprocess.run(cwd=ROOT) — no shell, no stdin —
      and the child inherits the environment, so this script imports
      scripts/quality/quality.py (same directory) and reuses the runner's own
      change detection: QUALITY_BASE_MANIFEST manifest-diff mode (snapshot
      lanes without git) or `git status --porcelain -z` vs HEAD (git
      checkouts). Scanned lines:
        - added file                      -> every line;
        - modified file, git mode         -> '+ ' lines of `git diff HEAD -U0`;
        - modified file, manifest mode    -> the whole current file (a base
          manifest stores hashes, not content, so a line-level diff is
          impossible there; scanning MORE lines is the fail-safe direction
          and never weakens the gate).
      Whitelist (exactly the RAI-069 contract): test files (basename
      *Test*/*Spec*, path segment /tests/ or /test/) and fixture placeholder
      values (dummy|example|test|sentinel|redacted, case-insensitive, matched
      INSIDE the secret value; real credentials do not contain these words).
      A real-shaped secret in a non-test file is a finding regardless of file
      type (SEC-02 verify-negative: a planted secret is caught, never passed).
      Findings are reported MASKED — the raw value never reaches stdout, gate
      logs or evidence (AGENTS.md: no secrets in tool output).

  (b) licensing summary: libraryDependencies parsed from build.sbt (regex),
      reported as group:artifact INFORMATIOn only — never a failure. License
      classification is license_audit.py's gate; an OWASP/CVE scanner is an
      explicit non-goal of RAI-069.

Exit codes: 0 = clean (JSON report on stdout), 1 = secret(s) found (masked
report JSON on stdout), 2 = scanner/infrastructure error (change detection
impossible, git diff failed) — an errored scan is NEVER reported as passed.
--self-test proves detection + masking on embedded fixtures (a fake sk- key
written to a tmp file must be detected and must not leak through the report)
WITHOUT change detection, so it also passes in isolated snapshot lanes; the
registry gate deliberately runs the real scan, and quality.py self-check
executes --self-test separately (RAI-069 self-check extension).
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import quality  # RAI-004 runner: single source of truth for change detection

ROOT = pathlib.Path(__file__).resolve().parents[2]

# Each rule: (rule id, compiled regex, index of the capture group holding the
# secret VALUE; 0 = whole match). Applied line by line.
PATTERNS: list[tuple[str, re.Pattern, int]] = [
    ("openai-style-key", re.compile(r"sk-[A-Za-z0-9]{20,}"), 0),
    ("aws-access-key-id", re.compile(r"\bAKIA[0-9A-Z]{16}\b"), 0),
    ("private-key-block", re.compile(r"-----BEGIN (RSA|EC|OPENSSH) PRIVATE KEY"), 0),
    ("yb-token", re.compile(r"yb_[0-9a-f]{64}"), 0),
    ("yag-token", re.compile(r"yag_[0-9a-f]{32}"), 0),
    ("bearer-header", re.compile(r"(?i:authorization)\s*:\s*(?i:bearer)\s+(\S+)"), 1),
    ("argv-credential", re.compile(r"--(?:password|api-key)=(\S+)"), 1),
]

# Fixture placeholder values are whitelisted inside the matched value only.
PLACEHOLDER = re.compile(r"(?i:dummy|example|test|sentinel|redacted)")

# Test-file whitelist, exactly the RAI-069 contract: basename *Test*/*Spec*
# (capitalized, Scala convention) or a /tests/ (or /test/) path segment.
TEST_FILE = re.compile(r"(?:^|/)[^/]*(?:Test|Spec)[^/]*$|/tests?/")

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")

MAX_SCAN_BYTES = 5_000_000


def scanner_fail(msg: str) -> "NoReturn":
    print(f"[security-secrets-scan] FAIL: {msg}", file=sys.stderr)
    raise SystemExit(2)


def detect_changes() -> tuple[str, list[dict]]:
    """Change set via the imported RAI-004 runner (not a second detector).

    QUALITY_BASE_MANIFEST set -> manifest-diff (snapshot lane mode); unset ->
    `git status --porcelain -z` vs HEAD in ROOT. Any failure is a scanner
    error (exit 2): an undetectable change set is never reported as clean.
    """
    base = os.environ.get("QUALITY_BASE_MANIFEST")
    try:
        _files, changes = quality.fingerprint(base)
    except quality.GitUnavailableError as exc:
        scanner_fail(f"cannot determine changed files ({exc}); set "
                     "QUALITY_BASE_MANIFEST or run inside a git checkout")
    except SystemExit:  # quality.fail: baseline manifest missing/malformed
        scanner_fail("change detection aborted by the runner (baseline manifest "
                     "missing or malformed); see the [quality] FAIL line above")
    return ("manifest-diff" if base else "git-status"), changes


def parse_git_diff(diff_text: str) -> list[tuple[int, str]]:
    """(new-file lineno, text) of the '+' lines of a unified diff (-U0).

    Header lines precede the first @@ hunk and are skipped; '-' and
    '\\ No newline' lines do not advance the new-file line counter.
    """
    added: list[tuple[int, str]] = []
    lineno = 0
    in_hunk = False
    for raw in diff_text.splitlines():
        if raw.startswith("@@"):
            m = HUNK.match(raw)
            if m:
                lineno = int(m.group(1))
                in_hunk = True
            continue
        if not in_hunk:
            continue
        if raw.startswith("+"):
            added.append((lineno, raw[1:]))
            lineno += 1
        elif raw.startswith("-") or raw.startswith("\\"):
            continue
        else:
            lineno += 1
    return added


def git_diff_added_lines(rel: str) -> list[tuple[int, str]]:
    proc = subprocess.run(
        ["git", "diff", "HEAD", "--unified=0", "--", rel],
        cwd=ROOT, capture_output=True, timeout=60)
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).decode("utf-8", errors="replace")
        scanner_fail(f"git diff failed for {rel}: {err.strip()[:200]}")
    return parse_git_diff(proc.stdout.decode("utf-8", errors="replace"))


def added_lines(rel: str, kind: str, mode: str) -> list[tuple[int, str]]:
    """Added lines of one changed file (mode semantics: module docstring).

    Unreadable/special/oversized files are skipped with a WARN (mirrors
    forbidden_apis.py); a vanished file is not a secret finding.
    """
    if kind == "deleted":
        return []
    path = ROOT / rel
    if path.is_symlink() or not path.is_file():
        print(f"[security-secrets-scan] WARN not a regular file, skipped: {rel}",
              file=sys.stderr)
        return []
    try:
        if path.stat().st_size > MAX_SCAN_BYTES:
            print(f"[security-secrets-scan] WARN file over {MAX_SCAN_BYTES} "
                  f"bytes, skipped: {rel}", file=sys.stderr)
            return []
        text = path.read_bytes().decode("utf-8", errors="replace")
    except OSError as exc:
        print(f"[security-secrets-scan] WARN unreadable {rel}: {exc}",
              file=sys.stderr)
        return []
    if kind == "added" or mode != "git-status":
        return list(enumerate(text.splitlines(), start=1))
    return git_diff_added_lines(rel)


def mask_secret(value: str) -> str:
    """Keep a 4-char head + 2-char tail for triage; the raw value never
    reaches stdout, logs or evidence (AGENTS.md trust boundary)."""
    if len(value) <= 6:
        return "*" * len(value)
    return value[:4] + "*" * (len(value) - 6) + value[-2:]


def scan_line(rel: str, lineno: int, line: str) -> list[dict]:
    if is_test_file(rel):
        return []  # test-file whitelist enforced here for EVERY caller
    findings: list[dict] = []
    for rule_id, rx, value_group in PATTERNS:
        for m in rx.finditer(line):
            value = m.group(value_group) if value_group else m.group(0)
            if PLACEHOLDER.search(value):
                continue  # fixture placeholder value (RAI-069 whitelist)
            findings.append({"path": rel, "line": lineno,
                             "pattern": rule_id, "masked": mask_secret(value)})
    return findings


def is_test_file(rel: str) -> bool:
    return TEST_FILE.search(rel) is not None


DEP = re.compile(r'"([^"]+)"\s*(?:%%|%)\s*"([^"]+)"')


def licensing_summary() -> dict:
    """group:artifact inventory from build.sbt — INFORMATION only: never
    fails the gate. License classification lives in license_audit.py; CVE
    scanning is an explicit RAI-069 non-goal."""
    try:
        text = (ROOT / "build.sbt").read_text(encoding="utf-8")
    except OSError as exc:
        return {"information_only": True, "error": f"build.sbt unreadable: {exc}",
                "count": 0, "dependencies": []}
    deps = sorted({f"{g}:{a}" for g, a in DEP.findall(text)})
    return {"information_only": True, "count": len(deps), "dependencies": deps}


def self_test() -> bool:
    """Prove detection + masking on embedded fixtures (SEC-02 basis).

    Every must-flag fixture line is assembled from concatenated literals so
    that THIS source file never matches the scanner when the gate scans its
    own diff. No change detection here: --self-test must pass in an isolated
    snapshot lane without git and without a base manifest.
    """
    fake = "sk-" + "Q7wE9rT2yU4iO6pA8sD1fG3z"  # 24 chars, no placeholder word
    ok = True

    def check_flag(rule_id: str, line: str, rel: str = "fixture/planted.txt") -> None:
        nonlocal ok
        hits = [f for f in scan_line(rel, 7, line) if f["pattern"] == rule_id]
        if not hits:
            print(f"[security-secrets-scan] SELF-TEST FAIL: {rule_id} "
                  f"did not detect: {line}")
            ok = False

    def check_pass(line: str, why: str) -> None:
        nonlocal ok
        if scan_line("fixture/planted.txt", 7, line):
            print(f"[security-secrets-scan] SELF-TEST FAIL: false positive "
                  f"({why}): {line}")
            ok = False

    must_flag = [
        ("openai-style-key", 'key = "' + fake + '"'),
        ("aws-access-key-id", "aws := " + "AKIA" + "Q2W3E4R5T6YU7I8A"),
        ("private-key-block", "-----BEGIN " + "RSA" + " PRIVATE KEY-----"),
        ("private-key-block", "-----BEGIN " + "OPENSSH" + " PRIVATE KEY-----"),
        ("yb-token", "tok(" + "yb_" + "0123456789abcdef" * 4 + ")"),
        ("yag-token", "tok(" + "yag_" + "0123456789abcdef" * 2 + ")"),
        ("bearer-header", "Authorization" + ": Bearer " + "t0k3nVal1dValue99x"),
        ("argv-credential", "run --" + "password=" + "hunter2real"),
        ("argv-credential", "run --" + "api-key=" + "K9L8M7N6O5P4Q3R2"),
    ]
    for rule_id, line in must_flag:
        check_flag(rule_id, line)

    must_pass = [
        'key = "' + "sk-" + "test" + "123456789012345678" + '"',  # placeholder in value
        "run --" + "password=" + "dummy",                          # placeholder in value
        "Authorization" + ": Bearer " + "sentinel",                # placeholder in value
        "run --" + "password=" + " (empty value is not matched)",
        "Bearer without an auth header is not a finding",
    ]
    for line in must_pass:
        check_pass(line, "whitelisted/no match")

    if scan_line("modules/x/src/test/scala/Thing" + "Spec.scala", 3,
                 'key = "' + fake + '"'):
        print("[security-secrets-scan] SELF-TEST FAIL: *Spec* basename must be "
              "whitelisted as a test file")
        ok = False
    if not is_test_file("modules/core/src/test/scala/Foo.scala"):
        print("[security-secrets-scan] SELF-TEST FAIL: /test/ segment must be "
              "whitelisted as a test file")
        ok = False

    got = parse_git_diff(
        "diff --git a/f.txt b/f.txt\n"
        "index 1111111..2222222 100644\n"
        "--- a/f.txt\n"
        "+++ b/f.txt\n"
        "@@ -1,2 +1,3 @@\n"
        " context\n"
        "-old\n"
        "+" + fake + "\n"
        "tail\n"
        "@@ -40,0 +41,1 @@\n"
        "+inserted at 41\n")
    if got != [(2, fake), (41, "inserted at 41")]:
        print(f"[security-secrets-scan] SELF-TEST FAIL: git diff parse gave {got}")
        ok = False

    # Task-mandated flow: a fake secret in a tmp FILE must be detected and
    # must not leak through the JSON report (masked output).
    with tempfile.TemporaryDirectory(prefix="security-selftest-") as td:
        p = pathlib.Path(td) / "planted-fixture.txt"
        p.write_text('key = "' + fake + '"\n', encoding="utf-8")
        round_trip = p.read_text(encoding="utf-8")
    planted = scan_line("planted-fixture.txt", 1, round_trip.strip())
    if len(planted) != 1 or planted[0]["pattern"] != "openai-style-key":
        print(f"[security-secrets-scan] SELF-TEST FAIL: tmp-file fixture "
              f"detection gave {planted}")
        ok = False
    dumped = json.dumps(planted)
    if fake in dumped or fake in planted[0].get("masked", ""):
        print("[security-secrets-scan] SELF-TEST FAIL: raw fixture value leaked "
              "through the report (masking broken)")
        ok = False
    if planted and planted[0]["masked"] != mask_secret(fake):
        print("[security-secrets-scan] SELF-TEST FAIL: masked value mismatch")
        ok = False
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(prog="security-checks")
    ap.add_argument("--self-test", action="store_true",
                    help="prove detection/masking on embedded fixtures, then exit")
    args = ap.parse_args()
    if args.self_test:
        return 0 if self_test() else 1

    mode, changes = detect_changes()
    findings: list[dict] = []
    scanned = 0
    skipped_test = 0
    for ch in changes:
        rel, kind = ch["path"], ch["kind"]
        if kind == "deleted":
            continue
        if is_test_file(rel):
            skipped_test += 1
            continue
        scanned += 1
        for lineno, line in added_lines(rel, kind, mode):
            findings.extend(scan_line(rel, lineno, line))

    report = {
        "check": "security-secrets-scan",
        "card": "RAI-069",
        "mode": mode,
        "changed_files": len(changes),
        "scanned_files": scanned,
        "skipped_test_files": skipped_test,
        "findings": findings,
        "licensing": licensing_summary(),
    }
    print(json.dumps(report, indent=2))
    if findings:
        print(f"[security-secrets-scan] FAIL: {len(findings)} finding(s) "
              "(values masked; see findings[] above)", file=sys.stderr)
        return 1
    print(f"[security-secrets-scan] PASS: {scanned} changed file(s) scanned, "
          f"{skipped_test} test file(s) whitelisted, 0 findings", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
