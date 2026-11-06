#!/usr/bin/env python3
"""RAI-004 quality runner (stage-aware; manifest-diff and git-status capable).

Subcommands:
  changed [--base-manifest P]      classify changed files -> profile plan (no execution)
  execute [--base-manifest P]      run active gates for selected profiles, write evidence
  inventory --out P                write a base inventory manifest (no git needed)
  verify MANIFEST --require P ...  validate statuses, logs, hashes, fingerprint
                                     (freshness is default; --allow-stale marks historical evidence)
  self-check                       registry integrity + classify/fingerprint (git AND
                                     manifest modes)/decision/run-id self-tests

Change source (docs/quality-gates.md section 2):
  - with --base-manifest / QUALITY_BASE_MANIFEST: diff vs the immutable inventory
    (added/modified/deleted; mode and symlink target); missing/corrupt baseline = error;
  - without base: `git status --porcelain -z --untracked-files=all` in the repo root
    (staged, unstaged, untracked, deleted; renames expanded as delete+add);
  - a clean tree selects the minimum `fast` profile;
  - no git (snapshot lane) AND no base manifest: hard error
    "Git unavailable: isolated snapshot" -- never a silent fast run.

Honesty rules: producer exit codes propagate; missing tool = unavailable = fail;
unknown profile = error; a profile with no executed gates = fail; skipped is never
passed. Evidence binds to the source fingerprint taken at execute time.

 RAI-054 (2026-10-06): classify() now returns a file-keyed mapping
 {path: [profiles]} (the old profile-keyed dict made cmd_execute flag every
 changed file as __unmapped__ and exit 1 before running any gate);
 fingerprint(None) now consults git instead of always returning an empty
 change list (a dirty tree looked clean without a base manifest).

 RAI-054 round 2 (2026-10-06, per independent reviewer findings): the
 manifest-mode diff now also compares symlink targets (quality-gates.md 2:
 "add/modify/delete, mode and symlink target") and is self-tested on a temp
 tree without git; run ids are `<YYYYmmdd-HHMMSS>-<pid>-<seq>` (per-process
 monotonic seq + pid) so parallel executes no longer share one run dir;
 git status output is parsed as bytes (NUL-split, utf-8/surrogateescape)
 so paths containing CR or non-utf8 bytes cannot be mangled by
 universal-newline translation.

 RAI-069 (2026-10-09, protected gate-policy addition, additions only): the
 new `security` profile (component stage) is registered as a component
 profile so load_policy validates its gate references, and self-check now
 also proves that the profile resolves and that
 scripts/quality/security_checks.py --self-test (secrets scanner detection +
 masking fixtures) passes. No existing gate, threshold or profile changed.

 RAI-255 (2026-11-01, verifier-only hardening; no gate, threshold, profile or
 manifest format change): `verify` no longer accepts manifests that attest
 nothing or attest a toolchain that was not the verifying environment:
   1. zero executed gates -> reject (all schema versions; the audit's
      synthetic `gates: []` manifest was previously accepted);
   2. gate-set completeness: a schema_version>=2 manifest claiming profile P
      must contain every gate the current registry requires for P (missing
      gates are named per gate + claiming profile). Schema-v1 manifests are
      EXEMPT from completeness (the `fast` profile grew after those
      manifests were produced) but still need >=1 gate;
   3. structural gate validation (v2): registry-known gate ids, no duplicate
      ids, argv equal to the registry, status/exit_code/timed_out mutually
      consistent (a timeout is never a pass), gate log present + hash match;
   4. toolchain identity: the manifest toolchain must record established
      `java`, `sbt` and `scala` identities (all schema versions); in strict
      verify (no --allow-stale) they must EQUAL the verifying environment's
      `java -version` / `sbt --version` snapshots (scala/zio are pinned
      constants). --allow-stale keeps the historical semantics: presence is
      required, identity comparison is skipped;
   5. malformed manifests are rejected with precise diagnostics instead of
      KeyError tracebacks, and the OK message no longer claims toolchain
      checks that did not happen.
 Migration note: manifests produced before RAI-255 verify unchanged when
 coherent; historical (--allow-stale) verification of old manifests still
 works if the policy hash matches and the toolchain identity was recorded.
 Compatibility check basis (quality manifests are gitignored, so this was an
 artifacts survey, not a git sweep): every schema_version-2 quality manifest
 present in the root checkout's artifacts/quality/ on 2026-11-01 was checked
 against the current registry and is complete; schema-v1 manifests from
 2026-10-03..05 predate the grown `fast` profile and rely on the documented
 v1 exemption.

 RAI-256 (2026-11-01, protected source-inventory policy; composition change
 with explicit versioning): the fingerprint inventory no longer mixes
 sibling-worktree copies or local opencode install state into product inputs,
 and checked-in agent prompts are no longer invisible:
    1. "wt" joins the registry fingerprint_excludes (segment at any depth):
       git worktrees of parallel lanes (convention wt/<card>, docs/
       parallel-development.md 1b) are operational copies, never product
       inputs. This is the audited defect: the 2026-11-01 integrated eval
       manifest inventoried 23,515 wt/ paths, and the merged self-check timed
       out inside the frozen 120 s budget hashing sibling worktrees (board
       wave H; remediation pruned the worktrees but the composition defect
       remained).
    2. ".opencode" STAYS excluded as local opencode install state
       (node_modules, package.json, ...), but the checked-in product-input
       prompt directories ".opencode/agents/**" and ".opencode/commands/**"
       are re-included by a root-anchored carve-out in _excluded: they are
       gate-policy inputs (AGENTS.md execution mode), and the documented
       fingerprint spec (docs/quality-gates.md 2: "tracked + nonignored
       untracked product source/config/specs/prompts") always meant to cover
       them.
    3. "artifacts/**" was already excluded by segment and is now pinned by a
       self-test: lane evidence directories must never enter the fingerprint.
    4. Inventory versioning: CURRENT_INVENTORY_VERSION = 2. cmd_execute
       records source.inventory_version; quality-inventory base manifests
       carry the version; strict verify rejects schema-v2 manifests whose
       recorded version differs from this verifier's composition, and
       fingerprint() refuses to diff a base manifest across compositions
       (regenerate the base — never diff two universes).
    5. Robustness: an OSError while hashing one entry (a file that vanishes
       mid-walk when another gate run mutates an operational directory
       concurrently) is skipped instead of crashing the walk; a vanished
       INCLUDED file re-appears as a diff on the next recomputation
       (fail-safe direction), and excluded paths never enter the inventory.
 Migration note: manifests and base inventories produced before RAI-256 were
 built by composition v1 and cannot be strict-verified (or diffed) against
 v2 — regenerate them. Real pre-change manifests additionally fail the
 policy-hash check (this card changes registry.json), so historical
 (--allow-stale) verification does not apply to them either. No gate,
 threshold, profile membership, or gate-verifier rejection semantics were
 changed; the self-test battery grows (_self_test_inventory_isolation plus
 pre-isolation-v2 verify cases).
 RAI-281 (gate-policy class): CHANGELOG.md now maps to the docs profile and
 the docs profile grows plan-validate -> plan-validate + changelog-lint
 (scripts/quality/changelog_lint.py, timeout_s=60, frozen in
 _self_test_gate_timeouts). Old manifests claiming the docs profile fail
 completeness if re-verified — expected growth, same precedent as every
  battery extension (RAI-209/RAI-275 re-froze budgets; this card adds a gate).
  RAI-288 (gate-policy class): vendor/** and THIRD_PARTY_NOTICES.md now map
  to the new component-stage vendor profile, whose single gate vendor-audit
  (scripts/quality/vendor_audit.py, timeout_s=120, frozen in
  _self_test_gate_timeouts) proves every vendored corpus is fully pinned:
  upstream ref + license recorded, sha256 manifest complete, all pinned
  bytes re-verified, notices entry present. This closes the fail-closed
  blocker where importing a vendor corpus (RAI-032/RAI-033 shapes) made
  quality-changed refuse with "changed files map to no profile (not
  skipped): THIRD_PARTY_NOTICES.md, vendor/..." — real coverage, never a
  silent skip. Old manifests claiming the vendor profile fail completeness
  if re-verified — expected growth, same precedent as RAI-281.
  """
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
POLICY = ROOT / "scripts/quality-policy/registry.json"
EVIDENCE_DIR = ROOT / "artifacts/quality"
OPERATIONAL_RECORDS = "docs/work/records"
# RAI-256: the source-inventory composition is explicitly versioned.
# Version 2 = the v1 composition (current inventory plus operational records,
# see legacy_inventory) minus sibling-worktree copies ("wt" path segment at
# any depth) and local opencode install state (".opencode" path segment),
# plus the checked-in product-input prompt directories listed in
# OPENCODE_PROMPT_INCLUDES. The authoritative composition lives in
# _excluded + registry fingerprint_excludes; any future composition change
# must bump this version and extend the verify migration diagnostics.
CURRENT_INVENTORY_VERSION = 2
# Root-anchored prefixes that re-enter the inventory despite the ".opencode"
# segment exclusion: checked-in agent/command prompts are product inputs.
# The suffix slash is required (prefix match, no sibling shadowing).
OPENCODE_PROMPT_INCLUDES = (".opencode/agents/", ".opencode/commands/")


def fail(msg: str) -> "None":
    print(f"[quality] FAIL: {msg}", file=sys.stderr)
    raise SystemExit(1)


class GitUnavailableError(RuntimeError):
    """Git cannot compute changes here: no .git, no git binary, or git failure."""


