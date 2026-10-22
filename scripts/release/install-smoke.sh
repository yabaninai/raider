#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 4 ]; then
  printf 'usage: %s <distribution-jar> <semver> [distribution-launcher] [release-directory]\n' "$0" >&2
  exit 2
fi
JAR=$1
VERSION=$2
[ -r "$JAR" ] || { printf 'jar is not readable: %s\n' "$JAR" >&2; exit 21; }
case "$VERSION" in
  ''|*[!0-9A-Za-z.+-]*) printf '%s\n' 'invalid version metadata' >&2; exit 21 ;;
esac
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
LAUNCHER=${3:-"$ROOT/packaging/raider"}
[ -r "$LAUNCHER" ] || { printf 'launcher is not readable: %s\n' "$LAUNCHER" >&2; exit 21; }
if [ "$#" -eq 4 ]; then
  sh "$ROOT/scripts/release/verify-local-release.sh" "$4" "$VERSION"
  [ "$(CDPATH= cd -- "$4" && pwd)" = "$(CDPATH= cd -- "$(dirname -- "$JAR")" && pwd)" ] || {
    printf '%s\n' 'distribution jar must come from the verified release directory' >&2
    exit 21
  }
fi
TMP=$(mktemp -d "${TMPDIR:-/tmp}/raider-install.XXXXXX")
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT
trap 'cleanup; exit 129' HUP
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM
INSTALL="$TMP/prefix/raider"
mkdir -p "$INSTALL"
cp "$JAR" "$INSTALL/raider-cli.jar"
cp "$LAUNCHER" "$INSTALL/raider"
printf '%s\n' "$VERSION" > "$INSTALL/version"
chmod +x "$INSTALL/raider"

if command -v shasum >/dev/null 2>&1; then
  HASH=$(shasum -a 256 "$INSTALL/raider-cli.jar" | awk '{print $1}')
elif command -v sha256sum >/dev/null 2>&1; then
  HASH=$(sha256sum "$INSTALL/raider-cli.jar" | awk '{print $1}')
else
  printf '%s\n' 'SHA-256 tool is required' >&2
  exit 21
fi
printf '%s  raider-cli.jar\n' "$HASH" > "$INSTALL/SHA256SUMS"
if command -v shasum >/dev/null 2>&1; then
  (cd "$INSTALL" && shasum -a 256 -c SHA256SUMS >/dev/null)
else
  (cd "$INSTALL" && sha256sum -c SHA256SUMS >/dev/null)
fi
cp "$INSTALL/raider-cli.jar" "$TMP/good.jar"
printf 'corruption' >> "$INSTALL/raider-cli.jar"
if command -v shasum >/dev/null 2>&1; then
  if (cd "$INSTALL" && shasum -a 256 -c SHA256SUMS >/dev/null 2>&1); then
    printf '%s\n' 'corrupt distribution checksum was accepted' >&2
    exit 20
  fi
else
  if (cd "$INSTALL" && sha256sum -c SHA256SUMS >/dev/null 2>&1); then
    printf '%s\n' 'corrupt distribution checksum was accepted' >&2
    exit 20
  fi
fi
mv "$TMP/good.jar" "$INSTALL/raider-cli.jar"

VERSION_OUT=$("$INSTALL/raider" --version)
[ "$VERSION_OUT" = "raider $VERSION" ] || { printf 'unexpected version output: %s\n' "$VERSION_OUT" >&2; exit 20; }
# Verify the installed launcher resolves the copied jar. An intentionally
# invalid command must reach the CLI and return its documented config exit (21).
set +e
"$INSTALL/raider" --not-a-command >"$TMP/stdout" 2>"$TMP/stderr"
STATUS=$?
set -e
[ "$STATUS" -eq 21 ] || { printf 'CLI exit mismatch: expected 21, got %s\n' "$STATUS" >&2; exit 20; }
printf '[install-smoke] PASS: fresh prefix version and jar resolution via %s; malformed CLI exits 21\n' \
  "$(if command -v cs >/dev/null 2>&1 && [ "${RAIDER_USE_COURSIER:-1}" = 1 ]; then printf coursier; else printf java; fi)"
