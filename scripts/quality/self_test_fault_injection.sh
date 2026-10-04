#!/bin/sh
# Fault injection for the quality runner: a failing gate tool must fail the run.
set -u
cd "$(dirname "$0")/../.."
fakebin="$(mktemp -d)"; ev="$(mktemp -d)"
overall=0
for mode in sbt sh; do
  rm -f "$fakebin"/*
  if [ "$mode" = sbt ]; then printf '#!/bin/sh\nexit 17\n' > "$fakebin/sbt"; chmod +x "$fakebin/sbt"; fi
  if [ "$mode" = sh ]; then printf '#!/bin/sh\nexit 17\n' > "$fakebin/sh"; chmod +x "$fakebin/sh"; fi
  if PATH="$fakebin:$PATH" python3 scripts/quality/quality.py execute > "$ev/q-$mode.log" 2>&1; then
    echo "SELF-TEST FAIL: quality execute returned 0 with broken $mode (false green)"
    overall=1
  else
    echo "self-test $mode: execute correctly failed"
  fi
done
rm -rf "$fakebin" "$ev"
[ "$overall" = 0 ] && echo "QUALITY FAULT-INJECTION SELF-TEST PASSED"
exit "$overall"
