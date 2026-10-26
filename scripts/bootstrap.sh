#!/bin/sh
# Toolchain bootstrap: fetch pinned jars from Maven Central (idempotent).
# Python here is bootstrap glue only (language policy); the product is Scala.
set -u
cd "$(dirname "$0")/.."
mkdir -p artifacts/toolchain

# RAI-218: resolve a TLS CA bundle before the pinned-coordinate downloads.
# A stock macOS python.org distribution does not trust the system keychain and
# its bundled OpenSSL points at a CA file that does not exist, so HTTPS to
# repo1.maven.org fails with CERTIFICATE_VERIFY_FAILED. Resolution order:
#   1. certifi, when importable by the same interpreter that will download;
#   2. the macOS system bundle /etc/ssl/cert.pem, when readable;
#   3. the interpreter default paths, when already usable (no override set);
#   4. otherwise a typed failure with a remediation hint (no stack trace).
# A caller-provided SSL_CERT_FILE is honored untouched. The resolver prints
# the chosen path (or "none") on stdout and diagnostics on stderr.
if [ -z "${SSL_CERT_FILE:-}" ]; then
    CA_BUNDLE=$(python3 scripts/bootstrap_ca.py) || exit 1
    case "$CA_BUNDLE" in
        none) ;;
        *) export SSL_CERT_FILE="$CA_BUNDLE" ;;
    esac
fi

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
