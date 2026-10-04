#!/bin/sh
# Interactive chat with Raider — context continuity, coding tools,
# live LLM backend. Auto-detects model from the llama.cpp server.
cd "$(dirname "$0")/../.."

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

exec java -cp "$CP" raider.cli.Main chat \
  --provider openai --base-url "$BASE_URL" --model "$MODEL" "$@"
