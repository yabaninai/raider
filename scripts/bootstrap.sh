#!/bin/sh
# Toolchain + pinned m2 consumer-closure bootstrap for fresh checkouts.
# Idempotent. Python here is bootstrap glue only (language policy); the
# product is Scala.
#
# Commands:
#   (none) | all  provision the pinned REPL toolchain (RAI-001/RAI-218) AND
#                 the pinned m2 consumer closure (RAI-239), then verify both
#   toolchain     provision only the pinned REPL toolchain
#   m2 [options]  provision only the pinned consumer closure under
#                 artifacts/tools/m2; see scripts/README.md for options
#
# m2 guarantee (RAI-239): an artifact is installed only after its sha256 and
# size match contracts/acceptance/cross-ecosystem/consumer-deps.json, and the
# whole pinned closure is re-verified from disk before exit. Any corrupted,
# missing, or unverifiable artifact is named on stderr and the exit code is
# nonzero. Provisioning is concurrency-safe (store-level lock + atomic
# per-artifact rename) and never deletes unrelated files from the store.
set -u
cd "$(dirname "$0")/.." || exit 1

usage() {
    cat <<'USAGE'
Usage: sh scripts/bootstrap.sh [all|toolchain|m2] [m2 options]

Commands:
  all          (default) provision the pinned REPL toolchain (artifacts/toolchain
               plus toolchain jars under artifacts/tools/m2) and the pinned m2
               consumer closure (31 artifacts pinned in
               contracts/acceptance/cross-ecosystem/consumer-deps.json).
  toolchain    provision only the pinned REPL toolchain (RAI-001/RAI-218 path).
  m2           provision only the pinned m2 consumer closure. Options:
                 --source DIR    copy artifacts from a populated m2 cache root
                                 (a directory containing com/, org/, ...; for
                                 example an existing checkout's
                                 artifacts/tools/m2). Offline.
                 --download      download from Maven Central (default when
                                 --source is absent); needs network.
                 --manifest FILE pin manifest to verify against
                                 (default contracts/acceptance/cross-ecosystem/consumer-deps.json).
                 --target DIR    m2 root to provision (default artifacts/tools/m2).

Every artifact is verified (sha256 + size) against the manifest before
installation and the whole closure is re-verified before exit 0. A corrupted
or missing artifact is named on stderr and the exit code is nonzero.
USAGE
}

# RAI-218: resolve a TLS CA bundle before any pinned-coordinate downloads.
# A stock macOS python.org distribution does not trust the system keychain and
# its bundled OpenSSL points at a CA file that does not exist, so HTTPS to
# repo1.maven.org fails with CERTIFICATE_VERIFY_FAILED. Resolution order:
#   1. certifi, when importable by the same interpreter that will download;
#   2. the macOS system bundle /etc/ssl/cert.pem, when readable;
#   3. the interpreter default paths, when already usable (no override set);
#   4. otherwise a typed failure with a remediation hint (no stack trace).
# A caller-provided SSL_CERT_FILE is honored untouched. The resolver prints
# the chosen path (or "none") on stdout and diagnostics on stderr.
resolve_ca() {
    if [ -z "${SSL_CERT_FILE:-}" ]; then
        CA_BUNDLE=$(python3 scripts/bootstrap_ca.py) || exit 1
        case "$CA_BUNDLE" in
            none) ;;
            *) export SSL_CERT_FILE="$CA_BUNDLE" ;;
        esac
    fi
}

do_toolchain() {
    RAIDER_TOOLCHAIN_INVENTORY=artifacts/toolchain/toolchain.json \
    python3 experiments/repl-spike/fetch_toolchain.py \
      org.scala-lang:scala3-compiler_3:3.9.0 \
      org.scala-lang:scala3-repl_3:3.9.0 \
      org.scala-lang:scala3-library_3:3.9.0 \
      dev.zio:zio_3:2.1.26 \
      dev.zio:zio-json_3:0.10.0 \
      org.jline:jline-terminal:4.0.14 \
      org.jline:jline-reader:4.0.14 \
      org.jline:jline-terminal-jni:4.0.14 \
      io.get-coursier:interface:1.0.29-M4
}

