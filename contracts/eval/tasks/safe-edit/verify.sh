#!/bin/sh
# RAI-081 eval fixture verifier: safe-edit.
# Usage: verify.sh [workspace-dir]
#   with argument  -> run hidden tests against the given agent workspace
#   without        -> sanity mode: materialize reference solution/ over fixture/
# Exit codes: 0 pass, 1 verification failed, 2 usage/environment error.
set -u

TASK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
[ -f "$TASK_DIR/hidden-tests/check.sh" ] || { echo "missing hidden-tests/check.sh" >&2; exit 2; }

if [ "$#" -gt 1 ]; then
  echo "usage: $0 [workspace-dir]" >&2
  exit 2
fi

if [ "$#" -eq 1 ]; then
  WS=$1
  [ -d "$WS" ] || { echo "workspace is not a directory: $WS" >&2; exit 2; }
  exec sh "$TASK_DIR/hidden-tests/check.sh" "$TASK_DIR" "$WS"
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/raider-eval-safe-edit-XXXXXX") || exit 2
trap 'rm -rf "$TMP"' EXIT INT TERM
cp -R "$TASK_DIR/fixture/." "$TMP/" || exit 2
cp -R "$TASK_DIR/solution/." "$TMP/" || exit 2
exec sh "$TASK_DIR/hidden-tests/check.sh" "$TASK_DIR" "$TMP"
