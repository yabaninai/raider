#!/bin/sh
# In-lane test runner for modules/compat-opencode (RAI-033).
# The module is not registered in the coordinator-owned sbt build; until the
# coordinator registers it, this script compiles the module main+test sources
# with the same pinned dotc 3.9.0 used by scripts/build/compile.sh and runs the
# zio-test suite via TestMain. Honest exit codes.
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
ZTEST="$M2/dev/zio/zio-test_3/2.1.26/zio-test_3-2.1.26.jar"
COMPILER="$M2/org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar"
INTERFACES="$M2/org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar"
TASTY="$M2/org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar"
ASM="$M2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar"
CIFACE="$M2/org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar"
UIFACE="$M2/org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"

need() { for f in "$@"; do [ -f "$f" ] || { echo "missing $f — run scripts/bootstrap.sh" >&2; exit 21; }; done; }
need "$LIB" "$ZIO" "$STACK" "$IMACROS" "$IZUMI" "$BOOP" "$COMPAT" "$STREAMS" "$JSON" "$MAGNOLIA" "$ZTEST" "$COMPILER" "$INTERFACES" "$TASTY" "$ASM" "$CIFACE" "$UIFACE"

DEPS="$LIB:$ZIO:$STACK:$IMACROS:$IZUMI:$BOOP:$COMPAT:$STREAMS:$JSON:$MAGNOLIA:$ZTEST"
COMPILER_CP="$COMPILER:$INTERFACES:$TASTY:$LIB:$ASM:$CIFACE:$UIFACE"
OUT=artifacts/build/classes-compat-opencode
rm -rf "$OUT"; mkdir -p "$OUT"

MAIN=$(find modules/compat-opencode/src/main -name '*.scala' -type f 2>/dev/null | sort)
TEST=$(find modules/compat-opencode/src/test -name '*.scala' -type f 2>/dev/null | sort)
[ -n "$TEST" ] || { echo "no test sources under modules/compat-opencode/src/test" >&2; exit 1; }

echo "== compiling compat-opencode main sources (pinned dotc 3.9.0) =="
MAIN_EXIT=0
if [ -n "$MAIN" ]; then
  # shellcheck disable=SC2086
  java -Xss4m -cp "$COMPILER_CP" dotty.tools.dotc.Main -classpath "$DEPS" -d "$OUT" $MAIN || MAIN_EXIT=$?
  [ "$MAIN_EXIT" -eq 0 ] || exit "$MAIN_EXIT"
else
  echo "no main sources yet (tests compile against nothing — RED phase)"
fi

echo "== compiling compat-opencode test sources =="
# shellcheck disable=SC2086
java -Xss4m -cp "$COMPILER_CP" dotty.tools.dotc.Main -classpath "$DEPS:$OUT" -d "$OUT" $TEST || exit $?

echo "== running TestMain (RAIDER_REPO_ROOT=$(pwd)) =="
RAIDER_REPO_ROOT="$(pwd)"
export RAIDER_REPO_ROOT
exec java -cp "$DEPS:$OUT" raider.compat.opencode.TestMain
