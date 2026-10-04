#!/bin/sh
# Toolchain bootstrap: fetch pinned jars from Maven Central (idempotent).
# Python here is bootstrap glue only (language policy); the product is Scala.
set -u
cd "$(dirname "$0")/.."
mkdir -p artifacts/toolchain
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
