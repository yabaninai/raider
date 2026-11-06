#!/bin/bash
# RAI-249 instrumented zinc induction experiment: execute one measurement tier
# with the ApiProbe instrumentation applied to every copy.
#
# usage: run-zinc-instrumented-tier.sh <tier-tag> <n-copies> [stagger-seconds]
#
# Derivative of RAI-241's run-zinc-tier.sh (same safety envelope), extended for
# the induction-site localization question:
#   - every copy gets the ApiProbe overlay (project/ sources, disposable tree
#     only) — a read-only member-level dump of the PERSISTED zinc API analysis
#     plus emitted-artifact hashes, recorded after every compile phase;
#   - emitted-side member check (javap, outside the sbt JVM) for
#     raider.core.tool$ToolCall#id after every phase;
#   - on a poisoning-signature failure, the zinc state AND the probe dumps are
#     preserved BEFORE any mutating diagnostic (retry/clean/heal chain).
#
# Safety properties (unchanged from RAI-241):
#   - at most 8 concurrent sbt builds (hard cap, refused above);
#   - per-phase watchdog HARDCAP_S (own process group only);
#   - every JVM capped at -Xmx2G -XX:+ExitOnOutOfMemoryError;
#   - heavy tiers (>=8 copies) wait up to WAIT_TIMEOUT_S for a calm window
#     (1-min load < MAXLOAD) before launching;
#   - shared-state write probe (~/.sbt + coursier cache) per tier;
#   - cleanup via the existing zinc-cleanup.sh <base> (own-JVM-only rule).
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
BASE="${RAI249_BASE:-/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-249-zinc}"
COMMIT="${RAI249_COMMIT:-}"
HARDCAP_S=1800
MAXLOAD=8
WAIT_TIMEOUT_S=1800
MAX_CONCURRENT=8

TAG="${1:-}"; N="${2:-}"; STAGGER="${3:-0}"
[ -n "$TAG" ] && [ -n "$N" ] || { echo "usage: run-zinc-instrumented-tier.sh <tier-tag> <n-copies> [stagger-seconds]" >&2; exit 64; }
[ "$N" -ge 1 ] 2>/dev/null || { echo "n-copies must be >= 1" >&2; exit 64; }
[ "$N" -le "$MAX_CONCURRENT" ] || { echo "refusing: n-copies $N exceeds burner cap $MAX_CONCURRENT" >&2; exit 64; }
[ -n "$REPO" ] || { echo "cannot locate repo root" >&2; exit 65; }
if [ -z "$COMMIT" ]; then COMMIT="$(git -C "$REPO" rev-parse HEAD)"; fi

# m2 snapshot source: prefer this checkout, fall back to the main checkout
# (a lane worktree may not carry artifacts/).
M2_SRC="${RAI249_M2_SRC:-$REPO/artifacts/tools/m2}"
if [ ! -d "$M2_SRC" ]; then
  MAIN_CANDIDATE="$(dirname "$(dirname "$REPO")")"
  if [ -d "$MAIN_CANDIDATE/artifacts/tools/m2" ]; then M2_SRC="$MAIN_CANDIDATE/artifacts/tools/m2"; fi
fi

TDIR="$BASE/copies/$TAG"
LDIR="$BASE/logs/$TAG"
mkdir -p "$TDIR" "$LDIR"

echo "[tier $TAG] commit=$COMMIT n=$N stagger=${STAGGER}s base=$BASE"

# ---- calm-window gate for heavy tiers (host discipline) ---------------------
if [ "$N" -ge 8 ]; then
  waited=0
  while true; do
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
{ echo "commit=$COMMIT"; echo "archive_sha256=$ANCHOR"; echo "n=$N"; echo "stagger_s=$STAGGER"; echo "overlay=$(shasum -a 256 "$SCRIPT_DIR/apply-api-probe-overlay.sh" | awk '{print $1}')"; echo "date_utc=$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)"; } >> "$LDIR/tier-manifest.txt"

# ---- fresh disposable copies + instrumentation overlay ------------------------
for i in $(seq 1 "$N"); do
  c=$(printf 'c%02d' "$i")
  if [ -d "$TDIR/$c" ]; then echo "refusing: copy $TDIR/$c already exists (never reuse copies)" >&2; exit 65; fi
  mkdir -p "$TDIR/$c"
  git -C "$REPO" archive "$COMMIT" | tar -x -C "$TDIR/$c" || exit 66
  mkdir -p "$TDIR/$c/artifacts/tools"
  [ -d "$M2_SRC" ] || { echo "m2 snapshot source not found (tried \$RAI249_M2_SRC, $REPO/artifacts/tools/m2)" >&2; exit 66; }
  cp -R "$M2_SRC" "$TDIR/$c/artifacts/tools/m2" || exit 66
  "$SCRIPT_DIR/apply-api-probe-overlay.sh" "$TDIR/$c" || exit 66
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
    clean)         argv="sbt --batch clean" ;;
    probe*)        argv="sbt --batch apiProbeDump" ;;
    *)             argv="sbt --batch Test/compile" ;;
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
  if [ "$code" -ne 0 ] && [ "$phase" != "clean" ]; then
    grep -n "Not Found Error\|is not a member\|compileIncremental" "$log" > "$out/$phase.signature" 2>/dev/null
  fi
  return "$code"
}

