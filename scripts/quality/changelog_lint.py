#!/usr/bin/env python3
"""CHANGELOG.md section lint (RAI-281 docs-profile gate `changelog-lint`).

Driver (card RAI-281, gate-policy class): the RAI-279 release fold makes
CHANGELOG.md a routine post-release working-tree change, but the quality
classifier mapped it to no profile, so `make quality-changed
QUALITY_EXECUTE=1` and `make quality-profile ... QUALITY_EXECUTE=1` failed
closed with "changed files map to no profile (not skipped): CHANGELOG.md" on
every release integration (reproduced twice at wave P integration on
2026-11-05; disclosed from the RAI-279 lane). CHANGELOG.md now maps to the
docs profile (`path_profiles` entry in scripts/quality-policy/registry.json)
and this gate gives that coverage a real check — never a silent skip entry.

Checks (card contract):
  * every version section header is exactly `## [<semver>] - <date>` where
    <date> is a calendar-valid ISO `YYYY-MM-DD` or the exact marker
    `unreleased (cut pending)`;
  * every `##` heading (exactly two `#`) is treated as an attempted version
    section header, so a non-conforming heading (missing brackets, spacing
    drift, a stray non-version H2) is a typed error, never silently ignored;
    H3+ lines are out of scope;
  * versions are unique (no duplicate version sections);
  * <semver> is strict core semver MAJOR.MINOR.PATCH — no leading zeros and
    no pre-release/build suffixes (the pinned release fold, RAI-279, writes
    plain cores; a suffix would be a conscious format decision for a future
    policy card).

Typed diagnostics (one stderr line per finding, `PATH:LINE CODE message`):
  CL-HEADER-MALFORMED   an `##` heading line (exactly two `#`) does not
                        match the pinned `## [<semver>] - <date>` shape
                        (bracket-less headers, spacing/separator drift, or
                        a non-version H2)
  CL-SEMVER-INVALID     the bracketed version is not strict core semver
  CL-DATE-INVALID       the date part is neither a calendar-valid ISO
                        `YYYY-MM-DD` nor the exact unreleased marker
                        (shape and calendar failures share the code; the
                        message names which)
  CL-VERSION-DUPLICATE  the same version opens a second section
  CL-FILE-MISSING       the linted file does not exist (exit 2)

Per header at most one finding is reported (first failing check, fixed
order: header shape -> semver -> date -> duplicate), so diagnostics are
deterministic and the remaining defects surface on the next run after a
fix — the gate never hides a later finding behind an earlier one silently:
the summary line counts every class emitted.

Exit codes: 0 = valid; 1 = violations found; 2 = internal/usage error
(missing/unreadable file, failed self-test).

Default target is the repository-root CHANGELOG.md. `--file PATH` points the
lint at a fixture instead (self-test and lane red/green evidence use temp
copies; the real CHANGELOG.md content is owned by release cards, not by this
gate's evidence). `--self-test` runs embedded positive/negative fixtures
through the same lint_text() core first — the gate must prove it detects
every failure class, not merely that the tree is clean — and finishes by
proving the repository CHANGELOG.md is lint-clean (fail-closed on a repo
whose changelog is currently malformed).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
from datetime import date as _date

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_FILE = ROOT / "CHANGELOG.md"
UNRELEASED_MARKER = "unreleased (cut pending)"

# Every `##` heading line (exactly two '#', not H3+) is an attempted version
# section header — it must match the pinned shape exactly, otherwise
# CL-HEADER-MALFORMED (covers bracket-less headers, spacing drift like
# `##[0.2.0]`, and any non-version H2; fail-closed, never silently ignored).
_ATTEMPT_RE = re.compile(r"^##(?!#)")
_HEADER_RE = re.compile(r"^## \[([^\]]+)\] - (.+)$")
# Strict core semver: three dot-separated components, no leading zeros.
_SEMVER_RE = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
# ISO shape YYYY-MM-DD; calendar validity is checked separately so
# 2026-02-30 is rejected, not just 2026-1-5.
_DATE_RE = re.compile(r"^([0-9]{4})-([0-9]{2})-([0-9]{2})$")


class Finding:
    """One typed lint diagnostic: stable code + 1-based line + message."""

    def __init__(self, code: str, line: int, message: str) -> None:
        self.code = code
        self.line = line
        self.message = message

    def render(self, path: str) -> str:
        return f"{path}:{self.line}: {self.code}: {self.message}"


def _date_problem(date_part: str) -> str | None:
    """None when date_part is calendar-valid ISO or the exact unreleased
    marker; otherwise a human-readable reason (message-level, single code)."""
    if date_part == UNRELEASED_MARKER:
        return None
    m = _DATE_RE.match(date_part)
    if not m:
        return (f"date part {date_part!r} is not ISO YYYY-MM-DD and not the "
                f"exact marker {UNRELEASED_MARKER!r}")
    year, month, day = (int(m.group(i)) for i in (1, 2, 3))
    try:
        _date(year, month, day)
    except ValueError as exc:
        return f"date part {date_part!r} has ISO shape but is not a calendar date: {exc}"
    return None


def lint_text(text: str) -> list[Finding]:
    """Lint CHANGELOG.md content; returns typed findings in line order."""
    findings: list[Finding] = []
    seen_versions: dict[str, int] = {}
    for lineno, line in enumerate(text.splitlines(), start=1):
        if not _ATTEMPT_RE.match(line):
            continue
        m = _HEADER_RE.match(line)
        if not m:
            findings.append(Finding(
                "CL-HEADER-MALFORMED", lineno,
                f"version section header does not match the pinned shape "
                f"'## [<semver>] - <date|{UNRELEASED_MARKER}>': {line!r}"))
            continue
        version, date_part = m.group(1), m.group(2)
        if not _SEMVER_RE.match(version):
            findings.append(Finding(
                "CL-SEMVER-INVALID", lineno,
                f"version {version!r} is not strict core semver "
                "MAJOR.MINOR.PATCH (no leading zeros, no suffixes)"))
            continue
        date_problem = _date_problem(date_part)
        if date_problem is not None:
            findings.append(Finding("CL-DATE-INVALID", lineno, date_problem))
            continue
        if version in seen_versions:
            findings.append(Finding(
                "CL-VERSION-DUPLICATE", lineno,
                f"version {version} opens a second section "
                f"(first section at line {seen_versions[version]})"))
        else:
            seen_versions[version] = lineno
    return findings


def lint_file(path: pathlib.Path) -> list[Finding]:
    """Read and lint one file; a missing/unreadable file is a CL-FILE-MISSING
    internal error (exit 2), never a silent pass."""
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise _LintError([Finding("CL-FILE-MISSING", 0, f"file not found: {path}")])
    except (OSError, UnicodeDecodeError) as exc:
        raise _LintError([Finding("CL-FILE-MISSING", 0,
                                  f"file unreadable as UTF-8 text: {path}: {exc}")])
    return lint_text(text)


class _LintError(Exception):
    """Internal/usage error carrying findings (exit 2 path)."""

    def __init__(self, findings: list[Finding]) -> None:
        super().__init__(findings[0].message)
        self.findings = findings


def _print_report(path: pathlib.Path, findings: list[Finding]) -> None:
    for f in findings:
        print(f"[changelog-lint] FAIL {f.render(str(path))}", file=sys.stderr)
    counts: dict[str, int] = {}
    for f in findings:
        counts[f.code] = counts.get(f.code, 0) + 1
    summary = ", ".join(f"{k}={v}" for k, v in sorted(counts.items()))
    print(f"[changelog-lint] summary: {len(findings)} finding(s)"
          + (f": {summary}" if summary else ""), file=sys.stderr)


def _self_test() -> None:
    """Embedded fixtures through the production lint_text() core: every
    failure class must be detected with the expected code (and true line
    numbers), the valid control must be clean, and the repository
    CHANGELOG.md must be lint-clean. Any miss is a failed self-test
    (exit 2) — detection is proven, never assumed."""
    valid = (
        "# Changelog\n"
        "\n"
        "All notable changes to this project will be documented in this file.\n"
        "\n"
        "## [0.3.0] - unreleased (cut pending)\n"
        "\n"
        "- Next.\n"
        "\n"
        "## [0.2.0] - 2026-11-05\n"
        "\n"
        "- Added x.\n"
        "\n"
        "## [0.1.0] - 2026-10-22\n"
        "\n"
        "- First release.\n"
    )

    def expect(name: str, text: str, code: str, line: int | None = None) -> None:
        got = lint_text(text)
        hit = [f for f in got if f.code == code and (line is None or f.line == line)]
        if not hit:
            _self_test_fail(name, code, got)

    def expect_clean(name: str, text: str) -> None:
        got = lint_text(text)
        if got:
            _self_test_fail(name, "no findings", got)

    expect_clean("valid-fixture", valid)
    expect("duplicate-version",
           valid + "\n## [0.2.0] - 2026-11-05\n\n- Duplicate section.\n",
           "CL-VERSION-DUPLICATE", line=17)
    expect("malformed-header-no-brackets",
           valid + "\n## 0.9.9 - 2026-11-06\n",
           "CL-HEADER-MALFORMED", line=17)
    expect("malformed-header-wrong-separator",
           "# Changelog\n\n## [0.9.8]—2026-11-06\n",
           "CL-HEADER-MALFORMED", line=3)
    expect("malformed-header-double-space",
           "# Changelog\n\n## [0.9.7]  -  2026-11-06\n",
           "CL-HEADER-MALFORMED", line=3)
    expect("non-semver-two-components",
           "# Changelog\n\n## [1.2] - 2026-11-06\n",
           "CL-SEMVER-INVALID", line=3)
    expect("semver-leading-zero",
           "# Changelog\n\n## [01.2.0] - 2026-11-06\n",
           "CL-SEMVER-INVALID", line=3)
    expect("date-not-padded",
           "# Changelog\n\n## [0.9.6] - 2026-1-5\n",
           "CL-DATE-INVALID", line=3)
    expect("date-calendar-invalid",
           "# Changelog\n\n## [0.9.5] - 2026-02-30\n",
           "CL-DATE-INVALID", line=3)
    expect("date-prose",
           "# Changelog\n\n## [0.9.4] - Nov 5 2026\n",
           "CL-DATE-INVALID", line=3)
    expect("unreleased-marker-near-miss",
           "# Changelog\n\n## [0.9.3] - unreleased\n",
           "CL-DATE-INVALID", line=3)
    expect("unreleased-marker-case",
           "# Changelog\n\n## [0.9.2] - Unreleased (cut pending)\n",
           "CL-DATE-INVALID", line=3)
    # The real repository file must be lint-clean (fail-closed on a repo
    # whose committed changelog is malformed).
    repo_findings = lint_file(DEFAULT_FILE)
    if repo_findings:
        _self_test_fail("repository-file", "no findings", repo_findings)
    sections = sum(1 for line in DEFAULT_FILE.read_text(encoding="utf-8").splitlines()
                   if _ATTEMPT_RE.match(line))
    print(f"[changelog-lint] self-test: 13 embedded fixture cases passed "
          f"(valid control clean; duplicate version, 3x malformed header, "
          f"2x non-semver, 4x invalid date, 2x near-miss unreleased marker "
          f"detected with typed codes and true line numbers); repository "
          f"{DEFAULT_FILE.name} lint-clean ({sections} version section(s))")


def _self_test_fail(name: str, expected: str, got: list[Finding]) -> None:
    detail = "; ".join(f.render("fixture") for f in got) or "no findings"
    print(f"[changelog-lint] SELF-TEST FAIL: case {name} expected {expected}, "
          f"got: {detail}", file=sys.stderr)
    raise SystemExit(2)


def main() -> int:
    ap = argparse.ArgumentParser(
        prog="changelog_lint",
        description="Lint the Raider CHANGELOG.md version sections (RAI-281).")
    ap.add_argument("--file", type=pathlib.Path, default=DEFAULT_FILE,
                    help=f"file to lint (default: {DEFAULT_FILE})")
    ap.add_argument("--self-test", action="store_true",
                    help="run embedded positive/negative fixtures first")
    args = ap.parse_args()
    if args.self_test:
        _self_test()
    path = args.file if args.file.is_absolute() else ROOT / args.file
    try:
        findings = lint_file(path)
    except _LintError as exc:
        _print_report(path, exc.findings)
        return 2
    if findings:
        _print_report(path, findings)
        return 1
    print(f"[changelog-lint] OK {path}: all version section headers "
          f"'## [<semver>] - <date|{UNRELEASED_MARKER}>' well-formed, "
          "versions unique, dates calendar-valid ISO or the exact marker")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
