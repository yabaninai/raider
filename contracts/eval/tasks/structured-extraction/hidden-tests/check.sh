#!/bin/sh
# Protected verifier for structured-extraction. NEVER ship this into the agent
# workspace. Args: TASK_DIR WS. Exit 0 pass / 1 fail. Requires python3.
set -u
TASK_DIR=$1
WS=$2

E="$WS/events.json"
if [ ! -f "$E" ]; then
  echo "FAIL: events.json not found in workspace root"
  exit 1
fi

fails=0
python3 - "$E" "$TASK_DIR/hidden-tests/expected/events.json" <<'PY'
import json, sys

got_path, want_path = sys.argv[1], sys.argv[2]
problems = []

def load(path):
    with open(path, "rb") as f:
        return json.load(f)

try:
    got = load(got_path)
except Exception as e:
    print(f"FAIL: events.json is not valid JSON: {e}")
    sys.exit(1)

want = load(want_path)

if not isinstance(got, dict) or set(got.keys()) != {"events"}:
    problems.append("top-level object must have exactly the key 'events'")
else:
    ev = got["events"]
    wv = want["events"]
    if not isinstance(ev, list):
        problems.append("'events' must be an array")
    else:
        for i, e in enumerate(ev):
            if not isinstance(e, dict):
                problems.append(f"events[{i}] must be an object")
                continue
            if set(e.keys()) != {"id", "ts", "level", "status", "ms"}:
                problems.append(f"events[{i}] keys must be exactly id,ts,level,status,ms; got {sorted(e.keys())}")
            for k in ("id", "ts", "level"):
                if k in e and not isinstance(e[k], str):
                    problems.append(f"events[{i}].{k} must be a string")
            for k in ("status", "ms"):
                if k in e and (not isinstance(e[k], int) or isinstance(e[k], bool)):
                    problems.append(f"events[{i}].{k} must be an integer")
        ids = [e.get("id") for e in ev if isinstance(e, dict)]
        if ids != sorted(ids):
            problems.append("events must be sorted by id ascending")
        if ev != wv:
            problems.append(f"events differ from ground truth; got {len(ev)} events, expected {len(wv)}")
            for g, w in zip(ev, wv):
                if g != w:
                    problems.append(f"first differing entry: got {g!r}, expected {w!r}")
                    break

if problems:
    for p in problems:
        print(f"FAIL: {p}")
    sys.exit(1)
print("PASS: events.json matches ground truth (schema + values + order)")
PY
if [ $? -ne 0 ]; then fails=$((fails + 1)); fi

# Diff constraint: fixture files must be untouched; only events.json may be added.
diffout=$(cd "$TASK_DIR/fixture" && find . -type f | sort | while IFS= read -r f; do
  cmp -s "$TASK_DIR/fixture/$f" "$WS/$f" || echo "workspace file modified or missing: $f"
done)
if [ -z "$diffout" ]; then
  extra=$(cd "$WS" && find . -type f | sort | while IFS= read -r f; do
    [ "$f" = "./events.json" ] && continue
    [ -f "$TASK_DIR/fixture/$f" ] || echo "$f"
  done)
  if [ -z "$extra" ]; then
    echo "PASS: logs untouched, no unexpected files"
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
  echo "VERIFY OK: structured-extraction"
  exit 0
fi
echo "VERIFY FAILED: structured-extraction ($fails failure(s))"
exit 1
