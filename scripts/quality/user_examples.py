#!/usr/bin/env python3
"""Execute the published user examples with real compiler/CLI and offline fixtures.

Standalone verification glue, not a replacement for the protected quality gates.
Every case logs actual argv, exits, expectations and input hashes in ignored output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
CASES = ("compiler", "lazy-map", "parallel-batch", "background", "session",
         "bundle", "coding", "artifacts")
REPL_FILES = ("01compiler.repl", "02lazy-map.repl", "03parallel-batch.repl",
              "04background-job.repl", "05session.repl")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=CASES)
    parser.add_argument("--skip-build", action="store_true",
                        help="Use artifacts built by scripts/build/user-examples.sh")
    args = parser.parse_args()
    if not __debug__:
        parser.error("verification requires assertions enabled (no python -O/PYTHONOPTIMIZE)")
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
    sources += [ROOT / "README.md", ROOT / "docs/user-guide.md", ROOT / "docs/user-guide-repl.md"]
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

    def execute(label, argv, expected=0, data=None, env=None, timeout=120):
        command_env = os.environ.copy()
        if env:
            command_env.update(env)
        stdout = run / f"{label}.stdout.txt"
        stderr = run / f"{label}.stderr.txt"
        with stdout.open("w") as out, stderr.open("w") as err:
            proc = subprocess.run(argv, input=data, text=True, stdout=out, stderr=err,
                                  env=command_env, timeout=timeout)
        entry = {"case": label, "argv": argv, "exit": proc.returncode,
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
                    for folder in (output / "bundle-classes", output / "stand-classes")
                    for p in folder.rglob("*") if p.is_file()}
        report["compiledInputs"] = compiled
        report["classpathSha256"] = digest(output / "classpath.txt")
        for name in selected:
            if name in CASES[:5]:
                index = CASES.index(name)
                script = ROOT / "examples/user-guide/repl" / REPL_FILES[index]
                stdout, stderr = execute(name, [java, f"-Duser.home={run / name}",
                    "-cp", cp, "raider.repl.Main"], data=script.read_text(),
                    env={"RAIDER_PROVIDER": "mock"})
                assert re.search(rf"(?m)^(?:raider> )*EXAMPLE-0{index + 1} PASS(?: .*)?$", stdout), "missing printed assertion PASS marker"
                assert "bye" in stdout, "REPL did not exit normally"
                assert not re.search(r"(?m)^.*(?:Not found:|--.*Error:|Syntax Error:|Type Mismatch Error:|AssertionError|command failed:)",
                                     stdout + stderr), "REPL compilation/assertion failed"
                if name == "background":
                    assert re.search(r"j_\d+\s+Succeeded", stdout), "job did not succeed"
            elif name == "bundle":
                base = [java, "-cp", cp, "raider.cli.Main", "run", "--bundle",
                        str(output / "task-summary.jar"), "--workflow", "task-summary", "--mock"]
                stdout, _ = execute("bundle", base + ["--input", "fix bug, add test", "--out", str(run / "summary")])
                result_dir = Path(json.loads(stdout.strip())["workDir"])
                result = json.loads((result_dir / "run.json").read_text())
                assert result["status"] == "Succeeded"
                assert "2 tasks: fix bug | add test" in (result_dir / "summary.md").read_text()
                execute("bundle-empty", base + ["--input", "", "--out", str(run / "empty")], expected=21)
            elif name == "coding":
                workspace = run / "workspace"
                shutil.copytree(ROOT / "examples/user-guide/fixture", workspace)
                state = run / "stand"
                server_log = (run / "stand.log").open("w")
                server = subprocess.Popen([java, "-cp", cp + os.pathsep + str(output / "stand-classes"),
                    "demo.CodingStand", str(state)], stdout=server_log, stderr=server_log)
                deadline = time.monotonic() + 15
                while not (state / "base-url.txt").exists():
                    if server.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("scripted stand did not become ready")
                    time.sleep(0.05)
                url = (state / "base-url.txt").read_text().strip()
                stdout, _ = execute("coding", [java, "-cp", cp, "raider.cli.Main", "run",
                    "--agent", "coder", "--provider", "openai", "--base-url", url,
                    "--model", "scripted-docs", "--workspace", str(workspace),
                    "--input", "Fix the greeting typo and verify it against expected.txt.",
                    "--out", str(run / "coding")], env={"RAIDER_API_KEY": "fixture-only"})
                coding_dir = Path(json.loads(stdout.strip())["workDir"])
                assert (workspace / "greeting.txt").read_bytes() == (workspace / "expected.txt").read_bytes()
                assert (ROOT / "examples/user-guide/fixture/greeting.txt").read_text() == "Hello Raiedr!\n"
                transcript = json.loads((coding_dir / "transcript.json").read_text())
                finished = [event["ToolCallFinished"] for event in transcript["events"] if "ToolCallFinished" in event]
                assert [event["tool"] for event in finished] == ["fs_read", "fs_search", "proc_run", "fs_edit", "proc_run"]
                tool_outputs = [json.loads(event["resultJson"])["output"] for event in finished]
                assert all("error" not in item for item in tool_outputs), "tool returned a policy/error payload"
                initial_sha = digest(ROOT / "examples/user-guide/fixture/greeting.txt")
                assert tool_outputs[0]["sha256"] == initial_sha
                assert any(match["file"] == "greeting.txt" and "Raiedr" in match["text"]
                           for match in tool_outputs[1]["matches"]), "search did not find the actual typo"
                assert tool_outputs[3]["old_sha256"] == initial_sha
                assert tool_outputs[3]["new_sha256"] == digest(workspace / "greeting.txt")
                checks = [json.loads(event["resultJson"])["output"] for event in finished if event["tool"] == "proc_run"]
                assert [check["exitCode"] for check in checks] == [1, 0]
                assert all(not check["timedOut"] for check in checks)
                assert all(event["success"] for event in finished)
                assert "[scripted-docs]" in (coding_dir / "summary.md").read_text()
                report["codingWorkDir"] = str(coding_dir.relative_to(ROOT))
                report["fixtureAfterSha256"] = digest(workspace / "greeting.txt")
                print("  fs_read → fs_search → proc_run(exit 1) → fs_edit → proc_run(exit 0)", flush=True)
                print(f"  Actual final file: {(workspace / 'greeting.txt').read_text().strip()}", flush=True)
                print(f"  Run artifacts: {coding_dir.relative_to(ROOT)}", flush=True)
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
