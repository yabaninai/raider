#!/usr/bin/env python3
"""Validate the planning package only; this is not a Raider runtime quality gate."""

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"\[[^\]\n]+\]\(([^)\n]+)\)")
CARD = re.compile(r"^## ((?:RAI-\d{3}|YB-RAI-\d{2})(?:\.[a-z]+)?): (.+)$", re.M)
GROUPS = {"M0": "m0.md", "M1": "m1.md", "M2": "m2-p2.md",
          "P2": "m2-p2.md", "Yabanin": "yabanin.md"}


def validate(yabanin_repo=None):
    errors = []
    checks = {}
    files = sorted({p for base in (ROOT / "docs", ROOT / ".opencode/commands",
                                   ROOT / "scripts/plan")
                    for p in base.rglob("*") if p.is_file()
                    and "__pycache__" not in p.parts}
                   | {ROOT / name for name in ("README.md", "AGENTS.md", ".gitignore")})
    for path in files:
        if path.suffix not in (".md", ".txt", ".py", ".json"):
            continue
        relative = path.relative_to(ROOT).as_posix()
        content = path.read_text(encoding="utf-8")
        for lineno, line in enumerate(content.splitlines(), 1):
            if line.rstrip() != line:
                errors.append(f"{relative}:{lineno}: trailing whitespace")
        if path.suffix == ".json":
            try:
                json.loads(content)
            except ValueError as exc:
                errors.append(f"{relative}: invalid JSON: {exc}")
        if path.suffix != ".md":
            continue
        fence = None
        prose = []
        for line in content.splitlines():
            match = re.match(r"^\s*(`{3,}|~{3,})(.*)$", line)
            if match:
                marker, remainder = match.groups()
                if fence is None:
                    fence = marker
                elif marker[0] == fence[0] and len(marker) >= len(fence) and not remainder.strip():
                    fence = None
                continue
            if fence is None:
                prose.append(line)
        if fence is not None:
            errors.append(f"{relative}: unclosed fenced block")
        # Scala type applications and printer examples can look like Markdown links.
        prose_without_code = re.sub(r"(`+).*?\1", "", "\n".join(prose))
        for match in LINK.finditer(prose_without_code):
            target = match.group(1).strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1]
            if urlsplit(target).scheme or target.startswith("#"):
                continue
            target = unquote(target.split("#", 1)[0])
            if target and not (path.parent / target).exists():
                errors.append(f"{relative}: missing relative link {target}")
    checks["document_links_json_fences_whitespace"] = len(files)

    backlog = json.loads((ROOT / "docs/tasks/backlog.json").read_text())
    cards = backlog["cards"]
    by_id = {c["id"]: c for c in cards}
    if len(by_id) != len(cards):
        errors.append("backlog: duplicate card IDs")
    raider = [c for c in cards if c["id"].startswith("RAI-") and "." not in c["id"]]
    companion = [c for c in cards if c["id"].startswith("YB-") and "." not in c["id"]]
    if len(raider) != backlog["raider_count"] or len(companion) != backlog["companion_count"]:
        errors.append("backlog: count metadata mismatch")
    for card in cards:
        for dependency in card["dependencies"]:
            if dependency not in by_id:
                errors.append(f"{card['id']}: unknown dependency {dependency}")
        for required in ("title", "milestone", "allowed_paths", "profiles", "behavior", "context"):
            if not card.get(required):
                errors.append(f"{card['id']}: missing {required}")
        if set(card["acceptance"]) != {"normal", "error", "concurrency"}:
            errors.append(f"{card['id']}: missing acceptance dimension")
    visiting, visited = set(), set()

    def visit(ident):
        if ident in visiting:
            errors.append(f"dependency cycle at {ident}")
            return
        if ident in visited or ident not in by_id:
            return
        visiting.add(ident)
        for dependency in by_id[ident]["dependencies"]:
            visit(dependency)
        visiting.remove(ident)
        visited.add(ident)

    for ident in by_id:
        visit(ident)
    checks["unique_ids_complete_dependencies_acyclic"] = len(cards)

    markdown_ids = []
    for filename in sorted(set(GROUPS.values())):
        content = (ROOT / "docs/tasks" / filename).read_text()
        matches = list(CARD.finditer(content))
        for i, match in enumerate(matches):
            ident, title = match.groups()
            markdown_ids.append(ident)
            if ident not in by_id:
                errors.append(f"{filename}: unregistered card {ident}")
                continue
            card = by_id[ident]
            if title != card["title"] or GROUPS[card["milestone"]] != filename:
                errors.append(f"{ident}: title/milestone mismatch")
            end = matches[i + 1].start() if i + 1 < len(matches) else len(content)
            section = content[match.end():end]
            dependencies = ", ".join(card["dependencies"]) or "нет"
            if f"Dependencies: {dependencies}." not in section:
                errors.append(f"{ident}: dependency metadata differs from Markdown")
            for value in (card["behavior"], card["context"], card["non_goals"],
                          *card["allowed_paths"], *card["acceptance"].values()):
                if value not in section:
                    errors.append(f"{ident}: Markdown and JSON contract mismatch")
            for label in ("**Required gates.**", "**Compatibility/rollback.**", "**Deliverables.**"):
                if label not in section:
                    errors.append(f"{ident}: missing {label}")
    if sorted(markdown_ids) != sorted(by_id):
        errors.append("Markdown cards do not match backlog IDs exactly")
    index = (ROOT / "docs/tasks/README.md").read_text()
    for ident in by_id:
        if f"**{ident}**" not in index:
            errors.append(f"index: missing {ident}")
    checks["markdown_backlog_index_consistency"] = len(markdown_ids)

    prompt = (ROOT / "docs/prompts/opencode-finish.txt").read_text()
    prompt_errors_before = len(errors)
    legacy_prompt = (ROOT / "docs/prompts/opencode-build.txt").read_text()
    if legacy_prompt != prompt:
        errors.append("Legacy build prompt does not match canonical finish prompt")
    for name in ("raider-build", "raider-finish"):
        command = (ROOT / ".opencode/commands" / f"{name}.md").read_text()
        parts = command.split("---", 2)
        if len(parts) != 3 or "agent: build" not in parts[1] or "subtask: false" not in parts[1]:
            errors.append(f"OpenCode {name}: invalid expected frontmatter")
        elif parts[2].lstrip("\n") != prompt:
            errors.append(f"OpenCode {name}: does not match canonical finish prompt")
    if not all(needle in prompt for needle in ("OpenAI-compatible", "Anthropic-compatible",
                                               "Yabanin optional", "RAI-001", "RAI-026",
                                               "W01–W17", "QUALITY_BASE_MANIFEST",
                                               "parallel-development.md")):
        errors.append("OpenCode prompt missing core constraints")
    checks["opencode_command_prompt_consistency"] = len(errors) == prompt_errors_before

    snapshot = json.loads((ROOT / "docs/research/yabanin-snapshot.json").read_text())
    if not re.fullmatch(r"[a-f0-9]{40}", snapshot["head"]):
        errors.append("snapshot: invalid commit hash")
    snapshot_paths = [item["path"] for item in snapshot["files"]]
    if len(snapshot_paths) != len(set(snapshot_paths)):
        errors.append("snapshot: duplicate source paths")
    for item in snapshot["files"]:
        if not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]) or item["bytes"] < 0:
            errors.append(f"snapshot: invalid hash/size for {item['path']}")
        if yabanin_repo:
            path = Path(yabanin_repo) / item["path"]
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
                errors.append(f"snapshot: source has changed {item['path']}")
    checks["snapshot_metadata"] = len(snapshot_paths)
    checks["snapshot_current_source_hashes"] = bool(yabanin_repo)
    sources = [{"path": p.relative_to(ROOT).as_posix(),
                "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]
    digest = hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()
    return {"schema_version": 1, "scope": "planning-package-only; no Scala/runtime/hosted-CI verification",
            "status": "failed" if errors else "passed",
            "checked_at": datetime.now(timezone.utc).isoformat(),
            "checks": checks, "source_sha256": digest, "sources": sources, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", help="Optional report path inside this repository")
    parser.add_argument("--yabanin-repo", help="Optional read-only snapshot hash verification")
    args = parser.parse_args()
    report = validate(args.yabanin_repo)
    if args.out:
        output = Path(args.out)
        if not output.is_absolute():
            output = ROOT / output
        output = output.resolve()
        if not output.is_relative_to(ROOT):
            parser.error("--out must be inside the repository")
        if not output.is_relative_to(ROOT / "artifacts"):
            parser.error("--out must be inside artifacts to avoid rewriting plan sources")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Plan package: {report['status']}; {report['checks']['unique_ids_complete_dependencies_acyclic']} cards")
    for error in report["errors"]:
        print(error, file=sys.stderr)
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
