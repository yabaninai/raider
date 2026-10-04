#!/bin/sh
# Non-TTY smoke for the W10 REPL (fast-path slice).
#
# Infra fact (2026-10-03): the Homebrew sbt launcher script redirects stdin
# from /dev/null whenever --batch is passed (`exec </dev/null`), so piped
# input can never reach the forked JVM through `sbt --batch ...`. The honest
# non-TTY path is the direct java invocation over the exported full classpath;
# the sbt --batch variant is still exercised below and must exit 0 (banner +
# clean EOF handling), but it cannot consume the pipe by launcher design.
#
# Exit 0 = all assertions passed; 20 = assertion failure; 21 = setup failure.
set -u
cd "$(dirname "$0")/../.."

OUT_DIR=artifacts/repl-fast
CP_FILE="$OUT_DIR/classpath.txt"
mkdir -p "$OUT_DIR"

COURSIER_CACHE="${COURSIER_CACHE:-/tmp/cc-master}" sbt --batch --error \
  "export raiderRepl/runtime:fullClasspath" > "$CP_FILE" || exit 21
CP=$(tail -1 "$CP_FILE")
[ -n "$CP" ] || exit 21

SCRIPT='val x = 1
x
scout.ask("ping")
val j = worker.start("bg")
j.await()
:jobs
:quit'

OUT=$(printf '%s\n' "$SCRIPT" | java -cp "$CP" raider.repl.Main 2>/dev/null)
STATUS=$?
printf '%s\n' "$OUT" > "$OUT_DIR/nontty-transcript.txt"
printf '%s\n' "$OUT"

fail() { echo "[smoke] FAIL: $1"; exit 20; }

[ "$STATUS" -eq 0 ] || fail "java exit=$STATUS (want 0)"
echo "$OUT" | grep -q  "val x: Int = 1"             || fail "missing val binding echo"
echo "$OUT" | grep -q  "val res0: Int = 1"          || fail "missing expression result for x"
echo "$OUT" | grep -q  "\[scripted-fixture\] mock answer" || fail "missing scripted ask answer"
echo "$OUT" | grep -q  "j_1"                        || fail "missing job id in transcript"
echo "$OUT" | grep -q  "Succeeded"                  || fail "job did not reach Succeeded in :jobs"
echo "$OUT" | grep -q  "bye"                        || fail "missing graceful bye"
echo "[smoke] non-TTY smoke PASS (exit 0, all assertions)"

# Launcher-fact evidence: with --batch the pipe is /dev/null by script design;
# the run must still be a clean exit 0 with the banner + EOF handling.
SBT_OUT=$(printf 'val x = 1\n:quit\n' | sbt --batch "raiderRepl/runMain raider.repl.Main" 2>/dev/null)
SBT_STATUS=$?
printf '%s\n' "$SBT_OUT" > "$OUT_DIR/nontty-sbt-batch-transcript.txt"
[ "$SBT_STATUS" -eq 0 ] || { echo "[smoke] FAIL: sbt --batch exit=$SBT_STATUS"; exit 20; }
echo "$SBT_OUT" | grep -q "Raider REPL" || { echo "[smoke] FAIL: no banner via sbt"; exit 20; }
echo "[smoke] sbt --batch variant: clean exit 0 (stdin is /dev/null by launcher design; see header)"
exit 0
