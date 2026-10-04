#!/bin/sh
# Headless CLI smoke (RAI-022 slice): run the packaged bundle through the
# REAL Main with CLOSED stdin (piped from /dev/null) and assert exit 0 +
# artifacts. The bundle jar is produced by HeadlessRunnerSpec (test-time
# packaging of the compiled fixtures — no runtime compiler).
set -u
cd "$(dirname "$0")/../.."

JAR=modules/cli/target/demo-bundle.jar
[ -f "$JAR" ] || { echo "[cli-smoke] FAIL: $JAR missing (run raiderCli/test first)"; exit 21; }

OUT=$(mktemp -d)
CP_FILE="$OUT/classpath.txt"
COURSIER_CACHE="${COURSIER_CACHE:-/tmp/cc-master}" sbt --batch --error \
  "export root/runtime:fullClasspath" > "$CP_FILE" || exit 21
CP=$(tail -1 "$CP_FILE")
[ -n "$CP" ] || exit 21

OUTTEXT=$(printf '' | java -cp "$CP" raider.cli.Main run \
  --bundle "$JAR" --workflow inspect --input "cli-smoke" \
  --out "$OUT" --mock 2>"$OUT/stderr.txt")
STATUS=$?
echo "$OUTTEXT"
[ $STATUS -eq 0 ] || { echo "[cli-smoke] FAIL: exit=$STATUS"; cat "$OUT/stderr.txt"; exit 20; }
echo "$OUTTEXT" | grep -q '"exitCode":0' || { echo "[cli-smoke] FAIL: no exitCode 0 line"; exit 20; }
WORKDIR=$(echo "$OUTTEXT" | sed -n 's/.*"workDir":"\([^"]*\)".*/\1/p')
[ -f "$WORKDIR/run.json" ] || { echo "[cli-smoke] FAIL: run.json missing"; exit 20; }
grep -q '"status":"Succeeded"' "$WORKDIR/run.json" || { echo "[cli-smoke] FAIL: not Succeeded"; exit 20; }
grep -q "fixture/mock" "$WORKDIR/summary.md" || { echo "[cli-smoke] FAIL: fixture marker missing"; exit 20; }
echo "[cli-smoke] PASS (exit 0, run.json Succeeded, fixture marked)"
exit 0
