#!/bin/sh
# Raider self-dev launcher: exec the headless CLI over the exported runtime
# classpath. Auto-detects the model name from the OpenAI-compatible server.
#
# Usage:
#   scripts/selfdev/raider.sh run --agent coder \
#     --provider openai [--base-url http://127.0.0.1:8081/v1] \
#     --input "YOUR PROMPT" --out artifacts/selfdev
#
# The script auto-detects the model name via /v1/models if --model is not
# given. Stdin is never read; exit codes per ci-runtime §8.
set -u
cd "$(dirname "$0")/../.."

BASE_URL="${RAIDER_BASE_URL:-http://127.0.0.1:8081/v1}"

# Build args, injecting --model if not provided
ARGS=""
HAS_MODEL=0
HAS_BASE=0
for arg in "$@"; do
  case "$arg" in
    --model) HAS_MODEL=1 ;;
    --base-url) HAS_BASE=1 ;;
  esac
done

if [ "$HAS_BASE" -eq 0 ]; then
  ARGS="$ARGS --base-url $BASE_URL"
fi

if [ "$HAS_MODEL" -eq 0 ]; then
  MODEL=$(curl -s --max-time 5 "$BASE_URL/models" 2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  print(d['models'][0]['model'])
except: print('local')
" 2>/dev/null)
  [ -n "$MODEL" ] || MODEL="local"
  ARGS="$ARGS --model $MODEL"
fi

CP_FILE=target/raider-classpath.txt
if [ ! -s "$CP_FILE" ]; then
  COURSIER_CACHE="${COURSIER_CACHE:-/tmp/cc-master}" sbt --batch --error \
    "export root/runtime:fullClasspath" > "$CP_FILE" || exit 21
fi
CP=$(tail -1 "$CP_FILE")
[ -n "$CP" ] || { echo "raider.sh: empty classpath" >&2; exit 21; }

exec java -cp "$CP" raider.cli.Main "$@" $ARGS
