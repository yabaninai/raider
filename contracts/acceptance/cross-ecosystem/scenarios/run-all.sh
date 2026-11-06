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

# RAI-238: crash-path stand cleanup. A consumer failure under `set -e` (or an
# INT/TERM signal) must not skip stand shutdown: the EXIT trap always runs a
# best-effort stop_stands before the driver exits (RAI-229 crash-path gap:
# stands leaked at load ~313 when a consumer failed mid-corpus). A stop
# failure inside the trap is diagnosed (WARN) and never masks the original
# exit code (RAI-229 STOP_RC pattern). stop_stands is idempotent, so this
# cannot double-kill the stands the normal path already stopped.
xe_cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  local stop_rc=0
  stop_stands || stop_rc=$?
  if [ "$stop_rc" -ne 0 ]; then
    log "WARN: EXIT-trap stop_stands failed (rc=$stop_rc); preserving original exit code $rc"
  fi
  exit "$rc"
}
trap xe_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$OUT" "$WS"
rm -rf "$OUT"/* "$WS"/*

compile_all

# Workspace dirs the consumers expect (fresh per official run). The stand A
# content file must exist BEFORE the stand starts (it reads it at startup).
# RAI-254: cancel-mid-response/ must pre-exist because stand B's scripted
# proc_run touch targets a marker inside it (touch creates the file, not the
# parent dirs, and the stand starts before the consumer creates scenario dirs).
mkdir -p "$WS/tool-task" "$WS/cancel-mid-run" "$WS/cancel-mid-response"
printf '{"total": 128}' > "$WS/.stand-content.json"

PORT_A=$(start_stand A --content-file "$WS/.stand-content.json" --marker "$WS/tool-task/marker.txt")
# RAI-254: stand B's scripted proc_run touch now targets the marker the llm4s
# consumer polls during cancel-mid-response (hardcoded path in
# XeLlm4sConsumer). cancel-mid-run and eof-uncertain are cancelled before any
# round-1 response is consumed (settle interrupts the run via manager.cancel),
# so they never execute the touch; cancel-late runs strictly after
# cancel-mid-response, so marker pre-existence cannot fake the placement proof.
PORT_B=$(start_stand B --delay-ms 3000 --marker "$WS/cancel-mid-response/marker.txt")

run_llm4s "$OUT/manifest-llm4s.json" "handshake-terminal,refusals,observe-dedup"
run_l4j "$OUT/manifest-l4j-mock.json" "handshake-terminal,refusals,observe-dedup"
run_llm4s "$OUT/manifest-llm4s-standA.json" "tool-task,extraction-task" "$PORT_A"
run_l4j "$OUT/manifest-l4j-standA.json" "tool-task,extraction-task" "$PORT_A"
# RAI-254: the stand B llm4s invocation now also carries the two RAI-242
# scenarios (cancel-mid-response, cancel-late). The manifest FILE NAME is
# unchanged on purpose: the checker's --manifests list lives in lib.sh
# (outside this card's allowed paths), so no new manifest file may be
# introduced; every golden entry still matches exactly one manifest entry.
run_llm4s "$OUT/manifest-llm4s-standB.json" "cancel-mid-run,eof-uncertain,cancel-mid-response,cancel-late" "$PORT_B"
run_l4j "$OUT/manifest-l4j-standB.json" "cancel-mid-run,eof-uncertain" "$PORT_B"

# RAI-229: a stop failure (a stand that survived SIGTERM+SIGKILL, or an
# unregistrable pid) is propagated: nonzero driver exit, no checker run.
if ! stop_stands; then
  log "FATAL: stands did not all terminate; refusing to report success"
  exit 1
fi

run_checker "$OUT/checker-report.json"
