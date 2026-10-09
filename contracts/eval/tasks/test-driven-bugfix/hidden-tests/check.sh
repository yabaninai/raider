#!/bin/sh
# Protected verifier for test-driven-bugfix. NEVER ship this into the agent
# workspace. Args: TASK_DIR WS. Exit 0 pass / 1 fail.
set -u
TASK_DIR=$1
WS=$2
fails=0
CALC="$WS/bin/calc.sh"
TESTS="$WS/tests/run-tests.sh"
BASE_TESTS="$TASK_DIR/hidden-tests/baseline-run-tests.sh"
CASES="$TASK_DIR/hidden-tests/regression/cases.txt"

[ -f "$CALC" ] || { echo "FAIL: bin/calc.sh missing in workspace"; exit 1; }
[ -f "$TESTS" ] || { echo "FAIL: tests/run-tests.sh missing in workspace"; exit 1; }

# 0. Tamper check: workspace test suite must equal its baseline byte-for-byte.
cmp -s "$BASE_TESTS" "$TESTS" \
  && echo "PASS: workspace test suite unmodified" \
  || { echo "FAIL: tests/run-tests.sh was modified (protected)"; fails=$((fails + 1)); }

# 1. Hidden regression cases (authoritative; not visible to the agent).
n=0
while IFS='|' read -r want_rc want_out args; do
  case "$want_rc" in ''|'#'*) continue ;; esac
  n=$((n + 1))
  # shellcheck disable=SC2086
  set -- $args
  out=$(sh "$CALC" "$@" 2>/dev/null)
  rc=$?
  if [ "$rc" -ne "$want_rc" ] || { [ "$want_rc" -eq 0 ] && [ "$out" != "$want_out" ]; }; then
    echo "FAIL: hidden regression case #$n (args: '$args'): want rc=$want_rc out='$want_out', got rc=$rc out='$out'"
    fails=$((fails + 1))
  fi
done < "$CASES"
[ "$fails" -eq 0 ] && echo "PASS: hidden regression cases ($n) OK"

# 2. Workspace suite must pass as a whole.
if (cd "$WS" && sh tests/run-tests.sh >/dev/null 2>&1); then
  echo "PASS: workspace tests/run-tests.sh passes"
else
  echo "FAIL: workspace tests/run-tests.sh does not pass"
  fails=$((fails + 1))
fi

# 3. Diff constraint: only bin/calc.sh may differ from the fixture.
diffout=$(cd "$TASK_DIR/fixture" && find . -type f | sort | while IFS= read -r f; do
  cmp -s "$TASK_DIR/fixture/$f" "$WS/$f" || echo "$f"
done)
diffbad=$(printf '%s\n' "$diffout" | grep -v '^$' | grep -vx '\./bin/calc\.sh')
if [ -z "$diffout" ]; then
  echo "FAIL: bin/calc.sh was not changed (bug not fixed)"
  fails=$((fails + 1))
elif [ -n "$diffbad" ]; then
  echo "FAIL: files other than bin/calc.sh were modified:"
  echo "$diffbad" | sed 's/^/  /'
  fails=$((fails + 1))
else
  echo "PASS: only bin/calc.sh was modified"
fi

if [ "$fails" -eq 0 ]; then
  echo "VERIFY OK: test-driven-bugfix"
  exit 0
fi
echo "VERIFY FAILED: test-driven-bugfix ($fails failure(s))"
exit 1
