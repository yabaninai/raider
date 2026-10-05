#!/usr/bin/env python3
"""License compliance audit for Raider runtime dependencies (MIT-only policy).

The project LICENSE is MIT. Policy: every RUNTIME dependency must carry a
permissive license (MIT / BSD / Apache-2.0 / ISC / CC0 / Unlicense /
public domain). Copyleft licenses (GPL/LGPL/AGPL) are FORBIDDEN in the
runtime graph; MPL/EPL require an explicit, reviewed entry in REVIEW_OK.

How it works:
  1. `sbt --batch "export root/runtime:fullClasspath"` — the union classpath.
  2. External jars = entries under the coursier cache (COURSIER_CACHE,
     default /tmp/cc-master) and not inside the repository.
  3. Each jar's sibling `.pom` (coursier layout: pom next to jar) is parsed
     for <licenses>; if absent, the <parent> pom is resolved the same way.
  4. Classification: ALLOWED licenses pass; anything else (forbidden,
     review-required, or UNKNOWN) fails the audit. Unknown is a failure —
     honesty rules: an unaudited dependency is not a safe dependency.

Exit codes: 0 = clean, 1 = violations/unknowns, 2 = internal error.
Pass --self-test to verify the parser/classifier on embedded fixtures first.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = pathlib.Path(
    subprocess.run(["sh", "-c", "echo ${COURSIER_CACHE:-/tmp/cc-master}"],
                   capture_output=True, text=True).stdout.strip()
)

ALLOWED = {
    "mit", "bsd", "bsd 2-clause", "bsd 3-clause", "bsd zero",
    "apache 2.0", "apache license 2.0", "apache-2.0", "asl 2.0",
    "isc", "cc0", "unlicense", "public domain",
}
FORBIDDEN = {"gpl", "gpl2", "gpl3", "lgpl", "lgpl2", "lgpl3", "agpl", "agpl3"}
REVIEW = {"mpl", "mpl 1.1", "mpl 2.0", "epl", "epl 1.0", "epl 2.0", "cddl"}

# Explicitly reviewed exceptions live here — each needs a comment + date.
REVIEW_OK: set[str] = set()
# Example:
# REVIEW_OK.add("mpl 2.0")  # owner-reviewed 2026-10-05, link to decision


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def first_child(el: ET.Element, name: str) -> ET.Element | None:
    for child in el:
        if local_name(child.tag) == name:
            return child
    return None


def parse_licenses(pom_path: pathlib.Path) -> tuple[list[str], str | None]:
    """Returns (license names, parent coordinates 'g:a:v' or None)."""
    try:
        root = ET.parse(pom_path).getroot()
    except ET.ParseError as exc:
        return [], None
    licensing = first_child(root, "licenses")
    names: list[str] = []
    if licensing is not None:
        for lic in licensing:
            if local_name(lic.tag) != "license":
                continue
            name_el = first_child(lic, "name")
            if name_el is not None and (name_el.text or "").strip():
                names.append(name_el.text.strip())
    parent = first_child(root, "parent")
    parent_coord = None
    if parent is not None:
        g = first_child(parent, "groupId")
        a = first_child(parent, "artifactId")
        v = first_child(parent, "version")
        if g is not None and a is not None and v is not None:
            parent_coord = f"{(g.text or '').strip()}:{(a.text or '').strip()}:{(v.text or '').strip()}"
    return names, parent_coord


def classify(name: str) -> str:
    """Semantic matching over verbose license names."""
    n = re.sub(r"\s+", " ", name.strip().lower())
    # forbidden first: LGPL/GPL/AGPL names all contain "general public license"
    if "affero general public" in n:
        return "forbidden"
    if "lesser general public" in n:
        return "forbidden"
    if "general public license" in n:
        return "forbidden"
    if "mozilla public license" in n:
        return "review" if n not in REVIEW_OK else "allowed"
    if "common development and distribution" in n:  # CDDL
        return "review" if n not in REVIEW_OK else "allowed"
    if "eclipse public license" in n:
        return "review" if n not in REVIEW_OK else "allowed"
    if "apache" in n and ("2.0" in n or "license" in n):
        return "allowed"
    if "bsd" in n or "berkeley software distribution" in n:
        return "allowed"
    if n == "mit" or "mit license" in n:
        return "allowed"
    if n in ALLOWED:
        return "allowed"
    return "unknown"


def resolve_licenses(pom_path: pathlib.Path, seen: set[str]) -> list[str]:
    key = str(pom_path)
    if key in seen:
        return []
    seen.add(key)
    names, parent = parse_licenses(pom_path)
    if names:
        return names
    if parent:
        g, a, v = parent.split(":")
        parent_pom = (
            CACHE / "https" / "repo1.maven.org" / "maven2"
            / g.replace(".", "/") / a / v / f"{a}-{v}.pom"
        )
        if parent_pom.exists():
            return resolve_licenses(parent_pom, seen)
    return []


def self_test() -> bool:
    ok = True
    fixtures = [
        ("MIT License", "allowed"),
        ("BSD 3-Clause", "allowed"),
        ("The Apache Software License, Version 2.0", "allowed"),
        ("GNU Lesser General Public License", "forbidden"),
        ("GNU General Public License, version 2", "forbidden"),
        ("Mozilla Public License, Version 2.0", "review"),
        ("Something Unheard-Of", "unknown"),
    ]
    for name, expected in fixtures:
        got = classify(name)
        if got != expected:
            print(f"[license-audit] SELF-TEST FAIL: {name!r} -> {got}, want {expected}")
            ok = False
    pom = """<project xmlns="http://maven.apache.org/POM/4.0.0">
      <licenses><license><name>MIT License</name></license></licenses>
    </project>"""
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".pom", delete=False) as fh:
        fh.write(pom)
        tmp = pathlib.Path(fh.name)
    names, _ = parse_licenses(tmp)
    tmp.unlink()
    if names != ["MIT License"]:
        print(f"[license-audit] SELF-TEST FAIL: pom parse gave {names}")
        ok = False
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(prog="license_audit")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test and not self_test():
        return 1

    started = time.time()
    proc = subprocess.run(
        ["sbt", "--batch", "export root/runtime:fullClasspath"],
        cwd=ROOT, capture_output=True, text=True, timeout=600,
        env={"PATH": "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin",
             "HOME": str(pathlib.Path.home()),
             "COURSIER_CACHE": str(CACHE)},
    )
    if proc.returncode != 0:
        print(f"[license-audit] FAIL: sbt export failed\n{proc.stderr[-800:]}")
        return 2
    classpath = proc.stdout.strip().splitlines()[-1].strip()

    external: dict[str, pathlib.Path] = {}
    for entry in classpath.split(":"):
        if not entry.endswith(".jar"):
            continue
        p = pathlib.Path(entry)
        if ROOT in p.parents or str(p).startswith(str(ROOT)):
            continue  # our own module jars
        external[f"{p.parent.parent.name}:{p.name}"] = p

    if not external:
        print("[license-audit] FAIL: no external jars found on the classpath")
        return 2

    results = []
    problems = 0
    for key, jar in sorted(external.items()):
        pom = jar.with_suffix(".pom")
        if not pom.exists():
            results.append({"artifact": key, "status": "unknown",
                            "licenses": [], "detail": "no sibling pom in cache"})
            problems += 1
            continue
        names = resolve_licenses(pom, set())
        if not names:
            results.append({"artifact": key, "status": "unknown",
                            "licenses": [], "detail": "pom has no licenses (parent chain exhausted)"})
            problems += 1
            continue
        statuses = sorted({classify(n) for n in names})
        # artifact passes only if EVERY declared license is allowed
        if "forbidden" in statuses or "unknown" in statuses:
            status = "forbidden" if "forbidden" in statuses else "unknown"
        elif "review" in statuses:
            status = "review"
        else:
            status = "allowed"
        if status != "allowed":
            problems += 1
        results.append({"artifact": key, "status": status,
                        "licenses": names, "detail": ""})

    out_dir = ROOT / "artifacts" / "license-audit"
    out_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "policy": "MIT-only runtime: allow MIT/BSD/Apache-2.0/ISC/CC0/Unlicense; "
                  "forbid GPL/LGPL/AGPL; MPL/EPL need REVIEW_OK entry",
        "cache": str(CACHE),
        "count": len(results),
        "problems": problems,
        "results": results,
    }
    out = out_dir / f"report-{time.strftime('%Y%m%d-%H%M%S')}.json"
    out.write_text(json.dumps(report, indent=2))

    for r in results:
        mark = "OK " if r["status"] == "allowed" else "!!!"
        if r["status"] != "allowed":
            print(f"  {mark} {r['artifact']}  [{r['status']}] {r['licenses'] or r['detail']}")
    print(f"[license-audit] {'PASS' if problems == 0 else 'FAIL'}: "
          f"{len(results)} artifacts, {problems} problem(s); report: {out} "
          f"({time.time() - started:.1f}s)")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
