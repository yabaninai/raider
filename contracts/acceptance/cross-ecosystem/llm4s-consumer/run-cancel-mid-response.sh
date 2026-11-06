#!/usr/bin/env bash
# RAI-242: standalone cancel-mid-response corpus driver (llm4s consumer).
#
# Validates the two RAI-242 scenarios against the frozen golden
# expected/cancel-mid-response-golden.json with the independent checker:
#   cancel-mid-response : cancel AFTER the first provider response was fully
#                         consumed and its scripted proc_run executed (marker
#                         file exists), while the round-2 provider response is
#                         still awaited on a slowly-streaming stand
#                         (--delay-ms); typed REQUESTED cancel settle
#                         (cancelAck accepted, terminal cancelled/incomplete,
#                         cleanup complete, exit 26).
#   cancel-late         : cancel issued only AFTER the terminal
#                         (completed/success) was consumed; the settle stays
#                         completed (exit 0), never cancelled (26).
#
# Run-all wiring is DEFERRED to RAI-238: it is not purely additive (it would
# require editing scenarios/run-all.sh and the run_checker manifest list in
# scenarios/lib.sh, and the frozen expected/golden-checks.json would demand
# the new scenarios in every corpus run). This driver is self-contained and
# reuses scenarios/lib.sh read-only.
#
# Stages:
#   1. compile stand + consumers + checker (lib.sh compile_all);
#   2. fresh isolated OUT/WS (artifacts/xe-out-cmr, artifacts/xe-ws-cmr);
#   3. fast stand: run the frozen tool-task scenario once so the checker's
#      workspace-marker section has genuinely produced evidence (never
#      fabricated);
#   4. slow stand (--delay-ms 4000): run cancel-mid-response + cancel-late;
#   5. stop the stands (honest RAI-229 stop policy);
#   6. checker against the RAI-242 golden.
#
# Usage: from the worktree root (ROOT is derived from the script location).
# Exit 0 only when every consumer scenario and the checker pass.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT="${ROOT:-$(cd "$HERE/../../../.." && pwd)}"
export OUT="${OUT:-$ROOT/artifacts/xe-out-cmr}"
export WS="${WS:-$ROOT/artifacts/xe-ws-cmr}"
# shellcheck source=../scenarios/lib.sh
source "$ROOT/contracts/acceptance/cross-ecosystem/scenarios/lib.sh"

mkdir -p "$OUT" "$WS"
rm -rf "$OUT"/* "$WS"/*

compile_all

# The stands' scripted tool rounds touch markers under these dirs (proc_run),
# so the parent dirs must exist before the consumers start. No --content-file
# is passed: the stand's built-in default content is used.
mkdir -p "$WS/tool-task" "$WS/cancel-mid-response"

# Fast stand: one frozen tool-task run produces the real workspace marker the
# checker's workspace section asserts (the RAI-242 golden does not cover
# tool-task, so its manifest is deliberately NOT passed to the checker).
PORT_FAST=$(start_stand fast --marker "$WS/tool-task/marker.txt")
run_llm4s "$OUT/manifest-tool-task-setup.json" "tool-task" "$PORT_FAST"

# Slowly-streaming stand: every provider request is delayed, so the round-2
# response of a coder run is still in flight when the client, having observed
# the round-1 tool execution client-side, issues the cancel.
PORT_SLOW=$(start_stand slow --delay-ms 4000 --marker "$WS/cancel-mid-response/marker.txt")
run_llm4s "$OUT/manifest-cancel-scenarios.json" "cancel-mid-response,cancel-late" "$PORT_SLOW"

if ! stop_stands; then
  log "FATAL: stands did not all terminate; refusing to report success"
  exit 1
fi

run_checker_rai242() { # <report path>
  java -cp "$XE/classes-checker:$SCALA_LIB:$UPICKLE_CP" xe.checker.IndependentChecker \
    --goldens "$FIX/expected/cancel-mid-response-golden.json" \
    --manifests "$OUT/manifest-cancel-scenarios.json" \
    --adapter-jar "$JAR" \
    --out-root "$OUT" --ws-root "$WS" \
    --stand-logs "$OUT/stand-fast.log,$OUT/stand-slow.log" \
    --report "$1"
}

run_checker_rai242 "$OUT/checker-cancel-mid-response-report.json"
