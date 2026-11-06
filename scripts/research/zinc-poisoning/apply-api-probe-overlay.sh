#!/bin/bash
# RAI-249: apply the ApiProbe instrumentation overlay to a disposable
# experiment copy. NEVER used inside the product build or worktrees.
#
# usage: apply-api-probe-overlay.sh <copy-dir>
#   copies ApiProbe.scala + RaiderApiProbePlugin.scala into <copy>/project/
#   and records the overlay fingerprints under <copy>/.api-probe-overlay.sha256
set -eu

COPY="${1:-}"
OVERLAY_DIR="$(cd "$(dirname "$0")" && pwd)/api-probe-overlay"
[ -n "$COPY" ] && [ -d "$COPY" ] || { echo "usage: apply-api-probe-overlay.sh <copy-dir>" >&2; exit 64; }
[ -d "$OVERLAY_DIR" ] || { echo "overlay dir missing: $OVERLAY_DIR" >&2; exit 65; }
# RAI-249 review fixup: never touch anything that belongs to a git checkout
# or a linked worktree (the latter has .git as a file, hence -e not -d).
[ ! -e "$COPY/.git" ] || { echo "refusing: $COPY contains a .git entry (git checkout or linked worktree) — overlay targets disposable git-archive experiment copies only" >&2; exit 65; }

mkdir -p "$COPY/project" "$COPY/.api-probe"
cp "$OVERLAY_DIR/ApiProbe.scala" "$COPY/project/ApiProbe.scala"
cp "$OVERLAY_DIR/RaiderApiProbePlugin.scala" "$COPY/project/RaiderApiProbePlugin.scala"

{
  echo "overlay source: $OVERLAY_DIR"
  for f in ApiProbe.scala RaiderApiProbePlugin.scala; do
    printf '%s  %s\n' "$(shasum -a 256 "$OVERLAY_DIR/$f" | awk '{print $1}')" "$f"
  done
  printf 'applied_utc=%s\n' "$(TZ=UTC date +%Y-%m-%dT%H:%M:%SZ)"
} > "$COPY/.api-probe-overlay.sha256"

echo "[overlay] applied ApiProbe instrumentation to $COPY"
