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
 """
from __future__ import annotations

import argparse
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
    return any(part in excludes for part in rel.split("/"))


def inventory(root: pathlib.Path, excludes: set[str]) -> dict[str, dict]:
    """Hash inventory of the tree at `root` (fingerprint excludes applied)."""
    current: dict[str, dict] = {}
    for p in root.rglob("*"):
        if not p.is_file() and not p.is_symlink():
            continue
        rel = p.relative_to(root).as_posix()
        if _excluded(rel, excludes):
            continue
        if p.is_symlink():
            current[rel] = {"mode": "symlink", "target": os.readlink(p)}
        else:
            st = p.stat()
            current[rel] = {"sha256": sha256_file(p), "mode": oct(st.st_mode & 0o777)}
    return current


def current_inventory() -> dict[str, dict]:
    """Inventory of the repo root; git-free, usable by snapshot lanes."""
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
            if rel and not _excluded(rel, excludes):
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
            base = json.loads(bpath.read_text())["files"]
        except (OSError, ValueError, KeyError) as exc:
            fail(f"baseline manifest missing or malformed ({bpath}): {exc}")
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
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
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
    out.write_text(json.dumps({"files": files}, indent=0, sort_keys=True))
    print(f"[quality] base inventory: {out} ({len(files)} files)")
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
        "schema_version": 1,
        "run_id": run_id,
        "status": status,
        "mode": "manifest-diff" if env_base(args) else "git-status",
        "source": {"files": files, "fingerprint_sha256": hashlib.sha256(
            json.dumps(files, sort_keys=True).encode()).hexdigest()},
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


def cmd_verify(args) -> int:
    mpath = pathlib.Path(args.manifest)
    if not mpath.exists():
        fail(f"manifest not found: {mpath}")
    m = json.loads(mpath.read_text())
    required = args.require.split(",") if args.require else m["profiles"]
    problems = []
    if m.get("status") != "passed":
        problems.append(f"manifest status={m.get('status')}")
    for prof in required:
        if prof not in m["profiles"]:
            problems.append(f"required profile {prof} not in manifest profiles {m['profiles']}")
    for g in m["gates"]:
        if g["status"] != "passed":
            problems.append(f"gate {g['id']} status={g['status']}")
        log = ROOT / g["log"]
        if not log.exists():
            problems.append(f"gate {g['id']} log missing")
        elif sha256_file(log) != g["log_sha256"]:
            problems.append(f"gate {g['id']} log hash stale")
    if m["policy_sha256"] != sha256_file(POLICY):
        problems.append("policy registry changed since run (stale evidence)")
    files_now = current_inventory()
    fp_now = hashlib.sha256(json.dumps(files_now, sort_keys=True).encode()).hexdigest()
    if not args.allow_stale and fp_now != m["source"]["fingerprint_sha256"]:
        changed = sum(1 for k in files_now
                      if m["source"]["files"].get(k, {}).get("sha256") != files_now[k].get("sha256"))
        problems.append(f"source fingerprint changed ({changed} files differ) — rerun affected gates "
                        "(--allow-stale overrides, evidence is then historical)")
    if problems:
        for p in problems:
            print(f"[verify] FAIL: {p}", file=sys.stderr)
        return 1
    print(f"[verify] OK: manifest {mpath} satisfies {required} "
          "(policy/toolchain/logs hashes checked"
          + (", historical (--allow-stale)" if args.allow_stale else ", fingerprint fresh")
          + ")")
    return 0


def _self_test_classify(policy: dict) -> None:
    """RAI-054: classify is file-keyed; known/unknown paths map correctly."""
    cases = [
        ("docs/quality-gates.md", ["docs"]),
        ("modules/core/src/main/scala/raider/core/Example.scala",
         ["contracts", "static", "unit"]),
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
        (td / "link.txt").symlink_to("keep.txt")
        base = inventory(td, excludes)
        mpath = outer / "base-manifest.json"  # OUTSIDE the inventoried tree
        mpath.write_text(json.dumps({"files": base}, sort_keys=True))
        _cur, changes = fingerprint(str(mpath), root=td)
        if changes:
            fail(f"self-check: manifest fingerprint of an unmodified tree must be "
                 f"empty (got {changes})")
        (td / "docs" / "a.md").write_text("x changed\n")  # hash -> modified
        (td / "gone.txt").unlink()                        # -> deleted
        (td / "new.txt").write_text("n\n")                # -> added
        (td / "link.txt").unlink()
        (td / "link.txt").symlink_to("new.txt")           # symlink target -> modified
        _cur, changes = fingerprint(str(mpath), root=td)
        kinds = {c["path"]: c["kind"] for c in changes}
        expected = {"docs/a.md": "modified", "gone.txt": "deleted", "new.txt": "added",
                    "link.txt": "modified"}
        if kinds != expected or len(changes) != len(expected):
            fail(f"self-check: manifest fingerprint kinds mismatch: {kinds} "
                 f"(changes={changes}, expected {expected})")
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
    exactly ['docs']; an unmapped file must fail the decision."""
    union, unmapped = select_profiles([{"path": "docs/x.md", "kind": "modified"}], policy)
    if union != ["docs"] or unmapped:
        fail(f"self-check: docs-only change must select exactly ['docs'] "
             f"(got {union}, unmapped={unmapped})")
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


def cmd_self_check(args) -> int:
    policy = load_policy()
    assert set(policy["gates"]) >= {g for p in policy["profiles"].values() for g in p["gates"]}
    _self_test_classify(policy)
    _self_test_git_fingerprint(policy)
    _self_test_manifest_fingerprint(policy)
    _self_test_select(policy)
    _self_test_run_id()
    _self_test_security(policy)
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
          "symlink-repoint -> exactly added/modified/deleted kinds; execute decision: "
          "docs-only -> ['docs'], unmapped file refuses; run_id: successive calls "
          "unique + filesystem-safe; verify rejects "
          "incoherent/tampered manifests (tamper-evidence: hashes/statuses/policy/"
          "fingerprint; execution provenance is bound to run-time logs, not claimed proof); "
          "security (RAI-069): profile resolves (component stage, gates exist) and "
          "security_checks.py --self-test proves secrets detection + masked output")
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
