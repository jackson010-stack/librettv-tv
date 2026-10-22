#!/usr/bin/env bash
# Restore gradle/wrapper/gradle-wrapper.jar from its base64 copy.
# The repository is published through a text-only GitHub connector, so the binary
# wrapper jar is stored as .b64 and decoded here.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=gradle/wrapper/gradle-wrapper.jar.b64
DST=gradle/wrapper/gradle-wrapper.jar

if [ -s "$DST" ]; then
  echo "$DST already exists, nothing to do"
  exit 0
fi
[ -f "$SRC" ] || { echo "missing $SRC"; exit 1; }

# Read from stdin: macOS base64 wants -i for file input, GNU coreutils does not.
base64 -d < "$SRC" > "$DST"

echo "restored $DST"
