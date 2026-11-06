#!/bin/bash
# RAI-241 zinc cold-compile poisoning experiment: execute one measurement tier.
#
# usage: run-zinc-tier.sh <tier-tag> <n-copies> [stagger-seconds]
#   tier-tag        label for logs/copies (e.g. T1, T2, T4, T8, T8s)
#   n-copies        1..8 (hard burner cap: this card's planned max tier)
#   stagger-seconds optional; delay between copy launches (default 0 = simultaneous)
#
# Protocol per copy (fresh, disposable, no .git — snapshot/instrument mode per
# docs/parallel-development.md §2):
#   phase "cold"      : sbt --batch Test/compile   (the COLD compile; where all
#                      three RAI-224 poisonings struck: 61/55/60 s fast-fails)
#   phase "recompile" : sbt --batch Test/compile   (incremental no-op; the
#                      corruption detector — poisoned zinc state re-fails here)
# On any compile failure, bounded auto-diagnostics (recorded as diag phases,
# never replacing the tier entry):
#   phase "retry"     : sbt --batch Test/compile   (does the false error persist?)
#   phase "clean"     : sbt --batch clean
#   phase "heal"      : sbt --batch Test/compile   (self-healing probe)
# A copy that failed with the poisoning signature also gets its zinc state
# preserved as a compressed tar for comparison against a healthy sibling.
#
# Safety properties (card acceptance: bounded burners with cleanup):
#   - at most 8 concurrent sbt builds (hard cap, refused above);
#   - per-phase watchdog HARDCAP_S (own process group only — never foreign PIDs);
#   - every JVM capped at -Xmx2G -XX:+ExitOnOutOfMemoryError (RAI-224 protocol);
#   - heavy tiers (>=8 copies) wait for a calm window (1-min load < MAXLOAD)
#     up to WAIT_TIMEOUT_S before launching (host discipline, recorded);
#   - shared-state write probe: files under ~/.sbt and the coursier cache
#     modified during the tier are listed (expected: none once populated).
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
BASE="${RAI241_BASE:-/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-241-zinc}"
COMMIT="${RAI241_COMMIT:-}"
HARDCAP_S=1800
MAXLOAD=8
WAIT_TIMEOUT_S=1800
MAX_CONCURRENT=8

TAG="${1:-}"; N="${2:-}"; STAGGER="${3:-0}"
[ -n "$TAG" ] && [ -n "$N" ] || { echo "usage: run-zinc-tier.sh <tier-tag> <n-copies> [stagger-seconds]" >&2; exit 64; }
[ "$N" -ge 1 ] 2>/dev/null || { echo "n-copies must be >= 1" >&2; exit 64; }
[ "$N" -le "$MAX_CONCURRENT" ] || { echo "refusing: n-copies $N exceeds burner cap $MAX_CONCURRENT" >&2; exit 64; }
[ -n "$REPO" ] || { echo "cannot locate repo root" >&2; exit 65; }
if [ -z "$COMMIT" ]; then COMMIT="$(git -C "$REPO" rev-parse HEAD)"; fi

TDIR="$BASE/copies/$TAG"
LDIR="$BASE/logs/$TAG"
mkdir -p "$TDIR" "$LDIR"

echo "[tier $TAG] commit=$COMMIT n=$N stagger=${STAGGER}s base=$BASE"

# ---- calm-window gate for heavy tiers (host discipline) ---------------------
if [ "$N" -ge 8 ]; then
  waited=0
  while true; do
    # int(): vm.loadavg prints locale comma decimals ("266,11"); a raw string
    # compare would evaluate lexicographically and always pass — coerce first.
    l1=$(sysctl -n vm.loadavg | sed 's/[{}]//g' | awk '{print int($1)}')
    ok=$(awk -v a="$l1" -v b="$MAXLOAD" 'BEGIN{print (a<b)?1:0}')
    [ "$ok" = "1" ] && break
    if [ "$waited" -ge "$WAIT_TIMEOUT_S" ]; then
      echo "[tier $TAG] WARNING: no calm window after ${WAIT_TIMEOUT_S}s (1-min load $l1 >= $MAXLOAD); launching WITH disclosure" | tee "$LDIR/calm-window-miss.txt"
      break
    fi
    echo "[tier $TAG] 1-min load $l1 >= $MAXLOAD; waiting (${waited}s/${WAIT_TIMEOUT_S}s)"
    sleep 60; waited=$((waited+60))
  done
  echo "[tier $TAG] launch gate passed: 1-min load $(sysctl -n vm.loadavg)"
