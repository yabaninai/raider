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
if find "$ROOT/artifacts/release" -maxdepth 1 -type d -name '.release-*' 2>/dev/null | grep -q .; then
  printf '%s\n' 'stale release staging directory exists under artifacts/release; remove it first' >&2
  exit 2
fi

make raider-jar

# RAI-279: stage the RAI-262 REPL distribution under the release version so
# the release driver can copy it into the release tree alongside the headless
# package. The REPL payload files use lowercase `version` and carry no
# release placeholders, so the release stage's rendering is a no-op on them
# and the nested SHA256SUMS stays valid.
sbt --batch "set version := \"$VERSION\"" raiderReplDist
DIST="$ROOT/modules/repl/target/raider-repl-dist"
for REPL_NAME in raider-repl raider-repl.jar README.txt SHA256SUMS version; do
  [ -f "$DIST/raider-repl-$VERSION/$REPL_NAME" ] || {
    printf 'REPL distribution payload is missing: %s\n' \
      "$DIST/raider-repl-$VERSION/$REPL_NAME" >&2
    exit 2
  }
done
[ -f "$DIST/raider-repl-$VERSION.zip" ] || {
  printf '%s\n' 'REPL distribution archive is missing' >&2
  exit 2
}
[ "$(cat "$DIST/raider-repl-$VERSION/version")" = "$VERSION" ] || {
  printf '%s\n' 'REPL distribution version metadata does not match the release version' >&2
  exit 2
}
DRIVER="$ROOT/scripts/release/LocalReleaseDriver.scala"
GIT_DIR=$(git rev-parse --absolute-git-dir)
GIT_POINTER_BACKUP=
CHANGELOG_TMP=
restore_git_pointer() {
  if [ -n "$CHANGELOG_TMP" ]; then
    rm -f "$CHANGELOG_TMP"
  fi
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

# RAI-279: the release stage renders the dated changelog section from the
# driver notes but appends it at the end of the file and leaves the unreleased
# section in place, so fold the repository CHANGELOG.md here (the staged copy
# is folded by the driver's materialization hook before the atomic move).
# Sections are `## [<version>] - <date-or-unreleased>` blocks; the fold moves
# the dated section directly below the file intro and drops the unreleased
# section for the same version. Runs only when both sections exist.
CHANGELOG="$ROOT/CHANGELOG.md"
CHANGELOG_TMP="$ROOT/CHANGELOG.md.release-fold.$$"
awk -v ver="$VERSION" '
  { lines[NR] = $0 }
  function sectionend(s,   k) {
    for (k = 1; k <= hn; k++) if (heads[k] > s) return heads[k] - 1
    return n
  }
  END {
    n = NR
    first = 0
    for (i = 1; i <= n; i++)
      if (substr(lines[i], 1, 4) == "## [") { first = i; break }
    if (first == 0) {
      for (i = 1; i <= n; i++) print lines[i]
      exit
    }
    hn = 0
    for (i = first; i <= n; i++)
      if (substr(lines[i], 1, 4) == "## [") heads[++hn] = i
    pre = "## [" ver "] - "
    dstart = 0; ustart = 0
    for (k = 1; k <= hn; k++) {
      h = lines[heads[k]]
      if (substr(h, 1, length(pre)) != pre) continue
      rest = substr(h, length(pre) + 1)
      if (rest ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/) dstart = heads[k]
      else if (rest == "unreleased (cut pending)") ustart = heads[k]
    }
    if (dstart == 0 || ustart == 0) {
      for (i = 1; i <= n; i++) print lines[i]
      exit
    }
    dend = sectionend(dstart)
    for (i = 1; i < first; i++) print lines[i]
    for (i = dstart; i <= dend; i++) print lines[i]
    if (lines[dend] != "") print ""
    for (k = 1; k <= hn; k++) {
      s = heads[k]
      e = (k < hn) ? heads[k + 1] - 1 : n
      if (s == dstart || s == ustart) continue
      for (i = s; i <= e; i++) print lines[i]
    }
  }
' "$CHANGELOG" > "$CHANGELOG_TMP"
mv "$CHANGELOG_TMP" "$CHANGELOG"
[ "$(grep -c "^## \\[$VERSION\\]" "$CHANGELOG")" = 1 ] || {
  printf '%s\n' 'changelog fold left duplicate sections for the release version' >&2
  exit 2
}
grep -q "^## \\[$VERSION\\] - 20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]$" "$CHANGELOG" || {
  printf '%s\n' 'changelog lacks the dated section for the release version' >&2
  exit 2
}
if grep -q "^## \\[$VERSION\\] - unreleased" "$CHANGELOG"; then
  printf '%s\n' 'changelog still contains the unreleased section after the fold' >&2
  exit 2
fi

RELEASE="$ROOT/artifacts/release/$VERSION"
test -d "$RELEASE"
verify_checksums() {
  (
    cd "$1" || exit 2
    if command -v shasum >/dev/null 2>&1; then
      shasum -a 256 -c SHA256SUMS
    elif command -v sha256sum >/dev/null 2>&1; then
      sha256sum -c SHA256SUMS
    else
      printf '%s\n' 'SHA-256 verification utility is required' >&2
      exit 2
    fi
  )
}
# RAI-279: the top-level manifest must now cover every staged file, including
# the nested REPL distribution; the nested manifest must also verify.
verify_checksums "$RELEASE"
REPL="$RELEASE/repl/raider-repl-$VERSION"
test -d "$REPL" || {
  printf '%s\n' 'staged REPL directory is missing from the release' >&2
  exit 2
}
test -f "$RELEASE/repl/raider-repl-$VERSION.zip" || {
  printf '%s\n' 'staged REPL archive is missing from the release' >&2
  exit 2
}
[ "$(cat "$REPL/version")" = "$VERSION" ] || {
  printf '%s\n' 'staged REPL version metadata does not match the release version' >&2
  exit 2
}
verify_checksums "$REPL"
if grep -F 'VERSION' \
  "$RELEASE/packaging/raider" \
  "$RELEASE/packaging/homebrew/raider.rb" \
  "$RELEASE/packaging/scoop/raider.json"; then
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
sh "$ROOT/scripts/release/install-smoke.sh" "$RELEASE/raider-cli.jar" "$VERSION" \
  "$RELEASE/packaging/raider" "$RELEASE"
printf 'release artifacts (%s):\n' "$VERSION"
find "$RELEASE" -type f -print | LC_ALL=C sort
printf 'jar_sha256=%s\n' "$JAR_SHA"
