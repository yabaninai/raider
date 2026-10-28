#!/usr/bin/env bash
# RAI-205 cross-ecosystem acceptance: shared helpers for the scenario drivers.
#
# Runs from the worktree root. Everything is offline: the raider-cli.jar
# candidate, the pinned dependency closures under artifacts/tools/m2, and the
# scripted OpenAI-compatible stand (no network, fixed key sk-stand-205).
set -euo pipefail

ROOT="${ROOT:-$PWD}"
M2="$ROOT/artifacts/tools/m2"
JAR="$ROOT/modules/cli/target/scala-3.9.0/raider-cli.jar"
FIX="$ROOT/contracts/acceptance/cross-ecosystem"
XE="$ROOT/artifacts/cross-ecosystem"
OUT="${OUT:-$ROOT/artifacts/xe-out}"
WS="${WS:-$ROOT/artifacts/xe-ws}"
STAND_KEY="sk-stand-205"

# -- pinned compile toolchain (Scala 3.9.0 dotc, same jars as the lane compile)
DOTC_CP="$M2/org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar:$M2/org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar:$M2/org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar:$M2/org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar:$M2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar:$M2/org/scala-lang/modules/scala-collection-compat_3/2.14.0/scala-collection-compat_3-2.14.0.jar:$M2/org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar:$M2/org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"

# -- pinned runtime closures (see contracts/acceptance/cross-ecosystem/consumer-deps.json)
SCALA_LIB="$M2/org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
UPICKLE_CP="$M2/com/lihaoyi/upickle_3/4.4.3/upickle_3-4.4.3.jar:$M2/com/lihaoyi/upickle-implicits_3/4.4.3/upickle-implicits_3-4.4.3.jar:$M2/com/lihaoyi/upickle-core_3/4.4.3/upickle-core_3-4.4.3.jar:$M2/com/lihaoyi/ujson_3/4.4.3/ujson_3-4.4.3.jar:$M2/com/lihaoyi/upack_3/4.4.3/upack_3-4.4.3.jar:$M2/com/lihaoyi/geny_3/1.1.1/geny_3-1.1.1.jar"
LLM4S_CP="$SCALA_LIB:$M2/org/llm4s/llm4s-core_3/0.5.1/llm4s-core_3-0.5.1.jar:$M2/org/llm4s/llm4s-media_3/0.5.1/llm4s-media_3-0.5.1.jar:$M2/com/lihaoyi/cats-core_3/2.13.0/cats-core_3-2.13.0.jar:$M2/com/lihaoyi/cats-kernel_3/2.13.0/cats-kernel_3-2.13.0.jar:$UPICKLE_CP"
L4J_DEPS="$M2/dev/langchain4j/langchain4j-core/1.22.0/langchain4j-core-1.22.0.jar:$M2/com/fasterxml/jackson/core/jackson-databind/2.22.1/jackson-databind-2.22.1.jar:$M2/com/fasterxml/jackson/core/jackson-core/2.22.1/jackson-core-2.22.1.jar:$M2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar:$M2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"
L4J_CP="$XE/classes-l4j:$L4J_DEPS"

declare -a STAND_PIDS=()

# -- stand stop policy (RAI-229): every registered stand pid gets SIGTERM, a
# bounded wait, then a bounded SIGKILL escalation. A stand that survives both
# is an honest FAILURE of stop_stands (nonzero rc + diagnostic), never a
# masked `|| true`. Bounds are whole seconds, overridable for drills.
XE_STOP_TERM_TIMEOUT="${XE_STOP_TERM_TIMEOUT:-10}"
XE_STOP_KILL_TIMEOUT="${XE_STOP_KILL_TIMEOUT:-5}"

log() { printf '[xe-driver] %s\n' "$*" >&2; }

compile_all() {
  log "compiling stand, consumers, checker"
  mkdir -p "$XE/classes" "$XE/classes-llm4s" "$XE/classes-l4j" "$XE/classes-checker"
  java -cp "$DOTC_CP" dotty.tools.dotc.Main -d "$XE/classes" -classpath "$SCALA_LIB" \
    "$FIX/stand/Stand.scala" 2>&1 | grep -v '^Compiling' || true
  java -cp "$DOTC_CP" dotty.tools.dotc.Main -d "$XE/classes-llm4s" -classpath "$LLM4S_CP" \
    "$FIX/llm4s-consumer/XeLlm4sConsumer.scala" 2>&1 | grep -v '^Compiling' || true
  javac -d "$XE/classes-l4j" -classpath "$L4J_DEPS" \
    "$FIX/langchain4j-consumer/XeLangChain4jConsumer.java" 2>&1 | grep -v 'warning' || true
  java -cp "$DOTC_CP" dotty.tools.dotc.Main -d "$XE/classes-checker" -classpath "$SCALA_LIB:$UPICKLE_CP" \
    "$FIX/checker/IndependentChecker.scala" 2>&1 | grep -v '^Compiling' || true
  for d in classes classes-llm4s classes-l4j classes-checker; do
    [ -n "$(ls -A "$XE/$d" 2>/dev/null)" ] || { log "FATAL: $d did not compile"; exit 2; }
  done
}

