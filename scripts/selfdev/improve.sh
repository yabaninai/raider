#!/bin/sh
# Self-improvement launcher: feeds the self-improvement prompt to the coder
# agent, which works on Raider's own codebase.
# Prompt: docs/PROMPT-SELF-IMPROVE.txt
set -u
cd "$(dirname "$0")/../.."

PROMPT_FILE=docs/PROMPT-SELF-IMPROVE.txt
[ -f "$PROMPT_FILE" ] || { echo "improve.sh: $PROMPT_FILE not found" >&2; exit 21; }

BASE_URL="${RAIDER_BASE_URL:-http://127.0.0.1:8081/v1}"
MODEL=$(curl -s --max-time 5 "$BASE_URL/models" 2>/dev/null | python3 -c "
import sys, json
try: print(json.load(sys.stdin)['models'][0]['model'])
except: print('local')
" 2>/dev/null)
[ -n "$MODEL" ] || MODEL="local"

CP_FILE=target/raider-classpath.txt
if [ ! -s "$CP_FILE" ]; then
  COURSIER_CACHE="${COURSIER_CACHE:-/tmp/cc-master}" sbt --batch --error \
    "export root/runtime:fullClasspath" > "$CP_FILE" || exit 21
fi
CP=$(tail -1 "$CP_FILE")

OUT=artifacts/selfdev/improve-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

echo "[improve] model=$MODEL prompt=$PROMPT_FILE out=$OUT"
exec java -cp "$CP" raider.cli.Main run \
  --agent coder --provider openai --base-url "$BASE_URL" --model "$MODEL" \
  --workspace . \
  --input "$(cat "$PROMPT_FILE")" \
  --out "$OUT"
