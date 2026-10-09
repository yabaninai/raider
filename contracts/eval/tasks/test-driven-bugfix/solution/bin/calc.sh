#!/bin/sh
# calc.sh — tiny integer calculator used by the bugfix eval fixture.
# Commands: abs N | max N N ... | median N N ...
set -u

is_int() {
  case "$1" in
    '') return 1 ;;
    -[0-9]*)
      case "${1#-}" in *[!0-9]*) return 1 ;; esac
      return 0 ;;
    [0-9]*) return 0 ;;
    *) return 1 ;;
  esac
}

die() { echo "error: $1" >&2; exit 2; }

cmd=${1:-}
[ -n "$cmd" ] || die "usage: calc.sh <abs|max|median> <ints...>"
shift

case "$cmd" in
  abs)
    [ $# -eq 1 ] || die "abs takes exactly one integer"
    is_int "$1" || die "not an integer: $1"
    n=$1
    if [ "$n" -lt 0 ]; then n=$(( -n )); fi
    echo "$n"
    ;;
  max)
    [ $# -ge 2 ] || die "max takes at least two integers"
    best=""
    for n in "$@"; do
      is_int "$n" || die "not an integer: $n"
      if [ -z "$best" ] || [ "$n" -gt "$best" ]; then best=$n; fi
    done
    echo "$best"
    ;;
  median)
    [ $# -ge 1 ] || die "median takes at least one integer"
    vals=""
    for n in "$@"; do
      is_int "$n" || die "not an integer: $n"
      vals="$vals $n"
    done
    sorted=$(printf '%s\n' $vals | sort -n)
    count=$#
    # Lower middle for even counts: element at 0-based index (count-1)/2.
    mid=$(( (count - 1) / 2 ))
    printf '%s\n' "$sorted" | sed -n "$(( mid + 1 ))p"
    ;;
  *)
    die "unknown command: $cmd"
    ;;
esac
