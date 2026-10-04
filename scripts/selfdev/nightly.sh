#!/bin/sh
# Raider Nightly Improvement Runner
# Runs the master prompt through multiple opencode sessions in parallel.
# Each session works on a different phase to maximize throughput.
#
# Usage:
#   sh scripts/selfdev/nightly.sh          # Run all phases
#   sh scripts/selfdev/nightly.sh 1 2      # Run only phases 1 and 2
#
# Prerequisites:
#   - llama.cpp on 127.0.0.1:8081 (for live testing)
#   - COURSIER_CACHE=/tmp/cc-master (for sbt)
#   - opencode installed (`which opencode`)
set -u
cd "$(dirname "$0")/../.."

PROMPT_FILE=docs/prompts/MASTER-NIGHTLY.md
[ -f "$PROMPT_FILE" ] || { echo "nightly.sh: $PROMPT_FILE not found" >&2; exit 21; }

PHASES="${*:-1 2 3 4 5 6}"

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Raider Nightly Improvement Runner                   ║"
echo "║  Phases: $PHASES                                       ║"
echo "║  Prompt: $PROMPT_FILE                                 ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# Create separate git worktrees for parallel work (no conflicts)
WORKTREE_BASE=/tmp/raider-nightly
mkdir -p "$WORKTREE_BASE"

PHASE_NAMES="quality-infrastructure core-improvements repl-live error-recovery packaging-ci documentation"
PHASE_NUM=1
for phase in $PHASES; do
  NAME=$(echo $PHASE_NAMES | cut -d' ' -f$phase)
  DIR="$WORKTREE_BASE/phase-$phase-$NAME"

  if [ -d "$DIR" ]; then
    echo "[phase-$phase] $DIR already exists — skipping setup"
  else
    echo "[phase-$phase] Creating worktree: $DIR"
    git worktree add "$DIR" -b "nightly/phase-$phase" 2>/dev/null || {
      echo "[phase-$phase] WARNING: git worktree failed, using copy"
      cp -r . "$DIR" 2>/dev/null
      rm -rf "$DIR/.git"
    }
  fi
  echo ""
done

echo "═══════════════════════════════════════════════════════"
echo "  Worktrees created. Now run opencode in each:"
echo ""
for phase in $PHASES; do
  NAME=$(echo $PHASE_NAMES | cut -d' ' -f$phase)
  DIR="$WORKTREE_BASE/phase-$phase-$NAME"
  echo "  # Terminal $phase:"
  echo "  cd $DIR && opencode"
  echo ""
done

echo "  Then paste this prompt into each opencode session:"
echo "  ┌─────────────────────────────────────────────────────┐"
echo "  │ Read the file docs/prompts/MASTER-NIGHTLY.md        │"
echo "  │ and execute ONLY Phase $PHASES tasks.               │"
echo "  │ Follow all rules and verification steps exactly.    │"
echo "  │ After completing all tasks, commit your changes.    │"
echo "  └─────────────────────────────────────────────────────┘"
echo ""
echo "  When all sessions complete, merge the branches:"
echo "  for phase in $PHASES; do"
echo "    git merge nightly/phase-\$phase --no-edit"
echo "  done"
echo "═══════════════════════════════════════════════════════"