# ---- emitted-side member check (javap, outside the sbt JVM) --------------------
# The "emitted TASTy" side of the at-write-time comparison: a class file whose
# bytes decode (via javap) to a public `id()` member is emitted-with-id.
emitted_check() { # emitted_check <copy-dir> <phase-name>
  local copy="$1" phase="$2"
  local name; name="$(basename "$copy")"
  local out="$LDIR/$name"; mkdir -p "$out"
  local chk="$out/$phase.emitted-check.txt"
  local classes; classes=$(ls -d "$copy"/modules/core/target/scala-*/classes 2>/dev/null | head -1)
  {
    echo "phase=$phase copy=$name ts=$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)"
    if [ -z "$classes" ]; then
      echo "classes-dir=ABSENT"
    else
      echo "classes-dir=$classes"
      local f
      for f in "$classes/raider/core/tool.tasty" "$classes/raider/core/tool\$ToolCall.class"; do
        if [ -f "$f" ]; then
          echo "artifact $(basename "$f") sha256=$(shasum -a 256 "$f" | awk '{print $1}') size=$(wc -c < "$f" | tr -d ' ')"
        else
          echo "artifact $(basename "$f") ABSENT"
        fi
      done
      if [ -f "$classes/raider/core/tool\$ToolCall.class" ]; then
        if JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/javap -p -cp "$classes" 'raider.core.tool$ToolCall' 2>&1 | grep -q ' id()'; then
          echo "javap-member id()=PRESENT"
        else
          echo "javap-member id()=ABSENT"
        fi
      else
        echo "javap-member skipped (no class file)"
      fi
    fi
  } > "$chk"
}

# ---- probe phase wrapper: ApiProbe dump + emitted check -------------------------
run_probe() { # run_probe <copy-dir> <after-phase>
  local copy="$1" after="$2"
  local name; name="$(basename "$copy")"
  local out="$LDIR/$name"
  run_phase "$copy" "probe-$after" || true
  emitted_check "$copy" "probe-$after"
  # copy the probe verdict next to the logs for grep-ability
  if [ -f "$copy/.api-probe/latest-verdict.txt" ]; then
    cp "$copy/.api-probe/latest-verdict.txt" "$out/probe-$after.verdict"
  fi
}

# ---- preserve poisoned state + probes BEFORE any mutating diagnostic -----------
preserve_poison() { # preserve_poison <copy-dir> <phase>
  local copy="$1" failed="$2"
  local name; name="$(basename "$copy")"
  local out="$LDIR/$name"
  echo "[tier $TAG] $name: POISONING SIGNATURE MATCH in $failed — preserving zinc state + probes"
  tar -czf "$out/poisoned-zinc-state.tar.gz" -C "$copy" modules/runtime/target modules/core/target .api-probe .api-probe-overlay.sha256 2>/dev/null
  shasum -a 256 "$out/poisoned-zinc-state.tar.gz" | tee "$out/poisoned-zinc-state.sha256"
}

launch_copies() { # launch_copies <phase>
  local phase="$1" i c
  local pids=""
  for i in $(seq 1 "$N"); do
    c=$(printf 'c%02d' "$i")
    run_phase "$TDIR/$c" "$phase" &
    pids="$pids $!"
    echo "$!" >> "$LDIR/phase-pids.txt"
    [ "$STAGGER" -gt 0 ] && sleep "$STAGGER"
  done
  # shellcheck disable=SC2086
  wait $pids
}

# ---- phase C: cold compile at tier concurrency ---------------------------------
echo "[tier $TAG] phase C (cold compile) launching: n=$N stagger=${STAGGER}s"
: > "$LDIR/phase-pids.txt"
c0=$(date +%s)
launch_copies cold
echo "[tier $TAG] phase C done in $(( $(date +%s) - c0 ))s"

# ---- per-copy probes after cold (sequential; read-only, cheap) ------------------
for i in $(seq 1 "$N"); do
  c=$(printf 'c%02d' "$i"); run_probe "$TDIR/$c" cold
done

# ---- phase R: incremental no-op recompile (corruption detector) ----------------
echo "[tier $TAG] phase R (recompile detector) launching"
r0=$(date +%s)
launch_copies recompile
echo "[tier $TAG] phase R done in $(( $(date +%s) - r0 ))s"

for i in $(seq 1 "$N"); do
  c=$(printf 'c%02d' "$i"); run_probe "$TDIR/$c" recompile
done

# ---- bounded auto-diagnostics for failed copies --------------------------------
for i in $(seq 1 "$N"); do
  c=$(printf 'c%02d' "$i"); copy="$TDIR/$c"; out="$LDIR/$c"
  failed=""
  for ph in cold recompile; do
    if [ -f "$out/$ph.result" ] && ! grep -q '"exit":0' "$out/$ph.result"; then failed="$ph"; break; fi
  done
  [ -n "$failed" ] || continue
  echo "[tier $TAG] $c failed in phase $failed — running bounded diagnostics (retry, clean, heal)"
  if grep -q "is not a member of raider.core.ToolCall" "$out/$failed.log" 2>/dev/null; then
    preserve_poison "$copy" "$failed"
  fi
  run_phase "$copy" retry
  run_probe "$copy" retry
  run_phase "$copy" clean
  run_phase "$copy" heal
  run_probe "$copy" heal
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
    cat "$LDIR/$c"/cold.result "$LDIR/$c"/recompile.result 2>/dev/null
    for pr in cold recompile retry heal; do
      [ -f "$LDIR/$c/probe-$pr.verdict" ] && { echo "[$c probe-$pr]"; grep -E "TOOLCALL|verdict=" "$LDIR/$c/probe-$pr.verdict" | sed 's/^/  /'; }
      [ -f "$LDIR/$c/probe-$pr.emitted-check.txt" ] && { echo "[$c probe-$pr emitted]"; grep -E "javap-member|artifact " "$LDIR/$c/probe-$pr.emitted-check.txt" | sed 's/^/  /'; }
    done
  done
} | tee "$LDIR/summary.txt"
echo "[tier $TAG] COMPLETE"
