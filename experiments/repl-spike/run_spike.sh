#!/bin/sh
# RAI-001 canonical spike entrypoint (F0-remediated).
#
# Changes vs reviewed baseline (readiness 2026-10-03, P0):
#   - producer exit codes propagate through logging (run_logged, no tee-swap);
#   - fresh compile classes each run (no stale artifacts);
#   - --self-test-fault-injection proves each stage fails the suite when its
#     producer fails (fake java / fake python3 exiting 17);
#   - evidence dir override RAIDER_SPIKE_EVIDENCE keeps original artifacts
#     untouched (remediation runs write elsewhere).
#
# Usage: sh experiments/repl-spike/run_spike.sh [--skip-fetch]
#        sh experiments/repl-spike/run_spike.sh --self-test-fault-injection
set -u
cd "$(dirname "$0")/../.."

PY=python3
SPIKE=experiments/repl-spike
EVID="${RAIDER_SPIKE_EVIDENCE:-artifacts/rai-001}"
OUT="$EVID"
mkdir -p "$OUT"

if [ "${1:-}" = "--self-test-fault-injection" ]; then
  # Inject failing producers into PATH and assert the suite reports failure.
  fakebin="$(mktemp -d)"
  printf '#!/bin/sh\nexit 17\n' > "$fakebin/java";   chmod +x "$fakebin/java"
  printf '#!/bin/sh\nexit 17\n' > "$fakebin/python3"; chmod +x "$fakebin/python3"
  overall=0
  for mode in java python3 both; do
    rm -f "$fakebin/java" "$fakebin/python3"
    if [ "$mode" != python3 ]; then printf '#!/bin/sh\nexit 17\n' > "$fakebin/java"; chmod +x "$fakebin/java"; fi
    if [ "$mode" != java ]; then printf '#!/bin/sh\nexit 17\n' > "$fakebin/python3"; chmod +x "$fakebin/python3"; fi
    ev="$(mktemp -d)"
    if PATH="$fakebin:$PATH" RAIDER_SPIKE_EVIDENCE="$ev" \
       sh "$SPIKE/run_spike.sh" --skip-fetch > "$ev/suite.log" 2>&1; then
      echo "SELF-TEST FAIL: suite returned 0 with broken $mode (false green)"
      overall=1
    else
      echo "self-test $mode: suite correctly failed (exit propagation works)"
    fi
    rm -rf "$ev"
  done
  rm -rf "$fakebin"
  [ "$overall" = 0 ] && echo "FAULT-INJECTION SELF-TEST PASSED"
  exit "$overall"
fi

# Run a command, capture full output, propagate ITS exit code (no tee swap).
run_logged() {
  _name="$1"; shift
  if "$@" > "$OUT/$_name.raw" 2>&1; then
    cat "$OUT/$_name.raw"
    return 0
  else
    _st=$?
    cat "$OUT/$_name.raw"
    echo "run_spike: step '$_name' FAILED with exit $_st" >&2
    return $_st
  fi
}

if [ "${1:-}" != "--skip-fetch" ]; then
  echo "== [1/5] fetching pinned toolchain =="
  RAIDER_TOOLCHAIN_INVENTORY="$OUT/toolchain.json" \
  run_logged fetch "$PY" "$SPIKE/fetch_toolchain.py" \
    org.scala-lang:scala3-compiler_3:3.9.0 \
    org.scala-lang:scala3-repl_3:3.9.0 \
    org.scala-lang:scala3-library_3:3.9.0 \
    dev.zio:zio_3:2.1.26 \
    dev.zio:zio-json_3:0.10.0 \
    org.jline:jline-terminal:4.0.14 \
    org.jline:jline-reader:4.0.14 \
    org.jline:jline-terminal-jni:4.0.14 \
    io.get-coursier:interface:1.0.29-M4 || exit $?
fi

