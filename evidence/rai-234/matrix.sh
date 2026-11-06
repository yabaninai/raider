#!/usr/bin/env bash
# RAI-234 verification-first battery: bounded loaded matrix for
# AgentLoopPhaseEmissionSpec (focused, current tree, unmodified baseline).
#
# Host discipline (card + AGENTS.md):
#   - load gate before EVERY sbt run: 1-min loadavg > 12 -> sleep 120, re-check
#     (bounded at 20 cycles = 40 min per run, then proceed WITH a disclosed
#     line: a loaded battery tolerates ambient load; every reading is recorded);
#   - one sbt JVM at a time in this worktree (sequential runs only);
#   - burners are OWN processes (recorded PIDs, comm-verified before kill);
#     no pattern-based sweep, foreign processes never touched;
#   - 1/5/15-min readings recorded before and after every run;
#   - stall watchdog: if the run's log gets no output for 300 s or exceeds
#     1800 s total, THIS script's own sbt PID (comm-verified) is killed, the
#     run is recorded as STALLED, and the battery moves on;
#   - crash-safe: burner PIDs persisted to a pidfile; on startup any surviving
#     pidfile PIDs are comm-verified (must be "yes") and killed.
# Resumable: a run is skipped if its log already exists and its matrix line
# is already present.
set -u
cd "$(dirname "$0")/../.." || exit 9

RUNS=${1:-32}
BURNERS=${2:-18}
BASE="evidence/rai-234"
OUT="$BASE/loaded"
PIDFILE="$OUT/burners.pidfile"
mkdir -p "$OUT"

log() { echo "$(date -u +%FT%TZ) $*" >> "$BASE/load-gate.log"; }

l1() { sysctl -n vm.loadavg | awk '{print $2}' | tr ',' '.'; }
loadline() { sysctl -n vm.loadavg | sed 's/[{}]//g' | sed 's/^ *//;s/ *$//'; }

# ---- crash-safe burner sweep of MY OWN recorded PIDs -----------------------
sweep_pidfile() {
  [ -f "$PIDFILE" ] || return 0
  while read -r p; do
    [ -n "$p" ] || continue
    local c
    c=$(ps -o comm= -p "$p" 2>/dev/null || true)
    if [ "$c" = "yes" ]; then
      kill "$p" 2>/dev/null || true
      log "pidfile-sweep killed leftover burner pid=$p"
    fi
  done < "$PIDFILE"
  rm -f "$PIDFILE"
}
sweep_pidfile

BURNER_PIDS=()

start_burners() {
  BURNER_PIDS=()
  local i=0 c p
  while [ "$i" -lt "$BURNERS" ]; do
    yes > /dev/null &
    BURNER_PIDS+=($!)
    i=$((i + 1))
  done
  printf '%s\n' "${BURNER_PIDS[@]}" > "$PIDFILE"
  for p in "${BURNER_PIDS[@]}"; do
    c=$(ps -o comm= -p "$p" 2>/dev/null || true)
    if [ "$c" != "yes" ]; then
      log "WARN burner pid=$p comm=$c (expected yes)"
    fi
  done
  log "burners started n=$BURNERS pids=${BURNER_PIDS[*]}"
}

stop_burners() {
  local p c killed=0
  for p in "${BURNER_PIDS[@]}"; do
    c=$(ps -o comm= -p "$p" 2>/dev/null || true)
    if [ "$c" = "yes" ]; then
      kill "$p" 2>/dev/null || true
      killed=$((killed + 1))
    fi
  done
  sleep 1
  for p in "${BURNER_PIDS[@]}"; do
    if kill -0 "$p" 2>/dev/null; then
      c=$(ps -o comm= -p "$p" 2>/dev/null || true)
      if [ "$c" = "yes" ]; then
        kill -9 "$p" 2>/dev/null || true
        log "WARN burner pid=$p needed SIGKILL"
      fi
    fi
  done
  log "burners stopped killed=$killed of ${#BURNER_PIDS[@]}"
  BURNER_PIDS=()
  rm -f "$PIDFILE"
}

CURRENT_SBT_PID=""

