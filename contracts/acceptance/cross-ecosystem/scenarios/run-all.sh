#!/usr/bin/env bash
# RAI-205 cross-ecosystem acceptance: the FULL consumer matrix, offline.
#
# Stages:
#   1. compile stand + both ecosystem consumers + the independent checker;
#   2. start the scripted stands (A fast, B delayed 3000 ms);
#   3. run both consumers: mock scenarios, stand A scenarios, stand B scenarios;
#   4. stop the stands;
#   5. validate everything against the committed goldens with the checker.
#
# Evidence: $OUT/manifest-*.json, invocation artifacts, stand logs (redacted),
# checker-report.json. Exit 0 only when every consumer run and the checker pass.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

mkdir -p "$OUT" "$WS"
rm -rf "$OUT"/* "$WS"/*

compile_all

# Workspace dirs the consumers expect (fresh per official run). The stand A
# content file must exist BEFORE the stand starts (it reads it at startup).
mkdir -p "$WS/tool-task" "$WS/cancel-mid-run"
printf '{"total": 128}' > "$WS/.stand-content.json"

PORT_A=$(start_stand A --content-file "$WS/.stand-content.json" --marker "$WS/tool-task/marker.txt")
PORT_B=$(start_stand B --delay-ms 3000 --marker "$WS/cancel-mid-run/marker.txt")

run_llm4s "$OUT/manifest-llm4s.json" "handshake-terminal,refusals,observe-dedup"
run_l4j "$OUT/manifest-l4j-mock.json" "handshake-terminal,refusals,observe-dedup"
run_llm4s "$OUT/manifest-llm4s-standA.json" "tool-task,extraction-task" "$PORT_A"
run_l4j "$OUT/manifest-l4j-standA.json" "tool-task,extraction-task" "$PORT_A"
run_llm4s "$OUT/manifest-llm4s-standB.json" "cancel-mid-run,eof-uncertain" "$PORT_B"
run_l4j "$OUT/manifest-l4j-standB.json" "cancel-mid-run,eof-uncertain" "$PORT_B"

# RAI-229: a stop failure (a stand that survived SIGTERM+SIGKILL, or an
# unregistrable pid) is propagated: nonzero driver exit, no checker run.
if ! stop_stands; then
  log "FATAL: stands did not all terminate; refusing to report success"
  exit 1
fi

run_checker "$OUT/checker-report.json"