do_m2() {
    python3 - "$@" <<'RAIDER_M2_PY'
import argparse
import fcntl
import hashlib
import json
import os
import pathlib
import sys
import urllib.request

DEFAULT_MANIFEST = "contracts/acceptance/cross-ecosystem/consumer-deps.json"
DEFAULT_TARGET = "artifacts/tools/m2"
M2_PREFIX = "artifacts/tools/m2/"
CENTRAL = "https://repo1.maven.org/maven2"
USER_AGENT = "raider-bootstrap/m2-provisioning (RAI-239)"
CHUNK = 1 << 20


def log(msg):
    print(f"[m2-bootstrap] {msg}", flush=True)


def err(msg):
    print(f"[m2-bootstrap] {msg}", file=sys.stderr, flush=True)


def die(msg):
    err(f"ERROR: {msg}")
    sys.exit(2)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def central_url(entry):
    group = entry.get("group", "")
    artifact = entry.get("artifact", "")
    version = entry.get("version", "")
    if not (group and artifact and version):
        raise ValueError(
            f"download URL not derivable from manifest entry "
            f"(group={group!r} artifact={artifact!r} version={version!r}); use --source"
        )
    if version.endswith("-SNAPSHOT") or "@" in version or "/" in version:
        raise ValueError(f"release URL not derivable for version {version!r}; use --source")
    return f"{CENTRAL}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"


def rel_under_m2(entry):
    path = entry.get("path", "")
    if path.startswith(M2_PREFIX):
        path = path[len(M2_PREFIX):]
    return path


def contained_in(root, path):
    real_root = os.path.realpath(root)
    real_path = os.path.realpath(path)
    return real_path == real_root or real_path.startswith(real_root + os.sep)


def fetch_to(url, tmp):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        if getattr(response, "status", 200) != 200:
            raise RuntimeError(f"HTTP {response.status} for {url}")
        with open(tmp, "wb") as out:
            while True:
                chunk = response.read(CHUNK)
                if not chunk:
                    break
                out.write(chunk)


def copy_to(src, tmp):
    with open(src, "rb") as fin, open(tmp, "wb") as fout:
        while True:
            chunk = fin.read(CHUNK)
            if not chunk:
                break
            fout.write(chunk)


def install_atomically(tmp, final):
    final.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(tmp, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(tmp, 0o644)
    os.replace(tmp, final)


def main():
    parser = argparse.ArgumentParser(
        prog="scripts/bootstrap.sh m2",
        description="Provision the pinned m2 consumer closure with sha256 verification.",
    )
    parser.add_argument("--source", metavar="DIR",
                        help="copy artifacts from a populated m2 cache root (offline)")
    parser.add_argument("--download", action="store_true",
                        help="download from Maven Central (default without --source)")
    parser.add_argument("--manifest", metavar="FILE", default=DEFAULT_MANIFEST)
    parser.add_argument("--target", metavar="DIR", default=DEFAULT_TARGET)
    args = parser.parse_args()
    if args.source and args.download:
        die("--source and --download are mutually exclusive")

    manifest_path = pathlib.Path(args.manifest)
    if not manifest_path.is_file():
        die(f"manifest not found: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as ex:
        die(f"manifest unreadable: {manifest_path}: {ex}")
    if not isinstance(manifest, dict) or not manifest:
        die(f"manifest has no entries: {manifest_path}")
    entries = sorted(manifest.items(), key=lambda kv: kv[1].get("path", kv[0]))
    for key, entry in entries:
        if not entry.get("path") or not entry.get("sha256"):
            die(f"manifest entry missing path/sha256: {key}")

    target = pathlib.Path(args.target)
    target.mkdir(parents=True, exist_ok=True)
    lock_path = target.parent / f".{target.name}-bootstrap.lock"

    mode = "copy" if args.source else "download"
    source_root = pathlib.Path(args.source) if args.source else None
    failures = []
    installed = 0

    with open(lock_path, "a+") as lock:
        # Serialize concurrent provisioning of the same store; the lock is
        # released automatically on process exit. Individual artifacts are
        # additionally installed via atomic rename, so a crashed run can
        # never leave a half-written jar in place of a pinned artifact.
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        log(f"provisioning {len(entries)} pinned artifacts "
            f"({'copy from ' + str(source_root) if mode == 'copy' else 'download from ' + CENTRAL})")
        log(f"target store: {target}")

        for key, entry in entries:
            rel = rel_under_m2(entry)
            final = target / rel
            if not rel or os.path.isabs(rel) or os.path.normpath(rel) != rel \
                    or not contained_in(target, final):
                failures.append(f"{rel or key}: unsafe manifest path {entry.get('path')!r}")
                err(f"FAIL {failures[-1]}")
                continue
            want_sha = entry["sha256"]
            want_size = entry.get("size")

            if final.is_file():
                got = sha256_of(final)
                if got == want_sha and (want_size is None or final.stat().st_size == want_size):
                    log(f"OK {got} {rel} (already verified)")
                    continue

            tmp = final.parent / f".{final.name}.tmp-{os.getpid()}"
            try:
                final.parent.mkdir(parents=True, exist_ok=True)
                if mode == "copy":
                    src = source_root / rel
                    if not src.is_file():
                        raise FileNotFoundError(f"missing in source cache: {src}")
                    copy_to(src, tmp)
                    origin = str(src)
                else:
                    url = central_url(entry)
                    fetch_to(url, tmp)
                    origin = url
                got = sha256_of(tmp)
                got_size = tmp.stat().st_size
                if got != want_sha or (want_size is not None and got_size != want_size):
                    raise ValueError(
                        f"sha256/size mismatch: expected {want_sha}/{want_size}, "
                        f"got {got}/{got_size} (from {origin})"
                    )
                install_atomically(tmp, final)
                installed += 1
                log(f"OK {got} {rel} ({'copied' if mode == 'copy' else 'downloaded'})")
            except KeyboardInterrupt:
                err("FAIL interrupted by user; partially fetched data discarded")
                raise
            except Exception as ex:
                failures.append(f"{rel}: {ex}")
                err(f"FAIL {failures[-1]}")
            finally:
                try:
                    if tmp.exists():
                        tmp.unlink()
                except OSError:
                    pass

        # Final pass: re-verify the ENTIRE pinned closure from disk, including
        # artifacts that were already present before this run.
        log(f"verifying {len(entries)}/{len(entries)} artifacts (sha256+size) against {args.manifest}")
        bad = []
        for key, entry in entries:
            rel = rel_under_m2(entry)
            final = target / rel
            if not final.is_file():
                bad.append(rel)
                err(f"FAIL {rel}: missing from target store")
                continue
            got = sha256_of(final)
            if got != entry["sha256"] or (
                entry.get("size") is not None and final.stat().st_size != entry["size"]
            ):
                bad.append(rel)
                err(f"FAIL {rel}: expected sha256 {entry['sha256']}, got {got}")
            else:
                log(f"VERIFIED {got} {rel}")

        if failures or bad:
            err(f"FAILED {len(failures) + len(bad)} problem(s) across {len(entries)} "
                f"pinned artifacts ({installed} installed this run); store NOT verified")
            sys.exit(1)
        log(f"verified {len(entries)}/{len(entries)} artifacts (sha256+size); store at {target}")


try:
    main()
except KeyboardInterrupt:
    err("ERROR: interrupted")
    sys.exit(130)
RAIDER_M2_PY
}

CMD="${1:-all}"
if [ "$#" -gt 0 ]; then
    shift
fi

case "$CMD" in
    all)
        resolve_ca
        do_toolchain || exit $?
        do_m2
        ;;
    toolchain)
        resolve_ca
        do_toolchain
        ;;
    m2)
        case " $* " in
            *" --source "*)
                # Offline copy mode: no downloads, no CA resolution needed.
                do_m2 "$@"
                ;;
            *)
                resolve_ca
                do_m2 "$@"
                ;;
        esac
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        usage >&2
        exit 64
        ;;
esac