def sha256_file(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def load_policy() -> dict:
    policy = json.loads(POLICY.read_text())
    # component-stage profiles: every referenced gate must exist in the registry
    component_profiles = (
        "self", "static", "unit", "contracts", "docs", "fast",
        "runtime", "tools", "transport", "repl", "ci",
        "security",  # RAI-069 (addition only; gate refs validated like the rest)
        "eval",      # RAI-135 (component-stage deterministic corpus)
        "perf",      # RAI-137 (component-stage measured UX baseline)
        "vendor",    # RAI-288 (component-stage vendored-corpus provenance audit)
    )
    for name in policy["profiles"]:
        if name not in component_profiles:
            continue  # milestone-only profiles may appear later
        for gate in policy["profiles"][name]["gates"]:
            if gate not in policy["gates"]:
                fail(f"profile {name} references unknown gate {gate}")
    return policy


def fingerprint_excludes() -> set[str]:
    return set(load_policy()["fingerprint_excludes"])


def _excluded(rel: str, excludes: set[str]) -> bool:
    # segment-based: excludes apply at ANY depth (target/, .git/, artifacts/ ...)
    # RAI-256 carve-out: ".opencode" stays a base exclusion (local opencode
    # install state: node_modules, package.json, ...) but the checked-in
    # product-input prompt directories re-enter the inventory. The prefixes
    # are ROOT-ANCHORED (startswith), not segment-based: "wt/raider-sibling/
    # .opencode/agents/x.md" must stay excluded because its wt segment is
    # excluded first — a sibling worktree's prompts are its own inputs, not
    # ours.
    if rel.startswith(OPENCODE_PROMPT_INCLUDES):
        return False
    return any(part in excludes for part in rel.split("/"))


def inventory(root: pathlib.Path, excludes: set[str]) -> dict[str, dict]:
    """Hash inventory of the tree at `root` (fingerprint excludes applied).

    RAI-256: an OSError while hashing one entry (a file that vanishes
    mid-walk when another gate run or lane mutates an operational directory
    concurrently) is skipped instead of crashing the computation; a vanished
    INCLUDED file re-appears as a diff on the next recomputation (fail-safe
    direction), and excluded paths never enter the inventory at all.
    """
    current: dict[str, dict] = {}
    for p in root.rglob("*"):
        if not p.is_file() and not p.is_symlink():
            continue
        rel = p.relative_to(root).as_posix()
        if _excluded(rel, excludes) or _is_operational_record(rel):
            continue
        try:
            if p.is_symlink():
                current[rel] = {"mode": "symlink", "target": os.readlink(p)}
            else:
                st = p.stat()
                current[rel] = {"sha256": sha256_file(p), "mode": oct(st.st_mode & 0o777)}
        except OSError:
            continue
    return current


def _is_operational_record(rel: str) -> bool:
    """Only docs/work/records/** is excluded by this additive policy."""
    return rel.startswith(OPERATIONAL_RECORDS + "/")


def operational_record_audit(root: pathlib.Path) -> list[dict]:
    """Visible hashes/revisions for excluded operational records, not inputs."""
    entries = []
    records = root / OPERATIONAL_RECORDS
    if not records.exists():
        return entries
    for path in sorted(records.rglob("*")):
        if not path.is_file() and not path.is_symlink():
            continue
        rel = path.relative_to(root).as_posix()
        entry = {"path": rel}
        if path.is_symlink():
            entry.update({"mode": "symlink", "target": os.readlink(path)})
        else:
            entry.update({"mode": oct(path.stat().st_mode & 0o777), "sha256": sha256_file(path)})
        try:
            revision = subprocess.run(["git", "rev-parse", f"HEAD:{rel}"], cwd=root,
                                      capture_output=True, text=True, timeout=10)
            entry["revision"] = revision.stdout.strip() if revision.returncode == 0 else None
        except (FileNotFoundError, subprocess.TimeoutExpired):
            entry["revision"] = None
        entries.append(entry)
    return entries


def validate_operational_record_audit(stored: list[dict], current: list[dict]
                                      ) -> tuple[list[str], list[dict]]:
    """Compare gate-time record identity metadata and report post-gate additions."""
    before = {entry["path"]: entry for entry in stored}
    after = {entry["path"]: entry for entry in current}
    problems = []
    for rel, entry in before.items():
        now = after.get(rel)
        if now is None:
            problems.append(f"excluded operational record missing since gate: {rel}")
        elif any(entry.get(key) != now.get(key) for key in ("sha256", "mode", "target")):
            problems.append(f"excluded operational record changed since gate: {rel}")
    additions = [after[rel] for rel in sorted(after.keys() - before.keys())]
    return problems, additions


def legacy_inventory(root: pathlib.Path) -> dict[str, dict]:
    """Schema-v1 fingerprint: the current composition plus operational
    records included. The exclude-set part of v1 is inherently approximate
    after registry changes (excludes come from the live registry); v1
    manifests also bind a policy hash, so a registry change already
    invalidates them."""
    current: dict[str, dict] = {}
    excludes = fingerprint_excludes()
    for p in root.rglob("*"):
        if not p.is_file() and not p.is_symlink():
            continue
        rel = p.relative_to(root).as_posix()
        if _excluded(rel, excludes):
            continue
        try:
            if p.is_symlink():
                current[rel] = {"mode": "symlink", "target": os.readlink(p)}
            else:
                st = p.stat()
                current[rel] = {"sha256": sha256_file(p), "mode": oct(st.st_mode & 0o777)}
        except OSError:
            continue
    return current


def current_inventory() -> dict[str, dict]:
    """Inventory of the repo root (composition version
    CURRENT_INVENTORY_VERSION); git-free, usable by snapshot lanes."""
    return inventory(ROOT, fingerprint_excludes())


def _status_entry(xy: str, path: str, old_path: str | None) -> list[tuple[str, str]]:
    """Map one `git status --porcelain -z` XY state to (path, kind) entries.
    For rename/copy records `path` is the NEW path (the -z format reverses the
    field order, "from -> to becomes to from" -- git-status docs).

    Kinds use the manifest vocabulary (added/modified/deleted). A rename (R)
    is expanded as deleted(old) + added(new) -- the treatment quality-gates.md
    section 2 sanctions (rename allowed as delete+add); the profile union then
    covers both locations, so a move never drops the origin's profiles. A
    copy (C) adds the destination only (the source stays).
    Untracked (??) and staged-add (A) are added; M/T/U and anything else
    active is modified.
    """
    x, y = xy[0], xy[1]
    if x in "RC" or y in "RC":
        if (x == "R" or y == "R") and old_path:
            return [(old_path, "deleted"), (path, "added")]
        return [(path, "added")]
    if xy == "??":
        return [(path, "added")]
    active = {c for c in (x, y) if c != " "}
    if active == {"D"}:
        return [(path, "deleted")]
    if active == {"A"}:
        return [(path, "added")]
    return [(path, "modified")]


def git_changes(root: pathlib.Path, excludes: set[str]) -> list[dict]:
    """Changed paths vs HEAD via `git status --porcelain -z --untracked-files=all`.

    Accounts for staged, unstaged, untracked, deleted and renamed files.
    `--untracked-files=all` expands untracked directories to per-file records
    (default mode collapses them to `?? dir/`, which would break path
    classification). Change paths are filtered through the same fingerprint
    excludes as the inventory.

    Guarantee: the result lists exactly the GIT-VISIBLE changes vs HEAD, and
    an empty list means git reported a clean tree. Git-IGNORED files (e.g.
    `*.log`, `/https/`, `.env*` per .gitignore) are invisible in git mode;
    manifest mode would still see them on the filesystem and classify them
    through path_profiles — falling to `__unmapped__` hard refusal when no
    pattern matches, the fail-safe direction (refuse, never silently skip).
    Output is parsed as bytes (NUL-split, utf-8/surrogateescape decode) so a
    path containing CR or non-utf8 bytes cannot be mangled by
    universal-newline translation. Raises GitUnavailableError when git cannot
    be consulted (the caller turns that into a hard error; it is never
    reported as a clean tree).
    """
    if not (root / ".git").exists():
        raise GitUnavailableError(f"no .git in {root}")
    try:
        proc = subprocess.run(
            ["git", "status", "--porcelain", "-z", "--untracked-files=all"],
            cwd=root, capture_output=True, timeout=60)  # bytes: no newline translation
    except FileNotFoundError:
        raise GitUnavailableError("git binary not found on PATH")
    except subprocess.TimeoutExpired:
        raise GitUnavailableError("git status timed out")
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or b"").decode("utf-8", errors="surrogateescape")
        raise GitUnavailableError(f"git status exited {proc.returncode}: {err.strip()[:200]}")
    changes: list[dict] = []
    tokens = proc.stdout.split(b"\0")
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        i += 1
        if not tok or len(tok) < 4:
            continue
        xy = tok[:2].decode("utf-8", errors="surrogateescape")
        path = tok[3:].decode("utf-8", errors="surrogateescape")  # for R/C records `path` is the NEW name
        old_path: str | None = None
        if "R" in xy or "C" in xy:
            # -z rename/copy records span two NUL-terminated strings with
            # REVERSED field order: "XY new\0old\0" (git-status man page)
            if i < len(tokens) and tokens[i]:
                old_path = tokens[i].decode("utf-8", errors="surrogateescape")
                i += 1
        for rel, kind in _status_entry(xy, path, old_path):
            if rel and not _excluded(rel, excludes) and not _is_operational_record(rel):
                changes.append({"path": rel, "kind": kind})
    changes.sort(key=lambda c: (c["path"], c["kind"]))
    return changes


def _manifest_diff(base: dict[str, dict], current: dict[str, dict]) -> list[dict]:
    """Diff a current inventory against an immutable base manifest.

    added/modified/deleted, where modified = sha256, mode OR symlink target
    changed (quality-gates.md 2: "Diff accounts for add/modify/delete, mode and
    symlink target"). Deterministically sorted by (path, kind).
    """
    changes: list[dict] = []
    for rel, cur in current.items():
        old = base.get(rel)
        if old is None:
            changes.append({"path": rel, "kind": "added"})
        elif (old.get("sha256") != cur.get("sha256")
              or old.get("mode") != cur.get("mode")
              or old.get("target") != cur.get("target")):
            changes.append({"path": rel, "kind": "modified"})
    for rel in base:
        if rel not in current:
            changes.append({"path": rel, "kind": "deleted"})
    changes.sort(key=lambda c: (c["path"], c["kind"]))
    return changes


def fingerprint(base_manifest: str | None,
                root: pathlib.Path | None = None) -> tuple[dict, list[dict]]:
    """Current tree inventory + change classification.

    base_manifest set: diff vs the immutable inventory (added/modified/deleted;
    hash, mode and symlink target) -- manifest-diff semantics, no git needed.
    base_manifest None: git mode in `root` (default ROOT; staged/unstaged/
    untracked/deleted, renames as delete+add); empty changes only for a clean
    tree. Without git AND without a manifest this is a hard error, never a
    silent minimum-fast run (quality-gates.md section 2). The `root` parameter
    exists so self-checks can exercise the production code path on a temp
    tree; production calls always use ROOT.
    """
    r = ROOT if root is None else root
    excludes = fingerprint_excludes()
    current = inventory(r, excludes)
    changes: list[dict] = []
    if base_manifest:
        bpath = pathlib.Path(base_manifest)
        try:
            base_doc = json.loads(bpath.read_text())
            base = base_doc["files"]
        except (OSError, ValueError, KeyError, TypeError) as exc:
            fail(f"baseline manifest missing or malformed ({bpath}): {exc}")
        # RAI-256: never diff across inventory compositions — a pre-change
        # base describes a different file universe (it may contain wt/**
        # entries and miss checked-in prompts), so the diff would be garbage
        # in either direction. Refuse with the regeneration instruction.
        base_version = base_doc.get("inventory_version")
        if base_version != CURRENT_INVENTORY_VERSION:
            fail(f"baseline manifest {bpath} was produced by source-inventory "
                 f"composition version {base_version!r}; this runner builds "
                 f"version {CURRENT_INVENTORY_VERSION} (RAI-256: wt/** sibling "
                 "worktrees and local .opencode state excluded, checked-in "
                 ".opencode prompts included) — regenerate the base inventory "
                 "in this checkout (quality-inventory), never diff across "
                 "compositions")
        changes = _manifest_diff(base, current)
    else:
        try:
            changes = git_changes(r, excludes)
        except GitUnavailableError as exc:
            fail(f"Git unavailable: isolated snapshot ({exc}). Set QUALITY_BASE_MANIFEST "
                 "(manifest mode) or run inside a git checkout; silent fast execution "
                 "is forbidden.")
    return current, changes


