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
  (.acceptance | type == "object") and
  (.distributions | type == "object") and
  (.distributions.headless | type == "string" and length > 0) and
  (.distributions.repl | type == "string" and length > 0) and
  (.distributions.replArchive | type == "string" and length > 0)
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
# RAI-279: the manifest must cover every regular file in the release except
# itself. Manifest entries are `sha256sum -c` lines (`<hex>  <path>`), so the
# path starts at column 67. A listed-but-missing or present-but-unlisted file
# is an integrity failure, not a warning. Only the top-level manifest is
# exempt; the nested REPL manifest is itself a covered payload file.
LISTED=$(LC_ALL=C awk '{ print substr($0, 67) }' "$RELEASE/SHA256SUMS" | LC_ALL=C sort)
ACTUAL=$(cd "$RELEASE" && find . -type f ! -path ./SHA256SUMS | LC_ALL=C sed 's|^\./||' | LC_ALL=C sort)
[ "$ACTUAL" = "$LISTED" ] || {
  printf '%s\n' 'checksum manifest does not cover every release file' >&2
  exit 21
}
printf '%s\n' '[release-verify] PASS: metadata and checksums are valid'
