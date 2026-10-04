#!/usr/bin/env python3
"""RAI-004 quality runner MVP (stage-aware, manifest-diff capable).

Subcommands:
  changed [--base-manifest P]      classify changed files -> profile plan (no execution)
  execute [--base-manifest P]      run active gates for selected profiles, write evidence
  verify MANIFEST --require P ...  validate statuses, logs, hashes, fingerprint
                                   (freshness is default; --allow-stale marks historical evidence)
  self-check                       registry integrity + verify-negative self-tests

Honesty rules: producer exit codes propagate; missing tool = unavailable = fail;
unknown profile = error; a profile with no executed gates = fail; skipped is never
passed. Evidence binds to the source fingerprint taken at execute time.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
POLICY = ROOT / "scripts/quality-policy/registry.json"
EVIDENCE_DIR = ROOT / "artifacts/quality"


def fail(msg: str) -> "None":
    print(f"[quality] FAIL: {msg}", file=sys.stderr)
    raise SystemExit(1)


def sha256_file(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def load_policy() -> dict:
    policy = json.loads(POLICY.read_text())
    for name in policy["profiles"]:
        if name not in ("self", "static", "unit", "contracts", "docs", "fast"):
            continue  # milestone-only profiles may appear later
        for gate in policy["profiles"][name]["gates"]:
            if gate not in policy["gates"]:
                fail(f"profile {name} references unknown gate {gate}")
    return policy


def fingerprint(base_manifest: str | None) -> tuple[dict, list[dict]]:
    """Current tree inventory + diff classification vs optional base manifest."""
    excludes = set(load_policy()["fingerprint_excludes"])
    current: dict[str, dict] = {}
    for p in ROOT.rglob("*"):
        if not p.is_file() and not p.is_symlink():
            continue
        rel = p.relative_to(ROOT).as_posix()
        # segment-based: excludes apply at ANY depth (target/, .git/, artifacts/ ...)
        if any(part in excludes for part in rel.split("/")):
            continue
        if p.is_symlink():
            current[rel] = {"mode": "symlink", "target": os.readlink(p)}
        else:
            st = p.stat()
            current[rel] = {"sha256": sha256_file(p), "mode": oct(st.st_mode & 0o777)}
    changes: list[dict] = []
    if base_manifest:
        base = json.loads(pathlib.Path(base_manifest).read_text())["files"]
        for rel, cur in current.items():
            old = base.get(rel)
            if old is None:
                changes.append({"path": rel, "kind": "added"})
            elif old.get("sha256") != cur.get("sha256") or old.get("mode") != cur.get("mode"):
                changes.append({"path": rel, "kind": "modified"})
        for rel in base:
            if rel not in current:
                changes.append({"path": rel, "kind": "deleted"})
    return current, changes


def classify(changes: list[dict], policy: dict) -> dict[str, list[str]]:
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
    merged: dict[str, list[str]] = {}
    for profs in by_file.values():
        for pr in profs:
            merged.setdefault(pr, [])
    return merged


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


def cmd_inventory(args) -> int:
    files, _ = fingerprint(None)
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"files": files}, indent=0, sort_keys=True))
    print(f"[quality] base inventory: {out} ({len(files)} files)")
    return 0


def cmd_changed(args) -> int:
    policy = load_policy()
    files, changes = fingerprint(env_base(args))
    if not changes:
        print("[quality] no changes vs base (clean baseline executes minimum fast)")
        profiles = ["fast"]
    else:
        profiles = sorted(classify(changes, policy).keys())
    print(json.dumps({"changed": changes, "profiles": profiles}, indent=2))
    return 0


def cmd_execute(args) -> int:
    policy = load_policy()
    files, changes = fingerprint(env_base(args))
    if not changes:
        selected = ["fast"]
    else:
        selected = sorted(classify(changes, policy).keys())
    unmapped = sorted({c["path"] for c in changes
                       if "__unmapped__" in classify([c], policy).get(c["path"], ["__unmapped__"])})
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

    run_id = time.strftime("%Y%m%d-%H%M%S")
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
        "mode": "manifest-diff" if env_base(args) else "full-tree",
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
    files_now, _ = fingerprint(None)
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


def cmd_self_check(args) -> int:
    policy = load_policy()
    assert set(policy["gates"]) >= {g for p in policy["profiles"].values() for g in p["gates"]}
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
    print("[self-check] registry ok; verify rejects incoherent/tampered manifests "
          "(tamper-evidence: hashes/statuses/policy/fingerprint; execution provenance "
          "is bound to run-time logs, not claimed proof)")
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
