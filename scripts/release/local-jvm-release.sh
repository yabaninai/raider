#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  printf 'usage: %s <semver>\n' "$0" >&2
  exit 2
fi
VERSION=$1
case "$VERSION" in
  ''|*[!0-9A-Za-z.+-]*) printf '%s\n' 'invalid SemVer' >&2; exit 2 ;;
esac
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"
[ -e .git ] || { printf '%s\n' 'local release requires a Git worktree' >&2; exit 2; }
[ -z "$(git status --porcelain)" ] || {
  printf '%s\n' 'local release requires a clean accepted revision; commit release inputs first' >&2
  exit 2
}
[ ! -e "artifacts/release/$VERSION" ] || {
  printf '%s\n' 'release version already exists' >&2
  exit 2
}
[ -z "$(git tag --list "v$VERSION")" ] || {
  printf '%s\n' 'local release tag already exists' >&2
  exit 2
}

make raider-jar
DRIVER="$ROOT/scripts/release/LocalReleaseDriver.scala"
GIT_DIR=$(git rev-parse --absolute-git-dir)
GIT_POINTER_BACKUP=
restore_git_pointer() {
  if [ -n "$GIT_POINTER_BACKUP" ] && [ -e "$GIT_POINTER_BACKUP" ]; then
    rm -f "$ROOT/.git/rai-193-release-request.json" \
      "$ROOT/.git/rai-193-release-decision.json"
    rm -f "$ROOT/.git"
    mv "$GIT_POINTER_BACKUP" "$ROOT/.git"
  fi
}
trap restore_git_pointer EXIT
if [ -f .git ] && [ ! -L .git ]; then
  GIT_POINTER_BACKUP="$GIT_DIR/rai-193-release-pointer.$$"
  [ ! -e "$GIT_POINTER_BACKUP" ] || {
    printf '%s\n' 'temporary Git pointer backup already exists' >&2
    exit 2
  }
  mv .git "$GIT_POINTER_BACKUP"
  ln -s "$GIT_DIR" .git
fi
sbt --batch \
  "set raiderCli / Compile / unmanagedSources += file(\"$DRIVER\")" \
  "raiderCli / runMain raider.cli.release.LocalReleaseDriver $ROOT $VERSION"

RELEASE="$ROOT/artifacts/release/$VERSION"
test -d "$RELEASE"
if command -v shasum >/dev/null 2>&1; then
  (cd "$RELEASE" && shasum -a 256 -c SHA256SUMS)
elif command -v sha256sum >/dev/null 2>&1; then
  (cd "$RELEASE" && sha256sum -c SHA256SUMS)
else
  printf '%s\n' 'SHA-256 verification utility is required' >&2
  exit 2
fi
if grep -F -e 'vVERSION' -e 'VERSION"' -e 'REPLACE_WITH_RELEASE_SHA256' \
  "$RELEASE/packaging/homebrew/raider.rb" "$RELEASE/packaging/scoop/raider.json"; then
  printf '%s\n' 'formula contains an unrendered release placeholder' >&2
  exit 2
fi
grep -F "releases/download/v$VERSION/raider-cli.jar" \
  "$RELEASE/packaging/homebrew/raider.rb" "$RELEASE/packaging/scoop/raider.json" >/dev/null
grep -F 'RAIDER_VER' "$RELEASE/packaging/scoop/raider.json" >/dev/null
if command -v shasum >/dev/null 2>&1; then
  JAR_SHA=$(shasum -a 256 "$RELEASE/raider-cli.jar" | awk '{print $1}')
else
  JAR_SHA=$(sha256sum "$RELEASE/raider-cli.jar" | awk '{print $1}')
fi
grep -F "$JAR_SHA" "$RELEASE/packaging/homebrew/raider.rb" "$RELEASE/packaging/scoop/raider.json" >/dev/null
sh "$ROOT/scripts/release/install-smoke.sh" "$RELEASE/raider-cli.jar" "$VERSION"
printf 'release artifacts (%s):\n' "$VERSION"
find "$RELEASE" -type f -print | LC_ALL=C sort
printf 'jar_sha256=%s\n' "$JAR_SHA"
