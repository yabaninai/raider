#!/bin/sh
# Pinned Scala 3.9.0 compile of product modules + contract fixtures (RAI-003 stage A).
# Product code is Scala-only; this script is bootstrap glue until the sbt build
# (RAI-003 stage B) is provisioned. Honest exit codes; fresh output dirs.
set -u
cd "$(dirname "$0")/../.."

M2=artifacts/tools/m2
LIB="$M2/org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
ZIO="$M2/dev/zio/zio_3/2.1.26/zio_3-2.1.26.jar"
STACK="$M2/dev/zio/zio-stacktracer_3/2.1.26/zio-stacktracer_3-2.1.26.jar"
IMACROS="$M2/dev/zio/zio-internal-macros_3/2.1.26/zio-internal-macros_3-2.1.26.jar"
IZUMI="$M2/dev/zio/izumi-reflect_3/3.0.9/izumi-reflect_3-3.0.9.jar"
BOOP="$M2/dev/zio/izumi-reflect-thirdparty-boopickle-shaded_3/3.0.9/izumi-reflect-thirdparty-boopickle-shaded_3-3.0.9.jar"
COMPAT="$M2/org/scala-lang/modules/scala-collection-compat_3/2.14.0/scala-collection-compat_3-2.14.0.jar"
STREAMS="$M2/dev/zio/zio-streams_3/2.1.26/zio-streams_3-2.1.26.jar"
JSON="$M2/dev/zio/zio-json_3/0.10.0/zio-json_3-0.10.0.jar"
MAGNOLIA="$M2/com/softwaremill/magnolia1_3/magnolia_3/1.3.23/magnolia_3-1.3.23.jar"
COMPILER="$M2/org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar"
INTERFACES="$M2/org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar"
TASTY="$M2/org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar"
ASM="$M2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar"
CIFACE="$M2/org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar"
UIFACE="$M2/org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"
# repl-engine / repl modules (fast-REPL slice): in-process scala3-repl + JLine
REPLDRIVER="$M2/org/scala-lang/scala3-repl_3/3.9.0/scala3-repl_3-3.9.0.jar"
JLINE_T="$M2/org/jline/jline-terminal/4.0.14/jline-terminal-4.0.14.jar"
JLINE_R="$M2/org/jline/jline-reader/4.0.14/jline-reader-4.0.14.jar"
JLINE_JNI="$M2/org/jline/jline-terminal-jni/4.0.14/jline-terminal-jni-4.0.14.jar"

need() { for f in "$@"; do [ -f "$f" ] || { echo "missing $f — run scripts/bootstrap.sh" >&2; exit 21; }; done; }
need "$LIB" "$ZIO" "$STACK" "$IMACROS" "$IZUMI" "$BOOP" "$COMPAT" "$STREAMS" "$JSON" "$MAGNOLIA" "$COMPILER" "$INTERFACES" "$TASTY" "$ASM" "$CIFACE" "$UIFACE" "$REPLDRIVER" "$JLINE_T" "$JLINE_R" "$JLINE_JNI"

RUNTIME_CP="$LIB:$ZIO:$STACK:$IMACROS:$IZUMI:$BOOP:$COMPAT:$STREAMS:$JSON:$MAGNOLIA:$REPLDRIVER:$JLINE_T:$JLINE_R:$JLINE_JNI:$COMPILER:$INTERFACES:$TASTY:$ASM"
COMPILER_CP="$COMPILER:$INTERFACES:$TASTY:$LIB:$ASM:$CIFACE:$UIFACE"
OUT=artifacts/build/classes
rm -rf "$OUT"; mkdir -p "$OUT"

SOURCES=$(find modules -path '*/src/main/*' -name '*.scala' -type f | sort)
[ -n "$SOURCES" ] || { echo "no scala sources under modules/" >&2; exit 1; }

echo "== compiling product modules (pinned dotc 3.9.0) =="
# shellcheck disable=SC2086
java -cp "$COMPILER_CP" dotty.tools.dotc.Main -classpath "$RUNTIME_CP" -d "$OUT" $SOURCES || exit $?

POS_OUT=artifacts/build/classes-contracts-pos
rm -rf "$POS_OUT"; mkdir -p "$POS_OUT"
echo "== compiling positive contract fixtures =="
java -cp "$COMPILER_CP" dotty.tools.dotc.Main -classpath "$RUNTIME_CP:$OUT" -d "$POS_OUT" \
  contracts/compile/positive/*.scala || exit $?

echo "== negative fixture must NOT compile =="
NEG_OUT=artifacts/build/classes-contracts-neg
rm -rf "$NEG_OUT"; mkdir -p "$NEG_OUT"
if java -cp "$COMPILER_CP" dotty.tools.dotc.Main -classpath "$RUNTIME_CP:$OUT" -d "$NEG_OUT" \
     contracts/compile/negative/*.scala > artifacts/build/negative.log 2>&1; then
  echo "NEGATIVE FIXTURE COMPILED — contract violated" >&2
  exit 22
fi
if ! grep -q "Type Mismatch" artifacts/build/negative.log || ! grep -q "Boolean" artifacts/build/negative.log; then
  echo "negative fixture failed for an UNEXPECTED reason (not the intended type mismatch):" >&2
  head -5 artifacts/build/negative.log >&2
  exit 23
fi
echo "negative fixture correctly rejected (intended type mismatch):"
grep -m1 "Type Mismatch" artifacts/build/negative.log

echo "BUILD OK: modules=$(echo "$SOURCES" | wc -l | tr -d ' ') scala files"
