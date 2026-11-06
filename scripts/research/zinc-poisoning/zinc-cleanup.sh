#!/bin/bash
# RAI-241 experiment cleanup: dispose of THIS card's burners only.
#
# usage: zinc-cleanup.sh [base-dir]
#   (defaults to the same RAI241_BASE as run-zinc-tier.sh)
#
# Safety properties:
#   - inspects the cwd of every java/sbt process and signals ONLY those whose
#     cwd is inside the experiment base directory (foreign lanes' JVMs are
#     never touched, even if they are also sbt);
#   - SIGTERM first, bounded grace, SIGKILL only if still alive;
#   - then removes the disposable copy/log tree and reports leftovers.
set -u

BASE="${1:-${RAI241_BASE:-/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-241-zinc}}"
BASE_REAL="$(cd "$BASE" 2>/dev/null && pwd -P || echo "$BASE")"

# helper (top level — bash cannot parse `case` inside $() with nested loops):
# prints the pid iff the process cwd is inside the experiment base
own_jvm() {
  local pid="$1" cwd
  cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | grep '^n' | sed 's/^n//' | head -1)
  [ -n "$cwd" ] || return 1
  case "$cwd" in
    "$BASE_REAL"|"$BASE_REAL"/*) echo "$pid" ;;
    *) return 1 ;;
  esac
}

echo "[cleanup] experiment base: $BASE_REAL"
[ -d "$BASE_REAL" ] || { echo "[cleanup] nothing to clean (base does not exist)"; exit 0; }

killed=0
for pid in $(pgrep -f 'sbt-launch|scala3|java' 2>/dev/null | sort -u); do
  if own=$(own_jvm "$pid"); then
    echo "[cleanup] killing own JVM pid=$own"
    kill -TERM "$own" 2>/dev/null && killed=$((killed+1))
  fi
done
if [ "$killed" -gt 0 ]; then
  sleep 10
  for pid in $(pgrep -f 'sbt-launch|scala3|java' 2>/dev/null | sort -u); do
    if own=$(own_jvm "$pid"); then
      kill -KILL "$own" 2>/dev/null && echo "[cleanup] SIGKILL leftover pid=$own"
    fi
  done
fi
echo "[cleanup] own JVMs signalled: $killed"

rm -rf "$BASE_REAL"
echo "[cleanup] removed $BASE_REAL"

left=""
for pid in $(pgrep -f 'sbt-launch|scala3|java' 2>/dev/null | sort -u); do
  if own=$(own_jvm "$pid"); then left="$left $own"; fi
done
if [ -n "$left" ]; then
  echo "[cleanup] WARNING leftover processes under base:$left"
  exit 1
fi
echo "[cleanup] OK: no remaining processes under the experiment base"