# Kill my own sbt launcher + any direct java children (each comm-verified).
kill_own_sbt() {
  local spid="$1" cpids c
  [ -n "$spid" ] || return 0
  kill "$spid" 2>/dev/null || true
  cpids=$(pgrep -P "$spid" 2>/dev/null || true)
  for c in $cpids; do
    if [ "$(ps -o comm= -p "$c" 2>/dev/null || true)" = "java" ]; then
      kill "$c" 2>/dev/null || true
      log "killed own java child pid=$c of sbt launcher $spid"
    fi
  done
  sleep 3
  if kill -0 "$spid" 2>/dev/null; then
    kill -9 "$spid" 2>/dev/null || true
  fi
  for c in $cpids; do
    if kill -0 "$c" 2>/dev/null && [ "$(ps -o comm= -p "$c" 2>/dev/null || true)" = "java" ]; then
      kill -9 "$c" 2>/dev/null || true
    fi
  done
}

trap 'stop_burners; [ -n "$CURRENT_SBT_PID" ] && kill_own_sbt "$CURRENT_SBT_PID"; exit 130' INT TERM
trap 'stop_burners' EXIT

load_gate() {
  local cycles=0 l1v
  while [ "$cycles" -lt 20 ]; do
    l1v=$(l1)
    if awk -v a="$l1v" 'BEGIN{exit !(a<=12)}'; then
      log "load-gate PASS 1min=$l1v"
      return 0
    fi
    log "load-gate WAIT 1min=$l1v cycle=$cycles (sleep 120)"
    sleep 120
    cycles=$((cycles + 1))
  done
  log "load-gate PROCEED-DISCLOSED 1min=$(l1) after 20 bounded cycles (loaded battery: ambient recorded, not blocking)"
  return 0
}

# ---- stall watchdog: kill ONLY my own sbt pid, comm-verified ----------------
watch_run() { # $1 = sbt pid, $2 = logfile
  local spid="$1" logf="$2" last_size=0 idle=0 total=0
  while kill -0 "$spid" 2>/dev/null; do
    sleep 15
    total=$((total + 15))
    if ! kill -0 "$spid" 2>/dev/null; then break; fi
    local sz
    sz=$(wc -c < "$logf" 2>/dev/null | tr -d ' ' || echo 0)
    if [ "$sz" != "$last_size" ]; then
      last_size=$sz
      idle=0
    else
      idle=$((idle + 15))
    fi
    if [ "$idle" -ge 300 ] || [ "$total" -ge 1800 ]; then
      local c
      c=$(ps -o comm= -p "$spid" 2>/dev/null || true)
      log "WATCHDOG firing pid=$spid comm=$c idle=${idle}s total=${total}s"
      if [ "$c" = "java" ] || [ "$c" = "sbt" ] || [ -z "$c" ]; then
        kill "$spid" 2>/dev/null || true
        sleep 3
        kill -9 "$spid" 2>/dev/null || true
      fi
      echo "WATCHDOG: killed own sbt pid=$spid comm=$c idle=${idle}s total=${total}s" >> "$logf"
      return 1
    fi
  done
  return 0
}

echo "# RAI-234 loaded matrix  runs=$RUNS burners=$BURNERS" > "$OUT/matrix.txt"
echo "# columns: run rc start dur_s load_before(1/5/15) load_after(1/5/15) summary" >> "$OUT/matrix.txt"

r=1
while [ "$r" -le "$RUNS" ]; do
  n=$(printf "%02d" "$r")
  if [ -f "$OUT/run-$n.log" ] && grep -q "^run=$n " "$OUT/matrix.txt" 2>/dev/null; then
    log "resume: run=$n already recorded, skipping"
    r=$((r + 1))
    continue
  fi
  rm -f "$OUT/run-$n.log"
  load_gate
  t0=$(date -u +%FT%TZ)
  before=$(loadline)
  start_burners
  s0=$(date +%s)
  sbt --batch "raiderRuntime/testOnly raider.runtime.loop.AgentLoopPhaseEmissionSpec" \
    > "$OUT/run-$n.log" 2>&1 &
  SPID=$!
  CURRENT_SBT_PID=$SPID
  echo "sbt pid=$SPID comm=$(ps -o comm= -p "$SPID" 2>/dev/null || echo '?')" >> "$OUT/proctree.log"
  watch_run "$SPID" "$OUT/run-$n.log"
  wait "$SPID"
  rc=$?
  CURRENT_SBT_PID=""
  s1=$(date +%s)
  after=$(loadline)
  stop_burners
  summary=$(grep -E "tests passed" "$OUT/run-$n.log" | tail -1)
  echo "run=$n rc=$rc start=$t0 dur=$((s1 - s0))s before=[$before] after=[$after] summary=\"$summary\"" >> "$OUT/matrix.txt"
  r=$((r + 1))
done

log "battery complete runs=$RUNS"
shasum -a 256 "$OUT"/run-*.log > "$OUT/logs.sha256"
echo "done" >> "$OUT/matrix.txt"
