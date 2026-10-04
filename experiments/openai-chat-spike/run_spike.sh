#!/bin/sh
# SPIKE-OAI-01 canonical entrypoint (F0-remediated).
#
# Changes vs reviewed baseline (readiness 2026-10-03, P0/P1):
#   - producer exit codes propagate (run_logged; no tee swap, no stderr discard);
#   - fresh separate classes dir (artifacts/spike/classes-oai), not shared with
#     the RAI-001 spike classes;
#   - --self-test-fault-injection proves a failing producer fails the suite;
#   - evidence dir override RAIDER_OAI_EVIDENCE keeps the original artifacts.
#
# NOTE: the self-test mutates build outputs (classes dirs) like a real run;
# rerun the full suite afterwards before citing evidence.
#
# Usage: sh experiments/openai-chat-spike/run_spike.sh
#        sh experiments/openai-chat-spike/run_spike.sh --self-test-fault-injection
set -u
cd "$(dirname "$0")/../.."

PY=python3
SPIKE=experiments/openai-chat-spike
EVID="${RAIDER_OAI_EVIDENCE:-artifacts/oai-spike}"
OUT="$EVID"
mkdir -p "$OUT"

if [ "${1:-}" = "--self-test-fault-injection" ]; then
  fakebin="$(mktemp -d)"
  printf '#!/bin/sh\nexit 17\n' > "$fakebin/java"; chmod +x "$fakebin/java"
  printf '#!/bin/sh\nexit 17\n' > "$fakebin/python3"; chmod +x "$fakebin/python3"
  overall=0
  for mode in java python3 both; do
    rm -f "$fakebin/java" "$fakebin/python3"
    if [ "$mode" != python3 ]; then printf '#!/bin/sh\nexit 17\n' > "$fakebin/java"; chmod +x "$fakebin/java"; fi
    if [ "$mode" != java ]; then printf '#!/bin/sh\nexit 17\n' > "$fakebin/python3"; chmod +x "$fakebin/python3"; fi
    ev="$(mktemp -d)"
    if PATH="$fakebin:$PATH" RAIDER_OAI_EVIDENCE="$ev" \
       sh "$SPIKE/run_spike.sh" > "$ev/suite.log" 2>&1; then
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

if [ ! -f artifacts/tools/cp-repl.txt ] || [ ! -f artifacts/tools/cp-headless.txt ]; then
  echo "toolchain classpaths missing; run experiments/repl-spike/run_spike.sh first" >&2
  exit 21
fi

echo "== [1/3] compiling OpenAI-compatible spike client (fresh isolated classes) =="
rm -rf artifacts/spike/classes-oai
mkdir -p artifacts/spike/classes-oai
CP_ALL="$(cat artifacts/tools/cp-repl.txt):$(cat artifacts/tools/cp-headless.txt)"
run_logged compile java -cp "$CP_ALL" dotty.tools.dotc.Main -classpath "$CP_ALL" \
  -d artifacts/spike/classes-oai "$SPIKE/OpenAiCompatSpike.scala" || exit $?

# source manifest BEFORE harness run: evidence binds to these exact hashes (review N3)
run_logged source-manifest "$PY" - << 'PYSM' || exit $?
import hashlib, json, pathlib
sources = {}
for p in sorted(pathlib.Path("experiments/openai-chat-spike").rglob("*")):
    if p.is_file() and "pty-home" not in p.parts:
        sources[str(p)] = hashlib.sha256(p.read_bytes()).hexdigest()
print(json.dumps({"source_manifest": sources}, indent=2, sort_keys=True))
PYSM

echo "== [2/3] mock gateway harness (OAI-01..05,07..09) and REPL PTY (OAI-06) =="
run_logged harness "$PY" "$SPIKE/harness/oai_spike_harness.py" || exit $?

echo "OAI SPIKE SUITE COMPLETE"
