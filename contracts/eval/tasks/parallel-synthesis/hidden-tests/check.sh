#!/bin/sh
# Protected verifier for parallel-synthesis. NEVER ship this into the agent
# workspace. Args: TASK_DIR WS. Exit 0 pass / 1 fail. Requires python3.
set -u
TASK_DIR=$1
WS=$2

S="$WS/synthesis.json"
if [ ! -f "$S" ]; then
  echo "FAIL: synthesis.json not found in workspace root"
  exit 1
fi

fails=0
python3 - "$S" "$TASK_DIR/hidden-tests/expected/synthesis.json" <<'PY'
import json, sys

got_path, want_path = sys.argv[1], sys.argv[2]

def load(path):
    with open(path, "rb") as f:
        return json.load(f)

try:
    got = load(got_path)
except Exception as e:
    print(f"FAIL: synthesis.json is not valid JSON: {e}")
    sys.exit(1)

want = load(want_path)
problems = []

if not isinstance(got, dict) or set(got.keys()) != {"modules", "total_items", "conflicts"}:
    problems.append("top-level keys must be exactly modules,total_items,conflicts")
else:
    mods = got["modules"]
    if not isinstance(mods, dict) or set(mods.keys()) != {"auth", "billing", "search", "ui"}:
        problems.append("modules must be keyed by exactly: auth,billing,search,ui")
    else:
        for name, m in mods.items():
            if not isinstance(m, dict) or set(m.keys()) != {"status", "items"}:
                problems.append(f"modules.{name} must have exactly status,items")
                continue
            if not isinstance(m["status"], str):
                problems.append(f"modules.{name}.status must be a string")
            if not isinstance(m["items"], int) or isinstance(m["items"], bool):
                problems.append(f"modules.{name}.items must be an integer")
    ti = got["total_items"]
    if not isinstance(ti, int) or isinstance(ti, bool):
        problems.append("total_items must be an integer")
    cf = got["conflicts"]
    if not isinstance(cf, list) or not all(isinstance(x, str) for x in cf):
        problems.append("conflicts must be an array of strings")
    elif cf != sorted(cf):
        problems.append("conflicts must be sorted alphabetically")
    if got != want:
        problems.append("merged result differs from ground truth")
        problems.append(f"expected: {json.dumps(want, sort_keys=True)}")
        problems.append(f"got:      {json.dumps(got, sort_keys=True)}")

if problems:
    for p in problems:
        print(f"FAIL: {p}")
    sys.exit(1)
print("PASS: synthesis.json matches ground truth (schema + values)")
PY
if [ $? -ne 0 ]; then fails=$((fails + 1)); fi

# Diff constraint: fixture files must be untouched; only synthesis.json may be added.
diffout=$(cd "$TASK_DIR/fixture" && find . -type f | sort | while IFS= read -r f; do
  cmp -s "$TASK_DIR/fixture/$f" "$WS/$f" || echo "workspace file modified or missing: $f"
done)
if [ -z "$diffout" ]; then
  extra=$(cd "$WS" && find . -type f | sort | while IFS= read -r f; do
    [ "$f" = "./synthesis.json" ] && continue
    [ -f "$TASK_DIR/fixture/$f" ] || echo "$f"
  done)
  if [ -z "$extra" ]; then
    echo "PASS: reports and policy untouched, no unexpected files"
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
  echo "VERIFY OK: parallel-synthesis"
  exit 0
fi
echo "VERIFY FAILED: parallel-synthesis ($fails failure(s))"
exit 1