fi
sysctl -n vm.loadavg > "$LDIR/launch-load.txt"

# ---- source snapshot integrity anchor ---------------------------------------
if [ ! -f "$BASE/archive-$COMMIT.sha256" ]; then
  git -C "$REPO" archive "$COMMIT" | shasum -a 256 | awk '{print $1}' > "$BASE/archive-$COMMIT.sha256"
fi
ANCHOR="$(cat "$BASE/archive-$COMMIT.sha256")"
echo "[tier $TAG] archive sha256=$ANCHOR" | tee "$LDIR/tier-manifest.txt"
{ echo "commit=$COMMIT"; echo "archive_sha256=$ANCHOR"; echo "n=$N"; echo "stagger_s=$STAGGER"; echo "date_utc=$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)"; } >> "$LDIR/tier-manifest.txt"

# ---- fresh disposable copies -------------------------------------------------
for i in $(seq 1 "$N"); do
  c=$(printf 'c%02d' "$i")
  if [ -d "$TDIR/$c" ]; then echo "refusing: copy $TDIR/$c already exists (never reuse copies)" >&2; exit 65; fi
  mkdir -p "$TDIR/$c"
  git -C "$REPO" archive "$COMMIT" | tar -x -C "$TDIR/$c" || exit 66
  # m2 is untracked (not in the archive); its parent dirs may also be absent
  mkdir -p "$TDIR/$c/artifacts/tools"
  cp -R "$REPO/artifacts/tools/m2" "$TDIR/$c/artifacts/tools/m2" || exit 66
done

# ---- shared-state write probe marker -----------------------------------------
MARKER="$LDIR/shared-probe-marker"
TZ=UTC date +%Y-%m-%dT%H:%M:%SZ > "$MARKER"
sleep 1

# ---- host sampler, 30 s cadence ------------------------------------------------
(
  while true; do
    ts=$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)
    load=$(uptime | sed 's/.*load averages: //')
    # macOS ps comm= yields full paths (/usr/bin/java, .../bin/java): match the
    # basename suffix only (RAI-224 sampler protocol)
    njvm=$(ps -eo comm= | grep -c 'java$')
    rss=$(ps -eo rss=,comm= | awk '$2 ~ /java$/ {s+=$1} END {print int(s/1024)}')
    listen=$(lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | awk '$1=="java"{c++} END{print int(c)}')
    echo "$ts load=$load java_procs=$njvm java_rss_mb=$rss java_listen_sockets=$listen" >> "$LDIR/host-samples.log"
    sleep 30
  done
) &
SAMPLER_PID=$!
trap 'kill "$SAMPLER_PID" 2>/dev/null' EXIT

# ---- phase runner (bounded watchdog; kills only its own process group) --------
run_phase() { # run_phase <copy-dir> <phase-name>
  local copy="$1" phase="$2"
  local name; name="$(basename "$copy")"
  local out="$LDIR/$name"; mkdir -p "$out"
  export SBT_OPTS="-Xmx2G -XX:+ExitOnOutOfMemoryError"
  export JAVA_TOOL_OPTIONS="-Xmx2G -XX:+ExitOnOutOfMemoryError"
  cd "$copy" || return 97
  local log="$out/$phase.log"
  local start_epoch; start_epoch=$(date +%s)
  local start_iso; start_iso=$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)
  local argv
  case "$phase" in
    clean) argv="sbt --batch clean" ;;
    *)     argv="sbt --batch Test/compile" ;;
  esac
  ( eval "$argv" ) > "$log" 2>&1 &
  local pid=$!
  local status="completed"
  while kill -0 "$pid" 2>/dev/null; do
    if [ $(( $(date +%s) - start_epoch )) -ge "$HARDCAP_S" ]; then
      local pgid; pgid=$(ps -o pgid= -p "$pid" | tr -d ' ')
      kill -TERM "-$pgid" 2>/dev/null; kill -TERM "$pid" 2>/dev/null
      sleep 10
      kill -KILL "-$pgid" 2>/dev/null; kill -KILL "$pid" 2>/dev/null
      status="hardcap-kill-${HARDCAP_S}s"
      break
    fi
    sleep 5
  done
  wait "$pid" 2>/dev/null
  local code=$?
  local dur=$(( $(date +%s) - start_epoch ))
  local end_iso; end_iso=$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)
  printf '{"copy":"%s","tier":"%s","phase":"%s","status":"%s","exit":%d,"duration_s":%d,"start":"%s","end":"%s"}\n' \
    "$name" "$TAG" "$phase" "$status" "$code" "$dur" "$start_iso" "$end_iso" > "$out/$phase.result"
  # verbatim failure-signature capture (mechanical poisoning detector)
  if [ "$code" -ne 0 ] && [ "$phase" != "clean" ]; then
    grep -n "Not Found Error\|is not a member\|compileIncremental" "$log" > "$out/$phase.signature" 2>/dev/null
  fi
  return "$code"
}

