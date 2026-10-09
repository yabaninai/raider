#!/bin/sh
# Protected verifier for repo-search. NEVER ship this into the agent workspace.
# Args: TASK_DIR WS. Exit 0 pass / 1 fail.
set -u
TASK_DIR=$1
WS=$2
fails=0
ok()  { echo "PASS: $1"; }
bad() { echo "FAIL: $1"; fails=$((fails + 1)); }

A="$WS/ANSWER.md"
if [ ! -f "$A" ]; then
  echo "FAIL: ANSWER.md not found in workspace root"
  exit 1
fi

field() { sed -n "s/^$1:[[:space:]]*//p" "$A" | head -n 1; }
q1=$(field Q1)
q2=$(field Q2)
q3=$(field Q3)
q4=$(field Q4)

# Q1: module defining final case class Invoice
if [ "$q1" = "src/billing" ]; then ok "Q1 module"; else bad "Q1: expected 'src/billing', got '$q1'"; fi

# Q2: number of TODO markers under src/**/*.scala
if [ "$q2" = "3" ]; then ok "Q2 TODO count"; else bad "Q2: expected '3', got '$q2'"; fi

# Q3: files calling Money.legacyParse( — exactly two, alphabetical
TMPD=$(mktemp -d) || exit 2
trap 'rm -rf "$TMPD"' EXIT INT TERM
echo "$q3" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' | sort > "$TMPD/got"
cat > "$TMPD/want" <<'EOT'
src/billing/InvoiceRepo.scala
src/orders/OrderRepo.scala
EOT
sort -o "$TMPD/want" "$TMPD/want"
if cmp -s "$TMPD/got" "$TMPD/want"; then ok "Q3 legacyParse call sites"; else bad "Q3: expected exactly 'src/billing/InvoiceRepo.scala, src/orders/OrderRepo.scala', got '$q3'"; fi

# Q4: default currency
if [ "$q4" = "CHF" ]; then ok "Q4 currency"; else bad "Q4: expected 'CHF', got '$q4'"; fi

# Diff constraint: workspace must otherwise match the fixture byte-for-byte.
diffout=$(cd "$TASK_DIR/fixture" && find . -type f | sort | while IFS= read -r f; do
  cmp -s "$TASK_DIR/fixture/$f" "$WS/$f" || echo "workspace file modified or missing: $f"
done)
if [ -z "$diffout" ]; then
  extra=$(cd "$WS" && find . -type f | sort | while IFS= read -r f; do
    [ "$f" = "./ANSWER.md" ] && continue
    [ -f "$TASK_DIR/fixture/$f" ] || echo "$f"
  done)
  if [ -z "$extra" ]; then
    ok "workspace otherwise unmodified (read-only task)"
  else
    bad "unexpected extra files in workspace:"
    echo "$extra"
    fails=$((fails + 1))
  fi
else
  bad "fixture files were modified (read-only task):"
  echo "$diffout"
  fails=$((fails + 1))
fi

if [ "$fails" -eq 0 ]; then
  echo "VERIFY OK: repo-search"
  exit 0
fi
echo "VERIFY FAILED: repo-search ($fails failure(s))"
exit 1