# start_stand <name> <extra args...> ; prints the port, registers the pid.
# Registration is DURABLE: the pid is written to $OUT/stand-<name>.pid because
# start_stand usually runs inside a command substitution subshell, so the
# in-memory STAND_PIDS array does not reach the driver shell. stop_stands
# reads the pidfiles; a missing/malformed pidfile is diagnosed, not silently
# passed (RAI-229).
start_stand() {
  local name="$1"; shift
  local sout="$OUT/stand-$name.out" serr="$OUT/stand-$name.err" slog="$OUT/stand-$name.log"
  mkdir -p "$OUT"
  : > "$slog"
  java -cp "$XE/classes:$SCALA_LIB" Stand --key "$STAND_KEY" --log "$slog" "$@" > "$sout" 2> "$serr" &
  local pid=$!
  STAND_PIDS+=("$pid")
  printf '%s\n' "$pid" > "$OUT/stand-$name.pid"
  local port=""
  for _ in $(seq 1 100); do
    port=$(grep -o 'PORT=[0-9]*' "$sout" 2>/dev/null | head -1 | cut -d= -f2 || true)
    [ -n "$port" ] && break
    kill -0 "$pid" 2>/dev/null || { stop_stands || true; log "FATAL: stand $name died"; cat "$serr"; exit 2; }
    sleep 0.2
  done
  [ -n "$port" ] || { stop_stands || true; log "FATAL: stand $name did not report PORT"; exit 2; }
  log "stand $name pid=$pid port=$port"
  printf '%s' "$port"
}

# xe_stop_alive <pid> : rc 0 when the pid is a live (non-zombie) process.
# kill -0 alone also succeeds for zombies (exited children awaiting reap),
# so the ps state is checked too.
xe_stop_alive() {
  local stat
  kill -0 "$1" 2>/dev/null || return 1
  stat="$(ps -p "$1" -o stat= 2>/dev/null || true)"
  case "$stat" in
    Z*|"") return 1 ;;
    *) return 0 ;;
  esac
}

# xe_stop_wait_exit <pid> <whole-second-bound> : poll until the pid is gone
# or the bound is exhausted; rc 0 = exited within the bound.
xe_stop_wait_exit() {
  local i=0 max="$(($2 * 10))"
  while xe_stop_alive "$1"; do
    [ "$i" -ge "$max" ] && return 1
    sleep 0.1
    i=$((i + 1))
  done
  return 0
}

# xe_reap <pid> : reap the exited stand if it is a child of this shell
# (drops the zombie); a non-child pid just yields wait's 127, ignored.
xe_reap() { wait "$1" 2>/dev/null || true; }

stop_one_stand() { # <pid>
  local pid="$1" cmd
  case "$pid" in
    ''|*[!0-9]*)
      log "ERROR: stop_stands: invalid stand pid entry '$pid' (stand was not registered correctly)"
      return 1 ;;
  esac
  if ! xe_stop_alive "$pid"; then
    log "stand pid=$pid already exited"
    xe_reap "$pid"
    return 0
  fi
  # Identity guard: only signal a process that is actually a registered Stand.
  # Protects against signaling a recycled pid (e.g. another checkout's process).
  cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  case "$cmd" in
    *Stand*) : ;;
    *)
      log "ERROR: stop_stands: pid=$pid is not a Stand process (cmd: '${cmd:-unavailable}'); refusing to signal a foreign process"
      return 1 ;;
  esac
  log "stopping stand pid=$pid (SIGTERM, wait bound ${XE_STOP_TERM_TIMEOUT}s)"
  kill -TERM "$pid" 2>/dev/null || true
  if xe_stop_wait_exit "$pid" "$XE_STOP_TERM_TIMEOUT"; then
    xe_reap "$pid"
    log "stand pid=$pid exited after SIGTERM"
    return 0
  fi
  log "WARN: stand pid=$pid still alive ${XE_STOP_TERM_TIMEOUT}s after SIGTERM; escalating to SIGKILL (wait bound ${XE_STOP_KILL_TIMEOUT}s)"
  kill -KILL "$pid" 2>/dev/null || true
  if xe_stop_wait_exit "$pid" "$XE_STOP_KILL_TIMEOUT"; then
    xe_reap "$pid"
    log "stand pid=$pid killed via SIGKILL escalation"
    return 0
  fi
  log "ERROR: stop_stands: stand pid=$pid SURVIVED SIGKILL after ${XE_STOP_KILL_TIMEOUT}s; manual cleanup required"
  return 1
}

