#!/bin/sh
# Protected verifier for review-planted-bug. NEVER ship this into the agent
# workspace. Args: TASK_DIR WS. Exit 0 pass / 1 fail.
set -u
TASK_DIR=$1
WS=$2
fails=0

F="$WS/FINDINGS.md"
if [ ! -f "$F" ]; then
  echo "FAIL: FINDINGS.md not found in workspace root"
  exit 1
fi

getlabel() { sed -n "s/^$1:[[:space:]]*//p" "$F" | head -n 1; }
f_file=$(getlabel FILE)
f_line=$(getlabel LINE)
f_sev=$(getlabel SEVERITY)
f_cause=$(getlabel CAUSE)

# FILE must name the defective file.
if [ "$f_file" = "src/Checkout.scala" ]; then
  echo "PASS: FILE points at src/Checkout.scala"
else
  echo "FAIL: FILE expected 'src/Checkout.scala', got '$f_file'"
  fails=$((fails + 1))
fi

# LINE must point at one of the two swapped branch lines (16 or 17).
case "$f_line" in
  16|17) echo "PASS: LINE points at the planted defect ($f_line)" ;;
  *) echo "FAIL: LINE expected 16 or 17, got '$f_line'"; fails=$((fails + 1)) ;;
esac

# SEVERITY must be one of the allowed values.
case "$f_sev" in
  critical|major|minor) echo "PASS: SEVERITY '$f_sev'" ;;
  *) echo "FAIL: SEVERITY must be critical|major|minor, got '$f_sev'"; fails=$((fails + 1)) ;;
esac

# CAUSE must describe the actual defect: coupon percent swap on WELCOME10/VIP20.
low=$(printf '%s' "$f_cause" | tr '[:upper:]' '[:lower:]')
has_coupon=0
case "$low" in *welcome10*|*vip20*) has_coupon=1 ;; esac
has_percent=0
case "$low" in *percent*|*%*|*процент*) has_percent=1 ;; esac
has_swap=0
case "$low" in
  *swap*|*swapped*|*reversed*|*inverted*|*wrong*|*instead*|*mismatch*|*перепутан*|*местами*|*наоборот*) has_swap=1 ;;
esac
if [ "$has_coupon" -eq 1 ] && [ "$has_percent" -eq 1 ] && [ "$has_swap" -eq 1 ]; then
  echo "PASS: CAUSE describes the percent swap"
else
  echo "FAIL: CAUSE must name WELCOME10/VIP20, the percent values, and that they are wrong/swapped; got: '$f_cause'"
  fails=$((fails + 1))
fi

# Diff constraint: workspace must otherwise match the fixture byte-for-byte.
diffout=$(cd "$TASK_DIR/fixture" && find . -type f | sort | while IFS= read -r f; do
  cmp -s "$TASK_DIR/fixture/$f" "$WS/$f" || echo "workspace file modified or missing: $f"
done)
if [ -z "$diffout" ]; then
  extra=$(cd "$WS" && find . -type f | sort | while IFS= read -r f; do
    [ "$f" = "./FINDINGS.md" ] && continue
    [ -f "$TASK_DIR/fixture/$f" ] || echo "$f"
  done)
  if [ -z "$extra" ]; then
    echo "PASS: workspace otherwise unmodified"
  else
    echo "FAIL: unexpected extra files in workspace:"
    echo "$extra"
    fails=$((fails + 1))
  fi
else
  echo "FAIL: fixture files were modified (read-only task):"
  echo "$diffout"
  fails=$((fails + 1))
fi

if [ "$fails" -eq 0 ]; then
  echo "VERIFY OK: review-planted-bug"
  exit 0
fi
echo "VERIFY FAILED: review-planted-bug ($fails failure(s))"
exit 1
