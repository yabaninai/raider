#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  printf 'usage: %s <release-directory> <expected-version>\n' "$0" >&2
  exit 2
fi
RELEASE=$1
VERSION=$2
[ -d "$RELEASE" ] || { printf '%s\n' 'release metadata is missing' >&2; exit 21; }
[ -r "$RELEASE/release.json" ] && [ -r "$RELEASE/SHA256SUMS" ] || {
  printf '%s\n' 'release metadata or checksums are missing' >&2
  exit 21
}
command -v jq >/dev/null 2>&1 || { printf '%s\n' 'jq is required to validate release metadata' >&2; exit 21; }
jq -e --arg version "$VERSION" '
  type == "object" and
  .schemaVersion == 1 and
  .version == $version and
  (.revision | type == "string" and length > 0) and
  .tag == ("v" + $version) and
  .localOnly == true and
  (.acceptance | type == "object")
' "$RELEASE/release.json" >/dev/null 2>&1 || {
  printf '%s\n' 'release metadata is malformed or inconsistent' >&2
  exit 21
}
if command -v shasum >/dev/null 2>&1; then
  (cd "$RELEASE" && shasum -a 256 -c SHA256SUMS >/dev/null) || {
    printf '%s\n' 'release checksum verification failed' >&2
    exit 21
  }
elif command -v sha256sum >/dev/null 2>&1; then
  (cd "$RELEASE" && sha256sum -c SHA256SUMS >/dev/null) || {
    printf '%s\n' 'release checksum verification failed' >&2
    exit 21
  }
else
  printf '%s\n' 'SHA-256 verification utility is required' >&2
  exit 21
fi
printf '%s\n' '[release-verify] PASS: metadata and checksums are valid'
