#!/usr/bin/env python3
"""Run current user examples against the real compiler/CLI and offline fixtures.

Standalone verification glue, not a replacement for the protected quality gates.
Every case logs actual argv, exits, expectations and input hashes in ignored output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
CASES = ("compiler", "lazy-map", "parallel-batch", "background", "session",
         "repl-review", "bundle", "coding", "audit", "artifacts")
REPL_FILES = ("01compiler.repl", "02lazy-map.repl", "03parallel-batch.repl",
              "04background-job.repl", "05session.repl")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=CASES)
    parser.add_argument("--live", action="store_true",
                        help="Use the explicitly configured live provider for coding, audit, or REPL review")
    parser.add_argument("--skip-build", action="store_true",
                        help="Use artifacts built by scripts/build/user-examples.sh")
    args = parser.parse_args()
    if not __debug__:
        parser.error("verification requires assertions enabled (no python -O/PYTHONOPTIMIZE)")
    if args.live and args.only not in (None, "coding", "audit", "repl-review"):
        parser.error("--live applies only to coding, audit, or repl-review")
    if args.live and os.environ.get("RAIDER_LIVE_APPROVED") != "1":
        parser.error("set RAIDER_LIVE_APPROVED=1 only after selecting a provider, endpoint, model, and spend budget")
    os.chdir(ROOT)
    output = ROOT / "artifacts/user-examples"
    output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="verification-", dir=output))
    selected = [args.only] if args.only else list(CASES)
    if "artifacts" in selected and "coding" not in selected:
        selected.insert(0, "coding")
    sources = [p for base in (ROOT / "examples/user-guide",)
               for p in base.rglob("*") if p.is_file()]
    sources += [Path(__file__), ROOT / "scripts/build/user-examples.sh"]
    sources += [ROOT / "README.md", ROOT / "docs/user-guide.md", ROOT / "docs/user-guide-repl.md",
                ROOT / "docs/selfdev-prompts.md"]
    report = {"schemaVersion": 1, "status": "running", "runDir": str(run),
              "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
              "inputs": {str(p.relative_to(ROOT)): digest(p) for p in sorted(sources)},
              "commands": [], "cases": []}
    report_path = run / "report.json"
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not java:
        raise RuntimeError("java not found")

    def save():
        report_path.write_text(json.dumps(report, indent=2) + "\n")

    def execute(label, argv, expected=0, data=None, env=None, timeout=120, cwd=ROOT):
        command_env = os.environ.copy()
        if env:
            command_env.update(env)
        stdout = run / f"{label}.stdout.txt"
        stderr = run / f"{label}.stderr.txt"
        with stdout.open("w") as out, stderr.open("w") as err:
            proc = subprocess.run(argv, input=data, text=True, stdout=out, stderr=err,
                                  env=command_env, timeout=timeout, cwd=cwd)
        entry = {"case": label, "argv": argv, "cwd": str(cwd), "exit": proc.returncode,
                 "expectedExit": expected, "stdout": str(stdout.relative_to(ROOT)),
                 "stderr": str(stderr.relative_to(ROOT)),
                 "stdoutSha256": digest(stdout), "stderrSha256": digest(stderr)}
        report["commands"].append(entry)
        save()
        if proc.returncode != expected:
            raise AssertionError(f"{label}: exit {proc.returncode}, expected {expected}; see {stderr}")
        return stdout.read_text(), stderr.read_text()

    server = None
    server_log = None
    coding_dir = None
    try:
        if not args.skip_build:
            execute("setup", ["sh", "scripts/build/user-examples.sh"], timeout=300)
        cp = (output / "classpath.txt").read_text().strip()
        if "\n" in cp or not cp:
            raise AssertionError("expected one nonempty classpath line")
        # Record compiled inputs too: --skip-build must not conceal what ran.
        compiled = {str(p.relative_to(ROOT)): digest(p)
                    for folder in (output / "bundle-classes", output / "stand-classes", output / "audit-stand-classes")
                    for p in folder.rglob("*") if p.is_file()}
        report["compiledInputs"] = compiled
        report["classpathSha256"] = digest(output / "classpath.txt")
        for name in selected:
            repl_indexes = {key: index for index, key in enumerate(CASES[:5])}
            if name in repl_indexes or name == "repl-review":
                index = 5 if name == "repl-review" else repl_indexes[name]
                script = ROOT / "examples/user-guide/repl" / ("06billing-review.repl" if name == "repl-review" else REPL_FILES[index])
                env = {"RAIDER_PROVIDER": "mock"}
                if name == "repl-review" and args.live:
                    provider = os.environ.get("RAIDER_PROVIDER", "openai")
                    base_url = os.environ.get("RAIDER_BASE_URL")
                    model = os.environ.get("RAIDER_MODEL")
                    if provider != "openai" or not base_url or not model:
                        raise RuntimeError("live repl-review requires RAIDER_PROVIDER=openai, RAIDER_BASE_URL, and RAIDER_MODEL")
                    from urllib.parse import urlsplit
                    parsed = urlsplit(base_url)
                    if parsed.username or parsed.password or parsed.query or parsed.fragment:
                        raise RuntimeError("live endpoint must not place credentials or tokens in its URL")
                    env.update({"RAIDER_PROVIDER": provider, "RAIDER_BASE_URL": base_url, "RAIDER_MODEL": model})
                    report["liveProvider"] = {"provider": provider, "endpoint": f"{parsed.scheme}://{parsed.netloc}{parsed.path}", "model": model}
                    report["liveMode"] = True
                repl_cwd = ROOT
                if name == "repl-review" and args.live:
                    repl_cwd = run / "repl-workspace"
                    shutil.copytree(ROOT / "examples/user-guide/fixture", repl_cwd)
                stdout, stderr = execute(name, [java, f"-Duser.home={run / name}",
                    "-cp", cp, "raider.repl.Main"], data=script.read_text(), env=env, timeout=600 if args.live else 120, cwd=repl_cwd)
                marker = "EXAMPLE-06 PASS" if name == "repl-review" else f"EXAMPLE-0{index + 1} PASS"
                assert re.search(rf"(?m)^(?:raider> )*{re.escape(marker)}(?: .*)?$", stdout), "missing printed assertion PASS marker"
                if name == "repl-review" and args.live:
                    assert "[scripted-fixture]" not in stdout + stderr, "live REPL fell back to a scripted fixture"
                    plain_stderr = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", stderr)
                    assert not re.search(r"(?m)^\s*(?:→\s|[✓✗]\s*←\s)", plain_stderr), "review made a tool request despite its tool-free task"
                assert "bye" in stdout, "REPL did not exit normally"
                assert not re.search(r"(?m)^.*(?:Not found:|--.*Error:|Syntax Error:|Type Mismatch Error:|AssertionError|command failed:)",
                                     stdout + stderr), "REPL compilation/assertion failed"
                if name == "lazy-map":
                    window = re.search(r"(?ms)^(?:raider> )*TYPE_QUERY_BEGIN\r?\n(.*?)^(?:raider> )*TYPE_QUERY_END(?:\r?\n|$)", stdout)
                    assert window is not None, "type-query output boundaries were not printed"
                    assert "AgentAsk[Int]" in window.group(1), "native type query did not return the descriptor type"
                    assert re.search(r"(?m)^(?:raider> )*Int\s*$", window.group(1)), "native type query did not return the effectful expression type"
                if name == "background":
                    assert re.search(r"j_\d+\s+Succeeded", stdout), "job did not succeed"
            elif name == "bundle":
                base = [java, "-cp", cp, "raider.cli.Main", "run", "--bundle",
                        str(output / "task-summary.jar"), "--workflow", "task-summary", "--mock"]
                stdout, _ = execute("bundle", base + ["--input", "tests=pass,migration=pass,rollback=pending", "--out", str(run / "summary")])
                result_dir = Path(json.loads(stdout.strip())["workDir"])
                result = json.loads((result_dir / "run.json").read_text())
                assert result["status"] == "Succeeded"
                assert '"decision":"BLOCKED"' in (result_dir / "summary.md").read_text()
                assert '"pending":["rollback"]' in (result_dir / "summary.md").read_text()
                ready_stdout, _ = execute("bundle-ready", base + ["--input", "tests=pass,migration=pass,rollback=pass", "--out", str(run / "ready")])
                ready_dir = Path(json.loads(ready_stdout.strip())["workDir"])
                assert '"decision":"READY"' in (ready_dir / "summary.md").read_text()
                assert '"pending":[]' in (ready_dir / "summary.md").read_text()
                execute("bundle-missing-required", base + ["--input", "tests=pass", "--out", str(run / "missing")], expected=21)
                execute("bundle-invalid", base + ["--input", "tests=pass,migration=unknown,rollback=pass", "--out", str(run / "invalid")], expected=21)
                execute("bundle-duplicate", base + ["--input", "tests=pass,tests=pending,migration=pass,rollback=pass", "--out", str(run / "duplicate")], expected=21)
            elif name == "coding":
                workspace = run / "workspace"
                shutil.copytree(ROOT / "examples/user-guide/fixture", workspace)
                original_fixture_hashes = {
                    str(p.relative_to(ROOT / "examples/user-guide/fixture")): digest(p)
                    for p in (ROOT / "examples/user-guide/fixture").rglob("*") if p.is_file()
                }
                if args.live:
                    provider = os.environ.get("RAIDER_PROVIDER", "openai")
                    base_url = os.environ.get("RAIDER_BASE_URL")
                    model = os.environ.get("RAIDER_MODEL")
                    if provider != "openai" or not base_url or not model:
                        raise RuntimeError("live mode needs RAIDER_PROVIDER=openai, RAIDER_BASE_URL, and RAIDER_MODEL")
                    from urllib.parse import urlsplit
                    parsed = urlsplit(base_url)
                    if parsed.username or parsed.password or parsed.query or parsed.fragment:
                        raise RuntimeError("live endpoint must not place credentials or tokens in its URL")
                    (workspace / "build/classes").mkdir(parents=True, exist_ok=True)
                    checker = workspace / ".example-checks/discount-check.sh"
                    checker.parent.mkdir(parents=True, exist_ok=True)
                    compile_argv = [java, "-cp", cp, "dotty.tools.dotc.Main", "-classpath", cp,
                                    "-Werror", "-d", "build/classes",
                                    "src/main/scala/billing/Discount.scala",
                                    "src/test/scala/billing/DiscountSpec.scala"]
                    test_argv = [java, "-cp", f"build/classes{os.pathsep}{cp}", "billing.DiscountSpec"]
                    checker.write_text("#!/bin/sh\nset -eu\n" +
                        " ".join(shlex.quote(part) for part in compile_argv) + "\n" +
                        " ".join(shlex.quote(part) for part in test_argv) + "\n")
                    checker_sha = digest(checker)
                    runbook = ("# Billing boundary task runbook\n\n"
                               "Use `proc_run` with cwd `.` and argv `[\"/bin/sh\", \".example-checks/discount-check.sh\"]`. "
                               "Run it before editing: compilation succeeds and the exact-threshold test exits 1. "
                               "Fix only `Discount.scala`, then run it again and require exit 0. Do not edit the test or checker.\n")
                    (workspace / "RUNBOOK.md").write_text(runbook)
                    report["liveRunbookSha256"] = digest(workspace / "RUNBOOK.md")
                    report["liveCheckerSha256"] = checker_sha
                    argv = [java, "-cp", cp, "raider.cli.Main", "run", "--agent", "coder",
                            "--provider", provider, "--base-url", base_url, "--model", model,
                            "--workspace", str(workspace), "--input",
                            "Read RUNBOOK.md. Fix the documented $10 billing discount boundary in Discount.scala. Use its exact compiler and test commands before editing, then make the smallest safe fix and run them again. Do not edit DiscountSpec.scala.",
                            "--out", str(run / "coding")]
                    stdout, _ = execute("coding-live", argv, timeout=600)
                    report["liveProvider"] = {"provider": provider, "endpoint": f"{parsed.scheme}://{parsed.netloc}{parsed.path}", "model": model}
                    report["liveMode"] = True
                else:
                    state = run / "stand"
                    server_log = (run / "stand.log").open("w")
                    server = subprocess.Popen([java, "-cp", cp + os.pathsep + str(output / "stand-classes"),
                        "demo.CodingStand", str(state), java, cp], stdout=server_log, stderr=server_log)
                    deadline = time.monotonic() + 15
                    while not (state / "base-url.txt").exists():
                        if server.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError("scripted stand did not become ready")
                        time.sleep(0.05)
                    url = (state / "base-url.txt").read_text().strip()
                    stdout, _ = execute("coding", [java, "-cp", cp, "raider.cli.Main", "run",
                        "--agent", "coder", "--provider", "openai", "--base-url", url,
                        "--model", "scripted-billing", "--workspace", str(workspace),
                        "--input", "Fix the billing discount boundary at exactly 100 dollars. Compile and run the boundary tests before and after the edit.",
                        "--out", str(run / "coding")], env={"RAIDER_API_KEY": "fixture-only"})
                coding_dir = Path(json.loads(stdout.strip())["workDir"])
                transcript = json.loads((coding_dir / "transcript.json").read_text())
                finished = [event["ToolCallFinished"] for event in transcript["events"] if "ToolCallFinished" in event]
                source = workspace / "src/main/scala/billing/Discount.scala"
                source_text = source.read_text()
                if args.live:
                    assert digest(source) != original_fixture_hashes["src/main/scala/billing/Discount.scala"], "live model made no source change"
                    test_source = workspace / "src/test/scala/billing/DiscountSpec.scala"
                    assert digest(test_source) == original_fixture_hashes["src/test/scala/billing/DiscountSpec.scala"], "live model changed the verifier test"
                    assert digest(workspace / ".example-checks/discount-check.sh") == checker_sha, "live model changed the pinned checker"
                    assert all(digest(ROOT / "examples/user-guide/fixture" / rel) == sha
                               for rel, sha in original_fixture_hashes.items()), "checked-in fixture changed during live run"
                    started_calls = {event["ToolCallStarted"]["callId"]: event["ToolCallStarted"]
                                     for event in transcript["events"] if "ToolCallStarted" in event}
                    live_process_checks = []
                    for index, event in enumerate(finished):
                        if event["tool"] == "proc_run":
                            started = started_calls.get(event["callId"])
                            assert started is not None and started["tool"] == "proc_run", "process finish lacks matching start"
                            arguments = json.loads(started["argumentsJson"])
                            if arguments.get("argv") != ["/bin/sh", ".example-checks/discount-check.sh"]:
                                continue
                            assert arguments.get("cwd", ".") == ".", "pinned checker ran in an unexpected cwd"
                            assert not arguments.get("env_allowlist") and not arguments.get("env_values"), "pinned checker environment was overridden"
                            try:
                                outcome = json.loads(event["resultJson"])["output"]
                            except (json.JSONDecodeError, KeyError, TypeError):
                                continue
                            live_process_checks.append((index, outcome))
                    edits = [index for index, event in enumerate(finished)
                             if event["tool"] in ("fs_edit", "fs_patch")]
                    assert edits, "live model did not edit the implementation"
                    assert any(index < edits[0] and item.get("exitCode") == 1
                               and "FAIL exact-threshold" in item.get("stdout", "")
                               for index, item in live_process_checks), "live run did not observe the baseline regression"
                    assert any(index > edits[-1] and item.get("exitCode") == 0
                               and "DiscountSpec: PASS 3 boundaries" in item.get("stdout", "")
                               for index, item in live_process_checks), "live run did not pass the pinned tests after its edit"
                    independent_classes = run / "live-independent-classes"
                    independent_classes.mkdir()
                    execute("coding-live-independent-compile", [java, "-cp", cp, "dotty.tools.dotc.Main",
                        "-classpath", cp, "-Werror", "-d", str(independent_classes),
                        str(source), str(test_source)], timeout=120)
                    execute("coding-live-independent-test", [java, "-cp",
                        f"{independent_classes}{os.pathsep}{cp}", "billing.DiscountSpec"], timeout=30)
                    assert "DiscountSpec: PASS 3 boundaries" in (run / "coding-live-independent-test.stdout.txt").read_text()
                else:
                    assert "s >= 10000" in source_text
                    assert "s > 10000" not in source_text
                    assert (ROOT / "examples/user-guide/fixture/src/main/scala/billing/Discount.scala").read_text().find("s > 10000") >= 0
                tools = [event["tool"] for event in finished]
                if not args.live:
                    assert tools == ["fs_read", "fs_search", "proc_run", "proc_run", "fs_edit", "proc_run", "proc_run"]
                tool_outputs = []
                for event in finished:
                    try:
                        tool_outputs.append(json.loads(event["resultJson"])["output"])
                    except (json.JSONDecodeError, KeyError, TypeError):
                        if not args.live:
                            raise AssertionError("scripted tool result was not valid JSON")
                assert all("error" not in item for item in tool_outputs), "tool returned a policy/error payload"
                if not args.live:
                    initial_sha = digest(ROOT / "examples/user-guide/fixture/src/main/scala/billing/Discount.scala")
                    assert tool_outputs[0]["sha256"] == initial_sha
                    assert any(match["file"].endswith("Discount.scala") and "s > 10000" in match["text"]
                               for match in tool_outputs[1]["matches"]), "search did not find the boundary defect"
                    assert tool_outputs[4]["old_sha256"] == initial_sha
                    assert tool_outputs[4]["new_sha256"] == digest(source)
                    checks = [item for item in tool_outputs if "exitCode" in item]
                    assert [check["exitCode"] for check in checks] == [0, 1, 0, 0]
                    assert "FAIL exact-threshold" in checks[1]["stdout"]
                    assert "DiscountSpec: PASS 3 boundaries" in checks[-1]["stdout"]
                    assert all(not check["timedOut"] for check in checks)
                assert all(event["success"] for event in finished)
                if not args.live:
                    assert "[scripted-billing]" in (coding_dir / "summary.md").read_text()
                report["codingWorkDir"] = str(coding_dir.relative_to(ROOT))
                report["fixtureAfterSha256"] = digest(source)
                print("  Scala compile → boundary test (expected fail) → SHA-256 guarded edit → compile → boundary tests pass", flush=True)
                print("  Independent pinned Scala compiler and three boundary assertions passed.", flush=True)
                print(f"  Run artifacts: {coding_dir.relative_to(ROOT)}", flush=True)
            elif name == "audit":
                # A full gallery reaches this case after coding. Close its
                # owned stand before replacing the server reference.
                if server is not None:
                    server.terminate()
                    try:
                        server.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait(timeout=5)
                    report["codingStandExit"] = server.returncode
                    server = None
                    if server_log is not None:
                        server_log.close()
                        server_log = None
                workspace = run / "audit-workspace"
                shutil.copytree(ROOT / "examples/user-guide/audit-fixture", workspace)
                if args.live:
                    provider = os.environ.get("RAIDER_PROVIDER", "openai")
                    base_url = os.environ.get("RAIDER_BASE_URL")
                    model = os.environ.get("RAIDER_MODEL")
                    if provider != "openai" or not base_url or not model:
                        raise RuntimeError("live mode needs RAIDER_PROVIDER=openai, RAIDER_BASE_URL, and RAIDER_MODEL")
                    from urllib.parse import urlsplit
                    parsed = urlsplit(base_url)
                    if parsed.username or parsed.password or parsed.query or parsed.fragment:
                        raise RuntimeError("live endpoint must not place credentials or tokens in its URL")
                    argv = [java, "-cp", cp, "raider.cli.Main", "run", "--agent", "coder",
                            "--provider", provider, "--base-url", base_url, "--model", model,
                            "--workspace", str(workspace), "--input",
                            ("Audit the payment config for plaintext transport fallback. Read and search the source, then create AUDIT.json. "
                            "Use this JSON contract: {\"schemaVersion\":1,\"status\":\"action_required\",\"findings\":[{\"id\":\"PAY-001\",\"severity\":\"high\",\"evidence\":{\"path\":\"relative/source/path\",\"line\":1,\"matched\":\"exact source text\"},\"impact\":\"explain the plaintext transport risk\",\"action\":\"specific configuration remediation\"}]}. "
                            "Derive the evidence path, line and text from the file; do not edit the configuration."),
                            "--out", str(run / "audit")]
                    stdout, _ = execute("audit-live", argv, timeout=600)
                    report["liveProvider"] = {"provider": provider, "endpoint": f"{parsed.scheme}://{parsed.netloc}{parsed.path}", "model": model}
                    report["liveMode"] = True
                else:
                    state = run / "audit-stand"
                    server_log = (run / "audit-stand.log").open("w")
                    server = subprocess.Popen([java, "-cp", cp + os.pathsep + str(output / "audit-stand-classes"),
                        "demo.AuditStand", str(state)], stdout=server_log, stderr=server_log)
                    deadline = time.monotonic() + 15
                    while not (state / "base-url.txt").exists():
                        if server.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError("scripted audit stand did not become ready")
                        time.sleep(0.05)
                    url = (state / "base-url.txt").read_text().strip()
                    stdout, _ = execute("audit", [java, "-cp", cp, "raider.cli.Main", "run",
                        "--agent", "coder", "--provider", "openai", "--base-url", url,
                        "--model", "scripted-audit", "--workspace", str(workspace),
                        "--input", ("Audit the payment config for plaintext transport fallback. Read and search the source, then create AUDIT.json. "
                            "Use this JSON contract: {\"schemaVersion\":1,\"status\":\"action_required\",\"findings\":[{\"id\":\"PAY-001\",\"severity\":\"high\",\"evidence\":{\"path\":\"relative/source/path\",\"line\":1,\"matched\":\"exact source text\"},\"impact\":\"explain the plaintext transport risk\",\"action\":\"specific configuration remediation\"}]}. "
                            "Derive the evidence path, line and text from the file; do not edit the configuration."),
                        "--out", str(run / "audit")], env={"RAIDER_API_KEY": "fixture-only"})
                audit_dir = Path(json.loads(stdout.strip())["workDir"])
                report_file = workspace / "AUDIT.json"
                audit = json.loads(report_file.read_text())
                assert audit["schemaVersion"] == 1 and audit["status"] == "action_required"
                assert isinstance(audit["findings"], list) and audit["findings"], "audit report has no findings"
                for finding in audit["findings"]:
                    assert finding.get("id") == "PAY-001", "audit finding lacks the documented stable ID"
                    severity = str(finding.get("severity", "")).lower()
                    assert severity in ("critical", "high", "medium", "low"), "finding lacks a recognized severity"
                    evidence = finding.get("evidence", {})
                    assert evidence.get("path") == "config/payments.conf" and evidence.get("line") == 2
                    assert "allow_plaintext_fallback = true" in evidence.get("matched", "")
                    assert any(term in finding.get("impact", "").lower()
                               for term in ("plaintext", "unencrypted", "cleartext")), "audit impact does not describe unencrypted transport"
                    action = finding.get("action", "").lower()
                    assert "allow_plaintext_fallback" in action and "false" in action
                assert (workspace / "config/payments.conf").read_bytes() == (ROOT / "examples/user-guide/audit-fixture/config/payments.conf").read_bytes()
                transcript = json.loads((audit_dir / "transcript.json").read_text())
                finished = [event["ToolCallFinished"] for event in transcript["events"] if "ToolCallFinished" in event]
                if args.live:
                    tools_by_name = {}
                    outputs_by_name = {}
                    for event in finished:
                        tools_by_name.setdefault(event["tool"], []).append(event)
                        try:
                            output = json.loads(event["resultJson"])["output"]
                        except (json.JSONDecodeError, KeyError, TypeError):
                            continue
                        outputs_by_name.setdefault(event["tool"], []).append(output)
                    assert "fs_read" in tools_by_name and "fs_search" in tools_by_name and "fs_edit" in tools_by_name
                    assert any(item.get("sha256") == digest(ROOT / "examples/user-guide/audit-fixture/config/payments.conf")
                               and "allow_plaintext_fallback = true" in item.get("content", "")
                               for item in outputs_by_name["fs_read"]), "audit read evidence does not match source"
                    assert any(any("allow_plaintext_fallback = true" in match.get("text", "")
                                   for match in item.get("matches", []))
                               for item in outputs_by_name["fs_search"]), "audit search did not find the source setting"
                    assert any(item.get("path") == "AUDIT.json" for item in outputs_by_name["fs_edit"]), "audit did not create its report"
                else:
                    assert [event["tool"] for event in finished] == ["fs_tree", "fs_read", "fs_search", "fs_edit"]
                    audit_results = [json.loads(event["resultJson"])["output"] for event in finished]
                    assert "payments.conf" in json.dumps(audit_results[0])
                    assert audit_results[1]["sha256"] == digest(ROOT / "examples/user-guide/audit-fixture/config/payments.conf")
                    assert any("allow_plaintext_fallback = true" in match["text"] for match in audit_results[2]["matches"])
                    assert audit_results[3]["action"] == "created"
                assert all(event["success"] for event in finished)
                if not args.live:
                    assert "[scripted-audit]" in (audit_dir / "summary.md").read_text()
                report["auditWorkDir"] = str(audit_dir.relative_to(ROOT))
                report["auditReportSha256"] = digest(report_file)
                print("  fs_tree → fs_read → fs_search → fs_edit(AUDIT.json); source unchanged", flush=True)
            elif name == "artifacts":
                assert coding_dir is not None
                files = ("run.json", "summary.md", "events.jsonl", "transcript.json", "lineage.scala", "lineage-data.json")
                for filename in files:
                    assert (coding_dir / filename).is_file(), f"missing {filename}"
                result = json.loads((coding_dir / "run.json").read_text())
                assert result["status"] == "Succeeded" and result["exitCode"] == 0
                events = [json.loads(line) for line in (coding_dir / "events.jsonl").read_text().splitlines()]
                assert events, "empty event stream"
                lineage = json.loads((coding_dir / "lineage-data.json").read_text())
                assert lineage, "empty lineage data"
                # Exported Scala projection must compile, not merely look like Scala.
                destination = run / "lineage-classes"
                destination.mkdir()
                execute("lineage-compile", [java, "-cp", cp, "dotty.tools.dotc.Main", "-classpath", cp,
                    "-d", str(destination), str(coding_dir / "lineage.scala")])
                report["artifacts"] = {filename: digest(coding_dir / filename) for filename in files}
            report["cases"].append({"id": name, "status": "passed"})
            save()
            print(f"{name}: PASS", flush=True)
        report["status"] = "passed"
        print(f"Evidence: {report_path.relative_to(ROOT)}", flush=True)
        return 0
    except (AssertionError, RuntimeError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        report["status"] = "failed"
        report["error"] = str(error)
        print(f"FAIL: {error}; evidence: {report_path}", file=sys.stderr)
        return 1
    finally:
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)
            report["standExit"] = server.returncode
        if server_log is not None:
            server_log.close()
        save()


if __name__ == "__main__":
    sys.exit(main())