stop_stands() {
  local rc=0 pid count=0 pidfile
  # Authoritative source: pidfiles written by start_stand (durable across the
  # command-substitution subshell boundary). Pidfile scope is this checkout's
  # $OUT, so concurrent runs in separate checkouts never cross-kill; the
  # identity guard in stop_one_stand additionally refuses recycled pids.
  for pidfile in "$OUT"/stand-*.pid; do
    [ -e "$pidfile" ] || continue
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    case "$pid" in
      ''|*[!0-9]*)
        log "ERROR: stop_stands: malformed pidfile $pidfile (content: '${pid:-<empty>}'); diagnosing, not silently passing"
        rc=1
        continue ;;
    esac
    count=$((count + 1))
    if stop_one_stand "$pid"; then
      rm -f "$pidfile"
    else
      log "ERROR: stop_stands: pidfile $pidfile retained for post-mortem (stand pid=$pid not cleanly stopped)"
      rc=1
    fi
  done
  # In-memory entries (direct registration, e.g. drills) are also honored;
  # stop_one_stand is idempotent for pids already stopped via pidfiles.
  if [ "${#STAND_PIDS[@]}" -gt 0 ]; then
    for pid in "${STAND_PIDS[@]}"; do
      case "$pid" in
        ''|*[!0-9]*)
          log "ERROR: stop_stands: invalid stand pid entry '$pid' (stand was not registered correctly)"
          rc=1
          continue ;;
      esac
      count=$((count + 1))
      stop_one_stand "$pid" || rc=1
    done
  fi
  STAND_PIDS=()
  # Review finding 1 (RAI-229 fixup): the rc of diagnosed-but-unstoppable
  # registrations (malformed pidfile / invalid pid entry with count==0) must
  # not be swallowed by the "nothing to stop" early return; return "$rc"
  # unconditionally and treat the notice as a success-only message.
  if [ "$rc" -eq 0 ] && [ "$count" -eq 0 ]; then
    log "stop_stands: no stand pidfiles or registered pids found (nothing to stop)"
  fi
  if [ "$rc" -ne 0 ]; then
    log "ERROR: stop_stands: processed $count stand pid(s), at least one did not terminate cleanly; failing honestly"
  fi
  return "$rc"
}

run_llm4s() { # <manifest> <scenarios> [stand-port]
  local manifest="$1" scenarios="$2" port="${3:-}"
  local args=(--adapter-jar "$JAR" --ws-root "$WS" --out-root "$OUT"
              --manifest "$manifest" --scenario "$scenarios")
  if [ -n "$port" ]; then
    args+=(--stand-url "http://127.0.0.1:$port/v1" --key "$STAND_KEY")
  fi
  java -cp "$XE/classes-llm4s:$LLM4S_CP" xe.llm4s.XeLlm4sConsumer "${args[@]}"
}

run_l4j() { # <manifest> <scenarios> [stand-port]
  local manifest="$1" scenarios="$2" port="${3:-}"
  local args=(--adapter-jar "$JAR" --ws-root "$WS" --out-root "$OUT"
              --manifest "$manifest" --scenario "$scenarios")
  if [ -n "$port" ]; then
    args+=(--stand-url "http://127.0.0.1:$port/v1" --key "$STAND_KEY")
  fi
  java -cp "$L4J_CP" xe.langchain4j.XeLangChain4jConsumer "${args[@]}" 2> "$OUT/langchain4j-sl4j-warn.txt"
}

run_checker() { # <report path>
  java -cp "$XE/classes-checker:$SCALA_LIB:$UPICKLE_CP" xe.checker.IndependentChecker \
    --goldens "$FIX/expected/golden-checks.json" \
    --manifests "$OUT/manifest-llm4s.json,$OUT/manifest-llm4s-standA.json,$OUT/manifest-llm4s-standB.json,$OUT/manifest-l4j-mock.json,$OUT/manifest-l4j-standA.json,$OUT/manifest-l4j-standB.json" \
    --adapter-jar "$JAR" \
    --out-root "$OUT" --ws-root "$WS" \
    --stand-logs "$OUT/stand-A.log,$OUT/stand-B.log" \
    --report "$1"
}
