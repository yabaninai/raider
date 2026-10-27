#!/usr/bin/env bash
# RAI-205 XE-ACCEPT-03 (budget): drive the portable workflow route against the
# scripted stand until the token budget is exhausted, expect the typed refusal
# (RA-PBUDGET) and stable exit code 23 (ExitCodes.BudgetExhausted).
#
# Fixture: expected/budget-exhaust.workflow.json — the extraction-classification
# example with limits.tokens=1 and sequential fanout; the stand's plain
# completions carry usage (in=100, out=50), so the SECOND dispatch is refused.
# The key is supplied via the environment (RAI-064), never argv.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

BUDGET_OUT="$ROOT/artifacts/xe-budget"
rm -rf "$BUDGET_OUT"
mkdir -p "$BUDGET_OUT/ws" "$BUDGET_OUT/run"
mkdir -p "$WS/budget"
compile_all

PORT_C=$(start_stand C --usage-in 100 --usage-out 50 \
  --content-file "$FIX/expected/budget-extract-content.json" \
  --marker "$WS/budget/marker.txt")

set +e
RAIDER_API_KEY="$STAND_KEY" java -jar "$JAR" workflow run \
  --doc "$FIX/expected/budget-exhaust.workflow.json" \
  --input "$FIX/expected/budget-input.json" \
  --out "$BUDGET_OUT/run" \
  --workspace "$BUDGET_OUT/ws" \
  --provider openai \
  --base-url "http://127.0.0.1:$PORT_C/v1" \
  --model stand-fixture \
  > "$BUDGET_OUT/stdout.log" 2> "$BUDGET_OUT/stderr.log"
EXIT=$?
set -e

# RAI-229: capture the stop result so the budget evidence below is still
# produced and verified, then propagate the failure at exit.
STOP_RC=0
stop_stands || STOP_RC=$?

log "budget run exit=$EXIT (expect 23)"
[ "$EXIT" -eq 23 ] || { log "FATAL: expected exit 23, got $EXIT"; tail -5 "$BUDGET_OUT/stderr.log" || true; exit 1; }

python3 - "$BUDGET_OUT" "$ROOT/modules/core/src/main/scala/raider/core/result.scala" <<'EOF'
import json, sys, pathlib
out = pathlib.Path(sys.argv[1])
work = sorted((out / "run").glob("work-*/run.json"))
if len(work) != 1:
    sys.exit(f"expected exactly one work-*/run.json under {out/'run'}, found {len(work)}")
run = json.loads(work[0].read_text())
stderr = (out / "stderr.log").read_text()
if "RA-PBUDGET" not in stderr:
    sys.exit("typed RA-PBUDGET refusal not found in stderr.log")
result = {
    "exitCode": 23,
    "expected": "RA-PBUDGET typed refusal (ExitCodes.BudgetExhausted)",
    "runStatus": run.get("status"),
    "runExitCode": run.get("exitCode"),
    "typedRefusal": "RA-PBUDGET",
    "artifacts": sorted(p.name for p in work[0].parent.iterdir()),
}
(out / "budget-result.json").write_text(json.dumps(result, indent=2) + "\n")
print("[xe-driver] budget-result:", json.dumps(result))
if run.get("exitCode") != 23:
    sys.exit("run.json exitCode is not 23")
EOF

if [ "$STOP_RC" -ne 0 ]; then
  log "FATAL: stands did not all terminate (stop rc=$STOP_RC); refusing to report success"
  exit "$STOP_RC"
fi

log "budget scenario OK"
