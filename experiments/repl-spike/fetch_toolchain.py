#!/usr/bin/env python3
"""RAI-001 spike toolchain fetcher.

Resolves a pinned, closed set of Maven coordinates by walking POMs from
repo1.maven.org (compile/runtime scope only) and downloads the jars into
artifacts/tools/m2. Emits artifacts/rai-001/toolchain.json with coordinates,
sizes and sha256. No shell, no remote scripts, no floating versions: roots
are exact versions passed on the command line.

This is a feasibility-spike tool, not the product bootstrap (RAI-003).
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import sys
import urllib.request
import xml.etree.ElementTree as ET

BASE = "https://repo1.maven.org/maven2"
OUT_ROOT = pathlib.Path("artifacts/tools/m2")
INVENTORY = pathlib.Path(os.environ.get("RAIDER_TOOLCHAIN_INVENTORY",
                                        "artifacts/rai-001/toolchain.json"))

POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def url_for(group: str, artifact: str, version: str, ext: str) -> str:
    return f"{BASE}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"


def fetch(url: str, timeout: int = 60) -> bytes:
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return resp.read()


def strip_ns(tag: str) -> str:
    return tag.split("}", 1)[1] if "}" in tag else tag


def parse_props(pom_xml: bytes) -> dict[str, str]:
    root = ET.fromstring(pom_xml)
    props: dict[str, str] = {}
    for child in root:
        if strip_ns(child.tag) == "properties":
            for p in child:
                props[strip_ns(p.tag)] = (p.text or "").strip()
    return props


def parse_parent(pom_xml: bytes):
    root = ET.fromstring(pom_xml)
    for child in root:
        if strip_ns(child.tag) == "parent":
            g = a = v = None
            for e in child:
                t = strip_ns(e.tag)
                if t == "groupId":
                    g = (e.text or "").strip()
                elif t == "artifactId":
                    a = (e.text or "").strip()
                elif t == "version":
                    v = (e.text or "").strip()
            return (g, a, v)
    return None


def subst(value: str | None, props: dict[str, str]) -> str | None:
    if value is None:
        return None
    out = value.strip()
    if out.startswith("${") and out.endswith("}"):
        key = out[2:-1]
        if key in props:
            return props[key]
        return None  # unresolved property (e.g. managed by parent) -> drop
    return out


def direct_deps(pom_xml: bytes) -> list[tuple[str, str, str, str]]:
    """Return (group, artifact, version, scope) for non-test deps with resolvable versions."""
    root = ET.fromstring(pom_xml)
    props = parse_props(pom_xml)
    parent = parse_parent(pom_xml)
    if parent:
        pg, _, _ = parent
        props.setdefault("project.parent.groupId", pg or "")
    deps: list[tuple[str, str, str, str]] = []
    for child in root:
        if strip_ns(child.tag) != "dependencies":
            continue
        for d in child:
            if strip_ns(d.tag) != "dependency":
                continue
            g = a = v = None
            scope = "compile"
            optional = False
            for e in d:
                t = strip_ns(e.tag)
                if t == "groupId":
                    g = (e.text or "").strip()
                elif t == "artifactId":
                    a = (e.text or "").strip()
                elif t == "version":
                    v = subst(e.text, props)
                elif t == "scope":
                    scope = (e.text or "").strip()
                elif t == "optional":
                    optional = (e.text or "").strip().lower() == "true"
            if optional or scope in ("test", "provided"):
                continue
            if not (g and a and v):
                continue
            deps.append((g, a, v, scope))
    return deps


def coordinate_key(g: str, a: str, v: str) -> str:
    return f"{g}:{a}:{v}"


def resolve(roots: list[tuple[str, str, str]]) -> dict[str, dict]:
    """BFS transitive resolution. Drops deps whose version is unresolvable from own pom."""
    seen: dict[str, dict] = {}
    queue: list[tuple[str, str, str]] = list(roots)
    while queue:
        g, a, v = queue.pop(0)
        key = coordinate_key(g, a, v)
        if key in seen:
            continue
        pom_url = url_for(g, a, v, "pom")
        try:
            pom = fetch(pom_url)
        except Exception as exc:  # noqa: BLE001 - spike tool, report and continue
            print(f"WARN pom fetch failed {key}: {exc}", file=sys.stderr)
            seen[key] = {"error": str(exc)}
            continue
        record = {"group": g, "artifact": a, "version": v}
        seen[key] = record
        for dg, da, dv, _scope in direct_deps(pom):
            queue.append((dg, da, dv))
    return seen


def download_all(resolved: dict[str, dict]) -> dict[str, dict]:
    for key, rec in resolved.items():
        if "error" in rec:
            continue
        g, a, v = rec["group"], rec["artifact"], rec["version"]
        jar_path = OUT_ROOT / g.replace(".", "/") / a / v / f"{a}-{v}.jar"
        if not jar_path.exists():
            jar_path.parent.mkdir(parents=True, exist_ok=True)
            data = fetch(url_for(g, a, v, "jar"))
            jar_path.write_bytes(data)
        data = jar_path.read_bytes()
        rec["path"] = str(jar_path)
        rec["size"] = len(data)
        rec["sha256"] = hashlib.sha256(data).hexdigest()
    return resolved


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: fetch_toolchain.py group:artifact:version ...", file=sys.stderr)
        return 2
    roots: list[tuple[str, str, str]] = []
    for arg in sys.argv[1:]:
        g, a, v = arg.split(":")
        roots.append((g, a, v))
    resolved = resolve(roots)
    resolved = download_all(resolved)
    INVENTORY.parent.mkdir(parents=True, exist_ok=True)
    INVENTORY.write_text(json.dumps(resolved, indent=2, sort_keys=True) + "\n")
    print(f"resolved {len(resolved)} coordinates; inventory at {INVENTORY}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