def classify(changes: list[dict], policy: dict) -> dict[str, list[str]]:
    """File-keyed classification: {path: sorted [profiles...]}.

    A path matching no path_profiles pattern maps to ["__unmapped__"].
    Matching is a segment prefix match on the alternation-split patterns
    (RAI-054: the old profile-keyed return value is removed).
    """
    by_file: dict[str, list[str]] = {}
    for ch in changes:
        rel = ch["path"]
        profiles: list[str] = []
        for pattern, profs in policy["path_profiles"].items():
            for alt in pattern.split("|"):
                alt = alt.strip().lstrip("^").rstrip("/")
                if alt and (rel == alt or rel.startswith(alt + "/")):
                    profiles.extend(profs)
        by_file[rel] = sorted(set(profiles)) or ["__unmapped__"]
    return by_file


def select_profiles(changes: list[dict], policy: dict) -> tuple[list[str], list[str]]:
    """Decision core (no gate execution): classify per file once, then return
    (sorted union of all per-file profiles -- includes the __unmapped__
    pseudo-profile when present, sorted list of unmapped file paths)."""
    by_file = classify(changes, policy)
    unmapped = sorted(p for p, profs in by_file.items() if "__unmapped__" in profs)
    union = sorted({pr for profs in by_file.values() for pr in profs})
    return union, unmapped


def toolchain_snapshot() -> dict:
    def run(cmd: list[str]) -> str:
        try:
            # RAI-255: pin to ROOT — `sbt --version` reports the project's sbt
            # and must not depend on the caller's cwd. Values for the standard
            # root invocation are unchanged.
            r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=30)
            return (r.stdout or r.stderr).strip().splitlines()[0] if (r.stdout or r.stderr) else "unknown"
        except Exception:
            return "unavailable"
    return {
        "java": run(["java", "-version"]),
        "sbt": run(["sbt", "--version"]),
        "python3": run(["python3", "--version"]),
        "scala": "3.9.0 (pinned)",
        "zio": "2.1.26 (pinned)",
        "zio_json": "0.10.0 (pinned)",
    }


def run_gate(gate_id: str, gate: dict, log_dir: pathlib.Path) -> dict:
    argv = list(gate["argv"])
    started = time.time()
    # sbt 'Test/runMain x.y.Z' style: last token may contain spaces -> subprocess list
    # handles it as ONE argv element, which is what sbt expects.
    try:
        proc = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True,
                              timeout=gate.get("timeout_s", 600))
        code, out = proc.returncode, proc.stdout + proc.stderr
        timed_out = False
    except subprocess.TimeoutExpired as exc:
        code, timed_out = 1, True
        def _txt(v):
            if isinstance(v, bytes):
                return v.decode(errors="replace")
            return v or ""
        out = _txt(exc.stdout) + _txt(exc.stderr) + "\n[quality] TIMEOUT"
    except FileNotFoundError as exc:
        code, timed_out, out = 1, False, f"[quality] tool not found: {exc}"
    log_path = log_dir / f"{gate_id}.log"
    log_path.write_text(out)
    status = "passed" if code == 0 and not timed_out else "failed"
    return {"id": gate_id, "argv": argv, "exit_code": code,
            "status": status, "timed_out": timed_out,
            "log": str(log_path.relative_to(ROOT)), "log_sha256": sha256_file(log_path),
            "duration_s": round(time.time() - started, 2)}


def env_base(args) -> str | None:
    return args.base_manifest or os.environ.get("QUALITY_BASE_MANIFEST")


_RUN_SEQ = 0
_RUN_SEQ_LOCK = threading.Lock()


def _next_run_id() -> str:
    """Filesystem-safe unique run id: `<YYYYmmdd-HHMMSS>-<pid>-<seq>`.

    Uniqueness: `seq` is a per-process monotonic counter, so two calls in one
    process can never collide; `<pid>` separates distinct concurrent
    processes in the same second. A collision would additionally require PID
    reuse in the same second at the same seq — not possible for two LIVE
    concurrent runs. This is a documented structural guarantee, not a
    cryptographic one. Fixes the round-1 second-granularity collision where
    two parallel executes in the same second shared one run dir and clobbered
    each other's logs/manifest (QUALITYRUN-03).
    """
    global _RUN_SEQ
    with _RUN_SEQ_LOCK:
        _RUN_SEQ += 1
        seq = _RUN_SEQ
    return f"{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}-{seq:04d}"


def cmd_inventory(args) -> int:
    files = current_inventory()
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # RAI-256: the base manifest carries the inventory composition version so
    # fingerprint() can refuse to diff across compositions.
    out.write_text(json.dumps({"files": files,
                               "inventory_version": CURRENT_INVENTORY_VERSION},
                              indent=0, sort_keys=True))
    print(f"[quality] base inventory: {out} ({len(files)} files, "
          f"inventory_version={CURRENT_INVENTORY_VERSION})")
    return 0


def cmd_changed(args) -> int:
    policy = load_policy()
    files, changes = fingerprint(env_base(args))
    if not changes:
        if env_base(args):
            print("[quality] no changes vs base manifest (clean baseline executes minimum fast)")
        else:
            print("[quality] no changes vs HEAD (clean tree executes minimum fast)")
        profiles = ["fast"]
    else:
        profiles, _unmapped = select_profiles(changes, policy)
    print(json.dumps({"changed": changes, "profiles": profiles}, indent=2))
    return 0


