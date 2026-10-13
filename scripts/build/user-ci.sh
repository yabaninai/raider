#!/bin/sh
# Compile the local Scala CI application against this checkout's runtime.
set -eu
cd "$(dirname "$0")/../.."
if [ -n "${JAVA_HOME:-}" ]; then
  RAIDER_JAVA="$JAVA_HOME/bin/java"
else
  RAIDER_JAVA=$(command -v java)
fi
case "$("$RAIDER_JAVA" -version 2>&1)" in
  *'version "21.'*) ;;
  *) echo 'Set JAVA_HOME to JDK 21 before building the CI example.' >&2; exit 21 ;;
esac
export JAVACMD="$RAIDER_JAVA"
export COURSIER_CACHE="${COURSIER_CACHE:-/tmp/cc-master}"
OUT=artifacts/user-ci
mkdir -p "$OUT/classes"
sbt --batch --error compile 'export root/runtime:fullClasspath' > "$OUT/build.log" 2>&1
tail -n 1 "$OUT/build.log" > "$OUT/runtime-classpath.txt"
RAIDER_RUNTIME_CP=$(cat "$OUT/runtime-classpath.txt")
[ -n "$RAIDER_RUNTIME_CP" ] || { echo 'Runtime classpath is empty.' >&2; exit 21; }
"$RAIDER_JAVA" -cp "$RAIDER_RUNTIME_CP" dotty.tools.dotc.Main \
  -classpath "$RAIDER_RUNTIME_CP" -Werror -d "$OUT/classes" \
  examples/user-guide/ci/BillingCi.scala
printf '%s:%s\n' "$(pwd)/$OUT/classes" "$RAIDER_RUNTIME_CP" > "$OUT/classpath.txt"
printf '%s\n' 'CI example compiled. Classpath: artifacts/user-ci/classpath.txt'
