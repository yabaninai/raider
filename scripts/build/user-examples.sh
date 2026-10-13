#!/bin/sh
# Build documented examples against this checkout, without adding build projects.
set -eu
cd "$(dirname "$0")/../.."

if [ -n "${JAVA_HOME:-}" ]; then
  RAIDER_JAVA="$JAVA_HOME/bin/java"
else
  RAIDER_JAVA=$(command -v java)
fi
RAIDER_JAVA_VERSION=$("$RAIDER_JAVA" -version 2>&1)
case "$RAIDER_JAVA_VERSION" in
  *'version "21.'*) ;;
  *) echo 'Examples require JDK 21; set JAVA_HOME to your JDK 21 installation.' >&2; exit 21 ;;
esac
# sbt launchers can otherwise select a different JVM from the shell's java.
export JAVACMD="$RAIDER_JAVA"
export COURSIER_CACHE="${COURSIER_CACHE:-/tmp/cc-master}"
OUT=artifacts/user-examples
rm -rf "$OUT/bundle-classes" "$OUT/stand-classes" "$OUT/audit-stand-classes"
mkdir -p "$OUT/bundle-classes" "$OUT/stand-classes" "$OUT/audit-stand-classes"
sbt --batch --error compile 'export root/runtime:fullClasspath' > "$OUT/build.log" 2>&1
tail -n 1 "$OUT/build.log" > "$OUT/classpath.txt"
RAIDER_CP=$(cat "$OUT/classpath.txt")
[ -n "$RAIDER_CP" ] || { echo 'Empty runtime classpath' >&2; exit 21; }
"$RAIDER_JAVA" -cp "$RAIDER_CP" dotty.tools.dotc.Main \
  -classpath "$RAIDER_CP" -Werror -d "$OUT/bundle-classes" \
  examples/user-guide/bundle/TaskSummary.scala
"$RAIDER_JAVA" -cp "$RAIDER_CP" dotty.tools.dotc.Main \
  -classpath "$RAIDER_CP" -Werror -d "$OUT/stand-classes" \
  examples/user-guide/stand/CodingStand.scala
"$RAIDER_JAVA" -cp "$RAIDER_CP" dotty.tools.dotc.Main \
  -classpath "$RAIDER_CP" -Werror -d "$OUT/audit-stand-classes" \
  examples/user-guide/stand/AuditStand.scala
jar --create --file "$OUT/task-summary.jar" \
  -C "$OUT/bundle-classes" . -C examples/user-guide/bundle META-INF
printf '%s\n' 'Examples built: artifacts/user-examples/classpath.txt and task-summary.jar'