def cmd_execute(args) -> int:
    policy = load_policy()
    files, changes = fingerprint(env_base(args))
    if not changes:
        selected = ["fast"]
    else:
        selected, unmapped = select_profiles(changes, policy)
        if unmapped:
            fail("changed files map to no profile (not skipped): "
                 + ", ".join(unmapped[:10]) + (" ..." if len(unmapped) > 10 else ""))
    if args.profiles:
        for pr in args.profiles.split(","):
            if pr not in policy["profiles"]:
                fail(f"unknown profile '{pr}' (registry: {sorted(policy['profiles'])})")
        selected = sorted(set(args.profiles.split(",")))
    if not selected:
        fail("no profiles selected")

    run_id = _next_run_id()
    started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    log_dir = EVIDENCE_DIR / run_id
    log_dir.mkdir(parents=True, exist_ok=True)
    gates_needed: list[str] = []
    for prof in selected:
        stage = policy["profiles"][prof]["stage"]
        if stage != "component":
            fail(f"profile {prof} is {stage}-stage: not executable yet (not a skip)")
        gates_needed.extend(policy["profiles"][prof]["gates"])
    gates_needed = list(dict.fromkeys(gates_needed))
    if not gates_needed:
        fail("no gates selected for profiles " + str(selected) + " — refusing empty suite")

    results = [run_gate(g, policy["gates"][g], log_dir) for g in gates_needed]
    status = "passed" if all(r["status"] == "passed" for r in results) else "failed"
    manifest = {
        "schema_version": 2,
        "run_id": run_id,
        "status": status,
        "mode": "manifest-diff" if env_base(args) else "git-status",
        "source": {"files": files,
                   # RAI-256: bind the evidence to the inventory composition
                   # that produced it (strict verify refuses a mismatch).
                   "inventory_version": CURRENT_INVENTORY_VERSION,
                   "fingerprint_sha256": hashlib.sha256(
                       json.dumps(files, sort_keys=True).encode()).hexdigest()},
        "audit": {"excluded_operational_records": operational_record_audit(ROOT)},
        "policy_sha256": sha256_file(POLICY),
        "toolchain": toolchain_snapshot(),
        "profiles": selected,
        "gates": results,
        "test_summary": {"passed": sum(1 for r in results if r["status"] == "passed"),
                         "failed": sum(1 for r in results if r["status"] != "passed"),
                         "skipped": 0},
        "started_at": started_at,
    }
    manifest["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    mpath = log_dir / "manifest.json"
    mpath.write_text(json.dumps(manifest, indent=2))
    print(f"[quality] status={status} profiles={selected} manifest={mpath}")
    for r in results:
        print(f"  {r['id']}: {r['status']} exit={r['exit_code']} timeout={r['timed_out']}")
    return 0 if status == "passed" else 1


# RAI-255: gate-entry shape written by run_gate for schema_version>=2.
_GATE_FIELDS = {"id", "argv", "exit_code", "status", "timed_out",
                "log", "log_sha256", "duration_s"}
# RAI-255: toolchain identity that must be recorded (and, in strict verify,
# must match this environment). python3 is recorded but deliberately not
# identity-compared: it is not part of the product toolchain identity.
_TOOLCHAIN_IDENTITY_KEYS = ("java", "sbt", "scala")
_NOT_ESTABLISHED = ("", "unknown", "unavailable")


def _required_gates(policy: dict, claimed: list[str]) -> dict[str, list[str]]:
    """Registry-required gate ids for the claimed profiles -> claiming profiles."""
    required: dict[str, list[str]] = {}
    for prof in claimed:
        for gid in policy["profiles"][prof]["gates"]:
            required.setdefault(gid, []).append(prof)
    return required


def _toolchain_verify_problems(recorded, strict: bool) -> list[str]:
    """RAI-255: the manifest toolchain must attest an established identity for
    java/sbt/scala (all schema versions). In strict verify (no --allow-stale)
    the recorded identity must ALSO equal this environment's snapshot — the
    old verifier printed 'toolchain hashes checked' without ever comparing.
    A missing local tool is a fail, never a skip (honesty rules)."""
    problems: list[str] = []
    if not isinstance(recorded, dict):
        return [f"manifest toolchain missing or not an object "
                f"(got {type(recorded).__name__}) — evidence cannot attest "
                "the executing toolchain"]
    recorded_identity: dict[str, str] = {}
    for key in _TOOLCHAIN_IDENTITY_KEYS:
        val = recorded.get(key)
        ok = isinstance(val, str) and val.strip() not in _NOT_ESTABLISHED
        if ok:
            recorded_identity[key] = val.strip()
        else:
            problems.append(f"manifest toolchain[{key}] is missing or not established "
                            f"({val!r}) — evidence cannot attest the executing toolchain")
    if not strict:
        return problems
    current = toolchain_snapshot()
    for key in _TOOLCHAIN_IDENTITY_KEYS:
        cur = (current.get(key) or "").strip()
        if cur in _NOT_ESTABLISHED:
            problems.append(f"verifying environment toolchain[{key}] is not established "
                            f"({cur!r}) — cannot compare identity (a missing tool is a "
                            "fail, not a skip)")
            continue
        rec = recorded_identity.get(key)
        if rec is not None and rec != cur:
            problems.append(f"toolchain identity mismatch on {key}: manifest recorded "
                            f"{rec!r}, verifying environment reports {cur!r} — rerun the "
                            "gates in this environment or pass --allow-stale to treat "
                            "the manifest as historical evidence")
    return problems


def _gate_verify_problems(gates, policy: dict, schema_version: int) -> tuple[list[str], dict[str, dict]]:
    """RAI-255 structural gate validation. Returns (problems, gate-by-id).
    Full field/registry/argv/consistency checks apply to schema_version>=2;
    older manifests keep the historical lenient checks (status/log/hash) —
    they are exempt from completeness too (registry grew after them)."""
    problems: list[str] = []
    by_id: dict[str, dict] = {}
    if not isinstance(gates, list):
        return ["manifest gates must be a list"], by_id
    strict_gates = schema_version >= 2
    for g in gates:
        if not isinstance(g, dict):
            problems.append("gate entry is not an object")
            continue
        gid = g.get("id")
        if not isinstance(gid, str) or not gid:
            problems.append(f"gate entry without a string id: {g!r}"[:200])
            continue
        if gid in by_id:
            problems.append(f"duplicate gate id '{gid}' in manifest")
            continue
        by_id[gid] = g
        if not strict_gates:
            # historical schema-v1: keep the pre-RAI-255 checks only (status +
            # log presence/hash). RAI-255 review fix: both fields are REQUIRED
            # non-empty strings — the base verifier crashed with KeyError on a
            # fabricated gate lacking them, so accepting such a gate would be
            # a false-accept regression of the audited defect class. All real
            # v1 manifests wrote both fields.
            if g.get("status") != "passed":
                problems.append(f"gate {gid} status={g.get('status')}")
            log_field = g.get("log")
            hash_field = g.get("log_sha256")
            log_ok = isinstance(log_field, str) and bool(log_field)
            hash_ok = isinstance(hash_field, str) and bool(hash_field)
            if not log_ok:
                problems.append(f"gate {gid} log field missing — a passed gate must "
                                "reference its run-time log (fabricated gate)")
            if not hash_ok:
                problems.append(f"gate {gid} log_sha256 missing — a passed gate must "
                                "bind its run-time log hash (fabricated gate)")
            if not (log_ok and hash_ok):
                continue
            log = ROOT / log_field
            if not log.exists():
                problems.append(f"gate {gid} log missing")
            elif sha256_file(log) != hash_field:
                problems.append(f"gate {gid} log hash stale")
            continue
        unknown = sorted(set(g) - _GATE_FIELDS)
        if unknown:
            problems.append(f"gate '{gid}' has unknown fields {unknown}")
        missing = sorted(_GATE_FIELDS - set(g))
        if missing:
            problems.append(f"gate '{gid}' missing fields {missing}")
            continue
        if gid not in policy["gates"]:
            problems.append(f"gate '{gid}' is not in the policy registry — evidence "
                            "cannot attest a check the policy does not define")
            continue
        if list(g["argv"]) != list(policy["gates"][gid]["argv"]):
            problems.append(f"gate '{gid}' argv does not match the policy registry "
                            f"(recorded {g['argv']!r}, policy "
                            f"{policy['gates'][gid]['argv']!r})")
        if g["status"] != "passed" or g["exit_code"] != 0:
            problems.append(f"gate '{gid}' did not pass (status={g['status']!r}, "
                            f"exit_code={g['exit_code']!r})")
        if g["timed_out"]:
            # independent diagnostic: a timeout is never a pass, whatever the
            # recorded exit code (RAI-209 classification invariant)
            problems.append(f"gate '{gid}' timed_out=true — a timeout is never a pass")
        if not isinstance(g["duration_s"], (int, float)) or g["duration_s"] < 0:
            problems.append(f"gate '{gid}' has invalid duration_s {g['duration_s']!r}")
        log = ROOT / str(g["log"])
        if not log.is_file():
            problems.append(f"gate '{gid}' log file missing: {g['log']}")
        elif sha256_file(log) != g["log_sha256"]:
            problems.append(f"gate '{gid}' log sha256 mismatch (tampered or replaced log)")
    return problems, by_id


def cmd_verify(args) -> int:
    """Verify a quality manifest (RAI-255 hardened; see module docstring).

    Rejects (exit 1, precise diagnostics, no tracebacks): malformed manifests;
    zero executed gates; claimed profiles missing registry-required gates
    (schema_version>=2; v1 exempt); structurally incoherent v2 gate entries;
    unestablished toolchain identity (all versions) or identity differing from
    this environment (strict mode only); plus the pre-existing tamper checks
    (statuses, log hashes, policy hash, fingerprint freshness, operational
    record audit). --allow-stale keeps fingerprint freshness and toolchain
    identity comparison off (historical evidence) — the policy hash and the
    structural checks still apply. RAI-256 adds: strict verify rejects
    schema-v2 manifests whose recorded source.inventory_version differs from
    this verifier's inventory composition."""
    load_policy()  # the registry must parse before any acceptance decision
    policy = json.loads(POLICY.read_text())
    mpath = pathlib.Path(args.manifest)
    if not mpath.exists():
        fail(f"manifest not found: {mpath}")
    try:
        m = json.loads(mpath.read_text())
    except json.JSONDecodeError as exc:
        fail(f"manifest is not valid JSON: {mpath}: {exc}")
    if not isinstance(m, dict):
        fail(f"manifest root must be a JSON object: {mpath}")

    problems: list[str] = []
    if m.get("status") != "passed":
        problems.append(f"manifest status={m.get('status')!r} (only status=passed verifies)")
    schema_version = m.get("schema_version", 1)
    if schema_version not in (1, 2):
        problems.append(f"unsupported manifest schema_version={schema_version!r}")

    required = args.require.split(",") if args.require else m.get("profiles", [])
    claimed = m.get("profiles")
    if not isinstance(claimed, list) or not all(isinstance(p, str) for p in claimed):
        problems.append(f"manifest claims no profiles (profiles must be a list of "
                        f"strings, got {claimed!r})")
        claimed = []
    for prof in required:
        if prof not in claimed:
            problems.append(f"required profile {prof} not in manifest profiles {claimed}")

    gate_problems, by_id = _gate_verify_problems(m.get("gates"), policy,
                                                 schema_version if isinstance(schema_version, int) else 0)
    problems.extend(gate_problems)

    # RAI-255: a manifest with zero executed gates attests nothing — reject on
    # every schema version (the audited synthetic `gates: []` manifest).
    if not m.get("gates"):
        problems.append("manifest records ZERO executed gates — it cannot attest "
                        "any quality profile")

    # RAI-255: gate-set completeness for the claimed profiles (v2+ only;
    # schema-v1 manifests predate the grown profiles and are exempt).
    if isinstance(schema_version, int) and schema_version >= 2:
        unknown_profiles = [p for p in claimed if p not in policy["profiles"]]
        if unknown_profiles:
            problems.append(f"manifest claims unknown profile(s) {unknown_profiles} "
                            f"(registry: {sorted(policy['profiles'])})")
        complete_claims = [p for p in claimed if p in policy["profiles"]]
        if complete_claims:
            missing = {gid: profs
                       for gid, profs in _required_gates(policy, complete_claims).items()
                       if gid not in by_id}
            if missing:
                detail = "; ".join(f"'{gid}' required by {profs}"
                                   for gid, profs in sorted(missing.items()))
                problems.append(f"manifest is incomplete for claimed profile(s) "
                                f"{complete_claims}: missing required gate(s): {detail}")

    if m.get("policy_sha256") != sha256_file(POLICY):
        problems.append("manifest policy_sha256 does not match "
                        "scripts/quality-policy/registry.json — evidence was produced "
                        "under a different gate policy")

    problems.extend(_toolchain_verify_problems(m.get("toolchain"), strict=not args.allow_stale))

    source = m.get("source") if isinstance(m.get("source"), dict) else {}
    recorded_fp = source.get("fingerprint_sha256")
    if not isinstance(recorded_fp, str) or not recorded_fp:
        problems.append("manifest source.fingerprint_sha256 missing — evidence is not "
                        "bound to any product-input snapshot")
    elif not args.allow_stale:
        # RAI-256: a schema-v2 manifest without (or with a foreign) recorded
        # inventory version predates this verifier's source composition —
        # strict-verify refuses it with the migration diagnostic even if the
        # fingerprint happens to match, because the recorded "files" universe
        # is not comparable to the recomputed one.
        inv_version = source.get("inventory_version")
        if (isinstance(schema_version, int) and schema_version >= 2
                and inv_version != CURRENT_INVENTORY_VERSION):
            problems.append(
                f"manifest source.inventory_version={inv_version!r} does not match "
                f"this verifier's inventory composition version "
                f"{CURRENT_INVENTORY_VERSION} (RAI-256 source-inventory isolation; "
                "pre-isolation manifests cannot be strict-verified against the new "
                "composition) — regenerate the affected gates; real pre-RAI-256 "
                "manifests also fail the policy-hash check (the registry changed in "
                "the same card), so historical verification does not apply to them "
                "either")
        files_now = legacy_inventory(ROOT) if schema_version == 1 else current_inventory()
        fp_now = hashlib.sha256(json.dumps(files_now, sort_keys=True).encode()).hexdigest()
        if fp_now != recorded_fp:
            changed = sum(1 for k in files_now
                          if source.get("files", {}).get(k, {}).get("sha256")
                          != files_now[k].get("sha256"))
            problems.append(f"source fingerprint changed ({changed} files differ) — rerun "
                            "affected gates (--allow-stale overrides, evidence is then "
                            "historical)")

    if schema_version == 2:
        stored_records = (m.get("audit") or {}).get("excluded_operational_records")
        if not isinstance(stored_records, list):
            problems.append("schema-v2 manifest is missing excluded operational record audit")
        else:
            audit_problems, additions = validate_operational_record_audit(
                stored_records, operational_record_audit(ROOT))
            problems.extend(audit_problems)
            for entry in additions:
                print(f"[verify] post-gate operational record addition: "
                      f"{entry['path']} {entry.get('sha256', entry.get('target'))}")

    if problems:
        for p in problems:
            print(f"[verify] FAIL: {p}", file=sys.stderr)
        return 1
    print(f"[verify] OK: manifest {mpath} satisfies {required} "
          f"({len(by_id)} gate(s), hashes checked; toolchain identity "
          f"{'recorded' if args.allow_stale else 'matches this environment'}; "
          + ("historical (--allow-stale)" if args.allow_stale else "fingerprint fresh")
          + ")")
    return 0


def _self_test_classify(policy: dict) -> None:
    """RAI-054: classify is file-keyed; known/unknown paths map correctly."""
    cases = [
        ("docs/quality-gates.md", ["docs"]),
        ("modules/core/src/main/scala/raider/core/Example.scala",
         ["contracts", "static", "unit"]),
        # RAI-256: checked-in .opencode prompts are product inputs mapped to
        # the docs profile (they must never classify as __unmapped__).
        (".opencode/agents/raider-worker.md", ["docs"]),
        (".opencode/commands/raider-wave.md", ["docs"]),
        # RAI-281: the post-release changelog maps to docs (the RAI-279 release
        # fold makes it a routine post-release change; previously it failed
        # closed as __unmapped__ on every release integration).
        ("CHANGELOG.md", ["docs"]),
        # RAI-288: vendored corpora and the notices file map to the vendor
        # profile (previously __unmapped__ on every corpus-import lane,
        # RAI-032/RAI-033 blocker; both lane corpus shapes covered).
        ("vendor/pi-profiles/MANIFEST.sha256", ["vendor"]),
        ("vendor/pi-profiles/upstream/agents.ts", ["vendor"]),
        ("vendor/opencode-profiles/extracted/profile.md", ["vendor"]),
        ("THIRD_PARTY_NOTICES.md", ["vendor"]),
        ("zzz-unmapped.bin", ["__unmapped__"]),
    ]
    for path, expected in cases:
        got = classify([{"path": path, "kind": "modified"}], policy)[path]
        if got != expected:
            fail(f"self-check: classify({path}) = {got}, expected {expected}")


def _git(cwd: pathlib.Path, *argv: str) -> None:
    r = subprocess.run(["git", *argv], cwd=cwd, capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        fail(f"self-check: git {' '.join(argv)} failed: "
             f"{(r.stderr or r.stdout).strip()[:200]}")


def _self_test_git_fingerprint(policy: dict) -> None:
    """RAI-054: git-mode fingerprint on a temp repo (system temp only, never the
    repo tree): clean tree -> empty; dirty tree -> correct kinds, rename R
    expanded as delete+add."""
    if shutil.which("git") is None:
        fail("self-check: git binary not found (git-mode fingerprint test requires git)")
    excludes = set(policy["fingerprint_excludes"])
    td = pathlib.Path(tempfile.mkdtemp(prefix="quality-selfcheck-git-"))
    try:
        _git(td, "init", "-q")
        (td / "docs").mkdir()
        (td / "docs" / "a.md").write_text("x\n")
        (td / "keep.txt").write_text("k\n")
        (td / "renamed-src.txt").write_text("r\n")
        _git(td, "add", "-A")
        _git(td, "-c", "user.name=quality-self-check",
             "-c", "user.email=quality-self-check@example.invalid",
             "commit", "-q", "-m", "init")
        if git_changes(td, excludes) != []:
            fail("self-check: git fingerprint must be empty on a clean tree")
        (td / "docs" / "a.md").write_text("x changed\n")  # modified
        (td / "keep.txt").unlink()                        # deleted
        (td / "new.txt").write_text("n\n")                # untracked -> added
        _git(td, "mv", "renamed-src.txt", "renamed-dst.txt")  # rename -> delete+add
        changes = git_changes(td, excludes)
        kinds = {c["path"]: c["kind"] for c in changes}
        expected = {"docs/a.md": "modified", "keep.txt": "deleted", "new.txt": "added",
                    "renamed-src.txt": "deleted", "renamed-dst.txt": "added"}
        for path, kind in expected.items():
            if kinds.get(path) != kind:
                fail(f"self-check: git fingerprint {path} = {kinds.get(path)!r}, "
                     f"expected {kind!r} (changes={changes})")
    finally:
        shutil.rmtree(td, ignore_errors=True)


def _self_test_manifest_fingerprint(policy: dict) -> None:
    """RAI-054 r2: manifest-mode fingerprint on a temp tree (system temp only,
    NO git present — the run also proves manifest mode is git-free): write a
    base inventory with the production inventory() logic, then modify a file,
    delete a file, add a file and re-point a symlink; assert fingerprint()
    yields EXACTLY added/modified/deleted kinds for exactly those paths, and
    that an untouched tree yields empty changes (spec 2: hash, mode, symlink
    target)."""
    excludes = set(policy["fingerprint_excludes"])
    outer = pathlib.Path(tempfile.mkdtemp(prefix="quality-selfcheck-manifest-"))
    try:
        td = outer / "tree"
        (td / "docs").mkdir(parents=True)
        (td / "docs" / "a.md").write_text("x\n")
        (td / "keep.txt").write_text("k\n")
        (td / "gone.txt").write_text("g\n")
        (td / "docs" / "work" / "records").mkdir(parents=True)
        (td / "docs" / "work" / "records" / "before.md").write_text("record before\n")
        (td / "docs" / "work" / "ordinary.md").write_text("ordinary docs\n")
        (td / "link.txt").symlink_to("keep.txt")
        base = inventory(td, excludes)
        mpath = outer / "base-manifest.json"  # OUTSIDE the inventoried tree
        # RAI-256: production base manifests carry the inventory version.
        mpath.write_text(json.dumps({"files": base,
                                     "inventory_version": CURRENT_INVENTORY_VERSION},
                                    sort_keys=True))
        _cur, changes = fingerprint(str(mpath), root=td)
        if changes:
            fail(f"self-check: manifest fingerprint of an unmodified tree must be "
                 f"empty (got {changes})")
        # RAI-256: a base manifest without an inventory version predates the
        # composition change — diffing across compositions is refused, not
        # silently interpreted.
        old_mpath = outer / "pre-isolation-base-manifest.json"
        old_mpath.write_text(json.dumps({"files": base}, sort_keys=True))
        try:
            fingerprint(str(old_mpath), root=td)
            fail("self-check[RAI-256]: a pre-isolation base manifest (no "
                 "inventory_version) must be refused, not diffed")
        except SystemExit as exc:
            if exc.code != 1:
                raise
        (td / "docs" / "a.md").write_text("x changed\n")  # hash -> modified
        (td / "docs" / "work" / "records" / "after.md").write_text("record after\n")
        (td / "docs" / "work" / "records" / "before.md").write_text("record changed\n")
        (td / "docs" / "work" / "ordinary.md").write_text("ordinary docs changed\n")
        (td / "gone.txt").unlink()                        # -> deleted
        (td / "new.txt").write_text("n\n")                # -> added
        (td / "link.txt").unlink()
        (td / "link.txt").symlink_to("new.txt")           # symlink target -> modified
        _cur, changes = fingerprint(str(mpath), root=td)
        kinds = {c["path"]: c["kind"] for c in changes}
        expected = {"docs/a.md": "modified", "docs/work/ordinary.md": "modified",
                    "gone.txt": "deleted", "new.txt": "added",
                    "link.txt": "modified"}
        if kinds != expected or len(changes) != len(expected):
            fail(f"self-check: manifest fingerprint kinds mismatch: {kinds} "
                 f"(changes={changes}, expected {expected})")
        audit = operational_record_audit(td)
        audited = {item["path"] for item in audit}
        if audited != {"docs/work/records/before.md", "docs/work/records/after.md"}:
            fail(f"self-check: record audit entries mismatch: {sorted(audited)}")
        if any(path in ("docs/work/records/before.md", "docs/work/records/after.md")
               for path in inventory(td, excludes)):
            fail("self-check: operational records leaked into fingerprint inventory")

        # Gate-time audit is immutable: tampering with a listed excluded record
        # must fail, while a post-gate addition is visible without refreshing it.
        gate_root = outer / "gate-tree"
        records = gate_root / OPERATIONAL_RECORDS
        records.mkdir(parents=True)
        listed = records / "listed.md"
        listed.write_text("gate-time record\n")
        gate_inventory = inventory(gate_root, excludes)
        gate_fp = hashlib.sha256(json.dumps(gate_inventory, sort_keys=True).encode()).hexdigest()
        gate_audit = operational_record_audit(gate_root)
        listed.write_text("tampered after gate\n")
        problems, additions = validate_operational_record_audit(
            gate_audit, operational_record_audit(gate_root))
        if not problems or additions:
            fail(f"self-check: tampered excluded record must fail audit validation "
                 f"(problems={problems}, additions={additions})")
        listed.write_text("gate-time record\n")
        added = records / "added.md"
        added.write_text("post-gate addition\n")
        current_fp = hashlib.sha256(json.dumps(
            inventory(gate_root, excludes), sort_keys=True).encode()).hexdigest()
        problems, additions = validate_operational_record_audit(
            gate_audit, operational_record_audit(gate_root))
        if problems or len(additions) != 1 or additions[0]["path"] != f"{OPERATIONAL_RECORDS}/added.md" \
                or additions[0].get("sha256") != sha256_file(added) or current_fp != gate_fp:
            fail(f"self-check: post-gate record addition must pass audit without changing "
                 f"fingerprint (problems={problems}, additions={additions}, "
                 f"fingerprints={gate_fp}/{current_fp})")
        print(f"[self-check] schema-v2 operational audit: tampered listed record fails; "
              f"post-gate addition passes with sha256={additions[0]['sha256']}; "
              f"fingerprint unchanged ({gate_fp})")
    finally:
        shutil.rmtree(outer, ignore_errors=True)


def _self_test_run_id() -> None:
    """RAI-054 r2: two immediately successive run ids in ONE process must
    differ (per-process monotonic seq) and be filesystem-safe. Exactly what
    this proves: in-process uniqueness of successive calls. It does NOT run
    parallel gate executes; cross-process uniqueness in the same second rests
    on distinct PIDs (documented limit in _next_run_id)."""
    a, b = _next_run_id(), _next_run_id()
    if a == b:
        fail(f"self-check: successive run_ids must differ (got {a!r} twice)")
    for rid in (a, b):
        if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", rid):
            fail(f"self-check: run_id is not filesystem-safe: {rid!r}")
    print(f"[self-check] run_id uniqueness: two immediately successive calls "
          f"differ ({a} != {b}); filesystem-safe charset verified "
          f"(in-process guarantee; cross-process same-second uniqueness = distinct PIDs)")


def _self_test_select(policy: dict) -> None:
    """RAI-054: execute decision (no gate execution) — docs-only change selects
    exactly ['docs'] (RAI-281: CHANGELOG.md-only likewise); an unmapped file
    must fail the decision."""
    union, unmapped = select_profiles([{"path": "docs/x.md", "kind": "modified"}], policy)
    if union != ["docs"] or unmapped:
        fail(f"self-check: docs-only change must select exactly ['docs'] "
             f"(got {union}, unmapped={unmapped})")
    union, unmapped = select_profiles(
        [{"path": "CHANGELOG.md", "kind": "modified"}], policy)
    if union != ["docs"] or unmapped:
        fail(f"self-check: CHANGELOG.md-only change must select exactly "
             f"['docs'] (got {union}, unmapped={unmapped})")
    # RAI-288: a vendor-corpus import (corpus + notices file) selects exactly
    # the vendor profile — previously the unmapped-paths refusal.
    union, unmapped = select_profiles(
        [{"path": "vendor/pi-profiles/MANIFEST.sha256", "kind": "added"},
         {"path": "THIRD_PARTY_NOTICES.md", "kind": "added"}], policy)
    if union != ["vendor"] or unmapped:
        fail(f"self-check: vendor-corpus-only change must select exactly "
             f"['vendor'] (got {union}, unmapped={unmapped})")
    union, unmapped = select_profiles([{"path": "zzz-unmapped.bin", "kind": "added"}], policy)
    if "zzz-unmapped.bin" not in unmapped:
        fail(f"self-check: unmapped file must fail the execute decision "
             f"(got union={union}, unmapped={unmapped})")


def _self_test_security(policy: dict) -> None:
    """RAI-069: the security profile must resolve as a component-stage profile
    with existing gates, and the secrets scanner must prove detection/masking
    on its embedded fixtures (SEC-02 verify-negative basis)."""
    sec = policy["profiles"].get("security")
    if not sec:
        fail("self-check: security profile missing from the registry")
    if sec.get("stage") != "component":
        fail(f"self-check: security profile stage is {sec.get('stage')!r}, "
             "expected component")
    for gate in sec["gates"]:
        if gate not in policy["gates"]:
            fail(f"self-check: security profile references unknown gate {gate}")
    r = subprocess.run(["python3", "scripts/quality/security_checks.py",
                        "--self-test"],
                       cwd=ROOT, capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        fail(f"self-check: security_checks.py --self-test exited "
             f"{r.returncode}: {(r.stderr or r.stdout).strip()[:300]}")


def _self_test_changelog_lint(policy: dict) -> None:
    """RAI-281: CHANGELOG.md maps to the docs profile and that coverage is a
    real check — the docs gate set must carry changelog-lint with its pinned
    argv, and the lint must prove failure-class detection on embedded fixtures
    (a clean repository tree is never the proof that a linter detects)."""
    docs = policy["profiles"].get("docs")
    if not docs:
        fail("self-check: docs profile missing from the registry")
    if "changelog-lint" not in docs["gates"]:
        fail("self-check: docs profile must carry the changelog-lint gate "
             "(RAI-281); got gates=" + repr(docs["gates"]))
    gate = policy["gates"].get("changelog-lint")
    if not gate:
        fail("self-check: changelog-lint gate missing from the registry")
    if gate.get("argv") != ["python3", "scripts/quality/changelog_lint.py"]:
        fail(f"self-check: changelog-lint argv drifted: {gate.get('argv')!r}")
    r = subprocess.run(["python3", "scripts/quality/changelog_lint.py",
                        "--self-test"],
                       cwd=ROOT, capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        fail(f"self-check: changelog_lint.py --self-test exited "
             f"{r.returncode}: {(r.stderr or r.stdout).strip()[:300]}")


def _self_test_vendor_audit(policy: dict) -> None:
    """RAI-288: vendor/** and THIRD_PARTY_NOTICES.md map to the vendor profile
    and that coverage is a real check — the vendor gate set must carry
    vendor-audit with its pinned argv, and the audit must prove every failure
    class detection on embedded fixtures through its own --self-test (a clean
    repository tree is never the proof that a checker detects; the lane tree
    currently has no vendor/ corpora — importer lanes RAI-032/RAI-033 own
    those, and this gate must already detect their tamper classes the day
    they land)."""
    vendor = policy["profiles"].get("vendor")
    if not vendor:
        fail("self-check: vendor profile missing from the registry")
    if vendor.get("stage") != "component":
        fail(f"self-check: vendor profile stage is {vendor.get('stage')!r}, "
             "expected component")
    if "vendor-audit" not in vendor["gates"]:
        fail("self-check: vendor profile must carry the vendor-audit gate "
             "(RAI-288); got gates=" + repr(vendor["gates"]))
    gate = policy["gates"].get("vendor-audit")
    if not gate:
        fail("self-check: vendor-audit gate missing from the registry")
    if gate.get("argv") != ["python3", "scripts/quality/vendor_audit.py"]:
        fail(f"self-check: vendor-audit argv drifted: {gate.get('argv')!r}")
    r = subprocess.run(["python3", "scripts/quality/vendor_audit.py",
                        "--self-test"],
                       cwd=ROOT, capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        fail(f"self-check: vendor_audit.py --self-test exited "
             f"{r.returncode}: {(r.stderr or r.stdout).strip()[:300]}")


def _self_test_gate_timeouts(policy: dict) -> None:
    """RAI-209 (owner decision 2026-10-26, board 2026-10-25 follow-up #4): the
    sbt-test budget was calibrated from 900 s to 1800 s to absorb host
    contention from parallel lanes (identical assertions, no bar change).
    RAI-275 (owner-ratified 2026-11-05 after independent review
    approve-with-conditions): the
    self-registry-check budget is re-frozen from 120 s to 180 s from the
    measured distribution in docs/quality-gates.md section 1a — the battery
    exceeded 120 s under sustained external host load immediately after an
    sbt-test run while every other gate stayed green (waves J/K/L). This pin
    proves the calibration and freezes every gate timeout so no
    registry edit can silently move a budget or change the gate set without
    a conscious policy-card update here. It also proves run_gate classifies
    an over-budget probe as timed_out+failed (a timeout is never a skip or a
    pass) — the same code path the 1800 s cap enforces; a fast control probe
    shows the classification is discriminating, not vacuously failing."""
    expected = {
        "self-registry-check": 180,  # RAI-275 PROPOSED: 120 -> 180, measured re-freeze
        "sbt-compile": 600,
        "core-contracts-spec": 600,
        "compile-fixtures": 300,
        "plan-validate": 120,
        "changelog-lint": 60,  # RAI-281: docs-profile changelog section lint
        "vendor-audit": 120,  # RAI-288: vendor corpus provenance audit
        "sbt-test": 1800,  # RAI-209: 900 -> 1800, contention calibration
        "scalafmt-check": 120,
        "scalafix-check": 180,
        "forbidden-apis": 60,
        "repl-smoke": 240,
        "cli-smoke": 120,
        "license-audit": 300,
        "security-secrets-scan": 300,
        "eval-corpus": 600,
        "ux-baseline": 1800,
    }
    if set(policy["gates"]) != set(expected):
        fail("self-check: gate set drifted vs the frozen timeout map: "
             + ", ".join(sorted(set(policy["gates"]) ^ set(expected))))
    for gate_id, budget in expected.items():
        got = policy["gates"][gate_id].get("timeout_s")
        if got != budget:
            fail(f"self-check: gate {gate_id} timeout_s = {got!r}, expected "
                 f"{budget!r} (budgets are frozen; changing one requires a "
                 "policy card plus a conscious update of this pin)")
    probe_dir = EVIDENCE_DIR / "self-negative" / "gate-timeout-probe"
    probe_dir.mkdir(parents=True, exist_ok=True)
    slow = run_gate("probe-over-budget",
                    {"argv": ["python3", "-c", "import time; time.sleep(3)"],
                     "timeout_s": 1}, probe_dir)
    if not (slow["timed_out"] and slow["status"] == "failed"
            and slow["exit_code"] != 0):
        fail(f"self-check: over-budget probe must classify timed_out+failed "
             f"(never skip/pass), got status={slow['status']} "
             f"timed_out={slow['timed_out']} exit_code={slow['exit_code']}")
    quick = run_gate("probe-under-budget",
                     {"argv": ["python3", "-c", "pass"], "timeout_s": 60},
                     probe_dir)
    if quick["timed_out"] or quick["status"] != "passed":
        fail(f"self-check: under-budget control probe must pass, got "
             f"status={quick['status']} timed_out={quick['timed_out']}")
    print(f"[self-check] gate budgets frozen (RAI-209): {len(expected)} gates "
          f"pinned, sbt-test timeout_s=1800; over-budget probe classified "
          f"timed_out+failed (exit {slow['exit_code']}, "
          f"{slow['duration_s']}s), under-budget control passed "
          f"({quick['duration_s']}s)")


def _synthetic_gate_entry(policy: dict, gid: str, log_dir: pathlib.Path,
                          *, exit_code: int = 0, timed_out: bool = False,
                          argv: list[str] | None = None) -> dict:
    """A schema-v2 gate entry in the exact run_gate shape (RAI-255), backed by
    a real log file under log_dir."""
    log = log_dir / f"{gid}.log"
    log.write_text("[quality self-check] synthetic passed gate log\n")
    return {"id": gid, "argv": argv if argv is not None else list(policy["gates"][gid]["argv"]),
            "exit_code": exit_code, "status": "passed", "timed_out": timed_out,
            "log": str(log.relative_to(ROOT)), "log_sha256": sha256_file(log),
            "duration_s": 0.1}


def _synthetic_manifest(ctx: dict, log_dir: pathlib.Path, profiles: list[str],
                        *, gates: list[dict] | None = None,
                        toolchain: dict | None = None,
                        fingerprint_sha256: str | None = None,
                        schema_version: int = 2,
                        inventory_version: int | None = CURRENT_INVENTORY_VERSION) -> dict:
    """A manifest coherent with the CURRENT tree, policy and toolchain except
    for the explicitly poisoned parameters (RAI-255 negative fixtures).
    RAI-256: schema-v2 manifests record source.inventory_version (default:
    this runner's composition); passing None reproduces the true pre-change
    shape (field absent)."""
    if gates is None:
        gate_ids = sorted({g for p in profiles for g in _required_gates(
            json.loads(POLICY.read_text()), profiles)})
        gates = [_synthetic_gate_entry(json.loads(POLICY.read_text()), g, log_dir)
                 for g in gate_ids]
    passed = sum(1 for g in gates if isinstance(g, dict) and g.get("status") == "passed")
    source = {"files": ctx["files"],
              "fingerprint_sha256": (fingerprint_sha256 if fingerprint_sha256
                                     else ctx["fingerprint"])}
    if schema_version >= 2 and inventory_version is not None:
        source["inventory_version"] = inventory_version
    manifest = {
        "schema_version": schema_version,
        "run_id": f"self-check-verify-{_next_run_id()}",
        "status": "passed",
        "mode": "git-status",
        "source": source,
        "policy_sha256": sha256_file(POLICY),
        "toolchain": toolchain if toolchain is not None else ctx["toolchain"],
        "profiles": profiles,
        "gates": gates,
        "test_summary": {"passed": passed, "failed": len(gates) - passed, "skipped": 0},
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "finished_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    if schema_version >= 2:
        manifest["audit"] = {"excluded_operational_records": ctx["audit"]}
    return manifest


def _run_verify(manifest_path: pathlib.Path, *extra: str) -> subprocess.CompletedProcess:
    return subprocess.run(["python3", "scripts/quality/quality.py", "verify",
                           str(manifest_path), *extra],
                          cwd=ROOT, capture_output=True, text=True, timeout=60)


def _self_test_verify_semantics(policy: dict) -> None:
    """RAI-255: verify must reject manifests that attest nothing (zero gates),
    claimed profiles with missing registry-required gates, structurally
    incoherent gate entries, and unestablished/mismatched toolchain identity —
    each with a precise diagnostic — while still accepting complete manifests
    (strict) and coherent historical ones (--allow-stale / schema-v1).
    Concurrency: parallel verify processes must reach identical verdicts on
    the same manifest (no partial state, no racy acceptance).

    The fixtures live under artifacts/ (gitignored): they are regression
    evidence, not product inputs."""
    vdir = EVIDENCE_DIR / "self-negative" / "verify"
    vdir.mkdir(parents=True, exist_ok=True)
    files_now = current_inventory()
    ctx = {"files": files_now,
           "fingerprint": hashlib.sha256(
               json.dumps(files_now, sort_keys=True).encode()).hexdigest(),
           # schema-v1 manifests fingerprint legacy_inventory (records included)
           "legacy_fingerprint": hashlib.sha256(
               json.dumps(legacy_inventory(ROOT), sort_keys=True).encode()).hexdigest(),
           "audit": operational_record_audit(ROOT),
           "toolchain": toolchain_snapshot()}

    def write_case(name: str, manifest: dict) -> pathlib.Path:
        p = vdir / f"{name}.json"
        p.write_text(json.dumps(manifest, indent=2))
        return p

    def expect_reject(name: str, manifest: dict, needles: list[str], *extra: str) -> None:
        p = write_case(name, manifest)
        r = _run_verify(p, *extra)
        combined = (r.stdout or "") + (r.stderr or "")
        if r.returncode == 0:
            fail(f"self-check[RAI-255]: verify ACCEPTED {name} (exit 0) — false green")
        for needle in needles:
            if needle not in combined:
                fail(f"self-check[RAI-255]: verify rejected {name} (exit "
                     f"{r.returncode}) but the diagnostic does not name the expected "
                     f"problem {needle!r}; output was: {combined.strip()[:500]}")

    def expect_accept(name: str, manifest: dict, *extra: str) -> None:
        p = write_case(name, manifest)
        r = _run_verify(p, *extra)
        if r.returncode != 0:
            fail(f"self-check[RAI-255]: verify REJECTED {name} (exit {r.returncode}): "
                 f"{((r.stderr or '') + (r.stdout or '')).strip()[:500]}")
        if "[verify] OK" not in (r.stdout or ""):
            fail(f"self-check[RAI-255]: verify passed {name} without an OK verdict line")

    # -- negative cases (each needle pins the diagnostic, not just the verdict)
    expect_reject("zero-gate-fast-v2",  # the audited repro, real fingerprint/policy
                  _synthetic_manifest(ctx, vdir, ["fast"], gates=[], toolchain={}),
                  ["ZERO executed gates", "missing required gate(s)",
                   "'sbt-compile' required by ['fast']",
                   "manifest toolchain[java] is missing or not established"])
    expect_reject("zero-gate-fast-v1",  # zero gates reject on every schema version
                  _synthetic_manifest(ctx, vdir, ["fast"], gates=[], toolchain={},
                                      schema_version=1),
                  ["ZERO executed gates",
                   "manifest toolchain[scala] is missing or not established"])
    expect_reject("partial-fast-v2",  # 1 of 10 required fast gates
                  _synthetic_manifest(ctx, vdir, ["fast"],
                                      gates=[_synthetic_gate_entry(policy, "self-registry-check", vdir)]),
                  ["missing required gate(s)", "'sbt-compile' required by ['fast']",
                   "'license-audit' required by ['fast']"])
    expect_reject("empty-manifest", {},
                  ["claims no profiles", "ZERO executed gates",
                   "manifest toolchain missing or not an object",
                   "source.fingerprint_sha256 missing"])
    foreign_tc = {**ctx["toolchain"],
                  "java": 'java version "1.8.0_292"',
                  "sbt": "sbt version in this project: 0.13.18"}
    foreign_fixture = _synthetic_manifest(ctx, vdir, ["self"], toolchain=foreign_tc)
    expect_reject("foreign-toolchain-v2-strict", foreign_fixture,
                  ["toolchain identity mismatch on java", 'java version "1.8.0_292"',
                   "toolchain identity mismatch on sbt"])
    expect_accept("foreign-toolchain-v2-allow-stale", foreign_fixture, "--allow-stale")
    expect_reject("required-profile-not-claimed",
                  _synthetic_manifest(ctx, vdir, ["fast"]),  # complete fast manifest
                  ["required profile self not in manifest profiles ['fast']"],
                  "--require", "self")
    dup_entry = _synthetic_gate_entry(policy, "self-registry-check", vdir)
    expect_reject("duplicate-and-unknown-gates",
                  _synthetic_manifest(ctx, vdir, ["self"],
                                      gates=[dup_entry, dict(dup_entry),
                                             _synthetic_gate_entry(
                                                 policy, "fabricated-gate", vdir,
                                                 argv=["echo", "fabricated-gate"])]),
                  ["duplicate gate id 'self-registry-check'",
                   "not in the policy registry"])
    bad_entry = _synthetic_gate_entry(policy, "self-registry-check", vdir)
    bad_entry.update(exit_code=3, timed_out=True,
                     argv=["definitely", "not", "the", "registry", "argv"],
                     duration_s=-1)
    expect_reject("incoherent-passed-gate",
                  _synthetic_manifest(ctx, vdir, ["self"], gates=[bad_entry]),
                  ["did not pass (status='passed', exit_code=3)",
                   "timed_out=true — a timeout is never a pass",
                   "argv does not match the policy registry",
                   "invalid duration_s"])
    tampered = _synthetic_manifest(ctx, vdir, ["self"])
    p = write_case("tampered-log-v2", tampered)
    (ROOT / tampered["gates"][0]["log"]).write_text("tampered after manifest write\n")
    r = _run_verify(p)
    if r.returncode == 0 or "log sha256 mismatch" not in ((r.stdout or "") + (r.stderr or "")):
        fail(f"self-check[RAI-255]: tampered gate log must be rejected with a hash "
             f"diagnostic (exit {r.returncode})")
    expect_reject("fingerprint-mismatch-v2",
                  _synthetic_manifest(ctx, vdir, ["self"], fingerprint_sha256="0" * 64),
                  ["source fingerprint changed"])
    # RAI-255 review fix (APPROVE-WITH-REQUIRED-FIX): fabricated v1 gates must
    # not verify — the base verifier crashed (KeyError) on them, so acceptance
    # would be a false-accept regression. Both reviewer probes, with a fresh
    # legacy_inventory fingerprint so the ONLY defects are the fabricated gates.
    expect_reject("v1-fabricated-nolog-gate",
                  _synthetic_manifest(ctx, vdir, ["fast"],
                                      gates=[{"id": "sbt-test", "status": "passed"}],
                                      schema_version=1,
                                      fingerprint_sha256=ctx["legacy_fingerprint"]),
                  ["gate sbt-test log field missing",
                   "gate sbt-test log_sha256 missing"])
    expect_reject("v1-log-without-hash-gate",
                  _synthetic_manifest(ctx, vdir, ["fast"],
                                      gates=[{"id": "sbt-test", "status": "passed",
                                              "log": "README.md"}],
                                      schema_version=1,
                                      fingerprint_sha256=ctx["legacy_fingerprint"]),
                  ["gate sbt-test log_sha256 missing"])
    # RAI-256: a schema-v2 manifest without source.inventory_version has the
    # true pre-change shape — strict verify must reject it with the migration
    # diagnostic even though its fingerprint is otherwise coherent with the
    # current tree. --allow-stale keeps the documented historical path (real
    # pre-change manifests additionally fail the policy-hash check because
    # this card changed the registry, so that path applies only to manifests
    # produced after this change but before a future policy change).
    expect_reject("pre-isolation-v2-no-inventory-version",
                  _synthetic_manifest(ctx, vdir, ["self"], inventory_version=None),
                  ["inventory_version", "RAI-256", "regenerate the affected gates"])
    expect_accept("pre-isolation-v2-allow-stale",
                  _synthetic_manifest(ctx, vdir, ["self"], inventory_version=None),
                  "--allow-stale")

    # -- positive controls
    self_gates = [_synthetic_gate_entry(policy, g, vdir)
                  for g in policy["profiles"]["self"]["gates"]]
    over_executed = self_gates + [_synthetic_gate_entry(policy, "plan-validate", vdir)]
    expect_accept("positive-self-v2-strict",  # over-execution beyond claims is allowed
                  _synthetic_manifest(ctx, vdir, ["self"], gates=over_executed))
    old_fast = ["self-registry-check", "sbt-compile", "core-contracts-spec",
                "compile-fixtures", "plan-validate", "sbt-test"]
    v1_gates = [{"id": g, "status": "passed", "exit_code": 0,
                 "log": str((vdir / f"{g}.log").relative_to(ROOT)),
                 "log_sha256": sha256_file(vdir / f"{g}.log")}
                for g in old_fast]
    expect_accept("positive-v1-historical",  # pre-registry-era manifest, historical verify
                  _synthetic_manifest(ctx, vdir, ["fast"], gates=v1_gates,
                                      toolchain=foreign_tc, schema_version=1),
                  "--allow-stale")

    # -- concurrency: parallel verifies of the same manifest reach one verdict
    positive_path = vdir / "positive-self-v2-strict.json"
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        runs = list(pool.map(lambda _: _run_verify(positive_path), range(6)))
    codes = {r.returncode for r in runs}
    if codes != {0} or not all("[verify] OK" in (r.stdout or "") for r in runs):
        fail(f"self-check[RAI-255]: 6 concurrent verifies of the same manifest must "
             f"all accept it (exits={codes})")

    print(f"[self-check] verify semantics (RAI-255 + RAI-256): 13 negative cases "
          f"rejected with named diagnostics (zero gates v1+v2, partial profile, "
          f"empty manifest, foreign toolchain strict, unclaimed --require, "
          f"duplicate/unknown gate, incoherent passed gate, tampered log, "
          f"fingerprint mismatch, fabricated v1 gate without log / without log "
          f"hash, pre-isolation v2 without inventory version); strict "
          f"verify matches toolchain identity to this environment and refuses "
          f"foreign inventory compositions; --allow-stale keeps only historical "
          f"semantics; complete v2 manifest + over-execution accepted; "
          f"schema-v1 historical manifest accepted; 6 concurrent verifies agreed "
          f"(all exit 0)")


def _self_test_inventory_isolation(policy: dict) -> None:
    """RAI-256 (protected policy): source-inventory isolation, exercised on a
    temp tree through the production inventory() path.

    ISO-01 composition: a product-shaped file, checked-in .opencode prompts
      (.opencode/agents/**, .opencode/commands/**) are inventoried, while a
      sibling-worktree copy (wt/raider-sibling/**, adversarially including a
      product-shaped .scala file AND a prompt copy) and lane-evidence trees
      (artifacts/**) are not.
    ISO-02 exclusion (adversarial): creating and modifying product-shaped
      files under wt/** and artifacts/** leaves fingerprint_sha256
      byte-identical — changes cannot be smuggled into the fingerprint
      through excluded paths (the audited eval-manifest defect class).
    ISO-03 local-state exclusion: .opencode local install state
      (node_modules, package.json, README.md) stays excluded — only the
      checked-in prompt directories are product inputs.
    ISO-04 inclusion: editing a checked-in .opencode prompt or a product
      source file each shifts the fingerprint (prompts are inputs, not
      invisible neighbors).
    ISO-05 concurrency: recomputing the inventory while a sibling-worktree
      churn runs concurrently (create/delete .scala files under wt/**) never
      crashes and never perturbs the included set — read-safety of the walk
      under concurrent gate runs (cancellation/concurrency acceptance).
    """
    excludes = set(policy["fingerprint_excludes"])
    outer = pathlib.Path(tempfile.mkdtemp(prefix="quality-selfcheck-iso-"))
    try:
        td = outer / "tree"
        (td / "modules" / "demo").mkdir(parents=True)
        (td / "modules" / "demo" / "Demo.scala").write_text("object Demo\n")
        (td / ".opencode" / "agents").mkdir(parents=True)
        (td / ".opencode" / "agents" / "raider-worker.md").write_text("worker prompt\n")
        (td / ".opencode" / "commands").mkdir(parents=True)
        (td / ".opencode" / "commands" / "raider-wave.md").write_text("wave prompt\n")
        (td / ".opencode" / "node_modules" / "opencode").mkdir(parents=True)
        (td / ".opencode" / "node_modules" / "opencode" / "cli.js").write_text("local install\n")
        (td / "wt" / "raider-sibling" / "modules" / "core").mkdir(parents=True)
        (td / "wt" / "raider-sibling" / "modules" / "core" / "Fake.scala").write_text("object Fake\n")
        (td / "wt" / "raider-sibling" / ".opencode" / "agents").mkdir(parents=True)
        (td / "wt" / "raider-sibling" / ".opencode" / "agents" / "raider-worker.md").write_text("sibling prompt\n")
        (td / "artifacts" / "lanes" / "rai-999").mkdir(parents=True)
        (td / "artifacts" / "lanes" / "rai-999" / "evidence.json").write_text("{}\n")

        def entries() -> dict:
            return inventory(td, excludes)

        def fp() -> str:
            return hashlib.sha256(
                json.dumps(entries(), sort_keys=True).encode()).hexdigest()

        # ISO-01: composition (the red-run defect message names both classes)
        inv0 = entries()
        fp0 = fp()
        missing_included = [p for p in ("modules/demo/Demo.scala",
                                        ".opencode/agents/raider-worker.md",
                                        ".opencode/commands/raider-wave.md")
                            if p not in inv0]
        leaked = sorted(p for p in inv0
                        if p.startswith(("wt/", "artifacts/"))
                        or ".opencode/node_modules" in p)
        if missing_included or leaked:
            fail("self-check[RAI-256]: inventory composition defects — missing "
                 f"product inputs: {missing_included or 'none'}; leaked excluded "
                 f"paths: {leaked or 'none'} (sibling worktrees wt/**, lane "
                 "evidence artifacts/** and local .opencode state must never be "
                 "product inputs; checked-in .opencode prompts must be)")
        # ISO-02: excluded churn cannot move the fingerprint
        (td / "wt" / "raider-sibling" / "modules" / "core" / "Fake.scala").write_text(
            "object Fake // sibling edited\n")
        (td / "wt" / "raider-sibling" / "NEW.scala").write_text("object New\n")
        (td / "artifacts" / "lanes" / "rai-999" / "extra.bin").write_text("x\n")
        if fp() != fp0:
            fail("self-check[RAI-256]: churn under wt/** and artifacts/** moved the "
                 "fingerprint — excluded paths leak into product inputs")
        # ISO-03: .opencode local install state stays excluded
        (td / ".opencode" / "node_modules" / "opencode" / "cli.js").write_text(
            "updated local install\n")
        (td / ".opencode" / "README.md").write_text("local note\n")
        if fp() != fp0:
            fail("self-check[RAI-256]: .opencode local install state moved the "
                 "fingerprint — only .opencode/agents/** and .opencode/commands/** "
                 "are product inputs")
        # ISO-04: included inputs DO move the fingerprint
        (td / ".opencode" / "agents" / "raider-worker.md").write_text(
            "worker prompt edited\n")
        fp_prompt = fp()
        if fp_prompt == fp0:
            fail("self-check[RAI-256]: editing a checked-in .opencode prompt did not "
                 "move the fingerprint — prompts are product inputs")
        (td / "modules" / "demo" / "Demo.scala").write_text("object Demo // changed\n")
        fp_prod = fp()
        if fp_prod == fp_prompt:
            fail("self-check[RAI-256]: editing a product source file did not move "
                 "the fingerprint")
        # ISO-05: concurrent sibling-worktree churn is read-safe and inert
        stop = threading.Event()
        churn_dir = td / "wt" / "churn-lane"

        def churn() -> None:
            i = 0
            while not stop.is_set():
                try:
                    churn_dir.mkdir(parents=True, exist_ok=True)
                    (churn_dir / f"f{i}.scala").write_text(f"object Churn{i}\n")
                    if i > 0:
                        (churn_dir / f"f{i - 1}.scala").unlink(missing_ok=True)
                except OSError:
                    pass  # the main thread's assertions judge outcomes, not the writer
                i += 1

        worker = threading.Thread(target=churn, daemon=True)
        worker.start()
        try:
            for _ in range(5):
                if fp() != fp_prod:
                    fail("self-check[RAI-256]: concurrent sibling-worktree churn "
                         "perturbed the inventory of included inputs")
        finally:
            stop.set()
            worker.join(timeout=10)
        print(f"[self-check] inventory isolation (RAI-256, composition v"
              f"{CURRENT_INVENTORY_VERSION}): {len(inv0)}-file fixture — product "
              f"file + checked-in .opencode prompts inventoried; wt/** "
              f"sibling worktree, artifacts/** lane evidence and .opencode local "
              f"state excluded; excluded churn (incl. concurrent) leaves the "
              f"fingerprint byte-identical ({fp_prod[:12]}…); prompt/product "
              f"edits shift it; concurrent churn walk never crashed")
    finally:
        shutil.rmtree(outer, ignore_errors=True)


def cmd_self_check(args) -> int:
    policy = load_policy()
    assert set(policy["gates"]) >= {g for p in policy["profiles"].values() for g in p["gates"]}
    _self_test_classify(policy)
    _self_test_git_fingerprint(policy)
    _self_test_manifest_fingerprint(policy)
    _self_test_inventory_isolation(policy)  # RAI-256: before the slow verify battery
    _self_test_select(policy)
    _self_test_run_id()
    _self_test_security(policy)
    _self_test_changelog_lint(policy)
    _self_test_vendor_audit(policy)
    _self_test_gate_timeouts(policy)
    _self_test_verify_semantics(policy)
    # verify-negative: tampered manifest must be rejected
    bad_dir = EVIDENCE_DIR / "self-negative"
    bad_dir.mkdir(parents=True, exist_ok=True)
    bad = {"schema_version": 1, "status": "passed", "profiles": ["static"],
           "source": {"files": {}, "fingerprint_sha256": "0" * 64},
           "policy_sha256": "0" * 64, "toolchain": {},
           "gates": [{"id": "x", "status": "failed", "exit_code": 1, "log": "x.log",
                      "log_sha256": "0" * 64}], "test_summary": {}}
    bad_path = bad_dir / "manifest.json"
    bad_path.write_text(json.dumps(bad))
    r = subprocess.run(["python3", "scripts/quality/quality.py", "verify",
                        str(bad_path), "--require", "static"],
                       cwd=ROOT, capture_output=True, text=True)
    if r.returncode == 0:
        fail("self-check: verify accepted a fabricated manifest (false green)")
    print("[self-check] registry ok; classify maps files->profiles "
          "(docs/scala/unmapped cases); git-mode fingerprint: clean temp tree empty, "
          "dirty temp tree kinds correct (rename=delete+add); manifest-mode "
          "fingerprint (temp tree, no git): unmodified -> empty, modify/delete/add/"
          "symlink-repoint -> exactly added/modified/deleted kinds; operational records "
          "excluded from inputs but present in audit; execute decision: "
          "docs-only -> ['docs'], unmapped file refuses; run_id: successive calls "
          "unique + filesystem-safe; verify rejects "
          "incoherent/tampered manifests (tamper-evidence: hashes/statuses/policy/"
          "fingerprint; execution provenance is bound to run-time logs, not claimed proof); "
          "security (RAI-069): profile resolves (component stage, gates exist) and "
          "security_checks.py --self-test proves secrets detection + masked output; "
          "changelog (RAI-281): CHANGELOG.md classifies to docs and the docs "
          "profile carries changelog-lint with pinned argv; changelog_lint.py "
          "--self-test proves every failure class detected with typed diagnostics; "
          "vendor (RAI-288): vendor/** and THIRD_PARTY_NOTICES.md classify to "
          "the vendor profile (corpus import no longer refuses unmapped) and "
          "the vendor profile carries vendor-audit with pinned argv; "
          "vendor_audit.py --self-test proves every failure class detected "
          "with typed diagnostics; "
          "gate budgets frozen (RAI-209): all gate timeout_s pinned, sbt-test=1800, "
          "over-budget probe classifies timed_out+failed (never skip/pass); "
          "verify semantics (RAI-255): zero-gate/incomplete/incoherent manifests "
          "rejected with named diagnostics, toolchain identity required and "
          "(strict) matched to this environment, complete/historical manifests "
          "accepted, concurrent verifies agree; inventory isolation (RAI-256): "
          "wt/** sibling worktrees, artifacts/** lane evidence and local .opencode "
          "state never enter the fingerprint, checked-in .opencode prompts do, "
          "excluded churn (incl. concurrent) is inert, pre-isolation manifests/"
          "base inventories are refused with migration diagnostics")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(prog="quality")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("changed"); p.add_argument("--base-manifest"); p.set_defaults(fn=cmd_changed)
    p = sub.add_parser("inventory"); p.add_argument("--out", required=True); p.set_defaults(fn=cmd_inventory)
    p = sub.add_parser("execute"); p.add_argument("--base-manifest")
    p.add_argument("--profiles"); p.set_defaults(fn=cmd_execute)
    p = sub.add_parser("verify"); p.add_argument("manifest")
    p.add_argument("--require"); p.add_argument("--allow-stale", action="store_true")
    p.set_defaults(fn=cmd_verify)
    p = sub.add_parser("self-check"); p.set_defaults(fn=cmd_self_check)
    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
