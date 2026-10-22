#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  printf 'usage: %s <release-directory>\n' "$0" >&2
  exit 2
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SOURCE=$1
TMP=$(mktemp -d "${TMPDIR:-/tmp}/raider-negative-install.XXXXXX")
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

mkdir "$TMP/missing-jdk"
mkdir "$TMP/installed"
cp "$ROOT/packaging/raider" "$TMP/installed/raider"
cp "$SOURCE/raider-cli.jar" "$TMP/installed/raider-cli.jar"
chmod +x "$TMP/installed/raider"
set +e
JAVA_HOME="$TMP/missing-jdk" RAIDER_USE_COURSIER=0 \
  "$TMP/installed/raider" --help >"$TMP/missing.out" 2>"$TMP/missing.err"
STATUS=$?
set -e
[ "$STATUS" -eq 21 ] && grep -F 'Java 21 or newer is required' "$TMP/missing.err" >/dev/null || {
  printf 'missing JDK was not rejected honestly: exit=%s\n' "$STATUS" >&2
  exit 20
}
printf '%s\n' '[negative-install-smoke] PASS: missing JAVA_HOME JDK exits 21 with a typed message'

mkdir -p "$TMP/incompatible-jdk/bin"
cat >"$TMP/incompatible-jdk/bin/java" <<'JAVA'
#!/bin/sh
printf '%s\n' 'openjdk version "17.0.12"' >&2
exit 0
JAVA
chmod +x "$TMP/incompatible-jdk/bin/java"
set +e
JAVA_HOME="$TMP/incompatible-jdk" RAIDER_USE_COURSIER=0 \
  "$TMP/installed/raider" --help >"$TMP/incompatible.out" 2>"$TMP/incompatible.err"
STATUS=$?
set -e
[ "$STATUS" -eq 21 ] && grep -F 'Java 21 or newer is required' "$TMP/incompatible.err" >/dev/null || {
  printf 'incompatible JDK was not rejected honestly: exit=%s\n' "$STATUS" >&2
  exit 20
}
printf '%s\n' '[negative-install-smoke] PASS: Java 17 JAVA_HOME exits 21 with a typed message'

for METADATA in release.json SHA256SUMS; do
  COPY="$TMP/$METADATA-release"
  cp -R "$SOURCE" "$COPY"
  printf '%s\n' 'corrupted metadata' >"$COPY/$METADATA"
  INSTALL_TMP="$TMP/install-$METADATA"
  mkdir "$INSTALL_TMP"
  set +e
  TMPDIR="$INSTALL_TMP" sh "$ROOT/scripts/release/install-smoke.sh" \
    "$COPY/raider-cli.jar" 0.1.0 "$COPY/packaging/raider" "$COPY" \
    >"$TMP/$METADATA.out" 2>"$TMP/$METADATA.err"
  STATUS=$?
  set -e
  [ "$STATUS" -eq 21 ] && [ -z "$(ls -A "$INSTALL_TMP")" ] || {
    printf 'corrupt %s was not refused before install: exit=%s\n' "$METADATA" "$STATUS" >&2
    exit 20
  }
  printf '[negative-install-smoke] PASS: corrupt %s refused (exit 21); no install staging\n' "$METADATA"
done