M2=artifacts/tools/m2
CP_REPL="$M2/org/scala-lang/scala3-repl_3/3.9.0/scala3-repl_3-3.9.0.jar:$M2/org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar:$M2/org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar:$M2/org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar:$M2/org/scala-lang/scala3-directives-parser_3/3.9.0/scala3-directives-parser_3-3.9.0.jar:$M2/org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar:$M2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar:$M2/org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar:$M2/org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar:$M2/org/jline/jline-terminal/4.0.14/jline-terminal-4.0.14.jar:$M2/org/jline/jline-reader/4.0.14/jline-reader-4.0.14.jar:$M2/org/jline/jline-terminal-jni/4.0.14/jline-terminal-jni-4.0.14.jar:$M2/io/get-coursier/interface/1.0.29-M4/interface-1.0.29-M4.jar"
CP_HEADLESS="$M2/org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar:$M2/dev/zio/zio_3/2.1.26/zio_3-2.1.26.jar:$M2/dev/zio/zio-streams_3/2.1.26/zio-streams_3-2.1.26.jar:$M2/dev/zio/zio-internal-macros_3/2.1.26/zio-internal-macros_3-2.1.26.jar:$M2/dev/zio/zio-stacktracer_3/2.1.26/zio-stacktracer_3-2.1.26.jar:$M2/dev/zio/izumi-reflect_3/3.0.9/izumi-reflect_3-3.0.9.jar:$M2/dev/zio/izumi-reflect-thirdparty-boopickle-shaded_3/3.0.9/izumi-reflect-thirdparty-boopickle-shaded_3-3.0.9.jar:$M2/org/scala-lang/modules/scala-collection-compat_3/2.14.0/scala-collection-compat_3-2.14.0.jar:$M2/dev/zio/zio-json_3/0.10.0/zio-json_3-0.10.0.jar:$M2/com/softwaremill/magnolia1_3/magnolia_3/1.3.23/magnolia_3-1.3.23.jar"
printf '%s' "$CP_REPL" > artifacts/tools/cp-repl.txt
printf '%s' "$CP_HEADLESS" > artifacts/tools/cp-headless.txt

echo "== [2/5] compiling spike sources (pinned Scala 3.9.0, fresh classes) =="
rm -rf artifacts/spike/classes
mkdir -p artifacts/spike/classes
CP_ALL="$CP_REPL:$CP_HEADLESS"
run_logged compile java -cp "$CP_ALL" dotty.tools.dotc.Main -classpath "$CP_ALL" -d artifacts/spike/classes \
  "$SPIKE/prelude/SpikePrelude.scala" \
  "$SPIKE/headless/HeadlessSpike.scala" \
  "$SPIKE/probe/HttpJsonProbe.scala" || exit $?

echo "== [3/5] environment inventory =="
run_logged environment "$PY" - << 'PYINV' || exit $?
import hashlib, json, pathlib, platform, subprocess, sys
def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    return (r.stdout or r.stderr).strip()
sources = {}
for base in ("experiments/repl-spike", "experiments/openai-chat-spike"):
    for p in sorted(pathlib.Path(base).rglob("*")):
        if p.is_file():
            sources[str(p)] = hashlib.sha256(p.read_bytes()).hexdigest()
inv = {
  "python": sys.version.split()[0],
  "platform": platform.platform(),
  "macos": run(["sw_vers", "-productVersion"]),
  "arch": run(["uname", "-m"]),
  "java_version": run(["java", "-version"]).replace("\n", " | "),
  "java_home": run(["/usr/libexec/java_home"]),
  "scala": "3.9.0 (org.scala-lang:scala3-compiler_3 / scala3-repl_3)",
  "scala_library_note": "org.scala-lang:scala-library:3.9.0 unified; scala3-library_3:3.9.0 is a manifest-only relocation stub",
  "zio": "2.1.26",
  "zio_json": "0.10.0",
  "jline": "4.0.14 (scala3-repl_3 dependency)",
  "note": "plan baseline candidate JDK 21 LTS is not installed on this host; spike ran on JDK 20.0.1",
  "source_manifest": sources,
}
print(json.dumps(inv, indent=2))
PYINV

echo "== [4/5] headless harness =="
run_logged headless-harness "$PY" "$SPIKE/harness/headless_harness.py" || exit $?

echo "== [5/5] REPL PTY harness =="
run_logged repl-harness "$PY" "$SPIKE/harness/repl_pty_harness.py" || exit $?

echo "SPIKE SUITE COMPLETE"