launch_copies() { # launch_copies <phase> — bounded concurrent launches
  local phase="$1" i c
  local pids=""
  for i in $(seq 1 "$N"); do
    c=$(printf 'c%02d' "$i")
    run_phase "$TDIR/$c" "$phase" &
    pids="$pids $!"
    echo "$!" >> "$LDIR/phase-pids.txt"
    [ "$STAGGER" -gt 0 ] && sleep "$STAGGER"
  done
  # wait on the recorded phase PIDs only: a bare `wait` would also block on the
  # never-terminating host sampler subshell and hang the tier after phase C.
  # shellcheck disable=SC2086
  wait $pids
}

# ---- phase C: cold compile at tier concurrency ---------------------------------
echo "[tier $TAG] phase C (cold compile) launching: n=$N stagger=${STAGGER}s"
: > "$LDIR/phase-pids.txt"
c0=$(date +%s)
launch_copies cold
echo "[tier $TAG] phase C done in $(( $(date +%s) - c0 ))s"

# ---- phase R: incremental no-op recompile (corruption detector) ----------------
echo "[tier $TAG] phase R (recompile detector) launching"
r0=$(date +%s)
launch_copies recompile
echo "[tier $TAG] phase R done in $(( $(date +%s) - r0 ))s"

# ---- bounded auto-diagnostics for failed copies --------------------------------
for i in $(seq 1 "$N"); do
  c=$(printf 'c%02d' "$i"); out="$LDIR/$c"
  failed=""
  for ph in cold recompile; do
    if [ -f "$out/$ph.result" ] && ! grep -q '"exit":0' "$out/$ph.result"; then failed="$ph"; break; fi
  done
  [ -n "$failed" ] || continue
  echo "[tier $TAG] $c failed in phase $failed — running bounded diagnostics (retry, clean, heal)"
  if grep -q "is not a member of raider.core.ToolCall" "$out/$failed.log" 2>/dev/null; then
    echo "[tier $TAG] $c: POISONING SIGNATURE MATCH — preserving zinc state"
    # RAI-241 evidence: the poison carrier is raiderCore's persisted zinc state
    # (core-only clean heals), so preserve core's target alongside runtime's.
    tar -czf "$out/poisoned-zinc-state.tar.gz" -C "$TDIR/$c" modules/runtime/target modules/core/target 2>/dev/null
    sha256sum "$out/poisoned-zinc-state.tar.gz" 2>/dev/null || shasum -a 256 "$out/poisoned-zinc-state.tar.gz"
  fi
  run_phase "$TDIR/$c" retry
  run_phase "$TDIR/$c" clean
  run_phase "$TDIR/$c" heal
done

# ---- shared-state write probe check ---------------------------------------------
find "$HOME/.sbt" "$HOME/Library/Caches/Coursier" -type f -newer "$MARKER" 2>/dev/null > "$LDIR/shared-writes-during-tier.txt"
wc -l < "$LDIR/shared-writes-during-tier.txt" | xargs echo "[tier $TAG] shared-state files modified during tier:"

# ---- stop sampler + summary ------------------------------------------------------
kill "$SAMPLER_PID" 2>/dev/null
{
  echo "--- results [$TAG] ---"
  for i in $(seq 1 "$N"); do
    c=$(printf 'c%02d' "$i")
    cat "$LDIR/$c"/{cold,recompile}.result 2>/dev/null
  done
} | tee "$LDIR/summary.txt"
echo "[tier $TAG] COMPLETE"
