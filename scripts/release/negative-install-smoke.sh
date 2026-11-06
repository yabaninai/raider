#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  printf 'usage: %s <release-directory>\n' "$0" >&2
  exit 2
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SOURCE=$1
command -v jq >/dev/null 2>&1 || {
  printf '%s\n' 'jq is required to read release metadata' >&2
  exit 2
}
VERSION=$(jq -r '.version // ""' "$SOURCE/release.json")
case "$VERSION" in
  ''|*[!0-9A-Za-z.+-]*) printf '%s\n' 'release metadata has no usable version' >&2; exit 2 ;;
esac
REPL="$SOURCE/repl/raider-repl-$VERSION"
[ -d "$REPL" ] && [ -f "$SOURCE/repl/raider-repl-$VERSION.zip" ] || {
  printf '%s\n' 'release directory lacks the REPL distribution; rerun the release first' >&2
  exit 2
}
TMP=$(mktemp -d "${TMPDIR:-/tmp}/raider-negative-install.XXXXXX")
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# $1 = case label, $2 = corrupt release copy, $3 = install staging directory.
# install-smoke must refuse with the typed config exit (21) and must not
# leave any staging content behind.
refusal_case() {
  set +e
  TMPDIR="$3" sh "$ROOT/scripts/release/install-smoke.sh" \
    "$2/raider-cli.jar" "$VERSION" "$2/packaging/raider" "$2" \
    >"$TMP/$1.out" 2>"$TMP/$1.err"
  STATUS=$?
  set -e
  [ "$STATUS" -eq 21 ] && [ -z "$(ls -A "$3")" ] || {
    printf '%s was not refused before install: exit=%s\n' "$1" "$STATUS" >&2
    exit 20
  }
  printf '[negative-install-smoke] PASS: %s refused (exit 21); no install staging\n' "$1"
}

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
  refusal_case "$METADATA" "$COPY" "$INSTALL_TMP"
done

# RAI-279: REPL payload refusal cases. Each corrupts one artifact of the
# staged REPL distribution in a copy of the release; the headless jar and
# launcher stay intact so a refusal can only come from the REPL tree.
COPY="$TMP/missing-repl-jar-release"
cp -R "$SOURCE" "$COPY"
rm "$COPY/repl/raider-repl-$VERSION/raider-repl.jar"
INSTALL_TMP="$TMP/install-missing-repl-jar"
mkdir "$INSTALL_TMP"
refusal_case missing-repl-jar "$COPY" "$INSTALL_TMP"

COPY="$TMP/truncated-repl-jar-release"
cp -R "$SOURCE" "$COPY"
head -c 100 "$COPY/repl/raider-repl-$VERSION/raider-repl.jar" \
  >"$COPY/repl/raider-repl-$VERSION/raider-repl.jar.truncated"
mv "$COPY/repl/raider-repl-$VERSION/raider-repl.jar.truncated" \
  "$COPY/repl/raider-repl-$VERSION/raider-repl.jar"
INSTALL_TMP="$TMP/install-truncated-repl-jar"
mkdir "$INSTALL_TMP"
refusal_case truncated-repl-jar "$COPY" "$INSTALL_TMP"

COPY="$TMP/tampered-repl-launcher-release"
cp -R "$SOURCE" "$COPY"
printf '%s\n' 'tampered after the fact' >>"$COPY/repl/raider-repl-$VERSION/raider-repl"
INSTALL_TMP="$TMP/install-tampered-repl-launcher"
mkdir "$INSTALL_TMP"
refusal_case tampered-repl-launcher "$COPY" "$INSTALL_TMP"

COPY="$TMP/missing-repl-zip-release"
cp -R "$SOURCE" "$COPY"
rm "$COPY/repl/raider-repl-$VERSION.zip"
INSTALL_TMP="$TMP/install-missing-repl-zip"
mkdir "$INSTALL_TMP"
refusal_case missing-repl-zip "$COPY" "$INSTALL_TMP"
