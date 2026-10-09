#!/bin/sh
# Workspace tests for calc.sh. Exit 0 when all assertions pass.
set -u
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
CALC="$HERE/../bin/calc.sh"
ERRFILE="$HERE/.stderr.tmp"
fails=0
total=0

expect() { # expect <desc> <expected> <args...>
  total=$((total + 1))
  desc=$1; want=$2; shift 2
  got=$(sh "$CALC" "$@" 2>"$ERRFILE")
  rc=$?
  if [ "$got" = "$want" ] && [ "$rc" -eq 0 ]; then
    echo "ok: $desc"
  else
    err=$(cat "$ERRFILE" 2>/dev/null)
    echo "FAIL: $desc — want '$want' rc=0, got '$got' rc=$rc $err"
    fails=$((fails + 1))
  fi
}

expect_err() { # expect_err <desc> <args...>
  total=$((total + 1))
  desc=$1; shift
  out=$(sh "$CALC" "$@" 2>"$ERRFILE")
  rc=$?
  err=$(cat "$ERRFILE" 2>/dev/null)
  if [ "$rc" -eq 2 ] && [ -n "$err" ] && [ -z "$out" ]; then
    echo "ok: $desc"
  else
    echo "FAIL: $desc — want rc=2 with stderr, got rc=$rc out='$out' err='$err'"
    fails=$((fails + 1))
  fi
}

expect "abs of -5"                    5   abs -5
expect "abs of 7"                     7   abs 7
expect "max of 3 9 2"                 9   max 3 9 2
expect "max with negatives"           -3  max -3 -7 -5
expect "median single"                5   median 5
expect "median odd"                   2   median 3 1 2
expect "median even is lower middle"  20  median 10 20 30 40
expect "median with negatives"        -5  median -5 5 -10
expect_err "median rejects non-integer"   median 1 x 3
expect_err "unknown command"              mod 4 2

rm -f "$ERRFILE"
echo "tests: $total, failed: $fails"
[ "$fails" -eq 0 ]
